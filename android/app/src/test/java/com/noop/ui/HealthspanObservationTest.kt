package com.noop.ui

import androidx.sqlite.db.SupportSQLiteQuery
import com.noop.analytics.HealthspanHistory
import com.noop.analytics.HealthspanPresentation
import com.noop.data.DailyMetric
import com.noop.data.SleepSession
import com.noop.data.WhoopDao
import com.noop.data.WhoopRepository
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HealthspanObservationTest {
    @Test fun midnightCommitRebindsActualBoundedRepositoryWithoutRemountAndKeepsHistoricalReference() = runTest {
        val clock = MutableClock(Instant.parse("2026-10-02T23:59:55Z"))
        val previousDay = LocalDate.now(clock)
        val fixture = RepositoryFixture((1..20).map {
            DailyMetric("strap-a", previousDay.minusDays(it.toLong()).toString(), recovery = 80.0)
        } + DailyMetric("strap-a", previousDay.minusDays(119).toString(), recovery = 80.0))
        val published = mutableListOf<HealthspanDatedRows>()
        val historicalIso = previousDay.minusDays(7).toString()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            healthspanDatedRows(healthspanCivilDays(fixture.repo.healthspanChangesFlow()) { LocalDate.now(clock) }) { from, to ->
                fixture.repo.daysMergedRangeFlow("strap-a", from, to)
            }.collect { published += it }
        }
        runCurrent()
        fun snapshot(rows: HealthspanDatedRows): HealthspanPresentation.Snapshot {
            val offsets = rows.rows.map { ChronoUnit.DAYS.between(LocalDate.parse(it.day), rows.today).toInt() }
            return HealthspanPresentation.snapshot(listOf(HealthspanPresentation.AgeSample(
                ChronoUnit.DAYS.between(previousDay, rows.today).toInt(), 31.0)), offsets, 35.0)
        }
        val before = published.last { it.loaded }
        assertEquals(previousDay, before.today)
        assertEquals(20, snapshot(before).eligibility.recoveryDays)
        assertEquals(HealthspanHistory.State.recentCoverage, snapshot(before).eligibility.state)
        clock.now = Instant.parse("2026-10-03T00:00:05Z")
        val today = LocalDate.now(clock)
        fixture.rows.value += DailyMetric("strap-a", today.toString(), recovery = 80.0)
        fixture.commits.emit(1)
        runCurrent()
        val after = published.last { it.loaded }
        assertEquals(today, after.today)
        assertTrue(fixture.bounds.any { it.second == today.toString() })
        assertEquals(21, snapshot(after).eligibility.recoveryDays)
        assertEquals(HealthspanHistory.State.ready, snapshot(after).eligibility.state)
        assertEquals(31.0, snapshot(after).age!!, 0.0)
        assertEquals(today, after.reference(null))
        assertEquals(LocalDate.parse(historicalIso), after.reference(historicalIso))
    }

    @Test fun foregroundRecollectionRechecksClockWithoutAnyCommittedChange() = runTest {
        val clock = MutableClock(Instant.parse("2026-10-02T20:00:00Z"))
        val fixture = RepositoryFixture(emptyList())
        val flow = healthspanCivilDays(fixture.repo.healthspanChangesFlow()) { LocalDate.now(clock) }
        assertEquals(LocalDate.of(2026, 10, 2), flow.first())
        clock.now = Instant.parse("2026-10-04T09:00:00Z")
        assertEquals(LocalDate.of(2026, 10, 4), flow.first())
    }

    @Test fun repeatedSameCountInvalidationsReloadEmptyReplacementAndRemovalAsOneSnapshot() = runTest {
        val fixture = RepositoryFixture(emptyList())
        var supplied = emptyList<HealthspanHistory.Sample>()
        val published = mutableListOf<HealthspanContributorRead>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            healthspanContributorReads(fixture.repo.healthspanChangesFlow(), { true }) { supplied }.collect { published += it }
        }
        runCurrent()
        assertNotNull(published.last().samples)
        assertTrue(published.last().samples!!.isEmpty())
        for (value in listOf(72.5, 76.5)) {
            supplied = listOf(HealthspanHistory.Sample(0, value))
            fixture.commits.emit(1)
            runCurrent()
            val samples = published.last().samples!!
            val comparison = HealthspanHistory.comparison(samples, false)
            val points = HealthspanHistory.points(samples, 180)
            val reading = HealthspanHistory.selected(points, 0, 180)
            assertEquals((value * 10).toInt(), comparison.recentTenths)
            assertEquals((value * 10).toInt(), comparison.longTermTenths)
            assertEquals(1, comparison.recentCount)
            assertEquals(value, points.single().value, 0.0)
            assertEquals(value, reading!!.value, 0.0)
        }
        supplied = emptyList()
        fixture.commits.emit(1)
        runCurrent()
        val removed = published.last()
        assertNotNull(removed.samples)
        assertFalse(removed.failed)
        assertNull(HealthspanHistory.comparison(removed.samples!!, false).recentTenths)
        assertTrue(HealthspanHistory.points(removed.samples, 180).isEmpty())
        assertNull(HealthspanHistory.selected(emptyList(), 0, 180))
    }

    @Test fun delayedOldSourceLoadCannotPublishAfterSourceSwitch() = runTest {
        val fixture = RepositoryFixture(emptyList())
        val activeSource = MutableStateFlow("strap-a")
        val published = mutableListOf<Pair<String, HealthspanContributorRead>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            activeSource.flatMapLatest { source ->
                healthspanContributorReads(fixture.repo.healthspanChangesFlow(), { activeSource.value == source }) {
                    if (source == "strap-a") withContext(NonCancellable) { delay(1_000) }
                    listOf(HealthspanHistory.Sample(0, if (source == "strap-a") 70.0 else 85.0))
                }.map { source to it }
            }.collect { published += it }
        }
        runCurrent()
        activeSource.value = "strap-b"
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        assertTrue(published.none { it.first == "strap-a" && it.second.samples != null })
        assertEquals("strap-b", published.last().first)
        assertEquals(85.0, published.last().second.samples!!.single().value, 0.0)
    }

    private class MutableClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
    }

    private class RepositoryFixture(initial: List<DailyMetric>) {
        val rows = MutableStateFlow(initial)
        val commits = MutableSharedFlow<Int>(extraBufferCapacity = 8)
        val bounds = mutableListOf<Pair<String, String>>()
        val repo: WhoopRepository
        init {
            val dao = Proxy.newProxyInstance(WhoopDao::class.java.classLoader, arrayOf(WhoopDao::class.java)) { _, method, args ->
                when (method.name) {
                    "healthspanChangesFlow" -> {
                        assertEquals("SELECT 1", (args!![0] as SupportSQLiteQuery).sql)
                        commits.onStart { emit(1) }
                    }
                    "dailyMetricsRangeFlow" -> {
                        val source = args!![0] as String
                        val from = args[1] as String
                        val to = args[2] as String
                        bounds += from to to
                        rows.map { values -> values.filter { it.deviceId == source && it.day in from..to } }
                    }
                    "editedSleepSessionsFlow" -> flowOf(emptyList<SleepSession>())
                    else -> error("Unexpected repository call: ${method.name}")
                }
            } as WhoopDao
            repo = WhoopRepository(dao)
        }
    }
}
