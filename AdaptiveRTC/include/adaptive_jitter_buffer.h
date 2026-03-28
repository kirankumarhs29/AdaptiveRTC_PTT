// ============================================================================
// adaptive_jitter_buffer.h
//
// Production-grade adaptive jitter buffer for PTT audio over Wi-Fi Direct.
//
// PROBLEM SOLVED:
//   Wi-Fi Direct delivers RTP packets with variable inter-arrival delay (jitter).
//   AudioTrack requires frames at a constant 20 ms cadence. A fixed-size buffer
//   either adds too much latency (over-buffering) or underruns when jitter spikes.
//   This class adapts its depth in real-time using RFC 3550 jitter measurement.
//
// ALGORITHM OVERVIEW:
//   1. pushPacket()  (network thread, ~50 pps)
//      a. Reject late packets (seq < next playout seq).
//      b. Insert into std::map<uint16_t, Entry> — O(log N), auto-sorted by seq.
//      c. Record wall-clock arrival_us for jitter measurement.
//      d. Update RFC 3550 EWMA jitter estimate:
//           D = |transit[i] – transit[i-1]|
//           J = J + (D – J) / 16
//      e. Release warmup hold once min(target_packets, WARMUP_MAX) frames buffered.
//
//   2. popFrame()  (audio thread, exactly every 20 ms)
//      a. During warmup: write silence and return — AudioTrack keeps running.
//      b. If next_seq_ is in the buffer: pop, save for PLC, advance next_seq_.
//      c. If next_seq_ is missing but later seqs exist (confirmed gap): skip seq,
//         write PLC frame (50% attenuated last frame), count loss.
//      d. If buffer is empty (underrun): write PLC frame, count underrun.
//      e. Call adaptDepth() after every pop to tune target.
//
//   3. adaptDepth()  (called inside popFrame, under lock)
//      target_ms = clamp(jitter_ms × 4 + 20, 40, 120)
//      - Increase immediately on jitter spike (latency > gap risk).
//      - Decrease only after DECREASE_HYSTERESIS consecutive stable pops
//        (prevents oscillation on bursty networks).
//
// THREADING MODEL:
//   - Single std::mutex guards all state.
//   - Lock hold time is O(log N) where N ≤ MAX_BUFFER_PKTS (≤ 32).
//     At 20 ms per pop the audio thread holds the lock for < 1 µs.
//   - No dynamic allocation on the audio-thread hot path (payloads are
//     pre-allocated during pushPacket on the network thread).
//
// SEQUENCE WRAP-AROUND:
//   All seq arithmetic uses signed 16-bit subtraction — correct through the
//   65535 → 0 boundary without any special-case logic.
//
// PLC (Packet Loss Concealment):
//   On loss/underrun the last valid frame is attenuated by 50% and written.
//   This is "PLC-lite": click/silence avoidance without a full waveform
//   extrapolation synthesiser (appropriate for PTT full-band PCM16).
// ============================================================================

#pragma once

#include <cstdint>
#include <cstring>
#include <map>
#include <mutex>
#include <vector>

namespace adaptive_rtc {

// ── Metrics snapshot ─────────────────────────────────────────────────────────

struct JitterStats {
    uint32_t depth_ms       = 0;   ///< Packets currently buffered, in ms
    uint32_t target_ms      = 0;   ///< Current dynamic target depth, in ms
    float    jitter_ms      = 0.f; ///< RFC 3550 running jitter estimate, in ms
    float    loss_rate_pct  = 0.f; ///< Packet loss percentage (0–100)
    uint64_t underrun_count = 0;   ///< AudioTrack underruns since last reset
    uint64_t packets_rx     = 0;   ///< Total accepted packets since last reset
    uint64_t packets_late   = 0;   ///< Packets dropped as too-late arrivals
};

// ── AdaptiveJitterBuffer ─────────────────────────────────────────────────────

class AdaptiveJitterBuffer {
public:
    // ── Public constants ─────────────────────────────────────────────────
    static constexpr uint32_t FRAME_MS            = 20;   ///< ms per RTP frame
    static constexpr uint32_t MIN_TARGET_MS       = 40;   ///< minimum buffer depth
    static constexpr uint32_t MAX_TARGET_MS       = 120;  ///< maximum buffer depth
    static constexpr int32_t  DECREASE_HYSTERESIS = 8;    ///< pops before shrinking
    static constexpr uint32_t MAX_BUFFER_PKTS     = 32;   ///< hard GC cap
    static constexpr uint32_t WARMUP_MAX_PKTS     = 4;    ///< warmup packets cap

    AdaptiveJitterBuffer();
    ~AdaptiveJitterBuffer() = default;

    // Non-copyable, non-movable (owns mutex + vector).
    AdaptiveJitterBuffer(const AdaptiveJitterBuffer&)            = delete;
    AdaptiveJitterBuffer& operator=(const AdaptiveJitterBuffer&) = delete;

    // ── Lifecycle ────────────────────────────────────────────────────────

