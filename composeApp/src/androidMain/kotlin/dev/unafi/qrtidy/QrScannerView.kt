package dev.unafi.qrtidy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

@OptIn(ExperimentalGetImage::class)
@Composable
fun QrScannerView(
    onQrCodeDetected: (String) -> Unit,
    scannerController: ScannerController? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    
    var isProcessing by remember { mutableStateOf(false) }

    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        },
        modifier = modifier.fillMaxSize(),
        update = { previewView ->
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val barcodeScanner = BarcodeScanning.getClient()

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor) { imageProxy ->
                            val mediaImage = imageProxy.image
                            if (mediaImage != null && !isProcessing) {
                                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                                barcodeScanner.process(image)
                                    .addOnSuccessListener { barcodes ->
                                        if (barcodes.isNotEmpty()) {
                                            val qrValue = barcodes[0].rawValue ?: ""
                                            if (qrValue.isNotEmpty()) {
                                                isProcessing = true
                                                onQrCodeDetected(qrValue)
                                                previewView.postDelayed({ isProcessing = false }, 3000)
                                            }
                                        }
                                    }
                                    .addOnFailureListener { Log.e("QrScanner", "QR Scan Error", it) }
                                    .addOnCompleteListener { imageProxy.close() }
                            } else {
                                imageProxy.close()
                            }
                        }
                    }

                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                
                scannerController?.bind(imageCapture)

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis,
                        imageCapture
                    )
                } catch (e: Exception) {
                    Log.e("QrScanner", "Binding failed", e)
                }
            }, ContextCompat.getMainExecutor(context))
        }
    )
}

class ScannerController {
    private var imageCapture: ImageCapture? = null

    fun bind(capture: ImageCapture) {
        this.imageCapture = capture
    }

    fun takePhoto(context: Context, onCaptured: (Bitmap) -> Unit) {
        val imageCapture = this.imageCapture ?: return

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        // 1. プレーンなBitmapを取得 (この時点では回転が適用されていない)
                        val originalBitmap = image.toBitmap()
                        
                        // 2. 回転情報を取得して補正用のMatrixを作成
                        val rotationDegrees = image.imageInfo.rotationDegrees
                        val matrix = Matrix().apply {
                            postRotate(rotationDegrees.toFloat())
                        }
                        
                        // 3. 回転済みのBitmapを作成
                        val rotatedBitmap = Bitmap.createBitmap(
                            originalBitmap, 0, 0, originalBitmap.width, originalBitmap.height, matrix, true
                        )
                        
                        // 4. 回転後の画像から正方形にクロップ
                        val size = Math.min(rotatedBitmap.width, rotatedBitmap.height)
                        val x = (rotatedBitmap.width - size) / 2
                        val y = (rotatedBitmap.height - size) / 2
                        
                        val squareBitmap = Bitmap.createBitmap(rotatedBitmap, x, y, size, size)
                        
                        onCaptured(squareBitmap)
                    } catch (e: Exception) {
                        Log.e("ScannerController", "Image processing failed", e)
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("ScannerController", "Photo capture failed: ${exception.message}", exception)
                }
            }
        )
    }
}
