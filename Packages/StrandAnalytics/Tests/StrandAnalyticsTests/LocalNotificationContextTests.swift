import XCTest
@testable import StrandAnalytics

final class LocalNotificationContextTests: XCTestCase {
private let contexts = [
    LocalNotificationContext(route: "local_briefing", eventID: "night:2026-10-02", family: "dailyOutlook", report:
        LocalRecordedReport(day: "2026-10-02", recovery: 82, sleepMinutes: 451, strainTenths: nil, streak: 7)),
    LocalNotificationContext(route: "local_briefing", eventID: "evening:2026-10-02", family: "dayInReview", report:
        LocalRecordedReport(day: "2026-10-02", recovery: nil, sleepMinutes: nil, strainTenths: 164, streak: 0,
            sleepNeedMinutes: 510, sleepDebtMinutes: 39)),
    LocalNotificationContext(route: "weekly_plan", eventID: "weeklyRecap:2026-09-21", family: "weeklyRecap",
        day: "2026-09-28", weekKey: "2026-09-21", message: "Saved plan recap"),
    LocalNotificationContext(route: "workouts", eventID: "workoutReady:1790967600", day: "2026-10-02",
        workoutStartSec: 1790967600, message: "Saved activity"),
    LocalNotificationContext(route: "devices", eventID: "devices")
]

    func testSwiftWireOracleAndRoundTrips() {
        let output = contexts.map { context in
            context.identity + "\n" + context.wireFields.keys.sorted().map {
                $0 + "=" + context.wireFields[$0]!
            }.joined(separator: "\n") + "\n--\n"
        }.joined()
        XCTAssertEqual(output, "local_briefing:night:2026-10-02\nlocalNotificationDay=2026-10-02\nlocalNotificationEvent=night:2026-10-02\nlocalNotificationFamily=dailyOutlook\nlocalNotificationRecovery=82\nlocalNotificationReport=1\nlocalNotificationRoute=local_briefing\nlocalNotificationSleep=451\nlocalNotificationStreak=7\n--\nlocal_briefing:evening:2026-10-02\nlocalNotificationDay=2026-10-02\nlocalNotificationEvent=evening:2026-10-02\nlocalNotificationFamily=dayInReview\nlocalNotificationReport=1\nlocalNotificationRoute=local_briefing\nlocalNotificationSleepDebt=39\nlocalNotificationSleepNeed=510\nlocalNotificationStrain=164\nlocalNotificationStreak=0\n--\nweekly_plan:weeklyRecap:2026-09-21\nlocalNotificationDay=2026-09-28\nlocalNotificationEvent=weeklyRecap:2026-09-21\nlocalNotificationFamily=weeklyRecap\nlocalNotificationMessage=Saved plan recap\nlocalNotificationRoute=weekly_plan\nlocalNotificationWeek=2026-09-21\n--\nworkouts:workoutReady:1790967600\nlocalNotificationDay=2026-10-02\nlocalNotificationEvent=workoutReady:1790967600\nlocalNotificationMessage=Saved activity\nlocalNotificationRoute=workouts\nlocalNotificationWorkoutStart=1790967600\n--\ndevices:devices\nlocalNotificationEvent=devices\nlocalNotificationRoute=devices\n--\n")
        for context in contexts { XCTAssertEqual(LocalNotificationContext(wireFields: context.wireFields), context) }
    }

    func testRetainedNightAfterMidnightAndNewDataPreservesMissingReadings() {
        let captured = LocalNotificationContext(wireFields: contexts[0].wireFields)!
        let latest = LocalRecordedReport(day: "2026-10-03", recovery: 22, sleepMinutes: 300, strainTenths: 99, streak: 8)
        let displayed = LocalRecordedReport.forDisplay(context: captured, current: latest)
        XCTAssertEqual(displayed, contexts[0].report)
        XCTAssertNil(displayed?.strainTenths)
        XCTAssertEqual(LocalRecordedReport.forDisplay(context: nil, current: latest), latest)
        XCTAssertNil(LocalRecordedReport.forDisplay(context: contexts[4], current: latest))
    }

    func testRecapPreservesRecordedWeekAndCopyAfterCurrentWeekChanges() {
        let captured = LocalNotificationContext(wireFields: contexts[2].wireFields)!
        let nextWeek = LocalNotificationContext(route: "weekly_plan", eventID: "weeklyRecap:2026-09-28",
            weekKey: "2026-09-28", message: "New recap")
        XCTAssertEqual(captured.weekKey, "2026-09-21")
        XCTAssertEqual(captured.message, "Saved plan recap")
        XCTAssertNotEqual(captured.identity, nextWeek.identity)
    }

    func testColdStartKeepsCompleteReportUntilTypedHandlerAttaches() {
        let buffer = LocalNotificationTapBuffer()
        buffer.receive(LocalNotificationContext(wireFields: contexts[0].wireFields)!)
        var delivered: [LocalNotificationContext] = []
        buffer.handler = { delivered.append($0) }
        XCTAssertEqual(delivered, [contexts[0]])
        buffer.handler = nil
        buffer.handler = { delivered.append($0) }
        XCTAssertEqual(delivered.count, 1)
        buffer.receive(contexts[2])
        XCTAssertEqual(delivered.last, contexts[2])
    }

    func testDatedReportsAndWorkoutsHaveDistinctRetainedIdentities() {
        let nextNight = LocalNotificationContext(route: "local_briefing", eventID: "night:2026-10-03")
        XCTAssertNotEqual(contexts[0].identity, nextNight.identity)
        let workout = LocalNotificationContext(wireFields: contexts[3].wireFields)!
        XCTAssertEqual(workout.workoutStartSec, 1790967600)
        XCTAssertNil(LocalNotificationContext(wireFields: [:]))
    }
}
