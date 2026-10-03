package com.noop.ui
import com.noop.data.*
import org.junit.Assert.assertEquals
import org.junit.Test
fun weeklyPlanDayAnchorOracleOutput(): String {
    data class Case(val label: String, val opened: String, val clockDay: String, val offset: Int)
    val cases = listOf(
        Case("Thursday-Friday", "2026-10-01", "2026-10-02", 0),
        Case("historical-Friday", "2026-10-01", "2026-10-02", -1),
        Case("Sunday-Monday", "2026-10-04", "2026-10-05", 0),
        Case("historical-Monday", "2026-10-04", "2026-10-05", -1),
        Case("later-week", "2026-10-04", "2026-10-19", 0),
        Case("historical-later", "2026-10-04", "2026-10-19", -2),
        Case("year-boundary", "2026-12-31", "2027-01-04", -1),
        Case("same-day", "2026-10-01", "2026-10-01", -2),
        Case("invalid-tick", "2026-10-01", "2026-02-30", -1),
        Case("clock-back", "2026-10-05", "2026-10-04", -1),
        Case("clock-back-current", "2026-10-05", "2026-10-04", 0),
        Case("leap", "2024-02-28", "2024-03-04", -1)
    )
    return cases.joinToString("\n") { (label, opened, clockDay, offset) ->
        val anchor = WeeklyPlanDayAnchor(opened, offset).advanced(clockDay)
        val current = WeeklyPlanCalendar.weekStart(anchor.today)!!
        val selected = WeeklyPlanCalendar.adding(anchor.weekOffset * 7, current)!!
        val editorWeek = WeeklyPlanCalendar.adding(offset * 7, WeeklyPlanCalendar.weekStart(opened)!!)!!
        val notice = WeeklyPlanNoticeResolver.resolve(anchor.today, setOf(current, WeeklyPlanCalendar.adding(-7, current)!!))
        "$label|${anchor.today}|${anchor.weekOffset}|$selected|${notice?.id ?: "-"}|$editorWeek"
    }
}

class WeeklyPlanDayAnchorTest {
    @Test
    fun clockTicksAdvanceNoticesAndPreserveHistoricalSelection() {
        assertEquals(ORACLE, weeklyPlanDayAnchorOracleOutput())
    }

    companion object {
        private val ORACLE = """
            Thursday-Friday|2026-10-02|0|2026-09-28|checkIn:2026-09-28|2026-09-28
            historical-Friday|2026-10-02|-1|2026-09-21|checkIn:2026-09-28|2026-09-21
            Sunday-Monday|2026-10-05|0|2026-10-05|recap:2026-09-28|2026-09-28
            historical-Monday|2026-10-05|-2|2026-09-21|recap:2026-09-28|2026-09-21
            later-week|2026-10-19|0|2026-10-19|recap:2026-10-12|2026-09-28
            historical-later|2026-10-19|-5|2026-09-14|recap:2026-10-12|2026-09-14
            year-boundary|2027-01-04|-2|2026-12-21|recap:2026-12-28|2026-12-21
            same-day|2026-10-01|-2|2026-09-14|-|2026-09-14
            invalid-tick|2026-10-01|-1|2026-09-21|-|2026-09-21
            clock-back|2026-10-04|0|2026-09-28|-|2026-09-28
            clock-back-current|2026-10-04|0|2026-09-28|-|2026-10-05
            leap|2024-03-04|-2|2024-02-19|recap:2024-02-26|2024-02-19
        """.trimIndent()
    }
}
