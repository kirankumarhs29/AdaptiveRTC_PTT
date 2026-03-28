// ============================================================================
// rtt_tracker.h - Round-Trip Time Measurement and Statistics
// ============================================================================
//
// PURPOSE:
// Tracks RTT samples from received packets and computes statistics.
// These statistics are inputs to ECSDetector for congestion prediction.
//
// RESPONSIBILITY:
// - Collect RTT samples in fixed-size sliding window
// - Compute: average, min, max, std-dev
// - Identify trend direction (increasing vs decreasing)
// - Detect sudden spikes
//
// WHY THIS DESIGN:
// - Sliding window (last 50 packets) avoids memory explosion
// - Deque for O(1) add/remove from both ends
// - Statistics updated incrementally (not recalculated each time)
//
// USAGE EXAMPLE:
//   RTTTracker tracker(50);  // Window size = 50 packets
//   tracker.addSample(45000);  // RTT = 45ms
//   tracker.addSample(48000);  // RTT = 48ms
//   
//   auto avg = tracker.getAverageRTT();      // 46500 microseconds
//   auto trend = tracker.getTrendDirection();  // INCREASING (values going up)
//
// ============================================================================

#pragma once

#include <cstdint>
#include <deque>
#include <vector>

namespace adaptive_rtc {

// ============================================================================
// RTT Trend Direction
// ============================================================================
enum class RTTTrend {
    UNDEFINED,      // Not enough samples
    INCREASING,     // RTT going up (congestion likely)
    DECREASING,     // RTT going down (network improving)
    STABLE          // RTT relatively flat
};

// ============================================================================
// RTTTracker Class
// ============================================================================
class RTTTracker {
public:
    /// Constructor
    ///
    /// Args:
    ///   window_size - number of recent RTT samples to keep
    ///   Default: 50 packets
    ///   Rationale: 50 packets = 1 second at 50 pps, captures short-term trends
    explicit RTTTracker(size_t window_size = 50);
    
    // ========================================================================
    // SAMPLE COLLECTION
    // ========================================================================
    
    /// Add a new RTT measurement
    ///
    /// Args:
    ///   rtt_us - round-trip time in microseconds
    ///
    /// Behavior:
    /// - Adds to sliding window
    /// - If window full, removes oldest sample
    /// - Updates statistics incrementally
    ///
    /// Idempotent: safe to call for every received packet
    void addSample(uint64_t rtt_us);
    
    /// Clear all samples (reset tracker)
    void reset();
    
    // ========================================================================
    // STATISTICS QUERIES
    // ========================================================================
    
    /// Average RTT across all samples in window
    ///
    /// Returns: microseconds
    uint64_t getAverageRTT() const;
    
    /// Minimum RTT in sliding window
    /// (baseline network latency)
    uint64_t getMinRTT() const;
    
    /// Maximum RTT in sliding window
    /// (worst-case latency we've seen)
    uint64_t getMaxRTT() const;
    
    /// Standard deviation of RTT
    /// (measure of jitter / variability)
    /// 
    /// Returns: microseconds
    /// Formula: sqrt(mean(rtt_i - avg)^2)
    double getStdDevRTT() const;
    
    /// Median RTT (robust to outliers)
    ///
    /// Returns: microseconds
    /// Usage: more stable than mean for trend detection
    uint64_t getMedianRTT() const;
    
    /// Number of samples in current window
    size_t getSampleCount() const { return samples_.size(); }
    
    // ========================================================================
    // TREND ANALYSIS
    // ========================================================================
    
    /// Determine if RTT is increasing, decreasing, or stable
    ///
    /// Returns: RTTTrend enum
    /// 
    /// Algorithm:
    /// Compare recent samples (last half) vs older samples (first half)
    /// If recent_avg > old_avg + noise_margin: INCREASING
    /// If recent_avg < old_avg - noise_margin: DECREASING
    /// Otherwise: STABLE
    RTTTrend getTrendDirection() const;
    
    /// Check if RTT just spiked (sudden large increase)
    ///
    /// Returns: true if latest sample > (avg + 2*stddev)
    /// Usage: detect transient congestion events
    bool isRTTSpiking() const;
    
    /// Get latest RTT sample
    ///
    /// Returns: most recent RTT measurement
    /// Precondition: getSampleCount() > 0
    uint64_t getLatestRTT() const;
    
    // ========================================================================
    // DEBUGGING
    // ========================================================================
    
    /// Get all samples for logging (debugging)
    const std::deque<uint64_t>& getAllSamples() const { return samples_; }

private:
    std::deque<uint64_t> samples_;              // Sliding window of RTT values
    size_t max_window_size_;                    // Maximum samples to keep
    
    // Cached statistics (updated on each addSample)
    uint64_t cached_sum_us_ = 0;               // Sum for fast average calc
    mutable uint64_t cached_avg_us_ = 0;       // Cached average
    mutable double cached_stddev_us_ = 0.0;    // Cached std-dev
    mutable bool stats_dirty_ = true;          // Need recalculation?
    
    /// Recalculate statistics (called when stats_dirty_ == true)
    void updateStatistics() const;
};

}  // namespace adaptive_rtc
