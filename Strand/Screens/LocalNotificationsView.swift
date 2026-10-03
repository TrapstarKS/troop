import SwiftUI
import UserNotifications
import StrandDesign
import StrandAnalytics
#if os(iOS)
import UIKit
#else
import AppKit
#endif

struct LocalNotificationsView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var behavior: BehaviorStore
    @Environment(\.openURL) private var openURL
    @Environment(\.scenePhase) private var scenePhase
    @AppStorage("notif.quietHoursEnabled") private var quiet = false
    @AppStorage("notif.quietStartMinutes") private var quietStart = 1320
    @AppStorage("notif.quietEndMinutes") private var quietEnd = 420
    @AppStorage("windDown.enabled") private var windDown = false
    @AppStorage("sleepPlanner.debtReminderEnabled") private var debtReminder = 0
    @AppStorage("notif.masterEnabled") private var wristAlerts = false
    @AppStorage("inactivity.enabled") private var inactivity = false
    @State private var authorization: UNAuthorizationStatus = .notDetermined
    @State private var denied = false
    @AppStorage("coachBrief.enabled") private var providerBrief = false
    #if os(iOS)
    @AppStorage(UnitPrefs.liveActivityKey) private var liveHeartRate = true
    @AppStorage(UnitPrefs.liftLiveActivityKey) private var liveLift = true
    @AppStorage(UnitPrefs.syncLiveActivityKey) private var liveSync = true
    #endif

    var body: some View {
        ScreenScaffold(title: "Notifications", subtitle: "Local alerts, on your terms") {
            NoopCard {
                VStack(alignment: .leading, spacing: NoopMetrics.space3) {
                    Text(permissionLabel).font(StrandFont.body).foregroundStyle(StrandPalette.textPrimary)
                    Button("Allow notifications") { Task { await requestPermission() } }
                        .font(StrandFont.body).foregroundStyle(StrandPalette.accent)
                    if authorization == .denied {
                        Button("Open notification settings") { openSystemSettings() }
                    }
                }
            }
            section("Daily reports") {
                family(.recoveryReady, "Recovery ready")
                family(.sleepReady, "Sleep ready")
                family(.strainReady, "Strain ready")
                family(.morningRecap, "Morning recap")
                family(.workoutReady, "Post-workout summary")
                Text("Ready reports wait for a recorded wake and completed data refresh. Rescoring the same night does not send another alert.")
                    .font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
            }
            section("Local coaching") {
                family(.dailyOutlook, "Daily Outlook")
                family(.dayInReview, "Day in Review")
                family(.streakSummary, "Streak summary")
                family(.weeklyCheckIn, "Weekly Plan Friday check-in")
                family(.weeklyRecap, "Weekly Plan Monday recap")
                Text("Evening summaries are checked after 20:00. Plan messages need a saved plan. Delivery is best effort when the app can run.")
                    .font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
                Toggle("Provider morning brief", isOn: Binding(get: { providerBrief }, set: { on in
                    CoachBriefScheduler.setEnabled(on, generateBrief: { await model.coach.generateBrief() }) { result in
                        providerBrief = result == .scheduled
                        denied = result == .denied
                    }
                }))
                Text("The provider brief uses your configured Coach provider and consent. It can send data off this device; offline summaries do not.")
                    .font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
                NavigationLink { CoachSettingsView() } label: { Text("BYOK Coach briefing settings") }
            }
            section("Device") {
                Toggle("Battery alerts", isOn: authorizedBinding($behavior.batteryAlerts))
                Toggle("Predictive battery alerts", isOn: authorizedBinding($behavior.batteryPredictiveAlerts))
                    .disabled(!behavior.batteryAlerts)
                Toggle("Charging needs attention", isOn: .constant(false)).disabled(true)
                Text("Charging fault alerts are unavailable because the device data does not establish a charger fault.")
                    .font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
                family(.disconnected, "Device disconnected")
                family(.wearReminder, "Wear reminder")
                Text("Battery alerts include low charge, full charge and runtime warnings. Connection and wear reminders use observed device state.")
                    .font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
            }
            section("Sleep & health") {
                Toggle("Wind-down reminder", isOn: Binding(get: { windDown }, set: { on in
                    WindDownNudge.setEnabled(on) { result in
                        windDown = result == .scheduled
                        denied = result == .denied
                    }
                }))
                Toggle("Sleep debt reminder", isOn: authorizedBinding(Binding(
                    get: { debtReminder == 1 }, set: { debtReminder = $0 ? 1 : 0 })))
                Toggle("Illness watch", isOn: authorizedBinding($behavior.illnessWatch))
                Toggle("Strain target nudge", isOn: authorizedBinding($behavior.strainTargetNudge))
                Toggle("Inactivity reminder", isOn: authorizedBinding($inactivity))
                Toggle("Stress check-ins", isOn: $behavior.stressCheckIn)
                NavigationLink { SmartAlarmView() } label: { Text("Alarms") }
                NavigationLink { AutomationsView() } label: { Text("Automations") }
                Text("Sleep, alarm and Health Monitor policies use their own settings. An alarm deadline keeps its configured behavior during quiet hours.")
                    .font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
            }
            section("Wrist alerts") {
                Toggle("Wrist alerts", isOn: authorizedBinding($wristAlerts))
                #if os(macOS)
                NavigationLink { NotificationSettingsView() } label: { Text("App notification mirroring") }
                #else
                Toggle("Live heart rate", isOn: $liveHeartRate)
                Toggle("Lift Log session", isOn: $liveLift)
                Toggle("Strap sync", isOn: $liveSync)
                Text("Live notification switches hide their status display. Recording and scoring continue.")
                    .font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
                #endif
            }
            section("Quiet hours") {
                Toggle("Quiet hours", isOn: $quiet)
                if quiet {
                    DatePicker("Start", selection: timeBinding($quietStart), displayedComponents: .hourAndMinute)
                    DatePicker("End", selection: timeBinding($quietEnd), displayedComponents: .hourAndMinute)
                    Text("Equal start and end times turn off the quiet-hour window.")
                        .font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
                }
            }
        }
        .task { authorization = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus }
        .onChangeCompat(of: scenePhase) { phase in
            if phase == .active {
                Task { authorization = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus }
            }
        }
        .alert("Notifications are off", isPresented: $denied) {
            Button("Open notification settings") { openSystemSettings() }
            Button("OK", role: .cancel) { }
        } message: { Text("Allow alerts in system settings before enabling this notification.") }
    }

    private var permissionLabel: String {
        switch authorization {
        case .authorized, .provisional, .ephemeral: return String(localized: "System notifications allowed")
        case .denied: return String(localized: "System notifications blocked")
        default: return String(localized: "Notification permission has not been requested")
        }
    }

    private func section<Content: View>(_ title: LocalizedStringKey, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: NoopMetrics.space3) {
            SectionHeader(title)
            NoopCard { VStack(alignment: .leading, spacing: NoopMetrics.space4) { content() }.font(StrandFont.body) }
        }
    }

    private func family(_ family: LocalNotificationFamily, _ title: LocalizedStringKey) -> some View {
        LocalFamilyToggle(family: family, title: title, denied: $denied)
    }

    private func authorizedBinding(_ binding: Binding<Bool>) -> Binding<Bool> {
        Binding(get: { binding.wrappedValue }, set: { on in
            guard on else { binding.wrappedValue = false; return }
            Task {
                let allowed = await requestPermission()
                binding.wrappedValue = allowed
                denied = !allowed
            }
        })
    }

    @discardableResult
    private func requestPermission() async -> Bool {
        let center = UNUserNotificationCenter.current()
        let settings = await center.notificationSettings()
        if settings.authorizationStatus == .notDetermined {
            _ = try? await center.requestAuthorization(options: [.alert, .sound])
        }
        authorization = await center.notificationSettings().authorizationStatus
        return [.authorized, .provisional, .ephemeral].contains(authorization)
    }

    private func openSystemSettings() {
        #if os(iOS)
        if let url = URL(string: UIApplication.openNotificationSettingsURLString) { openURL(url) }
        #else
        if let url = URL(string: "x-apple.systempreferences:com.apple.Notifications-Settings.extension") { openURL(url) }
        #endif
    }

    private func timeBinding(_ minutes: Binding<Int>) -> Binding<Date> {
        Binding(get: {
            Calendar.current.date(bySettingHour: min(max(minutes.wrappedValue, 0), 1439) / 60,
                                  minute: min(max(minutes.wrappedValue, 0), 1439) % 60,
                                  second: 0, of: Date()) ?? Date()
        }, set: {
            let parts = Calendar.current.dateComponents([.hour, .minute], from: $0)
            minutes.wrappedValue = (parts.hour ?? 0) * 60 + (parts.minute ?? 0)
        })
    }
}

