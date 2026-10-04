import XCTest
@testable import WhoopStore

final class WorkoutCopyIdentityTests: XCTestCase {
    func testStandaloneSwiftOraclePinsNaturalKeysAndProvenance() {
        var lines: [String] = []
        for mask in 0..<32 {
            let names = (1...5).map { $0 == 1 ? "Cycling (manual copy)" : "Cycling (manual copy \($0))" }
            let occupied = names.enumerated().compactMap { mask & (1 << $0.offset) != 0 ? $0.element : nil }
            lines.append(WorkoutCopyIdentity.sport("Cycling", occupied: occupied))
        }
        for (base, occupied) in [("Café", ["Cafe\u{301} (manual copy)"]),
                                 ("Cafe\u{301}", ["Café (manual copy)"]),
                                 ("跑步", ["跑步 (manual copy)"]),
                                 ("", []), ("Run (manual copy)", [])] {
            lines.append(WorkoutCopyIdentity.sport(base, occupied: occupied))
        }
        let output = lines.map { $0.utf8.map { String(format: "%02x", $0) }.joined() }.joined(separator: "|") + "\n" +
            ["manual-copy", "MANUAL-COPY", "Manual-Copy", "manual", " manual-copy", "manual-copy ", "whoop", ""].map {
                WorkoutCopyIdentity.isCopy($0) ? "1" : "0"
            }.joined(separator: "|")
        XCTAssertEqual(output, """
        4379636c696e6720286d616e75616c20636f707929|4379636c696e6720286d616e75616c20636f7079203229|4379636c696e6720286d616e75616c20636f707929|4379636c696e6720286d616e75616c20636f7079203329|4379636c696e6720286d616e75616c20636f707929|4379636c696e6720286d616e75616c20636f7079203229|4379636c696e6720286d616e75616c20636f707929|4379636c696e6720286d616e75616c20636f7079203429|4379636c696e6720286d616e75616c20636f707929|4379636c696e6720286d616e75616c20636f7079203229|4379636c696e6720286d616e75616c20636f707929|4379636c696e6720286d616e75616c20636f7079203329|4379636c696e6720286d616e75616c20636f707929|4379636c696e6720286d616e75616c20636f7079203229|4379636c696e6720286d616e75616c20636f707929|4379636c696e6720286d616e75616c20636f7079203529|4379636c696e6720286d616e75616c20636f707929|4379636c696e6720286d616e75616c20636f7079203229|4379636c696e6720286d616e75616c20636f707929|4379636c696e6720286d616e75616c20636f7079203329|4379636c696e6720286d616e75616c20636f707929|4379636c696e6720286d616e75616c20636f7079203229|4379636c696e6720286d616e75616c20636f707929|4379636c696e6720286d616e75616c20636f7079203429|4379636c696e6720286d616e75616c20636f707929|4379636c696e6720286d616e75616c20636f7079203229|4379636c696e6720286d616e75616c20636f707929|4379636c696e6720286d616e75616c20636f7079203329|4379636c696e6720286d616e75616c20636f707929|4379636c696e6720286d616e75616c20636f7079203229|4379636c696e6720286d616e75616c20636f707929|4379636c696e6720286d616e75616c20636f7079203629|436166c3a920286d616e75616c20636f707929|43616665cc8120286d616e75616c20636f707929|e8b791e6ada520286d616e75616c20636f7079203229|20286d616e75616c20636f707929|52756e20286d616e75616c20636f70792920286d616e75616c20636f707929
        1|1|1|0|0|0|0|0
        """)
    }

