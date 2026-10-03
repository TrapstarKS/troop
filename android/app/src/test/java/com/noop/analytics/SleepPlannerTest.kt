package com.noop.analytics

import java.time.Instant
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class SleepPlannerTest {
    /** Verbatim standalone swiftc -O oracle stdout, also pinned by the Swift twin. */
    @Test
    fun plannerAndAlarmPolicyMatchStandaloneSwiftOracle() {
        val lines = mutableListOf<String>()
        val planCases = listOf(
            intArrayOf(480, 0, 100, 420, 30, 0),
            intArrayOf(480, 60, 85, 420, 30, 3),
            intArrayOf(480, 120, 70, 420, 45, 10),
            intArrayOf(480, 59, 85, 420, 30, 3),
            intArrayOf(480, 60, 85, 420, 30, 2),
            intArrayOf(300, 0, 85, 255, 0, 3),
            intArrayOf(301, 0, 85, 256, 0, 3),
            intArrayOf(301, 0, 70, 211, 1, 3),
            intArrayOf(302, 0, 70, 211, 0, 3),
            intArrayOf(660, 120, 100, 780, 120, 3),
            intArrayOf(660, 120, 100, 900, 120, 3),
            intArrayOf(660, 120, 100, 1439, 120, 3),
            intArrayOf(0, -1, 0, -1, -1, -1),
            intArrayOf(-300, -120, 101, 2000, 200, 3),
            intArrayOf(Int.MIN_VALUE, Int.MIN_VALUE, Int.MIN_VALUE, Int.MIN_VALUE, Int.MIN_VALUE, Int.MIN_VALUE),
            intArrayOf(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE),
            intArrayOf(299, 119, 70, 0, 120, 3),
            intArrayOf(661, 121, 85, 1439, 0, 3),
            intArrayOf(479, 1, 85, 408, 120, 3),
            intArrayOf(479, 0, 85, 407, 0, 3),
            intArrayOf(480, 0, 70, 336, 0, 3),
            intArrayOf(481, 0, 70, 336, 0, 3),
            intArrayOf(480, 60, 100, 539, 0, 3),
            intArrayOf(480, 60, 100, 540, 1, 3),
            intArrayOf(480, 60, 100, 541, 1, 3),
        )
        planCases.forEachIndexed { index, input ->
            val plan = SleepPlanner.plan(
                baseNeedMinutes = input[0], debtMinutes = input[1], goalPercent = input[2],
                wakeMinutes = input[3], leadMinutes = input[4], historyNights = input[5],
            )
            lines.add("plan$index:${plan.needMinutes},${plan.debtMinutes},${plan.targetSleepMinutes},${plan.bedtimeMinutes},${plan.bedtimeDayShift},${plan.reminderMinutes},${plan.reminderDayShift},${plan.wakeMinutes},${plan.historyReady},${plan.debtNudge}")
        }

        val weekdayCases = listOf(
            Triple(1, mapOf(1 to 85, 7 to 70), 100),
            Triple(7, mapOf(1 to 85, 7 to 70), 100),
            Triple(2, mapOf(1 to 85), 70),
            Triple(3, mapOf(3 to 0), 85),
            Triple(0, mapOf(0 to 70), 85),
            Triple(8, mapOf(8 to 70), 85),
            Triple(4, emptyMap(), 10),
            Triple(Int.MIN_VALUE, mapOf(Int.MIN_VALUE to 70), 100),
            Triple(Int.MAX_VALUE, mapOf(Int.MAX_VALUE to 70), 100),
        )
        weekdayCases.forEachIndexed { index, input ->
            lines.add("weekday$index:${SleepPlanner.weekdayGoal(input.first, input.second, input.third)}")
        }
        lines.add("goals:${SleepPlannerGoal.entries.joinToString(",") { it.percent.toString() }}")

        data class QuietCase(val minute: Int, val enabled: Boolean, val start: Int, val end: Int)
        val quietCases = listOf(
            QuietCase(539, true, 540, 1020),
            QuietCase(540, true, 540, 1020),
            QuietCase(1019, true, 540, 1020),
            QuietCase(1020, true, 540, 1020),
            QuietCase(60, true, 1320, 420),
            QuietCase(419, true, 1320, 420),
            QuietCase(420, true, 1320, 420),
            QuietCase(1319, true, 1320, 420),
            QuietCase(1320, true, 1320, 420),
            QuietCase(1320, false, 1320, 420),
            QuietCase(0, true, 0, 0),
            QuietCase(1439, true, 1439, 1439),
            QuietCase(-1, true, -1, 300),
            QuietCase(Int.MIN_VALUE, true, Int.MIN_VALUE, Int.MAX_VALUE),
            QuietCase(Int.MAX_VALUE, true, Int.MIN_VALUE, Int.MAX_VALUE),
            QuietCase(Int.MIN_VALUE, true, Int.MAX_VALUE, Int.MIN_VALUE),
            QuietCase(Int.MAX_VALUE, true, Int.MAX_VALUE, Int.MIN_VALUE),
            QuietCase(Int.MAX_VALUE, true, Int.MAX_VALUE, Int.MAX_VALUE),
        )
        quietCases.forEachIndexed { index, input ->
            lines.add("quiet$index:${PlannerAlarmPolicy.isQuietMinute(input.minute, input.enabled, input.start, input.end)}")
        }

        val skipCases = listOf(
            Triple("2026-10-01|420", "2026-10-02T07:00:00Z", "UTC"),
            Triple("2026-10-03|0", "2026-10-02T23:59:00Z", "UTC"),
            Triple("2026-10-02|419", "2026-10-02T07:00:00Z", "UTC"),
            Triple("2026-10-02|420", "2026-10-02T07:00:00Z", "UTC"),
            Triple("2026-10-02|421", "2026-10-02T07:00:00Z", "UTC"),
            Triple("2026-12-31|1439", "2027-01-01T00:00:00Z", "UTC"),
            Triple("2027-01-01|0", "2026-12-31T23:59:00Z", "UTC"),
            Triple("2026-10-02|100", "2026-10-02T01:39:00Z", "UTC"),
            Triple("2026-10-02|99", "2026-10-02T01:40:00Z", "UTC"),
            Triple("", "2026-10-02T07:00:00Z", "UTC"),
            Triple("bad", "2026-10-02T07:00:00Z", "UTC"),
            Triple("2026-10-02", "2026-10-02T07:00:00Z", "UTC"),
            Triple("2026-10-02|420|bad", "2026-10-02T07:00:00Z", "UTC"),
            Triple("2026-1-02|420", "2026-10-02T07:00:00Z", "UTC"),
            Triple("2026_10_02|420", "2026-10-02T07:00:00Z", "UTC"),
            Triple("2026-10-02|x", "2026-10-02T07:00:00Z", "UTC"),
            Triple("2026-10-02|-1", "2026-10-02T07:00:00Z", "UTC"),
            Triple("2026-10-02|1440", "2026-10-02T07:00:00Z", "UTC"),
            Triple("2026-10-02|0420", "2026-10-02T07:00:00Z", "UTC"),
            Triple("2026-10-02|+420", "2026-10-02T07:00:00Z", "UTC"),
            Triple("2026-10-02|420 ", "2026-10-02T07:00:00Z", "UTC"),
            Triple("2026-10-02|0", "2026-10-02T00:00:00Z", "UTC"),
            Triple("2026-10-02|1439", "2026-10-02T23:59:00Z", "UTC"),
            Triple("2026-10-02|999999999999999999999", "2026-10-02T07:00:00Z", "UTC"),
            Triple("２０２６-10-02|420", "2026-10-02T07:00:00Z", "UTC"),
            Triple("2026-02-30|420", "2026-02-28T07:00:00Z", "UTC"),
            Triple("1900-02-29|420", "1900-02-28T07:00:00Z", "UTC"),
            Triple("2000-02-29|420", "2000-02-28T07:00:00Z", "UTC"),
            Triple("2026-13-01|420", "2026-12-31T07:00:00Z", "UTC"),
            Triple("0000-01-01|420", "2026-10-02T07:00:00Z", "UTC"),
            Triple("2026-10-02|420", "2026-10-02T07:00:01Z", "UTC"),
            Triple("2026-10-02|420", "2026-10-02T07:00:59Z", "UTC"),
            Triple("2026-11-01|90", "2026-11-01T05:31:00Z", "America/New_York"),
            Triple("2026-11-01|90", "2026-11-01T06:30:00Z", "America/New_York"),
            Triple("2026-11-01|90", "2026-11-01T06:31:00Z", "America/New_York"),
            Triple("2026-11-01|90", "2026-11-01T05:30:00Z", "America/New_York"),
        )
        skipCases.forEachIndexed { index, input ->
            val now = GregorianCalendar(TimeZone.getTimeZone(input.third)).apply {
                timeInMillis = Instant.parse(input.second).toEpochMilli()
            }
            val pending = PlannerAlarmPolicy.isSkipPending(input.first, now)
            lines.add("skip$index:$pending,${now.timeInMillis}")
        }

        val dateCases = listOf(
            intArrayOf(3, 8, 150, 480),
            intArrayOf(11, 1, 90, 480),
            intArrayOf(3, 8, 420, 480),
            intArrayOf(11, 1, 420, 480),
            intArrayOf(3, 8, 0, 480),
            intArrayOf(11, 1, 0, 480),
            intArrayOf(3, 8, -1, 0),
            intArrayOf(11, 1, Int.MAX_VALUE, Int.MAX_VALUE),
            intArrayOf(3, 8, 420, Int.MIN_VALUE),
            intArrayOf(11, 1, 420, 0),
        )
        dateCases.forEachIndexed { index, input ->
            val on = GregorianCalendar(TimeZone.getTimeZone("America/New_York")).apply {
                clear()
                set(2026, input[0] - 1, input[1], 12, 34, 42)
                set(Calendar.MILLISECOND, 123)
            }
            val wake = SleepPlanner.wakeDate(input[2], on)
            val bedtime = SleepPlanner.bedtime(wake, input[3])
            lines.add("dates$index:${wake.timeInMillis},${bedtime.timeInMillis},${on.timeInMillis}")
        }

        val firstFoldNow = GregorianCalendar(TimeZone.getTimeZone("America/New_York")).apply {
            timeInMillis = Instant.parse("2026-11-01T05:31:00Z").toEpochMilli()
        }
        val foldWake = SleepPlanner.wakeDate(90, firstFoldNow)
        val foldBedtime = SleepPlanner.bedtime(foldWake, 480)
        lines.add("foldCurrent:${foldWake.timeInMillis},${foldBedtime.timeInMillis},${firstFoldNow.timeInMillis}")

        val preferredCalendarCases = listOf(
            Triple("2024-02-29T05:30:00Z", "2024-02-29T05:29:00Z", "America/New_York"),
            Triple("2024-02-29T05:30:00Z", "2024-02-29T05:30:00Z", "America/New_York"),
            Triple("2024-02-29T05:30:00Z", "2024-02-29T05:31:00Z", "America/New_York"),
            Triple("2024-03-01T02:30:00Z", "2024-03-01T02:29:00Z", "America/New_York"),
            Triple("2024-02-28T17:05:00Z", "2024-02-28T17:04:00Z", "Asia/Bangkok"),
            Triple("2026-11-01T06:30:00Z", "2026-11-01T05:31:00Z", "America/New_York"),
            Triple("2026-03-08T07:30:00Z", "2026-03-08T07:29:00Z", "America/New_York"),
        )
        preferredCalendarCases.forEachIndexed { index, input ->
            val preferred = Calendar.getInstance(TimeZone.getTimeZone(input.third), Locale.forLanguageTag("th-TH"))
            val wake = (preferred.clone() as Calendar).apply {
                timeInMillis = Instant.parse(input.first).toEpochMilli()
            }
            val now = (preferred.clone() as Calendar).apply {
                timeInMillis = Instant.parse(input.second).toEpochMilli()
            }
            val key = PlannerAlarmPolicy.occurrenceKey(wake)
            val pending = PlannerAlarmPolicy.isSkipPending(key, now)
            lines.add("preferred$index:${wake.get(Calendar.YEAR)},$key,$pending,${wake.timeInMillis},${now.timeInMillis}")
        }

        val occurrenceCases = listOf(
            intArrayOf(2026, 10, 2, 420),
            intArrayOf(2026, 1, 3, 0),
            intArrayOf(2028, 2, 29, 1439),
            intArrayOf(2026, 12, 31, 420),
            intArrayOf(2027, 1, 1, 420),
            intArrayOf(9, 1, 2, 5),
            intArrayOf(2026, 10, 2, 421),
        )
        occurrenceCases.forEachIndexed { index, input ->
            lines.add("occurrence$index:${PlannerAlarmPolicy.occurrenceKey(input[0], input[1], input[2], input[3])}")
        }

        val expected = """
            plan0:480,0,480,1380,-1,1350,-1,420,false,false
            plan1:540,60,459,1401,-1,1371,-1,420,true,true
            plan2:600,120,420,0,0,1395,-1,420,true,true
            plan3:539,59,459,1401,-1,1371,-1,420,true,false
            plan4:540,60,459,1401,-1,1371,-1,420,false,false
            plan5:300,0,255,0,0,0,0,255,true,false
            plan6:301,0,256,0,0,0,0,256,true,false
            plan7:301,0,211,0,0,1439,-1,211,true,false
            plan8:302,0,212,1439,-1,1439,-1,211,true,false
            plan9:780,120,780,0,0,1320,-1,780,true,true
            plan10:780,120,780,120,0,0,0,900,true,true
            plan11:780,120,780,659,0,539,0,1439,true,true
            plan12:300,0,300,1140,-1,1140,-1,0,false,false
            plan13:300,0,300,1139,0,1019,0,1439,true,false
            plan14:300,0,300,1140,-1,1140,-1,0,false,false
            plan15:780,120,780,659,0,539,0,1439,true,true
            plan16:419,119,294,1146,-1,1026,-1,0,true,true
            plan17:780,120,663,776,0,776,0,1439,true,true
            plan18:480,1,408,0,0,1320,-1,408,true,false
            plan19:479,0,408,1439,-1,1439,-1,407,true,false
            plan20:480,0,336,0,0,0,0,336,true,false
            plan21:481,0,337,1439,-1,1439,-1,336,true,false
            plan22:540,60,540,1439,-1,1439,-1,539,true,true
            plan23:540,60,540,0,0,1439,-1,540,true,true
            plan24:540,60,540,1,0,0,0,541,true,true
            weekday0:85
            weekday1:70
            weekday2:70
            weekday3:100
            weekday4:85
            weekday5:85
            weekday6:100
            weekday7:100
            weekday8:100
            goals:100,85,70
            quiet0:false
            quiet1:true
            quiet2:true
            quiet3:false
            quiet4:true
            quiet5:true
            quiet6:false
            quiet7:false
            quiet8:true
            quiet9:false
            quiet10:false
            quiet11:false
            quiet12:true
            quiet13:true
            quiet14:false
            quiet15:false
            quiet16:true
            quiet17:false
            skip0:false,1790924400000
            skip1:true,1790985540000
            skip2:false,1790924400000
            skip3:true,1790924400000
            skip4:true,1790924400000
            skip5:false,1798761600000
            skip6:true,1798761540000
            skip7:true,1790905140000
            skip8:false,1790905200000
            skip9:false,1790924400000
            skip10:false,1790924400000
            skip11:false,1790924400000
            skip12:false,1790924400000
            skip13:false,1790924400000
            skip14:false,1790924400000
            skip15:false,1790924400000
            skip16:false,1790924400000
            skip17:false,1790924400000
            skip18:false,1790924400000
            skip19:false,1790924400000
            skip20:false,1790924400000
            skip21:true,1790899200000
            skip22:true,1790985540000
            skip23:false,1790924400000
            skip24:false,1790924400000
            skip25:false,1772262000000
            skip26:false,-2203952400000
            skip27:true,951721200000
            skip28:false,1798700400000
            skip29:false,1790924400000
            skip30:false,1790924401000
            skip31:false,1790924459000
            skip32:true,1793511060000
            skip33:true,1793514600000
            skip34:false,1793514660000
            skip35:true,1793511000000
            dates0:1772955000000,1772926200000,1772987682123
            dates1:1793514600000,1793485800000,1793554482123
            dates2:1772967600000,1772938800000,1772987682123
            dates3:1793534400000,1793505600000,1793554482123
            dates4:1772946000000,1772917200000,1772987682123
            dates5:1793505600000,1793476800000,1793554482123
            dates6:1772946000000,1772946000000,1772987682123
            dates7:1793595540000,1793548740000,1793554482123
            dates8:1772967600000,1772967600000,1772987682123
            dates9:1793534400000,1793534400000,1793554482123
            foldCurrent:1793514600000,1793485800000,1793511060000
            preferred0:2567,2024-02-29|30,true,1709184600000,1709184540000
            preferred1:2567,2024-02-29|30,true,1709184600000,1709184600000
            preferred2:2567,2024-02-29|30,false,1709184600000,1709184660000
            preferred3:2567,2024-02-29|1290,true,1709260200000,1709260140000
            preferred4:2567,2024-02-29|5,true,1709139900000,1709139840000
            preferred5:2569,2026-11-01|90,true,1793514600000,1793511060000
            preferred6:2569,2026-03-08|210,true,1772955000000,1772954940000
            occurrence0:2026-10-02|420
            occurrence1:2026-01-03|0
            occurrence2:2028-02-29|1439
            occurrence3:2026-12-31|420
            occurrence4:2027-01-01|420
            occurrence5:0009-01-02|5
            occurrence6:2026-10-02|421
        """.trimIndent()
        assertEquals(expected, lines.joinToString("\n"))
    }
}
