# Step-by-Step Code Explanation & Walkthrough

This guide explains the implementation line-by-line, helping you understand every design decision.

## PART 1: PACKET FOUNDATION

### File: `include/packet.h`

```cpp
struct Packet {
    uint32_t sequence_number;        // Line 1: Unique ID for each packet
    uint64_t send_time_us;           // Line 2: When sender created it
    uint64_t receive_time_us;        // Line 3: When receiver got it
    std::vector<uint8_t> payload;    // Line 4: Audio data
    uint16_t payload_size;           // Line 5: Quick size check
```

**Why these specific types?**

| Field | Type | Why |
|-------|------|-----|
| `sequence_number` | `uint32_t` | 2^32 packets ~ 85 sec at 50pps (enough for session) |
| `send_time_us`    | `uint64_t` | Microseconds ~ 584 years max (safe forever) |
| `receive_time_us` | `uint64_t` | Same timebase ensures RTT = receive - send is valid |
| `payload`         | `std::vector` | Codec-dependent size (40-160 bytes typical) |
| `payload_size`    | `uint16_t` | Redundant with .size() but allows optimization |

**Example RTT Calculation**:
```
send_time_us = 1,000,000 (microsecond clock value)
receive_time_us = 1,050,000 (50ms later)
rtt = 1,050,000 - 1,000,000 = 50,000 microseconds = 50ms ✓
```

### Method: `getRoundTripTime()`

```cpp
inline uint64_t getRoundTripTime() const {
    if (receive_time_us == 0) return 0;  // Not received yet
    return receive_time_us - send_time_us;
}
```

**Why check for 0?**
- Packet might not be received yet (receive_time_us still 0)
- Return 0 instead of negative number or exception
- Caller should validate: `if (rtt > 0)`

---

## PART 2: NETWORK SIMULATION

### File: `include/network_simulator.h`

```cpp
struct NetworkConfig {
    double packet_loss_percent;       // Probability [0, 100]
    uint64_t base_delay_us;           // Always add this (constant latency)
    uint64_t jitter_stddev_us;        // Random variation (Gaussian)
};
```

**Example Scenario**:
```
Network: Wi-Fi with 10% loss, 30ms latency, 10ms jitter
  packet_loss_percent = 10.0
  base_delay_us = 30000   (30ms)
  jitter_stddev_us = 10000  (10ms std-dev, ~95% within ±20ms)

Packet processing:
  1. Random: loss? (10% chance: yes)
     → Drop packet, return empty
  2. Calculate delay:
     delay = 30000 + jitter
     jitter ~ N(0, 10000)  (Gaussian)
     
  3. Examples:
     Jitter = -5000:  total delay = 25000us (25ms)
     Jitter = +1000:  total delay = 31000us (31ms)
     Jitter = +15000: total delay = 45000us (45ms) [outlier, ~5% chance]
```

### Method: `simulateTransport()`

```cpp
std::optional<Packet> NetworkSimulator::simulateTransport(
    const Packet& packet,
    uint64_t current_time_us)
{
    stats_.total_packets_sent++;  // Count: needed for validation
    
    if (shouldDropPacket()) {     // Random loss decision
        stats_.total_packets_lost++;
        return std::nullopt;      // Empty optional = packet lost
    }
    
    int64_t jitter = calculateJitter();  // Gaussian random
    int64_t total_delay_us = base_delay_us + jitter;
    total_delay_us = std::max(1LL, total_delay_us);  // Never negative
    
    Packet received = packet;     // Copy original
    received.receive_time_us = current_time_us + total_delay_us;
    
    return received;  // Optional containing packet
}
```

**Line-by-line explanation:**

Line 1: `if (shouldDropPacket())`
- Bernoulli distribution: true with probability = loss_percent/100
- Example: 10% loss → bernoulli returns true 10% of time

Line 2: `return std::nullopt`
- `std::optional<Packet>` can be empty (nullopt) or have value
- Cleaner than returning null pointer
- Compiler forces checking: `if (result) { ... }`

