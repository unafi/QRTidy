package dev.unafi.qrtidy

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.darwin.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.*

/**
 * 商品情報検索クライアント（iOS版）
 *
 * JAN/ISBNコードから商品情報を取得する。
 *
 * 検索フロー:
 * 1. ISBN（978/979始まり）→ OpenBD API → Google Books 補完
 * 2. 雑誌JAN（491始まり）→ 楽天ブックス雑誌検索API
 * 3. 将来: 一般商品JAN → 楽天商品検索API
 */
class ProductSearchClient {

    private val TAG = "QRTidy-iOS"

    private val client = HttpClient(Darwin) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * バーコードの種類を判別する
     */
    fun classifyBarcodeType(scannedValue: String): BarcodeType {
        // ISBN (978/979)
        val isbn = extractISBN(scannedValue)
        if (isbn != null) return BarcodeType.BOOK

        // JAN (491) -> 雑誌
        val jan = extractJANCode(scannedValue)
        if (jan != null && jan.startsWith("491")) return BarcodeType.MAGAZINE

        // その他 JAN
        if (jan != null) return BarcodeType.OTHER

        return BarcodeType.UNKNOWN
    }

    enum class BarcodeType {
        BOOK, MAGAZINE, OTHER, UNKNOWN
    }

    /**
     * JAN/ISBNコードから商品情報を検索
     * @param scannedValue スキャンされた値（ISBN単体 or "ISBN-JAN2" の2段組形式 or 一般JAN）
     * @return 商品情報。見つからない場合はnull
     */
    suspend fun search(scannedValue: String): ProductInfo? {
        val type = classifyBarcodeType(scannedValue)
        println("$TAG: ========== 商品検索開始 ==========")
        println("$TAG: スキャン値: $scannedValue")
        println("$TAG: バーコード種別: $type")

        return try {
            when (type) {
                BarcodeType.BOOK -> {
                    val isbn = extractISBN(scannedValue) ?: return null
                    println("$TAG: ISBN抽出結果: $isbn")
                    // 1. OpenBD で検索
                    var info = searchOpenBD(isbn)
                    
                    // 2. Google Books で補完
                    if (info != null) {
                         info = supplementWithGoogleBooks(info, isbn)
                    } else {
                        // OpenBDで見つからない場合、Google Books単体で検索
                        println("$TAG: [OpenBD] ヒットなし → Google Books API で検索試行")
                        info = searchGoogleBooks(isbn)
                    }
                    
                    if (info != null) {
                        println("$TAG: ========== 検索成功（書籍）==========")
                        return info
                    }
                    
                    // 3. 書籍APIで見つからない場合、Yahoo!ショッピングで検索（フォールバック）
                    println("$TAG: 書籍APIヒットなし → Yahoo!ショッピングで再検索")
                    val yahooResult = searchYahooShopping(isbn)
                    if (yahooResult != null) {
                         println("$TAG: ========== 検索成功（Yahoo!フォールバック）==========")
                         return yahooResult
                    }
                    
                    return null
                }
                BarcodeType.MAGAZINE -> {
                    val janCode = extractJANCode(scannedValue) ?: return null
                    println("$TAG: 雑誌JAN検出 → 楽天ブックス雑誌検索APIで検索")
                    val rakutenResult = searchRakutenMagazine(janCode)
                    if (rakutenResult != null) {
                        println("$TAG: ========== 検索成功（楽天ブックス雑誌検索）==========")
                        return rakutenResult
                    }
                    
                    // 雑誌APIで見つからない場合、Yahoo!ショッピングで検索（フォールバック）
                    println("$TAG: 楽天ブックス雑誌検索ヒットなし → Yahoo!ショッピングで再検索")
                    val yahooResult = searchYahooShopping(janCode)
                    if (yahooResult != null) {
                         println("$TAG: ========== 検索成功（Yahoo!フォールバック）==========")
                         return yahooResult
                    }
                    
                    return null
                }
                BarcodeType.OTHER -> {
                    println("$TAG: 一般商品JAN ($scannedValue) → Yahoo!ショッピングAPIで検索")
                    val yahooResult = searchYahooShopping(scannedValue)
                    if (yahooResult != null) {
                        println("$TAG: ========== 検索成功（Yahoo!一般商品）==========")
                        return yahooResult
                    }

                    // Yahoo!で見つからない場合、楽天市場APIで検索（最終手段）
                    println("$TAG: Yahoo!検索ヒットなし → 楽天市場APIで検索")
                    val rakutenResult = searchRakutenIchiba(scannedValue)
                    if (rakutenResult != null) {
                        println("$TAG: ========== 検索成功（楽天一般商品）==========")
                        return rakutenResult
                    }
                    
                    return null
                }
                else -> {
                    println("$TAG: 未知のコード形式 → 検索スキップ")
                    null
                }
            }
        } catch (e: Exception) {
            println("$TAG: 検索エラー: ${e.message}")
            e.printStackTrace()
            null
        } finally {
            println("$TAG: ========== 検索終了 ==========")
        }
    }

