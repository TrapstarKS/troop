package com.noop.data

// Stable on-device copy provenance and natural-key allocation; mirrored by Swift.
object WorkoutCopyIdentity {
    const val SOURCE = "manual-copy"

    fun isCopy(source: String): Boolean = source.lowercase() == SOURCE

    fun sport(original: String, occupied: List<String>): String {
        val keys = occupied.toSet()
        var ordinal = 1
        while (true) {
            val suffix = if (ordinal == 1) " (manual copy)" else " (manual copy $ordinal)"
            val candidate = original + suffix
            if (candidate !in keys) return candidate
            ordinal++
        }
    }
}
