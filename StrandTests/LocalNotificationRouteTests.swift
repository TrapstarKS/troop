import XCTest
import StrandAnalytics
@testable import Strand

final class LocalNotificationRouteTests: XCTestCase {
    @MainActor
    func testProducerKeysRemainPendingUntilConsumed() {
        let router = NavRouter()
        for (key, destination) in [("devices", NavRouter.Destination.devices),
                                   ("workouts", .workouts), ("weekly_plan", .weeklyPlan),
                                   ("local_briefing", .localBriefing)] {
            router.openLocalNotification(route: key)
            XCTAssertEqual(router.requestedDestination, destination)
            router.openLocalNotification(route: "activeWorkout")
            XCTAssertEqual(router.requestedDestination, destination)
            router.requestedDestination = nil
        }
    }

    @MainActor
    func testUnknownLocalRoutesCannotOpenWorkoutOrCoach() {
        let router = NavRouter()
        for key in ["activeWorkout", "coach", "https://example.com", ""] {
            router.openLocalNotification(route: key)
            XCTAssertNil(router.requestedDestination)
        }
    }
    @MainActor
    func testDatedTapRetainsMissingReadingsAndReplacesSameRouteEvent() {
        let router = NavRouter()
        let first = LocalNotificationContext(route: "local_briefing", eventID: "morning:2026-09-30",
            report: LocalRecordedReport(day: "2026-09-30", recovery: nil, sleepMinutes: 420,
                strainTenths: nil, streak: 4))
        router.openLocalNotification(context: first)
        XCTAssertEqual(router.requestedDestination, .localBriefing)
        XCTAssertEqual(router.requestedLocalNotificationContext, first)
        XCTAssertNil(router.requestedLocalNotificationContext?.report?.recovery)
        let second = LocalNotificationContext(route: "local_briefing", eventID: "evening:2026-09-30",
            day: "2026-09-30", message: "Saved evening notice")
        router.openLocalNotification(context: second)
        XCTAssertEqual(router.requestedLocalNotificationContext, second)
        router.openLocalNotification(context: LocalNotificationContext(route: "activeWorkout", eventID: "unknown"))
        XCTAssertEqual(router.requestedLocalNotificationContext, second)
        router.openLocalNotification(route: "devices")
        XCTAssertNil(router.requestedLocalNotificationContext)
        XCTAssertEqual(router.requestedDestination, .devices)
    }

}
