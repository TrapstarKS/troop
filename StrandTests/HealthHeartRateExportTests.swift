import Foundation
import XCTest
import WhoopProtocol
import WhoopStore
@testable import Strand

final class HealthHeartRateExportTests: XCTestCase {
    @MainActor
    func testExportUnionIncludesArchivedActiveAndCanonicalWithActiveOverlapPriority() async throws {
        let store = try await WhoopStore.inMemory()
        let registry = DeviceRegistryStore(dbQueue: store.registryWriter)
        try registry.add(PairedDevice(id: "whoop-a", brand: "WHOOP", model: "5.0",
            sourceKind: .liveBLE, capabilities: [.hr], status: .archived, addedAt: 1, lastSeenAt: 1))
        try registry.add(PairedDevice(id: "whoop-b", brand: "WHOOP", model: "5.0",
            sourceKind: .liveBLE, capabilities: [.hr], status: .active, addedAt: 2, lastSeenAt: 2))
        for (source, samples) in [
            ("whoop-a", [HRSample(ts: 120, bpm: 61), HRSample(ts: 180, bpm: 62)]),
            ("whoop-b", [HRSample(ts: 180, bpm: 72), HRSample(ts: 240, bpm: 73)]),
            ("my-whoop", [HRSample(ts: 60, bpm: 51), HRSample(ts: 180, bpm: 52)])
        ] {
            _ = try await store.insert(Streams(hr: samples), deviceId: source)
        }
        let repo = Repository(deviceId: "whoop-b")
        repo.setStoreForTesting(store)
        let buckets = await repo.hrBuckets(from: 0, to: 300, bucketSeconds: 60)
        XCTAssertEqual(buckets.map(\.ts), [60, 120, 180, 240])
        XCTAssertEqual(buckets.map(\.bpm), [51, 61, 72, 73])
        let frontier = await repo.hrFingerprint(from: 0, to: 300)
        XCTAssertEqual(frontier?.maxTs, 240, "The open-night gate must see the writer's re-paired HR frontier")
    }

    func testIOSWriterAndOpenNightGateUseTheRepositoryUnion() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let source = try String(contentsOf: root.appendingPathComponent("StrandiOS/Health/HealthKitBridge.swift"))
        let writer = try XCTUnwrap(source.range(of: "private func writeHeartRate("))
        let writerBody = String(source[writer.lowerBound...].prefix(4_000))
        XCTAssertTrue(writerBody.contains("repo.hrBuckets(from:"))
        XCTAssertFalse(writerBody.contains("whoopStore.hrBuckets(deviceId:"))
        XCTAssertTrue(source.contains("repo.hrFingerprint(from: nowTs - 2 * 86_400"))
        let resume = try XCTUnwrap(source.range(of: "func refreshAuthIfPreviouslyGranted()"))
        let end = try XCTUnwrap(source.range(of: "// MARK: - Live delivery", range: resume.upperBound..<source.endIndex))
        XCTAssertFalse(source[resume.lowerBound..<end.lowerBound].contains("store.requestAuthorization("),
                       "Foreground sync must own permission refresh; background grant checks cannot prompt")
    }
}
