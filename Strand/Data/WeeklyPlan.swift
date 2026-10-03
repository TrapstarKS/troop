import Foundation

struct WeeklyPlanRecoveryDay {
    let day: String
    let recovery: Double?
    let sleepProcessed: Bool
}

struct WeeklyPlanEligibility: Equatable {
    static let requiredRecoveries = 7
    let completedRecoveries: Int

    var remainingRecoveries: Int { max(0, Self.requiredRecoveries - completedRecoveries) }
    var isEligible: Bool { remainingRecoveries == 0 }

    static func resolve(recoveries: [WeeklyPlanRecoveryDay], today: String) -> WeeklyPlanEligibility {
        guard WeeklyPlanCalendar.date(today) != nil else { return WeeklyPlanEligibility(completedRecoveries: 0) }
        let completed = Set(recoveries.compactMap { row -> String? in
            guard row.sleepProcessed, WeeklyPlanCalendar.date(row.day) != nil, row.day <= today,
                  let score = row.recovery, score.isFinite, (0...100).contains(score) else { return nil }
            return row.day
        })
        return WeeklyPlanEligibility(completedRecoveries: completed.count)
    }
}

enum WeeklyPlanPreset: String, CaseIterable, Identifiable {
    case restRoutine, activeWeek, balancedWeek
    var id: String { rawValue }

    var goals: WeeklyPlanGoals {
        switch self {
        case .restRoutine: return WeeklyPlanGoals()
        case .activeWeek: return WeeklyPlanGoals(strainMinimum: 60, strainDays: 4)
        case .balancedWeek: return WeeklyPlanGoals(sleepMinutes: 450, strainMinimum: 40)
        }
    }
}

struct WeeklyPlanGoals: Equatable {
    var sleepMinutes = 480
    var sleepDays = 5
    var strainMinimum = 50
    var strainDays = 3
    var journalDays = 5
    var journalQuestion = ""
    var journalAnswer = "any"

    var normalized: WeeklyPlanGoals {
        var goals = self
        goals.sleepMinutes = min(720, max(240, sleepMinutes))
        goals.sleepDays = min(7, max(1, sleepDays))
        goals.strainMinimum = min(100, max(1, strainMinimum))
        goals.strainDays = min(7, max(1, strainDays))
        goals.journalDays = min(7, max(1, journalDays))
        if journalQuestion.isEmpty { goals.journalAnswer = "any" }
        else if !["yes", "no"].contains(journalAnswer) { goals.journalAnswer = "yes" }
        return goals
    }
}

struct WeeklyPlanDay {
    let day: String
    var sleepMinutes: Double?
    var strain: Double?
}

struct WeeklyPlanJournalDay {
    let day: String
    let question: String
    let answeredYes: Bool
}

struct WeeklyPlanProgress: Equatable {
    let completedDays: Int
    let observedDays: Int
    let targetDays: Int

    var percent: Int? {
        observedDays == 0 ? nil : min(100, completedDays * 100 / targetDays)
    }
}

struct WeeklyPlanSnapshot: Equatable {
    let weekStart: String
    let weekEnd: String
    let sleep: WeeklyPlanProgress
    let strain: WeeklyPlanProgress
    let journal: WeeklyPlanProgress

    var overallPercent: Int? {
        guard let sleep = sleep.percent, let strain = strain.percent, let journal = journal.percent else { return nil }
        return (sleep + strain + journal) / 3
    }
}

enum WeeklyPlanCalendar {
    private static var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        return calendar
    }

    static func date(_ day: String) -> Date? {
        let parts = day.split(separator: "-", omittingEmptySubsequences: false)
        guard parts.count == 3, parts[0].count == 4, parts[1].count == 2, parts[2].count == 2,
              let year = Int(parts[0]), let month = Int(parts[1]), let dayOfMonth = Int(parts[2]),
              (1...9999).contains(year),
              let date = calendar.date(from: DateComponents(year: year, month: month, day: dayOfMonth)),
              key(date) == day else { return nil }
        return date
    }

    static func key(_ date: Date) -> String {
        let parts = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", parts.year!, parts.month!, parts.day!)
    }

    static func adding(days: Int, to day: String) -> String? {
        guard let date = date(day), let result = calendar.date(byAdding: .day, value: days, to: date) else { return nil }
        let parts = calendar.dateComponents([.era, .year], from: result)
        guard parts.era == 1, let year = parts.year, (1...9999).contains(year) else { return nil }
        return key(result)
    }

    static func weekStart(_ day: String) -> String? {
        guard let date = date(day) else { return nil }
        let weekday = calendar.component(.weekday, from: date)
        return adding(days: -((weekday + 5) % 7), to: day)
    }

    static func weekday(_ day: String) -> Int? {
        date(day).map { (calendar.component(.weekday, from: $0) + 5) % 7 + 1 }
    }
}

