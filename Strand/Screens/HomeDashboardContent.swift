import SwiftUI
import StrandDesign
import StrandAnalytics
import WhoopStore

struct HomeDashboardContent<Dashboard: View, Extras: View>: View {
    let dayKey: String
    let dayOffset: Int
    var windowDayKey: String? = nil
    private var isToday: Bool { dayOffset == 0 }
    let day: DailyMetric?
    let sleepScore: Double?
    let recovery: Double?
    let recoveryDayKey: String
    let recoveryCaption: String?
    let strain: Double?
    let stress: Double?
    let workouts: [WorkoutRow]
    let onEdit: () -> Void
    let onWorkout: (WorkoutRow) -> Void
    let onActivitySaved: () async -> Void
    let onGuidance: (() -> Void)?
    @ViewBuilder let dashboard: () -> Dashboard
    @ViewBuilder let extras: () -> Extras
    @EnvironmentObject private var router: NavRouter
    @EnvironmentObject private var repo: Repository
    @EnvironmentObject private var intelligence: IntelligenceEngine
    @State private var showManualActivity = false
    @State private var manualEndDate = Date()
    @State private var startWorkoutRequested = false
    @ScaledMetric private var columnWidth = NoopMetrics.compactScoreDialDiameter

    var body: some View {
        LazyVStack(alignment: .leading, spacing: NoopMetrics.sectionGap) {
            scoreRow
            InsightCallout(text: guidance, actionLabel: onGuidance == nil ? nil : String(localized: "Daily Outlook"), onAction: onGuidance)
            monitorRow
            myDay
            myPlan
            VStack(alignment: .leading, spacing: NoopMetrics.space3) {
                HStack {
                    TrackedSectionHeader(title: String(localized: "My Dashboard"))
                    Button(action: onEdit) {
                        Image(systemName: "pencil")
                            .font(StrandFont.headline)
                            .frame(width: NoopMetrics.touchTarget, height: NoopMetrics.touchTarget)
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(StrandPalette.textPrimary)
                    .accessibilityLabel("Edit dashboard")
                }
                dashboard()
            }
            DisclosureGroup {
                extras()
            } label: {
                Text("Your Cards").font(StrandFont.headline).foregroundStyle(StrandPalette.textPrimary)
            }
        }
        .sheet(isPresented: $showManualActivity) {
            ManualWorkoutSheet(initialEndDate: manualEndDate) { row, _ in
                Task {
                    await repo.saveManualWorkout(row)
                    await intelligence.analyzeRecent()
                    await onActivitySaved()
                }
            }
        }
    }

    private var scoreRow: some View {
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .top, spacing: NoopMetrics.space3) {
                sleepDial
                recoveryDial
                strainDial
            }
            .frame(maxWidth: .infinity)
            VStack(spacing: NoopMetrics.space4) {
                sleepDial
                recoveryDial
                strainDial
            }
        }
        .padding(.vertical, NoopMetrics.space4)
    }

    private var sleepDial: some View {
        NavigationLink(value: TabRoute.sleepDetailForDay(dayKey: dayKey)) {
            dial(label: String(localized: "Sleep"), value: sleepScore,
                 display: sleepScore.map { "\(Int($0.rounded()))" } ?? "—", unit: "%",
                 color: StrandPalette.sleepPrimary,
                 caption: sleepScore == nil ? String(localized: isToday ? "No sleep yet" : "No data for this day") : nil)
        }
        .buttonStyle(.plain)
    }

    private var recoveryDial: some View {
        let availableRecovery = recovery.flatMap { RecoveryStrainDetailLogic.recoveryPercent($0) != nil ? $0 : nil }
        return NavigationLink(value: TabRoute.recoveryDetailForDay(dayKey: recoveryDayKey)) {
            dial(label: String(localized: "Recovery"), value: availableRecovery,
                 display: RecoveryStrainDetailLogic.recoveryPercent(availableRecovery).map(String.init) ?? "—", unit: "%",
                 color: availableRecovery.map(StrandPalette.recoveryColor) ?? StrandPalette.ringTrack,
                 caption: recoveryCaption)
        }
        .buttonStyle(.plain)
    }

    private var strainDial: some View {
        let availableStrain = strain.flatMap { $0.isFinite && (0...100).contains($0) ? $0 : nil }
        return NavigationLink(value: TabRoute.strainDetailForDay(dayKey: dayKey, effortOverride: availableStrain, windowDayKey: windowDayKey)) {
            dial(label: String(localized: "Strain"), value: availableStrain,
                 display: availableStrain.map { UnitFormatter.effortDisplay($0, scale: .whoop) } ?? "—", unit: "",
                 color: StrandPalette.strainPrimary, caption: nil)
        }
        .buttonStyle(.plain)
    }

    private func dial(label: String, value: Double?, display: String,
                      unit: String, color: Color, caption: String?) -> some View {
        VStack(spacing: NoopMetrics.space1) {
            ScoreDial(label: "\(label) ›", value: display, unit: value == nil ? "" : unit,
                      progress: value.map { $0 / 100 }, color: color, size: .compact,
                      accessibilityLabel: "\(label), \(display)\(value == nil ? "" : unit)")
            if let caption {
                Text(caption).font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
                    .multilineTextAlignment(.center)
            }
        }
        .frame(minWidth: columnWidth)
        .frame(maxWidth: .infinity, alignment: .top)
    }

    private var guidance: String {
        if !isToday && day == nil { return String(localized: "No data for this day") }
        guard let recovery else {
            return String(localized: "Still learning your baseline. A few more nights and this fills in.")
        }
        if recovery >= 67 { return String(localized: "You're primed. A hard session should land well today.") }
        if recovery >= 34 { return String(localized: "You're in a good spot for training.") }
        return String(localized: "Several recovery signals are down. Prioritise rest today.")
    }

    private var monitorRow: some View {
        HStack(alignment: .top, spacing: NoopMetrics.space3) {
            NavigationLink(value: TabRoute.healthMonitor) {
                MetricCard(label: String(localized: "Health Monitor"),
                           value: "\(availableMetrics)/5", detail: String(localized: "Available metrics"),
                           systemImage: "heart.text.clipboard", color: StrandPalette.positive)
            }
            NavigationLink(value: TabRoute.stressMonitor) {
                MetricCard(label: String(localized: "Stress Monitor"),
                           value: stress.map { String(format: "%.1f", $0) } ?? "—",
                           detail: String(localized: "Daily average"),
                           systemImage: "waveform.path.ecg", color: stress.map(StressRamp.color))
            }
        }
        .buttonStyle(.plain)
    }

    private var availableMetrics: Int {
        [day?.avgHrv, day?.restingHr.map(Double.init), day?.respRateBpm, day?.spo2Pct,
         day?.skinTempC ?? day?.skinTempDevC].compactMap { $0 }.filter { $0.isFinite }.count
    }

    private var myDay: some View {
        VStack(alignment: .leading, spacing: NoopMetrics.space3) {
            HStack {
                TrackedSectionHeader(title: String(localized: "My Day"))
                Menu {
                    Button {
                        manualEndDate = HomeDayActivities.manualEnd(dayKey: dayKey)
                        showManualActivity = true
                    } label: {
                        Label("Add activity", systemImage: "plus")
                    }
                    if isToday {
                        Button {
                            startWorkoutRequested = true
                        } label: {
                            Label("Start workout", systemImage: "figure.run")
                        }
                    }
                } label: {
                    Image(systemName: "plus").font(StrandFont.headline)
                        .frame(width: NoopMetrics.touchTarget, height: NoopMetrics.touchTarget)
                        .foregroundStyle(StrandPalette.onPrimaryAction)
                        .background(RoundedRectangle(cornerRadius: NoopMetrics.cardRadius)
                            .fill(StrandPalette.textPrimary))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Add activity")
            }
            if isToday { ActiveWorkoutIndicatorSection(onOpen: { startWorkoutRequested = true }) }
            NoopCard {
                VStack(spacing: NoopMetrics.space3) {
                    NavigationLink(value: TabRoute.sleepDetailForDay(dayKey: dayKey)) {
                        eventRow(title: String(localized: "Sleep"),
                                 subtitle: String(localized: day?.totalSleepMin == nil
                                    ? (isToday ? "No sleep yet" : "No data for this day") : "Recorded sleep"),
                                 value: day?.totalSleepMin.map { HomeDayActivities.duration($0) } ?? "—",
                                 icon: "moon.fill", color: StrandPalette.sleepPrimary)
                    }
                    ForEach(Array(HomeDayActivities.rows(workouts, dayKey: dayKey).enumerated()), id: \.offset) { _, workout in
                        Divider().overlay(StrandPalette.hairline)
                        Button { onWorkout(workout) } label: {
                            eventRow(title: WorkoutSource.displaySport(workout.sport),
                                     subtitle: Date(timeIntervalSince1970: TimeInterval(workout.startTs))
                                        .formatted(date: .omitted, time: .shortened),
                                     value: RecoveryStrainDetailLogic.durationMinutes(seconds: workout.durationS,
                                        fallbackSeconds: Double(workout.endTs - workout.startTs))
                                        .map { HomeDayActivities.duration(Double($0)) } ?? "—",
                                     icon: "figure.run", color: StrandPalette.strainPrimary)
                        }
                    }
                    if HomeDayActivities.rows(workouts, dayKey: dayKey).isEmpty {
                        Text("No activities").font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
                    }
                }
                .buttonStyle(.plain)
            }
            if isToday {
                WorkoutStartControl(showsButton: false, startRequested: $startWorkoutRequested)
                Text("Current day is still in progress.")
                    .font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
            }
        }
    }

    private func eventRow(title: String, subtitle: String, value: String, icon: String, color: Color) -> some View {
        HStack(spacing: NoopMetrics.space3) {
            Image(systemName: icon).font(StrandFont.headline).foregroundStyle(color)
                .frame(width: NoopMetrics.touchTarget)
            VStack(alignment: .leading, spacing: NoopMetrics.space1) {
                Text(title).font(StrandFont.headline).foregroundStyle(StrandPalette.textPrimary)
                Text(subtitle).font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
            }
            Spacer(minLength: NoopMetrics.space2)
            Text(value).font(StrandFont.bodyNumber).foregroundStyle(StrandPalette.textPrimary)
            Image(systemName: "chevron.right").font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
        }
        .frame(minHeight: NoopMetrics.touchTarget)
        .contentShape(Rectangle())
    }

    private var myPlan: some View {
        VStack(alignment: .leading, spacing: NoopMetrics.space3) {
            TrackedSectionHeader(title: String(localized: "My Plan"))
            HomeWeeklyPlanSummary()
            Button { router.openJournal(day: dayOffset) } label: {
                Label(String(localized: "Journal"), systemImage: "checklist")
                    .font(StrandFont.headline)
                    .frame(minHeight: NoopMetrics.touchTarget)
            }
            .buttonStyle(.plain)
        }
    }
}

