package com.netsense.mesh

import android.util.Log
import com.netsense.mesh.AppLogger.Module
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.nio.charset.StandardCharsets

enum class SignalingMessageType {
    PROBE,
    PROBE_ACK,
    CALL_REQUEST,
    CALL_ACCEPT,
    CALL_REJECT,
    CALL_END,
    PING,
    PONG,               // RTT echo — sent automatically in response to PING
    PTT_START,
    PTT_STOP,
    PTT_BUSY,           // floor occupied — sent to the requester when a PTT collision is detected
    TRANSPORT_READY,    // WiFi Direct client → owner: route validated, socket bound
    TRANSPORT_READY_ACK // WiFi Direct owner → client: ACK, both may start heartbeat
}

data class SignalingMessage(
    val type: SignalingMessageType,
    val from: String,
    val sessionId: String? = null,
    val attemptId: Long? = null,
    val peerId: String? = null,
    val callId: String? = null,
    val audioPort: Int? = null,
    val reason: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
) {
    fun toJsonBytes(): ByteArray {
        val json = JSONObject()
            .put("type", type.name)
            .put("from", from)
            .put("timestampMs", timestampMs)
        if (sessionId != null) json.put("sessionId", sessionId)
        if (attemptId != null) json.put("attemptId", attemptId)
        if (peerId != null) json.put("peerId", peerId)
        if (callId != null) json.put("callId", callId)
        if (audioPort != null) json.put("audioPort", audioPort)
        if (reason != null) json.put("reason", reason)
        return json.toString().toByteArray(StandardCharsets.UTF_8)
    }

    companion object {
        fun fromJson(payload: String): SignalingMessage? {
            return runCatching {
                val json = JSONObject(payload)
                SignalingMessage(
                    type = SignalingMessageType.valueOf(json.getString("type")),
                    from = json.optString("from", "unknown"),
                    sessionId = json.optString("sessionId", "").ifBlank { null },
                    attemptId = if (json.has("attemptId")) json.optLong("attemptId", -1L).takeIf { it >= 0L } else null,
                    peerId = json.optString("peerId", "").ifBlank { null },
                    callId = json.optString("callId", "").ifBlank { null },
                    audioPort = json.optInt("audioPort", -1).takeIf { it > 0 },
                    reason = json.optString("reason", "").ifBlank { null },
                    timestampMs = json.optLong("timestampMs", System.currentTimeMillis())
                )
            }.getOrNull()
        }
    }
}

sealed interface SignalingEvent {
    data class Probe(val message: SignalingMessage, val remoteAddress: InetAddress) : SignalingEvent
    data class ProbeAck(val message: SignalingMessage, val remoteAddress: InetAddress) : SignalingEvent
    data class CallRequest(val message: SignalingMessage, val remoteAddress: InetAddress) : SignalingEvent
    data class CallAccept(val message: SignalingMessage, val remoteAddress: InetAddress) : SignalingEvent
    data class CallReject(val message: SignalingMessage, val remoteAddress: InetAddress) : SignalingEvent
    data class CallEnd(val message: SignalingMessage, val remoteAddress: InetAddress) : SignalingEvent
    data class Ping(val message: SignalingMessage, val remoteAddress: InetAddress) : SignalingEvent
    /**
     * Emitted when a PONG is received in response to one of our PINGs.
     * [message.timestampMs] is the echo of our original PING timestamp so
     * the receiver can compute RTT = now - message.timestampMs.
     */
    data class Pong(val message: SignalingMessage, val remoteAddress: InetAddress) : SignalingEvent
    data class PttStart(val message: SignalingMessage, val remoteAddress: InetAddress) : SignalingEvent
    data class PttStop(val message: SignalingMessage, val remoteAddress: InetAddress) : SignalingEvent
    /** Sent to a PTT requester when the floor is already held by the other peer. */
    data class PttBusy(val message: SignalingMessage, val remoteAddress: InetAddress) : SignalingEvent
    /** WiFi Direct client tells owner it has a valid route and a bound socket. */
    data class TransportReady(val message: SignalingMessage, val remoteAddress: InetAddress) : SignalingEvent
    /** Owner confirms to client that it received TR; both sides may now start heartbeat. */
    data class TransportReadyAck(val message: SignalingMessage, val remoteAddress: InetAddress) : SignalingEvent
}

interface SignalingListener {
    fun onRemoteEndpointDiscovered(address: InetAddress)
    fun onSignalingEvent(event: SignalingEvent)
    fun onSignalingError(reason: String)
}

