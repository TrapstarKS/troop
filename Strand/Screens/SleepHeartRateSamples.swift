import WhoopStore

enum SleepHeartRateSamples {
    static func runs(_ buckets: [HRBucket], from: Int, to: Int) -> [[HRBucket]] {
        guard to > from else { return [] }
        var runs: [[HRBucket]] = []
        for bucket in buckets.filter({ $0.bpm.isFinite && $0.ts >= from && $0.ts <= to }).sorted(by: { $0.ts < $1.ts }) {
            if runs.isEmpty || bucket.ts - runs[runs.count - 1].last!.ts > 300 { runs.append([]) }
            runs[runs.count - 1].append(bucket)
        }
        return runs
    }
}
