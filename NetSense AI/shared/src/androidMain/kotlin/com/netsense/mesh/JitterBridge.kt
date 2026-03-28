package com.netsense.mesh

import android.util.Log

/**
 * JNI bridge to the AdaptiveRTC JitterBuffer C++ component.
 *
 * The jitter buffer absorbs network timing jitter by holding incoming RTP packets
 * in a reorder queue and releasing them at a fixed 20 ms schedule, so AudioTrack
 * always receives a steady stream regardless of network burstiness.
 *
 * Architecture:
 *  - [push]  → called from the UDP receive loop (Dispatchers.IO) on each RTP arrival.
 *  - [pull]  → called from the playout loop every [VoiceTransportConfig.FRAME_MS] ms.
 *  - The C++ side manages a ring buffer indexed by RTP sequence number.
 *
 * Fail-open: if the native library is unavailable (e.g. unit-test environment),
 * every operation silently no-ops and [pull] returns null, causing [RtpManager]
 * to fall through to the direct-write path.
 *
 * Thread-safety: all native calls are guarded by a single std::mutex on the C++ side.
 * Kotlin callers need not add additional synchronization.
 */
object JitterBridge {

    private const val TAG = "JitterBridge"

    // Target playout delay. 60 ms (3 frames) gives enough cushion for typical Wi-Fi
    // Direct jitter without adding perceptible latency.
    private const val TARGET_DEPTH_MS = 60

    // Ring buffer capacity in packets (600 ms = 30 frames of 20 ms audio).
    // Packets older than this are discarded as too-late arrivals.
    private const val MAX_PACKETS = 30

    /**
     * True only after [init] has verified that the JNI symbols for the jitter buffer
     * actually exist in the loaded library.
     *
     * We cannot rely on [EcsBridge.available] alone: the underlying library
     * (adaptive_rtc_jni) may load successfully for the ECS functions while the
     * jitter-buffer JNI exports have not yet been added to it.  Calling any
     * nativeJb* function in that state would raise [UnsatisfiedLinkError] and crash
     * the process.  [init] detects this by catching [UnsatisfiedLinkError] on the
     * first native call, setting this flag to false so every subsequent operation
     * falls through to the safe direct-write path.
     */
    @Volatile private var nativeAvailable = false

    // ── Lifecycle ─────────────────────────────────────────────────────────

    /**
     * Initialises the jitter buffer with the audio parameters from [VoiceTransportConfig].
     * Must be called before the first [push]/[pull] pair.
     * Safe to call multiple times — the C++ side is idempotent.
     *
     * @return true on success, false if the native library is unavailable or the JNI
     *         symbols for the jitter buffer are not present in the loaded library.
     */
    fun init(): Boolean {
        // Pre-condition: the shared library must be loaded (EcsBridge loads it).
        if (!EcsBridge.available) {
            Log.w(TAG, "jitter buffer unavailable: native library not loaded")
            nativeAvailable = false
            return false
        }
        return try {
            val ok = nativeJbInit(
                TARGET_DEPTH_MS,
                MAX_PACKETS,
                VoiceTransportConfig.SAMPLE_RATE_HZ,
                VoiceTransportConfig.frameBytes
            )
            nativeAvailable = ok
            if (ok) {
                Log.d(TAG, "jitter buffer initialised targetDepth=${TARGET_DEPTH_MS}ms maxPackets=$MAX_PACKETS")
                AppLogger.info(AppLogger.Module.RTP, "jitter-buffer init ok targetDepthMs=$TARGET_DEPTH_MS")
            } else {
                Log.w(TAG, "jitter buffer init returned false; falling back to direct-write path")
            }
            ok
        } catch (e: UnsatisfiedLinkError) {
            // JNI symbols not yet in the library — fail open so audio still works via
            // the direct-write path in RtpManager.
            nativeAvailable = false
            Log.w(TAG, "jitter buffer JNI symbols missing in native library; direct-write path active. Error: ${e.message}")
            AppLogger.warn(AppLogger.Module.RTP, "jitter-buffer unavailable: JNI symbols missing")
            false
        }
    }

    /**
     * Resets internal state without releasing native memory.
     * Call at each PTT press start to flush stale frames from the previous burst.
     */
    fun reset() {
        if (nativeAvailable) {
            try { nativeJbReset() } catch (e: UnsatisfiedLinkError) {
                nativeAvailable = false
                Log.w(TAG, "nativeJbReset missing; disabling jitter buffer")
            }
        }
    }

    /** Releases native resources. Call from [CallManager.cleanup]. */
    fun shutdown() {
        if (nativeAvailable) {
            try { nativeJbShutdown() } catch (e: UnsatisfiedLinkError) {
                nativeAvailable = false
            }
        }
        nativeAvailable = false
    }

    // ── Packet I/O ────────────────────────────────────────────────────────

    /**
     * Pushes an incoming RTP packet into the jitter buffer.
     *
     * @param seq          16-bit RTP sequence number (0–65535).
     * @param timestampRtp 32-bit RTP timestamp from the packet header.
     * @param payload      raw PCM-16 audio payload bytes.
     * @return true if the packet was accepted; false if it was discarded or unavailable.
     */
    fun push(seq: Int, timestampRtp: Long, payload: ByteArray): Boolean {
        if (!nativeAvailable) return false
        return try {
            nativeJbPush(seq, timestampRtp, payload, payload.size)
        } catch (e: UnsatisfiedLinkError) {
            nativeAvailable = false
            false
        }
    }

    /**
     * Pulls the next in-order 20 ms frame from the jitter buffer.
     *
     * Returns null when the buffer is not ready or unavailable, causing [RtpManager]
     * to fall back to the direct-write path.
     */
    fun pull(): ByteArray? {
        if (!nativeAvailable) return null
        return try {
            val out = ByteArray(VoiceTransportConfig.frameBytes)
            val len = nativeJbPull(out)
            if (len > 0) out.copyOf(len) else null
        } catch (e: UnsatisfiedLinkError) {
            nativeAvailable = false
            null
        }
    }

    /**
     * Current buffer depth in milliseconds. Use for diagnostics / adaptive tuning.
     * Returns 0 when the native library is unavailable.
     */
    fun depthMs(): Int {
        if (!nativeAvailable) return 0
        return try {
            nativeJbDepthMs()
        } catch (e: UnsatisfiedLinkError) {
            nativeAvailable = false
            0
        }
    }

    // ── JNI declarations ──────────────────────────────────────────────────
    // All methods are in the same native library as EcsBridge ("adaptive_rtc_jni").

    private external fun nativeJbInit(
        targetDepthMs: Int,
        maxPackets: Int,
        sampleRate: Int,
        frameBytes: Int
    ): Boolean

    private external fun nativeJbReset()

    private external fun nativeJbShutdown()

    /**
     * @param seq           RTP sequence number (uint16 range, passed as signed int).
     * @param timestampRtp  RTP timestamp (uint32 range, passed as Long to avoid sign issues).
     * @param payload       audio payload byte array.
     * @param len           number of valid bytes in [payload].
     * @return true if accepted, false if discarded.
     */
    private external fun nativeJbPush(
        seq: Int,
        timestampRtp: Long,
        payload: ByteArray,
        len: Int
    ): Boolean

    /**
     * @param outBuf pre-allocated buffer of exactly [VoiceTransportConfig.frameBytes] bytes.
     * @return number of bytes written; 0 = buffer not yet ready (warmup); -1 = error.
     */
    private external fun nativeJbPull(outBuf: ByteArray): Int

    /** @return current playout delay in milliseconds. */
    private external fun nativeJbDepthMs(): Int
}
