import Foundation
import ZIPFoundation

#if canImport(AppKit)
import AppKit
import UniformTypeIdentifiers
#elseif canImport(UIKit)
import UIKit
import UniformTypeIdentifiers
#endif

/// Cross-platform "save / share a file" helper.
///
/// - macOS uses `NSSavePanel` (sandbox-safe via the user-selected-file entitlement).
/// - iOS presents the system share sheet (`UIActivityViewController`) so the user can save the file
///   to Files, AirDrop it, or send it on — the idiomatic iOS way to get a file out of the sandbox.
enum FileExport {

    /// A short `yyMMdd-HHmm` wall-clock stamp for export filenames (#510 — maddognik's protocol RE),
    /// so a reporter who saves several strap logs / raw captures in a row gets sortable, non-colliding
    /// files (e.g. `noop-strap-log-260617-1042.txt`) instead of repeatedly overwriting one name.
    /// Locale-independent (POSIX) so the stamp is stable on every machine.
    static func timestamp(_ date: Date = Date()) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyMMdd-HHmm"
        return f.string(from: date)
    }

    /// Compose a timestamped suggested filename — `<prefix>-<yyMMdd-HHmm>.<ext>`
    /// (e.g. `timestampedName("noop-strap-log", ext: "txt")` → `noop-strap-log-260617-1042.txt`).
    static func timestampedName(_ prefix: String, ext: String) -> String {
        "\(prefix)-\(timestamp()).\(ext)"
    }

    /// Profile-tagged, self-describing bundle filename: `noop-<profile>-<platform>-v<version>-<yyMMdd-HHmm>.zip`
    /// (spec section 5.1). Self-describing at a glance so a maintainer knows the profile, platform and
    /// version before opening the zip. `timestampedName` keeps its old 2-arg form for the existing
    /// strap-log / raw-capture callers; this is the new bundle-name builder.
    static func bundleName(profile: String, platform: String, version: String, date: Date = Date()) -> String {
        "noop-\(profile)-\(platform)-v\(version)-\(timestamp(date)).zip"
    }

    /// Write `text` to a file and let the user choose where it goes.
    @MainActor
    static func exportText(_ text: String, suggestedName: String) {
        #if os(macOS)
        let panel = NSSavePanel()
        panel.nameFieldStringValue = suggestedName
        panel.canCreateDirectories = true
        guard panel.runModal() == .OK, let url = panel.url else { return }
        try? text.write(to: url, atomically: true, encoding: .utf8)
        #else
        // Write to a temp file FIRST and only present the share sheet if the file actually exists.
        // The previous `try?` swallowed write failures, then handed an empty/missing path to the
        // share sheet — the user saw a broken export with no error. Clean up the temp file after the
        // share sheet closes so the temporaryDirectory doesn't accumulate dead exports across runs.
        guard let url = stageText(text, suggestedName: suggestedName) else { return }
        present(activityItems: [url], cleanup: [url])
        #endif
    }

    /// Let the user save / share an existing file at `src`. On macOS this copies to a chosen
    /// destination; on iOS it offers the file through the share sheet. `src` is owned by the caller
    /// (e.g. a Puffin capture inside the app's container) and is NOT deleted by the share-sheet
    /// completion handler — only files we staged ourselves get cleaned up.
    @MainActor
    static func exportFile(at src: URL, suggestedName: String? = nil) {
        #if os(macOS)
        let panel = NSSavePanel()
        panel.nameFieldStringValue = suggestedName ?? src.lastPathComponent
        panel.canCreateDirectories = true
        guard panel.runModal() == .OK, let dest = panel.url else { return }
        let fm = FileManager.default
        do {
            if fm.fileExists(atPath: dest.path) { try fm.removeItem(at: dest) }
            try fm.copyItem(at: src, to: dest)
        } catch { /* best-effort */ }
        #else
        guard FileManager.default.fileExists(atPath: src.path) else { return }
        present(activityItems: [src], cleanup: [])
        #endif
    }

    @MainActor
    static func exportDebugText(_ text: String, suggestedName: String) {
        guard !Task.isCancelled else { return }
        let ticket = DebugExportReview.shared.beginPreparation()
        Task {
            await DebugExportReview.shared.stage([.init(name: "report.txt", data: Data(text.utf8))],
                                                destination: .text(suggestedName), ticket: ticket)
        }
    }

    @MainActor
    static func copyDebugText(_ text: String) {
        guard !Task.isCancelled else { return }
        let ticket = DebugExportReview.shared.beginPreparation()
        Task {
            await DebugExportReview.shared.stage([.init(name: "report.txt", data: Data(text.utf8))], destination: .copy, ticket: ticket)
        }
    }

    // Raw captures use the canonical name so the shared cap and attachment preview apply.
    @MainActor
    static func exportPair(file src: URL, fileSuggestedName _: String,
                           text: String, textSuggestedName: String,
                           read: (() async -> [BundleEntry])? = nil) async {
        guard !Task.isCancelled else { return }
        let ticket = DebugExportReview.shared.beginPreparation()
        let entries: [BundleEntry]
        if let read { entries = await read() }
        else {
            entries = await Task.detached(priority: .userInitiated) {
                DebugExportReview.pairEntries(file: src, text: text, textName: textSuggestedName)
            }.value
        }
        await DebugExportReview.shared.stage(entries,
            destination: .bundle(timestampedName("noop-export", ext: "zip")), ticket: ticket)
    }

    #if os(iOS)
    /// Present `UIActivityViewController` and, once it closes, best-effort remove the URLs in
    /// `cleanup` so staged exports don't accumulate in `temporaryDirectory` across runs.
    @MainActor
    @discardableResult
    private static func present(activityItems: [Any], cleanup: [URL],
                                completion: (@MainActor (Bool) -> Void)? = nil) -> (@MainActor () -> Void)? {
        var observer: NSObjectProtocol?
        var finished = false
        let finish: @MainActor (Bool) -> Void = { completed in
            guard !finished else { return }
            finished = true
            if let observer { NotificationCenter.default.removeObserver(observer) }
            observer = nil
            for url in cleanup { removeStaged(url) }
            completion?(completed)
        }
        guard let scene = UIApplication.shared.connectedScenes
                .compactMap({ $0 as? UIWindowScene })
                .first(where: { $0.activationState == .foregroundActive }),
              let root = scene.windows.first(where: { $0.isKeyWindow })?.rootViewController
                ?? scene.windows.first?.rootViewController else { finish(false); return nil }
        // Present from the TOP-MOST controller, not the root (#455). When the caller is itself inside a
        // SwiftUI sheet — e.g. the Trends report is shown via `.sheet` — root already has that sheet
        // presented, so `root.present(...)` is a no-op ("already presenting…") and the share sheet never
        // appears. Climb the presentedViewController chain so the share sheet stacks on top of whatever's
        // up. (The Share-strap-log path worked only because Settings isn't a sheet — root had nothing on it.)
        var presenter = root
        while let next = presenter.presentedViewController, !next.isBeingDismissed { presenter = next }
        guard presenter.viewIfLoaded?.window != nil,
              presenter.presentedViewController == nil,
              !presenter.isBeingDismissed, !presenter.isBeingPresented else { finish(false); return nil }
        let vc = UIActivityViewController(activityItems: activityItems, applicationActivities: nil)
        vc.completionWithItemsHandler = { _, completed, _, error in
            Task { @MainActor in finish(completed && error == nil) }
        }
        observer = NotificationCenter.default.addObserver(forName: UIScene.didDisconnectNotification,
            object: scene, queue: .main) { _ in Task { @MainActor in finish(false) } }
        // iPad: anchor the popover to the screen centre to avoid a crash.
        if let pop = vc.popoverPresentationController {
            pop.sourceView = presenter.view
            pop.sourceRect = CGRect(x: presenter.view.bounds.midX, y: presenter.view.bounds.midY, width: 0, height: 0)
            pop.permittedArrowDirections = []
        }
        presenter.present(vc, animated: true)
        return {
            finish(false)
            if vc.presentingViewController != nil { vc.dismiss(animated: true) }
        }
    }
    #endif

    // MARK: - Bundle export (Test Centre, spec section 5.1)

    /// A file destined for the zip bundle: its in-zip name plus the bytes. Text entries (report.txt,
    /// meta.json) are produced by the assembler; existing on-disk files (raw-capture, screenshot) are
    /// read into Data by the assembler. EVERY entry must already be redacted by the caller (section 5.3).
    /// Equatable so the assembler cap can assert an undersized bundle is returned untouched (section 5.4).
    struct BundleEntry: Equatable, Sendable { let name: String; let data: Data }

    private static func stagedURL(_ name: String) -> URL {
        let directory = NoopScratch.subdirectory("export-" + UUID().uuidString)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory.appendingPathComponent(name)
    }

    static func stageText(_ text: String, suggestedName: String) -> URL? {
        let url = stagedURL(suggestedName)
        do {
            try text.write(to: url, atomically: true, encoding: .utf8)
            return url
        } catch {
            removeStaged(url)
            return nil
        }
    }

    /// Zip `entries` into a single staged `.zip` under the temporary directory and return its URL, or nil
    /// if there are no entries. Pure file IO, no UI, so it is unit-testable. Uses ZIPFoundation's `Archive`
    /// which is available on macOS and iOS without shelling out. The caller presents the URL (NSSavePanel
    /// on macOS, share sheet on iOS) and cleans it up.
    static func zipData(entries: [BundleEntry], baseName: String,
                        writeEntry: ((Archive, BundleEntry) throws -> Void)? = nil) -> URL? {
        guard !entries.isEmpty else { return nil }
        let url = stagedURL("\(baseName).zip")
        do {
            let archive = try Archive(url: url, accessMode: .create)
            for entry in entries {
                if let writeEntry { try writeEntry(archive, entry) }
                else {
                    try archive.addEntry(with: entry.name, type: .file, uncompressedSize: Int64(entry.data.count)) { position, size in
                        let start = Int(position)
                        let end = min(start + size, entry.data.count)
                        return entry.data.subdata(in: start..<end)
                    }
                }
            }
        } catch {
            removeStaged(url)
            return nil
        }
        guard FileManager.default.fileExists(atPath: url.path) else { removeStaged(url); return nil }
        return url
    }

    private static func removeStaged(_ file: URL) {
        try? FileManager.default.removeItem(at: file)
        let directory = file.deletingLastPathComponent()
        if directory.lastPathComponent.hasPrefix("export-"),
           directory.deletingLastPathComponent().standardizedFileURL == NoopScratch.root().standardizedFileURL {
            try? FileManager.default.removeItem(at: directory)
        }
    }

    @MainActor
    static func sharePrepared(_ staged: URL, output: (URL) async -> Bool) async -> URL? {
        defer { removeStaged(staged) }
        guard !Task.isCancelled else { return nil }
        let completed = await output(staged)
        return completed && !Task.isCancelled ? staged : nil
    }

    @MainActor
    final class ShareReceipt {
        private var continuation: CheckedContinuation<Bool, Never>?
        private var cancelOutput: (@MainActor () -> Void)?
        private var result: Bool?

        func wait(start: (@escaping @MainActor (Bool) -> Void) -> (@MainActor () -> Void)?) async -> Bool {
            if let result { return result }
            return await withTaskCancellationHandler {
                await withCheckedContinuation { continuation in
                    self.continuation = continuation
                    guard !Task.isCancelled else { finish(false); return }
                    let cancelOutput = start { self.finish($0) }
                    if result == nil {
                        self.cancelOutput = cancelOutput
                        if cancelOutput == nil { finish(false) }
                    }
                }
            } onCancel: {
                Task { @MainActor in self.cancel() }
            }
        }

        func cancel() {
            guard result == nil else { return }
            let dismiss = cancelOutput
            finish(false)
            dismiss?()
        }

        private func finish(_ completed: Bool) {
            guard result == nil else { return }
            result = completed
            cancelOutput = nil
            continuation?.resume(returning: completed)
            continuation = nil
        }
    }

    /// Zip `entries` into one `.zip` and hand it to the user (NSSavePanel on macOS, share sheet on iOS).
    /// EVERY entry must already be redacted by the caller (section 5.3); the 20 MB cap (section 5.4) is the
    /// assembler's job before it calls here. Returns the saved URL on macOS, or the staged URL as a receipt
    /// after a completed iOS activity. The staged file is removed after completion; cancel/failure returns nil.
    ///
    /// #646/#651: `zipData` (the actual DEFLATE + write) runs off the main actor via a detached task, same
    /// pattern as `DataBackup.runExport`, so building a multi-entry bundle never beach-balls the UI. Only
    /// the save panel / share sheet presentation below hops back to the main actor.
    @MainActor @discardableResult
    static func exportBundle(entries: [BundleEntry], suggestedName: String,
                             completion: (() -> Void)? = nil,
                             share: ((URL) async -> Bool)? = nil) async -> URL? {
        guard !Task.isCancelled else { return nil }
        let base = suggestedName.hasSuffix(".zip") ? String(suggestedName.dropLast(4)) : suggestedName
        let stagingTask = Task.detached(priority: .userInitiated) {
            zipData(entries: entries, baseName: base)
        }
        guard let staged = await stagingTask.value else { completion?(); return nil }
        guard !Task.isCancelled else {
            removeStaged(staged)
            return nil
        }
        if let share { return await sharePrepared(staged, output: share) }
        #if os(macOS)
        let panel = NSSavePanel()
        panel.nameFieldStringValue = suggestedName
        panel.canCreateDirectories = true
        guard panel.runModal() == .OK, let dest = panel.url else {
            removeStaged(staged)
            return nil
        }
        let fm = FileManager.default
        do {
            if fm.fileExists(atPath: dest.path) { try fm.removeItem(at: dest) }
            try fm.copyItem(at: staged, to: dest)
        } catch {
            removeStaged(staged)
            return nil
        }
        removeStaged(staged)
        return dest
        #else
        return await sharePrepared(staged) { file in
            await ShareReceipt().wait { receive in
                present(activityItems: [file], cleanup: []) { completed in
                    receive(completed)
                    completion?()
                }
            }
        }
        #endif
    }
}
