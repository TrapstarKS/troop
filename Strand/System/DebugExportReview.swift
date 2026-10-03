import Foundation
import SwiftUI
import StrandDesign
#if os(iOS)
import UIKit
#elseif os(macOS)
import AppKit
#endif

@MainActor
final class DebugExportReview: ObservableObject {
    static let shared = DebugExportReview()

    enum Destination { case text(String), copy, bundle(String) }
    struct Pending: Identifiable {
        let id = UUID()
        var gate: ReportReviewGate
        let destination: Destination
        let output: ([FileExport.BundleEntry], Destination) async -> Void
        var isCopy: Bool { if case .copy = destination { return true }; return false }
    }

    enum PreparationError: Error { case tooLarge, invalidMetadata, duplicateAttachment }
    @Published private(set) var pending: Pending?
    private var generation = 0

    nonisolated static func prepare(_ entries: [FileExport.BundleEntry],
                                    capBytes: Int = TestBundleAssembler.defaultCapBytes) throws -> [FileExport.BundleEntry] {
        guard Set(entries.map(\.name)).count == entries.count else { throw PreparationError.duplicateAttachment }
        let redacted = TestBundleAssembler.redactEntries(entries).map { entry in
            let data = String(data: entry.data, encoding: .utf8).map { Data(redactBearer($0).utf8) } ?? entry.data
            guard entry.name == "report.txt", data.count > capBytes else { return FileExport.BundleEntry(name: entry.name, data: data) }
            var tail = TestBundleAssembler.trimToLineBoundary(data.suffix(max(0, capBytes)))
            while let byte = tail.first, byte & 0xC0 == 0x80 { tail.removeFirst() }
            return FileExport.BundleEntry(name: entry.name, data: tail)
        }
        let prepared = TestBundleAssembler.capEntries(redacted, capBytes: capBytes).0
        guard prepared.reduce(0, { $0 + $1.data.count }) <= capBytes else { throw PreparationError.tooLarge }
        return prepared
    }

    nonisolated static func pairEntries(file: URL, text: String,
                                       textName: String) -> [FileExport.BundleEntry] {
        var entries = [FileExport.BundleEntry(name: textName, data: Data(text.utf8))]
        if let data = try? Data(contentsOf: file) {
            entries.insert(.init(name: "raw-capture.jsonl", data: data), at: 0)
        }
        return entries
    }

    nonisolated static func prepareResearch(_ entries: [FileExport.BundleEntry],
                                            capBytes: Int = TestBundleAssembler.defaultCapBytes) throws -> [FileExport.BundleEntry] {
        guard entries.reduce(0, { $0 + $1.data.count }) <= capBytes else { throw PreparationError.tooLarge }
        guard Set(entries.map(\.name)).count == entries.count else { throw PreparationError.duplicateAttachment }
        let metadataNames: Set<String> = ["meta.json", "events.jsonl", "events.csv"]
        let prepared = try entries.map { entry -> FileExport.BundleEntry in
            guard metadataNames.contains(entry.name) else { return entry }
            guard let text = String(data: entry.data, encoding: .utf8) else { throw PreparationError.invalidMetadata }
            let scrubbed = redactBearer(LiveState.redactPii(text))
                .replacingOccurrences(of: #"("(?:device_id|strap_device_id)"\s*:\s*")[^"]*(")"#,
                                      with: "$1<device>$2", options: .regularExpression)
            return .init(name: entry.name, data: Data(scrubbed.utf8))
        }
        guard prepared.reduce(0, { $0 + $1.data.count }) <= capBytes else { throw PreparationError.tooLarge }
        return prepared
    }

    nonisolated static func redactBearer(_ text: String) -> String {
        text.replacingOccurrences(of: #"(?i:Bearer)(?:[\s\p{Z}\u0085]|\\[nrt])+[A-Za-z0-9._~+/-]+=*"#,
                                  with: "Bearer <redacted>", options: .regularExpression)
    }

    func beginPreparation() -> Int {
        guard !Task.isCancelled else { return generation }
        generation += 1
        let ticket = generation
        pending = nil
        return ticket
    }

    func isCurrentPreparation(_ ticket: Int) -> Bool { ticket == generation }

    @discardableResult
    func stage(_ entries: [FileExport.BundleEntry], destination: Destination,
               capBytes: Int = TestBundleAssembler.defaultCapBytes,
               research: Bool = false,
               ticket: Int? = nil,
               output: (([FileExport.BundleEntry], Destination) async -> Void)? = nil) async -> Bool {
        guard !Task.isCancelled else { return false }
        let preparation = ticket ?? beginPreparation()
        guard !Task.isCancelled, isCurrentPreparation(preparation) else { return false }
        let prepared = await Task.detached(priority: .userInitiated) {
            try? research ? Self.prepareResearch(entries, capBytes: capBytes) : Self.prepare(entries, capBytes: capBytes)
        }.value
        guard !Task.isCancelled, isCurrentPreparation(preparation), let prepared else { return false }
        pending = Pending(gate: ReportReviewGate(entries: prepared), destination: destination,
                          output: output ?? Self.output)
        return true
    }

