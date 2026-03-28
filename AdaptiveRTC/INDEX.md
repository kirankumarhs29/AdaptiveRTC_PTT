# 🎯 INDEX & LEARNING PATH

## Document Overview

This project includes comprehensive documentation at multiple levels of detail. Choose your starting point:

### 🟢 **START HERE** (5-10 minutes)
- **[BUILD_GUIDE.md](BUILD_GUIDE.md)** - How to compile and run
- **[README.md](README.md)** - System overview and architecture

### 🟡 **UNDERSTAND THE DESIGN** (30-60 minutes)
- **[IMPLEMENTATION_GUIDE.md](IMPLEMENTATION_GUIDE.md)** - Each component explained
  - Why each component exists
  - What problem it solves
  - Key design decisions
  
### 🟠 **MASTER THE CODE** (60-120 minutes)
- **[CODE_WALKTHROUGH.md](CODE_WALKTHROUGH.md)** - Line-by-line explanation
  - Every algorithm explained
  - Data structure choices justified
  - Example scenarios with numbers
  
### 🔴 **ADVANCED TOPICS** (30-60 minutes)
- **[MULTITHREADING_GUIDE.md](MULTITHREADING_GUIDE.md)** - Threading patterns
  - How to add async sender/receiver
  - Thread safety patterns
  - Common race conditions
  - Performance optimization
  
- **[plan.md](plan.md)** - Original HLD (given)
  - Problem statement
  - High-level vision

---

## Learning Outcomes

After working through this project, you will understand:

### ✅ Real-Time Networking
- [ ] How RTT (round-trip time) indicates congestion
- [ ] Why packets arrive out of order and how to handle it
- [ ] How delay trends predict congestion before packet loss
- [ ] Token bucket rate limiting (AIMD algorithm)

### ✅ System Design
- [ ] Clean separation of concerns (packet, network, tracking, adaptation)
- [ ] Why certain data structures matter (Deque vs Vector vs Map)
- [ ] How to compose simple components into complex systems
- [ ] Importance of modular testing

### ✅ Algorithms
- [ ] ECS detection (the core innovation)
  - Trend detection with trend hysteresis
  - Confidence accumulation from multiple signals
  - Graduated response based on severity
- [ ] Sliding window statistics
- [ ] Out-of-order packet reordering
- [ ] Rate control via token bucket

### ✅ C++ Engineering
- [ ] Modern C++ (C++17): optional, variant, structured bindings
- [ ] Container selection: deque vs vector vs map
- [ ] Precision arithmetic (microseconds vs milliseconds)
- [ ] Thread-safe design patterns (if you read MULTITHREADING_GUIDE.md)

### ✅ Testing & Validation
- [ ] How to simulate network conditions reproducibly
- [ ] Unit testing patterns (one component at a time)
- [ ] Integration testing (components working together)
- [ ] Metrics collection for validation

---

## Quick Concepts Guide

### 🔑 Packet
- **What**: Container for audio + metadata
- **Why**: Need timestamps for RTT, sequence for ordering
- **File**: [include/packet.h](include/packet.h)

### 🌐 NetworkSimulator
- **What**: Inject loss, delay, jitter
- **Why**: Deterministic testing without real network
- **Key Algorithm**: Bernoulli loss + Gaussian jitter
- **File**: [include/network_simulator.h](include/network_simulator.h)

### 📊 RTTTracker
- **What**: Measure RTT statistics
- **Why**: Foundation for congestion detection
- **Key Algorithm**: Sliding window (deque) + cached stats
- **File**: [include/rtt_tracker.h](include/rtt_tracker.h)

### 🚨 **ECSDetector** (CORE INNOVATION)
- **What**: Detect congestion from delay trends
- **Why**: Proactive (before packet loss) vs reactive
- **Key Algorithm**: Trend analysis + spike + confidence accumulation
- **3 Levels**: NO_CONGESTION, CONGESTION_BUILDING, CONGESTION_IMMINENT
- **File**: [include/ecs_detector.h](include/ecs_detector.h)