enum HomeDayActivities {
    static func manualEnd(dayKey: String, now: Date = Date(), calendar: Calendar = .current) -> Date {
        let parts = dayKey.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return now }
        let time = calendar.dateComponents([.hour, .minute, .second], from: now)
        let date = calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2],
            hour: time.hour, minute: time.minute, second: time.second)) ?? now
        return min(date, now)
    }

    static func duration(_ minutes: Double) -> String {
        guard minutes.isFinite,
              let rounded = RecoveryStrainDetailLogic.wholeNumber(max(0, minutes)) else { return "—" }
        return rounded >= 60
            ? String(localized: "\(rounded / 60)h \(rounded % 60)m")
            : String(localized: "\(rounded)m")
    }

    static func rows(_ rows: [WorkoutRow], dayKey: String) -> [WorkoutRow] {
        rows.filter {
            Repository.localDayKey(Date(timeIntervalSince1970: TimeInterval($0.startTs))) == dayKey
        }.sorted { $0.startTs < $1.startTs }
    }
}

struct HomeDateChrome: View {
    @Binding var selectedOffset: Int
    let selectedDate: Date
    let dateLabel: String
    let maxOffset: Int
    let streak: Int
    let onProfile: () -> Void
    @EnvironmentObject private var live: LiveState
    @EnvironmentObject private var router: NavRouter
    @State private var showCalendar = false

