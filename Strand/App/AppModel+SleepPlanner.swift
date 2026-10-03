import Foundation
import StrandAnalytics
import WhoopProtocol

extension AppModel {
    func refreshSleepPlannerInputs(revision: Int? = nil) {
        let expectedRevision = revision ?? repo.refreshSeq
        let expectedDevice = repo.deviceId
        Task { [weak self] in
            guard let self else { return }
            let habitualMidsleep = await self.repo.habitualMidsleepSec()
            guard self.repo.refreshSeq == expectedRevision, self.repo.deviceId == expectedDevice else { return }
            SleepPlannerSettings.shared.updateInputs(days: self.repo.days, sleeps: self.repo.sleeps,
                                                      habitualMidsleepSec: habitualMidsleep)
        }
    }

    var activeDeviceSupportsStrapAlarm: Bool {
        guard let registry = deviceRegistry,
              let device = registry.devices.first(where: { $0.id == registry.activeDeviceId }) else { return false }
        return DeviceFamily.forRegistryDevice(model: device.model, brand: device.brand) != nil
    }

    func sleepPlannerSnapshot(from now: Date, calendar: Calendar = .current) -> SleepPlannerSnapshot? {
        let settings = SleepPlannerSettings.shared
        guard let wake = Self.nextSmartAlarmDate(minutes: behavior.smartAlarmMinutes,
                                                weekdays: behavior.smartAlarmEnabled ? behavior.smartAlarmWeekdays : [],
                                                overrides: WindDownNudge.perDayWakeOverrides,
                                                skippedOccurrence: behavior.smartAlarmEnabled ? settings.skippedOccurrence : "",
                                                from: now, calendar: calendar) else { return nil }
        let day = calendar.component(.weekday, from: wake)
        let minutes = calendar.component(.hour, from: wake) * 60 + calendar.component(.minute, from: wake)
        let plan = settings.plan(weekday: day, wakeMinutes: minutes, leadMinutes: WindDownNudge.leadMinutes)
        let bedtime = SleepPlanner.bedtime(wake: wake, targetSleepMinutes: plan.targetSleepMinutes)
        let reminder = bedtime.addingTimeInterval(-Double(min(max(WindDownNudge.leadMinutes, 0), 120) * 60))
        let defaults = UserDefaults.standard
        let epoch = Int(wake.timeIntervalSince1970)
        let sent = behavior.smartAlarmEnabled
            && activeDeviceSupportsStrapAlarm
            && defaults.integer(forKey: "alarm.lastArmSentEpoch") == epoch
            && defaults.string(forKey: "alarm.lastArmDeviceId") == repo.deviceId
            && defaults.bool(forKey: "alarm.lastArmConnected")
        let confirmed = sent
            && defaults.integer(forKey: "alarm.lastReportedEpoch") == epoch
            && defaults.string(forKey: "alarm.lastReportedDeviceId") == repo.deviceId
            && defaults.double(forKey: "alarm.lastReportedAt") >= defaults.double(forKey: "alarm.lastArmAt")
            && defaults.integer(forKey: "alarm.rejectStreak") == 0
        let earlyWake = confirmed && wake.timeIntervalSince(now) <= 60 * 60
        return SleepPlannerSnapshot(wake: wake, bedtime: bedtime, reminder: reminder, plan: plan,
                                    alarmConfirmed: confirmed, alarmSent: sent, earlyWake: earlyWake)
    }

    func saveSleepPlannerAlarm(enabled: Bool, minutes: Int, weekdays: Set<Int>, overrides: [Int: Int], mode: String) -> Bool {
        guard activeDeviceSupportsStrapAlarm, live.connected, live.encryptedBond, ble.commandChannelReady,
              !(enabled && whoop5Detected && !PuffinExperiment.isEnabled) else { return false }
        let settings = SleepPlannerSettings.shared
        settings.skippedOccurrence = ""
        settings.alarmMode = mode
        behavior.smartAlarmMinutes = min(max(minutes, 0), 1439)
        behavior.smartAlarmWeekdays = Set(weekdays.filter { (1...7).contains($0) })
        behavior.smartAlarmEnabled = enabled
        WindDownNudge.replaceWakeSchedule(minutes: behavior.smartAlarmMinutes, overrides: overrides)
        applySmartAlarm()
        return true
    }

    func skipNextSleepPlannerAlarm(from now: Date) -> Bool {
        let settings = SleepPlannerSettings.shared
        guard !PlannerAlarmPolicy.isSkipPending(skippedOccurrence: settings.skippedOccurrence,
                                                from: now, calendar: .current) else { return false }
        guard behavior.smartAlarmEnabled, activeDeviceSupportsStrapAlarm, live.connected, live.encryptedBond, ble.commandChannelReady,
              !(whoop5Detected && !PuffinExperiment.isEnabled),
              let snapshot = sleepPlannerSnapshot(from: now) else { return false }
        settings.skippedOccurrence = Self.smartAlarmOccurrenceKey(snapshot.wake)
        applySmartAlarm(from: now)
        return true
    }

    nonisolated static func sleepPlannerNotificationComponents(_ date: Date) -> DateComponents {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        var parts = calendar.dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
        parts.calendar = calendar
        parts.timeZone = calendar.timeZone
        return parts
    }

    nonisolated static func smartAlarmOccurrenceKey(_ date: Date, calendar: Calendar = .current) -> String {
        PlannerAlarmPolicy.occurrenceKey(for: date, calendar: calendar)
    }
}