private struct LocalFamilyToggle: View {
    let family: LocalNotificationFamily
    let title: LocalizedStringKey
    @Binding var denied: Bool
    @AppStorage private var enabled: Bool
    @EnvironmentObject private var model: AppModel

    init(family: LocalNotificationFamily, title: LocalizedStringKey, denied: Binding<Bool>) {
        self.family = family
        self.title = title
        _denied = denied
        _enabled = AppStorage(wrappedValue: false, family.enabledKey)
    }

    var body: some View {
        Toggle(title, isOn: Binding(get: { enabled }, set: { on in
            guard on else {
                enabled = false
                return
            }
            Task {
                let center = UNUserNotificationCenter.current()
                if await center.notificationSettings().authorizationStatus == .notDetermined {
                    _ = try? await center.requestAuthorization(options: [.alert, .sound])
                }
                let status = await center.notificationSettings().authorizationStatus
                enabled = [.authorized, .provisional, .ephemeral].contains(status)
                denied = !enabled
                if enabled {
                    if family == .workoutReady,
                       let newest = model.repo.workouts.max(by: { $0.startTs < $1.startTs }) {
                        UserDefaults.standard.set("workoutReady:\(newest.startTs)", forKey: family.lastEventKey)
                    }
                    await model.localNotifications?.evaluate()
                }
            }
        }))
    }
}
