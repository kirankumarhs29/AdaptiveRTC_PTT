// ============================================================================
// test_rtt_tracker.cpp - Unit tests for RTTTracker class
// ============================================================================

#include "rtt_tracker.h"
#include <cassert>
#include <iostream>
#include <cmath>

using namespace adaptive_rtc;

int main() {
    std::cout << "Testing RTTTracker class..." << std::endl;

    // --------------------------------------------------------
    // Test 1: Basic statistics (min / max / average)
    // --------------------------------------------------------
    {
        RTTTracker tracker(10);
        tracker.addSample(40000);
        tracker.addSample(45000);
        tracker.addSample(50000);

        assert(tracker.getMinRTT()     == 40000);
        assert(tracker.getMaxRTT()     == 50000);
        assert(tracker.getAverageRTT() == 45000);
        assert(tracker.getSampleCount() == 3);

        std::cout << "✓ Test 1: Basic statistics passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 2: Sliding window — oldest sample evicted
    // --------------------------------------------------------
    {
        RTTTracker tracker(5);
        for (int i = 0; i < 10; ++i) {
            tracker.addSample(40000 + static_cast<uint64_t>(i) * 1000);
        }

        assert(tracker.getSampleCount() == 5);

        std::cout << "✓ Test 2: Sliding window eviction passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 3: INCREASING trend
    // --------------------------------------------------------
    {
        RTTTracker tracker(20);
        for (int i = 0; i < 20; ++i) {
            tracker.addSample(40000 + static_cast<uint64_t>(i) * 500);
        }

        assert(tracker.getTrendDirection() == RTTTrend::INCREASING);

        std::cout << "✓ Test 3: INCREASING trend detected passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 4: DECREASING trend
    // --------------------------------------------------------
    {
        RTTTracker tracker(20);
        for (int i = 0; i < 20; ++i) {
            tracker.addSample(200000 - static_cast<uint64_t>(i) * 5000);
        }

        RTTTrend t = tracker.getTrendDirection();
        assert(t == RTTTrend::DECREASING);

        std::cout << "✓ Test 4: DECREASING trend detected passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 5: STABLE trend — flat RTT
    // --------------------------------------------------------
    {
        RTTTracker tracker(20);
        for (int i = 0; i < 20; ++i) {
            tracker.addSample(45000);  // constant RTT
        }

        RTTTrend t = tracker.getTrendDirection();
        assert(t == RTTTrend::STABLE);

        std::cout << "✓ Test 5: STABLE trend detected passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 6: getLatestRTT — returns the most recent sample
    // --------------------------------------------------------
    {
        RTTTracker tracker(10);
        tracker.addSample(100000);
        tracker.addSample(200000);
        tracker.addSample(300000);

        assert(tracker.getLatestRTT() == 300000);

        std::cout << "✓ Test 6: getLatestRTT returns newest sample passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 7: getMedianRTT — middle value of window
    // --------------------------------------------------------
    {
        RTTTracker tracker(10);
        // Odd number of samples: median = middle element
        tracker.addSample(10000);
        tracker.addSample(30000);
        tracker.addSample(20000);  // 3 samples: sorted → 10k, 20k, 30k, median=20k

        uint64_t med = tracker.getMedianRTT();
        assert(med == 20000);

        std::cout << "✓ Test 7: getMedianRTT passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 8: isRTTSpiking — spike detected on large jump
    // --------------------------------------------------------
    {
        RTTTracker tracker(20);
        // Fill with stable baseline
        for (int i = 0; i < 18; ++i) {
            tracker.addSample(40000);
        }
        // Add a large spike (well above avg + 2*stddev)
        tracker.addSample(40000);
        tracker.addSample(500000);  // massive spike

        bool spiking = tracker.isRTTSpiking();
        assert(spiking);

        std::cout << "✓ Test 8: isRTTSpiking detects spike passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 9: isRTTSpiking — no spike on stable samples
    // --------------------------------------------------------
    {
        RTTTracker tracker(20);
        for (int i = 0; i < 20; ++i) {
            tracker.addSample(45000);
        }

        assert(!tracker.isRTTSpiking());

        std::cout << "✓ Test 9: isRTTSpiking stable window returns false passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 10: reset — clears all samples
    // --------------------------------------------------------
    {
        RTTTracker tracker(10);
        tracker.addSample(50000);
        tracker.addSample(60000);

        tracker.reset();
        assert(tracker.getSampleCount() == 0);
        assert(tracker.getMinRTT()      == 0);
        assert(tracker.getMaxRTT()      == 0);

        std::cout << "✓ Test 10: reset clears all samples passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 11: getAllSamples — returns exact copies
    // --------------------------------------------------------
    {
        RTTTracker tracker(5);
        tracker.addSample(10000);
        tracker.addSample(20000);
        tracker.addSample(30000);

        auto samples = tracker.getAllSamples();
        assert(samples.size() == 3);
        // Samples should contain the values we added
        bool found10 = false, found20 = false, found30 = false;
        for (auto s : samples) {
            if (s == 10000) found10 = true;
            if (s == 20000) found20 = true;
            if (s == 30000) found30 = true;
        }
        assert(found10 && found20 && found30);

        std::cout << "✓ Test 11: getAllSamples returns correct values passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 12: getStdDevRTT — non-zero for varying samples
    // --------------------------------------------------------
    {
        RTTTracker tracker(10);
        tracker.addSample(30000);
        tracker.addSample(40000);
        tracker.addSample(50000);
        tracker.addSample(60000);

        double stddev = tracker.getStdDevRTT();
        assert(stddev > 0.0);

        std::cout << "✓ Test 12: getStdDevRTT positive for varied samples passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 13: UNDEFINED trend with too few samples
    // --------------------------------------------------------
    {
        RTTTracker tracker(20);
        tracker.addSample(40000);   // only one sample — cannot determine trend

        RTTTrend t = tracker.getTrendDirection();
        // With 1 sample, window cannot be split — expect UNDEFINED or STABLE
        assert(t == RTTTrend::UNDEFINED || t == RTTTrend::STABLE);

        std::cout << "✓ Test 13: UNDEFINED/STABLE trend with 1 sample passed" << std::endl;
    }

    std::cout << "\nAll RTTTracker tests passed! (13/13)" << std::endl;
    return 0;
}
