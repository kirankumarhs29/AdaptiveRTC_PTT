#include "routing_table.h"

#include <vector>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <queue>
#include <mutex>

namespace mesh {

struct RouteEntry {
    std::string destination;
    std::string nextHop;
    int metric;
};


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
    if (source == destination) return source;

        std::queue<std::string> q;
        std::unordered_map<std::string, std::string> parent;
        std::unordered_set<std::string> visited;

        q.push(source);
        visited.insert(source);

        while (!q.empty()) {
            auto cur = q.front();
            q.pop();
            auto it = adjacency.find(cur);
            if (it == adjacency.end()) continue;

            for (auto &nbr : it->second) {
                if (visited.count(nbr)) continue;
                visited.insert(nbr);
                parent[nbr] = cur;
                if (nbr == destination) {
                    std::string n = destination;
                    std::string prev = parent[n];
                    while (prev != source) {
                        n = prev;
                        prev = parent[n];
                    }
                    return n;
                }
                q.push(nbr);
            }
        }

        return "";
    }


bool RoutingTable::hasRoute(const std::string &source, const std::string &destination) {
    std::lock_guard<std::mutex> lock(mutex);
    if (source == destination) return true;

    std::queue<std::string> q;
    std::unordered_set<std::string> visited;

    q.push(source);
    visited.insert(source);

        while (!q.empty()) {
            auto cur = q.front();
            q.pop();
            auto it = adjacency.find(cur);
            if (it == adjacency.end()) continue;
            for (auto &nbr : it->second) {
                if (nbr == destination) return true;
                if (visited.count(nbr)) continue;
                visited.insert(nbr);
                q.push(nbr);
            }
        }

    return false;
}

} // namespace mesh
