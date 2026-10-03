import XCTest
@testable import Strand

final class RecoveryStrainDetailLogicTests: XCTestCase {
    func testStandaloneSwiftOraclePinsWindowsTimestampsAndZoneProvenance() {
        var lines: [String] = []
        let windows: [(Int, Int, Bool, Bool, Int?, Int?, Int)] = [
         (0,86400,true,false,nil,nil,97200),
         (0,86400,false,false,nil,nil,172800),
         (0,86400,true,true,nil,nil,97200),
         (0,86400,false,true,nil,nil,172800),
         (0,86400,true,true,72000,nil,97200),
         (0,86400,false,true,72000,88200,172800),
         (0,86400,true,true,72000,88200,97200),
         (0,86400,true,true,72000,108000,97200),
         (0,86400,true,false,72000,88200,97200),
         (86400,169200,false,false,nil,nil,250000),
         (86400,176400,false,false,nil,nil,250000),
         (172800,259200,false,false,nil,nil,97200),
         (0,86400,true,true,72000,72000,97200),
         (0,86400,true,true,72000,71000,97200),
         (0,86400,true,true,100000,nil,97200),
         (86400,0,false,false,nil,nil,172800),
         (0,86400,false,true,72000,Int.min,172800),
         (0,86400,true,true,72000,Int.max,97200),
        ]
        lines.append(windows.map { c in RecoveryStrainDetailLogic.strainWindow(calendarStart:c.0,nextCalendarStart:c.1,isCurrentDay:c.2,sleepOnsetMode:c.3,onset:c.4,nextOnset:c.5,now:c.6).map { "\($0.lowerBound):\($0.upperBound)" } ?? "unavailable" }.joined(separator:","))
        let timestamps: [Double?] = [nil,.nan,.infinity,-.infinity,-0.1,0,0.9,1.9,Double(Int.min),Double(Int.min).nextDown,Double(Int.max),Double(Int.max).nextDown]
        lines.append(timestamps.map { RecoveryStrainDetailLogic.timestampSeconds($0).map(String.init) ?? "unavailable" }.joined(separator:","))
        let percentages = [10.0,20,20,20,30]
        let recorded = [1.0,2,3,4,5]
        let durations: [Double] = [0,-1,.nan,.infinity,-.infinity,600,90,1e300]
        var distributions = durations.map { RecoveryStrainDetailLogic.zoneDistribution(importedPercentages:percentages,durationSeconds:$0,recordedMinutes:recorded) }
        distributions += [RecoveryStrainDetailLogic.zoneDistribution(importedPercentages:nil,durationSeconds:600,recordedMinutes:recorded),RecoveryStrainDetailLogic.zoneDistribution(importedPercentages:percentages,durationSeconds:0),RecoveryStrainDetailLogic.zoneDistribution(importedPercentages:percentages,durationSeconds:600)]
        lines.append(distributions.map { d in d.map { ($0.imported ? "imported" : "recorded") + "|" + $0.minutes.map { String($0.bitPattern) }.joined(separator:":") } ?? "unavailable" }.joined(separator:","))
        let expected = """
        0:97200,0:86399,0:97200,0:86399,72000:97200,72000:88199,72000:88199,72000:97200,0:97200,86400:169199,86400:176399,unavailable,unavailable,unavailable,unavailable,unavailable,unavailable,72000:97200
        unavailable,unavailable,unavailable,unavailable,-1,0,0,1,-9223372036854775808,unavailable,unavailable,9223372036854774784
        recorded|4607182418800017408:4611686018427387904:4613937818241073152:4616189618054758400:4617315517961601024,recorded|4607182418800017408:4611686018427387904:4613937818241073152:4616189618054758400:4617315517961601024,recorded|4607182418800017408:4611686018427387904:4613937818241073152:4616189618054758400:4617315517961601024,recorded|4607182418800017408:4611686018427387904:4613937818241073152:4616189618054758400:4617315517961601024,recorded|4607182418800017408:4611686018427387904:4613937818241073152:4616189618054758400:4617315517961601024,imported|4607182418800017408:4611686018427387904:4611686018427387904:4611686018427387904:4613937818241073152,imported|4594572339843380019:4599075939470750515:4599075939470750515:4599075939470750515:4601778099247172813,imported|9053470209761937459:9057973809389307955:9057973809389307955:9057973809389307955:9060843088576613453,recorded|4607182418800017408:4611686018427387904:4613937818241073152:4616189618054758400:4617315517961601024,unavailable,imported|4607182418800017408:4611686018427387904:4611686018427387904:4611686018427387904:4613937818241073152
        """
        XCTAssertEqual(lines.joined(separator: "\n"), expected)
    }

