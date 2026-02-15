package dev.unafi.qrtidy

import com.google.gson.annotations.SerializedName
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*

// --- Notion API用のデータ構造 ---
data class NotionQueryRequest(val filter: Filter)
data class Filter(
    val property: String,
    val title: TitleFilter? = null,
    val rich_text: TextFilter? = null
)
data class TitleFilter(val equals: String)
data class TextFilter(val equals: String)

data class NotionResponse(val results: List<NotionPage>)

data class NotionPage(
    val id: String, 
    val properties: Map<String, NotionProperty>,
    val url: String
)

data class NotionProperty(
    val id: String,
    val type: String,
    val title: List<NotionText>? = null,
    val rich_text: List<NotionText>? = null,
    val relation: List<NotionRelation>? = null
)

data class NotionText(val plain_text: String)
data class NotionRelation(val id: String)

data class NotionCreateRequest(val parent: Parent, val properties: Map<String, Any>)
data class NotionUpdateRequest(val properties: Map<String, Any>)
data class Parent(val database_id: String)

// --- ファイルアップロード用 ---
data class NotionUploadInitRequest(
    val file: NotionUploadFileDetails
)
data class NotionUploadFileDetails(
    val name: String,
    val type: String
)

data class NotionUploadInitResponse(
    @SerializedName("upload_url") val url: String,
    @SerializedName("id") val file_id: String,
    val signed_get_url: String? = null
)

// --- API定義 ---
interface NotionService {
    @POST("v1/databases/{db_id}/query")
    suspend fun queryDatabase(
        @Path("db_id") dbId: String,
        @Header("Authorization") auth: String,
        @Header("Notion-Version") version: String = "2022-06-28",
        @Body request: NotionQueryRequest
    ): NotionResponse

    @POST("v1/pages")
    suspend fun createPage(
        @Header("Authorization") auth: String,
        @Header("Notion-Version") version: String = "2022-06-28",
        @Body request: NotionCreateRequest
    ): NotionPage

    @PATCH("v1/pages/{page_id}")
    suspend fun updatePage(
        @Path("page_id") pageId: String,
        @Header("Authorization") auth: String,
        @Header("Notion-Version") version: String = "2022-06-28",
        @Body request: NotionUpdateRequest
    ): NotionPage

    @POST("v1/file_uploads")
    suspend fun initializeUpload(
        @Header("Authorization") auth: String,
        @Header("Notion-Version") version: String = "2022-06-28",
        @Body request: NotionUploadInitRequest
    ): NotionUploadInitResponse

    // GAS版 (Code.gs) に合わせ、POST の Multipart 形式に変更
    @Multipart
    @POST
    suspend fun uploadFile(
        @Url url: String,
        @Header("Authorization") auth: String,
        @Header("Notion-Version") version: String = "2022-06-28",
        @Part file: MultipartBody.Part
    )
}

object NotionClient {
    private val logging = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }
    private val client = OkHttpClient.Builder()
        .addInterceptor(logging)
        .build()

    val service: NotionService = Retrofit.Builder()
        .baseUrl("https://api.notion.com/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(NotionService::class.java)
}
