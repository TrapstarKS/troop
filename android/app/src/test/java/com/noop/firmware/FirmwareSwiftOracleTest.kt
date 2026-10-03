package com.noop.firmware

import org.junit.Assert.assertEquals
import org.junit.Test

class FirmwareSwiftOracleTest {
    @Test
    fun `state transitions and local version comparison match compiled Swift oracle`() {
        var state = FirmwareSimulation()
        val trace = mutableListOf<String>()
        fun record() {
            val failure = state.failure?.let { if (it == FirmwareFailure.MOCK_FAILURE) "mockFailure" else it.name.lowercase() } ?: "-"
            trace += "${state.runId}|${state.phase.name.lowercase()}|${state.progress}|${if (state.paused) 1 else 0}|${state.checkpoint?.name?.lowercase() ?: "-"}|$failure"
        }
        fun send(event: FirmwareEvent) { state = state.applying(event); record() }
        record()
        send(FirmwareEvent.Start)
        send(FirmwareEvent.Advance(1, FirmwarePhase.CHECK))
        send(FirmwareEvent.Progress(1, FirmwarePhase.DOWNLOAD, -1))
        send(FirmwareEvent.Progress(1, FirmwarePhase.DOWNLOAD, 55))
        send(FirmwareEvent.Progress(1, FirmwarePhase.DOWNLOAD, 20))
        send(FirmwareEvent.Pause(1))
        send(FirmwareEvent.Advance(1, FirmwarePhase.DOWNLOAD))
        send(FirmwareEvent.Progress(1, FirmwarePhase.DOWNLOAD, 100))
        send(FirmwareEvent.Resume(1))
        send(FirmwareEvent.Fail(1, FirmwarePhase.DOWNLOAD, FirmwareFailure.PREREQUISITES))
        send(FirmwareEvent.Resume(1))
        send(FirmwareEvent.Advance(1, FirmwarePhase.DOWNLOAD))
        send(FirmwareEvent.Fail(1, FirmwarePhase.VERIFY, FirmwareFailure.VERIFICATION))
        send(FirmwareEvent.Cancel(1))
        send(FirmwareEvent.Resume(1))
        send(FirmwareEvent.Advance(1, FirmwarePhase.VERIFY))
        send(FirmwareEvent.Progress(1, FirmwarePhase.TRANSFER, 120))
        send(FirmwareEvent.Fail(1, FirmwarePhase.TRANSFER, FirmwareFailure.DISCONNECTED))
        send(FirmwareEvent.Resume(1))
        send(FirmwareEvent.Cancel(1))
        send(FirmwareEvent.Resume(1))
        send(FirmwareEvent.Advance(1, FirmwarePhase.TRANSFER))
        send(FirmwareEvent.Fail(1, FirmwarePhase.REBOOT, FirmwareFailure.TIMEOUT))
        send(FirmwareEvent.Start)
        send(FirmwareEvent.Advance(1, FirmwarePhase.CHECK))
        send(FirmwareEvent.Fail(2, FirmwarePhase.REBOOT, FirmwareFailure.TIMEOUT))
        send(FirmwareEvent.Advance(2, FirmwarePhase.CHECK))
        send(FirmwareEvent.Fail(2, FirmwarePhase.DOWNLOAD, FirmwareFailure.MOCK_FAILURE))
        send(FirmwareEvent.Reset)
        send(FirmwareEvent.Start)
        send(FirmwareEvent.Advance(4, FirmwarePhase.CHECK))
        send(FirmwareEvent.Advance(4, FirmwarePhase.DOWNLOAD))
        send(FirmwareEvent.Advance(4, FirmwarePhase.VERIFY))
        send(FirmwareEvent.Advance(4, FirmwarePhase.TRANSFER))
        send(FirmwareEvent.Advance(4, FirmwarePhase.REBOOT))
        send(FirmwareEvent.Resume(4))
        send(FirmwareEvent.Cancel(4))
        send(FirmwareEvent.Start)
        trace += "COMPARISON"
        for (line in SWIFT_ORACLE.substringAfter("COMPARISON\n").lines()) {
            val (observed, reference, _) = line.split('|')
            val result = FirmwareVersionReference.compare(observed.takeUnless { it == "<nil>" }, reference)
            trace += "$observed|$reference|${result.name.lowercase()}"
        }
        assertEquals(SWIFT_ORACLE, trace.joinToString("\n"))
    }

    companion object {
        // Verbatim stdout from swiftc -O FirmwareSimulation.swift FirmwareReferenceComparison.swift main.swift.
        private val SWIFT_ORACLE = """
            0|idle|0|0|-|-
            1|check|0|0|-|-
            1|download|0|0|-|-
            1|download|0|0|-|-
            1|download|55|0|-|-
            1|download|55|0|-|-
            1|download|55|1|-|-
            1|download|55|1|-|-
            1|download|55|1|-|-
            1|download|55|0|-|-
            1|failed|55|0|download|prerequisites
            1|download|55|0|-|-
            1|verify|0|0|-|-
            1|failed|0|0|verify|verification
            1|cancelled|0|0|verify|-
            1|verify|0|0|-|-
            1|transfer|0|0|-|-
            1|transfer|100|0|-|-
            1|failed|100|0|transfer|disconnected
            1|transfer|100|0|-|-
            1|cancelled|100|0|transfer|-
            1|transfer|100|0|-|-
            1|reboot|0|0|-|-
            1|failed|0|0|reboot|timeout
            2|check|0|0|-|-
            2|check|0|0|-|-
            2|check|0|0|-|-
            2|download|0|0|-|-
            2|failed|0|0|download|mockFailure
            3|idle|0|0|-|-
            4|check|0|0|-|-
            4|download|0|0|-|-
            4|verify|0|0|-|-
            4|transfer|0|0|-|-
            4|reboot|0|0|-|-
            4|done|100|0|-|-
            4|done|100|0|-|-
            4|done|100|0|-|-
            5|check|0|0|-|-
            COMPARISON
            <nil>|41.3|unknown
            |41.3|unknown
            41.9.0.0|41.10.0.0|older
            50.40.1.0|50.39.9.9|newer
            41.1.2.0|41.1.2.0|equal
            41.01.0002.0|41.1.2.0|equal
            41.01.0002.0|41.1.3.0|older
            41.1.2.0|50.1.2.0|unknown
            41.1.2|41.1.2.0|unknown
            41|41.3|unknown
            41..2|41.3|unknown
            .41.2|41.3|unknown
            41.2.|41.3|unknown
             41.2|41.3|unknown
            41.2 |41.3|unknown
            41.+2|41.3|unknown
            41.-2|41.3|unknown
            41.２|41.3|unknown
            41.2b|41.3|unknown
            41.2 / 17.2|41.3|unknown
            41.9223372036854775808|41.3|unknown
            41.9223372036854775806|41.9223372036854775807|older
            41.3|41.2b|unknown
            41.3|41.9223372036854775808|unknown
        """.trimIndent()
    }
}
