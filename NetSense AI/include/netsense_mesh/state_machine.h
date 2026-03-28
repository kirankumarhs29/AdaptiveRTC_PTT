#pragma once

#include "node.h"
#include <functional>

namespace netsense {

using StateChangedCallback = std::function<void(const std::string&, NodeState)>;

class NodeStateMachine {
public:
    explicit NodeStateMachine(StateChangedCallback cb);
    NodeState getState() const;
    void transition(const std::string &nodeId, NodeState next);

private:
    NodeState currentState = NodeState::Disconnected;
    StateChangedCallback callback;
};

} // namespace netsense
