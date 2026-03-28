// ============================================================================
// ecs_detector.h - Early Congestion Signal Detection
// ============================================================================
//
// PURPOSE:
// Detects congestion BEFORE packet loss occurs, enabling proactive adaptation.
// Uses delay trend analysis from RTTTracker as input.
//
// CORE INNOVATION:
// Instead of reacting to packet loss (reactive), we predict congestion from
// increasing delay trends (proactive). This gives sender/receiver time to adapt.
//
// DETECTION LEVELS:
// - NO_CONGESTION: Network healthy, maintain current rate
// - CONGESTION_BUILDING: Mild increase in delays, start backing off
// - CONGESTION_IMMINENT: Rapid increase or spike, aggressive backup
//
// USAGE EXAMPLE:
//   ECSDetector detector;
//   
//   detector.updateRTTStats(rtt_avg, rtt_stddev, trend);
//   ECSDetector::Status status = detector.detect();
//   
//   if (status == ECSDetector::Status::CONGESTION_IMMINENT) {
//       sender.reduceRate(0.75);  // Aggressive: reduce to 75% of current
//   }
//
// ============================================================================

#pragma once

#include <cstdint>
#include <vector>
#include <deque>
#include "rtt_tracker.h"

namespace adaptive_rtc {

// ============================================================================
// ECSDetector Status Levels
// ============================================================================
class ECSDetector {
public:
    /// Congestion detection status
    enum class Status {
        NO_CONGESTION = 0,          // Network healthy
        CONGESTION_BUILDING = 1,    // Beginning to degrade
        CONGESTION_IMMINENT = 2     // Serious degradation imminent
    };
    
    /// Constructor
    /// Args:
    ///   sampling_window - number of RTT samples for trend averaging
    ///   Default: 20 samples
    explicit ECSDetector(size_t sampling_window = 20);
    
    // ========================================================================
    // CONFIGURATION
    // ========================================================================
    
    /// Update RTT statistics from tracker
    /// Call this after every RTT sample arrives
    ///
    /// Args:
    ///   rtt_avg_us - average RTT from tracker (microseconds)
    ///   rtt_stddev_us - standard deviation from tracker (microseconds)
    ///   trend - trend direction from tracker (increasing/decreasing/stable)
    ///   is_spiking - true if RTT spike detected by tracker
    void updateRTTStats(
        uint64_t rtt_avg_us,
        double rtt_stddev_us,
        RTTTrend trend,
        bool is_spiking);
    
    // ========================================================================
    // DETECTION
    // ========================================================================
    
    /// Detect congestion and return status
    ///
    /// Returns: Status (NO_CONGESTION, CONGESTION_BUILDING, or CONGESTION_IMMINENT)
    ///
    /// Algorithm:
    /// 1. If trend is INCREASING + spike detected:
    ///    → CONGESTION_IMMINENT (high confidence)
    /// 2. Else if trend is INCREASING (sustained increase):
    ///    → CONGESTION_BUILDING (medium confidence)
    /// 3. Else if trend is DECREASING or at time since last signal > recovery_time:
    ///    → NO_CONGESTION (network recovering or recovered)
    /// 4. Else:
    ///    → return previous status (hysteresis: avoid flapping)
    Status detect();
    
    // ========================================================================
    // STATE QUERIES
    // ========================================================================
    
    /// Get current status
    Status getCurrentStatus() const { return current_status_; }
    
    /// Time (in seconds) since last congestion signal
    /// Usage: Sender uses this to decide when to try increasing rate
    double getTimeSinceLastSignal() const;
    
    /// Confidence level in current detection (0.0 to 1.0)
    /// Based on: trend strength + spike presence + history
    double getConfidence() const { return current_confidence_; }
    
    // ========================================================================
    // DEBUGGING
    // ========================================================================
    
    /// Reset detector to initial state
    void reset();
    
    /// Get detection history (for analysis)
    const std::deque<Status>& getHistory() const { return status_history_; }

private:
    // State
    Status current_status_ = Status::NO_CONGESTION;
    double current_confidence_ = 0.0;
    
    // Configuration
    size_t sampling_window_;
    
    // Thresholds for decision logic
    // These are tuned for typical voice networks
    // Adjust if needed for different network conditions
    
    /// Minimum improvement in RTT trend before cancelling congestion signal
    /// After sending congestion signal, wait for trend to reverse or RTT to drop
    /// Default: 5 seconds
    static constexpr double SIGNAL_RECOVERY_TIME_S = 5.0;
    
    /// Confidence threshold for IMMINENT classification
    /// If we're >90% sure, classify as IMMINENT
    static constexpr double IMMINENT_CONFIDENCE_THRESHOLD = 0.90;
    
    // Statistics tracking
    std::deque<Status> status_history_;              // Recent detection history
    uint64_t last_signal_time_us_ = 0;              // Time of last congestion signal
    uint64_t creation_time_us_ = 0;                 // When detector created
    
    // RTT trend history (for sustained trend detection)
    std::vector<uint64_t> rtt_samples_;             // Historical RTT samples
    RTTTrend last_trend_ = RTTTrend::UNDEFINED;     // Previous trend direction
    bool was_spiking_ = false;                      // Was RTT spiking last check?
    
    /// Helper: Determine confidence based on evidence
    /// Evidence factors:
    /// - RTT increasing rapidly (high evidence)
    /// - RTT spiking (medium evidence)
    /// - Trend sustained across multiple samples (increases confidence)
    /// - Recent history (more weight to recent detections)
    double calculateConfidence(
        RTTTrend trend,
        bool is_spiking,
        uint64_t rtt_avg,
        double rtt_stddev);
    
    /// Current system time in microseconds
    /// Platform-specific: uses std::chrono on all platforms
    static uint64_t getCurrentTimeUS();
};

}  // namespace adaptive_rtc
