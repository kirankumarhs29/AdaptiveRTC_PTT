#include "netsense_mesh/routing_table.h"
#include <queue>

namespace netsense {

void RoutingTable::addNeighbor(const std::string &from, const std::string &to) {
    std::lock_guard<std::mutex> lock(mutex);
    adjacency[from].insert(to);
    adjacency[to];
}

void RoutingTable::removeNeighbor(const std::string &from, const std::string &to) {
    std::lock_guard<std::mutex> lock(mutex);
    auto it = adjacency.find(from);
    if (it != adjacency.end()) it->second.erase(to);
}

std::string RoutingTable::findNextHop(const std::string &source, const std::string &destination) {
    std::lock_guard<std::mutex> lock(mutex);

    if (source == destination) return destination;

    if (adjacency.find(source) == adjacency.end()) return "";

    std::unordered_map<std::string, std::string> prev;
    std::queue<std::string> q;
    std::unordered_set<std::string> visited;

    q.push(source);
    visited.insert(source);

    while (!q.empty()) {
        auto current = q.front();
        q.pop();

        auto neighborsIt = adjacency.find(current);
        if (neighborsIt == adjacency.end()) continue;

        for (const auto &neighbor : neighborsIt->second) {
            if (visited.count(neighbor)) continue;
            visited.insert(neighbor);
            prev[neighbor] = current;
            if (neighbor == destination) {
                // backtrack to get first hop
                std::string step = destination;
                std::string next = destination;
                while (prev[next] != source) {
                    next = prev[next];
                    if (next.empty()) break;
                }
                return next;
            }
            q.push(neighbor);
        }
    }

    return "";
}

bool RoutingTable::hasRoute(const std::string &source, const std::string &destination) {
    std::lock_guard<std::mutex> lock(mutex);
    if (adjacency.find(source) == adjacency.end() || adjacency.find(destination) == adjacency.end()) {
        return false;
    }

    std::unordered_set<std::string> visited;
    std::queue<std::string> q;

    q.push(source);
    visited.insert(source);

    while (!q.empty()) {
        auto current = q.front();
        q.pop();

        if (current == destination) return true;

        auto neighborsIt = adjacency.find(current);
        if (neighborsIt == adjacency.end()) continue;

        for (const auto &neighbor : neighborsIt->second) {
            if (visited.count(neighbor)) continue;
            visited.insert(neighbor);
            q.push(neighbor);
        }
    }

    return false;
}

std::vector<RouteEntry> RoutingTable::dumpRoutes() {
    std::lock_guard<std::mutex> lock(mutex);
    std::vector<RouteEntry> all;
    for (auto &entry : routes) {
        all.push_back(entry.second);
    }
    return all;
}

} // namespace netsense