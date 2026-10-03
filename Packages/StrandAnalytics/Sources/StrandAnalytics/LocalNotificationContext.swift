public struct LocalRecordedReport: Equatable, Sendable {
    public let day: String
    public let recovery: Int?
    public let sleepMinutes: Int?
    public let strainTenths: Int?
    public let streak: Int
    public let sleepNeedMinutes: Int?
    public let sleepDebtMinutes: Int?

    public init(day: String, recovery: Int?, sleepMinutes: Int?, strainTenths: Int?, streak: Int,
                sleepNeedMinutes: Int? = nil, sleepDebtMinutes: Int? = nil) {
        self.day = day
        self.recovery = recovery
        self.sleepMinutes = sleepMinutes
        self.strainTenths = strainTenths
        self.streak = streak
        self.sleepNeedMinutes = sleepNeedMinutes
        self.sleepDebtMinutes = sleepDebtMinutes
    }

    /// A retained notification resolves its captured report, including missing readings.
    public static func forDisplay(context: LocalNotificationContext?, current: Self?) -> Self? {
        if let context { return context.report }
        return current
    }
}

public struct LocalNotificationContext: Equatable, Sendable {
    public let route: String
    public let eventID: String
    public let family: String?
    public let day: String?
    public let weekKey: String?
    public let workoutStartSec: Int?
    public let message: String?
    public let report: LocalRecordedReport?

    public init(route: String, eventID: String, family: String? = nil, day: String? = nil,
                weekKey: String? = nil, workoutStartSec: Int? = nil, message: String? = nil,
                report: LocalRecordedReport? = nil) {
        self.route = route
        self.eventID = eventID
        self.family = family
        self.day = day ?? report?.day
        self.weekKey = weekKey
        self.workoutStartSec = workoutStartSec
        self.message = message
        self.report = report
    }

    public var identity: String { "\(route):\(eventID)" }

    public var wireFields: [String: String] {
        var fields = ["localNotificationRoute": route, "localNotificationEvent": eventID]
        fields["localNotificationFamily"] = family
        fields["localNotificationDay"] = day
        fields["localNotificationWeek"] = weekKey
        fields["localNotificationWorkoutStart"] = workoutStartSec.map(String.init)
        fields["localNotificationMessage"] = message
        if let report {
            fields["localNotificationReport"] = "1"
            fields["localNotificationDay"] = report.day
            fields["localNotificationRecovery"] = report.recovery.map(String.init)
            fields["localNotificationSleep"] = report.sleepMinutes.map(String.init)
            fields["localNotificationStrain"] = report.strainTenths.map(String.init)
            fields["localNotificationStreak"] = String(report.streak)
            fields["localNotificationSleepNeed"] = report.sleepNeedMinutes.map(String.init)
            fields["localNotificationSleepDebt"] = report.sleepDebtMinutes.map(String.init)
        }
        return fields
    }

    public init?(wireFields fields: [String: String]) {
        guard let route = fields["localNotificationRoute"], !route.isEmpty else { return nil }
        let day = fields["localNotificationDay"]
        let report: LocalRecordedReport?
        if fields["localNotificationReport"] == "1", let day,
           let streak = fields["localNotificationStreak"].flatMap(Int.init) {
            report = LocalRecordedReport(day: day,
                recovery: fields["localNotificationRecovery"].flatMap(Int.init),
                sleepMinutes: fields["localNotificationSleep"].flatMap(Int.init),
                strainTenths: fields["localNotificationStrain"].flatMap(Int.init), streak: streak,
                sleepNeedMinutes: fields["localNotificationSleepNeed"].flatMap(Int.init),
                sleepDebtMinutes: fields["localNotificationSleepDebt"].flatMap(Int.init))
        } else { report = nil }
        self.init(route: route, eventID: fields["localNotificationEvent"] ?? route,
                  family: fields["localNotificationFamily"], day: day,
                  weekKey: fields["localNotificationWeek"],
                  workoutStartSec: fields["localNotificationWorkoutStart"].flatMap(Int.init),
                  message: fields["localNotificationMessage"], report: report)
    }
}

/// Holds a cold-start tap until the shell can accept the complete dated payload.
public final class LocalNotificationTapBuffer {
    public var handler: ((LocalNotificationContext) -> Void)? {
        didSet {
            guard let handler, let pending else { return }
            self.pending = nil
            handler(pending)
        }
    }
    private var pending: LocalNotificationContext?
    public init() {}

    public func receive(_ context: LocalNotificationContext) {
        if let handler { handler(context) } else { pending = context }
    }
}