    /**
     * OpenBD API で書籍情報を取得
     */
    private suspend fun searchOpenBD(isbn: String): ProductInfo? {
        val requestUrl = "https://api.openbd.jp/v1/get?isbn=$isbn"
        println("$TAG: [OpenBD] リクエスト: GET $requestUrl")

        val response: HttpResponse = client.get("https://api.openbd.jp/v1/get") {
            parameter("isbn", isbn)
        }

        println("$TAG: [OpenBD] HTTPステータス: ${response.status}")

        val bodyText = response.bodyAsText()
        println("$TAG: [OpenBD] レスポンスサイズ: ${bodyText.length} 文字")

        // レスポンスが短ければ全文出力（デバッグ用）
        if (bodyText.length < 2000) {
            println("$TAG: [OpenBD] レスポンス全文: $bodyText")
        } else {
            println("$TAG: [OpenBD] レスポンス先頭500文字: ${bodyText.take(500)}")
        }

        val jsonArray = try {
            json.parseToJsonElement(bodyText).jsonArray
        } catch (e: Exception) {
            println("$TAG: [OpenBD] JSONパースエラー: ${e.message}")
            return null
        }

        // レスポンスは [null] の場合がある（ISBNが見つからない）
        if (jsonArray.isEmpty() || jsonArray[0] is JsonNull) {
            println("$TAG: [OpenBD] レスポンスが空またはnull → ISBN未登録")
            return null
        }

        val book: OpenBDBook = try {
            json.decodeFromJsonElement(jsonArray[0])
        } catch (e: Exception) {
            println("$TAG: [OpenBD] データモデル変換エラー: ${e.message}")
            return null
        }

        val summary = book.summary
        if (summary == null) {
            println("$TAG: [OpenBD] summaryセクションが存在しない")
            return null
        }

        // 価格情報の取得
        val price = book.onix?.productSupply?.supplyDetail?.price
            ?.firstOrNull()?.priceAmount ?: ""

        // 概要と目次の取得（CollateralDetail にある場合）
        val textContents = book.onix?.collateralDetail?.textContent ?: emptyList()
        val description = textContents.filter { it.textType == "02" || it.textType == "03" }
            .joinToString("\n") { it.text }
        val toc = textContents.filter { it.textType == "04" }
            .joinToString("\n") { it.text }

        // --- 取得結果を詳細ログ出力 ---
        println("$TAG: [OpenBD] ── 取得データ詳細 ──")
        println("$TAG: [OpenBD]   ISBN:     ${summary.isbn}")
        println("$TAG: [OpenBD]   タイトル:  ${summary.title}")
        println("$TAG: [OpenBD]   著者:     ${summary.author.ifEmpty { "(なし)" }}")
        println("$TAG: [OpenBD]   出版社:   ${summary.publisher}")
        println("$TAG: [OpenBD]   価格:     ¥$price")
        println("$TAG: [OpenBD]   書影URL:  ${summary.cover.ifEmpty { "(なし)" }}")
        println("$TAG: [OpenBD]   シリーズ:  ${summary.series.ifEmpty { "(なし)" }}")
        println("$TAG: [OpenBD]   出版日:   ${summary.pubdate.ifEmpty { "(なし)" }}")
        println("$TAG: [OpenBD]   概要:     ${if (description.isNotEmpty()) "${description.take(200)}..." else "(なし)"}")
        println("$TAG: [OpenBD]   目次:     ${if (toc.isNotEmpty()) "${toc.take(200)}..." else "(なし)"}")
        println("$TAG: [OpenBD]   TextContent件数: ${textContents.size}")
        textContents.forEachIndexed { i, tc ->
            println("$TAG: [OpenBD]     [$i] type=${tc.textType} text=${tc.text.take(100)}")
        }
        println("$TAG: [OpenBD] ── 詳細ここまで ──")

        return ProductInfo(
            isbn = summary.isbn,
            title = summary.title,
            author = summary.author,
            publisher = summary.publisher,
            price = price,
            coverUrl = summary.cover,
            description = description,
            toc = toc,
            publishedDate = summary.pubdate,
            source = "OpenBD"
        )
    }

