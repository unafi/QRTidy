package dev.unafi.qrtidy

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.interop.UIKitView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import platform.AVFoundation.*
import platform.Foundation.*
import platform.UIKit.*

// スキャンモード (Android版と同じ)
enum class IOSScanMode {
    HUKURO_SCAN,
    HAKO_SCAN,
    SHIMAU_STEP1_HAKO,
    SHIMAU_STEP2_HUKURO
}

/**
 * iOS版のメインViewController。
 * Android版のMainActivity + MainScreenに相当。
 */
fun MainViewController() = androidx.compose.ui.window.ComposeUIViewController {
    val notionClient = remember { IOSNotionClient() }
    val scope = rememberCoroutineScope()

    // UI状態
    var currentMode by remember { mutableStateOf(IOSScanMode.HUKURO_SCAN) }
    var scannedId by remember { mutableStateOf("-") }
    var resultTitle by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("ボタンを押してスキャン開始") }
    var isScanningActive by remember { mutableStateOf(false) }
    var isFlashing by remember { mutableStateOf(false) }
    var isLocked by remember { mutableStateOf(false) }
    var capturedImageData by remember { mutableStateOf<ByteArray?>(null) }
    var capturedImageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    // 「箱にしまう」の一時保持用
    var selectedHakoPageId by remember { mutableStateOf<String?>(null) }
    var selectedHakoUid by remember { mutableStateOf<String?>(null) }

    // QR検出時の処理
    fun onIdDetected(id: String) {
        if (!isScanningActive || isLocked) return

        scope.launch {
            isLocked = true
            isFlashing = true
            scannedId = id
            statusMessage = "処理中..."

            delay(100)
            isFlashing = false

            try {
                when (currentMode) {
                    IOSScanMode.HUKURO_SCAN -> {
                        val page = notionClient.findOrCreatePage(
                            SecretConfig.DATABASE_ID_HUKURO, "袋ID", id, "商品名", "新規登録パーツ"
                        )
                        // 画像アップロード
                        capturedImageData?.let { imgData ->
                            val fileId = notionClient.uploadImage(imgData)
                            if (fileId != null) notionClient.updatePageImage(page.id, fileId)
                        }
                        // Notionページを開く
                        openUrl(page.url)
                        val name = page.properties["商品名"]?.rich_text?.firstOrNull()?.plain_text ?: id
                        resultTitle = name
                        statusMessage = "袋を開きました"
                        isScanningActive = false
                    }
                    IOSScanMode.HAKO_SCAN -> {
                        val page = notionClient.findOrCreatePage(
                            SecretConfig.DATABASE_ID_HAKO, "箱ID", id, "箱名", "新しい箱"
                        )
                        capturedImageData?.let { imgData ->
                            val fileId = notionClient.uploadImage(imgData)
                            if (fileId != null) notionClient.updatePageImage(page.id, fileId)
                        }
                        openUrl(page.url)
                        val name = page.properties["箱名"]?.rich_text?.firstOrNull()?.plain_text ?: id
                        resultTitle = name
                        statusMessage = "箱を開きました"
                        isScanningActive = false
                    }
                    IOSScanMode.SHIMAU_STEP1_HAKO -> {
                        val hakoPage = notionClient.findOrCreatePage(
                            SecretConfig.DATABASE_ID_HAKO, "箱ID", id, "箱名", "新しい箱"
                        )
                        capturedImageData?.let { imgData ->
                            val fileId = notionClient.uploadImage(imgData)
                            if (fileId != null) notionClient.updatePageImage(hakoPage.id, fileId)
                        }
                        selectedHakoPageId = hakoPage.id
                        selectedHakoUid = id
                        currentMode = IOSScanMode.SHIMAU_STEP2_HUKURO
                        val hakoName = hakoPage.properties["箱名"]?.rich_text?.firstOrNull()?.plain_text ?: id
                        statusMessage = "箱「$hakoName」を選択中。\n次に袋をスキャンしてください。"
                        capturedImageData = null
                        capturedImageBitmap = null
                    }
                    IOSScanMode.SHIMAU_STEP2_HUKURO -> {
                        val hakoId = selectedHakoPageId ?: return@launch
                        val hukuroPage = notionClient.findOrCreatePage(
                            SecretConfig.DATABASE_ID_HUKURO, "袋ID", id, "商品名", "新規登録パーツ"
                        )
                        capturedImageData?.let { imgData ->
                            val fileId = notionClient.uploadImage(imgData)
                            if (fileId != null) notionClient.updatePageImage(hukuroPage.id, fileId)
                        }
                        notionClient.updateHukuroLocation(hukuroPage.id, hakoId)
                        resultTitle = "完了"
                        statusMessage = "袋を箱に紐付けました！"
                        val finalHakoPage = notionClient.getPage(SecretConfig.DATABASE_ID_HAKO, "箱ID", selectedHakoUid!!)
                        finalHakoPage?.let { openUrl(it.url) }
                        currentMode = IOSScanMode.HUKURO_SCAN
                        isScanningActive = false
                    }
                }
            } catch (e: Exception) {
                resultTitle = "エラー"
                statusMessage = e.message ?: "不明なエラー"
                isScanningActive = false
            } finally {
                delay(400)
                isLocked = false
            }
        }
    }

    // モード変更時の処理
    fun onModeChange(mode: IOSScanMode) {
        currentMode = mode
        scannedId = "-"
        resultTitle = ""
        isScanningActive = true
        capturedImageData = null
        capturedImageBitmap = null
        statusMessage = when (mode) {
            IOSScanMode.HUKURO_SCAN -> "袋をスキャンしてください"
            IOSScanMode.HAKO_SCAN -> "箱をスキャンしてください"
            IOSScanMode.SHIMAU_STEP1_HAKO -> "【1/2】箱をスキャンしてください"
            IOSScanMode.SHIMAU_STEP2_HUKURO -> "【2/2】袋をスキャンしてください"
        }
    }

    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ステータスバー分のスペース
            Spacer(modifier = Modifier.height(48.dp))

            Text("QRTidy", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

            Spacer(modifier = Modifier.height(12.dp))

            // 「箱にしまう」ボタン
            Button(
                onClick = { onModeChange(IOSScanMode.SHIMAU_STEP1_HAKO) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (currentMode == IOSScanMode.SHIMAU_STEP1_HAKO || currentMode == IOSScanMode.SHIMAU_STEP2_HUKURO)
                        MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary
                )
            ) { Text("箱にしまう", fontSize = 18.sp) }

            Spacer(modifier = Modifier.height(8.dp))

            // 袋スキャン / 箱スキャン
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onModeChange(IOSScanMode.HUKURO_SCAN) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (currentMode == IOSScanMode.HUKURO_SCAN && isScanningActive)
                            MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                    )
                ) { Text("袋スキャン") }

                Button(
                    onClick = { onModeChange(IOSScanMode.HAKO_SCAN) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (currentMode == IOSScanMode.HAKO_SCAN && isScanningActive)
                            MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                    )
                ) { Text("箱スキャン") }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ステータスカード
            Card(
                modifier = Modifier.fillMaxWidth().height(120.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp).fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceAround
                ) {
                    Text(statusMessage, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text("ID: $scannedId", style = MaterialTheme.typography.labelSmall)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // カメラビューエリア
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f).background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (isScanningActive) {
                    // iOS ネイティブのカメラビューを UIKitView で埋め込み
                    key(currentMode) {
                        IOSCameraView(
                            onQrDetected = { qrValue -> onIdDetected(qrValue) },
                            onPhotoCaptured = { imageData ->
                                capturedImageData = imageData
                                // ByteArrayからImageBitmapに変換
                                try {
                                    val skiaImage = SkiaImage.makeFromEncoded(imageData)
                                    capturedImageBitmap = skiaImage.toComposeImageBitmap()
                                } catch (e: Exception) {
                                    println("QRTidy-iOS: 画像変換失敗: ${e.message}")
                                }
                            }
                        )
                    }

                    // スキャンガイド枠
                    Box(
                        modifier = Modifier.size(200.dp)
                            .border(2.dp, Color.White.copy(alpha = 0.5f), shape = MaterialTheme.shapes.medium)
                    )

                    // フラッシュ演出
                    androidx.compose.animation.AnimatedVisibility(
                        visible = isFlashing,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Box(modifier = Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.6f)))
                    }

                    // 撮影プレビュー + ボタン
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        capturedImageBitmap?.let { bitmap ->
                            Image(
                                bitmap = bitmap,
                                contentDescription = "Preview",
                                modifier = Modifier
                                    .size(180.dp)
                                    .offset(y = (-90).dp)
                                    .border(2.dp, Color.White, shape = MaterialTheme.shapes.medium)
                            )
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = {
                                    // Swift側のキャプチャを呼び出す
                                    // (UIKitView内のコントローラーに通知)
                                    NSNotificationCenter.defaultCenter.postNotificationName(
                                        "QRTidyCapturePhoto", `object` = null
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)
                            ) {
                                Text("📷 撮影", color = Color.Black)
                            }
                            Button(
                                onClick = {
                                    isScanningActive = false
                                    capturedImageData = null
                                    capturedImageBitmap = null
                                    statusMessage = "ボタンを押してスキャン開始"
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)
                            ) {
                                Text("中止", color = Color.Black)
                            }
                        }
                    }
                } else {
                    Text("ボタンを押してスキャン開始", color = Color.White)
                }
            }
        }
    }
}

/**
 * UIKitView でネイティブの AVFoundation カメラビューを埋め込むComposable
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
fun IOSCameraView(
    onQrDetected: (String) -> Unit,
    onPhotoCaptured: (ByteArray) -> Unit
) {
    UIKitView<UIView>(
        factory = {
            // Swift側の QRScannerViewController を生成
            QRScannerViewControllerFactory.create(
                onQrDetected = onQrDetected,
                onPhotoCaptured = onPhotoCaptured
            )
        },
        modifier = Modifier.fillMaxSize()
    )
}

/**
 * URLを開くヘルパー関数
 */
private fun openUrl(urlString: String) {
    val url = NSURL.URLWithString(urlString) ?: return
    UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
}