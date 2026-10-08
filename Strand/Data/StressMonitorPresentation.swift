import Foundation
import StrandAnalytics

extension StressMonitorReading.State {
    var message: String {
        switch self {
        case .recorded: return String(localized: "Latest recorded window")
        case .delayed: return String(localized: "Recorded window is over 15 minutes old.")
        case .noHeartRate: return String(localized: "No recorded heart rate for this day.")
        case .noWakingHeartRate: return String(localized: "No recorded heart rate in the 06:00–22:00 daytime window.")
        case .insufficientSamples: return String(localized: "A window needs at least 300 recorded heart-rate samples.")
        case .activityExcluded: return String(localized: "Recorded windows were excluded during activity.")
        }
    }
}
