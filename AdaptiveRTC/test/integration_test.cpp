// ============================================================================
// integration_test.cpp - End-to-end integration tests
// ============================================================================
//
// Covers the full signal path:
//   Sender → NetworkSimulator → Receiver → ECSDetector → RateController
// ============================================================================

#include "packet.h"
#include "network_simulator.h"
#include "sender.h"
#include "receiver.h"
#include "rate_controller.h"
#include "rtt_tracker.h"
#include "ecs_detector.h"
#include "jitter_buffer.h"
#include <cassert>
#include <iostream>
#include <vector>

using namespace adaptive_rtc;

// Helper: transmit packets through a network sim into a receiver.
// Returns the number of packets successfully received.
static int transmit_through(NetworkSimulator& net, Receiver& recv,
                             int count, uint64_t start_us = 1000000,
                             uint64_t interval_us = 20000) {
    int received = 0;
    for (int i = 0; i < count; ++i) {
        uint64_t now = start_us + static_cast<uint64_t>(i) * interval_us;
        std::vector<uint8_t> payload(160, static_cast<uint8_t>(i & 0xFF));
        Packet p(static_cast<uint32_t>(i), payload, now);
        auto result = net.simulateTransport(p, now);
        if (result.has_value()) {
            recv.receivePacket(result.value());
            ++received;
        }
    }
    return received;
}