    @discardableResult
    func stageResearch(_ entries: [FileExport.BundleEntry], suggestedName: String,
                       capBytes: Int = TestBundleAssembler.defaultCapBytes,
                       ticket: Int? = nil,
                       output: (([FileExport.BundleEntry], Destination) async -> Void)? = nil) async -> Bool {
        await stage(entries, destination: .bundle(suggestedName), capBytes: capBytes, research: true, ticket: ticket, output: output)
    }

    func cancel() {
        generation += 1
        pending = nil
    }

    func confirm(id: UUID, output: (([FileExport.BundleEntry], Destination) async -> Void)? = nil) async {
        guard var review = pending, review.id == id else { return }
        review.gate.confirm()
        pending = nil
        guard review.gate.isCleared else { return }
        await (output ?? review.output)(review.gate.entries, review.destination)
    }

    private static func output(_ entries: [FileExport.BundleEntry], _ destination: Destination) async {
        switch destination {
        case .copy:
            PlatformPasteboard.copy(String(data: entries.first?.data ?? Data(), encoding: .utf8) ?? "")
        case .text(let name):
            FileExport.exportText(String(data: entries.first?.data ?? Data(), encoding: .utf8) ?? "", suggestedName: name)
        case .bundle(let name):
            await FileExport.exportBundle(entries: entries, suggestedName: name)
        }
    }
}

@MainActor
final class DebugExportPresentation {
    typealias Dismiss = (@escaping () -> Void) -> Void
    typealias Present = (DebugExportReview.Pending, @escaping () -> Void, @escaping () -> Void) -> Dismiss?

    private let review: DebugExportReview
    private let present: Present
    private let confirm: (UUID) async -> Void
    private var presentedID: UUID?
    private var dismiss: Dismiss?
    private var isDismissing = false

    init(review: DebugExportReview, present: @escaping Present,
         confirm: ((UUID) async -> Void)? = nil) {
        self.review = review
        self.present = present
        self.confirm = confirm ?? { await review.confirm(id: $0) }
    }

    func update() {
        guard !isDismissing, presentedID != review.pending?.id else { return }
        if dismiss != nil {
            close { self.update() }
        } else if let pending = review.pending {
            dismiss = present(pending, {
                guard self.presentedID == pending.id else { return }
                self.review.cancel()
                self.update()
            }, { self.confirmPresented(pending.id) })
            if dismiss != nil { presentedID = pending.id }
        }
    }

    private func confirmPresented(_ id: UUID) {
        guard !isDismissing, presentedID == id, review.pending?.id == id else { return }
        close {
            Task {
                await self.confirm(id)
                self.update()
            }
        }
    }

    private func close(then completion: @escaping () -> Void) {
        guard let dismiss else { completion(); return }
        isDismissing = true
        dismiss {
            self.dismiss = nil
            self.presentedID = nil
            self.isDismissing = false
            completion()
        }
    }
}

@MainActor
struct DebugExportReviewHost: ViewModifier {
    @ObservedObject private var review = DebugExportReview.shared
    @Environment(\.scenePhase) private var scenePhase
    private static let presentation = DebugExportPresentation(review: .shared, present: present)

    func body(content: Content) -> some View {
        content
            .onChangeCompat(of: review.pending?.id) { _ in Self.presentation.update() }
            .onChangeCompat(of: scenePhase) { phase in if phase == .active { Self.presentation.update() } }
            .onAppear { Self.presentation.update() }
    }

    @MainActor
    private static func present(_ pending: DebugExportReview.Pending,
                                onCancel: @escaping () -> Void,
                                onConfirm: @escaping () -> Void) -> DebugExportPresentation.Dismiss? {
        let sheet = ReportReviewSheet(preview: pending.gate.previewText, isCopy: pending.isCopy,
                                      onCancel: onCancel, onConfirm: onConfirm)
            .environment(\.locale, AppLanguage.activeLocale)
            .preferredColorScheme(AppearanceMode.resolve(UserDefaults.standard.string(forKey: AppearanceMode.storageKey)
                                                        ?? AppearanceMode.system.rawValue).colorScheme)
            .dynamicTypeSize(...DynamicTypeSize.accessibility1)
        #if os(iOS)
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene })
            .first(where: { $0.activationState == .foregroundActive }),
              let root = scene.windows.first(where: { $0.isKeyWindow })?.rootViewController else { return nil }
        var presenter = root
        while let next = presenter.presentedViewController, !next.isBeingDismissed { presenter = next }
        let controller = UIHostingController(rootView: sheet)
        controller.isModalInPresentation = true
        presenter.present(controller, animated: true)
        return { completion in presenter.dismiss(animated: true, completion: completion) }
        #elseif os(macOS)
        guard var presenter = NSApp.keyWindow ?? NSApp.mainWindow else { return nil }
        while let attached = presenter.attachedSheet { presenter = attached }
        let controller = NSHostingController(rootView: sheet)
        let window = NSWindow(contentViewController: controller)
        window.styleMask.remove(.closable)
        var didDismiss: (() -> Void)?
        presenter.beginSheet(window) { _ in
            window.orderOut(nil)
            didDismiss?()
        }
        return { completion in
            didDismiss = completion
            presenter.endSheet(window)
        }
        #else
        return nil
        #endif
    }
}
