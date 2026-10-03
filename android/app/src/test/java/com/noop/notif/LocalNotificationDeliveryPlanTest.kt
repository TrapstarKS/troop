package com.noop.notif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNotificationDeliveryPlanTest {
    @Test fun everyEnabledAndAvailableCombinationSubmitsOneHighestPriorityFamily() {
        for (group in LocalNotificationReportGroup.entries) {
            val families = group.families
            for (availableMask in 0 until (1 shl families.size)) {
                val available = familySet(families, availableMask)
                for (enabledMask in 0 until (1 shl families.size)) {
                    val enabled = familySet(families, enabledMask)
                    val submitted = mutableListOf<LocalNotificationDeliveryPlan>()
                    val plan = LocalNotificationDeliveryPlan.deliver(group, available, enabled, emptySet(), true, false,
                        1000, 1100) {
                            submitted.add(it)
                            true
                        }
                    assertEquals(families.firstOrNull { it in available && it in enabled }, plan?.primaryFamily)
                    assertEquals(if (plan == null) null else families, plan?.coveredFamilies)
                    assertEquals(plan?.let { listOf(it) } ?: emptyList<LocalNotificationDeliveryPlan>(), submitted)
                }
            }
        }
        assertEquals(SWIFT_ORACLE, oracleOutput())
    }

    @Test fun anyDeliveredFamilySuppressesTheGroupDespiteChangedOptIns() {
        for (group in LocalNotificationReportGroup.entries) {
            val families = group.families
            for (deliveredMask in 1 until (1 shl families.size)) {
                for (enabledMask in 0 until (1 shl families.size)) {
                    assertNull(LocalNotificationDeliveryPlan.deliver(group, families.toSet(),
                        familySet(families, enabledMask), familySet(families, deliveredMask), true, false, 1000, 1100) {
                            throw AssertionError("A covered event must not be submitted")
                        })
                }
            }
            assertEquals(families.first(), LocalNotificationDeliveryPlan.resolve(group, families.toSet(),
                families.toSet(), setOf("workoutReady"))?.primaryFamily)
        }
    }

    @Test fun deniedQuietAndFailedAttemptsDoNotConsumeCoverageBeforeCatchUp() {
        for (group in LocalNotificationReportGroup.entries) {
            val families = group.families.toSet()
            val simulation = DeliverySimulation()
            assertNull(simulation.dispatch(group, "2026-10-02", families, families, authorized = false))
            assertTrue(simulation.markers.isEmpty())
            assertEquals(0, simulation.submissions)
            assertNull(simulation.dispatch(group, "2026-10-02", families, families, quiet = true))
            assertTrue(simulation.markers.isEmpty())
            assertEquals(0, simulation.submissions)
            assertNull(simulation.dispatch(group, "2026-10-02", families, families, accepted = false))
            assertTrue(simulation.markers.isEmpty())
            assertEquals(1, simulation.submissions)
            assertEquals(group.families.first(), simulation.dispatch(group, "2026-10-02", families, families, now = 1300))
            assertEquals(2, simulation.submissions)
            assertEquals(group.families.associateWith { "$it:2026-10-02" }, simulation.markers)
            val restarted = DeliverySimulation(simulation.markers.toMutableMap())
            for (enabledMask in 0 until (1 shl group.families.size)) {
                assertNull(restarted.dispatch(group, "2026-10-02", families, familySet(group.families, enabledMask)))
            }
            assertEquals(0, restarted.submissions)
            val previousMarkers = restarted.markers.toMap()
            assertNull(restarted.dispatch(group, "2026-10-03", families, families, accepted = false))
            assertEquals(previousMarkers, restarted.markers)
            assertEquals(1, restarted.submissions)
            assertEquals(group.families.first(), restarted.dispatch(group, "2026-10-03", families, families))
            assertEquals(2, restarted.submissions)
        }
    }

    @Test fun legacyGranularMarkerPreventsRicherRepeatAndGroupsRemainIndependent() {
        val simulation = DeliverySimulation(mutableMapOf("recoveryReady" to "recoveryReady:2026-10-02"))
        assertNull(simulation.dispatch(LocalNotificationReportGroup.NIGHT, "2026-10-02",
            LocalNotificationReportGroup.NIGHT.families.toSet(), setOf("dailyOutlook")))
        assertEquals(0, simulation.submissions)
        assertEquals(mapOf("recoveryReady" to "recoveryReady:2026-10-02"), simulation.markers)
        assertEquals("dayInReview", simulation.dispatch(LocalNotificationReportGroup.EVENING, "2026-10-02",
            setOf("dayInReview", "streakSummary"), setOf("dayInReview", "streakSummary")))
        assertEquals(1, simulation.submissions)
        assertEquals("recoveryReady:2026-10-02", simulation.markers["recoveryReady"])
        assertEquals("morningRecap", simulation.dispatch(LocalNotificationReportGroup.NIGHT, "2026-10-03",
            setOf("morningRecap", "recoveryReady"), setOf("morningRecap", "recoveryReady")))
        assertEquals(2, simulation.submissions)
    }

    @Test fun futureExpiredAndMissingOccurrencesNeverSubmit() {
        for (occurrence in listOf(null, -1L, 1101L, 0L)) {
            assertNull(LocalNotificationDeliveryPlan.deliver(LocalNotificationReportGroup.NIGHT, setOf("dailyOutlook"),
                setOf("dailyOutlook"), emptySet(), true, false, occurrence, if (occurrence == 0L) 86401L else 1100L) {
                    throw AssertionError("An invalid or expired event must not be submitted")
                })
        }
    }

    private fun familySet(families: List<String>, mask: Int): Set<String> =
        families.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()

    private fun oracleOutput(): String = buildString {
        for (group in LocalNotificationReportGroup.entries) {
            val families = group.families
            val name = if (group == LocalNotificationReportGroup.NIGHT) "night" else "evening"
            append("$name|${families.joinToString(",")}\n")
            for (availableMask in 0 until (1 shl families.size)) {
                append("$availableMask|")
                for (enabledMask in 0 until (1 shl families.size)) {
                    val plan = LocalNotificationDeliveryPlan.resolve(group, familySet(families, availableMask),
                        familySet(families, enabledMask), emptySet())
                    append(plan?.let { families.indexOf(it.primaryFamily).toString() } ?: "-")
                }
                append('\n')
            }
        }
    }

    private companion object {
        val SWIFT_ORACLE = """
            night|dailyOutlook,morningRecap,sleepReady,recoveryReady
            0|----------------
            1|-0-0-0-0-0-0-0-0
            2|--11--11--11--11
            3|-010-010-010-010
            4|----2222----2222
            5|-0-02020-0-02020
            6|--112211--112211
            7|-0102010-0102010
            8|--------33333333
            9|-0-0-0-030303030
            10|--11--1133113311
            11|-010-01030103010
            12|----222233332222
            13|-0-0202030302020
            14|--11221133112211
            15|-010201030102010
            evening|dayInReview,strainReady,streakSummary
            0|--------
            1|-0-0-0-0
            2|--11--11
            3|-010-010
            4|----2222
            5|-0-02020
            6|--112211
            7|-0102010
        """.trimIndent() + "\n"
    }
}

private class DeliverySimulation(val markers: MutableMap<String, String> = mutableMapOf()) {
    var submissions = 0

    fun dispatch(group: LocalNotificationReportGroup, event: String, available: Set<String>, enabled: Set<String>,
                 authorized: Boolean = true, quiet: Boolean = false, accepted: Boolean = true, now: Long = 1100): String? {
        val delivered = group.families.filter { markers[it] == "$it:$event" }.toSet()
        val plan = LocalNotificationDeliveryPlan.deliver(group, available, enabled, delivered, authorized, quiet, 1000, now) {
            submissions += 1
            accepted
        } ?: return null
        for (family in plan.coveredFamilies) markers[family] = "$family:$event"
        return plan.primaryFamily
    }
}
