package com.netsense.mesh

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for VoiceTransportConfig constants.
 *
 * These tests act as contract guards: if a constant changes and breaks the
 * protocol (e.g. wrong port, wrong frame size), these tests fail immediately.
 */
class VoiceTransportConfigTest {

    @Test
    fun `RTP_PORT is 5004`() {
        assertEquals(5004, VoiceTransportConfig.RTP_PORT)
    }

    @Test
    fun `CONTROL_PORT is 5005`() {
        assertEquals(5005, VoiceTransportConfig.CONTROL_PORT)
    }

    @Test
    fun `SAMPLE_RATE_HZ is 16000`() {
        assertEquals(16000, VoiceTransportConfig.SAMPLE_RATE_HZ)
    }

    @Test
    fun `FRAME_MS is 20`() {
        assertEquals(20, VoiceTransportConfig.FRAME_MS)
    }

    @Test
    fun `PAYLOAD_TYPE_PCM16 is 11`() {
        assertEquals(11, VoiceTransportConfig.PAYLOAD_TYPE_PCM16)
    }

    @Test
    fun `frameSamples is SAMPLE_RATE_HZ times FRAME_MS divided by 1000`() {
        val expected = VoiceTransportConfig.SAMPLE_RATE_HZ * VoiceTransportConfig.FRAME_MS / 1000
        assertEquals(expected, VoiceTransportConfig.frameSamples)
    }

    @Test
    fun `frameSamples equals 320`() {
        assertEquals(320, VoiceTransportConfig.frameSamples)
    }

    @Test
    fun `frameBytes equals 2 times frameSamples`() {
        assertEquals(VoiceTransportConfig.frameSamples * 2, VoiceTransportConfig.frameBytes)
    }

    @Test
    fun `frameBytes equals 640`() {
        assertEquals(640, VoiceTransportConfig.frameBytes)
    }

    @Test
    fun `TX_SOFTWARE_GAIN is 3f`() {
        assertEquals(3.0f, VoiceTransportConfig.TX_SOFTWARE_GAIN, 0.001f)
    }

    @Test
    fun `HEARTBEAT_INTERVAL_MS is 2000`() {
        assertEquals(2_000L, VoiceTransportConfig.HEARTBEAT_INTERVAL_MS)
    }

    @Test
    fun `ports do not overlap`() {
        assertNotEquals(VoiceTransportConfig.RTP_PORT, VoiceTransportConfig.CONTROL_PORT)
    }

    @Test
    fun `frameBytes is even (16-bit samples)`() {
        assertEquals(0, VoiceTransportConfig.frameBytes % 2)
    }
}
