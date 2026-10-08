public enum StressMonitorReading {
    public enum State: String, Equatable, Sendable {
        case recorded, delayed, noHeartRate, noWakingHeartRate, insufficientSamples, activityExcluded
    }

    public struct Window: Equatable, Sendable {
        public let startTs: Int
        public let endTs: Int
        public let level: Double?
        public let maskedForActivity: Bool

        public init(startTs: Int, endTs: Int, level: Double?, maskedForActivity: Bool = false) {
            self.startTs = startTs
            self.endTs = endTs
            self.level = level
            self.maskedForActivity = maskedForActivity
        }
    }

    public struct Reading: Equatable, Sendable {
        public let window: Window?
        public let state: State
    }

    /// Kotlin twin: `StressMonitorReading.resolve`.
    public static func resolve(windows: [Window], hasHeartRate: Bool, now: Int,
                               isToday: Bool, selectedStartTs: Int? = nil) -> Reading {
        let window: Window?
        if let selectedStartTs {
            window = windows.first { $0.startTs == selectedStartTs }
        } else {
            window = windows.filter { $0.level.map { $0.isFinite && (0...3).contains($0) } ?? false }
                .max { $0.startTs < $1.startTs }
        }
        if let window, let level = window.level, level.isFinite, (0...3).contains(level) {
            // Freshness labels a recorded estimate; it cannot erase the day's usable data.
            let delayed = selectedStartTs == nil && isToday && now - window.endTs > 900
            return Reading(window: window, state: delayed ? .delayed : .recorded)
        }
        let state: State
        if window?.maskedForActivity == true || (selectedStartTs == nil && windows.contains { $0.maskedForActivity }) {
            state = .activityExcluded
        } else if !hasHeartRate {
            state = .noHeartRate
        } else if windows.isEmpty {
            state = .noWakingHeartRate
        } else {
            state = .insufficientSamples
        }
        return Reading(window: window, state: state)
    }
}
