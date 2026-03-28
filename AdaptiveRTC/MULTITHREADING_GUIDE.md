# Multithreading & Advanced Topics

This guide covers:
1. How to add multithreading to the simulation
2. Synchronization patterns
3. Producer-consumer model
4. Common race conditions and how to avoid them
5. Performance optimization techniques

## 1. MULTITHREADING ARCHITECTURE

### Current Design: Single-Threaded

```cpp
// Synchronous simulation loop
for (time_ms = 0; time_ms < MAX_TIME; time_ms++) {
    sender.sendPacket();
    receiver.receivePacket();  // Happens immediately
    analyzer.detectCongestion();
    sender.applyFeedback();
}
```

**Problem**: Unrealistic. In real systems:
- Sender runs on device A
- Network operates independently
- Receiver runs on device B
- Feedback travels back over network

### Proposed Threading Model

```
┌─────────────────┐
│  Sender Thread  │
│                 │
│ while (running) {
│   pkt = generate()
│   send_queue.push(pkt)
│ }
└────────┬────────┘
         │ (packets)
         ▼
┌─────────────────────────┐
│  Network Thread         │
│ (NetworkSimulator)      │
│                         │
│ while (running) {
│   pkt = send_queue.pop()
│   pkt = simulate(pkt)
│   recv_queue.push(pkt)
│ }
└────────┬────────────────┘
         │ (packets)
         ▼
┌─────────────────┐
│ Receiver Thread │
│                 │
│ while (running) {
│   pkt = recv_queue.pop()
│   analyze(pkt)
│   feedback = detect()
│   feedback_queue.push()
│ }
└────────┬────────┘
         │ (feedback)
         ▼
┌─────────────────────────────┐
│  Sender Feedback Thread     │
│  (listening for feedback)   │
│                             │
│  while (running) {
│    feedback = feedback_queue.pop()
│    adapt_rate(feedback)
│  }
└─────────────────────────────┘
```

## 2. THREAD-SAFE QUEUE IMPLEMENTATION

### Problem: Data Race

```cpp
// NOT THREAD-SAFE!
std::queue<Packet> packet_queue;

// Sender thread:
packet_queue.push(pkt);

// Receiver thread:
if (!packet_queue.empty()) {
    pkt = packet_queue.front();
    packet_queue.pop();
}
```

**Race condition**:
```
Timeline:
t=1: Sender checks empty? NO
t=2: Receiver checks empty? NO (same pkt)
t=3: Sender pushes new pkt
t=4: Receiver pops
t=5: Receiver pops again (DOUBLE POP → CRASH!)
```

### Solution: Thread-Safe Queue

```cpp
template <typename T>
class ThreadSafeQueue {
public:
    // Add item to queue
    void push(const T& item) {
        std::unique_lock<std::mutex> lock(mutex_);
        queue_.push(item);
        cv_.notify_one();  // Wake up waiting consumer
    }
    
    // Remove item from queue (block if empty)
    T pop() {
        std::unique_lock<std::mutex> lock(mutex_);
        
        // Wait until queue has item
        cv_.wait(lock, [this] { return !queue_.empty(); });
        
        T item = queue_.front();
        queue_.pop();
        return item;
    }
    
    // Try to pop without blocking
    std::optional<T> try_pop() {
        std::unique_lock<std::mutex> lock(mutex_);
        
        if (queue_.empty()) {
            return std::nullopt;
        }
        
        T item = queue_.front();
        queue_.pop();
        return item;
    }
    
    bool empty() const {
        std::unique_lock<std::mutex> lock(mutex_);
        return queue_.empty();
    }
    
    size_t size() const {
        std::unique_lock<std::mutex> lock(mutex_);
        return queue_.size();
    }

private:
    mutable std::mutex mutex_;
    std::condition_variable cv_;
    std::queue<T> queue_;
};
```

**How It Works**:

