package com.noop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.BuildConfig
import com.noop.R
import com.noop.data.WeeklyPlanCalendar
import com.noop.data.WeeklyPlanDay
import com.noop.data.WeeklyPlanEngine
import com.noop.data.WeeklyPlanGoals
import com.noop.data.WeeklyPlanJournalDay
import com.noop.data.WeeklyPlanNotice
import com.noop.data.WeeklyPlanPreferences
import com.noop.data.WeeklyPlanPreset
import com.noop.data.WeeklyPlanProgress
import com.noop.data.seedWeeklyPlanDemo
import java.time.LocalDate

@Composable
fun WeeklyPlanScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val preferences = remember(context) { WeeklyPlanPreferences(context) }
    val reactiveDays by vm.recentDays.collectAsStateWithLifecycle()
    val journalSeq by vm.repo.journalRevision.collectAsStateWithLifecycle()
    val effortScale = UnitPrefs.effortScale(context)
    var today by remember { mutableStateOf(LocalDate.now().toString()) }
    var weekOffset by remember { mutableStateOf(0) }
    var days by remember { mutableStateOf<List<WeeklyPlanDay>>(emptyList()) }
    var journal by remember { mutableStateOf<List<WeeklyPlanJournalDay>>(emptyList()) }
    var goals by remember { mutableStateOf(WeeklyPlanGoals()) }
    var draft by remember { mutableStateOf(WeeklyPlanGoals()) }
    var loaded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<WeeklyPlanNotice?>(null) }
    var catalogItems by remember { mutableStateOf(loadJournalCatalogItems(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                today = LocalDate.now().toString()
                catalogItems = loadJournalCatalogItems(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val currentWeek = WeeklyPlanCalendar.weekStart(today) ?: today
    val selectedWeek = WeeklyPlanCalendar.adding(weekOffset * 7, currentWeek) ?: currentWeek
    val weekEnd = WeeklyPlanCalendar.adding(6, selectedWeek) ?: selectedWeek
    LaunchedEffect(Unit) {
        if (BuildConfig.ENABLE_DEMO) seedWeeklyPlanDemo(context, today)
    }
    LaunchedEffect(reactiveDays, journalSeq, today, vm.activeStrapId, selectedWeek) {
        days = vm.repo.daysMerged(vm.activeStrapId).map { WeeklyPlanDay(it.day, it.totalSleepMin, it.strain) }
        val imported = vm.repo.importedSourceIds(vm.activeStrapId).flatMap { vm.repo.journal(it, "0001-01-01", today) }
        val native = vm.repo.journal(JOURNAL_DEVICE_ID, "0001-01-01", today)
        journal = mergeJournalEntries(imported, native).map { WeeklyPlanJournalDay(it.day, it.question, it.answeredYes) }
        val suggested = WeeklyPlanEngine.suggestedGoals(days, today)
        goals = preferences.goals(selectedWeek, suggested)
        notice = preferences.notice(today)
        loaded = true
    }
    val snapshot = remember(goals, selectedWeek, today, days, journal) {
        WeeklyPlanEngine.snapshot(goals, selectedWeek, today, days, journal)
    }
    val items = remember(catalogItems, journal) {
        resolveJournalItems(journal.map { it.question }.distinct().sorted(), catalogItems).filter { !it.kind.isNumeric }
    }
    fun openEditor() { draft = goals; editing = true }
    fun moveWeek(offset: Int) {
        val next = weekOffset + offset
        val week = WeeklyPlanCalendar.adding(next * 7, currentWeek) ?: currentWeek
        goals = preferences.goals(week, WeeklyPlanEngine.suggestedGoals(days, today))
        weekOffset = next
        saved = false
    }
    fun dismiss(value: WeeklyPlanNotice) { preferences.dismiss(value); notice = null }
    fun habitLabel(question: String): String = journalLocalizedLabel(
        catalogItems.firstOrNull { normJournalKey(it.canonical) == normJournalKey(question) } ?: JournalCatalogItem(canonical = question),
    )

    ScreenScaffold(title = stringResource(R.string.weekly_plan_title), subtitle = stringResource(R.string.weekly_plan_subtitle)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { moveWeek(-1) }) {
                Icon(Icons.Default.ChevronLeft, stringResource(R.string.trends_previous_week), tint = Palette.textSecondary)
            }
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.weekly_plan_week_format, selectedWeek, weekEnd), style = NoopType.captionNumber, color = Palette.textPrimary)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { moveWeek(1) }, enabled = weekOffset < 0) {
                Icon(Icons.Default.ChevronRight, stringResource(R.string.trends_next_week), tint = Palette.textSecondary)
            }
        }
        if (loaded && (preferences.hasPlan(selectedWeek) || weekOffset == 0) && snapshot != null) {
            if (weekOffset == 0) notice?.let { value ->
                NoopCard {
                    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                        Text(stringResource(if (value.kind == WeeklyPlanNotice.Kind.CheckIn) R.string.weekly_plan_check_in else R.string.weekly_plan_recap),
                            style = NoopType.title2, color = Palette.textPrimary)
                        Text(stringResource(if (value.kind == WeeklyPlanNotice.Kind.CheckIn) R.string.weekly_plan_check_in_body else R.string.weekly_plan_recap_body),
                            style = NoopType.body, color = Palette.textSecondary)
                        if (value.kind == WeeklyPlanNotice.Kind.Recap) {
                            val recap = WeeklyPlanEngine.snapshot(preferences.goals(value.weekStart), value.weekStart, today, days, journal)
                            recap?.let {
                                Text(stringResource(R.string.weekly_plan_week_format, it.weekStart, it.weekEnd), style = NoopType.captionNumber, color = Palette.textPrimary)
                                it.overallPercent?.let { percent ->
                                    Text(stringResource(R.string.weekly_plan_percent, percent), style = NoopType.headline, color = Palette.textPrimary)
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                            NoopButton(stringResource(R.string.weekly_plan_edit), kind = NoopButtonKind.Secondary, onClick = { openEditor() })
                            if (value.kind == WeeklyPlanNotice.Kind.Recap) {
                                NoopButton(stringResource(R.string.weekly_plan_keep), onClick = {
                                    goals = preferences.goals(value.weekStart)
                                    preferences.save(goals, currentWeek)
                                    dismiss(value)
                                    saved = true
                                })
                            } else NoopButton(stringResource(R.string.weekly_plan_dismiss), onClick = { dismiss(value) })
                        }
                    }
                }
            }
            NoopCard {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                    Text(stringResource(if (preferences.hasPlan(selectedWeek)) R.string.weekly_plan_overall else R.string.weekly_plan_suggested), style = NoopType.overline, color = Palette.textSecondary)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (preferences.hasPlan(selectedWeek)) snapshot.overallPercent?.let { stringResource(R.string.weekly_plan_percent, it) } ?: "—" else "—",
                            style = NoopType.display(), color = Palette.textPrimary)
                        Spacer(Modifier.weight(1f))
                        if (weekOffset == 0) NoopButton(stringResource(if (preferences.hasPlan(selectedWeek)) R.string.weekly_plan_edit else R.string.weekly_plan_create), kind = NoopButtonKind.Secondary, onClick = { openEditor() })
                    }
                    if (!preferences.hasPlan(selectedWeek)) {
                        Text(stringResource(R.string.weekly_plan_suggestion_body), style = NoopType.subhead, color = Palette.textSecondary)
                    } else {
                        snapshot.overallPercent?.let { WeeklyPlanBar(it, Palette.accent) }
                            ?: Text(stringResource(R.string.weekly_plan_waiting), style = NoopType.subhead, color = Palette.textSecondary)
                    }
                }
            }
            WeeklyPlanGoalCard(stringResource(R.string.weekly_plan_sleep),
                stringResource(R.string.weekly_plan_sleep_target, goals.sleepMinutes / 60, goals.sleepMinutes % 60, goals.sleepDays), snapshot.sleep, Palette.restColor)
            WeeklyPlanGoalCard(stringResource(R.string.weekly_plan_strain),
                stringResource(R.string.weekly_plan_strain_target, UnitFormatter.effortDisplay(goals.strainMinimum.toDouble(), effortScale), "/" + UnitFormatter.effortScaleMax(effortScale), goals.strainDays),
                snapshot.strain, Palette.effortColor)
            val habit = if (goals.journalQuestion.isEmpty()) stringResource(R.string.weekly_plan_logged)
                else habitLabel(goals.journalQuestion)
            val target = if (goals.journalQuestion.isEmpty()) stringResource(R.string.weekly_plan_habit_days, habit, goals.journalDays)
                else stringResource(R.string.weekly_plan_habit_target, habit, stringResource(if (goals.journalAnswer == "yes") R.string.weekly_plan_yes else R.string.weekly_plan_no), goals.journalDays)
            WeeklyPlanGoalCard(stringResource(R.string.weekly_plan_journal), target, snapshot.journal, Palette.accent)
            if (saved) Text(stringResource(R.string.weekly_plan_saved), style = NoopType.caption, color = Palette.accent)
        } else if (loaded) {
            NoopCard { Text(stringResource(R.string.weekly_plan_no_saved), style = NoopType.body, color = Palette.textSecondary) }
        }
        Text(stringResource(R.string.weekly_plan_local), style = NoopType.caption, color = Palette.textTertiary)
    }
    if (editing) {
        var expanded by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(stringResource(R.string.weekly_plan_edit), style = NoopType.title2) },
            text = {
                Column(Modifier.heightIn(max = Metrics.dialogScrollableMaxHeight).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Metrics.space16)) {
                    Text(stringResource(if (preferences.hasPlan(currentWeek) && draft.normalized == goals) R.string.weekly_plan_saved else R.string.weekly_plan_unsaved), style = NoopType.caption)
                    Text(stringResource(R.string.weekly_plan_presets_heading), style = NoopType.overline)
                    val selectedPreset = WeeklyPlanPreset.entries.firstOrNull { it.goals == draft.normalized }
                    Text(stringResource(selectedPreset?.let(::weeklyPlanPresetLabel) ?: R.string.weekly_plan_custom), style = NoopType.caption)
                    WeeklyPlanPreset.entries.forEach { preset ->
                        NoopButton(stringResource(weeklyPlanPresetLabel(preset)), kind = NoopButtonKind.Secondary, fullWidth = true,
                            onClick = { draft = preset.goals })
                    }
                    WeeklyPlanTargetEditor(stringResource(R.string.weekly_plan_sleep_minutes), draft.sleepMinutes, 240..720, 15) { draft = draft.copy(sleepMinutes = it) }
                    WeeklyPlanTargetEditor(stringResource(R.string.weekly_plan_sleep_days), draft.sleepDays, 1..7) { draft = draft.copy(sleepDays = it) }
                    WeeklyPlanTargetEditor(stringResource(R.string.weekly_plan_strain_minimum), draft.strainMinimum, 1..100,
                        display = UnitFormatter.effortDisplay(draft.strainMinimum.toDouble(), effortScale) + " /" + UnitFormatter.effortScaleMax(effortScale)) { draft = draft.copy(strainMinimum = it) }
                    WeeklyPlanTargetEditor(stringResource(R.string.weekly_plan_strain_days), draft.strainDays, 1..7) { draft = draft.copy(strainDays = it) }
                    Column {
                        Text(stringResource(R.string.weekly_plan_behavior), style = NoopType.caption)
                        TextButton(onClick = { expanded = true }) {
                            Text(if (draft.journalQuestion.isEmpty()) stringResource(R.string.weekly_plan_logged)
                                 else habitLabel(draft.journalQuestion))
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.weekly_plan_logged)) }, onClick = {
                                draft = draft.copy(journalQuestion = "", journalAnswer = "any"); expanded = false
                            })
                            items.forEach { item -> DropdownMenuItem(text = { Text(journalLocalizedLabel(item)) }, onClick = {
                                draft = draft.copy(journalQuestion = item.canonical, journalAnswer = "yes"); expanded = false
                            }) }
                        }
                    }
                    if (draft.journalQuestion.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                            listOf("yes", "no").forEach { answer ->
                                NoopButton(stringResource(if (answer == "yes") R.string.weekly_plan_yes else R.string.weekly_plan_no),
                                    kind = if (draft.journalAnswer == answer) NoopButtonKind.Primary else NoopButtonKind.Secondary,
                                    onClick = { draft = draft.copy(journalAnswer = answer) })
                            }
                        }
                    }
                    WeeklyPlanTargetEditor(stringResource(R.string.weekly_plan_journal_days), draft.journalDays, 1..7) { draft = draft.copy(journalDays = it) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    goals = draft.normalized
                    preferences.save(goals, currentWeek)
                    notice?.let { dismiss(it) }
                    saved = true
                    editing = false
                }) { Text(stringResource(R.string.weekly_plan_save)) }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(R.string.weekly_plan_cancel)) } },
        )
    }
}

