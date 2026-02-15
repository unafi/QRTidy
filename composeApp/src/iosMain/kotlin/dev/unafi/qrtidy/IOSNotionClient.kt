package dev.unafi.qrtidy

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.darwin.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.*

/**
 * iOS版 Notion APIクライアント (Ktor/Darwin エンジン)
 * Android版の NotionClient + NotionRepository に相当
 */
class IOSNotionClient {

    private val auth = "Bearer ${SecretConfig.NOTION_API_KEY}"
    private val TAG = "QRTidy-iOS"

    // Ktor HTTPクライアント (Darwinエンジン = iOS ネイティブの URLSession を使用)
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

    // --- データベースクエリ ---
    suspend fun queryDatabase(databaseId: String, filter: IOSFilter): IOSNotionResponse {
        val response = client.post("https://api.notion.com/v1/databases/$databaseId/query") {
            header("Authorization", auth)
            header("Notion-Version", "2022-06-28")
            contentType(ContentType.Application.Json)
            setBody(IOSNotionQueryRequest(filter))
        }
        return response.body()
    }

    // --- ページ作成 ---
    suspend fun createPage(databaseId: String, properties: Map<String, JsonElement>): IOSNotionPage {
        val requestBody = IOSNotionCreateRequest(
            parent = IOSParent(databaseId),
            properties = properties
        )
        val response = client.post("https://api.notion.com/v1/pages") {
            header("Authorization", auth)
            header("Notion-Version", "2022-06-28")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }
        return response.body()
    }

    // --- ページ更新 ---
    suspend fun updatePage(pageId: String, properties: Map<String, JsonElement>): IOSNotionPage {
        val requestBody = IOSNotionUpdateRequest(properties = properties)
        val response = client.patch("https://api.notion.com/v1/pages/$pageId") {
            header("Authorization", auth)
            header("Notion-Version", "2022-06-28")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }
        return response.body()
    }

    // --- ページ検索/作成 ---
    suspend fun findOrCreatePage(
        databaseId: String,
        pkColumnName: String,
        uid: String,
        defaultNameColumn: String,
        defaultNameValue: String
    ): IOSNotionPage {
        val queryRes = queryDatabase(
            databaseId,
            IOSFilter(property = pkColumnName, title = IOSTitleFilter(equals = uid))
        )

        return if (queryRes.results.isNotEmpty()) {
            queryRes.results[0]
        } else {
            val props = buildJsonObject {
                put(pkColumnName, buildJsonObject {
                    putJsonArray("title") {
                        addJsonObject {
                            put("text", buildJsonObject { put("content", JsonPrimitive(uid)) })
                        }
                    }
                })
                put(defaultNameColumn, buildJsonObject {
                    putJsonArray("rich_text") {
                        addJsonObject {
                            put("text", buildJsonObject { put("content", JsonPrimitive(defaultNameValue)) })
                        }
                    }
                })
            }
            createPage(databaseId, props)
        }
    }

    // --- リレーション更新 ---
    suspend fun updateHukuroLocation(hukuroPageId: String, hakoPageId: String): IOSNotionPage {
        val props = buildJsonObject {
            put("現在の箱", buildJsonObject {
                putJsonArray("relation") {
                    addJsonObject { put("id", JsonPrimitive(hakoPageId)) }
                }
            })
        }
        return updatePage(hukuroPageId, props)
    }

    // --- ページ取得 ---
    suspend fun getPage(databaseId: String, pkColumnName: String, uid: String): IOSNotionPage? {
        val queryRes = queryDatabase(
            databaseId,
            IOSFilter(property = pkColumnName, title = IOSTitleFilter(equals = uid))
        )
        return queryRes.results.firstOrNull()
    }

    // --- 画像アップロード ---
    suspend fun uploadImage(imageData: ByteArray): String? {
        return try {
            println("$TAG: Notion: アップロード初期化中...")
            // 1. アップロード初期化
            val initResponse = client.post("https://api.notion.com/v1/file_uploads") {
                header("Authorization", auth)
                header("Notion-Version", "2022-06-28")
                contentType(ContentType.Application.Json)
                setBody(IOSNotionUploadInitRequest(
                    file = IOSNotionUploadFileDetails(name = "ios_photo.jpg", type = "image/jpeg")
                ))
            }
            val initResult: IOSNotionUploadInitResponse = initResponse.body()
            println("$TAG: Notion: 初期化成功. ID: ${initResult.file_id}")

            // 2. Multipartでファイル送信
            println("$TAG: Notion: バイナリ送信中 (Multipart)...")
            client.post(initResult.url) {
                header("Authorization", auth)
                header("Notion-Version", "2022-06-28")
                setBody(MultiPartFormDataContent(formData {
                    append("file", imageData, Headers.build {
                        append(HttpHeaders.ContentType, "image/jpeg")
                        append(HttpHeaders.ContentDisposition, "filename=\"ios_photo.jpg\"")
                    })
                }))
            }
            println("$TAG: Notion: 送信完了.")
            initResult.file_id
        } catch (e: Exception) {
            println("$TAG: Notion: アップロード失敗: ${e.message}")
            null
        }
    }

    // --- ページの写真プロパティ更新 ---
    suspend fun updatePageImage(pageId: String, fileId: String): Boolean {
        return try {
            println("$TAG: Notion: ページ $pageId に画像 $fileId を紐付け中...")
            val props = buildJsonObject {
                put("写真", buildJsonObject {
                    putJsonArray("files") {
                        addJsonObject {
                            put("type", JsonPrimitive("file_upload"))
                            put("file_upload", buildJsonObject { put("id", JsonPrimitive(fileId)) })
                            put("name", JsonPrimitive("iOS Scan Image"))
                        }
                    }
                })
            }
            updatePage(pageId, props)
            println("$TAG: Notion: 紐付けリクエスト送信完了.")
            true
        } catch (e: Exception) {
            println("$TAG: Notion: 紐付け失敗: ${e.message}")
            false
        }
    }
}
