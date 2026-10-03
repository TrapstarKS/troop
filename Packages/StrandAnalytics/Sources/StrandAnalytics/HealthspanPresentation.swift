import Foundation

/// A presentation of existing Body Age history, never a clinical biological-age model.
public enum HealthspanPresentation {
    public struct AgeSample: Equatable, Sendable {
        public let daysAgo: Int
        public let age: Double
        public init(daysAgo: Int, age: Double) { self.daysAgo = daysAgo; self.age = age }
    }

    public struct Snapshot: Equatable, Sendable {
        public let age: Double?
        public let paceTenths: Int?
        public let recoveryDays: Int
        public let recentSamples: Int
        public let historySamples: Int
        public var pace: Double? { paceTenths.map { Double($0) / 10 } }
    }

    /// Local calibration: 21 recoveries in 31 days; pace also needs 90 days of history.
    /// Pace = 1 + 2 × (30-day mean Body Age − up-to-180-day mean Body Age), clamped to −1…3.
    /// The factor 2 is a chosen display scale, not an annualized biological-aging rate.
    public static func snapshot(samples: [AgeSample], recoveryDays: Int, chronologicalAge: Double) -> Snapshot {
        var byDay: [Int: Double] = [:]
        for sample in samples where (0..<180).contains(sample.daysAgo) && sample.age.isFinite && (20...90).contains(sample.age) {
            byDay[sample.daysAgo] = sample.age
        }
        let offsets = byDay.keys.sorted()
        let recent = offsets.filter { $0 < 30 }.compactMap { byDay[$0] }
        let history = offsets.compactMap { byDay[$0] }
        let ready = chronologicalAge >= 18 && chronologicalAge.isFinite && recoveryDays >= 21
        let latest = offsets.first.flatMap { $0 <= 14 ? byDay[$0] : nil }
        let age = ready ? latest : nil
        var paceTenths: Int?
        if age != nil, recent.count >= 3, history.count >= 8, (offsets.last ?? 0) >= 89 {
            let recentMean = recent.reduce(0, +) / Double(recent.count)
            let historyMean = history.reduce(0, +) / Double(history.count)
            let pace = min(3, max(-1, 1 + 2 * (recentMean - historyMean)))
            paceTenths = Int(floor(pace * 10 + 0.5))
        }
        return Snapshot(age: age, paceTenths: paceTenths, recoveryDays: max(0, recoveryDays),
                        recentSamples: recent.count, historySamples: history.count)
    }

    /// Zone durations count non-overlapping hourly buckets, never the sliding display timeline.
    public static func zoneMinutes(hours: [(level: Double?, minutes: Int)]) -> [Int] {
        var minutes = [0, 0, 0]
        for hour in hours {
            guard let value = hour.level, value.isFinite, (0...3).contains(value), hour.minutes > 0 else { continue }
            let zone = value < 1 ? 0 : (value < 2 ? 1 : 2)
            minutes[zone] += hour.minutes
        }
        return minutes
    }
}
