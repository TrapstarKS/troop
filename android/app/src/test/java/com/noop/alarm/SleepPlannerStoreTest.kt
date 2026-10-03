package com.noop.alarm

import android.content.Context
import com.noop.analytics.PlannerAlarmPolicy
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SleepPlannerStoreTest {
    @Test fun persistedSkipKeepsItsRawValueAndOnlyCanonicalKeysSuppressAnOccurrence() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("sleep-planner-test", Context.MODE_PRIVATE)
        val store = SleepPlannerStore(prefs)
        val now = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(2026, Calendar.SEPTEMBER, 16, 6, 0, 0)
        }
        for (suffix in listOf("+420", "0420", "420")) {
            val key = "2026-09-16|$suffix"
            prefs.edit().putString("sleepPlanner.skippedOccurrence", key).apply()
            assertEquals(key, store.read().skippedOccurrence)
            store.write(store.read())
            val restored = SleepPlannerStore(prefs).read().skippedOccurrence
            assertEquals(key, prefs.getString("sleepPlanner.skippedOccurrence", null))
            assertEquals(key, restored)
            assertEquals(suffix == "420", PlannerAlarmPolicy.isSkipPending(restored, now))
            val deadline = SmartAlarmScheduler.nextDeadline(
                now, setOf(Calendar.WEDNESDAY), 0, skippedOccurrence = restored,
            ) { 420 }!!
            assertEquals(if (suffix == "420") 23 else 16, deadline.get(Calendar.DAY_OF_MONTH))
            assertEquals(7, deadline.get(Calendar.HOUR_OF_DAY))
            assertEquals(0, deadline.get(Calendar.MINUTE))
        }
    }
}