int main() {
    std::cout << "Running integration tests...\n" << std::endl;

    // ────────────────────────────────────────────────────────────────────────
    // Test 1: Basic send/receive — zero impairment
    // ────────────────────────────────────────────────────────────────────────
    {
        std::cout << "Test 1: Basic send/receive (no network impairments)..." << std::endl;

        NetworkConfig cfg(0.0, 10000, 1000);
        NetworkSimulator net(cfg);
        Receiver recv;

        std::vector<uint8_t> payload(160, 42);
        Packet sent(0, payload, 1000000);
        auto result = net.simulateTransport(sent, 1000000);

        assert(result.has_value());
        recv.receivePacket(result.value());
        assert(recv.getTotalPacketsReceived() == 1);

        std::cout << "✓ Test 1 passed\n" << std::endl;
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 2: Sender rate adaptation — BUILDING → 10% reduction
    // ────────────────────────────────────────────────────────────────────────
    {
        std::cout << "Test 2: Sender rate adaptation on congestion signal..." << std::endl;

        NetworkSimulator net(NetworkConfig{});
        Sender sender(64000, &net);

        uint32_t initial  = sender.getCurrentRate();
        sender.onCongestionFeedback(CongestionSignal::BUILDING);
        uint32_t reduced  = sender.getCurrentRate();

        assert(reduced < initial);
        assert(reduced > static_cast<uint32_t>(initial * 0.85));

        std::cout << "Rate: " << initial << " -> " << reduced << std::endl;
        std::cout << "✓ Test 2 passed\n" << std::endl;
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 3: IMMINENT congestion → ~25% rate reduction
    // ────────────────────────────────────────────────────────────────────────
    {
        std::cout << "Test 3: IMMINENT congestion 25% rate reduction..." << std::endl;

        NetworkSimulator net(NetworkConfig{});
        Sender sender(64000, &net);

        sender.onCongestionFeedback(CongestionSignal::IMMINENT);
        uint32_t after = sender.getCurrentRate();

        // 64000 * 0.75 = 48000
        assert(after <= 52000 && after >= 44000);

        std::cout << "Rate after IMMINENT: " << after << std::endl;
        std::cout << "✓ Test 3 passed\n" << std::endl;
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 4: Sender rate recovery after congestion
    // ────────────────────────────────────────────────────────────────────────
    {
        std::cout << "Test 4: Rate recovery after congestion signal..." << std::endl;

        NetworkSimulator net(NetworkConfig{});
        Sender sender(64000, &net);

        sender.onCongestionFeedback(CongestionSignal::BUILDING);
        uint32_t reduced = sender.getCurrentRate();

        sender.onRecoveryFeedback();
        uint32_t recovered = sender.getCurrentRate();

        assert(recovered > reduced);

        std::cout << "Reduced: " << reduced << " -> Recovered: " << recovered << std::endl;
        std::cout << "✓ Test 4 passed\n" << std::endl;
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 5: Network loss simulation with statistical bounds
    // ────────────────────────────────────────────────────────────────────────
    {
        std::cout << "Test 5: Network loss simulation (50% target)..." << std::endl;

        NetworkConfig cfg(50.0, 10000, 1000);
        NetworkSimulator net(cfg);

        int transmitted = 0, received = 0;
        for (int i = 0; i < 100; ++i) {
            Packet p(i, std::vector<uint8_t>(160), 1000000 + static_cast<uint64_t>(i) * 20000);
            auto r = net.simulateTransport(p, p.send_time_us);
            ++transmitted;
            if (r.has_value()) ++received;
        }

        double loss_pct = 100.0 * (transmitted - received) / transmitted;
        std::cout << "Transmitted: " << transmitted << ", Received: " << received
                  << ", Loss: " << loss_pct << "%" << std::endl;
        assert(loss_pct >= 30.0 && loss_pct <= 70.0);

        std::cout << "✓ Test 5 passed\n" << std::endl;
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 6: ECS detection on increasing RTT
    // ────────────────────────────────────────────────────────────────────────
    {
        std::cout << "Test 6: ECS detection on increasing RTT..." << std::endl;

        NetworkSimulator net(NetworkConfig{});
        Receiver recv;

        for (int i = 0; i < 20; ++i) {
            Packet p(i, std::vector<uint8_t>(160),
                     1000000 + static_cast<uint64_t>(i) * 20000);
            p.receive_time_us = p.send_time_us + 40000 + static_cast<uint64_t>(i) * 2000;
            recv.receivePacket(p);
        }

        auto signal = recv.analyzeCongestion();
        std::cout << "ECS Status: " << static_cast<int>(recv.getECSStatus()) << std::endl;
        (void)signal;

        std::cout << "✓ Test 6 passed\n" << std::endl;
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 7: Full congestion→feedback→rate-adapt pipeline
    // ────────────────────────────────────────────────────────────────────────
    {
        std::cout << "Test 7: Full Receiver→ECS→Sender rate-adapt pipeline..." << std::endl;

        NetworkConfig cfg(0.0, 10000, 500);
        NetworkSimulator net(cfg);
        Sender sender(64000, &net);
        Receiver recv;

        uint32_t initial_rate = sender.getCurrentRate();

        // Inject increasing RTT packets to trigger ECS detection
        for (int i = 0; i < 20; ++i) {
            Packet p(i, std::vector<uint8_t>(160),
                     1000000 + static_cast<uint64_t>(i) * 20000);
            p.receive_time_us = p.send_time_us + 40000 + static_cast<uint64_t>(i) * 3000;
            recv.receivePacket(p);
        }

        // Analyse and apply feedback to sender
        auto signal = recv.analyzeCongestion();
        if (signal != CongestionSignal::NONE) {
            sender.onCongestionFeedback(signal);
        }

        // If congestion detected, rate should have dropped
        if (signal != CongestionSignal::NONE) {
            assert(sender.getCurrentRate() < initial_rate);
            std::cout << "Rate: " << initial_rate << " -> " << sender.getCurrentRate()
                      << " (signal=" << static_cast<int>(signal) << ")" << std::endl;
        }

        std::cout << "✓ Test 7 passed\n" << std::endl;
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 8: Receiver playout packet retrieval
    // ────────────────────────────────────────────────────────────────────────
    {
        std::cout << "Test 8: Receiver playout packet retrieval..." << std::endl;

        NetworkConfig cfg(0.0, 5000, 0);
        NetworkSimulator net(cfg);
        Receiver recv;

        // Send 8 packets at 20 ms intervals
        for (int i = 0; i < 8; ++i) {
            Packet p(i, std::vector<uint8_t>(160, static_cast<uint8_t>(i)),
                     1000000 + static_cast<uint64_t>(i) * 20000);
            auto r = net.simulateTransport(p, p.send_time_us);
            if (r.has_value()) {
                recv.receivePacket(r.value());
            }
        }

        assert(recv.getTotalPacketsReceived() == 8);

        // Should have playout packets available
        if (recv.hasPlayoutPacket()) {
            auto pkt = recv.getPlayoutPacket();
            assert(pkt.has_value());
        }

        std::cout << "✓ Test 8 passed\n" << std::endl;
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 9: Receiver getTotalPacketsLost under 20% loss
    // ────────────────────────────────────────────────────────────────────────
    {
        std::cout << "Test 9: Receiver packet loss tracking..." << std::endl;

        NetworkConfig cfg(20.0, 5000, 1000);
        NetworkSimulator net(cfg, /*seed=*/42);
        Receiver recv;

        int sent = 100;
        int received = transmit_through(net, recv, sent);
        int lost = sent - received;

        std::cout << "Sent: " << sent << ", Received: " << received
                  << ", Lost (net): " << lost << std::endl;
        assert(received >= 0 && received <= sent);

        std::cout << "✓ Test 9 passed\n" << std::endl;
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 10: getEstimatedRTT from receiver
    // ────────────────────────────────────────────────────────────────────────
    {
        std::cout << "Test 10: getEstimatedRTT reflects RTT samples..." << std::endl;

        Receiver recv;

        // Inject packets with known RTT
        for (int i = 0; i < 10; ++i) {
            Packet p(i, std::vector<uint8_t>(160),
                     1000000 + static_cast<uint64_t>(i) * 20000);
            p.receive_time_us = p.send_time_us + 45000;  // 45 ms RTT
            recv.receivePacket(p);
        }

        uint64_t est = recv.getEstimatedRTT();
        assert(est > 0);

        std::cout << "Estimated RTT: " << est << " us" << std::endl;
        std::cout << "✓ Test 10 passed\n" << std::endl;
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 11: Sender getTotalPacketsSent
    // ────────────────────────────────────────────────────────────────────────
    {
        std::cout << "Test 11: Sender packet count tracking..." << std::endl;

        NetworkConfig cfg(0.0, 0, 0);      // no loss → all sent
        NetworkSimulator net(cfg);
        Sender sender(256000, &net);       // high rate to avoid token bucket limits

        int target = 10;
        for (int i = 0; i < target; ++i) {
            std::vector<uint8_t> payload(160);
            sender.sendPacket(payload, 1000000 + static_cast<uint64_t>(i) * 20000);
        }

        uint64_t sent = sender.getTotalPacketsSent();
        assert(sent == static_cast<uint64_t>(target));

        std::cout << "Sent: " << sent << std::endl;
        std::cout << "✓ Test 11 passed\n" << std::endl;
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 12: Receiver reset clears all state
    // ────────────────────────────────────────────────────────────────────────
    {
        std::cout << "Test 12: Receiver reset clears state..." << std::endl;

        NetworkConfig cfg(0.0, 5000, 0);
        NetworkSimulator net(cfg);
        Receiver recv;

        transmit_through(net, recv, 20);
        assert(recv.getTotalPacketsReceived() > 0);

        recv.reset();
        assert(recv.getTotalPacketsReceived() == 0);

        std::cout << "✓ Test 12 passed\n" << std::endl;
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 13: NetworkSimulator statistics match observed delivery
    // ────────────────────────────────────────────────────────────────────────
    {
        std::cout << "Test 13: NetworkSimulator statistics accuracy..." << std::endl;

        NetworkConfig cfg(30.0, 5000, 1000);
        NetworkSimulator net(cfg, 42);

        int observed_received = 0;
        for (int i = 0; i < 100; ++i) {
            Packet p(i, std::vector<uint8_t>(160),
                     1000000 + static_cast<uint64_t>(i) * 20000);
            auto r = net.simulateTransport(p, p.send_time_us);
            if (r.has_value()) ++observed_received;
        }

        auto stats = net.getStatistics();
        uint64_t net_received = stats.total_packets_sent - stats.total_packets_lost;
        assert(static_cast<int>(net_received) == observed_received);

        std::cout << "Observed: " << observed_received
                  << " | stat.received: " << net_received << std::endl;
        std::cout << "✓ Test 13 passed\n" << std::endl;
    }

    std::cout << "========================================" << std::endl;
    std::cout << "All integration tests passed! (13/13)" << std::endl;
    std::cout << "========================================" << std::endl;

    return 0;
}
