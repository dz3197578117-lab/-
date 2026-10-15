package top.wkbin.zaomeng.data.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import top.wkbin.zaomeng.data.api.RunsResponse
import top.wkbin.zaomeng.ktor.services.StorageService

/**
 * 书卷列表：**进程内直连**（原 `KtorRunsClient`）。
 *
 * 原先 GET /api/web/runs 只是把 `StorageService.listRunManifests()` 包成 `{"items":[...]}`，
 * 客户端再解码成 [RunsResponse]。这里保留同样的 JSON 形状与解码配置，
 * 只是去掉了 HTTP 往返。
 */
class RunsClient(
    private val storage: StorageService,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun list(): RunsResponse = json.decodeFromJsonElement(
        buildJsonObject {
            put(
                "items",
                buildJsonArray { storage.listRunManifests().forEach { add(it) } },
            )
        },
    )
}
