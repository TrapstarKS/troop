import Foundation
import UserNotifications
import StrandAnalytics

@MainActor
enum WindDownNudge {

    private static let requestId = "wind-down-nudge"
    private static var enableRevision = UUID()


    private enum K {
        static let enabled = "windDown.enabled"
        static let sleepNeed = "windDown.sleepNeedMinutes"
        static let lead = "windDown.leadMinutes"
        static let wake = "windDown.wakeMinutes"
        static let perDayWake = "windDown.perDayWakeMinutes"
    }

    static var isEnabled: Bool { UserDefaults.standard.bool(forKey: K.enabled) }

    static var sleepNeedMinutes: Int {
        let v = UserDefaults.standard.object(forKey: K.sleepNeed) as? Int ?? 8 * 60
        return min(max(v, 5 * 60), 11 * 60)
    }

    static var leadMinutes: Int {
        let v = UserDefaults.standard.object(forKey: K.lead) as? Int ?? 30
        return min(max(v, 0), 120)
    }

    static var wakeMinutes: Int {
        let v = UserDefaults.standard.object(forKey: K.wake) as? Int ?? 7 * 60
        return min(max(v, 0), 24 * 60 - 1)
    }


    static var perDayWakeOverrides: [Int: Int] {
        guard let data = UserDefaults.standard.data(forKey: K.perDayWake),
              let raw = try? JSONDecoder().decode([String: Int].self, from: data) else { return [:] }
        var out: [Int: Int] = [:]
        for (k, v) in raw {
            guard let day = Int(k), (1...7).contains(day) else { continue }
            out[day] = min(max(v, 0), 24 * 60 - 1)
        }
        return out
    }

    static func wakeMinutes(forWeekday weekday: Int) -> Int {
        perDayWakeOverrides[weekday] ?? wakeMinutes
    }

    static var hasPerDayOverrides: Bool { !perDayWakeOverrides.isEmpty }

    static func setWakeOverride(weekday: Int, minutes: Int?) {
        guard (1...7).contains(weekday) else { return }
        var map = perDayWakeOverrides
        if let m = minutes {
            map[weekday] = min(max(m, 0), 24 * 60 - 1)
        } else {
            map.removeValue(forKey: weekday)
        }
        let encodable = Dictionary(uniqueKeysWithValues: map.map { (String($0.key), $0.value) })
        if let data = try? JSONEncoder().encode(encodable) {
            UserDefaults.standard.set(data, forKey: K.perDayWake)
        }
        reschedule()
    }

    static func nudgeMinuteOfDay(forWeekday weekday: Int) -> Int {
        let raw = wakeMinutes(forWeekday: weekday) - sleepNeedMinutes - leadMinutes
        let day = 24 * 60
        return ((raw % day) + day) % day
    }

    static func nudgeDayShift(forWeekday weekday: Int) -> Int {
        let raw = wakeMinutes(forWeekday: weekday) - sleepNeedMinutes - leadMinutes
        let day = 24 * 60
        return raw < 0 ? -(((-raw - 1) / day) + 1) : raw / day
    }

    static func shiftedWeekday(weekday: Int, by shift: Int) -> Int {
        let zeroBased = weekday - 1 + shift
        return ((zeroBased % 7) + 7) % 7 + 1
    }


    enum EnableOutcome { case scheduled, denied, off }

    static func setEnabled(_ on: Bool, completion: (@MainActor (EnableOutcome) -> Void)? = nil) {
        enableRevision = UUID()
        let revision = enableRevision
        guard on else {
            UserDefaults.standard.set(false, forKey: K.enabled)
            reschedule()
            completion?(.off)
            return
        }
        authorize { allowed in
            guard revision == enableRevision else { return }
            UserDefaults.standard.set(allowed, forKey: K.enabled)
            reschedule()
            completion?(allowed ? .scheduled : .denied)
        }
    }

    static func authorizeDebtReminder(completion: @escaping @MainActor (Bool) -> Void) {
        authorize { allowed in
            if !allowed { SleepPlannerSettings.shared.debtReminderEnabled = false }
            reschedule()
            completion(allowed)
        }
    }

    private static func authorize(completion: @escaping @MainActor (Bool) -> Void) {
        let center = UNUserNotificationCenter.current()
        center.getNotificationSettings { settings in
            Task { @MainActor in
                switch settings.authorizationStatus {
                case .authorized, .provisional, .ephemeral:
                    completion(true)
                case .notDetermined:
                    center.requestAuthorization(options: [.alert, .sound]) { allowed, _ in
                        Task { @MainActor in completion(allowed) }
                    }
                default:
                    completion(false)
                }
            }
        }
    }

