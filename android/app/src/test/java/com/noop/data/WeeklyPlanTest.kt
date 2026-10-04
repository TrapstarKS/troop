package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

fun weeklyPlanOracleOutput(): String {
    val lines = mutableListOf<String>()
    for (preset in WeeklyPlanPreset.entries) {
        val goals = preset.goals
        lines += "preset:${preset.key}:${goals.sleepMinutes},${goals.sleepDays},${goals.strainMinimum},${goals.strainDays},${goals.journalDays},${goals.journalQuestion},${goals.journalAnswer}"
    }
    for (day in listOf("2026-2-01", "2026-02-30", "2024-02-29", "2026-10-02", "2026-12-31", "2027-01-01", "0001-01-01", "9999-12-31")) {
        lines += "date:$day:${WeeklyPlanCalendar.weekStart(day) ?: "-"}:${WeeklyPlanCalendar.weekday(day) ?: "-"}"
    }
    for (value in listOf(-999, 0, 1, 7, 8, 50, 100, 101, 1000)) {
        val goals = WeeklyPlanGoals(value, value, value, value, value, "habit", "invalid").normalized
        lines += "goals:$value:${goals.sleepMinutes},${goals.sleepDays},${goals.strainMinimum},${goals.strainDays},${goals.journalDays},${goals.journalAnswer}"
    }
    val sleep = listOf(480.0, 420.0, null, 500.0, 0.0, 999.0, 240.0)
    val strain = listOf(60.0, 45.0, Double.NaN, 100.0, -1.0, 200.0, 0.0)
    val days = mutableListOf(WeeklyPlanDay("2026-09-28", 600.0, 100.0), WeeklyPlanDay("2026-02-30", 600.0, 100.0))
    for (offset in 0 until 7) days += WeeklyPlanDay(WeeklyPlanCalendar.adding(offset, "2026-09-28")!!, sleep[offset], strain[offset])
    val entries = listOf(WeeklyPlanJournalDay("2026-09-28", "habit", true),
        WeeklyPlanJournalDay("2026-09-28", "habit", false),
        WeeklyPlanJournalDay("2026-09-29", "habit", true),
        WeeklyPlanJournalDay("2026-10-01", "habit", false),
        WeeklyPlanJournalDay("2026-10-02", "other", true),
        WeeklyPlanJournalDay("2026-10-04", "habit", true))
    for (today in listOf("2026-09-27", "2026-09-28", "2026-09-29", "2026-09-30", "2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04", "2026-10-05")) {
        for (answer in listOf("any", "yes", "no")) {
            val goals = WeeklyPlanGoals(480, 3, 50, 2, 3, if (answer == "any") "" else "habit", answer)
            val snapshot = WeeklyPlanEngine.snapshot(goals, "2026-09-28", today, days, entries)!!
            fun result(progress: WeeklyPlanProgress) = "${progress.completedDays}/${progress.observedDays}/${progress.percent ?: "-"}"
            lines += "snapshot:$today:$answer:${result(snapshot.sleep)}:${result(snapshot.strain)}:${result(snapshot.journal)}:${snapshot.overallPercent ?: "-"}"
        }
        val notice = WeeklyPlanNoticeResolver.resolve(today, setOf("2026-09-21", "2026-09-28"))
        lines += "notice:$today:${notice?.id ?: "-"}"
    }
    for (today in listOf("2026-10-02", "2026-10-05")) {
        val goals = WeeklyPlanEngine.suggestedGoals(days, today)
        lines += "suggested:$today:${goals.sleepMinutes}:${goals.strainMinimum}"
    }
    val extreme = WeeklyPlanEngine.suggestedGoals(listOf(WeeklyPlanDay("2026-09-28", 1e308), WeeklyPlanDay("2026-09-29", 1e308)), "2026-10-02")
    lines += "suggested:extreme:${extreme.sleepMinutes}:${extreme.strainMinimum}"
    return lines.joinToString("\n")
}

