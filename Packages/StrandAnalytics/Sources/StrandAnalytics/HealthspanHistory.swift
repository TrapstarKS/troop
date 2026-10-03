import Foundation

/// Read-only coverage and contributor comparisons; never changes stored Body Age.
public enum HealthspanHistory {
    public enum State: String, Sendable { case adultOnly, initialCalibration, recentCoverage, unavailable, ready }
    public struct Eligibility: Equatable, Sendable {
        public let state: State
        public let initialDays: Int
        public let recoveryDays: Int
    }
    public struct Sample: Equatable, Sendable {
        public let daysAgo: Int
        public let value: Double
        public init(daysAgo: Int, value: Double) { self.daysAgo = daysAgo; self.value = value }
    }
    public struct Comparison: Equatable, Sendable {
        public let recentTenths: Int?
        public let longTermTenths: Int?
        public let recentCount: Int
        public let longTermCount: Int
    }

    /// Initial span is earliest dated valid recovery through the selected day, not a wear claim.
    /// Continued coverage is unique recovery dates in the inclusive preceding 31 days.
    /// Kotlin twin: `HealthspanHistory.eligibility`.
    public static func eligibility(recoveryOffsets: [Int], chronologicalAge: Double,
                                   latestAgeDaysAgo: Int?) -> Eligibility {
        let offsets = Set(recoveryOffsets.filter { (0..<4000).contains($0) })
        let initialDays = offsets.max().map { $0 + 1 } ?? 0
        let recent = offsets.filter { $0 < 31 }.count
        let state: State
        if !chronologicalAge.isFinite || chronologicalAge < 18 { state = .adultOnly }
        else if initialDays < 90 { state = .initialCalibration }
        else if recent < 21 { state = .recentCoverage }
        else if latestAgeDaysAgo.map({ (0...14).contains($0) }) != true { state = .unavailable }
        else { state = .ready }
        return Eligibility(state: state, initialDays: initialDays, recoveryDays: recent)
    }

    /// Shared 4,000-day history bound; the earliest selection retains its 31-day window.
    /// Kotlin twin: `HealthspanHistory.oldestReferenceOffset`.
    public static func oldestReferenceOffset(dayOffsets: [Int]) -> Int {
        max(0, (dayOffsets.filter { (0..<4000).contains($0) }.max() ?? 0) - 30)
    }

    /// Missing days remain gaps. Last valid duplicate wins; no zero filling or age impact.
    /// Weekly time averages only seven-day bins containing recorded observations.
    /// Kotlin twin: `HealthspanHistory.comparison`.
    public static func comparison(samples: [Sample], weekly: Bool = false) -> Comparison {
        let recent = values(samples: samples, days: 30, weekly: weekly)
        let longTerm = values(samples: samples, days: 180, weekly: weekly)
        // Kotlin twin: HealthspanHistory.tenths
        func tenths(_ values: [Double]) -> Int? {
            guard !values.isEmpty else { return nil }
            return Int(floor(values.reduce(0, +) / Double(values.count) * 10 + 0.5))
        }
        return Comparison(recentTenths: tenths(recent), longTermTenths: tenths(longTerm),
                          recentCount: recent.count, longTermCount: longTerm.count)
    }

    /// Selects a real recorded point within the requested comparison window.
    /// Kotlin twin: `HealthspanHistory.selected`.
    public static func selected(samples: [Sample], daysAgo: Int, windowDays: Int) -> Sample? {
        points(samples: samples, windowDays: windowDays).min { abs($0.daysAgo - daysAgo) < abs($1.daysAgo - daysAgo) }
    }

    /// Canonical recorded points, oldest offset first; Kotlin twin: `HealthspanHistory.points`.
    public static func points(samples: [Sample], windowDays: Int) -> [Sample] {
        var byDay: [Int: Double] = [:]
        for sample in samples where (0..<windowDays).contains(sample.daysAgo) && sample.value.isFinite && (0...100_000).contains(sample.value) {
            byDay[sample.daysAgo] = sample.value
        }
        return byDay.keys.sorted().map { Sample(daysAgo: $0, value: byDay[$0]!) }
    }

    public struct Activity: Sendable {
        public let daysAgo: Int
        public let durationSeconds: Double
        public let zones: [Double]?
        public let strength: Bool
        public init(daysAgo: Int, durationSeconds: Double, zones: [Double]?, strength: Bool) {
            self.daysAgo = daysAgo; self.durationSeconds = durationSeconds; self.zones = zones; self.strength = strength
        }
    }

    /// Logged activity grouped by start day; missing zones never become zero observations.
    /// Kotlin twin: `HealthspanHistory.activitySeries`.
    public static func activitySeries(_ activities: [Activity]) -> [[Sample]] {
        var totals = [[Int: Double](), [Int: Double](), [Int: Double]()]
        for activity in activities {
            guard (0..<180).contains(activity.daysAgo), activity.durationSeconds.isFinite, activity.durationSeconds > 0 else { continue }
            let minutes = activity.durationSeconds / 60
            if let zones = activity.zones, zones.count == 5, zones.allSatisfy({ $0.isFinite && (0...100).contains($0) }), zones.contains(where: { $0 > 0 }) {
                totals[0][activity.daysAgo, default: 0] += minutes * zones.prefix(3).reduce(0, +) / 100
                totals[1][activity.daysAgo, default: 0] += minutes * zones.suffix(2).reduce(0, +) / 100
            }
            if activity.strength { totals[2][activity.daysAgo, default: 0] += minutes }
        }
        return totals.map { byDay in byDay.keys.sorted().map { Sample(daysAgo: $0, value: byDay[$0]!) } }
    }

    /// Only explicit stored strength labels or the existing lifting-log source qualify.
    /// Kotlin twin: `HealthspanHistory.isStrength`.
    public static func isStrength(sport: String, source: String) -> Bool {
        let label = sport.lowercased().filter { $0 != " " && $0 != "_" && $0 != "-" }
        return source == "lifting" || ["strength", "strengthtraining", "traditionalstrengthtraining", "functionalstrengthtraining", "weightlifting", "weighttraining"].contains(label)
    }

    // Kotlin twin: HealthspanHistory.values
    private static func values(samples: [Sample], days: Int, weekly: Bool) -> [Double] {
        var bins: [Int: Double] = [:]
        for sample in points(samples: samples, windowDays: days) {
            bins[weekly ? sample.daysAgo / 7 : sample.daysAgo, default: 0] += sample.value
        }
        return bins.keys.sorted().compactMap { bins[$0] }
    }
}
