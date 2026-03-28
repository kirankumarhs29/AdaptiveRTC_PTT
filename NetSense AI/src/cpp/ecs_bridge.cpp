/**
 * ecs_bridge.cpp
 *
 * JNI bridge between the Kotlin EcsBridge singleton (com.netsense.mesh.EcsBridge)
 * and the three AdaptiveRTC C++ components:
 *   - adaptive_rtc::RTTTracker
 *   - adaptive_rtc::ECSDetector
 *   - adaptive_rtc::RateController
 *
 * Thread-safety model:
 *   A single std::mutex guards all global state.  Every JNI function acquires this
 *   mutex for its entire duration.  Each call is O(1) / constant-time (no allocations
 *   on the hot path) so lock contention is negligible.
 *
 * Fail-safe invariant:
 *   nativeInit must succeed before any other function modifies state.
 *   If g_initialized is false every function returns the permissive default
 *   (canSend = true, status = 0) so Kotlin audio remains unthrottled.
 */

#include <jni.h>
#include <memory>
#include <mutex>
#include <cstdint>

// AdaptiveRTC headers (resolved via CMake include_directories)
#include "rtt_tracker.h"
#include "ecs_detector.h"
#include "rate_controller.h"
#include "netsense_mesh/CoreLogger.h"
#include "adaptive_jitter_buffer.h"

// ─── Global engine state ────────────────────────────────────────────────────

namespace {
    std::mutex                                    g_mutex;
    std::unique_ptr<adaptive_rtc::RTTTracker>     g_rtt_tracker;
    std::unique_ptr<adaptive_rtc::ECSDetector>    g_ecs_detector;
    std::unique_ptr<adaptive_rtc::RateController> g_rate_controller;
    bool                                          g_initialized = false;

    // Jitter buffer lives in its own mutex — push/pop are on separate threads
    // from the ECS engine, so a dedicated lock avoids any cross-component
    // priority inversion between the network receive thread and the audio thread.
    std::unique_ptr<adaptive_rtc::AdaptiveJitterBuffer> g_jitter_buffer;

    // Pushes current RTTTracker statistics into ECSDetector.
    // Must be called with g_mutex already held.
    void updateEcsFromRtt() {
        g_ecs_detector->updateRTTStats(
            g_rtt_tracker->getAverageRTT(),
            g_rtt_tracker->getStdDevRTT(),
            g_rtt_tracker->getTrendDirection(),
            g_rtt_tracker->isRTTSpiking()
        );
    }
} // anonymous namespace

// ─── JNI implementations ────────────────────────────────────────────────────

extern "C" {

// ── nativeSetLogPath ────────────────────────────────────────────────────────
// Maps to: EcsBridge.nativeSetLogPath(path: String)
// Must be called before nativeInit so C++ logs from the start.
JNIEXPORT void JNICALL
Java_com_netsense_mesh_EcsBridge_nativeSetLogPath(
        JNIEnv* env, jobject /*thiz*/, jstring path)
{
    const char* p = env->GetStringUTFChars(path, nullptr);
    if (p) {
        core_log::init(p);
        env->ReleaseStringUTFChars(path, p);
    }
}

// ── nativeInit ──────────────────────────────────────────────────────────────
// Maps to: EcsBridge.nativeInit(initialRateBps: Int): Boolean
JNIEXPORT jboolean JNICALL
Java_com_netsense_mesh_EcsBridge_nativeInit(
        JNIEnv* /*env*/, jobject /*thiz*/, jint initial_rate_bps)
{
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_initialized) return JNI_TRUE;

    g_rtt_tracker     = std::make_unique<adaptive_rtc::RTTTracker>(50);
    g_ecs_detector    = std::make_unique<adaptive_rtc::ECSDetector>(20);
    g_rate_controller = std::make_unique<adaptive_rtc::RateController>(
            static_cast<uint32_t>(initial_rate_bps));
    g_initialized = true;
    core_log::info(core_log::Module::ECS,
        "ECS engine initialised initialRate=" + std::to_string(initial_rate_bps / 1000) + "kbps");
    return JNI_TRUE;
}

