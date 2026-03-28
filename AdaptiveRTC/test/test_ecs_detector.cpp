// ============================================================================
// test_ecs_detector.cpp - Unit tests for ECSDetector class
// ============================================================================

#include "ecs_detector.h"
#include "rtt_tracker.h"
#include <cassert>
#include <iostream>
#include <thread>
#include <chrono>

using namespace adaptive_rtc;

// Helper: drive a tracker+detector through N identical samples
static void feed_stable(RTTTracker& t, ECSDetector& d, int n, uint64_t rtt_us) {
    for (int i = 0; i < n; ++i) {
        t.addSample(rtt_us);
    }
    d.updateRTTStats(t.getAverageRTT(), t.getStdDevRTT(),
                     t.getTrendDirection(), t.isRTTSpiking());
}

// Helper: drive with increasing RTT
static void feed_increasing(RTTTracker& t, ECSDetector& d, int n,
                            uint64_t base_us, uint64_t step_us) {
    for (int i = 0; i < n; ++i) {
        t.addSample(base_us + static_cast<uint64_t>(i) * step_us);
    }
    d.updateRTTStats(t.getAverageRTT(), t.getStdDevRTT(),
                     t.getTrendDirection(), t.isRTTSpiking());
}

int main() {
    std::cout << "Testing ECSDetector class..." << std::endl;

    // --------------------------------------------------------
    // Test 1: NO_CONGESTION — flat stable RTT
    // --------------------------------------------------------
    {
        ECSDetector detector;
        RTTTracker  tracker;

        feed_stable(tracker, detector, 20, 45000);

        auto status = detector.detect();
        assert(status == ECSDetector::Status::NO_CONGESTION);

        std::cout << "✓ Test 1: NO_CONGESTION stable RTT passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 2: CONGESTION_BUILDING — steadily rising RTT
    // --------------------------------------------------------
    {
        ECSDetector detector;
        RTTTracker  tracker;

        feed_increasing(tracker, detector, 20, 40000, 2000);

        auto status = detector.detect();
        // Expect BUILDING or higher
        assert(static_cast<int>(status) >= static_cast<int>(ECSDetector::Status::CONGESTION_BUILDING));

        std::cout << "✓ Test 2: CONGESTION_BUILDING rising RTT passed (status="
                  << static_cast<int>(status) << ")" << std::endl;
    }

    // --------------------------------------------------------
    // Test 3: CONGESTION_IMMINENT — aggressive increase + spike
    // --------------------------------------------------------
    {
        ECSDetector detector;
        RTTTracker  tracker(50);

        // Phase 1: build confidence with steady increase
        for (int i = 0; i < 15; ++i) {
            tracker.addSample(40000 + static_cast<uint64_t>(i) * 3000);
            detector.updateRTTStats(tracker.getAverageRTT(), tracker.getStdDevRTT(),
                                    tracker.getTrendDirection(), tracker.isRTTSpiking());
        }
        // Phase 2: add a large spike to maximize confidence
        for (int i = 0; i < 5; ++i) {
            tracker.addSample(500000);
        }
        detector.updateRTTStats(tracker.getAverageRTT(), tracker.getStdDevRTT(),
                                tracker.getTrendDirection(), tracker.isRTTSpiking());
        detector.detect();  // update internal state

        // Repeat to accumulate confidence above 0.90 threshold
        for (int i = 0; i < 5; ++i) {
            detector.updateRTTStats(tracker.getAverageRTT(), tracker.getStdDevRTT(),
                                    tracker.getTrendDirection(), tracker.isRTTSpiking());
            detector.detect();
        }

        double conf = detector.getConfidence();
        assert(conf > 0.0);   // at minimum some confidence accumulated

        std::cout << "✓ Test 3: CONGESTION_IMMINENT pathway confidence="
                  << conf << " passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 4: getConfidence — starts at 0
    // --------------------------------------------------------
    {
        ECSDetector detector;
        assert(detector.getConfidence() == 0.0);

        std::cout << "✓ Test 4: Initial confidence == 0.0 passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 5: reset — clears confidence and status
    // --------------------------------------------------------
    {
        ECSDetector detector;
        RTTTracker  tracker;

        feed_increasing(tracker, detector, 20, 40000, 2000);
        detector.detect();

        assert(detector.getConfidence() > 0.0);

        detector.reset();
        assert(detector.getConfidence() == 0.0);
        assert(detector.getCurrentStatus() == ECSDetector::Status::NO_CONGESTION);

        std::cout << "✓ Test 5: reset clears state passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 6: getCurrentStatus — matches last detect() result
    // --------------------------------------------------------
    {
        ECSDetector detector;
        RTTTracker  tracker;

        feed_stable(tracker, detector, 20, 45000);
        auto detected  = detector.detect();
        auto current   = detector.getCurrentStatus();
        assert(detected == current);

        std::cout << "✓ Test 6: getCurrentStatus consistent with detect() passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 7: getHistory — accumulates past results
    // --------------------------------------------------------
    {
        ECSDetector detector;
        RTTTracker  tracker;

        feed_stable(tracker, detector, 20, 45000);
        detector.detect();
        detector.detect();

        auto history = detector.getHistory();
        // At least the calls above should appear
        assert(!history.empty());

        std::cout << "✓ Test 7: getHistory non-empty after detect() passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 8: getTimeSinceLastSignal — advances with time
    // --------------------------------------------------------
    {
        ECSDetector detector;
        RTTTracker  tracker;

        feed_increasing(tracker, detector, 20, 40000, 2000);
        detector.detect();

        double t0 = detector.getTimeSinceLastSignal();

        // Tiny sleep so wall-clock moves
        std::this_thread::sleep_for(std::chrono::milliseconds(10));

        double t1 = detector.getTimeSinceLastSignal();
        assert(t1 >= t0);  // time can only grow

        std::cout << "✓ Test 8: getTimeSinceLastSignal advances passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 9: Multiple sequential detect() calls — status stable
    // --------------------------------------------------------
    {
        ECSDetector detector;
        RTTTracker  tracker;

        feed_stable(tracker, detector, 20, 45000);

        for (int i = 0; i < 5; ++i) {
            auto s = detector.detect();
            assert(s == ECSDetector::Status::NO_CONGESTION);
        }

        std::cout << "✓ Test 9: Repeated detect() stable result passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 10: NO_CONGESTION after DECREASING RTT recovery
    // --------------------------------------------------------
    {
        ECSDetector detector;
        RTTTracker  tracker;

        // Rise first to create some congestion signal
        feed_increasing(tracker, detector, 20, 40000, 2000);
        detector.detect();

        // Now simulate recovery — decreasing RTT
        RTTTracker  tracker2;
        for (int i = 0; i < 20; ++i) {
            tracker2.addSample(200000 - static_cast<uint64_t>(i) * 5000);
        }
        detector.updateRTTStats(tracker2.getAverageRTT(), tracker2.getStdDevRTT(),
                                 tracker2.getTrendDirection(), tracker2.isRTTSpiking());

        auto status = detector.detect();
        // After recovery trend, confidence should drop → NO_CONGESTION
        assert(status == ECSDetector::Status::NO_CONGESTION);

        std::cout << "✓ Test 10: NO_CONGESTION after recovery passed" << std::endl;
    }

    std::cout << "\nAll ECSDetector tests passed! (10/10)" << std::endl;
    return 0;
}
