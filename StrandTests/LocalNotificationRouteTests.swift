import XCTest
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
}
