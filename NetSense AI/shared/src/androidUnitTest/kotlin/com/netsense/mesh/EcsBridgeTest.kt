package com.netsense.mesh

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for EcsBridge fail-open behavior.
 *
 * When the native library (adaptive_rtc_jni.so) is not loaded on the JVM
 * unit-test host, EcsBridge.available == false. All public methods must
 * return safe defaults (fail-open) so the call path never crashes.
 *
 * These tests validate:
 *  - Status/signal constant values (match C++ enum ordinals)
 *  - Fail-open return values when JNI unavailable
 *  - Input validation guards (negative RTT ignored)
 */
class EcsBridgeTest {

    // ── Constant value contracts ──────────────────────────────────────────────

    @Test
    fun `STATUS_NO_CONGESTION equals 0`() {
        assertEquals(0, EcsBridge.STATUS_NO_CONGESTION)
    }

    @Test
    fun `STATUS_BUILDING equals 1`() {
        assertEquals(1, EcsBridge.STATUS_BUILDING)
    }

    @Test
    fun `STATUS_IMMINENT equals 2`() {
        assertEquals(2, EcsBridge.STATUS_IMMINENT)
    }

    @Test
    fun `SIGNAL_NONE equals 0`() {
        assertEquals(0, EcsBridge.SIGNAL_NONE)
    }

    @Test
    fun `SIGNAL_BUILDING equals 1`() {
        assertEquals(1, EcsBridge.SIGNAL_BUILDING)
    }

    @Test
    fun `SIGNAL_IMMINENT equals 2`() {
        assertEquals(2, EcsBridge.SIGNAL_IMMINENT)
    }

    @Test
    fun `STATUS constants are distinct`() {
        assertNotEquals(EcsBridge.STATUS_NO_CONGESTION, EcsBridge.STATUS_BUILDING)
        assertNotEquals(EcsBridge.STATUS_BUILDING,      EcsBridge.STATUS_IMMINENT)
        assertNotEquals(EcsBridge.STATUS_NO_CONGESTION, EcsBridge.STATUS_IMMINENT)
    }

    // ── Fail-open behavior when JNI is unavailable ────────────────────────────

    @Test
    fun `analyzeCongestion returns STATUS_NO_CONGESTION when native unavailable`() {
        // On JVM host, JNI native library is not loaded → fail-open
        if (!EcsBridge.available) {
            assertEquals(EcsBridge.STATUS_NO_CONGESTION, EcsBridge.analyzeCongestion())
        }
    }

    @Test
    fun `canSendPacket returns true when native unavailable`() {
        if (!EcsBridge.available) {
            assertTrue(EcsBridge.canSendPacket(1000L))
        }
    }

    @Test
    fun `getConfidence returns 0 dot 0 when native unavailable`() {
        if (!EcsBridge.available) {
            assertEquals(0.0, EcsBridge.getConfidence(), 0.0001)
        }
    }

    @Test
    fun `getCurrentRateBps returns non-negative value when native unavailable`() {
        if (!EcsBridge.available) {
            assertTrue(EcsBridge.getCurrentRateBps() >= 0)
        }
    }

    // ── Input validation — should not crash ───────────────────────────────────

    @Test
    fun `addRttSample with zero does not throw`() {
        EcsBridge.addRttSample(0L)   // guarded: rttUs <= 0 → no-op
    }

    @Test
    fun `addRttSample with negative value does not throw`() {
        EcsBridge.addRttSample(-1L)
    }

    @Test
    fun `addRttSample with valid value does not throw`() {
        EcsBridge.addRttSample(45_000L)   // 45 ms
    }

    @Test
    fun `onCongestionSignal NONE does not throw`() {
        EcsBridge.onCongestionSignal(EcsBridge.SIGNAL_NONE)
    }

    @Test
    fun `onCongestionSignal BUILDING does not throw`() {
        EcsBridge.onCongestionSignal(EcsBridge.SIGNAL_BUILDING)
    }

    @Test
    fun `onCongestionSignal IMMINENT does not throw`() {
        EcsBridge.onCongestionSignal(EcsBridge.SIGNAL_IMMINENT)
    }

    @Test
    fun `onRecovery does not throw`() {
        EcsBridge.onRecovery()
    }

    // ── Sequential call sequence — simulates a real call ─────────────────────

    @Test
    fun `sequential RTT feed and congestion analysis does not throw`() {
        // Simulate 20 RTT samples at increasing delay
        for (i in 0 until 20) {
            EcsBridge.addRttSample(40_000L + i * 2_000L)
        }
        val status = EcsBridge.analyzeCongestion()
        // Whatever the result, it must be a valid status code
        assertTrue(status in listOf(
            EcsBridge.STATUS_NO_CONGESTION,
            EcsBridge.STATUS_BUILDING,
            EcsBridge.STATUS_IMMINENT
        ))
    }

    @Test
    fun `reset does not throw`() {
        EcsBridge.reset()
    }
}
