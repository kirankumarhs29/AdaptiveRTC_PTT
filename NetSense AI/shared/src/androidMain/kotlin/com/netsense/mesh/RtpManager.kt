package com.netsense.mesh

import android.media.AudioRecord
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.pow

interface RtpListener {
    fun onRtpStats(txPackets: Long, rxPackets: Long)
    fun onRtpError(reason: String)
    /** Called every [ECS_ANALYSIS_INTERVAL_PACKETS] packets with the latest ECS status. */
    fun onEcsStatus(status: Int, confidencePct: Int, currentRateBps: Int) {}
    /**
     * Called when no RTP packets have arrived for [silenceMs] milliseconds while the
     * remote-transmitting flag is set. Used by [CallManager] to release the PTT floor
     * when PTT_STOP is lost in transit (double-coverage with the 30 s hard watchdog).
     */
    fun onRtpReceiveSilence(silenceMs: Long) {}
}

class RtpManager(
    private val audioManager: VoiceAudioManager,
    private val scope: CoroutineScope,
    private val listener: RtpListener,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    companion object {
        private const val TAG = "RtpManager"
        private const val KPI_TAG = "KPI_VOICE"
        // Run ECS analysis every N packets (= every 400 ms at 50 pps).
        private const val ECS_ANALYSIS_INTERVAL_PACKETS = 20
    }

    private val random = SecureRandom()
    private val streaming = AtomicBoolean(false)

    private var receiveSocket: DatagramSocket? = null
    private var sendSocket: DatagramSocket? = null
    private var receiveJob: Job? = null
    private var sendJob: Job? = null
    /** Scheduled 20 ms playout loop that pulls from the jitter buffer into AudioTrack. */
    private var playoutJob: Job? = null
    /** True when JitterBridge was successfully initialised for this session. */
    @Volatile private var jitterBufferActive: Boolean = false
    /**
     * Consecutive 160 ms receive-timeouts while [remoteTransmittingFlag] is set.
     * At 13 timeouts the cumulative silence is ~2080 ms — trigger silence-release.
     */
    private var consecutiveRxTimeouts: Int = 0
    private val SILENCE_RELEASE_TIMEOUTS = 13  // 13 × 160 ms ≈ 2080 ms

    @Volatile
    private var remoteAddress: InetAddress? = null

    @Volatile
    private var remotePort: Int = VoiceTransportConfig.RTP_PORT

    private var txPackets: Long = 0
    private var rxPackets: Long = 0
    private var sequenceNumber: Int = 0
    private var rtpTimestamp: Long = 0
    private val ssrc: Long = random.nextInt().toLong() and 0xFFFFFFFFL
    private var lastSendLevelLogAt: Long = 0
    private var lastReceiveLevelLogAt: Long = 0
    private var pttTouchAtMs: Long = 0L
    private var firstTxKpiPending = false
    private var firstRxAtMs: Long = 0L

    fun bindReceiver(): Boolean {
        if (receiveSocket != null) return true
        return try {
            val socket = DatagramSocket(null)
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(VoiceTransportConfig.RTP_PORT))
            socket.soTimeout = 160
            receiveSocket = socket
            receiveJob = scope.launch(ioDispatcher) { receiveLoop(socket) }
            // Initialise and start the jitter buffer playout loop if the native lib is available.
            jitterBufferActive = JitterBridge.init()
            if (jitterBufferActive) {
                playoutJob = scope.launch(ioDispatcher) { playoutLoop() }
                AppLogger.info(AppLogger.Module.RTP, "jitter buffer active; playout loop started")
            } else {
                AppLogger.info(AppLogger.Module.RTP, "jitter buffer unavailable; using direct-write path")
            }
            Log.d(TAG, "RTP receiver bound port=${VoiceTransportConfig.RTP_PORT} jitterBuffer=$jitterBufferActive")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "failed to bind RTP receiver", t)
            listener.onRtpError("RTP bind failed: ${t.message}")
            false
        }
    }

    fun setRemoteEndpoint(address: InetAddress, port: Int) {
        remoteAddress = address
        remotePort = port
        Log.d(TAG, "remote endpoint=${address.hostAddress}:$port")
    }

    fun isReady(): Boolean = receiveSocket != null && remoteAddress != null && audioManager.playbackPrepared

    fun startStreaming(pttTouchTimestampMs: Long = System.currentTimeMillis()): Boolean {
        if (streaming.get()) return true
        if (!isReady()) {
            Log.w(
                TAG,
                "startStreaming blocked receiveSocket=${receiveSocket != null} remote=${remoteAddress?.hostAddress} playbackPrepared=${audioManager.playbackPrepared}"
            )
            AppLogger.warn(AppLogger.Module.RTP, "startStreaming blocked: socket=${receiveSocket != null} remote=${remoteAddress?.hostAddress}")
            listener.onRtpError("RTP path not ready")
            return false
        }

        if (!streaming.compareAndSet(false, true)) return true
        pttTouchAtMs = pttTouchTimestampMs
        firstTxKpiPending = true
        EcsBridge.reset()
        JitterBridge.reset()   // flush stale frames from the previous PTT burst
        AppLogger.info(AppLogger.Module.RTP, "streaming started remote=${remoteAddress?.hostAddress}:$remotePort")
        sendJob = scope.launch(ioDispatcher) { sendLoop() }
        Log.d(TAG, "streaming started")
        return true
    }

    fun stopStreaming() {
        if (!streaming.compareAndSet(true, false)) return
        sendJob?.cancel()
        sendJob = null
        audioManager.stopRecorder()
        AppLogger.info(AppLogger.Module.RTP, "streaming stopped tx=$txPackets rx=$rxPackets")
        Log.d(TAG, "streaming stopped")
        // Reset per-call counters so the next call starts with clean state.
        // consecutiveRxTimeouts must be reset to prevent a spurious onRtpReceiveSilence()
        // callback at the very start of the next PTT burst (the receive loop keeps running
        // between calls when stopStreaming() is used instead of stop()).
        consecutiveRxTimeouts = 0
        firstRxAtMs = 0L
        txPackets = 0
        rxPackets = 0
        sequenceNumber = 0
        rtpTimestamp = 0
        lastSendLevelLogAt = 0
        lastReceiveLevelLogAt = 0
    }

    suspend fun stop() {
        stopStreaming()
        playoutJob?.cancel()
        playoutJob = null
        receiveJob?.cancelAndJoin()
        receiveJob = null
        try { receiveSocket?.close() } catch (_: Throwable) {}
        try { sendSocket?.close() } catch (_: Throwable) {}
        receiveSocket = null
        sendSocket = null
        JitterBridge.shutdown()
        jitterBufferActive = false
        consecutiveRxTimeouts = 0
        audioManager.shutdown()
        txPackets = 0
        rxPackets = 0
        sequenceNumber = 0
        rtpTimestamp = 0
        listener.onRtpStats(0, 0)
    }

    private suspend fun sendLoop() {
        val recorder = audioManager.startRecorder()
        if (recorder == null) {
            streaming.set(false)
            listener.onRtpError("AudioRecord init failed")
            return
        }

        val destination = remoteAddress
        if (destination == null) {
            streaming.set(false)
            audioManager.stopRecorder()
            listener.onRtpError("Remote RTP endpoint missing")
            return
        }
        Log.d(TAG, "sendLoop start remote=${destination.hostAddress}:$remotePort frameBytes=${audioManager.frameBytes}")

        val frame = ByteArray(audioManager.frameBytes)
        try {
            val socket = sendSocket ?: DatagramSocket().also { sendSocket = it }
            var lastSendTimeUs = System.nanoTime() / 1_000L  // microseconds
            while (scope.isActive && streaming.get()) {
                val bytesRead = recorder.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                if (bytesRead <= 0) continue

                // ── ECS token-bucket gate ─────────────────────────────────
                // Measure elapsed time since last send (µs) and ask the rate
                // controller whether we have budget for this packet.
                // If not, we skip this frame (equivalent to a paced rate drop)
                // and yield so the coroutine does not busy-spin.
                val nowUs = System.nanoTime() / 1_000L
                val elapsedUs = nowUs - lastSendTimeUs
                if (!EcsBridge.canSendPacket(elapsedUs)) {
                    AppLogger.debug(AppLogger.Module.RTP, "frame paced-drop: token budget exhausted elapsed=${elapsedUs}µs")
                    delay(VoiceTransportConfig.FRAME_MS.toLong())  // back off one frame
                    continue
                }
                lastSendTimeUs = nowUs
                // ─────────────────────────────────────────────────────────

                val payload = if (bytesRead == frame.size) frame.copyOf() else frame.copyOf(bytesRead)
                val gainedPayload = applySoftwareGain(payload, VoiceTransportConfig.TX_SOFTWARE_GAIN)
                val packet = RtpPacket(
                    version = 2,
                    payloadType = VoiceTransportConfig.PAYLOAD_TYPE_PCM16,
                    marker = false,
                    sequenceNumber = sequenceNumber,
                    timestamp = rtpTimestamp,
                    ssrc = ssrc,
                    payload = gainedPayload
                )
                val raw = packet.toByteArray()
                socket.send(DatagramPacket(raw, raw.size, destination, remotePort))

                if (firstTxKpiPending) {
                    firstTxKpiPending = false
                    val touchToPacketOutMs = System.currentTimeMillis() - pttTouchAtMs
                    Log.i(KPI_TAG, "touch_to_packet_out_ms=$touchToPacketOutMs")
                    AppLogger.info(AppLogger.Module.RTP, "touch_to_packet_out_ms=$touchToPacketOutMs")
                }

                txPackets += 1
                sequenceNumber = (sequenceNumber + 1) and 0xFFFF
                rtpTimestamp = (rtpTimestamp + audioManager.frameSamples) and 0xFFFFFFFFL
                maybeLogSendLevel(payload, gainedPayload)
                if (txPackets % ECS_ANALYSIS_INTERVAL_PACKETS.toLong() == 0L) {
                    // ── Periodic ECS analysis (every 400 ms) ─────────────
                    val status = EcsBridge.analyzeCongestion()
                    val statusName = when (status) {
                        EcsBridge.STATUS_BUILDING -> { EcsBridge.onCongestionSignal(EcsBridge.SIGNAL_BUILDING); "BUILDING" }
                        EcsBridge.STATUS_IMMINENT -> { EcsBridge.onCongestionSignal(EcsBridge.SIGNAL_IMMINENT); "IMMINENT" }
                        else                      -> { EcsBridge.onRecovery(); "NO_CONGESTION" }
                    }
                    val confidence = (EcsBridge.getConfidence() * 100).toInt()
                    val rateBps    = EcsBridge.getCurrentRateBps()
                    // Only write to file when status is non-trivial or every 25 intervals (~10 s).
                    if (status != EcsBridge.STATUS_NO_CONGESTION || txPackets % (ECS_ANALYSIS_INTERVAL_PACKETS * 25L) == 0L) {
                        AppLogger.debug(AppLogger.Module.ECS,
                            "ecs=$statusName confidence=${confidence}% rate=${rateBps / 1000}kbps tx=$txPackets")
                    }
                    listener.onEcsStatus(status, confidence, rateBps)
                    // ─────────────────────────────────────────────────────
                    listener.onRtpStats(txPackets, rxPackets)
                }
            }
        } catch (t: Throwable) {
            if (streaming.get()) {
                Log.e(TAG, "send loop failed", t)
                AppLogger.error(AppLogger.Module.RTP, "send loop failed: ${t.message}", t)
                listener.onRtpError("RTP send loop failed: ${t.message}")
            }
        } finally {
            streaming.set(false)
            audioManager.stopRecorder()
        }
    }

    private fun receiveLoop(socket: DatagramSocket) {
        val buffer = ByteArray(1500)
        while (scope.isActive) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)

                val rtpPacket = RtpPacket.parse(packet.data, packet.length) ?: continue
                if (rtpPacket.payloadType != VoiceTransportConfig.PAYLOAD_TYPE_PCM16) continue

                if (firstRxAtMs == 0L) {
                    firstRxAtMs = System.currentTimeMillis()
                }

                if (jitterBufferActive) {
                    // Push into the jitter buffer; the playout loop handles AudioTrack writes
                    // on a fixed 20 ms schedule, achieving smooth output regardless of burst arrival.
                    JitterBridge.push(rtpPacket.sequenceNumber, rtpPacket.timestamp, rtpPacket.payload)
                } else {
                    // Direct-write fallback (jitter buffer unavailable)
                    val playbackStarted = audioManager.writePlayback(rtpPacket.payload)
                    if (playbackStarted && firstRxAtMs > 0L) {
                        val packetInToSpeakerMs = System.currentTimeMillis() - firstRxAtMs
                        Log.i(KPI_TAG, "packet_in_to_speaker_ms=$packetInToSpeakerMs")
                        firstRxAtMs = 0L
                    }
                    rxPackets += 1
                    if (rxPackets % 20L == 0L) listener.onRtpStats(txPackets, rxPackets)
                }

                consecutiveRxTimeouts = 0
                maybeLogReceiveLevel(rtpPacket.payload)
            } catch (_: SocketTimeoutException) {
                consecutiveRxTimeouts += 1
                // After 3 timeouts (~480 ms) with no incoming audio, flush AudioTrack.
                if (consecutiveRxTimeouts == 3) {
                    audioManager.onPlaybackIdle()
                    firstRxAtMs = 0L
                }
                // After ~2 s of silence, notify CallManager for possible floor release.
                if (consecutiveRxTimeouts == SILENCE_RELEASE_TIMEOUTS) {
                    val silenceMs = consecutiveRxTimeouts * socket.soTimeout.toLong()
                    listener.onRtpReceiveSilence(silenceMs)
                    consecutiveRxTimeouts = 0   // reset so we don't spam the callback
                }
            } catch (_: SocketException) {
                if (socket.isClosed) return
            } catch (t: Throwable) {
                Log.e(TAG, "receive loop failed", t)
                listener.onRtpError("RTP receive loop failed: ${t.message}")
            }
        }
    }

    /**
     * Drives the jitter buffer playout schedule at exactly [VoiceTransportConfig.FRAME_MS] ms
     * intervals. Runs independently of the receive coroutine, providing smooth AudioTrack output
     * even when packets arrive in bursts.
     *
     * Falls back gracefully: if [JitterBridge.pull] returns null (buffer still filling), the
     * loop simply waits for the next tick without writing anything to AudioTrack.
     */
    private suspend fun playoutLoop() {
        var consecutiveEmpty = 0
        while (scope.isActive) {
            val frame = JitterBridge.pull()
            if (frame != null) {
                consecutiveEmpty = 0
                val playbackStarted = audioManager.writePlayback(frame)
                if (playbackStarted && firstRxAtMs > 0L) {
                    val latencyMs = System.currentTimeMillis() - firstRxAtMs
                    Log.i(KPI_TAG, "jb_packet_to_speaker_ms=$latencyMs depth=${JitterBridge.depthMs()}ms")
                    AppLogger.info(AppLogger.Module.RTP, "jitter-buffer first-frame latency=${latencyMs}ms depth=${JitterBridge.depthMs()}ms")
                    firstRxAtMs = 0L
                }
                rxPackets += 1
                if (rxPackets % 20L == 0L) listener.onRtpStats(txPackets, rxPackets)
            } else {
                consecutiveEmpty++
                // If the jitter buffer has been empty for 3 consecutive ticks (~60 ms),
                // the remote has stopped transmitting — flush AudioTrack.
                if (consecutiveEmpty == 3) {
                    audioManager.onPlaybackIdle()
                }
            }
            kotlinx.coroutines.delay(VoiceTransportConfig.FRAME_MS.toLong())
        }
    }

    private fun maybeLogSendLevel(inputPayload: ByteArray, outputPayload: ByteArray) {
        val now = System.currentTimeMillis()
        if (now - lastSendLevelLogAt < 2000L) return
        lastSendLevelLogAt = now
        val rmsIn = computeRms(inputPayload)
        val rmsOut = computeRms(outputPayload)
        Log.i(
            KPI_TAG,
            "rtp_tx callActive=${streaming.get()} txPackets=$txPackets rxPackets=$rxPackets pcmRmsIn=$rmsIn pcmRmsOut=$rmsOut payloadBytes=${outputPayload.size}"
        )
    }

    private fun maybeLogReceiveLevel(payload: ByteArray) {
        val now = System.currentTimeMillis()
        if (now - lastReceiveLevelLogAt < 2000L) return
        lastReceiveLevelLogAt = now
        val rms = computeRms(payload)
        Log.i(
            KPI_TAG,
            "rtp_rx callActive=${streaming.get()} txPackets=$txPackets rxPackets=$rxPackets pcmRms=$rms payloadBytes=${payload.size}"
        )
    }

    private fun computeRms(pcm16Le: ByteArray): Int {
        val samples = pcm16Le.size / 2
        if (samples <= 0) return 0
        var sum = 0.0
        var i = 0
        while (i + 1 < pcm16Le.size) {
            val lo = pcm16Le[i].toInt() and 0xFF
            val hi = pcm16Le[i + 1].toInt()
            val sample = (hi shl 8) or lo
            sum += sample.toDouble() * sample.toDouble()
            i += 2
        }
        return kotlin.math.sqrt(sum / samples).toInt()
    }

    private fun applySoftwareGain(pcm16Le: ByteArray, gainDb: Float): ByteArray {
        if (gainDb == 0.0f) return pcm16Le

        val gainLinear = 10.0f.pow(gainDb / 20.0f)
        val out = ByteArray(pcm16Le.size)
        var i = 0
        while (i + 1 < pcm16Le.size) {
            val lo = pcm16Le[i].toInt() and 0xFF
            val hi = pcm16Le[i + 1].toInt()
            val sample = (hi shl 8) or lo
            val scaled = (sample * gainLinear).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[i] = scaled.toByte()
            out[i + 1] = (scaled shr 8).toByte()
            i += 2
        }
        return out
    }
}