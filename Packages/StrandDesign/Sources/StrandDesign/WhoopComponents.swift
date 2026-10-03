import SwiftUI

public enum ScoreDialSize: Sendable {
    case full
    case compact

    var diameter: CGFloat {
        self == .full ? NoopMetrics.scoreDialDiameter : NoopMetrics.compactScoreDialDiameter
    }

    var stroke: CGFloat {
        self == .full ? NoopMetrics.scoreDialStroke : NoopMetrics.compactScoreDialStroke
    }

    var fontSize: CGFloat {
        self == .full ? NoopMetrics.scoreDisplaySize : NoopMetrics.compactScoreDisplaySize
    }
}

/// The host supplies formatted text and normalized progress from one data snapshot.
/// A nil progress draws only the neutral track; the host supplies its unavailable-state label.
public struct ScoreDial: View {
    public var label: String
    public var value: String
    public var unit: String
    public var progress: Double?
    public var color: Color
    public var size: ScoreDialSize
    public var target: Double?
    public var targetRange: ClosedRange<Double>?
    public var accessibilityLabel: String?
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    public init(label: String, value: String, unit: String = "", progress: Double?, color: Color,
                size: ScoreDialSize = .full, target: Double? = nil,
                targetRange: ClosedRange<Double>? = nil, accessibilityLabel: String? = nil) {
        self.label = label
        self.value = value
        self.unit = unit
        self.progress = progress
        self.color = color
        self.size = size
        self.target = target
        self.targetRange = targetRange
        self.accessibilityLabel = accessibilityLabel
    }

    static func bounded(_ value: Double?) -> Double? {
        guard let value, value.isFinite else { return nil }
        return min(max(value, 0), 1)
    }

    public var body: some View {
        VStack(spacing: NoopMetrics.space2) {
            ZStack {
                Circle().stroke(StrandPalette.ringTrack, lineWidth: size.stroke)
                if let targetRange,
                   let lower = Self.bounded(targetRange.lowerBound),
                   let upper = Self.bounded(targetRange.upperBound) {
                    Circle().trim(from: lower, to: upper)
                        .stroke(StrandPalette.targetBand, lineWidth: size.stroke)
                        .rotationEffect(.degrees(-90))
                }
                if let fraction = Self.bounded(progress), fraction > 0 {
                    Circle().trim(from: 0, to: fraction)
                        .stroke(color, style: StrokeStyle(lineWidth: size.stroke, lineCap: fraction == 1 ? .butt : .round))
                        .rotationEffect(.degrees(-90))
                }
                if let target = Self.bounded(target) {
                    Rectangle().fill(StrandPalette.textPrimary)
                        .frame(width: NoopMetrics.scoreTargetWidth, height: size.stroke + NoopMetrics.space1)
                        .offset(y: -(size.diameter - size.stroke) / 2)
                        .rotationEffect(.degrees(target * 360))
                }
                VStack(spacing: NoopMetrics.space2) {
                    HStack(alignment: .firstTextBaseline, spacing: NoopMetrics.spaceHalf) {
                        Text(value).font(StrandFont.display(size.fontSize))
                        if !unit.isEmpty {
                            Text(unit).font(StrandFont.number(size.fontSize * 0.5, weight: .bold))
                        }
                    }
                    .foregroundStyle(StrandPalette.textPrimary)
                    if size == .full && !dynamicTypeSize.isAccessibilitySize {
                        Text(label).strandOverline().multilineTextAlignment(.center)
                    }
                }
                .padding(size.stroke + NoopMetrics.space2)
            }
            .frame(width: size.diameter - size.stroke, height: size.diameter - size.stroke)
            .padding(size.stroke / 2)
            if size == .compact || dynamicTypeSize.isAccessibilitySize {
                Text(label).strandOverline().multilineTextAlignment(.center)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: accessibilityLabel ?? [label, value + unit].joined(separator: ", ")))
    }
}

public struct MetricCard: View {
    public var label: String
    public var value: String
    public var unit: String
    public var detail: String?
    public var systemImage: String?
    public var color: Color?

    public init(label: String, value: String, unit: String = "", detail: String? = nil,
                systemImage: String? = nil, color: Color? = nil) {
        self.label = label
        self.value = value
        self.unit = unit
        self.detail = detail
        self.systemImage = systemImage
        self.color = color
    }

