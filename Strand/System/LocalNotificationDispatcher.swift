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
    static func isAuthorized(_ status: UNAuthorizationStatus) -> Bool {
        if status == .authorized || status == .provisional { return true }
        #if os(iOS)
        return status == .ephemeral
        #else
        return false
        #endif
    }

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
        model.$deviceRegistry.map { registry -> AnyPublisher<String, Never> in
            registry?.$activeDeviceId.eraseToAnyPublisher() ?? Just("my-whoop").eraseToAnyPublisher()
        }.switchToLatest().removeDuplicates().dropFirst().sink { [weak self] _ in
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
        let authorized = LocalNotificationPreferences.isAuthorized(settings.authorizationStatus)
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
                let report = LocalRecordedReport(day: row.day, recovery: row.recovery.map { Int($0.rounded()) },
                    sleepMinutes: row.totalSleepMin.map { Int($0.rounded()) }, strainTenths: nil, streak: streak,
                    sleepNeedMinutes: model.repo.importedSleep[row.day]?.needMin.map { Int($0.rounded()) },
                    sleepDebtMinutes: model.repo.importedSleep[row.day]?.debtMin.map { Int($0.rounded()) })
                var available: Set<String> = []
                if row.recovery != nil { available.insert(LocalNotificationFamily.recoveryReady.rawValue) }
                if row.totalSleepMin != nil { available.insert(LocalNotificationFamily.sleepReady.rawValue) }
                if !available.isEmpty { available.formUnion(["morningRecap", "dailyOutlook"]) }
                await postReport(group: .night, report: report, available: available,
                                 occurrence: wake, now: nowSec)

            }
        }

        if minute >= 20 * 60, let row = model.repo.days.last(where: { $0.day == today }) {
            let occurrence = Int((Calendar.current.date(bySettingHour: 20, minute: 0, second: 0, of: now) ?? now).timeIntervalSince1970)
            let report = LocalRecordedReport(day: row.day, recovery: row.recovery.map { Int($0.rounded()) },
                sleepMinutes: row.totalSleepMin.map { Int($0.rounded()) },
                strainTenths: row.strain.map { Int(($0 * 2.1).rounded()) }, streak: streak,
                sleepNeedMinutes: model.repo.importedSleep[row.day]?.needMin.map { Int($0.rounded()) },
                sleepDebtMinutes: model.repo.importedSleep[row.day]?.debtMin.map { Int($0.rounded()) })
            var available: Set<String> = []
            if row.strain != nil { available.insert("strainReady") }
            if streak > 0 { available.insert("streakSummary") }
            if row.recovery != nil || row.totalSleepMin != nil || !available.isEmpty { available.insert("dayInReview") }
            await postReport(group: .evening, report: report, available: available,
                             occurrence: occurrence, now: nowSec)

        }
        if defaults.bool(forKey: LocalNotificationFamily.workoutReady.enabledKey),
           let workout = await model.repo.workoutRows(days: 1).max(by: { $0.startTs < $1.startTs }),
           workout.endTs <= nowSec {
            await post(.workoutReady, event: String(workout.startTs), occurrence: workout.endTs, now: nowSec,
                       title: String(localized: "Workout ready"),
                       body: String(localized: "Your recorded activity is available in Workouts after the data refresh."),
                       context: LocalNotificationContext(route: "workouts", eventID: "workoutReady:\(workout.startTs)",
                           day: Repository.localDayKey(Date(timeIntervalSince1970: Double(workout.startTs))),
                           workoutStartSec: workout.startTs,
                           message: String(localized: "Your recorded activity is available in Workouts after the data refresh.")))
        }
        if let plan = weeklyPlanProvider?.notificationSummary(now: now), minute >= 17 * 60 {
            let weekday = Calendar.current.component(.weekday, from: now)
            let occurrence = Int((Calendar.current.date(bySettingHour: 17, minute: 0, second: 0, of: now) ?? now).timeIntervalSince1970)
            if weekday == 6, let body = plan.checkIn {
                await post(.weeklyCheckIn, event: plan.weekKey, occurrence: occurrence, now: nowSec,
                           title: String(localized: "Weekly Plan check-in"), body: body,
                           context: LocalNotificationContext(route: "weekly_plan", eventID: "weeklyCheckIn:\(plan.weekKey)",
                               family: "weeklyCheckIn", day: today, weekKey: plan.weekKey, message: body))
            }
            if weekday == 2, let body = plan.recap {
                await post(.weeklyRecap, event: plan.weekKey, occurrence: occurrence, now: nowSec,
                           title: String(localized: "Weekly Plan recap"), body: body,
                           context: LocalNotificationContext(route: "weekly_plan", eventID: "weeklyRecap:\(plan.weekKey)",
                               family: "weeklyRecap", day: today, weekKey: plan.weekKey, message: body))
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

    private func postReport(group: LocalNotificationReportGroup, report: LocalRecordedReport,
                            available: Set<String>, occurrence: Int, now: Int) async {
        let lock = "\(group):\(report.day)"
        guard !inFlight.contains(lock) else { return }
        inFlight.insert(lock)
        defer { inFlight.remove(lock) }
        let enabled = Set(group.families.filter {
            defaults.bool(forKey: "localNotifications.\($0).enabled")
        })
        let delivered = Set(group.families.filter {
            defaults.string(forKey: "localNotifications.\($0).lastEventKey") == "\($0):\(report.day)"
        })
        let accepted = await LocalNotificationDeliveryPlan.deliver(group: group, availableFamilies: available,
            enabledFamilies: enabled, deliveredFamilies: delivered, authorized: true, quiet: false,
            occurrenceSec: occurrence, nowSec: now) { plan in
                guard let family = LocalNotificationFamily(rawValue: plan.primaryFamily) else { return false }
                let body = LocalBriefingCopy.summary(recovery: report.recovery, sleepMinutes: report.sleepMinutes,
                    strainTenths: report.strainTenths, streak: report.streak)
                return await self.post(family, event: report.day, occurrence: occurrence, now: now,
                    title: self.reportTitle(family), body: body,
                    context: LocalNotificationContext(route: "local_briefing", eventID: "\(group):\(report.day)",
                        family: family.rawValue, report: report))
            }
        guard let accepted else { return }
        for family in accepted.coveredFamilies {
            defaults.set("\(family):\(report.day)", forKey: "localNotifications.\(family).lastEventKey")
        }
    }

    private func reportTitle(_ family: LocalNotificationFamily) -> String {
        switch family {
        case .recoveryReady: return String(localized: "Recovery is ready")
        case .sleepReady: return String(localized: "Sleep is ready")
        case .strainReady: return String(localized: "Today's saved Strain")
        case .morningRecap: return String(localized: "Your recorded night")
        case .dailyOutlook: return String(localized: "Daily Outlook")
        case .dayInReview: return String(localized: "Day in Review")
        default: return String(localized: "Your recorded streak")
        }
    }

    @discardableResult
    private func post(_ family: LocalNotificationFamily, event: String, occurrence: Int,
                      now: Int, title: String, body: String, context: LocalNotificationContext? = nil) async -> Bool {
        let key = "\(family.rawValue):\(event)"
        guard !inFlight.contains(key), LocalNotificationPolicy.shouldDeliver(
            enabled: defaults.bool(forKey: family.enabledKey), authorized: true, quiet: false,
            eventKey: key, lastEventKey: defaults.string(forKey: family.lastEventKey),
            occurrenceSec: occurrence, nowSec: now) else { return false }
        inFlight.insert(key)
        defer { inFlight.remove(key) }
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        guard LocalNotificationPreferences.isAuthorized(settings.authorizationStatus),
              !LocalNotificationPreferences.isQuiet(now: clock(), defaults: defaults),
              defaults.bool(forKey: family.enabledKey) else { return false }
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        content.categoryIdentifier = "local-report"
        content.userInfo = (context ?? LocalNotificationContext(route: "devices", eventID: key,
            family: family.rawValue, day: event, message: body)).wireFields
        do {
            try await UNUserNotificationCenter.current().add(
                UNNotificationRequest(identifier: key, content: content, trigger: nil))
            defaults.set(key, forKey: family.lastEventKey)
            return true
        } catch {
            model?.live.append(log: "Local notification delivery failed: \(error.localizedDescription)")
            return false
        }
    }
}
