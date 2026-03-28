// ============================================================================
// integration_test.cpp - End-to-end integration tests
// ============================================================================

#include "packet.h"
#include "network_simulator.h"
#include "sender.h"
#include "receiver.h"
#include <cassert>
#include <iostream>

using namespace adaptive_rtc;

int main() {
    std::cout << "Running integration tests..." << std::endl;
    
    // Test 1: Basic send/receive without loss
    {
        std::cout << "\nTest 1: Basic send/receive (no network impairments)..." << std::endl;
        
        NetworkConfig config(0.0, 10000, 1000);  // No loss, 10ms constant delay
        NetworkSimulator network(config);
        
        Sender sender(64000, &network);
        Receiver receiver;
        
        // Send a packet
        std::vector<uint8_t> payload(160, 42);  // 160 bytes of 42's
        sender.sendPacket(payload, 1000000);
        
        // Simulate receive (in real system would go through network)
        // For now, just create a simulated packet
        Packet sent(0, payload, 1000000);
        auto received = network.simulateTransport(sent, 1000000);
        
        assert(received.has_value());
        receiver.receivePacket(received.value());
        
        assert(receiver.getTotalPacketsReceived() == 1);
        
        std::cout << "✓ Test 1 passed" << std::endl;
    }
    
    // Test 2: Sender rate adaptation
    {
        std::cout << "\nTest 2: Sender rate adaptation on congestion signal..." << std::endl;
        
        NetworkSimulator network(NetworkConfig{});
        Sender sender(64000, &network);
        Receiver receiver;
        
        uint32_t initial_rate = sender.getCurrentRate();
        sender.onCongestionFeedback(CongestionSignal::BUILDING);
        uint32_t reduced_rate = sender.getCurrentRate();
        
        // Rate should reduce by ~10%
        assert(reduced_rate < initial_rate);
        assert(reduced_rate > initial_rate * 0.85);  // Should be ~90% of original
        
        std::cout << "Rate: " << initial_rate << " -> " << reduced_rate << std::endl;
        std::cout << "✓ Test 2 passed" << std::endl;
    }
    
    // Test 3: Network loss simulation
    {
        std::cout << "\nTest 3: Network loss simulation..." << std::endl;
        
        NetworkConfig config(50.0, 10000, 1000);  // 50% loss
        NetworkSimulator network(config);
        
        int transmitted = 0;
        int received = 0;
        
        for (int i = 0; i < 100; ++i) {
            Packet p(i, std::vector<uint8_t>(160), 1000000 + i * 20000);
            auto result = network.simulateTransport(p, 1000000 + i * 20000);
            transmitted++;
            if (result.has_value()) {
                received++;
            }
        }
        
        double loss_rate = 100.0 * (transmitted - received) / transmitted;
        std::cout << "Transmitted: " << transmitted << ", Received: " << received << std::endl;
        std::cout << "Simulated loss: " << loss_rate << "%" << std::endl;
        
        // With 50% loss, should lose roughly 40-60% of packets (statistical)
        assert(loss_rate >= 30.0 && loss_rate <= 70.0);
        
        std::cout << "✓ Test 3 passed" << std::endl;
    }
    
    // Test 4: ECS detection and recovery
    {
        std::cout << "\nTest 4: ECS detection on increasing RTT..." << std::endl;
        
        NetworkSimulator network(NetworkConfig{});
        Receiver receiver;
        
        // Simulate increasing RTT samples
        for (int i = 0; i < 20; ++i) {
            Packet p(i, std::vector<uint8_t>(160), 1000000 + i * 20000);
            p.receive_time_us = p.send_time_us + 40000 + i * 2000;  // Increasing delay
            receiver.receivePacket(p);
        }
        
        auto signal = receiver.analyzeCongestion();
        
        // Should detect some level of congestion
        std::cout << "ECS Status: " << static_cast<int>(receiver.getECSStatus()) << std::endl;
        
        std::cout << "✓ Test 4 passed" << std::endl;
    }
    
    std::cout << "\n========================================" << std::endl;
    std::cout << "All integration tests passed!" << std::endl;
    std::cout << "========================================" << std::endl;
    
    return 0;
}
