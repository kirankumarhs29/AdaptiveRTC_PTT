package net.sense.mesh

import kotlinx.coroutines.flow.Flow

interface MeshService {
    suspend fun initialize(localNodeId: String)
    suspend fun startDiscovery()
    suspend fun stopDiscovery()
    suspend fun connectPeer(peerId: String)
    suspend fun disconnectPeer(peerId: String)
    suspend fun sendMessage(destinationId: String, payload: String)
    suspend fun startPushToTalk()
    suspend fun stopPushToTalk()
    suspend fun endVoiceCall()
    suspend fun startVoiceCall()

    fun observeState(): Flow<MeshUiState>
    fun observeEvents(): Flow<MeshEvent>
}
