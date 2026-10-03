package com.noop.data

import com.noop.analytics.HealthSignalReliability
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** Keeps HRV field provenance attached until alert eligibility is decided. */
object IllnessHistory {
    data class Snapshot(
        val days: List<DailyMetric>,
        val alertDays: List<DailyMetric>,
        val hrvReliabilityByDay: Map<String, HealthSignalReliability.Record>,
        val activeId: String = "",
    ) {
        fun isCurrent(currentDays: List<DailyMetric>, currentActiveId: String): Boolean =
            days == currentDays && activeId == currentActiveId
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(repository: WhoopRepository, windows: Flow<Pair<String, List<DailyMetric>>>): Flow<Snapshot?> =
        windows.flatMapLatest { (activeId, days) ->
            flow(repository, activeId, days).map<Snapshot, Snapshot?> { it }
                .onStart { emit(null) }.catch { emit(null) }
        }

    fun flow(repository: WhoopRepository, activeId: String, days: List<DailyMetric>): Flow<Snapshot> {
        val from = days.firstOrNull()?.day ?: return flowOf(Snapshot(days, days, emptyMap(), activeId))
        val to = days.last().day
        val importedIds = repository.importedSourceIds(activeId)
        val computedIds = repository.computedSourceIds(activeId)
        return repository.hrvProvenanceFlow(activeId, from, to).map { rows ->
            resolve(days, rows, importedIds + computedIds, computedIds, activeId)
        }
    }

    internal fun resolve(days: List<DailyMetric>, rows: List<HrvProvenanceRow>,
                         sourceIds: List<String>, computedIds: List<String>, activeId: String = ""): Snapshot {
        val records = rows.groupBy { it.day }.mapValues { (_, dayRows) ->
            HealthSignalReliability.firstRecord(sourceIds, dayRows.associate { row ->
                row.deviceId to HealthSignalReliability.Record(row.value,
                    HealthSignalReliability.hrv(row.value, row.deviceId in computedIds,
                        row.freshScoringValid, row.overcount) != null)
            })
        }
        val reliability = LinkedHashMap<String, HealthSignalReliability.Record>()
        val eligibleDays = days.map { row ->
            val record = records[row.day]
            val hrv = row.avgHrv?.takeIf { record?.matches(it) == true }
            if (row.avgHrv != null) reliability[row.day] = record
                ?: HealthSignalReliability.Record(row.avgHrv, false)
            row.copy(avgHrv = hrv)
        }
        return Snapshot(days, eligibleDays, reliability, activeId)
    }
}
