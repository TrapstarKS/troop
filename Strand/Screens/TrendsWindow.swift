import Foundation

struct TrendsWindow: Equatable {
    let start: String
    let end: String

    func contains(_ day: String) -> Bool {
        day >= start && day <= end
    }

    static func period(days: Int, offset: Int, today: String) -> TrendsWindow? {
        guard let date = parse(today) else { return nil }
        let calendar = Self.calendar
        let shift = min(offset, 0)
        let start: Date
        let end: Date
        if days == 7 {
            let weekday = calendar.component(.weekday, from: date)
            let monday = calendar.date(byAdding: .day, value: -((weekday + 5) % 7), to: date)!
            start = calendar.date(byAdding: .day, value: shift * 7, to: monday)!
            end = calendar.date(byAdding: .day, value: 6, to: start)!
        } else {
            let months = days == 180 ? 6 : 1
            let month = calendar.date(from: calendar.dateComponents([.year, .month], from: date))!
            start = calendar.date(byAdding: .month, value: shift * months - (months - 1), to: month)!
            let next = calendar.date(byAdding: .month, value: months, to: start)!
            end = calendar.date(byAdding: .day, value: -1, to: next)!
        }
        return TrendsWindow(start: key(start), end: key(min(end, date)))
    }

    static func minimumOffset(days: Int, earliest: String?, today: String) -> Int {
        guard let earliest, parse(earliest) != nil else { return 0 }
        var offset = 0
        while offset > -520, let window = period(days: days, offset: offset, today: today), window.start > earliest {
            offset -= 1
        }
        return offset
    }

    static func parse(_ day: String) -> Date? {
        let formatter = Self.formatter
        guard let date = formatter.date(from: day), formatter.string(from: date) == day else { return nil }
        return date
    }

    private static var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        return calendar
    }

    private static var formatter: DateFormatter {
        let formatter = DateFormatter()
        formatter.calendar = calendar
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = calendar.timeZone
        formatter.dateFormat = "yyyy-MM-dd"
        formatter.isLenient = false
        return formatter
    }

    private static func key(_ date: Date) -> String { formatter.string(from: date) }
}
