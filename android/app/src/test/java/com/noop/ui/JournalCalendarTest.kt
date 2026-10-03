package com.noop.ui

import com.noop.data.WeeklyPlanCalendar
import org.junit.Assert.assertEquals
import org.junit.Test

private data class JournalRolloverCase(val label: String, val offset: Long, val from: String, val to: String, val dirty: Boolean)

fun journalCalendarOracleOutput(): String {
    val cases = listOf(
        JournalRolloverCase("sameDay", 0L, "2026-10-02", "2026-10-02", false),
        JournalRolloverCase("cleanToday", 0L, "2026-10-02", "2026-10-03", false),
        JournalRolloverCase("dirtyToday", 0L, "2026-10-02", "2026-10-03", true),
        JournalRolloverCase("historical", 6L, "2026-10-02", "2026-10-03", false),
        JournalRolloverCase("dirtyHistory", 2L, "2026-10-02", "2026-10-03", true),
        JournalRolloverCase("tomorrow", -1L, "2026-10-02", "2026-10-03", false),
        JournalRolloverCase("future", -3L, "2026-10-02", "2026-10-03", false),
        JournalRolloverCase("multiDayDraft", 0L, "2026-10-02", "2026-10-07", true),
        JournalRolloverCase("multiDayHistory", 4L, "2026-10-02", "2026-10-07", false),
        JournalRolloverCase("backwardDraft", 0L, "2026-10-02", "2026-09-30", true),
        JournalRolloverCase("backwardHistory", 3L, "2026-10-02", "2026-09-30", false),
        JournalRolloverCase("backwardCleanToday", 0L, "2026-10-02", "2026-09-30", false),
        JournalRolloverCase("year", 2L, "2026-12-31", "2027-01-01", false),
        JournalRolloverCase("leap", 0L, "2024-02-28", "2024-03-01", true),
        JournalRolloverCase("nonLeap", 1L, "2025-02-28", "2025-03-01", false),
        JournalRolloverCase("usSpring", 0L, "2026-03-08", "2026-03-09", true),
        JournalRolloverCase("usFall", 2L, "2026-11-01", "2026-11-02", false),
        JournalRolloverCase("euSpring", -1L, "2026-03-29", "2026-03-30", false),
        JournalRolloverCase("euFall", 0L, "2026-10-25", "2026-10-26", true),
        JournalRolloverCase("brazilSpring", 6L, "2018-11-03", "2018-11-04", false),
        JournalRolloverCase("invalidFrom", 3L, "bad", "2026-10-03", false),
        JournalRolloverCase("invalidTo", -1L, "2026-10-02", "2026-02-30", true),
        JournalRolloverCase("invalidWidth", 0L, "2026-2-01", "2026-03-01", true),
        JournalRolloverCase("month", 0L, "2026-01-31", "2026-02-02", true),
        JournalRolloverCase("ancient", 1L, "0001-01-01", "0001-01-02", false),
        JournalRolloverCase("upperOverflow", Long.MAX_VALUE, "2026-10-02", "2026-10-03", true),
        JournalRolloverCase("lowerOverflow", Long.MIN_VALUE, "2026-10-03", "2026-10-02", true),
    )
    return cases.joinToString("\n") { case ->
        val resolved = JournalCalendar.rolloverOffset(case.offset, case.from, case.to, case.dirty)
        "${case.label}|${case.offset}|${case.from}|${case.to}|${case.dirty}|$resolved"
    }
}

class JournalCalendarTest {
    @Test
    fun matchesVerbatimSwiftOracle() {
        assertEquals(EXPECTED_ORACLE, journalCalendarOracleOutput())
    }

    @Test
    fun preservesSelectedDateAcrossCalendarTransitions() {
        for ((from, to) in listOf("2026-10-02" to "2026-10-03", "2026-10-02" to "2026-10-07",
                                 "2026-10-02" to "2026-09-30", "2026-12-31" to "2027-01-01",
                                 "2024-02-28" to "2024-03-01", "2026-03-08" to "2026-03-09",
                                 "2026-11-01" to "2026-11-02")) {
            for (offset in -1L..6L) {
                val resolved = JournalCalendar.rolloverOffset(offset, from, to, preserveDraft = true)
                assertEquals(WeeklyPlanCalendar.adding((-offset).toInt(), from), WeeklyPlanCalendar.adding((-resolved).toInt(), to))
            }
        }
    }

    companion object {
        val EXPECTED_ORACLE = """
            sameDay|0|2026-10-02|2026-10-02|false|0
            cleanToday|0|2026-10-02|2026-10-03|false|0
            dirtyToday|0|2026-10-02|2026-10-03|true|1
            historical|6|2026-10-02|2026-10-03|false|7
            dirtyHistory|2|2026-10-02|2026-10-03|true|3
            tomorrow|-1|2026-10-02|2026-10-03|false|0
            future|-3|2026-10-02|2026-10-03|false|-2
            multiDayDraft|0|2026-10-02|2026-10-07|true|5
            multiDayHistory|4|2026-10-02|2026-10-07|false|9
            backwardDraft|0|2026-10-02|2026-09-30|true|-2
            backwardHistory|3|2026-10-02|2026-09-30|false|1
            backwardCleanToday|0|2026-10-02|2026-09-30|false|0
            year|2|2026-12-31|2027-01-01|false|3
            leap|0|2024-02-28|2024-03-01|true|2
            nonLeap|1|2025-02-28|2025-03-01|false|2
            usSpring|0|2026-03-08|2026-03-09|true|1
            usFall|2|2026-11-01|2026-11-02|false|3
            euSpring|-1|2026-03-29|2026-03-30|false|0
            euFall|0|2026-10-25|2026-10-26|true|1
            brazilSpring|6|2018-11-03|2018-11-04|false|7
            invalidFrom|3|bad|2026-10-03|false|3
            invalidTo|-1|2026-10-02|2026-02-30|true|-1
            invalidWidth|0|2026-2-01|2026-03-01|true|0
            month|0|2026-01-31|2026-02-02|true|2
            ancient|1|0001-01-01|0001-01-02|false|2
            upperOverflow|9223372036854775807|2026-10-02|2026-10-03|true|9223372036854775807
            lowerOverflow|-9223372036854775808|2026-10-03|2026-10-02|true|-9223372036854775808
        """.trimIndent()
    }
}
