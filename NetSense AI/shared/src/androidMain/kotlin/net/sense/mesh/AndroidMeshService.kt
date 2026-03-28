package net.sense.mesh

import android.content.Context
import android.util.Log
import com.netsense.mesh.AppLogger
import com.netsense.mesh.AppLogger.Module
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update

class AndroidMeshService(
    context: Context,
    private val meshManager: com.netsense.mesh.MeshManager,
    private val voiceManager: com.netsense.mesh.WifiDirectVoiceManager
) : MeshService, MeshCallback, com.netsense.mesh.VoiceTransportListener {
    companion object {
        private const val TAG = "AndroidMeshService"
    }

    private val _state = MutableStateFlow(MeshUiState(localNodeId = ""))
    private val _events = MutableSharedFlow<MeshEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val callbackScope = CoroutineScope(Dispatchers.Main.immediate)
    private val localMessageStore = LocalMessageStore(context)
    private var activePeerId: String? = null
    private var pendingVoiceAutoStartPeerId: String? = null
    /**
     * Set to true when the user invokes [startVoiceCall] while the transport is not yet
     * [VoicePhase.Connected].  When the transport reaches Connected, [onVoicePhaseChanged]
     * fires [voiceManager.startCall] automatically and clears this flag.
     */
    private var pendingCallAfterConnect: Boolean = false

    private fun appendChat(peerId: String, text: String, direction: ChatDirection) {
        val item = ChatLogEntry(
            peerId = peerId,
            text = text,
            direction = direction,
            timestampEpochMs = System.currentTimeMillis()
        )
        localMessageStore.append(item)
        _state.update { current ->
            val existing = current.chatHistory[peerId].orEmpty()
            current.copy(chatHistory = current.chatHistory + (peerId to (existing + item)))
        }
    }

    private fun isVoiceTransportConnected(phase: VoicePhase): Boolean = when (phase) {
        VoicePhase.Connected,
        VoicePhase.AwaitingAccept,
        VoicePhase.Preparing,
        VoicePhase.Ready,
        VoicePhase.Streaming,
        VoicePhase.Ending -> true
        else -> false
    }

    private suspend fun publishVoiceState(
        phase: VoicePhase,
        detail: String,
        pushToTalkActive: Boolean? = null,
        remoteEndpoint: String? = _state.value.voice.remoteEndpoint,
        txPackets: Long = _state.value.voice.txPackets,
        rxPackets: Long = _state.value.voice.rxPackets,
        wifiSignalBars: Int = _state.value.voice.wifiSignalBars
    ) {
        Log.d(
            TAG,
            "publishVoiceState phase=$phase detail=$detail ptt=${pushToTalkActive ?: _state.value.voice.pushToTalkActive} endpoint=$remoteEndpoint tx=$txPackets rx=$rxPackets"
        )
        _state.update { current ->
            current.copy(
                voice = current.voice.copy(
                    phase = phase,
                    connected = isVoiceTransportConnected(phase),
                    pushToTalkActive = pushToTalkActive ?: current.voice.pushToTalkActive,
                    remoteEndpoint = remoteEndpoint,
                    txPackets = txPackets,
                    rxPackets = rxPackets,
                    detail = detail,
                    wifiSignalBars = wifiSignalBars
                )
            )
        }

        val snapshot = _state.value.voice
        _events.emit(
            MeshEvent.VoiceStatus(
                peerId = activePeerId ?: "voice",
                phase = snapshot.phase,
                connected = snapshot.connected,
                pushToTalkActive = snapshot.pushToTalkActive,
                remoteTransmitting = snapshot.remoteTransmitting,
                remoteEndpoint = snapshot.remoteEndpoint,
                txPackets = snapshot.txPackets,
                rxPackets = snapshot.rxPackets,
                detail = snapshot.detail
            )
        )
    }

    init {
        meshManager.setCallback(this)
        voiceManager.listener = this
    }

    override suspend fun initialize(localNodeId: String) {
        val groupedHistory = localMessageStore.load().groupBy { it.peerId }
        _state.update { current ->
            current.copy(
                localNodeId = localNodeId,
                connectionState = ConnectionState.Disconnected,
                chatHistory = groupedHistory
            )
        }
    }

    override suspend fun startDiscovery() {
        Log.d(TAG, "startDiscovery")
        AppLogger.info(Module.UI, "startDiscovery")
        meshManager.start()
        _state.update { current -> current.copy(connectionState = ConnectionState.Discovering) }
        publishVoiceState(
            VoicePhase.Idle,
            "scan for peers via BLE",
            pushToTalkActive = false,
            remoteEndpoint = null,
            txPackets = 0,
            rxPackets = 0
        )
    }

    override suspend fun stopDiscovery() {
        Log.d(TAG, "stopDiscovery")
        AppLogger.info(Module.UI, "stopDiscovery")
        meshManager.stop()
        voiceManager.stop()
        _state.update { current -> current.copy(connectionState = ConnectionState.Disconnected) }
        publishVoiceState(VoicePhase.Idle, "stopped", pushToTalkActive = false, remoteEndpoint = null, txPackets = 0, rxPackets = 0)
    }

    override suspend fun connectPeer(peerId: String) {
        activePeerId = peerId
        pendingVoiceAutoStartPeerId = peerId
        _state.update { current ->
            val peers = current.peers.map { peer ->
                if (peer.id == peerId) peer.copy(state = ConnectionState.Connecting) else peer
            }
            current.copy(connectionState = ConnectionState.Connecting, peers = peers)
        }
        _events.emit(MeshEvent.ConnectionUpdated(peerId, ConnectionState.Connecting))
        // BLE handshake first; voice/Wi-Fi Direct will start from onSessionSecurityStateChanged
        // once the secure session is Established. Starting Wi-Fi Direct before BLE is ready
        // just wastes the 30s connect window.
        publishVoiceState(VoicePhase.Connecting, "establishing secure link…", pushToTalkActive = false, remoteEndpoint = null, txPackets = 0, rxPackets = 0)
        meshManager.handshake(peerId)
    }

    override suspend fun disconnectPeer(peerId: String) {
        // Disconnect BLE only — voice/Wi-Fi Direct path is independent and stays alive.
        activePeerId = null
        if (pendingVoiceAutoStartPeerId == peerId) {
            pendingVoiceAutoStartPeerId = null
        }
        meshManager.disconnectPeer(peerId)
        _state.update { current ->
            val peers = current.peers.map { peer -> peer.copy(state = ConnectionState.Disconnected) }
            current.copy(
                connectionState = ConnectionState.Disconnected,
                peers = peers
            )
        }
        _events.emit(MeshEvent.ConnectionUpdated(peerId, ConnectionState.Disconnected))
    }

    override suspend fun sendMessage(destinationId: String, payload: String) {
        val sent = meshManager.sendMessage(destinationId, payload)
        if (sent) {
            appendChat(destinationId, payload, ChatDirection.Outgoing)
            _events.emit(MeshEvent.MessageSent(destinationId, payload))
        } else {
            _events.emit(MeshEvent.Error("Failed to send message to $destinationId"))
        }
    }

    override fun observeState(): StateFlow<MeshUiState> = _state
    override fun observeEvents(): Flow<MeshEvent> = _events.asSharedFlow()

    override suspend fun startPushToTalk() {
        voiceManager.startPushToTalk()
    }

    override suspend fun stopPushToTalk() {
        voiceManager.stopPushToTalk()
    }

    override suspend fun endVoiceCall() {
        // Tear down Wi-Fi Direct voice only — BLE link is not touched.
        voiceManager.disconnect()
    }

    override suspend fun startVoiceCall() {
        val currentPhase = _state.value.voice.phase
        if (currentPhase == VoicePhase.Connected) {
            // Transport already alive — place the call directly without re-connecting.
            Log.d(TAG, "startVoiceCall: transport Connected, calling startCall()")
            AppLogger.info(Module.CALL, "voice start-call direct phase=Connected")
            voiceManager.startCall()
        } else {
            // Transport not ready — connect first.  Once Connected, onVoicePhaseChanged
            // will fire startCall() automatically via pendingCallAfterConnect.
            val peerId = activePeerId ?: return
            Log.d(TAG, "startVoiceCall: transport not Connected ($currentPhase), connecting peer=$peerId")
            AppLogger.info(Module.CALL, "voice start-call via connect phase=$currentPhase peerId=$peerId")
            pendingCallAfterConnect = true
            publishVoiceState(VoicePhase.Connecting, "connecting voice transport", pushToTalkActive = false, remoteEndpoint = null, txPackets = 0, rxPackets = 0)
            voiceManager.connect(peerId, originateCall = true)
        }
    }

    override fun onPeerDiscovered(peerId: String, peerName: String, rssi: Int) {
        callbackScope.launch {
            Log.d(TAG, "onPeerDiscovered id=$peerId name=$peerName rssi=$rssi")
            AppLogger.info(Module.BLE, "peer-discovered id=$peerId name=$peerName rssi=$rssi")
            val node = Node(peerId, peerName, rssi, ConnectionState.Disconnected)
            _state.update { current ->
                val existing = current.peers.filterNot { it.id == peerId }
                current.copy(peers = existing + node)
            }
            _events.emit(MeshEvent.PeerFound(node))
        }
    }

    override fun onVoicePeerDiscovered(peerId: String, peerName: String) {
        Log.d(TAG, "onVoicePeerDiscovered peerId=$peerId peerName=$peerName")
    }

    override fun onVoicePhaseChanged(phase: VoicePhase, detail: String) {
        Log.d(TAG, "onVoicePhaseChanged phase=$phase detail=$detail")
        AppLogger.info(Module.CALL, "voice-phase-changed phase=$phase detail=$detail")
        callbackScope.launch {
            publishVoiceState(phase, detail)
            // If startVoiceCall() was called while the transport was not yet ready, the
            // pendingCallAfterConnect flag was set.  Fire startCall() now that the
            // transport has reached the Connected state.
            if (phase == VoicePhase.Connected && pendingCallAfterConnect) {
                pendingCallAfterConnect = false
                Log.d(TAG, "onVoicePhaseChanged: auto-calling after transport connected")
                AppLogger.info(Module.CALL, "voice auto-call after transport connected")
                voiceManager.startCall()
            }
        }
    }

    override fun onVoicePathReady(remoteIp: String, rtpPort: Int) {
        Log.d(TAG, "onVoicePathReady remote=$remoteIp:$rtpPort")
        AppLogger.info(Module.NETWORK, "voice-path-ready remote=$remoteIp:$rtpPort")
        callbackScope.launch {
            val endpoint = "$remoteIp:$rtpPort"
            val phase = _state.value.voice.phase.let { current ->
                when (current) {
                    VoicePhase.Streaming -> VoicePhase.Streaming
                    VoicePhase.Error -> VoicePhase.Error
                    VoicePhase.Ending -> VoicePhase.Ending
                    else -> VoicePhase.Ready
                }
            }
            publishVoiceState(phase, "RTP path ready", remoteEndpoint = endpoint)
        }
    }

    override fun onVoicePushToTalk(active: Boolean) {
        Log.d(TAG, "onVoicePushToTalk active=$active phase=${_state.value.voice.phase}")
        AppLogger.info(Module.CALL, "ptt active=$active phase=${_state.value.voice.phase}")
        callbackScope.launch {
            val currentPhase = _state.value.voice.phase
            val phase = if (active) {
                VoicePhase.Streaming
            } else {
                when (currentPhase) {
                    VoicePhase.Streaming -> VoicePhase.Ready
                    else -> currentPhase
                }
            }
            publishVoiceState(phase, if (active) "Push-to-talk started" else "Push-to-talk stopped", pushToTalkActive = active)
        }
    }

    override fun onVoiceRemoteTransmitting(active: Boolean) {
        Log.d(TAG, "onVoiceRemoteTransmitting active=$active")
        callbackScope.launch {
            _state.update { current ->
                current.copy(voice = current.voice.copy(remoteTransmitting = active))
            }
            val snapshot = _state.value.voice
            _events.emit(
                MeshEvent.VoiceStatus(
                    peerId = activePeerId ?: "voice",
                    phase = snapshot.phase,
                    connected = snapshot.connected,
                    pushToTalkActive = snapshot.pushToTalkActive,
                    remoteTransmitting = active,
                    remoteEndpoint = snapshot.remoteEndpoint,
                    txPackets = snapshot.txPackets,
                    rxPackets = snapshot.rxPackets,
                    detail = snapshot.detail
                )
            )
        }
    }

    override fun onVoicePacketStats(txPackets: Long, rxPackets: Long) {
        callbackScope.launch {
            publishVoiceState(_state.value.voice.phase, _state.value.voice.detail, txPackets = txPackets, rxPackets = rxPackets)
        }
    }

    override fun onPeerRssiUpdated(peerId: String, rssi: Int) {
        Log.d(TAG, "onPeerRssiUpdated peerId=$peerId rssi=$rssi")
        _state.update { current ->
            current.copy(
                peers = current.peers.map { peer ->
                    if (peer.id == peerId) peer.copy(rssi = rssi) else peer
                }
            )
        }
    }

    override fun onVoiceError(reason: String) {
        Log.e(TAG, "onVoiceError reason=$reason phase=${_state.value.voice.phase} endpoint=${_state.value.voice.remoteEndpoint}")
        AppLogger.error(Module.CALL, "voice-error reason=$reason phase=${_state.value.voice.phase}")
        callbackScope.launch {
            publishVoiceState(VoicePhase.Error, reason, pushToTalkActive = false)
            _events.emit(MeshEvent.Error("Voice: $reason"))
        }
    }

    override fun onMessageReceived(source: String, destination: String, payload: String) {
        callbackScope.launch {
            Log.d(TAG, "onMessageReceived source=$source destination=$destination payload=$payload")
            appendChat(source, payload, ChatDirection.Incoming)
            _events.emit(MeshEvent.MessageReceived(source, payload))
        }
    }

    override fun onConnectionStateChanged(state: ConnectionState) {
        callbackScope.launch {
            Log.d(TAG, "onConnectionStateChanged state=$state")
            AppLogger.info(Module.BLE, "ble-connection-state state=$state peerId=${activePeerId}")
            _state.update { current ->
                val targetId = activePeerId
                val updatedPeers = if (targetId == null) current.peers else current.peers.map { peer ->
                    if (peer.id == targetId) peer.copy(state = state) else peer
                }
                current.copy(connectionState = state, peers = updatedPeers)
            }
            val peerId = activePeerId ?: "mesh"
            _events.emit(MeshEvent.ConnectionUpdated(peerId, state))
        }
    }

    override fun onMessageDeliveryUpdated(peerId: String, messageId: String, status: DeliveryStatus, attempt: Int) {
        callbackScope.launch {
            Log.d(TAG, "onMessageDeliveryUpdated peerId=$peerId messageId=$messageId status=$status attempt=$attempt")
            _events.emit(MeshEvent.DeliveryUpdated(peerId, messageId, status, attempt))
        }
    }

    override fun onSessionSecurityStateChanged(peerId: String, state: SessionSecurityState) {
        callbackScope.launch {
            Log.d(TAG, "onSessionSecurityStateChanged peerId=$peerId state=$state")
            AppLogger.info(Module.BLE, "session-security peerId=$peerId state=$state")
            _events.emit(MeshEvent.SessionSecurityUpdated(peerId, state))

            if (state == SessionSecurityState.Established) {
                // BLE secure session is symmetric, but voice call setup should not be.
                // Only the side that explicitly initiated the BLE link should originate
                // the Wi-Fi Direct call. The peer waits for the incoming CALL_REQUEST.
                if (activePeerId == null) {
                    activePeerId = peerId
                }

                // Mark this peer as Connected in the peer list (receiver side may not have done this yet).
                _state.update { current ->
                    val updatedPeers = current.peers.map { peer ->
                        if (peer.id == peerId) peer.copy(state = ConnectionState.Connected) else peer
                    }
                    current.copy(connectionState = ConnectionState.Connected, peers = updatedPeers)
                }

                val shouldAutoStartVoice = pendingVoiceAutoStartPeerId == peerId
                if (shouldAutoStartVoice) {
                    pendingVoiceAutoStartPeerId = null
                    val voicePhase = _state.value.voice.phase
                    if (voicePhase != VoicePhase.AwaitingAccept &&
                        voicePhase != VoicePhase.Preparing &&
                        voicePhase != VoicePhase.Ready &&
                        voicePhase != VoicePhase.Streaming
                    ) {
                        Log.d(TAG, "onSessionSecurityStateChanged: initiating voice connect for peerId=$peerId voicePhase=$voicePhase")
                        AppLogger.info(Module.CALL, "voice auto-start initiator peerId=$peerId voicePhase=$voicePhase")
                        publishVoiceState(VoicePhase.Connecting, "connecting voice transport", pushToTalkActive = false, remoteEndpoint = null, txPackets = 0, rxPackets = 0)
                        voiceManager.connect(peerId, originateCall = true)
                    }
                } else {
                    AppLogger.info(Module.CALL, "voice transport-prepare callee peerId=$peerId")
                    val voicePhase = _state.value.voice.phase
                    if (voicePhase == VoicePhase.Idle ||
                        voicePhase == VoicePhase.Connecting ||
                        voicePhase == VoicePhase.Error
                    ) {
                        publishVoiceState(VoicePhase.Connecting, "preparing inbound voice transport", pushToTalkActive = false, remoteEndpoint = null, txPackets = 0, rxPackets = 0)
                        // originateCall=true so both sides try to maintain the group on loss;
                        // auto-call is NOT triggered — the initiator will send CALL_REQUEST.
                        voiceManager.connect(peerId, originateCall = true)
                    }
                }
            }
        }
    }

    override fun onPeerHealthUpdated(health: PeerHealth) {
        callbackScope.launch {
            Log.d(
                TAG,
                "onPeerHealthUpdated peerId=${health.peerId} state=${health.state} reconnectAttempts=${health.reconnectAttempts} rtt=${health.roundTripMs}"
            )
            AppLogger.debug(Module.BLE, "peer-health peerId=${health.peerId} state=${health.state} rtt=${health.roundTripMs}ms reconnects=${health.reconnectAttempts}")
            _events.emit(MeshEvent.PeerHealthUpdated(health))
            // Derive Wi-Fi signal bar count from BLE RTT as a proxy for overall link quality.
            // WifiP2pManager does not expose per-peer RSSI, so RTT is the best available signal.
            val bars = when (val rtt = health.roundTripMs) {
                null -> 0
                in 0..80 -> 4
                in 81..160 -> 3
                in 161..300 -> 2
                else -> 1
            }
            if (health.peerId == activePeerId && bars != _state.value.voice.wifiSignalBars) {
                publishVoiceState(
                    _state.value.voice.phase,
                    _state.value.voice.detail,
                    wifiSignalBars = bars
                )
            }
        }
    }
}

