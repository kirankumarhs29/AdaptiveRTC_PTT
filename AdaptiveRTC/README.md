# AdaptiveRTC - Intelligent Voice Communication System

## Quick Start

```bash
# Build
cd build
cmake ..
cmake --build .

# Run simulation
./bin/rtc_simulator

# Run tests
./bin/rtc_tests

# Results
cat simulation_results.csv
```

## System Overview

This project implements a complete real-time voice communication system with **Early Congestion Signal (ECS)** detection and adaptive jitter buffering.

**Key Innovation**: Detects congestion from delay trends BEFORE packet loss, enabling proactive adaptation.

```
┌──────────┐     ┌──────────────┐     ┌──────────┐
│  Sender  │────▶│  Network     │────▶│ Receiver │
│          │     │ Simulator    │     │          │
│          │     │ (loss/delay) │     │          │
│ Rate     │     │              │     │ RTT      │
│ Control  │     │              │     │ Tracker  │
└──────────┘     └──────────────┘     └──────────┘
   ▲                                        │
   │                                        ▼
   │                                   ┌──────────┐
   └───────────────────────────────────│ ECS      │
                                       │ Detector │
                Feedback Signal         └──────────┘
              (reduce/maintain rate)
```

## Architecture

### Core Components

Each component has a single responsibility:

| Component | Role | Why This Design |
|-----------|------|-----------------|
| **Packet** | Data container with timestamps | Simple, no dependencies |
| **NetworkSimulator** | Inject loss/delay/jitter | Deterministic testing without real network |
| **RTTTracker** | Measure RTT statistics | Foundation for ECS detection |
| **ECSDetector** | Predict congestion from delay trends | Proactive, before packet loss |
| **RateController** | AIMD rate adaptation | Smooth, fair congestion control |
| **JitterBuffer** | Absorb packet arrival variation | Smooth playback despite jitter |
| **Sender** | Transmit packets with rate control | Connects components |
| **Receiver** | Process packets and generate feedback | Analyzes network, signals sender |

### Data Flow

```
Sender Side:
  Audio Frame (20ms)
    ▼
  RateController.canSendPacket()
    ▼
  NetworkSimulator.simulateTransport()
    ▼
  (packet dropped or delayed)

Receiver Side:
  Receive Packet
    ▼
  RTTTracker.addSample()
    ▼
  ECSDetector.analyze()
    ▼
  Generate Signal (NONE, BUILDING, IMMINENT)
    ▼
  Sender.onCongestionFeedback()
    ▼
  RateController.onCongestionSignal()
    ▼
  Adjust transmission rate
```

## Algorithm Details

### 1. ECS Detection Algorithm

**Goal**: Detect congestion BEFORE packet loss.

**Mechanism**: Monitor RTT (round-trip time) trends.

```
RTT Samples: [45, 47, 49, 52, 55, 58, 62, 65, 68, ...]
             └─────────────────────────────────────┘
             Increasing trend → CONGESTION_BUILDING

RTT Samples: [45, 45, 45, 80, 81, 82, ...]
                        └──────────────┘
                        Spike detected → CONGESTION_IMMINENT
```

**Confidence Accumulation**:
- INCREASING trend: +0.6 base confidence
- Sustained increase (>5 samples):  +0.2
- RTT spike (>2σ): +0.3
- High variance (CV > 20%): +0.1
- Result clamped to [0, 1]

**Decision Thresholds**:
- Confidence > 0.9: CONGESTION_IMMINENT (aggressive action)
- Confidence > 0.5: CONGESTION_BUILDING (mild action)
- RTT improving: NO_CONGESTION (recovery)

### 2. Rate Control (AIMD)

**Additive Increase, Multiplicative Decrease**:

```
On CONGESTION_BUILDING:
  new_rate = current_rate * 0.90  (10% reduction)

On CONGESTION_IMMINENT:
  new_rate = current_rate * 0.75  (25% reduction)

On Recovery:
  new_rate = current_rate * 1.05  (5% increase)
```