fun weeklyPlanEligibilityOracleOutput(): String {
    val today = "2026-10-05"
    val valid = (0 until 8).map {
        WeeklyPlanRecoveryDay(WeeklyPlanCalendar.adding(it, "2026-09-28")!!, 50.0, true)
    }
    val lines = mutableListOf<String>()
    fun append(label: String, rows: List<WeeklyPlanRecoveryDay>, day: String = "2026-10-05") {
        val result = WeeklyPlanEligibility.resolve(rows, day)
        lines += "$label:${result.completedRecoveries}:${result.remainingRecoveries}:${result.isEligible}"
    }
    for (count in listOf(0, 1, 6, 7, 8)) append("count-$count", valid.take(count), today)
    append("duplicate-seven", valid.take(7) + valid.take(7))
    append("duplicate-one", List(7) { valid[0] })
    val scores = listOf("missing" to null, "nan" to Double.NaN, "infinity" to Double.POSITIVE_INFINITY,
        "negative-infinity" to Double.NEGATIVE_INFINITY, "negative" to -1.0, "above-range" to 101.0, "zero" to 0.0, "hundred" to 100.0)
    for ((label, score) in scores) append(label, valid.take(6) + WeeklyPlanRecoveryDay("2026-10-04", score, true))
    for (day in listOf("2026-02-30", "2026-2-01", "", "2026-10-06")) {
        append("day-$day", valid.take(6) + WeeklyPlanRecoveryDay(day, 50.0, true))
    }
    append("invalid-today", valid, "2026-02-30")
    append("before-seventh", valid.take(7), "2026-10-03")
    append("incomplete", valid.take(6) + WeeklyPlanRecoveryDay("2026-10-04", 50.0, false))
    append("subsequent-refresh", valid.take(7))
    append("not-processed", valid.map { WeeklyPlanRecoveryDay(it.day, it.recovery, false) })
    return lines.joinToString("\n")
}

class WeeklyPlanTest {
    @Test
    fun completedRecoveryEligibilityMatchesVerbatimSwiftOracle() {
        assertEquals(ELIGIBILITY_ORACLE, weeklyPlanEligibilityOracleOutput())
    }

    @Test
    fun matchesVerbatimSwiftOracle() {
        assertEquals(EXPECTED_ORACLE, weeklyPlanOracleOutput())
    }

    @Test
    fun unavailableDoesNotBecomeZeroProgress() {
        val snapshot = WeeklyPlanEngine.snapshot(WeeklyPlanGoals(), "2026-09-28", "2026-10-02", emptyList(), emptyList())!!
        assertNull(snapshot.sleep.percent)
        assertNull(snapshot.strain.percent)
        assertEquals(0, snapshot.journal.percent)
        assertNull(snapshot.overallPercent)
        assertNull(WeeklyPlanEngine.snapshot(WeeklyPlanGoals(), "2026-09-29", "2026-10-02", emptyList(), emptyList()))
    }

    @Test
    fun unansweredHabitIsNotCountedAsNo() {
        val goals = WeeklyPlanGoals(journalQuestion = "habit", journalAnswer = "no")
        val snapshot = WeeklyPlanEngine.snapshot(goals, "2026-09-28", "2026-10-02", emptyList(),
            listOf(WeeklyPlanJournalDay("2026-09-29", "other", false)))!!
        assertEquals(0, snapshot.journal.completedDays)
        assertNull(snapshot.journal.percent)
    }

    @Test
    fun noticesRequireSavedPlansAndRespectAcknowledgment() {
        assertNull(WeeklyPlanNoticeResolver.resolve("2026-10-02", emptySet()))
        assertEquals("checkIn:2026-09-28", WeeklyPlanNoticeResolver.resolve("2026-10-02", setOf("2026-09-28"))?.id)
        assertNull(WeeklyPlanNoticeResolver.resolve("2026-10-02", setOf("2026-09-28"), setOf("checkIn:2026-09-28")))
        assertEquals("recap:2026-09-28", WeeklyPlanNoticeResolver.resolve("2026-10-05", setOf("2026-09-28"))?.id)
        assertNull(WeeklyPlanNoticeResolver.resolve("2026-10-06", setOf("2026-09-28")))
    }

