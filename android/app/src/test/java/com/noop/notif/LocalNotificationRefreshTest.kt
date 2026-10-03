package com.noop.notif

import com.noop.data.DailyMetric
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNotificationRefreshTest {
    @Test
    fun delayedOldRowCannotReplaceNewBriefingOrConsumeItsMarker() = runTest {
        val refresh = LocalNotificationRefresh()
        val delayed = CompletableDeferred<Unit>()
        val delivered = mutableListOf<String>()
        var briefing: String? = null
        val older = launch {
            refresh.run(read = { delayed.await(); "old-row" }, isCurrent = { true }) {
                briefing = it
                delivered += it
            }
        }
        runCurrent()
        refresh.run(read = { "new-row" }, isCurrent = { true }) {
            briefing = it
            delivered += it
        }
        delayed.complete(Unit)
        older.join()
        assertEquals("new-row", briefing)
        assertEquals(listOf("new-row"), delivered)
    }

    @Test
    fun switchingDeviceDuringReadUsesCapturedSourceAndDiscardsOldResult() = runTest {
        val refresh = LocalNotificationRefresh()
        val delayed = CompletableDeferred<Unit>()
        val reads = mutableListOf<String>()
        val delivered = mutableListOf<String>()
        var source = "strap-a"
        var briefing: String? = null
        suspend fun evaluate(wait: Boolean) {
            val capturedSource = source
            refresh.run(read = {
                reads += "sleep:$capturedSource"
                if (wait) delayed.await()
                reads += "history:$capturedSource"
                capturedSource
            }, isCurrent = { capturedSource == source }) {
                briefing = it
                delivered += it
            }
        }
        val older = launch { evaluate(wait = true) }
        runCurrent()
        source = "strap-b"
        refresh.invalidate()
        evaluate(wait = false)
        delayed.complete(Unit)
        older.join()
        assertEquals("strap-b", briefing)
        assertEquals(listOf("strap-b"), delivered)
        assertEquals(listOf("sleep:strap-a", "sleep:strap-b", "history:strap-b", "history:strap-a"), reads)
    }

    @Test
    fun changedInputsCannotPublishEvenWithoutAnotherRefresh() = runTest {
        val refresh = LocalNotificationRefresh()
        val delayed = CompletableDeferred<Unit>()
        var inputGeneration = 1
        val captured = inputGeneration
        var deliveries = 0
        val older = launch {
            refresh.run(read = { delayed.await() }, isCurrent = { inputGeneration == captured }) { deliveries++ }
        }
        runCurrent()
        inputGeneration++
        delayed.complete(Unit)
        older.join()
        assertEquals(0, deliveries)
    }

    @Test
    fun importedProvenanceRequiresEveryReportedFieldWakeSourceAndStreak() {
        val imported = DailyMetric("my-whoop", "2026-10-03", recovery = 75.0, totalSleepMin = 420.0, strain = 50.0)
        fun provenance(row: DailyMetric = imported, wake: List<String> = listOf("my-whoop"), streak: Int = 3) =
            hasImportedNotificationInputs(row, listOf(imported), wake, listOf("my-whoop"), streak, 3)
        assertTrue(provenance())
        assertFalse(provenance(imported.copy(recovery = 90.0)))
        assertFalse(provenance(imported.copy(totalSleepMin = 500.0)))
        assertFalse(provenance(imported.copy(strain = 80.0)))
        assertFalse(provenance(wake = listOf("my-whoop-noop")))
        assertFalse(provenance(streak = 4))
        assertFalse(hasImportedNotificationInputs(imported, listOf(imported.copy(recovery = null)),
            listOf("my-whoop"), listOf("my-whoop"), 3, 3))
    }
}
