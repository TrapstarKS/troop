package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

class PlannerAdviceLedgerTest {
    @Test fun adviceLedgerMatchesStandaloneSwiftOracle() {
        val first = "2026-10-03|420"
        val second = "2026-10-04|420"
        val lines = mutableListOf<String>()
        val queues = listOf(
            mapOf(second to 2000L, first to 1000L, "bad" to 99L, "2026-02-30|420" to 100L, "2026-10-03|0420" to 100L, "2026-10-05|420" to 0L),
            mapOf("2000-02-29|0" to 1L, "2026-10-03|1439" to Long.MAX_VALUE, "2026-10-03|1" to -1L),
        )
        queues.forEachIndexed { index, input -> lines.add("queue$index:${PlannerAlarmPolicy.adviceQueue(input).replace("\n", "/")}") }
        data class Case(val queued: String, val previous: String, val now: Long, val day: String)
        val cases = listOf(
            Case("$first=1000", "", 999, "2026-10-03"),
            Case("$first=1000", "", 1000, "2026-10-03"),
            Case("$second=2000", first, 2000, "2026-10-03"),
            Case("$second=2000", first, 2000, "2026-10-04"),
            Case("", "bad\n$second\n$first\n$first", 2000, "2026-10-03"),
            Case("$first=+1000\n$first=01000\n$first=1000 \n$first=0\n$first=-1\n$first=1000=2\n$first=9223372036854775808", "", 2000, "2026-10-03"),
            Case("$first=1000\n$first=1000", first, 2000, "2026-10-03"),
            Case("$first=9223372036854775807", "", Long.MAX_VALUE, "2026-10-03"),
            Case("$first=1", first, Long.MIN_VALUE, "2026-10-03"),
            Case("$first=1000\n$second=2000", first, 2000, "2026-10-05"),
        )
        cases.forEachIndexed { index, input ->
            lines.add("handled$index:${PlannerAlarmPolicy.handledAdvice(input.queued, input.previous, input.now, input.day).replace("\n", "/")}")
        }
        val expected = """
            queue0:2026-10-03|420=1000/2026-10-04|420=2000
            queue1:2000-02-29|0=1/2026-10-03|1439=9223372036854775807
            handled0:
            handled1:2026-10-03|420
            handled2:2026-10-03|420/2026-10-04|420
            handled3:2026-10-04|420
            handled4:2026-10-03|420/2026-10-04|420
            handled5:
            handled6:2026-10-03|420
            handled7:2026-10-03|420
            handled8:2026-10-03|420
            handled9:
        """.trimIndent()
        assertEquals(expected, lines.joinToString("\n"))
    }
}
