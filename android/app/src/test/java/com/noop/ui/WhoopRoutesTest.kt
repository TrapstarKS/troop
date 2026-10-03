package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhoopRoutesTest {
    @Test
    fun `every public detail hook resolves to a registered destination`() {
        val hooks = listOf(WhoopRoute.recoveryDetail, WhoopRoute.strainDetail, WhoopRoute.sleepDetail,
            WhoopRoute.sleepPlanner, WhoopRoute.healthMonitor, WhoopRoute.healthspan,
            WhoopRoute.stressMonitor, WhoopRoute.weeklyPlan, WhoopRoute.journal)
        assertEquals(hooks.size, hooks.distinct().size)
        hooks.forEach { route -> assertEquals(route, Destination.forRoute(route).route) }
    }

    @Test
    fun `sleep trends devices and settings stay in More`() {
        val entries = drawerGroups.flatMap { it.items }
        assertTrue(entries.containsAll(listOf(Destination.Sleep, Destination.Trends, Destination.Devices,
            Destination.Settings, Destination.Insights, Destination.WeeklyPlan)))
    }
}