    public var body: some View {
        StrandCard {
            VStack(alignment: .leading, spacing: NoopMetrics.space3) {
                HStack(spacing: NoopMetrics.space2) {
                    if let systemImage { Image(systemName: systemImage).accessibilityHidden(true) }
                    Text(label).strandOverline()
                }
                .foregroundStyle(StrandPalette.textSecondary)
                HStack(alignment: .firstTextBaseline, spacing: NoopMetrics.space1) {
                    Text(value).font(StrandFont.number(NoopMetrics.metricValueSize, weight: .bold))
                        .foregroundStyle(color ?? StrandPalette.textPrimary)
                    if !unit.isEmpty { Text(unit).font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary) }
                }
                if let detail { Text(detail).font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary) }
            }
        }
    }
}

public struct TrackedSectionHeader: View {
    public var title: String
    public var microLabel: String?
    public var actionLabel: String?
    public var onAction: (() -> Void)?

    public init(title: String, microLabel: String? = nil, actionLabel: String? = nil,
                onAction: (() -> Void)? = nil) {
        self.title = title
        self.microLabel = microLabel
        self.actionLabel = actionLabel
        self.onAction = onAction
    }

    public var body: some View {
        HStack(alignment: .lastTextBaseline, spacing: NoopMetrics.space3) {
            VStack(alignment: .leading, spacing: NoopMetrics.space1) {
                if let microLabel { Text(microLabel).strandOverline() }
                Text(title).font(StrandFont.title2).foregroundStyle(StrandPalette.textPrimary)
                    .accessibilityAddTraits(.isHeader)
            }
            Spacer(minLength: NoopMetrics.space2)
            if let actionLabel, let onAction {
                Button(action: onAction) { Text(actionLabel).strandOverline() }
                    .buttonStyle(.plain)
                    .frame(minHeight: NoopMetrics.touchTarget)
            }
        }
    }
}

public struct ContributorRow: View {
    public var label: String
    public var value: String
    public var unit: String
    public var systemImage: String?
    public var comparison: String?
    public var comparisonSystemImage: String?
    public var comparisonColor: Color?

    public init(label: String, value: String, unit: String = "", systemImage: String? = nil,
                comparison: String? = nil, comparisonSystemImage: String? = nil, comparisonColor: Color? = nil) {
        self.label = label
        self.value = value
        self.unit = unit
        self.systemImage = systemImage
        self.comparison = comparison
        self.comparisonSystemImage = comparisonSystemImage
        self.comparisonColor = comparisonColor
    }

    public var body: some View {
        HStack(spacing: NoopMetrics.space3) {
            if let systemImage {
                Image(systemName: systemImage).font(.system(size: NoopMetrics.iconSize))
                    .foregroundStyle(StrandPalette.textTertiary)
                    .frame(width: NoopMetrics.iconSize).accessibilityHidden(true)
            }
            Text(label).strandOverline().fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: NoopMetrics.space2)
            VStack(alignment: .trailing, spacing: NoopMetrics.space1) {
                HStack(alignment: .firstTextBaseline, spacing: NoopMetrics.space1) {
                    Text(value).font(StrandFont.number(NoopMetrics.metricValueSize, weight: .bold))
                    if !unit.isEmpty { Text(unit).font(StrandFont.caption) }
                    if let comparisonSystemImage {
                        Image(systemName: comparisonSystemImage).font(StrandFont.caption)
                            .foregroundStyle(comparisonColor ?? StrandPalette.textTertiary)
                            .accessibilityHidden(true)
                    }
                }
                .foregroundStyle(StrandPalette.textPrimary)
                if let comparison {
                    Text(comparison).font(StrandFont.caption)
                        .foregroundStyle(comparisonColor ?? StrandPalette.textSecondary)
                }
            }
        }
        .padding(.vertical, NoopMetrics.space3)
        .accessibilityElement(children: .combine)
    }
}

public struct StatusPill: View {
    public var label: String
    public var systemImage: String?
    public var color: Color?

    public init(label: String, systemImage: String? = nil, color: Color? = nil) {
        self.label = label
        self.systemImage = systemImage
        self.color = color
    }