**Why Multiplicative?**
- Fair: proportional to current rate
- Example: 1 Mbps → 900 kbps AND 100 kbps → 90 kbps
- Proven in TCP Reno

**Why Different Factors?**
- Decrease aggressively: stop congestion quickly
- Increase conservatively: avoid retriggering

### 3. Jitter Buffer

**Purpose**: Smooth network jitter while maintaining low latency.

**Algorithm**:
```
Arriving packets (jittered times):
  Seq 1: t=0ms
  Seq 3: t=15ms (out of order)
  Seq 2: t=25ms (reordered)
  Seq 4: t=35ms

Buffer storage (sorted by seq):
  map[1] = Pkt1
  map[2] = Pkt2  (reordered!)
  map[3] = Pkt3
  map[4] = Pkt4

Playback (fixed intervals, every 20ms):
  t=20ms: output seq 1
  t=40ms: output seq 2 (not out of order anymore!)
  t=60ms: output seq 3
  t=80ms: output seq 4
```

**Data Structure: std::map**
- Key: sequence number
- Automatically maintains sort order
- O(log n) insertion + O(1) sequential access
- Handles out-of-order arrival efficiently

**Why Not Deque?**
- Deque: O(1) sequential but O(n) for out-of-order insertion
- Map: O(log n) both cases
- Out-of-order is common in jittery networks

## Key Design Decisions

### 1. Microsecond Timestamps (uint64_t)

```cpp
uint64_t send_time_us;     // Microsecond precision
uint64_t receive_time_us;

RTT = receive_time_us - send_time_us;  // Exact integer, no rounding
```

**Why not milliseconds or floats?**
- Milliseconds: only 1000 units/sec (insufficient)
- Floats: rounding errors accumulate
- Microseconds: 1,000,000 units/sec (plenty for network jitter)

### 2. Sliding Window (Deque for RTTTracker)

```cpp
std::deque<uint64_t> samples_;  // Last N RTT samples

// O(1) add to back, remove from front
// Focus on recent conditions (not permanent history)
// Typical: 50 samples = 1 second history
```

**Why?**
- Forget old history (network conditions change)
- Efficient: no reallocation on every sample
- Fixed memory: predictable allocation

### 3. Token Bucket Rate Limiting

```
Rate = 64 kbit/s
Time elapsed = 20ms

Tokens generated = 64000 * 20000 / 1e6 = 1280 bits

Can send 800-bit packet? YES (1280 > 800)
Remaining tokens = 1280 - 800 = 480
```

**Benefits**:
- Smooth traffic (not bursty)
- Respects rate limits exactly
- Industry standard (proven effective)

### 4. Standard Deviation for Spike Detection

```cpp
// Spike if: latest > avg + 2*stddev
// 2σ rule: >95% of normal values within ±2σ
// Therefore: >2σ = definitely abnormal

if (latest_rtt > avg_rtt + 2 * stddev) {
    is_spiking = true;
}
```

**Why 2σ?**
- Statistical standard
- Avoids false positives (too strict)
- Avoids false negatives (too loose)

## Performance Characteristics

### Time Complexity

| Operation | Complexity | Typical Cost |
|-----------|-----------|--------------|
| Add RTT sample | O(1) | Immediate |
| Calculate statistics | O(n) cached | Once per query |
| Add packet to jitter buffer | O(log n) | Map insertion |
| Get next packet | O(1) amortized | Map find + erase |
| ECS Detection | O(n) | Window analysis |
| Rate control | O(1) | Token update |

### Space Complexity

| Component | Space | Typical |
|-----------|-------|---------|
| RTTTracker | O(n) | 50 samples × 8 bytes = 400 bytes |
| JitterBuffer | O(m) | 10 packets × 200 bytes = 2KB |
| RateController | O(1) | ~50 bytes |
| ECSDetector | O(1) | ~100 bytes |

**Total**: ~3KB per receiver (very efficient)

## Testing Strategy

### Unit Tests

Each component tested independently:
- `test_packet.cpp`: Basic data container
- `test_rtt_tracker.cpp`: Statistics calculation
- `test_ecs_detector.cpp`: Congestion detection
- `test_jitter_buffer.cpp`: Packet reordering