Line 3: `jitter = calculateJitter()`
- Gaussian random: mean=0, stddev=jitter_stddev_us
- ~68% within ±1σ, ~95% within ±2σ, rare >3σ
- Can be negative (packet arrives earlier than baseline)

Line 4: `std::max(1LL, total_delay_us)`
- Ensure delay >= 1 microsecond (never negative, never zero)
- Zero delay is physically unrealistic
- 1 microsecond is negligible in practice

Line 5: `receive_time_us = current_time_us + total_delay_us`
- **Critical**: sender sees send_time_us, receiver sees receive_time_us
- RTT = receive - send = (current_time + delay) - send_time
- = delay (if current_time ≈ send_time, which is true in simulation)

---

## PART 3: RTT TRACKING

### File: `include/rtt_tracker.h`

```cpp
class RTTTracker {
    std::deque<uint64_t> samples_;      // Sliding window (FIFO)
    uint64_t cached_sum_us_ = 0;        // Incremental sum (O(1) average)
    size_t max_window_size_;            // Keep last N samples
};
```

**Why Deque?**
```
Vector:
  - removeOldest: O(n) (shift all elements back)
  - addNew: O(1) or O(n) if realloc

Deque:
  - removeOldest (pop_front): O(1) ✓
  - addNew (push_back): O(1) ✓
  - Random access: O(1) ✓

For sliding window, deque is perfect!
```

### Method: `addSample(uint64_t rtt_us)`

```cpp
void RTTTracker::addSample(uint64_t rtt_us) {
    if (samples_.size() >= max_window_size_) {
        cached_sum_us_ -= samples_.front();  // Remove oldest from sum
        samples_.pop_front();                 // O(1)
    }
    
    samples_.push_back(rtt_us);              // O(1)
    cached_sum_us_ += rtt_us;                // Update sum
    stats_dirty_ = true;                     // Recalc needed
}
```

**Example: window_size = 5**
```
Add 45000:  [45000]                             sum = 45000
Add 48000:  [45000, 48000]                      sum = 93000
Add 50000:  [45000, 48000, 50000]               sum = 143000
Add 52000:  [45000, 48000, 50000, 52000]        sum = 195000
Add 55000:  [45000, 48000, 50000, 52000, 55000] sum = 250000
Add 58000:  Remove 45000       ← max size exceeded
            [48000, 50000, 52000, 55000, 58000] sum = 263000
            (sum = 250000 - 45000 + 58000)
```

**Why cache sum?**
- Average = sum / count
- Without caching: recalculate Sum = O(n) every query
- With caching: update += O(1) on each addSample
- Query Average = O(1)

### Method: `getTrendDirection()`

```cpp
RTTTrend RTTTracker::getTrendDirection() const {
    if (samples_.size() < 10) {
        return RTTTrend::UNDEFINED;  // Need at least 10 samples
    }
    
    // Split into two halves
    size_t mid = samples_.size() / 2;
    
    uint64_t old_avg = (sum of first half) / mid;
    uint64_t recent_avg = (sum of second half) / (size - mid);
    
    double noise_margin = 1.5 * stddev;  // Tolerance
    
    if (recent_avg > old_avg + noise_margin) {
        return RTTTrend::INCREASING;  // Congestion!
    } else if (recent_avg + noise_margin < old_avg) {
        return RTTTrend::DECREASING;  // Improving!
    } else {
        return RTTTrend::STABLE;      // No trend
    }
}
```

**Example: Data = [50, 51, 52, 53, 55, 58, 62, 65, 68, 70]**

```
First half:  [50, 51, 52, 53, 55]  → avg = 52.2
Second half: [58, 62, 65, 68, 70]  → avg = 64.6
Difference:  64.6 - 52.2 = 12.4

stddev ≈ 7.5, noise_margin ≈ 11.2

Is 64.6 > 52.2 + 11.2 = 63.4?  YES!
→ INCREASING trend detected (congestion building)
```

