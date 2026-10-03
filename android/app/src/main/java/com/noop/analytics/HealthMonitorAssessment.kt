package com.noop.analytics

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Display-only assessment against a trusted personal baseline. No population-range fallback. */
object HealthMonitorAssessment {
    enum class Status(val raw: String) {
        UNAVAILABLE("unavailable"), UNVERIFIED("unverified"), CALIBRATING("calibrating"),
        WITHIN_RANGE("withinRange"), OUTSIDE_RANGE("outsideRange"), FAR_OUTSIDE_RANGE("farOutsideRange"),
    }

    data class Result(
        val status: Status,
        val lower: Double? = null,
        val upper: Double? = null,
        val nights: Int = 0,
    )

    /** Local display configuration; does not change recovery scoring or stored values. */
    val bloodOxygenCfg: MetricCfg
        get() = MetricCfg(minVal = 70.0, maxVal = 100.0, floorSpread = 0.5, halfLifeB = 14.0, halfLifeS = 21.0)

    /** History is oldest first, excludes the displayed day, and includes missing calendar nights.
     * Swift twin: `HealthMonitorAssessment.assess`. */
    fun assess(value: Double?, history: List<Double?>, cfg: MetricCfg, verified: Boolean = true): Result {
        if (value == null || !value.isFinite()) return Result(Status.UNAVAILABLE)
        val finiteHistory = history.map { it?.takeIf { night -> night.isFinite() } }
        val state = Baselines.foldHistory(finiteHistory, cfg)
        if (!verified || !(cfg.minVal <= value && value <= cfg.maxVal)) {
            return Result(Status.UNVERIFIED, nights = state.nValid)
        }
        if (!state.trusted) return Result(Status.CALIBRATING, nights = state.nValid)

        val width = VitalBands.sigmaK * Baselines.sigma(state)
        val lower = max(cfg.minVal, state.baseline - width)
        val upper = min(cfg.maxVal, state.baseline + width)
        val z = abs(Baselines.deviation(value, state).z)
        val status = when {
            z <= VitalBands.sigmaK -> Status.WITHIN_RANGE
            z <= 3.0 -> Status.OUTSIDE_RANGE
            else -> Status.FAR_OUTSIDE_RANGE
        }
        return Result(status, lower, upper, state.nValid)
    }
}
