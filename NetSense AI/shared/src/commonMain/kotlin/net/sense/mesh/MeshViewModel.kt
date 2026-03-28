package net.sense.mesh

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MeshViewModel(private val meshService: MeshService) {
    private val viewModelJob = SupervisorJob()
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        // Log or handle as required
        println("MeshViewModel exception: ${throwable.message}")
    }
    private val scope = CoroutineScope(MainDispatcher + viewModelJob + exceptionHandler)

    private val _uiState = MutableStateFlow(MeshUiState(localNodeId = ""))
    val uiState: StateFlow<MeshUiState> = _uiState

    private val _events = MutableSharedFlow<MeshEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<MeshEvent> = _events.asSharedFlow()

    fun start(localNodeId: String) {
        scope.launch {
            meshService.initialize(localNodeId)
            meshService.startDiscovery()
        }
        scope.launch {
            meshService.observeState().collectLatest { state ->
                _uiState.value = state
            }
        }
        scope.launch {
            meshService.observeEvents().collectLatest { event ->
                _events.emit(event)
            }
        }
    }

    fun stop() {
        scope.launch { meshService.stopDiscovery() }
        viewModelJob.cancel()
    }

    fun startDiscovery() {
        scope.launch { meshService.startDiscovery() }
    }

    fun stopDiscovery() {
        scope.launch { meshService.stopDiscovery() }
    }

    fun connect(peerId: String) {
        scope.launch { meshService.connectPeer(peerId) }
    }

    fun disconnect(peerId: String) {
        scope.launch { meshService.disconnectPeer(peerId) }
    }

    fun sendMessage(peerId: String, payload: String) {
        scope.launch { meshService.sendMessage(peerId, payload) }
    }

    fun startPushToTalk() {
        scope.launch { meshService.startPushToTalk() }
    }

    fun stopPushToTalk() {
        scope.launch { meshService.stopPushToTalk() }
    }

    fun endVoiceCall() {
        scope.launch { meshService.endVoiceCall() }
    }

    fun startVoiceCall() {
        scope.launch { meshService.startVoiceCall() }
    }
}
