package com.noop.notif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNotificationPolicyTest {
    @Test fun matchesCompiledSwiftOracle() {
        val gates = buildString {
            for (enabled in listOf(false, true)) for (authorized in listOf(false, true)) for (quiet in listOf(false, true)) {
                append(if (LocalNotificationPolicy.shouldDeliver(enabled, authorized, quiet, "night", null, 1000, 1100)) "1" else "0")
            }
        }
        val events = buildString {
            for (key in listOf(null, "", "night")) for (occurrence in listOf(null, 1101L, -85301L, 0L, 1100L)) {
                append(if (LocalNotificationPolicy.shouldDeliver(true, true, false, key, null, occurrence, 1100)) "1" else "0")
            }
        }
        val quiet = buildString {
            for (minute in listOf(-1, 0, 419, 420, 1319, 1320, 1439, 1440)) {
                append(if (LocalNotificationPolicy.isQuiet(minute, 1320, 420, true)) "1" else "0")
            }
        }
        for (minute in 0 until 1440) {
            assertFalse(LocalNotificationPolicy.isQuiet(minute, 420, 420, true))
            assertFalse(LocalNotificationPolicy.isQuiet(minute, 420, 420, false))
        }
        val output = listOf(gates, events, quiet).joinToString("\n", postfix = "\n")
        assertEquals(SWIFT_ORACLE, output)
    }

    @Test fun recordedEventsExpireWithoutConsumingBlockedDelivery() {
        assertTrue(LocalNotificationPolicy.shouldDeliver(true, true, false, "sleepReady:2026-10-02", null, 0, 86400))
        assertFalse(LocalNotificationPolicy.shouldDeliver(true, true, false, "sleepReady:2026-10-02", null, 0, 86401))
        assertFalse(LocalNotificationPolicy.shouldDeliver(true, true, false, "sleepReady:2026-10-02", "sleepReady:2026-10-02", 1000, 1100))
        assertFalse(LocalNotificationPolicy.shouldDeliver(true, true, false, "night", null, Long.MIN_VALUE, Long.MAX_VALUE))
        assertFalse(LocalNotificationPolicy.shouldDeliver(true, true, false, "night", null, 0, -1))
    }

    private companion object {
        val SWIFT_ORACLE = "00000010\n000000000000011\n11100111\n"
    }
}
