import Foundation

enum JournalCalendar {
    static func date(_ key: String) -> Date? {
        let parts = key.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        return Calendar(identifier: .gregorian).date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2]))
    }

    static func rolloverOffset(offset: Int, from: String, to: String, preserveDraft: Bool) -> Int {
        guard let previous = WeeklyPlanCalendar.date(from), let current = WeeklyPlanCalendar.date(to) else { return offset }
        guard offset != 0 || preserveDraft else { return 0 }
        let delta = Int(current.timeIntervalSince(previous) / 86_400)
        let (result, overflow) = offset.addingReportingOverflow(delta)
        return overflow ? offset : result
    }
}
