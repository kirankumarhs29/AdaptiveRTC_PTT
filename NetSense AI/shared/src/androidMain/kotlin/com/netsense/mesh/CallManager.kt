package com.netsense.mesh

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import net.sense.mesh.VoicePhase
import java.net.InetAddress
import java.util.UUID
import kotlin.random.Random

class CallManager(
    private val localNodeId: String,
    private val scope: CoroutineScope,
    private val listener: VoiceTransportListener,
    private val audioManager: VoiceAudioManager
) : SignalingListener, RtpListener {
    companion object {
        private const val TAG = "CallManager"
        private const val KPI_TAG = "KPI_VOICE"
        /** Exponential backoff schedule for retries (ms). */
        private val RETRY_BACKOFF_MS = longArrayOf(400L, 800L, 1600L, 2500L, 4000L)
        /** Retry interval for PROBE resend while waiting for PROBE_ACK (ms). */
        private const val PROBE_RETRY_INTERVAL_MS = 600L
        /** Max PROBE resend attempts after the initial send. */
        private const val PROBE_RETRY_MAX = 8
        /** Hard deadline for transport sync completion after first READY send (ms). */
        private const val TR_ACK_TIMEOUT_MS = 10_000L
    }

    private data class Session(
        val sessionId: String? = null,
        val attemptId: Long = 0L,
        val remotePeerId: String? = null,
        val callId: String? = null,
        val remoteAddress: InetAddress? = null,
        val remoteRtpPort: Int = VoiceTransportConfig.RTP_PORT,
        val transportConnected: Boolean = false,
        val infrastructureReady: Boolean = false,
        val signalingReady: Boolean = false,
        val heartbeatStarted: Boolean = false,
        val outgoingRequested: Boolean = false,
        val callAccepted: Boolean = false,
        val requestSent: Boolean = false,
        val remoteTransmitting: Boolean = false,
        /** Epoch-ms when remote PTT_START was last received; used for collision tie-breaking. */
        val remoteTransmittingStartedAtMs: Long = 0L,
        val selfProbeSent: Boolean = false,
        val selfProbeAcked: Boolean = false,
        val peerProbeSeen: Boolean = false,
        /** True once the TRANSPORT_READY / TRANSPORT_READY_ACK handshake completes. */
        val peerTransportReady: Boolean = false,
        /** True after we (the WiFi Direct client) have sent TRANSPORT_READY. */
        val selfTransportReadySent: Boolean = false
    )

    private val mutex = Mutex()
    private val cleanupMutex = Mutex()
    private val stateMachine = CallStateMachine()
    private val signalingManager = SignalingManager(localNodeId, scope, this)
    private val rtpManager = RtpManager(audioManager, scope, this)
    private var session = Session()
    /** Job for the TRANSPORT_READY_ACK timeout/retry on the client side. */
    private var trRetryJob: kotlinx.coroutines.Job? = null
    /** Job for PROBE resend while waiting for PROBE_ACK. */
    private var probeRetryJob: kotlinx.coroutines.Job? = null
    /** Job for the CALL_REQUEST retry on the initiator side. */
    private var callRequestRetryJob: kotlinx.coroutines.Job? = null
    /**
     * Hard 30 s floor watchdog: if remote PTT_STOP is lost, this releases the floor
     * automatically so local PTT is never permanently blocked.
     */
    private var floorTimeoutJob: kotlinx.coroutines.Job? = null
    @Volatile
    private var cleanupInProgress: Boolean = false
    /** Monotonic lifecycle token; increments on every new transport attempt/cleanup. */
    @Volatile
    private var lifecycleToken: Long = 0L
    /**
     * True while the Wi-Fi Direct group is alive and sockets are bound.
     * [callLevelCleanup] (end-call path) stays in [VoicePhase.Connected] when this is true.
     * [cleanup] (full shutdown path) always resets this to false before transitioning to Idle.
     */
    @Volatile
    private var transportAlive: Boolean = false
    /** Snapshot of isGroupOwner from the most recent [onTransportConnected] invocation. */
    @Volatile
    private var storedIsGroupOwner: Boolean = false
    @Volatile
    private var setupStartAtMs: Long = 0L

    private suspend fun sessionSnapshot(): String = mutex.withLock {
        "sessionId=${session.sessionId} attempt=${session.attemptId} callId=${session.callId} " +
            "remotePeer=${session.remotePeerId} remote=${session.remoteAddress?.hostAddress}:${session.remoteRtpPort} " +
            "transportConnected=${session.transportConnected} infraReady=${session.infrastructureReady} " +
            "probeSent=${session.selfProbeSent} probeAcked=${session.selfProbeAcked} peerProbe=${session.peerProbeSeen} " +
            "signalingReady=${session.signalingReady} hbStarted=${session.heartbeatStarted} " +
            "outgoingRequested=${session.outgoingRequested} accepted=${session.callAccepted} requestSent=${session.requestSent} " +
            "remoteTx=${session.remoteTransmitting}"
    }

    private suspend fun buildSignalMessage(
        type: SignalingMessageType,
        callId: String? = null,
        audioPort: Int? = null,
        reason: String? = null,
        peerIdOverride: String? = null
    ): SignalingMessage {
        val s = mutex.withLock { session }
        return SignalingMessage(
            type = type,
            from = localNodeId,
            sessionId = s.sessionId,
            attemptId = s.attemptId,
            peerId = peerIdOverride ?: s.remotePeerId ?: "unknown",
            callId = callId,
            audioPort = audioPort,
            reason = reason
        )
    }

    private fun eventMessage(event: SignalingEvent): SignalingMessage = when (event) {
        is SignalingEvent.Probe -> event.message
        is SignalingEvent.ProbeAck -> event.message
        is SignalingEvent.CallRequest -> event.message
        is SignalingEvent.CallAccept -> event.message
        is SignalingEvent.CallReject -> event.message
        is SignalingEvent.CallEnd -> event.message
        is SignalingEvent.Ping -> event.message
        is SignalingEvent.Pong -> event.message
        is SignalingEvent.PttStart -> event.message
        is SignalingEvent.PttStop -> event.message
        is SignalingEvent.PttBusy -> event.message
        is SignalingEvent.TransportReady -> event.message
        is SignalingEvent.TransportReadyAck -> event.message
    }

    private suspend fun shouldDropAsStale(event: SignalingEvent): Boolean {
        val msg = eventMessage(event)
        val s = mutex.withLock { session }
        val bootstrapTypes = setOf(
            SignalingMessageType.PROBE,
            SignalingMessageType.PROBE_ACK,
            SignalingMessageType.TRANSPORT_READY,
            SignalingMessageType.TRANSPORT_READY_ACK
        )

        // Explicit recipient mismatch — drop immediately.
        if (msg.peerId != null && msg.peerId != "unknown" && msg.peerId != localNodeId) {
            Log.d(TAG, "drop ${msg.type}: peerId mismatch expected=$localNodeId got=${msg.peerId}")
            return true
        }

        // Bootstrap signaling must flow even when both sides generated different
        // local session/attempt values; these messages are what synchronize state.
        if (msg.type in bootstrapTypes) {
            return false
        }

        // Call-lifecycle messages are correlated by callId, not sessionId.
        // Both sides generate sessionId independently, so cross-peer sessionId will never
        // match. If the incoming callId matches our active callId, the message is legitimate.
        val localCallId = s.callId
        if (localCallId != null && msg.callId != null && msg.callId == localCallId) {
            return false
        }

        // Non-bootstrap messages require an active local session.
        if (s.sessionId == null) {
            Log.d(TAG, "drop ${msg.type}: no active session")
            return true
        }

        // If session is known, enforce strict session/attempt matching.
        if (s.sessionId != null && msg.sessionId != null && msg.sessionId != s.sessionId) {
            Log.d(TAG, "drop ${msg.type}: session mismatch local=${s.sessionId} remote=${msg.sessionId}")
            return true
        }
        if (msg.attemptId != null && msg.attemptId != s.attemptId) {
            Log.d(TAG, "drop ${msg.type}: attempt mismatch local=${s.attemptId} remote=${msg.attemptId}")
            return true
        }

        return false
    }

    suspend fun publishDiscoveryState(detail: String) {
        Log.d(TAG, "publishDiscoveryState detail=$detail snapshot=${sessionSnapshot()}")
        publishState(VoicePhase.Discovering, detail)
    }

    /**
     * Explicitly starts an outgoing call from the [VoicePhase.Connected] state.
     *
     * Unlike [requestOutgoingCall], this method validates that the transport is ready and the
     * state machine is in [VoicePhase.Connected] before proceeding.  It is the entry point
     * for user-initiated calls after the Wi-Fi Direct group has been established.
     */
    suspend fun startCall() {
        if (cleanupInProgress) {
            Log.d(TAG, "startCall ignored: cleanup in progress")
            return
        }
        val state = stateMachine.state()
        if (state != VoicePhase.Connected) {
            Log.w(TAG, "startCall ignored: transport not in Connected state, current=$state")
            return
        }
        Log.d(TAG, "startCall: explicitly initiating call from Connected state")
        AppLogger.info(AppLogger.Module.CALL, "startCall explicit callId=${currentCallId()}")
        requestOutgoingCall()
    }

    suspend fun requestOutgoingCall() {
        if (cleanupInProgress) {
            Log.d(TAG, "requestOutgoingCall ignored: cleanup in progress")
            return
        }
        var shouldSend = false
        var remoteAddress: InetAddress? = null
        var callId: String? = null

        mutex.withLock {
            val nextCallId = session.callId ?: UUID.randomUUID().toString()
            session = session.copy(
                callId = nextCallId,
                outgoingRequested = true
            )
            remoteAddress = session.remoteAddress
            callId = nextCallId
            shouldSend = session.transportConnected && session.infrastructureReady &&
                session.peerTransportReady && remoteAddress != null && !session.requestSent
        }
        setupStartAtMs = System.currentTimeMillis()
        Log.i(KPI_TAG, "call_setup_start direction=outgoing callId=$callId remote=${remoteAddress?.hostAddress}")

        Log.d(TAG, "requestOutgoingCall snapshot=${sessionSnapshot()}")

        publishState(VoicePhase.Connecting, "transport connecting")
        if (shouldSend && remoteAddress != null && callId != null) {
            sendCallRequest(remoteAddress!!, callId!!)
        }
    }

    /**
     * Called by WifiDirectVoiceManager when the WiFi Direct group is formed.
     *
     * [remoteHint] is non-null only for the WiFi Direct **client** (the group-owner
     * IP, 192.168.49.1).  [isGroupOwner] mirrors the value from [WifiP2pInfo].
     *
     * Neither role starts the heartbeat here.  The heartbeat starts only once the
     * TRANSPORT_READY / TRANSPORT_READY_ACK handshake completes:
     *  - Client sends TRANSPORT_READY after route validation → waits for TR_ACK.
     *  - Owner receives TRANSPORT_READY → sends TR_ACK, starts heartbeat.
     *  - Client receives TR_ACK → starts heartbeat (handleTransportReadyAck).
     */
    suspend fun onTransportConnected(remoteHint: InetAddress?, isGroupOwner: Boolean) {
        if (cleanupInProgress) {
            Log.d(TAG, "onTransportConnected ignored: cleanup in progress")
            return
        }
        val attempt = ++lifecycleToken
        // Mark transport alive so callLevelCleanup() knows the group is still up and can
        // resolve to Connected instead of Idle after the call ends.
        transportAlive = true
        storedIsGroupOwner = isGroupOwner
        val alreadyPrepared = mutex.withLock { session.infrastructureReady }
        if (!alreadyPrepared) {
            val ready = prepareInfrastructure(remoteHint)
            if (!ready) {
                fail("Audio or socket preparation failed")
                return
            }
        } else {
            Log.d(TAG, "onTransportConnected infrastructure already prepared; skipping duplicate prepare")
        }

        // fail-open: if the native lib is absent EcsBridge.init() returns false
        // and the session proceeds without congestion control.
        EcsBridge.init()
        AppLogger.info(AppLogger.Module.CALL,
            "transport connected remote=${remoteHint?.hostAddress} isGroupOwner=$isGroupOwner ecsAvailable=${EcsBridge.available}")

        mutex.withLock {
            session = session.copy(
                sessionId = session.sessionId ?: UUID.randomUUID().toString(),
                attemptId = attempt,
                transportConnected = true,
                infrastructureReady = true,
                remoteAddress = remoteHint ?: session.remoteAddress,
                signalingReady = false,
                heartbeatStarted = false,
                selfProbeSent = false,
                selfProbeAcked = false,
                peerProbeSeen = false,
                peerTransportReady = false,
                selfTransportReadySent = false
            )
        }

        Log.d(TAG, "onTransportConnected remoteHint=${remoteHint?.hostAddress} isGroupOwner=$isGroupOwner snapshot=${sessionSnapshot()}")

        publishState(
            VoicePhase.Connecting,
            buildString {
                append("transport ready isGroupOwner=$isGroupOwner")
                if (remoteHint != null) append(" remote=").append(remoteHint.hostAddress)
            }
        )

        if (!isGroupOwner && remoteHint != null) {
            // CLIENT path: validate that the kernel has an installed route to the owner
            // before sending any UDP.  Without this check, ENETUNREACH surfaces and
            // onSignalingError tears down the call while the group is still forming.
            val routeOk = waitForRoute(remoteHint)
            if (!routeOk) {
                fail("No route to group owner ${remoteHint.hostAddress} after 5 s")
                return
            }
            // Start transport validation handshake.
            maybeSendProbe(remoteHint)
        }
        // OWNER path: wait for TRANSPORT_READY from the client (→ handleTransportReady).
        // No heartbeat is started here for either role.
    }

    /**
     * Polls the kernel routing table until a route to [address] exists or [maxMs] elapses.
     * Uses DatagramSocket.connect(), which is a pure kernel route-table lookup — no bytes
     * are sent over the wire.
     */
    private suspend fun waitForRoute(address: InetAddress, maxMs: Long = 5_000L): Boolean {
        val deadline = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < deadline) {
            val reachable = try {
                java.net.DatagramSocket().use { s ->
                    s.connect(address, VoiceTransportConfig.CONTROL_PORT)
                    true
                }
            } catch (_: Exception) { false }
            if (reachable) {
                AppLogger.info(AppLogger.Module.NETWORK, "route confirmed remote=${address.hostAddress}")
                return true
            }
            kotlinx.coroutines.delay(250L)
        }
        AppLogger.warn(AppLogger.Module.NETWORK, "route unavailable remote=${address.hostAddress} after ${maxMs}ms")
        return false
    }

    suspend fun startPushToTalk() {
        val gate = mutex.withLock {
            val state = stateMachine.state()
            Triple(state, session.callAccepted, session.infrastructureReady)
        }
        val allowed = (gate.first == VoicePhase.Ready || gate.first == VoicePhase.Streaming) && gate.second && gate.third
        if (!allowed) {
            Log.w(TAG, "PTT blocked state=${gate.first} accepted=${gate.second} infraReady=${gate.third}")
            AppLogger.warn(AppLogger.Module.CALL, "PTT blocked state=${gate.first} accepted=${gate.second}")
            return
        }

        // Half-duplex enforcement: block local PTT while the remote peer is transmitting.
        val (remoteTx, remoteStartedAtMs, remotePeerId) = mutex.withLock {
            Triple(session.remoteTransmitting, session.remoteTransmittingStartedAtMs, session.remotePeerId)
        }
        if (remoteTx) {
            // Collision window: if remote PTT_START arrived within the last 200 ms,
            // both sides pressed simultaneously. Use lexicographic nodeId tie-breaking:
            // lower nodeId wins the floor. The loser sends PTT_BUSY back to the winner.
            val collisionWindowMs = 200L
            val timeSinceRemoteStarted = System.currentTimeMillis() - remoteStartedAtMs
            val isCollision = remoteStartedAtMs > 0L && timeSinceRemoteStarted <= collisionWindowMs
            if (isCollision) {
                val weWin = localNodeId < (remotePeerId ?: "")
                if (weWin) {
                    // We win the tie-break: clear remote flag, send PTT_BUSY to remote,
                    // then continue to transmit normally.
                    cancelFloorWatchdog()
                    mutex.withLock {
                        session = session.copy(remoteTransmitting = false, remoteTransmittingStartedAtMs = 0L)
                    }
                    listener.onVoiceRemoteTransmitting(false)
                    val remote = currentRemoteAddress()
                    val callId = currentCallId()
                    if (remote != null) {
                        signalingManager.send(
                            SignalingMessage(type = SignalingMessageType.PTT_BUSY, from = localNodeId, callId = callId),
                            remote
                        )
                    }
                    AppLogger.info(AppLogger.Module.CALL, "floor collision: we win tie-break (localId=$localNodeId < remote=$remotePeerId)")
                    // Fall through to transmit below.
                } else {
                    // Remote wins: cancel our attempt, inform UI.
                    Log.d(TAG, "PTT collision: remote wins tie-break (localId=$localNodeId >= remote=$remotePeerId)")
                    AppLogger.info(AppLogger.Module.CALL, "floor collision: remote wins tie-break")
                    listener.onVoicePushToTalk(false)
                    return
                }
            } else {
                // Floor genuinely held by remote: block with UI feedback.
                Log.d(TAG, "PTT blocked: remote peer is currently transmitting")
                AppLogger.debug(AppLogger.Module.CALL, "floor: blocked (remote holding floor)")
                return
            }
        }

        if (!rtpManager.startStreaming(System.currentTimeMillis())) {
            return
        }
        AppLogger.info(AppLogger.Module.CALL, "PTT started callId=${currentCallId()}")

        // Notify the peer that we are now transmitting so they block their own PTT.
        val remote = currentRemoteAddress()
        val callId = currentCallId()
        if (remote != null) {
            signalingManager.send(
                SignalingMessage(type = SignalingMessageType.PTT_START, from = localNodeId, callId = callId),
                remote
            )
        }

        publishState(VoicePhase.Streaming, "push-to-talk active")
        listener.onVoicePushToTalk(true)
    }

    suspend fun stopPushToTalk() {
        rtpManager.stopStreaming()
        AppLogger.info(AppLogger.Module.CALL, "PTT stopped callId=${currentCallId()}")
        if (stateMachine.state() == VoicePhase.Streaming) {
            publishState(VoicePhase.Ready, "push-to-talk idle")
        }

        // Notify the peer that we have stopped transmitting so they can talk.
        val remote = currentRemoteAddress()
        val callId = currentCallId()
        if (remote != null) {
            signalingManager.send(
                SignalingMessage(type = SignalingMessageType.PTT_STOP, from = localNodeId, callId = callId),
                remote
            )
        }

        listener.onVoicePushToTalk(false)
    }

    /**
     * Ends the active call.
     *
     * [fullCleanup] = true → tears down the entire transport (called by [WifiDirectVoiceManager.stop]).
     * [fullCleanup] = false (default) → call-level teardown only; the Wi-Fi Direct group and
     *   sockets stay alive so the UI transitions to [VoicePhase.Connected] and the user can
     *   call again instantly without re-establishing the Wi-Fi Direct transport.
     */
    suspend fun endCall(localInitiated: Boolean, detail: String, fullCleanup: Boolean = false) {
        val state = stateMachine.state()
        val remote = currentRemoteAddress()
        val hasEstablishedCall = state in setOf(
            VoicePhase.AwaitingAccept,
            VoicePhase.Preparing,
            VoicePhase.Ready,
            VoicePhase.Streaming,
            VoicePhase.Ending
        )

        if (!hasEstablishedCall) {
            Log.d(TAG, "endCall without active call detail=$detail state=$state snapshot=${sessionSnapshot()}")
            if (fullCleanup) cleanup(detail) else callLevelCleanup(detail)
            return
        }

        if (localInitiated && remote != null) {
            // Send CALL_END synchronously on the IO thread so the packet leaves
            // before cleanup() closes the signaling socket.  Using
            // withContext(Dispatchers.IO) instead of the fire-and-forget send()
            // prevents the race where cleanup() closes the socket first.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    signalingManager.sendBlocking(
                        SignalingMessage(
                            type = SignalingMessageType.CALL_END,
                            from = localNodeId,
                            callId = currentCallId(),
                            reason = detail
                        ),
                        remote
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "CALL_END send failed (best-effort): ${t.message}")
                }
            }
        }
        publishState(VoicePhase.Ending, detail)
        listener.onVoicePushToTalk(false)
        if (fullCleanup) cleanup(detail) else callLevelCleanup(detail)
    }

    suspend fun shutdown(detail: String) {
        // Full shutdown — mark transport gone so callLevelCleanup (if racing) goes Idle too.
        transportAlive = false
        cleanup(detail)
    }

    /**
     * Call-level cleanup: tears down heartbeat, RTP, and resets session call fields.
     * Keeps the signaling socket and Wi-Fi Direct transport alive so the transition lands
     * on [VoicePhase.Connected] (if transport is alive) instead of [VoicePhase.Idle].
     * This is the path taken when the *call* ends but the *connection* persists.
     */
    private suspend fun callLevelCleanup(detail: String) {
        cleanupMutex.withLock {
            cleanupInProgress = true
            try {
                Log.d(TAG, "callLevelCleanup detail=$detail transportAlive=$transportAlive")
                AppLogger.info(AppLogger.Module.CALL, "call-level cleanup detail=$detail")

                trRetryJob?.cancel()
                trRetryJob = null
                probeRetryJob?.cancel()
                probeRetryJob = null
                callRequestRetryJob?.cancel()
                callRequestRetryJob = null

                val wasTransportAlive = transportAlive
                val savedRemote: InetAddress?
                val savedRtpPort: Int
                val savedInfraReady: Boolean
                val savedTransportConnected: Boolean
                val savedPeerTransportReady: Boolean
                // Transport-layer identifiers preserved for between-call heartbeat.
                val savedSessionId: String?
                val savedAttemptId: Long
                val savedPeerId: String?
                mutex.withLock {
                    savedRemote             = session.remoteAddress
                    savedRtpPort            = session.remoteRtpPort
                    savedInfraReady         = session.infrastructureReady
                    savedTransportConnected = session.transportConnected
                    savedPeerTransportReady = session.peerTransportReady
                    savedSessionId          = session.sessionId
                    savedAttemptId          = session.attemptId
                    savedPeerId             = session.remotePeerId
                    // Reset only call-level fields; preserve transport + session identifiers
                    // so the between-call heartbeat and stale-message filter keep working.
                    session = Session(
                        sessionId           = session.sessionId,
                        attemptId           = session.attemptId,
                        remotePeerId        = session.remotePeerId,
                        remoteAddress       = session.remoteAddress,
                        remoteRtpPort       = session.remoteRtpPort,
                        transportConnected  = session.transportConnected,
                        infrastructureReady = session.infrastructureReady,
                        peerTransportReady  = session.peerTransportReady,
                        signalingReady      = session.signalingReady
                    )
                }

                stateMachine.transitionTo(VoicePhase.Ending, detail)

                // Cancel floor watchdog before stopping heartbeat.
                cancelFloorWatchdog()

                // Stop call-level heartbeat — keep the receive loop and socket alive so we can
                // accept an incoming CALL_REQUEST from the peer in Connected state.
                safeCleanupStep("callLevelCleanup: stopHeartbeat") { signalingManager.stopHeartbeat() }
                // Stop only the send/capture path — keep the receive socket and AudioTrack alive
                // so that `infrastructureReady = true` remains accurate.  Without this, the second
                // call skips prepareInfrastructure() (because the flag is true) but finds a null
                // receive socket, causing the first PTT to fail → fail() → full transport teardown.
                rtpManager.stopStreaming()
                // Leave ECS initialised so congestion control is active on the next call without
                // needing a full onTransportConnected() cycle. startStreaming() calls EcsBridge.reset()
                // at each PTT press to clear per-burst state.

                listener.onVoicePacketStats(0, 0)
                listener.onVoiceRemoteTransmitting(false)
                listener.onVoicePushToTalk(false)

                if (wasTransportAlive && savedRemote != null) {
                    // Group is still alive — surface Connected so the user can call again
                    // without re-establishing the Wi-Fi Direct transport.
                    stateMachine.transitionTo(VoicePhase.Connected, "call ended — transport alive")
                    // Resume between-call liveness heartbeat so we detect peer crash/disconnect
                    // even when no call is in progress. Uses preserved session identifiers so the
                    // shouldDropAsStale filter continues to accept PONG messages.
                    if (savedSessionId != null && savedPeerId != null) {
                        signalingManager.startHeartbeat(
                            remoteAddress = savedRemote,
                            callId        = null,
                            sessionId     = savedSessionId,
                            attemptId     = savedAttemptId,
                            peerId        = savedPeerId
                        )
                        AppLogger.info(AppLogger.Module.CALL,
                            "between-call heartbeat started remote=${savedRemote.hostAddress} sessionId=$savedSessionId")
                    }
                    listener.onVoicePhaseChanged(VoicePhase.Connected, "ready to call again")
                    Log.d(TAG, "callLevelCleanup complete: resolved to Connected with active heartbeat")
                } else {
                    stateMachine.transitionTo(VoicePhase.Idle, "cleanup complete")
                    runCatching { signalingManager.stop() }
                        .onFailure { Log.w(TAG, "callLevelCleanup: signaling stop failed", it) }
                    listener.onVoicePhaseChanged(VoicePhase.Idle, "cleanup complete")
                    Log.d(TAG, "callLevelCleanup complete: resolved to Idle (transport gone)")
                }
            } finally {
                cleanupInProgress = false
            }
        }
    }

    private suspend fun safeCleanupStep(step: String, timeoutMs: Long = 1500L, block: suspend () -> Unit) {
        try {
            withTimeout(timeoutMs) { block() }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "$step timed out after ${timeoutMs}ms")
        } catch (t: Throwable) {
            Log.w(TAG, "$step failed", t)
        }
    }

    /** Full teardown — stops everything including the signaling socket; always resolves to Idle. */
    private suspend fun cleanup(detail: String) {
        cleanupMutex.withLock {
            // Full cleanup resets transport state; any concurrent callLevelCleanup will
            // also resolve to Idle because transportAlive is now false.
            transportAlive = false
            cleanupInProgress = true
            try {
                Log.d(TAG, "cleanup detail=$detail snapshotBefore=${sessionSnapshot()}")
                AppLogger.info(AppLogger.Module.CALL, "call cleanup detail=$detail")

                // Cancel pending retry jobs so they don't fire after session is cleared.
                trRetryJob?.cancel()
                trRetryJob = null
                probeRetryJob?.cancel()
                probeRetryJob = null
                callRequestRetryJob?.cancel()
                callRequestRetryJob = null
                cancelFloorWatchdog()

                // Clear session first so stale callbacks cannot reuse the previous call context.
                mutex.withLock {
                    session = Session()
                }

                // Best-effort convergence back to Idle; ignore rejected transitions.
                stateMachine.transitionTo(VoicePhase.Ending, detail)
                stateMachine.transitionTo(VoicePhase.Idle, "cleanup complete")

                // Use safeCleanupStep (with timeout) so a hung stop/shutdown cannot
                // block shutdownJob indefinitely and prevent the next connect().
                safeCleanupStep("cleanup: signaling stop") { signalingManager.stop() }
                safeCleanupStep("cleanup: rtp stop") { rtpManager.stop() }
                safeCleanupStep("cleanup: ecs shutdown") { EcsBridge.shutdown() }

                listener.onVoicePacketStats(0, 0)
                listener.onVoiceRemoteTransmitting(false)
                listener.onVoicePushToTalk(false)
                listener.onVoicePhaseChanged(VoicePhase.Idle, "cleanup complete")
                Log.d(TAG, "cleanup complete snapshotAfter=${sessionSnapshot()}")
            } finally {
                cleanupInProgress = false
            }
        }
    }

    override fun onRemoteEndpointDiscovered(address: InetAddress) {
        scope.launch {
            if (cleanupInProgress) {
                Log.d(TAG, "onRemoteEndpointDiscovered ignored: cleanup in progress")
                return@launch
            }
            var shouldSendRequest = false
            mutex.withLock {
                session = session.copy(remoteAddress = address)
                shouldSendRequest = session.transportConnected && session.infrastructureReady &&
                    session.peerTransportReady && session.outgoingRequested && !session.requestSent
            }

            // Owner learns client endpoint here; this is the earliest point where owner can
            // send transport validation PROBE.
            maybeSendProbe(address)

            rtpManager.setRemoteEndpoint(address, currentRemoteRtpPort())
            // Heartbeat is controlled by the TRANSPORT_READY/ACK exchange; do not start it here.

            if (shouldSendRequest) {
                val callId = currentCallId()
                if (callId != null) {
                    sendCallRequest(address, callId)
                }
            }
        }
    }

    override fun onSignalingEvent(event: SignalingEvent) {
        scope.launch {
            if (cleanupInProgress) {
                Log.d(TAG, "onSignalingEvent ${event::class.simpleName} ignored: cleanup in progress")
                return@launch
            }
            if (shouldDropAsStale(event)) {
                return@launch
            }
            when (event) {
                is SignalingEvent.Probe       -> handleProbe(event)
                is SignalingEvent.ProbeAck    -> handleProbeAck(event)
                is SignalingEvent.CallRequest -> handleCallRequest(event)
                is SignalingEvent.CallAccept  -> handleCallAccept(event)
                is SignalingEvent.CallReject  -> handleCallReject(event)
                is SignalingEvent.CallEnd     -> handleCallEnd(event)
                is SignalingEvent.Ping        -> handlePing(event)
                is SignalingEvent.Pong        -> handlePong(event)
                is SignalingEvent.PttStart          -> handleRemotePttStart()
                is SignalingEvent.PttStop           -> handleRemotePttStop()
                is SignalingEvent.PttBusy           -> handlePttBusy()
                is SignalingEvent.TransportReady    -> handleTransportReady(event)
                is SignalingEvent.TransportReadyAck -> handleTransportReadyAck(event)
            }
        }
    }

    override fun onSignalingError(reason: String) {
        scope.launch { fail(reason) }
    }

    override fun onRtpStats(txPackets: Long, rxPackets: Long) {
        listener.onVoicePacketStats(txPackets, rxPackets)
    }

    override fun onRtpError(reason: String) {
        scope.launch { fail(reason) }
    }

    /**
     * Called by [RtpManager] when no RTP packets have arrived for ~2 s while the
     * remote floor flag is set. This handles the common case where PTT_STOP is lost
     * but audio also stops — double coverage with the 30 s hard watchdog.
     */
    override fun onRtpReceiveSilence(silenceMs: Long) {
        scope.launch {
            val wasHeld = mutex.withLock {
                val held = session.remoteTransmitting
                if (held) session = session.copy(remoteTransmitting = false, remoteTransmittingStartedAtMs = 0L)
                held
            }
            if (wasHeld) {
                cancelFloorWatchdog()
                AppLogger.info(AppLogger.Module.CALL,
                    "floor: silence-release after ${silenceMs}ms (PTT_STOP likely lost)")
                audioManager.onPlaybackIdle()
                listener.onVoiceRemoteTransmitting(false)
            }
        }
    }

    /**
     * Starts all voice-path prerequisites in parallel:
     *  - UDP control socket (SignalingManager)
     *  - UDP RTP receive socket (RtpManager)
     *  - AudioTrack allocation (VoiceAudioManager.preparePlayback)
     *  - Capture hardware check (VoiceAudioManager.validateCaptureSupport)
     *
     * All four operations are independent and can run concurrently on IO threads,
     * cutting sequential latency (~40-120 ms) down to the slowest single operation.
     */
    private suspend fun prepareInfrastructure(remoteHint: InetAddress?): Boolean = coroutineScope {
        val signalingDeferred = async(Dispatchers.IO) { signalingManager.start() }
        val captureDeferred   = async(Dispatchers.IO) { audioManager.validateCaptureSupport() }
        val playbackDeferred  = async(Dispatchers.IO) { audioManager.preparePlayback() }
        val rtpDeferred       = async(Dispatchers.IO) { rtpManager.bindReceiver() }

        val signalingReady = signalingDeferred.await()
        val captureReady   = captureDeferred.await()
        val playbackReady  = playbackDeferred.await()
        val rtpReady       = rtpDeferred.await()

        if (remoteHint != null) {
            rtpManager.setRemoteEndpoint(remoteHint, currentRemoteRtpPort())
        }
        Log.d(
            TAG,
            "prepare signaling=$signalingReady capture=$captureReady playback=$playbackReady rtp=$rtpReady remote=${remoteHint?.hostAddress}"
        )
        signalingReady && captureReady && playbackReady && rtpReady
    }

    /**
     * Pre-warms voice infrastructure (sockets + AudioTrack) during the Wi-Fi Direct
     * discovery/group-formation window so that [onTransportConnected] finds everything
     * already ready and skips the sequential prepare phase entirely.
     *
     * Safe to call multiple times — idempotent at both CallManager and component level.
     */
    suspend fun prewarm() {
        val alreadyPrepared = mutex.withLock { session.infrastructureReady }
        if (alreadyPrepared) {
            Log.d(TAG, "prewarm skipped: infrastructure already prepared")
            return
        }
        Log.d(TAG, "prewarm: warming socket and audio infrastructure in parallel")
        val ready = prepareInfrastructure(null)
        if (ready) {
            mutex.withLock {
                session = session.copy(infrastructureReady = true)
            }
            Log.d(TAG, "prewarm complete: infrastructure ready before group formation")
        } else {
            Log.w(TAG, "prewarm failed: will retry on onTransportConnected")
        }
    }

    private suspend fun sendCallRequest(remoteAddress: InetAddress, callId: String) {
        mutex.withLock {
            session = session.copy(requestSent = true)
        }
        signalingManager.send(
            SignalingMessage(
                type = SignalingMessageType.CALL_REQUEST,
                from = localNodeId,
                callId = callId,
                audioPort = VoiceTransportConfig.RTP_PORT
            ),
            remoteAddress
        )
        publishState(VoicePhase.AwaitingAccept, "CALL_REQUEST sent to ${remoteAddress.hostAddress}")

        // Retry CALL_REQUEST if no CALL_ACCEPT arrives.  UDP is unreliable — losing
        // the single CALL_REQUEST leaves the initiator stuck at AwaitingAccept forever.
        callRequestRetryJob?.cancel()
        callRequestRetryJob = scope.launch {
            repeat(RETRY_BACKOFF_MS.size) { attempt ->
                val delayMs = RETRY_BACKOFF_MS[attempt] + Random.nextLong(0, 150)
                kotlinx.coroutines.delay(delayMs)
                val accepted = mutex.withLock { session.callAccepted }
                if (accepted || cleanupInProgress) return@launch
                AppLogger.info(AppLogger.Module.CALL,
                    "call-request retry attempt=${attempt + 1}/${RETRY_BACKOFF_MS.size} callId=$callId")
                signalingManager.send(
                    SignalingMessage(
                        type = SignalingMessageType.CALL_REQUEST,
                        from = localNodeId,
                        callId = callId,
                        audioPort = VoiceTransportConfig.RTP_PORT
                    ),
                    remoteAddress
                )
            }
            // All retries exhausted — if still no answer, surface a timeout so the user
            // can try again instead of being stuck in AwaitingAccept indefinitely.
            val accepted = mutex.withLock { session.callAccepted }
            if (!accepted && !cleanupInProgress) {
                AppLogger.warn(AppLogger.Module.CALL,
                    "call-request timed out after ${RETRY_BACKOFF_MS.size} retries: no answer callId=$callId")
                // fullCleanup=false: group is still alive, go back to Connected so the user
                // can immediately retry without re-establishing the Wi-Fi Direct transport.
                endCall(localInitiated = true, detail = "no answer", fullCleanup = false)
            }
        }
    }

    private suspend fun handleCallRequest(event: SignalingEvent.CallRequest) {
        setupStartAtMs = System.currentTimeMillis()
        Log.i(KPI_TAG, "call_setup_start direction=incoming callId=${event.message.callId} remote=${event.remoteAddress.hostAddress}")

        val state = stateMachine.state()
        val glareResolution = mutex.withLock {
            val outgoingWaiting = state == VoicePhase.AwaitingAccept && session.outgoingRequested && !session.callAccepted
            if (!outgoingWaiting) {
                null
            } else {
                // Deterministic tie-breaker for simultaneous outgoing requests.
                // One side yields to avoid both returning CALL_REJECT(busy).
                val localYields = localNodeId > event.message.from
                localYields
            }
        }

        if (glareResolution != null) {
            if (glareResolution) {
                Log.w(TAG, "glare detected; yielding to remote caller=${event.message.from}")
                mutex.withLock {
                    session = session.copy(
                        callId = event.message.callId ?: session.callId,
                        outgoingRequested = false,
                        requestSent = false
                    )
                }
            } else {
                Log.w(TAG, "glare detected; keeping local outgoing call and rejecting remote as busy")
                signalingManager.send(
                    SignalingMessage(
                        type = SignalingMessageType.CALL_REJECT,
                        from = localNodeId,
                        callId = event.message.callId,
                        reason = "busy"
                    ),
                    event.remoteAddress
                )
                return
            }
        }

        // Idempotent handling: if this is a duplicate CALL_REQUEST for the same call that
        // we already accepted, re-send CALL_ACCEPT instead of replying busy.
        val duplicateAccepted = mutex.withLock {
            val activeCallId = session.callId
            session.callAccepted &&
                activeCallId != null &&
                event.message.callId != null &&
                activeCallId == event.message.callId
        }
        if (duplicateAccepted) {
            rtpManager.setRemoteEndpoint(event.remoteAddress, event.message.audioPort ?: VoiceTransportConfig.RTP_PORT)
            signalingManager.send(
                buildSignalMessage(
                    type = SignalingMessageType.CALL_ACCEPT,
                    callId = event.message.callId,
                    audioPort = VoiceTransportConfig.RTP_PORT
                ),
                event.remoteAddress
            )
            AppLogger.info(
                AppLogger.Module.CALL,
                "duplicate call-request re-acked callId=${event.message.callId} remote=${event.remoteAddress.hostAddress}"
            )
            return
        }

        val busy = mutex.withLock {
            when (stateMachine.state()) {
                VoicePhase.Preparing,
                VoicePhase.Ready,
                VoicePhase.Streaming,
                VoicePhase.Ending -> true
                else -> false
            }
        }
        if (busy) {
            signalingManager.send(
                SignalingMessage(
                    type = SignalingMessageType.CALL_REJECT,
                    from = localNodeId,
                    callId = event.message.callId,
                    reason = "busy"
                ),
                event.remoteAddress
            )
            return
        }

        // Only prepare infrastructure when it hasn't been done yet. The group-owner
        // side calls onTransportConnected before any signaling arrives, so infra is
        // already set up by the time this CALL_REQUEST lands.
        val alreadyPrepared = mutex.withLock { session.infrastructureReady }
        if (!alreadyPrepared) {
            val ready = prepareInfrastructure(event.remoteAddress)
            if (!ready) {
                fail("Incoming call preparation failed")
                return
            }
        }

        mutex.withLock {
            session = session.copy(
                callId = event.message.callId ?: UUID.randomUUID().toString(),
                remoteAddress = event.remoteAddress,
                remoteRtpPort = event.message.audioPort ?: VoiceTransportConfig.RTP_PORT,
                transportConnected = true,
                infrastructureReady = true,
                outgoingRequested = false,
                callAccepted = true,
                requestSent = false
            )
        }

        rtpManager.setRemoteEndpoint(event.remoteAddress, event.message.audioPort ?: VoiceTransportConfig.RTP_PORT)
        maybeStartSignaling(event.remoteAddress)
        publishState(VoicePhase.Preparing, "CALL_REQUEST received from ${event.message.from}")

        signalingManager.send(
            buildSignalMessage(
                type = SignalingMessageType.CALL_ACCEPT,
                callId = currentCallId(),
                audioPort = VoiceTransportConfig.RTP_PORT
            ),
            event.remoteAddress
        )

        promoteReady("CALL_ACCEPT sent")
    }

    private suspend fun handleCallAccept(event: SignalingEvent.CallAccept) {
        val knownCallId = currentCallId()
        if (knownCallId != null && event.message.callId != null && event.message.callId != knownCallId) {
            Log.w(TAG, "ignoring CALL_ACCEPT for unknown callId=${event.message.callId}")
            return
        }
        // Stop retrying CALL_REQUEST — the peer accepted.
        callRequestRetryJob?.cancel()
        callRequestRetryJob = null

        mutex.withLock {
            session = session.copy(
                remoteAddress = event.remoteAddress,
                remoteRtpPort = event.message.audioPort ?: VoiceTransportConfig.RTP_PORT,
                callAccepted = true,
                transportConnected = true,
                infrastructureReady = true
            )
        }

        rtpManager.setRemoteEndpoint(event.remoteAddress, event.message.audioPort ?: VoiceTransportConfig.RTP_PORT)
        maybeStartSignaling(event.remoteAddress)
        publishState(VoicePhase.Preparing, "CALL_ACCEPT received")
        promoteReady("media ready")
    }

    private suspend fun handleCallReject(event: SignalingEvent.CallReject) {
        val reason = event.message.reason ?: "call rejected"
        val state = stateMachine.state()
        val accepted = mutex.withLock { session.callAccepted }

        // During simultaneous call setup, a stale busy reject can arrive after
        // media has already been accepted and promoted to Ready.
        if (reason.equals("busy", ignoreCase = true) && accepted &&
            state in setOf(VoicePhase.Preparing, VoicePhase.Ready, VoicePhase.Streaming)
        ) {
            Log.w(TAG, "Ignoring stale CALL_REJECT busy in state=$state accepted=$accepted")
            return
        }

        publishState(VoicePhase.Ending, reason)
        // Call-level cleanup: a rejected call does not tear down the Wi-Fi Direct transport.
        callLevelCleanup(reason)
    }

    private suspend fun handleCallEnd(event: SignalingEvent.CallEnd) {
        val reason = event.message.reason ?: "remote ended call"
        publishState(VoicePhase.Ending, reason)
        // Call-level cleanup: remote ended the call but the Wi-Fi Direct group is still
        // alive, so we resolve to Connected rather than Idle.
        callLevelCleanup(reason)
    }

    private suspend fun handleRemotePttStart() {
        mutex.withLock {
            session = session.copy(
                remoteTransmitting = true,
                remoteTransmittingStartedAtMs = System.currentTimeMillis()
            )
        }
        Log.d(TAG, "remote PTT started - local PTT now blocked")
        AppLogger.info(AppLogger.Module.CALL, "floor: remote PTT started")
        listener.onVoiceRemoteTransmitting(true)
        startFloorWatchdog()
    }

    private suspend fun handleRemotePttStop() {
        cancelFloorWatchdog()
        mutex.withLock {
            session = session.copy(remoteTransmitting = false, remoteTransmittingStartedAtMs = 0L)
        }
        Log.d(TAG, "remote PTT stopped - local PTT now unblocked")
        AppLogger.info(AppLogger.Module.CALL, "floor: remote PTT stopped")
        audioManager.onPlaybackIdle()
        listener.onVoiceRemoteTransmitting(false)
    }

    /** Remote sends PTT_BUSY in response to a collision where they win the tie-break. */
    private fun handlePttBusy() {
        rtpManager.stopStreaming()
        AppLogger.info(AppLogger.Module.CALL, "floor: received PTT_BUSY — floor held by remote, transmission cancelled")
        listener.onVoicePushToTalk(false)
    }

    /**
     * Starts a 30 s watchdog that releases the remote floor if PTT_STOP is lost.
     * Also wires into [onRtpReceiveSilence]: if no audio arrives for 2 s while the
     * floor is held, the floor is released early.
     */
    private fun startFloorWatchdog() {
        floorTimeoutJob?.cancel()
        floorTimeoutJob = scope.launch {
            kotlinx.coroutines.delay(30_000L)
            val wasHeld = mutex.withLock {
                val held = session.remoteTransmitting
                if (held) session = session.copy(remoteTransmitting = false, remoteTransmittingStartedAtMs = 0L)
                held
            }
            if (wasHeld) {
                AppLogger.warn(AppLogger.Module.CALL, "floor-watchdog: remote floor force-released after 30 s (PTT_STOP lost)")
                audioManager.onPlaybackIdle()
                listener.onVoiceRemoteTransmitting(false)
            }
        }
    }

    private fun cancelFloorWatchdog() {
        floorTimeoutJob?.cancel()
        floorTimeoutJob = null
    }

    /**
     * Sends a transport PROBE once a remote endpoint is known and local infra is ready.
     */
    private suspend fun maybeSendProbe(remote: InetAddress) {
        val shouldSend = mutex.withLock {
            session.transportConnected &&
                session.infrastructureReady &&
                !session.selfProbeSent
        }
        if (!shouldSend) return

        val routeOk = SignalingManager.hasRouteToAddress(remote, VoiceTransportConfig.CONTROL_PORT)
        if (!routeOk) return

        mutex.withLock {
            session = session.copy(selfProbeSent = true)
        }
        signalingManager.send(
            buildSignalMessage(SignalingMessageType.PROBE),
            remote,
            reportError = false
        )
        AppLogger.info(AppLogger.Module.NETWORK, "probe sent remote=${remote.hostAddress}")

        // Handle race where peer binds signaling socket after our first PROBE.
        probeRetryJob?.cancel()
        probeRetryJob = scope.launch {
            repeat(PROBE_RETRY_MAX) { attempt ->
                kotlinx.coroutines.delay(PROBE_RETRY_INTERVAL_MS)
                val shouldRetry = mutex.withLock {
                    session.transportConnected &&
                        session.infrastructureReady &&
                        !session.selfProbeAcked &&
                        !session.peerProbeSeen
                }
                if (!shouldRetry || cleanupInProgress) return@launch
                signalingManager.send(
                    buildSignalMessage(SignalingMessageType.PROBE),
                    remote,
                    reportError = false
                )
                AppLogger.info(AppLogger.Module.NETWORK,
                    "probe retry attempt=${attempt + 1}/$PROBE_RETRY_MAX remote=${remote.hostAddress}")
            }
        }
    }

    private suspend fun handleProbe(event: SignalingEvent.Probe) {
        probeRetryJob?.cancel()
        probeRetryJob = null
        val remote = event.remoteAddress

        // Record peer identity/session context from the probe.
        mutex.withLock {
            session = session.copy(
                remoteAddress = remote,
                remotePeerId = event.message.from,
                peerProbeSeen = true
            )
        }

        // Always ACK probe. We have already received a UDP datagram from this remote,
        // which is stronger evidence than a transient route-table check.
        val routeOk = SignalingManager.hasRouteToAddress(remote, VoiceTransportConfig.CONTROL_PORT)
        if (!routeOk) {
            AppLogger.warn(AppLogger.Module.NETWORK,
                "probe-ack route-check false; sending ack anyway remote=${remote.hostAddress}")
        }

        signalingManager.send(
            buildSignalMessage(SignalingMessageType.PROBE_ACK, peerIdOverride = event.message.from),
            remote,
            reportError = false
        )
        AppLogger.info(AppLogger.Module.NETWORK, "probe-ack sent remote=${remote.hostAddress}")

        // Owner can only advance sync after seeing a valid probe from client.
        maybeSendTransportReadyClient()
        maybeEnterSignalingReady(remote)
    }

    private suspend fun handleProbeAck(event: SignalingEvent.ProbeAck) {
        probeRetryJob?.cancel()
        probeRetryJob = null
        mutex.withLock {
            session = session.copy(
                remoteAddress = event.remoteAddress,
                remotePeerId = event.message.from,
                selfProbeAcked = true
            )
        }
        AppLogger.info(AppLogger.Module.NETWORK, "probe-ack received remote=${event.remoteAddress.hostAddress}")
        maybeSendTransportReadyClient()
        maybeEnterSignalingReady(event.remoteAddress)
    }

    /** Client-only: sends TRANSPORT_READY after transport validation completes on both sides. */
    private suspend fun maybeSendTransportReadyClient() {
        var remote: InetAddress? = null
        val shouldSend: Boolean
        mutex.withLock {
            remote = session.remoteAddress
            shouldSend = !storedIsGroupOwner &&
                session.transportConnected &&
                session.infrastructureReady &&
                session.selfProbeAcked &&
                session.peerProbeSeen &&
                !session.selfTransportReadySent
            if (shouldSend) {
                session = session.copy(selfTransportReadySent = true)
            }
        }
        if (!shouldSend || remote == null) return

        trRetryJob?.cancel()
        trRetryJob = scope.launch {
            val startMs = System.currentTimeMillis()
            var attempt = 0
            while (true) {
                val acked = mutex.withLock { session.peerTransportReady }
                if (acked || cleanupInProgress) return@launch
                if (System.currentTimeMillis() - startMs >= TR_ACK_TIMEOUT_MS) {
                    fail("TRANSPORT_READY_ACK not received from ${remote!!.hostAddress} after ${TR_ACK_TIMEOUT_MS}ms")
                    return@launch
                }

                signalingManager.send(
                    buildSignalMessage(SignalingMessageType.TRANSPORT_READY),
                    remote!!,
                    reportError = false
                )
                val base = RETRY_BACKOFF_MS[minOf(attempt, RETRY_BACKOFF_MS.lastIndex)]
                val jitter = Random.nextLong(0, 151)
                kotlinx.coroutines.delay(base + jitter)
                attempt++
            }
        }
    }

    /**
     * Promotes the protocol to SignalingReady once validation + sync are done,
     * then calls [maybeStartSignaling] (single heartbeat gate).
     *
     * Probe validation is asymmetric:
     *  - Owner never initiates a PROBE (only replies with PROBE_ACK), so [Session.selfProbeAcked]
     *    is always false on the owner.  The owner validates by seeing the client's probe
     *    ([Session.peerProbeSeen]).
     *  - Client never receives a PROBE (only sends one and waits for PROBE_ACK), so
     *    [Session.peerProbeSeen] is always false on the client.  The client validates by
     *    receiving the PROBE_ACK ([Session.selfProbeAcked]).
     *
     * Returns true if signaling was promoted (caller may skip a redundant publishState).
     */
    private suspend fun maybeEnterSignalingReady(remote: InetAddress?): Boolean {
        val shouldPromote = mutex.withLock {
            val probeValidated = if (storedIsGroupOwner) {
                session.peerProbeSeen   // owner: validated by receiving client's probe
            } else {
                session.selfProbeAcked  // client: validated by receiving PROBE_ACK
            }
            session.transportConnected &&
                session.infrastructureReady &&
                probeValidated &&
                session.peerTransportReady
        }
        if (!shouldPromote) return false

        mutex.withLock {
            session = session.copy(signalingReady = true)
        }
        publishState(VoicePhase.Connected, "voice transport ready")
        maybeStartSignaling(remote ?: currentRemoteAddress())
        return true
    }

    /**
     * Single heartbeat gate.
     * Conditions:
     *  - validated + synced transport (signalingReady)
     *  - current lifecycle token matches active session attempt
     *  - state is Connected/Preparing/Ready/Streaming
     */
    private suspend fun maybeStartSignaling(remote: InetAddress?) {
        val r = remote ?: return
        val state = stateMachine.state()
        val shouldStart = mutex.withLock {
            val tokenMatches = lifecycleToken == session.attemptId
            val phaseOk = state in setOf(VoicePhase.Connected, VoicePhase.Preparing, VoicePhase.Ready, VoicePhase.Streaming)
            val ok = session.signalingReady && tokenMatches && phaseOk && !session.heartbeatStarted
            if (ok) {
                session = session.copy(heartbeatStarted = true)
            }
            ok
        }
        if (!shouldStart) return

        val s = mutex.withLock { session }
        signalingManager.startHeartbeat(
            remoteAddress = r,
            callId = s.callId,
            sessionId = s.sessionId,
            attemptId = s.attemptId,
            peerId = s.remotePeerId ?: "unknown"
        )
    }

    /**
     * Owner receives TRANSPORT_READY from the client.  Records the client's IP, sends
     * TRANSPORT_READY_ACK, then starts the heartbeat.  Also sends CALL_REQUEST if this
     * device is the call initiator and the request has been queued.
     */
    private suspend fun handleTransportReady(event: SignalingEvent.TransportReady) {
        if (cleanupInProgress) {
            Log.d(TAG, "handleTransportReady ignored: cleanup in progress")
            return
        }
        // Defensively ensure infra is ready (may arrive before onTransportConnected runs).
        val alreadyPrepared = mutex.withLock { session.infrastructureReady }
        if (!alreadyPrepared) {
            val ready = prepareInfrastructure(event.remoteAddress)
            if (!ready) {
                fail("Infrastructure preparation failed on TRANSPORT_READY receipt")
                return
            }
        }
        mutex.withLock {
            session = session.copy(
                remoteAddress = event.remoteAddress,
                transportConnected = true,
                infrastructureReady = true,
                peerTransportReady = true
            )
        }
        rtpManager.setRemoteEndpoint(event.remoteAddress, currentRemoteRtpPort())
        AppLogger.info(AppLogger.Module.NETWORK, "transport-ready-ack sent remote=${event.remoteAddress.hostAddress}")
        signalingManager.send(
            buildSignalMessage(type = SignalingMessageType.TRANSPORT_READY_ACK),
            event.remoteAddress,
            reportError = false
        )
        AppLogger.info(AppLogger.Module.CALL, "peer transport ready remote=${event.remoteAddress.hostAddress}")
        // maybeEnterSignalingReady emits Connected and starts the heartbeat when it promotes;
        // fall back to a plain publishState only if promotion did not happen (defensive).
        val promoted = maybeEnterSignalingReady(event.remoteAddress)
        if (!promoted) {
            maybeStartSignaling(event.remoteAddress)
            publishState(VoicePhase.Connected, "voice transport ready")
        }
    }

    /**
     * Client receives TRANSPORT_READY_ACK from the owner.  Sets peerTransportReady,
     * starts the heartbeat, then emits Connected to await an explicit call.
     */
    private suspend fun handleTransportReadyAck(event: SignalingEvent.TransportReadyAck) {
        if (cleanupInProgress) {
            Log.d(TAG, "handleTransportReadyAck ignored: cleanup in progress")
            return
        }
        // Stop TR retry loop — the owner acknowledged.
        trRetryJob?.cancel()
        trRetryJob = null
        mutex.withLock {
            session = session.copy(peerTransportReady = true)
        }
        val remote = currentRemoteAddress() ?: run {
            Log.w(TAG, "handleTransportReadyAck: no remote address in session; ignoring")
            return
        }
        AppLogger.info(AppLogger.Module.CALL, "peer transport ready ack remote=${remote.hostAddress}")
        // maybeEnterSignalingReady emits Connected and starts the heartbeat when it promotes;
        // fall back to a plain publishState only if promotion did not happen (defensive).
        val promoted = maybeEnterSignalingReady(remote)
        if (!promoted) {
            maybeStartSignaling(remote)
            publishState(VoicePhase.Connected, "voice transport ready")
        }
    }

    private suspend fun handlePing(event: SignalingEvent.Ping) {
        val shouldPromote = mutex.withLock {
            session = session.copy(
                remoteAddress = event.remoteAddress,
                remoteRtpPort = event.message.audioPort ?: session.remoteRtpPort
            )
            session.callAccepted && session.infrastructureReady
        }
        rtpManager.setRemoteEndpoint(event.remoteAddress, event.message.audioPort ?: currentRemoteRtpPort())
        if (shouldPromote) {
            promoteReady("heartbeat synchronized")
        }
    }

    /**
     * PONG is the echo of our own PING.
     * [event.message.timestampMs] is the original PING send-time in milliseconds.
     * RTT (ms) = now - timestampMs  → feed to ECS in microseconds.
     */
    private fun handlePong(event: SignalingEvent.Pong) {
        val rttMs = System.currentTimeMillis() - event.message.timestampMs
        if (rttMs in 1L..30_000L) {                     // sanity-clamp: 1 ms – 30 s
            val rttUs = rttMs * 1_000L
            EcsBridge.addRttSample(rttUs)
            // Sampled UI-layer RTT log (every 25 PONGs = ~50 s):
            AppLogger.rttSample(
                rttMs,
                when (EcsBridge.analyzeCongestion()) {
                    EcsBridge.STATUS_BUILDING -> "BUILDING"
                    EcsBridge.STATUS_IMMINENT -> "IMMINENT"
                    else -> "NO_CONGESTION"
                },
                EcsBridge.getCurrentRateBps()
            )
            Log.d(TAG, "ECS rtt=${rttMs}ms confidence=${(EcsBridge.getConfidence() * 100).toInt()}%")
        }
    }

    private suspend fun promoteReady(detail: String) {
        val remoteAddress: InetAddress?
        val remotePort: Int
        val shouldPromote: Boolean
        val currentState = stateMachine.state()

        if (currentState == VoicePhase.Ready || currentState == VoicePhase.Streaming) {
            return
        }

        mutex.withLock {
            remoteAddress = session.remoteAddress
            remotePort = session.remoteRtpPort
            shouldPromote = session.callAccepted && session.infrastructureReady && remoteAddress != null
        }

        if (!shouldPromote || remoteAddress == null) return
        rtpManager.setRemoteEndpoint(remoteAddress!!, remotePort)
        val setupMs = if (setupStartAtMs > 0L) System.currentTimeMillis() - setupStartAtMs else -1L
        Log.i(
            KPI_TAG,
            "call_ready callId=${currentCallId()} remote=${remoteAddress!!.hostAddress}:$remotePort setupMs=$setupMs detail=$detail"
        )
        Log.d(TAG, "voice ready remote=${remoteAddress!!.hostAddress}:$remotePort detail=$detail")
        publishState(VoicePhase.Ready, detail)
        listener.onVoicePathReady(remoteAddress!!.hostAddress ?: "unknown", remotePort)
    }

    private suspend fun publishState(state: VoicePhase, detail: String) {
        stateMachine.transitionTo(state, detail)
        val current = stateMachine.state()
        Log.d(TAG, "publishState phase=$current detail=$detail snapshot=${sessionSnapshot()}")
        AppLogger.info(AppLogger.Module.CALL, "phase=$current detail=$detail callId=${session.callId}")
        listener.onVoicePhaseChanged(current, detail)
    }

    private suspend fun fail(reason: String) {
        Log.e(TAG, "fail reason=$reason snapshot=${sessionSnapshot()}")
        AppLogger.error(AppLogger.Module.CALL, "call failed: $reason")
        // Stop heartbeat immediately so no further ENETUNREACH errors fire from the error state.
        signalingManager.stopHeartbeat()
        stateMachine.transitionTo(VoicePhase.Error, reason)
        listener.onVoicePhaseChanged(VoicePhase.Error, reason)
        listener.onVoiceError(reason)
        listener.onVoicePushToTalk(false)
        cleanup("failure: $reason")
    }

    private suspend fun currentCallId(): String? = mutex.withLock { session.callId }

    private suspend fun currentRemoteAddress(): InetAddress? = mutex.withLock { session.remoteAddress }

    private suspend fun currentRemoteRtpPort(): Int = mutex.withLock { session.remoteRtpPort }
}