### Integration Tests

Components working together:
- `integration_test.cpp`: Send/receive/adapt cycle

### Simulation

Realistic scenario testing:
```
Phase 0-2s:   Baseline (no congestion)
              - Stable RTT ~15ms
              - No rate reduction
              
Phase 2-5s:   Congestion Building
              - RTT increases to 50ms
              - ECS: CONGESTION_BUILDING
              - Sender reduces rate 10%
              
Phase 5-7s:   Peak Congestion
              - RTT jumps to 100ms, 10% loss
              - ECS: CONGESTION_IMMINENT
              - Sender reduces rate 25%
              
Phase 7-10s:  Recovery
              - RTT decreases to 20ms
              - ECS: NO_CONGESTION
              - Sender increases rate 5%
```

## Extensibility

### Adding New Congestion Signals

Currently: NONE, BUILDING, IMMINENT

To add (e.g., CRITICAL):
```cpp
// In ecs_detector.h:
enum class Status {
    NO_CONGESTION,
    CONGESTION_BUILDING,
    CONGESTION_IMMINENT,
    CONGESTION_CRITICAL  // NEW
};

// In ecs_detector.cpp:
if (confidence > 0.95) {
    return Status::CONGESTION_CRITICAL;
}

// In sent onCongestionFeedback:
case CongestionSignal::CRITICAL:
    rate *= 0.5;  // Aggressive: 50%
    break;
```

### Adding New Network Parameters

Currently: loss, delay, jitter

To add (e.g., bitrate limit):
```cpp
struct NetworkConfig {
    double packet_loss_percent;
    uint64_t base_delay_us;
    uint64_t jitter_stddev_us;
    uint32_t bandwidth_limit_bps;  // NEW
};
```

### Adding Multithreading

Current: Single-threaded simulation

To add async sender/receiver:
```cpp
// Sender thread
std::thread sender_thread([&]() {
    while (running) {
        auto payload = audio_queue.pop();
        sender.sendPacket(payload, now());
    }
});

// Receiver thread
std::thread receiver_thread([&]() {
    while (running) {
        auto pkt = network.getNextPacket();
        if (pkt) receiver.receivePacket(*pkt);
    }
});
```

## Debugging Tips

### Check RTT Trends

```cpp
// See if ECS is detecting trend correctly
RTTTrend trend = receiver.getRTTTracker().getTrendDirection();
cout << "Trend: "
     << (trend == RTTTrend::INCREASING ? "INCREASING" :
         trend == RTTTrend::DECREASING ? "DECREASING" :
         "STABLE") << endl;
```

### Verify Network Simulation

```cpp
auto stats = network.getStatistics();
cout << "Simulated Loss: " << stats.getLossRate() << "%\n";
cout << "Avg Delay: " << stats.getAverageDelay() / 1000.0 << " ms\n";
```

### Check Jitter Buffer Status

```cpp
cout << "Buffer depth: " << receiver.getJitterBuffer().getCurrentDepth()
     << "/" << receiver.getJitterBuffer().getTargetDepth() << "\n";
cout << "Lost: " << receiver.getJitterBuffer().getLostPacketCount() << "\n";
cout << "Duplicates: " << receiver.getJitterBuffer().getDuplicateCount() << "\n";
```

## References

### Algorithms
- **AIMD**: TCP Reno congestion control (RFC 2581)
- **Jitter Buffer**: ITU-T G.131 (VoIP Transmission)
- **Standard Deviation**: Statistical process control

### Papers
- "TCP Reno" - Van Jacobson (1988)
- "Measurement and Early Congestion Detection in the Internet" - Various authors
- "The Dynamics of TCP Traffic over ATM Networks" - Kurose (1996)

### Standards
- RFC 3550 (RTP)
- RFC 3557 (RTP Retransmission)
- ITU-T G.131 (VoIP Transmission Planning)

---

**Author**: Senior Platform Engineer
**Date**: 2026
**License**: MIT

