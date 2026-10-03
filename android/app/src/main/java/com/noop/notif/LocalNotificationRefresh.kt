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
