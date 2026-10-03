import Foundation

/// Eligibility for local wellness alerts; a retained computed HRV is not evidence from the fresh scan.
public enum HealthSignalReliability {
    public struct Record: Equatable, Sendable {
        public let value: Double
        public let eligible: Bool
        public init(value: Double, eligible: Bool) { self.value = value; self.eligible = eligible }
        /// Kotlin twin: `HealthSignalReliability.Record.matches`.
        public func matches(_ currentValue: Double?) -> Bool { eligible && value.isFinite && currentValue == value }
    }

    /// Kotlin twin: `HealthSignalReliability.firstRecord`.
    public static func firstRecord(sourceIds: [String], bySource: [String: Record]) -> Record? {
        sourceIds.lazy.compactMap { bySource[$0] }.first
    }

    /// Kotlin twin: `HealthSignalReliability.respiration`.
    public static func respiration(_ value: Double?, computed: Bool, freshScoringValid: Double? = nil) -> Double? {
        guard let value, value.isFinite, value >= Baselines.respCfg.minVal, value <= Baselines.respCfg.maxVal else { return nil }
        guard !computed || (freshScoringValid.map { $0.isFinite && $0 >= 0.5 } ?? false) else { return nil }
        return value
    }

    /// Kotlin twin: `HealthSignalReliability.hrv`.
    public static func hrv(_ value: Double?, computed: Bool,
                           freshScoringValid: Double? = nil, overcount: Double? = nil) -> Double? {
        guard let value, value.isFinite, value >= Baselines.hrvCfg.minVal,
              value <= Baselines.hrvCfg.maxVal else { return nil }
        if computed {
            guard let freshScoringValid, freshScoringValid.isFinite, freshScoringValid >= 0.5,
                  !(overcount.map { !$0.isFinite || $0 >= 0.5 } ?? false) else { return nil }
        }
        return value
    }

    /// Chronological civil-day keys, including missing nights, independent of the current time zone.
    /// Kotlin twin: `HealthSignalReliability.dayKeys`.
    public static func dayKeys(ending day: String, count: Int, daysAgo: Int = 0,
                               baselineEpoch: Double = 0) -> [String] {
        guard count > 0 else { return [] }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        let formatter = DateFormatter()
        formatter.calendar = calendar
        formatter.timeZone = calendar.timeZone
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        guard let end = formatter.date(from: day), formatter.string(from: end) == day else { return [] }
        return (0..<count).compactMap { index in
            guard let date = calendar.date(byAdding: .day, value: index - count + 1 - daysAgo, to: end),
                  baselineEpoch <= 0 || date.timeIntervalSince1970 >= baselineEpoch else { return nil }
            return formatter.string(from: date)
        }
    }
}

/// Mirrors Android's durable clear-to-raised, once-per-local-day notification policy.
public enum IllnessAlertPolicy {
    /// Kotlin twin: `IllnessAlertPolicy.shouldNotify`.
    public static func shouldNotify(alert: String?, previouslyRaised: Bool?,
                                    lastNotifiedDay: String?, today: String) -> Bool {
        alert != nil && previouslyRaised == false && lastNotifiedDay != today
    }
    /// Kotlin twin: `IllnessAlertPolicy.shouldRecordEvaluation`.
    public static func shouldRecordEvaluation(enabled: Bool, valid: Bool) -> Bool {
        enabled && valid
    }
}
