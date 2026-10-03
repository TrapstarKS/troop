/// Pure policy for advancing an alarm within its final hour. The caller supplies only
/// a recovery value from the current night; this helper cannot establish freshness.
/// Only recovery percentages in 67...100 can advance the alarm.
public enum PlannerAlarmPolicy {
    public static func shouldWakeEarly(
        mode: String,
        targetSleepMinutes: Int,
        observedSleepMinutes: Int?,
        currentNightRecoveryPercent: Int?,
        minutesUntilDeadline: Int
    ) -> Bool {
        guard (1...60).contains(minutesUntilDeadline) else { return false }

        switch mode {
        case "sleepGoal":
            guard targetSleepMinutes > 0, let observedSleepMinutes else { return false }
            return observedSleepMinutes >= targetSleepMinutes
        case "recovery":
            guard let currentNightRecoveryPercent else { return false }
            return (67...100).contains(currentNightRecoveryPercent)
        default:
            return false
        }
    }

    /// Stable identity for a resolved local alarm date and minute. Calendar resolution
    /// belongs to the caller; supplied components are preserved rather than normalized.
    public static func occurrenceKey(year: Int, month: Int, day: Int, minutes: Int) -> String {
        "\(padded(year, width: 4))-\(padded(month, width: 2))-\(padded(day, width: 2))|\(minutes)"
    }

    /// Advice quiet hours with an inclusive start and exclusive end. Equal bounds
    /// disable the quiet window; wake-alarm deadlines are exempt at the caller.
    public static func isQuietMinute(minute: Int, enabled: Bool, startMinutes: Int, endMinutes: Int) -> Bool {
        guard enabled else { return false }
        let currentMinute = min(max(minute, 0), 1439)
        let start = min(max(startMinutes, 0), 1439)
        let end = min(max(endMinutes, 0), 1439)
        guard start != end else { return false }
        return start < end ? currentMinute >= start && currentMinute < end : currentMinute >= start || currentMinute < end
    }

    /// Pending through the saved local minute. Scheduling still matches the exact
    /// occurrence key; this comparison only gates the pending status and duplicate skip.
    public static func isSkipPending(skippedOccurrence: String, currentOccurrence: String) -> Bool {
        guard let skipped = occurrenceParts(skippedOccurrence), let current = occurrenceParts(currentOccurrence) else { return false }
        return skipped.day > current.day || (skipped.day == current.day && skipped.minutes >= current.minutes)
    }

    private static func occurrenceParts(_ key: String) -> (day: String, minutes: Int)? {
        let parts = key.split(separator: "|", omittingEmptySubsequences: false)
        guard parts.count == 2 else { return nil }
        let day = String(parts[0])
        let bytes = Array(day.utf8)
        guard bytes.count == 10,
              bytes.enumerated().allSatisfy({ index, byte in
                  index == 4 || index == 7 ? byte == 45 : (48...57).contains(byte)
              }),
              let year = Int(day.prefix(4)), let month = Int(day.dropFirst(5).prefix(2)), let date = Int(day.suffix(2)),
              year > 0, (1...12).contains(month),
              let minutes = Int(parts[1]), (0...1439).contains(minutes), String(minutes) == parts[1] else { return nil }
        let leapYear = year % 400 == 0 || (year % 4 == 0 && year % 100 != 0)
        let days = [31, leapYear ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
        guard (1...days[month - 1]).contains(date) else { return nil }
        return (day, minutes)
    }

    private static func padded(_ value: Int, width: Int) -> String {
        let text = String(value)
        return String(repeating: "0", count: max(0, width - text.count)) + text
    }
}
