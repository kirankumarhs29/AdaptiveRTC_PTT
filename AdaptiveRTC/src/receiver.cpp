// ============================================================================
// receiver.cpp - Implementation
// ============================================================================

#include "receiver.h"

namespace adaptive_rtc {

Receiver::Receiver(uint64_t packet_duration_us)
    : rtt_tracker_(50),
      jitter_buffer_(packet_duration_us),
      packets_received_(0)
{
    // Set reasonable default buffer depth
    // Example: 100ms RTT / 20ms packets = 5 packets + 2 safety margin = 7
    jitter_buffer_.setTargetDepth(7);
}

bool Receiver::receivePacket(const Packet& packet) {
    // Only accept if packet successfully received
    if (!packet.isReceived()) {
        return false;
    }
    
    packets_received_++;
    
    // Track RTT
    uint64_t rtt = packet.getRoundTripTime();
    if (rtt > 0) {
        rtt_tracker_.addSample(rtt);
    }
    
    // Add to jitter buffer (handles sorting, loss detection, etc.)
    jitter_buffer_.addPacket(packet);
    
    return true;
}

std::optional<Packet> Receiver::getPlayoutPacket() {
    if (!jitter_buffer_.hasNextPacket()) {
        // Underrun: no packet available
        return std::nullopt;
    }
    
    return jitter_buffer_.getNextPacket();
}

bool Receiver::hasPlayoutPacket() const {
    return jitter_buffer_.hasNextPacket();
}

CongestionSignal Receiver::analyzeCongestion() {
    // Get latest RTT statistics
    uint64_t rtt_avg = rtt_tracker_.getAverageRTT();
    double rtt_stddev = rtt_tracker_.getStdDevRTT();
    RTTTrend trend = rtt_tracker_.getTrendDirection();
    bool is_spiking = rtt_tracker_.isRTTSpiking();
    
    // Update ECS detector
    ecs_detector_.updateRTTStats(rtt_avg, rtt_stddev, trend, is_spiking);
    
    // Get detection result
    ECSDetector::Status status = ecs_detector_.detect();
    
    // Convert to signal for sender
    switch (status) {
        case ECSDetector::Status::CONGESTION_IMMINENT:
            return CongestionSignal::IMMINENT;
        case ECSDetector::Status::CONGESTION_BUILDING:
            return CongestionSignal::BUILDING;
        default:
            return CongestionSignal::NONE;
    }
}

ECSDetector::Status Receiver::getECSStatus() const {
    return ecs_detector_.getCurrentStatus();
}

double Receiver::getEstimatedRTT() const {
    return static_cast<double>(rtt_tracker_.getAverageRTT()) / 1000.0;  // Convert to ms
}

void Receiver::reset() {
    rtt_tracker_.reset();
    ecs_detector_.reset();
    jitter_buffer_.reset();
    packets_received_ = 0;
}

}  // namespace adaptive_rtc