    var body: some View {
        VStack(alignment: .leading, spacing: NoopMetrics.space1) {
            TopChrome(dateLabel: dateLabel, previousLabel: String(localized: "Previous day"),
                      nextLabel: String(localized: "Next day"), profileLabel: String(localized: "Menu and settings"),
                      strapLabel: live.connected ? String(localized: "Connected") : String(localized: "Disconnected"),
                      batteryPercent: batteryPercent, isConnected: live.connected, canGoNext: selectedOffset > 0,
                      onPrevious: { step(1) }, onNext: { step(-1) }, onDate: { showCalendar = true },
                      onProfile: onProfile, onStrap: { router.openDevices() })
            HStack(spacing: NoopMetrics.space2) {
                Label("\(streak)", systemImage: "flame.fill")
                    .font(StrandFont.captionNumber).foregroundStyle(StrandPalette.textSecondary)
                    .accessibilityLabel("Scored-day streak")
                Spacer()
                if selectedOffset == 0 {
                    if live.backfilling {
                        StatusPill(label: String(localized: "Syncing history"), systemImage: "arrow.triangle.2.circlepath",
                                   color: StrandPalette.textSecondary)
                    } else if let state = TodayView.recordingState(live: live, selectedDayOffset: selectedOffset) {
                        Text(state.label).font(StrandFont.caption)
                            .foregroundStyle(StrandPalette.textSecondary)
                            .accessibilityLabel(state.accessibilityText)
                    }
                }
            }
        }
        .popover(isPresented: $showCalendar) {
            DatePicker("", selection: Binding(get: { selectedDate }, set: {
                selectedOffset = min(maxOffset, TodayView.pickedDayOffset(pickedDate: $0,
                    anchorLogicalDay: Repository.logicalDay(Date())))
                showCalendar = false
            }), in: ...Repository.logicalDay(Date()), displayedComponents: [.date])
            .datePickerStyle(.graphical)
            .padding(NoopMetrics.space4)
        }
    }

    private var batteryPercent: Int? {
        guard live.connected else { return nil }
        return LiveConsoleReadout.batteryPercent(activeIsWhoop: live.activeIsWhoop,
            whoopPct: live.batteryPct, ringPct: live.ouraBatteryPct)
    }

    private func step(_ delta: Int) {
        selectedOffset = TodayView.clampedDayOffset(current: selectedOffset, delta: delta, maxOffset: maxOffset)
    }
}

struct HomeWorkoutTarget: Identifiable {
    let id = UUID()
    let row: WorkoutRow
}

enum HomeMetricRoute {
    static func route(_ metric: KeyMetric) -> TabRoute {
        switch metric {
        case .charge: return .recoveryDetail
        case .effort: return .strainDetail
        case .rest: return .sleepDetail
        case .hrv: return .metric("hrv")
        case .restingHr: return .metric("rhr")
        case .bloodOxygen: return .metric("spo2")
        case .respiratory: return .metric("resp_rate")
        case .steps: return .metricSourced(key: "steps", source: "my-whoop")
        case .weight: return .metricSourced(key: "weight", source: "apple-health")
        case .calories: return .metricSourced(key: "active_kcal", source: "apple-health")
        case .skinTemp: return .metric("skin_temp")
        }
    }
}
