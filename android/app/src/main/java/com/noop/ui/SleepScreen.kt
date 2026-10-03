package com.noop.ui

import com.noop.R
import androidx.compose.ui.res.stringResource
import android.app.TimePickerDialog
import android.widget.Toast
import com.noop.analytics.SleepMark
import com.noop.analytics.SleepMarkType
import com.noop.analytics.SleepWindowReclip
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Tune
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.ensureActive
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.noop.data.DailyMetric
import com.noop.data.HrBucket
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.analytics.AnalyticsEngine
import com.noop.analytics.CircadianEngine
import com.noop.analytics.HypnogramCoverage
import com.noop.analytics.ScoreConfidence
import com.noop.analytics.SleepEditGuard
import com.noop.analytics.SleepGroupEdit
import com.noop.analytics.SleepStageTotals
import com.noop.analytics.StagePercentages
import com.noop.data.DismissedSleep
import com.noop.data.SleepSession
import com.noop.data.WhoopRepository
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.noop.analytics.ClockFormat

internal enum class SleepFreshnessStatus {
    SYNCING, CALCULATING, SYNC_FAILED, AWAITING_SYNC, NOT_DETECTED,
}

private data class SleepFreshnessLiveSnapshot(
    val backfilling: Boolean,
    val analyzing: Boolean,
    val lastSyncAt: Long?,
    val syncFailed: Boolean,
)

/** Pure priority ladder for the expected current night. The missing states wait until morning so opening
 * Sleep at 02:00 does not claim the night still in progress has been missed. Swift twin:
 * `resolveSleepFreshness`. */
internal fun resolveSleepFreshness(
    hasCurrentNight: Boolean,
    morningReady: Boolean,
    syncing: Boolean,
    calculating: Boolean,
    syncedSinceDayStart: Boolean,
    syncFailed: Boolean,
): SleepFreshnessStatus? {
    if (syncing) return SleepFreshnessStatus.SYNCING
    // #2108: a night already in hand outranks CALCULATING. It used to sit below, so `hasCurrentNight`
    // could only silence the missing-night states and a finished night was structurally unable to
    // silence this one: the banner said "detecting and staging the night now" directly above that same
    // night scored, timed and staged on screen. A note that contradicts the content beside it is worse
    // than no note, and one that is always on is read by nobody the day it matters. SYNCING stays above,
    // because data still arriving can genuinely change what is shown.
    if (hasCurrentNight) return null
    if (calculating) return SleepFreshnessStatus.CALCULATING
    if (!morningReady) return null
    if (syncFailed) return SleepFreshnessStatus.SYNC_FAILED
    return if (syncedSinceDayStart) SleepFreshnessStatus.NOT_DETECTED
    else SleepFreshnessStatus.AWAITING_SYNC
}

@Composable
private fun SleepFreshnessNote(status: SleepFreshnessStatus, chunks: Int) {
    when (status) {
        SleepFreshnessStatus.SYNCING -> SyncingHistoryNote(chunks)
        SleepFreshnessStatus.CALCULATING -> DataPendingNote(
            title = stringResource(R.string.sleep_status_calculating_title),
            body = stringResource(R.string.sleep_status_calculating_body),
        )
        SleepFreshnessStatus.SYNC_FAILED -> DataPendingNote(
            title = stringResource(R.string.sleep_status_sync_failed_title),
            body = stringResource(R.string.sleep_status_sync_failed_body),
        )
        SleepFreshnessStatus.AWAITING_SYNC -> DataPendingNote(
            title = stringResource(R.string.sleep_status_waiting_title),
            body = stringResource(R.string.sleep_status_waiting_body),
        )
        SleepFreshnessStatus.NOT_DETECTED -> DataPendingNote(
            title = stringResource(R.string.sleep_status_not_detected_title),
            body = stringResource(R.string.sleep_status_not_detected_body),
        )
    }
}

