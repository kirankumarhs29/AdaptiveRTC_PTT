// ============================================================================
// rate_controller.h - Adaptive Transmission Rate Control
// ============================================================================
//
// PURPOSE:
// Manages sender transmission rate based on congestion feedback.
// Implements AIMD-like (Additive Increase, Multiplicative Decrease):
// - Multiplicative decrease on congestion (aggressive response)
// - Additive increase on recovery (cautious recovery)
//
// RESPONSIBILITY:
// - Maintain target send rate (bits per second)
// - Respond to ECS signals from receiver
// - Smooth rate changes (avoid jitter in adaptation)
// - Respect bandwidth boundaries
//
// WHY THIS APPROACH:
// - Multiplicative: proportional to current rate, fair with other flows
// - AIMD proven effective in TCP Reno and variants
// - Smooth adaptation: avoid sudden rate drops that sound bad to user
//
// USAGE EXAMPLE:
//   RateController controller(64000);  // Start at 64 kbit/s
//   controller.setRateLimits(32000, 128000);  // Range: 32-128 kbit/s
//   
//   // When congestion detected:
//   controller.onCongestionSignal(RateController::Signal::BUILDING);
//   uint32_t new_rate = controller.getCurrentRate();  // ~58 kbit/s (90% of previous)
//   
//   // When network improving:
//   controller.onRecoverySignal();
//   new_rate = controller.getCurrentRate();  // ~60 kbit/s (5% increase)
//
// ============================================================================

#pragma once

#include <cstdint>
#include <chrono>

namespace adaptive_rtc {

// ============================================================================
// Signal Types (from ECSDetector)
// ============================================================================
enum class CongestionSignal {
    NONE = 0,
    BUILDING = 1,
    IMMINENT = 2
};

// ============================================================================
// RateController Class
// ============================================================================
class RateController {
public:
    /// Constructor
    ///
    /// Args:
    ///   initial_rate_bps - starting transmission rate in bits/second
    ///   Example: 64000 for 64 kbit/s voice codec
    explicit RateController(uint32_t initial_rate_bps);
    
    // ========================================================================
    // RATE CONFIGURATION
    // ========================================================================
    
    /// Set acceptable rate range
    ///
    /// Args:
    ///   min_bps - minimum allowed rate (floor)
    ///   max_bps - maximum allowed rate (ceiling)
    ///
    /// All rate changes clamped to [min, max]
    /// Typical: voice codecs have min/max rates specified
    void setRateLimits(uint32_t min_bps, uint32_t max_bps);
    
    /// Get current target transmission rate
    ///
    /// Returns: rate in bits per second
    /// Usage: sender uses this to calculate transmission interval
    uint32_t getCurrentRate() const { return current_rate_bps_; }
    
    /// Get minimum allowed rate
    uint32_t getMinRate() const { return min_rate_bps_; }
    
    /// Get maximum allowed rate
    uint32_t getMaxRate() const { return max_rate_bps_; }
    
    // ========================================================================
    // RESPONSE TO FEEDBACK
    // ========================================================================
    
    /// Handle congestion signal from receiver
    ///
    /// Args:
    ///   signal - severity of congestion (BUILDING or IMMINENT)
    ///
    /// BUILDING: 10% rate reduction (current_rate *= 0.9)
    /// IMMINENT: 25% rate reduction (current_rate *= 0.75)
    ///
    /// Motivation:
    /// - BUILDING: mild feedback, gentle correction
    /// - IMMINENT: strong feedback, aggressive correction
    /// - Both multiplicative: fair, proportional to current rate
    void onCongestionSignal(CongestionSignal signal);
    
    /// Handle network recovery
    ///
    /// Increases rate by 5% (current_rate *= 1.05)
    /// Called when RTT trend reverses or network conditions improve
    ///
    /// Conservative (5% vs 10% decrease) because:
    /// - Increase might re-trigger congestion
    /// - Better to probe slowly than oscillate
    void onRecoverySignal();
    
    // ========================================================================
    // RATE LIMITING
    // ========================================================================
    
    /// Check if sender can send packet now
    ///
    /// Args:
    ///   packet_size_bits - size of packet in bits
    ///   elapsed_time_us - microseconds since last packet check
    ///
    /// Returns: true if rate allows sending this packet now
    ///
    /// Algorithm:
    /// - Maintain token bucket
    /// - Each microsecond, add (current_rate / 1e6) tokens
    /// - Each packet costs (packet_size_bits) tokens
    /// - Send if tokens available
    ///
    /// Benefit: smooth traffic rate (not bursty)
    bool canSendPacket(uint32_t packet_size_bits, uint64_t elapsed_time_us);
    
    /// Reset token bucket (for new session or error recovery)
    void resetTokenBucket();
    
    // ========================================================================
    // STATISTICS & DEBUGGING
    // ========================================================================
    
    /// Get most recent rate reduction factor
    /// (e.g., 0.9 for 10% reduction)
    double getLastReductionFactor() const { return last_reduction_factor_; }
    
    /// Time (in milliseconds) since last rate change
    double getTimeSinceLastChange() const;
    
    /// Reset controller to initial state
    void reset();

private:
    // Rate control
    uint32_t current_rate_bps_;                 // Current rate (bits/second)
    uint32_t min_rate_bps_;                     // Minimum allowed
    uint32_t max_rate_bps_;                     // Maximum allowed
    
    // Token bucket (for smooth packet transmission)
    double available_tokens_;                   // Current token count
    uint64_t last_token_update_us_;             // When last updated
    
    // Adaptation parameters
    static constexpr double REDUCTION_BUILDING = 0.90;  // 10% reduction
    static constexpr double REDUCTION_IMMINENT = 0.75;  // 25% reduction
    static constexpr double INCREASE_FACTOR = 1.05;     // 5% increase
    
    // Statistics
    double last_reduction_factor_ = 1.0;
    std::chrono::steady_clock::time_point last_change_time_;
    
    /// Internal: clamp rate to [min, max]
    void clampRate();
    
    /// Internal: update token bucket
    void updateTokenBucket(uint64_t elapsed_time_us);
};

}  // namespace adaptive_rtc