    func testRepeatedCopiesPreserveEveryOriginalFieldAndTimestamp() async throws {
        let store = try await WhoopStore.inMemory()
        let original = WorkoutRow(startTs: 1_000, endTs: 4_009, sport: "Cycling", source: "whoop",
            durationS: 3009.25, energyKcal: 296.8, avgHr: 139, maxHr: 167, strain: 52.25,
            distanceM: 8723.6, zonesJSON: "{\"z1\":7.3,\"z2\":28.3}", notes: "Original import", steps: 1_234)
        try await store.upsertWorkouts([original], deviceId: "my-whoop")
        let first = try await store.insertManualWorkoutCopy(original, deviceId: "my-whoop")
        let second = try await store.insertManualWorkoutCopy(original, deviceId: "my-whoop")
        let rows = try await store.workouts(deviceId: "my-whoop", from: 0, to: 10_000, limit: 100)
        XCTAssertEqual(rows.count, 3)
        XCTAssertEqual(rows.first { $0.sport == original.sport }, original)
        XCTAssertEqual(first.sport, "Cycling (manual copy)")
        XCTAssertEqual(second.sport, "Cycling (manual copy 2)")
        for copy in [first, second] {
            XCTAssertEqual(copy, WorkoutRow(startTs: original.startTs, endTs: original.endTs,
                sport: copy.sport, source: WorkoutCopyIdentity.source, durationS: original.durationS,
                energyKcal: original.energyKcal, avgHr: original.avgHr, maxHr: original.maxHr,
                strain: original.strain, distanceM: original.distanceM, zonesJSON: original.zonesJSON,
                notes: original.notes, steps: original.steps))
        }
    }

    func testMovedCopyCollisionPreservesOriginalAndExistingCopies() async throws {
        let store = try await WhoopStore.inMemory()
        let original = WorkoutRow(startTs: 1_000, endTs: 2_000, sport: "Cycling", source: "whoop",
            durationS: 1000.25, energyKcal: 296.8, avgHr: 139, maxHr: 167, strain: 52.25,
            distanceM: 8723.6, zonesJSON: nil, notes: "Original import", steps: 1_234)
        try await store.upsertWorkouts([original], deviceId: "my-whoop")
        let first = try await store.insertManualWorkoutCopy(original, deviceId: "my-whoop")
        _ = try await store.insertManualWorkoutCopy(original, deviceId: "my-whoop")
        let before = try await store.workouts(deviceId: "my-whoop", from: 0, to: 10_000, limit: 100)
        for target in [original.sport, "Cycling (manual copy 2)"] {
            let moved = WorkoutRow(startTs: first.startTs, endTs: first.endTs, sport: target,
                source: WorkoutCopyIdentity.source, durationS: first.durationS, energyKcal: 999,
                avgHr: first.avgHr, maxHr: first.maxHr, strain: first.strain, distanceM: first.distanceM,
                zonesJSON: first.zonesJSON, notes: "Edited copy", steps: first.steps)
            do {
                try await store.insertWorkout(moved, deviceId: "my-whoop")
                XCTFail("A moved copy cannot replace another activity")
            } catch { }
            let after = try await store.workouts(deviceId: "my-whoop", from: 0, to: 10_000, limit: 100)
            XCTAssertEqual(after, before)
        }
    }

    func testMovedKeysMatchStandaloneSwiftOracle() {
        let pairs = [("", ""), ("Running", "Running"), ("Running", "Cycling"),
                     ("Café", "Cafe\u{301}"), ("跑步", "跑步"), ("Cycling (manual copy)", "Cycling")]
        let output = [1_000, 1_001].flatMap { start in
            pairs.map { WorkoutCopyIdentity.keyMoved(oldStart: 1_000, oldSport: $0.0,
                newStart: start, newSport: $0.1) ? "1" : "0" }
        }.joined(separator: "|")
        XCTAssertEqual(output, "0|0|1|1|0|1|1|1|1|1|1|1")
    }

    func testConcurrentCopiesCannotClobberOriginalOrAnotherCopy() async throws {
        let store = try await WhoopStore.inMemory()
        let original = WorkoutRow(startTs: 1_000, endTs: 2_000, sport: "Running", source: "whoop",
            durationS: nil, energyKcal: nil, avgHr: nil, maxHr: nil, strain: nil,
            distanceM: nil, zonesJSON: nil, notes: nil, steps: nil)
        try await store.upsertWorkouts([original], deviceId: "my-whoop")
        try await withThrowingTaskGroup(of: Void.self) { group in
            for _ in 0..<12 {
                group.addTask { _ = try await store.insertManualWorkoutCopy(original, deviceId: "my-whoop") }
            }
            try await group.waitForAll()
        }
        let rows = try await store.workouts(deviceId: "my-whoop", from: 0, to: 10_000, limit: 100)
        XCTAssertEqual(rows.count, 13)
        XCTAssertEqual(Set(rows.map(\.sport)).count, 13)
        XCTAssertEqual(rows.first { $0.sport == original.sport }, original)
    }
}
