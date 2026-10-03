import Foundation

final class LocalNotificationDeliveryGate {
    private enum Status { case pending, completed, invalidated }
    private struct Attempt {
        let token: UUID
        var status: Status
    }
    private let lock = NSLock()
    private var attempts: [String: Attempt] = [:]

    func begin(_ identifier: String) -> UUID? {
        lock.lock()
        defer { lock.unlock() }
        if let attempt = attempts[identifier], case .pending = attempt.status { return nil }
        let token = UUID()
        attempts[identifier] = Attempt(token: token, status: .pending)
        return token
    }

    func invalidate(_ identifier: String) {
        lock.lock()
        defer { lock.unlock() }
        guard var attempt = attempts[identifier] else { return }
        attempt.status = .invalidated
        attempts[identifier] = attempt
    }

    func isCurrent(_ identifier: String, token: UUID) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard let attempt = attempts[identifier], attempt.token == token else { return false }
        if case .pending = attempt.status { return true }
        return false
    }

    func finish(_ identifier: String, token: UUID, accepted: Bool,
                onAccepted: () -> Void, onStale: () -> Void) {
        lock.lock()
        defer { lock.unlock() }
        guard var attempt = attempts[identifier], attempt.token == token else { return }
        switch attempt.status {
        case .pending:
            attempt.status = .completed
            attempts[identifier] = attempt
            if accepted { onAccepted() }
        case .invalidated:
            attempt.status = .completed
            attempts[identifier] = attempt
            if accepted { onStale() }
        case .completed:
            break
        }
    }
}
