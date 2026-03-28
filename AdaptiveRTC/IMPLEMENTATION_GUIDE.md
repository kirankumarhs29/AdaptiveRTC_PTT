# AdaptiveRTC - Complete Implementation Guide

## Table of Contents
1. Project Architecture
2. Folder Structure
3. Core Classes Overview
4. Step-by-Step Implementation
5. Algorithm Details
6. Multithreading Strategy
7. Performance Considerations
8. Testing & Validation

---

## 1. PROJECT ARCHITECTURE

### Why This Design?

The system is built on **clean layering principles**:

```
┌─────────────────────────────────────────────────────────────────┐
│                    SIMULATION LAYER                             │
│  (NetworkSimulator: injects packet loss, delay, jitter)         │
└──────────────────────┬──────────────────────────────────────────┘
                       │
┌──────────────────────┴──────────────────────────────────────────┐
│                    TRANSPORT LAYER                              │
│  Sender ──── [UDP/RTP packets] ──── Receiver                    │
├─────────────────────────────────────────────────────────────────┤
│  Sender Components:                 Receiver Components:        │
│  ├─ RateController                  ├─ RTTTracker (measures)    │
│  └─ RateAdaptation                  ├─ ECSDetector (predicts)   │
│                                     ├─ JitterBuffer (absorbs)   │
│                                     └─ PacketQueue (stores)     │
└─────────────────────────────────────────────────────────────────┘
```

### Why Each Component?

**NetworkSimulator**
- Isolates network from transport logic
- Can inject any loss/delay pattern
- Enables deterministic testing

**RateController at Sender**
- Proactively reduces sending rate when congestion predicted
- Smooth adaptation (no sudden drops)
- Based on receiver feedback (RTT, ECS)

**RTTTracker at Receiver**
- Measures round-trip time by tracking packet timestamps
- Computes moving average and variance
- Foundation for ECS detection

**ECSDetector at Receiver**
- Detects congestion BEFORE packet loss
- Uses delay trend analysis
- Signals sender to reduce rate proactively

**JitterBuffer at Receiver**
- Smooths packet arrival variation
- Dynamic sizing based on network conditions
- Balances low-latency vs. smooth playback

---

## 2. FOLDER STRUCTURE

```
AdaptiveRTC/
├── CMakeLists.txt                    # Build configuration (explained in detail)
├── plan.md                           # High-level design (given)
├── IMPLEMENTATION_GUIDE.md           # This file
├── include/                          # Headers - define contracts
│   ├── packet.h
│   ├── network_simulator.h
│   ├── rtt_tracker.h
│   ├── ecs_detector.h
│   ├── rate_controller.h
│   ├── jitter_buffer.h
│   ├── sender.h
│   ├── receiver.h
│   └── metrics_logger.h
├── src/                              # Implementation - fulfill contracts
│   ├── packet.cpp
│   ├── network_simulator.cpp
│   ├── rtt_tracker.cpp
│   ├── ecs_detector.cpp
│   ├── rate_controller.cpp
│   ├── jitter_buffer.cpp
│   ├── sender.cpp
│   ├── receiver.cpp
│   ├── metrics_logger.cpp
│   └── main_simulator.cpp            # Main simulation entry point
├── test/                             # Unit + integration tests
│   ├── test_packet.cpp
│   ├── test_rtt_tracker.cpp
│   ├── test_ecs_detector.cpp
│   ├── test_jitter_buffer.cpp
│   └── integration_test.cpp
└── build/                            # Build output (Git-ignored)

### Why This Structure?

**include/ vs src/ Split**
- include/: PUBLIC interface - what other modules need to know
- src/: PRIVATE implementation - internal details hidden

**Example**:
- include/ecs_detector.h exposes only: `DetectorStatus detect(const RTTSample&)`
- src/ecs_detector.cpp hides: sliding window algorithm, thresholds, state machine

**Benefits**:
- Easy to change internals without breaking other modules
- Clear dependencies
- Easier to test (mock interfaces)

**test/ Organization**
- One test file per core class
- integration_test.cpp: tests classes working together
- Each test is independent and deterministic

---

## 3. CORE CLASSES OVERVIEW

### 3.1 Packet

**Purpose**: Represent a single voice packet in the system.

**Responsibilities**:
- Store audio data and metadata
- Track timestamps for RTT calculation
- Support serialization for network transmission

**Internal Variables**:
```cpp
struct Packet {
    uint32_t sequence_number;        // Unique packet ID
    uint64_t send_time_us;           // When sender transmitted (microseconds)
    uint64_t receive_time_us;        // When receiver got it (microseconds)
    std::vector<uint8_t> payload;    // Audio data
    uint16_t payload_size;           // Redundant with payload.size() but explicit
};
```

**Why microseconds?**
- Milliseconds have only 1000 units/second - insufficient precision
- Microseconds have 1,000,000 units/second - sufficient for network jitter (typically 10-200ms variation)
- Easier to compute: don't need floating-point

**Key Methods**:
- `Packet(uint32_t seq, const std::vector<uint8_t>& data)` - constructor
- `uint64_t getRoundTripTime()` - returns receive_time - send_time

---

### 3.2 NetworkSimulator

**Purpose**: Simulate network conditions without actual network infrastructure.

**Responsibilities**:
- Inject artificial delay
- Simulate packet loss
- Add jitter (random variation)
- Deterministic for testing

**Why Separate Component?**
- Real UDP sockets are non-deterministic
- Hard to test congestion scenarios consistently
- Enables controlled experiments

**Behavior**:
- Input: packet from sender
- Processing: apply delay, decide loss probabilistically
- Output: packet to receiver (or drop it)

---

### 3.3 RTTTracker

**Purpose**: Measure and track round-trip time (RTT) statistics.

**Responsibilities**:
- Collect RTT samples from received packets
- Compute moving average (for trend detection)
- Compute variance (for jitter estimation)
- Detect sudden RTT spikes

**Internal Variables**:
```cpp
class RTTTracker {
    // Circular buffer for last N RTT samples
    std::deque<uint64_t> rtt_samples;
    
