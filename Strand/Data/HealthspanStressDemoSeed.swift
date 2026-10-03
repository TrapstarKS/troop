#if DEBUG
import Foundation
import GRDB
import WhoopProtocol
import WhoopStore

enum HealthspanStressDemoSeed {
    static func seedIfDemo(into store: WhoopStore) async throws {
        guard AppleDemoSeeder.requested else { return }
        let isDemo = try await store.registryWriter.read { db in
            try String.fetchOne(db, sql: "SELECT name FROM device WHERE id = ?",
                                arguments: [AppleDemoSeeder.whoop]) == "WHOOP (demo)"
        }
        guard isDemo else { return }

        let counts = try await store.storageRowCounts()
        let biometricTables = ["hr", "rr", "spo2", "skinTemp", "steps", "resp", "gravity",
                               "ppgHr", "sleepState", "ppgWaveform", "v18Aux"]
        // ponytail: launch-time coverage is seeded once; reset the demo store when fresh coverage is needed.
        guard biometricTables.allSatisfy({ counts[$0] == 0 }) else { return }

        // Synthetic banked HR only; never produced by the strap or used outside the demo seed.
        let hourlyBPM = [52, 56, 64, 68, 72, 78, 84, 88, 82, 74, 68, 76, 80, 70, 60, 54]
        let now = Date()
        let latestTs = Int(now.timeIntervalSince1970)
        let calendar = Calendar.current
        let today = calendar.startOfDay(for: now)
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
}
#endif
