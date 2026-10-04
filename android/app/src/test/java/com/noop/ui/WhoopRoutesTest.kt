package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhoopRoutesTest {
    @Test
    fun `selected Sleep date has its own restorable entry without changing its caller tab`() {
        val route = WhoopRoute.sleepForDay("2026-10-02")
        assertEquals("sleep/2026-10-02", route)
        assertEquals("sleep/{dayKey}", WhoopRoute.sleepDetailForDay)
        assertEquals(Destination.Sleep, Destination.forRoute(route))
        assertNull(Destination.rootForRoute(route))
        assertEquals("sleep", WhoopRoute.sleepDetail)
    }

    @Test
    fun `only exact roots change the selected shell tab`() {
        listOf(Destination.Today, Destination.Health, Destination.Plan, Destination.More).forEach {
            assertEquals(it, Destination.rootForRoute(it.route))
        }
        listOf(LOCAL_NOTICE_ROUTE, WhoopRoute.localBriefing, WhoopRoute.weeklyPlan,
            Destination.Workouts.route, Destination.Settings.route, "settings/PROFILE", "unknown", null)
            .forEach { assertNull(Destination.rootForRoute(it)) }
    }

    @Test
    fun `every public detail hook resolves to a registered destination`() {
        val hooks = listOf(WhoopRoute.recoveryDetail, WhoopRoute.strainDetail, WhoopRoute.sleepDetail,
            WhoopRoute.sleepPlanner, WhoopRoute.healthMonitor, WhoopRoute.healthspan,
            WhoopRoute.stressMonitor, WhoopRoute.weeklyPlan, WhoopRoute.journal,
            WhoopRoute.localBriefing, WhoopRoute.localNotifications)
        assertEquals(hooks.size, hooks.distinct().size)
        hooks.forEach { route -> assertEquals(route, Destination.forRoute(route).route) }
    }

    @Test
    fun `sleep trends devices and settings stay in More`() {
        val entries = drawerGroups.flatMap { it.items }
        assertTrue(entries.containsAll(listOf(Destination.Sleep, Destination.Trends, Destination.Devices,
            Destination.Settings, Destination.Insights, Destination.WeeklyPlan, Destination.Healthspan)))
    }
}
