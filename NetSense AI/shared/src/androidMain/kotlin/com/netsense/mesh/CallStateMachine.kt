package com.netsense.mesh

import android.util.Log
import com.netsense.mesh.AppLogger.Module
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.sense.mesh.VoicePhase

class CallStateMachine(initialState: VoicePhase = VoicePhase.Idle) {
    companion object {
        private const val TAG = "CallStateMachine"
    }

    private val mutex = Mutex()
    private var currentState: VoicePhase = initialState

    suspend fun state(): VoicePhase = mutex.withLock { currentState }

    suspend fun transitionTo(nextState: VoicePhase, reason: String): Boolean = mutex.withLock {
        if (currentState == nextState) {
            Log.d(TAG, "state unchanged=$currentState reason=$reason")
            AppLogger.debug(Module.CALL, "state unchanged=$currentState reason=$reason")
            return@withLock true
        }
        if (!isAllowed(currentState, nextState)) {
            Log.w(TAG, "rejected transition $currentState -> $nextState reason=$reason")
            AppLogger.warn(Module.CALL, "rejected transition $currentState -> $nextState reason=$reason")
            return@withLock false
        }

        Log.d(TAG, "transition $currentState -> $nextState reason=$reason")
        AppLogger.info(Module.CALL, "state $currentState -> $nextState reason=$reason")
        currentState = nextState
        true
    }

    private fun isAllowed(from: VoicePhase, to: VoicePhase): Boolean = when (from) {
        VoicePhase.Idle        -> to in setOf(VoicePhase.Discovering, VoicePhase.Connecting, VoicePhase.Ending, VoicePhase.Error)
        VoicePhase.Discovering -> to in setOf(VoicePhase.Idle, VoicePhase.Connecting, VoicePhase.Error)
        VoicePhase.Connecting  -> to in setOf(VoicePhase.Idle, VoicePhase.Connected, VoicePhase.AwaitingAccept, VoicePhase.Preparing, VoicePhase.Ending, VoicePhase.Error)
        // Connected = transport ready, no active call.  Can go to a call state or back to Idle.
        VoicePhase.Connected   -> to in setOf(VoicePhase.Idle, VoicePhase.Connecting, VoicePhase.AwaitingAccept, VoicePhase.Preparing, VoicePhase.Ending, VoicePhase.Error)
        VoicePhase.AwaitingAccept -> to in setOf(VoicePhase.Idle, VoicePhase.Connected, VoicePhase.Preparing, VoicePhase.Ending, VoicePhase.Error)
        VoicePhase.Preparing   -> to in setOf(VoicePhase.Idle, VoicePhase.Connected, VoicePhase.Ready, VoicePhase.Ending, VoicePhase.Error)
        VoicePhase.Ready       -> to in setOf(VoicePhase.Idle, VoicePhase.Connected, VoicePhase.Streaming, VoicePhase.Ending, VoicePhase.Error)
        VoicePhase.Streaming   -> to in setOf(VoicePhase.Idle, VoicePhase.Connected, VoicePhase.Ready, VoicePhase.Ending, VoicePhase.Error)
        // Ending → Connected allows returning to transport-ready state after call terminates.
        VoicePhase.Ending      -> to in setOf(VoicePhase.Idle, VoicePhase.Connected, VoicePhase.Error)
        VoicePhase.Error       -> to in setOf(VoicePhase.Idle, VoicePhase.Connected, VoicePhase.Connecting, VoicePhase.Ending)
    }
}