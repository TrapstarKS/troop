#if os(macOS) && DEBUG && NOOP_PURE_TEST_HOST
import SwiftUI

@main
struct NoopPureTestHost: App {
    var body: some Scene {
        WindowGroup { EmptyView() }
    }
}
#endif