// ── nativeShutdown ──────────────────────────────────────────────────────────
// Maps to: EcsBridge.nativeShutdown()
JNIEXPORT void JNICALL
Java_com_netsense_mesh_EcsBridge_nativeShutdown(JNIEnv* /*env*/, jobject /*thiz*/)
{
    std::lock_guard<std::mutex> lock(g_mutex);
    core_log::info(core_log::Module::ECS, "ECS engine shutdown");
    g_rtt_tracker.reset();
    g_ecs_detector.reset();
    g_rate_controller.reset();
    g_initialized = false;
}

// ── nativeReset ─────────────────────────────────────────────────────────────
// Maps to: EcsBridge.nativeReset()
// Called at PTT start to clear stale history from the previous transmission.
JNIEXPORT void JNICALL
Java_com_netsense_mesh_EcsBridge_nativeReset(JNIEnv* /*env*/, jobject /*thiz*/)
{
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_initialized) return;
    g_rtt_tracker->reset();
    g_ecs_detector->reset();
    g_rate_controller->resetTokenBucket();
}

// ── nativeAddRttSample ──────────────────────────────────────────────────────
// Maps to: EcsBridge.nativeAddRttSample(rttUs: Long)
// Called for every PONG received from the remote peer.
// Feeds the sample into RTTTracker then propagates statistics to ECSDetector.
JNIEXPORT void JNICALL
Java_com_netsense_mesh_EcsBridge_nativeAddRttSample(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong rtt_us)
{
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_initialized || rtt_us <= 0) return;

    g_rtt_tracker->addSample(static_cast<uint64_t>(rtt_us));
    updateEcsFromRtt();

    // Sampled logging: every RTT_LOG_INTERVAL calls, log to core.log
    const char* status_str = "NO_CONGESTION";
    auto status = g_ecs_detector->getCurrentStatus();
    if (status == adaptive_rtc::ECSDetector::Status::CONGESTION_BUILDING)  status_str = "BUILDING";
    if (status == adaptive_rtc::ECSDetector::Status::CONGESTION_IMMINENT)  status_str = "IMMINENT";
    core_log::rttSample(
        static_cast<uint64_t>(rtt_us),
        status_str,
        g_rate_controller->getCurrentRate());
}

// ── nativeAnalyzeCongestion ─────────────────────────────────────────────────
// Maps to: EcsBridge.nativeAnalyzeCongestion(): Int
// Returns: 0=NO_CONGESTION, 1=CONGESTION_BUILDING, 2=CONGESTION_IMMINENT
JNIEXPORT jint JNICALL
Java_com_netsense_mesh_EcsBridge_nativeAnalyzeCongestion(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_initialized) return 0;
    return static_cast<jint>(g_ecs_detector->detect());
}

// ── nativeGetConfidence ─────────────────────────────────────────────────────
// Maps to: EcsBridge.nativeGetConfidence(): Double
JNIEXPORT jdouble JNICALL
Java_com_netsense_mesh_EcsBridge_nativeGetConfidence(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_initialized) return 0.0;
    return g_ecs_detector->getConfidence();
}

// ── nativeCanSendPacket ─────────────────────────────────────────────────────
// Maps to: EcsBridge.nativeCanSendPacket(packetSizeBits: Int, elapsedUs: Long): Boolean
// The rate controller's token bucket determines whether we have budget to send.
// Returns JNI_TRUE (fail-open) when not initialized.
JNIEXPORT jboolean JNICALL
Java_com_netsense_mesh_EcsBridge_nativeCanSendPacket(
        JNIEnv* /*env*/, jobject /*thiz*/,
        jint packet_size_bits, jlong elapsed_us)
{
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_initialized) return JNI_TRUE;

    return g_rate_controller->canSendPacket(
            static_cast<uint32_t>(packet_size_bits),
            static_cast<uint64_t>(elapsed_us))
        ? JNI_TRUE : JNI_FALSE;
}

