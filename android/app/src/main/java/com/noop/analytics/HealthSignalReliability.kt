package com.noop.analytics

/** Eligibility for local wellness alerts; retained computed HRV is not evidence from the fresh scan. */
object HealthSignalReliability {
    data class Record(val value: Double, val eligible: Boolean) {
        /** Swift twin: `HealthSignalReliability.Record.matches`. */
        fun matches(currentValue: Double?): Boolean = eligible && value.isFinite() && currentValue == value
    }

    /** Swift twin: `HealthSignalReliability.firstRecord`. */
    fun firstRecord(sourceIds: List<String>, bySource: Map<String, Record>): Record? =
        sourceIds.firstNotNullOfOrNull { bySource[it] }

    /** Swift twin: `HealthSignalReliability.respiration`. */
    fun respiration(value: Double?, computed: Boolean, freshScoringValid: Double? = null): Double? {
        if (value == null || !value.isFinite() || value < Baselines.respCfg.minVal || value > Baselines.respCfg.maxVal) return null
        if (computed && (freshScoringValid == null || !freshScoringValid.isFinite() || freshScoringValid < 0.5)) return null
        return value
    }

    /** Swift twin: `HealthSignalReliability.hrv`. */
    fun hrv(value: Double?, computed: Boolean,
            freshScoringValid: Double? = null, overcount: Double? = null): Double? {
        if (value == null || !value.isFinite() || value < Baselines.hrvCfg.minVal ||
            value > Baselines.hrvCfg.maxVal) return null
        if (computed && (freshScoringValid == null || !freshScoringValid.isFinite() ||
                freshScoringValid < 0.5 || overcount?.let { !it.isFinite() || it >= 0.5 } == true)) return null
        return value
    }

    /** Chronological civil-day keys, including missing nights, independent of the current time zone.
     * Swift twin: `HealthSignalReliability.dayKeys`. */
    fun dayKeys(ending: String, count: Int, daysAgo: Int = 0, baselineEpoch: Double = 0.0): List<String> {
        if (count <= 0) return emptyList()
        val end = runCatching { java.time.LocalDate.parse(ending) }.getOrNull() ?: return emptyList()
        if (end.toString() != ending) return emptyList()
        return (0 until count).map { end.plusDays((it - count + 1 - daysAgo).toLong()) }
            .filter { baselineEpoch <= 0.0 || it.atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond() >= baselineEpoch }
            .map { it.toString() }
    }
}
