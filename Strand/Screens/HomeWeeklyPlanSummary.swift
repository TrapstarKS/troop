import SwiftUI
import StrandDesign

struct HomeWeeklyPlanSummary: View {
    @EnvironmentObject private var repo: Repository
    @Environment(\.scenePhase) private var scenePhase
    @State private var today = Repository.localDayKey(Date())
    @State private var preferenceRevision = 0
    @State private var snapshot: WeeklyPlanSnapshot?
    @State private var hasSavedPlan: Bool?

    var body: some View {
        NavigationLink {
            WeeklyPlanView()
        } label: {
            NoopCard {
                VStack(alignment: .leading, spacing: NoopMetrics.space3) {
                    HStack {
                        Text("Weekly Plan").font(StrandFont.headline)
                        Spacer()
                        Text("This week").font(StrandFont.caption)
                            .foregroundStyle(StrandPalette.textSecondary)
                        Image(systemName: "chevron.right").font(StrandFont.caption)
                    }
                    if let snapshot {
                        HStack {
                            Text("Overall progress").font(StrandFont.caption)
                            Spacer()
                            Text(verbatim: snapshot.overallPercent.map { "\($0)%" } ?? "—")
                                .font(StrandFont.bodyNumber)
                        }
                        if let percent = snapshot.overallPercent {
                            ProgressView(value: Double(percent), total: 100).tint(StrandPalette.accent)
                        }
                        goal(String(localized: "Sleep goal"), progress: snapshot.sleep)
                        goal(String(localized: "Strain goal"), progress: snapshot.strain)
                        goal(String(localized: "Journal habit"), progress: snapshot.journal)
                    } else if hasSavedPlan == false {
                        Text("No plan saved for this week").font(StrandFont.body)
                            .foregroundStyle(StrandPalette.textSecondary)
                    } else {
                        ProgressView()
                    }
                }
                .foregroundStyle(StrandPalette.textPrimary)
            }
        }
        .buttonStyle(.plain)
        .task(id: "\(repo.refreshSeq):\(repo.journalSeq):\(repo.loaded):\(today):\(preferenceRevision)") {
            await load()
        }
        .onReceive(NotificationCenter.default.publisher(for: UserDefaults.didChangeNotification).receive(on: RunLoop.main)) { _ in
            preferenceRevision += 1
        }
        .onChangeCompat(of: scenePhase) { phase in
            if phase == .active { today = Repository.localDayKey(Date()) }
        }
    }

    private func goal(_ label: String, progress: WeeklyPlanProgress) -> some View {
        HStack {
            Text(label).font(StrandFont.caption).foregroundStyle(StrandPalette.textSecondary)
            Spacer()
            Text(progress.percent == nil ? "—"
                : String(localized: "\(progress.completedDays) of \(progress.targetDays) days"))
                .font(StrandFont.captionNumber)
        }
    }

    private func load() async {
        let currentDay = today
        guard let week = WeeklyPlanCalendar.weekStart(currentDay) else { return }
        let preferences = WeeklyPlanPreferences()
        guard preferences.hasPlan(weekStart: week) else {
            hasSavedPlan = false
            snapshot = nil
            return
        }
        hasSavedPlan = true
        guard repo.loaded else { return }
        let goals = preferences.goals(weekStart: week)
        let entries = await repo.journalEntries(days: 7)
        guard !Task.isCancelled, currentDay == today else { return }
        snapshot = WeeklyPlanEngine.snapshot(goals: goals, weekStart: week, today: currentDay,
            days: repo.days.map { WeeklyPlanDay(day: $0.day, sleepMinutes: $0.totalSleepMin, strain: $0.strain) },
            journal: entries.map { WeeklyPlanJournalDay(day: $0.day, question: $0.question, answeredYes: $0.answeredYes) })
    }
}
