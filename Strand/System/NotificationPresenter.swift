import Foundation
import UserNotifications
import StrandAnalytics

/// Foreground presentation delegate for the app's local notifications (wind-down nudge, smart-alarm
/// backup, battery/illness alerts).
///
/// Without a `UNUserNotificationCenterDelegate`, iOS/macOS suppress a notification's banner while the
/// app is in the FOREGROUND (the default). A user testing a reminder with the app open would see
/// nothing and conclude notifications are broken. Returning banner + sound + list here makes them
/// visible whether the app is open or not — matching what the user expects from a reminder.
///
/// Cross-platform (iOS + macOS). Register once at launch:
/// `UNUserNotificationCenter.current().delegate = NotificationPresenter.shared`.
final class NotificationPresenter: NSObject, UNUserNotificationCenterDelegate {

    static let shared = NotificationPresenter()

    private override init() { super.init() }

    /// K5: wired by the app root (`StrandApp` on macOS, `StrandiOSApp` on iOS) at launch to route a
    /// tapped scheduled morning-brief notification to the Coach screen via `NavRouter.openCoach()`. nil
    /// is a safe no-op (the tap is simply not routed) rather than a crash if this ever fires before the
    /// root has wired it.
    var onCoachBriefTapped: (() -> Void)?
    private let localTapBuffer = LocalNotificationTapBuffer()
    var onLocalNotificationContextTapped: ((LocalNotificationContext) -> Void)? {
        didSet { localTapBuffer.handler = onLocalNotificationContextTapped }
    }
    // Compatibility for existing route-only notifications. New dated reports use the complete handler.
    var onLocalNotificationTapped: ((String) -> Void)?

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound, .list])
    }

    /// Route report and device taps to their explicit destination. Retain a cold-start local route
    /// until the app root attaches its handler; provider briefs retain their existing Coach route.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let content = response.notification.request.content
        if content.categoryIdentifier == "local-report",
           let notification = LocalNotificationContext(wireFields: content.userInfo.reduce(into: [String: String]()) {
               if let key = $1.key as? String, let value = $1.value as? String { $0[key] = value }
           }) {
            DispatchQueue.main.async {
                if content.userInfo["localNotificationEvent"] == nil,
                   self.onLocalNotificationContextTapped == nil, let handler = self.onLocalNotificationTapped {
                    handler(notification.route)
                } else { self.localTapBuffer.receive(notification) }
            }
        } else if content.categoryIdentifier == CoachBriefScheduler.notificationCategoryId {
            onCoachBriefTapped?()
        }
        completionHandler()
    }
}
