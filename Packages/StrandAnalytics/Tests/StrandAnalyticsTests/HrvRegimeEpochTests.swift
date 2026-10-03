import XCTest
import Foundation
@testable import StrandAnalytics

final class HrvRegimeEpochTests: XCTestCase {
    func testStandaloneSwiftOracleAndCalibrationEra() {
        var rows: [String] = []
        for first in [nil, -1, 0, 86399, 86400, 1777672799, 1777672800] as [Int?] {
            for offset in [-43200, 0, 19800, 50400] {
                for manual in [0.0, 172800.0, 1777680000.0] {
                    let epoch = Baselines.effectiveHrvEpoch(manualEpoch: manual,
                        firstScorableTimestamp: first, isWhoop5: true, offsetSec: offset)
                    rows.append(String(format: "%016llx", epoch.bitPattern))
                }
            }
        }
        rows.append(String(format: "%016llx", Baselines.effectiveHrvEpoch(manualEpoch: 172800,
            firstScorableTimestamp: 1777672800, isWhoop5: false, offsetSec: 0).bitPattern))
        for day in ["2026-04-30", "2026-05-01", "2026-05-02", "bad"] {
            rows.append(Baselines.isInHrvEra(day: day, epoch: 1777593600) ? "1" : "0")
        }
        let keys = ["2026-04-29", "2026-04-30", "2026-05-01", "2026-05-02"]
        let state = Baselines.foldHistory([40, 41, 42, 43], dayKeys: keys,
            cfg: Baselines.hrvCfg, baselineEpoch: 1777593600)
        rows.append(String(state.nValid))
        // Actual standalone Swift -O stdout, also pinned in HrvRegimeEpochTest.kt.
        XCTAssertEqual(rows.joined(separator: ","), "0000000000000000,4105180000000000,41da7d4fa0000000,0000000000000000,4105180000000000,41da7d4fa0000000,0000000000000000,4105180000000000,41da7d4fa0000000,0000000000000000,4105180000000000,41da7d4fa0000000,0000000000000000,4105180000000000,41da7d4fa0000000,0000000000000000,4105180000000000,41da7d4fa0000000,0000000000000000,4105180000000000,41da7d4fa0000000,0000000000000000,4105180000000000,41da7d4fa0000000,0000000000000000,4105180000000000,41da7d4fa0000000,0000000000000000,4105180000000000,41da7d4fa0000000,0000000000000000,4105180000000000,41da7d4fa0000000,0000000000000000,4105180000000000,41da7d4fa0000000,0000000000000000,4105180000000000,41da7d4fa0000000,0000000000000000,4105180000000000,41da7d4fa0000000,40f5180000000000,4105180000000000,41da7d4fa0000000,40f5180000000000,4105180000000000,41da7d4fa0000000,0000000000000000,4105180000000000,41da7d4fa0000000,40f5180000000000,4105180000000000,41da7d4fa0000000,40f5180000000000,4105180000000000,41da7d4fa0000000,40f5180000000000,4105180000000000,41da7d4fa0000000,41da7cfb40000000,41da7cfb40000000,41da7d4fa0000000,41da7cfb40000000,41da7cfb40000000,41da7d4fa0000000,41da7d4fa0000000,41da7d4fa0000000,41da7d4fa0000000,41da7d4fa0000000,41da7d4fa0000000,41da7d4fa0000000,41da7cfb40000000,41da7cfb40000000,41da7d4fa0000000,41da7cfb40000000,41da7cfb40000000,41da7d4fa0000000,41da7d4fa0000000,41da7d4fa0000000,41da7d4fa0000000,41da7d4fa0000000,41da7d4fa0000000,41da7d4fa0000000,4105180000000000,0,1,1,0,2")
        XCTAssertEqual(state.nValid, 2)
        XCTAssertEqual(state.status, .calibrating)
    }
}
