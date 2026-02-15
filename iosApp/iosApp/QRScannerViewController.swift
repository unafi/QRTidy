import AVFoundation
import UIKit
import ComposeApp

/// AVFoundation による QRコードスキャナー + 写真撮影
/// UIView サブクラスとして実装（UIKitView で直接埋め込み可能）
class QRScannerView: UIView,
                     AVCaptureMetadataOutputObjectsDelegate,
                     AVCapturePhotoCaptureDelegate {

    // Kotlin側へのコールバック
    private let onQrDetected: (String) -> Void
    private let onPhotoCaptured: (KotlinByteArray) -> Void

    // QRコードのみモード（箱スキャン時）
    private let qrOnly: Bool

    // AVFoundation
    private var captureSession: AVCaptureSession?
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var photoOutput: AVCapturePhotoOutput?

    // 二重検出防止
    private var isProcessing = false

    init(qrOnly: Bool = false,
         onQrDetected: @escaping (String) -> Void,
         onPhotoCaptured: @escaping (KotlinByteArray) -> Void) {
        self.qrOnly = qrOnly
        self.onQrDetected = onQrDetected
        self.onPhotoCaptured = onPhotoCaptured
        super.init(frame: .zero)
        setupCamera()
        setupNotifications()
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) は未対応です")
    }

    // MARK: - UIView ライフサイクル

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window != nil {
            startSession()
        } else {
            stopSession()
        }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        previewLayer?.frame = bounds
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    // MARK: - カメラセットアップ

    private func setupCamera() {
        // オーディオセッションの設定（シャッター音用）
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, options: [])
            try AVAudioSession.sharedInstance().setActive(true)
        } catch {
            print("QRTidy-iOS: オーディオセッション設定失敗: \(error)")
        }

        let session = AVCaptureSession()
        session.sessionPreset = .high

        // カメラ入力
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let input = try? AVCaptureDeviceInput(device: device) else {
            print("QRTidy-iOS: カメラの初期化に失敗")
            return
        }

        if session.canAddInput(input) {
            session.addInput(input)
        }

        // バーコード検出用のメタデータ出力
        let metadataOutput = AVCaptureMetadataOutput()
        if session.canAddOutput(metadataOutput) {
            session.addOutput(metadataOutput)
            metadataOutput.setMetadataObjectsDelegate(self, queue: DispatchQueue.main)

            if qrOnly {
                // 箱スキャン: QRコードのみ（箱にはQRシールを貼る）
                metadataOutput.metadataObjectTypes = [.qr]
                print("QRTidy-iOS: スキャンモード → QRのみ")
            } else {
                // 袋スキャン: QR + 1次元バーコード全対応
                metadataOutput.metadataObjectTypes = [
                    .qr,
                    .ean8, .ean13,
                    .code128, .code39,
                    .interleaved2of5,
                    .itf14,
                    .upce
                ]
                print("QRTidy-iOS: スキャンモード → 全バーコード対応")
            }
        }

        // 写真撮影用の出力
        let photo = AVCapturePhotoOutput()
        if session.canAddOutput(photo) {
            session.addOutput(photo)
            
            // 出力の向きを「縦（Portrait）」に完全固定
            if let connection = photo.connection(with: .video), connection.isVideoOrientationSupported {
                connection.videoOrientation = .portrait
            }
        }
        self.photoOutput = photo

        // プレビューレイヤー
        let preview = AVCaptureVideoPreviewLayer(session: session)
        preview.videoGravity = .resizeAspectFill
        preview.frame = bounds
        layer.addSublayer(preview)
        
        // プレビューの向きも「縦（Portrait）」に完全固定
        if let connection = preview.connection, connection.isVideoOrientationSupported {
            connection.videoOrientation = .portrait
        }
        
        self.previewLayer = preview

        self.captureSession = session
    }

    // MARK: - セッション開始/停止

    private func startSession() {
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            self?.captureSession?.startRunning()
        }
    }

    private func stopSession() {
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            self?.captureSession?.stopRunning()
        }
    }

    // MARK: - 通知ハンドリング (Kotlin側から撮影リクエスト)

    private func setupNotifications() {
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(capturePhoto),
            name: NSNotification.Name("QRTidyCapturePhoto"),
            object: nil
        )
    }

    @objc private func capturePhoto() {
        guard let photoOutput = self.photoOutput else { return }

        // 念のため、撮影の瞬間にも向きを「縦（Portrait）」に強制固定
        if let connection = photoOutput.connection(with: .video), connection.isVideoOrientationSupported {
            connection.videoOrientation = .portrait
        }

        // シャッター音
        AudioServicesPlaySystemSound(1108)
        
        let settings = AVCapturePhotoSettings()
        photoOutput.capturePhoto(with: settings, delegate: self)
        print("QRTidy-iOS: 撮影開始")
    }

    // MARK: - バーコード検出デリゲート（QR + 1次元 + 2段組対応）

    func metadataOutput(_ output: AVCaptureMetadataOutput,
                        didOutput metadataObjects: [AVMetadataObject],
                        from connection: AVCaptureConnection) {
        guard !isProcessing else { return }

        // 有効なバーコードオブジェクトのみ抽出
        let barcodes = metadataObjects.compactMap { $0 as? AVMetadataMachineReadableCodeObject }
            .filter { $0.stringValue != nil }

        guard !barcodes.isEmpty else { return }

        // EAN-13バーコードのみ抽出
        let ean13Barcodes = barcodes.filter { $0.type == .ean13 }

        let resultValue: String

        if ean13Barcodes.count >= 2 {
            // ── 2段組バーコード検出 ──
            // ISBN（978/979始まり）を上段、それ以外を下段として結合
            // これにより本を逆さにかざしても正しい順序を保証
            let isbnBarcode = ean13Barcodes.first { $0.stringValue!.hasPrefix("978") || $0.stringValue!.hasPrefix("979") }
            let otherBarcode = ean13Barcodes.first { !($0.stringValue!.hasPrefix("978") || $0.stringValue!.hasPrefix("979")) }

            if let isbn = isbnBarcode?.stringValue, let other = otherBarcode?.stringValue {
                // ISBN（上段）→ 分類・価格（下段）の順に結合
                resultValue = "\(isbn)-\(other)"
                print("QRTidy-iOS: 2段組バーコード検出（書籍）: \(resultValue)")
            } else {
                // 両方ISBNまたは両方非ISBNの場合はY座標でソート
                let sorted = ean13Barcodes.sorted { $0.bounds.origin.y < $1.bounds.origin.y }
                let upper = sorted[0].stringValue!
                let lower = sorted[1].stringValue!
                resultValue = "\(upper)-\(lower)"
                print("QRTidy-iOS: 2段組バーコード検出（Y座標順）: \(resultValue)")
            }
        } else {
            // ── 単体バーコード（QR / EAN-13 / その他）──
            resultValue = barcodes.first!.stringValue!
        }

        isProcessing = true

        // 検出音
        AudioServicesPlaySystemSound(1108)

        print("QRTidy-iOS: コード検出: \(resultValue)")
        onQrDetected(resultValue)
    }

    // MARK: - 写真撮影デリゲート

    func photoOutput(_ output: AVCapturePhotoOutput,
                     didFinishProcessingPhoto photo: AVCapturePhoto,
                     error: Error?) {
        if let error = error {
            print("QRTidy-iOS: 撮影エラー: \(error.localizedDescription)")
            return
        }

        guard let imageData = photo.fileDataRepresentation() else {
            print("QRTidy-iOS: 画像データの取得に失敗")
            return
        }

        // UIImage生成
        guard let uiImage = UIImage(data: imageData) else {
            print("QRTidy-iOS: UIImage変換に失敗")
            return
        }
        
        // **ここから追加**: 正方形にクロップ
        let croppedImage = uiImage.cropToSquare()
        
        // JPEG圧縮 (品質70%)
        guard let jpegData = croppedImage.jpegData(compressionQuality: 0.7) else {
            print("QRTidy-iOS: JPEG変換に失敗")
            return
        }

        print("QRTidy-iOS: 撮影完了. サイズ: \(jpegData.count) bytes")

        // Data → KotlinByteArray への変換
        let kotlinBytes = KotlinByteArray(size: Int32(jpegData.count))
        jpegData.withUnsafeBytes { (buffer: UnsafeRawBufferPointer) in
            guard let baseAddress = buffer.baseAddress else { return }
            let bytes = baseAddress.assumingMemoryBound(to: Int8.self)
            for i in 0..<jpegData.count {
                kotlinBytes.set(index: Int32(i), value: bytes[i])
            }
        }
        onPhotoCaptured(kotlinBytes)
    }
}

// MARK: - 画像加工用拡張
extension UIImage {
    func cropToSquare() -> UIImage {
        // オリジナルの向きを考慮したサイズを取得
        let originalWidth = size.width
        let originalHeight = size.height
        let edge = min(originalWidth, originalHeight)
        
        // 中央を切り抜くための座標計算
        let posX = (originalWidth - edge) / 2.0
        let posY = (originalHeight - edge) / 2.0
        
        let cropRect = CGRect(x: posX, y: posY, width: edge, height: edge)
        
        // 画像を現在の向きなどを考慮して描画し直し、クロップする
        let renderer = UIGraphicsImageRenderer(size: cropRect.size)
        return renderer.image { _ in
            draw(at: CGPoint(x: -posX, y: -posY))
        }
    }
}
