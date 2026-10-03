import XCTest
@testable import StrandAnalytics

final class LocalNotificationDeliveryPlanTests: XCTestCase {
    func testEveryEnabledAndAvailableCombinationSubmitsOneHighestPriorityFamily() async {
        for group in LocalNotificationReportGroup.allCases {
            let families = group.families
            for availableMask in 0..<(1 << families.count) {
                let available = familySet(families, mask: availableMask)
                for enabledMask in 0..<(1 << families.count) {
                    let enabled = familySet(families, mask: enabledMask)
                    var submitted: [LocalNotificationDeliveryPlan] = []
                    let plan = await LocalNotificationDeliveryPlan.deliver(group: group, availableFamilies: available,
                        enabledFamilies: enabled, deliveredFamilies: [], authorized: true, quiet: false,
                        occurrenceSec: 1000, nowSec: 1100) {
                            submitted.append($0)
                            return true
                        }
                    XCTAssertEqual(plan?.primaryFamily, families.first { available.contains($0) && enabled.contains($0) })
                    XCTAssertEqual(plan?.coveredFamilies, plan == nil ? nil : families)
                    XCTAssertEqual(submitted, plan.map { [$0] } ?? [])
                }
            }
        }
        XCTAssertEqual(oracleOutput(), Self.swiftOracle)
    }

    func testAnyDeliveredFamilySuppressesTheGroupDespiteChangedOptIns() async {
        for group in LocalNotificationReportGroup.allCases {
            let families = group.families
            for deliveredMask in 1..<(1 << families.count) {
                for enabledMask in 0..<(1 << families.count) {
                    let plan = await LocalNotificationDeliveryPlan.deliver(group: group, availableFamilies: Set(families),
                        enabledFamilies: familySet(families, mask: enabledMask),
                        deliveredFamilies: familySet(families, mask: deliveredMask), authorized: true, quiet: false,
                        occurrenceSec: 1000, nowSec: 1100) { _ in
                            XCTFail("A covered event must not be submitted")
                            return true
                        }
                    XCTAssertNil(plan)
                }
            }
            XCTAssertEqual(LocalNotificationDeliveryPlan.resolve(group: group, availableFamilies: Set(families),
                enabledFamilies: Set(families), deliveredFamilies: ["workoutReady"])?.primaryFamily, families.first)
        }
    }

    func testDeniedQuietAndFailedAttemptsDoNotConsumeCoverageBeforeCatchUp() async {
        for group in LocalNotificationReportGroup.allCases {
            let families = Set(group.families)
            let simulation = DeliverySimulation()
            let denied = await simulation.dispatch(group: group, event: "2026-10-02", available: families,
                enabled: families, authorized: false)
            XCTAssertNil(denied)
            XCTAssertTrue(simulation.markers.isEmpty)
            XCTAssertEqual(simulation.submissions, 0)
            let quiet = await simulation.dispatch(group: group, event: "2026-10-02", available: families,
                enabled: families, quiet: true)
            XCTAssertNil(quiet)
            XCTAssertTrue(simulation.markers.isEmpty)
            XCTAssertEqual(simulation.submissions, 0)
            let failed = await simulation.dispatch(group: group, event: "2026-10-02", available: families,
                enabled: families, accepted: false)
            XCTAssertNil(failed)
            XCTAssertTrue(simulation.markers.isEmpty)
            XCTAssertEqual(simulation.submissions, 1)
            let catchUp = await simulation.dispatch(group: group, event: "2026-10-02", available: families,
                enabled: families, now: 1300)
            XCTAssertEqual(catchUp, group.families.first)
            XCTAssertEqual(simulation.submissions, 2)
            XCTAssertEqual(simulation.markers, Dictionary(uniqueKeysWithValues: group.families.map {
                ($0, "\($0):2026-10-02")
            }))
            let restarted = DeliverySimulation(markers: simulation.markers)
            for enabledMask in 0..<(1 << group.families.count) {
                let repeated = await restarted.dispatch(group: group, event: "2026-10-02", available: families,
                    enabled: familySet(group.families, mask: enabledMask))
                XCTAssertNil(repeated)
            }
            XCTAssertEqual(restarted.submissions, 0)
            let previousMarkers = restarted.markers
            let failedNextEvent = await restarted.dispatch(group: group, event: "2026-10-03", available: families,
                enabled: families, accepted: false)
            XCTAssertNil(failedNextEvent)
            XCTAssertEqual(restarted.markers, previousMarkers)
            XCTAssertEqual(restarted.submissions, 1)
            let nextEvent = await restarted.dispatch(group: group, event: "2026-10-03", available: families,
                enabled: families)
            XCTAssertEqual(nextEvent, group.families.first)
            XCTAssertEqual(restarted.submissions, 2)
        }
    }

