package com.netsense.mesh

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for JitterBridge fail-open behavior and stats API.
 *
 * On the JVM unit-test host the adaptive_rtc_jni.so native library is not
 * available. JitterBridge's fail-open design means all public methods must
 * return safe defaults and never crash.
 *
 * Also validates:
 *  - Stats data class construction and field access
 *  - Constants used for native initialization
 */
class JitterBridgeTest {

    @Before
    fun setUp() {
        // Ensure JitterBridge is shut down before each test so state is clean
        JitterBridge.shutdown()
    }

    // ── init() ────────────────────────────────────────────────────────────────

    @Test
    fun `init does not throw when native unavailable`() {
        val result = JitterBridge.init()
        // On JVM: JNI unavailable → returns false (graceful degradation)
        // On device: may return true — either is acceptable
        assertTrue(result || !result)   // assert no exception
    }

    // ── push() fail-open ──────────────────────────────────────────────────────

    @Test
    fun `push returns false when native unavailable`() {
        if (!isNativeAvailable()) {
            val payload = ByteArray(VoiceTransportConfig.frameBytes)
            val ok = JitterBridge.push(seq = 0, timestampRtp = 0L, payload = payload)
            assertFalse(ok)
        }
    }

    // ── pull() fail-open ──────────────────────────────────────────────────────

    @Test
    fun `pull returns null when native unavailable`() {
        if (!isNativeAvailable()) {
            assertNull(JitterBridge.pull())
        }
    }

    // ── depthMs() fail-open ───────────────────────────────────────────────────

    @Test
    fun `depthMs returns 0 when native unavailable`() {
        if (!isNativeAvailable()) {
            assertEquals(0, JitterBridge.depthMs())
        }
    }

    // ── jitterMs() fail-open ──────────────────────────────────────────────────

    @Test
    fun `jitterMs returns 0f when native unavailable`() {
        if (!isNativeAvailable()) {
            assertEquals(0.0f, JitterBridge.jitterMs(), 0.001f)
        }
    }

    // ── lossRatePct() fail-open ───────────────────────────────────────────────

    @Test
    fun `lossRatePct returns 0f when native unavailable`() {
        if (!isNativeAvailable()) {
            assertEquals(0.0f, JitterBridge.lossRatePct(), 0.001f)
        }
    }

    // ── underrunCount() fail-open ─────────────────────────────────────────────

    @Test
    fun `underrunCount returns 0L when native unavailable`() {
        if (!isNativeAvailable()) {
            assertEquals(0L, JitterBridge.underrunCount())
        }
    }

    // ── getStats() snapshot ───────────────────────────────────────────────────

    @Test
    fun `getStats returns consistent snapshot with individual accessors`() {
        val s = JitterBridge.getStats()
        assertEquals(JitterBridge.depthMs(),       s.depthMs)
        assertEquals(JitterBridge.jitterMs(),      s.jitterMs,    0.001f)
        assertEquals(JitterBridge.lossRatePct(),   s.lossRatePct, 0.001f)
        assertEquals(JitterBridge.underrunCount(), s.underrunCount)
    }

    // ── Stats data class ──────────────────────────────────────────────────────

    @Test
    fun `Stats data class fields are accessible`() {
        val stats = JitterBridge.Stats(
            depthMs      = 60,
            jitterMs     = 5.0f,
            lossRatePct  = 3.5f,
            underrunCount = 2L
        )
        assertEquals(60,    stats.depthMs)
        assertEquals(5.0f,  stats.jitterMs,    0.001f)
        assertEquals(3.5f,  stats.lossRatePct, 0.001f)
        assertEquals(2L,    stats.underrunCount)
    }

    @Test
    fun `Stats data class equality works`() {
        val s1 = JitterBridge.Stats(60, 5.0f, 0.0f, 0L)
        val s2 = JitterBridge.Stats(60, 5.0f, 0.0f, 0L)
        assertEquals(s1, s2)
    }

    @Test
    fun `Stats data class copy works`() {
        val original = JitterBridge.Stats(40, 2.0f, 1.5f, 3L)
        val copy     = original.copy(depthMs = 80)
        assertEquals(80,    copy.depthMs)
        assertEquals(2.0f,  copy.jitterMs,    0.001f)
        assertEquals(1.5f,  copy.lossRatePct, 0.001f)
        assertEquals(3L,    copy.underrunCount)
    }

    // ── push/pull do not crash even under repeated calls ──────────────────────

    @Test
    fun `repeated push calls do not throw`() {
        val payload = ByteArray(VoiceTransportConfig.frameBytes)
        for (i in 0 until 10) {
            JitterBridge.push(seq = i, timestampRtp = (i * 320).toLong(), payload = payload)
        }
    }

    @Test
    fun `repeated pull calls do not throw`() {
        for (i in 0 until 10) {
            JitterBridge.pull()
        }
    }

    // ── reset() / shutdown() do not throw ─────────────────────────────────────

    @Test
    fun `reset does not throw`() {
        JitterBridge.reset()
    }

    @Test
    fun `shutdown does not throw`() {
        JitterBridge.shutdown()
    }

    @Test
    fun `shutdown then init is safe`() {
        JitterBridge.shutdown()
        JitterBridge.init()   // should not throw
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /**
     * Checks whether the native JNI library was successfully loaded.
     * On the JVM unit-test host this is always false.
     */
    private fun isNativeAvailable(): Boolean {
        return try {
            val field = JitterBridge::class.java.getDeclaredField("nativeAvailable")
            field.isAccessible = true
            field.getBoolean(JitterBridge)
        } catch (_: Exception) {
            false
        }
    }
}
