package com.netsense.mesh

import kotlinx.coroutines.test.runTest
import net.sense.mesh.VoicePhase
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for CallStateMachine.
 *
 * Tests cover:
 *  - Initial state
 *  - Valid transitions (happy path to Streaming)
 *  - Invalid transitions (rejected by isAllowed guard)
 *  - Idempotent same-state transition
 *  - Error recovery path
 *  - Full call setup lifecycle (Idle → Streaming)
 *
 * Uses kotlinx-coroutines-test for suspend function invocation.
 * android.util.Log calls are suppressed via testOptions.unitTests.returnDefaultValues = true.
 */
class CallStateMachineTest {

    private lateinit var sm: CallStateMachine

    @Before
    fun setUp() {
        sm = CallStateMachine()
    }

    // ── Test 1: Initial state is Idle ─────────────────────────────────────────

    @Test
    fun `initial state is Idle`() = runTest {
        assertEquals(VoicePhase.Idle, sm.state())
    }

    // ── Test 2: Idle → Discovering (valid) ───────────────────────────────────

    @Test
    fun `Idle to Discovering is allowed`() = runTest {
        val ok = sm.transitionTo(VoicePhase.Discovering, "test")
        assertTrue(ok)
        assertEquals(VoicePhase.Discovering, sm.state())
    }

    // ── Test 3: Idle → Connecting (valid) ────────────────────────────────────

    @Test
    fun `Idle to Connecting is allowed`() = runTest {
        assertTrue(sm.transitionTo(VoicePhase.Connecting, "test"))
        assertEquals(VoicePhase.Connecting, sm.state())
    }

    // ── Test 4: Idle → Streaming (invalid — must be rejected) ───────────────

    @Test
    fun `Idle to Streaming is rejected`() = runTest {
        val ok = sm.transitionTo(VoicePhase.Streaming, "test")
        assertFalse(ok)
        assertEquals(VoicePhase.Idle, sm.state())   // state unchanged
    }

    // ── Test 5: Idle → Ready (invalid) ───────────────────────────────────────

    @Test
    fun `Idle to Ready is rejected`() = runTest {
        assertFalse(sm.transitionTo(VoicePhase.Ready, "test"))
    }

    // ── Test 6: Same-state transition returns true (idempotent) ──────────────

    @Test
    fun `same-state transition returns true without state change`() = runTest {
        // Start in Idle, transition to Idle again
        val ok = sm.transitionTo(VoicePhase.Idle, "noop")
        assertTrue(ok)
        assertEquals(VoicePhase.Idle, sm.state())
    }

    // ── Test 7: Discovering → Idle (valid) ───────────────────────────────────

    @Test
    fun `Discovering to Idle is allowed`() = runTest {
        sm.transitionTo(VoicePhase.Discovering, "discover")
        assertTrue(sm.transitionTo(VoicePhase.Idle, "cancel"))
        assertEquals(VoicePhase.Idle, sm.state())
    }

    // ── Test 8: Connecting → Connected (valid) ────────────────────────────────

    @Test
    fun `Connecting to Connected is allowed`() = runTest {
        sm.transitionTo(VoicePhase.Connecting, "dial")
        assertTrue(sm.transitionTo(VoicePhase.Connected, "link-up"))
        assertEquals(VoicePhase.Connected, sm.state())
    }

    // ── Test 9: Connecting → Discovering (invalid) ───────────────────────────

    @Test
    fun `Connecting to Discovering is rejected`() = runTest {
        sm.transitionTo(VoicePhase.Connecting, "dial")
        assertFalse(sm.transitionTo(VoicePhase.Discovering, "bad"))
        assertEquals(VoicePhase.Connecting, sm.state())
    }

    // ── Test 10: Full happy-path to Streaming ────────────────────────────────

    @Test
    fun `full call setup path Idle to Streaming succeeds`() = runTest {
        assertTrue(sm.transitionTo(VoicePhase.Connecting,  "connect"))
        assertTrue(sm.transitionTo(VoicePhase.Connected,   "link"))
        assertTrue(sm.transitionTo(VoicePhase.Preparing,   "negotiate"))
        assertTrue(sm.transitionTo(VoicePhase.Ready,       "ready"))
        assertTrue(sm.transitionTo(VoicePhase.Streaming,   "ptt-start"))

        assertEquals(VoicePhase.Streaming, sm.state())
    }

    // ── Test 11: Ending → Idle (valid call teardown) ──────────────────────────

    @Test
    fun `Ending to Idle is allowed`() = runTest {
        sm.transitionTo(VoicePhase.Connecting, "connect")
        sm.transitionTo(VoicePhase.Ending,     "hangup")
        assertTrue(sm.transitionTo(VoicePhase.Idle, "cleanup"))
        assertEquals(VoicePhase.Idle, sm.state())
    }

    // ── Test 12: Error → Idle (recovery) ─────────────────────────────────────

    @Test
    fun `Error to Idle recovery is allowed`() = runTest {
        sm.transitionTo(VoicePhase.Connecting, "connect")
        sm.transitionTo(VoicePhase.Error,      "crash")
        assertTrue(sm.transitionTo(VoicePhase.Idle, "recover"))
        assertEquals(VoicePhase.Idle, sm.state())
    }

    // ── Test 13: Error → Connecting (retry) ──────────────────────────────────

    @Test
    fun `Error to Connecting retry is allowed`() = runTest {
        sm.transitionTo(VoicePhase.Connecting, "connect")
        sm.transitionTo(VoicePhase.Error,      "failure")
        assertTrue(sm.transitionTo(VoicePhase.Connecting, "retry"))
    }

    // ── Test 14: Streaming → Ending (PTT stop) ───────────────────────────────

    @Test
    fun `Streaming to Ending is allowed`() = runTest {
        sm.transitionTo(VoicePhase.Connecting, "c")
        sm.transitionTo(VoicePhase.Connected,  "l")
        sm.transitionTo(VoicePhase.Preparing,  "p")
        sm.transitionTo(VoicePhase.Ready,      "r")
        sm.transitionTo(VoicePhase.Streaming,  "s")

        assertTrue(sm.transitionTo(VoicePhase.Ending, "ptt-stop"))
        assertEquals(VoicePhase.Ending, sm.state())
    }

    // ── Test 15: Idle → Error (transition to error always allowed) ────────────

    @Test
    fun `Idle to Error is allowed`() = runTest {
        assertTrue(sm.transitionTo(VoicePhase.Error, "critical"))
        assertEquals(VoicePhase.Error, sm.state())
    }

    // ── Test 16: Custom initial state ─────────────────────────────────────────

    @Test
    fun `custom initial state is respected`() = runTest {
        val custom = CallStateMachine(VoicePhase.Connected)
        assertEquals(VoicePhase.Connected, custom.state())
    }

    // ── Test 17: AwaitingAccept → Preparing ──────────────────────────────────

    @Test
    fun `AwaitingAccept to Preparing is allowed`() = runTest {
        sm.transitionTo(VoicePhase.Connecting,     "dial")
        sm.transitionTo(VoicePhase.AwaitingAccept, "offer-sent")
        assertTrue(sm.transitionTo(VoicePhase.Preparing, "accepted"))
    }

    // ── Test 18: Preparing → Ready ───────────────────────────────────────────

    @Test
    fun `Preparing to Ready is allowed`() = runTest {
        sm.transitionTo(VoicePhase.Connecting, "c")
        sm.transitionTo(VoicePhase.Preparing,  "p")
        assertTrue(sm.transitionTo(VoicePhase.Ready, "jitter-warm"))
    }
}