    func testLegacyGranularMarkerPreventsRicherRepeatAndGroupsRemainIndependent() async {
        let simulation = DeliverySimulation(markers: ["recoveryReady": "recoveryReady:2026-10-02"])
        let repeatNight = await simulation.dispatch(group: .night, event: "2026-10-02",
            available: Set(LocalNotificationReportGroup.night.families), enabled: ["dailyOutlook"])
        XCTAssertNil(repeatNight)
        XCTAssertEqual(simulation.submissions, 0)
        XCTAssertEqual(simulation.markers, ["recoveryReady": "recoveryReady:2026-10-02"])
        let evening = await simulation.dispatch(group: .evening, event: "2026-10-02",
            available: ["dayInReview", "streakSummary"], enabled: ["dayInReview", "streakSummary"])
        XCTAssertEqual(evening, "dayInReview")
        XCTAssertEqual(simulation.submissions, 1)
        XCTAssertEqual(simulation.markers["recoveryReady"], "recoveryReady:2026-10-02")
        let nextNight = await simulation.dispatch(group: .night, event: "2026-10-03",
            available: ["morningRecap", "recoveryReady"], enabled: ["morningRecap", "recoveryReady"])
        XCTAssertEqual(nextNight, "morningRecap")
        XCTAssertEqual(simulation.submissions, 2)
    }

    func testFutureExpiredAndMissingOccurrencesNeverSubmit() async {
        for occurrence in [nil, -1, 1101, 0] as [Int?] {
            let plan = await LocalNotificationDeliveryPlan.deliver(group: .night, availableFamilies: ["dailyOutlook"],
                enabledFamilies: ["dailyOutlook"], deliveredFamilies: [], authorized: true, quiet: false,
                occurrenceSec: occurrence, nowSec: occurrence == 0 ? 86_401 : 1100) { _ in
                    XCTFail("An invalid or expired event must not be submitted")
                    return true
                }
            XCTAssertNil(plan)
        }
    }

    private func familySet(_ families: [String], mask: Int) -> Set<String> {
        Set(families.enumerated().compactMap { mask & (1 << $0.offset) != 0 ? $0.element : nil })
    }

    private func oracleOutput() -> String {
        var lines: [String] = []
        for group in LocalNotificationReportGroup.allCases {
            let families = group.families
            lines.append("\(group)|\(families.joined(separator: ","))")
            for availableMask in 0..<(1 << families.count) {
                var selections = ""
                for enabledMask in 0..<(1 << families.count) {
                    let plan = LocalNotificationDeliveryPlan.resolve(group: group,
                        availableFamilies: familySet(families, mask: availableMask),
                        enabledFamilies: familySet(families, mask: enabledMask), deliveredFamilies: [])
                    selections += plan.map { String(families.firstIndex(of: $0.primaryFamily)!) } ?? "-"
                }
                lines.append("\(availableMask)|\(selections)")
            }
        }
        return lines.joined(separator: "\n") + "\n"
    }

    private static let swiftOracle = """
    night|dailyOutlook,morningRecap,sleepReady,recoveryReady
    0|----------------
    1|-0-0-0-0-0-0-0-0
    2|--11--11--11--11
    3|-010-010-010-010
    4|----2222----2222
    5|-0-02020-0-02020
    6|--112211--112211
    7|-0102010-0102010
    8|--------33333333
    9|-0-0-0-030303030
    10|--11--1133113311
    11|-010-01030103010
    12|----222233332222
    13|-0-0202030302020
    14|--11221133112211
    15|-010201030102010
    evening|dayInReview,strainReady,streakSummary
    0|--------
    1|-0-0-0-0
    2|--11--11
    3|-010-010
    4|----2222
    5|-0-02020
    6|--112211
    7|-0102010
    """ + "\n"
}

private final class DeliverySimulation {
    var markers: [String: String]
    var submissions = 0

    init(markers: [String: String] = [:]) { self.markers = markers }

    func dispatch(group: LocalNotificationReportGroup, event: String, available: Set<String>, enabled: Set<String>,
                  authorized: Bool = true, quiet: Bool = false, accepted: Bool = true, now: Int = 1100) async -> String? {
        let delivered = Set(group.families.filter { markers[$0] == "\($0):\(event)" })
        guard let plan = await LocalNotificationDeliveryPlan.deliver(group: group, availableFamilies: available,
            enabledFamilies: enabled, deliveredFamilies: delivered, authorized: authorized, quiet: quiet,
            occurrenceSec: 1000, nowSec: now, submit: { _ in
                self.submissions += 1
                return accepted
            }) else { return nil }
        for family in plan.coveredFamilies { markers[family] = "\(family):\(event)" }
        return plan.primaryFamily
    }
}
