package com.noop.analytics

import com.noop.data.DailyMetric
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Heart-rate variability.
 *
 * Ported verbatim from `AppModel.rmssd` in the hardware-verified Swift reference
 * (`Strand/App/AppModel.swift`). RMSSD = root-mean-square of successive R-R
 * interval differences (milliseconds in, milliseconds out).
 */
object Hrv {
    /**
     * Root mean square of successive differences over a list of R-R intervals (ms).
     *
     * Returns 0.0 when fewer than two intervals are available (matching the Swift
     * guard `rr.count >= 2`).
     */
    fun rmssd(rr: List<Int>): Double {
        if (rr.size < 2) return 0.0
        var sum = 0.0
        var n = 0
        for (i in 1 until rr.size) {
            val d = (rr[i] - rr[i - 1]).toDouble()
            sum += d * d
            n += 1
        }
        return if (n > 0) sqrt(sum / n.toDouble()) else 0.0
    }
}

/**
 * Heart-rate training zones.
 *
 * Ported from the zone ladder in `AppModel.coachZone` (`Strand/App/AppModel.swift`):
 * pct >= 0.9 → 5, >= 0.8 → 4, >= 0.7 → 3, >= 0.6 → 2, else 1.
 */
object Zones {
    /**
     * Zone (1..5) for a heart rate given an estimated maximum heart rate.
     *
     * Mirrors the Swift `pct = hr / maxHR` ladder. If [hrMax] is non-positive the
     * percentage is undefined, so we fall back to the lowest zone.
     */
    fun zone(hr: Int, hrMax: Int): Int {
        if (hrMax <= 0) return 1
        val pct = hr.toDouble() / hrMax.toDouble()
        return when {
            pct >= 0.9 -> 5
            pct >= 0.8 -> 4
            pct >= 0.7 -> 3
            pct >= 0.6 -> 2
            else -> 1
        }
    }

    /**
     * Tanaka maximum-heart-rate estimate: round(208 - 0.7 * age).
     */
    fun hrMaxTanaka(age: Int): Int = (208.0 - 0.7 * age).roundToInt()
}

/**
 * Illness / strain early-warning.
 *
 * Ported from `AppModel.evaluateIllness` (`Strand/App/AppModel.swift`). Compares the
 * last ~2 days against a ~28-day baseline ending 3 days ago across resting HR, HRV,
 * skin-temperature deviation and respiration. Two or more anomalies surface a banner;
 * the classic early-illness signature is RHR up + HRV down + skin-temp up.
 * Each firing signal must still be abnormal on the newest day, so a recovered
 * night cannot inherit yesterday's warning from the two-day average.
 *
 * The Swift method also gates on a user toggle (`behavior.illnessWatch`); that toggle
 * is a UI concern, so this pure function omits it. Callers decide whether to run it.
 */
object IllnessWatch {
    /**
     * Evaluate the [days] history (oldest -> newest). Returns a human-readable banner
     * message when 2+ anomaly flags fire, otherwise null.
     *
     * Requires at least 14 days of history (matching `days.count >= 14`).
     */
    data class Evaluation(val alert: String?, val valid: Boolean)

    fun evaluate(days: List<DailyMetric>): String? = evaluateWindow(days).alert

    fun evaluate(days: List<DailyMetric>, hrvBaselineEpoch: Double, recoveryBaselineEpoch: Double): String? =
        evaluateWindow(days, hrvBaselineEpoch, recoveryBaselineEpoch).alert

