import SwiftUI
import StrandDesign
import WhoopStore

struct DetailComparisonRow: View {
    let label: String
    let value: Double?
    let baseline: Double?
    let unit: String
    let systemImage: String
    var decimals = 1
    var higherIsBetter: Bool? = nil

    private var delta: Double? {
        RecoveryStrainDetailLogic.comparisonDelta(current: value, mean: baseline, decimals: decimals)
    }
    private var comparisonColor: Color {
        guard let delta, delta != 0, let higherIsBetter else { return StrandPalette.textSecondary }
        return (delta > 0) == higherIsBetter ? StrandPalette.positive : StrandPalette.statusWarning
    }
    private var direction: String {
        guard let delta, delta != 0 else { return "circle.fill" }
        return delta > 0 ? "arrowtriangle.up.fill" : "arrowtriangle.down.fill"
    }
    private var comparison: String {
        let mean = baseline.map { String(localized: "30-day average: \(detailComparisonNumber($0, decimals: decimals)) \(unit)") }
            ?? String(localized: "Baseline unavailable")
        guard let delta else { return mean }
        let change = String(format: delta == 0 ? "%.\(decimals)f" : "%+.\(decimals)f", locale: AppLanguage.activeLocale, delta)
        return String(localized: "\(change) \(unit) · \(mean)")
    }

    var body: some View {
        DetailContributorRow(label: label, value: detailComparisonNumber(value, decimals: decimals), unit: unit,
                       systemImage: systemImage,
                       comparison: baseline.map { detailComparisonNumber($0, decimals: decimals) + " " + unit },
                       comparisonSystemImage: direction, comparisonColor: comparisonColor)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text(label))
            .accessibilityValue(Text([detailComparisonNumber(value, decimals: decimals) + " " + unit, comparison].joined(separator: ", ")))
    }
}

struct DetailComparisonLegend: View {
    var body: some View {
        HStack(spacing: NoopMetrics.space2) {
            Image(systemName: "arrowtriangle.up.fill").foregroundStyle(StrandPalette.positive)
            Image(systemName: "arrowtriangle.down.fill").foregroundStyle(StrandPalette.statusWarning)
            Text("Compared with the prior 30 days")
        }
        .font(StrandFont.caption)
        .foregroundStyle(StrandPalette.textSecondary)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(NoopMetrics.space2)
        .background(StrandPalette.canvasBottom)
        .accessibilityElement(children: .combine)
    }
}

struct DetailStrainContributors: View {
    let minutes: [Double]?
    let row: DailyMetric?
    let history: [DailyMetric]
    let dayKey: String
    var strengthSeconds: Double? = nil
    var steps: Double? = nil

    private func duration(_ indices: Range<Int>) -> String {
        guard let minutes, minutes.count == 5,
              let count = RecoveryStrainDetailLogic.durationMinutes(seconds: indices.map { minutes[$0] }.reduce(0, +) * 60) else { return "—" }
        return count >= 60 ? String(localized: "\(count / 60)h \(count % 60)m") : String(localized: "\(count)m")
    }

    var body: some View {
        NoopCard {
            VStack(spacing: NoopMetrics.spaceHalf) {
                DetailContributorRow(label: String(localized: "Time in Zones 1–3"), value: duration(0..<3), systemImage: "heart")
                Divider().overlay(StrandPalette.hairline)
                DetailContributorRow(label: String(localized: "Time in Zones 4–5"), value: duration(3..<5), systemImage: "heart.fill")
                Divider().overlay(StrandPalette.hairline)
                DetailContributorRow(label: String(localized: "Strength duration"), value: RecoveryStrainDetailLogic.durationMinutes(seconds: strengthSeconds).map { $0 >= 60 ? String(localized: "\($0 / 60)h \($0 % 60)m") : String(localized: "\($0)m") } ?? "—", systemImage: "dumbbell")
                Divider().overlay(StrandPalette.hairline)
                let baseline = RecoveryStrainDetailLogic.priorMean(dayKeys: history.map(\.day),
                    values: history.map { $0.steps.map(Double.init) },
                    fromDay: RecoveryStrainDetailLogic.startKey(selectedDay: dayKey, days: 30), selectedDay: dayKey)
                DetailComparisonRow(label: String(localized: "Steps"), value: steps,
                    baseline: baseline, unit: "", systemImage: "figure.walk", decimals: 0)
                DetailComparisonLegend()
            }
        }
    }
}

private func detailComparisonNumber(_ value: Double?, decimals: Int) -> String {
    RecoveryStrainDetailLogic.comparisonValue(value, decimals: decimals)
        .map { String(format: "%.\(decimals)f", locale: AppLanguage.activeLocale, $0) } ?? "—"
}
