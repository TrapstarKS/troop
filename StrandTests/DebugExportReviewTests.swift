import XCTest
import Combine
import StrandAnalytics
@testable import Strand

@MainActor
final class DebugExportReviewTests: XCTestCase {
    func testConfirmExportsTheReviewedSnapshotOnce() async throws {
        let review = DebugExportReview()
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        let line = "{\"console\":\"WHOOP 4C1594026 connected\"}\n"
        try Data(String(repeating: line, count: 40).utf8).write(to: file)
        let entries = DebugExportReview.pairEntries(file: file, text: "WHOOP 4C1594026", textName: "report.txt")
        var exported: [[FileExport.BundleEntry]] = []
        await review.stage(entries, destination: .bundle("test.zip"), capBytes: 256)
        let pending = try XCTUnwrap(review.pending)
        XCTAssertTrue(exported.isEmpty)
        XCTAssertTrue(pending.gate.previewText.contains("raw-capture.jsonl"))
        XCTAssertFalse(pending.gate.previewText.contains("4C1594026"))
        let snapshot = pending.gate.entries
        XCTAssertEqual(Set(snapshot.map(\.name)), ["report.txt", "raw-capture.jsonl"])
        XCTAssertLessThanOrEqual(snapshot.reduce(0) { $0 + $1.data.count }, 256)
        XCTAssertFalse(snapshot.contains { String(decoding: $0.data, as: UTF8.self).contains("4C1594026") })
        try Data("changed after review".utf8).write(to: file)
        await review.confirm(id: pending.id) { entries, _ in exported.append(entries) }
        await review.confirm(id: pending.id) { entries, _ in exported.append(entries) }
        XCTAssertEqual(exported, [snapshot])
    }

    func testCopyStagesTheRedactedBoundedText() async throws {
        let review = DebugExportReview()
        await review.stage([.init(name: "report.txt", data: Data(String(repeating: "WHOOP 4C1594026\n", count: 40).utf8))],
                           destination: .copy, capBytes: 128)
        let pending = try XCTUnwrap(review.pending)
        var copied: Data?
        await review.confirm(id: pending.id) { entries, destination in
            if case .copy = destination { copied = entries.first?.data }
        }
        XCTAssertEqual(copied, pending.gate.entries.first?.data)
        XCTAssertLessThanOrEqual(try XCTUnwrap(copied).count, 128)
        XCTAssertFalse(String(decoding: try XCTUnwrap(copied), as: UTF8.self).contains("4C1594026"))
    }

    func testCancelAndMissingGateCannotOutput() async throws {
        let review = DebugExportReview()
        var outputs = 0
        await review.confirm(id: UUID()) { _, _ in outputs += 1 }
        await review.stage([.init(name: "report.txt", data: Data("log".utf8))], destination: .copy)
        let id = try XCTUnwrap(review.pending?.id)
        review.cancel()
        await review.confirm(id: id) { _, _ in outputs += 1 }
        XCTAssertEqual(outputs, 0)
        XCTAssertNil(review.pending)
    }

