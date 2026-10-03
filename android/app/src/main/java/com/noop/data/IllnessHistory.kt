package com.noop.data

import com.noop.analytics.HealthSignalReliability
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** Keeps HRV and respiratory field provenance attached until alert eligibility is decided. */
object IllnessHistory {
    data class Snapshot(
        val days: List<DailyMetric>,
        val alertDays: List<DailyMetric>,
        val hrvReliabilityByDay: Map<String, HealthSignalReliability.Record>,
        val activeId: String = "",
        val respReliabilityByDay: Map<String, HealthSignalReliability.Record> = emptyMap(),
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
        fun records(respiration: Boolean): Map<String, HealthSignalReliability.Record?> =
            rows.groupBy { it.day }.mapValues { (_, dayRows) ->
                HealthSignalReliability.firstRecord(sourceIds, dayRows.mapNotNull { row ->
                    val value = (if (respiration) row.respValue else row.value) ?: return@mapNotNull null
                    val computed = row.deviceId in computedIds
                    val eligible = if (respiration) HealthSignalReliability.respiration(value, computed, row.respFreshScoringValid) != null
                        else HealthSignalReliability.hrv(value, computed, row.freshScoringValid, row.overcount) != null
                    row.deviceId to HealthSignalReliability.Record(value, eligible)
                }.toMap())
            }
        val hrvRecords = records(false)
        val respRecords = records(true)
        val hrvReliability = LinkedHashMap<String, HealthSignalReliability.Record>()
        val respReliability = LinkedHashMap<String, HealthSignalReliability.Record>()
        val eligibleDays = days.map { row ->
            val hrvRecord = hrvRecords[row.day]
            val respRecord = respRecords[row.day]
            val hrv = row.avgHrv?.takeIf { hrvRecord?.matches(it) == true }
            val resp = row.respRateBpm?.takeIf { respRecord?.matches(it) == true }
            if (row.avgHrv != null) hrvReliability[row.day] = hrvRecord
                ?: HealthSignalReliability.Record(row.avgHrv, false)
            if (row.respRateBpm != null) respReliability[row.day] = respRecord
                ?: HealthSignalReliability.Record(row.respRateBpm, false)
            row.copy(avgHrv = hrv, respRateBpm = resp)
        }
        return Snapshot(days, eligibleDays, hrvReliability, activeId, respReliability)
    }
}