**Why both halves?**
- Single spike doesn't = trend
- Sustained increase across multiple packets = real trend
- Averaging reduces noise

---

## PART 4: ECS DETECTION

### File: `include/ecs_detector.h`

```cpp
enum class Status {
    NO_CONGESTION,              // 0: OK
    CONGESTION_BUILDING,        // 1: Mild (reduce 10%)
    CONGESTION_IMMINENT,        // 2: Severe (reduce 25%)
};
```

### Method: `detect()`

```cpp
ECSDetector::Status ECSDetector::detect() {
    // CASE 1: High confidence
    if (current_confidence_ > 0.90) {  // >90% sure
        return Status::CONGESTION_IMMINENT;
    }
    
    // CASE 2: Medium confidence
    if (last_trend_ == RTTTrend::INCREASING && 
        current_confidence_ > 0.50) {   // >50% sure
        return Status::CONGESTION_BUILDING;
    }
    
    // CASE 3: Network recovering
    if (last_trend_ == RTTTrend::DECREASING) {
        return Status::NO_CONGESTION;
    }
    
    // CASE 4: Hysteresis (avoid flapping)
    return current_status_;  // Keep previous status
}
```

**Why Hysteresis?**
```
Without hysteresis:
  RTT: [50, 60, 50, 60, 50, 60, ...]
  Status would flip every packet: BUILDING, NORMAL, BUILDING, NORMAL, ...
  Sender would oscillate: reduce→increase→reduce
  Result: unstable, jittery playback ❌

With hysteresis:
  First BUILDING → stay BUILDING until DECREASING detected
  Prevents rapid flapping
  More stable adaptation ✓
```

### Method: `calculateConfidence()`

```cpp
double confidence = 0.0;

// Evidence 1: RTT Trend (most important)
if (trend == RTTTrend::INCREASING) {
    confidence += 0.6;        // Base: 60%
    // If sustained (many samples):
    if (consistently_increasing) {
        confidence += 0.2;    // Bonus: 20%
    }
}

// Evidence 2: RTT Spike (sudden large jump)
if (is_spiking) {
    confidence += 0.3;        // Spike: 30%
}

// Evidence 3: High Variability
double cv = stddev / mean;   // Coefficient of variation
if (cv > 0.20) {             // High variability
    confidence += 0.1;        // Bonus: 10%
}

// Clamp to [0, 1]
return std::min(1.0, std::max(0.0, confidence));
```

**Example Scenarios**:

```
Scenario A: Single spike
  trend = STABLE, spike = true
  confidence = 0.3
  → NO_CONGESTION (too uncertain)

Scenario B: Increasing trend, no spike
  trend = INCREASING, spike = false
  confidence = 0.6
  → CONGESTION_BUILDING (mild evidence)

Scenario C: Increasing + sustained + spike
  trend = INCREASING, sustained = true, spike = true
  confidence = 0.6 + 0.2 + 0.3 = 1.1 → clamped to 1.0
  → CONGESTION_IMMINENT (definite!)
```

---

## PART 5: RATE CONTROL

### File: `include/rate_controller.h`

```cpp
class RateController {
    uint32_t current_rate_bps_;           // Bits per second
    double available_tokens_;              // Token bucket
    uint64_t last_token_update_us_;        // When last updated
};
```

### Method: `onCongestionSignal()`

```cpp
void RateController::onCongestionSignal(CongestionSignal signal) {
    if (signal == CongestionSignal::BUILDING) {
        new_rate = current_rate * 0.90;   // Reduce 10%
    } else {  // IMMINENT
        new_rate = current_rate * 0.75;   // Reduce 25%
    }
    
    current_rate = new_rate;
    clampRate();  // Enforce [min, max] bounds
    resetTokenBucket();  // Start fresh token count
}
```

**Example Rate Reduction**:
```
Initial: 64 kbit/s

BUILDING detected:
  new = 64 * 0.90 = 57.6 kbit/s
  
Still BUILDING after 1 second:
  new = 57.6 * 0.90 = 51.84 kbit/s
  
IMMINENT soon after:
  new = 51.84 * 0.75 = 38.88 kbit/s (aggressive!)
```