    /**
     * Google Books API で書籍情報を取得
     * 登録不要・無料（1日1,000リクエストまで）
     */
    private suspend fun searchGoogleBooks(isbn: String): ProductInfo? {
        val requestUrl = "https://www.googleapis.com/books/v1/volumes?q=isbn:$isbn"
        println("$TAG: [GoogleBooks] リクエスト: GET $requestUrl")

        val response: HttpResponse = try {
            client.get("https://www.googleapis.com/books/v1/volumes") {
                parameter("q", "isbn:$isbn")
            }
        } catch (e: Exception) {
            println("$TAG: [GoogleBooks] HTTP通信エラー: ${e::class.simpleName}: ${e.message}")
            return null
        }

        println("$TAG: [GoogleBooks] HTTPステータス: ${response.status}")

        val bodyText = response.bodyAsText()
        println("$TAG: [GoogleBooks] レスポンスサイズ: ${bodyText.length} 文字")

        if (bodyText.length < 3000) {
            println("$TAG: [GoogleBooks] レスポンス全文: $bodyText")
        } else {
            println("$TAG: [GoogleBooks] レスポンス先頭500文字: ${bodyText.take(500)}")
        }

        val gbResponse: GoogleBooksResponse = try {
            json.decodeFromString(bodyText)
        } catch (e: Exception) {
            println("$TAG: [GoogleBooks] JSONパースエラー: ${e::class.simpleName}: ${e.message}")
            return null
        }

        println("$TAG: [GoogleBooks] 検索ヒット数: ${gbResponse.totalItems}")

        if (gbResponse.totalItems == 0 || gbResponse.items.isNullOrEmpty()) {
            println("$TAG: [GoogleBooks] ヒットなし")
            return null
        }

        val volumeInfo = gbResponse.items[0].volumeInfo
        if (volumeInfo == null) {
            println("$TAG: [GoogleBooks] volumeInfo が存在しない")
            return null
        }

        val authors = volumeInfo.authors?.joinToString(", ") ?: ""
        val thumbnailUrl = (volumeInfo.imageLinks?.thumbnail ?: volumeInfo.imageLinks?.smallThumbnail ?: "")
            .replace("http://", "https://")

        // --- 取得結果を詳細ログ出力 ---
        println("$TAG: [GoogleBooks] ── 取得データ詳細 ──")
        println("$TAG: [GoogleBooks]   タイトル:    ${volumeInfo.title}")
        println("$TAG: [GoogleBooks]   著者:       ${authors.ifEmpty { "(なし)" }}")
        println("$TAG: [GoogleBooks]   出版社:     ${volumeInfo.publisher.ifEmpty { "(なし)" }}")
        println("$TAG: [GoogleBooks]   出版日:     ${volumeInfo.publishedDate.ifEmpty { "(なし)" }}")
        println("$TAG: [GoogleBooks]   概要:       ${if (volumeInfo.description.isNotEmpty()) "${volumeInfo.description.take(200)}..." else "(なし)"}")
        println("$TAG: [GoogleBooks]   書影URL:    ${thumbnailUrl.ifEmpty { "(なし)" }}")
        println("$TAG: [GoogleBooks] ── 詳細ここまで ──")

        return ProductInfo(
            isbn = isbn,
            title = volumeInfo.title,
            author = authors,
            publisher = volumeInfo.publisher,
            price = "",  // Google Books には日本円の価格情報はない
            coverUrl = thumbnailUrl,
            description = volumeInfo.description,
            toc = "",
            publishedDate = volumeInfo.publishedDate,
            source = "GoogleBooks"
        )
    }

