# NetSense AI — Adaptive Walkie-Talkie

A peer-to-peer walkie-talkie Android app with an intelligent C++ voice engine. Devices discover each other over BLE, negotiate a Wi-Fi Direct link, and exchange real-time audio with proactive congestion control — all without any server or internet connection.

---

## Repository Structure

```
combineBoth/
├── NetSense AI/          # Android KMP application (Kotlin + Jetpack Compose)
│   ├── androidApp/       # Android entry point (MainActivity, splash, permissions)
│   ├── shared/           # KMP shared logic (BLE, RTP, signaling, call state)
│   ├── src/cpp/          # JNI bridge layer (ecs_bridge.cpp, CoreLogger.cpp)
│   └── CMakeLists.txt    # Builds netsense_mesh.so + adaptive_rtc_jni.so
│
├── AdaptiveRTC/          # C++ voice engine (compiled into NetSense AI via JNI)
│   ├── include/          # Public headers (ECSDetector, RTTTracker, etc.)
│   ├── src/              # C++17 implementation
│   ├── test/             # Google Test unit + integration tests
│   └── build/            # CMake build output (Windows/Linux standalone tests)
│
└── ECS_INTEGRATION.md    # Architecture deep-dive: how the two projects connect
```

> **AdaptiveRTC is not a separate app.** Its source files are compiled directly into
> `adaptive_rtc_jni.so` by NetSense AI's `CMakeLists.txt`. Both folders must be present.

---

## Architecture

### Layer Overview

```
┌──────────────────────────────────────────────────────┐
│                  Android UI Layer                     │
│  Jetpack Compose  •  MeshHomeScreen  •  CallScreen   │
└────────────────────────┬─────────────────────────────┘
                         │ StateFlow / events
┌────────────────────────▼─────────────────────────────┐
│               Kotlin Shared Logic (KMP)               │
│                                                       │
│  CallManager ──► CallStateMachine                     │
│  SignalingManager  (PROBE / PROBE_ACK / PING / PONG)  │
│  RtpManager  ──► sendLoop / receiveLoop               │
│  EcsBridge   ──► canSendPacket() / addRttSample()     │
│  JitterBridge ──► push() / pull() / depthMs()         │
│  VoiceAudioManager  (AudioRecord + AudioTrack)        │
│  WifiDirectVoiceManager  (P2P negotiation)            │
└────────────────────────┬─────────────────────────────┘
                         │ JNI (< 1 µs/call)
┌────────────────────────▼─────────────────────────────┐
│          adaptive_rtc_jni.so  (C++17 native)          │
│                                                       │
│  ECSDetector   — predicts congestion from RTT trends  │
│  RTTTracker    — 50-sample sliding window, µs stats   │
│  RateController — token-bucket AIMD gate              │
│  JitterBuffer  — reorders seq, adaptive 40–120 ms     │
└──────────────────────────────────────────────────────┘
         ▲                             ▲
         │ Wi-Fi Direct UDP :5004      │ BLE GATT
┌────────┴────────┐          ┌─────────┴────────┐
│  Remote Device  │          │  Remote Device   │
│  (audio RX/TX)  │          │  (discovery)     │
└─────────────────┘          └──────────────────┘
```

### Key Components

| Component | File | Role |
|-----------|------|------|
| **RtpManager** | `shared/.../RtpManager.kt` | Master voice orchestrator — 20 ms send/receive loops |
| **EcsBridge** | `shared/.../EcsBridge.kt` | JNI facade for rate control and congestion analysis |
| **JitterBridge** | `shared/.../JitterBridge.kt` | JNI facade for adaptive jitter buffer |
| **CallManager** | `shared/.../CallManager.kt` | Push-to-talk call lifecycle |
| **SignalingManager** | `shared/.../SignalingManager.kt` | PING/PONG heartbeat → RTT samples |
| **ECSDetector** | `AdaptiveRTC/src/ecs_detector.cpp` | Detects BUILDING / IMMINENT congestion from delay trend |
| **RTTTracker** | `AdaptiveRTC/src/rtt_tracker.cpp` | µs-precision RTT statistics and spike detection |
| **RateController** | `AdaptiveRTC/src/rate_controller.cpp` | Token-bucket gate, multipliers: 0.75 / 0.90 / 1.05 |
| **JitterBuffer** | `AdaptiveRTC/src/jitter_buffer.cpp` | Out-of-order sequence reordering and depth adaptation |

### Audio Send Path (one 20 ms frame)

```
AudioRecord.read(640 bytes)
  → EcsBridge.canSendPacket()    ← token-bucket gate (C++ RateController)
  → RtpPacket.toByteArray()      ← 12-byte RTP header + 640-byte payload
  → DatagramSocket.send()        → Wi-Fi Direct peer UDP :5004
```

### Audio Receive Path

```
DatagramSocket.receive()
  → RtpPacket.parse()
  → JitterBridge.push(seq, ts, payload)   ← C++ JitterBuffer (reorder, store)
  → [every 20 pkts] EcsBridge.addRttSample(rttUs)
  → [every 20 pkts] EcsBridge.analyzeCongestion()
      → STATUS_NO_CONGESTION  → rate × 1.05 (recovery)
      → STATUS_BUILDING       → rate × 0.90
      → STATUS_IMMINENT       → rate × 0.75

[Audio playout thread]
  → JitterBridge.pull()          ← ordered frame from C++ JitterBuffer
  → AudioTrack.write()           → speaker
```

### Peer Discovery & Call Setup

