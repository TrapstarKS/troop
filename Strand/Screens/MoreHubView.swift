import SwiftUI
import StrandDesign
import StrandAnalytics

enum SettingsCategory: String, CaseIterable, Identifiable, Hashable {
    case profile, appearance, device, data, scoring, features, diagnostics, about, all
    var id: String { rawValue }
    var title: String {
        switch self {
        case .profile: return String(localized: "Profile & units")
        case .appearance: return String(localized: "Appearance")
        case .device: return String(localized: "Device & capture")
        case .data: return String(localized: "Data & backup")
        case .scoring: return String(localized: "Scoring & baselines")
        case .features: return String(localized: "Features")
        case .diagnostics: return String(localized: "Diagnostics & experiments")
        case .about: return String(localized: "Help & about")
        case .all: return String(localized: "All settings")
        }
    }
    var icon: String {
        switch self {
        case .profile: return "person.crop.circle"
        case .appearance: return "paintpalette"
        case .device: return "sensor.tag.radiowaves.forward"
        case .data: return "externaldrive"
        case .scoring: return "chart.xyaxis.line"
        case .features: return "slider.horizontal.3"
        case .diagnostics: return "stethoscope"
        case .about: return "questionmark.circle"
        case .all: return "list.bullet"
        }
    }
}

struct MoreHubView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var repo: Repository
    @EnvironmentObject private var profile: ProfileStore
    @EnvironmentObject private var live: LiveState

    var body: some View {
        ScreenScaffold(title: "More", subtitle: "Your profile, device and preferences",
                       onRefresh: { await repo.refresh() }) {
            NavigationLink(value: MoreHubRoute.settings(.profile)) {
                NoopCard {
                    HStack(spacing: NoopMetrics.space4) {
                        ProfileAvatarView(imageData: profile.avatarImageData, size: NoopMetrics.controlHeight)
                        VStack(alignment: .leading, spacing: NoopMetrics.space1) {
                            Text("Your profile").font(StrandFont.title2).foregroundStyle(StrandPalette.textPrimary)
                            Text("\(streak)-day recorded recovery streak")
                                .font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
                        }
                        Spacer()
                        Image(systemName: "chevron.right").foregroundStyle(StrandPalette.textSecondary)
                    }
                }
            }.buttonStyle(.plain)
            group("Device") {
                row("Devices", "sensor.tag.radiowaves.forward", .devices,
                    detail: live.connectionStatusLabel)
                row("Power saving", "battery.25", .powerSaving)
                row("Alarms", "alarm", .alarms)
            }
            group("App settings") {
                row("Settings", "gearshape", .settings(nil))
                row("Notifications", "bell", .notifications)
                row("Automations", "wand.and.stars", .automations)
            }
            group("Integrations") {
                row("Apple Health", "heart", .appleHealth)
                row("Data Sources", "square.and.arrow.down", .dataSources)
                row("Coach settings", "sparkles", .coachSettings)
                row("Daily Outlook", "text.bubble", .briefing)
                #if os(iOS)
                row("Shortcuts Export", "square.and.arrow.up", .shortcutsExport)
                row("Siri & Shortcuts", "mic", .siriShortcuts)
                #endif
            }
            group("Your data") {
                row("Backup & Sync", "externaldrive", .backupSync)
                row("Your Data, Fused", "square.stack.3d.up", .fusedRecord)
                row("Mi Band", "figure.walk", .miBand)
            }
            group("Help & about") {
                row("Help & about", "questionmark.circle", .settings(.about))
                row("NOOP Limitations", "list.bullet.rectangle", .limitations)
                row("Test Centre", "stethoscope", .testCentre)
            }
            group("Explore all features") {
                ForEach(MoreHubRoute.legacy, id: \.self) { route in
                    row(route.title, route.icon, route)
                }
            }
        }
        .navigationDestination(for: MoreHubRoute.self) { $0.destination }
    }

    private var streak: Int {
        StreakCalculator.streaks(dayKeys: repo.days.map(\.day),
                                qualified: repo.days.map { $0.recovery != nil },
                                today: Repository.localDayKey(Date())).current
    }

    private func group<Content: View>(_ title: LocalizedStringKey, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: NoopMetrics.space3) {
            SectionHeader(title)
            NoopCard { VStack(spacing: NoopMetrics.space3) { content() } }
        }
    }

    private func row(_ title: LocalizedStringKey, _ icon: String, _ route: MoreHubRoute,
                     detail: String? = nil) -> some View {
        NavigationLink(value: route) {
            HStack(spacing: NoopMetrics.space3) {
                Image(systemName: icon).font(StrandFont.body).foregroundStyle(StrandPalette.textSecondary)
                    .frame(width: NoopMetrics.space6)
                VStack(alignment: .leading, spacing: NoopMetrics.space1) {
                    Text(title).font(StrandFont.body).foregroundStyle(StrandPalette.textPrimary)
                    if let detail { Text(detail).font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary) }
                }
                Spacer()
                Image(systemName: "chevron.right").font(StrandFont.caption).foregroundStyle(StrandPalette.textTertiary)
            }.frame(minHeight: NoopMetrics.controlHeight).contentShape(Rectangle())
        }.buttonStyle(.plain)
    }
}

