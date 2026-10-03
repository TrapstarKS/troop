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
        val vitalDays: List<DailyMetric> = days,
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
                    val metric = row.metric
                    val value = (if (metric == null) {
                        if (respiration) row.respValue else row.value
                    } else {
                        if (respiration) metric.respRateBpm else metric.avgHrv
                    }) ?: return@mapNotNull null
                    val computed = row.deviceId in computedIds
                    val eligible = if (respiration) HealthSignalReliability.respiration(value, computed, row.respFreshScoringValid) != null
                        else HealthSignalReliability.hrv(value, computed, row.freshScoringValid, row.overcount) != null
                    row.deviceId to HealthSignalReliability.Record(value, eligible)
                }.toMap())
            }
        val cachedByDay = days.associateBy { it.day }
        val physicalMetrics = rows.mapNotNull { it.metric }
        val vitalDays = if (rows.any { it.metric == null }) days else WhoopRepository.mergeActivityFileSteps(
            WhoopRepository.mergeDaily(
                imported = WhoopRepository.unionByDay(sourceIds.filterNot { it in computedIds }.map { id ->
                    physicalMetrics.filter { it.deviceId == id }
                }),
                computed = WhoopRepository.unionByDay(computedIds.map { id ->
                    physicalMetrics.filter { it.deviceId == id }
                }),
            ),
            physicalMetrics.filter { it.deviceId == WhoopRepository.ACTIVITY_FILE_SOURCE },
        ).map { row ->
            cachedByDay[row.day]?.let { cached ->
                row.copy(totalSleepMin = cached.totalSleepMin, efficiency = cached.efficiency,
                    deepMin = cached.deepMin, remMin = cached.remMin, lightMin = cached.lightMin,
                    disturbances = cached.disturbances)
            } ?: row
        }
        val hrvRecords = records(false)
        val respRecords = records(true)
        val hrvReliability = LinkedHashMap<String, HealthSignalReliability.Record>()
        val respReliability = LinkedHashMap<String, HealthSignalReliability.Record>()
        val eligibleDays = vitalDays.map { row ->
            val contextualRow = cachedByDay[row.day]?.copy(
                restingHr = row.restingHr, avgHrv = row.avgHrv, avgSdnn = row.avgSdnn,
                respRateBpm = row.respRateBpm, spo2Pct = row.spo2Pct,
                skinTempC = row.skinTempC, skinTempDevC = row.skinTempDevC, recovery = row.recovery,
            ) ?: row
            val hrvRecord = hrvRecords[row.day]
            val respRecord = respRecords[row.day]
            val hrv = row.avgHrv?.takeIf { hrvRecord?.matches(it) == true }
            val resp = row.respRateBpm?.takeIf { respRecord?.matches(it) == true }
            if (row.avgHrv != null) hrvReliability[row.day] = hrvRecord
                ?: HealthSignalReliability.Record(row.avgHrv, false)
            if (row.respRateBpm != null) respReliability[row.day] = respRecord
                ?: HealthSignalReliability.Record(row.respRateBpm, false)
            contextualRow.copy(avgHrv = hrv, respRateBpm = resp)
        }
        return Snapshot(days, eligibleDays, hrvReliability, activeId, respReliability, vitalDays)
    }
}
