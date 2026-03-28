// ============================================================================
// ecs_detector.cpp - Implementation
// ============================================================================

#include "ecs_detector.h"
#include "rtt_tracker.h"
#include <algorithm>
#include <chrono>
#include <cmath>

namespace adaptive_rtc {

// ============================================================================
// Constructor
// ============================================================================
ECSDetector::ECSDetector(size_t sampling_window)
    : sampling_window_(sampling_window),
      creation_time_us_(getCurrentTimeUS())
{
}

// ============================================================================
// updateRTTStats - Accept new RTT measurements
// ============================================================================
void ECSDetector::updateRTTStats(
    uint64_t rtt_avg_us,
    double rtt_stddev_us,
    RTTTrend trend,
    bool is_spiking)
{
    // Store sample for trend history
    rtt_samples_.push_back(rtt_avg_us);
    if (rtt_samples_.size() > sampling_window_) {
        rtt_samples_.erase(rtt_samples_.begin());
    }
    
    // Store trend for detecting pattern changes
    last_trend_ = trend;
    was_spiking_ = is_spiking;
    
    // Update confidence based on new evidence
    current_confidence_ = calculateConfidence(trend, is_spiking, rtt_avg_us, rtt_stddev_us);
}

// ============================================================================
// detect - Main Detection Algorithm
// ============================================================================
ECSDetector::Status ECSDetector::detect() {
    // DETECTION STATE MACHINE
    // ================================================================
    
    // CASE 1: High confidence in congestion
    // If RTT increasing AND spiking, or very high confidence:
    // → CONGESTION_IMMINENT
    if (current_confidence_ > IMMINENT_CONFIDENCE_THRESHOLD) {
        Status new_status = Status::CONGESTION_IMMINENT;
        
        // Record signal time only if status changed
        if (new_status != current_status_) {
            last_signal_time_us_ = getCurrentTimeUS();
        }
        
        current_status_ = new_status;
        return new_status;
    }
    
    // CASE 2: Moderate evidence of congestion
    // If RTT trend is INCREASING (but not yet spiking):
    // → CONGESTION_BUILDING
    if (last_trend_ == RTTTrend::INCREASING && 
        current_confidence_ > 0.5) {  // At least 50% confident
        
        Status new_status = Status::CONGESTION_BUILDING;
        
        if (new_status != current_status_) {
            last_signal_time_us_ = getCurrentTimeUS();
        }
        
        current_status_ = new_status;
        return new_status;
    }
    
    // CASE 3: Network recovering or time to retry
    // If trend reversed (DECREASING) or enough time passed:
    // → NO_CONGESTION
    if (last_trend_ == RTTTrend::DECREASING ||
        getTimeSinceLastSignal() > SIGNAL_RECOVERY_TIME_S) {
        
        current_status_ = Status::NO_CONGESTION;
        return current_status_;
    }
    
    // CASE 4: Stable trend but was previously warned
    // Keep current status (hysteresis - avoid flapping)
    // Only drop alarm if trend clearly reversed
    return current_status_;
}

// ============================================================================
// State Queries
// ============================================================================

double ECSDetector::getTimeSinceLastSignal() const {
    uint64_t now = getCurrentTimeUS();
    if (last_signal_time_us_ == 0) {
        return 1000.0;  // No signal yet, return large value (can retry)
    }
    
    uint64_t elapsed_us = now - last_signal_time_us_;
    return static_cast<double>(elapsed_us) / 1000000.0;  // Convert to seconds
}

// ============================================================================
// reset - Clear all state
// ============================================================================
void ECSDetector::reset() {
    current_status_ = Status::NO_CONGESTION;
    current_confidence_ = 0.0;
    last_signal_time_us_ = 0;
    rtt_samples_.clear();
    status_history_.clear();
    last_trend_ = RTTTrend::UNDEFINED;
    was_spiking_ = false;
}

// ============================================================================
// calculateConfidence - Determine confidence in congestion
// ============================================================================
double ECSDetector::calculateConfidence(
    RTTTrend trend,
    bool is_spiking,
    uint64_t rtt_avg,
    double rtt_stddev)
{
    double confidence = 0.0;
    
    // EVIDENCE 1: RTT Trend Direction
    // ================================================================
    // INCREASING trend is key evidence of congestion
    
    if (trend == RTTTrend::INCREASING) {
        confidence += 0.6;  // 60% base confidence from trend
        
        // EVIDENCE 2: Sustained Trend
        // If trend been INCREASING for multiple samples:
        // → More confident (not just one noisy sample)
        if (rtt_samples_.size() >= 5) {
            // Check if recent RTT samples are consistently increasing
            bool increasing_sustained = true;
            for (size_t i = 1; i < rtt_samples_.size(); ++i) {
                if (rtt_samples_[i] < rtt_samples_[i-1]) {
                    increasing_sustained = false;
                    break;
                }
            }
            
            if (increasing_sustained) {
                confidence += 0.2;  // Additional 20% for sustained increase
            }
        }
    }
    else if (trend == RTTTrend::STABLE) {
        confidence += 0.1;  // Just baseline, stable is OK
    }
    else if (trend == RTTTrend::DECREASING) {
        confidence = 0.0;   // Decreasing → network recovering, no alarm
    }
    
    // EVIDENCE 3: RTT Spike Detection
    // ================================================================
    // Spike indicates sudden congestion (transient event)
    // But not as reliable as sustained trend
    
    if (is_spiking) {
        confidence += 0.3;  // 30% additional confidence from spike
        
        // If spiking AND already increasing: compound effect
        if (trend == RTTTrend::INCREASING) {
            confidence += 0.1;  // Extra 10% for spike during INCREASING trend
        }
    }
    
    // EVIDENCE 4: RTT Variability
    // ================================================================
    // High variance (stddev) indicates instability
    // Normalize by mean: coefficient of variation
    
    if (rtt_avg > 0) {
        double cv = rtt_stddev / static_cast<double>(rtt_avg);  // Coefficient of variation
        
        // High CV (>20%) indicates high variability
        if (cv > 0.20) {
            confidence += 0.1;  // Extra confirmation
        }
    }
    
    // EVIDENCE 5: Recent History
    // ================================================================
    // If we recently sent a congestion signal, keep confidence up
    // (avoids flapping - don't cancel and re-signal rapidly)
    
    if (current_status_ != Status::NO_CONGESTION && 
        getTimeSinceLastSignal() < 1.0) {  // Within 1 second
        confidence *= 1.1;  // Boost confidence (hysteresis)
    }
    
    // Clamp confidence to [0, 1]
    confidence = std::min(1.0, std::max(0.0, confidence));
    
    // ALGORITHM EXPLANATION:
    // ================================================================
    // Confidence is accumulated from multiple independent evidence sources:
    // - RTT Trend (most important): 0.6 base, up to 0.8
    // - RTT Spike (medium): +0.3
    // - Variability (supporting): +0.1
    // - Recent history (hysteresis): scaling factor
    //
    // Total can reach 1.0 (very confident) but usually 0.5-0.9
    //
    // Decision boundaries:
    // - <0.5: NO_CONGESTION (too uncertain)
    // - 0.5-0.9: CONGESTION_BUILDING (building evidence)
    // - >0.9: CONGESTION_IMMINENT (high confidence)
    //
    // This avoids false positives (one spike = full alarm)
    // And avoids missing real congestion (trend + spike = definite alarm)
    
    return confidence;
}

// ============================================================================
// getCurrentTimeUS - Platform-independent time in microseconds
// ============================================================================
uint64_t ECSDetector::getCurrentTimeUS() {
    using namespace std::chrono;
    auto now = high_resolution_clock::now();
    auto us = duration_cast<microseconds>(now.time_since_epoch());
    return us.count();
}

// ============================================================================
// EXPLANATION OF CORE ALGORITHM
// ============================================================================
//
// PROBLEM BEING SOLVED:
// Real-time voice networks often suffer congestion before packet loss.
// Detecting congestion from delay BEFORE loss allows proactive adaptation.
//
// SOLUTION: ECS (Early Congestion Signal)
// 1. Monitor RTT (round-trip time) trends
// 2. Classify RTT pattern: INCREASING, DECREASING, or STABLE
// 3. Generate signal with confidence level
// 4. Provide 3 detection levels for graduated response
//
// DETECTION LOGIC:
// ================================================================
// Input: RTT measurements from RTTTracker
//        └─ average, stddev, trend direction, spike flag
//
// Decision Tree:
// if (INCREASING trend + RTT spiking):
//     confidence = HIGH (0.9+)
//     status = CONGESTION_IMMINENT
//     sender action: reduce rate aggressively (to 75%)
//
// else if (INCREASING trend sustained):
//     confidence = MEDIUM (0.5-0.9)
//     status = CONGESTION_BUILDING
//     sender action: reduce rate gradually (to 90%)
//
// else if (DECREASING trend):
//     confidence = LOW (0.0)
//     status = NO_CONGESTION
//     sender action: optionally increase rate (cautious)
//
// else if (time since signal > recovery_time):
//     confidence = LOW
//     status = NO_CONGESTION
//     sender action: try increasing rate
//
// WHY THIS WORKS:
// 1. Delay trends appear BEFORE packet loss
//    - TCP RTT increases as queues grow
//    - Packets eventually drop when buffer overflows
//    - By detecting trend early, we avoid the overflow
//
// 2. Hysteresis (state machine) prevents flapping
//    - Single spike ≠ sustained congestion
//    - Don't cancel & re-signal every packet
//    - Recovery takes time (5 second minimum)
//
// 3. Confidence accumulation is robust
//    - Multiple weak signals → strong conclusion
//    - Single evidence type not enough
//    - True congestion shows trend + spike + history
//
// TUNING PARAMETERS (if needed):
// - SIGNAL_RECOVERY_TIME_S: wait time before allowing rate increase
//   └─ Lower (1s)  = aggressive recovery (might retrigger)
//   └─ Higher (10s) = conservative recovery (slow to recover)
// - IMMINENT_CONFIDENCE_THRESHOLD: confidence for aggressive action
//   └─ Lower (0.7)  = eager response (false positives possible)
//   └─ Higher (0.95) = very sure (might miss real congestion)
// - calculateConfidence() weights: how much each factor matters
//

}  // namespace adaptive_rtc