    companion object {
        val ELIGIBILITY_ORACLE = """
            count-0:0:7:false
            count-1:1:6:false
            count-6:6:1:false
            count-7:7:0:true
            count-8:8:0:true
            duplicate-seven:7:0:true
            duplicate-one:1:6:false
            missing:6:1:false
            nan:6:1:false
            infinity:6:1:false
            negative-infinity:6:1:false
            negative:6:1:false
            above-range:6:1:false
            zero:7:0:true
            hundred:7:0:true
            day-2026-02-30:6:1:false
            day-2026-2-01:6:1:false
            day-:6:1:false
            day-2026-10-06:6:1:false
            invalid-today:0:7:false
            before-seventh:6:1:false
            incomplete:6:1:false
            subsequent-refresh:7:0:true
            not-processed:0:7:false
        """.trimIndent()

        val EXPECTED_ORACLE = """
            preset:restRoutine:480,5,50,3,5,,any
            preset:activeWeek:480,5,60,4,5,,any
            preset:balancedWeek:450,5,40,3,5,,any
            date:2026-2-01:-:-
            date:2026-02-30:-:-
            date:2024-02-29:2024-02-26:4
            date:2026-10-02:2026-09-28:5
            date:2026-12-31:2026-12-28:4
            date:2027-01-01:2026-12-28:5
            date:0001-01-01:-:6
            date:9999-12-31:9999-12-27:5
            goals:-999:240,1,1,1,1,yes
            goals:0:240,1,1,1,1,yes
            goals:1:240,1,1,1,1,yes
            goals:7:240,7,7,7,7,yes
            goals:8:240,7,8,7,7,yes
            goals:50:240,7,50,7,7,yes
            goals:100:240,7,100,7,7,yes
            goals:101:240,7,100,7,7,yes
            goals:1000:720,7,100,7,7,yes
            snapshot:2026-09-27:any:0/0/-:0/0/-:0/0/-:-
            snapshot:2026-09-27:yes:0/0/-:0/0/-:0/0/-:-
            snapshot:2026-09-27:no:0/0/-:0/0/-:0/0/-:-
            notice:2026-09-27:-
            snapshot:2026-09-28:any:1/1/33:1/1/50:1/1/33:38
            snapshot:2026-09-28:yes:1/1/33:1/1/50:0/1/0:27
            snapshot:2026-09-28:no:1/1/33:1/1/50:1/1/33:38
            notice:2026-09-28:recap:2026-09-21
            snapshot:2026-09-29:any:1/2/33:1/2/50:2/2/66:49
            snapshot:2026-09-29:yes:1/2/33:1/2/50:1/2/33:38
            snapshot:2026-09-29:no:1/2/33:1/2/50:1/2/33:38
            notice:2026-09-29:-
            snapshot:2026-09-30:any:1/2/33:1/2/50:2/3/66:49
            snapshot:2026-09-30:yes:1/2/33:1/2/50:1/2/33:38
            snapshot:2026-09-30:no:1/2/33:1/2/50:1/2/33:38
            notice:2026-09-30:-
            snapshot:2026-10-01:any:2/3/66:2/3/100:3/4/100:88
            snapshot:2026-10-01:yes:2/3/66:2/3/100:1/3/33:66
            snapshot:2026-10-01:no:2/3/66:2/3/100:2/3/66:77
            notice:2026-10-01:-
            snapshot:2026-10-02:any:2/4/66:2/3/100:4/5/100:88
            snapshot:2026-10-02:yes:2/4/66:2/3/100:1/3/33:66
            snapshot:2026-10-02:no:2/4/66:2/3/100:2/3/66:77
            notice:2026-10-02:checkIn:2026-09-28
            snapshot:2026-10-03:any:3/5/100:2/3/100:4/6/100:100
            snapshot:2026-10-03:yes:3/5/100:2/3/100:1/3/33:77
            snapshot:2026-10-03:no:3/5/100:2/3/100:2/3/66:88
            notice:2026-10-03:-
            snapshot:2026-10-04:any:3/6/100:2/4/100:5/7/100:100
            snapshot:2026-10-04:yes:3/6/100:2/4/100:2/4/66:88
            snapshot:2026-10-04:no:3/6/100:2/4/100:2/4/66:88
            notice:2026-10-04:-
            snapshot:2026-10-05:any:3/6/100:2/4/100:5/7/100:100
            snapshot:2026-10-05:yes:3/6/100:2/4/100:2/4/66:88
            snapshot:2026-10-05:no:3/6/100:2/4/100:2/4/66:88
            notice:2026-10-05:recap:2026-09-28
            suggested:2026-10-02:467:68
            suggested:2026-10-05:528:51
            suggested:extreme:720:50
        """.trimIndent()
    }
}
