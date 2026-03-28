#pragma once

#include <string>
#include <unordered_map>
#include <unordered_set>
#include <queue>
#include <mutex>

namespace mesh {

class RoutingTable {
public:
    void addNeighbor(const std::string &from, const std::string &to);
    void removeNeighbor(const std::string &from, const std::string &to);
    std::string findNextHop(const std::string &source, const std::string &destination);
    bool hasRoute(const std::string &source, const std::string &destination);

private:
    std::unordered_map<std::string, std::unordered_set<std::string>> adjacency;
    std::mutex mutex;
};

} // namespace mesh
