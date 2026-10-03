import Foundation
import Combine
import StrandAnalytics
import WhoopStore

@MainActor
final class SleepPlannerSettings: ObservableObject {
    static let shared = SleepPlannerSettings()

    @Published var goalPercent: Int { didSet { defaults.set(goalPercent, forKey: "sleepPlanner.goalPercent") } }
    @Published var goalOverrides: [Int: Int] {
        didSet {
            for day in 1...7 {
                defaults.set(goalOverrides[day], forKey: "sleepPlanner.goal.\(day)")
            }
        }
    }
    @Published var alarmMode: String { didSet { defaults.set(alarmMode, forKey: "sleepPlanner.alarmMode") } }
    @Published var skippedOccurrence: String { didSet { defaults.set(skippedOccurrence, forKey: "sleepPlanner.skippedOccurrence") } }
    @Published var debtReminderEnabled: Bool { didSet { defaults.set(debtReminderEnabled ? 1 : 0, forKey: "sleepPlanner.debtReminderEnabled") } }
    @Published private(set) var baseNeedMinutes: Int
    @Published private(set) var debtMinutes: Int
    @Published private(set) var historyNights: Int

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        goalPercent = Self.validGoal(defaults.object(forKey: "sleepPlanner.goalPercent") as? Int ?? 100)
        goalOverrides = Dictionary(uniqueKeysWithValues: (1...7).compactMap { day in
            (defaults.object(forKey: "sleepPlanner.goal.\(day)") as? Int).map { (day, Self.validGoal($0)) }
        })
        let mode = defaults.string(forKey: "sleepPlanner.alarmMode") ?? "exact"
        alarmMode = ["exact", "sleepGoal", "recovery"].contains(mode) ? mode : "exact"
        skippedOccurrence = defaults.string(forKey: "sleepPlanner.skippedOccurrence") ?? ""
        debtReminderEnabled = defaults.integer(forKey: "sleepPlanner.debtReminderEnabled") == 1
        baseNeedMinutes = min(max(defaults.object(forKey: "sleepPlanner.baseNeedMinutes") as? Int ?? 480, 300), 660)
        debtMinutes = max(defaults.integer(forKey: "sleepPlanner.debtMinutes"), 0)
        historyNights = max(defaults.integer(forKey: "sleepPlanner.historyNights"), 0)
    }

    func plan(weekday: Int, wakeMinutes: Int, leadMinutes: Int = 30) -> SleepPlan {
        SleepPlanner.plan(baseNeedMinutes: baseNeedMinutes, debtMinutes: debtMinutes,
                          goalPercent: goalOverrides[weekday] ?? goalPercent,
                          wakeMinutes: wakeMinutes, leadMinutes: leadMinutes,
                          historyNights: historyNights)
    }

    func updateInputs(days: [DailyMetric], sleeps: [CachedSleepSession], habitualMidsleepSec: Int?) {
        let naps = SleepModel.napSleepMinutesByDay(navDays: SleepModel.navDays(navSessions: sleeps),
                                                  habitualMidsleepSec: habitualMidsleepSec)
        let ledger = SleepModel.debtLedger(days: days, napSleepMinByDay: naps)
        let base = min(max(Int(ledger.needMin.rounded()), 300), 660)
        let debt = max(Int(ledger.magnitudeMin.rounded()), 0)
        let nights = ledger.nightCount
        guard base != baseNeedMinutes || debt != debtMinutes || nights != historyNights else { return }
        baseNeedMinutes = base
        debtMinutes = debt
        historyNights = nights
        defaults.set(base, forKey: "sleepPlanner.baseNeedMinutes")
        defaults.set(debt, forKey: "sleepPlanner.debtMinutes")
        defaults.set(nights, forKey: "sleepPlanner.historyNights")
        WindDownNudge.reschedule()
    }

    nonisolated static func validGoal(_ value: Int) -> Int {
        [100, 85, 70].contains(value) ? value : 100
    }
}

struct SleepPlannerSnapshot {
    let wake: Date
    let bedtime: Date
    let reminder: Date
    let plan: SleepPlan
    let alarmConfirmed: Bool
    let alarmSent: Bool
    let earlyWake: Bool
}