    /**
     * OpenBD で取得した情報を Google Books API で補完する
     * 空のフィールド（書影URL、概要、出版日、著者）を Google Books から埋める
     */
    private suspend fun supplementWithGoogleBooks(openBDResult: ProductInfo, isbn: String): ProductInfo {
        // 補完が必要か判定
        val needsCover = openBDResult.coverUrl.isEmpty()
        val needsDescription = openBDResult.description.isEmpty()
        val needsPublishedDate = openBDResult.publishedDate.isEmpty()
        val needsAuthor = openBDResult.author.isEmpty()

        if (!needsCover && !needsDescription && !needsPublishedDate && !needsAuthor) {
            println("$TAG: [補完] OpenBD データが完全 → Google Books 補完不要")
            return openBDResult
        }

        println("$TAG: [補完] OpenBD で不足しているフィールド:")
        if (needsCover) println("$TAG: [補完]   - 書影URL")
        if (needsDescription) println("$TAG: [補完]   - 概要")
        if (needsPublishedDate) println("$TAG: [補完]   - 出版日")
        if (needsAuthor) println("$TAG: [補完]   - 著者")
        println("$TAG: [補完] Google Books API で補完を試行...")

        val gbResult = searchGoogleBooks(isbn) ?: run {
            println("$TAG: [補完] Google Books からデータ取得できず → OpenBD データのみ使用")
            return openBDResult
        }

        // 不足フィールドのみ Google Books の値で上書き
        val supplemented = openBDResult.copy(
            coverUrl = if (needsCover && gbResult.coverUrl.isNotEmpty()) gbResult.coverUrl else openBDResult.coverUrl,
            description = if (needsDescription && gbResult.description.isNotEmpty()) gbResult.description else openBDResult.description,
            publishedDate = if (needsPublishedDate && gbResult.publishedDate.isNotEmpty()) gbResult.publishedDate else openBDResult.publishedDate,
            author = if (needsAuthor && gbResult.author.isNotEmpty()) gbResult.author else openBDResult.author,
            source = "OpenBD+GoogleBooks"
        )

        // 補完結果のログ
        println("$TAG: [補完] ── 補完後の最終データ ──")
        println("$TAG: [補完]   ISBN:      ${supplemented.isbn}")
        println("$TAG: [補完]   タイトル:   ${supplemented.title}")
        println("$TAG: [補完]   著者:      ${supplemented.author.ifEmpty { "(なし)" }} ${if (needsAuthor && gbResult.author.isNotEmpty()) "← GoogleBooks" else ""}")
        println("$TAG: [補完]   出版社:    ${supplemented.publisher}")
        println("$TAG: [補完]   価格:      ¥${supplemented.price}")
        println("$TAG: [補完]   書影URL:   ${supplemented.coverUrl.ifEmpty { "(なし)" }} ${if (needsCover && gbResult.coverUrl.isNotEmpty()) "← GoogleBooks" else ""}")
        println("$TAG: [補完]   出版日:    ${supplemented.publishedDate.ifEmpty { "(なし)" }} ${if (needsPublishedDate && gbResult.publishedDate.isNotEmpty()) "← GoogleBooks" else ""}")
        println("$TAG: [補完]   概要:      ${if (supplemented.description.isNotEmpty()) "${supplemented.description.take(200)}..." else "(なし)"} ${if (needsDescription && gbResult.description.isNotEmpty()) "← GoogleBooks" else ""}")
        println("$TAG: [補完]   目次:      ${if (supplemented.toc.isNotEmpty()) "${supplemented.toc.take(200)}..." else "(なし)"}")
        println("$TAG: [補完]   データソース: ${supplemented.source}")
        println("$TAG: [補完] ── 補完ここまで ──")

        return supplemented
    }

