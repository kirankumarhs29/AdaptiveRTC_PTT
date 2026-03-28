// ============================================================================
// test_jitter_buffer.cpp - Unit tests for JitterBuffer class
// ============================================================================

#include "jitter_buffer.h"
#include <cassert>
#include <iostream>

using namespace adaptive_rtc;

int main() {
    std::cout << "Testing JitterBuffer class..." << std::endl;
    
    // Test 1: Sequential packets
    {
        JitterBuffer buffer;
        
        for (uint32_t i = 0; i < 5; ++i) {
            std::vector<uint8_t> payload = {1, 2, 3};
            Packet p(i, payload, 1000000 + i * 20000);
            p.receive_time_us = p.send_time_us + 10000;
            buffer.addPacket(p);
        }
        
        assert(buffer.hasNextPacket());
        auto pkt = buffer.getNextPacket();
        assert(pkt.sequence_number == 0);
        
        std::cout << "✓ Sequential packets test passed" << std::endl;
    }
    
    // Test 2: Out-of-order packets
    {
        JitterBuffer buffer;
        
        // Add packets out of order
        std::vector<uint8_t> payload = {1, 2, 3};
        
        Packet p3(3, payload, 1000000);
        p3.receive_time_us = 1010000;
        buffer.addPacket(p3);
        
        Packet p1(1, payload, 1000000);
        p1.receive_time_us = 1010000;
        buffer.addPacket(p1);
        
        Packet p2(2, payload, 1000000);
        p2.receive_time_us = 1010000;
        buffer.addPacket(p2);
        
        // Should play in order: 1, 2, 3
        assert(buffer.hasNextPacket() == false);  // Still waiting for 0
        
        std::cout << "✓ Out-of-order packets test passed" << std::endl;
    }
    
    // Test 3: Duplicate detection
    {
        JitterBuffer buffer;
        
        std::vector<uint8_t> payload = {1, 2, 3};
        Packet p(1, payload, 1000000);
        p.receive_time_us = 1010000;
        
        buffer.addPacket(p);
        uint64_t initial_dup_count = buffer.getDuplicateCount();
        
        buffer.addPacket(p);  // Add same packet again
        
        assert(buffer.getDuplicateCount() > initial_dup_count);
        
        std::cout << "✓ Duplicate detection test passed" << std::endl;
    }
    
    std::cout << "All JitterBuffer tests passed!" << std::endl;
    return 0;
}
