import XCTest
import WhoopStore
@testable import Strand

final class BLEStartupGateTests: XCTestCase {
    @MainActor
    func testWatchOnlySourcePreservesAuthorizedRestorationAndGenericBLEIsDenied() async {
        func row(_ id: String, brand: String, sourceKind: SourceKind) -> PairedDevice {
            PairedDevice(id: id, brand: brand, model: brand, sourceKind: sourceKind,
                         capabilities: [.hr], status: .active, addedAt: 1, lastSeenAt: 1)
        }
        let watch = row("apple-health", brand: "Apple", sourceKind: .liveAppleWatch)
        let whoop = row("my-whoop", brand: "WHOOP", sourceKind: .liveBLE)
        let strap = row("belt", brand: "Polar", sourceKind: .liveBLE)
        XCTAssertTrue(BLEStartupGate.allowsWhoopBLE(for: watch))
        XCTAssertTrue(BLEStartupGate.allowsWhoopBLE(for: whoop))
        XCTAssertTrue(BLEStartupGate.allowsWhoopBLE(for: nil))
        XCTAssertFalse(BLEStartupGate.allowsWhoopBLE(for: strap))
        let gate = BLEStartupGate()
        let token = gate.beginRestoration(identifier: "whoop-peripheral")
        var discoveries = 0
        var releases = 0
        await gate.resume(prepare: { true }, isAllowed: { BLEStartupGate.allowsWhoopBLE(for: watch) },
                          onDenied: { releases += 1 }) {
            if gate.claimRestoration(.discover, token: token) { discoveries += 1 }
        }
        XCTAssertEqual(discoveries, 1)
        XCTAssertEqual(releases, 0)
        await gate.resume(prepare: { true }, isAllowed: { BLEStartupGate.allowsWhoopBLE(for: strap) },
                          onDenied: { releases += 1 }) { discoveries += 1 }
        XCTAssertEqual(discoveries, 1)
        XCTAssertEqual(releases, 1)
    }

    @MainActor
    func testInactiveDeviceSeedPrecedesBothStartupCallbacks() async {
        let gate = BLEStartupGate()
        var releaseStore: CheckedContinuation<Void, Never>?
        var opens = 0
        var allowed = true // The launch default, before the persisted ring selection is read.
        var gateReads = 0
        var actions: [String] = []
        let prepare: @MainActor () async -> Bool = {
            opens += 1
            await withCheckedContinuation { releaseStore = $0 }
            allowed = false
            return true
        }
        let check: @MainActor () -> Bool = { gateReads += 1; return allowed }
        let poweredOn = Task { @MainActor in
            await gate.resume(prepare: prepare, isAllowed: check) { actions.append("connect") }
        }
        while releaseStore == nil { await Task.yield() }
        var secondEntered = false
        let restored = Task { @MainActor in
            secondEntered = true
            await gate.resume(prepare: prepare, isAllowed: check) { actions.append("discover") }
        }
        // Let the second callback enter the shared preparation while the first is suspended.
        while !secondEntered { await Task.yield() }
        XCTAssertEqual(opens, 1)
        XCTAssertEqual(gateReads, 0)
        XCTAssertTrue(actions.isEmpty)
        releaseStore?.resume()
        await poweredOn.value
        await restored.value
        XCTAssertEqual(opens, 1)
        XCTAssertEqual(gateReads, 2)
        XCTAssertTrue(actions.isEmpty, "An inactive WHOOP must neither reconnect nor discover services")
    }

    @MainActor
    func testSelectedWhoopResumesAfterStoreIsReady() async {
        let gate = BLEStartupGate()
        var order: [String] = []
        await gate.resume(prepare: { order.append("store"); return true }, isAllowed: {
            order.append("gate")
            return true
        }) { order.append("discover") }
        XCTAssertEqual(order, ["store", "gate", "discover"])
    }

    @MainActor
    func testFailedStoreDoesNotConsultDefaultGateAndCanRetryOnUnlock() async {
        let gate = BLEStartupGate()
        var reads = 0
        var actions = 0
        await gate.resume(prepare: { false }, isAllowed: { reads += 1; return true }) { actions += 1 }
        XCTAssertEqual(reads, 0)
        XCTAssertEqual(actions, 0)
        await gate.resume(prepare: { true }, isAllowed: { reads += 1; return true }) { actions += 1 }
        XCTAssertEqual(reads, 1)
        XCTAssertEqual(actions, 1)
    }

