package top.wkbin.zaomeng.data.local

import top.wkbin.zaomeng.data.api.OriginalKnowledgeSearchResponse
import top.wkbin.zaomeng.ktor.services.OriginalKnowledgeService
import top.wkbin.zaomeng.ktor.services.StorageService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

/**
 * 原文知识：**进程内直连**（原 `KtorOriginalKnowledgeClient`）。
 *
 * 与其它域不同的是，原先 `OriginalKnowledgeRoutes` 里带有**响应整形逻辑**——
 * `search` 的结果是 `List<Map<String, Any?>>`，路由把它逐项转成 JSON 再包进 `items`。
 * 去掉 HTTP 后这段整形没有别处可去，因此**原样搬到这里**（连同 `toJson` / `stringArray`），
 * 保证客户端拿到的 JSON 形状与经 HTTP 时逐字段一致。
 *
 * 另外保留路由原有的两个语义，避免行为漂移：
 * 1. 每个操作都先确认 run 存在（原先 `originalKnowledgeCall` 会 404），
 *    缺失时抛 [NoSuchElementException]；
 * 2. `rebuild` 走的是 `service.rebuild(manifest)`——它需要 manifest 而不是 runId。
 */
class OriginalKnowledgeClient(
    private val service: OriginalKnowledgeService,
    private val storage: StorageService,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun search(
        runId: String,
        query: String,
        participants: List<String>,
        pinnedOnly: Boolean,
        limit: Int = 50,
    ): OriginalKnowledgeSearchResponse {
        val manifest = requireManifest(runId)
        val items = service.search(
            runManifest = manifest,
            query = query,
            participants = participants,
            limit = limit,
            pinnedOnly = pinnedOnly,
        )
        val payload = buildJsonObject {
            put("items", buildJsonArray { items.forEach { item -> add(item.toJson()) } })
        }
        return json.decodeFromJsonElement(payload)
    }

    suspend fun updateBoundary(runId: String, entryId: String, visibility: String, knowers: List<String>) {
        requireManifest(runId)
        service.updateBoundary(runId, entryId, visibility, knowers)
    }

    suspend fun updatePinned(runId: String, entryId: String, pinned: Boolean) {
        requireManifest(runId)
        service.updatePinned(runId, entryId, pinned)
    }

    suspend fun rebuild(runId: String) {
        service.rebuild(requireManifest(runId))
    }

    private fun requireManifest(runId: String): JsonObject =
        storage.readRunManifest(runId) ?: throw NoSuchElementException("Run not found")
}

/** 与路由里的整形逐字段一致：把 `Map<String, Any?>` 转成固定形状的 JSON。 */
private fun Map<String, Any?>.toJson(): JsonObject = buildJsonObject {
    put("source_id", this@toJson["source_id"]?.toString().orEmpty())
    put("title", this@toJson["title"]?.toString().orEmpty())
    put("excerpt", this@toJson["excerpt"]?.toString().orEmpty())
    put("score", (this@toJson["score"] as? Number)?.toDouble() ?: 0.0)
    put("visibility", this@toJson["visibility"]?.toString().orEmpty())
    put("knowers", stringArray(this@toJson["knowers"]))
    put("characters", stringArray(this@toJson["characters"]))
    put("boundary_source", this@toJson["boundary_source"]?.toString().orEmpty())
    put("pinned", this@toJson["pinned"] as? Boolean ?: false)
    put("epistemic_status", this@toJson["epistemic_status"]?.toString().orEmpty())
    put("allowed_characters", stringArray(this@toJson["allowed_characters"]))
    put("denied_characters", stringArray(this@toJson["denied_characters"]))
    val location = this@toJson["location"] as? Map<*, *>
    put(
        "location",
        buildJsonObject {
            put("start_char", (location?.get("start_char") as? Number)?.toInt() ?: 0)
            put("end_char", (location?.get("end_char") as? Number)?.toInt() ?: 0)
        },
    )
}

private fun stringArray(value: Any?): JsonArray = buildJsonArray {
    (value as? List<*>)?.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }
        ?.distinct()
        ?.forEach { add(JsonPrimitive(it)) }
}
