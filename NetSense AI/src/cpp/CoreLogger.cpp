/**
 * CoreLogger.cpp
 *
 * Implementation of the C++ core logger.
 * See CoreLogger.h for design notes and usage.
 */

#include "netsense_mesh/CoreLogger.h"

#include <fstream>
#include <sstream>
#include <mutex>
#include <chrono>
#include <ctime>
#include <cstdio>    // std::rename
#include <atomic>

// Android logcat integration (no-op on desktop)
#ifdef __ANDROID__
#   include <android/log.h>
#   define ANDROID_LOG(prio, tag, msg) __android_log_write(prio, tag, (msg).c_str())
#else
#   define ANDROID_LOG(prio, tag, msg) ((void)0)
#endif

namespace core_log {

// ── Configuration ─────────────────────────────────────────────────────────────
static constexpr std::streamoff MAX_FILE_BYTES  = 2LL * 1024 * 1024; // 2 MB
static constexpr int            FLUSH_EVERY_N   = 20;                 // lines

// ── Module-level state ────────────────────────────────────────────────────────
bool g_debug_enabled = true;

namespace {
    std::mutex      g_mutex;
    std::ofstream   g_file;
    std::string     g_path;
    int             g_lines_since_flush = 0;
    std::atomic<int> g_rtt_call_count{0};

    // ── Timestamp formatting ──────────────────────────────────────────────────
    // Returns "2026-03-21 12:45:23.456"
    std::string timestamp() {
        using namespace std::chrono;
        auto now   = system_clock::now();
        auto ms    = duration_cast<milliseconds>(now.time_since_epoch()) % 1000;
        std::time_t tt = system_clock::to_time_t(now);
        std::tm tm{};
#ifdef _WIN32
        localtime_s(&tm, &tt);
#else
        localtime_r(&tt, &tm);
#endif
        char buf[24];
        std::snprintf(buf, sizeof(buf),
            "%04d-%02d-%02d %02d:%02d:%02d.%03d",
            tm.tm_year + 1900, tm.tm_mon + 1, tm.tm_mday,
            tm.tm_hour, tm.tm_min, tm.tm_sec,
            static_cast<int>(ms.count()));
        return buf;
    }

    // ── Rotate when file exceeds MAX_FILE_BYTES ───────────────────────────────
    // Must be called with g_mutex held.
    void rotateIfNeeded() {
        if (!g_file.is_open()) return;
        auto pos = g_file.tellp();
        if (pos < MAX_FILE_BYTES) return;

        g_file.flush();
        g_file.close();

        std::string backup = g_path + ".1";
        std::remove(backup.c_str());
        std::rename(g_path.c_str(), backup.c_str());

        g_file.open(g_path, std::ios::out | std::ios::app);
        g_lines_since_flush = 0;

        // write rotation notice without recursing
        if (g_file.is_open()) {
            g_file << "[" << timestamp() << "][INFO][SYSTEM] log rotated"
                   << " previous=" << backup << "\n";
        }
    }

    // ── Core write (must be called with g_mutex held) ─────────────────────────
    void writeLine(std::string_view level, Module module, std::string_view message) {
        if (!g_file.is_open()) return;

        // Format: [2026-03-21 12:45:23.456][INFO][ECS] message
        g_file << '[' << timestamp() << "]["
               << level             << "]["
               << moduleName(module) << "] "
               << message           << '\n';

        ++g_lines_since_flush;
        if (g_lines_since_flush >= FLUSH_EVERY_N) {
            g_file.flush();
            g_lines_since_flush = 0;
        }
        rotateIfNeeded();
    }

    // ── Android logcat priority map ───────────────────────────────────────────
#ifdef __ANDROID__
    int androidPriority(std::string_view level) {
        if (level == "DEBUG") return ANDROID_LOG_DEBUG;
        if (level == "INFO")  return ANDROID_LOG_INFO;
        if (level == "WARN")  return ANDROID_LOG_WARN;
        if (level == "ERROR") return ANDROID_LOG_ERROR;
        return ANDROID_LOG_VERBOSE;
    }
#endif

    void log(std::string_view level, Module module, std::string_view message) {
        // Build the logcat/stderr string before acquiring the file mutex
        // so formatting cost is off the critical path.
        std::string full;
        full.reserve(message.size() + 8);
        full = message;

#ifdef __ANDROID__
        std::string tag(moduleName(module));
        ANDROID_LOG(androidPriority(level), tag.c_str(), full);
#endif

        std::lock_guard<std::mutex> lock(g_mutex);
        writeLine(level, module, full);
    }
} // anonymous namespace

// ── Public API ────────────────────────────────────────────────────────────────

void init(const std::string& path) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_path = path;
    if (g_file.is_open()) g_file.close();
    g_file.open(path, std::ios::out | std::ios::app);
    g_lines_since_flush = 0;
    if (g_file.is_open()) {
        writeLine("INFO", Module::SYSTEM,
                  "CoreLogger initialised path=" + path);
    }
}

void shutdown() {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_file.is_open()) return;
    writeLine("INFO", Module::SYSTEM, "CoreLogger shutting down");
    g_file.flush();
    g_file.close();
}

void debug(Module module, std::string_view message) {
    if (!g_debug_enabled) return;
    log("DEBUG", module, message);
}

void info(Module module, std::string_view message) {
    log("INFO", module, message);
}

void warn(Module module, std::string_view message) {
    log("WARN", module, message);
}

void error(Module module, std::string_view message) {
    log("ERROR", module, message);
}

void rttSample(uint64_t rtt_us, std::string_view status, uint32_t rate_bps) {
    int count = ++g_rtt_call_count;
    if (count % RTT_LOG_INTERVAL != 0) return;

    // Build message without holding the mutex.
    std::ostringstream oss;
    oss << "rtt=" << (rtt_us / 1000) << "ms"
        << " ecs=" << status
        << " rate=" << (rate_bps / 1000) << "kbps"
        << " sample=" << count;

    debug(Module::ECS, oss.str());
}

} // namespace core_log
