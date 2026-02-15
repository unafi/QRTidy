import AVFoundation
import UIKit
import ComposeApp

/// AVFoundation による QRコードスキャナー + 写真撮影
/// Kotlin側の QRScannerViewControllerFactory から呼び出される
class QRScannerViewController: UIViewController,
                                AVCaptureMetadataOutputObjectsDelegate,
                                AVCapturePhotoCaptureDelegate {

    // Kotlin側へのコールバック
    private let onQrDetected: (String) -> Void
    private let onPhotoCaptured: (KotlinByteArray) -> Void

    // AVFoundation
    private var captureSession: AVCaptureSession?
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var photoOutput: AVCapturePhotoOutput?

    // 二重検出防止
    private var isProcessing = false

    init(onQrDetected: @escaping (String) -> Void,
         onPhotoCaptured: @escaping (KotlinByteArray) -> Void) {
        self.onQrDetected = onQrDetected
        self.onPhotoCaptured = onPhotoCaptured
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) は未対応です")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        setupCamera()
        setupNotifications()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        startSession()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        stopSession()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    // MARK: - カメラセットアップ

    private func setupCamera() {
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

        // QRコード検出用のメタデータ出力
        let metadataOutput = AVCaptureMetadataOutput()
        if session.canAddOutput(metadataOutput) {
            session.addOutput(metadataOutput)
            metadataOutput.setMetadataObjectsDelegate(self, queue: DispatchQueue.main)
            metadataOutput.metadataObjectTypes = [.qr]
        }

        // 写真撮影用の出力
        let photo = AVCapturePhotoOutput()
        if session.canAddOutput(photo) {
            session.addOutput(photo)
        }
        self.photoOutput = photo

        // プレビューレイヤー
        let preview = AVCaptureVideoPreviewLayer(session: session)
        preview.videoGravity = .resizeAspectFill
        preview.frame = view.bounds
        view.layer.addSublayer(preview)
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
        let settings = AVCapturePhotoSettings()
        photoOutput.capturePhoto(with: settings, delegate: self)
        print("QRTidy-iOS: 撮影開始")
    }

    // MARK: - QR検出デリゲート

    func metadataOutput(_ output: AVCaptureMetadataOutput,
                        didOutput metadataObjects: [AVMetadataObject],
                        from connection: AVCaptureConnection) {
        guard !isProcessing else { return }
        guard let object = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              let qrValue = object.stringValue else { return }

        isProcessing = true
        print("QRTidy-iOS: QR検出: \(qrValue)")
        onQrDetected(qrValue)

        // 連続検出を防止 (0.5秒後に解除)
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
            self?.isProcessing = false
        }
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

        // JPEG圧縮 (品質70%)
        guard let uiImage = UIImage(data: imageData),
              let jpegData = uiImage.jpegData(compressionQuality: 0.7) else {
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
