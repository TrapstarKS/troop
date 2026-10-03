import Foundation

final class LocalNotificationDeliveryGate {
    private let lock = NSLock()
    private var attempts: [String: UUID] = [:]

    func begin(_ identifier: String) -> UUID? {
        lock.lock()
        defer { lock.unlock() }
        guard attempts[identifier] == nil else { return nil }
        let token = UUID()
        attempts[identifier] = token
        return token
    }

    func invalidate(_ identifier: String) {
        lock.lock()
        attempts.removeValue(forKey: identifier)
        lock.unlock()
    }

    func isCurrent(_ identifier: String, token: UUID) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return attempts[identifier] == token
    }

    func finish(_ identifier: String, token: UUID, accepted: Bool,
                onAccepted: () -> Void, onStale: () -> Void) {
        lock.lock()
        defer { lock.unlock() }
        guard attempts[identifier] == token else {
            if accepted, attempts[identifier] == nil { onStale() }
            return
        }
        attempts.removeValue(forKey: identifier)
        if accepted { onAccepted() }
    }
}
