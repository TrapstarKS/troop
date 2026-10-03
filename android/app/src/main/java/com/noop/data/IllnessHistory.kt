package com.noop.data

import com.noop.analytics.HealthSignalReliability
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/** Keeps HRV field provenance attached until alert eligibility is decided. */
object IllnessHistory {
    data class Snapshot(
        val days: List<DailyMetric>,
        val alertDays: List<DailyMetric>,
        val hrvReliabilityByDay: Map<String, Boolean>,
    )

    fun flow(repository: WhoopRepository, activeId: String, days: List<DailyMetric>): Flow<Snapshot> {
        val from = days.firstOrNull()?.day ?: return flowOf(Snapshot(days, days, emptyMap()))
        val to = days.last().day
        return combine(
            repository.importedDailyUnionFlow(activeId, from, to),
            repository.metricSeriesComputedUnionFlow(activeId, "hrv_fresh_scoring_valid", from, to),
            repository.metricSeriesComputedUnionFlow(activeId, "hrv_rr_overcount", from, to),
        ) { imported, fresh, overcount ->
            resolve(days, imported, fresh.associate { it.day to it.value }, overcount.associate { it.day to it.value })
        }
    }

    internal fun resolve(days: List<DailyMetric>, imported: List<DailyMetric>,
                         freshByDay: Map<String, Double>, overcountByDay: Map<String, Double>): Snapshot {
        val importedHrv = imported.associate { it.day to it.avgHrv }
        val reliability = LinkedHashMap<String, Boolean>()
        val eligibleDays = days.map { row ->
            val hrv = HealthSignalReliability.hrv(row.avgHrv,
                computed = importedHrv[row.day] == null || importedHrv[row.day] != row.avgHrv,
                freshScoringValid = freshByDay[row.day], overcount = overcountByDay[row.day])
            if (row.avgHrv != null) reliability[row.day] = hrv != null
            row.copy(avgHrv = hrv)
        }
        return Snapshot(days, eligibleDays, reliability)
    }
}
