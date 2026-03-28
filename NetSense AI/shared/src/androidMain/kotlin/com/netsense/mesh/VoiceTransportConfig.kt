package com.netsense.mesh

object VoiceTransportConfig {
    const val RTP_PORT = 5004
    const val CONTROL_PORT = 5005
    const val SAMPLE_RATE_HZ = 16000
    const val FRAME_MS = 20
    const val PAYLOAD_TYPE_PCM16 = 11
    const val HEARTBEAT_INTERVAL_MS = 2_000L

    // Software gain helps low-level MIC capture on some devices.
    const val TX_SOFTWARE_GAIN = 3.0f

    val frameSamples: Int = SAMPLE_RATE_HZ * FRAME_MS / 1000
    val frameBytes: Int = frameSamples * 2
}