```
Sender Thread:              Receiver Thread:
push(pkt1)                  
  lock(mutex)               
  queue_.push(pkt1)         
  notify_one()              
    ▼ wakes up              
  unlock                    
                            pop() blocks...
                            wait(mutex, condition)
                              ◄─── woken by notify
                            lock(mutex)
                            queue not empty? YES
                            pkt1 = queue.front()
                            queue.pop()
                            unlock
                            return pkt1
```

**Why condition_variable?**
- Without it: receiver thread would busy-wait (CPU 100%)
- With it: receiver thread sleeps until packet arrives
- Much more efficient!

## 3. SENDER THREAD IMPLEMENTATION

```cpp
class SenderThread {
public:
    SenderThread(NetworkSimulator* network)
        : network_(network),
          running_(false),
          thread_(&SenderThread::run, this)
    {
    }
    
    ~SenderThread() {
        stop();
        // Join thread before destr...
        uction
        if (thread_.joinable()) {
            thread_.join();
        }
    }
    
    void start() {
        running_ = true;
    }
    
    void stop() {
        running_ = false;
    }

private:
    void run() {
        Sender sender(64000, network_);
        uint64_t sequence = 0;
        uint64_t last_send_time_us = 0;
        
        while (running_) {
            uint64_t now_us = getCurrentTimeUS();
            uint64_t elapsed_us = now_us - last_send_time_us;
            
            // Generate 20ms of audio (160 bytes at 64kbit/s)
            std::vector<uint8_t> payload(160);
            
            // Try to send (rate-limited)
            if (sender.sendPacket(payload, now_us)) {
                send_queue_.push(Packet(sequence, payload, now_us));
                sequence++;
                last_send_time_us = now_us;
            } else {
                // Rate limited, wait a bit
                std::this_thread::sleep_for(
                    std::chrono::microseconds(100));
            }
        }
    }
    
    bool running_;
    NetworkSimulator* network_;
    std::thread thread_;
    ThreadSafeQueue<Packet> send_queue_;
};
```

**Thread Lifecycle**:
```
1. Constructor: Create thread but don't start
2. start(): Set running_ = true, thread starts executing run()
3. run() loop: Generate packets while running_ = true
4. stop(): Set running_ = false
5. Destructor: Join thread (wait for completion), then cleanup
```

## 4. RECEIVER THREAD IMPLEMENTATION

```cpp
class ReceiverThread {
public:
    ReceiverThread()
        : running_(false),
          thread_(&ReceiverThread::run, this)
    {
    }
    
    void start(ThreadSafeQueue<Packet>* send_queue) {
        send_queue_ = send_queue;
        running_ = true;
    }
    
    void stop() {
        running_ = false;
    }

private:
    void run() {
        Receiver receiver;
        uint64_t last_feedback_time_us = 0;
        
        while (running_) {
            // Try to get packet from network
            // (In real system: from socket, here simulated)
            
            uint64_t now_us = getCurrentTimeUS();
            
            // Check for packet in send queue
            // (This is simplified; real system would have recv socket)
            auto pkt = send_queue_->try_pop();
            
            if (pkt) {
                // Simulate network (simplification)
                // In real system: network simulator is separate
                receiver.receivePacket(*pkt);
            }
            
            // Every 100ms: analyze and generate feedback
            if (now_us - last_feedback_time_us > 100000) {
                auto signal = receiver.analyzeCongestion();
                feedback_queue_.push(signal);
                last_feedback_time_us = now_us;
            }
            
            std::this_thread::sleep_for(
                std::chrono::microseconds(1000));  // Don't busy-wait
        }
    }
    
    bool running_;
    ThreadSafeQueue<Packet>* send_queue_;
    ThreadSafeQueue<CongestionSignal> feedback_queue_;
    std::thread thread_;
};
```

## 5. SYNCHRONIZATION PATTERNS

### Pattern 1: Mutex-Protected Shared State

