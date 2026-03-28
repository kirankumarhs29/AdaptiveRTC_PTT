# ECS Engine Integration — Architecture & Implementation Guide

> **Audience**: Principal engineers working on the NetSense AI KMP walkie-talkie app.
> **Scope**: Integrating the AdaptiveRTC C++ ECS (Early Congestion Signal) engine into
> the existing RTP audio-sending pipeline via JNI.

---

## 1. Integration Architecture

### Approach Comparison

| Approach | Latency | Memory copies | Build complexity | Maintainability | Verdict |
|---|---|---|---|---|---|
| **JNI (shared lib)** | ~1 µs/call | Zero (JVM ↔ native, no buffer copy) | Medium — one extra `.so` | High — typed Kotlin API | ✅ **Chosen** |
| Unix-domain socket | ~50–200 µs/frame | 1 copy (serialize to bytes, send, receive) | Low | Medium | ❌ Too slow for 20 ms frames |
| Shared memory (mmap) | < 1 µs | Zero | High — IPC, pointer discipline, locks | Low — complex lifecycle | ❌ Over-engineered |
| Compile ECS into app directly (no JNI) | N/A | N/A | High — Kotlin must call C++ transcompiled | Very low | ❌ Not viable in KMP |

**Why JNI wins here:**

1. The project already ships `netsense_mesh.so` via JNI — the pattern is established.
2. ECS calls are on `Dispatchers.IO` (async), never blocking the UI or audio playout thread.
3. The ECS engine is pure CPU with no I/O — zero risk of native blocking.
4. JNI overhead per call is < 1 µs with no heap allocation. Audio frames are 20 ms,
   so JNI cost is < 0.005 % of the frame budget.
5. The ECS library remains unchanged. No modification to any AdaptiveRTC source.

### New Shared Library: `adaptive_rtc_jni.so`

```
AdaptiveRTC/
  src/
    rtt_tracker.cpp       ─┐
    ecs_detector.cpp       │ compiled into
    rate_controller.cpp    │ adaptive_rtc_jni.so
    packet.cpp             │ (in addition to existing
    jitter_buffer.cpp      │  netsense_mesh.so)
    metrics_logger.cpp    ─┘
NetSense AI/
  src/cpp/
    ecs_bridge.cpp         ← JNI glue (new)
  CMakeLists.txt           ← updated to add the new target
  shared/androidMain/.../
    EcsBridge.kt           ← Kotlin singleton facade (new)
```

---

## 2. Data Flow Design

### End-to-End Pipeline with ECS

```
──────────────────────── SENDER SIDE ──────────────────────────────────────
                                                  [Dispatchers.IO coroutine]
  Microphone
      │ AudioRecord.read(frame[640], BLOCKING)   ← 20 ms cadence
      ▼
  applySoftwareGain(×3.0f)
      │
      ▼
  ┌─────────────────────────────────────────┐
  │  EcsBridge.canSendPacket(elapsedUs)     │  ← token-bucket gate
  │                                         │    < 1 µs, no allocation
  │  if false → delay(20 ms), continue      │  ← frame drop = rate pacing
  └─────────────────────────────────────────┘
      │ [allowed]
      ▼
  RtpPacket(v=2, PT=11, seq++, ts+=320, ssrc)
  .toByteArray()  →  652 bytes
      │
      ▼
  DatagramSocket.send()  ──→  WiFi Direct P2P  ──→  Remote peer
                                                          │
  ←─────────── SignalingManager PONG  ←─────────────────┤
  (every 2 s, echoes our PING timestampMs)              │
      │                                                   │
      ▼                                                   ▼
  rttUs = (now - pong.timestampMs) × 1000             UDP :5004
  EcsBridge.addRttSample(rttUs)                          │
      │                                              RtpPacket.parse()
      ▼                                                   │
  [every 20 packets = 400 ms]                       audioManager.writePlayback()
  EcsBridge.analyzeCongestion()                          │
      │                                              AudioTrack (speaker)
      ├─ STATUS_NO_CONGESTION  → onRecovery()  ─────── RECEIVER SIDE ──────
      ├─ STATUS_BUILDING       → onCongestionSignal(BUILDING) → rate × 0.90
      └─ STATUS_IMMINENT       → onCongestionSignal(IMMINENT) → rate × 0.75
```

### Where ECS Sits — Precisely

```
RtpManager.sendLoop()  [Dispatchers.IO]
    │
    ├── 1. AudioRecord.read()                  ← blocking, ends at frame boundary
    ├── 2. EcsBridge.canSendPacket(elapsedUs)  ← ECS gate (< 1 µs)
    │        └─ if false: delay + continue     ← paced drop
    ├── 3. Build RtpPacket
    ├── 4. DatagramSocket.send()               ← UDP transmit
    └── 5. Every 20 pkts: analyzeCongestion()  ← ECS analysis + rate update
```

