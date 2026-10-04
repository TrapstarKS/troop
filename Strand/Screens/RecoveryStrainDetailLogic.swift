import Foundation
import StrandAnalytics

enum RecoveryStrainDetailLogic {
    enum TargetStatus: String {
        case unavailable, under, optimal, over
    }

    struct ZoneDistribution {
        let minutes: [Double]
        let imported: Bool
    }

    static func comparisonValue(_ value: Double?, decimals: Int) -> Double? {
        guard let value, value.isFinite, (0...1).contains(decimals) else { return nil }
        let factor = decimals == 0 ? 1.0 : 10.0
        let scaled = value * factor
        let rounded = scaled.isFinite ? scaled.rounded() / factor : value
        return rounded == 0 ? 0 : rounded
    }

    static func comparisonDelta(current: Double?, mean: Double?, decimals: Int) -> Double? {
        guard let current = comparisonValue(current, decimals: decimals),
              let mean = comparisonValue(mean, decimals: decimals) else { return nil }
        return comparisonValue(current - mean, decimals: decimals)
    }

    static func zoneDistribution(importedPercentages: [Double]?, durationSeconds: Double,
                                 recordedMinutes: [Double]? = nil) -> ZoneDistribution? {
        if let importedPercentages, durationSeconds.isFinite, durationSeconds > 0 {
            let minutes = importedPercentages.map { durationSeconds / 60 * $0 / 100 }
            return ZoneDistribution(minutes: minutes, imported: true)
        }
        return recordedMinutes.map { ZoneDistribution(minutes: $0, imported: false) }
    }

    static func timestampSeconds(_ value: Double?) -> Int? {
        value.flatMap { Int(exactly: floor($0)) }
    }

    static func strainWindow(calendarStart: Int, nextCalendarStart: Int, isCurrentDay: Bool,
                             sleepOnsetMode: Bool, onset: Int?, nextOnset: Int?, now: Int) -> ClosedRange<Int>? {
        guard calendarStart < nextCalendarStart else { return nil }
        let start = sleepOnsetMode ? onset ?? calendarStart : calendarStart
        let boundary = sleepOnsetMode ? nextOnset : nil
        let end: Int
        if let boundary = boundary ?? (isCurrentDay ? nil : nextCalendarStart) {
            guard boundary > start else { return nil }
            end = min(now, boundary - 1)
        } else {
            end = now
        }
        guard start <= end else { return nil }
        return start...end
    }

    static func wholeNumber(_ value: Double?) -> Int64? {
        guard let value, value.isFinite, value >= 0 else { return nil }
        return Int64(exactly: value.rounded())
    }

    static func durationMinutes(seconds: Double?, fallbackSeconds: Double? = nil) -> Int64? {
        (seconds ?? fallbackSeconds).flatMap { wholeNumber($0 / 60) }
    }

    /// Summed seconds of the strength sessions, nil when none. Kotlin twin: `strengthSeconds`.
    static func strengthSeconds(_ rows: [(sport: String, source: String, durationS: Double?, startTs: Int, endTs: Int)]) -> Double? {
        let strength = rows.filter { HealthspanHistory.isStrength(sport: $0.sport, source: $0.source) }
        guard !strength.isEmpty else { return nil }
        return strength.reduce(0) { $0 + ($1.durationS ?? Double($1.endTs - $1.startTs)) }
    }

    static func recoveryPercent(_ score: Double?) -> Int? {
        guard let score, score.isFinite, (0...100).contains(score) else { return nil }
        return Int(floor(score))
    }

    static func priorMean(dayKeys: [String], values: [Double?], fromDay: String, selectedDay: String) -> Double? {
        let readings = zip(dayKeys, values).compactMap { day, value -> Double? in
            guard day >= fromDay, day < selectedDay, let value, value.isFinite else { return nil }
            return value
        }
        guard !readings.isEmpty else { return nil }
        return readings.reduce(0, +) / Double(readings.count)
    }

    static func targetStatus(displayedStrain: String?, lower: Int?, upper: Int?) -> TargetStatus {
        guard let displayedStrain, let strain21 = Double(displayedStrain), strain21.isFinite,
              let lower, let upper, lower <= upper else { return .unavailable }
        if strain21 < Double(lower) { return .under }
        if strain21 > Double(upper) { return .over }
        return .optimal
    }

    static func date(_ key: String) -> Date? {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter.date(from: key)
    }

    static func startKey(selectedDay: String, days: Int) -> String {
        guard let date = date(selectedDay), let start = Calendar.current.date(byAdding: .day, value: -days, to: date) else {
            return selectedDay
        }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter.string(from: start)
    }

    static func dateLabel(_ day: String, locale: Locale = .current) -> String {
        guard let date = date(day) else { return day }
        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.dateStyle = .full
        return formatter.string(from: date)
    }
}
