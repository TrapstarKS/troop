import Foundation

/// Existing Body Age and step observations, never a clinical biological-age model.
public enum HealthspanPresentation {
    public struct AgeSample: Equatable, Sendable {
        public let daysAgo: Int
        public let age: Double
        public init(daysAgo: Int, age: Double) { self.daysAgo = daysAgo; self.age = age }
    }

    public struct StepSample: Equatable, Sendable {
        public let day: String
        public let count: Double
        public let source: String

        public init(day: String, count: Double, source: String) {
            self.day = day
            self.count = count
            self.source = source
        }
    }

    public struct Snapshot: Equatable, Sendable {
        public let eligibility: HealthspanHistory.Eligibility
        public let age: Double?
        public let paceTenths: Int?
        public let recoveryDays: Int
        public let recentSamples: Int
        public let historySamples: Int
        public var pace: Double? { paceTenths.map { Double($0) / 10 } }
    }

    /// Age requires initial 90-day recovery span and continuing 21-in-31 coverage.
    /// Pace = 1 + 2 × (30-day mean Body Age − up-to-180-day mean Body Age), clamped to −1…3.
    /// The factor 2 is a chosen display scale, not an annualized biological-aging rate.
    /// Kotlin twin: `HealthspanPresentation.snapshot`.
    public static func snapshot(samples: [AgeSample], recoveryOffsets: [Int], chronologicalAge: Double) -> Snapshot {
        var byDay: [Int: Double] = [:]
        for sample in samples where (0..<180).contains(sample.daysAgo) && sample.age.isFinite && (20...90).contains(sample.age) {
            byDay[sample.daysAgo] = sample.age
        }
        let offsets = byDay.keys.sorted()
        let recent = offsets.filter { $0 < 30 }.compactMap { byDay[$0] }
        let history = offsets.compactMap { byDay[$0] }
        let eligibility = HealthspanHistory.eligibility(recoveryOffsets: recoveryOffsets, chronologicalAge: chronologicalAge, latestAgeDaysAgo: offsets.first)
        let age = eligibility.state == .ready ? offsets.first.flatMap { byDay[$0] } : nil
        var paceTenths: Int?
        if age != nil, recent.count >= 3, history.count >= 8, (offsets.last ?? 0) >= 89 {
            let recentMean = recent.reduce(0, +) / Double(recent.count)
            let historyMean = history.reduce(0, +) / Double(history.count)
            let pace = min(3, max(-1, 1 + 2 * (recentMean - historyMean)))
            paceTenths = Int(floor(pace * 10 + 0.5))
        }
        return Snapshot(eligibility: eligibility, age: age, paceTenths: paceTenths, recoveryDays: eligibility.recoveryDays,
                        recentSamples: recent.count, historySamples: history.count)
    }

    /// Zone durations count non-overlapping hourly buckets, never the sliding display timeline.
    /// Kotlin twin: `HealthspanPresentation.zoneMinutes`.
    public static func zoneMinutes(hours: [(level: Double?, minutes: Int)]) -> [Int] {
        var minutes = [0, 0, 0]
        for hour in hours {
            guard let value = hour.level, value.isFinite, (0...3).contains(value), hour.minutes > 0 else { continue }
            let zone = value < 1 ? 0 : (value < 2 ? 1 : 2)
            minutes[zone] += hour.minutes
        }
        return minutes
    }

    /// Uses inclusive canonical ISO day keys and caller-resolved measured WHOOP samples.
    /// Measured rows, including zero, win per day; imported maxima retain the first tied row.
    /// Kotlin twin: `HealthspanPresentation.latestSteps`.
    public static func latestSteps(measured: [StepSample], imported: [StepSample],
                                   fromDay: String, throughDay: String) -> StepSample? {
        // Kotlin twin: HealthspanPresentation.valid
        func valid(_ sample: StepSample) -> Bool {
            sample.count.isFinite && sample.count >= 0 && sample.day >= fromDay && sample.day <= throughDay
        }
        var byDay: [String: StepSample] = [:]
        for sample in imported where valid(sample) {
            if sample.count > (byDay[sample.day]?.count ?? -1) { byDay[sample.day] = sample }
        }
        // Reverse traversal preserves the first valid measured row for each day.
        for sample in measured.reversed() where valid(sample) { byDay[sample.day] = sample }
        return byDay.keys.max().flatMap { byDay[$0] }
    }
}
