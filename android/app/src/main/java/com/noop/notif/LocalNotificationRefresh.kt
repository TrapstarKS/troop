package com.noop.notif

import com.noop.data.DailyMetric

/** Latest request wins even when an older database read finishes after a newer one. */
class LocalNotificationRefresh {
    private var generation = 0L

    fun invalidate() { generation++ }

    suspend fun <T> run(read: suspend () -> T, isCurrent: () -> Boolean, publish: (T) -> Unit) {
        val request = ++generation
        val result = read()
        if (request == generation && isCurrent()) publish(result)
    }
}

/** Merged rows retain the imported id even when their missing fields came from computed data. */
internal fun hasImportedNotificationInputs(
    row: DailyMetric,
    imported: List<DailyMetric>,
    wakeSources: List<String>,
    importedSourceIds: List<String>,
    streak: Int,
    importedStreak: Int,
): Boolean {
    val original = imported.firstOrNull { it.day == row.day } ?: return false
    return original.recovery == row.recovery && original.totalSleepMin == row.totalSleepMin &&
        original.strain == row.strain && wakeSources.all { it in importedSourceIds } && streak == importedStreak
}

/** Raw computed candidates stay ordered by source: coalesced rows cannot identify a field's owner. */
internal fun notificationComputedSources(
    row: DailyMetric,
    imported: List<DailyMetric>,
    computedCandidates: List<DailyMetric>,
    wakeSources: List<String>,
    importedSourceIds: List<String>,
    streakRows: List<DailyMetric>,
): Set<String>? {
    val sources = HashSet<String>()
    fun addSource(id: String): Boolean {
        if (!id.endsWith("-noop")) return false
        sources.add(id.removeSuffix("-noop"))
        return true
    }
    fun field(metric: DailyMetric, value: (DailyMetric) -> Double?): Boolean {
        val actual = value(metric) ?: return true
        if (imported.firstOrNull { it.day == metric.day }?.let(value) == actual) return true
        val candidate = computedCandidates.firstOrNull { it.day == metric.day && value(it) != null }
        return candidate != null && value(candidate) == actual && addSource(candidate.deviceId)
    }
    if (!field(row) { it.recovery } || !field(row) { it.strain }) return null
    if (row.totalSleepMin != null) {
        val candidate = computedCandidates.firstOrNull { metric -> metric.day == row.day &&
            (metric.totalSleepMin != null || metric.efficiency != null || metric.deepMin != null ||
                metric.remMin != null || metric.lightMin != null || metric.disturbances != null)
        }
        // Edited sleep can override an import even when the displayed totals happen to match.
        if (candidate != null && candidate.totalSleepMin == row.totalSleepMin) {
            if (!addSource(candidate.deviceId)) return null
        } else if (imported.firstOrNull { it.day == row.day }?.totalSleepMin != row.totalSleepMin) return null
    }
    for (source in wakeSources) if (source !in importedSourceIds && !addSource(source)) return null
    for (metric in streakRows) if (!field(metric) { it.recovery }) return null
    return sources
}
