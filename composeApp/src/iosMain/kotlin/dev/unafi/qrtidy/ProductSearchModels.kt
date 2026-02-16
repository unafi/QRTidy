package dev.unafi.qrtidy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- 統一的な商品情報 ---

data class ProductInfo(
    val isbn: String,
    val title: String,
    val author: String,
    val publisher: String,
    val price: String,
    val coverUrl: String,
    val description: String,  // 概要
    val toc: String,          // 目次
    val publishedDate: String, // 出版日
    val source: String        // "OpenBD", "GoogleBooks" etc.
)

// --- OpenBD API レスポンスモデル ---
// レスポンスは JSON配列 [{ "onix": {...}, "summary": {...}, "hanmoto": {...} }]
// null の場合（ISBNが見つからない）は [null] が返る

@Serializable
data class OpenBDBook(
    val onix: OpenBDOnix? = null,
    val summary: OpenBDSummary? = null
)

@Serializable
data class OpenBDSummary(
    val isbn: String = "",
    val title: String = "",
    val volume: String = "",
    val series: String = "",
    val publisher: String = "",
    val pubdate: String = "",
    val cover: String = "",
    val author: String = ""
)

@Serializable
data class OpenBDOnix(
    @SerialName("CollateralDetail")
    val collateralDetail: OpenBDCollateralDetail? = null,
    @SerialName("ProductSupply")
    val productSupply: OpenBDProductSupply? = null
)

@Serializable
data class OpenBDCollateralDetail(
    @SerialName("TextContent")
    val textContent: List<OpenBDTextContent>? = null
)

@Serializable
data class OpenBDTextContent(
    @SerialName("TextType")
    val textType: String = "",  // "02"=概要, "04"=目次
    @SerialName("Text")
    val text: String = ""
)

@Serializable
data class OpenBDProductSupply(
    @SerialName("SupplyDetail")
    val supplyDetail: OpenBDSupplyDetail? = null
)

@Serializable
data class OpenBDSupplyDetail(
    @SerialName("Price")
    val price: List<OpenBDPrice>? = null
)

@Serializable
data class OpenBDPrice(
    @SerialName("PriceAmount")
    val priceAmount: String = ""
)

// --- Google Books API レスポンスモデル ---

@Serializable
data class GoogleBooksResponse(
    val totalItems: Int = 0,
    val items: List<GoogleBooksItem>? = null
)

@Serializable
data class GoogleBooksItem(
    val volumeInfo: GoogleBooksVolumeInfo? = null
)

@Serializable
data class GoogleBooksVolumeInfo(
    val title: String = "",
    val authors: List<String>? = null,
    val publisher: String = "",
    val publishedDate: String = "",
    val description: String = "",
    val imageLinks: GoogleBooksImageLinks? = null
)

@Serializable
data class GoogleBooksImageLinks(
    val smallThumbnail: String = "",
    val thumbnail: String = ""
)

// --- 楽天ブックス雑誌検索 API レスポンスモデル ---
// formatVersion=2 形式

@Serializable
data class RakutenMagazineResponse(
    val count: Int = 0,
    val page: Int = 0,
    val hits: Int = 0,
    @SerialName("Items")
    val items: List<RakutenMagazineItem>? = null
)

@Serializable
data class RakutenMagazineItem(
    val title: String = "",
    val titleKana: String = "",
    val publisherName: String = "",
    val jan: String = "",
    val itemCaption: String = "",
    val salesDate: String = "",
    val cycle: String = "",           // "週刊", "月刊" etc.
    val itemPrice: Int = 0,
    val itemUrl: String = "",
    val smallImageUrl: String = "",
    val mediumImageUrl: String = "",
    val largeImageUrl: String = "",
    val availability: String = "",
    val booksGenreId: String = ""
)

// --- 楽天市場商品検索 API レスポンスモデル ---
// formatVersion=2 形式

@Serializable
data class RakutenIchibaResponse(
    val count: Int = 0,
    val page: Int = 0,
    val hits: Int = 0,
    @SerialName("Items")
    val items: List<RakutenIchibaItem>? = null
)

@Serializable
data class RakutenIchibaItem(
    val itemName: String = "",
    val itemPrice: Int = 0,
    val itemCaption: String = "",
    val itemUrl: String = "",
    val shopName: String = "",
    val shopCode: String = "",
    val genreId: String = "",
    val mediumImageUrls: List<String>? = null, // 配列で返る
    val smallImageUrls: List<String>? = null
)