    func testStandaloneSwiftOraclePinsDisplayedComparisonPrecisionAndSignedZero() {
        var lines: [String] = []
        let values: [Double?] = [nil,.nan,.infinity,-.infinity,-0.0,0,0.01,-0.01,0.05,-0.05,0.15,-0.15,0.5,-0.5,Double(0.5).nextDown,Double(0.5).nextUp,Double(-0.5).nextDown,Double(-0.5).nextUp,56.49,56.5,1e300,Double.greatestFiniteMagnitude]
        for decimals in [-1,0,1,2] {
         lines.append(values.map { RecoveryStrainDetailLogic.comparisonValue($0,decimals:decimals).map { String($0.bitPattern) } ?? "unavailable" }.joined(separator:","))
        }
        let pairs: [(Double?,Double?,Int)] = [(56,55.999,0),(56,56.01,0),(56,55.5,0),(56.49,55.5,0),(56.5,55.5,0),(14.6,14.61,1),(14.6,14.65,1),(77.1,80.4,1),(nil,56,0),(56,nil,0),(.nan,56,0),(56,.infinity,0),(-1e308,1e308,1)]
        lines.append(pairs.map { RecoveryStrainDetailLogic.comparisonDelta(current:$0.0,mean:$0.1,decimals:$0.2).map { String($0.bitPattern) } ?? "unavailable" }.joined(separator:","))
        let expected = """
        unavailable,unavailable,unavailable,unavailable,unavailable,unavailable,unavailable,unavailable,unavailable,unavailable,unavailable,unavailable,unavailable,unavailable,unavailable,unavailable,unavailable,unavailable,unavailable,unavailable,unavailable,unavailable
        unavailable,unavailable,unavailable,unavailable,0,0,0,0,0,0,0,0,4607182418800017408,13830554455654793216,0,4607182418800017408,13830554455654793216,0,4633078116657397760,4633218854145753088,9094988921128908188,9218868437227405311
        unavailable,unavailable,unavailable,unavailable,0,0,0,0,4591870180066957722,13815242216921733530,4596373779694328218,13819745816549104026,4602678819172646912,13826050856027422720,4602678819172646912,4602678819172646912,13826050856027422720,13826050856027422720,4633148485401575424,4633148485401575424,9094988921128908188,9218868437227405311
        unavailable,unavailable,unavailable,unavailable,unavailable,unavailable,unavailable,unavailable,unavailable,unavailable,unavailable,unavailable,unavailable,unavailable,unavailable,unavailable,unavailable,unavailable,unavailable,unavailable,unavailable,unavailable
        0,0,0,0,4607182418800017408,0,13815242216921733530,13837985395039954534,unavailable,unavailable,unavailable,unavailable,unavailable
        """
        XCTAssertEqual(lines.joined(separator: "\n"), expected)
    }

    func testEqualDisplayedReadingsHaveUnsignedZeroChangeAtTheirOwnPrecision() {
        XCTAssertEqual(RecoveryStrainDetailLogic.comparisonDelta(current: 56, mean: 55.999, decimals: 0)?.bitPattern, 0)
        XCTAssertEqual(RecoveryStrainDetailLogic.comparisonDelta(current: 56, mean: 56.01, decimals: 0)?.bitPattern, 0)
        XCTAssertEqual(RecoveryStrainDetailLogic.comparisonDelta(current: 14.6, mean: 14.61, decimals: 1)?.bitPattern, 0)
    }