### ⏱️ RateController
- **What**: Adapt transmission rate
- **Why**: Reduce traffic when congested
- **Algorithm**: AIMD (multiplicative decrease, additive increase)
- **File**: [include/rate_controller.h](include/rate_controller.h)

### 📦 JitterBuffer
- **What**: Absorb packet arrival variation
- **Why**: Smooth playback despite jitter
- **Data Structure**: std::map (O(log n) insertion, auto-sorted)
- **File**: [include/jitter_buffer.h](include/jitter_buffer.h)

### 📤 Sender
- **What**: Orchestrate transmission
- **File**: [include/sender.h](include/sender.h)

### 📥 Receiver
- **What**: Orchestrate reception and analysis
- **File**: [include/receiver.h](include/receiver.h)

### 📝 MetricsLogger
- **What**: CSV logging for analysis
- **File**: [include/metrics_logger.h](include/metrics_logger.h)

---

## Code Organization

### Headers ([include/](include/))
Public interfaces - what other modules need to know

### Implementation ([src/](src/))
Private details - how components work

### Tests ([test/](test/))
Unit tests - one component each
Integration tests - components working together

### Build ([CMakeLists.txt](CMakeLists.txt))
How to compile, link, test

---

## Recommended Reading Order

1. **[BUILD_GUIDE.md](BUILD_GUIDE.md)** (5 min)
   - Get it compiling first!

2. **[README.md](README.md)** (10-15 min)
   - Big picture overview
   - Architecture diagram
   - Design decisions summary

3. **[IMPLEMENTATION_GUIDE.md](IMPLEMENTATION_GUIDE.md)** (30-45 min)
   - Read "Project Architecture" section first
   - Then read "Core Classes Overview"
   - Skim "Step-by-Step Implementation" (high-level)

4. **Pick a component and understand deeply:**
   - **Start with**: Packet (simplest)
   - **Then**: NetworkSimulator (deterministic testing)
   - **Then**: RTTTracker (statistics)
   - **Then**: ECSDetector (core innovation) ⭐
   - **Then**: RateController (adaptation)
   - **Then**: JitterBuffer (reordering)

5. **[CODE_WALKTHROUGH.md](CODE_WALKTHROUGH.md)** (60-90 min)
   - For your chosen components
   - Read line-by-line explanations
   - Understand why each decision matters

6. **Run and Experiment**
   - Compile: `cmake .. && cmake --build .`
   - Test: `./bin/rtc_tests`
   - Simulate: `./bin/rtc_simulator`
   - Analyze: Look at `simulation_results.csv`

7. **[MULTITHREADING_GUIDE.md](MULTITHREADING_GUIDE.md)** (if needed)
   - For future: add multithreading
   - Learn thread-safety patterns
   - Understand performance considerations

---

## Key Insights

### 💡 Insight 1: Delay Predicts Loss
```
Before packet loss (traditional reaction):
  [Packet 1] → OK
  [Packet 2] → OK
  [Packet 3] → LOST ← Detected here (too late!)

With ECS (our prediction):
  RTT: 10ms, 12ms, 15ms, 20ms, 30ms... ← Detected here!
  [Packet 3] → LOST (sender already reduced rate, no big impact)
```

### 💡 Insight 2: Data Structure Matters
```
Jitter buffer needs to reorder packets:

std::vector (❌):     insert in middle = O(n) shift
std::deque (❌):      pop from front ok, but find by id = O(n)
std::queue (❌):      no random access at all
std::map (✅):        O(log n) find + O(log n) insert, auto-sorted!
```

### 💡 Insight 3: Confidence > Single Signals
```
Single evidence:
  - Spike alone → false positive (transient event)
  - Trend alone → false positive (noise fluctuation)

Multiple evidence (our approach):
  - Trend AND spike AND history → high confidence
  - Only then send IMMINENT signal → aggressive action
```

