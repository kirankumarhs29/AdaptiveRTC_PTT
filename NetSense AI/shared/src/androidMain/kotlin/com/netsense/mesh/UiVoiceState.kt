package com.netsense.mesh

import net.sense.mesh.VoicePhase

/**
 * Six user-facing states that map the complex 10-state internal [VoicePhase] machine
 * down to what a PTT user actually needs to see.
 *
 * Rule: UI never shows internal engineering states. All complexity is absorbed here.
 */
sealed class UiVoiceState(val label: String) {

    /** BLE discovery running; no Wi-Fi Direct group yet. */
    object Searching : UiVoiceState("Searching for peers…")

    /** Wi-Fi Direct group forming or call-setup handshake in progress. */
    object Connecting : UiVoiceState("Connecting…")

    /** Transport ready, no active call. User may press PTT. */
    object Ready : UiVoiceState("Ready to talk")

    /** Local PTT is active — microphone is open, we are transmitting. */
    object Speaking : UiVoiceState("You are speaking")

    /** Remote peer is transmitting — speaker is active, local PTT is blocked. */
    object Listening : UiVoiceState("Peer is speaking")

    /**
     * Transport was lost; automatic reconnect is in progress.
     * [reason] is logged but not shown verbatim in production UI.
     */
    data class Reconnecting(val reason: String = "") : UiVoiceState("Reconnecting…")

    companion object {
        /**
         * Maps the internal [VoicePhase] + optional [detail] hint to a [UiVoiceState].
         *
         * Speaking / Listening are driven by [onVoicePushToTalk] / [onVoiceRemoteTransmitting]
         * callbacks in [VoiceCallService], not by [VoicePhase.Streaming], because the same
         * Streaming state covers both local and remote transmission.
         */
        fun from(phase: VoicePhase, detail: String = ""): UiVoiceState = when (phase) {
            VoicePhase.Idle,
            VoicePhase.Discovering      -> Searching

            VoicePhase.Connecting,
            VoicePhase.AwaitingAccept,
            VoicePhase.Preparing        -> Connecting

            VoicePhase.Connected,
            VoicePhase.Ready            -> Ready

            // Streaming maps to Ready here; VoiceCallService overrides to Speaking/Listening
            // via the onVoicePushToTalk / onVoiceRemoteTransmitting callbacks.
            VoicePhase.Streaming        -> Ready

            VoicePhase.Ending           -> Connecting

            VoicePhase.Error            -> Reconnecting(detail)
        }
    }
}
