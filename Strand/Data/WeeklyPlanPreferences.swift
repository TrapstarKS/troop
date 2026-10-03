import Foundation

final class WeeklyPlanPreferences {
    private let defaults: UserDefaults
    private let prefix = "noop.weeklyPlan."

    init(defaults: UserDefaults = .standard) { self.defaults = defaults }

    func hasPlan(weekStart: String) -> Bool {
        defaults.object(forKey: prefix + weekStart + ".sleepMinutes") != nil
    }

    func goals(weekStart: String, suggested: WeeklyPlanGoals = WeeklyPlanGoals()) -> WeeklyPlanGoals {
        read(scope: hasPlan(weekStart: weekStart) ? weekStart : "template", fallback: suggested)
    }

    func save(_ goals: WeeklyPlanGoals, weekStart: String, updateTemplate: Bool = true) {
        guard WeeklyPlanCalendar.weekStart(weekStart) == weekStart else { return }
        write(goals.normalized, scope: weekStart)
        if updateTemplate { write(goals.normalized, scope: "template") }
    }

    func eligibleNotice(today: String, eligibility: WeeklyPlanEligibility) -> WeeklyPlanNotice? {
        guard eligibility.isEligible else { return nil }
        return notice(today: today)
    }

    private func notice(today: String) -> WeeklyPlanNotice? {
        guard let week = WeeklyPlanCalendar.weekStart(today),
              let previous = WeeklyPlanCalendar.adding(days: -7, to: week) else { return nil }
        let available = Set([week, previous].filter { hasPlan(weekStart: $0) })
        let dismissed = Set(["checkIn", "recap"].compactMap { defaults.string(forKey: prefix + "dismissed." + $0) })
        return WeeklyPlanNoticeResolver.resolve(today: today, availableWeeks: available, dismissedIDs: dismissed)
    }

    func dismiss(_ notice: WeeklyPlanNotice) {
        defaults.set(notice.id, forKey: prefix + "dismissed." + notice.kind.rawValue)
    }

    private func read(scope: String, fallback: WeeklyPlanGoals) -> WeeklyPlanGoals {
        let key = prefix + scope + "."
        func integer(_ name: String, _ value: Int) -> Int {
            defaults.object(forKey: key + name) == nil ? value : defaults.integer(forKey: key + name)
        }
        return WeeklyPlanGoals(sleepMinutes: integer("sleepMinutes", fallback.sleepMinutes),
            sleepDays: integer("sleepDays", fallback.sleepDays),
            strainMinimum: integer("strainMinimum", fallback.strainMinimum),
            strainDays: integer("strainDays", fallback.strainDays),
            journalDays: integer("journalDays", fallback.journalDays),
            journalQuestion: defaults.string(forKey: key + "journalQuestion") ?? fallback.journalQuestion,
            journalAnswer: defaults.string(forKey: key + "journalAnswer") ?? fallback.journalAnswer).normalized
    }

    private func write(_ goals: WeeklyPlanGoals, scope: String) {
        let key = prefix + scope + "."
        defaults.set(goals.sleepMinutes, forKey: key + "sleepMinutes")
        defaults.set(goals.sleepDays, forKey: key + "sleepDays")
        defaults.set(goals.strainMinimum, forKey: key + "strainMinimum")
        defaults.set(goals.strainDays, forKey: key + "strainDays")
        defaults.set(goals.journalDays, forKey: key + "journalDays")
        defaults.set(goals.journalQuestion, forKey: key + "journalQuestion")
        defaults.set(goals.journalAnswer, forKey: key + "journalAnswer")
    }
}

func seedWeeklyPlanDemo(defaults: UserDefaults = .standard, today: String) {
    guard let week = WeeklyPlanCalendar.weekStart(today),
          let previous = WeeklyPlanCalendar.adding(days: -7, to: week) else { return }
    let preferences = WeeklyPlanPreferences(defaults: defaults)
    let goals = WeeklyPlanGoals(sleepMinutes: 420, sleepDays: 5, strainMinimum: 50, strainDays: 3, journalDays: 5)
    if !preferences.hasPlan(weekStart: previous) { preferences.save(goals, weekStart: previous, updateTemplate: false) }
    if !preferences.hasPlan(weekStart: week) { preferences.save(goals, weekStart: week) }
}