/** Sleep detail for the selected local wake-day. Imported scores retain their provenance, recorded
 * stage intervals preserve timestamps and gaps, and duration-only records show stage totals. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepScreen(
    vm: AppViewModel,
    onOpenJournal: () -> Unit = {},
    onOpenAlarms: () -> Unit = {},
    initialDayKey: String? = null,
) {
    val days by vm.recentDays.collectAsStateWithLifecycle()
    // Whether the ACTIVE strap is an Oura ring, off the canonical brand table (not an "oura" literal) — so
    // the sleep surfaces name a ring-PROVIDED night's provenance "Oura" and flag its split as the ring's
    // RAW on-device stages. Read/UI only, no stored value. Mirrors macOS Repository.activeDeviceIsOura.
    val activeIsOura = com.noop.data.DeviceBrandCatalog.isOura(vm.activeStrapId)
    // #1680: the body-clock phase behind the 24 h dial section. Same snapshot the Health screen reads for
    // BodyClockCard, so the two surfaces cannot disagree about the estimate.
    val v5Signals by vm.v5Signals.collectAsStateWithLifecycle()

    // PERF (#scroll-jank): the BLE live state ticks ~1Hz. This screen reads `live` ONLY for the
    // "syncing history" note (backfilling + the chunk count), so reading the whole `live` object at
    // body scope recomposed the entire Sleep screen on every HR tick. Collapse it to the two fields the
    // note needs via a structural-equality snapshot: a 72→73 bpm tick produces an EQUAL snapshot and
    // the body is NOT recomposed; it only recomposes when the backfilling state / chunk count actually
    // changes. Mirrors the shipped Today liveSnap fix. Appearance-preserving.
    val live by vm.live.collectAsStateWithLifecycle()
    val backfillNote by remember {
        derivedStateOf {
            val s = live
            if (s.backfilling) s.syncChunksThisSession else null
        }
    }
    // Like backfillNote, collapse the 1 Hz BLE state to only fields that can change the missing-night
    // banner. Live HR ticks then remain equality-identical and do not recompose this heavy screen.
    val freshnessLive by remember {
        derivedStateOf {
            SleepFreshnessLiveSnapshot(
                backfilling = live.backfilling,
                analyzing = live.analyzingHistory,
                lastSyncAt = live.lastSyncAt,
                syncFailed = live.lastSyncError != null,
            )
        }
    }

    // Every recorded sleep BLOCK, oldest→newest — the hero's ◀/▶ chevrons walk this whole list,
    // including same-day naps / split sleep that `sleepSessionsMerged` collapses to one-per-night
    // for the dashboard (#170). Derived un-deduplicated: every imported session, plus the computed
    // "-noop" sessions on days the import doesn't cover (imported-wins / computed-fills, mirroring
    // mergeSleep but WITHOUT the per-night collapse). Keyed on `days` so a sync/import (which always
    // rewrites dailyMetric too) reloads; these reads have no Flow. (#160, #170)
    var sleeps by remember { mutableStateOf<List<SleepSession>>(emptyList()) }
    var loadedSleepDays by remember { mutableStateOf<List<DailyMetric>?>(null) }
    var loadedSleepStrap by remember { mutableStateOf<String?>(null) }
    val sleepRowsReady = loadedSleepDays == days && loadedSleepStrap == vm.activeStrapId
    // Durable deleted-night markers. Unlike the 7-second Undo banner these remain reachable after the
    // session row is gone, giving each suppressed window a "Recompute this night" escape hatch (#515).
    var dismissedSleeps by remember { mutableStateOf<List<DismissedSleep>>(emptyList()) }
    var recomputingSleep by remember { mutableStateOf<Pair<String, Long>?>(null) }
    // 0 = latest night, N = N sleep-sessions back. Reset to the newest night only on a REAL data
    // reload (new sync / re-import via `days` changing). The optimistic bed/wake edit rewrites
    // `sleeps` in place WITHOUT touching `days`, so it must not reset the browse — keeping the
    // user on the night they just edited. (#160)
    var nightOffset by remember(initialDayKey, vm.activeStrapId) { mutableIntStateOf(0) }
    var pendingInitialDayKey by remember(initialDayKey, vm.activeStrapId) {
        mutableStateOf(initialDayKey)
    }
    LaunchedEffect(days, vm.activeStrapId) {
        loadedSleepDays = null
        val strap = vm.activeStrapId
        val loaded = runCatching {
            val now = System.currentTimeMillis() / 1000L
            // Read the ACTIVE-strap ∪ canonical "my-whoop" union (#814/#1008), not the canonical id
            // alone: after a strap remove+re-add live nights land under the fresh "whoop-<uuid>" id, so
            // a canonical-only read left this screen STUCK on the last pre-re-add night while every
            // union-joined surface moved on (the #1014/#1009 stuck-sleep divergence, in the OTHER
            // direction). Exact-duplicate (startTs, endTs) blocks recorded under both ids are dropped;
            // naps/split blocks survive. Single-device installs collapse to one id, byte-identical.
            val imported = vm.repo.sleepSessionsUnion(strap, 0L, now)
            val computed = vm.repo.computedSleepSessionsUnion(strap, 0L, now)
            // Key by the LOCAL wake-day (#304), matching WhoopRepository.mergeSleep — a UTC key
            // mis-attributed a UTC+ user's early-morning wake to yesterday. REUSE the existing
            // dayString(ts, offsetSec) overload; do not add a new one (it clashes on the JVM).
            fun localEndDay(ts: Long): String {
                val offsetSec = (java.util.TimeZone.getDefault().getOffset(ts * 1000) / 1000).toLong()
                return AnalyticsEngine.dayString(ts, offsetSec)
            }
            // Imported wins per local wake-day, WITH the #241 richness exception (a stage-less import
            // yields to a computed day that has stages) — the SAME rule the browse/CSV path uses via
            // WhoopRepository.mergeSleep. Sort by the EFFECTIVE onset so a hand-edited bedtime orders the
            // night correctly (PR #395).
            WhoopRepository.mergeSleepRichness(imported, computed) { localEndDay(it.endTs) }
                .sortedBy { it.effectiveStartTs }
        }.getOrDefault(emptyList())
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        sleeps = loaded
        nightOffset = 0
        loadedSleepDays = days
        loadedSleepStrap = strap
    }

    // Read the active∪canonical management union so a marker created before a strap re-add remains
    // visible. Keyed on days because deletes/recomputes both rescore and republish the affected day.
    LaunchedEffect(days) {
        dismissedSleeps = runCatching {
            vm.repo.dismissedSleepsUnion(vm.activeStrapId)
        }.getOrDefault(dismissedSleeps)
    }

    // #65: the transient UNDO banner shown after a suppressing delete. Holds the deleted SleepSession
    // (which still carries its OWNING deviceId + userEdited), so Undo restores it into the original
    // namespace and lifts the tombstone. Auto-cleared after ~7s by a keyed LaunchedEffect; a new delete
    // replaces it. Mirrors the macOS SleepView sleepUndoBanner + WorkoutsView postLogNote idiom.
    // #1492: a LIST, because a bed/wake correction can retire more than one fragment of a bridged night.
    // `fromEdit` distinguishes those from an outright delete so the banner says what actually happened.
    var sleepUndo by remember { mutableStateOf<SleepUndoState?>(null) }
    LaunchedEffect(sleepUndo) {
        if (sleepUndo != null) {
            kotlinx.coroutines.delay(7_000)
            sleepUndo = null
        }
    }

    // The user's LEARNED habitual midsleep (local time-of-day seconds), or null under the cold-start
    // threshold. Loaded from `vm.repo.habitualMidsleepSec` — the SAME value AnalyticsEngine.analyzeDay
    // threads into the daily total — and fed into the main-night selector so the hero, the naps split,
    // and the edit target pick the SAME block the analytics rollup did, for a shift/late sleeper too.
    // null keeps the existing cold-start overnight-band fallback. Keyed on `days` so it refreshes
    // alongside `sleeps`. Mirrors iOS SleepView.habitualMidsleepSec. (#547)
    var habitualMidsleep by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(days) {
        // Thread the ACTIVE strap id so the learner unions active + canonical nights (#814/#1008);
        // habitualMidsleepSec resolves the canonical "my-whoop" sibling internally either way.
        habitualMidsleep = runCatching { vm.repo.habitualMidsleepSec(vm.activeStrapId) }.getOrNull()
    }

    // Persisted per-epoch MOTION keyed by each session's detected startTs (#407). Loaded alongside
    // `sleeps`; `selectNight` reads only the ALREADY-resolved main-night GROUP's entries (no re-resolution)
    // and lays them along the hypnogram's timeline. A block with no stored series stays absent (honest empty
    // state for older rows whose motionJSON is NULL). Mirrors iOS SleepView.motionByStart.
    var motionByStart by remember { mutableStateOf<Map<Long, List<Double>>>(emptyMap()) }
    LaunchedEffect(sleeps, vm.activeStrapId) {
        motionByStart = runCatching {
            vm.repo.sessionMotions(vm.activeStrapId, sleeps)
        }.getOrDefault(emptyMap())
    }

    // Export-verbatim sleep figures (sleep_performance / consistency / need / debt) — the
    // headline tiles prefer them over the on-device approximations. Keyed on `days` so a
    // fresh import (which always rewrites dailyMetric too) reloads; metricSeries has no Flow.
    var imported by remember { mutableStateOf(ImportedSleepSeries()) }
    LaunchedEffect(days) {
        suspend fun load(key: String) = runCatching {
            vm.repo.metricSeries("my-whoop", key, "0000-00-00", "9999-99-99")
        }.getOrDefault(emptyList()).associate { it.day to it.value }
        imported = ImportedSleepSeries(
            performance = load("sleep_performance"),
            consistency = load("sleep_consistency"),
            needMin = load("sleep_need_min"),
            debtMin = load("sleep_debt_min"),
        )
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Day-cycle sky backdrop (#698). Default ON. When off, the screen drops the liquid sky and the
    // scaffold paints the plain dark surface canvas instead — the SAME gate the liquid Today honours.
    // SharedPreferences isn't reactive, so it's read once into local state (mirrors iOS @AppStorage).
    val showDayCycleBackground = remember { NoopPrefs.showDayCycleBackground(context) }
    // Sky-behind-cards (#434 family): when on, the sky fills the whole viewport so the transparent
    // cards reveal it the whole way down, exactly like Today and the metric-detail screens.
    val skyBehindCards = remember { NoopPrefs.skyBehindCards(context) }

    // Morning-journal nudge: once per calendar day, when the freshest night ended within the last
    // 12 hours, invite the user to log how they felt. The shown-day is persisted so the sheet never
    // re-pops on a recomposition or a same-day re-open. (PR #260)
    var showJournalPrompt by remember { mutableStateOf(false) }

    // #sleep-layout: the arrangeable analytical-card order + explicit hidden set (SleepLayoutPrefs).
    // SharedPreferences isn't reactive, so hold it in state and refresh after the Arrange sheet saves.
    // Mirrors TodayScreen's section-order state.
    var sleepSectionOrder by remember { mutableStateOf(SleepLayoutPrefs.order(context)) }
    var hiddenSections by remember { mutableStateOf(SleepLayoutPrefs.hidden(context)) }
    var showSleepArrange by remember { mutableStateOf(false) }
    // #sleep-layout (hold-to-drag): the hoisted list state (the drag math needs layoutInfo + scrollBy) and
    // the live drag state, mirroring Today (TodayScreen.kt §today-layout). The frame loop runs ONLY while a
    // card is lifted: each frame it retries the swap (so a card held still at a viewport edge keeps
    // reordering as the list scrolls under it — onDrag alone only fires while the finger moves) and applies
    // the edge auto-scroll velocity SleepReorderableSection's onDrag computed. Persistence is on drop
    // (onDrop below), not here — this only updates the in-memory order live.
    val sleepListState = rememberLazyListState()
    val sleepSectionDrag = remember { SleepSectionDragState() }
    val sleepDragActive = sleepSectionDrag.key != null
    LaunchedEffect(sleepDragActive) {
        // Auto-scroll is TIME-based (px/second × real frame delta), not per-frame, so it reads the same on
        // 60/90/120 Hz. dt is clamped so a dropped/backgrounded frame can't produce one giant jump.
        var lastFrameNanos = 0L
        while (sleepSectionDrag.key != null) {
            val frameNanos = withFrameNanos { it }
            val dtSec = if (lastFrameNanos == 0L) 0f
            else ((frameNanos - lastFrameNanos) / 1_000_000_000f).coerceAtMost(0.05f)
            lastFrameNanos = frameNanos
            swapTargetForDraggedSleepSection(sleepListState, sleepSectionDrag, sleepSectionOrder)?.let { (dragged, target) ->
                // Freeze the scroll anchor across the reorder so a swap involving the first visible item
                // can't leap the viewport by the two cards' height difference in a single frame.
                val anchorIndex = sleepListState.firstVisibleItemIndex
                val anchorOffset = sleepListState.firstVisibleItemScrollOffset
                sleepSectionOrder = sleepSectionOrder.movedSleepSection(dragged, target)
                sleepListState.scrollToItem(anchorIndex, anchorOffset)
            }
            if (sleepSectionDrag.autoScrollPxPerSecond != 0f && dtSec > 0f) {
                sleepListState.scrollBy(sleepSectionDrag.autoScrollPxPerSecond * dtSec)
            }
        }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(sleeps) {
        // #627: the journal-reminder toggle (default ON) gates this morning sheet too, so disabling the
        // reminder silences both the Today card and this sheet with one switch.
        if (!NoopPrefs.journalReminderEnabled(context)) return@LaunchedEffect
        val latestEnd = sleeps.lastOrNull()?.endTs ?: return@LaunchedEffect
        val nowS = System.currentTimeMillis() / 1000L
        val hoursAgo = (nowS - latestEnd) / 3600.0
        if (hoursAgo in 0.0..12.0) {
            val today = LocalDate.now().toString()
            // #684: don't nudge when today's journal is already logged — e.g. via the Today card (#656),
            // which never sets KEY_LAST_JOURNAL_PROMPT, so the once-per-day dedup alone would still pop
            // this sheet. Reuse the SAME completion signal the Today card uses (repo.journal for today).
            val loggedToday = runCatching {
                vm.repo.journal(JOURNAL_DEVICE_ID, today, today).any { it.day == today }
            }.getOrDefault(false)
            if (loggedToday) return@LaunchedEffect
            val prefs = NoopPrefs.of(context)
            val lastPrompted = prefs.getString(NoopPrefs.KEY_LAST_JOURNAL_PROMPT, "")
            if (lastPrompted != today) {
                prefs.edit().putString(NoopPrefs.KEY_LAST_JOURNAL_PROMPT, today).apply()
                showJournalPrompt = true
            }
        }
    }

    if (showJournalPrompt) {
        ModalBottomSheet(
            onDismissRequest = { showJournalPrompt = false },
            sheetState = sheetState,
            containerColor = Palette.surfaceRaised,
            contentColor = Palette.textPrimary,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(Metrics.space24),
                verticalArrangement = Arrangement.spacedBy(Metrics.space16),
            ) {
                Text(uiString(R.string.l10n_sleep_screen_good_morning_33e88869), style = NoopType.title2, color = Palette.textPrimary)
                Text(
                    uiString(R.string.l10n_sleep_screen_your_night_data_is_in_logging_ec461720),
                    style = NoopType.subhead,
                    color = Palette.textSecondary,
                )
                Button(
                    onClick = { showJournalPrompt = false; onOpenJournal() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.accent),
                ) {
                    Text(uiString(R.string.l10n_sleep_screen_open_journal_4bf0daee), style = NoopType.headline, color = Palette.surfaceBase)
                }
                TextButton(
                    onClick = { showJournalPrompt = false },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(uiString(R.string.l10n_sleep_screen_maybe_later_27ad1d83), style = NoopType.subhead, color = Palette.textTertiary)
                }
            }
        }
    }

    // #sleep-layout: the Arrange sheet — reorder / show-hide the analytical cards, persisted via
    // SleepLayoutPrefs. Reuses Today's generic EditableVisibilityRows editor. Refresh the in-memory
    // order/hidden state on save so the cards re-lay-out immediately (SharedPreferences isn't reactive).
    if (showSleepArrange) {
        SleepArrangeSheet(
            initialOrder = sleepSectionOrder,
            initialHidden = hiddenSections,
            onDismiss = { showSleepArrange = false },
            onSave = { order, hidden ->
                SleepLayoutPrefs.setOrder(context, order)
                SleepLayoutPrefs.setHidden(context, hidden)
                sleepSectionOrder = order
                hiddenSections = hidden
                showSleepArrange = false
            },
        )
    }

    // Debt credit is the canonical main-night DailyMetric total PLUS actual asleep minutes from blocks
    // outside that main-night group. Keep the nap sum separate: Rest, the hero and daily total deliberately
    // remain main-night-only. Stage-less naps add no guessed in-bed time. Mirrors Swift SleepView. (#525)
    val napSleepMinByDay = remember(sleeps, habitualMidsleep) {
        napSleepMinutesByDay(sleeps, habitualMidsleep)
    }

    // Tapping a metric tile opens a full-history detail sheet for that one metric. (PR #260)
    val metricSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var detailMetricKey by remember { mutableStateOf<String?>(null) }
    val currentDetailKey = detailMetricKey
    if (currentDetailKey != null) {
        ModalBottomSheet(
            onDismissRequest = { detailMetricKey = null },
            sheetState = metricSheetState,
            containerColor = Palette.surfaceRaised,
            contentColor = Palette.textPrimary,
        ) {
            SleepMetricDetailSheetContent(
                vm = vm,
                key = currentDetailKey,
                imported = imported,
                napSleepMinByDay = napSleepMinByDay,
            )
        }
    }

    // The browsable DAY list: every block grouped by the calendar day it ENDS on (matching the
    // dashboard's per-night merge key, `localEndDay` above), newest day first, blocks within a day
    // oldest→newest. Each day is ONE ◀/▶ stop, so a split-sleep / nap day reads as a single night
    // and a WHOOP 4.0 user with one detected night isn't stuck on dead arrows — the chevrons step
    // by DAY, not by flat session index (#57/#59). Mirrors iOS SleepView.navDays (in-view grouping).
    val navDays = remember(sleeps) {
        sleeps.groupBy { localDayString(it.endTs) }
            .toSortedMap(reverseOrder())                       // newest day first
            .map { (_, blocks) -> blocks.sortedBy { it.effectiveStartTs } }
    }

    val requestedOffset = if (sleepRowsReady)
        requestedSleepNightOffset(navDays, pendingInitialDayKey) else null
    val visibleNightOffset = if (pendingInitialDayKey != null) requestedOffset else nightOffset
    val unavailableRequestedDay = pendingInitialDayKey?.takeIf { sleepRowsReady && requestedOffset == null }
    val onNavigateNight: (Int) -> Unit = { offset ->
        if (sleepRowsReady) {
            pendingInitialDayKey = null
            nightOffset = offset.coerceIn(0, max(navDays.lastIndex, 0))
        }
    }

    // The navigated night, decoded once per (offset, data) change — chevron taps re-pick
    // instantly without re-parsing stagesJSON on every recomposition. The offset now indexes
    // DAYS (navDays), so a day with a detected night always resolves to that night. (#160, #59)
    // #1821: the reader's chosen clock, resolved once for this screen. It is a remember KEY below so
    // changing the setting re-derives the labels instead of leaving the old clock on screen.
    val is24h = ClockPrefs.uses24Hour(LocalContext.current)
    val night = remember(visibleNightOffset, navDays, days, habitualMidsleep, motionByStart, is24h) {
        visibleNightOffset?.let {
            selectNight(navDays, days, it, habitualMidsleep, motionByStart, is24h = is24h)
        }
    }

    // #1311: label the carousel by CALENDAR nights, not the flat recorded-night index — a night with no
    // data (strap off-body) is skipped by the carousel, so labelling by index makes two nights either
    // side of it read as consecutive and desyncs the "N nights ago" labels. Shared by the Rest hero
    // overline and the nav header so both name the SAME calendar night.
    val nightLabel = pendingInitialDayKey?.takeIf { visibleNightOffset == null }
        ?: nightRelativeLabel(calendarNightsAgo(navDays, visibleNightOffset ?: nightOffset,
            java.util.TimeZone.getDefault()))

    // The HERO follows the selected night (its stage breakdown comes from that day's row); the
    // at-a-glance TILES, the debt ledger, the personal need and the trend stay full-history /
    // latest-anchored, matching iOS SleepView. `selectedDay` re-points only the hero. Model is null
    // when the selected day has no stage minutes. (#5)
    val model = remember(days, night, imported, napSleepMinByDay, sleeps, is24h) {
        night?.let {
            buildSleepModel(days, it.session, imported, selectedDay = it.dayKey,
                heroStages = it.groupStages, heroSegments = it.groupSegments,
                napSleepMinByDay = napSleepMinByDay, sessions = sleeps, is24h = is24h)
        }
    }
    val recordedStages = remember(night) { selectedNightStages(night) }
    val display = remember(model, night, recordedStages) { heroDisplay(model, night, recordedStages) }
    val h9Display = remember(model, night) { heroDisplay(model, night, recordedStages = null) }

    val resultSnapshot = if (night != null && display != null) SleepResultSnapshot(
        scope = "${vm.activeStrapId}:${(night.heroGroup.ifEmpty { listOf(night.session) }).map { it.deviceId }.distinct().sorted().joinToString(",")}:${night.dayKey}",
        onset = night.heroOnsetTs ?: night.session.effectiveStartTs,
        wake = night.heroWakeTs ?: night.session.endTs,
        asleepMinutes = display.stages.asleep,
        edited = night.session.userEdited || night.heroGroup.any { it.userEdited },
    ) else null
    val changeTracker = remember(vm.activeStrapId, visibleNightOffset, resultSnapshot?.scope) { SleepResultChangeTracker() }
    var resultChanged by remember(vm.activeStrapId, visibleNightOffset, resultSnapshot?.scope) { mutableStateOf(false) }
    var resultRevision by remember(vm.activeStrapId, visibleNightOffset, resultSnapshot?.scope) { mutableIntStateOf(0) }
    LaunchedEffect(resultSnapshot, sleepRowsReady, freshnessLive.analyzing, changeTracker) {
        if (changeTracker.observe(resultSnapshot, sleepRowsReady && !freshnessLive.analyzing)) {
            resultChanged = true
            resultRevision++
        }
    }
    LaunchedEffect(resultRevision, changeTracker) {
        if (resultChanged) {
            kotlinx.coroutines.delay(8_000)
            resultChanged = false
        }
    }

    val sleepFreshness = remember(sleeps, freshnessLive) {
        val zone = ZoneId.systemDefault()
        val now = Instant.now().atZone(zone)
        val latestWake = sleeps.maxOfOrNull { it.endTs }
        val current = latestWake?.let {
            Instant.ofEpochSecond(it).atZone(zone).toLocalDate() == now.toLocalDate()
        } ?: false
        val dayStart = now.toLocalDate().atStartOfDay(zone).toEpochSecond()
        resolveSleepFreshness(
            hasCurrentNight = current,
            morningReady = now.hour >= 6,
            syncing = freshnessLive.backfilling,
            calculating = freshnessLive.analyzing,
            syncedSinceDayStart = (freshnessLive.lastSyncAt ?: 0L) >= dayStart,
            syncFailed = freshnessLive.syncFailed,
        )
    }

    // #940: ONE stage-less SELECTED day (typically the newest, after an impossible hand-edit staged
    // it all-awake) must not hide the whole tab's history. The tiles / ledger / trends are
    // full-history and independent of the browsed night (matching iOS, where browsing only
    // re-points the hero), so when the selected day's model fails to build, anchor them to the
    // newest stage-bearing day instead of vanishing. The HERO stays on `model`/`display` (an
    // honest no-stage-data fallback for the bad day, edit pencil reachable). Null only when NO day
    // has stage data: the true first-run empty state.
    val tilesModel = remember(model, days, imported, napSleepMinByDay, sleeps) {
        model ?: fallbackSleepModel(days, imported, napSleepMinByDay, sessions = sleeps)
    }

    val selectedDetailModel = remember(days, night, imported, napSleepMinByDay, sleeps, is24h, habitualMidsleep) {
        selectedSleepDetailModel(days, night, imported, napSleepMinByDay, sleeps, is24h, habitualMidsleep)
    }
    val selectedEfficiencyPct = selectedSleepEfficiency(night, days, recordedStages)
    val selectedAmounts = selectedSleepAmounts(recordedStages, days.lastOrNull { it.day == night?.dayKey },
        selectedDetailModel?.hoursVsNeeded.selectedValue(), night?.dayKey?.let { imported.needMin[it] })

    // Jump straight to a night by its (local) wake-day — the center date block opens a picker.
    // navDays is newest-day-first, so the day's index IS its offset (0 = last night). (#160, #59)
    val onPickNightDate: (LocalDate) -> Unit = { targetDate ->
        val targetStr = targetDate.toString()
        val dayIdx = navDays.indexOfFirst { day -> day.any { localDayString(it.endTs) == targetStr } }
        if (dayIdx >= 0) onNavigateNight(dayIdx)
    }

    val onUpdateSleepTimes: (SleepSession, Long, Long) -> Unit = { s, start, end ->
        // #940 belt-and-braces: never apply (optimistically OR durably) a future-ending
        // or inverted window, whatever the pickers produced. The editor's own guards
        // (cross-midnight auto-correct + the disjoint confirm) should make this
        // unreachable; sharing ONE safe window here keeps the in-memory copy and the DB
        // write in lockstep. Same rule as WhoopRepository.updateSleepSessionTimes.
        val safe = SleepEditGuard.clampedEditWindow(start, end, System.currentTimeMillis() / 1000L)
        if (safe != null) {
            val (safeStart, safeEnd) = safe
            // Optimistic: rewrite this session in `sleeps` so every metric recomputes
            // immediately, then persist DURABLY off the UI thread. Mirror the persist path —
            // keep the IMMUTABLE detected startTs and store the corrected onset in
            // startTsAdjusted with userEdited=true, so display (via effectiveStartTs) tracks the
            // edit while the (deviceId,startTs) key never moves. (PR #260 + #395)
            // Reclip stagesJSON in-memory so the hypnogram strip updates instantly (same
            // reclip logic runs again in WhoopRepository for the durable DB copy).
            // #1492: apply across the WHOLE bridged night. Editing only `s` (the winning
            // fragment) left the fragments defining the displayed bedtime and wake exactly
            // where they were, so a corrected night looked unchanged. ONE plan drives both the
            // optimistic copy and the durable write, so they cannot disagree.
            val group = sleepEditGroupFor(s, night?.heroGroup.orEmpty())
            val plan = SleepGroupEdit.plan(group, safeStart, safeEnd)
            if (plan.clipped.isNotEmpty()) {
                val edited = plan.clipped.associateBy { it.deviceId to it.startTs }
                val gone = plan.dropped.map { it.deviceId to it.startTs }.toSet()
                sleeps = sleeps.mapNotNull { row ->
                    val key = row.deviceId to row.startTs
                    when {
                        key in gone -> null
                        else -> edited[key] ?: row
                    }
                }
                if (plan.dropped.isNotEmpty()) {
                    sleepUndo = SleepUndoState(plan.dropped, fromEdit = true)
                }
                scope.launch { vm.updateSleepGroupTimes(group, safeStart, safeEnd) }
            }
        } else {
            // The clamp refused a future/inverted window. Never drop an edit silently (the nap
            // pickers used to do exactly that): tell the user why nothing changed. (#940)
            Toast.makeText(
                context,
                "That time can't be saved (it lands in the future or ends before it starts).",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
    val onDeleteSleepSession: (SleepSession) -> Unit = { s ->
        // Delete = the edit path minus the re-insert: drop this session from `sleeps`
        // so every metric recomputes immediately as if the night were never recorded,
        // then persist the removal off the UI thread. Lets the user clear a misread or
        // spurious night. (#281)
        // #65: offer a transient UNDO. `s` still carries its owning deviceId + userEdited,
        // everything undo needs to restore it into the original namespace.
        sleeps = sleeps.filterNot { it.deviceId == s.deviceId && it.startTs == s.startTs }
        sleepUndo = SleepUndoState(listOf(s), fromEdit = false)
        scope.launch {
            vm.deleteSleepSession(s)
            dismissedSleeps = runCatching {
                vm.repo.dismissedSleepsUnion(vm.activeStrapId)
            }.getOrDefault(dismissedSleeps)
        }
    }
    val onAddSleepNap: (Long, Long) -> Unit = { startTs, endTs ->
        // Persist the new nap as its OWN session (#508); reload `sleeps` afterwards so the
        // new block shows in the ◀/▶ browse without waiting for a sync. We don't optimistically
        // insert here because the stages are staged from raw off the UI thread.
        scope.launch {
            vm.addManualNap(startTs, endTs)
            sleeps = runCatching {
                val now = System.currentTimeMillis() / 1000L
                // Same active∪canonical union as the main loader (#814/#1008), so the
                // post-nap reload can't snap the browse back to a canonical-only night set.
                val importedSessions = vm.repo.sleepSessionsUnion(vm.activeStrapId, 0L, now)
                val computed = vm.repo.computedSleepSessionsUnion(vm.activeStrapId, 0L, now)
                fun localEndDay(ts: Long): String {
                    val offsetSec = (java.util.TimeZone.getDefault().getOffset(ts * 1000) / 1000).toLong()
                    return AnalyticsEngine.dayString(ts, offsetSec)
                }
                // Same imported-wins + #241 richness merge as the main loader.
                WhoopRepository.mergeSleepRichness(importedSessions, computed) { localEndDay(it.endTs) }
                    .sortedBy { it.effectiveStartTs }
            }.getOrDefault(sleeps)
        }
    }

    LazyScreenScaffold(
        title = uiString(R.string.l10n_sleep_screen_sleep_3cac34e6),
        subtitle = stringResource(R.string.whoop_sleep_detail_subtitle),
        listState = sleepListState,   // #sleep-layout: the hold-to-drag frame loop drives this list state
        // LIQUID SKY BACKDROP (the pilot pattern — LiquidScreenSky.kt): the static time-of-day liquid sky
        // settles into the theme canvas behind the header + hero, bled full-width up behind the status bar
        // via the scaffold's topBackground plumbing. Gated on the day-cycle preference exactly like Today
        // (showDayCycleBackground ? sky : plain canvas). Replaces the classic per-hero scene backdrop.
        topBackground = screenBackdropSlot(showDayCycleBackground, skyBehindCards),
        // Sky-behind-cards fills the viewport so the transparent cards reveal the sky the whole way down
        // (Today / metric-detail parity — the same two prefs drive the same two behaviours everywhere).
        fullBleedBackground = screenBackdropFullBleed(showDayCycleBackground, skyBehindCards),
    ) {
        // #65: the transient UNDO banner after a suppressing delete. Restores the deleted row into its
        // ORIGINAL namespace + lifts the tombstone. Mirrors the macOS SleepView sleepUndoBanner.
        sleepUndo?.let { undo ->
            item {
                SleepUndoBanner(
                    undo = undo,
                    onUndo = {
                        sleepUndo = null
                        scope.launch {
                            vm.undoDeleteSleepSessions(undo.sessions)
                            // Re-read so the restored night reappears in the ◀/▶ browse. Same
                            // active∪canonical union as the main loader (#814/#1008), so the undo
                            // reload can't snap the browse back to a canonical-only night set.
                            sleeps = runCatching {
                                val now = System.currentTimeMillis() / 1000L
                                vm.repo.sleepSessionsUnion(vm.activeStrapId, 0L, now) +
                                    vm.repo.computedSleepSessionsUnion(vm.activeStrapId, 0L, now)
                            }.getOrDefault(sleeps)
                        }
                    },
                )
            }
        }
        if (dismissedSleeps.isNotEmpty()) {
            item {
                DeletedSleepWindowsCard(
                    windows = dismissedSleeps,
                    recomputing = recomputingSleep,
                    onHide = { marker ->
                        scope.launch {
                            val hidden = vm.hideDeletedSleepWindow(marker)
                            if (hidden) {
                                dismissedSleeps = dismissedSleeps.filterNot {
                                    it.deviceId == marker.deviceId && it.startTs == marker.startTs
                                }
                            }
                            Toast.makeText(
                                context,
                                if (hidden) {
                                    uiString(R.string.l10n_sleep_screen_deleted_sleep_window_hidden_5c848a32)
                                } else {
                                    uiString(R.string.l10n_sleep_screen_couldn_t_hide_this_deleted_sleep_bbedac55)
                                },
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                    onRecompute = { marker ->
                        val key = marker.deviceId to marker.startTs
                        recomputingSleep = key
                        scope.launch {
                            val cleared = vm.recomputeDeletedSleep(marker)
                            dismissedSleeps = runCatching {
                                vm.repo.dismissedSleepsUnion(vm.activeStrapId)
                            }.getOrDefault(dismissedSleeps)
                            recomputingSleep = null
                            Toast.makeText(
                                context,
                                if (cleared) {
                                    uiString(R.string.l10n_sleep_screen_sleep_detection_reran_using_the_data_01757aad)
                                } else {
                                    uiString(R.string.l10n_sleep_screen_couldn_t_reopen_this_night_try_88265690)
                                },
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                )
            }
        }
        if (resultChanged) {
            item {
                DataPendingNote(
                    title = stringResource(R.string.sleep_result_updated_title),
                    body = stringResource(R.string.sleep_result_updated_message),
                )
            }
        }
        sleepFreshness?.let { status ->
            item { SleepFreshnessNote(status, backfillNote ?: 0) }
        }
        if (unavailableRequestedDay != null) {
            item {
                DataPendingNote(title = stringResource(R.string.whoop_sleep_unavailable),
                    body = stringResource(R.string.whoop_sleep_requested_missing))
            }
        }
        // #940: the empty state is ONLY for a truly empty history. A newest day that merely fails
        // to merge (the phantom-edit shape) keeps the hero (night != null) and the full-history
        // tiles (tilesModel != null), so intact older nights are never hidden behind "no nights".
        if (tilesModel == null && night == null) {
            item {
                SleepEmptyState()
            }
            item { SleepAlarmsEntry(onOpenAlarms) }
        } else {
            item {
                NightNavHeader(if (sleepRowsReady) visibleNightOffset ?: -1 else -1, nightLabel,
                    if (sleepRowsReady) max(navDays.lastIndex, 0) else -1,
                    navHeaderClockLabel(night?.clockLabel, navDays, visibleNightOffset ?: -1, is24h),
                    onNavigateNight, night?.session, heroGroup = night?.heroGroup.orEmpty(),
                    onUpdateTimes = onUpdateSleepTimes, onDeleteSession = onDeleteSleepSession,
                    onAddNap = onAddSleepNap, onPickNightDate = onPickNightDate.takeIf { sleepRowsReady })
            }
            item {
                SleepPerformanceSummary(
                    score = heroPerformanceScore(night, days, imported),
                    efficiencyPct = selectedEfficiencyPct,
                    sufficiencyPct = selectedAmounts.sufficiencyPct,
                    detail = selectedDetailModel,
                    source = restHeroSource(imported, night?.dayKey, activeIsOura),
                    importedScore = night?.dayKey?.let { imported.performance[it] != null } == true,
                    onMetricClick = { detailMetricKey = it },
                )
            }
            item(key = "sleepSelectedStages") {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                    // #1537: the night's heart rate for the Classic view's line chart. Loaded here
                    // because Hero takes data rather than a repo, and keyed on the night so paging the
                    // carousel refetches. 60-second buckets, matching the iOS Sleep tab's hrBuckets call.
                    val hrFrom = night?.session?.effectiveStartTs
                    val hrTo = night?.session?.endTs
                    var nightHr by remember(hrFrom, hrTo) { mutableStateOf(emptyList<HrBucket>()) }
                    LaunchedEffect(hrFrom, hrTo, vm.activeStrapId) {
                        nightHr = if (hrFrom != null && hrTo != null && hrTo > hrFrom) {
                            runCatching {
                                vm.repo.hrBucketsUnion(vm.activeStrapId, hrFrom, hrTo, bucketSeconds = 60L)
                            }.getOrDefault(emptyList())
                        } else {
                            emptyList()
                        }
                    }
                    Hero(
                        display = display,
                        efficiencyPct = selectedEfficiencyPct,
                        asleepMin = selectedAmounts.asleepMin,
                        h9Stages = h9Display?.stages,
                        activeIsOura = activeIsOura,
                        nightHr = nightHr,
                        session = night?.session,
                        heroGroup = night?.heroGroup.orEmpty(),
                        onUpdateTimes = onUpdateSleepTimes,
                        onDeleteSession = onDeleteSleepSession,
                        napBlocks = night?.napBlocks ?: emptyList(),
                        habitualMidsleepSec = habitualMidsleep,
                        motionEpochs = night?.groupMotion ?: emptyList(),
                        groupInBedMin = night?.groupInBedMin,
                        windowOnsetTs = night?.heroOnsetTs,
                        windowWakeTs = night?.heroWakeTs,
                    )
                }
            }
            item {
                SleepSupportingMetrics(selectedDetailModel, display?.stages, selectedEfficiencyPct,
                    selectedAmounts.asleepMin, selectedAmounts.needMin) { detailMetricKey = it }
            }
            item { SleepAlarmsEntry(onOpenAlarms) }
            // #sleep-layout: a compact "Arrange" affordance (the same Tune entry Today uses) opens the
            // reorder / show-hide sheet. Pinned just above the arrangeable cards.
            item {
                Row(Modifier.fillMaxWidth().padding(top = Metrics.selectorTopUp)) {
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { showSleepArrange = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = Palette.textTertiary),
                    ) {
                        Icon(Icons.Filled.Tune, contentDescription = stringResource(R.string.sleep_customize_title), modifier = Modifier.size(Metrics.iconSmall))
                        Spacer(Modifier.width(Metrics.space4))
                        // Concise verb on the affordance (the full "Customize Sleep" title is the icon's a11y
                        // label + the sheet header); reuses Today's generic "Customize" action string.
                        Text(stringResource(R.string.today_customize_action), style = NoopType.footnote)
                    }
                }
            }
            // Analytical cards render in the user's saved order (SleepLayoutPrefs), minus the hidden set.
            // Reordered via the Arrange sheet OR by long-press hold-to-drag on the card (#sleep-layout,
            // mirrors Today); each card's data guards (tilesModel/model) are preserved. Each section is ONE
            // keyed reorderable item so the whole card (incl. its top spacer) lifts and drops as a unit.
            // #sleep-layout: persist the live hold-to-drag reorder when the gesture drops (the in-memory
            // `sleepSectionOrder` already moved live in the frame loop; this writes it through).
            val persistSleepOrder = { SleepLayoutPrefs.setOrder(context, sleepSectionOrder) }
            sleepSectionOrder.filterNot { it in hiddenSections }.forEach { section ->
              val k = SLEEP_SECTION_KEY_PREFIX + section.raw
              when (section) {
                SleepSection.SLEEP_MARKS -> item(key = k) {
                    // SLEEP MARKS — tap to log "going to sleep" / "I'm awake" (#461, Phase 1). LOGGING ONLY:
                    // a mark is persisted to the `sleep_mark` series + the shareable strap log; it never
                    // changes the detected sleep. Mirrors macOS SleepView.sleepMarkCard.
                    SleepReorderableSection(k, sleepListState, sleepSectionDrag, persistSleepOrder) {
                    Column {
                    Spacer(Modifier.height(Metrics.selectorTopUp))
                    SleepMarkCard(
                onMark = { type ->
                    val mark = SleepMark.now(type)
                    // The shareable strap log is the human-readable surface in a debug export.
                    vm.ble.externalLog(mark.logLine())
                    scope.launch {
                        runCatching {
                            vm.repo.upsertMetricSeries(listOf(mark.metricPoint("my-whoop")))
                        }
                    }
                    Toast.makeText(context, mark.confirmation(), Toast.LENGTH_SHORT).show()
                },
            )
                    }
                    }
                }
                SleepSection.STAGES -> Unit
                // Tiles / ledger / trends read the FULL-history model (#940): they stay up when only the
                // selected day's model failed to build, exactly as iOS keeps them while browsing. Each
                // `tilesModel?.let { m -> ... }` binds a non-null local so the smart-cast carries across
                // the item {} lambda boundary — same guard the old `if (tilesModel != null)` block used.
                // The 24 h body-clock dial (#1680). Drawn only for a fit that is at least WIDE: an
                // UNREADABLE rhythm has no phase to compare a night against, and an empty ring would read
                // as a broken chart rather than as "not enough data". It is a reorderable Sleep section,
                // so anyone who does not want it hides it in Arrange — the same affordance every other
                // card here already has, rather than a setting of its own. Mirrors SleepView.
                SleepSection.BODY_CLOCK -> {
                    val phase = v5Signals?.bodyClock
                    val session = night?.session
                    if (phase != null &&
                        phase.confidence != CircadianEngine.PhaseConfidence.UNREADABLE &&
                        session != null
                    ) {
                        item(key = k) {
                            SleepReorderableSection(k, sleepListState, sleepSectionDrag, persistSleepOrder) {
                                Column {
                                    Spacer(Modifier.height(Metrics.selectorTopUp))
                                    BodyClockDialCard(
                                        estimate = phase,
                                        actualBedHour = localClockHour(session.effectiveStartTs),
                                        actualWakeHour = localClockHour(session.endTs),
                                    )
                                }
                            }
                        }
                    }
                }
                SleepSection.NIGHT_DETAIL -> Unit
                SleepSection.SLEEP_DEBT -> tilesModel?.let { m ->
                    item(key = k) {
                        SleepReorderableSection(k, sleepListState, sleepSectionDrag, persistSleepOrder) {
                            Column {
                                Spacer(Modifier.height(Metrics.selectorTopUp))
                                SleepDebtLedgerHostCard(m)
                            }
                        }
                    }
                }
                // StagesVsTypical reads the SELECTED day's model, never the full-history fallback: a
                // phantom newest day with no stage model would otherwise label ANOTHER night's stages as
                // this one (#940). Guarded on BOTH tilesModel and model, exactly as the pre-refactor
                // nesting was (it lived inside the `if (tilesModel != null)` block).
                SleepSection.STAGES_VS_TYPICAL -> if (tilesModel != null) model?.let { selectedModel ->
                    item(key = k) {
                        SleepReorderableSection(k, sleepListState, sleepSectionDrag, persistSleepOrder) {
                            Column {
                                Spacer(Modifier.height(Metrics.selectorTopUp))
                                StagesVsTypicalHostCard(selectedModel.copy(stages = display?.stages ?: selectedModel.stages))
                            }
                        }
                    }
                }
                SleepSection.ASLEEP_DURATION -> tilesModel?.let { m ->
                    item(key = k) {
                        SleepReorderableSection(k, sleepListState, sleepSectionDrag, persistSleepOrder) {
                            Column {
                                Spacer(Modifier.height(Metrics.selectorTopUp))
                                DurationTrend(m)
                            }
                        }
                    }
                }
                // #sleep-layout: the two former pinned detail cards are now arrangeable sections.
                SleepSection.HOURS_VS_NEEDED -> tilesModel?.let { m ->
                    item(key = k) {
                        SleepReorderableSection(k, sleepListState, sleepSectionDrag, persistSleepOrder) {
                            Column {
                                Spacer(Modifier.height(Metrics.selectorTopUp))
                                HoursVsNeededCard(m)
                            }
                        }
                    }
                }
                // Gated on tilesModel to preserve the pre-refactor visibility (both cards shared one
                // `tilesModel?.let` wrapper) — the card reads `sleeps`, not the model, but must not appear
                // in the #940 phantom-edit state (night != null, tilesModel == null) where it didn't before.
                SleepSection.CONSISTENCY -> if (tilesModel != null) item(key = k) {
                    SleepReorderableSection(k, sleepListState, sleepSectionDrag, persistSleepOrder) {
                        Column {
                            Spacer(Modifier.height(Metrics.selectorTopUp))
                            SleepConsistencyCard(sleeps, habitualMidsleep, tilesModel.consistency.latest)
                        }
                    }
                }
              }
            }
        }
    }
}

/** The existing alarm settings, reachable from Sleep with or without recorded nights. */
@Composable
private fun SleepAlarmsEntry(onOpenAlarms: () -> Unit) {
    NoopCard(modifier = Modifier.clickable(onClick = onOpenAlarms), tint = Palette.restColor) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            Icon(Icons.Filled.Alarm, contentDescription = null, tint = Palette.restColor)
            Text(stringResource(R.string.whoop_sleep_planner), style = NoopType.headline, color = Palette.textPrimary,
                 modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Palette.textTertiary)
        }
    }
}

