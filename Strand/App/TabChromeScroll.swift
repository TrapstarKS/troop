import SwiftUI
import StrandDesign

extension View {
    /// Observe one screen's vertical scroll without replacing its gestures or navigation identity.
    @ViewBuilder
    func tabChromeScrollObserver(_ onScroll: ((CGFloat, CGFloat) -> Void)? = nil) -> some View {
        #if os(iOS)
        if #available(iOS 26.0, *), let onScroll {
            modifier(TabChromeScrollObserver(onScroll: onScroll))
        } else {
            self
        }
        #else
        self
        #endif
    }
}

#if os(iOS)
@available(iOS 26.0, *)
private struct TabChromeScrollObserver: ViewModifier {
    let onScroll: (CGFloat, CGFloat) -> Void
    @State private var isUserScrolling = false

    func body(content: Content) -> some View {
        content
            .onScrollPhaseChange { _, phase in
                isUserScrolling = phase == .interacting || phase == .decelerating
            }
            .onScrollGeometryChange(for: CGFloat.self) { geometry in
                let offset = max(0, geometry.contentOffset.y + geometry.contentInsets.top)
                return (offset / NoopMetrics.gap).rounded(.down) * NoopMetrics.gap
            } action: { oldOffset, newOffset in
                if newOffset == 0 || isUserScrolling {
                    onScroll(oldOffset, newOffset)
                }
            }
    }
}
#endif
