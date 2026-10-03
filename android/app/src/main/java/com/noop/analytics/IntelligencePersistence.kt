package com.noop.analytics

import com.noop.data.DailyMetric
import com.noop.data.MetricSeriesRow
import com.noop.data.ScoreInputProvenanceRow
import com.noop.data.SleepSession
import com.noop.data.WhoopRepository

/** Persistence-only helpers kept out of the already large scoring orchestrator. */
internal object IntelligencePersistence {
    fun historyRepairIsPending(effortDone: Boolean, sleepWearDone: Boolean): Boolean =
        !effortDone || !sleepWearDone

    data class LegacyScoreSnapshot(
        val avgHrv: Double,
        val recovery: Double?,
        val respRateBpm: Double?,
        val avgSdnn: Double?,
        val recoverySource: String?,
    )

    data class LegacyScoreClock(
        val nowLocalMidnight: Long,
        val nowSeconds: Long,
        val tzOffsetSeconds: Long,
    )

    data class ComputedWindow(
        val deviceId: String,
        val from: String,
        val to: String,
        val dailies: List<DailyMetric>,
        val metricRows: List<MetricSeriesRow>,
        val provenance: List<ScoreInputProvenanceRow>,
        val markerSourceIds: List<String>,
    )

    /** Keep the per-day repair loop outside the instrumented scoring coroutine's bytecode budget. */
    suspend fun persistComputedWindow(repo: WhoopRepository, window: ComputedWindow, preserveUnscoredHistory: Boolean) {
        val windows = if (preserveUnscoredHistory) byScoredDay(window) else listOf(window)
        for (part in windows) part.persistInto(repo)
    }

    private suspend fun ComputedWindow.persistInto(repo: WhoopRepository) =
        repo.replaceComputedScoreWindow(this)

    /** Per-day repair writes leave older, unscorable cached days and their provenance intact. */
    fun byScoredDay(window: ComputedWindow): List<ComputedWindow> {
        val dailiesByDay = window.dailies.groupBy { it.day }
        val metricsByDay = window.metricRows.groupBy { it.day }
        val provenanceByDay = window.provenance.groupBy { it.day }
        return dailiesByDay.keys.sorted().map { day ->
            window.copy(from = day, to = day,
                dailies = dailiesByDay.getValue(day),
                metricRows = metricsByDay[day].orEmpty(),
                provenance = provenanceByDay[day].orEmpty())
        }
    }

    /** Source-only imported recovery fold kept outside the instrumented scoring coroutine. */
    suspend fun appendImportedRecoveryDays(
        repo: WhoopRepository,
        importedDeviceId: String,
        computedId: String,
        oldestDay: String,
        newestDay: String,
        dailies: MutableList<DailyMetric>,
        restRows: MutableList<MetricSeriesRow>,
        out: MutableList<IntelligenceEngine.Computed>,
        resolvedScoreOwnerByDay: MutableMap<String, String>,
        wearableImportSources: List<String>,
    ) {
        // ── Source-only Charge/Rest fold for imported-only days (#823) ──────────────────────────────────
        // A user who ONLY imports (Health Connect, or an Oura/Fitbit/Garmin export, or Apple Health) has
        // DAILY aggregates (HRV + resting HR) but no raw HR stream, so the raw-HR scoring loop above never
        // touched their days and the import left recovery null , Today/Recovery show a blank Charge. Score it
        // from the daily aggregate vs the person's own baseline with the [IntelligenceEngine.watchRecoveries] engine (which
        // reuses RecoveryScorer.recovery verbatim), then write the score under the COMPUTED ("-noop") source
        // so it merges onto Today exactly like a live day. The imported daily row keeps its raw values
        // untouched; the computed row carries the NOOP-derived Charge + the Rest composite. HONEST DATA: the
        // engine returns null + calibrating until the HRV baseline is usable, so an import-only day stays
        // calibrating rather than faking a number. Strap/WHOOP-import days keep winning , we skip any day
        // already scored this pass. Health Connect writes its DailyMetric rows under the strap source
        // ("my-whoop"), so importedDeviceId is included; a row already carrying its OWN recovery is left
        // alone. Mirrors the Swift fold.
        val importScoredDays = HashSet<String>().apply { addAll(dailies.map { it.day }) }
        val healthConnectDays = repo.appleDaily(
            WhoopRepository.HEALTH_CONNECT_SOURCE,
            oldestDay,
            newestDay,
        ).mapTo(HashSet()) { it.day }
        val importSourceIds = buildList {
            add(importedDeviceId) // Health Connect imports its DailyMetric rows under the strap source.
            add(WhoopRepository.APPLE_HEALTH_SOURCE)
            add(WhoopRepository.HEALTH_CONNECT_SOURCE)
            addAll(wearableImportSources)
        }.distinct()
        for (source in importSourceIds) {
            val rows = repo.dailyMetrics(source, oldestDay, newestDay)
            // A real export that already carries its OWN recovery WINS , never overwrite a verbatim imported
            // score; those days also pre-claim the slot so the fold doesn't re-score them.
            val byDay = rows.associateBy { it.day }
            for (r in rows) if (r.recovery != null) importScoredDays.add(r.day)
            for (w in IntelligenceEngine.watchRecoveries(rows, importScoredDays)) {
                val recovery = w.recovery ?: continue
                val row = byDay[w.day] ?: continue
                val scored = row.copy(deviceId = computedId, recovery = recovery)
                dailies.add(scored)
                restRows.add(MetricSeriesRow(computedId, w.day, "hrv_fresh_scoring_valid", 0.0))
                importScoredDays.add(w.day)
                // Health Connect's compatibility DailyMetric row lives under `my-whoop`, while its
                // AppleDaily row retains the real source. Preserve that provider fact without changing
                // ingestion or score precedence.
                resolvedScoreOwnerByDay[w.day] =
                    if (source == importedDeviceId && w.day in healthConnectDays) {
                        WhoopRepository.HEALTH_CONNECT_SOURCE
                    } else {
                        source
                    }
                RestScorer.restFromDaily(scored)?.let { rest ->
                    restRows.add(MetricSeriesRow(deviceId = computedId, day = w.day, key = "sleep_performance", value = rest))
                }
                out.add(
                    IntelligenceEngine.Computed(
                        day = w.day,
                        recovery = recovery,
                        strain = scored.strain,
                        sleepMin = scored.totalSleepMin,
                        hrv = scored.avgHrv,
                        rhr = scored.restingHr,
                    ),
                )
            }
        }
    }

