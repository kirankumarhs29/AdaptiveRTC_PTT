package com.netsense.mesh

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Unit tests for RtpPacket serialization and parsing.
 *
 * Tests cover:
 *  - Header constants
 *  - Valid round-trip: toByteArray() → parse()
 *  - Boundary/rejection cases: short packet, wrong version, CSRC, extension
 *  - Payload preservation
 *  - Marker bit encoding
 */
class RtpPacketTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildValidPacket(
        version: Int       = 2,
        payloadType: Int   = 11,
        marker: Boolean    = false,
        seq: Int           = 1,
        ts: Long           = 160L,
        ssrc: Long         = 0xDEADBEEFL,
        payload: ByteArray = ByteArray(10) { it.toByte() }
    ): RtpPacket = RtpPacket(version, payloadType, marker, seq, ts, ssrc, payload)

    // ── Test 1: HEADER_SIZE_BYTES constant ───────────────────────────────────

    @Test
    fun `HEADER_SIZE_BYTES is 12`() {
        assertEquals(12, RtpPacket.HEADER_SIZE_BYTES)
    }

    // ── Test 2: toByteArray total length = 12 + payload ──────────────────────

    @Test
    fun `toByteArray length equals 12 plus payload`() {
        val payload = ByteArray(160)
        val pkt = buildValidPacket(payload = payload)
        val bytes = pkt.toByteArray()
        assertEquals(12 + 160, bytes.size)
    }

    // ── Test 3: Round-trip — parse(toByteArray()) reproduces original ─────────

    @Test
    fun `round-trip serialization preserves all fields`() {
        val payload = ByteArray(20) { (it + 1).toByte() }
        val original = buildValidPacket(
            seq     = 42,
            ts      = 3200L,
            ssrc    = 12345678L,
            payload = payload
        )
        val bytes  = original.toByteArray()
        val parsed = RtpPacket.parse(bytes, bytes.size)

        assertNotNull(parsed)
        assertEquals(2,           parsed!!.version)
        assertEquals(11,          parsed.payloadType)
        assertEquals(false,       parsed.marker)
        assertEquals(42,          parsed.sequenceNumber)
        assertEquals(3200L,       parsed.timestamp)
        assertEquals(12345678L,   parsed.ssrc)
        assertArrayEquals(payload, parsed.payload)
    }

    // ── Test 4: parse() rejects packet shorter than 12 bytes ─────────────────

    @Test
    fun `parse rejects packet shorter than header`() {
        val tooShort = ByteArray(11)
        assertNull(RtpPacket.parse(tooShort, tooShort.size))
    }

    // ── Test 5: parse() rejects version != 2 ─────────────────────────────────

    @Test
    fun `parse rejects version field not equal to 2`() {
        val pkt = buildValidPacket(version = 1)
        val bytes = pkt.toByteArray()
        assertNull(RtpPacket.parse(bytes, bytes.size))
    }

    // ── Test 6: parse() rejects packet with CSRC count != 0 ──────────────────

    @Test
    fun `parse rejects non-zero CSRC count`() {
        val pkt   = buildValidPacket()
        val bytes = pkt.toByteArray().copyOf()

        // Byte 0: [V V P X CC CC CC CC]
        // Set CC bits (low 4 bits of byte 0) to 1
        bytes[0] = (bytes[0].toInt() or 0x01).toByte()
        assertNull(RtpPacket.parse(bytes, bytes.size))
    }

    // ── Test 7: parse() rejects extension bit set ─────────────────────────────

    @Test
    fun `parse rejects extension bit set`() {
        val pkt   = buildValidPacket()
        val bytes = pkt.toByteArray().copyOf()

        // Extension bit is bit 4 of byte 0 (0x10)
        bytes[0] = (bytes[0].toInt() or 0x10).toByte()
        assertNull(RtpPacket.parse(bytes, bytes.size))
    }

    // ── Test 8: Marker bit true is preserved across round-trip ──────────────

    @Test
    fun `marker bit true survives round-trip`() {
        val pkt   = buildValidPacket(marker = true)
        val bytes = pkt.toByteArray()
        val parsed = RtpPacket.parse(bytes, bytes.size)

        assertNotNull(parsed)
        assertTrue(parsed!!.marker)
    }

    // ── Test 9: Marker bit false is preserved ────────────────────────────────

    @Test
    fun `marker bit false survives round-trip`() {
        val pkt    = buildValidPacket(marker = false)
        val bytes  = pkt.toByteArray()
        val parsed = RtpPacket.parse(bytes, bytes.size)

        assertNotNull(parsed)
        assertFalse(parsed!!.marker)
    }

    // ── Test 10: Empty payload is preserved ──────────────────────────────────

    @Test
    fun `empty payload round-trip`() {
        val pkt    = buildValidPacket(payload = ByteArray(0))
        val bytes  = pkt.toByteArray()
        val parsed = RtpPacket.parse(bytes, bytes.size)

        assertNotNull(parsed)
        assertEquals(0, parsed!!.payload.size)
    }

    // ── Test 11: Large sequence number (max uint16 = 65535) ──────────────────

    @Test
    fun `max sequence number 65535 survives round-trip`() {
        val pkt    = buildValidPacket(seq = 65535)
        val bytes  = pkt.toByteArray()
        val parsed = RtpPacket.parse(bytes, bytes.size)

        assertNotNull(parsed)
        assertEquals(65535, parsed!!.sequenceNumber)
    }

    // ── Test 12: Sequence number 0 survives round-trip ───────────────────────

    @Test
    fun `sequence number 0 survives round-trip`() {
        val pkt    = buildValidPacket(seq = 0)
        val bytes  = pkt.toByteArray()
        val parsed = RtpPacket.parse(bytes, bytes.size)

        assertNotNull(parsed)
        assertEquals(0, parsed!!.sequenceNumber)
    }

    // ── Test 13: packetLength parameter shorter than array length ────────────

    @Test
    fun `parse uses packetLength not array length`() {
        val pkt   = buildValidPacket(payload = ByteArray(20))
        val bytes = pkt.toByteArray()
        // Claim the packet is only 11 bytes long (< header)
        assertNull(RtpPacket.parse(bytes, 11))
    }

    // ── Test 14: Payload type 11 (PCM16) preserved ──────────────────────────

    @Test
    fun `payload type 11 preserved`() {
        // Use PT=11 (PAYLOAD_TYPE_PCM16)
        val pkt11  = buildValidPacket(payloadType = 11)
        val bytes  = pkt11.toByteArray()
        val parsed = RtpPacket.parse(bytes, bytes.size)

        assertNotNull(parsed)
        assertEquals(11, parsed!!.payloadType)
    }

    // ── Test 15: Timestamp masking — only lower 32 bits stored ───────────────

    @Test
    fun `timestamp masked to 32 bits on serialization`() {
        // timestamp = 0x1_00000001L : lower 32 bits = 1
        val pkt    = buildValidPacket(ts = 0x1_00000001L)
        val bytes  = pkt.toByteArray()
        val parsed = RtpPacket.parse(bytes, bytes.size)

        assertNotNull(parsed)
        assertEquals(1L, parsed!!.timestamp)   // upper bit stripped
    }
}

@Suppress("UNUSED_PARAMETER")
private fun void(x: Any?) {} // helper to suppress unused warning
