// ============================================================================
// test_jitter_buffer.cpp - Unit tests for JitterBuffer class
// ============================================================================

#include "jitter_buffer.h"
#include <cassert>
#include <iostream>

using namespace adaptive_rtc;

// Helper: build a received packet
static Packet make_pkt(uint32_t seq, uint64_t send_us = 1000000) {
    std::vector<uint8_t> payload = {0xDE, 0xAD, 0xBE, 0xEF};
    Packet p(seq, payload, send_us);
    p.receive_time_us = send_us + 10000;
    return p;
}

int main() {
    std::cout << "Testing JitterBuffer class..." << std::endl;

    // --------------------------------------------------------
    // Test 1: Sequential packets — in-order playout
    // --------------------------------------------------------
    {
        JitterBuffer buffer;

        for (uint32_t i = 0; i < 5; ++i) {
            buffer.addPacket(make_pkt(i, 1000000 + i * 20000));
        }

        assert(buffer.hasNextPacket());
        Packet first = buffer.getNextPacket();
        assert(first.sequence_number == 0);

        std::cout << "✓ Test 1: Sequential in-order playout passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 2: Out-of-order packets — waits for seq 0 before playing
    // --------------------------------------------------------
    {
        JitterBuffer buffer;
        std::vector<uint8_t> payload = {1};

        buffer.addPacket(make_pkt(3));
        buffer.addPacket(make_pkt(1));
        buffer.addPacket(make_pkt(2));

        // Next expected is 0 — buffer must not yet allow playout
        assert(buffer.hasNextPacket() == false);

        // Add missing seq 0 — now 0,1,2,3 should be available
        buffer.addPacket(make_pkt(0));
        assert(buffer.hasNextPacket());

        // Out-of-order count should reflect 3 re-ordered arrivals
        assert(buffer.getOutOfOrderCount() >= 1);

        std::cout << "✓ Test 2: Out-of-order reordering passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 3: Duplicate detection
    // --------------------------------------------------------
    {
        JitterBuffer buffer;
        Packet p = make_pkt(1);

        buffer.addPacket(p);
        uint64_t before = buffer.getDuplicateCount();
        buffer.addPacket(p);   // same packet again
        assert(buffer.getDuplicateCount() > before);

        std::cout << "✓ Test 3: Duplicate detection passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 4: peekNextPacket — does not consume
    // --------------------------------------------------------
    {
        JitterBuffer buffer;
        buffer.addPacket(make_pkt(0));

        assert(buffer.hasNextPacket());
        auto peeked = buffer.peekNextPacket();   // returns std::optional<Packet>
        assert(peeked.has_value());
        assert(peeked.value().sequence_number == 0);

        // peek must not consume — packet still available
        assert(buffer.hasNextPacket());
        Packet consumed = buffer.getNextPacket();
        assert(consumed.sequence_number == 0);

        // now buffer should be empty
        assert(!buffer.hasNextPacket());

        std::cout << "✓ Test 4: peekNextPacket non-consuming passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 5: skipPacket — advances sequence without returning data
    // --------------------------------------------------------
    {
        JitterBuffer buffer;
        buffer.addPacket(make_pkt(0));
        buffer.addPacket(make_pkt(1));
        buffer.addPacket(make_pkt(2));

        // Skip seq 0
        buffer.skipPacket();
        // Next should now be seq 1
        Packet next = buffer.getNextPacket();
        assert(next.sequence_number == 1);

        std::cout << "✓ Test 5: skipPacket advances sequence passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 6: setTargetDepth / getCurrentDepth / getTargetDepth
    // --------------------------------------------------------
    {
        JitterBuffer buffer;
        buffer.setTargetDepth(10);
        assert(buffer.getTargetDepth() == 10);

        // After adding 3 packets depth grows to 3
        buffer.addPacket(make_pkt(0));
        buffer.addPacket(make_pkt(1));
        buffer.addPacket(make_pkt(2));
        assert(buffer.getCurrentDepth() == 3);

        std::cout << "✓ Test 6: Target/current depth management passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 7: getNextSequence — starts at 0, increments on consume
    // --------------------------------------------------------
    {
        JitterBuffer buffer;
        assert(buffer.getNextSequence() == 0);

        buffer.addPacket(make_pkt(0));
        buffer.getNextPacket();
        assert(buffer.getNextSequence() == 1);

        std::cout << "✓ Test 7: getNextSequence tracking passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 8: resetStatistics — clears counters, keeps packets
    // --------------------------------------------------------
    {
        JitterBuffer buffer;
        Packet dup = make_pkt(0);
        buffer.addPacket(dup);
        buffer.addPacket(dup);   // forces duplicate count > 0

        assert(buffer.getDuplicateCount() > 0);
        buffer.resetStatistics();
        assert(buffer.getDuplicateCount() == 0);
        assert(buffer.getLostPacketCount() == 0);
        assert(buffer.getOutOfOrderCount() == 0);

        std::cout << "✓ Test 8: resetStatistics clears counters passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 9: reset — full buffer reset
    // --------------------------------------------------------
    {
        JitterBuffer buffer;
        for (uint32_t i = 0; i < 5; ++i) {
            buffer.addPacket(make_pkt(i));
        }
        assert(buffer.getCurrentDepth() > 0);

        buffer.reset();
        assert(buffer.getCurrentDepth() == 0);
        assert(!buffer.hasNextPacket());
        assert(buffer.getNextSequence() == 0);

        std::cout << "✓ Test 9: reset clears buffer completely passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 10: shouldAcceptPacket — rejects already-played seqs
    // --------------------------------------------------------
    {
        JitterBuffer buffer;
        buffer.addPacket(make_pkt(0));
        buffer.getNextPacket();  // seq 0 consumed

        // seq 0 should no longer be accepted (already played)
        assert(!buffer.shouldAcceptPacket(0));
        // seq 1 should be accepted
        assert(buffer.shouldAcceptPacket(1));

        std::cout << "✓ Test 10: shouldAcceptPacket rejects stale seqs passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 11: getLostPacketCount — tracks losses on skipPacket
    // --------------------------------------------------------
    {
        JitterBuffer buffer;
        buffer.addPacket(make_pkt(0));
        buffer.addPacket(make_pkt(2));   // seq 1 missing

        buffer.getNextPacket();          // consume 0
        buffer.skipPacket();             // skip missing seq 1

        uint64_t lost = buffer.getLostPacketCount();
        assert(lost >= 1);

        std::cout << "✓ Test 11: getLostPacketCount after skip passed" << std::endl;
    }

    std::cout << "\nAll JitterBuffer tests passed! (11/11)" << std::endl;
    return 0;
}
