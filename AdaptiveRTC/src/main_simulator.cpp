// ============================================================================
// main_simulator.cpp - Simulation Entry Point
// ============================================================================
//
// EXECUTABLE: rtc_simulator
//
// PURPOSE:
// Run a complete end-to-end simulation:
// 1. Sender transmits packets
// 2. Network adds jitter/loss/delay
// 3. Receiver analyzes and detects congestion
// 4. ECS signals sent back to sender
// 5. Sender adapts transmission rate
//
// SIMULATION SCENARIOS:
// Scenario 1: Steady state (no congestion)
// Scenario 2: Congestion onset (delay increases gradually)
// Scenario 3: Packet loss (sudden increase)
// Scenario 4: Transient spike (brief congestion, clears)
// Scenario 5: Sustained congestion then recovery
//
// ============================================================================

#include "packet.h"
#include "network_simulator.h"
#include "sender.h"
#include "receiver.h"
#include "metrics_logger.h"
#include <iostream>
#include <vector>
#include <chrono>
#include <cmath>

using namespace adaptive_rtc;

// ============================================================================
// Simulation Parameters
// ============================================================================

constexpr uint64_t SIMULATION_TIME_MS = 10000;     // 10 seconds
constexpr uint64_t PACKET_DURATION_US = 20000;    // 20 ms packets
constexpr uint32_t INITIAL_RATE_BPS = 64000;      // 64 kbit/s
constexpr uint16_t PAYLOAD_SIZE_BYTES = 160;      // 160 bytes per 20ms at 64kbit/s

// ============================================================================
// Scenario Definitions
// ============================================================================

struct ScenarioConfig {
    std::string name;
    
    // Time ranges for network conditions
    uint64_t start_ms;
    uint64_t end_ms;
    
    double packet_loss_pct;
    uint64_t base_delay_us;
    uint64_t jitter_stddev_us;
};

std::vector<ScenarioConfig> defineScenarios() {
    return {
        // Scenario 1: Baseline (0-2000ms)
        // No congestion, stable 20ms RTT
        {
            "Baseline (0-2s)",
            0, 2000,
            0.0,           // 0% loss
            10000,         // 10ms base delay
            5000           // 5ms jitter
        },
        
        // Scenario 2: Congestion Building (2000-5000ms)
        // Delay increases gradually, simulating buffer buildup
        {
            "Building (2-5s)",
            2000, 5000,
            2.0,           // 2% loss
            50000,         // 50ms base delay (congestion!)
            15000          // 15ms jitter (increased)
        },
        
        // Scenario 3: Congestion Peak (5000-7000ms)
        // Aggressive congestion
        {
            "Peak (5-7s)",
            5000, 7000,
            10.0,          // 10% loss (significant)
            100000,        // 100ms base delay (bad!)
            30000          // 30ms jitter (high variation)
        },
        
        // Scenario 4: Recovery (7000-10000ms)
        // Network improves
        {
            "Recovery (7-10s)",
            7000, 10000,
            1.0,           // 1% loss (improving)
            20000,         // 20ms base delay (better)
            5000           // 5ms jitter (normalized)
        },
    };
}

// ============================================================================
// Scenario-Based Network Configuration
// ============================================================================

NetworkConfig getNetworkConfigForTime(uint64_t time_ms) {
    auto scenarios = defineScenarios();
    
    for (const auto& scenario : scenarios) {
        if (time_ms >= scenario.start_ms && time_ms < scenario.end_ms) {
            // Smooth transition within scenario
            double progress = (time_ms - scenario.start_ms) / 
                             static_cast<double>(scenario.end_ms - scenario.start_ms);
            
            // For now, just use the config as-is
            return NetworkConfig(
                scenario.packet_loss_pct,
                scenario.base_delay_us,
                scenario.jitter_stddev_us
            );
        }
    }
    
    // Default: stable
    return NetworkConfig(0.0, 10000, 5000);
}

// ============================================================================
// Packet Generation (Simulate "Audio Encoder")
// ============================================================================

std::vector<uint8_t> generateAudioPayload(size_t size_bytes) {
    // In real system: actual audio codec output
    // In simulation: just random data (we only care about structure, not content)
    std::vector<uint8_t> payload(size_bytes);
    for (size_t i = 0; i < size_bytes; ++i) {
        payload[i] = static_cast<uint8_t>(i % 256);
    }
    return payload;
}

// ============================================================================
// Main Simulation Loop
// ============================================================================

