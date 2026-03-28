// ============================================================================
// receiver.h - Packet Reception, Analysis, and Feedback
// ============================================================================
//
// PURPOSE:
// Receives packets, measures network conditions, detects congestion,
// and provides feedback to sender.
//
// RESPONSIBILITIES:
// - Receive packets from network
// - Track RTT statistics
// - Detect ECS signals
// - Buffer for jitter absorption
// - Generate feedback signal to sender
//
// ============================================================================

#pragma once

#include "packet.h"
#include "rtt_tracker.h"
#include "ecs_detector.h"
#include "jitter_buffer.h"
#include "rate_controller.h"
#include <cstdint>
#include <vector>
#include <optional>

namespace adaptive_rtc {

class Receiver {
public:
    /// Constructor
    ///
    /// Args:
    ///   packet_duration_us - duration each packet represents
    explicit Receiver(uint64_t packet_duration_us = 20000);
    
    // ========================================================================
    // PACKET RECEPTION
    // ========================================================================
    
    /// Process received packet
    ///
    /// Args:
    ///   packet - received packet
    ///
    /// Returns: true if packet accepted, false if lost/duplicate
    bool receivePacket(const Packet& packet);
    
    // ========================================================================
    // PLAYBACK
    // ========================================================================
    
    /// Get next packet for decoding/playback
    ///
    /// Returns: optional packet (empty if underrun/loss)
    std::optional<Packet> getPlayoutPacket();
    
    /// Check if packet available for playout
    bool hasPlayoutPacket() const;
    
    // ========================================================================
    // CONGESTION DETECTION & FEEDBACK
    // ========================================================================
    
    /// Analyze network conditions and generate feedback signal
    ///
    /// Returns: CongestionSignal to send to sender
    CongestionSignal analyzeCongestion();
    
    /// Get current ECS detector status
    ECSDetector::Status getECSStatus() const;
    
    // ========================================================================
    // STATISTICS
    // ========================================================================
    
    uint64_t getTotalPacketsReceived() const { return packets_received_; }
    uint64_t getTotalPacketsLost() const { return jitter_buffer_.getLostPacketCount(); }
    double getEstimatedRTT() const;
    
    const RTTTracker& getRTTTracker() const { return rtt_tracker_; }
    const ECSDetector& getECSDetector() const { return ecs_detector_; }
    const JitterBuffer& getJitterBuffer() const { return jitter_buffer_; }
    
    void reset();

private:
    RTTTracker rtt_tracker_;
    ECSDetector ecs_detector_;
    JitterBuffer jitter_buffer_;
    
    uint64_t packets_received_ = 0;
};

}  // namespace adaptive_rtc