private fun weeklyPlanPresetLabel(preset: WeeklyPlanPreset): Int = when (preset) {
    WeeklyPlanPreset.RestRoutine -> R.string.weekly_plan_rest_routine
    WeeklyPlanPreset.ActiveWeek -> R.string.weekly_plan_active_week
    WeeklyPlanPreset.BalancedWeek -> R.string.weekly_plan_balanced_week
}

@Composable
private fun WeeklyPlanGoalCard(title: String, target: String, progress: WeeklyPlanProgress, tint: Color) {
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            Text(title, style = NoopType.overline, color = Palette.textSecondary)
            Text(target, style = NoopType.headline, color = Palette.textPrimary)
            progress.percent?.let {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.weekly_plan_days_progress, progress.completedDays, progress.targetDays), style = NoopType.bodyNumber, color = Palette.textPrimary)
                    Text(stringResource(R.string.weekly_plan_percent, it), style = NoopType.captionNumber, color = tint)
                }
                WeeklyPlanBar(it, tint)
            }
                ?: Text(stringResource(R.string.weekly_plan_missing), style = NoopType.caption, color = Palette.textSecondary)
        }
    }
}

@Composable
private fun WeeklyPlanBar(percent: Int, tint: Color) {
    LinearProgressIndicator(progress = percent / 100f, modifier = Modifier.fillMaxWidth(), color = tint, trackColor = Palette.surfaceInset)
}

@Composable
private fun WeeklyPlanTargetEditor(title: String, value: Int, range: IntRange, step: Int = 1,
                                   display: String = value.toString(), onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.space8)) {
        Text(title, style = NoopType.body, modifier = Modifier.weight(1f))
        Text(display, style = NoopType.captionNumber)
        StepperButton("−", { onChange((value - step).coerceIn(range)) },
            stringResource(R.string.l10n_components_decrease_accessibility_df5f1511, title), enabled = value > range.first)
        StepperButton("+", { onChange((value + step).coerceIn(range)) },
            stringResource(R.string.l10n_components_increase_accessibility_0949c0e9, title), enabled = value < range.last)
    }
}
