import XCTest
import Combine
import CoreBluetooth
import WhoopStore
@testable import Strand

final class InertDemoTransportTests: XCTestCase {
    func testOnlyRequestedDebugDemoDisablesTransportFactories() {
        XCTAssertTrue(LiveTransportPolicy.allowsLiveTransports(isDebug: false, demoRequested: false))
        XCTAssertTrue(LiveTransportPolicy.allowsLiveTransports(isDebug: false, demoRequested: true))
        XCTAssertTrue(LiveTransportPolicy.allowsLiveTransports(isDebug: true, demoRequested: false))
        XCTAssertFalse(LiveTransportPolicy.allowsLiveTransports(isDebug: true, demoRequested: true))
        var attempts = 0
        let value: Int? = LiveTransportPolicy.makeTransport(allowed: false) {
            attempts += 1
            return 42
        }
        XCTAssertNil(value)
        XCTAssertEqual(attempts, 0)
        XCTAssertEqual(LiveTransportPolicy.makeTransport(allowed: true) {
            attempts += 1
            return 42
        }, 42)
        XCTAssertEqual(attempts, 1)
    }

    @MainActor
    func testInertWhoopConstructorsAndControlsNeverRequestACentral() {
        var attempts = 0
        let factory: (CBCentralManagerDelegate) -> CBCentralManager? = { _ in
            attempts += 1
            return nil
        }
        let live = LiveState()
        let managers = [
            BLEManager(state: live, allowsLiveTransports: false, centralFactory: factory),
            BLEManager(state: live, collector: nil, allowsLiveTransports: false, centralFactory: factory),
        ]
        for manager in managers {
            manager.connect()
            manager.connectFromSystem()
            manager.scanForWhoops()
            manager.stopWhoopScan()
            manager.disconnect()
        }
        XCTAssertEqual(attempts, 0)
        XCTAssertFalse(live.connected)
        XCTAssertFalse(live.bonded)
    }

    @MainActor
    func testWizardSourceConstructorsAndControlsRespectTheTransportPolicy() {
        var attempts = 0
        let factory: (CBCentralManagerDelegate) -> CBCentralManager? = { _ in
            attempts += 1
            return nil
        }
        for allowed in [false, true] {
            let live = LiveState()
            let sources: [any LiveHRSource] = [
                StandardHRSource(live: live, deviceId: "preview", persist: { _ in },
                                 allowsLiveTransports: allowed, centralFactory: factory),
                FTMSSource(live: live, feedsLive: false,
                           allowsLiveTransports: allowed, centralFactory: factory),
                HuamiHRSource(live: live, deviceId: "preview", feedsLive: false,
                              allowsLiveTransports: allowed, centralFactory: factory),
                OuraLiveSource(live: live, deviceId: "preview", ringGen: .gen3, authKey: { nil },
                               feedsLive: false, allowsLiveTransports: allowed, centralFactory: factory),
            ]
            for source in sources {
                source.scan()
                source.connect(UUID())
                source.stop()
            }
            XCTAssertEqual(attempts, allowed ? 4 : 0)
            XCTAssertFalse(live.connected)
        }
        _ = BLEManager(state: LiveState(), allowsLiveTransports: true, centralFactory: factory)
        XCTAssertEqual(attempts, 5, "The ordinary WHOOP constructor still delegates to the transport factory")
    }

    @MainActor
    func testInertCoordinatorKeepsRegistryReadsWithoutCreatingLiveSources() async throws {
        let store = try await WhoopStore.inMemory()
        let registry = DeviceRegistry(store: DeviceRegistryStore(dbQueue: store.registryWriter))
        registry.reload()
        let kinds: [SourceKind] = [.liveBLE, .ftms, .huami, .oura]
        for (index, kind) in kinds.enumerated() {
            registry.add(PairedDevice(id: "preview-\(index)", brand: "Test", model: "Test",
                peripheralId: UUID().uuidString, sourceKind: kind, capabilities: [.hr],
                status: .paired, addedAt: 0, lastSeenAt: 0))
        }
        var starts = 0
        var stops = 0
        var targeting = 0
        var factories = 0
        let source = FakeSource()
        let coordinator = SourceCoordinator(registry: registry, live: LiveState(), storeHandle: { store },
            startWhoop: { starts += 1 }, stopWhoop: { stops += 1 },
            setWhoopPreferredPeripheral: { _ in targeting += 1 },
            setWhoopActiveDeviceId: { _ in targeting += 1 },
            connectedPeripheralUUID: Empty<String?, Never>().eraseToAnyPublisher(),
            allowsLiveTransports: false, sourceFactory: { _ in factories += 1; return source })
        coordinator.start()
        for index in kinds.indices { registry.setActive("preview-\(index)") }
        coordinator.reconnectActiveRing()
        XCTAssertEqual(registry.activeDeviceId, "preview-3")
        XCTAssertEqual(starts, 0)
        XCTAssertEqual(stops, 0)
        XCTAssertEqual(targeting, 0)
        XCTAssertEqual(factories, 0)
        XCTAssertEqual(source.actions, 0)
        XCTAssertNil(coordinator.ouraSource)
    }

    @MainActor
    func testOrdinaryCoordinatorStillTransitionsToTheSelectedSource() async throws {
        let store = try await WhoopStore.inMemory()
        let registry = DeviceRegistry(store: DeviceRegistryStore(dbQueue: store.registryWriter))
        registry.reload()
        registry.add(PairedDevice(id: "preview", brand: "Polar", model: "H10", peripheralId: nil,
            sourceKind: .liveBLE, capabilities: [.hr], status: .paired, addedAt: 0, lastSeenAt: 0))
        var stops = 0
        var factories = 0
        let source = FakeSource()
        let coordinator = SourceCoordinator(registry: registry, live: LiveState(), storeHandle: { store },
            startWhoop: {}, stopWhoop: { stops += 1 }, setWhoopPreferredPeripheral: { _ in },
            setWhoopActiveDeviceId: { _ in },
            connectedPeripheralUUID: Empty<String?, Never>().eraseToAnyPublisher(),
            allowsLiveTransports: true, sourceFactory: { _ in factories += 1; return source })
        coordinator.activeDeviceChanged(to: "preview")
        XCTAssertEqual(stops, 1)
        XCTAssertEqual(factories, 1)
        XCTAssertEqual(source.actions, 1)
    }

    @MainActor
    func testInertBroadcastResumeNeverRequestsAPeripheralManager() {
        var attempts = 0
        for allowed in [false, true] {
            let broadcaster = HrBroadcaster(allowsLiveTransports: allowed, managerFactory: { _ in
                attempts += 1
                return nil
            })
            broadcaster.start()
            broadcaster.stop()
            XCTAssertFalse(broadcaster.advertising)
            XCTAssertEqual(attempts, allowed ? 1 : 0)
        }
    }

    @MainActor
    private final class FakeSource: LiveHRSource {
        var actions = 0
        func scan() { actions += 1 }
        func connect(_ id: UUID) { actions += 1 }
        func stop() { actions += 1 }
    }
}
