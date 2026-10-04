package com.noop.ui

import com.noop.analytics.HealthspanHistory
import com.noop.data.DailyMetric
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformLatest

internal data class HealthspanDatedRows(val today: LocalDate, val rows: List<DailyMetric>, val loaded: Boolean) {
    fun reference(selectedIso: String?): LocalDate = selectedIso?.let(LocalDate::parse) ?: today
}

/** Civil day is rechecked on collection/resume, committed changes, and foreground minute ticks. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun healthspanCivilDays(changes: Flow<Unit>, today: () -> LocalDate = { LocalDate.now() }): Flow<LocalDate> =
    merge(flow {
        while (true) {
            emit(today())
            delay(60_000)
        }
    }, changes.map { today() }).distinctUntilChanged()

/** Each publication carries the exact day used for its bounded query. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun healthspanDatedRows(days: Flow<LocalDate>, query: (String, String) -> Flow<List<DailyMetric>>): Flow<HealthspanDatedRows> =
    days.flatMapLatest { today ->
        query(today.minusDays(3999).toString(), today.toString())
            .map { HealthspanDatedRows(today, it.toList(), true) }
            .onStart { emit(HealthspanDatedRows(today, emptyList(), false)) }
    }

internal data class HealthspanContributorRead(val samples: List<HealthspanHistory.Sample>? = null, val failed: Boolean = false)

/** Loading, recorded-empty and failure are distinct; cancelled or obsolete reads never publish. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun healthspanContributorReads(changes: Flow<Unit>, isCurrent: () -> Boolean,
                                       load: suspend () -> List<HealthspanHistory.Sample>): Flow<HealthspanContributorRead> =
    changes.transformLatest {
        if (isCurrent()) emit(HealthspanContributorRead())
        val read = try {
            HealthspanContributorRead(load().toList())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            HealthspanContributorRead(emptyList(), failed = true)
        }
        currentCoroutineContext().ensureActive()
        if (isCurrent()) emit(read)
    }
