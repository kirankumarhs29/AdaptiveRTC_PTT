#pragma once

#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>
#include <mutex>

namespace netsense {

struct RouteEntry {
    std::string destination;
    std::string nextHop;
    int metric = 0;
};

class RoutingTable {
public:
    void addNeighbor(const std::string &from, const std::string &to);
    void removeNeighbor(const std::string &from, const std::string &to);
    std::string findNextHop(const std::string &source, const std::string &destination);
    bool hasRoute(const std::string &source, const std::string &destination);
    std::vector<RouteEntry> dumpRoutes();

private:
    std::unordered_map<std::string, std::unordered_set<std::string>> adjacency;
    std::unordered_map<std::string, RouteEntry> routes;
    std::mutex mutex;
};

} // namespace netsense
