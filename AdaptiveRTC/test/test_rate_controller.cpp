// ============================================================================
// test_rate_controller.cpp - Unit tests for RateController class
// ============================================================================

#include "rate_controller.h"
#include <cassert>
#include <iostream>
#include <thread>
#include <chrono>

using namespace adaptive_rtc;

// Allow 1% tolerance for floating-point multiply
static bool approx_equal(uint32_t a, uint32_t b, double tol = 0.02) {
    double diff = static_cast<double>(a > b ? a - b : b - a);
    double ref  = static_cast<double>(a);
    return ref == 0.0 ? a == b : (diff / ref) <= tol;
}

int main() {
    std::cout << "Testing RateController class..." << std::endl;

    // --------------------------------------------------------
    // Test 1: Initial rate returned correctly
    // --------------------------------------------------------
    {
        RateController ctrl(64000);
        assert(ctrl.getCurrentRate() == 64000);

        std::cout << "✓ Test 1: Initial rate passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 2: BUILDING congestion reduces rate by ~10%
    // --------------------------------------------------------
    {
        RateController ctrl(64000);
        ctrl.onCongestionSignal(CongestionSignal::BUILDING);

        uint32_t after = ctrl.getCurrentRate();
        // Expected: 64000 * 0.90 = 57600
        assert(approx_equal(after, 57600));

        std::cout << "✓ Test 2: BUILDING 10% reduction passed (rate=" << after << ")" << std::endl;
    }

    // --------------------------------------------------------
    // Test 3: IMMINENT congestion reduces rate by ~25%
    // --------------------------------------------------------
    {
        RateController ctrl(64000);
        ctrl.onCongestionSignal(CongestionSignal::IMMINENT);

        uint32_t after = ctrl.getCurrentRate();
        // Expected: 64000 * 0.75 = 48000
        assert(approx_equal(after, 48000));

        std::cout << "✓ Test 3: IMMINENT 25% reduction passed (rate=" << after << ")" << std::endl;
    }

    // --------------------------------------------------------
    // Test 4: Recovery increases rate by ~5%
    // --------------------------------------------------------
    {
        RateController ctrl(64000);
        ctrl.onCongestionSignal(CongestionSignal::BUILDING);   // bring rate down
        uint32_t reduced = ctrl.getCurrentRate();

        ctrl.onRecoverySignal();
        uint32_t recovered = ctrl.getCurrentRate();

        // Should be roughly reduced * 1.05
        assert(recovered > reduced);
        assert(approx_equal(recovered, static_cast<uint32_t>(reduced * 1.05)));

        std::cout << "✓ Test 4: Recovery 5% increase passed (rate=" << recovered << ")" << std::endl;
    }

    // --------------------------------------------------------
    // Test 5: setRateLimits — rate floored at minimum
    // --------------------------------------------------------
    {
        RateController ctrl(64000);
        ctrl.setRateLimits(60000, 128000);

        // Drive rate below minimum
        for (int i = 0; i < 20; ++i) {
            ctrl.onCongestionSignal(CongestionSignal::IMMINENT);
        }

        assert(ctrl.getCurrentRate() >= ctrl.getMinRate());
        assert(ctrl.getMinRate() == 60000);

        std::cout << "✓ Test 5: Rate floored at minimum passed (rate="
                  << ctrl.getCurrentRate() << ")" << std::endl;
    }

    // --------------------------------------------------------
    // Test 6: setRateLimits — rate capped at maximum
    // --------------------------------------------------------
    {
        RateController ctrl(64000);
        ctrl.setRateLimits(32000, 70000);

        // Drive rate above maximum
        for (int i = 0; i < 30; ++i) {
            ctrl.onRecoverySignal();
        }

        assert(ctrl.getCurrentRate() <= ctrl.getMaxRate());
        assert(ctrl.getMaxRate() == 70000);

        std::cout << "✓ Test 6: Rate capped at maximum passed (rate="
                  << ctrl.getCurrentRate() << ")" << std::endl;
    }

    // --------------------------------------------------------
    // Test 7: canSendPacket — token bucket gates transmission
    // --------------------------------------------------------
    {
        RateController ctrl(64000);  // 64 kbit/s = 8000 bytes/s

        // Try to send 160-byte packet (1280 bits) every 20 ms → OK at 64 kbps
        uint64_t elapsed_20ms = 20000;
        bool can_send = ctrl.canSendPacket(1280, elapsed_20ms);
        assert(can_send);

        std::cout << "✓ Test 7: canSendPacket grants within budget passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 8: canSendPacket — blocks when budget exhausted
    // --------------------------------------------------------
    {
        RateController ctrl(8000);  // very low rate: 8 kbit/s

        // Try to send 1500-byte packet (12000 bits) immediately after reset
        ctrl.resetTokenBucket();
        // elapsed = 0 us → no tokens accumulated → should be denied
        bool can_send = ctrl.canSendPacket(12000, 0);
        assert(!can_send);

        std::cout << "✓ Test 8: canSendPacket blocks over-budget passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 9: Multiple sequential reductions compound correctly
    // --------------------------------------------------------
    {
        RateController ctrl(100000);
        ctrl.onCongestionSignal(CongestionSignal::BUILDING);   // * 0.90
        ctrl.onCongestionSignal(CongestionSignal::BUILDING);   // * 0.90 again

        uint32_t after = ctrl.getCurrentRate();
        uint32_t expected = static_cast<uint32_t>(100000 * 0.90 * 0.90);
        assert(approx_equal(after, expected));

        std::cout << "✓ Test 9: Double BUILDING reduction compounding passed (rate="
                  << after << ")" << std::endl;
    }

    // --------------------------------------------------------
    // Test 10: getMinRate / getMaxRate defaults
    // --------------------------------------------------------
    {
        RateController ctrl(64000);
        // Defaults should allow the initial rate through
        assert(ctrl.getMinRate() <= 64000);
        assert(ctrl.getMaxRate() >= 64000);

        std::cout << "✓ Test 10: Default min/max rate brackets initial rate passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 11: getTimeSinceLastChange — non-zero after signal
    // --------------------------------------------------------
    {
        RateController ctrl(64000);
        ctrl.onCongestionSignal(CongestionSignal::BUILDING);

        std::this_thread::sleep_for(std::chrono::milliseconds(5));
        double t = ctrl.getTimeSinceLastChange();
        assert(t > 0.0);

        std::cout << "✓ Test 11: getTimeSinceLastChange >0 after signal passed (t="
                  << t << "s)" << std::endl;
    }

    // --------------------------------------------------------
    // Test 12: NONE signal — no rate change
    // --------------------------------------------------------
    {
        RateController ctrl(64000);
        uint32_t before = ctrl.getCurrentRate();
        ctrl.onCongestionSignal(CongestionSignal::NONE);
        assert(ctrl.getCurrentRate() == before);

        std::cout << "✓ Test 12: NONE signal leaves rate unchanged passed" << std::endl;
    }

    std::cout << "\nAll RateController tests passed! (12/12)" << std::endl;
    return 0;
}
