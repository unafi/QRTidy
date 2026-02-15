import UIKit
import SwiftUI
import ComposeApp

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        // Swift側のカメラファクトリをKotlin側に登録
        QRScannerViewControllerFactory.shared.register { onQrDetected, onPhotoCaptured in
            let scannerView = QRScannerView(
                onQrDetected: { qrValue in onQrDetected(qrValue) },
                onPhotoCaptured: { imageData in onPhotoCaptured(imageData) }
            )
            return scannerView
        }
        return MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea()
    }
}
