import Foundation

enum LocalBriefingCopy {
    // Newly authored templates; values are resolved before either screen or notification uses them.
    static func summary(recovery: Int?, sleepMinutes: Int?, strainTenths: Int?, streak: Int) -> String {
        var parts: [String] = []
        if let recovery { parts.append(String(localized: "Recovery \(recovery)%")) }
        if let sleepMinutes { parts.append(String(localized: "Sleep \(sleepMinutes) min")) }
        if let strainTenths {
            let value = "\(strainTenths / 10).\(strainTenths % 10)"
            parts.append(String(localized: "Strain \(value) / 21"))
        }
        if streak > 0 { parts.append(String(localized: "\(streak)-day recorded recovery streak")) }
        return parts.isEmpty
            ? String(localized: "No scored metrics are available yet. Sync your strap to build a local summary.")
            : parts.joined(separator: " · ")
    }
}
