#include "netsense_mesh/state_machine.h"

namespace netsense {

NodeStateMachine::NodeStateMachine(StateChangedCallback cb)
    : callback(std::move(cb)), currentState(NodeState::Disconnected) {}

NodeState NodeStateMachine::getState() const {
    return currentState;
}

void NodeStateMachine::transition(const std::string &nodeId, NodeState next) {
    if (currentState == next) return;
    currentState = next;
    if (callback) callback(nodeId, next);
}

} // namespace netsense