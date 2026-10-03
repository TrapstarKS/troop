import Foundation

/// Demo captures retain local data and UI state without constructing radio transports.
enum LiveTransportPolicy {
    static func allowsLiveTransports(isDebug: Bool, demoRequested: Bool) -> Bool {
        !isDebug || !demoRequested
    }

    static let enabled: Bool = {
        #if DEBUG
        return allowsLiveTransports(isDebug: true, demoRequested: AppleDemoSeeder.requested)
        #else
        return true
        #endif
    }()

    static func makeTransport<T>(allowed: Bool, factory: () -> T?) -> T? {
        allowed ? factory() : nil
    }
}
