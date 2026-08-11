import UIKit
import SwiftUI
import Shared

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Self.Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Self.Context) {}
}

struct ContentView: View {
    var body: some View {
        // Sin esto, SwiftUI encaja el UIViewController de Compose dentro del
        // safe area (entre notch y home indicator) y las franjas de arriba/abajo
        // quedan pintadas con el negro por defecto de WindowGroup: se ve como un
        // margen que no debería existir. Compose ya recibe los insets reales de
        // iOS y los respeta donde toca (Scaffold + WindowInsets en App.kt), así
        // que aquí solo hace falta que la vista ocupe la pantalla entera.
        ComposeView()
            .ignoresSafeArea()
    }
}