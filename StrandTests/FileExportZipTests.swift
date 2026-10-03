import XCTest
import ZIPFoundation
@testable import Strand

final class FileExportZipTests: XCTestCase {

    func testZipDataRoundTripsTwoEntries() throws {
        let entries = [
            FileExport.BundleEntry(name: "report.txt", data: Data("hello report".utf8)),
            FileExport.BundleEntry(name: "meta.json", data: Data("{\"schema\":1}".utf8)),
        ]
        let zipURL = try XCTUnwrap(FileExport.zipData(entries: entries, baseName: "test-bundle"))
        addTeardownBlock { try? FileManager.default.removeItem(at: zipURL.deletingLastPathComponent()) }

        // The file exists and is non-empty.
        XCTAssertTrue(FileManager.default.fileExists(atPath: zipURL.path))
        let size = (try FileManager.default.attributesOfItem(atPath: zipURL.path)[.size] as? Int) ?? 0
        XCTAssertGreaterThan(size, 0)

        // Read it back: both entries present with the right bytes.
        let archive = try Archive(url: zipURL, accessMode: .read)
        let report = try XCTUnwrap(archive["report.txt"])
        var out = Data()
        _ = try archive.extract(report) { out.append($0) }
        XCTAssertEqual(String(data: out, encoding: .utf8), "hello report")
        XCTAssertNotNil(archive["meta.json"])
    }

    func testZipDataEmptyEntriesReturnsNil() {
        XCTAssertNil(FileExport.zipData(entries: [], baseName: "empty"))
    }