ECS is **not** on a separate thread. It runs inline in the existing `sendLoop`
because all ECS methods are O(1) and mutex-guarded. No extra threading is needed.

---

## 3. Interface Definition

### Kotlin ↔ C++ Contract (`EcsBridge`)

```kotlin
object EcsBridge {
    // Status codes (mirror ECSDetector::Status ordinals)
    const val STATUS_NO_CONGESTION = 0
    const val STATUS_BUILDING      = 1
    const val STATUS_IMMINENT      = 2

    // Signal codes (mirror CongestionSignal ordinals)
    const val SIGNAL_NONE     = 0
    const val SIGNAL_BUILDING = 1
    const val SIGNAL_IMMINENT = 2

    fun init(initialRateBps: Int = 260_000): Boolean  // call once per session
    fun shutdown()                                     // call on cleanup
    fun reset()                                        // call at PTT start

    fun addRttSample(rttUs: Long)                     // from PONG handler
    fun analyzeCongestion(): Int                       // STATUS_*
    fun getConfidence(): Double                        // 0.0–1.0
    fun canSendPacket(elapsedUs: Long): Boolean        // token bucket gate
    fun onCongestionSignal(signal: Int)                // SIGNAL_*
    fun onRecovery()
    fun getCurrentRateBps(): Int
}
```

### RTT Feedback — Signaling Message Extension

```
PING  →  { type: "PING",  from: "NodeA", timestampMs: 1743200000000, callId: "…" }
PONG  ←  { type: "PONG",  from: "NodeB", timestampMs: 1743200000000, callId: "…" }
                                             ^^^^^^^^^^^^^^^^^^^^^^^^^^^
                                             echoed unchanged from PING
RTT = System.currentTimeMillis() - pong.timestampMs
```

Two parallel RTT paths:
- **Coarse (2 s)**: Existing `startHeartbeat()` PING interval — always active during a call
- **Fine (future)**: RTP extension header with 64-bit send timestamp + `RTP_FEEDBACK` signaling

### Packet Data (unchanged wire format)

```
RTP header (12 bytes):  V=2 | PT=11 | seq(16) | timestamp(32) | SSRC(32)
Payload    (640 bytes): signed-16-bit mono PCM @ 16 kHz, 20 ms
Total: 652 bytes per packet = 50 pps × 652 × 8 = ~260 kbps baseline
```

---

## 4. Module Boundaries

```
┌─────────────────────────────────────────────────────────────────────────┐
│  KMP Layer  (Kotlin, Dispatchers.IO, coroutines)                        │
│                                                                         │
│  WifiDirectVoiceManager ──controls──► CallManager                      │
│                                           │                             │
│                          ┌────────────────┼──────────────┐             │
│                          │                │              │             │
│                     SignalingManager   RtpManager   VoiceAudioManager  │
│                          │                │                             │
└──────────────────────────│────────────────│─────────────────────────── ┘
                           │ UDP :5005      │ UDP :5004
                           ▼                ▼
                      [WiFi Direct P2P link]

┌─────────────────────────────────────────────────────────────────────────┐
│  Bridge Layer  (Kotlin singleton + JNI)                                 │
│                                                                         │
│   EcsBridge.kt  ──System.loadLibrary──►  adaptive_rtc_jni.so           │
│   (fail-open)      (per-call native state behind std::mutex)            │
└─────────────────────────────────────────────────────────────────────────┘
                           │
                           ▼ C++ calls
┌─────────────────────────────────────────────────────────────────────────┐
│  C++ ECS Layer  (AdaptiveRTC — zero changes to existing source)         │
│                                                                         │
│   RTTTracker      ECSDetector      RateController                       │
│   window=50       window=20        token-bucket                         │
│   sliding avg     confidence 0–1   min: 130 kbps / max: 520 kbps       │
└─────────────────────────────────────────────────────────────────────────┘
```

**Responsibility table:**

| What | Owner |
|---|---|
| Audio capture, gain, RTP packetization | `RtpManager` (KMP) |
| UDP send/receive | `RtpManager` (KMP) |
| Call signaling (CALL_REQUEST, PING/PONG) | `SignalingManager` + `CallManager` (KMP) |
| RTT sample ingestion (PONG → ECS) | `CallManager.handlePong()` (KMP) |
| Token-bucket send gate | `EcsBridge.canSendPacket()` called in `RtpManager.sendLoop()` |
| Rate decision | `RateController` (C++) |
| RTT statistics | `RTTTracker` (C++) |
| Congestion detection | `ECSDetector` (C++) |
| JNI glue, mutex, state lifecycle | `ecs_bridge.cpp` + `EcsBridge.kt` (Bridge) |

