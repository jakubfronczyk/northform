import SwiftUI
import Northform

/// Hosts the Compose root. Renders nothing of its own.
struct ComposeHost: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        NorthformApp.shared.rootViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
