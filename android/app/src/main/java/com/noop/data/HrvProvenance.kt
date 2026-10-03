package com.noop.data

/** One physical source's HRV and fresh-scan evidence from one SQLite read snapshot. */
data class HrvProvenanceRow(
    val deviceId: String,
    val day: String,
    val value: Double,
    val freshScoringValid: Double?,
    val overcount: Double?,
)

internal const val HRV_PROVENANCE_SQL =
    "SELECT d.deviceId, d.day, d.avgHrv AS value, fresh.value AS freshScoringValid, overcount.value AS overcount " +
        "FROM dailyMetric d " +
        "LEFT JOIN metricSeries fresh ON fresh.deviceId = d.deviceId AND fresh.day = d.day " +
        "AND fresh.key = 'hrv_fresh_scoring_valid' " +
        "LEFT JOIN metricSeries overcount ON overcount.deviceId = d.deviceId AND overcount.day = d.day " +
        "AND overcount.key = 'hrv_rr_overcount' " +
        "WHERE d.deviceId IN (:deviceIds) AND d.day >= :from AND d.day <= :to AND d.avgHrv IS NOT NULL " +
        "ORDER BY d.day, d.deviceId"
