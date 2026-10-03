import Foundation
import WhoopStore

/// Serializes store initialization before automatic BLE startup can consult the device registry.
@MainActor
final class BLEStartupGate {
    /// A HealthKit-only watch preserves the WHOOP link, while other selected devices replace it.
    static func allowsWhoopBLE(for row: PairedDevice?) -> Bool {
        guard let row else { return true }
        return SourceIdentity.isWhoop(row) || row.sourceKind == .liveAppleWatch
    }

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

    func invalidateRestoration(token: RestorationToken?) {
        guard let token, token == restoration else { return }
        restorationGeneration += 1
        restoration = nil
        restorationActions.removeAll()
    }

    func claimRestoration(_ action: RestorationAction, token: RestorationToken,
                          preferredIdentifier: String? = nil) -> Bool {
        guard token == restoration,
              preferredIdentifier == nil || token.identifier == preferredIdentifier else { return false }
        return restorationActions.insert(action).inserted
    }

    static func allowsConnectionCallback(identifier: String, currentIdentifier: String?,
                                         preferredIdentifier: String?, intentionalDisconnect: Bool,
                                         isConnected: Bool) -> Bool {
        !intentionalDisconnect && isConnected && identifier == currentIdentifier
            && (preferredIdentifier == nil || identifier == preferredIdentifier)
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
