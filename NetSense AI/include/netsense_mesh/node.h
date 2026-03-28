#pragma once

#include <string>
#include <chrono>

namespace netsense {

enum class NodeState {
    Disconnected,
    Connecting,
    Handshake,
    Connected,
    Failed
};

struct NodeInfo {
    std::string nodeId;
    std::string deviceName;
    NodeState state = NodeState::Disconnected;
    std::chrono::steady_clock::time_point lastSeen;
    int signalStrength = 0;

    NodeInfo() = default;
    NodeInfo(std::string id, std::string name)
        : nodeId(std::move(id)), deviceName(std::move(name)), state(NodeState::Disconnected) {
        lastSeen = std::chrono::steady_clock::now();
    }
};

} // namespace netsense