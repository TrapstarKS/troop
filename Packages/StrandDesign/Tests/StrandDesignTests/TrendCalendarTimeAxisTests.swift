import XCTest
@testable import StrandDesign

final class TrendCalendarTimeAxisTests: XCTestCase {
    private func day(_ key: String) -> Date {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter.date(from: key)!
    }

    func testSparseSixMonthWindowKeepsUnavailableMonthsOnTheAxis() {
        let points = [TrendPoint(date: day("2026-06-06"), value: 42),
                      TrendPoint(date: day("2026-10-03"), value: 64)]
        let chart = TrendChart(points: points, calendarTimeAxis: day("2026-05-01")...day("2026-10-03"))
        XCTAssertEqual(chart.axisDays, [day("2026-05-01"), day("2026-07-17"), day("2026-10-03")])
        XCTAssertEqual(chart.points.map(\.date), points.map(\.date))
        XCTAssertEqual(chart.points.map(\.value), [42, 64])
    }

    func testSingleDayWindowHasOneLabelAndPreservesItsReading() {
        let date = day("2026-10-05")
        let chart = TrendChart(points: [TrendPoint(date: date, value: 72)], calendarTimeAxis: date...date)
        XCTAssertEqual(chart.axisDays, [date])
        XCTAssertEqual(chart.points.map(\.value), [72])
    }

    func testCalendarLabelsKeepLeapDayAndDeduplicateTwoDayWindow() {
        let chart = TrendChart(points: [], calendarTimeAxis: day("2024-02-28")...day("2024-02-29"))
        XCTAssertEqual(chart.axisDays, [day("2024-02-28"), day("2024-02-29")])
        XCTAssertTrue(chart.points.isEmpty)
    }

    func testExistingChartsStillDeriveTheirAxisFromObservedDates() {
        let dates = [day("2026-06-06"), day("2026-10-03")]
        let chart = TrendChart(points: dates.map { TrendPoint(date: $0, value: 50) })
        XCTAssertNil(chart.calendarTimeAxis)
        XCTAssertEqual(chart.axisDays, ChartAxisDays.spanning(dates))
    }

    func testIsolatedReadingRemainsVisibleInDenseCalendarWindow() {
        let start = day("2026-05-01")
        var points = (0..<61).map { TrendPoint(date: start.addingTimeInterval(Double($0) * 86_400), value: 50, segment: "0") }
        let isolated = TrendPoint(date: start.addingTimeInterval(65 * 86_400), value: 64, segment: "1")
        points.append(isolated)
        let chart = TrendChart(points: points, calendarTimeAxis: start...isolated.date)
        XCTAssertEqual(chart.markerPoints.map(\.date), [isolated.date])
        XCTAssertEqual(chart.markerPoints.map(\.value), [64])
        XCTAssertTrue(TrendChart(points: points).markerPoints.isEmpty)
    }
}
