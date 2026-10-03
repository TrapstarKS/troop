package com.noop.firmware

enum class FirmwarePhase {
    IDLE, CHECK, DOWNLOAD, VERIFY, TRANSFER, REBOOT, DONE, FAILED, CANCELLED;

    val isActive: Boolean
        get() = this in CHECK..REBOOT
}

enum class FirmwareFailure { PREREQUISITES, VERIFICATION, TIMEOUT, DISCONNECTED, MOCK_FAILURE }

sealed interface FirmwareEvent {
    data object Start : FirmwareEvent
    data class Pause(val runId: Int) : FirmwareEvent
    data class Resume(val runId: Int) : FirmwareEvent
    data class Cancel(val runId: Int) : FirmwareEvent
    data object Reset : FirmwareEvent
    data class Advance(val runId: Int, val phase: FirmwarePhase) : FirmwareEvent
    data class Progress(val runId: Int, val phase: FirmwarePhase, val percent: Int) : FirmwareEvent
    data class Fail(val runId: Int, val phase: FirmwarePhase, val reason: FirmwareFailure) : FirmwareEvent
}

/** In-memory mock only: no device identity, payload, file, network or Bluetooth dependency. */
data class FirmwareSimulation(
    val runId: Int = 0,
    val phase: FirmwarePhase = FirmwarePhase.IDLE,
    val progress: Int = 0,
    val paused: Boolean = false,
    val checkpoint: FirmwarePhase? = null,
    val failure: FirmwareFailure? = null,
) {
    fun applying(event: FirmwareEvent): FirmwareSimulation = when (event) {
        FirmwareEvent.Start -> if (phase.isActive) this else FirmwareSimulation(runId + 1, FirmwarePhase.CHECK)
        FirmwareEvent.Reset -> FirmwareSimulation(runId + 1)
        is FirmwareEvent.Pause -> if (event.runId == runId && phase.isActive) copy(paused = true) else this
        is FirmwareEvent.Resume -> when {
            event.runId != runId -> this
            phase.isActive && paused -> copy(paused = false)
            (phase == FirmwarePhase.FAILED || phase == FirmwarePhase.CANCELLED) && checkpoint != null ->
                copy(phase = checkpoint, checkpoint = null, failure = null, paused = false)
            else -> this
        }
        is FirmwareEvent.Cancel -> when {
            event.runId != runId -> this
            phase.isActive -> copy(phase = FirmwarePhase.CANCELLED, checkpoint = phase, failure = null, paused = false)
            phase == FirmwarePhase.FAILED -> copy(phase = FirmwarePhase.CANCELLED, failure = null, paused = false)
            else -> this
        }
        is FirmwareEvent.Advance -> if (accepts(event.runId, event.phase) && !paused) {
            val next = FirmwarePhase.entries[phase.ordinal + 1]
            copy(phase = next, progress = if (next == FirmwarePhase.DONE) 100 else 0)
        } else this
        is FirmwareEvent.Progress -> if (accepts(event.runId, event.phase) && !paused &&
            (phase == FirmwarePhase.DOWNLOAD || phase == FirmwarePhase.TRANSFER)
        ) copy(progress = maxOf(progress, event.percent.coerceIn(0, 100))) else this
        is FirmwareEvent.Fail -> if (accepts(event.runId, event.phase)) {
            copy(phase = FirmwarePhase.FAILED, checkpoint = phase, failure = event.reason, paused = false)
        } else this
    }

    private fun accepts(eventRunId: Int, eventPhase: FirmwarePhase): Boolean =
        phase.isActive && eventRunId == runId && eventPhase == phase
}
