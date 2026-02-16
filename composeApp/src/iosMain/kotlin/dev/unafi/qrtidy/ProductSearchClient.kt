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
 * 現在は OpenBD のみ対応。楽天APIは後日追加予定。
 *
 * 検索フロー:
 * 1. ISBN（978/979始まり）→ OpenBD API で書籍情報を取得
 * 2. 将来: OpenBDでヒットしない場合 → 楽天APIにフォールバック
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
     * JAN/ISBNコードから商品情報を検索
     * @param scannedValue スキャンされた値（ISBN単体 or "ISBN-JAN2" の2段組形式 or 一般JAN）
     * @return 商品情報。見つからない場合はnull
     */
    suspend fun search(scannedValue: String): ProductInfo? {
        println("$TAG: ========== 商品検索開始 ==========")
        println("$TAG: スキャン値: $scannedValue")
        println("$TAG: バーコード種別: ${classifyBarcode(scannedValue)}")

        return try {
            // 2段組バーコード（"978...-192..."）の場合、ISBN部分を取り出す
            val isbn = extractISBN(scannedValue)
            println("$TAG: ISBN抽出結果: ${isbn ?: "ISBNなし"}")

            if (isbn != null) {
                // Step 1: OpenBD で基本情報を取得（ISBN がある場合のみ）
                var result = searchOpenBD(isbn)

                // Step 2: Google Books API で補完
                if (result != null) {
                    result = supplementWithGoogleBooks(result, isbn)
                    println("$TAG: ========== 検索成功（OpenBD + Google Books 補完）==========")
                    return result
                }
                println("$TAG: OpenBD: ISBNに対応するデータなし")

                // Step 3: OpenBD にない場合、Google Books のみで試行
                println("$TAG: Google Books のみで検索を試行（ISBN: $isbn）")
                val gbResult = searchGoogleBooks(isbn)
                if (gbResult != null) {
                    println("$TAG: ========== 検索成功（Google Books のみ）==========")
                    return gbResult
                }
            } else {
                println("$TAG: ISBN未検出 → 書籍検索スキップ（雑誌・一般商品は対象外）")
            }

            // TODO: 楽天API フォールバック（後日実装）
            println("$TAG: ========== 検索終了（結果なし）==========")
            null
        } catch (e: Exception) {
            println("$TAG: ========== 検索エラー ==========")
            println("$TAG: エラー種別: ${e::class.simpleName}")
            println("$TAG: エラー内容: ${e.message}")
            println("$TAG: スタックトレース: ${e.stackTraceToString()}")
            null
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
        val thumbnailUrl = volumeInfo.imageLinks?.thumbnail ?: volumeInfo.imageLinks?.smallThumbnail ?: ""

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
}