    /// Initialise (or re-initialise) with audio parameters.
    /// @param target_depth_ms  desired steady-state depth, clamped to [40, 120] ms.
    /// @param max_packets      hard buffer cap (use 16–30 for PTT).
    /// @param sample_rate_hz   audio sample rate (e.g. 16000).
    /// @param frame_bytes      PCM bytes per frame (e.g. 640 for 16kHz 16-bit 20ms).
    /// @return true always (reserved for future validation).
    bool init(uint32_t target_depth_ms,
              uint32_t max_packets,
              uint32_t sample_rate_hz,
              uint32_t frame_bytes);

    /// Reset playout state without releasing resources.
    /// Call at each PTT press to flush stale frames from the prior transmission.
    void reset();

    /// Release all resources. Must call init() again before further use.
    void shutdown();

    // ── Producer interface (call from network/receive thread) ────────────

    /// Accept an incoming RTP packet.
    ///
    /// @param seq     16-bit RTP sequence number (wrap-around handled).
    /// @param rtp_ts  32-bit RTP timestamp in RTP clock units (e.g. 16000 Hz → 320 per frame).
    /// @param payload pointer to PCM frame payload.
    /// @param len     payload byte count; must equal frame_bytes_.
    /// @return true if accepted and buffered; false if late/duplicate/invalid.
    bool pushPacket(uint16_t seq, uint32_t rtp_ts,
                    const uint8_t* payload, uint32_t len);

    // ── Consumer interface (call from audio playout thread, every 20 ms) ─

    /// Pull one 20 ms frame for playout.
    ///
    /// Always writes exactly frame_bytes_ to out_buf (silence or PLC on gap/underrun)
    /// so AudioTrack never stalls waiting for data.
    ///
    /// @param out_buf  pre-allocated buffer of at least frame_bytes_ bytes.
    /// @param buf_len  size of out_buf; must be ≥ frame_bytes_.
    /// @return frame_bytes_ on success; 0 on misconfiguration (not initialised / buf too small).
    int popFrame(uint8_t* out_buf, uint32_t buf_len);

    // ── Metrics ──────────────────────────────────────────────────────────

    /// Current buffered audio in milliseconds (lock-safe).
    uint32_t depthMs() const;

    /// RFC 3550 running jitter estimate in milliseconds.
    float jitterMs() const;

    /// Packet loss rate as a percentage (0.0–100.0).
    float lossRatePct() const;

    /// Total playout underruns since last reset().
    uint64_t underrunCount() const;

    /// Atomic snapshot of all metrics under a single lock.
    JitterStats stats() const;

private:
    // ── Buffered packet entry ─────────────────────────────────────────────
    struct Entry {
        std::vector<uint8_t> payload;
        uint32_t             rtp_ts;
        int64_t              arrival_us; ///< wall-clock µs at arrival (steady_clock)
    };

    // ── Buffer: sorted by RTP sequence number ────────────────────────────
    // Key is uint16_t so the map order mirrors sequence wrap-around correctly
    // for the seqDiff comparison used in isLate().
    std::map<uint16_t, Entry> buffer_;
    mutable std::mutex        mutex_;

    // ── Configuration (immutable after init) ─────────────────────────────
    uint32_t sample_rate_hz_   = 16000;
    uint32_t frame_bytes_      = 640;
    uint32_t max_packets_      = 16;
    uint32_t rtp_ts_per_frame_ = 320;  ///< sample_rate_hz_ * FRAME_MS / 1000

    // ── Playout state ─────────────────────────────────────────────────────
    uint16_t next_seq_         = 0;
    bool     initialized_      = false;
    bool     warmup_           = true;
    uint32_t warmup_needed_    = 3;    ///< packets to accumulate before first output

    // ── RFC 3550 jitter estimator state ──────────────────────────────────
    int64_t  last_arrival_us_  = -1;  ///< -1 = no packet seen yet
    uint32_t last_rtp_ts_      = 0;
    double   jitter_us_        = 0.0; ///< running EWMA jitter in µs

    // ── Adaptive depth state ──────────────────────────────────────────────
    uint32_t target_packets_   = 3;   ///< current target depth in packets (default 60ms)
    int32_t  stable_periods_   = 0;   ///< consecutive pops at stable jitter level

    // ── PLC state ─────────────────────────────────────────────────────────
    std::vector<uint8_t> last_frame_; ///< last successfully decoded frame for PLC

    // ── Statistics ────────────────────────────────────────────────────────
    uint64_t packets_rx_       = 0;
    uint64_t packets_late_     = 0;
    uint64_t packets_lost_     = 0;   ///< gaps detected at pop time
    uint64_t duplicate_count_  = 0;
    uint64_t underrun_count_   = 0;

    // ── Private helpers ───────────────────────────────────────────────────

    /// Wall clock in microseconds using std::chrono::steady_clock.
    static int64_t nowUs();

    /// Update RFC 3550 EWMA jitter with a new packet's (rtp_ts, arrival_us).
    void updateJitter(uint32_t rtp_ts, int64_t arrival_us);

    /// Recalculate target_packets_ based on current jitter estimate.
    /// Called inside popFrame() under the lock.
    void adaptDepth();

    /// Returns true if seq has already passed its playout deadline.
    bool isLate(uint16_t seq) const;

    /// Signed 16-bit sequence difference with wrap-around: returns a – b.
    static int16_t seqDiff(uint16_t a, uint16_t b);
};

} // namespace adaptive_rtc