    func testFileExportPairStagesActualRawAttachment() async throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file); DebugExportReview.shared.cancel() }
        try Data("{\"console\":\"WHOOP 4C1594026\"}\n".utf8).write(to: file)
        await FileExport.exportPair(file: file, fileSuggestedName: "capture.txt", text: "strap log", textSuggestedName: "report.txt")
        let pending = try XCTUnwrap(DebugExportReview.shared.pending)
        XCTAssertFalse(pending.gate.isCleared)
        let raw = try XCTUnwrap(pending.gate.entries.first { $0.name == "raw-capture.jsonl" })
        XCTAssertTrue(String(decoding: raw.data, as: UTF8.self).contains("WHOOP <serial>"))
        var output = [FileExport.BundleEntry]()
        await DebugExportReview.shared.confirm(id: pending.id) { entries, _ in output = entries }
        XCTAssertEqual(output, pending.gate.entries)
    }

    func testPairSourceReadCannotUndoCancellationOrReplaceNewerReview() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory); DebugExportReview.shared.cancel() }
        let oldFile = directory.appendingPathComponent("old.jsonl")
        let latestFile = directory.appendingPathComponent("latest.jsonl")
        try Data("old raw\n".utf8).write(to: oldFile)
        try Data("latest raw\n".utf8).write(to: latestFile)
        let entered = expectation(description: "old pair read entered")
        var resume: CheckedContinuation<Void, Never>?
        let old = Task {
            await FileExport.exportPair(file: oldFile, fileSuggestedName: "old", text: "old report", textSuggestedName: "report.txt", read: {
                await withCheckedContinuation { resume = $0; entered.fulfill() }
                return DebugExportReview.pairEntries(file: oldFile, text: "old report", textName: "report.txt")
            })
        }
        await fulfillment(of: [entered], timeout: 5)
        await FileExport.exportPair(file: latestFile, fileSuggestedName: "latest", text: "latest report", textSuggestedName: "report.txt")
        let latest = try XCTUnwrap(DebugExportReview.shared.pending)
        resume?.resume()
        await old.value
        XCTAssertEqual(DebugExportReview.shared.pending?.id, latest.id)
        var output = [FileExport.BundleEntry]()
        await DebugExportReview.shared.confirm(id: latest.id) { entries, _ in output = entries }
        XCTAssertEqual(output, latest.gate.entries)
        XCTAssertTrue(output.contains { String(decoding: $0.data, as: UTF8.self).contains("latest") })
        XCTAssertFalse(output.contains { String(decoding: $0.data, as: UTF8.self).contains("old") })

        let cancelledEntered = expectation(description: "cancelled pair read entered")
        var resumeCancelled: CheckedContinuation<Void, Never>?
        let cancelled = Task {
            await FileExport.exportPair(file: oldFile, fileSuggestedName: "old", text: "cancelled report", textSuggestedName: "report.txt", read: {
                await withCheckedContinuation { resumeCancelled = $0; cancelledEntered.fulfill() }
                return DebugExportReview.pairEntries(file: oldFile, text: "cancelled report", textName: "report.txt")
            })
        }
        await fulfillment(of: [cancelledEntered], timeout: 5)
        DebugExportReview.shared.cancel()
        resumeCancelled?.resume()
        await cancelled.value
        XCTAssertNil(DebugExportReview.shared.pending)
    }

    func testAlreadyCancelledProducerCannotClearNewerReview() async throws {
        let review = DebugExportReview.shared
        defer { review.cancel() }
        await review.stage([.init(name: "report.txt", data: Data("latest report".utf8))], destination: .copy)
        let latest = try XCTUnwrap(review.pending)
        let report = TestCentreReport()
        let live = LiveState()
        await report.start(mode: TestCentreView.masterReportMode, live: live) {
            [.init(name: "report.txt", data: Data("latest capture".utf8))]
        }.value
        let latestCapture = try XCTUnwrap(report.pending?.id)
        let entered = expectation(description: "producer waits before cancellation")
        var resume: CheckedContinuation<Void, Never>?
        var reads = 0
        let cancelled = Task {
            await withCheckedContinuation { resume = $0; entered.fulfill() }
            XCTAssertTrue(Task.isCancelled)
            let ticket = review.beginPreparation()
            let defaultStage = await review.stage([.init(name: "report.txt", data: Data("cancelled".utf8))], destination: .copy)
            let ticketStage = await review.stage([.init(name: "report.txt", data: Data("cancelled".utf8))], destination: .copy, ticket: ticket)
            XCTAssertFalse(defaultStage)
            XCTAssertFalse(ticketStage)
            FileExport.exportDebugText("cancelled", suggestedName: "cancelled.txt")
            FileExport.copyDebugText("cancelled")
            await FileExport.exportPair(file: URL(fileURLWithPath: "/unused-cancelled-source"), fileSuggestedName: "cancelled",
                                        text: "cancelled", textSuggestedName: "report.txt", read: {
                reads += 1
                return [.init(name: "report.txt", data: Data("cancelled".utf8))]
            })
            await report.start(mode: TestCentreView.masterReportMode, live: live) {
                reads += 1
                return [.init(name: "report.txt", data: Data("cancelled".utf8))]
            }.value
        }
        await fulfillment(of: [entered], timeout: 5)
        cancelled.cancel()
        resume?.resume()
        await cancelled.value
        await Task.yield()
        XCTAssertEqual(review.pending?.id, latest.id)
        XCTAssertEqual(report.pending?.id, latestCapture)
        XCTAssertEqual(reads, 0)
        var output = [FileExport.BundleEntry]()
        await review.confirm(id: latest.id) { entries, _ in output = entries }
        XCTAssertEqual(output, latest.gate.entries)
    }

    func testScheduledPrivateWriterScrubsActualCaptureWithoutReview() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let capture = directory.appendingPathComponent("source.jsonl")
        try Data("{\"console\":\"WHOOP 4C1594026\"}\n".utf8).write(to: capture)
        DebugExportReview.shared.cancel()
        XCTAssertNotNil(ScheduledDebugExport.runNow(captureURL: capture, directory: directory))
        let files = try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
        let raw = try XCTUnwrap(files.first { $0.lastPathComponent.hasPrefix("noop-raw-capture-") })
        let text = try String(contentsOf: raw, encoding: .utf8)
        XCTAssertFalse(text.contains("4C1594026"))
        XCTAssertTrue(text.contains("WHOOP <serial>"))
        XCTAssertNil(DebugExportReview.shared.pending)
        XCTAssertTrue(try String(contentsOf: capture, encoding: .utf8).contains("4C1594026"))
    }

    func testPresentationReplacesOnlyAfterDismissalAndRejectsStaleConfirm() async throws {
        let review = DebugExportReview()
        var shown = [UUID]()
        var confirms = [() -> Void]()
        var dismissals = [() -> Void]()
        var outputs = 0
        let presentation = DebugExportPresentation(review: review, present: { pending, _, confirm in
            shown.append(pending.id)
            confirms.append(confirm)
            return { finished in dismissals.append(finished) }
        }, confirm: { id in
            await review.confirm(id: id) { _, _ in outputs += 1 }
        })
        await review.stage([.init(name: "report.txt", data: Data("first".utf8))], destination: .copy)
        presentation.update()
        presentation.update()
        XCTAssertEqual(shown.count, 1)
        await review.stage([.init(name: "report.txt", data: Data("replacement".utf8))], destination: .copy)
        presentation.update()
        presentation.update()
        XCTAssertEqual(shown.count, 1)
        XCTAssertEqual(dismissals.count, 1)
        dismissals.removeFirst()()
        XCTAssertEqual(shown.count, 2)
        confirms[0]()
        XCTAssertEqual(outputs, 0)
        XCTAssertEqual(dismissals.count, 0)
        confirms[1]()
        XCTAssertEqual(dismissals.count, 1)
        review.cancel()
        presentation.update()
        dismissals.removeFirst()()
        await Task.yield()
        XCTAssertEqual(outputs, 0)
        XCTAssertEqual(shown.count, 2)
    }

    func testCancellingVisibleReviewPreventsQueuedReplacement() async {
        let review = DebugExportReview()
        var cancel: (() -> Void)?
        var finishDismissal: (() -> Void)?
        var presentations = 0
        let presentation = DebugExportPresentation(review: review, present: { _, action, _ in
            presentations += 1
            cancel = action
            return { finished in finishDismissal = finished }
        })
        await review.stage([.init(name: "report.txt", data: Data("first".utf8))], destination: .copy)
        presentation.update()
        await review.stage([.init(name: "report.txt", data: Data("replacement".utf8))], destination: .copy)
        presentation.update()
        cancel?()
        finishDismissal?()
        XCTAssertNil(review.pending)
        XCTAssertEqual(presentations, 1)
    }

    func testPresentationDismissesBeforeOneConfirmedOutput() async throws {
        let review = DebugExportReview()
        var confirm: (() -> Void)?
        var finishDismissal: (() -> Void)?
        let exported = expectation(description: "confirmed after dismissal")
        var outputs = 0
        let presentation = DebugExportPresentation(review: review, present: { _, _, action in
            confirm = action
            return { finished in finishDismissal = finished }
        }, confirm: { id in
            await review.confirm(id: id) { _, _ in outputs += 1; exported.fulfill() }
        })
        await review.stage([.init(name: "report.txt", data: Data("log".utf8))], destination: .copy)
        presentation.update()
        try XCTUnwrap(confirm)()
        try XCTUnwrap(confirm)()
        XCTAssertEqual(outputs, 0)
        try XCTUnwrap(finishDismissal)()
        await fulfillment(of: [exported], timeout: 5)
        XCTAssertEqual(outputs, 1)
        XCTAssertNil(review.pending)
    }

    func testCancellationDuringPreparationCannotResurfaceReview() async {
        let review = DebugExportReview()
        var cancelNextUpdate = true
        let token = review.$pending.dropFirst().sink { _ in
            if cancelNextUpdate {
                cancelNextUpdate = false
                review.cancel()
            }
        }
        await review.stage([.init(name: "report.txt", data: Data("log".utf8))], destination: .copy)
        XCTAssertNil(review.pending)
        withExtendedLifetime(token) { }
    }

    func testResearchSnapshotScrubsOnlyMetadataAndKeepsSensorBytes() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = RawDataSessionStore(directory: directory)
        let session = try XCTUnwrap(store.start(deviceId: "private-strap", now: Date(timeIntervalSince1970: 100)))
        _ = store.addMarker(sessionId: session.id, at: Date(timeIntervalSince1970: 102),
                            type: "issue", text: "WHOOP 4C1594026 Bearer secret.token")
        store.stop(now: Date(timeIntervalSince1970: 106))
        let sensor = Data("WHOOP 4C1594026,Bearer sensor-token,414141414141414141\n".utf8)
        let imu = Data([0, 255, 128]) + sensor
        var entries = store.exportEntries(for: try XCTUnwrap(store.sessions.first))
        let eventIndex = try XCTUnwrap(entries.firstIndex { $0.name == "events.jsonl" })
        entries[eventIndex] = .init(name: "events.jsonl", data: entries[eventIndex].data
            + Data("{\"strap_device_id\":\"private-other\",\"text\":\"Bearer\\tjson-secret\"}\n".utf8))
        entries += [
            FileExport.BundleEntry(name: "history-sensors.csv", data: sensor),
            FileExport.BundleEntry(name: "imu/frame.bin", data: imu),
            FileExport.BundleEntry(name: "events.csv", data: Data("text\nBearer csv-secret\n".utf8)),
        ]
        let review = DebugExportReview()
        var output = [FileExport.BundleEntry]()
        let staged = await review.stageResearch(entries, suggestedName: "research.zip") { entries, _ in output = entries }
        XCTAssertTrue(staged)
        let cancelledID = try XCTUnwrap(review.pending?.id)
        review.cancel()
        await review.confirm(id: cancelledID)
        XCTAssertTrue(output.isEmpty)
        await review.stageResearch(entries, suggestedName: "research.zip") { entries, _ in output = entries }
        let pending = try XCTUnwrap(review.pending)
        XCTAssertEqual(pending.gate.entries.first { $0.name == "history-sensors.csv" }?.data, sensor)
        XCTAssertEqual(pending.gate.entries.first { $0.name == "imu/frame.bin" }?.data, imu)
        XCTAssertTrue(pending.gate.previewText.contains("imu/frame.bin"))
        XCTAssertFalse(pending.gate.previewText.contains("=== imu/frame.bin ==="))
        let metadata = pending.gate.entries.filter { ["meta.json", "events.jsonl", "events.csv"].contains($0.name) }
        for entry in metadata {
            let text = String(decoding: entry.data, as: UTF8.self)
            XCTAssertFalse(text.contains("private-strap"))
            XCTAssertFalse(text.contains("private-other"))
            XCTAssertFalse(text.contains("secret.token"))
            XCTAssertFalse(text.contains("csv-secret"))
            XCTAssertFalse(text.contains("json-secret"))
            XCTAssertFalse(text.contains("4C1594026"))
        }
        await review.confirm(id: pending.id)
        XCTAssertEqual(output, pending.gate.entries)
    }

    func testOversizedFixedAndResearchEntriesFailClosed() async throws {
        let entries = [FileExport.BundleEntry(name: "imu/frame.bin", data: Data(repeating: 0, count: 1025))]
        XCTAssertThrowsError(try DebugExportReview.prepare(entries, capBytes: 1024))
        XCTAssertThrowsError(try DebugExportReview.prepareResearch(entries, capBytes: 1024))
        let review = DebugExportReview()
        var outputs = 0
        let staged = await review.stageResearch(entries, suggestedName: "too-large.zip", capBytes: 1024) { _, _ in outputs += 1 }
        XCTAssertFalse(staged)
        XCTAssertNil(review.pending)
        await review.confirm(id: UUID())
        XCTAssertEqual(outputs, 0)
    }

    func testBearerRedactionMatchesStandaloneOracle() {
        let cases = [
            "Authorization: Bearer secret.token", "bearer\tabc",
            #"{"text":"Bearer\tjson-secret"}"#, #"{"text":"Bearer\njson-secret"}"#,
            "not a token", "Bearer a+/b==", "prefixBearer secret", "Bearer \nabc", "BEARER s3cr3t",
            "Bearer\u{00A0}secret", "Bearer\u{0085}secret", "Bearer\u{2007}secret",
            "Bearer Ksecret", "Bearer ſsecret", "Bearer aKb", "Bearer aſb", "Bearer βeta",
        ]
        let actual = cases.enumerated().map { index, text in
            "\(index):" + Data(DebugExportReview.redactBearer(text).utf8).base64EncodedString()
        }.joined(separator: "\n")
        let expected = """
        0:QXV0aG9yaXphdGlvbjogQmVhcmVyIDxyZWRhY3RlZD4=
        1:QmVhcmVyIDxyZWRhY3RlZD4=
        2:eyJ0ZXh0IjoiQmVhcmVyIDxyZWRhY3RlZD4ifQ==
        3:eyJ0ZXh0IjoiQmVhcmVyIDxyZWRhY3RlZD4ifQ==
        4:bm90IGEgdG9rZW4=
        5:QmVhcmVyIDxyZWRhY3RlZD4=
        6:cHJlZml4QmVhcmVyIDxyZWRhY3RlZD4=
        7:QmVhcmVyIDxyZWRhY3RlZD4=
        8:QmVhcmVyIDxyZWRhY3RlZD4=
        9:QmVhcmVyIDxyZWRhY3RlZD4=
        10:QmVhcmVyIDxyZWRhY3RlZD4=
        11:QmVhcmVyIDxyZWRhY3RlZD4=
        12:QmVhcmVyIOKEqnNlY3JldA==
        13:QmVhcmVyIMW/c2VjcmV0
        14:QmVhcmVyIDxyZWRhY3RlZD7ihKpi
        15:QmVhcmVyIDxyZWRhY3RlZD7Fv2I=
        16:QmVhcmVyIM6yZXRh
        """
        XCTAssertEqual(actual, expected)
    }

    func testReportStartOverlapAndCancelCannotPublishOldCapture() async throws {
        let report = TestCentreReport()
        let live = LiveState()
        let mode = TestCentreView.masterReportMode
        let firstEntered = expectation(description: "first gather entered")
        var resumeFirst: CheckedContinuation<[FileExport.BundleEntry], Never>?
        let first = report.start(mode: mode, live: live) {
            await withCheckedContinuation { continuation in
                resumeFirst = continuation
                firstEntered.fulfill()
            }
        }
        await fulfillment(of: [firstEntered], timeout: 5)
        let latest = report.start(mode: mode, live: live) {
            [.init(name: "report.txt", data: Data("latest".utf8))]
        }
        await latest.value
        let current = try XCTUnwrap(report.pending)
        resumeFirst?.resume(returning: [.init(name: "report.txt", data: Data("old".utf8))])
        await first.value
        XCTAssertEqual(report.pending?.id, current.id)
        XCTAssertTrue(report.pending?.gate.previewText.contains("latest") == true)

        let cancelledEntered = expectation(description: "cancelled gather entered")
        var resumeCancelled: CheckedContinuation<[FileExport.BundleEntry], Never>?
        let cancelled = report.start(mode: mode, live: live) {
            await withCheckedContinuation { continuation in
                resumeCancelled = continuation
                cancelledEntered.fulfill()
            }
        }
        await fulfillment(of: [cancelledEntered], timeout: 5)
        report.cancel()
        resumeCancelled?.resume(returning: [.init(name: "report.txt", data: Data("cancelled".utf8))])
        await cancelled.value
        XCTAssertNil(report.pending)
    }

    func testReportOutputWaitsForDismissalAndRunsOnce() async throws {
        let report = TestCentreReport()
        let start = report.start(mode: TestCentreView.masterReportMode, live: LiveState()) {
            [.init(name: "report.txt", data: Data("WHOOP 4C1594026".utf8))]
        }
        await start.value
        let snapshot = try XCTUnwrap(report.pending?.gate.entries)
        var outputs = [[FileExport.BundleEntry]]()
        report.confirm()
        report.confirm()
        XCTAssertTrue(outputs.isEmpty)
        XCTAssertNil(report.pending)
        let share = report.reviewDismissed { pending in
            XCTAssertTrue(pending.gate.isCleared)
            outputs.append(pending.gate.entries)
        }
        await share?.value
        XCTAssertEqual(outputs, [snapshot])
        XCTAssertNil(report.reviewDismissed { pending in outputs.append(pending.gate.entries) })
    }

    func testPrivatePreparationNeedsNoReviewAndPreservesOuraHex() throws {
        let hex = "414141414141414141414141"
        let entries = try DebugExportReview.prepare([
            .init(name: "report.txt", data: Data("WHOOP 4C1594026".utf8)),
            .init(name: "oura-raw.jsonl", data: Data("{\"deviceId\":\"private-ring\",\"hex\":\"\(hex)\"}\n".utf8)),
        ])
        XCTAssertTrue(String(decoding: entries[0].data, as: UTF8.self).contains("WHOOP <serial>"))
        let raw = String(decoding: entries[1].data, as: UTF8.self)
        XCTAssertTrue(raw.contains(hex))
        XCTAssertFalse(raw.contains("private-ring"))
    }
}
