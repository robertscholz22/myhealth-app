import Shared
import SwiftUI

/// The iOS shell (P21): one window hosting the shared Compose UI. All screens, data and logic live
/// in the Kotlin `shared` module; see `MainViewController.kt`.
@main
struct MyHealthApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView().ignoresSafeArea()
        }
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