enum WeeklyPlanEngine {
    static func suggestedGoals(days: [WeeklyPlanDay], today: String) -> WeeklyPlanGoals {
        guard let from = WeeklyPlanCalendar.adding(days: -30, to: today) else { return WeeklyPlanGoals() }
        let window = uniqueDays(days.filter { $0.day >= from && $0.day < today })
        let sleep = window.compactMap(\.sleepMinutes).filter { $0.isFinite && $0 > 0 }
        let strain = window.compactMap(\.strain).filter { $0.isFinite && (0...100).contains($0) }
        var goals = WeeklyPlanGoals()
        if !sleep.isEmpty { goals.sleepMinutes = Int(min(720, max(240, sleep.reduce(0, +) / Double(sleep.count))).rounded()) }
        if !strain.isEmpty { goals.strainMinimum = Int((strain.reduce(0, +) / Double(strain.count)).rounded()) }
        return goals.normalized
    }

    static func snapshot(goals: WeeklyPlanGoals, weekStart: String, today: String,
                         days: [WeeklyPlanDay], journal: [WeeklyPlanJournalDay]) -> WeeklyPlanSnapshot? {
        guard WeeklyPlanCalendar.weekStart(weekStart) == weekStart,
              WeeklyPlanCalendar.date(today) != nil,
              let weekEnd = WeeklyPlanCalendar.adding(days: 6, to: weekStart) else { return nil }
        let goals = goals.normalized
        let end = min(today, weekEnd)
        let window = uniqueDays(days.filter { $0.day >= weekStart && $0.day <= end })
        let sleep = window.compactMap(\.sleepMinutes).filter { $0.isFinite && $0 >= 0 }
        let strain = window.compactMap(\.strain).filter { $0.isFinite && (0...100).contains($0) }
        var byDay: [String: [String: WeeklyPlanJournalDay]] = [:]
        for entry in journal where entry.day >= weekStart && entry.day <= end && WeeklyPlanCalendar.date(entry.day) != nil {
            byDay[entry.day, default: [:]][entry.question] = entry
        }
        let entries = byDay.values.flatMap { $0.values }
        let answered = Set(entries.filter { goals.journalQuestion.isEmpty || $0.question == goals.journalQuestion }.map(\.day))
        let completed = Set(entries.filter {
            goals.journalQuestion.isEmpty || ($0.question == goals.journalQuestion && $0.answeredYes == (goals.journalAnswer == "yes"))
        }.map(\.day))
        var elapsed = 0
        for offset in 0..<7 {
            if let day = WeeklyPlanCalendar.adding(days: offset, to: weekStart), day <= end { elapsed += 1 }
        }
        return WeeklyPlanSnapshot(weekStart: weekStart, weekEnd: weekEnd,
            sleep: WeeklyPlanProgress(completedDays: sleep.filter { $0 >= Double(goals.sleepMinutes) }.count,
                                      observedDays: sleep.count, targetDays: goals.sleepDays),
            strain: WeeklyPlanProgress(completedDays: strain.filter { $0 >= Double(goals.strainMinimum) }.count,
                                       observedDays: strain.count, targetDays: goals.strainDays),
            journal: WeeklyPlanProgress(completedDays: completed.count,
                                        observedDays: goals.journalQuestion.isEmpty ? elapsed : answered.count,
                                        targetDays: goals.journalDays))
    }

    private static func uniqueDays(_ days: [WeeklyPlanDay]) -> [WeeklyPlanDay] {
        var byDay: [String: WeeklyPlanDay] = [:]
        for day in days where WeeklyPlanCalendar.date(day.day) != nil { byDay[day.day] = day }
        return byDay.keys.sorted().compactMap { byDay[$0] }
    }
}

struct WeeklyPlanNotice: Equatable, Identifiable {
    enum Kind: String { case checkIn, recap }
    let kind: Kind
    let weekStart: String
    var id: String { "\(kind.rawValue):\(weekStart)" }
}

enum WeeklyPlanNoticeResolver {
    static func resolve(today: String, availableWeeks: Set<String>, dismissedIDs: Set<String> = []) -> WeeklyPlanNotice? {
        guard let week = WeeklyPlanCalendar.weekStart(today), let weekday = WeeklyPlanCalendar.weekday(today) else { return nil }
        let notice: WeeklyPlanNotice
        if weekday == 5 { notice = WeeklyPlanNotice(kind: .checkIn, weekStart: week) }
        else if weekday == 1, let previous = WeeklyPlanCalendar.adding(days: -7, to: week) {
            notice = WeeklyPlanNotice(kind: .recap, weekStart: previous)
        } else { return nil }
        return availableWeeks.contains(notice.weekStart) && !dismissedIDs.contains(notice.id) ? notice : nil
    }
}