---

## 5. Threading Model

```
  Kotlin Coroutines                    Native C++ (adaptive_rtc_jni.so)
  ─────────────────────────────────────────────────────────────────────
  Dispatchers.Main (UI thread)
     └─ MeshViewModel, Compose UI          [never touches ECS]

  Dispatchers.IO (thread pool)
     ├─ RtpManager.sendLoop()
     │     ├─ EcsBridge.canSendPacket()  → std::mutex → RateController
     │     └─ EcsBridge.analyzeCongestion/onCongestionSignal()
     │                                   → std::mutex → ECSDetector
     │                                              → RTTTracker stats
     │
     ├─ SignalingManager.receiveLoop()
     │     └─ auto-sends PONG to remote  [no ECS access here]
     │
     └─ CallManager.handlePong()          ← launched in scope (IO)
           └─ EcsBridge.addRttSample()  → std::mutex → RTTTracker
```

**Key invariants:**
- `sendLoop` and `handlePong` can run concurrently → both acquire `g_mutex` in C++ → safe
- `sendLoop` never blocks longer than `delay(20 ms)` on the ECS path
- No ECS access from the main thread or from BLE/audio callbacks
- `EcsBridge.init()` / `EcsBridge.shutdown()` called from `CallManager` unambiguously at session start/end

---

## 6. Performance Considerations

| Concern | Mitigation |
|---|---|
| Per-packet JNI overhead | < 1 µs; lock is uncontended in steady state |
| `std::mutex` contention | Two callers max (sendLoop + handlePong); both complete in O(1) |
| Memory allocation on hot path | None — token bucket uses floating-point arithmetic only |
| Frame drops under congestion | `delay(20 ms)` yields coroutine; AudioRecord buffers keep recording |
| RTT granularity (2 s PING) | Sufficient for trend-based ECS; upgrade to 200 ms if needed by reducing `HEARTBEAT_INTERVAL_MS` |
| `ECSDetector` window size | 20 samples × 2 s = 40 s history; detects sustained trends, ignores spikes |
| Rate limits | min = 130 kbps (`initialRate/2`), max = 520 kbps (`initialRate×2`); audio never silenced |

---

## 7. Failure Handling

### Scenario → Response table

| Failure | Response |
|---|---|
| `System.loadLibrary` fails (missing .so) | `EcsBridge.available = false`; all methods return permissive defaults; audio proceeds at full rate |
| `nativeInit` returns false | Same fail-open path; session continues without ECS |
| `STATUS_IMMINENT` for extended period | Rate floor = 130 kbps (50 pps × 260 bytes × 8); audio degrades but never silences |
| PING/PONG stops (peer unreachable) | No new RTT samples → ECSDetector history expires → returns `STATUS_NO_CONGESTION` → rate recovers |
| `delay(20 ms)` in sendLoop accumulates | Same as losing a packet; equivalent to sender-side FEC with no replacement. Acceptable for PTT walkie-talkie |
| ECS rate drops below 1 packet/20 ms | `REDUCTION_IMMINENT × REDUCTION_IMMINENT = 0.75² = 0.56`; absolute floor enforced by `min_rate_bps` |
| Spurious congestion spike | `ECSDetector.confidence` threshold 0.90 required for `CONGESTION_IMMINENT`; one bad RTT reading doesn't trigger |

**Fallback guarantee**: ECS is strictly additive. Removing it (by not loading the library or calling `EcsBridge.shutdown()`) restores exact pre-integration behaviour.

---

## 8. Migration Plan

Each phase is independently deployable and independently revertable.

### Phase 0 — Foundation (no behaviour change)

**Goal**: Build system and new files in place; ECS code path is dead.

1. Add `src/cpp/ecs_bridge.cpp` to the repo.
2. Add `EcsBridge.kt` to `shared/androidMain`.
3. Update `NetSense AI/CMakeLists.txt` to add the `adaptive_rtc_jni` target.
4. Add `EcsBridge.init()`/`shutdown()` stubs to `CallManager` — the library
   won't be bundled yet so `System.loadLibrary` will throw and `available = false`.
5. **Verify**: existing audio calls work identically; no regression.

### Phase 1 — RTT measurement (observe only)

**Goal**: Prove RTT measurement works; ECS not influencing audio.

