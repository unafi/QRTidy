package dev.unafi.qrtidy

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

class NotionRepository {

    private val auth = "Bearer ${SecretConfig.NOTION_API_KEY}"
    private val service = NotionClient.service
    private val TAG = "QRTidy"

    suspend fun findOrCreatePage(
        databaseId: String,
        pkColumnName: String,
        uid: String,
        defaultNameColumn: String,
        defaultNameValue: String
    ): NotionPage = withContext(Dispatchers.IO) {
        val queryRes = service.queryDatabase(
            databaseId, auth,
            request = NotionQueryRequest(Filter(property = pkColumnName, title = TitleFilter(equals = uid)))
        )

        if (queryRes.results.isNotEmpty()) {
            queryRes.results[0]
        } else {
            val props = mapOf(
                pkColumnName to mapOf("title" to listOf(mapOf("text" to mapOf("content" to uid)))),
                defaultNameColumn to mapOf("rich_text" to listOf(mapOf("text" to mapOf("content" to defaultNameValue))))
            )
            service.createPage(auth, request = NotionCreateRequest(Parent(databaseId), props))
        }
    }

    suspend fun updateHukuroLocation(hukuroPageId: String, hakoPageId: String): NotionPage = withContext(Dispatchers.IO) {
        val updateProps = mapOf(
            "現在の箱" to mapOf("relation" to listOf(mapOf("id" to hakoPageId)))
        )
        service.updatePage(hukuroPageId, auth, request = NotionUpdateRequest(updateProps))
    }

    suspend fun getPage(databaseId: String, pkColumnName: String, uid: String): NotionPage? = withContext(Dispatchers.IO) {
        val queryRes = service.queryDatabase(
            databaseId, auth,
            request = NotionQueryRequest(Filter(property = pkColumnName, title = TitleFilter(equals = uid)))
        )
        queryRes.results.firstOrNull()
    }

    /**
     * 画像をアップロードしてFileIDを返します。
     */
    suspend fun uploadImage(bitmap: android.graphics.Bitmap): String? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Notion: 画像圧縮中...")
            val outputStream = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, outputStream)
            val byteArray = outputStream.toByteArray()
            
            Log.d(TAG, "Notion: アップロード初期化中...")
            val initRes = service.initializeUpload(
                auth = auth,
                request = NotionUploadInitRequest(
                    file = NotionUploadFileDetails(name = "android_photo.jpg", type = "image/jpeg")
                )
            )
            Log.d(TAG, "Notion: 初期化成功. ID: ${initRes.file_id}")

            // GAS版に合わせて Multipart 形式で送信
            val requestFile = byteArray.toRequestBody("image/jpeg".toMediaTypeOrNull())
            val body = MultipartBody.Part.createFormData("file", "android_photo.jpg", requestFile)
            
            Log.d(TAG, "Notion: バイナリ送信中 (Multipart)...")
            service.uploadFile(initRes.url, auth, file = body)
            Log.d(TAG, "Notion: 送信完了.")

            initRes.file_id
        } catch (e: retrofit2.HttpException) {
            val errorBody = e.response()?.errorBody()?.string()
            Log.e(TAG, "Notion: HTTPエラー ${e.code()}: $errorBody")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Notion: アップロード失敗: ${e.localizedMessage}", e)
            null
        }
    }

    /**
     * ページの「写真」プロパティを更新します。
     */
    suspend fun updatePageImage(pageId: String, fileId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Notion: ページ $pageId に画像 $fileId を紐付け中...")
                
                val props = mapOf(
                    "写真" to mapOf(
                        "files" to listOf(
                            mapOf(
                                "type" to "file_upload",
                                "file_upload" to mapOf("id" to fileId),
                                "name" to "Android Scan Image"
                            )
                        )
                    )
                )

                service.updatePage(pageId, auth, request = NotionUpdateRequest(props))
                Log.d(TAG, "Notion: 紐付けリクエスト送信完了.")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Notion: 紐付け失敗: ${e.localizedMessage}", e)
                false
            }
        }
    }
}
