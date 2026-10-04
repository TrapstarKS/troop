package com.noop.ui

internal data class SleepDisplayAmounts(
    val asleepMin: Double?, val needMin: Double?, val sufficiencyPct: Double?, val efficiencyPct: Double?,
) {
    companion object {
        // Presentation only: recorded stages own durations; scoring keeps its incumbent inputs.
        fun resolve(
            recordedAsleep: Double?, recordedTotal: Double?, dailyAsleep: Double?,
            dailySufficiencyPct: Double?, importedNeed: Double?, storedEfficiency: Double?,
        ): SleepDisplayAmounts {
            val recorded = recordedAsleep?.let { asleep ->
                recordedTotal?.takeIf { total -> total.isFinite() && total > 0.0 && asleep.isFinite() &&
                    asleep >= 0.0 && asleep <= total }?.let { total -> asleep to total }
            }
            val daily = dailyAsleep?.takeIf { it.isFinite() && it > 0.0 }
            val asleep = recorded?.first ?: daily
            val imported = importedNeed?.takeIf { it.isFinite() && it > 0.0 }
            val inferred = dailySufficiencyPct?.takeIf { it.isFinite() && it > 0.0 }
                ?.let { ratio -> daily?.let { it / ratio * 100.0 } }
                ?.takeIf { it.isFinite() && it > 0.0 }
            val need = imported ?: inferred
            val sufficiency = need?.let { need -> asleep?.let { it / need * 100.0 } }
            val stored = storedEfficiency?.takeIf { it.isFinite() }?.let { if (it <= 1.0) it * 100.0 else it }
            val efficiency = recorded?.let { it.first / it.second * 100.0 } ?: stored
            return SleepDisplayAmounts(asleep, need, sufficiency, efficiency?.coerceIn(0.0, 100.0))
        }
    }
}