```
Device A                            Device B
   │── BLE advertise ──────────────────▶│
   │◀── BLE scan + connect ─────────────│
   │── SignalingManager PROBE ──────────▶│
   │◀── PROBE_ACK ──────────────────────│
   │── (TRANSPORT_READY) ───────────────▶│
   │◀─────── Wi-Fi Direct negotiation ──│
   │════════ UDP RTP audio stream ══════│
```

---

## Getting Started

### Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| Android Studio | Hedgehog+ | Build & install Android APK |
| Android NDK | r25c or r26 | Compile C++ native libraries |
| CMake | 3.22+ | Native build system |
| JDK | 17 | Kotlin / Gradle |
| Two Android devices | API 26+ | Run the app (emulator won't do BLE/Wi-Fi Direct) |

### 1. Clone the Repo

```bash
git clone https://github.com/<your-username>/netsense-ai.git
cd netsense-ai
```

> Both `NetSense AI/` and `AdaptiveRTC/` must remain in the same directory.
> The CMake build references AdaptiveRTC via relative path `../AdaptiveRTC/`.

### 2. Configure local.properties

In `NetSense AI/local.properties` set your Android SDK path:

```
sdk.dir=C\:\\Users\\YourName\\AppData\\Local\\Android\\Sdk
```

Android Studio creates this automatically when you open the project.

### 3. Build & Install (Android)

Open `NetSense AI/` as a project in Android Studio, or use the command line:

```bash
cd "NetSense AI"

# Build debug APK
./gradlew :androidApp:assembleDebug

# Install on all connected devices at once
./gradlew :androidApp:installDebug
```

Connect two Android devices via USB, grant Bluetooth and location permissions on both,
then install.

### 4. Run the App

1. Launch the app on **both** devices.
2. Tap **Start Discovery** on both.
3. Wait for the remote peer to appear in the list (~5–10 s).
4. Select the peer on one device and tap **Connect**.
5. Once status shows **Connected**, hold **Talk** (PTT) to speak.
6. Audio plays in real time on the other device.

### 5. Build & Test AdaptiveRTC Standalone (Windows / Linux)

The C++ engine has its own test suite that runs without Android:

```bash
cd AdaptiveRTC/build

# Windows (Visual Studio)
cmake .. -G "Visual Studio 17 2022" -A x64
cmake --build . --config Release

# Linux / macOS
cmake .. -DCMAKE_BUILD_TYPE=Release
cmake --build .

# Run simulation
./bin/rtc_simulator        # prints per-packet stats + CSV

# Run unit + integration tests
./bin/rtc_tests
```

### 6. Validate Fast (Kotlin only)

After editing shared Kotlin code, skip a full APK build:

```bash
cd "NetSense AI"
./gradlew :shared:compileDebugKotlinAndroid
```

---

## Native Libraries Produced

| Library | Built from | Loaded by |
|---------|-----------|-----------|
| `netsense_mesh.so` | `src/cpp/mesh_engine.cpp`, `node.cpp`, `routing_table.cpp`, `state_machine.cpp`, `jni_bridge.cpp` | `MeshManager` via `System.loadLibrary("netsense_mesh")` |
| `adaptive_rtc_jni.so` | `src/cpp/ecs_bridge.cpp` + all `AdaptiveRTC/src/*.cpp` | `EcsBridge` / `JitterBridge` via `System.loadLibrary("adaptive_rtc_jni")` |

---

## Key Design Decisions

- **Proactive congestion control**: ECSDetector detects rising RTT trends and signals `BUILDING` before any packet loss occurs — reducing quality degradation vs. reactive NACK/PLI approaches.
- **JNI over sockets**: ECS calls average < 1 µs, negligible in a 20 ms frame budget. Unix-domain sockets would add 50–200 µs per frame.
- **No server**: Discovery uses BLE; audio uses Wi-Fi Direct UDP. Zero infrastructure required.
- **AdaptiveRTC source unchanged**: The C++ engine is compiled as-is into the Android `.so`; no fork or modification needed.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---------|-------------|-----|
| Both devices show `sig-send PROBE` but call never connects | Route-check gating deadlock in PROBE_ACK path | Check `SignalingManager` `handleProbeAck` route validation |
| Audio is choppy under load | Jitter buffer depth too shallow | Inspect `JitterBridge.depthMs()` logs; confirm ECS rate is not stuck at 0.75× |
| Logs appear stale | Reading exported logs instead of live app storage | `adb exec-out run-as com.netsense.meshapp cat /data/data/com.netsense.meshapp/files/core.log` |
| NDK build fails: header not found | AdaptiveRTC folder missing or moved | Ensure `AdaptiveRTC/` is a sibling of `NetSense AI/` |
| `installDebug` installs on only one device | Second device not authorized | Check `adb devices`; accept USB debugging on both |

---

## Further Reading

- [ECS_INTEGRATION.md](ECS_INTEGRATION.md) — detailed architecture, JNI approach rationale, full data-flow diagrams
- [AdaptiveRTC/COMPLETE_SUMMARY.md](AdaptiveRTC/COMPLETE_SUMMARY.md) — C++ engine design, test results, performance numbers
- [AdaptiveRTC/CODE_WALKTHROUGH.md](AdaptiveRTC/CODE_WALKTHROUGH.md) — line-by-line explanation of each C++ component
- [AdaptiveRTC/MULTITHREADING_GUIDE.md](AdaptiveRTC/MULTITHREADING_GUIDE.md) — thread safety analysis