```cpp
class RateControllerThreadSafe {
public:
    void setRate(uint32_t rate) {
        std::unique_lock<std::mutex> lock(mutex_);
        rate_ = rate;
    }
    
    uint32_t getRate() const {
        std::unique_lock<std::mutex> lock(mutex_);
        return rate_;
    }

private:
    mutable std::mutex mutex_;
    uint32_t rate_ = 64000;
};
```

**When to use**:
- Simple shared state
- Infrequent access
- No waiting needed

### Pattern 2: Read-Write Lock

```cpp
class RTTTrackerThreadSafe {
public:
    void addSample(uint64_t rtt) {
        std::unique_lock<std::shared_mutex> lock(mutex_);
        tracker_.addSample(rtt);
    }
    
    uint64_t getAverageRTT() const {
        std::shared_lock<std::shared_mutex> lock(mutex_);
        return tracker_.getAverageRTT();
    }

private:
    mutable std::shared_mutex mutex_;
    RTTTracker tracker_;
};
```

**Benefit**: Multiple readers can access simultaneously
- Writer lock: exclusive access (for addSample)
- Reader lock: shared access (for queries)

### Pattern 3: Atomic For Simple Types

```cpp
class SimpleSharedValue {
public:
    void setValue(uint32_t v) {
        value_.store(v, std::memory_order_release);
    }
    
    uint32_t getValue() const {
        return value_.load(std::memory_order_acquire);
    }

private:
    std::atomic<uint32_t> value_{0};
};
```

**Benefit**: No mutex overhead for simple types
- Lock-free on most platforms
- Use for simple counters, flags

### Pattern 4: Future for Async Results

```cpp
// Compute something in background thread
std::future<uint64_t> computeStatistics() {
    return std::async(std::launch::async, [this] {
        uint64_t sum = 0;
        for (int i = 0; i < 1000000; ++i) {
            sum += computeOnce();
        }
        return sum;
    });
}

// Main thread waits for result
auto future = computeStatistics();
// ... do other work ...
uint64_t result = future.get();  // Blocks until ready
```

## 6. COMMON RACE CONDITIONS & FIXES

### Race 1: Double-Check Locking (BROKEN)

```cpp
// WRONG! Don't do this!
if (!initialized_) {           // Race 1: read without lock
    std::lock_guard<std::mutex> lock(mutex_);
    if (!initialized_) {       // Race 2: can still race
        initialize();          // between first check & lock
        initialized_ = true;
    }
}
```

**Fix**: Check only inside lock
```cpp
// CORRECT
std::lock_guard<std::mutex> lock(mutex_);
if (!initialized_) {
    initialize();
    initialized_ = true;
}
```

Or use call_once:
```cpp
std::once_flag init_flag;

void ensureInitialized() {
    std::call_once(init_flag, [this] {
        initialize();
    });
}
```

### Race 2: Lost Update

```cpp
// Thread A:
rate_ = 64000;
rate_ = rate_ * 0.9;  // 3 steps:
                       // 1. load rate_
                       // 2. multiply
                       // 3. store

// Thread B (same time):
rate_ = 64000;
rate_ = rate_ * 0.1;  // 3 steps:
                       // 1. load rate_
                       // 2. multiply
                       // 3. store

// Timeline:
// A.load: rate=64000
// B.load: rate=64000 (same value, races!)
// A.compute: 64000*0.9 = 57600
// A.store: rate_=57600
// B.compute: 64000*0.1 = 6400
// B.store: rate_=6400 (overwrites A's value!)

// Result: rate=6400 (lost A's update!)
```

**Fix**: Perform entire operation under lock
```cpp
{
    std::lock_guard<std::mutex> lock(mutex_);
    rate_ = rate_ * 0.9;  // Atomic: no race
}
```

### Race 3: Iterator Invalidation

```cpp
// WRONG!
std::vector<Packet> buffer;

// Thread A:
for (auto& pkt : buffer) {
    process(pkt);
}

// Thread B (simultaneously):
buffer.erase(buffer.begin());  // Invalidates iterator!
// Thread A's iterator is now dangling → CRASH!
```

