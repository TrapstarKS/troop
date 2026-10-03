import Foundation
import UserNotifications
import StrandAnalytics

/// Surfaces the illness early-warning as a macOS user notification when the banner transitions
/// from clear to raised — today it is silent unless the window is open (the menu-bar extra keeps
/// NOOP alive). Rate-limited to once per local calendar day; the in-app banner stays the live
/// surface. On-device only; the summary is APPROXIMATE — informational, not a diagnosis.
enum IllnessNotifier {
    private static let lastDayKey = "behavior.illnessLastNotifiedDay"
    private static let raisedKey = "behavior.illnessWasRaised"

    /// Ask up front (called when the user enables the watch) so the system dialog appears at a
    /// predictable moment, not on the first 3 a.m. transition.
    static func requestAuthorization() {
        UNUserNotificationCenter.current()
            .requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    /// Record every valid evaluation; only a persisted clear-to-raised edge can notify.
    static func onEvaluated(_ message: String?, enabled: Bool, valid: Bool) {
        guard IllnessAlertPolicy.shouldRecordEvaluation(enabled: enabled, valid: valid) else { return }
        let day = dayKey(Date())
        let defaults = UserDefaults.standard
        let previous = defaults.object(forKey: raisedKey) == nil ? nil : defaults.bool(forKey: raisedKey)
        let notify = IllnessAlertPolicy.shouldNotify(alert: message, previouslyRaised: previous,
                                                    lastNotifiedDay: defaults.string(forKey: lastDayKey), today: day)
        let raised = message != nil
        if previous != raised { defaults.set(raised, forKey: raisedKey) }
        guard notify, let message else { return }
        defaults.set(day, forKey: lastDayKey)
        let center = UNUserNotificationCenter.current()
        // Authorization is requested once via requestAuthorization() when the watch is enabled;
        // here we only check status (no second system prompt).
        center.getNotificationSettings { settings in
            guard settings.authorizationStatus == .authorized else { return }
            let content = UNMutableNotificationContent()
            content.title = String(localized: "Early warning: take it easy")
            content.subtitle = String(localized: "On-device estimate (approximate), not a diagnosis.")
            content.body = message
            content.sound = .default
            center.add(UNNotificationRequest(identifier: "illness-watch",
                                             content: content, trigger: nil))
        }
    }

    private static func dayKey(_ date: Date) -> String {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .gregorian)
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f.string(from: date)
    }
}
