import SwiftUI
import StrandDesign
import StrandAnalytics
#if os(iOS)
import UIKit
#elseif os(macOS)
import AppKit
#endif

struct SmartAlarmView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var behavior: BehaviorStore
    @StateObject private var planner = SleepPlannerSettings.shared
    @State private var wakeMinutes = 420
    @State private var weekdays: Set<Int> = []
    @State private var overrides: [Int: Int] = [:]
    @State private var enabled = false
    @State private var mode = "exact"
    @State private var windDownOn = WindDownNudge.isEnabled
    @State private var showSaveError = false
    @State private var showNotifDenied = false
    @State private var savedMessage = false
    private static let weekdayOrder = [2, 3, 4, 5, 6, 7, 1]

    var body: some View {
        ScreenScaffold(title: "Sleep Planner", subtitle: String(localized: "Plan tonight. Choose how to wake tomorrow.")) {
            TimelineView(.periodic(from: .now, by: 60)) { tick in
                VStack(alignment: .leading, spacing: NoopMetrics.sectionGap) {
                    if let snapshot = model.sleepPlannerSnapshot(from: tick.date) {
                        planCard(snapshot, now: tick.date)
                        if snapshot.earlyWake && !skipIsPending(from: tick.date) {
                            earlyWakeCard(now: tick.date)
                        }
                    }
                    goalCard
                    alarmCard
                    daySchedule
                    SleepPlannerBatteryWarnings(live: model.live, alarmEnabled: behavior.smartAlarmEnabled && model.activeDeviceSupportsStrapAlarm)
                    reminderCard
                    phoneCapabilityCard
                }
            }
        }
        .onAppear {
            wakeMinutes = behavior.smartAlarmMinutes
            weekdays = behavior.smartAlarmWeekdays
            overrides = WindDownNudge.perDayWakeOverrides
            enabled = behavior.smartAlarmEnabled
            mode = planner.alarmMode
        }
        .alert(String(localized: "Alarm could not be saved"), isPresented: $showSaveError) {
            Button(String(localized: "OK"), role: .cancel) {}
        } message: {
            Text("Reconnect your paired strap and try again. WHOOP 5/MG also needs Protocol probes enabled. Your existing alarm has not been changed.")
        }
        .alert(String(localized: "Notifications are off"), isPresented: $showNotifDenied) {
            Button(String(localized: "Open Settings")) { Self.openNotificationSettings() }
            Button(String(localized: "Not now"), role: .cancel) {}
        } message: {
            Text("Allow NOOP notifications in system Settings to receive bedtime reminders.")
        }
    }

    private func planCard(_ snapshot: SleepPlannerSnapshot, now: Date) -> some View {
        StrandCard(tint: StrandPalette.restColor) {
            VStack(alignment: .leading, spacing: NoopMetrics.cardInnerSpacing) {
                Text("Next sleep plan").strandOverline()
                HStack(alignment: .top, spacing: NoopMetrics.gap) {
                    timeColumn(String(localized: "Suggested bedtime"), date: snapshot.bedtime)
                    Spacer(minLength: 0)
                    timeColumn(planner.alarmMode == "exact" ? String(localized: "Planned wake") : String(localized: "Latest wake"), date: snapshot.wake)
                }
                Text("Target sleep: \(duration(snapshot.plan.targetSleepMinutes)) · Need: \(duration(snapshot.plan.needMinutes))")
                    .font(StrandFont.subhead).foregroundStyle(StrandPalette.textSecondary)
                Text(snapshot.plan.historyReady
                     ? String(localized: "A local estimate from your recent sleep, debt, and selected goal.")
                     : String(localized: "Building your plan. Record at least three nights; this recommendation uses your usual need."))
                    .font(StrandFont.footnote).foregroundStyle(StrandPalette.textSecondary)
                if snapshot.plan.debtNudge {
                    Text("Give yourself more room for sleep tonight. The plan includes \(duration(snapshot.plan.debtMinutes)) for recent sleep debt.")
                        .font(StrandFont.footnote).foregroundStyle(StrandPalette.restBright)
                }
                if behavior.smartAlarmEnabled {
                    Divider().overlay(StrandPalette.hairline)
                    Text(alarmStatus(snapshot))
                        .font(StrandFont.headline)
                        .foregroundStyle(snapshot.alarmConfirmed ? StrandPalette.accent : StrandPalette.statusWarning)
                    if snapshot.alarmConfirmed {
                        Text(countdown(until: snapshot.wake, now: now))
                            .font(StrandFont.subhead).foregroundStyle(StrandPalette.textSecondary)
                    }
                    if !skipIsPending(from: now) {
                        Button(String(localized: "Skip next alarm")) {
                            if !model.skipNextSleepPlannerAlarm(from: now) { showSaveError = true }
                        }
                        .buttonStyle(NoopButtonStyle(.secondary, fullWidth: true))
                    }
                } else {
                    Text("Alarm off").font(StrandFont.subhead).foregroundStyle(StrandPalette.textSecondary)
                }
                if skipIsPending(from: now) {
                    Text("One wake has been skipped. Your recurring days are still saved.")
                        .font(StrandFont.footnote).foregroundStyle(StrandPalette.textSecondary)
                }
            }
        }
    }

    private func skipIsPending(from now: Date) -> Bool {
        PlannerAlarmPolicy.isSkipPending(skippedOccurrence: planner.skippedOccurrence,
                                         currentOccurrence: AppModel.smartAlarmOccurrenceKey(now))
    }

    private var goalCard: some View {
        StrandCard {
            VStack(alignment: .leading, spacing: NoopMetrics.cardInnerSpacing) {
                Text("Sleep goal").font(StrandFont.title2).foregroundStyle(StrandPalette.textPrimary)
                Picker(String(localized: "Sleep goal"), selection: $planner.goalPercent) {
                    Text("Peak · 100%").tag(100)
                    Text("Perform · 85%").tag(85)
                    Text("Get By · 70%").tag(70)
                }.pickerStyle(.segmented)
                Text("A percentage of your sleep need, including debt. This is a planning goal, not your Sleep Performance score.")
                    .font(StrandFont.footnote).foregroundStyle(StrandPalette.textSecondary)
            }
        }
        .onChangeCompat(of: planner.goalPercent) { _ in WindDownNudge.reschedule() }
    }

    private var alarmCard: some View {
        StrandCard {
            VStack(alignment: .leading, spacing: NoopMetrics.cardInnerSpacing) {
                Text("Haptic Alarm").font(StrandFont.title2).foregroundStyle(StrandPalette.textPrimary)
                Toggle(String(localized: "Wake me with a strap buzz"), isOn: $enabled)
                    .tint(StrandPalette.accent)
                Picker(String(localized: "Alarm mode"), selection: $mode) {
                    Text("Exact time").tag("exact")
                    Text("Sleep goal").tag("sleepGoal")
                    Text("Recovery").tag("recovery")
                }
                HStack {
                    Text(mode == "exact" ? String(localized: "Wake at") : String(localized: "Latest wake deadline"))
                        .font(StrandFont.body).foregroundStyle(StrandPalette.textPrimary)
                    Spacer(minLength: 0)
                    DatePicker("", selection: minuteBinding($wakeMinutes), displayedComponents: .hourAndMinute)
                        .labelsHidden().accessibilityLabel("Alarm deadline")
                }
                if mode != "exact" {
                    Text("The wake window is the final hour before your deadline. This build cannot observe fresh sleep-goal or Recovery progress overnight, so the strap uses the deadline. Adaptive waking is unavailable.")
                        .font(StrandFont.footnote).foregroundStyle(StrandPalette.statusWarning)
                }
                Text("The alarm is a silent wrist vibration. Saving sends the existing strap command; confirmation depends on the strap's reply.")
                    .font(StrandFont.footnote).foregroundStyle(StrandPalette.textSecondary)
                Button(String(localized: "Save alarm")) {
                    savedMessage = model.saveSleepPlannerAlarm(enabled: enabled, minutes: wakeMinutes,
                                                               weekdays: weekdays, overrides: overrides, mode: mode)
                    showSaveError = !savedMessage
                }.buttonStyle(NoopButtonStyle(.primary, fullWidth: true))
                if savedMessage {
                    Text("Settings saved. Check the status above for strap confirmation.")
                        .font(StrandFont.footnote).foregroundStyle(StrandPalette.textSecondary)
                }
            }
        }
    }

    private var daySchedule: some View {
        StrandCard {
            VStack(alignment: .leading, spacing: NoopMetrics.cardInnerSpacing) {
                Text("Weekly schedule").font(StrandFont.title2).foregroundStyle(StrandPalette.textPrimary)
                Text("These times move your strap alarm AND the evening reminder. Save alarm to apply time and day changes.")
                    .font(StrandFont.footnote).foregroundStyle(StrandPalette.textSecondary)
                ForEach(Self.weekdayOrder, id: \.self) { day in
                    DisclosureGroup {
                        VStack(alignment: .leading, spacing: NoopMetrics.cardInnerSpacing) {
                            Toggle(String(localized: "Alarm on this day"), isOn: Binding(
                                get: { Self.alarmWeekdayIsSelected(day, in: weekdays) },
                                set: { _ in weekdays = Self.alarmToggledWeekday(day, in: weekdays) }))
                            DatePicker(String(localized: "Day's deadline"), selection: dayTimeBinding(day),
                                       displayedComponents: .hourAndMinute)
                            Picker(String(localized: "Day's sleep goal"), selection: Binding(
                                get: { planner.goalOverrides[day] ?? planner.goalPercent },
                                set: { planner.goalOverrides[day] = $0; WindDownNudge.reschedule() })) {
                                Text("Peak · 100%").tag(100)
                                Text("Perform · 85%").tag(85)
                                Text("Get By · 70%").tag(70)
                            }
                            if overrides[day] != nil || planner.goalOverrides[day] != nil {
                                Button(String(localized: "Use default for this day")) {
                                    overrides[day] = nil
                                    planner.goalOverrides[day] = nil
                                    WindDownNudge.reschedule()
                                }
                            }
                        }.padding(.top, NoopMetrics.space2)
                    } label: {
                        HStack {
                            Text(Self.weekdayName(day)).font(StrandFont.body)
                            Spacer(minLength: 0)
                            Text(Self.alarmWeekdayIsSelected(day, in: weekdays)
                                 ? timeLabel(overrides[day] ?? wakeMinutes) : String(localized: "Off"))
                                .font(StrandFont.subhead).foregroundStyle(StrandPalette.textSecondary)
                        }
                    }
                }
            }
        }
    }

    private var reminderCard: some View {
        StrandCard {
            VStack(alignment: .leading, spacing: NoopMetrics.cardInnerSpacing) {
                Text("Bedtime reminders").font(StrandFont.title2).foregroundStyle(StrandPalette.textPrimary)
                Toggle(String(localized: "Wind-down reminder"), isOn: $windDownOn)
                    .tint(StrandPalette.restColor)
                    .onChangeCompat(of: windDownOn) { on in
                        WindDownNudge.setEnabled(on) { outcome in
                            if outcome == .denied { windDownOn = false; showNotifDenied = true }
                        }
                    }
                Toggle(String(localized: "Sleep Debt reduction nudge"), isOn: $planner.debtReminderEnabled)
                    .tint(StrandPalette.restColor)
                    .onChangeCompat(of: planner.debtReminderEnabled) { on in
                        if on {
                            WindDownNudge.authorizeDebtReminder { allowed in
                                if !allowed { planner.debtReminderEnabled = false; showNotifDenied = true }
                            }
                        } else { WindDownNudge.reschedule() }
                    }
                Text("A calm reminder before the suggested bedtime, following the saved day and goal. Debt advice is included when recent debt is at least an hour. Newly authored guidance.")
                    .font(StrandFont.footnote).foregroundStyle(StrandPalette.textSecondary)
                Text("Bedtime reminders cover the next seven selected wakes and refresh when the app opens.")
                    .font(StrandFont.footnote).foregroundStyle(StrandPalette.textSecondary)
            }
        }
    }

    private var phoneCapabilityCard: some View {
        StrandCard {
            VStack(alignment: .leading, spacing: NoopMetrics.cardInnerSpacing) {
                Text("Phone wake reminder").font(StrandFont.headline).foregroundStyle(StrandPalette.textPrimary)
                Text("On iPhone, NOOP schedules notifications for the next 28 selected wakes and refreshes them when opened. Focus, silent mode, and notification permissions can suppress them. It cannot run a continuous sleep detector or guarantee a loud wake in the background. Keep a Clock alarm as your backup. macOS does not schedule a phone wake reminder.")
                    .font(StrandFont.footnote).foregroundStyle(StrandPalette.textSecondary)
            }
        }
    }

    private func earlyWakeCard(now: Date) -> some View {
        StrandCard(tint: StrandPalette.restColor) {
            VStack(alignment: .leading, spacing: NoopMetrics.cardInnerSpacing) {
                Text("Awake already?").font(StrandFont.headline).foregroundStyle(StrandPalette.textPrimary)
                Text("Cancel this morning's buzz while keeping future wake days.")
                    .font(StrandFont.footnote).foregroundStyle(StrandPalette.textSecondary)
                Button(String(localized: "Cancel this wake")) {
                    if !model.skipNextSleepPlannerAlarm(from: now) { showSaveError = true }
                }.buttonStyle(NoopButtonStyle(.secondary, fullWidth: true))
            }
        }
    }

    private func alarmStatus(_ snapshot: SleepPlannerSnapshot) -> String {
        if snapshot.alarmConfirmed { return String(localized: "Confirmed on strap") }
        if snapshot.alarmSent { return String(localized: "Sent to strap · awaiting confirmation") }
        return String(localized: "Saved locally · strap not confirmed")
    }

    private func timeColumn(_ label: String, date: Date) -> some View {
        VStack(alignment: .leading, spacing: NoopMetrics.spaceHalf) {
            Text(label).font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
            Text(date, format: .dateTime.hour().minute().locale(AppLanguage.activeLocale))
                .font(StrandFont.title1).foregroundStyle(StrandPalette.restBright)
            Text(date, format: .dateTime.weekday(.abbreviated).day().month(.abbreviated).locale(AppLanguage.activeLocale))
                .font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
        }
    }

    private func minuteBinding(_ binding: Binding<Int>) -> Binding<Date> {
        Binding(get: {
            Calendar.current.date(bySettingHour: binding.wrappedValue / 60, minute: binding.wrappedValue % 60,
                                  second: 0, of: Date()) ?? Date()
        }, set: { date in
            let parts = Calendar.current.dateComponents([.hour, .minute], from: date)
            binding.wrappedValue = (parts.hour ?? 0) * 60 + (parts.minute ?? 0)
            savedMessage = false
        })
    }

    private func dayTimeBinding(_ day: Int) -> Binding<Date> {
        minuteBinding(Binding(get: { overrides[day] ?? wakeMinutes }, set: { overrides[day] = $0 }))
    }

    private func timeLabel(_ minutes: Int) -> String {
        let date = Calendar.current.date(bySettingHour: minutes / 60, minute: minutes % 60, second: 0, of: Date()) ?? Date()
        let formatter = DateFormatter()
        formatter.locale = AppLanguage.activeLocale
        formatter.setLocalizedDateFormatFromTemplate("j:mm")
        return formatter.string(from: date)
    }

    private func duration(_ minutes: Int) -> String {
        let formatter = DateComponentsFormatter()
        var calendar = Calendar.current
        calendar.locale = AppLanguage.activeLocale
        formatter.calendar = calendar
        formatter.allowedUnits = [.hour, .minute]
        formatter.unitsStyle = .abbreviated
        return formatter.string(from: TimeInterval(minutes * 60)) ?? "—"
    }

    private func countdown(until date: Date, now: Date) -> String {
        let minutes = max(Int(date.timeIntervalSince(now) / 60), 0)
        let span = duration(minutes)
        return String(localized: "Alarm in \(span)")
    }

    private static func weekdayName(_ day: Int) -> String {
        let formatter = DateFormatter()
        formatter.locale = AppLanguage.activeLocale
        return formatter.weekdaySymbols[day - 1]
    }

    private static func openNotificationSettings() {
        #if os(iOS)
        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
        #elseif os(macOS)
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.notifications") { NSWorkspace.shared.open(url) }
        #endif
    }

    nonisolated static func alarmWeekdayIsSelected(_ dow: Int, in days: Set<Int>) -> Bool {
        days.isEmpty || days.contains(dow)
    }

    /// Toggle one weekday, normalising "every day" at both ends so the empty set always means every day.
    nonisolated static func alarmToggledWeekday(_ dow: Int, in days: Set<Int>) -> Set<Int> {
        var next: Set<Int>
        if days.isEmpty {
            next = Set(1...7)
            next.remove(dow)
        } else if days.contains(dow) {
            next = days
            next.remove(dow)
        } else {
            next = days
            next.insert(dow)
        }
        return next.count == 7 ? [] : next
    }

    /// Human-readable summary of the selection.
    nonisolated static func alarmWeekdaySummary(_ days: Set<Int>) -> String {
        if days.isEmpty || days.count == 7 { return String(localized: "Every day") }
        if days == Set(2...6) { return String(localized: "Weekdays") }
        if days == Set([1, 7]) { return String(localized: "Weekends") }
        return weekdayOrder.filter { days.contains($0) }.map { alarmWeekdayShort($0) }.joined(separator: ", ")
    }

    /// One-letter day chip. Derived from the localized short name so the initials follow the
    /// language (and Tue/Thu or Sat/Sun never share a single collision-prone key). English output
    /// is byte-identical to the old hardcoded initials.
    private static func alarmWeekdayInitial(_ dow: Int) -> String {
        let short = alarmWeekdayShort(dow)
        return short == "?" ? "?" : String(short.prefix(1))
    }

    nonisolated private static func alarmWeekdayShort(_ dow: Int) -> String {
        switch dow {
        case 1: return String(localized: "Sun")
        case 2: return String(localized: "Mon")
        case 3: return String(localized: "Tue")
        case 4: return String(localized: "Wed")
        case 5: return String(localized: "Thu")
        case 6: return String(localized: "Fri")
        case 7: return String(localized: "Sat")
        default: return "?"
        }
    }
}
