import SwiftUI
import StrandDesign
import StrandAnalytics

struct CoachDestinationView: View {
    @EnvironmentObject private var coach: AICoachEngine
    var body: some View {
        if coach.isConfigured { CoachView() } else { LocalBriefingView() }
    }
}

struct LocalBriefingView: View {
    @EnvironmentObject private var repo: Repository

    var body: some View {
        let now = Date()
        let today = Repository.localDayKey(now)
        let row = Repository.resolveToday(days: repo.days, logicalKey: Repository.logicalDayKey(now), localKey: today)
        let streak = StreakCalculator.streaks(dayKeys: repo.days.map(\.day), qualified: repo.days.map { $0.recovery != nil }, today: today).current
        let figures = row.flatMap { repo.importedSleep[$0.day] }
        ScreenScaffold(title: "Daily Outlook", subtitle: "An offline summary of your recorded data",
                       onRefresh: { await repo.refresh() }) {
            NoopCard {
                VStack(alignment: .leading, spacing: NoopMetrics.space3) {
                    Text("Daily Outlook").font(StrandFont.title2).foregroundStyle(StrandPalette.textPrimary)
                    Text(LocalBriefingCopy.summary(
                        recovery: row?.recovery.map { Int($0.rounded()) },
                        sleepMinutes: row?.totalSleepMin.map { Int($0.rounded()) },
                        strainTenths: nil, streak: streak))
                        .font(StrandFont.body).foregroundStyle(StrandPalette.textPrimary)
                    if let row { Text(row.day).font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary) }
                }
            }
            NoopCard {
                VStack(alignment: .leading, spacing: NoopMetrics.space3) {
                    Text("Day in Review").font(StrandFont.title2).foregroundStyle(StrandPalette.textPrimary)
                    Text(LocalBriefingCopy.summary(
                        recovery: row?.recovery.map { Int($0.rounded()) },
                        sleepMinutes: row?.totalSleepMin.map { Int($0.rounded()) },
                        strainTenths: row?.strain.map { Int(($0 * 2.1).rounded()) }, streak: streak))
                        .font(StrandFont.body).foregroundStyle(StrandPalette.textPrimary)
                    if let debt = figures?.debtMin {
                        Text("Recorded sleep debt: \(Int(debt.rounded())) min").font(StrandFont.caption)
                    }
                    if let need = figures?.needMin {
                        Text("Recorded sleep need: \(Int(need.rounded())) min").font(StrandFont.caption)
                    } else {
                        Text("Tonight's sleep plan is available in Alarms when enough local data exists.")
                            .font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
                    }
                    NavigationLink { SmartAlarmView() } label: { Label("Alarms", systemImage: "alarm") }
                }
            }
            NoopCard {
                VStack(alignment: .leading, spacing: NoopMetrics.space3) {
                    Text("This briefing uses only data saved on this device. Missing readings stay unavailable. Local estimates can differ from the official app.")
                        .font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
                    NavigationLink { CoachSettingsView() } label: { Label("Coach settings", systemImage: "sparkles") }
                    NavigationLink { LocalNotificationsView() } label: { Label("Notifications", systemImage: "bell") }
                }
            }
        }
    }
}