    // Statistics (updated as samples arrive)
    uint64_t rtt_avg_us;
    uint64_t rtt_min_us;
    uint64_t rtt_max_us;
    double rtt_stddev_us;
};
```

**Why Deque?**
- Fixed-size sliding window (last 50 packets)
- Efficient push_back + pop_front in O(1)
- Better than vector (which requires O(n) shift on pop_front)
- Better than queue (needs indexed access for statistics)

**Methods**:
- `addSample(uint64_t rtt)` - add new RTT measurement
- `getMedian()` - middle value (robust to outliers)
- `getTrendDirection()` - is RTT increasing or decreasing?

---

### 3.4 ECSDetector

**Purpose**: Detect Early Congestion Signal before packet loss occurs.

**Responsibilities**:
- Monitor RTT trend
- Identify congestion buildup
- Trigger adaptation signals
- Handle false positives

**Algorithm Concept**:
```
RTT Trend Detection:
├─ If RTT increasing rapidly for N consecutive samples
│  └─ CONGESTION_BUILDING (medium confidence)
├─ If RTT increasing + spike in variance
│  └─ CONGESTION_IMMINENT (high confidence)
└─ If RTT stable or decreasing
   └─ NO_CONGESTION

Action:
├─ NO_CONGESTION: sender maintains rate
├─ CONGESTION_BUILDING: sender reduces rate by 10%
└─ CONGESTION_IMMINENT: sender reduces rate by 25%
```

**Why This Approach?**
- Purely delay-based (works before packets drop)
- Conservative (avoids over-reduction)
- Reversible (rate can increase if congestion clears)

---

### 3.5 RateController

**Purpose**: Manage sender transmission rate based on network feedback.

**Responsibilities**:
- Maintain target send rate
- Adapt rate based on ECS signals
- Smooth rate changes (avoid sudden drops)
- Respect bandwidth limits

**Internal State**:
```cpp
class RateController {
    uint32_t current_rate_bps;       // bits per second
    uint32_t min_rate_bps;           // floor (don't go below)
    uint32_t max_rate_bps;           // ceiling
    double rate_decrease_factor;     // multiply by 0.9 on congestion
    double rate_increase_factor;     // multiply by 1.1 on recovery
};
```

**Adaptation Logic**:
```cpp
if (ecs_signal == CONGESTION_IMMINENT) {
    current_rate *= 0.75;            // Aggressive reduce: 25% less
}
else if (ecs_signal == CONGESTION_BUILDING) {
    current_rate *= 0.90;            // Mild reduce: 10% less
}
else if (rtt_improving && time_since_reduction > 2_seconds) {
    current_rate *= 1.05;            // Cautious increase: 5% more
}
// Always clamp to [min, max]
current_rate = std::clamp(current_rate, min_rate_bps, max_rate_bps);
```

**Why Multiplicative Adjustment?**
- Proportional: higher rate → larger absolute decrease
- Example: 1 Mbps → 0.75 Mbps, but also 100 kbps → 75 kbps (proportional)
- Better than additive: additive might leave 100 kbps → 100 kbps - 250 kbps = 0 (unstable)

---

### 3.6 JitterBuffer

**Purpose**: Absorb packet arrival variation and smooth playback.

**Responsibilities**:
- Store arriving packets in order
- Synthesize audio at fixed play intervals
- Adapt buffer depth to network conditions
- Handle packet loss/reordering

**Buffer Visualization**:
```
Network packets arrive with jitter:
├─ Packet 1: arrives at t=0
├─ Packet 2: arrives at t=15 (delayed 5ms)
├─ Packet 3: arrives at t=18 (delayed 8ms)
└─ Packet 4: arrives at t=25 (delayed 15ms)

