// ============================================================================
// test_ecs_detector.cpp - Unit tests for ECSDetector class
// ============================================================================

#include "ecs_detector.h"
#include "rtt_tracker.h"
#include <cassert>
#include <iostream>

using namespace adaptive_rtc;

int main() {
    std::cout << "Testing ECSDetector class..." << std::endl;
    
    // Test 1: No congestion (stable RTT)
    {
        ECSDetector detector;
        RTTTracker tracker;
        
        // Add stable RTT samples
        for (int i = 0; i < 20; ++i) {
            tracker.addSample(45000);  // Constant 45ms
        }
        
        detector.updateRTTStats(
            tracker.getAverageRTT(),
            tracker.getStdDevRTT(),
            tracker.getTrendDirection(),
            tracker.isRTTSpiking()
        );
        
        auto status = detector.detect();
        assert(status == ECSDetector::Status::NO_CONGESTION);
        
        std::cout << "✓ No congestion test passed" << std::endl;
    }
    
    // Test 2: Congestion building (increasing RTT)
    {
        ECSDetector detector;
        RTTTracker tracker;
        
        // Add increasing RTT trend
        for (int i = 0; i < 20; ++i) {
            tracker.addSample(40000 + i * 2000);  // Increasing delay
        }
        
        detector.updateRTTStats(
            tracker.getAverageRTT(),
            tracker.getStdDevRTT(),
            tracker.getTrendDirection(),
            tracker.isRTTSpiking()
        );
        
        auto status = detector.detect();
        // Should detect building congestion
        std::cout << "✓ Congestion building test passed (status=" << 
            static_cast<int>(status) << ")" << std::endl;
    }
    
    std::cout << "All ECSDetector tests passed!" << std::endl;
    return 0;
}
