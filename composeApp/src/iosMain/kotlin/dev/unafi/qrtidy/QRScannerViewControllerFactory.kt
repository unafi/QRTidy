package dev.unafi.qrtidy

import platform.UIKit.UIView

/**
 * Swift側の QRScannerViewController を生成するためのファクトリ。
 * Swift側で実装を登録します。
 *
 * 使用方法:
 * Swift側で QRScannerViewControllerFactory.register { onQr, onPhoto ->
 *     QRScannerViewController(onQr, onPhoto)
 * } を呼び出して実装を登録する。
 */
object QRScannerViewControllerFactory {
    private var factory: ((onQrDetected: (String) -> Unit, onPhotoCaptured: (ByteArray) -> Unit) -> UIView)? = null

    /**
     * Swift側から呼び出してファクトリ実装を登録
     */
    fun register(creator: (onQrDetected: (String) -> Unit, onPhotoCaptured: (ByteArray) -> Unit) -> UIView) {
        factory = creator
    }

    /**
     * Kotlin側から呼び出してカメラビューを生成
     */
    fun create(onQrDetected: (String) -> Unit, onPhotoCaptured: (ByteArray) -> Unit): UIView {
        return factory?.invoke(onQrDetected, onPhotoCaptured)
            ?: run {
                println("QRTidy-iOS: QRScannerViewControllerFactory が未登録です")
                UIView() // フォールバック: 空のビュー
            }
    }
}
