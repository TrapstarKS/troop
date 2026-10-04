import Foundation

struct WeeklyPlanDayAnchor: Equatable {
    let today: String
    let weekOffset: Int

    func advanced(to day: String) -> WeeklyPlanDayAnchor {
        guard day != today, let oldWeek = WeeklyPlanCalendar.weekStart(today),
              let nextWeek = WeeklyPlanCalendar.weekStart(day) else { return self }
        var offset = weekOffset
        if weekOffset < 0, let selected = WeeklyPlanCalendar.adding(days: weekOffset * 7, to: oldWeek),
           let selectedDate = WeeklyPlanCalendar.date(selected), let nextDate = WeeklyPlanCalendar.date(nextWeek) {
            offset = min(0, Int(selectedDate.timeIntervalSince(nextDate) / 604_800))
        }
        return WeeklyPlanDayAnchor(today: day, weekOffset: offset)
    }
}