    fun evaluateWindow(days: List<DailyMetric>, hrvBaselineEpoch: Double = 0.0,
                       recoveryBaselineEpoch: Double = 0.0): Evaluation {
        if (days.size < 14) return Evaluation(null, false)
        val byDay = days.associateBy { it.day }
        val latest = days.maxByOrNull { it.day } ?: return Evaluation(null, false)
        val recent = HealthSignalReliability.dayKeys(latest.day, 2).mapNotNull { byDay[it] }
        fun mean(values: List<Double>): Double? = values.takeIf { it.isNotEmpty() }?.average()
        fun values(selector: (DailyMetric) -> Double?, cfg: MetricCfg): (DailyMetric) -> Double? = { row ->
            selector(row)?.takeIf { it.isFinite() && it >= cfg.minVal && it <= cfg.maxVal }
        }
        val flags = mutableListOf<String>()
        var presentSignals = 0

        fun signal(key: String, selector: (DailyMetric) -> Double?, fires: (Double, Double, Double) -> Boolean,
                   phrase: (Double, Double) -> String) {
            val cfg = Baselines.metricCfg[key] ?: return
            val eligible = values(selector, cfg)
            val epoch = if (key == "hrv") hrvBaselineEpoch else recoveryBaselineEpoch
            val keys = HealthSignalReliability.dayKeys(latest.day, 28, 3, epoch)
            val history = keys.map { byDay[it]?.let(eligible) }
            val state = Baselines.foldHistory(history, keys, cfg, epoch)
            val recentMean = mean(recent.mapNotNull(eligible))
            val baselineMean = mean(history.filterNotNull())
            val current = eligible(latest)
            if (!state.trusted || recentMean == null || baselineMean == null || current == null) return
            presentSignals++
            if (fires(recentMean, baselineMean, current)) flags.add(phrase(current, baselineMean))
        }

        // These are established local wellness thresholds, not official WHOOP thresholds.
        // Apple alerts use the separate IllnessSignalEngine z-score/confounder model; convergence is pending.
        signal("resting_hr", { it.restingHr?.toDouble() },
            { recentMean, baseline, current -> recentMean >= baseline + 5 && current >= baseline + 5 },
            { current, baseline -> "resting HR +${(current - baseline).roundToInt()} bpm" })
        signal("hrv", { it.avgHrv },
            { recentMean, baseline, current -> baseline > 0 && recentMean <= baseline * 0.80 && current <= baseline * 0.80 },
            { current, baseline -> "HRV −${((1 - current / baseline) * 100).roundToInt()}%" })

        val skinCfg = VitalBands.skinTempDeviationCfg
        val skinEligible = values({ it.skinTempDevC?.takeUnless(VitalBands::isAbsoluteSkinTemp) }, skinCfg)
        val skinKeys = HealthSignalReliability.dayKeys(latest.day, 28, 3, recoveryBaselineEpoch)
        val skinState = Baselines.foldHistory(skinKeys.map { byDay[it]?.let(skinEligible) }, skinKeys, skinCfg, recoveryBaselineEpoch)
        val recentSkin = mean(recent.mapNotNull(skinEligible))
        val currentSkin = skinEligible(latest)
        if (skinState.trusted && recentSkin != null && currentSkin != null) {
            presentSignals++
            if (recentSkin >= 0.6 && currentSkin >= 0.6) flags.add("skin temp +${formatOneDp(currentSkin)}°C")
        }

        // RSA respiration remains conservative: plausible sleeping values and a sustained +2.5 bpm.
        signal("resp", { it.respRateBpm?.takeIf { value -> value in 8.0..25.0 } },
            { recentMean, baseline, current -> recentMean >= baseline + 2.5 && current >= baseline + 2.5 },
            { _, _ -> "respiration up" })

        val alert = if (flags.size >= IllnessSignalEngine.minCorroboratingSignals) {
            "Your body looks strained - " + flags.joinToString(", ") + ". Consider taking it easy."
        } else null
        return Evaluation(alert, presentSignals >= IllnessSignalEngine.minCorroboratingSignals)
    }

    /** Format a double to one decimal place (locale-independent), matching "%.1f". */
    private fun formatOneDp(value: Double): String {
        val scaled = (value * 10.0).roundToInt()
        val whole = scaled / 10
        val frac = kotlin.math.abs(scaled % 10)
        return "$whole.$frac"
    }
}
