// ============================================================================
// jitter_buffer.cpp - Implementation
// ============================================================================

#include "jitter_buffer.h"
#include <algorithm>

namespace adaptive_rtc {

// ============================================================================
// Constructor
// ============================================================================
JitterBuffer::JitterBuffer(uint64_t packet_duration_us)
    : packet_duration_us_(packet_duration_us),
      target_depth_(50 / 20)  // Default: 50ms latency / 20ms packets = ~2-3 buffer
{
}

// ============================================================================
// addPacket - Store incoming packet
// ============================================================================
void JitterBuffer::addPacket(const Packet& packet) {
    uint32_t seq = packet.sequence_number;
    
    // STEP 1: Check if packet should be accepted
    // ================================================================
    if (!shouldAcceptPacket(seq)) {
        return;  // Discard: unreasonable sequence number
    }
    
    // STEP 2: Check for duplicates
    // ================================================================
    if (buffer_.find(seq) != buffer_.end()) {
        // Duplicate: this sequence already in buffer
        duplicate_count_++;
        return;  // Reject duplicate
    }
    
    // STEP 3: Detect packet loss (gaps in sequence)
    // ================================================================
    // If this packet's seq is not next_seq_to_play and not close to it,
    // we've lost packets in between
    if (seq > next_seq_to_play_) {
        uint32_t gap = seq - next_seq_to_play_;
        if (gap > 1) {
            // Missing packets in between
            // Count each missing one
            lost_packet_count_ += (gap - 1);
            
            // Also count out-of-order if we haven't played next yet
            if (!buffer_.empty() && seq > next_seq_to_play_) {
                out_of_order_count_++;
            }
        }
    }
    
    // STEP 4: Update max sequence seen
    // ================================================================
    max_seq_ever_seen_ = std::max(max_seq_ever_seen_, seq);
    
    // STEP 5: Insert packet into buffer
    // ================================================================
    buffer_[seq] = packet;
    
    // STEP 6: Garbage collect old entries
    // ================================================================
    // If buffer grows too large, something is wrong (memory leak risk)
    // Keep only recent packets (within MAX_SEQ_GAP of max_seq_ever_seen)
    if (buffer_.size() > 1000) {
        // Remove packets too far in the past
        uint32_t min_acceptable_seq = 
            (max_seq_ever_seen_ > MAX_SEQ_GAP) ? 
            (max_seq_ever_seen_ - MAX_SEQ_GAP) : 0;
        
        auto it = buffer_.begin();
        while (it != buffer_.end() && it->first < min_acceptable_seq) {
            it = buffer_.erase(it);
        }
    }
}

// ============================================================================
// shouldAcceptPacket - Validate sequence number
// ============================================================================
bool JitterBuffer::shouldAcceptPacket(uint32_t sequence_number) const {
    // Packets should be:
    // 1. Not too far in the past (weird if sequence number wraps around)
    // 2. Not impossibly far in the future (avoid memory explosion)
    
    // Case 1: First packet ever
    if (max_seq_ever_seen_ == 0) {
        return true;  // Accept any first packet
    }
    
    // Case 2: Normal range check
    // Accept if within reasonable window of expected next sequence
    if (sequence_number >= next_seq_to_play_) {
        // Packet is in future or current (normal case)
        uint32_t gap = sequence_number - next_seq_to_play_;
        return gap <= MAX_SEQ_GAP;  // Not too far ahead
    } else {
        // Packet is in the past
        uint32_t delay = next_seq_to_play_ - sequence_number;
        return delay <= 100;  // Allow up to 100 packets late (for reordering)
    }
}

// ============================================================================
// Playback Methods
// ============================================================================

bool JitterBuffer::hasNextPacket() const {
    return buffer_.find(next_seq_to_play_) != buffer_.end();
}

Packet JitterBuffer::getNextPacket() {
    // Precondition: hasNextPacket() == true
    auto it = buffer_.find(next_seq_to_play_);
    Packet result = it->second;
    
    // Remove from buffer and advance
    buffer_.erase(it);
    next_seq_to_play_++;
    
    return result;
}

std::optional<Packet> JitterBuffer::peekNextPacket() const {
    auto it = buffer_.find(next_seq_to_play_);
    if (it == buffer_.end()) {
        return std::nullopt;
    }
    return it->second;
}

void JitterBuffer::skipPacket() {
    // Packet lost: advance sequence but don't get data
    next_seq_to_play_++;
    lost_packet_count_++;
}

// ============================================================================
// Buffer Adaptation
// ============================================================================

void JitterBuffer::setTargetDepth(size_t target_packets) {
    target_depth_ = target_packets;
}

size_t JitterBuffer::getCurrentDepth() const {
    return buffer_.size();
}

// ============================================================================
// Statistics & Status
// ============================================================================

PlayoutStatus JitterBuffer::getStatus() const {
    size_t current = getCurrentDepth();
    
    if (current == 0) {
        return PlayoutStatus::BUFFER_UNDERRUN;  // Empty!
    }
    
    if (current > target_depth_ * 2) {
        return PlayoutStatus::BUFFER_OVERRUN;   // Too full!
    }
    
    return PlayoutStatus::OK;
}

void JitterBuffer::resetStatistics() {
    lost_packet_count_ = 0;
    duplicate_count_ = 0;
    out_of_order_count_ = 0;
}

void JitterBuffer::reset() {
    buffer_.clear();
    next_seq_to_play_ = 0;
    max_seq_ever_seen_ = 0;
    resetStatistics();
}

// ============================================================================
// EXPLANATION OF JITTER BUFFER DESIGN
// ============================================================================
//
// PROBLEM THIS SOLVES:
// ================================================================
// Network packets arrive at irregular times (jitter):
// - Packet 1: arrives at t=0ms
// - Packet 2: arrives at t=25ms (delayed!)
// - Packet 3: arrives at t=15ms (earlier, out of order!)
// - Packet 4: arrives at t=35ms (delayed)
//
// But audio playback needs regular samples (20ms intervals):
// - Output 1: t=20ms
// - Output 2: t=40ms
// - Output 3: t=60ms
// - Output 4: t=80ms
//
// Without jitter buffer: playback would be stuttery
// With jitter buffer: smooth playback by absorbing timing variation
//
// HOW IT WORKS:
// ================================================================
// 1. STORE: packet arrives → store in map indexed by seq number
//    map[1] = Packet1
//    map[3] = Packet3
//    map[2] = Packet2  (even though arrived after 3!)
//    map[4] = Packet4
//
// 2. REORDER: map automatically maintains sort order by seq
//
// 3. PLAY: retrieve packets in seq order at fixed intervals
//    t=20ms: get map[1] → play
//    t=40ms: get map[2] → play
//    t=60ms: get map[3] → play
//    t=80ms: get map[4] → play
//
// RESULT: Smooth, continuous playback despite jittery arrival!
//
// WHY std::map (vs std::deque)?
// ================================================================
// Deque:
// ✓ Fast sequential access
// ✗ Can't efficiently find by sequence number (out-of-order cases)
// ✗ Insertion in middle is O(n)
//
// Map:
// ✓ Find by sequence number: O(log n)
// ✓ Insertion maintains sorted order
// ✓ Auto-handling of out-of-order arrival
// ✗ Slightly slower than deque for sequential access
//
// For jitter buffer, map is preferred because out-of-order is common!
//
// LOSS DETECTION:
// ================================================================
// When packet with seq=5 arrives but we expected seq=2 next:
// Gap = 5 - 2 = 3 packets
// We've lost packets 2, 3, 4 (3 packets total)
//
// Algorithm:
// - Track next_seq_to_play (what we want)
// - When new packet arrives, compute gap
// - Count gap - 1 as lost (subtract 1 because one of them might arrive late)
//
// DUPLICATE DETECTION:
// ================================================================
// If packet with seq=5 arrives twice:
// - First arrival: buffer[5] = pkt1
// - Second arrival: duplicate_count++, reject
//
// Why important: some networks retransmit duplicates
// We discard to avoid playing twice
//
// BUFFER DEPTH ADAPTATION:
// ================================================================
// RTT = 100ms, packet duration = 20ms
// Target buffer = RTT / packet_duration + safety margin
//                = 100 / 20 + 2 = 7 packets
//
// If buffer grows to 20 packets: network backlog building
//   Receiver should notify sender to reduce rate
//   ECSDetector sees this through RTT increase
//
// If buffer empty: underrun, packet loss or too much reduction
//   Decoder generates comfort noise
//   Sender should increase rate when safe
//

}  // namespace adaptive_rtc