    public var body: some View {
        HStack(spacing: NoopMetrics.space1) {
            if let systemImage { Image(systemName: systemImage).accessibilityHidden(true) }
            Text(label)
        }
        .font(StrandFont.caption.weight(.semibold))
        .foregroundStyle(color ?? StrandPalette.textSecondary)
        .padding(.horizontal, NoopMetrics.space2).padding(.vertical, NoopMetrics.space1)
        .background(Capsule().fill((color ?? StrandPalette.textSecondary).opacity(0.12)))
        .accessibilityElement(children: .combine)
    }
}

public struct InsightCallout: View {
    public var text: String
    public var actionLabel: String?
    public var onAction: (() -> Void)?

    public init(text: String, actionLabel: String? = nil, onAction: (() -> Void)? = nil) {
        self.text = text
        self.actionLabel = actionLabel
        self.onAction = onAction
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: NoopMetrics.space3) {
            Text(text).font(StrandFont.body.weight(.medium)).foregroundStyle(StrandPalette.textPrimary)
                .fixedSize(horizontal: false, vertical: true)
            if let actionLabel, let onAction {
                Button(action: onAction) {
                    HStack(spacing: NoopMetrics.space2) {
                        Text(actionLabel).font(StrandFont.overline).tracking(StrandFont.overlineTracking).textCase(.uppercase)
                        Image(systemName: "arrow.right").accessibilityHidden(true)
                    }
                    .foregroundStyle(StrandPalette.coachCyan)
                    .frame(minHeight: NoopMetrics.touchTarget, alignment: .leading)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(NoopMetrics.cardPadding)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: NoopMetrics.cardRadius).fill(StrandPalette.surfaceBase))
        .overlay(RoundedRectangle(cornerRadius: NoopMetrics.cardRadius)
            .stroke(LinearGradient(gradient: StrandPalette.coachGradient, startPoint: .leading, endPoint: .trailing),
                    lineWidth: NoopMetrics.coachStroke))
    }
}

public struct TopChrome: View {
    public var dateLabel: String
    public var previousLabel: String
    public var nextLabel: String
    public var profileLabel: String
    public var strapLabel: String
    public var avatarInitials: String
    public var batteryPercent: Int?
    public var isConnected: Bool
    public var canGoNext: Bool
    public var onPrevious: () -> Void
    public var onNext: () -> Void
    public var onDate: () -> Void
    public var onProfile: () -> Void
    public var onStrap: () -> Void

    public init(dateLabel: String, previousLabel: String, nextLabel: String, profileLabel: String,
                strapLabel: String, avatarInitials: String = "", batteryPercent: Int? = nil,
                isConnected: Bool = false, canGoNext: Bool = true, onPrevious: @escaping () -> Void,
                onNext: @escaping () -> Void, onDate: @escaping () -> Void,
                onProfile: @escaping () -> Void, onStrap: @escaping () -> Void) {
        self.dateLabel = dateLabel
        self.previousLabel = previousLabel
        self.nextLabel = nextLabel
        self.profileLabel = profileLabel
        self.strapLabel = strapLabel
        self.avatarInitials = avatarInitials
        self.batteryPercent = batteryPercent
        self.isConnected = isConnected
        self.canGoNext = canGoNext
        self.onPrevious = onPrevious
        self.onNext = onNext
        self.onDate = onDate
        self.onProfile = onProfile
        self.onStrap = onStrap
    }

    public var body: some View {
        HStack(spacing: NoopMetrics.space1) {
            Button(action: onProfile) {
                Group {
                    if avatarInitials.isEmpty { Image(systemName: "person.crop.circle") }
                    else { Text(avatarInitials).font(StrandFont.caption.weight(.bold)) }
                }
                .frame(width: NoopMetrics.touchTarget, height: NoopMetrics.touchTarget)
                .background(Circle().fill(StrandPalette.surfaceElevated))
            }
            .accessibilityLabel(profileLabel)
            Spacer(minLength: 0)
            HStack(spacing: 0) {
                chromeButton("chevron.left", label: previousLabel, action: onPrevious)
                Button(action: onDate) {
                    Text(dateLabel).font(StrandFont.overline).tracking(StrandFont.overlineTracking)
                        .textCase(.uppercase).padding(.horizontal, NoopMetrics.space2)
                        .frame(minHeight: NoopMetrics.touchTarget)
                }
                chromeButton("chevron.right", label: nextLabel, action: onNext).disabled(!canGoNext)
            }
            .background(Capsule().fill(StrandPalette.surfaceRaised))
            Spacer(minLength: 0)
            Button(action: onStrap) {
                HStack(spacing: NoopMetrics.space1) {
                    if let batteryPercent {
                        Text(Double(min(max(batteryPercent, 0), 100)) / 100,
                             format: .percent.precision(.fractionLength(0)))
                            .font(StrandFont.captionNumber)
                    }
                    Image(systemName: isConnected ? "sensor.tag.radiowaves.forward" : "sensor.tag.radiowaves.forward.fill")
                        .foregroundStyle(isConnected ? StrandPalette.positive : StrandPalette.textTertiary)
                }
                .frame(minWidth: NoopMetrics.touchTarget, minHeight: NoopMetrics.touchTarget)
            }
            .accessibilityLabel(strapLabel)
        }
        .buttonStyle(.plain)
        .foregroundStyle(StrandPalette.textPrimary)
    }

