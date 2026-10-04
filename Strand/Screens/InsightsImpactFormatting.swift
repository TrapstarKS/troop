import Foundation

/// Display rounding for observed behavior impacts; does not alter comparison values.
enum InsightsImpactFormatting {
    static func percentage(_ percent: Double) -> String {
        guard percent.isFinite else { return "—" }
        guard percent != 0 else { return "0%" }
        let sign = percent > 0 ? "+" : "−"
        let magnitude = abs(percent)
        return sign + (magnitude < 1 ? "<1" : String(format: "%.0f", magnitude.rounded())) + "%"
    }
}
