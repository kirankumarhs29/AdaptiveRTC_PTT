// ============================================================================
// jitter_buffer.h - Adaptive Playback Jitter Buffer
// ============================================================================
//
// PURPOSE:
// Smooth packet arrival variation and provide continuous audio playback.
// Absorbs network jitter (packets arriving at irregular intervals).
//
// RESPONSIBILITY:
// - Store arriving packets in sorted order
// - Provide packets at fixed playback intervals (20ms typical)
// - Adapt buffer depth to network conditions
// - Handle packet loss (missing packets)
// - Detect and handle out-of-order/duplicate packets
//
// WHY SEPARATE BUFFER:
// - Network delivers packets irregularly (jitter)
// - Audio playback needs regular samples (e.g., 20ms intervals)
// - Buffer bridges the gap between irregular input and regular output
//
// INTERNAL BEHAVIOR:
//   Packets arrive (jittered times):
//   ├─ Pkt 1: t=0ms
//   ├─ Pkt 2: t=25ms   (late)
//   ├─ Pkt 3: t=15ms   (arrived out-of-order!)
//   └─ Pkt 4: t=35ms   (late)
//   
//   Buffer stores [1, 3, 2, 4] (sorted by sequence number)
//   
//   Playback clock (20ms intervals):
//   ├─ t=20ms: play packet 1
//   ├─ t=40ms: play packet 3 (reordered!)
//   ├─ t=60ms: play packet 2
//   └─ t=80ms: play packet 4
//   
//   Result: smooth, continuous playback despite jitter + reordering
//
// ============================================================================

#pragma once

#include "packet.h"
#include <deque>
#include <map>
#include <cstdint>
#include <optional>

namespace adaptive_rtc {

// ============================================================================
// PlayoutStatus - State of jitter buffer
// ============================================================================
enum class PlayoutStatus {
    OK = 0,                    // Normal operation
    BUFFER_UNDERRUN = 1,       // Not enough packets (network loss)
    BUFFER_OVERRUN = 2         // Too many packets (stopped playback?)
};

// ============================================================================
// JitterBuffer Class
// ============================================================================
class JitterBuffer {
public:
    /// Constructor
    ///
    /// Args:
    ///   packet_duration_us - how long each packet represents (e.g., 20ms)
    ///   Default: 20000 microseconds (20 ms at 50 packets/second)
    ///
    /// Rationale: voice codecs typically encode in 20ms frames
    explicit JitterBuffer(uint64_t packet_duration_us = 20000);
    
    // ========================================================================
    // PACKET INSERTION (from receiver)
    // ========================================================================
    
    /// Add packet to buffer
    ///
    /// Args:
    ///   packet - received packet with sequence number
    ///
    /// Behavior:
    /// - Stores in sequence-number order (not arrival order)
    /// - Detects duplicate packets (same sequence → ignore)
    /// - Detects out-of-order packets (handles jitter)
    /// - Updates buffer depth statistics
    ///
    /// Idempotent for same packet: second call with same seq → no-op
    void addPacket(const Packet& packet);
    
    /// Check if packet should be accepted
    ///
    /// Returns: true if sequence number is "reasonable"
    /// (not too far in past, not impossibly far in future)
    ///
    /// Prevents: memory explosion from accepting packets with huge seq gaps
    bool shouldAcceptPacket(uint32_t sequence_number) const;
    
    // ========================================================================
    // PLAYBACK (to audio decoder/speaker)
    // ========================================================================
    
    /// Check if packet ready for playback
    ///
    /// Returns: true if next_seq_to_play available in buffer
    bool hasNextPacket() const;
    
    /// Get next packet for playback
    ///
    /// Returns: packet with next_seq_to_play
    /// Precondition: hasNextPacket() == true
    /// 
    /// After calling:
    /// - Packet removed from buffer
    /// - next_seq_to_play incremented
    Packet getNextPacket();
    
    /// Peek at next packet without removing
    ///
    /// Returns: optional packet (empty if not available)
    std::optional<Packet> peekNextPacket() const;
    
    /// Skip missing packet (for packet loss)
    ///
    /// Advances next_seq_to_play without providing audio
    /// Decoder should generate comfort noise or silence
    void skipPacket();
    
    // ========================================================================
    // BUFFER ADAPTATION
    // ========================================================================
    
    /// Set target buffer depth
    ///
    /// Args:
    ///   target_packets - desired number of packets to keep buffered
    ///
    /// Rationale:
    /// - If network RTT = 100ms and packet duration = 20ms
    /// - target = 100ms / 20ms + margin = 5 + 2 = 7 packets
    /// - Ensures ~140ms of buffered audio (delay + margin)
    void setTargetDepth(size_t target_packets);
    
    /// Get current buffer depth
    /// (number of packets currently stored)
    size_t getCurrentDepth() const;
    
    /// Get target buffer depth
    size_t getTargetDepth() const { return target_depth_; }
    
    // ========================================================================
    // STATISTICS
    // ========================================================================
    
    /// Get buffer status
    PlayoutStatus getStatus() const;
    
    /// Get next sequence number to play
    uint32_t getNextSequence() const { return next_seq_to_play_; }
    
    /// Count of packets lost (never arrived)
    uint64_t getLostPacketCount() const { return lost_packet_count_; }
    
    /// Count of duplicate packets rejected
    uint64_t getDuplicateCount() const { return duplicate_count_; }
    
    /// Count of out-of-order packets (received but not next in sequence)
    uint64_t getOutOfOrderCount() const { return out_of_order_count_; }
    
    /// Reset statistics
    void resetStatistics();
    
    /// Reset buffer (clear all packets)
    void reset();

private:
    // ========================================================================
    // BUFFER STORAGE
    // ========================================================================
    
    /// Packets stored by sequence number
    /// Using map: efficient sorted storage + O(log n) lookup
    /// Could use deque if sequence is always sequential (not with jitter)
    std::map<uint32_t, Packet> buffer_;
    
    /// Sequence number for next playback
    uint32_t next_seq_to_play_ = 0;
    
    /// Maximum sequence number we've ever seen
    /// Used to detect suspicious gaps
    uint32_t max_seq_ever_seen_ = 0;
    
    // ========================================================================
    // CONFIGURATION
    // ========================================================================
    
    /// Duration each packet represents (in microseconds)
    /// Example: 20ms voice frames → 20000 microseconds
    uint64_t packet_duration_us_;
    
    /// Target number of packets to maintain in buffer
    size_t target_depth_;
    
    /// Maximum acceptable gap in sequence numbers
    /// If gap > this, treat as probable loss not out-of-order
    /// Default: 1000 packets
    static constexpr uint32_t MAX_SEQ_GAP = 1000;
    
    // ========================================================================
    // STATISTICS
    // ========================================================================
    
    uint64_t lost_packet_count_ = 0;        // Packets never arrived
    uint64_t duplicate_count_ = 0;          // Duplicate packets rejected
    uint64_t out_of_order_count_ = 0;       // Out-of-order packets received
};

}  // namespace adaptive_rtc
