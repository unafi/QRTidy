package dev.unafi.qrtidy

import platform.UIKit.UIView

/**
 * Swift側の QRScannerViewController を生成するためのファクトリ。
 * Swift側で実装を登録します。
 *
 * 使用方法:
 * Swift側で QRScannerViewControllerFactory.register { qrOnly, onQr, onPhoto ->
 *     QRScannerView(qrOnly: qrOnly, onQr, onPhoto)
 * } を呼び出して実装を登録する。
 */
object QRScannerViewControllerFactory {
    private var factory: ((qrOnly: Boolean, onQrDetected: (String) -> Unit, onPhotoCaptured: (ByteArray) -> Unit) -> UIView)? = null

    /**
     * Swift側から呼び出してファクトリ実装を登録
     */
    fun register(creator: (qrOnly: Boolean, onQrDetected: (String) -> Unit, onPhotoCaptured: (ByteArray) -> Unit) -> UIView) {
        factory = creator
    }

    /**
     * Kotlin側から呼び出してカメラビューを生成
     * @param qrOnly true=QRコードのみ（箱スキャン用）、false=全バーコード対応（袋スキャン用）
     */
    fun create(qrOnly: Boolean = false, onQrDetected: (String) -> Unit, onPhotoCaptured: (ByteArray) -> Unit): UIView {
        return factory?.invoke(qrOnly, onQrDetected, onPhotoCaptured)
            ?: run {
                println("QRTidy-iOS: QRScannerViewControllerFactory が未登録です")
                UIView() // フォールバック: 空のビュー
            }
    }
}