    func testStrainWindowIncludesPostMidnightCurrentDataAndAfterMidnightOnsets() {
        XCTAssertEqual(RecoveryStrainDetailLogic.strainWindow(calendarStart: 0, nextCalendarStart: 86_400,
            isCurrentDay: true, sleepOnsetMode: false, onset: nil, nextOnset: nil, now: 97_200), 0...97_200)
        XCTAssertEqual(RecoveryStrainDetailLogic.strainWindow(calendarStart: 0, nextCalendarStart: 86_400,
            isCurrentDay: false, sleepOnsetMode: false, onset: nil, nextOnset: nil, now: 172_800), 0...86_399)
        XCTAssertEqual(RecoveryStrainDetailLogic.strainWindow(calendarStart: 0, nextCalendarStart: 86_400,
            isCurrentDay: true, sleepOnsetMode: true, onset: 72_000, nextOnset: nil, now: 97_200), 72_000...97_200)
        XCTAssertEqual(RecoveryStrainDetailLogic.strainWindow(calendarStart: 0, nextCalendarStart: 86_400,
            isCurrentDay: false, sleepOnsetMode: true, onset: 72_000, nextOnset: 88_200, now: 172_800), 72_000...88_199)
        XCTAssertEqual(RecoveryStrainDetailLogic.strainWindow(calendarStart: 0, nextCalendarStart: 86_400,
            isCurrentDay: true, sleepOnsetMode: false, onset: 72_000, nextOnset: 88_200, now: 97_200), 0...97_200)
        XCTAssertNil(RecoveryStrainDetailLogic.strainWindow(calendarStart: 172_800, nextCalendarStart: 259_200,
            isCurrentDay: false, sleepOnsetMode: false, onset: nil, nextOnset: nil, now: 97_200))
        XCTAssertNil(RecoveryStrainDetailLogic.strainWindow(calendarStart: 0, nextCalendarStart: 86_400,
            isCurrentDay: true, sleepOnsetMode: true, onset: 72_000, nextOnset: 72_000, now: 97_200))
    }

    func testInvalidActivityDurationKeepsRecordedZoneValuesAndProvenanceTogether() {
        let percentages = [10.0, 20, 20, 20, 30]
        let recorded = [1.0, 2, 3, 4, 5]
        for duration in [0.0, -1, .nan, .infinity, -.infinity] {
            let result = RecoveryStrainDetailLogic.zoneDistribution(
                importedPercentages: percentages, durationSeconds: duration, recordedMinutes: recorded)
            XCTAssertEqual(result?.minutes, recorded)
            XCTAssertEqual(result?.imported, false)
            XCTAssertNil(RecoveryStrainDetailLogic.zoneDistribution(
                importedPercentages: percentages, durationSeconds: duration))
        }
        let imported = RecoveryStrainDetailLogic.zoneDistribution(
            importedPercentages: percentages, durationSeconds: 600, recordedMinutes: recorded)
        XCTAssertEqual(imported?.minutes, [1, 2, 2, 2, 3])
        XCTAssertEqual(imported?.imported, true)
        let withoutSplit = RecoveryStrainDetailLogic.zoneDistribution(
            importedPercentages: nil, durationSeconds: 600, recordedMinutes: recorded)
        XCTAssertEqual(withoutSplit?.minutes, recorded)
        XCTAssertEqual(withoutSplit?.imported, false)
    }

    func testWholePercentPresentationKeepsTheStoredRecoveryBand() {
        for score in stride(from: 0.0, through: 100.0, by: 0.001) {
            let shown = RecoveryStrainDetailLogic.recoveryPercent(score)!
            XCTAssertEqual(shown >= 67, score >= 67)
            XCTAssertEqual(shown >= 34, score >= 34)
        }
        XCTAssertEqual(RecoveryStrainDetailLogic.recoveryPercent(33.999), 33)
        XCTAssertEqual(RecoveryStrainDetailLogic.recoveryPercent(66.999), 66)
        XCTAssertEqual(RecoveryStrainDetailLogic.recoveryPercent(34), 34)
        XCTAssertEqual(RecoveryStrainDetailLogic.recoveryPercent(67), 67)
        XCTAssertEqual(RecoveryStrainDetailLogic.recoveryPercent(100), 100)
        let invalidScores: [Double?] = [nil, .nan, .infinity, -1, 101]
        for score in invalidScores {
            XCTAssertNil(RecoveryStrainDetailLogic.recoveryPercent(score))
        }
    }

    func testComparisonExcludesSelectedAndFutureDaysAndMissingValues() {
        let keys = ["2026-08-31", "2026-09-01", "2026-09-15", "2026-09-30", "2026-10-01", "2026-10-02", "2026-10-03"]
        let values: [Double?] = [999, 10, nil, 20, .nan, 100, 1000]
        XCTAssertEqual(RecoveryStrainDetailLogic.priorMean(dayKeys: keys, values: values,
                                                         fromDay: "2026-09-01", selectedDay: "2026-10-02"), 15)
        XCTAssertNil(RecoveryStrainDetailLogic.priorMean(dayKeys: ["2026-10-02"], values: [100],
                                                       fromDay: "2026-09-01", selectedDay: "2026-10-02"))
    }

