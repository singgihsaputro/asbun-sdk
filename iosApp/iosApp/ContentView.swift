import SwiftUI
import UIKit
import Shared

/// Membungkus UI Compose Multiplatform (modul `shared`) ke dalam SwiftUI.
/// Seluruh UI, simulasi, dan asisten suara (SFSpeechRecognizer + AVSpeechSynthesizer)
/// ada di Kotlin — lihat shared/src/iosMain.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea() // Compose mengatur safe area & keyboard sendiri
    }
}
