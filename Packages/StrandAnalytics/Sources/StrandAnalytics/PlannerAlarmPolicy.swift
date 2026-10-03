import Foundation

/// Shared occurrence identities and quiet/deduplication policy for planner reminders and alarm skips.
public enum PlannerAlarmPolicy {
    /// Stable identity for supplied Gregorian date components and a minute. Supplied
    /// components are preserved rather than normalized.
    public static func occurrenceKey(year: Int, month: Int, day: Int, minutes: Int) -> String {
        "\(padded(year, width: 4))-\(padded(month, width: 2))-\(padded(day, width: 2))|\(minutes)"
    }

    /// Gregorian identity for a resolved alarm instant in the caller's timezone,
    /// independent of the caller's preferred calendar.
    public static func occurrenceKey(for date: Date, calendar: Calendar) -> String {
        var gregorian = Calendar(identifier: .gregorian)
        gregorian.timeZone = calendar.timeZone
        let parts = gregorian.dateComponents([.year, .month, .day, .hour, .minute], from: date)
        return occurrenceKey(year: parts.year ?? 0, month: parts.month ?? 0, day: parts.day ?? 0,
                             minutes: (parts.hour ?? 0) * 60 + (parts.minute ?? 0))
    }

    /// Advice quiet hours with an inclusive start and exclusive end. Equal bounds
    /// disable the quiet window; wake-alarm deadlines are exempt at the caller.
    /// Kotlin twin: `PlannerAlarmPolicy.isQuietMinute`.
    public static func isQuietMinute(minute: Int, enabled: Bool, startMinutes: Int, endMinutes: Int) -> Bool {
        guard enabled else { return false }
        let currentMinute = min(max(minute, 0), 1439)
        let start = min(max(startMinutes, 0), 1439)
        let end = min(max(endMinutes, 0), 1439)
        guard start != end else { return false }
        return start < end ? currentMinute >= start && currentMinute < end : currentMinute >= start || currentMinute < end
    }

    /// Canonical local advice queue. Epochs are whole UTC seconds on both platforms.
    /// Kotlin twin: `PlannerAlarmPolicy.adviceQueue`.
    public static func adviceQueue(_ occurrences: [String: Int64]) -> String {
        occurrences.keys.sorted().compactMap { key in
            guard occurrenceParts(key) != nil, let epoch = occurrences[key], epoch > 0 else { return nil }
            return "\(key)=\(epoch)"
        }.joined(separator: "\n")
    }

    /// Retains handled wakes from today onward. An elapsed queued reminder is consumed
    /// conservatively, without claiming that the operating system actually delivered it.
    /// Kotlin twin: `PlannerAlarmPolicy.handledAdvice`.
    public static func handledAdvice(queued: String, previous: String, nowEpoch: Int64, localDay: String) -> String {
        var keys = Set(previous.split(separator: "\n").map(String.init).filter { occurrenceParts($0) != nil })
        for line in queued.split(separator: "\n") {
            let parts = line.split(separator: "=", omittingEmptySubsequences: false)
            guard parts.count == 2, occurrenceParts(String(parts[0])) != nil,
                  let epoch = Int64(parts[1]), String(epoch) == parts[1], epoch > 0, epoch <= nowEpoch else { continue }
            keys.insert(String(parts[0]))
        }
        return keys.filter { String($0.prefix(10)) >= localDay }.sorted().joined(separator: "\n")
    }

    /// Pending until the saved wake instant. Scheduling still matches the exact
    /// occurrence key; this comparison only gates the pending status and duplicate skip.
    /// Kotlin twin: `PlannerAlarmPolicy.isSkipPending`.
    public static func isSkipPending(skippedOccurrence: String, from now: Date, calendar: Calendar) -> Bool {
        guard let saved = occurrenceParts(skippedOccurrence) else { return false }
        var gregorian = Calendar(identifier: .gregorian)
        gregorian.timeZone = calendar.timeZone
        let parts = DateComponents(year: Int(saved.day.prefix(4)), month: Int(saved.day.dropFirst(5).prefix(2)),
                                   day: Int(saved.day.suffix(2)), hour: 12)
        guard let savedDay = gregorian.date(from: parts) else { return false }
        let resolved = gregorian.dateComponents([.year, .month, .day], from: savedDay)
        guard resolved.year == parts.year, resolved.month == parts.month, resolved.day == parts.day,
              let wake = SleepPlanner.wakeDate(minutes: saved.minutes, on: savedDay, calendar: gregorian) else { return false }
        return wake >= now
    }

    // Kotlin twin: `PlannerAlarmPolicy.occurrenceParts`.
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