    func testInclusiveTargetBoundariesAndUnavailableValues() {
        let values: [String?] = [nil, "nan", "0.0", "3.99", "4.0", "10.0", "10.01", "21.0"]
        let expected: [RecoveryStrainDetailLogic.TargetStatus] = [.unavailable, .unavailable, .under, .under, .optimal, .optimal, .over, .over]
        XCTAssertEqual(values.map { RecoveryStrainDetailLogic.targetStatus(displayedStrain: $0, lower: 4, upper: 10) }, expected)
        XCTAssertEqual(RecoveryStrainDetailLogic.targetStatus(displayedStrain: "12.0", lower: nil, upper: nil), .unavailable)
    }

    func testTargetStatusMatchesTheVisibleOneDecimalScore() {
        let axisValues = [3.94, 3.99, 4.0, 10.0, 10.01, 10.06]
        let shown = axisValues.map { UnitFormatter.effortDisplay($0 / UnitFormatter.effortScaleFactor, scale: .whoop) }
        XCTAssertEqual(shown, ["3.9", "4.0", "4.0", "10.0", "10.0", "10.1"])
        XCTAssertEqual(shown.map { RecoveryStrainDetailLogic.targetStatus(displayedStrain: $0, lower: 4, upper: 10) },
                       [.under, .optimal, .optimal, .optimal, .optimal, .over])
    }

    func testPresentationMappingPreservesOriginalEffort() {
        for value in stride(from: 0.0, through: 100.0, by: 0.25) {
            let shown = UnitFormatter.effortValue(value, scale: .whoop)
            XCTAssertEqual(shown / UnitFormatter.effortScaleFactor, value, accuracy: 0.000000001)
        }
    }

    func testStandaloneSwiftOraclePinsCrossPlatformPresentation() {
        var lines: [String] = []
        let keys = ["2026-08-31", "2026-09-01", "2026-09-15", "2026-09-30", "2026-10-01", "2026-10-02", "2026-10-03"]
        let values: [Double?] = [999, 10, nil, 20, .nan, 100, 1000]
        lines.append(String(RecoveryStrainDetailLogic.priorMean(dayKeys: keys, values: values, fromDay: "2026-09-01", selectedDay: "2026-10-02")!))
        let targets: [String?] = [nil, "nan", "0.0", "3.99", "4.0", "10.0", "10.01", "21.0"]
        lines.append(targets.map { RecoveryStrainDetailLogic.targetStatus(displayedStrain: $0, lower: 4, upper: 10).rawValue }.joined(separator: ","))

        let recoveries: [Double?] = [nil, .nan, .infinity, -1, 0, 33.49, 33.999, 34, 66.49, 66.999, 67, 99.999, 100, 101]
        lines.append(recoveries.map { RecoveryStrainDetailLogic.recoveryPercent($0).map(String.init) ?? "unavailable" }.joined(separator: ","))

        let wholeValues: [Double?] = [nil, .nan, .infinity, -.infinity, -1, -0.0, 0, 0.49, 0.5, 1.49, 1.5, 1e300, 9223372036854775808.0, 9223372036854775808.0.nextDown]
        lines.append(wholeValues.map { RecoveryStrainDetailLogic.wholeNumber($0).map(String.init) ?? "unavailable" }.joined(separator: ","))
        let durations = [-1.0, Double.nan, 1e300, 29.9, 30, 59, 60, 89, 90, 119.9, 120, 1482.5, 1499, 1500]
        lines.append(durations.map { RecoveryStrainDetailLogic.durationMinutes(seconds: $0).map(String.init) ?? "unavailable" }.joined(separator: ","))
        let durationFallbacks: [(Double?, Double?)] = [(nil, nil), (nil, 3661), (0, 3661), (59, 3661), (60, 3661), (nil, -1), (nil, .nan), (nil, .infinity), (nil, 1e300), (-1, 3600), (.nan, 3600), (.infinity, 3600), (1e300, 3600)]
        lines.append(durationFallbacks.map { RecoveryStrainDetailLogic.durationMinutes(seconds: $0.0, fallbackSeconds: $0.1).map(String.init) ?? "unavailable" }.joined(separator: ","))
        let expected = """
        15.0
        unavailable,unavailable,under,under,optimal,optimal,over,over
        unavailable,unavailable,unavailable,unavailable,0,33,33,34,66,66,67,99,100,unavailable
        unavailable,unavailable,unavailable,unavailable,unavailable,0,0,0,1,1,2,unavailable,unavailable,9223372036854774784
        unavailable,unavailable,unavailable,0,1,1,1,1,2,2,2,25,25,25
        unavailable,61,0,1,1,unavailable,unavailable,unavailable,unavailable,unavailable,unavailable,unavailable,unavailable
        """
        XCTAssertEqual(lines.joined(separator: "\n"), expected)
    }
}