// MARK: - 0b. SLEEP MARKS — tap to log "going to sleep" / "I'm awake" (#461, Phase 1)
//
// A compact additive card with two buttons. Tapping reports the chosen mark up to [onMark], which the
// screen persists to the `sleep_mark` metric series AND appends to the shareable strap log, then
// confirms with a Toast. LOGGING ONLY: a mark never touches the sleep detector or the night boundaries
// on this screen; it's a record for later tap-driven sleep bounds + calibration. Mirrors macOS
// SleepView.sleepMarkCard.

// Lives in the Sleep tab but also hostable in Today (#today-hosted-cards), so it is `internal` (not
// private). Self-contained apart from the [onMark] persistence callback the host supplies.
@Composable
internal fun SleepMarkCard(onMark: (SleepMarkType) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader(title = uiString(R.string.l10n_sleep_screen_sleep_marks_8e9b86f0), overline = "Tap to log", trailing = "Phase 1")
        NoopCard(tint = Palette.restColor) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    uiString(R.string.l10n_sleep_screen_tap_when_you_re_heading_to_1f401690),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                    Button(
                        onClick = { onMark(SleepMarkType.BEDTIME) },
                        modifier = Modifier.weight(1f).semantics { contentDescription = uiString(R.string.l10n_sleep_screen_log_going_to_sleep_6c2b519d) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Palette.surfaceInset,
                            contentColor = Palette.textPrimary,
                        ),
                    ) {
                        Icon(Icons.Filled.Bedtime, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(uiString(R.string.l10n_sleep_screen_going_to_sleep_9c6c63fd), style = NoopType.subhead)
                    }
                    Button(
                        onClick = { onMark(SleepMarkType.WAKE) },
                        modifier = Modifier.weight(1f).semantics { contentDescription = uiString(R.string.l10n_sleep_screen_log_waking_up_2f9c230e) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Palette.surfaceInset,
                            contentColor = Palette.textPrimary,
                        ),
                    ) {
                        Icon(Icons.Filled.WbSunny, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(uiString(R.string.l10n_sleep_screen_i_m_awake_2caf0e7f), style = NoopType.subhead)
                    }
                }
            }
        }
    }
}