// ── nativeOnCongestionSignal ────────────────────────────────────────────────
// Maps to: EcsBridge.nativeOnCongestionSignal(signal: Int)
// BUILDING(1) → rate × 0.90   IMMINENT(2) → rate × 0.75   NONE(0) → no-op
JNIEXPORT void JNICALL
Java_com_netsense_mesh_EcsBridge_nativeOnCongestionSignal(
        JNIEnv* /*env*/, jobject /*thiz*/, jint signal)
{
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_initialized) return;
    auto cs = static_cast<adaptive_rtc::CongestionSignal>(signal);
    g_rate_controller->onCongestionSignal(cs);
    core_log::info(core_log::Module::ECS,
        "congestion signal=" + std::to_string(signal) +
        " newRate=" + std::to_string(g_rate_controller->getCurrentRate() / 1000) + "kbps");
}

// ── nativeOnRecovery ────────────────────────────────────────────────────────
// Maps to: EcsBridge.nativeOnRecovery()
// rate × 1.05 (gradual probe-up during recovery)
JNIEXPORT void JNICALL
Java_com_netsense_mesh_EcsBridge_nativeOnRecovery(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_initialized) return;
    g_rate_controller->onRecoverySignal();
    core_log::debug(core_log::Module::ECS,
        "recovery rate=" + std::to_string(g_rate_controller->getCurrentRate() / 1000) + "kbps");
}

// ── nativeGetCurrentRateBps ─────────────────────────────────────────────────
// Maps to: EcsBridge.nativeGetCurrentRateBps(): Int
JNIEXPORT jint JNICALL
Java_com_netsense_mesh_EcsBridge_nativeGetCurrentRateBps(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_initialized) return 0;
    return static_cast<jint>(g_rate_controller->getCurrentRate());
}


// ════════════════════════════════════════════════════════════════════════════
// Jitter buffer JNI — com.netsense.mesh.JitterBridge
// ════════════════════════════════════════════════════════════════════════════
//
// Threading contract:
//   nativeJbPush  → called from Dispatchers.IO (network/receive coroutine)
//   nativeJbPull  → called from the playout coroutine every 20 ms
//   AdaptiveJitterBuffer uses its own internal mutex; these JNI wrappers add
//   no extra locking so neither thread blocks the other for more than O(log N).

// ── nativeJbInit ─────────────────────────────────────────────────────────────
// Maps to: JitterBridge.nativeJbInit(targetDepthMs, maxPackets, sampleRate, frameBytes)
JNIEXPORT jboolean JNICALL
Java_com_netsense_mesh_JitterBridge_nativeJbInit(
        JNIEnv* /*env*/, jobject /*thiz*/,
        jint target_depth_ms, jint max_packets,
        jint sample_rate,     jint frame_bytes)
{
    if (!g_jitter_buffer) {
        g_jitter_buffer = std::make_unique<adaptive_rtc::AdaptiveJitterBuffer>();
    }
    bool ok = g_jitter_buffer->init(
        static_cast<uint32_t>(target_depth_ms),
        static_cast<uint32_t>(max_packets),
        static_cast<uint32_t>(sample_rate),
        static_cast<uint32_t>(frame_bytes));
    core_log::info(core_log::Module::ECS,
        "jitter-buffer init targetMs=" + std::to_string(target_depth_ms) +
        " maxPkts="   + std::to_string(max_packets)   +
        " rate="      + std::to_string(sample_rate)   +
        " frameBytes="+ std::to_string(frame_bytes));
    return ok ? JNI_TRUE : JNI_FALSE;
}

// ── nativeJbReset ────────────────────────────────────────────────────────────
// Maps to: JitterBridge.nativeJbReset()
// Called at each PTT press start to flush stale frames from the prior burst.
JNIEXPORT void JNICALL
Java_com_netsense_mesh_JitterBridge_nativeJbReset(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    if (g_jitter_buffer) {
        g_jitter_buffer->reset();
        core_log::debug(core_log::Module::ECS, "jitter-buffer reset");
    }
}

// ── nativeJbShutdown ─────────────────────────────────────────────────────────
// Maps to: JitterBridge.nativeJbShutdown()
JNIEXPORT void JNICALL
Java_com_netsense_mesh_JitterBridge_nativeJbShutdown(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    if (g_jitter_buffer) {
        g_jitter_buffer->shutdown();
        g_jitter_buffer.reset();
        core_log::info(core_log::Module::ECS, "jitter-buffer shutdown");
    }
}

