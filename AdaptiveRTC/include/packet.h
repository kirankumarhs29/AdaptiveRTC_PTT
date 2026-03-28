// ============================================================================
// packet.h - Basic packet structure for voice transport
// ============================================================================
// 
// PURPOSE:
// Represents a single network packet carrying voice data. Contains:
// - Audio payload (encoded voice)
// - Metadata for RTT tracking (timestamps)
// - Sequence number for ordering/loss detection
//
// WHY THIS DESIGN:
// - Simple container (no initialization logic, no inheritance)
// - Uses uint64_t for microsecond timestamps (high precision for short delays)
// - Sequence number uniquely identifies each packet (detect duplicate/loss)
// - Payload is std::vector for flexibility (can extend for FEC, etc.)
//
// ============================================================================

#pragma once

#include <cstdint>
#include <vector>
#include <chrono>

namespace adaptive_rtc {

// ============================================================================
// Packet Structure
// ============================================================================
struct Packet {
    // ========================================================================
    // SEQUENCE TRACKING
    // ========================================================================
    
    /// Unique sequence number across all packets from sender
    /// Used to detect packet loss and reordering
    /// Incremented with each packet (wraps at uint32_t max)
    uint32_t sequence_number;
    
    // ========================================================================
    // TIMING INFORMATION (for RTT tracking)
    // ========================================================================
    
    /// Microsecond timestamp when sender created packet
    /// Set at sender side with system clock (not adjustable later)
    /// Used to calculate RTT: rtt = receiver_time - send_time
    uint64_t send_time_us;
    
    /// Microsecond timestamp when receiver got packet
    /// Set by NetworkSimulator or network layer
    /// Represents clock time, same timebase as send_time_us
    uint64_t receive_time_us;
    
    // ========================================================================
    // PAYLOAD DATA
    // ========================================================================
    
    /// Raw audio data (codec-dependent)
    /// Could be PCM, Opus, or any codec output
    /// Size typically 20-60 bytes for voice (20ms at 8-16 kbit/s)
    std::vector<uint8_t> payload;
    
    /// Redundant but explicit: size of payload in bytes
    /// Used for quick size checks without .size() call
    uint16_t payload_size;
    
    // ========================================================================
    // CONSTRUCTORS
    // ========================================================================
    
    /// Default constructor - all fields zero
    Packet() 
        : sequence_number(0), 
          send_time_us(0), 
          receive_time_us(0),
          payload_size(0) 
    {}
    
    /// Constructor for creating packets at sender
    /// 
    /// Args:
    ///   seq - sequence number for this packet
    ///   data - raw audio payload
    ///   timestamp_us - system time when packet created (microseconds)
    Packet(uint32_t seq, const std::vector<uint8_t>& data, uint64_t timestamp_us)
        : sequence_number(seq),
          send_time_us(timestamp_us),
          receive_time_us(0),  // Set later by receiver
          payload(data),
          payload_size(static_cast<uint16_t>(data.size()))
    {}
    
    // ========================================================================
    // INLINE CALCULATIONS
    // ========================================================================
    
    /// Calculate round-trip time (RTT) if packet received
    /// 
    /// Returns:
    ///   RTT in microseconds (receive_time - send_time)
    /// 
    /// Precondition: receive_time_us > 0 (packet must be received)
    /// Note: Returns 0 if packet not received (receive_time_us == 0)
    inline uint64_t getRoundTripTime() const {
        if (receive_time_us == 0) return 0;
        return receive_time_us - send_time_us;
    }
    
    /// Check if packet has been received
    /// 
    /// Returns:
    ///   true if receive_time_us is set (packet was received)
    inline bool isReceived() const {
        return receive_time_us > 0;
    }
    
    /// Size of complete packet (header + payload)
    /// Used for bandwidth calculations
    /// 
    /// Header size = 12 bytes (3 x uint32_t for seq, send_time, receive_time)
    ///               + 2 bytes (payload_size)
    ///             = 14 bytes minimum
    /// Plus payload
    inline uint16_t getTotalSize() const {
        return 14 + payload_size;  // Header + payload
    }
};

// ============================================================================
// EXPLANATION OF KEY DESIGN DECISIONS
// ============================================================================
//
// 1. uint64_t for timestamps (not uint32_t or double)
//    - Why: Microsecond precision for ~70 YEARS (uint64_t can hold 2^64 us)
//    - Avoids floating point errors in RTT calculations
//    - Example: 
//        send_time_us = 1000000000
//        recv_time_us = 1000050000
//        rtt = 50000 us = 50 ms (exact integer, no rounding)
//
// 2. std::vector for payload (not fixed array)
//    - Why: Voice compression varies by codec
//        - Opus at 16 kbit/s, 20ms frame: ~40 bytes
//        - Opus at 32 kbit/s, 20ms frame: ~80 bytes
//        - Future: FEC might expand to 120 bytes
//    - Vector handles all cases without pre-allocation waste
//
// 3. receive_time_us is 0 when constructed (not -1 or special value)
//    - Why: Clearer semantics (0 = not set)
//    - RTT calculation safe: if rec_time=0, return 0 explicitly
//    - No magic numbers in packet logic
//
// 4. Sequence numbers (uint32_t)
//    - Why: 2^32 packets = ~85 seconds at 50 Mbps
//    - Long enough for typical session
//    - Wrapping handled by comparison logic in receiver (seq > last_seq)
//
// 5. getTotalSize() includes 14-byte header
//    - Why: Needed for precise bandwidth calculations
//        - Sender: "if time left can fit packet"
//        - Receiver: "compute throughput exactly"
//    - Header size constant: 12 bytes timestamps + 2 bytes size field
//

}  // namespace adaptive_rtc
