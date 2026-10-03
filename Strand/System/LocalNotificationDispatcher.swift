import Foundation
import Combine
import UserNotifications
import StrandAnalytics

enum LocalNotificationFamily: String, CaseIterable, Identifiable {
    case recoveryReady, sleepReady, strainReady, workoutReady, morningRecap, dailyOutlook, dayInReview
    case streakSummary, disconnected, wearReminder, weeklyCheckIn, weeklyRecap
    var id: String { rawValue }
    var enabledKey: String { "localNotifications.\(rawValue).enabled" }
    var lastEventKey: String { "localNotifications.\(rawValue).lastEventKey" }
}

struct WeeklyPlanNotification {
    let weekKey: String
    let checkIn: String?
    let recap: String?
}

@MainActor
protocol WeeklyPlanNotificationProviding {
    func notificationSummary(now: Date) -> WeeklyPlanNotification?
}

enum LocalNotificationPreferences {
    static func isQuiet(now: Date = Date(), defaults: UserDefaults = .standard) -> Bool {
        let parts = Calendar.current.dateComponents([.hour, .minute], from: now)
        return LocalNotificationPolicy.isQuiet(
            minute: (parts.hour ?? 0) * 60 + (parts.minute ?? 0),
            start: defaults.object(forKey: "notif.quietStartMinutes") as? Int ?? 1320,
            end: defaults.object(forKey: "notif.quietEndMinutes") as? Int ?? 420,
            enabled: defaults.bool(forKey: "notif.quietHoursEnabled"))
    }
}

@MainActor
final class LocalNotificationDispatcher {
    private weak var model: AppModel?
    var weeklyPlanProvider: WeeklyPlanNotificationProviding?
    private var observers: Set<AnyCancellable> = []
    private var timer: Timer?
    private var inFlight: Set<String> = []
    private var disconnectedAt: Date?
    private var offWristAt: Date?
    private var hadConnection = false
    private let defaults: UserDefaults
    private let clock: () -> Date

    init(model: AppModel, defaults: UserDefaults = .standard, clock: @escaping () -> Date = Date.init) {
        self.model = model
        self.defaults = defaults
        self.clock = clock
        model.repo.$refreshSeq.sink { [weak self] _ in
            Task { @MainActor in await self?.evaluate() }
        }.store(in: &observers)
        model.live.$connected.sink { [weak self, weak model] connected in
            guard let self, let model else { return }
            guard model.live.activeIsWhoop else {
                self.hadConnection = false
                self.disconnectedAt = nil
                self.offWristAt = nil
                return
            }
            if connected {
                self.hadConnection = true
                self.disconnectedAt = nil
            } else {
                self.offWristAt = nil
                if self.hadConnection, self.disconnectedAt == nil { self.disconnectedAt = self.clock() }
            }
        }.store(in: &observers)
        model.live.$worn.dropFirst().sink { [weak self, weak model] worn in
            guard let self, let model else { return }
            guard model.live.connected, model.live.activeIsWhoop else {
                self.offWristAt = nil
                return
            }
            self.offWristAt = worn ? nil : self.offWristAt ?? self.clock()
        }.store(in: &observers)
        model.live.$activeIsWhoop.sink { [weak self, weak model] active in
            guard let self, let model else { return }
            if active, model.live.connected { self.hadConnection = true }
            if !active {
                self.offWristAt = nil
                self.disconnectedAt = nil
                self.hadConnection = false
            }
        }.store(in: &observers)
        model.registry.$activeDeviceId.dropFirst().sink { [weak self] _ in
            self?.offWristAt = nil
            self?.disconnectedAt = nil
            self?.hadConnection = false
        }.store(in: &observers)
        timer = Timer.scheduledTimer(withTimeInterval: 60, repeats: true) { [weak self] _ in
            Task { @MainActor in await self?.evaluate() }
        }
    }

    deinit { timer?.invalidate() }

