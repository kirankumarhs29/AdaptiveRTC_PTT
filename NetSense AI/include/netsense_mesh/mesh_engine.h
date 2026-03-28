#pragma once

#include "node.h"
#include "routing_table.h"
#include "state_machine.h"

#include <string>
#include <unordered_map>
#include <mutex>
#include <optional>
#include <functional>

namespace netsense {

struct MeshMessage {
    std::string source;
    std::string destination;
    std::string payload;
    int ttl = 8;
    uint64_t sequence = 0;
};

enum class SendResult {
    Success,
    NoRoute,
    InvalidNode,
    TtlExpired,
    LocalDestination
};

class MeshEngine {
public:
    MeshEngine(const std::string &localNodeId);
    ~MeshEngine();

    void setStateChangeCallback(StateChangedCallback cb);
    void setOnPeerDiscovered(std::function<void(const std::string&, const std::string&, int)> callback);
    void setOnMessageReceived(std::function<void(const std::string&, const std::string&, const std::string&)> callback);
    void setOnConnectionStateChanged(std::function<void(NodeState)> callback);

    void onPeerDiscovered(const std::string &peerId, const std::string &peerName, int rssi);
    void handshakeWithPeer(const std::string &peerId);
    void establishConnection(const std::string &peerId);
    void disconnectPeer(const std::string &peerId);

    SendResult sendMessage(const MeshMessage &message);
    void receiveMessage(const MeshMessage &message);

    std::optional<std::string> routeTo(const std::string &destination);
    std::string getLocalNodeId() const;

private:
    void updateRouteGraph(const std::string &peerId);
    void runRoutingFor(const std::string &destination);

private:
    const std::string localNodeId;
    std::unordered_map<std::string, NodeInfo> peers;
    RoutingTable routingTable;
    NodeStateMachine stateMachine;
    mutable std::mutex mutex;
    uint64_t sequenceCounter = 1;

    std::function<void(const std::string&, const std::string&, int)> peerDiscoveredCallback;
    std::function<void(const std::string&, const std::string&, const std::string&)> messageReceivedCallback;
    std::function<void(NodeState)> connectionStateChangedCallback;
};

} // namespace netsense
