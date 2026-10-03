#if DEBUG
import Foundation
import GRDB
import WhoopProtocol
import WhoopStore

enum HealthspanStressDemoSeed {
    static func seedIfDemo(into store: WhoopStore, pristineBeforeBaseSeed: Bool = false) async throws {
        guard AppleDemoSeeder.requested else { return }
        let isDemo = try await store.registryWriter.read { db in
            try String.fetchOne(db, sql: "SELECT name FROM device WHERE id = ?",
                                arguments: [AppleDemoSeeder.whoop]) == "WHOOP (demo)"
        }
        guard isDemo else { return }

        // ponytail: launch-time coverage is seeded once; reset the demo store when fresh coverage is needed.
        if !pristineBeforeBaseSeed {
            guard try await hasEmptyStreams(into: store) else { return }
        }

        // Synthetic banked HR only; never produced by the strap or used outside the demo seed.
        let hourlyBPM = [52, 56, 64, 68, 72, 78, 84, 88, 82, 74, 68, 76, 80, 70, 60, 54]
        let now = Date()
        let latestTs = Int(now.timeIntervalSince1970)
        let calendar = Calendar.current
        let today = calendar.startOfDay(for: now)
        try await seedDailyStressIfMissing(into: store, today: today, calendar: calendar)
        try await seedYesterdayActivityIfMissing(into: store, today: today, calendar: calendar)
        var hr: [HRSample] = []
        for dayOffset in -1...0 {
            guard let day = calendar.date(byAdding: .day, value: dayOffset, to: today) else { continue }
            for hour in 6..<22 {
                guard let start = calendar.date(bySettingHour: hour, minute: 0, second: 0, of: day) else { continue }
                let startTs = Int(start.timeIntervalSince1970)
                for sample in 0..<600 {
                    let ts = startTs + sample * 6
                    guard ts <= latestTs else { break }
                    let bpm = hourlyBPM[hour - 6] + (sample / 10) % 5 - 2
                    hr.append(HRSample(ts: ts, bpm: bpm))
                }
            }
        }
        _ = try await store.insert(Streams(hr: hr), deviceId: AppleDemoSeeder.whoop)
    }

    static func hasEmptyStreams(into store: WhoopStore) async throws -> Bool {
        let counts = try await store.storageRowCounts()
        let biometricTables = ["hr", "rr", "spo2", "skinTemp", "steps", "resp", "gravity",
                               "ppgHr", "sleepState", "ppgWaveform", "v18Aux"]
        return biometricTables.allSatisfy { counts[$0] == 0 }
    }

    // Kotlin twin: HealthspanStressDemoSeed.seedYesterdayActivityIfMissing.
    private static func seedYesterdayActivityIfMissing(into store: WhoopStore, today: Date,
                                                       calendar: Calendar) async throws {
        guard let yesterday = calendar.date(byAdding: .day, value: -1, to: today),
              let wakingStart = calendar.date(bySettingHour: 6, minute: 0, second: 0, of: yesterday),
              let wakingEnd = calendar.date(bySettingHour: 22, minute: 0, second: 0, of: yesterday),
              let noon = calendar.date(bySettingHour: 12, minute: 0, second: 0, of: yesterday) else { return }
        let from = Int(wakingStart.timeIntervalSince1970)
        let to = Int(wakingEnd.timeIntervalSince1970)
        for source in [AppleDemoSeeder.whoop, AppleDemoSeeder.apple] {
            let existing = try await store.workouts(deviceId: source, from: 0,
                                                    to: Int(today.timeIntervalSince1970) - 1, limit: .max)
            if existing.contains(where: { $0.endTs > $0.startTs && $0.startTs < to && $0.endTs > from }) { return }
        }

        // Synthetic activity context for the yesterday HR fixture; no physiological metrics are fabricated.
        let start = Int(noon.timeIntervalSince1970)
        _ = try await store.upsertWorkouts([
            WorkoutRow(startTs: start, endTs: start + 1200, sport: "Walking", source: AppleDemoSeeder.whoop,
                       durationS: 1200.0, energyKcal: nil, avgHr: nil, maxHr: nil, strain: nil,
                       distanceM: nil, zonesJSON: nil, notes: "Synthetic demo activity overlay", steps: nil),
        ], deviceId: AppleDemoSeeder.whoop)
    }

    private static func seedDailyStressIfMissing(into store: WhoopStore, today: Date,
                                                 calendar: Calendar) async throws {
        let existing = try await store.metricSeries(deviceId: AppleDemoSeeder.whoop, key: "stress",
                                                    from: "0000-00-00", to: "9999-99-99")
        guard existing.isEmpty,
              let yesterday = calendar.date(byAdding: .day, value: -1, to: today) else { return }

        // Synthetic daily preview fixtures, independent of the raw-HR stress analysis.
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = calendar.timeZone
        formatter.dateFormat = "yyyy-MM-dd"
        _ = try await store.upsertMetricSeries([
            MetricPoint(day: formatter.string(from: yesterday), key: "stress", value: 1.8),
            MetricPoint(day: formatter.string(from: today), key: "stress", value: 1.2),
        ], deviceId: AppleDemoSeeder.whoop)
    }
}
#endif
