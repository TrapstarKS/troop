package com.noop.analytics

object StressMonitorReading {
    enum class State(val rawValue: String) {
        RECORDED("recorded"),
        DELAYED("delayed"),
        NO_HEART_RATE("noHeartRate"),
        NO_WAKING_HEART_RATE("noWakingHeartRate"),
        INSUFFICIENT_SAMPLES("insufficientSamples"),
        ACTIVITY_EXCLUDED("activityExcluded"),
    }

    data class Window(
        val startTs: Long,
        val endTs: Long,
        val level: Double?,
        val maskedForActivity: Boolean = false,
    )

    data class Reading(val window: Window?, val state: State)

    fun resolve(
        windows: List<Window>,
        hasHeartRate: Boolean,
        now: Long,
        isToday: Boolean,
        selectedStartTs: Long? = null,
    ): Reading {
        val window = if (selectedStartTs != null) {
            windows.firstOrNull { it.startTs == selectedStartTs }
        } else {
            windows.filter { it.level?.let { level -> level.isFinite() && level in 0.0..3.0 } == true }
                .maxByOrNull { it.startTs }
        }
        val level = window?.level
        if (window != null && level != null && level.isFinite() && level in 0.0..3.0) {
            val delayed = selectedStartTs == null && isToday && now - window.endTs > 900L
            return Reading(window, if (delayed) State.DELAYED else State.RECORDED)
        }
        val state = when {
            window?.maskedForActivity == true || (selectedStartTs == null && windows.any { it.maskedForActivity }) -> State.ACTIVITY_EXCLUDED
            !hasHeartRate -> State.NO_HEART_RATE
            windows.isEmpty() -> State.NO_WAKING_HEART_RATE
            else -> State.INSUFFICIENT_SAMPLES
        }
        return Reading(window, state)
    }
}