    private func chromeButton(_ image: String, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: image).font(StrandFont.caption.weight(.bold))
                .frame(width: NoopMetrics.touchTarget, height: NoopMetrics.touchTarget)
        }
        .accessibilityLabel(label)
    }
}

public struct TabCapsuleItem: Identifiable, Sendable {
    public let id: String
    public let label: String
    public let systemImage: String

    public init(id: String, label: String, systemImage: String) {
        self.id = id
        self.label = label
        self.systemImage = systemImage
    }
}

public struct TabCapsule: View {
    public var items: [TabCapsuleItem]
    public var selectedID: String
    public var onSelect: (String) -> Void

    public init(items: [TabCapsuleItem], selectedID: String, onSelect: @escaping (String) -> Void) {
        self.items = items
        self.selectedID = selectedID
        self.onSelect = onSelect
    }

    public var body: some View {
        HStack(spacing: 0) {
            ForEach(items) { item in
                Button { onSelect(item.id) } label: {
                    VStack(spacing: NoopMetrics.space1) {
                        Image(systemName: item.systemImage).font(.system(size: NoopMetrics.tabIconSize, weight: .medium))
                        Text(item.label).font(StrandFont.overlineScaled(10))
                    }
                    .foregroundStyle(selectedID == item.id ? StrandPalette.textPrimary : StrandPalette.textTertiary)
                    .frame(maxWidth: .infinity, minHeight: NoopMetrics.tabHeight)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(item.label)
                .accessibilityAddTraits(selectedID == item.id ? .isSelected : [])
            }
        }
        .padding(.horizontal, NoopMetrics.space2)
        .background(Capsule().fill(StrandPalette.surfaceElevated))
        .overlay(Capsule().stroke(StrandPalette.hairline, lineWidth: NoopMetrics.hairlineWidth))
    }
}

public struct CoachOrb: View {
    public var label: String
    public var onTap: () -> Void

    public init(label: String, onTap: @escaping () -> Void) {
        self.label = label
        self.onTap = onTap
    }

    public var body: some View {
        Button(action: onTap) {
            Image(systemName: "sparkles").font(.system(size: NoopMetrics.tabIconSize, weight: .medium))
                .foregroundStyle(StrandPalette.textPrimary)
                .frame(width: NoopMetrics.coachDiameter, height: NoopMetrics.coachDiameter)
                .background(Circle().fill(StrandPalette.surfaceElevated))
                .overlay(Circle().stroke(
                    LinearGradient(gradient: StrandPalette.coachGradient, startPoint: .topLeading, endPoint: .bottomTrailing),
                    lineWidth: NoopMetrics.coachStroke))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

/// Shared chart presentation; data, gaps, bounds and selections remain host responsibilities.
public enum ChartTokens {
    public static let barRadius = NoopMetrics.chartBarRadius
    public static let lineWidth = NoopMetrics.chartLineWidth
    public static let grid = StrandPalette.hairline
    public static let axis = StrandPalette.textTertiary
    public static let comparison = StrandPalette.targetBand
    public static let favorable = StrandPalette.positive
    public static let adverse = StrandPalette.stressHigh
    public static let sleep = StrandPalette.sleepPrimary
    public static let strain = StrandPalette.strainPrimary

    public static func hypnogram(_ stage: SleepStage) -> Color {
        StrandPalette.sleepStageColor(stage)
    }
}
