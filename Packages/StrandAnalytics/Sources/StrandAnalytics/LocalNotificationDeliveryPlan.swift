public enum LocalNotificationReportGroup: CaseIterable, Sendable {
    case night, evening

    public var families: [String] {
        switch self {
        case .night: return ["dailyOutlook", "morningRecap", "sleepReady", "recoveryReady"]
        case .evening: return ["dayInReview", "strainReady", "streakSummary"]
        }
    }
}

public struct LocalNotificationDeliveryPlan: Equatable, Sendable {
    public let primaryFamily: String
    public let coveredFamilies: [String]

    /// Delivered families must belong to this recorded event. Persist coverage only after delivery succeeds.
    public static func resolve(group: LocalNotificationReportGroup, availableFamilies: Set<String>,
                               enabledFamilies: Set<String>, deliveredFamilies: Set<String>) -> Self? {
        let families = group.families
        guard !families.contains(where: deliveredFamilies.contains),
              let primary = families.first(where: { availableFamilies.contains($0) && enabledFamilies.contains($0) })
        else { return nil }
        return Self(primaryFamily: primary, coveredFamilies: families)
    }

    public static func deliver(group: LocalNotificationReportGroup, availableFamilies: Set<String>,
                               enabledFamilies: Set<String>, deliveredFamilies: Set<String>,
                               authorized: Bool, quiet: Bool, occurrenceSec: Int?, nowSec: Int,
                               submit: (Self) async -> Bool) async -> Self? {
        guard let plan = resolve(group: group, availableFamilies: availableFamilies,
                enabledFamilies: enabledFamilies, deliveredFamilies: deliveredFamilies),
              LocalNotificationPolicy.shouldDeliver(enabled: true, authorized: authorized, quiet: quiet,
                eventKey: plan.primaryFamily, lastEventKey: nil, occurrenceSec: occurrenceSec, nowSec: nowSec),
              await submit(plan) else { return nil }
        return plan
    }
}