    func evaluate(now: Date? = nil) async {
        guard let model else { return }
        let now = now ?? clock()
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        let authorized = [.authorized, .provisional, .ephemeral].contains(settings.authorizationStatus)
        guard authorized, !LocalNotificationPreferences.isQuiet(now: now, defaults: defaults) else { return }
        let nowSec = Int(now.timeIntervalSince1970)
        let today = Repository.localDayKey(now)
        let minute = Calendar.current.component(.hour, from: now) * 60 + Calendar.current.component(.minute, from: now)
        let streak = StreakCalculator.streaks(dayKeys: model.repo.days.map(\.day),
                                             qualified: model.repo.days.map { $0.recovery != nil }, today: today).current
        await evaluateDevice(model: model, now: now, today: today, nowSec: nowSec)
        guard !model.live.backfilling, !model.live.historyPendingSync, !model.intelligence.computing else { return }

        let offset = TimeZone.current.secondsFromGMT(for: now)
        let history = model.repo.sleeps.compactMap { session -> SleepStageTotals.HistoryBlock? in
            guard session.endTs > session.effectiveStartTs else { return nil }
            let mid = session.effectiveStartTs + (session.endTs - session.effectiveStartTs) / 2
            return SleepStageTotals.HistoryBlock(start: session.effectiveStartTs, end: session.endTs,
                dayKey: Repository.localDayKey(Date(timeIntervalSince1970: Double(mid))))
        }
        let habitual = SleepStageTotals.habitualMidsleepSec(history, offsetSec: offset)
        if let row = Repository.resolveToday(days: model.repo.days, logicalKey: Repository.logicalDayKey(now), localKey: today) {
            let sessions = model.repo.sleeps.filter {
                Repository.localDayKey(Date(timeIntervalSince1970: Double($0.endTs))) == row.day
                    && $0.endTs > $0.effectiveStartTs
            }
            let group = SleepStageTotals.mainNightGroupIndices(
                sessions.map { SleepStageTotals.NightBlock(start: $0.effectiveStartTs, end: $0.endTs) },
                offsetSec: offset, habitualMidsleepSec: habitual) ?? []
            if let wake = group.map({ sessions[$0].endTs }).max(), wake <= nowSec, nowSec - wake <= 86_400 {
                let summary = LocalBriefingCopy.summary(
                    recovery: row.recovery.map { Int($0.rounded()) },
                    sleepMinutes: row.totalSleepMin.map { Int($0.rounded()) },
                    strainTenths: row.strain.map { Int(($0 * 2.1).rounded()) }, streak: streak)
                let key = row.day
                if row.recovery != nil {
                    await post(.recoveryReady, event: key, occurrence: wake, now: nowSec,
                               title: String(localized: "Recovery is ready"), body: summary)
                }
                if row.totalSleepMin != nil {
                    await post(.sleepReady, event: key, occurrence: wake, now: nowSec,
                               title: String(localized: "Sleep is ready"), body: summary)
                }
                if row.recovery != nil || row.totalSleepMin != nil {
                    await post(.morningRecap, event: key, occurrence: wake, now: nowSec,
                               title: String(localized: "Your recorded night"), body: summary)
                    await post(.dailyOutlook, event: key, occurrence: wake, now: nowSec,
                               title: String(localized: "Daily Outlook"), body: summary)
                }
            }
        }

        if minute >= 20 * 60, let row = model.repo.days.last(where: { $0.day == today }) {
            let occurrence = Int((Calendar.current.date(bySettingHour: 20, minute: 0, second: 0, of: now) ?? now).timeIntervalSince1970)
            let summary = LocalBriefingCopy.summary(
                recovery: row.recovery.map { Int($0.rounded()) },
                sleepMinutes: row.totalSleepMin.map { Int($0.rounded()) },
                strainTenths: row.strain.map { Int(($0 * 2.1).rounded()) }, streak: streak)
            if row.strain != nil {
                await post(.strainReady, event: today, occurrence: occurrence, now: nowSec,
                           title: String(localized: "Today's saved Strain"), body: summary)
            }
            if row.recovery != nil || row.totalSleepMin != nil || row.strain != nil {
                await post(.dayInReview, event: today, occurrence: occurrence, now: nowSec,
                           title: String(localized: "Day in Review"), body: summary)
            }
            if streak > 0 {
                await post(.streakSummary, event: today, occurrence: occurrence, now: nowSec,
                           title: String(localized: "Your recorded streak"), body: summary)
            }
        }
        if let workout = model.repo.workouts.max(by: { $0.startTs < $1.startTs }), workout.endTs <= nowSec {
            await post(.workoutReady, event: String(workout.startTs), occurrence: workout.endTs, now: nowSec,
                       title: String(localized: "Workout ready"),
                       body: String(localized: "Your recorded activity is available in Workouts after the data refresh."))
        }
        if let plan = weeklyPlanProvider?.notificationSummary(now: now), minute >= 17 * 60 {
            let weekday = Calendar.current.component(.weekday, from: now)
            let occurrence = Int((Calendar.current.date(bySettingHour: 17, minute: 0, second: 0, of: now) ?? now).timeIntervalSince1970)
            if weekday == 6, let body = plan.checkIn {
                await post(.weeklyCheckIn, event: plan.weekKey, occurrence: occurrence, now: nowSec,
                           title: String(localized: "Weekly Plan check-in"), body: body)
            }
            if weekday == 2, let body = plan.recap {
                await post(.weeklyRecap, event: plan.weekKey, occurrence: occurrence, now: nowSec,
                           title: String(localized: "Weekly Plan recap"), body: body)
            }
        }
    }

