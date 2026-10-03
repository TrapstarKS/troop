package com.noop.analytics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Completion witness shared by queued, direct and background scoring callers. */
class ScoringReadiness {
    data class State(
        val generation: Long = 0,
        val pending: Int = 0,
        val successfulGeneration: Long? = null,
        val failedGeneration: Long? = null,
        val computedInputFingerprint: String? = null,
        val completedSourceId: String? = null,
        val completedDays: Set<String> = emptySet(),
    ) {
        fun ready(importedInputs: Boolean, inputFingerprint: String, day: String, sourceIds: List<String>): Boolean {
            if (pending != 0 || failedGeneration != null) return false
            if (importedInputs) return true
            val completed = computedInputFingerprint
            return !completed.isNullOrEmpty() && completed == inputFingerprint && day in completedDays &&
                completedSourceId in sourceIds
        }
    }

    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()

    suspend fun <T> track(
        sourceId: String,
        inputFingerprint: suspend () -> String,
        completedDays: (T) -> Set<String>,
        operation: suspend () -> T,
    ): T {
        begin()
        try {
            val fingerprint = inputFingerprint()
            val result = operation()
            finish(success = true, fingerprint = fingerprint, sourceId = sourceId, completedDays = completedDays(result))
            return result
        } catch (failure: Throwable) {
            finish(success = false, fingerprint = null, sourceId = sourceId, completedDays = emptySet())
            throw failure
        }
    }

    /** Publishing and synchronous delivery cannot straddle a scoring transition. */
    @Synchronized
    fun ifCurrent(expected: State, publish: () -> Unit) {
        if (mutableState.value == expected) publish()
    }

    @Synchronized
    private fun begin() {
        val current = mutableState.value
        mutableState.value = current.copy(generation = current.generation + 1, pending = current.pending + 1)
    }

    @Synchronized
    private fun finish(success: Boolean, fingerprint: String?, sourceId: String, completedDays: Set<String>) {
        val current = mutableState.value
        val generation = current.generation + 1
        mutableState.value = current.copy(
            generation = generation,
            pending = current.pending - 1,
            successfulGeneration = if (success) generation else current.successfulGeneration,
            failedGeneration = if (!success) generation else null,
            computedInputFingerprint = fingerprint ?: current.computedInputFingerprint,
            completedSourceId = if (success) sourceId else current.completedSourceId,
            completedDays = if (success) completedDays else current.completedDays,
        )
    }
}
