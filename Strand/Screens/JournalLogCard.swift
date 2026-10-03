import SwiftUI
import StrandDesign

/// Journal drafts with explicit Save, grouped yes/no and numeric factors, and historical dates.
/// Answers write under
/// `Repository.journalDeviceId` ("noop-journal"), NEVER the imported source, so a CSV re-import can't
/// clobber them and clearing is safe (imported rows are never touched). Tri-state: tapping the selected
/// chip again clears the answer. Day attribution follows the importer's wake-day convention, answers
/// describe the night and day leading into the selected morning, so logged days line up with imported
/// history.
///
/// v2 (#322): items sit under collapsible groups (Nutrition / Supplements / …); an item can be a
/// numeric value (with a unit) instead of a toggle; and custom items can be renamed / regrouped /
/// converted / reordered in edit mode. The stored KEY (`canonical`) never changes on a rename, so all
/// history, logged and imported, stays joined under the original question.
struct JournalLogCard: View {
    @EnvironmentObject var repo: Repository
    @ObservedObject private var catalog: JournalCatalogStore

    /// Distinct imported question strings (from InsightsView's load), adopted into the catalog so
    /// logged answers and imported history group under the same behaviour.
    let importedQuestions: [String]
    /// question → answeredYes for the selected day, native rows only (drives the chip state).
    let answers: [String: Bool]
    /// question → numeric value for the selected day, native rows only (drives the numeric fields).
    let numericAnswers: [String: Double]
    @Binding var dayOffset: Int            // -1 = tomorrow, 0 = today, 1 = yesterday
    let anchorDay: String
    let answersDayKey: String
    let onDirtyChanged: (Bool) -> Void
    let onChanged: () -> Void              // parent re-runs load() after a write

    init(catalog: JournalCatalogStore, importedQuestions: [String], answers: [String: Bool],
         numericAnswers: [String: Double] = [:], dayOffset: Binding<Int>, answersDayKey: String, anchorDay: String,
         onDirtyChanged: @escaping (Bool) -> Void, onChanged: @escaping () -> Void) {
        _catalog = ObservedObject(wrappedValue: catalog)
        self.importedQuestions = importedQuestions
        self.answers = answers
        self.numericAnswers = numericAnswers
        self._dayOffset = dayOffset
        self.anchorDay = anchorDay
        self.answersDayKey = answersDayKey
        self.onDirtyChanged = onDirtyChanged
        self.onChanged = onChanged
    }

    @State private var draftAnswers: [String: Bool] = [:]
    @State private var draftNumeric: [String: Double] = [:]
    @State private var draftNumericText: [String: String] = [:]
    @State private var baselineAnswers: [String: Bool] = [:]
    @State private var baselineNumeric: [String: Double] = [:]
    @State private var saving = false
    @State private var saveFailed = false
    @State private var showingCalendar = false
    @State private var calendarDate = Date()
    @State private var pendingOffset: Int?

    private var dirty: Bool {
        draftAnswers != baselineAnswers || draftNumeric != baselineNumeric || draftNumericText.contains {
            $0.value != (baselineNumeric[$0.key].map(NumericLogField.format) ?? "")
        }
    }
    private var invalidNumeric: Bool {
        draftNumericText.contains { question, text in
            !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
                journalNumericValue(text, allowNegative: journalAllowsNegative(question)) == nil
        }
    }
    private var selectedDate: Date {
        JournalCalendar.date(dayKey) ?? Date()
    }

    @State private var customDraft = ""
    @State private var customIsNumeric = false
    @State private var customGroup: JournalGroup = .other
    /// Edit mode: swaps the answer controls for rename/group/convert/remove and reveals hidden items.
    @State private var editing = false
    /// Collapsed groups (persisted per group).
    @AppStorage("journal.collapsedGroups") private var collapsedGroupsRaw = ""
    /// The item being renamed (drives the rename sheet).
    @State private var renaming: JournalCatalogItem?
    @State private var renameDraft = ""

    private var dayKey: String {
        WeeklyPlanCalendar.adding(days: -dayOffset, to: anchorDay) ?? anchorDay
    }

