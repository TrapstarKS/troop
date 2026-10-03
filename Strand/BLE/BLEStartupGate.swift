import Foundation

/// Serializes store initialization before automatic BLE startup can consult the device registry.
@MainActor
final class BLEStartupGate {
    private var preparation: Task<Bool, Never>?
    enum RestorationAction: Hashable { case connect, discover }
    struct RestorationToken: Equatable {
        let identifier: String
        let generation: Int
    }
    private var restoration: RestorationToken?
    private var restorationGeneration = 0
    private var restorationActions = Set<RestorationAction>()

    func beginRestoration(identifier: String) -> RestorationToken {
        restorationGeneration += 1
        let token = RestorationToken(identifier: identifier, generation: restorationGeneration)
        restoration = token
        restorationActions.removeAll()
        return token
    }

    func claimRestoration(_ action: RestorationAction, token: RestorationToken) -> Bool {
        guard token == restoration else { return false }
        return restorationActions.insert(action).inserted
    }

    func prepare(_ operation: @escaping @MainActor () async -> Bool) async -> Bool {
        if let preparation { return await preparation.value }
        let task = Task { @MainActor in await operation() }
        preparation = task
        let ready = await task.value
        preparation = nil
        return ready
    }

    func resume(prepare operation: @escaping @MainActor () async -> Bool,
                isAllowed: @MainActor () -> Bool,
                onDenied: @MainActor () -> Void = {},
                action: @MainActor () -> Void) async {
        guard await prepare(operation) else { return }
        guard isAllowed() else { onDenied(); return }
        action()
    }
}