/** What the undo strip is holding: the retired sessions, plus whether they went as an outright delete or
 *  as fragments a bed/wake correction left outside a bridged night (#1492). A delete carries exactly one;
 *  a correction can retire several at once, and every one of them must come back on Undo.
 *
 *  Undo reverses the REMOVAL, not the whole correction: the retired fragments return with their original
 *  bounds while the surviving ones keep their corrected ones. That is what the strip offers in words
 *  ("sleep outside the new times was removed"), and it is the more useful half to be able to take back —
 *  the corrected bed and wake times were the point of the edit, and only the deletion is unrecoverable. */
private data class SleepUndoState(val sessions: List<SleepSession>, val fromEdit: Boolean)

/**
 * #65: the transient UNDO strip after a suppressing sleep delete. A Rest-tinted card stating the window
 * NOOP won't re-detect + a real Undo button. The banner auto-clears after ~7s (the caller's keyed
 * LaunchedEffect); Undo restores the deleted row into its ORIGINAL namespace and lifts the tombstone.
 * Mirrors the macOS SleepView.sleepUndoBanner (role-alert-ish, explicit Undo label).
 */
@Composable
private fun SleepUndoBanner(undo: SleepUndoState, onUndo: () -> Unit) {
    // Both construction sites are non-empty (a delete carries one row, a correction only raises this when
    // it retired something), but `first()` on an empty list would take the whole Sleep tab down — too
    // steep a price for a strip that is only ever informational. Render nothing instead.
    val session = undo.sessions.firstOrNull() ?: return
    val timeFmt = SimpleDateFormat(                                   // #1821
        ClockFormat.hourMinutePattern(ClockPrefs.uses24Hour(LocalContext.current)), Locale.US,
    )
    // effectiveStartTs is the displayed onset (a userEdited night's corrected bed time), matching iOS.
    val startText = timeFmt.format(java.util.Date(session.effectiveStartTs * 1000L))
    val endText = timeFmt.format(java.util.Date(session.endTs * 1000L))
    // Branch the copy on userEdited: a hand-edited/added (nap) night writes NO tombstone (it is never
    // re-detected), so the suppression promise would be false for it. Only a DETECTED delete tombstones,
    // so only it gets the "won't detect ... again" wording. Mirrors the macOS branch. (#65 banner honesty.)
    // #1492: a correction that narrows a bridged night RETIRES the fragments left outside it — otherwise
    // they keep defining the night's displayed start or end and the edit looks like it did nothing. That is
    // real recorded sleep going away from an action that reads as "adjust the times", so it must be as
    // reversible, and as clearly stated, as an outright delete. Count-free wording so one dropped fragment
    // and several read the same (no plural forms in the Android catalogue yet).
    val message = when {
        undo.fromEdit -> uiString(R.string.l10n_sleep_screen_sleep_outside_the_new_times_was_6229881e)
        session.userEdited -> "Sleep deleted."
        else -> "Sleep deleted. NOOP won't detect sleep between $startText and $endText again."
    }
    NoopCard(tint = Palette.restColor) {
        Row(
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = message },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                message,
                style = NoopType.footnote,
                color = Palette.textSecondary,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onUndo,
                modifier = Modifier.semantics { contentDescription = uiString(R.string.l10n_sleep_screen_undo_sleep_deletion_1774a23c) },
            ) {
                Text(uiString(R.string.l10n_sleep_screen_undo_39fc7212), style = NoopType.subhead, color = Palette.restColor)
            }
        }
    }
}

