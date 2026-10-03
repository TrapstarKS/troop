import Foundation

enum RecoveryStrainDetailLogic {
    enum TargetStatus: String {
        case unavailable, under, optimal, over
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