Jitter Buffer:
├─ Playback clock reads at: t=0, 20, 40, 60, ... (every 20ms)
├─ At t=20: buffer has packets 1,2,3 → play packet 2, advance clock
├─ At t=40: buffer has packet 4 → play it
└─ At t=60: buffer empty → play silence or repeat last packet (loss recovery)
```

**Internal Variables**:
```cpp
class JitterBuffer {
    std::deque<Packet> buffer;          // Storing incoming packets
    uint32_t buffer_target_size;        // Packets we want to keep
    uint64_t next_play_time_us;         // When to output next packet
    uint32_t packet_duration_us;        // How long each packet represents (e.g., 20ms)
};
```

**Why Deque?**
- Packets arrive out of order → need indexed access
- Packets are consumed sequentially → need efficient pop_front
- Deque is O(1) for both operations
- Vector would be O(n) on pop_front
- Queue can't do indexed access

**Buffer Depth Adaptation**:
```cpp
// Observe output of RTTTracker
target_size = median_rtt_us / packet_duration_us + safety_margin;

// Example: RTT=100ms, packet_duration=20ms
// target_size = 5 + 2 = 7 packets in buffer
// Ensures ~140ms latency, smooth playback
```

---

### 3.7 Sender

**Purpose**: Orchestrate transmission of voice packets.

**Responsibilities**:
- Encode audio into packets
- Apply rate control
- Schedule transmission
- Manage packet numbering

**Flow**:
```
1. Get audio frame (20ms of voice)
2. Encode to bitstream
3. Create Packet with sequence number
4. Check rate control: can we send now?
5. If yes → send via NetworkSimulator
   If no → place in queue, wait for rate allowance
6. Track packet for RTT monitoring
```

---

### 3.8 Receiver

**Purpose**: Coordinate reception and playout of packets.

**Responsibilities**:
- Receive packets from NetworkSimulator
- Track RTT and detect congestion
- Manage jitter buffer
- Decode and play audio
- Send feedback to sender

**Flow**:
```
1. Receive packet from NetworkSimulator
2. Record receive_time
3. RTTTracker.addSample(receive_time - send_time)
4. ECSDetector.analyze(rtt_stats)
5. If ECS_SIGNAL_GENERATED:
   → Send feedback to Sender (reduce rate)
6. JitterBuffer.addPacket(packet)
7. At playback time:
   → Get packet from JitterBuffer
   → Decode
   → Play
```

---

## 4. STEP-BY-STEP IMPLEMENTATION

Now let's implement each component. Each step builds on previous ones.

### Step 0: Basic Packet Structure

The foundation - nothing fancy, just a container.

### Step 1: Packet + NetworkSimulator

Start with:
- How packets are represented
- How network conditions are applied

**Design Decisions**:
- Packet uses microseconds (precision critical for RTT)
- NetworkSimulator is synchronous (not event-based yet)
- Jitter applied via Gaussian distribution

### Step 2: Sender + Receiver (No Feedback)

Build the basic send/receive loop:
- Sender generates and sends packets
- Receiver stores them
- No adaptation yet

### Step 3: RTT Tracking + Feedback Channel

Add measurement:
- Receiver measures RTT
- Sends back congestion info
- Sender receives feedback

### Step 4: ECS Detection Algorithm

Implement the core innovation:
- Analyze delay trends
- Detect congestion signals
- Classify urgency level

### Step 5: Rate Control

Apply feedback:
- Sender adjusts rate based on ECS
- Smooth multiplicative adaptation
- Respects bandwidth limits

### Step 6: Jitter Buffer

Absorb network variation:
- Buffer manages playout timing
- Adaptive sizing
- Loss recovery

### Step 7: Multithreading

Scale to real-time:
- Sender thread
- Receiver thread
- Simulation scheduler thread
- Synchronization via mutex + condition_variable

Each step includes full code + explanation below.

