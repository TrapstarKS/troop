import Foundation
import XCTest
@testable import StrandAnalytics

/// Pins every display status and the exact range bits to the standalone optimized Swift oracle.
final class HealthMonitorAssessmentTests: XCTestCase {
    func testMatchesStandaloneSwiftOracle() {
        let trusted: [Double?] = Array(repeating: 35.0, count: 14)
        let boundaryCfg = MetricCfg(minVal: -100, maxVal: 100, floorSpread: 1, halfLifeB: 14, halfLifeS: 21)
        let zeroHistory: [Double?] = Array(repeating: 0.0, count: 14)
        let sigma = 1.253
        let oxygen = HealthMonitorAssessment.bloodOxygenCfg

        var rows: [String] = []
        func bits(_ value: Double?) -> String {
            value.map { String($0.bitPattern, radix: 16) } ?? "nil"
        }
        func record(_ name: String, _ value: Double?, _ history: [Double?], _ cfg: MetricCfg = Baselines.hrvCfg,
                    verified: Bool = true) {
            let result = HealthMonitorAssessment.assess(value: value, history: history, cfg: cfg, verified: verified)
            rows.append("\(name)|\(result.status.rawValue)|\(bits(result.lower))|\(bits(result.upper))|\(result.nights)")
        }

        record("missing", nil, trusted)
        record("nan", .nan, trusted)
        record("positiveInfinity", .infinity, trusted)
        record("negativeInfinity", -.infinity, trusted)
        record("missingUnverified", nil, trusted, verified: false)
        record("unverified", 35, trusted, verified: false)
        record("implausibleLow", 4, trusted)
        record("implausibleHigh", 251, trusted)
        record("noHistory", 35, [])
        for nights in [1, 3, 4, 13, 14] {
            record("nights\(nights)", 35, Array(repeating: 35.0, count: nights))
        }
        record("missingHistory", 35, Array(repeating: nil, count: 20))
        record("mixedHistory", 35, [nil, .nan, .infinity, -.infinity, 4, 251] + trusted)
        record("thirteenWithNan", 35, Array(repeating: 35.0, count: 13) + [.nan])
        record("gap14", 35, trusted + Array(repeating: nil, count: 14))
        record("gap15", 35, trusted + Array(repeating: nil, count: 15))
        record("nonfiniteGap15", 35, trusted + Array(repeating: .infinity, count: 15))
        record("resumed", 35, trusted + Array(repeating: nil, count: 15) + [35])
        for multiplier in [-3.000001, -3.0, -2.000001, -2.0, 0.0, 2.0, 2.000001, 3.0, 3.000001] {
            record("z\(String(format: "%.6f", locale: Locale(identifier: "en_US_POSIX"), multiplier))",
                   multiplier * sigma, zeroHistory, boundaryCfg)
        }
        record("oxygenCalibrating", 98, Array(repeating: 98.0, count: 13), oxygen)
        record("oxygenWithin", 98, Array(repeating: 98.0, count: 14), oxygen)
        record("oxygenOutside", 96.5, Array(repeating: 98.0, count: 14), oxygen)
        record("oxygenFarOutside", 95, Array(repeating: 98.0, count: 14), oxygen)
        record("oxygenClippedHigh", 100, Array(repeating: 100.0, count: 14), oxygen)
        record("oxygenClippedLow", 70, Array(repeating: 70.0, count: 14), oxygen)
        record("oxygenImplausible", 69, Array(repeating: 98.0, count: 14), oxygen)
        record("skinDeviation", 0.2, Array(repeating: 0.2, count: 14), VitalBands.skinTempDeviationCfg)
        record("varyingHistory", 38, (0..<30).map { Double(30 + ($0 * 7) % 11) })
        XCTAssertEqual(rows.count, 39)
        XCTAssertEqual(rows.joined(separator: "\n") + "\n", Self.swiftOracle + "\n")
    }

    private static let swiftOracle = """
missing|unavailable|nil|nil|0
nan|unavailable|nil|nil|0
positiveInfinity|unavailable|nil|nil|0
negativeInfinity|unavailable|nil|nil|0
missingUnverified|unavailable|nil|nil|0
unverified|unverified|nil|nil|14
implausibleLow|unverified|nil|nil|14
implausibleHigh|unverified|nil|nil|14
noHistory|calibrating|nil|nil|0
nights1|calibrating|nil|nil|1
nights3|calibrating|nil|nil|3
nights4|calibrating|nil|nil|4
nights13|calibrating|nil|nil|13
nights14|withinRange|40367851eb851eb8|4047c3d70a3d70a4|14
missingHistory|calibrating|nil|nil|0
mixedHistory|withinRange|40367851eb851eb8|4047c3d70a3d70a4|14
thirteenWithNan|calibrating|nil|nil|13
gap14|withinRange|40367851eb851eb8|4047c3d70a3d70a4|14
gap15|calibrating|nil|nil|14
nonfiniteGap15|calibrating|nil|nil|14
resumed|withinRange|40367851eb851eb8|4047c3d70a3d70a4|15
z-3.000001|farOutsideRange|c0040c49ba5e353f|40040c49ba5e353f|14
z-3.000000|outsideRange|c0040c49ba5e353f|40040c49ba5e353f|14
z-2.000001|outsideRange|c0040c49ba5e353f|40040c49ba5e353f|14
z-2.000000|withinRange|c0040c49ba5e353f|40040c49ba5e353f|14
z0.000000|withinRange|c0040c49ba5e353f|40040c49ba5e353f|14
z2.000000|withinRange|c0040c49ba5e353f|40040c49ba5e353f|14
z2.000001|outsideRange|c0040c49ba5e353f|40040c49ba5e353f|14
z3.000000|outsideRange|c0040c49ba5e353f|40040c49ba5e353f|14
z3.000001|farOutsideRange|c0040c49ba5e353f|40040c49ba5e353f|14
oxygenCalibrating|calibrating|nil|nil|13
oxygenWithin|withinRange|40582fced916872b|4058d03126e978d5|14
oxygenOutside|outsideRange|40582fced916872b|4058d03126e978d5|14
oxygenFarOutside|farOutsideRange|40582fced916872b|4058d03126e978d5|14
oxygenClippedHigh|withinRange|4058afced916872b|4059000000000000|14
oxygenClippedLow|withinRange|4051800000000000|4051d03126e978d5|14
oxygenImplausible|unverified|nil|nil|14
skinDeviation|withinRange|bfe1a858793dd97e|3fee7525460aa64c|14
varyingHistory|withinRange|40367c0179714b7c|4047c5aed1338706|30
"""
}
