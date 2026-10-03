package com.noop.data

import com.noop.analytics.HealthspanHistory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class HealthspanHistoryRangeTest {
    @Test fun boundedHistoryRetainsOldWeeksAndReadsSelectedRecoveryWindowAfterSourceSwitch() = runBlocking {
        val today = LocalDate.of(2026, 10, 3)
        val rows = (0 until 1000).map { DailyMetric("strap-a", today.minusDays(it.toLong()).toString(), recovery = 80.0, restingHr = 50) } +
            (0 until 90).map { DailyMetric("strap-b", today.minusDays(it.toLong()).toString(), recovery = 70.0, restingHr = 65) }
        val reads = mutableListOf<Triple<String, String, String>>()
        val dao = Proxy.newProxyInstance(WhoopDao::class.java.classLoader, arrayOf(WhoopDao::class.java)) { _, method, args ->
            when (method.name) {
                "dailyMetricsRangeFlow" -> {
                    val source = args!![0] as String; val from = args[1] as String; val to = args[2] as String
                    reads += Triple(source, from, to)
                    flowOf(rows.filter { it.deviceId == source && it.day in from..to }.sortedBy { it.day })
                }
                "editedSleepSessionsFlow" -> flowOf(emptyList<SleepSession>())
                else -> error("Historical read must not use dashboard/unbounded queries: ${method.name}")
            }
        } as WhoopDao
        val repo = WhoopRepository(dao)
        val from = today.minusDays(3999).toString(); val to = today.toString()
        val a = repo.daysMergedRangeFlow("strap-a", from, to).first()
        assertEquals(1000, a.size)
        fun offsets(days: List<DailyMetric>, reference: LocalDate) = days.map {
            ChronoUnit.DAYS.between(LocalDate.parse(it.day), reference).toInt()
        }
        val oldest = HealthspanHistory.oldestReferenceOffset(offsets(a, today))
        assertEquals(969, oldest)
        val reference = today.minusDays(oldest.toLong())
        val selected = repo.daysMergedRangeFlow("strap-a", reference.minusDays(30).toString(), reference.toString()).first()
        assertEquals(31, selected.size)
        assertEquals(31, HealthspanHistory.eligibility(offsets(selected, reference), 40.0, 0).recoveryDays)
        val b = repo.daysMergedRangeFlow("strap-b", from, to).first()
        assertEquals(90, b.size)
        assertEquals(59, HealthspanHistory.oldestReferenceOffset(offsets(b, today)))
        assertTrue(b.all { it.restingHr == 65 })
        assertEquals(HealthspanHistory.State.ready, HealthspanHistory.eligibility(offsets(b, today), 40.0, 0).state)
        assertTrue(reads.any { it.first == "strap-a" && it.second == reference.minusDays(30).toString() && it.third == reference.toString() })
    }
}