    @MainActor
    func testRadioOrDeviceChangeDuringStoreOpenIsCheckedAfterAwait() async {
        let gate = BLEStartupGate()
        var releaseStore: CheckedContinuation<Void, Never>?
        var allowed = true
        var actions = 0
        let startup = Task { @MainActor in
            await gate.resume(prepare: {
                await withCheckedContinuation { releaseStore = $0 }
                return true
            }, isAllowed: { allowed }) { actions += 1 }
        }
        while releaseStore == nil { await Task.yield() }
        allowed = false
        releaseStore?.resume()
        await startup.value
        XCTAssertEqual(actions, 0)
    }
    @MainActor
    func testRestoredConnectAndDiscoveryAreEachClaimedOnce() {
        let gate = BLEStartupGate()
        let token = gate.beginRestoration(identifier: "strap-a")
        XCTAssertTrue(gate.claimRestoration(.connect, token: token))
        XCTAssertFalse(gate.claimRestoration(.connect, token: token))
        XCTAssertTrue(gate.claimRestoration(.discover, token: token))
        XCTAssertFalse(gate.claimRestoration(.discover, token: token))
    }

    @MainActor
    func testSupersededRestorationCannotClaimAnActionEvenForTheSameUUID() {
        let gate = BLEStartupGate()
        let old = gate.beginRestoration(identifier: "strap-a")
        let replacement = gate.beginRestoration(identifier: "strap-a")
        XCTAssertFalse(gate.claimRestoration(.connect, token: old))
        XCTAssertFalse(gate.claimRestoration(.discover, token: old))
        XCTAssertTrue(gate.claimRestoration(.discover, token: replacement))
        let other = gate.beginRestoration(identifier: "strap-b")
        XCTAssertFalse(gate.claimRestoration(.discover, token: replacement))
        XCTAssertTrue(gate.claimRestoration(.discover, token: other))
    }

    @MainActor
    func testFailedPreparationDoesNotReleaseRestorationButKnownDenialCan() async {
        let gate = BLEStartupGate()
        var releases = 0
        var actions = 0
        await gate.resume(prepare: { false }, isAllowed: { false },
                          onDenied: { releases += 1 }) { actions += 1 }
        XCTAssertEqual(releases, 0)
        XCTAssertEqual(actions, 0)
        await gate.resume(prepare: { true }, isAllowed: { false },
                          onDenied: { releases += 1 }) { actions += 1 }
        XCTAssertEqual(releases, 1)
        XCTAssertEqual(actions, 0)
    }

    @MainActor
    func testChangedRestorationWhilePreparingCannotDiscoverTheOldPeripheral() async {
        let gate = BLEStartupGate()
        var releaseStore: CheckedContinuation<Void, Never>?
        let old = gate.beginRestoration(identifier: "strap-a")
        var current = old
        var discoveries = 0
        let pending = Task { @MainActor in
            await gate.resume(prepare: {
                await withCheckedContinuation { releaseStore = $0 }
                return true
            }, isAllowed: { old == current }) {
                if gate.claimRestoration(.discover, token: old) { discoveries += 1 }
            }
        }
        while releaseStore == nil { await Task.yield() }
        current = gate.beginRestoration(identifier: "strap-b")
        releaseStore?.resume()
        await pending.value
        XCTAssertEqual(discoveries, 0)
        XCTAssertTrue(gate.claimRestoration(.discover, token: current))
    }