    /**
     * 楽天ブックス雑誌検索 API で雑誌情報を取得
     * JAN コード (491xxx) で検索する
     * 認証: applicationId + accessKey + Origin/Referer ヘッダー
     */
    private suspend fun searchRakutenMagazine(janCode: String): ProductInfo? {
        val baseUrl = "https://openapi.rakuten.co.jp/services/api/BooksMagazine/Search/20170404"
        println("$TAG: [楽天雑誌] リクエスト: GET $baseUrl?jan=$janCode")

        val response: HttpResponse = try {
            client.get(baseUrl) {
                parameter("applicationId", SecretConfig.RAKUTEN_APP_ID)
                parameter("accessKey", SecretConfig.RAKUTEN_API_KEY)
                parameter("jan", janCode)
                parameter("formatVersion", "2")
                parameter("format", "json")
                parameter("hits", "1")
                parameter("outOfStockFlag", "1") // 品切れ・絶版本も検索対象にする
                // 楽天 OpenAPI はリファラー制限あり → Origin/Referer ヘッダー必須
                headers {
                    append("Origin", "https://github.com")
                    append("Referer", "https://github.com/")
                }
            }
        } catch (e: Exception) {
            println("$TAG: [楽天雑誌] HTTP通信エラー: ${e::class.simpleName}: ${e.message}")
            return null
        }

        println("$TAG: [楽天雑誌] HTTPステータス: ${response.status}")

        val bodyText = response.bodyAsText()
        println("$TAG: [楽天雑誌] レスポンスサイズ: ${bodyText.length} 文字")

        if (bodyText.length < 3000) {
            println("$TAG: [楽天雑誌] レスポンス全文: $bodyText")
        } else {
            println("$TAG: [楽天雑誌] レスポンス先頭500文字: ${bodyText.take(500)}")
        }

        val rakutenResponse: RakutenMagazineResponse = try {
            json.decodeFromString(bodyText)
        } catch (e: Exception) {
            println("$TAG: [楽天雑誌] JSONパースエラー: ${e::class.simpleName}: ${e.message}")
            return null
        }

        println("$TAG: [楽天雑誌] 検索ヒット数: ${rakutenResponse.count}")

        if (rakutenResponse.count == 0 || rakutenResponse.items.isNullOrEmpty()) {
            println("$TAG: [楽天雑誌] ヒットなし")
            return null
        }

        val item = rakutenResponse.items[0]

        // --- 取得結果を詳細ログ出力 ---
        println("$TAG: [楽天雑誌] ── 取得データ詳細 ──")
        println("$TAG: [楽天雑誌]   タイトル:    ${item.title}")
        println("$TAG: [楽天雑誌]   出版社:     ${item.publisherName}")
        println("$TAG: [楽天雑誌]   JAN:       ${item.jan}")
        println("$TAG: [楽天雑誌]   発売日:     ${item.salesDate}")
        println("$TAG: [楽天雑誌]   刊行頻度:   ${item.cycle.ifEmpty { "(なし)" }}")
        println("$TAG: [楽天雑誌]   価格:       ¥${item.itemPrice}")
        println("$TAG: [楽天雑誌]   書影URL:    ${item.largeImageUrl.ifEmpty { "(なし)" }}")
        println("$TAG: [楽天雑誌]   商品URL:    ${item.itemUrl}")
        println("$TAG: [楽天雑誌]   概要:       ${item.itemCaption.ifEmpty { "(なし)" }}")
        println("$TAG: [楽天雑誌]   在庫状況:   ${item.availability}")
        println("$TAG: [楽天雑誌] ── 詳細ここまで ──")

        return ProductInfo(
            isbn = item.jan,  // 雑誌はISBNではなくJANコード
            title = item.title,
            author = "",  // 雑誌に著者はない
            publisher = item.publisherName,
            price = item.itemPrice.toString(),
            coverUrl = item.largeImageUrl,
            description = item.itemCaption,
            toc = "",
            publishedDate = item.salesDate,
            source = "楽天ブックス"
        )
    }

