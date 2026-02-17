package dev.unafi.qrtidy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- Notion API用のデータ構造 (kotlinx.serialization版) ---

@Serializable
data class IOSNotionQueryRequest(val filter: IOSFilter)

@Serializable
data class IOSFilter(
    val property: String,
    val title: IOSTitleFilter? = null,
    val rich_text: IOSTextFilter? = null
)

@Serializable
data class IOSTitleFilter(val equals: String)

@Serializable
data class IOSTextFilter(val equals: String)

@Serializable
data class IOSNotionResponse(val results: List<IOSNotionPage>)

@Serializable
data class IOSNotionPage(
    val id: String,
    val properties: Map<String, IOSNotionProperty> = emptyMap(),
    val url: String = ""
)

@Serializable
data class IOSNotionProperty(
    val id: String = "",
    val type: String = "",
    val title: List<IOSNotionText>? = null,
    val rich_text: List<IOSNotionText>? = null,
    val relation: List<IOSNotionRelation>? = null,
    val select: IOSNotionSelect? = null
)

@Serializable
data class IOSNotionSelect(
    val id: String? = null,
    val name: String? = null,
    val color: String? = null
)

@Serializable
data class IOSNotionText(val plain_text: String)

@Serializable
data class IOSNotionRelation(val id: String)

@Serializable
data class IOSNotionCreateRequest(val parent: IOSParent, val properties: Map<String, kotlinx.serialization.json.JsonElement>)

@Serializable
data class IOSNotionUpdateRequest(val properties: Map<String, kotlinx.serialization.json.JsonElement>)

@Serializable
data class IOSParent(val database_id: String)

// --- ファイルアップロード用 ---
@Serializable
data class IOSNotionUploadInitRequest(
    val file: IOSNotionUploadFileDetails
)

@Serializable
data class IOSNotionUploadFileDetails(
    val name: String,
    val type: String
)

@Serializable
data class IOSNotionUploadInitResponse(
    @SerialName("upload_url") val url: String,
    @SerialName("id") val file_id: String,
    val signed_get_url: String? = null
)
