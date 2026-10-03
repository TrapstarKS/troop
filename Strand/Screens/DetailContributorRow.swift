import SwiftUI
import StrandDesign

struct DetailContributorRow: View {
    let label: String
    let value: String
    var unit = ""
    var systemImage: String? = nil
    var comparison: String? = nil
    var comparisonSystemImage: String? = nil
    var comparisonColor: Color = StrandPalette.textSecondary
    @Environment(\.dynamicTypeSize) var typeSize

    var body: some View {
        if typeSize > .large {
            VStack(spacing: NoopMetrics.spaceHalf) {
                ContributorRow(label: label, value: "", systemImage: systemImage)
                ContributorRow(label: "", value: value, unit: unit, comparison: comparison,
                               comparisonSystemImage: comparisonSystemImage, comparisonColor: comparisonColor)
            }
            .accessibilityElement(children: .combine)
        } else {
            ContributorRow(label: label, value: value, unit: unit, systemImage: systemImage,
                           comparison: comparison, comparisonSystemImage: comparisonSystemImage,
                           comparisonColor: comparisonColor)
        }
    }
}
