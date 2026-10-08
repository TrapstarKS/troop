import SwiftUI
import StrandAnalytics
import StrandDesign

struct StressTodayCurveCard: View {
    @EnvironmentObject private var repo: Repository
    @EnvironmentObject private var model: AppModel
    @State private var observedSource: String?
    @State private var loadedSource: String?
    @State private var loadedDay: Int?
    @State private var result: DaytimeStress.Result?
    @State private var latestSampleTs: Int?
    @State private var readFailed = false

    private var sourceID: String { healthspanSourceID(observedSource ?? model.deviceRegistry?.activeDeviceId, repo: repo) }

    var body: some View {
        TimelineView(.periodic(from: .now, by: 60)) { context in
            let now = context.date
            let day = StressDayCurve.localDayNumber(now)
            let visible = loadedSource == sourceID && loadedDay == day ? result : nil
            let reading = visible?.monitorReading(latestSampleTs: latestSampleTs,
                                                   now: Int(now.timeIntervalSince1970), isToday: true)
            NoopCard(tint: StressRamp.calm) {
                VStack(alignment: .leading, spacing: NoopMetrics.space4) {
                    Text("Stress through the day").strandOverline()
                    if let visible, visible.timeline.contains(where: { $0.level != nil }) {
                        DaytimeLoadLine(hours: visible.timeline)
                        if reading?.state == .delayed {
                            Text(StressMonitorReading.State.delayed.message)
                                .font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
                        }
                    } else {
                        Text(readFailed ? String(localized: "Stored samples could not be read.")
                             : reading?.state.message ?? String(localized: "Loading…"))
                            .font(StrandFont.subhead).foregroundStyle(StrandPalette.textTertiary)
                            .frame(maxWidth: .infinity, minHeight: NoopMetrics.touchTarget, alignment: .center)
                    }
                    if let caption = stressActivityMaskedHoursCaption(visible?.activityMaskedHours ?? 0) {
                        Text(caption).font(StrandFont.footnote).foregroundStyle(StrandPalette.textTertiary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
            .task(id: "\(sourceID)|\(repo.refreshSeq)|\(day)|\(Int(now.timeIntervalSince1970) / 900)|\(PuffinExperiment.stressPersonalBaselineEnabled)") {
                let source = sourceID
                guard await healthspanAwaitSource(source, repo: repo) else { return }
                let revision = repo.refreshSeq
                let snapshot = await StressDayCurve.today(repo: repo, now: now,
                    personalBaseline: PuffinExperiment.stressPersonalBaselineEnabled)
                guard healthspanSourceIsCurrent(source, model: model, repo: repo), revision == repo.refreshSeq else { return }
                readFailed = snapshot == nil
                if let snapshot {
                    result = snapshot.result; latestSampleTs = snapshot.latestSampleTs
                    loadedSource = source; loadedDay = snapshot.day
                }
            }
        }
        .onReceive(healthspanSourcePublisher(model: model, repo: repo)) { observedSource = $0 }
    }
}
