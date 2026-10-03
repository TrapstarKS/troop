package com.noop.notif

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [IllnessAlertPolicy], the pure once-per-local-day gate behind the illness
 * early-warning notification (CallAlertPolicy test idiom). The gate is persisted, so the two
 * call sites (app-open AppViewModel + background WhoopConnectionService) can never double-post.
 */
class IllnessAlertPolicyTest {

    @Test
    fun nullAlertNeverNotifies() {
        assertFalse(IllnessAlertPolicy.shouldNotify(null, false, null, "2026-06-10"))
        assertFalse(IllnessAlertPolicy.shouldNotify(null, false, "2026-06-09", "2026-06-10"))
    }

    @Test
    fun firstAlertOfTheDayNotifies() {
        assertTrue(IllnessAlertPolicy.shouldNotify("strained", false, null, "2026-06-10"))
        assertTrue(IllnessAlertPolicy.shouldNotify("strained", false, "2026-06-09", "2026-06-10"))
    }

    /** No stored previous evaluation on upgrade is unknown, not proof of a clear-to-raised edge. */
    @Test
    fun firstEvaluationOfAlreadyRaisedHistoryDoesNotNotify() {
        assertFalse(IllnessAlertPolicy.shouldNotify("strained", null, null, "2026-06-10"))
        assertFalse(IllnessAlertPolicy.shouldNotify("strained", null, "2026-06-09", "2026-06-10"))
    }

    @Test
    fun sameDayRepeatIsSuppressed() {
        assertFalse(IllnessAlertPolicy.shouldNotify("strained", false, "2026-06-10", "2026-06-10"))
    }

    /**
     * #2586. The reported shape: one bad night raises the banner, the two-day scoring window keeps it
     * raised into the next day, and the app is reopened. Before the fix the in-memory edge made that
     * look like a fresh transition and the new calendar day opened the once-a-day gate, so the wearer
     * was told again, about a night they had already been told about.
     */
    @Test
    fun anAlertThatWasAlreadyRaisedDoesNotNotifyAgainOnANewDay() {
        assertFalse(IllnessAlertPolicy.shouldNotify("strained", true, "2026-06-09", "2026-06-10"))
    }

    /** Still raised, same day: suppressed by both guards, and neither alone is relied on. */
    @Test
    fun anAlertAlreadyRaisedIsSuppressedOnTheSameDayToo() {
        assertFalse(IllnessAlertPolicy.shouldNotify("strained", true, "2026-06-10", "2026-06-10"))
    }

    /**
     * Clearing re-arms it. Without this the fix would be a one-shot: a wearer who recovered and then
     * genuinely declined again weeks later would never be told, which is worse than the bug.
     */
    @Test
    fun aClearedThenRaisedAlertNotifiesAgain() {
        assertFalse(IllnessAlertPolicy.shouldNotify(null, true, "2026-06-09", "2026-06-10"))
        assertTrue(IllnessAlertPolicy.shouldNotify("strained", false, "2026-06-09", "2026-06-10"))
    }

    @Test
    fun matchesStandaloneSwiftDurableEdgeOracle() {
        val actual = buildString {
            for (alert in listOf(null, "strained")) for (previous in listOf(null, false, true))
                for (last in listOf(null, "2026-06-09", "2026-06-10"))
                    append(if (IllnessAlertPolicy.shouldNotify(alert, previous, last, "2026-06-10")) '1' else '0')
        }
        org.junit.Assert.assertEquals("000000000000110000", actual)
    }
    @Test
    fun optOutAndLoadingPreserveRaisedStateUntilAValidClear() {
        var previous: Boolean? = true
        var notifications = 0
        val events = listOf(Triple(false, true, null), Triple(true, false, null),
            Triple(true, true, "still raised"), Triple(true, true, null), Triple(true, true, "new raised"))
        for ((enabled, valid, alert) in events) {
            if (!IllnessAlertPolicy.shouldRecordEvaluation(enabled, valid)) continue
            if (IllnessAlertPolicy.shouldNotify(alert, previous, "2026-06-09", "2026-06-10")) notifications++
            previous = alert != null
        }
        org.junit.Assert.assertEquals(1, notifications)
        org.junit.Assert.assertEquals(true, previous)
    }
    @Test
    fun matchesStandaloneSwiftEvaluationRecordOracle() {
        val actual = buildString {
            for (enabled in listOf(false, true)) for (valid in listOf(false, true))
                append(if (IllnessAlertPolicy.shouldRecordEvaluation(enabled, valid)) '1' else '0')
        }
        org.junit.Assert.assertEquals("0001", actual)
    }
}
