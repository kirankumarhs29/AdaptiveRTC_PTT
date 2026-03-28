package com.example.mesh

class MeshManager(private val localNodeId: String) {

    init {
        System.loadLibrary("mesh_engine")
        nativeInit(localNodeId)
    }

    fun startDiscovery() {
        nativeStart()
    }

    fun stopDiscovery() {
        nativeStop()
    }

    fun addPeer(peerId: String) {
        nativeAddPeer(peerId)
    }

    fun sendMessage(destination: String, payload: String, ttl: Int = 8): Boolean {
        return nativeSendMessage(destination, payload, ttl)
    }

    private external fun nativeInit(localNodeId: String)
    private external fun nativeStart()
    private external fun nativeStop()
    private external fun nativeAddPeer(peerId: String)
    private external fun nativeSendMessage(destination: String, payload: String, ttl: Int): Boolean

    fun onMeshMessage(source: String, destination: String, payload: String) {
        // callback entrypoint from native code
        // should be called from mesh_engine via JNI
        println("Mesh message from=$source dest=$destination payload=$payload")
    }
}
