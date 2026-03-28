// ============================================================================
// test_packet.cpp - Unit tests for Packet class
// ============================================================================

#include "packet.h"
#include <cassert>
#include <iostream>

using namespace adaptive_rtc;

int main() {
    std::cout << "Testing Packet class..." << std::endl;
    
    // Test 1: Basic construction
    {
        std::vector<uint8_t> payload = {1, 2, 3, 4, 5};
        Packet p(1, payload, 1000000);
        
        assert(p.sequence_number == 1);
        assert(p.send_time_us == 1000000);
        assert(p.receive_time_us == 0);
        assert(p.payload_size == 5);
        assert(!p.isReceived());
        
        std::cout << "✓ Construction test passed" << std::endl;
    }
    
    // Test 2: RTT calculation
    {
        std::vector<uint8_t> payload = {1, 2, 3};
        Packet p(1, payload, 1000000);
        p.receive_time_us = 1050000;
        
        assert(p.getRoundTripTime() == 50000);  // 50ms
        assert(p.isReceived());
        
        std::cout << "✓ RTT calculation test passed" << std::endl;
    }
    
    // Test 3: Size calculation
    {
        std::vector<uint8_t> payload(160);  // Typical voice packet
        Packet p(1, payload, 1000000);
        uint16_t total_size = p.getTotalSize();
        
        assert(total_size == 14 + 160);  // header (14) + payload (160)
        
        std::cout << "✓ Size calculation test passed" << std::endl;
    }
    
    std::cout << "All Packet tests passed!" << std::endl;
    return 0;
}
