#include "netsense_mesh/mesh_engine.h"
#include <iostream>

namespace netsense {

MeshEngine::MeshEngine(const std::string &localNodeId)
    : localNodeId(localNodeId), stateMachine([this](const std::string &peer, NodeState state) {
          if (connectionStateChangedCallback) connectionStateChangedCallback(state);
      }) {
}

void MeshEngine::setOnPeerDiscovered(std::function<void(const std::string&, const std::string&, int)> callback) {
    peerDiscoveredCallback = std::move(callback);
}

void MeshEngine::setOnMessageReceived(std::function<void(const std::string&, const std::string&, const std::string&)> callback) {
    messageReceivedCallback = std::move(callback);
}

void MeshEngine::setOnConnectionStateChanged(std::function<void(NodeState)> callback) {
    connectionStateChangedCallback = std::move(callback);
}

MeshEngine::~MeshEngine() = default;

std::string MeshEngine::getLocalNodeId() const {
    return localNodeId;
}

void MeshEngine::setStateChangeCallback(StateChangedCallback cb) {
    stateMachine = NodeStateMachine(std::move(cb));
}

void MeshEngine::onPeerDiscovered(const std::string &peerId, const std::string &peerName, int rssi) {
    std::lock_guard<std::mutex> lock(mutex);
    auto it = peers.find(peerId);
    if (it == peers.end()) {
        peers.emplace(peerId, NodeInfo(peerId, peerName));
        peers[peerId].state = NodeState::Disconnected;
    }
    peers[peerId].signalStrength = rssi;
    peers[peerId].lastSeen = std::chrono::steady_clock::now();
    updateRouteGraph(peerId);

    if (peerDiscoveredCallback) {
        peerDiscoveredCallback(peerId, peerName, rssi);
    }
}

void MeshEngine::handshakeWithPeer(const std::string &peerId) {
    std::lock_guard<std::mutex> lock(mutex);
    auto it = peers.find(peerId);
    if (it == peers.end()) {
        return;
    }

    it->second.state = NodeState::Handshake;
    stateMachine.transition(peerId, NodeState::Handshake);
    // Simulated handshake; in real world perform crypto exchange
    it->second.state = NodeState::Connected;
    stateMachine.transition(peerId, NodeState::Connected);
    establishConnection(peerId);
}

void MeshEngine::establishConnection(const std::string &peerId) {
    std::lock_guard<std::mutex> lock(mutex);
    auto it = peers.find(peerId);
    if (it == peers.end()) return;
    it->second.state = NodeState::Connected;
    stateMachine.transition(peerId, NodeState::Connected);
    updateRouteGraph(peerId);
}

void MeshEngine::disconnectPeer(const std::string &peerId) {
    std::lock_guard<std::mutex> lock(mutex);
    auto it = peers.find(peerId);
    if (it != peers.end()) {
        it->second.state = NodeState::Disconnected;
        stateMachine.transition(peerId, NodeState::Disconnected);
    }
    // route cleanup
    routingTable.removeNeighbor(localNodeId, peerId);
}

SendResult MeshEngine::sendMessage(const MeshMessage &message) {
    std::lock_guard<std::mutex> lock(mutex);

    if (message.source != localNodeId) {
        std::cout << "Error: invalid source for outgoing message: " << message.source << std::endl;
        return SendResult::InvalidNode;
    }

    if (message.destination == localNodeId) {
        std::cout << "Error: message destination is local node " << localNodeId << "" << std::endl;
        return SendResult::LocalDestination;
    }

    if (message.ttl <= 0) {
        std::cout << "Error: TTL expired for message to " << message.destination << std::endl;
        return SendResult::TtlExpired;
    }

    if (!routingTable.hasRoute(localNodeId, message.destination)) {
        std::cout << "Error: no route to " << message.destination << std::endl;
        return SendResult::NoRoute;
    }

    auto nextHop = routeTo(message.destination);
    if (!nextHop.has_value()) {
        std::cout << "Error: routeTo could not resolve next hop for " << message.destination << std::endl;
        return SendResult::NoRoute;
    }

    // In production, would serialize and transmit over Wi-Fi Direct socket
    std::cout << "Forwarding message from " << message.source
              << " to " << message.destination
              << " via " << nextHop.value() << " TTL=" << message.ttl << std::endl;

    if (messageReceivedCallback) {
        messageReceivedCallback(message.source, message.destination, message.payload);
    }

    return SendResult::Success;
}

void MeshEngine::receiveMessage(const MeshMessage &message) {
    std::lock_guard<std::mutex> lock(mutex);

    if (message.destination == localNodeId) {
        std::cout << "Received message for local node: " << message.payload << std::endl;
        if (messageReceivedCallback) {
            messageReceivedCallback(message.source, message.destination, message.payload);
        }
        return;
    }

    if (message.ttl <= 0) {
        std::cout << "Dropping message from " << message.source << " due TTL expired" << std::endl;
        return;
    }

    if (!routingTable.hasRoute(localNodeId, message.destination)) {
        std::cout << "No route for message to " << message.destination << ", dropping." << std::endl;
        return;
    }

    auto nextHop = routeTo(message.destination);
    if (!nextHop.has_value()) {
        std::cout << "No next hop for destination " << message.destination << " after route check" << std::endl;
        return;
    }

    MeshMessage relay = message;
    relay.ttl = message.ttl - 1;
    relay.sequence = sequenceCounter++;

    std::cout << "Relaying message from " << relay.source
              << " to " << relay.destination
              << " via " << nextHop.value()
              << " ttl=" << relay.ttl << std::endl;

    // In production, send to transport layer here.
}

std::optional<std::string> MeshEngine::routeTo(const std::string &destination) {
    if (destination == localNodeId) return localNodeId;
    updateRouteGraph(destination);
    auto nextHop = routingTable.findNextHop(localNodeId, destination);
    if (!nextHop.empty()) return nextHop;
    return std::nullopt;
}

void MeshEngine::updateRouteGraph(const std::string &peerId) {
    // Quick update: add local link to active peers
    if (peers.find(peerId) == peers.end()) return;
    routingTable.addNeighbor(localNodeId, peerId);

    // rebuild routes for discovered nodes
    for (auto const &entry : peers) {
        if (entry.first != localNodeId) {
            runRoutingFor(entry.first);
        }
    }
}

void MeshEngine::runRoutingFor(const std::string &destination) {
    auto nextHop = routingTable.findNextHop(localNodeId, destination);
    if (!nextHop.empty()) {
        RouteEntry route{destination, nextHop, 1};
        // placeholder: in production update route state
    }
}

} // namespace netsense