/** Persistent management surface for detected nights whose deletion tombstone outlived the transient
 * Undo banner. Each row targets one exact marker; clearing it lets the normal analysis pass derive sleep
 * from the raw data again without weakening the default "deleted means deleted" behaviour (#515). */
@Composable
private fun DeletedSleepWindowsCard(
    windows: List<DismissedSleep>,
    recomputing: Pair<String, Long>?,
    onHide: (DismissedSleep) -> Unit,
    onRecompute: (DismissedSleep) -> Unit,
) {
    val dateFmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    NoopCard(tint = Palette.restColor) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
                Text(
                    uiString(R.string.l10n_sleep_screen_deleted_sleep_windows_46fea77a),
                    style = NoopType.headline,
                    color = Palette.textPrimary,
                )
                Text(
                    uiString(R.string.l10n_sleep_screen_recompute_a_night_to_clear_its_fd9e15c3),
                    style = NoopType.footnote,
                    color = Palette.textSecondary,
                )
            }
            windows.forEach { marker ->
                val key = marker.deviceId to marker.startTs
                val busy = recomputing == key
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Metrics.space8),
                ) {
                    Text(
                        uiString(
                            R.string.l10n_sleep_screen_deleted_sleep_window_range_7bc5f027,
                            dateFmt.format(Date(marker.startTs * 1000L)),
                            dateFmt.format(Date(marker.endTs * 1000L)),
                        ),
                        style = NoopType.footnote,
                        color = Palette.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = recomputing == null,
                        onClick = { onHide(marker) },
                        modifier = Modifier.semantics {
                            contentDescription = uiString(
                                R.string.l10n_sleep_screen_hide_this_deleted_sleep_window_c349003f,
                            )
                        },
                    ) {
                        Text(
                            uiString(R.string.l10n_sleep_screen_hide_7aee4b04),
                            style = NoopType.subhead,
                            color = if (recomputing == null) Palette.textSecondary else Palette.textTertiary,
                        )
                    }
                    TextButton(
                        enabled = recomputing == null,
                        onClick = { onRecompute(marker) },
                        modifier = Modifier.semantics {
                            contentDescription = uiString(
                                R.string.l10n_sleep_screen_recompute_this_deleted_sleep_night_2d2f46f6,
                            )
                        },
                    ) {
                        Text(
                            if (busy) {
                                uiString(R.string.l10n_sleep_screen_recomputing_6f8e54e3)
                            } else {
                                uiString(R.string.l10n_sleep_screen_recompute_this_night_5ba0d05c)
                            },
                            style = NoopType.subhead,
                            color = if (recomputing == null) Palette.restColor else Palette.textTertiary,
                        )
                    }
                }
            }
            Text(
                uiString(R.string.l10n_sleep_screen_if_this_sleep_came_only_from_d0892088),
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )
        }
    }
}

