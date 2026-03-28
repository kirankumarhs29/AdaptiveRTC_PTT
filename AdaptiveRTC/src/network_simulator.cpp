// ============================================================================
// network_simulator.cpp - Implementation
// ============================================================================

#include "network_simulator.h"
#include <algorithm>
#include <cmath>

namespace adaptive_rtc {

// ============================================================================
// Constructor
// ============================================================================
NetworkSimulator::NetworkSimulator(const NetworkConfig& config, uint64_t random_seed)
    : config_(config),
      rng_(random_seed),
      loss_distribution_(config.packet_loss_percent / 100.0),  // Convert % to probability
      jitter_distribution_(0.0, static_cast<double>(config.jitter_stddev_us))
{
    // All initialization in member initializer list
}

// ============================================================================
// simulateTransport - The Core Method
// ============================================================================
std::optional<Packet> NetworkSimulator::simulateTransport(
    const Packet& packet,
    uint64_t current_time_us)
{
    // Update statistics
    stats_.total_packets_sent++;
    
    // STEP 1: Simulate packet loss
    // ========================================================================
    // Bernoulli distribution: true with probability p_loss, false otherwise
    // 
    // Example: 10% loss
    //   shouldDropPacket() returns true 10% of time → packet lost
    //   shouldDropPacket() returns false 90% of time → packet continues
    if (shouldDropPacket()) {
        stats_.total_packets_lost++;
        return std::nullopt;  // Packet lost, return empty optional
    }
    
    // STEP 2: Calculate delay
    // ========================================================================
    // delay = base_delay + jitter
    // 
    // base_delay: constant component (e.g., speed of light over distance)
    // jitter: random variation from Gaussian distribution
    // 
    // Jitter can be negative (packet arrives earlier than expected)
    // but we clamp delay to [1, max_int64] to avoid negative receive time
    int64_t jitter = calculateJitter();
    
    // Total delay = base_delay + jitter
    // Clamp to reasonable values (at least 1 microsecond, at most INT64_MAX)
    int64_t total_delay_us = static_cast<int64_t>(config_.base_delay_us) + jitter;
    total_delay_us = std::max(static_cast<int64_t>(1), total_delay_us);  // Never negative or zero
    
    // STEP 3: Create received packet
    // ========================================================================
    Packet received = packet;  // Copy original packet
    
    // Set receive time to send time + delay
    received.receive_time_us = current_time_us + static_cast<uint64_t>(total_delay_us);
    
    // Track statistics
    stats_.cumulative_delay_us += total_delay_us;
    
    return received;  // Return successfully transmitted packet
}

// ============================================================================
// updateConfig
// ============================================================================
void NetworkSimulator::updateConfig(const NetworkConfig& new_config) {
    config_ = new_config;
    
    // Update probability distributions with new config
    // This allows dynamic network changes during simulation
    loss_distribution_ = std::bernoulli_distribution(config_.packet_loss_percent / 100.0);
    jitter_distribution_ = std::normal_distribution<double>(0.0, 
        static_cast<double>(config_.jitter_stddev_us));
}

// ============================================================================
// Helper Methods
// ============================================================================

bool NetworkSimulator::shouldDropPacket() {
    return loss_distribution_(rng_);
}

int64_t NetworkSimulator::calculateJitter() {
    // Generate Gaussian random number
    double jitter_double = jitter_distribution_(rng_);
    
    // Convert to int64_t
    return static_cast<int64_t>(std::round(jitter_double));
}

// ============================================================================
// EXPLANATION OF KEY DESIGN DECISIONS
// ============================================================================
//
// 1. Why Bernoulli Distribution for Loss?
//    - Each packet independently: true (drop) or false (keep)
//    - Probability = loss_percent / 100.0
//    - Realistic: real networks have independent loss events
//    
//    Example: 5% loss
//      for 100 packets: ~5 dropped randomly throughout
//      not: first 95 transmitted perfectly, then 5 consecutive drops
//
// 2. Why Gaussian (Normal) Distribution for Jitter?
//    - Real networks have jitter ~ normal distribution
//    - 68% of packets within ±1σ of mean
//    - Few outliers (>3σ), but they exist (realistic)
//    
//    Example: 20ms std-dev
//      Most packets: 0 ± 20ms
//      Rare: 60ms+ delay (3σ beyond mean)
//    
//    vs. Uniform: all delays equally likely (unrealistic, too spiky)
//
// 3. Why std::optional<Packet>?
//    - Cleaner than returning null pointer
//    - Type-safe: compiler ensures we check for loss
//    - No default construction of empty Packet needed
//    
//    Usage:
//      auto result = sim.simulate(pkt, now);
//      if (result) {
//          Packet received = result.value();  // or *result
//      } else {
//          // Handle loss
//      }
//
// 4. Why min-clamp delay to 1 microsecond?
//    - Avoid receive_time_us == current_time_us (confusing)
//    - Avoid negative delays (jitter too aggressive)
//    - 1 microsecond is negligible but explicit
//
// 5. Why track statistics?
//    - Validate simulator matches config
//      - Set loss=10%, measure actual: should be ~10%
//      - Set jitter=20ms, measure: mean should be ~20ms
//    - Debug network parameter choices
//    - Compare experiments
//

}  // namespace adaptive_rtc