    suspend fun prepareComputedWindow(
        repo: WhoopRepository,
        importedDeviceId: String,
        computedId: String,
        from: String,
        to: String,
        dailies: List<DailyMetric>,
        metricRows: List<MetricSeriesRow>,
        cycle: PhysiologicalStepCycleEngine.Result,
        candidatePriorities: List<Pair<String, Int>>,
        ownerByDay: Map<String, String>,
        legacyClock: LegacyScoreClock,
        computed: MutableList<IntelligenceEngine.Computed>,
    ): ComputedWindow {
        val mutableDailies = dailies.toMutableList()
        val snapshots = preserveLegacyScores(
            repo, computedId, from, to, mutableDailies, ownerByDay,
            legacyClock,
        )
        applyLegacyScoresToOutput(computed, snapshots)
        val provenance = scoreProvenance(computedId, mutableDailies, metricRows, ownerByDay, snapshots)
        return ComputedWindow(
            deviceId = computedId,
            from = from,
            to = to,
            dailies = mutableDailies,
            metricRows = (metricRows + cycle.recoveredOwnerMarkerRows)
                .distinctBy { Triple(it.deviceId, it.day, it.key) },
            provenance = provenance,
            markerSourceIds = DayCycleIntelligenceIntegration.markerRewriteSourceIds(
                repo.computedSourceIds(importedDeviceId),
                candidatePriorities.map { (owner, _) -> repo.computedDeviceId(owner) },
            ),
        )
    }

    fun scoreProvenance(
        computedId: String,
        dailies: List<DailyMetric>,
        metricRows: List<MetricSeriesRow>,
        ownerByDay: Map<String, String>,
        legacySnapshots: Map<String, LegacyScoreSnapshot> = emptyMap(),
    ): List<ScoreInputProvenanceRow> {
        val byCell = LinkedHashMap<Pair<String, String>, ScoreInputProvenanceRow>()
        for (daily in dailies) {
            val source = ownerByDay[daily.day] ?: continue
            if (daily.recovery != null) {
                val legacy = legacySnapshots[daily.day]
                val recoverySource = legacy?.recoverySource ?: if (legacy == null) source else null
                if (recoverySource != null) {
                    byCell[daily.day to "recovery"] = ScoreInputProvenanceRow(
                        computedId, daily.day, "recovery", recoverySource,
                    )
                }
            }
            if (daily.strain != null) {
                byCell[daily.day to "strain"] = ScoreInputProvenanceRow(
                    computedId, daily.day, "strain", source,
                )
            }
        }
        for (point in metricRows) {
            val source = ownerByDay[point.day] ?: continue
            byCell[point.day to point.key] = ScoreInputProvenanceRow(
                computedId, point.day, point.key, source,
            )
        }
        return byCell.values.toList()
    }

