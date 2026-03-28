// ============================================================================
// adaptive_jitter_buffer.cpp
//
// See adaptive_jitter_buffer.h for full design documentation.
// ============================================================================

#include "adaptive_jitter_buffer.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>

namespace adaptive_rtc {

// ─── Constructor ─────────────────────────────────────────────────────────────

AdaptiveJitterBuffer::AdaptiveJitterBuffer() = default;

// ─── Lifecycle ────────────────────────────────────────────────────────────────

bool AdaptiveJitterBuffer::init(uint32_t target_depth_ms,
                                uint32_t max_packets,
                                uint32_t sample_rate_hz,
                                uint32_t frame_bytes)
{
    std::lock_guard<std::mutex> lock(mutex_);

    sample_rate_hz_   = (sample_rate_hz > 0) ? sample_rate_hz : 16000;
    frame_bytes_      = (frame_bytes    > 0) ? frame_bytes    : 640;
    max_packets_      = (max_packets    > 0)
                            ? std::min(max_packets, MAX_BUFFER_PKTS)
                            : 16;
    rtp_ts_per_frame_ = sample_rate_hz_ * FRAME_MS / 1000; // e.g. 320

    // Convert requested ms → packets, clamped to [MIN, MAX].
    uint32_t clamped_ms = std::max(MIN_TARGET_MS,
                          std::min(MAX_TARGET_MS, target_depth_ms));
    target_packets_   = std::max(1u, clamped_ms / FRAME_MS);
    warmup_needed_    = std::min(target_packets_, WARMUP_MAX_PKTS);

    // Reset all state so init() is idempotent.
    buffer_.clear();
    last_frame_.assign(frame_bytes_, 0);
    next_seq_          = 0;
    warmup_            = true;
    initialized_       = true;
    last_arrival_us_   = -1;
    last_rtp_ts_       = 0;
    jitter_us_         = 0.0;
    stable_periods_    = 0;
    packets_rx_        = 0;
    packets_late_      = 0;
    packets_lost_      = 0;
    duplicate_count_   = 0;
    underrun_count_    = 0;

    return true;
}

void AdaptiveJitterBuffer::reset()
{
    std::lock_guard<std::mutex> lock(mutex_);
    buffer_.clear();
    next_seq_          = 0;
    warmup_            = true;
    last_arrival_us_   = -1;
    last_rtp_ts_       = 0;
    jitter_us_         = 0.0;
    stable_periods_    = 0;
    underrun_count_    = 0;
    packets_rx_        = 0;
    packets_late_      = 0;
    packets_lost_      = 0;
    duplicate_count_   = 0;
    last_frame_.assign(frame_bytes_, 0);
    // Preserve target_packets_ and configuration — don't re-clamp depth on reset.
}

void AdaptiveJitterBuffer::shutdown()
{
    std::lock_guard<std::mutex> lock(mutex_);
    buffer_.clear();
    last_frame_.clear();
    initialized_ = false;
}

// ─── Producer (network/receive thread) ───────────────────────────────────────
//
// Design note on locking:
//   We take a full lock for the entire function. The work done under the lock is:
//   - One map::count()  O(log N)
//   - One map[] insert  O(log N) + vector copy of frame_bytes_ bytes
//   - One jitter update O(1)
//   At 50 pps the lock is acquired ~every 20 ms. The audio thread holds the same
//   lock for a similar duration at the same cadence on the other CPU core.
//   Contention is therefore negligible in practice.

bool AdaptiveJitterBuffer::pushPacket(uint16_t seq, uint32_t rtp_ts,
                                      const uint8_t* payload, uint32_t len)
{
    if (!payload || len == 0) return false;

    std::lock_guard<std::mutex> lock(mutex_);
    if (!initialized_) return false;
    if (len != frame_bytes_)  return false; // mismatched frame size

    const int64_t now = nowUs();
    ++packets_rx_;

    // ── 1. Late-packet gate ───────────────────────────────────────────────
    // Packets that have already passed their playout deadline are useless; the
    // audio thread has already output silence/PLC for that slot.
    if (isLate(seq)) {
        ++packets_late_;
        return false;
    }

    // ── 2. Duplicate gate ────────────────────────────────────────────────
    if (buffer_.count(seq)) {
        ++duplicate_count_;
        return false;
    }

    // ── 3. Overflow guard ─────────────────────────────────────────────────
    // A stalled audio thread or a burst loss event could allow the buffer to
    // grow unboundedly. Hard cap at MAX_BUFFER_PKTS by evicting the oldest.
    while (buffer_.size() >= MAX_BUFFER_PKTS) {
        buffer_.erase(buffer_.begin()); // oldest seq first
    }

    // ── 4. Store packet ───────────────────────────────────────────────────
    Entry e;
    e.payload.assign(payload, payload + len);
    e.rtp_ts     = rtp_ts;
    e.arrival_us = now;
    buffer_[seq] = std::move(e);

    // ── 5. RFC 3550 jitter update ─────────────────────────────────────────
    updateJitter(rtp_ts, now);

    // ── 6. Warmup release ─────────────────────────────────────────────────
    // Once we have accumulated enough packets, anchor next_seq_ to the oldest
    // buffered packet and open the gate for popFrame().
    if (warmup_ && buffer_.size() >= warmup_needed_) {
        next_seq_ = buffer_.begin()->first;
        warmup_   = false;
    }

    return true;
}

// ─── Consumer (audio playout thread, every 20 ms) ────────────────────────────

int AdaptiveJitterBuffer::popFrame(uint8_t* out_buf, uint32_t buf_len)
{
    if (!out_buf || buf_len < frame_bytes_) return 0;

    std::lock_guard<std::mutex> lock(mutex_);
    if (!initialized_) return 0;

    // ── Warmup: output silence until buffer is primed ────────────────────
    // AudioTrack is already running; writing zeros avoids underrun glitches
    // while we wait for the first burst of packets to arrive.
    if (warmup_) {
        std::memset(out_buf, 0, frame_bytes_);
        return static_cast<int>(frame_bytes_);
    }

    // ── Try to pop the expected next sequence number ──────────────────────
    auto it = buffer_.find(next_seq_);
    if (it != buffer_.end()) {
        // Happy path: expected packet ready.
        const std::vector<uint8_t>& payload = it->second.payload;
        std::memcpy(out_buf, payload.data(), frame_bytes_);
        last_frame_ = payload;       // snapshot for PLC
        buffer_.erase(it);
        ++next_seq_;                 // uint16_t wraps at 65535 → 0 automatically

        adaptDepth();                // nudge target based on current jitter
        return static_cast<int>(frame_bytes_);
    }

    // ── Expected packet missing ───────────────────────────────────────────
    // Determine whether this is a confirmed packet loss (later packets exist
    // in the buffer, meaning next_seq_ was skipped by the sender/network)
    // or a true buffer underrun (nothing queued at all).

    if (!buffer_.empty()) {
        // At least one future packet exists → this seq is definitively lost.
        ++packets_lost_;
        ++next_seq_;                 // skip the lost slot
        stable_periods_ = 0;        // reset hysteresis: network is troubled
    } else {
        // Buffer empty → underrun.
        ++underrun_count_;
        // Do NOT advance next_seq_: the sender may still be transmitting and
        // the packet may arrive late enough to be popped next cycle (edge case:
        // very bursty network but not a full loss). If it truly doesn't arrive,
        // the next pushPacket() will become "late" or the next pop will underrun
        // again, at which point packets_lost_ will accumulate via the gap path.
        stable_periods_ = 0;
    }

    // ── PLC-lite: attenuated last frame ───────────────────────────────────
    // Attenuating by 50% (right-shift by 1) avoids a hard click at the gap
    // boundary while signalling to the listener that something is wrong.
    if (last_frame_.size() == frame_bytes_) {
        const int16_t* src = reinterpret_cast<const int16_t*>(last_frame_.data());
        int16_t*       dst = reinterpret_cast<int16_t*>(out_buf);
        const uint32_t samples = frame_bytes_ / 2;
        for (uint32_t i = 0; i < samples; ++i) {
            dst[i] = static_cast<int16_t>(src[i] >> 1);
        }
    } else {
        std::memset(out_buf, 0, frame_bytes_); // no prior frame: pure silence
    }

    return static_cast<int>(frame_bytes_);
}

// ─── Metrics ─────────────────────────────────────────────────────────────────

uint32_t AdaptiveJitterBuffer::depthMs() const
{
    std::lock_guard<std::mutex> lock(mutex_);
    return static_cast<uint32_t>(buffer_.size()) * FRAME_MS;
}

float AdaptiveJitterBuffer::jitterMs() const
{
    std::lock_guard<std::mutex> lock(mutex_);
    return static_cast<float>(jitter_us_ / 1000.0);
}

float AdaptiveJitterBuffer::lossRatePct() const
{
    std::lock_guard<std::mutex> lock(mutex_);
    // Total expected = packets we received + packets we declared lost at pop.
    const uint64_t total = packets_rx_ + packets_lost_;
    if (total == 0) return 0.f;
    return static_cast<float>(packets_lost_ * 100.0 / static_cast<double>(total));
}

uint64_t AdaptiveJitterBuffer::underrunCount() const
{
    std::lock_guard<std::mutex> lock(mutex_);
    return underrun_count_;
}

JitterStats AdaptiveJitterBuffer::stats() const
{
    std::lock_guard<std::mutex> lock(mutex_);
    JitterStats s;
    s.depth_ms       = static_cast<uint32_t>(buffer_.size()) * FRAME_MS;
    s.target_ms      = target_packets_ * FRAME_MS;
    s.jitter_ms      = static_cast<float>(jitter_us_ / 1000.0);
    s.underrun_count = underrun_count_;
    s.packets_rx     = packets_rx_;
    s.packets_late   = packets_late_;
    const uint64_t total = packets_rx_ + packets_lost_;
    s.loss_rate_pct  = (total > 0)
                       ? static_cast<float>(packets_lost_ * 100.0 / static_cast<double>(total))
                       : 0.f;
    return s;
}

// ─── Private helpers ──────────────────────────────────────────────────────────

int64_t AdaptiveJitterBuffer::nowUs()
{
    using namespace std::chrono;
    return duration_cast<microseconds>(
        steady_clock::now().time_since_epoch()).count();
}

// ── RFC 3550 §A.8 EWMA jitter update ─────────────────────────────────────────
//
// transit[i] = arrival_time_us[i] − (rtp_ts[i] / sample_rate × 1_000_000)
// D(i−1,i)  = |transit[i] − transit[i−1]|
// J(i)       = J(i−1) + (D − J(i−1)) / 16
//
// The division-by-16 is the standard RFC 3550 smoothing factor, chosen to
// give a half-life of ~16 packets (≈ 320 ms at 50 pps), long enough to filter
// out individual-packet spikes but short enough to track genuine congestion.

void AdaptiveJitterBuffer::updateJitter(uint32_t rtp_ts, int64_t arrival_us)
{
    // Convert RTP timestamp → µs using the configured clock rate.
    const int64_t rtp_us =
        static_cast<int64_t>(rtp_ts) * 1000000LL /
        static_cast<int64_t>(sample_rate_hz_);

    const int64_t transit = arrival_us - rtp_us;

    if (last_arrival_us_ < 0) {
        // Bootstrap on first packet.
        last_rtp_ts_     = rtp_ts;
        last_arrival_us_ = arrival_us;
        jitter_us_       = 0.0;
        return;
    }

    const int64_t last_rtp_us =
        static_cast<int64_t>(last_rtp_ts_) * 1000000LL /
        static_cast<int64_t>(sample_rate_hz_);

    const int64_t last_transit = last_arrival_us_ - last_rtp_us;

    // |transit difference| = inter-arrival jitter contribution.
    const double D = std::abs(static_cast<double>(transit - last_transit));

    // RFC 3550 EWMA with α = 1/16.
    jitter_us_ += (D - jitter_us_) / 16.0;

    last_rtp_ts_     = rtp_ts;
    last_arrival_us_ = arrival_us;
}

// ── Adaptive depth: called inside popFrame() under the lock ──────────────────
//
// Target depth formula:
//   target_ms = clamp(jitter_ms × JITTER_MULTIPLIER + SAFETY_MARGIN_MS, 40, 120)
//
// JITTER_MULTIPLIER = 4  → covers ~4 standard deviations of jitter variation,
//                          providing high probability that packets arrive before
//                          their playout slot (empirically validated on Wi-Fi Direct).
// SAFETY_MARGIN_MS  = 20 → one extra frame of headroom beyond the jitter budget.
//
// Hysteresis:
//   Increase → apply immediately (latency is less important than avoiding gaps).
//   Decrease → apply only after DECREASE_HYSTERESIS consecutive stable pops.
//              This prevents rapid oscillation on bursty (but not congested) links.

void AdaptiveJitterBuffer::adaptDepth()
{
    constexpr uint32_t JITTER_MULTIPLIER = 4;
    constexpr uint32_t SAFETY_MARGIN_MS  = 20;

    const uint32_t jitter_ms   = static_cast<uint32_t>(jitter_us_ / 1000.0);
    const uint32_t desired_ms  = std::max(MIN_TARGET_MS,
                                 std::min(MAX_TARGET_MS,
                                     jitter_ms * JITTER_MULTIPLIER + SAFETY_MARGIN_MS));
    const uint32_t desired_pkts = std::max(1u, desired_ms / FRAME_MS);

    if (desired_pkts > target_packets_) {
        // Jitter increased → raise buffer immediately.
        target_packets_ = desired_pkts;
        warmup_needed_  = std::min(target_packets_, WARMUP_MAX_PKTS);
        stable_periods_ = 0;
    } else if (desired_pkts < target_packets_) {
        // Jitter reduced → lower buffer after sustained stability.
        if (++stable_periods_ >= DECREASE_HYSTERESIS) {
            target_packets_ = desired_pkts;
            warmup_needed_  = std::min(target_packets_, WARMUP_MAX_PKTS);
            stable_periods_ = 0;
        }
    } else {
        // Stable at current target.
        ++stable_periods_;
    }
}

// ── Late-packet detection with wrap-around ────────────────────────────────────
//
// seq is "late" if it is strictly before next_seq_ in the playout window.
// seqDiff(seq, next_seq_) < 0  ↔  seq < next_seq_ in the circular sense.

bool AdaptiveJitterBuffer::isLate(uint16_t seq) const
{
    return seqDiff(seq, next_seq_) < 0;
}

// Signed 16-bit wrap-around subtraction: returns a − b in [−32768, 32767].
int16_t AdaptiveJitterBuffer::seqDiff(uint16_t a, uint16_t b)
{
    return static_cast<int16_t>(static_cast<uint16_t>(a - b));
}

} // namespace adaptive_rtc