    /// The resolved, grouped catalog for the current imported set. Hidden items included only while
    /// editing (so they can be restored in place).
    private var resolved: [JournalCatalogItem] {
        catalog.resolvedItems(imported: importedQuestions, includeHidden: editing)
    }

    /// Items grouped by their group, each group ordered by sortIndex then display.
    private func items(in group: JournalGroup) -> [JournalCatalogItem] {
        resolved.filter { $0.group == group }
            .sorted { ($0.sortIndex, $0.display) < ($1.sortIndex, $1.display) }
    }

    private var answeredCount: Int { resolved.filter { draftAnswers[$0.canonical] != nil }.count }

    private var collapsedGroups: Set<String> {
        Set(collapsedGroupsRaw.split(separator: ",").map(String.init))
    }

    private func toggleCollapsed(_ group: JournalGroup) {
        var set = collapsedGroups
        if set.contains(group.rawValue) { set.remove(group.rawValue) } else { set.insert(group.rawValue) }
        collapsedGroupsRaw = set.sorted().joined(separator: ",")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: NoopMetrics.gap) {
            HStack(alignment: .center) {
                SectionHeader("Journal", overline: "Log")
                Spacer()
                if editing {
                    pillButton("Done", selected: true) { editing = false }
                } else {
                    pillButton("Edit", selected: false) { editing = true }
                }
            }
            if !editing {
                HStack(spacing: NoopMetrics.space2) {
                    Button {
                        calendarDate = selectedDate
                        showingCalendar = true
                    } label: {
                        Label(selectedDate.formatted(date: .abbreviated, time: .omitted), systemImage: "calendar")
                            .font(StrandFont.body)
                    }
                    .buttonStyle(.plain)
                    Spacer()
                    Text(dirty ? String(localized: "Unsaved") : baselineAnswers.isEmpty ? String(localized: "Not started") : String(localized: "Saved"))
                        .font(StrandFont.caption)
                        .foregroundStyle(dirty ? StrandPalette.statusWarning : StrandPalette.textSecondary)
                }
                .disabled(saving)
            }
            if !editing, dayOffset == 0, baselineAnswers.isEmpty,
               repo.days.contains(where: { $0.day == dayKey && $0.totalSleepMin != nil }) {
                Text("Sleep is ready. Take a moment to record yesterday’s habits.")
                    .font(StrandFont.footnote)
                    .foregroundStyle(StrandPalette.textSecondary)
            }
            // Day picker (#656): a bounded, scrollable range — Tomorrow back through the last 7 days — so
            // any recent day can be backfilled (was Yesterday/Today/Tomorrow only). Chronological
            // left→right; snaps to the selected day, so a deep-link from the Today journal widget lands on
            // that day's pill. Only when not editing.
            if !editing {
                ScrollViewReader { proxy in
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: NoopMetrics.space2) {
                            ForEach(Self.journalDayOffsets, id: \.self) { off in
                                dayPill(journalDayLabel(off), offset: off).id(off)
                            }
                        }
                        .padding(.horizontal, NoopMetrics.spaceHalf)   // don't clip the selected pill's ring
                    }
                    // Defer the initial scroll a tick: scrollTo in onAppear can no-op before the pills lay
                    // out, which would leave the picker on the oldest day instead of the selected one.
                    .onAppear { DispatchQueue.main.async { proxy.scrollTo(dayOffset, anchor: .center) } }
                    // onChangeCompat, not onChange: the zero/two-arg onChange is macOS 14+, and this card
                    // is shared with the macOS 13 target.
                    .onChangeCompat(of: dayOffset) { _ in proxy.scrollTo(dayOffset, anchor: .center) }
                }
            }
            NoopCard(tint: StrandPalette.restColor) {
                VStack(alignment: .leading, spacing: NoopMetrics.space3) {
                    Text(editing
                         ? "Choose the questions you want to track."
                         : dayOffset == -1
                         ? "Record habits for tomorrow’s entry."
                         : "Record habits from yesterday and last night.")
                        .font(StrandFont.footnote)
                        .foregroundStyle(StrandPalette.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)

                    ForEach(JournalGroup.displayOrder, id: \.self) { group in
                        groupBlock(group)
                    }

                    if !editing {
                        Divider().overlay(StrandPalette.hairline)
                        HStack {
                            Text("\(answeredCount) of \(resolved.count) answered")
                                .font(StrandFont.captionNumber)
                                .foregroundStyle(StrandPalette.textSecondary)
                            Spacer()
                            Button("Discard") { resetDraft() }
                                .disabled(!dirty || saving)
                            Button(action: saveDraft) { Text(saving ? String(localized: "Saving…") : String(localized: "Save journal")) }
                                .buttonStyle(.borderedProminent)
                                .disabled(!dirty || saving || invalidNumeric)
                        }
                        if invalidNumeric {
                            Text("Enter a valid number to save.")
                                .font(StrandFont.footnote)
                                .foregroundStyle(StrandPalette.statusWarning)
                        }
                        if saveFailed {
                            Text("Journal could not be saved. Try again.")
                                .font(StrandFont.footnote)
                                .foregroundStyle(StrandPalette.statusWarning)
                        }
                    } else {
                        Divider().overlay(StrandPalette.hairline)
                        addRow
                    }
                }
            }
        }
        .disabled(saving || answersDayKey != dayKey)
        .onAppear {
            if answersDayKey == dayKey && !dirty { resetDraft() }
            onDirtyChanged(dirty)
        }
        .onChangeCompat(of: dirty) { onDirtyChanged($0) }
        .onChangeCompat(of: answers) { _ in if answersDayKey == dayKey && !dirty { resetDraft() } }
        .onChangeCompat(of: numericAnswers) { _ in if answersDayKey == dayKey && !dirty { resetDraft() } }
        .onChangeCompat(of: answersDayKey) { _ in if answersDayKey == dayKey && !dirty { resetDraft() } }
        .sheet(item: $renaming) { item in renameSheet(item) }
        .sheet(isPresented: $showingCalendar) {
            VStack(spacing: NoopMetrics.gap) {
                DatePicker("Journal date", selection: $calendarDate,
                           in: ...Calendar.current.date(byAdding: .day, value: 1, to: Date())!,
                           displayedComponents: .date)
                    .datePickerStyle(.graphical)
                Button("Done") {
                    let offset = Calendar.current.dateComponents([.day], from: Calendar.current.startOfDay(for: calendarDate),
                                                                  to: JournalCalendar.date(anchorDay) ?? Date()).day ?? 0
                    showingCalendar = false
                    selectDay(offset)
                }
            }
            .padding(NoopMetrics.cardPadding)
        }
        .confirmationDialog("Discard unsaved journal changes?", isPresented: Binding(
            get: { pendingOffset != nil }, set: { if !$0 { pendingOffset = nil } })) {
            Button("Discard", role: .destructive) {
                if let offset = pendingOffset { changeDay(offset) }
                pendingOffset = nil
            }
            Button("Cancel", role: .cancel) { pendingOffset = nil }
        }
    }

    private func resetDraft() {
        baselineAnswers = answers
        baselineNumeric = numericAnswers
        draftAnswers = answers
        draftNumeric = numericAnswers
        draftNumericText = [:]
        saveFailed = false
    }

    private func selectDay(_ offset: Int) {
        guard offset != dayOffset, !saving else { return }
        if dirty { pendingOffset = offset } else { changeDay(offset) }
    }

    private func changeDay(_ offset: Int) {
        baselineAnswers = [:]
        baselineNumeric = [:]
        draftAnswers = [:]
        draftNumeric = [:]
        draftNumericText = [:]
        dayOffset = offset
        onChanged()
    }

    private func saveDraft() {
        guard dirty, !saving, !invalidNumeric else { return }
        let day = dayKey
        let nextAnswers = draftAnswers
        let nextNumeric = draftNumeric
        let questions = Set(baselineAnswers.keys).union(baselineNumeric.keys).union(nextAnswers.keys).union(nextNumeric.keys)
            .filter { baselineAnswers[$0] != nextAnswers[$0] || baselineNumeric[$0] != nextNumeric[$0] }
        saving = true
        Task {
            for question in questions {
                if let value = nextNumeric[question] {
                    await repo.saveJournalNumeric(day: day, question: question, value: value)
                } else if let yes = nextAnswers[question] {
                    await repo.saveJournalAnswer(day: day, question: question, answeredYes: yes)
                } else {
                    await repo.clearJournalAnswer(day: day, question: question)
                }
            }
            let savedAnswers = await repo.nativeJournalAnswers(day: day)
            let savedNumeric = await repo.nativeJournalNumeric(day: day)
            saveFailed = savedAnswers != nextAnswers || savedNumeric != nextNumeric
            if !saveFailed {
                baselineAnswers = nextAnswers
                baselineNumeric = nextNumeric
                draftNumericText = [:]
                repo.noteJournalChanged()
            }
            onChanged()
            saving = false
        }
    }

    // MARK: - Group block

    @ViewBuilder private func groupBlock(_ group: JournalGroup) -> some View {
        let groupItems = items(in: group)
        // Empty groups hide outside edit mode; all groups remain available while editing.
        if !groupItems.isEmpty || editing {
            let collapsed = collapsedGroups.contains(group.rawValue)
            VStack(alignment: .leading, spacing: NoopMetrics.space2) {
                Button { toggleCollapsed(group) } label: {
                    HStack(spacing: NoopMetrics.space2) {
                        Text(group.title.uppercased())
                            .font(StrandFont.overline)
                            .tracking(StrandFont.overlineTracking)
                            .foregroundStyle(StrandPalette.textTertiary)
                        Text("\(groupItems.count)")
                            .font(StrandFont.caption)
                            .foregroundStyle(StrandPalette.textTertiary)
                        Spacer()
                        Image(systemName: collapsed ? "chevron.right" : "chevron.down")
                            .font(StrandFont.caption)
                            .foregroundStyle(StrandPalette.textTertiary)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("\(group.title), \(groupItems.count) items, \(collapsed ? "collapsed" : "expanded")")

                if !collapsed {
                    ForEach(groupItems) { item in itemRow(item) }
                }
            }
        }
    }

    // MARK: - Item row

    @ViewBuilder private func itemRow(_ item: JournalCatalogItem) -> some View {
        HStack {
            Text(verbatim: item.localizedDisplay)   // display = rename ?? canonical; data, not a UI literal
                .font(StrandFont.body)
                .foregroundStyle(item.hidden ? StrandPalette.textTertiary : StrandPalette.textPrimary)
            Spacer()
            if editing {
                editControls(item)
            } else if item.kind.isNumeric {
                numericField(item)
            } else {
                answerPill("Yes", q: item.canonical, value: true)
                answerPill("No", q: item.canonical, value: false)
            }
        }
    }

    // MARK: - Numeric field

    private func numericField(_ item: JournalCatalogItem) -> some View {
        let current = draftNumeric[item.canonical]
        return HStack(spacing: NoopMetrics.space2) {
            stepperButton("minus", q: item.canonical, current: current, unitLabel: item.kind.unitLabel)
            NumericLogField(
                text: Binding(get: { draftNumericText[item.canonical] ?? draftNumeric[item.canonical].map(NumericLogField.format) ?? "" },
                              set: { editNumeric(item.canonical, text: $0) }),
                placeholder: "—",
                label: [item.localizedDisplay, item.kind.unitLabel].compactMap { $0 }.joined(separator: " "),
                allowsNegative: journalAllowsNegative(item.canonical))
            .frame(width: NoopMetrics.space4 * 4)
            if let unit = item.kind.unitLabel, !unit.isEmpty {
                Text(verbatim: unit)
                    .font(StrandFont.footnote)
                    .foregroundStyle(StrandPalette.textTertiary)
                    .lineLimit(1)
                    .fixedSize(horizontal: true, vertical: false)
            }
            stepperButton("plus", q: item.canonical, current: current, unitLabel: item.kind.unitLabel)
            if current != nil {
                Button {
                    draftNumeric.removeValue(forKey: item.canonical)
                    draftAnswers.removeValue(forKey: item.canonical)
                    draftNumericText.removeValue(forKey: item.canonical)
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(StrandFont.footnote)
                        .foregroundStyle(StrandPalette.textTertiary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Clear \(item.localizedDisplay)")
            }
        }
    }

    private func stepperButton(_ symbol: String, q: String, current: Double?, unitLabel: String?) -> some View {
        Button {
            let base = current ?? 0
            let next = symbol == "plus" ? base + 1 : (unitLabel == "°C" ? base - 1 : max(0, base - 1))
            commitNumeric(q, value: next)
        } label: {
            Image(systemName: "\(symbol).circle")
                .font(StrandFont.body)
                .foregroundStyle(StrandPalette.textSecondary)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(symbol == "plus" ? "Increase" : "Decrease")
    }

    private func commitNumeric(_ q: String, value: Double) {
        guard value.isFinite, value >= 0 || journalAllowsNegative(q) else { return }
        draftNumericText.removeValue(forKey: q)
        draftNumeric[q] = value
        draftAnswers[q] = true
        saveFailed = false
    }

    private func editNumeric(_ q: String, text: String) {
        guard !saving, answersDayKey == dayKey else { return }
        draftNumericText[q] = text
        if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            draftNumeric.removeValue(forKey: q)
            draftAnswers.removeValue(forKey: q)
        } else if let value = journalNumericValue(text, allowNegative: journalAllowsNegative(q)) {
            draftNumeric[q] = value
            draftAnswers[q] = true
        }
        saveFailed = false
    }

    // MARK: - Edit-mode controls

    private func editControls(_ item: JournalCatalogItem) -> some View {
        HStack(spacing: NoopMetrics.space3) {
            if item.hidden {
                pillButton("Restore", selected: false) { catalog.restore(item.canonical) }
            } else {
                Menu {
                    Button("Rename…") { startRename(item) }
                    Menu("Group") {
                        ForEach(JournalGroup.displayOrder, id: \.self) { g in
                            Button(g.title) { catalog.setGroup(item.canonical, to: g) }
                        }
                    }
                    if item.kind.isNumeric {
                        Button("Change to Yes/No") { catalog.setKind(item.canonical, to: .bool) }
                    } else {
                        Button("Change to Number") { catalog.setKind(item.canonical, to: .numeric(unitLabel: nil)) }
                    }
                } label: {
                    Image(systemName: "slider.horizontal.3")
                        .font(StrandFont.body)
                        .foregroundStyle(StrandPalette.textSecondary)
                }
                .menuStyle(.borderlessButton)
                .fixedSize()
                .accessibilityLabel("Edit \(item.display)")

                removeButton(item)
            }
        }
    }

    /// Edit-mode control: delete a custom question / hide a built-in one. Tinted red to read as removal.
    private func removeButton(_ item: JournalCatalogItem) -> some View {
        Button { catalog.remove(item.canonical) } label: {
            Image(systemName: "minus.circle.fill")
                .font(StrandFont.body)
                .foregroundStyle(StrandPalette.statusCritical)
        }
        .buttonStyle(.plain)
        .help(item.custom ? "Delete this custom item" : "Hide this item")
        .accessibilityLabel(item.custom ? "Delete \(item.display)" : "Hide \(item.display)")
    }

    // MARK: - Rename sheet

    private func startRename(_ item: JournalCatalogItem) {
        renameDraft = item.displayName ?? item.canonical
        renaming = item
    }

    private func renameSheet(_ item: JournalCatalogItem) -> some View {
        VStack(alignment: .leading, spacing: NoopMetrics.gap) {
            Text("Rename item").font(StrandFont.headline)
            TextField("Display name", text: $renameDraft)
                .textFieldStyle(.roundedBorder)
            Text("History stays under the original question so WHOOP imports still line up.")
                .font(StrandFont.footnote)
                .foregroundStyle(StrandPalette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
            HStack {
                Button("Cancel") { renaming = nil }
                    .buttonStyle(.bordered)
                Spacer()
                Button("Save") {
                    catalog.rename(item.canonical, to: renameDraft)
                    renaming = nil
                }
                .buttonStyle(.borderedProminent)
            }
        }
        .padding(NoopMetrics.space4)
        #if os(macOS)
        .frame(minWidth: NoopMetrics.editorSheetMinWidth)
        #endif
    }

    // MARK: - Add row

    private var addRow: some View {
        VStack(alignment: .leading, spacing: NoopMetrics.space2) {
            HStack {
                TextField("Add a custom item…", text: $customDraft)
                    .textFieldStyle(.roundedBorder)
                pillButton(customIsNumeric ? "Number" : "Yes/No", selected: customIsNumeric) {
                    customIsNumeric.toggle()
                }
                Button("Add") {
                    let t = customDraft.trimmingCharacters(in: .whitespaces)
                    guard !t.isEmpty else { return }
                    catalog.addCustom(t,
                                      kind: customIsNumeric ? .numeric(unitLabel: nil) : .bool,
                                      group: customGroup)
                    customDraft = ""
                }
                .buttonStyle(.bordered)
                .disabled(customDraft.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            Picker("Group", selection: $customGroup) {
                ForEach(JournalGroup.displayOrder, id: \.self) { g in
                    Text(g.title).tag(g)
                }
            }
            .pickerStyle(.menu)
            .labelsHidden()
            .accessibilityLabel("New item group")
        }
    }

    // MARK: - Controls

    private func dayPill(_ label: LocalizedStringKey, offset: Int) -> some View {
        pillButton(label, selected: dayOffset == offset) {
            selectDay(offset)
        }
    }

    /// Recent-day shortcuts stay chronological; the calendar opens older recorded dates.
    private static let journalDayOffsets: [Int] = Array((-1...6).reversed())

    /// Short pill label for a day-picker offset (daysBack; -1 = Tomorrow). "%lld days ago" is a String
    /// Catalog key, so 2–6 stay localized just like the twin "%lld nights ago" (#527/#656).
    private func journalDayLabel(_ offset: Int) -> LocalizedStringKey {
        switch offset {
        case -1: return "Tomorrow"
        case 0: return "Today"
        case 1: return "Yesterday"
        default: return "\(offset) days ago"
        }
    }

    private func answerPill(_ label: LocalizedStringKey, q: String, value: Bool) -> some View {
        let selected = draftAnswers[q] == value
        return pillButton(label, selected: selected) {
            draftNumericText.removeValue(forKey: q)
            if selected {
                draftAnswers.removeValue(forKey: q)
                draftNumeric.removeValue(forKey: q)
            } else {
                draftAnswers[q] = value
                draftNumeric.removeValue(forKey: q)
            }
            saveFailed = false
        }
    }

    private func pillButton(_ label: LocalizedStringKey, selected: Bool,
                            action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(StrandFont.footnote)
                .foregroundStyle(selected ? StrandPalette.surfaceBase : StrandPalette.textSecondary)
                .padding(.horizontal, NoopMetrics.space3)
                .padding(.vertical, NoopMetrics.space2)
                .background(selected ? StrandPalette.restColor : StrandPalette.surfaceInset,
                            in: Capsule())
                .overlay(Capsule().stroke(selected ? StrandPalette.restColor : StrandPalette.hairline,
                                          lineWidth: NoopMetrics.hairlineWidth))
        }
        .buttonStyle(.plain)
    }
}

private func journalAllowsNegative(_ question: String) -> Bool {
    let unit = JournalFactor.find(question)?.unit
    return unit == nil || unit == "°C"
}

private func journalNumericValue(_ text: String, allowNegative: Bool) -> Double? {
    let cleaned = text.replacingOccurrences(of: ",", with: ".").trimmingCharacters(in: .whitespacesAndNewlines)
    guard let value = Double(cleaned), value.isFinite, value >= 0 || allowNegative else { return nil }
    return value
}

/// A compact numeric log field whose text stays in the parent draft, including incomplete numbers.
private struct NumericLogField: View {
    @Binding var text: String
    let placeholder: String
    let label: String
    let allowsNegative: Bool

    var body: some View {
        TextField(placeholder, text: $text)
            .textFieldStyle(.roundedBorder)
            .multilineTextAlignment(.center)
            .font(StrandFont.bodyNumber)
            .accessibilityLabel(Text(verbatim: label))
        #if os(iOS)
            .keyboardType(allowsNegative ? .numbersAndPunctuation : .decimalPad)
        #endif
    }

    static func format(_ v: Double) -> String {
        guard v.isFinite else { return "—" }
        let integral = v == v.rounded()
        let rounded = integral ? v : (v * 10).rounded(.toNearestOrEven) / 10
        return String(format: integral ? "%.0f" : "%.1f", locale: Locale(identifier: "en_US_POSIX"), rounded)
    }
}
