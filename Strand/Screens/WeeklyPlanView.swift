import SwiftUI
import StrandDesign

private struct WeeklyPlanEditorSession: Identifiable {
    let id = UUID()
    let weekStart: String
    let initialGoals: WeeklyPlanGoals
    let hadPlan: Bool
}

struct WeeklyPlanView: View {
    @EnvironmentObject private var repo: Repository
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var catalog = JournalCatalogStore()
    @AppStorage(UnitPrefs.effortScaleKey) private var effortScaleRaw = EffortScale.hundred.rawValue
    @State private var today = Repository.localDayKey(Date())
    @State private var weekOffset = 0
    @State private var goals = WeeklyPlanGoals()
    @State private var editorSession: WeeklyPlanEditorSession?
    @State private var journal: [WeeklyPlanJournalDay] = []
    @State private var loaded = false
    @State private var saved = false
    @State private var notice: WeeklyPlanNotice?

    private let preferences = WeeklyPlanPreferences()
    private var effortScale: EffortScale { UnitPrefs.resolveEffortScale(effortScaleRaw) }
    private var currentWeek: String { WeeklyPlanCalendar.weekStart(today) ?? today }
    private var selectedWeek: String { WeeklyPlanCalendar.adding(days: weekOffset * 7, to: currentWeek) ?? currentWeek }
    private var days: [WeeklyPlanDay] {
        repo.days.map { WeeklyPlanDay(day: $0.day, sleepMinutes: $0.totalSleepMin, strain: $0.strain) }
    }
    private var snapshot: WeeklyPlanSnapshot? {
        WeeklyPlanEngine.snapshot(goals: goals, weekStart: selectedWeek, today: today, days: days, journal: journal)
    }
    private var items: [JournalCatalogItem] {
        catalog.resolvedItems(imported: Array(Set(journal.map(\.question))).sorted()).filter { $0.kind == .bool }
    }

    var body: some View {
        ScreenScaffold(title: "Weekly Plan", subtitle: "Choose local goals for your week.") {
            weekNavigation
            if loaded, preferences.hasPlan(weekStart: selectedWeek) || weekOffset == 0, let snapshot {
                if weekOffset == 0, let notice { noticeCard(notice) }
                NoopCard {
                    VStack(alignment: .leading, spacing: NoopMetrics.space3) {
                        Text(preferences.hasPlan(weekStart: selectedWeek) ? String(localized: "Overall progress") : String(localized: "Suggested goals")).strandOverline()
                        HStack(alignment: .firstTextBaseline, spacing: NoopMetrics.space2) {
                            Text(preferences.hasPlan(weekStart: selectedWeek) ? (snapshot.overallPercent.map { "\($0)%" } ?? "—") : "—")
                                .font(StrandFont.display()).foregroundStyle(StrandPalette.textPrimary)
                            Spacer()
                            if weekOffset == 0 {
                                NoopButton(preferences.hasPlan(weekStart: selectedWeek) ? "Edit goals" : "Create plan", systemImage: "pencil", kind: .secondary) { openEditor() }
                            }
                        }
                        if preferences.hasPlan(weekStart: selectedWeek) {
                            if let percent = snapshot.overallPercent {
                                ProgressView(value: Double(percent), total: 100).tint(StrandPalette.accent)
                            } else {
                                Text("Progress appears when sleep, strain and selected journal data are available.")
                                    .font(StrandFont.subhead).foregroundStyle(StrandPalette.textSecondary)
                            }
                        }
                    }
                }
                goalCard(title: String(localized: "Sleep goal"), target: sleepTarget(goals),
                         progress: snapshot.sleep, tint: StrandPalette.restColor)
                goalCard(title: String(localized: "Strain goal"), target: strainTarget(goals),
                         progress: snapshot.strain, tint: StrandPalette.effortColor)
                goalCard(title: String(localized: "Journal habit"), target: journalTarget(goals),
                         progress: snapshot.journal, tint: StrandPalette.accent)
                if saved {
                    Text("Goals saved for this week").font(StrandFont.caption).foregroundStyle(StrandPalette.accent)
                }
            } else if loaded {
                NoopCard {
                    Text("No plan saved for this week").font(StrandFont.body).foregroundStyle(StrandPalette.textSecondary)
                }
            } else { ProgressView() }
            Text("Goals and progress stay on this device.")
                .font(StrandFont.caption).foregroundStyle(StrandPalette.textTertiary)
        }
        .task(id: "\(repo.refreshSeq):\(repo.journalSeq):\(repo.loaded):\(today):\(weekOffset)") { await load() }
        .onChange(of: scenePhase) { phase in
            if phase == .active { today = Repository.localDayKey(Date()) }
        }
        .sheet(item: $editorSession) { session in
            WeeklyPlanEditor(session: session, items: items, effortScale: effortScale,
                             journalLabel: journalLabel,
                             onSave: { saveEditor($0, weekStart: $1) },
                             onCancel: { editorSession = nil })
        }
    }