### Method: `canSendPacket()`

```cpp
bool RateController::canSendPacket(
    uint32_t packet_size_bits, 
    uint64_t elapsed_time_us)
{
    updateTokenBucket(elapsed_time_us);
    
    bool can_send = available_tokens_ >= packet_size_bits;
    
    if (can_send) {
        available_tokens_ -= packet_size_bits;
    }
    
    return can_send;
}

void RateController::updateTokenBucket(uint64_t elapsed_time_us) {
    // rate_bps is bits per second
    // elapsed_time_us is microseconds
    // tokens_generated = rate_bps * elapsed_time_us / 1e6
    
    double tokens = (current_rate_bps * elapsed_time_us) / 1e6;
    available_tokens_ += tokens;
}
```

**Example: 64 kbit/s, Packet = 800 bits**

```
t=0:      available_tokens = 0
t=+10ms:  elapsed = 10000us
          tokens += 64000 * 10000 / 1e6 = 640 bits
          can send 800-bit packet? NO (640 < 800)

t=+20ms:  elapsed = 10000us
   tokens += 640 more = 1280 total
          can send 800-bit packet? YES! (1280 >= 800)
          tokens = 1280 - 800 = 480 remaining

t=+30ms:  elapsed = 10000us
          tokens += 640 = 1120 total
          can send 800-bit packet? YES! (1120 >= 800)
          tokens = 1120 - 800 = 320 remaining
```

**Result**: Packets sent at ~20ms intervals (matches 64kbit/s for 800-bit packets)

---

## PART 6: JITTER BUFFER

### File: `include/jitter_buffer.h`

```cpp
class JitterBuffer {
    std::map<uint32_t, Packet> buffer_;     // Map: sorted by seq
    uint32_t next_seq_to_play_;             // Expected next sequence
};
```

**Why Map?**
```
Out-of-order arrival common in jittery networks:
  Packet 3 arrives first
  Packet 1 arrives later (delayed)
  Packet 2 arrives in between

Vector approach:
  [_, _, _, Pkt3]  then shift to insert Pkt1?  O(n) ❌
  
Map approach:
  map[3] = Pkt3
  map[1] = Pkt1  (automatically sorted!)
  map[2] = Pkt2
  →[Pkt1, Pkt2, Pkt3]  ✓ (O(log n))
```

### Method: `addPacket()`

```cpp
void JitterBuffer::addPacket(const Packet& packet) {
    uint32_t seq = packet.sequence_number;
    
    // Check for duplicate
    if (buffer_.find(seq) != buffer_.end()) {
        duplicate_count_++;
        return;  // Skip duplicate
    }
    
    // Detect loss (gap in sequence)
    if (seq > next_seq_to_play_) {
        uint32_t gap = seq - next_seq_to_play_;
        if (gap > 1) {
            lost_packet_count_ += (gap - 1);
        }
    }
    
    // Insert (map maintains sort order automatically)
    buffer_[seq] = packet;
}
```

**Example: Detecting Loss**

```
next_seq_to_play_ = 5
New packet arrives: seq = 9

gap = 9 - 5 = 4

This means: sequences 5, 6, 7, 8, 9
            ✓  ✗  ✗  ✗  ✓  
            
Lost packets: 6, 7, 8 = 3 packets
lost_packet_count_ += (gap - 1) = 3
```

### Method: `getNextPacket()`

```cpp
Packet JitterBuffer::getNextPacket() {
    // Retrieve packet with next_seq_to_play
    auto it = buffer_.find(next_seq_to_play_);
    Packet result = it->second;  // Copy it
    
    buffer_.erase(it);           // Remove from buffer
    next_seq_to_play_++;         // Advance
    
    return result;
}
```

