package com.netsense.mesh

import java.nio.ByteBuffer

/**
 * Minimal RTP packet for PCM payload transport.
 * Header size is fixed at 12 bytes with no CSRC/extension.
 */
data class RtpPacket(
    val version: Int,
    val payloadType: Int,
    val marker: Boolean,
    val sequenceNumber: Int,
    val timestamp: Long,
    val ssrc: Long,
    val payload: ByteArray
) {
    fun toByteArray(): ByteArray {
        val buffer = ByteBuffer.allocate(12 + payload.size)
        val markerBit = if (marker) 1 else 0

        val b0 = ((version and 0x03) shl 6)
        val b1 = ((markerBit and 0x01) shl 7) or (payloadType and 0x7F)

        buffer.put(b0.toByte())
        buffer.put(b1.toByte())
        buffer.putShort((sequenceNumber and 0xFFFF).toShort())
        buffer.putInt((timestamp and 0xFFFFFFFFL).toInt())
        buffer.putInt((ssrc and 0xFFFFFFFFL).toInt())
        buffer.put(payload)
        return buffer.array()
    }

    companion object {
        const val HEADER_SIZE_BYTES = 12

        fun parse(packetData: ByteArray, packetLength: Int): RtpPacket? {
            if (packetLength < HEADER_SIZE_BYTES) return null
            val buffer = ByteBuffer.wrap(packetData, 0, packetLength)

            val b0 = buffer.get().toInt() and 0xFF
            val b1 = buffer.get().toInt() and 0xFF

            val version = (b0 ushr 6) and 0x03
            val csrcCount = b0 and 0x0F
            val extension = (b0 ushr 4) and 0x01
            if (version != 2 || csrcCount != 0 || extension != 0) {
                return null
            }

            val marker = ((b1 ushr 7) and 0x01) == 1
            val payloadType = b1 and 0x7F
            val sequenceNumber = buffer.short.toInt() and 0xFFFF
            val timestamp = buffer.int.toLong() and 0xFFFFFFFFL
            val ssrc = buffer.int.toLong() and 0xFFFFFFFFL

            val payloadSize = packetLength - HEADER_SIZE_BYTES
            val payload = ByteArray(payloadSize)
            buffer.get(payload)

            return RtpPacket(
                version = version,
                payloadType = payloadType,
                marker = marker,
                sequenceNumber = sequenceNumber,
                timestamp = timestamp,
                ssrc = ssrc,
                payload = payload
            )
        }
    }
}