    private var weekNavigation: some View {
        HStack(spacing: NoopMetrics.space3) {
            Button { moveWeek(by: -1) } label: { Image(systemName: "chevron.left") }
                .frame(width: NoopMetrics.controlHeight, height: NoopMetrics.controlHeight)
                .accessibilityLabel("Previous week")
            Spacer()
            Text("\(selectedWeek) – \(WeeklyPlanCalendar.adding(days: 6, to: selectedWeek) ?? selectedWeek)")
                .font(StrandFont.captionNumber).foregroundStyle(StrandPalette.textPrimary)
            Spacer()
            Button { moveWeek(by: 1) } label: { Image(systemName: "chevron.right") }
                .frame(width: NoopMetrics.controlHeight, height: NoopMetrics.controlHeight)
                .disabled(weekOffset >= 0).accessibilityLabel("Next week")
        }
        .tint(StrandPalette.textSecondary)
    }

    private func goalCard(title: String, target: String, progress: WeeklyPlanProgress, tint: Color) -> some View {
        NoopCard {
            VStack(alignment: .leading, spacing: NoopMetrics.space3) {
                Text(title).strandOverline()
                Text(target).font(StrandFont.headline).foregroundStyle(StrandPalette.textPrimary)
                if let percent = progress.percent {
                    HStack {
                        Text("\(progress.completedDays) of \(progress.targetDays) days")
                            .font(StrandFont.bodyNumber).foregroundStyle(StrandPalette.textPrimary)
                        Spacer()
                        Text("\(percent)%").font(StrandFont.captionNumber).foregroundStyle(tint)
                    }
                    ProgressView(value: Double(percent), total: 100).tint(tint)
                } else {
                    Text("No readings in this week yet").font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
                }
            }
        }
    }

    private func noticeCard(_ notice: WeeklyPlanNotice) -> some View {
        NoopCard {
            VStack(alignment: .leading, spacing: NoopMetrics.space3) {
                Text(notice.kind == .checkIn ? String(localized: "Friday check-in") : String(localized: "Monday recap"))
                    .font(StrandFont.title2).foregroundStyle(StrandPalette.textPrimary)
                Text(notice.kind == .checkIn
                     ? String(localized: "Review your progress and adjust the rest of your week.")
                     : String(localized: "Review last week and choose what to carry forward."))
                    .font(StrandFont.body).foregroundStyle(StrandPalette.textSecondary)
                if notice.kind == .recap,
                   let recap = WeeklyPlanEngine.snapshot(goals: preferences.goals(weekStart: notice.weekStart),
                                                        weekStart: notice.weekStart, today: today, days: days, journal: journal) {
                    Text("\(notice.weekStart) – \(recap.weekEnd)").font(StrandFont.captionNumber)
                    if let percent = recap.overallPercent {
                        Text("Overall progress: \(percent)%").font(StrandFont.headline)
                    }
                }
                HStack(spacing: NoopMetrics.space2) {
                    NoopButton("Edit goals", kind: .secondary) { openEditor() }
                    if notice.kind == .recap {
                        NoopButton("Keep goals", kind: .primary) {
                            goals = preferences.goals(weekStart: notice.weekStart)
                            preferences.save(goals, weekStart: currentWeek)
                            dismiss(notice)
                            saved = true
                        }
                    } else { NoopButton("Done", kind: .primary) { dismiss(notice) } }
                }
            }
        }
    }

