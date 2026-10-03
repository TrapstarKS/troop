#if DEBUG
import Foundation
import WhoopStore
import WhoopProtocol

enum RecoveryStrainDemoSeed {
    static func seed(into store: WhoopStore, deviceId: String, now: Date = Date()) async throws {
        let calendar = Calendar.current
        let today = calendar.startOfDay(for: now)
        let elapsedMinutes = Int(now.timeIntervalSince(today) / 60)
        let endMinute = min(1125, elapsedMinutes)
        let startMinute = max(0, endMinute - 45)
        var samples: [HRSample] = []
        for offset in -6...0 {
            guard let date = calendar.date(byAdding: .day, value: offset, to: today) else { continue }
            let start = Int(date.timeIntervalSince1970)
            let next = calendar.date(byAdding: .day, value: 1, to: date)!
            let totalMinutes = Int(next.timeIntervalSince(date) / 60)
            for minute in 0..<totalMinutes {
                let timestamp = start + minute * 60
                guard timestamp <= Int(now.timeIntervalSince1970) else { break }
                let exercise = offset == 0 ? (startMinute..<endMinute).contains(minute) : (1080..<1125).contains(minute)
                samples.append(HRSample(ts: timestamp, bpm: exercise ? 130 + minute % 12 * 3 : 58 + minute % 24))
            }
        }
        _ = try await store.insert(Streams(hr: samples), deviceId: deviceId)
        guard let activityDay = elapsedMinutes >= 45 ? today : calendar.date(byAdding: .day, value: -1, to: today) else { return }
        let activityEndMinute = elapsedMinutes >= 45 ? endMinute : 1125
        let activityStartMinute = activityEndMinute - 45
        let heartRates = (activityStartMinute..<activityEndMinute).map { 130 + $0 % 12 * 3 }
        let start = Int(activityDay.timeIntervalSince1970) + activityStartMinute * 60
        let end = Int(activityDay.timeIntervalSince1970) + activityEndMinute * 60
        let workout = WorkoutRow(startTs: start, endTs: end, sport: "Running", source: "manual",
                                 durationS: Double(end - start), energyKcal: 310,
                                 avgHr: heartRates.reduce(0, +) / heartRates.count, maxHr: heartRates.max(),
                                 strain: 52, distanceM: nil, zonesJSON: nil, notes: nil, steps: nil)
        _ = try await store.upsertWorkouts([workout], deviceId: deviceId)
    }
}
#endif