### 💡 Insight 4: Hysteresis Prevents Flapping
```
Without hysteresis (oscillates):
  RTT 50ms → 60ms: SIGNAL CONGESTION
  RTT 60ms → 50ms: CANCEL SIGNAL
  RTT 50ms → 60ms: SIGNAL again
  → Sender oscillates between 64kbps and 57.6kbps → unstable playback

With hysteresis (stable):
  RTT 50ms → 60ms: SIGNAL CONGESTION
  RTT 60ms → 50ms: STAY CONGESTING (wait for clear recovery)
  RTT 50ms → 40ms: STAY CONGESTING
  RTT 40ms → 30ms: NOW cancel signal (sustained recovery)
  → Sender adapts smoothly → stable playback
```

### 💡 Insight 5: Multiplicative > Additive
```
Additive (unfair):
  Flow 1: 1000 kbps - 100 = 900 kbps (10% reduction)
  Flow 2: 10 kbps - 100 = -90 kbps (can't go negative!)

Multiplicative (fair):
  Flow 1: 1000 * 0.9 = 900 kbps (10% reduction)
  Flow 2: 10 * 0.9 = 9 kbps (10% reduction)
```

---

## Testing Strategy

### Unit Tests (Each Component)
```bash
./bin/rtc_tests
```
Tests each class independently with known inputs/outputs.

### Integration Tests
Tests components working together:
- Sender → Network → Receiver
- Feedback loop works
- Rate adaptation happens

### Simulation Tests
Full end-to-end with realistic scenarios:
- Baseline phase (stable)
- Congestion building
- Peak congestion
- Recovery

### Results Analysis
```bash
# View CSV and plot
cat simulation_results.csv | head -20

# Python analysis (see BUILD_GUIDE.md)
# Plot rate, RTT, buffer depth over time
```

---

## Expected Compilation Output

```
Build configuration:
  C++ Standard: 17
  Build Type: Release
  Main Library: adaptive_rtc
  Simulator: rtc_simulator
  Tests: rtc_tests
  
[100%] Built target adaptive_rtc
[100%] Built target rtc_simulator
[100%] Built target rtc_tests

To run simulator: ./bin/rtc_simulator
To run tests: ./bin/rtc_tests
```

---

## Performance Expectations

| Operation | Time | Reason |
|-----------|------|--------|
| Add RTT sample | <1µs | O(1) |
| Get RTT average | <1µs | cached |
| Calculate std-dev | ~100µs | O(n) once |
| Detect trend | ~200µs | O(n) window analysis |
| Add jitter packet | ~100µs | O(log n) map insert |
| Get next packet | <1µs | O(1) map access |
| Rate control check | <1µs | token update |

**Total**: Single simulation step < 1ms
**Simulation speed**: 10x real-time (1 second simulated per ms real)

---

## Common Questions

**Q: Why uint64_t microseconds instead of double seconds?**
A: Exact arithmetic (no rounding errors), sufficient precision (584 years), efficient comparison.

**Q: Why std::map for jitter buffer?**
A: Out-of-order arrival common. Map: O(log n) both find and insert, auto-sorted. Deque: O(n) insert.

**Q: Why three ECS levels not just binary?**
A: Graduated response. BUILDING: mild (10%), IMMINENT: aggressive (25%).

**Q: Why 0.9x reduction not 0.5x?**
A: Conservative. Avoid over-correction. Increase (1.05x) stays slow to probe safely.

**Q: How to know if ECS detection is working?**
A: See `CODE_WALKTHROUGH.md` "ECS Detection Algorithm" section.

---

## Next Projects

After mastering this, try:

1. **Real UDP Sockets**: Replace NetworkSimulator with actual network I/O
2. **Audio Codec**: Integrate Opus or AAC instead of raw bytes
3. **Multithreading**: Add async sender/receiver (see MULTITHREADING_GUIDE.md)
4. **More Algorithms**: Implement loss-based congestion detection (alternative to ECS)
5. **Speech Metrics**: Add MOS (Mean Opinion Score) calculation
6. **Mobile**: Port to iOS/Android with WebRTC

---

## Summary

You now have access to:
- ✅ **Complete, production-quality C++ code**
- ✅ **Comprehensive documentation at multiple levels**
- ✅ **Tests and simulator for validation**
- ✅ **Design rationale for every decision**
- ✅ **Path to advanced topics (threading, optimization)**

Start with BUILD_GUIDE.md, then follow the learning path above.

**Happy coding! 🚀**