    private func load() async {
        guard repo.loaded else { return }
        let entries = await repo.journalEntries()
        journal = entries.map { WeeklyPlanJournalDay(day: $0.day, question: $0.question, answeredYes: $0.answeredYes) }
        let suggested = WeeklyPlanEngine.suggestedGoals(days: days, today: today)
        goals = preferences.goals(weekStart: selectedWeek, suggested: suggested)
        notice = preferences.notice(today: today)
        loaded = true
    }

    private func openEditor() {
        let week = selectedWeek
        editorSession = WeeklyPlanEditorSession(weekStart: week, initialGoals: goals,
                                               hadPlan: preferences.hasPlan(weekStart: week))
    }
    private func saveEditor(_ nextGoals: WeeklyPlanGoals, weekStart: String) {
        goals = nextGoals
        preferences.save(goals, weekStart: weekStart)
        if weekStart == currentWeek, let notice { dismiss(notice) }
        if let target = WeeklyPlanCalendar.date(weekStart), let anchor = WeeklyPlanCalendar.date(currentWeek) {
            weekOffset = Int(target.timeIntervalSince(anchor) / 604_800)
        }
        saved = true
        editorSession = nil
    }
    private func moveWeek(by offset: Int) {
        weekOffset += offset
        goals = preferences.goals(weekStart: selectedWeek, suggested: WeeklyPlanEngine.suggestedGoals(days: days, today: today))
        saved = false
    }
    private func dismiss(_ value: WeeklyPlanNotice) { preferences.dismiss(value); notice = nil }
    private func sleepTarget(_ value: WeeklyPlanGoals) -> String {
        String(localized: "\(value.sleepMinutes / 60) h \(value.sleepMinutes % 60) min · \(value.sleepDays) days")
    }
    private func strainTarget(_ value: WeeklyPlanGoals) -> String {
        String(localized: "At least \(UnitFormatter.effortDisplay(Double(value.strainMinimum), scale: effortScale)) /\(UnitFormatter.effortScaleMax(effortScale)) · \(value.strainDays) days")
    }
    private func journalTarget(_ value: WeeklyPlanGoals) -> String {
        guard !value.journalQuestion.isEmpty else {
            return String(localized: "Any saved journal entry · \(value.journalDays) days")
        }
        let label = journalLabel(value.journalQuestion)
        let answer = value.journalAnswer == "yes" ? String(localized: "Yes") : String(localized: "No")
        return String(localized: "\(label) · \(answer) · \(value.journalDays) days")
    }

    private func journalLabel(_ question: String) -> String {
        catalog.item(for: question)?.localizedDisplay ?? JournalFactor.find(question)?.label ?? question
    }
}

private struct WeeklyPlanEditor: View {
    let session: WeeklyPlanEditorSession
    let items: [JournalCatalogItem]
    let effortScale: EffortScale
    let journalLabel: (String) -> String
    let onSave: (WeeklyPlanGoals, String) -> Void
    let onCancel: () -> Void
    @State private var draft: WeeklyPlanGoals