    @MainActor
    func testPreferredWhoopSwitchRejectsPendingRestorationWithOrWithoutInvalidation() async {
        let actions: [BLEStartupGate.RestorationAction] = [.connect, .discover]
        for invalidates in [false, true] {
            for action in actions {
                let gate = BLEStartupGate()
                let restoredA = gate.beginRestoration(identifier: "strap-a")
                var preferred = "strap-a"
                var intentionalDisconnect = false
                let allowsWhoop = true
                var releaseStore: CheckedContinuation<Void, Never>?
                var boundaries = 0
                var adoptions = 0
                var connects = 0
                var discoveries = 0
                let pending = Task { @MainActor in
                    await gate.resume(prepare: {
                        await withCheckedContinuation { releaseStore = $0 }
                        return true
                    }, isAllowed: { allowsWhoop && !intentionalDisconnect }) {
                        boundaries += 1
                        guard gate.claimRestoration(action, token: restoredA,
                            preferredIdentifier: preferred) else { return }
                        adoptions += 1
                        switch action {
                        case .connect: connects += 1
                        case .discover: discoveries += 1
                        }
                    }
                }
                while releaseStore == nil { await Task.yield() }
                intentionalDisconnect = true
                preferred = "strap-b"
                if invalidates { gate.invalidateRestoration(token: restoredA) }
                intentionalDisconnect = false
                XCTAssertTrue(allowsWhoop)
                XCTAssertFalse(intentionalDisconnect)
                releaseStore?.resume()
                await pending.value
                let scenario = "action=\(action), invalidates=\(invalidates)"
                XCTAssertEqual(boundaries, 1, scenario)
                XCTAssertEqual(adoptions, 0, scenario)
                XCTAssertEqual(connects, 0, scenario)
                XCTAssertEqual(discoveries, 0, scenario)
                let restoredB = gate.beginRestoration(identifier: "strap-b")
                for freshAction in actions {
                    XCTAssertTrue(gate.claimRestoration(freshAction, token: restoredB,
                        preferredIdentifier: preferred), scenario)
                    XCTAssertFalse(gate.claimRestoration(freshAction, token: restoredB,
                        preferredIdentifier: preferred), scenario)
                }
            }
        }
    }

    @MainActor
    func testRestorationInvalidationOnlyAffectsTheMatchingGeneration() {
        let gate = BLEStartupGate()
        let oldA = gate.beginRestoration(identifier: "strap-a")
        gate.invalidateRestoration(token: oldA)
        let actions: [BLEStartupGate.RestorationAction] = [.connect, .discover]
        for action in actions {
            XCTAssertFalse(gate.claimRestoration(action, token: oldA,
                preferredIdentifier: "strap-a"))
        }
        let currentB = gate.beginRestoration(identifier: "strap-b")
        gate.invalidateRestoration(token: oldA)
        gate.invalidateRestoration(token: nil)
        for action in actions {
            XCTAssertTrue(gate.claimRestoration(action, token: currentB,
                preferredIdentifier: "strap-b"))
            XCTAssertFalse(gate.claimRestoration(action, token: currentB,
                preferredIdentifier: "strap-b"))
        }
        let replacementB = gate.beginRestoration(identifier: "strap-b")
        gate.invalidateRestoration(token: currentB)
        for action in actions {
            XCTAssertTrue(gate.claimRestoration(action, token: replacementB,
                preferredIdentifier: "strap-b"))
            XCTAssertFalse(gate.claimRestoration(action, token: replacementB,
                preferredIdentifier: "strap-b"))
        }
    }

    @MainActor
    func testConnectionCallbacksRequireTheCurrentSelectedConnectedAndRunningPeripheral() {
        let cases: [(String, String, String?, String?, Bool, Bool, Bool)] = [
            ("stopped A", "strap-a", "strap-a", "strap-a", true, true, false),
            ("old A after B is current", "strap-a", "strap-b", "strap-b", false, true, false),
            ("A remains current after B is selected", "strap-a", "strap-a", "strap-b", false, true, false),
            ("current peripheral unavailable", "strap-b", nil, "strap-b", false, true, false),
            ("B is disconnected", "strap-b", "strap-b", "strap-b", false, false, false),
            ("legacy selection has no preferred ID", "strap-a", "strap-a", nil, false, true, true),
            ("valid B", "strap-b", "strap-b", "strap-b", false, true, true),
            ("B is no longer selected", "strap-b", "strap-b", "strap-a", false, true, false),
        ]
        for (scenario, identifier, current, preferred, stopped, connected, expected) in cases {
            XCTAssertEqual(BLEStartupGate.allowsConnectionCallback(identifier: identifier,
                currentIdentifier: current, preferredIdentifier: preferred,
                intentionalDisconnect: stopped, isConnected: connected), expected, scenario)
        }
    }

}