// ── nativeJbPush ─────────────────────────────────────────────────────────────
// Maps to: JitterBridge.nativeJbPush(seq, timestampRtp, payload, len)
// Called from the UDP receive loop for every arriving RTP packet.
JNIEXPORT jboolean JNICALL
Java_com_netsense_mesh_JitterBridge_nativeJbPush(
        JNIEnv* env, jobject /*thiz*/,
        jint seq, jlong rtp_ts,
        jbyteArray payload, jint len)
{
    if (!g_jitter_buffer || !payload || len <= 0) return JNI_FALSE;

    // GetByteArrayElements returns a pointer valid until ReleaseByteArrayElements.
    // We copy into the buffer inside pushPacket, so the region is held for the
    // duration of one O(log N) map insert — typically < 5 µs.
    jbyte* data = env->GetByteArrayElements(payload, nullptr);
    if (!data) return JNI_FALSE;

    bool accepted = g_jitter_buffer->pushPacket(
        static_cast<uint16_t>(seq  & 0xFFFF),
        static_cast<uint32_t>(rtp_ts & 0xFFFFFFFF),
        reinterpret_cast<const uint8_t*>(data),
        static_cast<uint32_t>(len));

    env->ReleaseByteArrayElements(payload, data, JNI_ABORT); // read-only, no write-back
    return accepted ? JNI_TRUE : JNI_FALSE;
}

// ── nativeJbPull ─────────────────────────────────────────────────────────────
// Maps to: JitterBridge.nativeJbPull(outBuf): Int
// Called from the playout loop every 20 ms to feed AudioTrack.
// Always writes exactly frame_bytes into outBuf (silence/PLC on gap/underrun).
JNIEXPORT jint JNICALL
Java_com_netsense_mesh_JitterBridge_nativeJbPull(
        JNIEnv* env, jobject /*thiz*/,
        jbyteArray out_buf)
{
    if (!g_jitter_buffer || !out_buf) return -1;

    const jsize buf_len = env->GetArrayLength(out_buf);
    if (buf_len <= 0) return -1;

    jbyte* data = env->GetByteArrayElements(out_buf, nullptr);
    if (!data) return -1;

    int written = g_jitter_buffer->popFrame(
        reinterpret_cast<uint8_t*>(data),
        static_cast<uint32_t>(buf_len));

    // JNI_COMMIT writes data back to the Java array without freeing the buffer.
    env->ReleaseByteArrayElements(out_buf, data, 0); // 0 = commit + free
    return static_cast<jint>(written);
}

// ── nativeJbDepthMs ──────────────────────────────────────────────────────────
// Maps to: JitterBridge.nativeJbDepthMs(): Int
JNIEXPORT jint JNICALL
Java_com_netsense_mesh_JitterBridge_nativeJbDepthMs(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    if (!g_jitter_buffer) return 0;
    return static_cast<jint>(g_jitter_buffer->depthMs());
}

// ── nativeJbJitterMs ─────────────────────────────────────────────────────────
// Maps to: JitterBridge.nativeJbJitterMs(): Float
JNIEXPORT jfloat JNICALL
Java_com_netsense_mesh_JitterBridge_nativeJbJitterMs(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    if (!g_jitter_buffer) return 0.f;
    return static_cast<jfloat>(g_jitter_buffer->jitterMs());
}

// ── nativeJbLossRatePct ───────────────────────────────────────────────────────
// Maps to: JitterBridge.nativeJbLossRatePct(): Float
JNIEXPORT jfloat JNICALL
Java_com_netsense_mesh_JitterBridge_nativeJbLossRatePct(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    if (!g_jitter_buffer) return 0.f;
    return static_cast<jfloat>(g_jitter_buffer->lossRatePct());
}

// ── nativeJbUnderrunCount ─────────────────────────────────────────────────────
// Maps to: JitterBridge.nativeJbUnderrunCount(): Long
JNIEXPORT jlong JNICALL
Java_com_netsense_mesh_JitterBridge_nativeJbUnderrunCount(
        JNIEnv* /*env*/, jobject /*thiz*/)
{
    if (!g_jitter_buffer) return 0L;
    return static_cast<jlong>(g_jitter_buffer->underrunCount());
}

} // extern "C"