**Playback Loop**:
```cpp
while (receiver.hasPlayoutPacket()) {
    Packet pkt = receiver.getPlayoutPacket();
    // Decode pkt.payload and play audio
}

// If no packet (loss):
if (!receiver.hasPlayoutPacket()) {
    // Generate comfort noise or repeat last frame
    playComfortNoise(20ms);
    receiver.skipPacket();  // Just advance counter
}
```

---

## PART 7: COMPLETE SIMULATION FLOW

### File: `src/main_simulator.cpp`

```cpp
for (uint64_t time_ms = 0; time_ms < SIMULATION_TIME_MS; time_ms += 20) {
    // STEP 1: Update network config based on time
    NetworkConfig config = getNetworkConfigForTime(time_ms);
    network.updateConfig(config);
    
    // STEP 2: Sender generates and sends packet
    auto payload = generateAudioPayload(PAYLOAD_SIZE_BYTES);
    sender.sendPacket(payload, time_ms * 1000);
    
    // (Receiver side happens in background/separate thread in real system)
    
    // STEP 3: Every 100ms, analyze congestion
    if (time_ms % 100 == 0) {
        CongestionSignal signal = receiver.analyzeCongestion();
        
        if (signal != CongestionSignal::NONE) {
            sender.onCongestionFeedback(signal);
        } else {
            sender.onRecoveryFeedback();
        }
        
        logger.logSnapshot(time_ms, sender, receiver, network);
    }
}
```

**Timeline Example (first 500ms)**:

```
t=0ms:     Phase = Baseline
           Sender: rate=64kbps
           Network: loss=0%, delay=10ms, jitter=5ms
           ECS: NO_CONGESTION

t=20ms:    Sender: sends Pkt 0
t=30ms:    Receiver: gets Pkt 0 (20ms later)
t=40ms:    Sender: sends Pkt 1
t=60ms:    Receiver: gets Pkt 1 (20ms later)
           RTTTracker adds sample: 30ms (30000us)

t=100ms:   [FEEDBACK] Analyze congestion
           RTT avg = 30ms, trend = STABLE
           ECS signal = NONE
           Sender rate unchanged: 64kbps
           
t=120ms:   Phase = Baseline (still)
t=140ms:   Similar pattern...

t=200ms:   [FEEDBACK] Analyze
           RTT avg = 30ms, trend = STABLE
           ECS signal = NONE

t=300ms:   Phase = Building (time_ms >= 200)
           Network now: loss=2%, delay=50ms, jitter=15ms

t=320ms:   Packets take ~50ms (not 10ms!)
           RTT samples increase

t=400ms:   [FEEDBACK] Analyze
           RTT avg = 55ms (much higher!)
           RTT trend = INCREASING (last half >> first half)
           Confidence = 0.6 (trend) → BUILDING
           Signal = CONGESTION_BUILDING
           
           Sender: onCongestionFeedback(BUILDING)
           Rate: 64 * 0.9 = 57.6 kbps
           
t=500ms:   [FEEDBACK] Analyze
           RTT still increasing
           Confidence = 0.7 (trend + sustained)
           Signal = CONGESTION_BUILDING
           Rate: 57.6 * 0.9 = 51.84 kbps
```

---

## Key Takeaways

| Concept | Why | How |
|---------|-----|-----|
| **Microseconds** | Network effects visible at µs scale | uint64_t for exact arithmetic |
| **Sliding Window** | Forget old history, focus recent | Deque: O(1) add/remove |
| **Token Bucket** | Smooth traffic rate | Accumulate tokens, consume on send |
| **AIMD** | Fair, proven rate control | Multiplicative decrease, add increase |
| **Hysteresis** | Prevent flappingadaptation | Keep status until trend reverses |
| **Map for Jitter Buffer** | Handle out-of-order packets | O(log n) insertion, auto-sorted |
| **Multi-evidence ECS** | Avoid false positives | Combine trend + spike + history |
| **Confidence Accumulation** | Graduated responses | >0.5 = building, >0.9 = imminent |

---

This completes the walkthrough. Every line of code in the implementation files follows these principles.

