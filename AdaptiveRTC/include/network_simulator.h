// ============================================================================
// network_simulator.h - Simulates network conditions for testing
// ============================================================================
//
// PURPOSE:
// Provides deterministic network simulation without real network infrastructure.
// Enables reproducible testing of congestion scenarios.
//
// FEATURES:
// - Configurable packet loss rate (0-100%)
// - Configurable base latency (constant delay)
// - Configurable jitter (random variation)
// - Jitter distribution: Gaussian (normal distribution)
//
// WHY SEPARATE SIMULATOR?
// - Real UDP is non-deterministic → can't reproduce test failures reliably
// - Can inject specific congestion patterns for algorithm validation
// - Enables controlled experiments (loss vs delay vs jitter)
//
// USAGE EXAMPLE:
//   NetworkSimulator sim(50.0, 100.0, 20.0);  // 50% loss, 100ms delay, 20ms jitter std-dev
//   Packet p("hello");
//   Packet received = sim.simulateTransport(p);  // Returns modified packet or empty
//
// ============================================================================

#pragma once

#include "packet.h"
#include <optional>
#include <random>
#include <memory>

namespace adaptive_rtc {

// ============================================================================
// NetworkSimulator Configuration
// ============================================================================
struct NetworkConfig {
    /// Packet loss probability (0.0 to 100.0)
    /// 0.0 = no loss, 100.0 = all packets dropped
    double packet_loss_percent;
    
    /// Base network latency in microseconds
    /// Added to every packet (constant component)
    /// Example: 100ms = 100000 microseconds
    uint64_t base_delay_us;
    
    /// Jitter standard deviation in microseconds
    /// Actual jitter = Gaussian random with this std-dev
    /// Example: 20ms std-dev = 20000 microseconds
    uint64_t jitter_stddev_us;
    
    NetworkConfig(double loss_pct = 0.0, 
                  uint64_t base_delay = 0,
                  uint64_t jitter_std = 0)
        : packet_loss_percent(loss_pct),
          base_delay_us(base_delay),
          jitter_stddev_us(jitter_std)
    {}
};

// ============================================================================
// NetworkSimulator Class
// ============================================================================
class NetworkSimulator {
public:
    /// Constructor
    ///
    /// Args:
    ///   config - NetworkConfig with loss, delay, jitter parameters
    ///   random_seed - deterministic seed for reproducible results
    explicit NetworkSimulator(const NetworkConfig& config, uint64_t random_seed = 42);
    
    /// Simulate packet transmission through network
    ///
    /// Applies:
    /// 1. Loss decision (stochastic): drop with probability loss_percent
    /// 2. Delay: add base_delay + jitter
    /// 3. Timestamp: set receive_time_us
    ///
    /// Args:
    ///   packet - packet to transmit
    ///   current_time_us - current system time (for receive timestamp)
    ///
    /// Returns:
    ///   std::optional<Packet> - packet if delivered, empty if lost
    ///   If delivered, receive_time_us is set to current_time + delay
    std::optional<Packet> simulateTransport(
        const Packet& packet,
        uint64_t current_time_us);
    
    /// Update network configuration at runtime
    ///
    /// Useful for:
    /// - Simulating congestion onset (increase loss/delay)
    /// - Simulating congestion recovery (decrease loss/delay)
    /// - A/B testing different conditions
    void updateConfig(const NetworkConfig& new_config);
    
    /// Get current configuration
    const NetworkConfig& getConfig() const { return config_; }
    
    /// Statistics tracking (optional, for analysis)
    struct Statistics {
        uint64_t total_packets_sent = 0;
        uint64_t total_packets_lost = 0;
        double cumulative_delay_us = 0.0;
        
        double getLossRate() const {
            if (total_packets_sent == 0) return 0.0;
            return 100.0 * total_packets_lost / total_packets_sent;
        }
        
        double getAverageDelay() const {
            if (total_packets_sent == 0) return 0.0;
            return cumulative_delay_us / total_packets_sent;
        }
    };
    
    /// Get statistics (for validation/debugging)
    const Statistics& getStatistics() const { return stats_; }
    
    /// Clear statistics for new run
    void resetStatistics() { stats_ = Statistics(); }

private:
    NetworkConfig config_;
    Statistics stats_;
    
    // Random number generation
    std::mt19937_64 rng_;                           // Mersenne Twister engine
    std::bernoulli_distribution loss_distribution_; // Determines loss (true/false)
    std::normal_distribution<double> jitter_distribution_;  // Gaussian jitter
    
    /// Calculate if packet is lost this time
    bool shouldDropPacket();
    
    /// Calculate jitter for this packet (can be negative)
    int64_t calculateJitter();
};

}  // namespace adaptive_rtc
