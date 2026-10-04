package com.noop.ui

import org.junit.Assert.*
import org.junit.Test

class ShellNotificationRoutesTest {
    @Test fun `Coach brief route-only launch payload stages a legacy route and opens Coach chooser`() {
        val (typed, route) = com.noop.notif.stagedLocalNotification(com.noop.notif.routeOnlyNotificationFields("coach"))
        assertNull(typed)
        assertEquals("coach", route)
        assertEquals(ShellDetailDestination("more", "coach"), localNotificationDestination(route!!, true))
        assertEquals(ShellDetailDestination("more", WhoopRoute.localBriefing), localNotificationDestination(route, false))
    }

    @Test fun `typed dated payload stages a context, not a legacy route`() {
        val ctx = com.noop.notif.LocalNotificationContext("workouts", "workoutReady:1")
        val (typed, route) = com.noop.notif.stagedLocalNotification(ctx.wireFields)
        assertEquals("workouts", typed?.route)
        assertNull(route)
    }

    @Test fun `local reports ignore Coach availability and provider configuration`() {
        for (hasKey in listOf(false, true)) {
            assertEquals(ShellDetailDestination("more", "local_briefing"),
                localNotificationDestination("local_briefing", hasKey))
        }
        assertEquals("local_briefing", localNotificationDestination("coach", false)?.detail)
        assertEquals("coach", localNotificationDestination("coach", true)?.detail)
    }

    @Test fun `only producer routes are accepted and Plan links install Plan root`() {
        for (key in listOf("devices", "workouts", "weekly_plan", "local_briefing")) {
            assertNotNull(localNotificationDestination(key, false))
        }
        for (key in listOf("active_workout", "https://example.com", "", "settings")) {
            assertNull(localNotificationDestination(key, true))
        }
        assertEquals(ShellDetailDestination("plan", "weekly_plan"), localNotificationDestination("weekly_plan", false))
        assertEquals(ShellDetailDestination("plan", "trends"), updatesDestination("trends"))
        assertNull(updatesDestination("unknown"))
    }
    @Test fun `dated notifications install recorded host in the owning tab`() {
        assertEquals(ShellDetailDestination("plan", LOCAL_NOTICE_ROUTE), datedNotificationDestination("weekly_plan"))
        for (key in listOf("workouts", "local_briefing")) {
            assertEquals(ShellDetailDestination("more", LOCAL_NOTICE_ROUTE), datedNotificationDestination(key))
        }
        assertEquals(ShellDetailDestination("more", "devices"), datedNotificationDestination("devices"))
        assertNull(datedNotificationDestination("coach"))
        assertNull(datedNotificationDestination("active_workout"))
    }

}
