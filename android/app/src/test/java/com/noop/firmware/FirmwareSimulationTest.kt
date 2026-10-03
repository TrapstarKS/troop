package com.noop.firmware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirmwareSimulationTest {
    private val activePhases = FirmwarePhase.entries.filter { it.isActive }

    private fun at(phase: FirmwarePhase): FirmwareSimulation {
        var state = FirmwareSimulation().applying(FirmwareEvent.Start)
        while (state.phase != phase) state = state.applying(FirmwareEvent.Advance(state.runId, state.phase))
        return state
    }

    @Test
    fun `default is inactive and complete mock path never changes the run ID`() {
        var state = FirmwareSimulation()
        assertEquals(FirmwarePhase.IDLE, state.phase)
        assertFalse(state.phase.isActive)
        state = state.applying(FirmwareEvent.Start)
        for (phase in activePhases) {
            assertEquals(phase, state.phase)
            assertEquals(1, state.runId)
            state = state.applying(FirmwareEvent.Advance(state.runId, phase))
        }
        assertEquals(FirmwarePhase.DONE, state.phase)
        assertEquals(100, state.progress)
    }

    @Test
    fun `every phase can pause resume abort and restore its checkpoint`() {
        for (phase in activePhases) {
            val original = at(phase)
            val paused = original.applying(FirmwareEvent.Pause(original.runId))
            assertTrue(paused.paused)
            assertEquals(paused, paused.applying(FirmwareEvent.Advance(paused.runId, phase)))
            assertEquals(paused, paused.applying(FirmwareEvent.Progress(paused.runId, phase, 75)))
            assertEquals(original, paused.applying(FirmwareEvent.Resume(paused.runId)))
            for (state in listOf(original, paused)) {
                val cancelled = state.applying(FirmwareEvent.Cancel(state.runId))
                assertEquals(FirmwarePhase.CANCELLED, cancelled.phase)
                assertEquals(phase, cancelled.checkpoint)
                assertFalse(cancelled.paused)
                assertEquals(original, cancelled.applying(FirmwareEvent.Resume(cancelled.runId)))
            }
        }
    }

    @Test
    fun `every injected failure in every phase can resume or abort`() {
        for (phase in activePhases) {
            for (reason in FirmwareFailure.entries) {
                val original = at(phase)
                for (state in listOf(original, original.applying(FirmwareEvent.Pause(original.runId)))) {
                    val failed = state.applying(FirmwareEvent.Fail(state.runId, phase, reason))
                    assertEquals(FirmwarePhase.FAILED, failed.phase)
                    assertEquals(reason, failed.failure)
                    assertEquals(phase, failed.checkpoint)
                    assertFalse(failed.paused)
                    assertEquals(original, failed.applying(FirmwareEvent.Resume(failed.runId)))
                    val aborted = failed.applying(FirmwareEvent.Cancel(failed.runId))
                    assertEquals(FirmwarePhase.CANCELLED, aborted.phase)
                    assertNull(aborted.failure)
                    assertEquals(original, aborted.applying(FirmwareEvent.Resume(aborted.runId)))
                }
            }
        }
    }

    @Test
    fun `download and transfer progress is bounded monotonic and survives interruptions`() {
        for (phase in listOf(FirmwarePhase.DOWNLOAD, FirmwarePhase.TRANSFER)) {
            var state = at(phase)
            for ((input, expected) in listOf(-1 to 0, 25 to 25, 10 to 25, 250 to 100, 50 to 100)) {
                state = state.applying(FirmwareEvent.Progress(state.runId, phase, input))
                assertEquals(expected, state.progress)
            }
            val failed = state.applying(FirmwareEvent.Fail(state.runId, phase, FirmwareFailure.DISCONNECTED))
            assertEquals(state, failed.applying(FirmwareEvent.Resume(failed.runId)))
            val cancelled = state.applying(FirmwareEvent.Cancel(state.runId))
            assertEquals(state, cancelled.applying(FirmwareEvent.Resume(cancelled.runId)))
            val next = state.applying(FirmwareEvent.Advance(state.runId, phase))
            assertEquals(0, next.progress)
        }
    }

    @Test
    fun `progress cannot move non-transfer phases`() {
        for (phase in activePhases.filter { it != FirmwarePhase.DOWNLOAD && it != FirmwarePhase.TRANSFER }) {
            val state = at(phase)
            assertEquals(state, state.applying(FirmwareEvent.Progress(state.runId, phase, 100)))
        }
    }

    @Test
    fun `stale and out of order events cannot move any active phase`() {
        for (phase in activePhases) {
            val state = at(phase)
            for (event in listOf(
                FirmwareEvent.Advance(state.runId - 1, phase),
                FirmwareEvent.Progress(state.runId - 1, phase, 50),
                FirmwareEvent.Fail(state.runId - 1, phase, FirmwareFailure.TIMEOUT),
                FirmwareEvent.Pause(state.runId - 1),
                FirmwareEvent.Resume(state.runId - 1),
                FirmwareEvent.Cancel(state.runId - 1),
                FirmwareEvent.Advance(state.runId, FirmwarePhase.IDLE),
                FirmwareEvent.Progress(state.runId, FirmwarePhase.IDLE, 50),
                FirmwareEvent.Fail(state.runId, FirmwarePhase.IDLE, FirmwareFailure.TIMEOUT),
                FirmwareEvent.Start,
            )) assertEquals(state, state.applying(event))
        }
        val old = at(FirmwarePhase.TRANSFER)
        val replacement = old.applying(FirmwareEvent.Reset).applying(FirmwareEvent.Start)
        assertEquals(replacement, replacement.applying(FirmwareEvent.Fail(old.runId, FirmwarePhase.CHECK, FirmwareFailure.TIMEOUT)))
    }

    @Test
    fun `terminal states never advance accidentally and fresh runs clear all checkpoints`() {
        val active = at(FirmwarePhase.TRANSFER).applying(FirmwareEvent.Progress(1, FirmwarePhase.TRANSFER, 75))
        val terminalStates = listOf(
            FirmwareSimulation(),
            at(FirmwarePhase.DONE),
            active.applying(FirmwareEvent.Fail(1, FirmwarePhase.TRANSFER, FirmwareFailure.MOCK_FAILURE)),
            active.applying(FirmwareEvent.Cancel(active.runId)),
        )
        for (state in terminalStates) {
            for (event in listOf(
                FirmwareEvent.Advance(state.runId, state.phase),
                FirmwareEvent.Progress(state.runId, state.phase, 50),
                FirmwareEvent.Fail(state.runId, state.phase, FirmwareFailure.MOCK_FAILURE),
                FirmwareEvent.Pause(state.runId),
            )) assertEquals(state, state.applying(event))
            assertEquals(FirmwareSimulation(state.runId + 1, FirmwarePhase.CHECK), state.applying(FirmwareEvent.Start))
            assertEquals(FirmwareSimulation(state.runId + 1), state.applying(FirmwareEvent.Reset))
        }
        val done = at(FirmwarePhase.DONE)
        assertEquals(done, done.applying(FirmwareEvent.Resume(done.runId)))
        assertEquals(done, done.applying(FirmwareEvent.Cancel(done.runId)))
        assertEquals(FirmwareSimulation(), FirmwareSimulation().applying(FirmwareEvent.Resume(0)))
    }

    @Test
    fun `reset invalidates events and drops local state in every phase`() {
        for (phase in activePhases) {
            val state = at(phase)
            val reset = state.applying(FirmwareEvent.Reset)
            assertEquals(FirmwareSimulation(state.runId + 1), reset)
            assertEquals(reset, reset.applying(FirmwareEvent.Advance(state.runId, phase)))
        }
    }
}
