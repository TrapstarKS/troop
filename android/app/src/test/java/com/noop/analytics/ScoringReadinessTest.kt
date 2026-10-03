package com.noop.analytics

import com.noop.data.WhoopDao
import com.noop.data.WhoopRepository
import com.noop.data.DailyMetric
import com.noop.notif.notificationComputedSources
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoringReadinessTest {
    @Test
    fun cachedComputedRowNeedsSuccessfulPassButExplicitImportDoesNot() = runTest {
        val readiness = ScoringReadiness()
        assertFalse(readiness.state.value.ready(false, "input", "today", listOf("my-whoop")))
        assertTrue(readiness.state.value.ready(true, "input", "today", listOf("my-whoop")))
        readiness.track(sourceId = "my-whoop", inputFingerprint = { "input" }, completedDays = { setOf("today") }) { }
        assertTrue(readiness.state.value.ready(false, "input", "today", listOf("my-whoop")))
        assertFalse(readiness.state.value.ready(false, "new-input", "today", listOf("my-whoop")))
        assertNotNull(readiness.state.value.successfulGeneration)
        assertFalse(readiness.state.value.ready(false, "input", "today", listOf("other-strap")))
    }

    @Test
    fun queuedStartupAndEditPassesNeverConsumeExistingRowsUntilSuccessfulRetry() = runTest {
        val readiness = ScoringReadiness()
        val gate = Mutex()
        val startup = CompletableDeferred<Unit>()
        val edit = CompletableDeferred<Unit>()
        var markers = 0
        fun evaluate() { if (readiness.state.value.ready(false, "input", "today", listOf("my-whoop"))) markers++ }
        readiness.track(sourceId = "my-whoop", inputFingerprint = { "input" }, completedDays = { setOf("today") }) { }
        val first = launch { readiness.track(sourceId = "my-whoop", inputFingerprint = { "input" }, completedDays = { setOf("today") }) { gate.withLock { startup.await() } } }
        runCurrent()
        val second = launch {
            runCatching {
                readiness.track(sourceId = "my-whoop", inputFingerprint = { "input" }, completedDays = { setOf("today") }) {
                    gate.withLock { edit.await(); error("edit failed") }
                }
            }
        }
        runCurrent()
        assertEquals(2, readiness.state.value.pending)
        evaluate()
        startup.complete(Unit)
        first.join()
        assertEquals(1, readiness.state.value.pending)
        evaluate()
        edit.complete(Unit)
        second.join()
        assertEquals(0, readiness.state.value.pending)
        assertNotNull(readiness.state.value.failedGeneration)
        evaluate()
        assertEquals(0, markers)
        assertFalse(readiness.state.value.ready(true, "input", "today", listOf("my-whoop")))
        readiness.track(sourceId = "my-whoop", inputFingerprint = { "input" }, completedDays = { setOf("today") }) { }
        evaluate()
        assertEquals(1, markers)
    }

    @Test
    fun emptyOrUnrelatedSuccessfulPassCannotReleaseCachedDailyRow() = runTest {
        val readiness = ScoringReadiness()
        readiness.track(sourceId = "my-whoop", inputFingerprint = { "input" }, completedDays = { emptySet() }) { }
        assertFalse(readiness.state.value.ready(false, "input", "today", listOf("my-whoop")))
        readiness.track(sourceId = "my-whoop", inputFingerprint = { "input" }, completedDays = { setOf("yesterday") }) { }
        assertFalse(readiness.state.value.ready(false, "input", "today", listOf("my-whoop")))
        assertTrue(readiness.state.value.ready(false, "input", "yesterday", listOf("my-whoop")))
    }

    @Test
    fun scoringTransitionInvalidatesPreviouslyReadSnapshot() = runTest {
        val readiness = ScoringReadiness()
        readiness.track(sourceId = "my-whoop", inputFingerprint = { "input" }, completedDays = { setOf("today") }) { }
        val captured = readiness.state.value
        runCatching { readiness.track(sourceId = "my-whoop", inputFingerprint = { "input" }, completedDays = { setOf("today") }) { error("failed") } }
        var deliveries = 0
        readiness.ifCurrent(captured) { deliveries++ }
        assertEquals(0, deliveries)
    }

    @Test
    fun canceledPassPropagatesAndLeavesFailedCompletionWitness() = runTest {
        val readiness = ScoringReadiness()
        val failure = runCatching {
            readiness.track(sourceId = "my-whoop", inputFingerprint = { "input" }, completedDays = { setOf("today") }) {
                throw CancellationException("stopped")
            }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(0, readiness.state.value.pending)
        assertFalse(readiness.state.value.ready(false, "input", "today", listOf("my-whoop")))
    }

    @Test
    fun actualEngineEntryExposesInFlightAndFailureWithoutBleFlags() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val dao = Proxy.newProxyInstance(WhoopDao::class.java.classLoader, arrayOf(WhoopDao::class.java)) {
            _, method, _ -> if (method.name == "analysisFingerprint") "input" else error("Unexpected DAO ${method.name}")
        } as WhoopDao
        val owner = object : IntelligenceEngine.DayOwnerSource {
            override suspend fun candidatePriorities(): List<Pair<String, Int>> {
                entered.complete(Unit)
                finish.await()
                error("startup scoring failed")
            }
            override suspend fun lockedOwner(day: String): String? = null
        }
        val before = IntelligenceEngine.scoringReadiness.state.value.generation
        val pass = async { runCatching { IntelligenceEngine.analyzeRecent(WhoopRepository(dao), ownerSource = owner) } }
        withTimeout(5_000) { entered.await() }
        val inFlight = IntelligenceEngine.scoringReadiness.state.value
        assertTrue(inFlight.generation > before)
        assertTrue(inFlight.pending > 0)
        assertFalse(inFlight.ready(false, "input", "today", listOf("my-whoop")))
        finish.complete(Unit)
        assertTrue(pass.await().isFailure)
        val failed = IntelligenceEngine.scoringReadiness.state.value
        assertEquals(0, failed.pending)
        assertNotNull(failed.failedGeneration)
        assertFalse(failed.ready(false, "input", "today", listOf("my-whoop")))
    }

    @Test
    fun canonicalSuccessCannotReleaseActiveComputedFieldsUnderEitherRowIdentity() = runTest {
        val readiness = ScoringReadiness()
        val day = "2026-10-03"
        val active = DailyMetric("strap-b-noop", day, recovery = 80.0, totalSleepMin = 420.0, strain = 40.0)
        val canonical = active.copy(deviceId = "my-whoop-noop", recovery = 65.0)
        val imported = DailyMetric("strap-b", day)
        val retainedImportId = WhoopRepository.mergeDaily(listOf(imported), listOf(active)).single()
        assertEquals("strap-b", retainedImportId.deviceId)
        readiness.track(sourceId = "my-whoop", inputFingerprint = { "input" }, completedDays = { setOf(day) }) { }
        for (row in listOf(active, retainedImportId)) {
            val required = notificationComputedSources(row, listOf(imported), listOf(active, canonical),
                listOf("strap-b-noop"), listOf("strap-b", "my-whoop"), listOf(row))!!
            assertEquals(setOf("strap-b"), required)
            assertFalse(readiness.state.value.ready(false, "input", day, required))
        }
        val canonicalSources = notificationComputedSources(canonical, emptyList(), listOf(canonical),
            listOf("my-whoop-noop"), listOf("strap-b", "my-whoop"), listOf(canonical))!!
        assertTrue(readiness.state.value.ready(false, "input", day, canonicalSources))
        val mixedSources = notificationComputedSources(active, emptyList(), listOf(active, canonical),
            listOf("my-whoop-noop"), listOf("strap-b", "my-whoop"), listOf(active))!!
        assertEquals(setOf("strap-b", "my-whoop"), mixedSources)
        assertFalse(readiness.state.value.ready(false, "input", day, mixedSources))
        readiness.track(sourceId = "strap-b", inputFingerprint = { "input" }, completedDays = { setOf(day) }) { }
        assertTrue(readiness.state.value.ready(false, "input", day, setOf("strap-b")))
        assertFalse(readiness.state.value.ready(false, "input", day, mixedSources))
    }

    @Test
    fun perFieldCoalescingRequiresBothActualComputedContributors() = runTest {
        val day = "2026-10-03"
        val active = DailyMetric("strap-b-noop", day, recovery = 80.0)
        val canonical = DailyMetric("my-whoop-noop", day, totalSleepMin = 420.0, strain = 40.0)
        val row = WhoopRepository.unionByDay(listOf(listOf(active), listOf(canonical))).single()
        assertEquals("strap-b-noop", row.deviceId)
        val required = notificationComputedSources(row, emptyList(), listOf(active, canonical),
            listOf("my-whoop-noop"), listOf("strap-b", "my-whoop"), listOf(row))!!
        assertEquals(setOf("strap-b", "my-whoop"), required)
        val readiness = ScoringReadiness()
        readiness.track(sourceId = "my-whoop", inputFingerprint = { "input" }, completedDays = { setOf(day) }) { }
        assertFalse(readiness.state.value.ready(false, "input", day, required))
    }
}