    func testEntryWriteFailureRejectsAndDeletesPartialArchive() throws {
        enum Failure: Error { case disk }
        let name = "failed-" + UUID().uuidString
        var attempted: URL?
        var writes = 0
        let result = FileExport.zipData(entries: [
            .init(name: "first.txt", data: Data("first".utf8)),
            .init(name: "second.txt", data: Data("second".utf8)),
        ], baseName: name) { archive, entry in
            attempted = archive.url
            writes += 1
            if writes == 2 { throw Failure.disk }
            try archive.addEntry(with: entry.name, type: .file, uncompressedSize: Int64(entry.data.count)) { position, size in
                entry.data.subdata(in: Int(position)..<min(Int(position) + size, entry.data.count))
            }
        }
        XCTAssertEqual(writes, 2)
        XCTAssertNil(result)
        let url = try XCTUnwrap(attempted)
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.deletingLastPathComponent().path))
    }

    func testSameSuggestedNameKeepsEachReviewedArchiveIndependent() throws {
        let name = "same-" + UUID().uuidString
        let first = try XCTUnwrap(FileExport.zipData(entries: [.init(name: "report.txt", data: Data("first".utf8))], baseName: name))
        let second = try XCTUnwrap(FileExport.zipData(entries: [.init(name: "report.txt", data: Data("second".utf8))], baseName: name))
        defer {
            try? FileManager.default.removeItem(at: first.deletingLastPathComponent())
            try? FileManager.default.removeItem(at: second.deletingLastPathComponent())
        }
        XCTAssertNotEqual(first, second)
        XCTAssertEqual(first.lastPathComponent, second.lastPathComponent)
        for (file, expected) in [(first, "first"), (second, "second")] {
            let archive = try Archive(url: file, accessMode: .read)
            let entry = try XCTUnwrap(archive["report.txt"])
            var data = Data()
            _ = try archive.extract(entry) { data.append($0) }
            XCTAssertEqual(String(decoding: data, as: UTF8.self), expected)
        }
    }

    func testSameSuggestedNameKeepsEachTextShareIndependent() throws {
        let name = UUID().uuidString + ".txt"
        let first = try XCTUnwrap(FileExport.stageText("first", suggestedName: name))
        let second = try XCTUnwrap(FileExport.stageText("second", suggestedName: name))
        defer {
            try? FileManager.default.removeItem(at: first.deletingLastPathComponent())
            try? FileManager.default.removeItem(at: second.deletingLastPathComponent())
        }
        XCTAssertNotEqual(first, second)
        XCTAssertEqual(first.lastPathComponent, second.lastPathComponent)
        XCTAssertEqual(try String(contentsOf: first, encoding: .utf8), "first")
        XCTAssertEqual(try String(contentsOf: second, encoding: .utf8), "second")
    }

    @MainActor
    func testRejectedShareReturnsNilAndDeletesStagedArchive() async throws {
        var staged: URL?
        let result = await FileExport.exportBundle(entries: [.init(name: "report.txt", data: Data("x".utf8))],
            suggestedName: UUID().uuidString + ".zip", share: { file in
                staged = file
                XCTAssertTrue(FileManager.default.fileExists(atPath: file.path))
                return false
            })
        XCTAssertNil(result)
        XCTAssertFalse(FileManager.default.fileExists(atPath: try XCTUnwrap(staged).path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: try XCTUnwrap(staged).deletingLastPathComponent().path))
    }

    @MainActor
    func testCompletedShareReturnsReceiptAndDeletesStagedArchive() async throws {
        var staged: URL?
        let result = await FileExport.exportBundle(entries: [.init(name: "report.txt", data: Data("x".utf8))],
            suggestedName: UUID().uuidString + ".zip", share: { file in staged = file; return true })
        XCTAssertEqual(result, staged)
        XCTAssertNotNil(result)
        XCTAssertFalse(FileManager.default.fileExists(atPath: try XCTUnwrap(staged).path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: try XCTUnwrap(staged).deletingLastPathComponent().path))
    }

    @MainActor
    func testCancellationAfterShareCannotReturnSuccessOrKeepStagedFile() async throws {
        let file = NoopScratch.file(UUID().uuidString)
        try Data("x".utf8).write(to: file)
        let task = Task { await FileExport.sharePrepared(file) { _ in
            withUnsafeCurrentTask { $0?.cancel() }
            return true
        } }
        let result = await task.value
        XCTAssertNil(result)
        XCTAssertFalse(FileManager.default.fileExists(atPath: file.path))
    }

    @MainActor
    func testCancellationResolvesShareAndIgnoresLateActivityCompletion() async throws {
        let file = try XCTUnwrap(FileExport.stageText("reviewed", suggestedName: UUID().uuidString + ".txt"))
        let receipt = FileExport.ShareReceipt()
        let started = expectation(description: "presented"), finished = expectation(description: "cancelled")
        var callback: (@MainActor (Bool) -> Void)?
        var dismissals = 0
        let task = Task {
            let result = await FileExport.sharePrepared(file) { _ in
                await receipt.wait { completion in
                    callback = completion
                    started.fulfill()
                    return { dismissals += 1 }
                }
            }
            XCTAssertNil(result)
            finished.fulfill()
        }
        await fulfillment(of: [started], timeout: 5)
        task.cancel()
        await fulfillment(of: [finished], timeout: 5)
        await task.value
        callback?(true)
        callback?(false)
        receipt.cancel()
        XCTAssertEqual(dismissals, 1)
        XCTAssertFalse(FileManager.default.fileExists(atPath: file.deletingLastPathComponent().path))
    }

    @MainActor
    func testUnavailablePresenterResolvesReceiptWithoutWaiting() async {
        let receipt = FileExport.ShareReceipt()
        let completed = await receipt.wait { _ in nil }
        XCTAssertFalse(completed)
    }

    @MainActor
    func testLifecycleCancellationResolvesOnceAndCannotBecomeSuccess() async {
        let receipt = FileExport.ShareReceipt()
        var callback: (@MainActor (Bool) -> Void)?
        let started = expectation(description: "presented")
        let task = Task { await receipt.wait { completion in
            callback = completion
            started.fulfill()
            return {}
        } }
        await fulfillment(of: [started], timeout: 5)
        callback?(false)
        callback?(true)
        let completed = await task.value
        XCTAssertFalse(completed)
    }
}
