// ============================================================================
// sender.cpp - Implementation
// ============================================================================

#include "sender.h"

namespace adaptive_rtc {

Sender::Sender(uint32_t initial_rate_bps, NetworkSimulator* network_sim)
    : rate_controller_(initial_rate_bps),
      network_(network_sim),
      last_packet_time_us_(0),
      packets_sent_(0)
{
}

bool Sender::sendPacket(const std::vector<uint8_t>& payload, uint64_t current_time_us) {
    // Calculate elapsed time since last packet
    uint64_t elapsed_us = (last_packet_time_us_ == 0) ? 0 : (current_time_us - last_packet_time_us_);
    
    // Calculate packet size in bits
    uint32_t packet_size_bits = static_cast<uint32_t>(payload.size()) * 8;
    
    // Check rate controller
    if (!rate_controller_.canSendPacket(packet_size_bits, elapsed_us)) {
        return false;  // Rate limited
    }
    
    // Create packet
    Packet pkt(next_sequence_number_, payload, current_time_us);
    
    // Send via network simulator
    auto received = network_->simulateTransport(pkt, current_time_us);
    // (We don't do anything with result here - receiver gets it)
    
    // Update state
    next_sequence_number_++;
    last_packet_time_us_ = current_time_us;
    packets_sent_++;
    
    return true;
}

void Sender::onCongestionFeedback(CongestionSignal signal) {
    rate_controller_.onCongestionSignal(signal);
}

void Sender::onRecoveryFeedback() {
    rate_controller_.onRecoverySignal();
}

void Sender::reset() {
    rate_controller_.reset();
    next_sequence_number_ = 0;
    last_packet_time_us_ = 0;
    packets_sent_ = 0;
}

}  // namespace adaptive_rtc
