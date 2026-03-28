// ============================================================================
// sender.h - Packet Transmission and Rate Control
// ============================================================================
//
// PURPOSE:
// Coordinates packet transmission with rate control and congestion adaptation.
// Acts as the sending side of the system.
//
// RESPONSIBILITIES:
// - Generate audio packets (with sequence numbering)
// - Apply rate control (token bucket)
// - Respond to congestion feedback from receiver
// - Track packet statistics
//
// ============================================================================

#pragma once

#include "packet.h"
#include "rate_controller.h"
#include "network_simulator.h"
#include <cstdint>
#include <vector>
#include <map>

namespace adaptive_rtc {

class Sender {
public:
    /// Constructor
    ///
    /// Args:
    ///   initial_rate_bps - starting transmission rate
    ///   network_sim - reference to network simulator
    explicit Sender(uint32_t initial_rate_bps, NetworkSimulator* network_sim);
    
    // ========================================================================
    // TRANSMISSION
    // ========================================================================
    
    /// Try to send a packet
    ///
    /// Args:
    ///   payload - audio data to send
    ///   current_time_us - current system time
    ///
    /// Returns: true if packet sent, false if rate limited
    bool sendPacket(const std::vector<uint8_t>& payload, uint64_t current_time_us);
    
    /// Explicit: update sending rate
    /// 
    /// Called when receiver signals congestion
    void setTransmissionRate(uint32_t rate_bps) {
        rate_controller_.setRateLimits(rate_bps / 2, rate_bps * 2);
    }
    
    // ========================================================================
    // FEEDBACK HANDLING
    // ========================================================================
    
    /// Process congestion signal from receiver
    void onCongestionFeedback(CongestionSignal signal);
    
    /// Process recovery feedback from receiver
    void onRecoveryFeedback();
    
    // ========================================================================
    // STATISTICS
    // ========================================================================
    
    uint32_t getCurrentRate() const { return rate_controller_.getCurrentRate(); }
    uint32_t getTotalPacketsSent() const { return packets_sent_; }
    
    const RateController& getRateController() const { return rate_controller_; }
    
    void reset();

private:
    RateController rate_controller_;
    NetworkSimulator* network_;
    
    uint32_t next_sequence_number_ = 0;
    uint64_t last_packet_time_us_ = 0;
    uint32_t packets_sent_ = 0;
};

}  // namespace adaptive_rtc