int main() {
    std::cout << "========== AdaptiveRTC Simulation ==========" << std::endl;
    std::cout << "Simulating intelligent voice communication system" << std::endl;
    std::cout << "with ECS detection and rate adaptation" << std::endl << std::endl;
    
    // Create components
    NetworkConfig initial_config = getNetworkConfigForTime(0);
    NetworkSimulator network(initial_config, 42);  // Seed 42 for reproducibility
    
    Sender sender(INITIAL_RATE_BPS, &network);
    Receiver receiver(PACKET_DURATION_US);
    
    MetricsLogger logger("simulation_results.csv");
    
    std::cout << "Initial Configuration:" << std::endl;
    std::cout << "  Loss: 0%, RTT: ~15ms, Jitter: ±5ms" << std::endl;
    std::cout << "  Payload: " << PAYLOAD_SIZE_BYTES << " bytes/packet" << std::endl;
    std::cout << "  Initial Rate: " << INITIAL_RATE_BPS << " bps" << std::endl;
    std::cout << "  Simulation Duration: " << SIMULATION_TIME_MS << " ms" << std::endl << std::endl;
    
    // Simulation loop
    uint64_t last_feedback_time_ms = 0;
    const uint64_t FEEDBACK_INTERVAL_MS = 100;  // Send feedback every 100ms
    
    for (uint64_t time_ms = 0; time_ms < SIMULATION_TIME_MS; time_ms += 20) {
        uint64_t time_us = time_ms * 1000;
        
        // STEP 1: Update network conditions for this time
        NetworkConfig config = getNetworkConfigForTime(time_ms);
        network.updateConfig(config);
        
        // STEP 2: Sender transmits packet
        auto payload = generateAudioPayload(PAYLOAD_SIZE_BYTES);
        sender.sendPacket(payload, time_us);
        
        // STEP 3: Receiver processes available packets and logs metrics
        // (In a real system, this would be async via network. Here it's synchronous for simulation)
        
        // STEP 4: Periodic feedback (every 100ms)
        if (time_ms - last_feedback_time_ms >= FEEDBACK_INTERVAL_MS) {
            // Analyze congestion
            CongestionSignal signal = receiver.analyzeCongestion();
            
            // Send feedback to sender
            if (signal == CongestionSignal::IMMINENT) {
                sender.onCongestionFeedback(signal);
            } else if (signal == CongestionSignal::BUILDING) {
                sender.onCongestionFeedback(signal);
            } else {
                // Try recovery
                sender.onRecoveryFeedback();
            }
            
            last_feedback_time_ms = time_ms;
            
            // Log metrics
            logger.logSnapshot(time_ms, sender, receiver, network);
        }
    }
    
    // Print summary
    std::cout << "\nSimulation Complete!" << std::endl;
    std::cout << "Results logged to: simulation_results.csv" << std::endl << std::endl;
    
    std::cout << "Final Statistics:" << std::endl;
    std::cout << "  Packets Sent: " << sender.getTotalPacketsSent() << std::endl;
    std::cout << "  Packets Received: " << receiver.getTotalPacketsReceived() << std::endl;
    std::cout << "  Packets Lost: " << receiver.getTotalPacketsLost() << std::endl;
    std::cout << "  Loss Rate: " << 
        (100.0 * receiver.getTotalPacketsLost() / sender.getTotalPacketsSent()) << "%" << std::endl;
    std::cout << "  Final RTT: " << receiver.getEstimatedRTT() << " ms" << std::endl;
    std::cout << "  Final Rate: " << sender.getCurrentRate() << " bps" << std::endl;
    
    return 0;
}

// ============================================================================
// EXPECTED OUTPUT:
// ===========================
// Simulation runs for 10 seconds:
// 
// Phase 0-2s (Baseline):
//   - Minimal loss/delay
//   - Sender rate stable at 64 kbit/s
//   - RTT around 15-20ms
//   - Status: NO_CONGESTION
//
// Phase 2-5s (Building):
//   - Delay increases from 50ms
//   - ECS detector: CONGESTION_BUILDING
//   - Sender: rate reduces to 57.6 kbit/s (90%)
//   - RTT trend: INCREASING
//
// Phase 5-7s (Peak):
//   - 10% loss, 100ms delay
//   - ECS detector: CONGESTION_IMMINENT
//   - Sender: rate reduces to 43.2 kbit/s (75%)
//   - Some packet loss in jitter buffer
//
// Phase 7-10s (Recovery):
//   - Conditions improve
//   - ECS detector: NO_CONGESTION
//   - Sender: rate increases cautiously to 45.36 kbit/s (5% increase)
//   - RTT trend: DECREASING
//   - System stabilizes
//
// ============================================================================
