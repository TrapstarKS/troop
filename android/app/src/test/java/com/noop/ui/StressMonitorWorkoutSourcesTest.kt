package com.noop.ui

import com.noop.analytics.StrainScorer
import com.noop.data.DismissedWorkout
import com.noop.data.HrWindowStats
import com.noop.data.PairedDeviceRow
import com.noop.data.WhoopDao
import com.noop.data.WhoopRepository
import com.noop.data.WorkoutRow
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StressMonitorWorkoutSourcesTest {
    @Test fun switchingActiveStrapChangesDuplicateBoundsAndHrFill() = runBlocking {
        val fixture = Fixture(
            rows = mapOf(
                "whoop-a" to listOf(row("whoop-a", 100L, 200L, "manual").copy(avgHr = null, maxHr = null)),
                "whoop-b" to listOf(row("whoop-b", 100L, 240L, "manual").copy(avgHr = null, maxHr = null)),
                "my-whoop" to listOf(row("my-whoop", 100L, 220L, "manual")),
            ),
            hr = mapOf(
                "whoop-a" to HrWindowStats(100L, 119.6, 150),
                "whoop-b" to HrWindowStats(140L, 139.6, 170),
            ),
        )

        val before = stressMonitorWorkoutRows(fixture.repo, "whoop-a", 10_000L, 190.0, "female", StrainScorer.Method.EDWARDS).single()
        val after = stressMonitorWorkoutRows(fixture.repo, "whoop-b", 10_000L, 190.0, "female", StrainScorer.Method.EDWARDS).single()

        assertEquals("whoop-a", before.deviceId)
        assertEquals(200L, before.endTs)
        assertEquals(120, before.avgHr)
        assertEquals("whoop-b", after.deviceId)
        assertEquals(240L, after.endTs)
        assertEquals(140, after.avgHr)
        assertEquals(
            listOf(
                listOf<Any>("whoop-a", "my-whoop", 100L, 200L),
                listOf<Any>("whoop-b", "my-whoop", 100L, 240L),
            ),
            fixture.hrWindows,
        )
    }

    @Test fun sharedAndArchivedContextSurvivesWhileDismissedDetectionsStayHidden() = runBlocking {
        val fixture = Fixture(
            rows = mapOf(
                "whoop-archived" to listOf(row("whoop-archived", 1_000L, 1_100L)),
                "whoop-b" to listOf(row("whoop-b", 2_000L, 2_100L, "manual")),
                "my-whoop" to listOf(row("my-whoop", 3_000L, 3_100L)),
                "apple-health" to listOf(
                    row("apple-health", 4_000L, 4_100L),
                    row("apple-health", 2_010L, 2_110L).copy(strain = null),
                ),
                "health-connect" to listOf(row("health-connect", 5_000L, 5_100L)),
                "activity-file" to listOf(row("activity-file", 6_000L, 6_100L)),
                "lifting" to listOf(row("lifting", 7_000L, 7_100L).copy(avgHr = null, maxHr = null, strain = null)),
                "whoop-a-noop" to listOf(row("whoop-a-noop", 8_000L, 8_100L)),
                "oura-ring" to listOf(row("oura-ring", 9_000L, 9_100L)),
            ),
            markers = mapOf("my-whoop-noop" to listOf(DismissedWorkout("my-whoop-noop", 7_950L, 8_150L))),
        )

        val result = stressMonitorWorkoutRows(fixture.repo, "whoop-b", 10_000L, 180.0, "male", StrainScorer.Method.BANISTER)

        assertEquals(
            listOf("lifting", "activity-file", "health-connect", "apple-health", "my-whoop", "whoop-b", "whoop-archived"),
            result.map { it.deviceId },
        )
        assertEquals(listOf(7_000L, 6_000L, 5_000L, 4_000L, 3_000L, 2_000L, 1_000L), result.map { it.startTs })
        assertEquals(
            setOf("whoop-b-noop", "whoop-a-noop", "whoop-archived-noop", "my-whoop-noop"),
            fixture.dismissalReads.toSet(),
        )
        assertFalse(fixture.hrWindows.any { it[2] == 7_000L })
        assertEquals(null, result.first().avgHr)
    }

    private fun row(id: String, start: Long, end: Long, source: String = id) = WorkoutRow(
        deviceId = id, startTs = start, endTs = end, sport = "Running", source = source,
        avgHr = 110, maxHr = 150, strain = 4.0,
    )

    private class Fixture(
        rows: Map<String, List<WorkoutRow>>,
        markers: Map<String, List<DismissedWorkout>> = emptyMap(),
        hr: Map<String, HrWindowStats> = emptyMap(),
    ) {
        val hrWindows = mutableListOf<List<Any>>()
        val dismissalReads = mutableListOf<String>()
        private val paired = listOf("whoop-a", "whoop-b", "whoop-archived").mapIndexed { index, id ->
            PairedDeviceRow(
                id = id, brand = "WHOOP", model = "5.0", nickname = null,
                sourceKind = "historyBLE", capabilities = "hr",
                status = if (id == "whoop-archived") "archived" else "paired",
                addedAt = index.toLong(), lastSeenAt = index.toLong(),
            )
        } + PairedDeviceRow(
            id = "oura-ring", brand = "Oura", model = "Ring", nickname = null,
            sourceKind = "oura", capabilities = "hr", status = "paired", addedAt = 3L, lastSeenAt = 3L,
        )
        private val dao = Proxy.newProxyInstance(
            WhoopDao::class.java.classLoader, arrayOf(WhoopDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "pairedDevices" -> paired
                "workouts" -> rows[(args!![0] as String)].orEmpty().filter {
                    it.startTs in (args[1] as Long)..(args[2] as Long)
                }
                "dismissedWorkouts" -> {
                    val id = args!![0] as String
                    dismissalReads += id
                    markers[id].orEmpty()
                }
                "hrWindowStats" -> {
                    hrWindows += args!!.take(4).map { requireNotNull(it) }
                    hr[args[0] as String] ?: HrWindowStats(0L, null, null)
                }
                else -> throw AssertionError("unexpected DAO call ${method.name}")
            }
        } as WhoopDao
        val repo = WhoopRepository(dao)
    }
}