    init(session: WeeklyPlanEditorSession, items: [JournalCatalogItem], effortScale: EffortScale,
         journalLabel: @escaping (String) -> String,
         onSave: @escaping (WeeklyPlanGoals, String) -> Void, onCancel: @escaping () -> Void) {
        self.session = session
        self.items = items
        self.effortScale = effortScale
        self.journalLabel = journalLabel
        self.onSave = onSave
        self.onCancel = onCancel
        _draft = State(initialValue: session.initialGoals)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: NoopMetrics.sectionSpacing) {
                    Text("\(session.weekStart) – \(WeeklyPlanCalendar.adding(days: 6, to: session.weekStart) ?? session.weekStart)")
                        .font(StrandFont.captionNumber).foregroundStyle(StrandPalette.textPrimary)
                    Text(session.hadPlan && draft.normalized == session.initialGoals ? String(localized: "Goals saved for this week") : String(localized: "Unsaved changes"))
                        .font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
                    NoopCard {
                        VStack(alignment: .leading, spacing: NoopMetrics.space2) {
                            Text("Choose a starting routine").strandOverline()
                            Text(WeeklyPlanPreset.allCases.first { $0.goals == draft.normalized }.map(presetLabel) ?? String(localized: "Custom goals"))
                                .font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
                            ForEach(WeeklyPlanPreset.allCases) { preset in
                                NoopButton(LocalizedStringKey(presetLabel(preset)), kind: .secondary, fullWidth: true) { draft = preset.goals }
                            }
                        }
                    }
                    NoopCard {
                        VStack(spacing: NoopMetrics.space4) {
                            Stepper(value: $draft.sleepMinutes, in: 240...720, step: 15) {
                                Text("Sleep minutes: \(draft.sleepMinutes)")
                            }
                            Stepper("Sleep days: \(draft.sleepDays)", value: $draft.sleepDays, in: 1...7)
                        }.font(StrandFont.body).foregroundStyle(StrandPalette.textPrimary)
                    }
                    NoopCard {
                        VStack(spacing: NoopMetrics.space4) {
                            Stepper(value: $draft.strainMinimum, in: 1...100) {
                                Text("Minimum strain: \(UnitFormatter.effortDisplay(Double(draft.strainMinimum), scale: effortScale)) /\(UnitFormatter.effortScaleMax(effortScale))")
                            }
                            Stepper("Strain days: \(draft.strainDays)", value: $draft.strainDays, in: 1...7)
                        }.font(StrandFont.body).foregroundStyle(StrandPalette.textPrimary)
                    }
                    NoopCard {
                        VStack(alignment: .leading, spacing: NoopMetrics.space4) {
                            Picker("Tracked behavior", selection: $draft.journalQuestion) {
                                Text("Any saved journal entry").tag("")
                                ForEach(items) { Text($0.localizedDisplay).tag($0.canonical) }
                                if !draft.journalQuestion.isEmpty, !items.contains(where: { $0.canonical == draft.journalQuestion }) {
                                    Text(journalLabel(draft.journalQuestion)).tag(draft.journalQuestion)
                                }
                            }
                            if !draft.journalQuestion.isEmpty {
                                Picker("Answer", selection: $draft.journalAnswer) {
                                    Text("Yes").tag("yes")
                                    Text("No").tag("no")
                                }.pickerStyle(.segmented)
                            }
                            Stepper("Journal days: \(draft.journalDays)", value: $draft.journalDays, in: 1...7)
                        }.font(StrandFont.body).foregroundStyle(StrandPalette.textPrimary)
                    }
                    NoopButton("Save goals", kind: .primary, fullWidth: true) {
                        onSave(draft.normalized, session.weekStart)
                    }
                }.padding(NoopMetrics.screenPadding)
            }
            .background(StrandPalette.surfaceBase)
            .navigationTitle("Edit goals")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { onCancel() } } }
        }
        #if os(macOS)
        .frame(minWidth: NoopMetrics.editorSheetMinWidth, minHeight: NoopMetrics.editorSheetMinHeight)
        #else
        .noopSheetPresentation(largeFirst: true)
        #endif
    }

    private func presetLabel(_ preset: WeeklyPlanPreset) -> String {
        switch preset {
        case .restRoutine: return String(localized: "Rest routine")
        case .activeWeek: return String(localized: "Active week")
        case .balancedWeek: return String(localized: "Balanced week")
        }
    }
}
