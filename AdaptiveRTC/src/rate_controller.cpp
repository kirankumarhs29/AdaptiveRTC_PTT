// ============================================================================
// rate_controller.cpp - Implementation
// ============================================================================

#include "rate_controller.h"
#include <algorithm>
#include <cmath>

namespace adaptive_rtc {

// ============================================================================
// Constructor
// ============================================================================
RateController::RateController(uint32_t initial_rate_bps)
    : current_rate_bps_(initial_rate_bps),
      min_rate_bps_(initial_rate_bps / 2),  // Default: 50% of initial
      max_rate_bps_(initial_rate_bps * 2),   // Default: 200% of initial
      available_tokens_(0.0),
      last_token_update_us_(0),
      last_change_time_(std::chrono::steady_clock::now())
{
}

// ============================================================================
// setRateLimits
// ============================================================================
void RateController::setRateLimits(uint32_t min_bps, uint32_t max_bps) {
    // Sanity check: min must be less than max
    if (min_bps >= max_bps) {
        return;  // Silently reject invalid configuration
    }
    
    min_rate_bps_ = min_bps;
    max_rate_bps_ = max_bps;
    
    // Re-clamp current rate to new limits
    clampRate();
}

// ============================================================================
// onCongestionSignal - Handle feedback from receiver
// ============================================================================
void RateController::onCongestionSignal(CongestionSignal signal) {
    if (signal == CongestionSignal::NONE) {
        return;  // No action
    }
    
    // Choose reduction factor based on signal severity
    double reduction_factor;
    
    if (signal == CongestionSignal::BUILDING) {
        // Mild feedback: reduce by 10%
        // Example: 100 kbit/s → 90 kbit/s
        reduction_factor = REDUCTION_BUILDING;  // 0.90
    } else {  // IMMINENT
        // Aggressive feedback: reduce by 25%
        // Example: 100 kbit/s → 75 kbit/s
        reduction_factor = REDUCTION_IMMINENT;  // 0.75
    }
    
    // Apply reduction (multiplicative)
    // ================================================================
    // Why multiplicative?
    // - Fair across flows: all reduce proportionally
    // - Example: 1 Mbps → 900 kbps, also 100 kbps → 90 kbps
    // - TCP Reno uses this: proven effective
    
    uint32_t new_rate = static_cast<uint32_t>(
        std::round(current_rate_bps_ * reduction_factor)
    );
    
    current_rate_bps_ = new_rate;
    last_reduction_factor_ = reduction_factor;
    clampRate();
    
    // Reset token bucket on rate change
    // (avoid sending burst before new rate takes effect)
    resetTokenBucket();
    
    // Update timestamp
    last_change_time_ = std::chrono::steady_clock::now();
}

// ============================================================================
// onRecoverySignal - Attempt to increase rate
// ============================================================================
void RateController::onRecoverySignal() {
    // Conservative increase: 5% (vs 10-25% decrease)
    // Rationale: increasing rate is risky, might retrigger congestion
    // Better to probe slowly
    
    uint32_t new_rate = static_cast<uint32_t>(
        std::round(current_rate_bps_ * INCREASE_FACTOR)  // 1.05
    );
    
    current_rate_bps_ = new_rate;
    last_reduction_factor_ = INCREASE_FACTOR;
    clampRate();
    
    // Update timestamp
    last_change_time_ = std::chrono::steady_clock::now();
}

// ============================================================================
// canSendPacket - Token bucket rate limiter
// ============================================================================
bool RateController::canSendPacket(uint32_t packet_size_bits, uint64_t elapsed_time_us) {
    // Token bucket algorithm (leaky bucket variant)
    // ================================================================
    // Tokens accumulate at rate = current_rate_bps
    // Each packet costs = packet_size_bits
    // Send allowed if tokens >= packet_size_bits
    //
    // Math:
    // - tokens generated per microsecond = current_rate_bps / 1e6
    // - tokens generated in elapsed_time_us = (current_rate_bps / 1e6) * elapsed_time_us
    //                                        = current_rate_bps * elapsed_time_us / 1e6
    //
    // Example:
    // - current_rate = 64000 bps (64 kbit/s)
    // - elapsed_time = 20000 us (20 ms)
    // - tokens generated = 64000 * 20000 / 1e6 = 1280 bits
    // - if packet_size = 800 bits: can send ✓
    // - if packet_size = 1500 bits: cannot send ✗
    
    // Update token bucket
    updateTokenBucket(elapsed_time_us);
    
    // Check if enough tokens for packet
    bool can_send = available_tokens_ >= packet_size_bits;
    
    if (can_send) {
        // Consume tokens
        available_tokens_ -= packet_size_bits;
        last_token_update_us_ = 0;  // Reset timer for next check
    }
    
    return can_send;
}

// ============================================================================
// resetTokenBucket - Clear tokens (start fresh)
// ============================================================================
void RateController::resetTokenBucket() {
    available_tokens_ = 0.0;
    last_token_update_us_ = 0;
}

// ============================================================================
// getTimeSinceLastChange - For debugging/monitoring
// ============================================================================
double RateController::getTimeSinceLastChange() const {
    auto now = std::chrono::steady_clock::now();
    auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        now - last_change_time_
    );
    return static_cast<double>(elapsed.count());
}

