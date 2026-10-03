import SwiftUI
import StrandDesign

struct SleepStageInspection {
    let seconds: TimeInterval
    let stage: SleepStage?

    static func resolve(fraction: Double, span: TimeInterval, intervals: [SleepInterval]) -> SleepStageInspection? {
        guard fraction.isFinite, span.isFinite, span > 0 else { return nil }
        let seconds = min(max(fraction, 0), 1) * span
        let stage = intervals.first { seconds >= $0.start && seconds < $0.end }?.stage
        return SleepStageInspection(seconds: seconds, stage: stage)
    }
}

struct SleepStageInspectionChart: View {
    let intervals: [SleepInterval]
    let nightStart: Date
    let span: TimeInterval
    let highlightedStage: SleepStage?
    @State private var selection: SleepStageInspection?
    private let stages: [SleepStage] = [.awake, .rem, .light, .deep]

    var body: some View {
        VStack(alignment: .leading, spacing: NoopMetrics.space2) {
            HStack(spacing: NoopMetrics.space3) {
                VStack(alignment: .trailing, spacing: 0) {
                    ForEach(stages, id: \.self) { stage in
                        Text(stage.label).font(StrandFont.caption)
                            .foregroundStyle(StrandPalette.textSecondary)
                            .frame(maxHeight: .infinity)
                    }
                }
                .frame(width: NoopMetrics.space10 + NoopMetrics.space1)
                VStack(spacing: NoopMetrics.space2) {
                    GeometryReader { geometry in
                        Canvas { context, size in
                            guard span > 0 else { return }
                            let laneHeight = size.height / CGFloat(stages.count)
                            for index in stages.indices {
                                let y = laneHeight * (CGFloat(index) + 0.5)
                                var lane = Path()
                                lane.move(to: CGPoint(x: 0, y: y))
                                lane.addLine(to: CGPoint(x: size.width, y: y))
                                context.stroke(lane, with: .color(StrandPalette.hairline))
                            }
                            for (position, interval) in intervals.enumerated() {
                                guard let index = stages.firstIndex(of: interval.stage) else { continue }
                                let left = CGFloat(max(0, min(interval.start, span)) / span) * size.width
                                let right = CGFloat(max(0, min(interval.end, span)) / span) * size.width
                                let y = laneHeight * (CGFloat(index) + 0.5)
                                let rect = CGRect(x: left, y: y - NoopMetrics.hypnogramBandMinThickness / 2,
                                                  width: max(0, right - left), height: NoopMetrics.hypnogramBandMinThickness)
                                let opacity = highlightedStage == nil || highlightedStage == interval.stage ? 1.0 : 0.2
                                context.fill(Path(rect), with: .color(StrandPalette.sleepStageColor(interval.stage).opacity(opacity)))
                                if position > 0 {
                                    let previous = intervals[position - 1]
                                    if previous.end == interval.start, let previousIndex = stages.firstIndex(of: previous.stage) {
                                        var transition = Path()
                                        transition.move(to: CGPoint(x: left, y: laneHeight * (CGFloat(previousIndex) + 0.5)))
                                        transition.addLine(to: CGPoint(x: left, y: y))
                                        context.stroke(transition, with: .color(StrandPalette.sleepStageColor(interval.stage).opacity(opacity)))
                                    }
                                }
                            }
                            if let selection {
                                let x = CGFloat(selection.seconds / span) * size.width
                                var cursor = Path()
                                cursor.move(to: CGPoint(x: x, y: 0))
                                cursor.addLine(to: CGPoint(x: x, y: size.height))
                                context.stroke(cursor, with: .color(StrandPalette.textPrimary))
                            }
                        }
                        .contentShape(Rectangle())
                        .onTapGesture { location in inspect(location.x, width: geometry.size.width) }
                        .simultaneousGesture(DragGesture(minimumDistance: NoopMetrics.space2)
                            .onChanged { value in
                                guard abs(value.translation.width) > abs(value.translation.height) else { return }
                                inspect(value.location.x, width: geometry.size.width)
                            })
                        .onContinuousHover { phase in
                            switch phase {
                            case .active(let location): inspect(location.x, width: geometry.size.width)
                            case .ended: selection = nil
                            }
                        }
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel(Text("Stage breakdown"))
                        .accessibilityValue(Text(selectionText))
                        .accessibilityAdjustableAction { direction in
                            let fraction = (selection?.seconds ?? 0) / max(span, 1)
                            let step = 60 / max(span, 1)
                            selection = SleepStageInspection.resolve(fraction: fraction + (direction == .increment ? step : -step),
                                                                     span: span, intervals: intervals)
                        }
                    }
                    .frame(height: NoopMetrics.chartHeight)
                    HStack {
                        Text(clock(0))
                        Spacer()
                        Text(clock(span / 2))
                        Spacer()
                        Text(clock(span))
                    }
                    .font(StrandFont.caption)
                    .foregroundStyle(StrandPalette.textSecondary)
                }
            }
            Text(selectionText)
                .font(StrandFont.caption)
                .foregroundStyle(StrandPalette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .onChange(of: nightStart) { _ in selection = nil }
    }

    private func inspect(_ x: CGFloat, width: CGFloat) {
        guard width > 0 else { return }
        selection = SleepStageInspection.resolve(fraction: Double(x / width), span: span, intervals: intervals)
    }

    private func clock(_ seconds: TimeInterval) -> String {
        AppClock.hourMinuteFormatter().string(from: nightStart.addingTimeInterval(seconds))
    }

    private var selectionText: String {
        guard let selection else { return String(localized: "Touch the timeline to inspect sleep stages.") }
        let stage = selection.stage?.label ?? String(localized: "No data")
        return "\(clock(selection.seconds)) · \(stage)"
    }
}