    /**
     * 楽天市場商品検索 API で商品情報を取得
     * 一般 JAN (45xxx, 49xxx) や雑誌のフォールバック用
     * keyword パラメータに JAN コードを指定して検索
     */
    private suspend fun searchRakutenIchiba(janCode: String): ProductInfo? {
        val baseUrl = "https://openapi.rakuten.co.jp/ichibams/api/IchibaItem/Search/20220601"
        println("$TAG: [楽天市場] リクエスト: GET $baseUrl?keyword=$janCode")

        val response: HttpResponse = try {
            client.get(baseUrl) {
                parameter("applicationId", SecretConfig.RAKUTEN_APP_ID)
                parameter("accessKey", SecretConfig.RAKUTEN_API_KEY)
                parameter("keyword", janCode) // JANコードをキーワードとして指定
                parameter("formatVersion", "2")
                parameter("format", "json")
                parameter("hits", "1")
                // 楽天 OpenAPI はリファラー制限あり
                headers {
                    append("Origin", "https://github.com")
                    append("Referer", "https://github.com/")
                }
            }
        } catch (e: Exception) {
            println("$TAG: [楽天市場] HTTP通信エラー: ${e::class.simpleName}: ${e.message}")
            return null
        }

        val bodyText = response.bodyAsText()
        if (bodyText.length < 3000) {
            println("$TAG: [楽天市場] レスポンス全文: $bodyText")
        } else {
            println("$TAG: [楽天市場] レスポンス先頭500文字: ${bodyText.take(500)}")
        }

        val rakutenResponse: RakutenIchibaResponse = try {
            json.decodeFromString(bodyText)
        } catch (e: Exception) {
            println("$TAG: [楽天市場] JSONパースエラー: ${e::class.simpleName}: ${e.message}")
            return null
        }

        println("$TAG: [楽天市場] 検索ヒット数: ${rakutenResponse.count}")

        if (rakutenResponse.count == 0 || rakutenResponse.items.isNullOrEmpty()) {
            println("$TAG: [楽天市場] ヒットなし")
            return null
        }

        val item = rakutenResponse.items[0]
        // 画像URLの取得（配列の最初の要素）
        val imageUrl = item.mediumImageUrls?.firstOrNull() ?: item.smallImageUrls?.firstOrNull() ?: ""

        // --- 取得結果を詳細ログ出力 ---
        println("$TAG: [楽天市場] ── 取得データ詳細 ──")
        println("$TAG: [楽天市場]   商品名:     ${item.itemName}")
        println("$TAG: [楽天市場]   価格:       ¥${item.itemPrice}")
        println("$TAG: [楽天市場]   ショップ:    ${item.shopName}")
        println("$TAG: [楽天市場]   商品URL:    ${item.itemUrl}")
        println("$TAG: [楽天市場]   画像URL:    ${imageUrl.ifEmpty { "(なし)" }}")
        println("$TAG: [楽天市場]   説明:       ${item.itemCaption.take(50).replace("\n", "")}...")
        println("$TAG: [楽天市場] ── 詳細ここまで ──")

        return ProductInfo(
            isbn = janCode,
            title = item.itemName,
            author = "",
            publisher = item.shopName, // 出版社の代わりにショップ名を入れる
            price = item.itemPrice.toString(),
            coverUrl = imageUrl,
            description = item.itemCaption,
            toc = "",
            publishedDate = "",
            source = "楽天市場"
        )
    }