// ============================================================================
// reset - Reset to initial state
// ============================================================================
void RateController::reset() {
    last_change_time_ = std::chrono::steady_clock::now();
    last_reduction_factor_ = 1.0;
    resetTokenBucket();
}

// ============================================================================
// Private Helper Methods
// ============================================================================

void RateController::clampRate() {
    current_rate_bps_ = std::clamp(current_rate_bps_, min_rate_bps_, max_rate_bps_);
}

void RateController::updateTokenBucket(uint64_t elapsed_time_us) {
    // Generate tokens for elapsed time
    // tokens = rate (bits/sec) * time (sec) = rate * time_us / 1e6
    
    double tokens_generated = (static_cast<double>(current_rate_bps_) * 
                               static_cast<double>(elapsed_time_us)) / 1000000.0;
    
    available_tokens_ += tokens_generated;
    
    // Cap tokens to avoid unrealistic bursts
    // Max burst = 100 ms of data at current rate
    double max_tokens = (static_cast<double>(current_rate_bps_) * 100000.0) / 1000000.0;
    available_tokens_ = std::min(available_tokens_, max_tokens);
}

// ============================================================================
// EXPLANATION OF TOKEN BUCKET ALGORITHM
// ============================================================================
//
// PURPOSE:
// Rate limit packet transmission to match current_rate_bps.
// Without this, packets could be sent in bursts (not smooth).
//
// ALGORITHM:
// ┌───────────────────────────────────────┐
// │          Token Bucket                  │
// │     ┌──────────────────┐               │
// │     │   Available      │               │
// │     │   Tokens: X      │ ← decreases   │
// │     │   bits available │  when sending │
// │     └──────────────────┘               │
// │            ↑                           │
// │     Tokens refill at                  │
// │     rate_bps bits/second              │
// └───────────────────────────────────────┘
//
// SENDING LOGIC:
// 1. Measure time since last send: elapsed_time_us
// 2. Generate tokens: (rate_bps * elapsed_time_us) / 1e6
// 3. Check: if available_tokens >= packet_size_bits?
//    YES → send! (deduct tokens)
//    NO  → wait (accumulate more tokens)
//
// EXAMPLE: 64 kbit/s voice codec
// ┌──────────────────────────────────────┐
// │ Time      │ Tokens   │ Send?  │ After│
// │ (ms)      │ (bits)   │ 800bit │ Send │
// ├──────────────────────────────────────┤
// │ t=0       │ 0        │ NO     │ 0    │
// │ t=+10ms   │ 1280     │ YES ✓  │ 480  │
// │ t=+20ms   │ 1280+640 │ YES ✓  │ 1120 │
// │ t=+30ms   │ 1120+960 │ YES ✓  │ 1280 │
// └──────────────────────────────────────┘
//
// RESULT: Packets sent smoothly at ~20ms intervals (matching 64kbit/s)
// Without token bucket: could send 3 packets in 1ms (bursty)
//
// TUNING:
// - max_tokens (burst allowance): set to ~100ms of data
//   └─ Allows brief bursts (e.g., TCP bulk transfer)
//   └─ Prevents sustained bursts
// - Can queue packets if can't send immediately
//
// FAIRNESS:
// Multiple flows with AIMD + token bucket:
// - Each flow reduces rate proportionally (multiplicative)
// - Each flow limited to fair share of bandwidth
// - Proven in practice by TCP Reno success
//

}  // namespace adaptive_rtc
