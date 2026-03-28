package net.sense.mesh

enum class ConnectionState {
    Disconnected,
    Discovering,
    Connecting,
    Connected,
    Error
}

data class Node(
    val id: String,
    val name: String,
    val rssi: Int,
    val state: ConnectionState
)

data class MeshMessage(
    val source: String,
    val destination: String,
    val payload: String,
    val sequence: Long
)

enum class DeliveryStatus {
    Queued,
    Sent,
    Delivered,
    RetryScheduled,
    TimedOut,
    Failed
}

enum class SessionSecurityState {
    NotEstablished,
    Handshaking,
    Established,
    Failed
}

enum class PeerHealthState {
    Healthy,
    Degraded,
    Recovering,
    Unreachable
}

enum class ChatDirection {
    Incoming,
    Outgoing,
    System
}

data class ChatLogEntry(
    val peerId: String,
    val text: String,
    val direction: ChatDirection,
    val timestampEpochMs: Long
)

enum class VoicePhase {
    Idle,
    Discovering,
    Connecting,
    /** Wi-Fi Direct group formed + TRANSPORT_READY handshake complete. No active call yet. */
    Connected,
    AwaitingAccept,
    Preparing,
    Ready,
    Streaming,
    Ending,
    Error
}

data class PeerHealth(
    val peerId: String,
    val state: PeerHealthState,
    val reconnectAttempts: Int,
    val lastSeenEpochMs: Long,
    val roundTripMs: Long?
)

sealed class MeshEvent {
    data class PeerFound(val node: Node) : MeshEvent()
    data class MessageReceived(val from: String, val payload: String) : MeshEvent()
    data class MessageSent(val to: String, val payload: String) : MeshEvent()
    data class DeliveryUpdated(
        val peerId: String,
        val messageId: String,
        val status: DeliveryStatus,
        val attempt: Int
    ) : MeshEvent()
    data class SessionSecurityUpdated(
        val peerId: String,
        val state: SessionSecurityState
    ) : MeshEvent()
    data class PeerHealthUpdated(val health: PeerHealth) : MeshEvent()
    data class ConnectionUpdated(val peerId: String, val state: ConnectionState) : MeshEvent()
    data class VoiceStatus(
        val peerId: String,
        val phase: VoicePhase,
        val connected: Boolean,
        val pushToTalkActive: Boolean,
        val remoteTransmitting: Boolean,
        val remoteEndpoint: String?,
        val txPackets: Long,
        val rxPackets: Long,
        val detail: String
    ) : MeshEvent()
    data class Error(val reason: String) : MeshEvent()
}

data class VoiceUiState(
    val phase: VoicePhase = VoicePhase.Idle,
    val connected: Boolean = false,
    val pushToTalkActive: Boolean = false,
    val remoteTransmitting: Boolean = false,
    val remoteEndpoint: String? = null,
    val txPackets: Long = 0,
    val rxPackets: Long = 0,
    val detail: String = "idle",
    // 0-4 bars derived from BLE round-trip time (proxy for Wi-Fi Direct link quality,
    // since WifiP2pManager does not expose per-peer RSSI in its public API).
    val wifiSignalBars: Int = 0
)

data class MeshUiState(
    val localNodeId: String,
    val peers: List<Node> = emptyList(),
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val voice: VoiceUiState = VoiceUiState(),
    val chatHistory: Map<String, List<ChatLogEntry>> = emptyMap(),
    val events: List<MeshEvent> = emptyList()
)
