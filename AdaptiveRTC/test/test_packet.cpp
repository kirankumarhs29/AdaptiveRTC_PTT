// ============================================================================
// test_packet.cpp - Unit tests for Packet class
// ============================================================================

#include "packet.h"
#include <cassert>
#include <iostream>

using namespace adaptive_rtc;

// Helper: build a minimal received packet
static Packet make_received(uint32_t seq, uint64_t send_us, uint64_t recv_us) {
    std::vector<uint8_t> payload = {0xAA, 0xBB, 0xCC};
    Packet p(seq, payload, send_us);
    p.receive_time_us = recv_us;
    return p;
}

int main() {
    std::cout << "Testing Packet class..." << std::endl;

    // --------------------------------------------------------
    // Test 1: Basic construction
    // --------------------------------------------------------
    {
        std::vector<uint8_t> payload = {1, 2, 3, 4, 5};
        Packet p(1, payload, 1000000);

        assert(p.sequence_number == 1);
        assert(p.send_time_us == 1000000);
        assert(p.receive_time_us == 0);
        assert(p.payload_size == 5);
        assert(!p.isReceived());

        std::cout << "✓ Test 1: Construction passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 2: RTT calculation — 50 ms round trip
    // --------------------------------------------------------
    {
        Packet p = make_received(1, 1000000, 1050000);

        assert(p.getRoundTripTime() == 50000);
        assert(p.isReceived());

        std::cout << "✓ Test 2: RTT 50 ms passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 3: Size calculation — header is 14 bytes
    // --------------------------------------------------------
    {
        std::vector<uint8_t> payload(160);   // typical 20 ms PCM16 frame
        Packet p(1, payload, 1000000);

        assert(p.getTotalSize() == static_cast<uint16_t>(14 + 160));

        std::cout << "✓ Test 3: Size 160-byte payload passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 4: Default constructor — zero-initialised fields
    // --------------------------------------------------------
    {
        Packet p;

        assert(p.sequence_number == 0);
        assert(p.send_time_us    == 0);
        assert(p.receive_time_us == 0);
        assert(p.payload_size    == 0);
        assert(!p.isReceived());

        std::cout << "✓ Test 4: Default constructor passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 5: Payload content is preserved exactly
    // --------------------------------------------------------
    {
        std::vector<uint8_t> payload = {0x01, 0xFF, 0x7F, 0x80, 0x00, 0xAB};
        Packet p(42, payload, 5000000);

        assert(p.payload.size() == payload.size());
        for (size_t i = 0; i < payload.size(); ++i) {
            assert(p.payload[i] == payload[i]);
        }

        std::cout << "✓ Test 5: Payload content preserved passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 6: Sequence number boundary — max uint32
    // --------------------------------------------------------
    {
        std::vector<uint8_t> payload = {0};
        uint32_t max_seq = 0xFFFFFFFFu;
        Packet p(max_seq, payload, 9999999);

        assert(p.sequence_number == max_seq);

        std::cout << "✓ Test 6: Max sequence number passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 7: Zero-length payload — getTotalSize == header only
    // --------------------------------------------------------
    {
        std::vector<uint8_t> empty;
        Packet p(0, empty, 1000);

        assert(p.payload_size == 0);
        assert(p.getTotalSize() == 14);

        std::cout << "✓ Test 7: Zero-length payload passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 8: Same send/receive timestamp — RTT == 0
    // --------------------------------------------------------
    {
        Packet p = make_received(10, 2000000, 2000000);

        assert(p.getRoundTripTime() == 0);
        assert(p.isReceived());   // receive_time_us != 0

        std::cout << "✓ Test 8: Zero RTT (same timestamps) passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 9: Large RTT — 500 ms
    // --------------------------------------------------------
    {
        Packet p = make_received(5, 0, 500000);

        assert(p.getRoundTripTime() == 500000);

        std::cout << "✓ Test 9: Large RTT 500 ms passed" << std::endl;
    }

    // --------------------------------------------------------
    // Test 10: isReceived transitions correctly
    // --------------------------------------------------------
    {
        std::vector<uint8_t> payload = {1};
        Packet p(0, payload, 1000);

        assert(!p.isReceived());     // initially not received
        p.receive_time_us = 5000;
        assert(p.isReceived());      // after setting receive time

        std::cout << "✓ Test 10: isReceived transition passed" << std::endl;
    }

    std::cout << "\nAll Packet tests passed! (10/10)" << std::endl;
    return 0;
}