    private suspend fun preserveLegacyScores(
        repo: WhoopRepository,
        computedId: String,
        from: String,
        to: String,
        dailies: MutableList<DailyMetric>,
        ownerByDay: Map<String, String>,
        clock: LegacyScoreClock,
    ): Map<String, LegacyScoreSnapshot> {
        val existingByDay = repo.dailyMetrics(computedId, from, to).associateBy { it.day }
        val snapshots = LinkedHashMap<String, LegacyScoreSnapshot>()
        for (index in dailies.indices) {
            val fresh = dailies[index]
            val existing = existingByDay[fresh.day] ?: continue
            val oldHrv = existing.avgHrv ?: continue
            if (fresh.avgHrv != null || (fresh.totalSleepMin ?: 0.0) <= 0.0) continue
            val owner = ownerByDay[fresh.day] ?: continue
            val dayStart = try {
                java.time.LocalDate.parse(fresh.day).toEpochDay() * 86_400L - clock.tzOffsetSeconds
            } catch (_: java.time.format.DateTimeParseException) { continue }
            val readFrom = dayStart - StreamReadCap.LOOKBACK_SECONDS
            val readTo = IntelligenceEngine.sleepReadWindowEnd(
                dayStart, clock.nowLocalMidnight, clock.nowSeconds,
            )
            if (!repo.legacyWhoop5RrWithheld(owner, readFrom, readTo)) continue
            val snapshot = LegacyScoreSnapshot(
                avgHrv = oldHrv,
                recovery = existing.recovery,
                respRateBpm = existing.respRateBpm,
                avgSdnn = existing.avgSdnn,
                recoverySource = repo.scoreInputSource(computedId, fresh.day, "recovery"),
            )
            dailies[index] = fresh.copy(
                avgHrv = snapshot.avgHrv,
                recovery = snapshot.recovery,
                respRateBpm = snapshot.respRateBpm,
                avgSdnn = snapshot.avgSdnn,
            )
            snapshots[fresh.day] = snapshot
        }
        return snapshots
    }

    private fun applyLegacyScoresToOutput(
        computed: MutableList<IntelligenceEngine.Computed>,
        snapshots: Map<String, LegacyScoreSnapshot>,
    ) {
        for (index in computed.indices) {
            val snapshot = snapshots[computed[index].day] ?: continue
            computed[index] = computed[index].copy(hrv = snapshot.avgHrv, recovery = snapshot.recovery)
        }
    }

    suspend fun persistDetectedSleepDetails(
        repo: WhoopRepository,
        computedId: String,
        kept: List<SleepSession>,
        scoredNights: List<DayResult>,
    ) {
        // Only kept (not edited/dismissed) sessions receive their per-epoch motion and band-state arrays.
        // Missing streams remain absent rather than being materialised as synthetic zero arrays.
        if (kept.isNotEmpty()) repo.upsertSleepSessions(kept)
        val keptStarts = kept.map { it.startTs }.toHashSet()
        val motionByStart = HashMap<Long, List<Double>>()
        val sleepStateByStart = HashMap<Long, List<Int>>()
        for (result in scoredNights) {
            for ((start, motion) in result.sessionMotionByStart) {
                if (start in keptStarts) motionByStart[start] = motion
            }
            for ((start, states) in result.sessionSleepStateByStart) {
                if (start in keptStarts) sleepStateByStart[start] = states
            }
        }
        for ((start, motion) in motionByStart) repo.persistSessionMotion(computedId, start, motion)
        for ((start, states) in sleepStateByStart) repo.persistSessionSleepState(computedId, start, states)
    }
}

/** Keep the oversized scoring orchestrator at a single, auditable transactional call site. */
internal suspend fun WhoopRepository.replaceComputedScoreWindow(
    window: IntelligencePersistence.ComputedWindow,
) = replaceComputedScoreWindow(
    deviceId = window.deviceId,
    from = window.from,
    to = window.to,
    dailyMetrics = window.dailies,
    metricPoints = window.metricRows,
    provenance = window.provenance,
    replaceMetricKeys = listOf(DayCycleIntelligenceIntegration.ONSET_KEY),
    replaceMetricSourceIds = window.markerSourceIds,
)