**Fix**: Lock during iteration
```cpp
{
    std::lock_guard<std::mutex> lock(vector_mutex_);
    for (auto& pkt : buffer) {
        process(pkt);
    }
}
```

Or use thread-safe container (std::vector is NOT thread-safe):
```cpp
ThreadSafeQueue<Packet> buffer;  // Safe!

// Separate threads can push/pop safely
```

## 7. PERFORMANCE OPTIMIZATION

### Optimization 1: Reduce Lock Contention

**Before**:
```cpp
// Every addSample() holds lock for O(n) calculation
void RTTTracker::addSample(uint64_t rtt) {
    std::lock_guard<std::mutex> lock(mutex_);
    samples_.push_back(rtt);
    
    // This is slow and holds lock!
    double stddev = calculateStdDev();  // O(n)
    cache_stddev_ = stddev;
}
```

**After**:
```cpp
// Lazy evaluation: don't compute in addSample
void RTTTracker::addSample(uint64_t rtt) {
    std::lock_guard<std::mutex> lock(mutex_);
    samples_.push_back(rtt);
    stats_dirty_ = true;  // Just mark
}

// Compute only when queried
double RTTTracker::getStdDev() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (stats_dirty_) {
        cache_stddev_ = calculateStdDev();  // O(n), only sometimes
        stats_dirty_ = false;
    }
    return cache_stddev_;
}
```

**Result**: Lock held for ~0.1ms instead of ~1ms

### Optimization 2: Read-Write Locks

```cpp
struct RTTStats {
    std::shared_mutex mutex_;
    uint64_t avg_rtt_;
};

// Multiple readers (no lock contention):
uint64_t rtt1 = stats.getAverage();  // Reader lock
uint64_t rtt2 = stats.getAverage();  // Reader lock (concurrent!)

// Single writer:
stats.addSample(50000);  // Writer lock (exclusive)
```

### Optimization 3: Lock-Free Data Structures

For simple types, use atomic:
```cpp
std::atomic<uint64_t> packet_count_(0);

// Thread A:
packet_count_.fetch_add(1, std::memory_order_relaxed);  // No mutex!

// Thread B:
uint64_t count = packet_count_.load();  // No mutex!
```

### Optimization 4: Thread Affinity

Pin threads to CPU cores to avoid cache misses:
```cpp
void setThreadAffinity(std::thread& t, int core) {
    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);
    CPU_SET(core, &cpuset);
    pthread_setaffinity_np(t.native_handle(), sizeof(cpu_set_t), &cpuset);
}

// In main:
std::thread sender_thread(...);
std::thread receiver_thread(...);

setThreadAffinity(sender_thread, 0);    // Core 0
setThreadAffinity(receiver_thread, 1);  // Core 1
```

## 8. COMPLETE MULTITHREADED EXAMPLE

```cpp
int main() {
    // Create thread-safe components
    SenderThread sender;
    ReceiverThread receiver;
    
    // Start threads
    sender.start();
    receiver.start(&sender.getPacketQueue());
    
    // Simulation runs for 10 seconds
    std::this_thread::sleep_for(std::chrono::seconds(10));
    
    // Stop threads
    sender.stop();
    receiver.stop();
    
    // Threads automatically join in destructors
    return 0;
}
```

**Expected Output**:
```
[Sender]   Packet 0 sent at t=0ms
[Receiver] Packet 0 received at t=10ms
[Sender]   Packet 1 sent at t=20ms
[Receiver] Packet 1 received at t=30ms
          ...
[Receiver] Analyzing congestion at t=100ms
[Sender]   Rate reduced due to congestion signal
```

---

**Key Takeaways**:
1. Use ThreadSafeQueue for thread communication
2. Protect shared state with mutex or atomic
3. Avoid lock contention (lazy evaluation, read locks)
4. Use condition_variable to avoid busy-waiting
5. Always join threads before destruction
6. Test with ThreadSanitizer to find races early

