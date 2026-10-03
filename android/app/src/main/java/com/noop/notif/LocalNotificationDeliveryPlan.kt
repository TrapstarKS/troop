package com.noop.notif

enum class LocalNotificationReportGroup(val families: List<String>) {
    NIGHT(listOf("dailyOutlook", "morningRecap", "sleepReady", "recoveryReady")),
    EVENING(listOf("dayInReview", "strainReady", "streakSummary")),
}

data class LocalNotificationDeliveryPlan(val primaryFamily: String, val coveredFamilies: List<String>) {
    companion object {
        /** Delivered families belong to this recorded event. Persist coverage only after delivery succeeds. */
        fun resolve(
            group: LocalNotificationReportGroup,
            availableFamilies: Set<String>,
            enabledFamilies: Set<String>,
            deliveredFamilies: Set<String>,
        ): LocalNotificationDeliveryPlan? {
            if (group.families.any(deliveredFamilies::contains)) return null
            val primary = group.families.firstOrNull { it in availableFamilies && it in enabledFamilies } ?: return null
            return LocalNotificationDeliveryPlan(primary, group.families)
        }

        fun deliver(
            group: LocalNotificationReportGroup,
            availableFamilies: Set<String>,
            enabledFamilies: Set<String>,
            deliveredFamilies: Set<String>,
            authorized: Boolean,
            quiet: Boolean,
            occurrenceSec: Long?,
            nowSec: Long,
            submit: (LocalNotificationDeliveryPlan) -> Boolean,
        ): LocalNotificationDeliveryPlan? {
            val plan = resolve(group, availableFamilies, enabledFamilies, deliveredFamilies) ?: return null
            if (!LocalNotificationPolicy.shouldDeliver(true, authorized, quiet, plan.primaryFamily, null,
                    occurrenceSec, nowSec) || !submit(plan)) return null
            return plan
        }
    }
}