    /**
     * スキャン値からISBNを抽出
     * - "9784047914742" → そのまま
     * - "9784047914742-1920045018009" → ハイフン前のISBN部分
     * - "4901234567890" (一般JAN) → null
     */
    private fun extractISBN(value: String): String? {
        // 2段組バーコードの場合、ハイフンで分割してISBN部分を取得
        val parts = value.split("-")
        println("$TAG: スキャン値分解: parts=${parts.joinToString(", ")}")
        for (part in parts) {
            if (part.startsWith("978") || part.startsWith("979")) {
                return part
            }
        }
        return null
    }

    /**
     * スキャン値からJANコード（EAN-13）を抽出
     * ISBN以外のコード（雑誌491xxx、一般商品45xxxなど）も含む
     * - "4910xxx..." (雑誌) → そのまま
     * - "4901xxx..." (一般商品) → そのまま
     * - "978xxx-192xxx" (2段組) → null（extractISBNで処理するため）
     */
    private fun extractJANCode(value: String): String? {
        // ハイフンを含む場合は2段組バーコード → ISBN側で処理済み
        if (value.contains("-")) return null
        // 13桁の数字ならEAN-13として扱う
        if (value.length == 13 && value.all { it.isDigit() }) {
            return value
        }
        // 8桁の数字ならEAN-8として扱う
        if (value.length == 8 && value.all { it.isDigit() }) {
            return value
        }
        return null
    }

    /**
     * バーコード種別を判定（ログ表示用）
     */
    private fun classifyBarcode(value: String): String {
        val parts = value.split("-")

        // 2段組バーコード
        if (parts.size == 2) {
            val hasISBN = parts.any { it.startsWith("978") || it.startsWith("979") }
            val hasBookJAN2 = parts.any { it.startsWith("192") || it.startsWith("191") }
            if (hasISBN && hasBookJAN2) return "書籍2段組バーコード (ISBN+書籍JAN2)"
            if (hasISBN) return "ISBN含む2段組バーコード"
            return "2段組バーコード（不明）"
        }

        // 単一バーコード
        val code = parts[0]
        if (code.startsWith("978") || code.startsWith("979")) return "ISBN (書籍)"
        if (code.startsWith("491")) return "雑誌JAN (491)"
        if (code.startsWith("192")) return "書籍JAN2段目・図書 (192)"
        if (code.startsWith("191")) return "書籍JAN2段目・雑誌 (191)"
        if (code.startsWith("49") || code.startsWith("45")) return "一般商品JAN (日本)"
        if (code.length == 13) return "EAN-13 (その他)"
        if (code.length == 8) return "EAN-8"
        return "不明 (${code.length}桁)"
    }

