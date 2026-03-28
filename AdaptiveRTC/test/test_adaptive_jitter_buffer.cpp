// ============================================================================
// test_adaptive_jitter_buffer.cpp - Unit tests for AdaptiveJitterBuffer
// ============================================================================
//
// Tests the production-grade adaptive jitter buffer (RFC 3550 jitter estimate,
// dynamic depth adaptation, PLC-lite, warmup gate, sequence wrap-around).
// ============================================================================

#include "adaptive_jitter_buffer.h"
#include <cassert>
#include <iostream>
#include <vector>
#include <cstring>

using namespace adaptive_rtc;

// ── Constants matching VoiceTransportConfig ──────────────────────────────────
static constexpr uint32_t SR_HZ        = 16000;
static constexpr uint32_t FRAME_BYTES  = 640;    // 16kHz × 16-bit × 20 ms
static constexpr uint32_t TARGET_DEPTH = 60;     // ms — 3 packets at 20 ms
static constexpr uint32_t MAX_PACKETS  = 16;

// Helper: create a PCM16 frame filled with a repeating byte value
static std::vector<uint8_t> make_frame(uint8_t fill = 0x42) {
    return std::vector<uint8_t>(FRAME_BYTES, fill);
}

// Helper: push sequential packets starting from seq=start
static void push_n(AdaptiveJitterBuffer& jb, uint16_t start, int count,
                   uint8_t fill = 0x11) {
    auto frame = make_frame(fill);
    for (int i = 0; i < count; ++i) {
        uint16_t seq = static_cast<uint16_t>(start + i);
        uint32_t ts  = static_cast<uint32_t>(seq) * 320u;
        jb.pushPacket(seq, ts, frame.data(), FRAME_BYTES);
    }
}

