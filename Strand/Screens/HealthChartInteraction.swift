import SwiftUI

#if canImport(UIKit)
import UIKit

// Android uses detectHorizontalDragGestures for the same chart interaction.
struct HealthChartInteraction: UIViewRepresentable {
    var onSelect: (CGPoint) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onSelect: onSelect) }

    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        let pan = UIPanGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.select(_:)))
        pan.delegate = context.coordinator
        pan.cancelsTouchesInView = false
        let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.select(_:)))
        tap.cancelsTouchesInView = false
        tap.require(toFail: pan)
        view.addGestureRecognizer(pan)
        view.addGestureRecognizer(tap)
        return view
    }

    func updateUIView(_ view: UIView, context: Context) {
        context.coordinator.onSelect = onSelect
    }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        var onSelect: (CGPoint) -> Void

        init(onSelect: @escaping (CGPoint) -> Void) { self.onSelect = onSelect }

        func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
            guard let pan = gestureRecognizer as? UIPanGestureRecognizer else { return true }
            let velocity = pan.velocity(in: pan.view)
            return abs(velocity.x) > abs(velocity.y)
        }

        @objc func select(_ recognizer: UIGestureRecognizer) {
            guard recognizer.state == .began || recognizer.state == .changed || recognizer.state == .ended else { return }
            onSelect(recognizer.location(in: recognizer.view))
        }
    }
}
#else
struct HealthChartInteraction: View {
    var onSelect: (CGPoint) -> Void

    var body: some View {
        Rectangle().fill(.clear).contentShape(Rectangle())
            .simultaneousGesture(SpatialTapGesture().onEnded { onSelect($0.location) })
            .simultaneousGesture(DragGesture().onChanged { event in
                if abs(event.translation.width) > abs(event.translation.height) { onSelect(event.location) }
            })
    }
}
#endif