    static func setWakeMinutes(_ minutes: Int) {
        UserDefaults.standard.set(min(max(minutes, 0), 24 * 60 - 1), forKey: K.wake)
        reschedule()
    }

    static func nudgeMinuteOfDay() -> Int {
        let raw = wakeMinutes - sleepNeedMinutes - leadMinutes
        let day = 24 * 60
        return ((raw % day) + day) % day
    }


    private static var perDayRequestIds: [String] { (1...7).map { "\(requestId)-wd\($0)" } }

    static func replaceWakeSchedule(minutes: Int, overrides: [Int: Int]) {
        UserDefaults.standard.set(min(max(minutes, 0), 1439), forKey: K.wake)
        let clean = overrides.filter { (1...7).contains($0.key) && (0..<1440).contains($0.value) }
        let encoded = Dictionary(uniqueKeysWithValues: clean.map { (String($0.key), $0.value) })
        if let data = try? JSONEncoder().encode(encoded) {
            UserDefaults.standard.set(data, forKey: K.perDayWake)
        }
        reschedule()
    }

    static func reschedule() {
        schedule()
    }

    private static func schedule() {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: [requestId] + perDayRequestIds)
        let planner = SleepPlannerSettings.shared
        guard isEnabled || planner.debtReminderEnabled else { return }
        let defaults = UserDefaults.standard
        let defaultWake = defaults.object(forKey: "behavior.smartAlarmMinutes") as? Int ?? wakeMinutes
        let selectedDays = Set(defaults.array(forKey: "behavior.smartAlarmWeekdays") as? [Int] ?? [])
        let alarmOn = defaults.bool(forKey: "behavior.smartAlarmEnabled")
        let now = Date()
        let calendar = Calendar.current
        var cursor = now
        var count = 0
        for _ in 0..<28 {
            guard let wake = AppModel.nextSmartAlarmDate(minutes: defaultWake,
                                                        weekdays: alarmOn ? selectedDays : [],
                                                        overrides: perDayWakeOverrides,
                                                        skippedOccurrence: planner.skippedOccurrence,
                                                        from: cursor, calendar: calendar) else { break }
            cursor = wake
            let weekday = calendar.component(.weekday, from: wake)
            let minute = calendar.component(.hour, from: wake) * 60 + calendar.component(.minute, from: wake)
            let plan = planner.plan(weekday: weekday, wakeMinutes: minute, leadMinutes: leadMinutes)
            let debtAdvice = planner.debtReminderEnabled && plan.debtNudge
            let bedtime = SleepPlanner.bedtime(wake: wake, targetSleepMinutes: plan.targetSleepMinutes)
            let reminder = bedtime.addingTimeInterval(-Double(leadMinutes * 60))
            let reminderMinute = calendar.component(.hour, from: reminder) * 60 + calendar.component(.minute, from: reminder)
            guard isEnabled || debtAdvice,
                  !PlannerAlarmPolicy.isQuietMinute(minute: reminderMinute,
                                                    enabled: defaults.bool(forKey: "notif.quietHoursEnabled"),
                                                    startMinutes: defaults.object(forKey: "notif.quietStartMinutes") as? Int ?? 1320,
                                                    endMinutes: defaults.object(forKey: "notif.quietEndMinutes") as? Int ?? 420),
                  reminder > now else { continue }
            let content = UNMutableNotificationContent()
            content.title = String(localized: "Time to wind down")
            let bedtimeLabel = formattedTime(bedtime)
            content.body = debtAdvice
                ? String(localized: "Make room for sleep tonight. Your local plan suggests bed at \(bedtimeLabel), with recent sleep debt included in your need.")
                : String(localized: "Your sleep plan suggests bed at \(bedtimeLabel). Take a little time to wind down.")
            content.sound = .default
            let parts = calendar.dateComponents([.year, .month, .day, .hour, .minute], from: reminder)
            let trigger = UNCalendarNotificationTrigger(dateMatching: parts, repeats: false)
            center.add(UNNotificationRequest(identifier: "\(requestId)-wd\(count + 1)", content: content, trigger: trigger))
            count += 1
            if count == 7 { break }
        }
    }

    private static func formattedTime(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = AppLanguage.activeLocale
        formatter.setLocalizedDateFormatFromTemplate("j:mm")
        return formatter.string(from: date)
    }
}
