// ============================================================================
// test_rtt_tracker.cpp - Unit tests for RTTTracker class
// ============================================================================

#include "rtt_tracker.h"
#include <cassert>
#include <iostream>

using namespace adaptive_rtc;

int main() {
    std::cout << "Testing RTTTracker class..." << std::endl;
    
    // Test 1: Basic statistics
    {
        RTTTracker tracker(10);
        tracker.addSample(40000);
        tracker.addSample(45000);
        tracker.addSample(50000);
        
        assert(tracker.getMinRTT() == 40000);
        assert(tracker.getMaxRTT() == 50000);
        assert(tracker.getAverageRTT() == 45000);
        assert(tracker.getSampleCount() == 3);
        
        std::cout << "✓ Statistics test passed" << std::endl;
    }
    
    // Test 2: Sliding window
    {
        RTTTracker tracker(5);
        for (int i = 0; i < 10; ++i) {
            tracker.addSample(40000 + i * 1000);
        }
        
        assert(tracker.getSampleCount() == 5);  // Max 5 samples
        
        std::cout << "✓ Sliding window test passed" << std::endl;
    }
    
    // Test 3: Trend detection
    {
        RTTTracker tracker(20);
        // Add increasing RTT trend
        for (int i = 0; i < 20; ++i) {
            tracker.addSample(40000 + i * 500);  // Increasing by 500us each
        }
        
        RTTTrend trend = tracker.getTrendDirection();
        assert(trend == RTTTrend::INCREASING);
        
        std::cout << "✓ Trend detection test passed" << std::endl;
    }
    
    std::cout << "All RTTTracker tests passed!" << std::endl;
    return 0;
}
