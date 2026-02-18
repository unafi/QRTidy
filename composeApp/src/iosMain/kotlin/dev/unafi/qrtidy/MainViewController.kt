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
import kotlinx.serialization.json.*

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
    val productSearchClient = remember { ProductSearchClient() }
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
    // 共通処理: 物（アイテム）情報のスキャン・登録・更新
    suspend fun processHukuroScan(id: String): IOSNotionPage {
        // 1. バーコード種別判定
        val codeType = productSearchClient.classifyBarcodeType(id)
        
        // 2. Notionページ検索/作成
        val page = notionClient.findOrCreatePage(
            SecretConfig.DATABASE_ID_HUKURO, "物ID", id, "物名", "新規登録パーツ"
        )
        
        // 3. カテゴリ未設定の場合のみ API検索 & 情報更新
        // デバッグ: 取得したページのプロパティキーを確認
        println("QRTidy-iOS: Page ID: ${page.id}")
        println("QRTidy-iOS: Properties included: ${page.properties.keys.sorted().joinToString(", ")}")
        
        // 3. カテゴリ未設定の場合のみ API検索 & 情報更新
        val categoryProp = page.properties["カテゴリ"]
        
        // カテゴリは Select または RichText の可能性があるため両方チェック
        // RichTextの場合は全要素を結合してトリムする
        val currentCategory = (categoryProp?.select?.name 
            ?: categoryProp?.rich_text?.joinToString("") { it.plain_text }
            ?: "").trim()
        
        println("QRTidy-iOS: カテゴリプロパティ取得: $categoryProp")
        println("QRTidy-iOS: 現在のカテゴリ(判定値): '$currentCategory'")

        if (currentCategory.isEmpty()) {
            // 対象: 書籍, 雑誌, または一般商品(その他)
            if (codeType == ProductSearchClient.BarcodeType.BOOK || 
                codeType == ProductSearchClient.BarcodeType.MAGAZINE ||
                codeType == ProductSearchClient.BarcodeType.OTHER) {
                
                println("QRTidy-iOS: カテゴリ未設定 & コード検出($codeType) → API検索実行")
                val productInfo = productSearchClient.search(id)
                
                // 更新用プロパティの構築
                val updateProps = buildJsonObject {
                    // カテゴリ設定
                    val categoryName = when (codeType) {
                        ProductSearchClient.BarcodeType.BOOK -> "書籍"
                        ProductSearchClient.BarcodeType.MAGAZINE -> "雑誌"
                        else -> "一般" // 一般商品のデフォルトカテゴリ
                    }
                    put("カテゴリ", buildJsonObject {
                        putJsonArray("rich_text") {
                            addJsonObject { put("text", buildJsonObject { put("content", JsonPrimitive(categoryName)) }) }
                        }
                    })
                    
                    // APIヒット時の詳細情報
                    if (productInfo != null) {
                        println("QRTidy-iOS: API情報あり → 詳細プロパティ構築")
                        
                        // [物名] = タイトル
                        put("物名", buildJsonObject {
                            putJsonArray("rich_text") {
                                addJsonObject { put("text", buildJsonObject { put("content", JsonPrimitive(productInfo.title)) }) }
                            }
                        })

                        // Yahoo!検索の場合、ProductInfo のフィールドを特殊なマッピングで使用しているため注意
                        // author -> [カテゴリ] (抽出した種類)
                        // description -> [詳細] (登場作品など)
                        // toc -> [補足情報] (JAN, メーカー, サイズ, 発売日)
                        
                        // [カテゴリ] 上書き更新 (APIから種類が取れた場合)
                        if (productInfo.source == "YahooShopping" && productInfo.author.isNotEmpty()) {
                             put("カテゴリ", buildJsonObject {
                                putJsonArray("rich_text") {
                                    addJsonObject { put("text", buildJsonObject { put("content", JsonPrimitive(productInfo.author)) }) }
                                }
                            })
                        } else if (productInfo.source == "YahooShopping") {
                            // 種類が取れなかった場合は "その他"
                             put("カテゴリ", buildJsonObject {
                                putJsonArray("rich_text") {
                                    addJsonObject { put("text", buildJsonObject { put("content", JsonPrimitive("その他")) }) }
                                }
                            })
                        }

                        // [詳細]
                        val detailText = if (productInfo.source == "YahooShopping") productInfo.description else productInfo.description.take(2000)
                        // Yahoo!の場合は空文字でも更新対象に含める（以前の値をクリアするため）、他は空ならスキップ
                        if (productInfo.source == "YahooShopping" || detailText.isNotEmpty()) {
                            put("詳細", buildJsonObject {
                                putJsonArray("rich_text") {
                                    addJsonObject { put("text", buildJsonObject { put("content", JsonPrimitive(detailText)) }) }
                                }
                            })
                        }
                        
                        // [補足情報]
                        val supplement = if (productInfo.source == "YahooShopping") {
                            productInfo.toc // Yahooの場合はここに入れている
                        } else {
                            buildString {
                                if (productInfo.author.isNotEmpty()) appendLine("著者: ${productInfo.author}")
                                if (productInfo.publisher.isNotEmpty()) appendLine("出版社: ${productInfo.publisher}")
                                if (productInfo.publishedDate.isNotEmpty()) appendLine("出版日: ${productInfo.publishedDate}")
                                if (productInfo.price.isNotEmpty()) appendLine("価格: ${productInfo.price}")
                                if (productInfo.isbn.isNotEmpty()) appendLine("ISBN/JAN: ${productInfo.isbn}")
                                append("ソース: ${productInfo.source}")
                            }
                        }
                        put("補足情報", buildJsonObject {
                            putJsonArray("rich_text") {
                                addJsonObject { put("text", buildJsonObject { put("content", JsonPrimitive(supplement)) }) }
                            }
                        })
                        // 書影 (写真プロパティ)
                        if (productInfo.coverUrl.isNotEmpty()) {
                            println("QRTidy-iOS: 書影URLあり (${productInfo.coverUrl}) → 画像ダウンロード試行")
                            val imageBytes = notionClient.downloadImage(productInfo.coverUrl)
                            var fileId: String? = null
                            
                            if (imageBytes != null) {
                                println("QRTidy-iOS: 書影ダウンロード成功 → Notionアップロード試行")
                                fileId = notionClient.uploadImage(imageBytes)
                            }
                            
                            if (fileId != null) {
                                println("QRTidy-iOS: 書影アップロード成功 (ID: $fileId)")
                                put("写真", buildJsonObject {
                                    putJsonArray("files") {
                                        addJsonObject {
                                            put("type", JsonPrimitive("file_upload"))
                                            put("file_upload", buildJsonObject { put("id", JsonPrimitive(fileId)) })
                                            put("name", JsonPrimitive("Cover Image"))
                                        }
                                    }
                                })
                            } else {
                                println("QRTidy-iOS: 書影処理失敗 → External URL で設定")
                                put("写真", buildJsonObject {
                                    putJsonArray("files") {
                                        addJsonObject {
                                            put("type", JsonPrimitive("external"))
                                            put("name", JsonPrimitive("Cover Image"))
                                            put("external", buildJsonObject { put("url", JsonPrimitive(productInfo.coverUrl)) })
                                        }
                                    }
                                })
                            }
                        }
                    } else {
                        println("QRTidy-iOS: APIヒットなし → カテゴリのみ更新")
                    }
                }
                // Notion更新実行
                notionClient.updatePageProperties(page.id, updateProps)
            } else {
                println("QRTidy-iOS: カテゴリ未設定だが検索対象外コード($codeType)のためAPI検索スキップ")
            }
        } else {
             println("QRTidy-iOS: カテゴリ設定済み($currentCategory)のためAPI検索スキップ")
        }

        // 4. 画像アップロード (カメラ撮影分があれば常に追加/上書き)
        capturedImageData?.let { imgData ->
            val fileId = notionClient.uploadImage(imgData)
            if (fileId != null) notionClient.updatePageImage(page.id, fileId)
        }
        
        return page
    }

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
                        // 共通処理を呼び出し
                        val page = processHukuroScan(id)

                        // Notionページを開く
                        openUrl(page.url)
                        
                        // 表示更新
                        // 注: APIでタイトル更新した場合も、ここでの page は更新前の情報しか持っていない。
                        // 必要なら再取得するか、APIレスポンスを利用する必要があるが、一旦既存挙動(更新前または作成直後のタイトル)を表示。
                        val name = page.properties["物名"]?.rich_text?.firstOrNull()?.plain_text ?: id
                        resultTitle = name
                        statusMessage = "物を開きました"
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
                        statusMessage = "箱「$hakoName」を選択中。\n次に物をスキャンしてください。"
                        capturedImageData = null
                        capturedImageBitmap = null
                    }
                    IOSScanMode.SHIMAU_STEP2_HUKURO -> {
                        val hakoId = selectedHakoPageId ?: return@launch
                        
                        // 共通処理を呼び出し
                        val hukuroPage = processHukuroScan(id)
                        
                        // 箱に紐付け
                        notionClient.updateHukuroLocation(hukuroPage.id, hakoId)
                        
                        resultTitle = "完了"
                        statusMessage = "物を箱に紐付けました！"
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
            IOSScanMode.HUKURO_SCAN -> "物をスキャンしてください"
            IOSScanMode.HAKO_SCAN -> "箱をスキャンしてください"
            IOSScanMode.SHIMAU_STEP1_HAKO -> "【1/2】箱をスキャンしてください"
            IOSScanMode.SHIMAU_STEP2_HUKURO -> "【2/2】物をスキャンしてください"
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
                ) { Text("物スキャン") }

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
                            qrOnly = (currentMode == IOSScanMode.HAKO_SCAN || currentMode == IOSScanMode.SHIMAU_STEP1_HAKO),
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
    qrOnly: Boolean = false,
    onQrDetected: (String) -> Unit,
    onPhotoCaptured: (ByteArray) -> Unit
) {
    UIKitView<UIView>(
        factory = {
            // Swift側の QRScannerView を生成（qrOnly: 箱スキャン時はQRのみ）
            QRScannerViewControllerFactory.create(
                qrOnly = qrOnly,
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