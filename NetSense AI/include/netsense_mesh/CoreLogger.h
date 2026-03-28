/**
 * CoreLogger.h
 *
 * Thread-safe, file-backed logger for the C++ ECS/RTP/network layer.
 *
 * Design decisions:
 *  - Writes to core.log (path set at init-time by the JNI bridge).
 *  - Single std::mutex guards all writes — no lock-free trickery needed for
 *    the expected 2–3 concurrent callers (sendLoop, PONG handler, ECS analysis).
 *  - Internal append buffer (std::ostringstream) is built before locking,
 *    so formatting cost is off the critical path.
 *  - DEBUG level suppressed at compile-time in Release via CORE_LOG_ENABLE_DEBUG.
 *  - File rotation: when the file exceeds MAX_FILE_BYTES it is renamed to
 *    core.log.1 and a new core.log is opened (single-generation rollover).
 *  - Sampling helper rttSample() logs RTT only every N calls:
 *      CoreLogger::rttSample(rttMs, ecsStatus, rateBps);  // call on every measurement
 *
 * Log format (matches AppLogger.kt):
 *   [2026-03-21 12:45:23.456][INFO][ECS] RTT=28ms, trend=upward
 *
 * Usage:
 *   CoreLogger::init("/data/data/com.netsense.meshapp/files/core.log");
 *   CoreLogger::info(Module::ECS,  "detector reset");
 *   CoreLogger::debug(Module::RTP, "seq=42 sent 652 bytes");
 *   CoreLogger::warn(Module::NET,  "PONG timeout after 5s");
 *   CoreLogger::error(Module::ECS, "RateController::init failed");
 *   CoreLogger::shutdown();
 */

#pragma once

#include <string>
#include <string_view>
#include <cstdint>

namespace core_log {

// ── Modules (mirrors AppLogger.Module) ──────────────────────────────────────
enum class Module : uint8_t {
    SYSTEM  = 0,
    ECS     = 1,
    RTP     = 2,
    NETWORK = 3,
    AUDIO   = 4,
    JNI     = 5
};

inline std::string_view moduleName(Module m) {
    switch (m) {
        case Module::SYSTEM:  return "SYSTEM";
        case Module::ECS:     return "ECS";
        case Module::RTP:     return "RTP";
        case Module::NETWORK: return "NETWORK";
        case Module::AUDIO:   return "AUDIO";
        case Module::JNI:     return "JNI";
        default:              return "UNKNOWN";
    }
}

// ── Global toggle — set to false to suppress DEBUG in production ─────────────
extern bool g_debug_enabled;

// ── Sampling counter for RTT (logs every RTT_LOG_INTERVAL calls) ─────────────
static constexpr int RTT_LOG_INTERVAL = 25;

// ── Lifecycle ────────────────────────────────────────────────────────────────
void init(const std::string& path);   // call once from JNI_OnLoad or nativeInit
void shutdown();                       // flush + close

// ── Log functions ─────────────────────────────────────────────────────────────
void debug(Module module, std::string_view message);
void info (Module module, std::string_view message);
void warn (Module module, std::string_view message);
void error(Module module, std::string_view message);

/**
 * Conditional RTT logger.
 * Thread-safe; logs only every RTT_LOG_INTERVAL invocations.
 *
 * @param rtt_us   round-trip time in microseconds
 * @param status   "NO_CONGESTION" | "BUILDING" | "IMMINENT"
 * @param rate_bps current allowed bitrate
 */
void rttSample(uint64_t rtt_us, std::string_view status, uint32_t rate_bps);

} // namespace core_log