class SignalingManager(
    private val localNodeId: String,
    private val scope: CoroutineScope,
    private val listener: SignalingListener,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    companion object {
        private const val TAG = "SignalingManager"

        /**
         * Probes whether the kernel has a route to [address] by performing a
         * connection-less UDP connect().  On Android this does a route-table lookup
         * without sending any bytes; throws SocketException (ENETUNREACH) when the
         * interface is not yet up or the route has not been installed.
         */
        fun hasRouteToAddress(address: java.net.InetAddress, port: Int): Boolean = try {
            java.net.DatagramSocket().use { s ->
                s.connect(address, port)
                true
            }
        } catch (_: Exception) { false }
    }

    private var controlSocket: DatagramSocket? = null
    private var receiveJob: Job? = null
    private var heartbeatJob: Job? = null
    @Volatile private var lastDiscoveredAddress: String? = null
    @Volatile private var heartbeatRemoteAddress: String? = null
    @Volatile private var heartbeatCallId: String? = null
    @Volatile private var heartbeatSessionId: String? = null
    @Volatile private var heartbeatAttemptId: Long? = null
    @Volatile private var heartbeatPeerId: String? = null
    /** Incremented on every stopHeartbeat/startHeartbeat so stale jobs self-terminate. */
    @Volatile private var heartbeatToken: Long = 0L

    fun start(): Boolean {
        if (controlSocket != null) return true
        return try {
            val socket = DatagramSocket(null)
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(VoiceTransportConfig.CONTROL_PORT))
            controlSocket = socket
            receiveJob = scope.launch(ioDispatcher) { receiveLoop(socket) }
            Log.d(TAG, "control socket bound port=${VoiceTransportConfig.CONTROL_PORT}")
            AppLogger.info(Module.NETWORK, "signaling socket bound port=${VoiceTransportConfig.CONTROL_PORT}")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "failed to bind signaling socket", t)
            AppLogger.error(Module.NETWORK, "signaling bind failed: ${t.message}")
            listener.onSignalingError("Signaling bind failed: ${t.message}")
            false
        }
    }

    fun stop() {
        stopHeartbeat()
        lastDiscoveredAddress = null
        receiveJob?.cancel()
        receiveJob = null
        try {
            controlSocket?.close()
        } catch (_: Throwable) {
        }
        controlSocket = null
    }

    /** Cancels only the heartbeat loop; the receive loop and socket remain intact. */
    fun stopHeartbeat() {
        heartbeatToken++          // invalidates any running job immediately
        heartbeatJob?.cancel()
        heartbeatJob = null
        heartbeatRemoteAddress = null
        heartbeatCallId = null
        heartbeatSessionId = null
        heartbeatAttemptId = null
        heartbeatPeerId = null
        AppLogger.info(Module.NETWORK, "heartbeat stopped")
    }

    /**
     * @param reportError When false, send failures are logged but not propagated to the
     *   listener as fatal errors.  Use for fire-and-forget probes (TRANSPORT_READY, heartbeats
     *   during the pre-connected phase) where a transient failure should not tear down the call.
     */
    fun send(
        message: SignalingMessage,
        remoteAddress: InetAddress,
        remotePort: Int = VoiceTransportConfig.CONTROL_PORT,
        reportError: Boolean = true
    ) {
        scope.launch(ioDispatcher) {
            try {
                val socket = controlSocket ?: return@launch
                val raw = message.toJsonBytes()
                socket.send(DatagramPacket(raw, raw.size, remoteAddress, remotePort))
                Log.d(TAG, "sent type=${message.type} remote=${remoteAddress.hostAddress}:$remotePort callId=${message.callId}")
                AppLogger.debug(Module.NETWORK, "sig-send type=${message.type} remote=${remoteAddress.hostAddress} callId=${message.callId}")
            } catch (t: Throwable) {
                Log.e(TAG, "failed to send type=${message.type}", t)
                AppLogger.error(Module.NETWORK, "sig-send failed type=${message.type}: ${t.message}")
                if (reportError) {
                    listener.onSignalingError("Failed to send ${message.type}: ${t.message}")
                }
            }
        }
    }

    /**
     * Synchronous send — blocks the calling thread until the packet is on the wire.
     * Used by [CallManager.endCall] to guarantee the `CALL_END` is sent before
     * [stop] closes the socket.  Must be called from a background thread (IO dispatcher).
     */
    fun sendBlocking(
        message: SignalingMessage,
        remoteAddress: InetAddress,
        remotePort: Int = VoiceTransportConfig.CONTROL_PORT
    ) {
        val socket = controlSocket ?: return
        val raw = message.toJsonBytes()
        socket.send(DatagramPacket(raw, raw.size, remoteAddress, remotePort))
        Log.d(TAG, "sent-blocking type=${message.type} remote=${remoteAddress.hostAddress}:$remotePort callId=${message.callId}")
        AppLogger.debug(Module.NETWORK, "sig-send-blocking type=${message.type} remote=${remoteAddress.hostAddress} callId=${message.callId}")
    }

    fun startHeartbeat(
        remoteAddress: InetAddress,
        callId: String?,
        sessionId: String?,
        attemptId: Long?,
        peerId: String?
    ) {
        val remoteHost = remoteAddress.hostAddress
        if (heartbeatJob?.isActive == true &&
            heartbeatRemoteAddress == remoteHost &&
            heartbeatCallId == callId &&
            heartbeatSessionId == sessionId &&
            heartbeatAttemptId == attemptId &&
            heartbeatPeerId == peerId
        ) {
            return
        }

        val myToken = ++heartbeatToken   // new token for this job; any prior job will self-terminate
        heartbeatJob?.cancel()
        heartbeatRemoteAddress = remoteHost
        heartbeatCallId = callId
        heartbeatSessionId = sessionId
        heartbeatAttemptId = attemptId
        heartbeatPeerId = peerId
        Log.d(TAG, "startHeartbeat remote=$remoteHost callId=$callId token=$myToken")
        AppLogger.info(Module.NETWORK, "heartbeat started remote=$remoteHost callId=$callId")
        heartbeatJob = scope.launch(ioDispatcher) {
            while (isActive && heartbeatToken == myToken) {
                send(
                    SignalingMessage(
                        type = SignalingMessageType.PING,
                        from = localNodeId,
                        sessionId = sessionId,
                        attemptId = attemptId,
                        peerId = peerId,
                        callId = callId,
                        audioPort = VoiceTransportConfig.RTP_PORT
                    ),
                    remoteAddress,
                    reportError = (heartbeatToken == myToken)  // silence errors from superseded jobs
                )
                delay(VoiceTransportConfig.HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    private suspend fun receiveLoop(socket: DatagramSocket) {
        val buffer = ByteArray(1024)
        while (scope.isActive) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val remoteHost = packet.address.hostAddress
                if (remoteHost != lastDiscoveredAddress) {
                    lastDiscoveredAddress = remoteHost
                    Log.d(TAG, "remote endpoint discovered remote=$remoteHost")
                    AppLogger.info(Module.NETWORK, "remote endpoint discovered remote=$remoteHost")
                    listener.onRemoteEndpointDiscovered(packet.address)
                }

                val payload = String(packet.data, 0, packet.length, StandardCharsets.UTF_8)
                val message = SignalingMessage.fromJson(payload)
                if (message == null) {
                    Log.w(TAG, "ignoring malformed signaling payload from=${packet.address.hostAddress} payload=$payload")
                    AppLogger.warn(Module.NETWORK, "malformed signaling payload from=${packet.address.hostAddress}")
                    continue
                }

                // Auto-respond to PING with PONG so the sender can measure RTT.
                // The original PING timestampMs is echoed unchanged so the sender
                // computes RTT = now - pong.timestampMs without needing clock sync.
                if (message.type == SignalingMessageType.PING) {
                    val pong = message.copy(type = SignalingMessageType.PONG, from = localNodeId)
                    val raw = pong.toJsonBytes()
                    try {
                        socket.send(DatagramPacket(raw, raw.size, packet.address, packet.port))
                    } catch (t: Throwable) {
                        Log.w(TAG, "failed to send PONG to ${packet.address.hostAddress}", t)
                    }
                }

                val event = when (message.type) {
                    SignalingMessageType.PROBE               -> SignalingEvent.Probe(message, packet.address)
                    SignalingMessageType.PROBE_ACK           -> SignalingEvent.ProbeAck(message, packet.address)
                    SignalingMessageType.CALL_REQUEST        -> SignalingEvent.CallRequest(message, packet.address)
                    SignalingMessageType.CALL_ACCEPT         -> SignalingEvent.CallAccept(message, packet.address)
                    SignalingMessageType.CALL_REJECT         -> SignalingEvent.CallReject(message, packet.address)
                    SignalingMessageType.CALL_END            -> SignalingEvent.CallEnd(message, packet.address)
                    SignalingMessageType.PING                -> SignalingEvent.Ping(message, packet.address)
                    SignalingMessageType.PONG                -> SignalingEvent.Pong(message, packet.address)
                    SignalingMessageType.PTT_START           -> SignalingEvent.PttStart(message, packet.address)
                    SignalingMessageType.PTT_STOP            -> SignalingEvent.PttStop(message, packet.address)
                    SignalingMessageType.PTT_BUSY            -> SignalingEvent.PttBusy(message, packet.address)
                    SignalingMessageType.TRANSPORT_READY     -> SignalingEvent.TransportReady(message, packet.address)
                    SignalingMessageType.TRANSPORT_READY_ACK -> SignalingEvent.TransportReadyAck(message, packet.address)
                }
                Log.d(TAG, "received type=${message.type} remote=${packet.address.hostAddress} callId=${message.callId}")
                AppLogger.debug(Module.NETWORK, "sig-recv type=${message.type} remote=${packet.address.hostAddress} callId=${message.callId}")
                listener.onSignalingEvent(event)
            } catch (_: SocketException) {
                if (socket.isClosed) return
            } catch (t: Throwable) {
                Log.e(TAG, "signaling receive loop failed", t)
                AppLogger.error(Module.NETWORK, "signaling receive loop failed: ${t.message}")
                listener.onSignalingError("Signaling receive failed: ${t.message}")
            }
        }
    }
}