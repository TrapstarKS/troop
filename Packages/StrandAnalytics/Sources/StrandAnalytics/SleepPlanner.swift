import Foundation

/// Sleep-duration goals as a share of the local planning estimate, not composite performance scores.
public enum SleepPlannerGoal: Int, CaseIterable, Sendable {
    case peak = 100
    case perform = 85
    case getBy = 70
}

/// A local sleep-duration estimate. Clock minutes are relative to the wake day's midnight;
/// a day shift of -1 places bedtime or its reminder on the previous calendar day.
public struct SleepPlan: Equatable, Sendable {
    public let needMinutes: Int
    public let debtMinutes: Int
    public let targetSleepMinutes: Int
    public let bedtimeMinutes: Int
    public let bedtimeDayShift: Int
    public let reminderMinutes: Int
    public let reminderDayShift: Int
    public let wakeMinutes: Int
    public let historyReady: Bool
    public let debtNudge: Bool
}

public enum SleepPlanner {
    /// Plans from a caller-supplied base need and nonnegative debt magnitude. Base need is
    /// bounded to 300...660 minutes; the added debt estimate is capped at 120 minutes.
    /// These are local planning bounds, not a physiological claim. The target is rounded
    /// up to the next whole minute and unsupported goals fall back to 100 percent.
    /// Kotlin twin: `SleepPlanner.plan`.
    public static func plan(
        baseNeedMinutes: Int,
        debtMinutes: Int,
        goalPercent: Int,
        wakeMinutes: Int,
        leadMinutes: Int,
        historyNights: Int
    ) -> SleepPlan {
        let base = min(max(baseNeedMinutes, 300), 660)
        let debt = min(max(debtMinutes, 0), 120)
        let need = base + debt
        let target = (need * normalizedGoal(goalPercent) + 99) / 100
        let wake = min(max(wakeMinutes, 0), 1439)
        let lead = min(max(leadMinutes, 0), 120)
        let rawBedtime = wake - target
        let bedtime = clock(rawBedtime)
        let reminder = clock(rawBedtime - lead)
        let historyReady = historyNights >= 3

        return SleepPlan(
            needMinutes: need,
            debtMinutes: debt,
            targetSleepMinutes: target,
            bedtimeMinutes: bedtime.minutes,
            bedtimeDayShift: bedtime.dayShift,
            reminderMinutes: reminder.minutes,
            reminderDayShift: reminder.dayShift,
            wakeMinutes: wake,
            historyReady: historyReady,
            debtNudge: historyReady && debt >= 60
        )
    }

    /// Resolves a weekday override (1 = Sunday, 7 = Saturday). Invalid weekdays use
    /// the default; unsupported percentages fall back to the full local need.
    /// Kotlin twin: `SleepPlanner.weekdayGoal`.
    public static func weekdayGoal(
        _ weekday: Int,
        overrides: [Int: Int],
        defaultPercent: Int = 100
    ) -> Int {
        let selected = (1...7).contains(weekday) ? overrides[weekday] ?? defaultPercent : defaultPercent
        return normalizedGoal(selected)
    }

    /// Resolves a local wake time using native calendar rules: nonexistent times retain
    /// smaller components while advancing through a gap; repeated times use the later match.
    /// Kotlin twin: `SleepPlanner.wakeDate`.
    public static func wakeDate(minutes: Int, on date: Date, calendar: Calendar) -> Date? {
        let minute = min(max(minutes, 0), 1439)
        return calendar.nextDate(
            after: calendar.startOfDay(for: date).addingTimeInterval(-1),
            matching: DateComponents(hour: minute / 60, minute: minute % 60, second: 0),
            matchingPolicy: .nextTimePreservingSmallerComponents,
            repeatedTimePolicy: .last,
            direction: .forward
        )
    }

    /// Subtracts elapsed sleep duration rather than civil clock minutes across a DST change.
    /// Kotlin twin: `SleepPlanner.bedtime`.
    public static func bedtime(wake: Date, targetSleepMinutes: Int) -> Date {
        wake.addingTimeInterval(-Double(min(max(targetSleepMinutes, 0), 780)) * 60)
    }

    // Kotlin twin: `SleepPlanner.normalizedGoal`.
    private static func normalizedGoal(_ percent: Int) -> Int {
        SleepPlannerGoal(rawValue: percent)?.rawValue ?? SleepPlannerGoal.peak.rawValue
    }

    private static func clock(_ minutes: Int) -> (minutes: Int, dayShift: Int) {
        let remainder = minutes % 1440
        let quotient = minutes / 1440
        return remainder < 0 ? (remainder + 1440, quotient - 1) : (remainder, quotient)
    }
}