int main() {
    std::cout << "Testing AdaptiveJitterBuffer class..." << std::endl;

    // --------------------------------------------------------
    // Test 1: init() succeeds and returns true
    // --------------------------------------------------------
    {
        AdaptiveJitterBuffer jb;
        bool ok = jb.init(TARGET_DEPTH, MAX_PACKETS, SR_HZ, FRAME_BYTES);
        assert(ok);

        std::cout << "✓ Test 1: init() returns true passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 2: popFrame before warmup returns silence (zeros)
    // --------------------------------------------------------
    {
        AdaptiveJitterBuffer jb;
        jb.init(TARGET_DEPTH, MAX_PACKETS, SR_HZ, FRAME_BYTES);

        std::vector<uint8_t> out(FRAME_BYTES, 0xFF);
        int n = jb.popFrame(out.data(), FRAME_BYTES);

        // During warmup the buffer writes silence, returns FRAME_BYTES
        assert(n == static_cast<int>(FRAME_BYTES));
        // Output must be silence (zeroes)
        for (auto b : out) {
            assert(b == 0x00);
        }

        std::cout << "✓ Test 2: popFrame during warmup writes silence passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 3: pushPacket accepts valid packets
    // --------------------------------------------------------
    {
        AdaptiveJitterBuffer jb;
        jb.init(TARGET_DEPTH, MAX_PACKETS, SR_HZ, FRAME_BYTES);

        auto frame = make_frame(0xAA);
        bool ok = jb.pushPacket(0, 0, frame.data(), FRAME_BYTES);
        assert(ok);

        std::cout << "✓ Test 3: pushPacket accepts valid packet passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 4: depthMs grows as packets are pushed
    // --------------------------------------------------------
    {
        AdaptiveJitterBuffer jb;
        jb.init(TARGET_DEPTH, MAX_PACKETS, SR_HZ, FRAME_BYTES);

        // Initially 0 depth
        assert(jb.depthMs() == 0);

        push_n(jb, 0, 3);
        // 3 packets × 20 ms = 60 ms depth
        assert(jb.depthMs() == 60);

        std::cout << "✓ Test 4: depthMs reflects buffered packets passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 5: popFrame returns real frame after warmup period
    // --------------------------------------------------------
    {
        AdaptiveJitterBuffer jb;
        jb.init(TARGET_DEPTH, MAX_PACKETS, SR_HZ, FRAME_BYTES);

        // Push enough to exit warmup (target=3 packets for 60 ms / 20 ms)
        push_n(jb, 0, 4, 0xBB);

        // Drain warmup silence pops first — up to WARMUP_MAX_PKTS
        std::vector<uint8_t> out(FRAME_BYTES, 0);
        bool got_real = false;
        for (int i = 0; i < 8 && !got_real; ++i) {
            int n = jb.popFrame(out.data(), FRAME_BYTES);
            assert(n == static_cast<int>(FRAME_BYTES));
            // A real frame has fill byte 0xBB
            if (out[0] == 0xBB) {
                got_real = true;
            }
        }
        assert(got_real);

        std::cout << "✓ Test 5: popFrame delivers real frame post-warmup passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 6: Duplicate sequence number rejected
    // --------------------------------------------------------
    {
        AdaptiveJitterBuffer jb;
        jb.init(TARGET_DEPTH, MAX_PACKETS, SR_HZ, FRAME_BYTES);

        auto frame = make_frame();
        bool first  = jb.pushPacket(5, 5 * 320, frame.data(), FRAME_BYTES);
        bool second = jb.pushPacket(5, 5 * 320, frame.data(), FRAME_BYTES);

        assert(first);
        assert(!second);   // duplicate must be rejected

        std::cout << "✓ Test 6: Duplicate packet rejected passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 7: popFrame with buf_len < frame_bytes returns 0
    // --------------------------------------------------------
    {
        AdaptiveJitterBuffer jb;
        jb.init(TARGET_DEPTH, MAX_PACKETS, SR_HZ, FRAME_BYTES);

        std::vector<uint8_t> tiny(64, 0);
        int n = jb.popFrame(tiny.data(), 64);
        assert(n == 0);   // buffer too small → fail-safe

        std::cout << "✓ Test 7: popFrame tiny buffer returns 0 passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 8: popFrame without init() returns 0
    // --------------------------------------------------------
    {
        AdaptiveJitterBuffer jb;   // NOT init()ed

        std::vector<uint8_t> out(FRAME_BYTES, 0xFF);
        int n = jb.popFrame(out.data(), FRAME_BYTES);
        assert(n == 0);

        std::cout << "✓ Test 8: popFrame without init returns 0 passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 9: reset() clears buffer
    // --------------------------------------------------------
    {
        AdaptiveJitterBuffer jb;
        jb.init(TARGET_DEPTH, MAX_PACKETS, SR_HZ, FRAME_BYTES);

        push_n(jb, 0, 5);
        assert(jb.depthMs() == 100);

        jb.reset();
        assert(jb.depthMs() == 0);

        std::cout << "✓ Test 9: reset() clears buffer to 0 depth passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 10: shutdown() + re-init cycle
    // --------------------------------------------------------
    {
        AdaptiveJitterBuffer jb;
        jb.init(TARGET_DEPTH, MAX_PACKETS, SR_HZ, FRAME_BYTES);

        push_n(jb, 0, 3);
        jb.shutdown();

        // After shutdown, popFrame must return 0 (not initialised)
        std::vector<uint8_t> out(FRAME_BYTES, 0);
        int n = jb.popFrame(out.data(), FRAME_BYTES);
        assert(n == 0);

        // Re-init should work
        bool ok = jb.init(TARGET_DEPTH, MAX_PACKETS, SR_HZ, FRAME_BYTES);
        assert(ok);
        assert(jb.depthMs() == 0);

        std::cout << "✓ Test 10: shutdown + re-init cycle passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 11: jitterMs() is non-negative
    // --------------------------------------------------------
    {
        AdaptiveJitterBuffer jb;
        jb.init(TARGET_DEPTH, MAX_PACKETS, SR_HZ, FRAME_BYTES);

        push_n(jb, 0, 5);
        float jms = jb.jitterMs();
        assert(jms >= 0.0f);

        std::cout << "✓ Test 11: jitterMs() >= 0 passed (jitter=" << jms << " ms)" << std::endl;
    }

    // --------------------------------------------------------
    // Test 12: lossRatePct() is 0 before any pops
    // --------------------------------------------------------
    {
        AdaptiveJitterBuffer jb;
        jb.init(TARGET_DEPTH, MAX_PACKETS, SR_HZ, FRAME_BYTES);

        assert(jb.lossRatePct() == 0.0f);

        std::cout << "✓ Test 12: lossRatePct() == 0 before pops passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 13: underrunCount() starts at 0
    // --------------------------------------------------------
    {
        AdaptiveJitterBuffer jb;
        jb.init(TARGET_DEPTH, MAX_PACKETS, SR_HZ, FRAME_BYTES);

        assert(jb.underrunCount() == 0);

        std::cout << "✓ Test 13: underrunCount() initial value == 0 passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 14: stats() snapshot is consistent
    // --------------------------------------------------------
    {
        AdaptiveJitterBuffer jb;
        jb.init(TARGET_DEPTH, MAX_PACKETS, SR_HZ, FRAME_BYTES);

        push_n(jb, 0, 3);

        JitterStats s = jb.stats();
        assert(s.depth_ms  == jb.depthMs());
        assert(s.jitter_ms == jb.jitterMs());
        assert(s.underrun_count == jb.underrunCount());

        std::cout << "✓ Test 14: stats() snapshot consistent with individual accessors passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 15: MAX_BUFFER_PKTS overflow — oldest packets dropped
    // --------------------------------------------------------
    {
        AdaptiveJitterBuffer jb;
        jb.init(TARGET_DEPTH, MAX_PACKETS, SR_HZ, FRAME_BYTES);

        // Push more than max_packets
        push_n(jb, 0, static_cast<int>(MAX_PACKETS) + 5);

        // Buffer must not exceed max
        uint32_t depth_pkts = jb.depthMs() / AdaptiveJitterBuffer::FRAME_MS;
        assert(depth_pkts <= MAX_PACKETS);

        std::cout << "✓ Test 15: Overflow cap enforced (depth_pkts="
                  << depth_pkts << ")" << " passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 16: Sequence number wrap-around (65535 → 0)
    // --------------------------------------------------------
    {
        AdaptiveJitterBuffer jb;
        jb.init(TARGET_DEPTH, MAX_PACKETS, SR_HZ, FRAME_BYTES);

        auto frame = make_frame(0xFE);

        // Push packets near the wrap boundary
        jb.pushPacket(0xFFFE, 0xFFFE * 320u, frame.data(), FRAME_BYTES);
        jb.pushPacket(0xFFFF, 0xFFFF * 320u, frame.data(), FRAME_BYTES);
        jb.pushPacket(0x0000, 0u,             frame.data(), FRAME_BYTES);

        // All 3 pushed, depth should be 60 ms
        assert(jb.depthMs() == 60);

        std::cout << "✓ Test 16: Sequence wrap-around (65535→0) accepted passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 17: init() is idempotent — calling twice resets state
    // --------------------------------------------------------
    {
        AdaptiveJitterBuffer jb;
        jb.init(TARGET_DEPTH, MAX_PACKETS, SR_HZ, FRAME_BYTES);
        push_n(jb, 0, 3);
        assert(jb.depthMs() == 60);

        // Second init — state must reset
        bool ok = jb.init(TARGET_DEPTH, MAX_PACKETS, SR_HZ, FRAME_BYTES);
        assert(ok);
        assert(jb.depthMs() == 0);

        std::cout << "✓ Test 17: init() is idempotent (second call resets) passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 18: pushPacket wrong length rejected
    // --------------------------------------------------------
    {
        AdaptiveJitterBuffer jb;
        jb.init(TARGET_DEPTH, MAX_PACKETS, SR_HZ, FRAME_BYTES);

        std::vector<uint8_t> wrong(100, 0);  // wrong size
        bool ok = jb.pushPacket(0, 0, wrong.data(), 100);
        assert(!ok);   // wrong frame_bytes — must be rejected

        std::cout << "✓ Test 18: pushPacket rejects wrong-length payload passed" << std::endl;
    }

    std::cout << "\nAll AdaptiveJitterBuffer tests passed! (18/18)" << std::endl;
    return 0;
}
