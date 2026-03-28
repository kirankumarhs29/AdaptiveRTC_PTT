package com.netsense.mesh

class NetSenseMesh(private val localNodeId: String) {
    init {
        System.loadLibrary("netsense_mesh")
        nativeInit(localNodeId)
    }

    fun shutdown() {
        nativeShutdown()
    }

    fun setCallback(callback: net.sense.mesh.MeshCallback) {
        nativeSetCallback(callback)
    }

    fun peerDiscovered(peerId: String, peerName: String, rssi: Int) {
        nativePeerDiscovered(peerId, peerName, rssi)
    }

    fun handshake(peerId: String) {
        nativeHandshake(peerId)
    }

    fun sendMessage(destination: String, payload: String, ttl: Int = 8): Boolean {
        return nativeSendMessage(destination, payload, ttl)
    }

    fun receiveMessage(source: String, destination: String, payload: String) {
        nativeReceiveMessage(source, destination, payload)
    }

    private external fun nativeInit(localNodeId: String)
    private external fun nativeShutdown()
    private external fun nativeSetCallback(callback: net.sense.mesh.MeshCallback)
    private external fun nativePeerDiscovered(peerId: String, peerName: String, rssi: Int)
    private external fun nativeHandshake(peerId: String)
    private external fun nativeSendMessage(destination: String, payload: String, ttl: Int): Boolean
    private external fun nativeReceiveMessage(source: String, destination: String, payload: String)
}