    /**
     * Yahoo!ショッピング商品検索 API (v3) で商品情報を取得
     * 一般JANコードなどで検索
     */
    private suspend fun searchYahooShopping(janCode: String): ProductInfo? {
        val baseUrl = "https://shopping.yahooapis.jp/ShoppingWebService/V3/itemSearch"
        println("$TAG: [Yahoo!] リクエスト: GET $baseUrl?jan_code=$janCode")

        val response: HttpResponse = try {
            client.get(baseUrl) {
                parameter("appid", SecretConfig.YAHOO_CLIENT_ID)
                parameter("jan_code", janCode)
                parameter("sort", "-price") // 価格高い順（信頼できそうなショップが上位に来ることを期待）
                parameter("results", "1")
            }
        } catch (e: Exception) {
            println("$TAG: [Yahoo!] HTTP通信エラー: ${e::class.simpleName}: ${e.message}")
            return null
        }

        val bodyText = response.bodyAsText()
        if (bodyText.length < 3000) {
            println("$TAG: [Yahoo!] レスポンス全文: $bodyText")
        } else {
            println("$TAG: [Yahoo!] レスポンス先頭500文字: ${bodyText.take(500)}")
        }

        val yahooResponse: YahooShoppingResponse = try {
            json.decodeFromString(bodyText)
        } catch (e: Exception) {
            println("$TAG: [Yahoo!] JSONパースエラー: ${e::class.simpleName}: ${e.message}")
            return null
        }

        println("$TAG: [Yahoo!] 検索ヒット数: ${yahooResponse.totalResultsAvailable} (取得数: ${yahooResponse.hits.size})")

        if (yahooResponse.hits.isEmpty()) {
            println("$TAG: [Yahoo!] ヒットなし")
            return null
        }

        val item = yahooResponse.hits[0]
        val imageUrl = item.image?.medium ?: item.image?.small ?: ""
        val brandName = item.brand?.name ?: ""

        // 詳細情報の抽出ロジック（ユーザー指定の切り出し方法）
        // [物名] = ■タイトル: の後ろ 〜 <br> の直前まで
        // [補足情報] = ■JAN/EAN:, ■メーカー:, ■サイズ:, ■発売日:
        
        val rawDescription = item.description // <br> を置換せずにそのまま使用

        fun extractValue(key: String): String {
            val startMarker = "■$key:"
            val startIndex = rawDescription.indexOf(startMarker)
            if (startIndex == -1) return ""
            
            val contentStart = startIndex + startMarker.length
            val endIndex = rawDescription.indexOf("<br>", contentStart)
            
            // <br>が見つからない場合は末尾まで、見つかればそこまで
            return if (endIndex != -1) {
                rawDescription.substring(contentStart, endIndex).trim()
            } else {
                rawDescription.substring(contentStart).trim()
            }
        }

        // 項目の抽出
        val extractedTitle = extractValue("タイトル")
        val itemType = extractValue("機種").ifEmpty { extractValue("商品形態") }.ifEmpty { "その他" } // [カテゴリ]用
        
        val janEan = extractValue("JAN/EAN").ifEmpty { extractValue("JAN") }
        val manufacturer = extractValue("メーカー").ifEmpty { brandName }
        val size = extractValue("サイズ")
        val releaseDate = extractValue("発売日")
        
        // [補足説明]
        val supplementInfo = buildString {
            appendLine("JAN/EAN: ${janEan.ifEmpty { item.janCode.ifEmpty { janCode } }}")
            if (manufacturer.isNotEmpty()) appendLine("メーカー: $manufacturer")
            if (size.isNotEmpty()) appendLine("サイズ: $size")
            if (releaseDate.isNotEmpty()) appendLine("発売日: $releaseDate")
        }.trim()

        // --- 取得結果を詳細ログ出力 ---
        println("$TAG: [Yahoo!] ── 取得データ詳細 ──")
        println("$TAG: [Yahoo!]   タイトル(抽出): $extractedTitle")
        println("$TAG: [Yahoo!]   商品名(元):   ${item.name}")
        println("$TAG: [Yahoo!]   種類(カテゴリ): $itemType")
        println("$TAG: [Yahoo!]   メーカー:     $manufacturer")
        println("$TAG: [Yahoo!]   価格:         ¥${item.price}")
        println("$TAG: [Yahoo!] ── 詳細ここまで ──")

        return ProductInfo(
            isbn = item.janCode.ifEmpty { janCode },
            title = extractedTitle.ifEmpty { item.name }, // 抽出できたタイトルがあればそれを使用、なければ元の商品名
            author = itemType,           // [カテゴリ]
            publisher = manufacturer,
            price = item.price.toString(),
            coverUrl = imageUrl,
            description = "",            // [詳細] は空のままにする（ユーザー指示）
            toc = supplementInfo,        // [補足情報]
            publishedDate = releaseDate,
            source = "YahooShopping"
        )
    }
}
