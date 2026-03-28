package com.netsense.mesh

import android.util.Log

/**
 * JNI facade for the AdaptiveRTC ECS (Early Congestion Signal) engine.
 *
 * Wraps three C++ components as a single unified Kotlin API:
 *   - RTTTracker   – sliding-window RTT statistics (50-sample window)
 *   - ECSDetector  – congestion detection from RTT trends
 *   - RateController – token-bucket send-rate enforcement
 *
 * Thread-safety: all native calls delegate to a single std::mutex on the C++ side.
 * Designed to be called inline from [RtpManager.sendLoop] on Dispatchers.IO.
 *
 * Fail-open: if the native library fails to load (e.g. first install, CI),
 * every query returns the permissive answer so audio continues unthrottled.
 */
object EcsBridge {

    private const val TAG = "EcsBridge"
    private const val LIB_NAME = "adaptive_rtc_jni"

    // ── Mirrors ECSDetector::Status ordinals ──────────────────────────────
    const val STATUS_NO_CONGESTION = 0
    const val STATUS_BUILDING      = 1
    const val STATUS_IMMINENT      = 2

    // ── Mirrors CongestionSignal ordinals ─────────────────────────────────
    const val SIGNAL_NONE     = 0
    const val SIGNAL_BUILDING = 1
    const val SIGNAL_IMMINENT = 2

    /**
     * PCM-16 mono 16 kHz, 20 ms frame:
     *   640 bytes payload + 12 bytes RTP header = 652 bytes = 5216 bits.
     * At 50 pps (packets-per-second) this is ~260 kbps.
     */
    private const val RTP_PACKET_SIZE_BITS = 652 * 8
    private const val INITIAL_RATE_BPS     = 260_000   // 260 kbps

    /** True when the native library was successfully loaded. */
    @Volatile
    var available: Boolean = false
        private set

    init {
        available = try {
            System.loadLibrary(LIB_NAME)
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "ECS native library unavailable – running without congestion control: ${e.message}")
            false
        }
    }

    /**
     * Configure the C++ logger file path. Call this BEFORE [init] so that
     * every line from the ECS engine goes to core.log from the first write.
     *
     * Typically:
     *   EcsBridge.setLogPath(context.filesDir.absolutePath + "/core.log")
     */
    fun setLogPath(path: String) {
        if (available) nativeSetLogPath(path)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    /**
     * Must be called before any other method (typically from [CallManager.onTransportConnected]).
     * Safe to call multiple times (idempotent on the C++ side).
     */
    fun init(initialRateBps: Int = INITIAL_RATE_BPS): Boolean {
        if (!available) return false
        return nativeInit(initialRateBps)
    }

    /** Release native resources. Call from [CallManager.cleanup]. */
    fun shutdown() {
        if (available) nativeShutdown()
    }

    /** Reset all state without tearing down (e.g. at PTT start). */
    fun reset() {
        if (available) nativeReset()
    }

    // ── RTT feed ──────────────────────────────────────────────────────────

    /**
     * Feed a new RTT sample from the PING/PONG heartbeat.
     * Automatically updates RTTTracker, recomputes statistics, and
     * pushes the new stats into ECSDetector.
     *
     * @param rttUs round-trip time in microseconds (must be > 0)
     */
    fun addRttSample(rttUs: Long) {
        if (available && rttUs > 0L) nativeAddRttSample(rttUs)
    }

    // ── Congestion detection ──────────────────────────────────────────────

    /**
     * Run the ECS detector and return the current congestion status.
     * Returns [STATUS_NO_CONGESTION] when the library is unavailable.
     */
    fun analyzeCongestion(): Int {
        if (!available) return STATUS_NO_CONGESTION
        return nativeAnalyzeCongestion()
    }

    /** 0.0–1.0 confidence that detected congestion is real. */
    fun getConfidence(): Double {
        if (!available) return 0.0
        return nativeGetConfidence()
    }

    // ── Rate control / token bucket ───────────────────────────────────────

    /**
     * Check whether the token bucket allows sending the next RTP packet now.
     *
     * Fail-open: returns `true` when the library is unavailable so that
     * audio is never silenced by a missing native dependency.
     *
     * @param elapsedUs microseconds since the previous send call
     */
    fun canSendPacket(elapsedUs: Long): Boolean {
        if (!available) return true
        return nativeCanSendPacket(RTP_PACKET_SIZE_BITS, elapsedUs)
    }

    /**
     * Inform the rate controller of a detected congestion level.
     * BUILDING → rate × 0.90, IMMINENT → rate × 0.75.
     */
    fun onCongestionSignal(signal: Int) {
        if (available) nativeOnCongestionSignal(signal)
    }

    /** Inform the rate controller of congestion recovery → rate × 1.05. */
    fun onRecovery() {
        if (available) nativeOnRecovery()
    }

    /** Current allowed bitrate in bps. Returns the initial rate if unavailable. */
    fun getCurrentRateBps(): Int {
        if (!available) return INITIAL_RATE_BPS
        return nativeGetCurrentRateBps()
    }

    // ── Native declarations ───────────────────────────────────────────────

    private external fun nativeSetLogPath(path: String)
    private external fun nativeInit(initialRateBps: Int): Boolean
    private external fun nativeShutdown()
    private external fun nativeReset()
    private external fun nativeAddRttSample(rttUs: Long)
    private external fun nativeAnalyzeCongestion(): Int
    private external fun nativeGetConfidence(): Double
    private external fun nativeCanSendPacket(packetSizeBits: Int, elapsedUs: Long): Boolean
    private external fun nativeOnCongestionSignal(signal: Int)
    private external fun nativeOnRecovery()
    private external fun nativeGetCurrentRateBps(): Int
}