// MARK: - Liquid hero tokens (the liquid Sleep restyle)
//
// MARK: - 1. HERO — stage breakdown for the navigated night

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Hero(
    display: HeroDisplay?,
    efficiencyPct: Double?,
    asleepMin: Double?,
    h9Stages: Stages?,
    activeIsOura: Boolean = false,
    session: SleepSession? = null,
    nightHr: List<HrBucket> = emptyList(),
    heroGroup: List<SleepSession> = emptyList(),
    onUpdateTimes: (SleepSession, Long, Long) -> Unit = { _, _, _ -> },
    onDeleteSession: (SleepSession) -> Unit = {},
    napBlocks: List<SleepSession> = emptyList(),
    habitualMidsleepSec: Long? = null,
    motionEpochs: List<Double> = emptyList(),
    groupInBedMin: Double? = null,
    windowOnsetTs: Long? = null,
    windowWakeTs: Long? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        // The night's clock window — when you fell asleep and when you woke — as its own clearly
        // labelled row. These were only ever in the nav-header's trailing caption, which truncates
        // between the two chevrons on a phone, so in practice the two times people look for first
        // were effectively hidden. Shown for every night that has a session (including the stage-less
        // stub, where it's the only thing the hero can say). Mirrors iOS SleepView.sleepWindowRow.
        // #345: the row shows the WHOLE night's window — on a split night the session (edit anchor)
        // ends mid-night and its endTs contradicted the header pill two lines above.
        if (heroGroup.size > 1) StatusPill(stringResource(R.string.whoop_sleep_fragmented), color = Palette.sleepPrimary)
        session?.let { SleepWindowRow(windowOnsetTs ?: it.effectiveStartTs, windowWakeTs ?: it.endTs) }
        if (display == null) {
            // Honest fallback: this night recorded no usable stage data — never silently
            // substitute another night's hypnogram. (#160)
            NoopCard(tint = Palette.restColor) {
                Text(
                    uiString(R.string.l10n_sleep_screen_no_stage_data_recorded_for_this_93a86806),
                    style = NoopType.subhead,
                    color = Palette.textTertiary,
                )
            }
        } else {
            val s = display.stages
            // After a bed/wake edit the session window is the source of truth for time-in-bed,
            // so the subtitle tracks the edit even before the stage minutes are recomputed. Uses the
            // EFFECTIVE onset so a hand-edited bedtime is reflected. (#160 / PR #395)
            // A fragmented night prefers the GROUP total (#561): `session` is only the WINNING
            // fragment, so its window alone undershot the summed stage minutes shown beside it.
            val inBedMin = groupInBedMin
                ?: session?.let { (it.endTs - it.effectiveStartTs) / 60.0 }
                ?: s.total
            // An Oura night's stages are the ring's RAW on-device SleepNet classification (decoded off the
            // 0x49 phase stream), NOT a NOOP approximation — so it gets its own honest caption instead of the
            // "approx. stages (on-device)" one that describes NOOP's own sparse-motion staging.
            val efficiencyText = efficiencyPct?.let {
                uiString(R.string.l10n_sleep_screen_percent_2281d326, it.roundToInt())
            } ?: "—"
