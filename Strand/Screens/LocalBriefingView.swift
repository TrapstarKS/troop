import SwiftUI
import StrandDesign
import StrandAnalytics

struct CoachDestinationView: View {
    @EnvironmentObject private var coach: AICoachEngine
    var body: some View {
        if coach.hasKey { CoachView() } else { LocalBriefingView() }
    }
}

struct LocalBriefingView: View {
    @EnvironmentObject private var repo: Repository
    var notificationContext: LocalNotificationContext? = nil

    var body: some View {
        let now = Date()
        let today = Repository.localDayKey(now)
        let row = Repository.resolveToday(days: repo.days, logicalKey: Repository.logicalDayKey(now), localKey: today)
        let streak = StreakCalculator.streaks(dayKeys: repo.days.map(\.day), qualified: repo.days.map { $0.recovery != nil }, today: today).current
        let figures = row.flatMap { repo.importedSleep[$0.day] }
        let current = LocalRecordedReport(day: row?.day ?? today,
            recovery: RecoveryStrainDetailLogic.recoveryPercent(row?.recovery),
            sleepMinutes: row?.totalSleepMin.map { Int($0.rounded()) },
            strainTenths: row?.strain.map { Int(($0 * 2.1).rounded()) }, streak: streak,
            sleepNeedMinutes: figures?.needMin.map { Int($0.rounded()) },
            sleepDebtMinutes: figures?.debtMin.map { Int($0.rounded()) })
        let report = LocalRecordedReport.forDisplay(context: notificationContext, current: current)
        ScreenScaffold(title: "Daily Outlook", subtitle: "An offline summary of your recorded data",
                       onRefresh: { await repo.refresh() }) {
            if let message = notificationContext?.message {
                NoopCard { Text(message).font(StrandFont.body).foregroundStyle(StrandPalette.textPrimary) }
            }
            NoopCard {
                VStack(alignment: .leading, spacing: NoopMetrics.space3) {
                    Text("Daily Outlook").font(StrandFont.title2).foregroundStyle(StrandPalette.textPrimary)
                    Text(LocalBriefingCopy.summary(
                        recovery: report?.recovery,
                        sleepMinutes: report?.sleepMinutes,
                        strainTenths: nil, streak: report?.streak ?? 0))
                        .font(StrandFont.body).foregroundStyle(StrandPalette.textPrimary)
                    if let day = report?.day ?? notificationContext?.day { Text(day).font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary) }
                }
            }
            NoopCard {
                VStack(alignment: .leading, spacing: NoopMetrics.space3) {
                    Text("Day in Review").font(StrandFont.title2).foregroundStyle(StrandPalette.textPrimary)
                    Text(LocalBriefingCopy.summary(
                        recovery: report?.recovery,
                        sleepMinutes: report?.sleepMinutes,
                        strainTenths: report?.strainTenths, streak: report?.streak ?? 0))
                        .font(StrandFont.body).foregroundStyle(StrandPalette.textPrimary)
                    if let debt = report?.sleepDebtMinutes {
                        Text("Recorded sleep debt: \(debt) min").font(StrandFont.caption)
                    }
                    if let need = report?.sleepNeedMinutes {
                        Text("Recorded sleep need: \(need) min").font(StrandFont.caption)
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

// The shell passes the dated payload here for saved plan/workout notices. Opening a notice never
// silently resolves the current week or a different activity from the latest database contents.
struct LocalRecordedNoticeView: View {
    let notificationContext: LocalNotificationContext

    var body: some View {
        ScreenScaffold(title: notificationContext.route == "weekly_plan" ? "Weekly Plan" : "Workouts") {
            NoopCard {
                VStack(alignment: .leading, spacing: NoopMetrics.space3) {
                    if let message = notificationContext.message {
                        Text(message).font(StrandFont.body).foregroundStyle(StrandPalette.textPrimary)
                    }
                    if let day = notificationContext.weekKey ?? notificationContext.day {
                        Text(day).font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
                    }
                    if let start = notificationContext.workoutStartSec {
                        Text(Date(timeIntervalSince1970: Double(start)), style: .date)
                            .font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
                    }
                }
            }
        }
    }
}