private enum MoreHubRoute: Hashable {
    case settings(SettingsCategory?), devices, powerSaving, alarms, notifications, automations
    case appleHealth, dataSources, coachSettings, briefing, backupSync, fusedRecord, miBand, limitations, testCentre
    case insightsHub, intelligence, insights, explore, compare, live, workouts, liftLog, health, labBook, stress, breathe, intervals, rhythm, sleep, trends
    #if os(iOS)
    case shortcutsExport, siriShortcuts
    #endif

    static let legacy: [Self] = [.insightsHub, .intelligence, .insights, .explore, .compare, .trends,
        .sleep, .live, .workouts, .liftLog, .health, .labBook, .stress, .breathe, .intervals, .rhythm]

    var title: LocalizedStringKey {
        switch self {
        case .insightsHub: return "What Moves You"
        case .intelligence: return "Intelligence"
        case .insights: return "Insights"
        case .explore: return "Explore"
        case .compare: return "Compare"
        case .trends: return "Trends"
        case .sleep: return "Sleep"
        case .live: return "Live"
        case .workouts: return "Workouts"
        case .liftLog: return "Lift Log"
        case .health: return "Health"
        case .labBook: return "Lab Book"
        case .stress: return "Stress"
        case .breathe: return "Breathe"
        case .intervals: return "Intervals"
        case .rhythm: return "Rhythm"
        default: return "More"
        }
    }
    var icon: String {
        switch self {
        case .live: return "waveform.path.ecg"
        case .sleep: return "moon"
        case .workouts, .liftLog: return "figure.run"
        case .health, .stress, .rhythm: return "heart"
        case .breathe: return "wind"
        case .intervals: return "timer"
        default: return "chart.xyaxis.line"
        }
    }

    @ViewBuilder var destination: some View {
        switch self {
        case .settings(let category): SettingsView(category: category)
        case .devices: DevicesView()
        case .powerSaving: PowerSavingView()
        case .alarms: SmartAlarmView()
        case .notifications: LocalNotificationsView()
        case .automations: AutomationsView()
        case .appleHealth: AppleHealthView()
        case .dataSources: DataSourcesView()
        case .coachSettings: CoachSettingsView()
        case .briefing: LocalBriefingView()
        case .backupSync: BackupSyncView()
        case .fusedRecord: FusedRecordHost()
        case .miBand: XiaomiBandView()
        case .limitations: NoopLimitationsView()
        case .testCentre: TestCentreView()
        case .insightsHub: InsightsHubView()
        case .intelligence: IntelligenceView()
        case .insights: InsightsView()
        case .explore: MetricExplorerView()
        case .compare: CompareView()
        case .trends: TrendsView()
        case .sleep: SleepView()
        case .live: LiveView()
        case .workouts: WorkoutsView()
        case .liftLog: LiftLogView()
        case .health: HealthView()
        case .labBook: LabBookView()
        case .stress: StressView()
        case .breathe: BreathingView()
        case .intervals: IntervalTimerView()
        case .rhythm: RhythmHost()
        #if os(iOS)
        case .shortcutsExport: ShortcutExportSettingsView()
        case .siriShortcuts: SiriShortcutsSettingsView()
        #endif
        }
    }
}