    private func evaluateDevice(model: AppModel, now: Date, today: String, nowSec: Int) async {
        guard model.live.activeIsWhoop else { return }
        if let disconnectedAt, !model.live.connected, now.timeIntervalSince(disconnectedAt) >= 300 {
            await post(.disconnected, event: today, occurrence: Int(disconnectedAt.timeIntervalSince1970) + 300,
                       now: nowSec, title: String(localized: "Device disconnected"),
                       body: String(localized: "The app has been out of contact for five minutes. Open Devices to check the connection."))
        }
        if let offWristAt, model.live.connected, model.live.activeIsWhoop, !model.live.worn, now.timeIntervalSince(offWristAt) >= 1800 {
            await post(.wearReminder, event: today, occurrence: Int(offWristAt.timeIntervalSince1970) + 1800,
                       now: nowSec, title: String(localized: "Wear reminder"),
                       body: String(localized: "The connected strap reported off-wrist for thirty minutes. Put it on when you are ready to record."))
        }
    }

    private func post(_ family: LocalNotificationFamily, event: String, occurrence: Int,
                      now: Int, title: String, body: String) async {
        let key = "\(family.rawValue):\(event)"
        guard !inFlight.contains(key), LocalNotificationPolicy.shouldDeliver(
            enabled: defaults.bool(forKey: family.enabledKey), authorized: true, quiet: false,
            eventKey: key, lastEventKey: defaults.string(forKey: family.lastEventKey),
            occurrenceSec: occurrence, nowSec: now) else { return }
        inFlight.insert(key)
        defer { inFlight.remove(key) }
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        content.categoryIdentifier = "local-report"
        let route: String
        switch family {
        case .disconnected, .wearReminder: route = "devices"
        case .workoutReady: route = "workouts"
        case .weeklyCheckIn, .weeklyRecap: route = "weekly_plan"
        default: route = "local_briefing"
        }
        content.userInfo = ["localNotificationRoute": route]
        do {
            try await UNUserNotificationCenter.current().add(
                UNNotificationRequest(identifier: key, content: content, trigger: nil))
            defaults.set(key, forKey: family.lastEventKey)
        } catch {
            model?.live.append(log: "Local notification delivery failed: \(error.localizedDescription)")
        }
    }
}
