package net.sense.mesh

interface MeshCallback {
    fun onPeerDiscovered(peerId: String, peerName: String, rssi: Int)
    fun onPeerRssiUpdated(peerId: String, rssi: Int)
    fun onMessageReceived(source: String, destination: String, payload: String)
    fun onConnectionStateChanged(state: ConnectionState)
    fun onMessageDeliveryUpdated(peerId: String, messageId: String, status: DeliveryStatus, attempt: Int)
    fun onSessionSecurityStateChanged(peerId: String, state: SessionSecurityState)
    fun onPeerHealthUpdated(health: PeerHealth)
}
