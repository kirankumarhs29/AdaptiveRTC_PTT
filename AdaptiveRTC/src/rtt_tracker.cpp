// ============================================================================
// rtt_tracker.cpp - Implementation
// ============================================================================

#include "rtt_tracker.h"
#include <algorithm>
#include <cmath>
#include <numeric>

namespace adaptive_rtc {

// ============================================================================
// Constructor
// ============================================================================
RTTTracker::RTTTracker(size_t window_size)
    : max_window_size_(window_size)
{
}

// ============================================================================
// addSample - Add new RTT measurement
// ============================================================================
void RTTTracker::addSample(uint64_t rtt_us) {
    // Keep sliding window at fixed size (FIFO)
    if (samples_.size() >= max_window_size_) {
        cached_sum_us_ -= samples_.front();  // Remove oldest from sum
        samples_.pop_front();
    }
    
    // Add new sample
    samples_.push_back(rtt_us);
    cached_sum_us_ += rtt_us;
    
    // Mark statistics as stale (recalculate on next query)
    stats_dirty_ = true;
}

// ============================================================================
// reset - Clear all samples
// ============================================================================
void RTTTracker::reset() {
    samples_.clear();
    cached_sum_us_ = 0;
    cached_avg_us_ = 0;
    cached_stddev_us_ = 0.0;
    stats_dirty_ = true;
}

// ============================================================================
// Statistics Query Methods
// ============================================================================

uint64_t RTTTracker::getAverageRTT() const {
    if (samples_.empty()) return 0;
    return cached_sum_us_ / samples_.size();  // Integer division
}

uint64_t RTTTracker::getMinRTT() const {
    if (samples_.empty()) return 0;
    return *std::min_element(samples_.begin(), samples_.end());
}

uint64_t RTTTracker::getMaxRTT() const {
    if (samples_.empty()) return 0;
    return *std::max_element(samples_.begin(), samples_.end());
}

double RTTTracker::getStdDevRTT() const {
    if (samples_.size() < 2) return 0.0;
    
    if (stats_dirty_) {
        updateStatistics();
    }
    return cached_stddev_us_;
}

uint64_t RTTTracker::getMedianRTT() const {
    if (samples_.empty()) return 0;
    
    // Make copy and sort (don't modify original)
    std::deque<uint64_t> sorted = samples_;
    std::sort(sorted.begin(), sorted.end());
    
    // Return middle value (or average of two middle for even size)
    if (sorted.size() % 2 == 1) {
        return sorted[sorted.size() / 2];
    } else {
        size_t mid = sorted.size() / 2;
        return (sorted[mid - 1] + sorted[mid]) / 2;
    }
}

uint64_t RTTTracker::getLatestRTT() const {
    if (samples_.empty()) return 0;
    return samples_.back();
}

// ============================================================================
// Trend Analysis
// ============================================================================

RTTTrend RTTTracker::getTrendDirection() const {
    // Need at least 10 samples to detect trend reliably
    if (samples_.size() < 10) {
        return RTTTrend::UNDEFINED;
    }
    
    // Split window into two halves
    size_t mid = samples_.size() / 2;
    
    // Calculate average of first half (older samples)
    uint64_t old_sum = 0;
    for (size_t i = 0; i < mid; ++i) {
        old_sum += samples_[i];
    }
    uint64_t old_avg = old_sum / mid;
    
    // Calculate average of second half (recent samples)
    uint64_t recent_sum = 0;
    for (size_t i = mid; i < samples_.size(); ++i) {
        recent_sum += samples_[i];
    }
    uint64_t recent_avg = recent_sum / (samples_.size() - mid);
    
    // Compute variance for noise threshold
    // If trend is smaller than noise, report STABLE
    double stddev = getStdDevRTT();
    double noise_margin = 1.5 * stddev;  // Allow 1.5σ deviation before classifying trend
    
    // LOGIC:
    // If recent significantly larger than old: INCREASING
    // If recent significantly smaller than old: DECREASING
    // Otherwise: STABLE (within noise bounds)
    
    if (recent_avg > old_avg + noise_margin) {
        return RTTTrend::INCREASING;
    } else if (recent_avg + noise_margin < old_avg) {
        return RTTTrend::DECREASING;
    } else {
        return RTTTrend::STABLE;
    }
}

bool RTTTracker::isRTTSpiking() const {
    if (samples_.empty()) return false;
    
    uint64_t latest = getLatestRTT();
    uint64_t avg = getAverageRTT();
    double stddev = getStdDevRTT();
    
    // Spike = latest > avg + 2*stddev
    // 2σ rule: >95% of normal values fall within avg ± 2σ
    // So >2σ deviation is definitely abnormal
    double spike_threshold = avg + 2.0 * stddev;
    
    return latest > spike_threshold;
}

// ============================================================================
// updateStatistics - Recalculate cached statistics
// ============================================================================
void RTTTracker::updateStatistics() const {
    if (samples_.empty()) {
        cached_avg_us_ = 0;
        cached_stddev_us_ = 0.0;
        stats_dirty_ = false;
        return;
    }
    
    // Average (already computed incrementally)
    cached_avg_us_ = getAverageRTT();
    
    // Standard Deviation
    // Formula: sqrt(E[(X - μ)²])
    //        = sqrt(mean of squared deviations from average)
    double sum_squared_dev = 0.0;
    for (uint64_t sample : samples_) {
        double deviation = static_cast<double>(sample) - static_cast<double>(cached_avg_us_);
        sum_squared_dev += deviation * deviation;
    }
    cached_stddev_us_ = std::sqrt(sum_squared_dev / samples_.size());
    
    stats_dirty_ = false;
}

// ============================================================================
// EXPLANATION OF KEY DESIGN DECISIONS
// ============================================================================
//
// 1. Why Sliding Window (Deque)?
//    - Forget old history (not permanent)
//    - Focus on recent conditions
//    - Example: if network was bad 30s ago but recovered 10s ago,
//              sliding window detects recovery; full history would not
//    - Deque is O(1) for pop_front + push_back
//
// 2. Why Cache Statistics?
//    - Average: computed incrementally, O(1) query
//    - Std-dev: computed on-demand (O(n) once then cached)
//    - Avoid recomputing stddev for every getTrendDirection() call
//    - stats_dirty_ flag indicates when recalculation needed
//
// 3. Why Separate Trend Detection Strategy?
//    - Compare old_half vs recent_half (not just direction)
//    - Smooth trends take multiple packets to detect
//    - Avoids false positives from single spike
//    - Example:
//        RTT: 50, 50, 50, 55, 57, 60, 62, 65, 65, 65
//        Old half avg: 51.25
//        Recent avg: 63
//        Result: INCREASING (clear trend toward congestion)
//
// 4. Why 1.5σ Noise Margin?
//    - Accounts for natural variation
//    - Avoids classifying noise as trend
//    - 1.5σ is aggressive but not too strict
//    - If we used 0.5σ: every small fluctuation → trend (false positives)
//    - If we used 3σ: major trends undetected (false negatives)
//
// 5. Why 2σ For Spike Detection?
//    - Statistical standard: ~95% of values within ±2σ
//    - Therefore >2σ is definitely abnormal
//    - Transient congestion produces spikes
//    - But single spike ≠ sustained congestion (see getTrendDirection)
//
// 6. Why Both Trend AND Spike Detection?
//    - Trend: sustained increase (congestion building)
//    - Spike: sudden jump (transient event)
//    - ECSDetector uses both to classify urgency level
//

}  // namespace adaptive_rtc
