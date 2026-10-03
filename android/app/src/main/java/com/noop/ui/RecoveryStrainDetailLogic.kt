package com.noop.ui

import kotlin.math.floor

internal object RecoveryStrainDetailLogic {
    enum class TargetStatus { Unavailable, Under, Optimal, Over }

    fun recoveryPercent(score: Double?): Int? =
        score?.takeIf { it.isFinite() && it in 0.0..100.0 }?.let { floor(it).toInt() }

    fun priorMean(
        dayKeys: List<String>,
        values: List<Double?>,
        fromDay: String,
        selectedDay: String,
    ): Double? {
        val prior = dayKeys.zip(values).mapNotNull { (day, value) ->
            value?.takeIf { day >= fromDay && day < selectedDay && it.isFinite() }
        }
        return prior.takeIf { it.isNotEmpty() }?.average()
    }

    fun targetStatus(displayedStrain: String?, lower: Int?, upper: Int?): TargetStatus {
        val strain21 = displayedStrain?.toDoubleOrNull()
        if (strain21 == null || !strain21.isFinite() || lower == null || upper == null || lower > upper) {
            return TargetStatus.Unavailable
        }
        return when {
            strain21 < lower -> TargetStatus.Under
            strain21 > upper -> TargetStatus.Over
            else -> TargetStatus.Optimal
        }
    }
}