1. Update `SignalingManager.kt` — add `PONG` type + auto-respond to `PING`.
2. Add `SignalingEvent.Pong` + `CallManager.handlePong()`.
3. Log RTT values but do **not** call `EcsBridge.onCongestionSignal()`.
4. Build `adaptive_rtc_jni.so` and bundle it.
5. **Verify**: RTT samples appear in Logcat; values match expected link RTTs;
   `ECSDetector.getConfidence()` rises as samples accumulate.

### Phase 2 — Passive ECS (detect, do not act)

**Goal**: Validate ECS detection accuracy on real traffic.

1. Enable `EcsBridge.analyzeCongestion()` every 20 packets in `RtpManager.sendLoop()`.
2. Call `listener.onEcsStatus(status, confidence, rate)` — surface in debug UI.
3. Do **not** call `EcsBridge.onCongestionSignal()` yet → token bucket stays at max rate.
4. **Verify**: Status transitions between `NO_CONGESTION` → `BUILDING` → `IMMINENT`
   when network is artificially degraded (e.g. via Android Traffic Control or airplane mode tapping).

### Phase 3 — Active rate control

**Goal**: ECS throttles send rate; observe audio quality impact.

1. Uncomment `EcsBridge.onCongestionSignal(signal)` / `onRecovery()` in `sendLoop`.
2. Enable `EcsBridge.canSendPacket()` gate — frame drops under congestion.
3. **Verify**: Under simulated 50 % packet loss, rate converges at ~130–195 kbps
   instead of constant 260 kbps, reducing queuing delay.

### Phase 4 — Tune & harden

1. Verify audio quality at each congestion level with actual devices.
2. Reduce PING interval to 500 ms during streaming if 2 s is too coarse:
   ```kotlin
   // In CallManager.startPushToTalk(), after rtpManager.startStreaming():
   signalingManager.startHeartbeat(remote, callId, intervalMs = 500L)
   ```
   (requires adding `intervalMs` parameter to `startHeartbeat`)
3. Add `onEcsStatus` to `VoiceTransportListener` → expose in UI as network health bar.
4. Consider replacing PING-based RTT with per-packet OWD feedback (RTP extension header
   + `RTP_FEEDBACK` signaling type) for 20 ms granularity.
5. A/B test with and without ECS on weak WiFi environments.

---

## New Files Summary

| File | Purpose |
|---|---|
| [NetSense AI/src/cpp/ecs_bridge.cpp](NetSense%20AI/src/cpp/ecs_bridge.cpp) | JNI implementation; holds global ECS engine state |
| [NetSense AI/shared/src/androidMain/kotlin/com/netsense/mesh/EcsBridge.kt](NetSense%20AI/shared/src/androidMain/kotlin/com/netsense/mesh/EcsBridge.kt) | Kotlin singleton facade; fail-open |

## Modified Files Summary

| File | Change |
|---|---|
| [NetSense AI/CMakeLists.txt](NetSense%20AI/CMakeLists.txt) | Adds `adaptive_rtc_jni` shared-library target linking AdaptiveRTC sources |
| [NetSense AI/shared/.../SignalingManager.kt](NetSense%20AI/shared/src/androidMain/kotlin/com/netsense/mesh/SignalingManager.kt) | Adds `PONG` message type; auto-responds to `PING`; emits `SignalingEvent.Pong` |
| [NetSense AI/shared/.../RtpManager.kt](NetSense%20AI/shared/src/androidMain/kotlin/com/netsense/mesh/RtpManager.kt) | ECS token-bucket gate in `sendLoop`; periodic `analyzeCongestion`; `EcsBridge.reset()` at PTT start |
| [NetSense AI/shared/.../CallManager.kt](NetSense%20AI/shared/src/androidMain/kotlin/com/netsense/mesh/CallManager.kt) | `EcsBridge.init()` on session start; `EcsBridge.shutdown()` on cleanup; `handlePong()` feeds RTT |

---

## Quick Reference: ECS Engine Parameters

| Parameter | Value | Meaning |
|---|---|---|
| RTTTracker window | 50 samples | ~100 s at 2 s PING interval |
| ECSDetector window | 20 samples | ~40 s trend window |
| `IMMINENT_CONFIDENCE_THRESHOLD` | 0.90 | high bar before acting |
| `SIGNAL_RECOVERY_TIME_S` | 5.0 s | cooldown after last congestion signal |
| Rate reduction — BUILDING | × 0.90 | gentle: ~26 kbps reduction |
| Rate reduction — IMMINENT | × 0.75 | aggressive: ~65 kbps reduction |
| Rate recovery | × 1.05 | slow probe-up to avoid oscillation |
| min bitrate | initial / 2 | 130 kbps — always enough for voice |
| max bitrate | initial × 2 | 520 kbps — ceiling for probe-up |
