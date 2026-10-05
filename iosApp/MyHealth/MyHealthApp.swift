import Shared
import SwiftUI

/// The iOS shell (P21): one window hosting the shared Compose UI. All screens, data and logic live
/// in the Kotlin `shared` module; see `MainViewController.kt`.
@main
struct MyHealthApp: App {
    @Environment(\.scenePhase) private var scenePhase

    init() {
        #if DEBUG
        // Simulator tests (P22.1): fill the Health store with the fixed sample data set.
        if ProcessInfo.processInfo.arguments.contains("-seedHealthKit") {
            HealthKitSeeder.shared.seed(days: 45) { _ in }
        }
        #endif
    }

    var body: some Scene {
        WindowGroup {
            ComposeView().ignoresSafeArea()
        }
        .onChange(of: scenePhase) { _, phase in
            // Apple Health is read whenever the app comes to the front (P22.1).
            if phase == .active { MainViewControllerKt.onAppBecameActive() }
        }
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
