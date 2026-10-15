package top.wkbin.zaomeng.data.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import top.wkbin.zaomeng.data.api.CreateDialogueSessionRequest
import top.wkbin.zaomeng.data.api.DeleteSessionsRequest
import top.wkbin.zaomeng.data.api.DeleteSessionsResponse
import top.wkbin.zaomeng.data.api.DeleteStatusDto
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.data.api.MessagesResponse
import top.wkbin.zaomeng.data.api.SessionsResponse
import top.wkbin.zaomeng.ktor.services.SessionManagementService
import top.wkbin.zaomeng.ktor.services.SessionsPage
import top.wkbin.zaomeng.ktor.services.leanSession
import top.wkbin.zaomeng.ktor.services.pageTranscript
import top.wkbin.zaomeng.ktor.services.withTranscriptCount

/**
 * 会话管理：**进程内直连**（原 `KtorSessionClient`）。
 *
 * 这个域有两个值得写下来的点：
 *
 * 1. **响应整形本来就在服务层**（`SessionResponseUtils` 的 `withTranscriptCount` /
 *    `leanSession` / `pageTranscript`），路由只是调用它们。所以这里可以直接复用，
 *    不需要像 OriginalKnowledge 那样把整形逻辑搬过来。
 * 2. **分页参数的夹取被保留**：原路由对 HTTP 入参做过 `limit.coerceIn(1,200)`、
 *    `query.take(120)`、`sort` 白名单归一。虽然进程内调用方是我们自己的 Repository，
 *    但去掉夹取会**静默改变行为**（例如传入 limit=500 时过去只返回 200 条）。
 *    因此这里照搬同一套归一化，保证结果逐字段一致。
 */
class SessionClient(
    private val service: SessionManagementService,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun listForRun(
        runId: String,
        offset: Int = 0,
        limit: Int = 50,
        query: String = "",
        sort: String = "recent",
    ): SessionsResponse {
        // 原路由：run 不存在 → 404
        if (!service.runExists(runId)) throw NoSuchElementException("Run not found")
        val (normalizedQuery, normalizedSort) = normalize(query, sort)
        return service.listDialogueSessions(runId, offset, normalizeLimit(limit), normalizedQuery, normalizedSort)
            .toSessionsResponse()
    }

    suspend fun listRecent(
        offset: Int = 0,
        limit: Int = 50,
        query: String = "",
        sort: String = "recent",
    ): SessionsResponse {
        val (normalizedQuery, normalizedSort) = normalize(query, sort)
        return service.listRecentSessions(offset, normalizeLimit(limit), normalizedQuery, normalizedSort)
            .toSessionsResponse()
    }

    suspend fun delete(runId: String, sessionId: String): DeleteStatusDto {
        // 原路由：删不到 → 404
        if (!service.deleteDialogueSession(runId, sessionId)) {
            throw NoSuchElementException("Session not found")
        }
        return DeleteStatusDto(status = "deleted")
    }

    suspend fun deleteBatch(payload: DeleteSessionsRequest): DeleteSessionsResponse {
        val deleted = payload.items.filter { service.deleteDialogueSession(it.runId, it.sessionId) }
        val notFound = payload.items - deleted.toSet()
        return DeleteSessionsResponse(
            status = "ok",
            deletedCount = deleted.size,
            deleted = deleted,
            notFoundCount = notFound.size,
            notFound = notFound,
        )
    }

    suspend fun create(runId: String, payload: CreateDialogueSessionRequest): DialogueSessionDto =
        decode(
            service.openDialogueSession(
                runId = runId,
                mode = payload.mode,
                participants = payload.participants,
                controlledCharacter = payload.controlledCharacter,
                sceneCardId = payload.sceneCardId,
                sceneProfile = payload.sceneProfile,
                selfCardId = payload.selfCardId,
                selfProfile = payload.selfProfile,
                startChapter = payload.startChapter,
            ),
        )

    suspend fun get(
        runId: String,
        sessionId: String,
        includeTranscript: Boolean = true,
    ): DialogueSessionDto {
        val result = service.getDialogueSession(runId, sessionId)
        return decode(if (includeTranscript) withTranscriptCount(result) else leanSession(result))
    }

    suspend fun listMessages(
        runId: String,
        sessionId: String,
        offset: Int = 0,
        limit: Int = 100,
        order: String = "asc",
    ): MessagesResponse {
        // 原路由对这三个参数做过同样的归一化。
        val normalizedOffset = offset.coerceAtLeast(0)
        val normalizedLimit = limit.coerceIn(1, 500)
        val normalizedOrder = order.trim().takeIf { it in setOf("asc", "desc") } ?: "asc"
        return pageTranscript(
            session = service.getDialogueSession(runId, sessionId),
            offset = normalizedOffset,
            limit = normalizedLimit,
            order = normalizedOrder,
        )
    }

    suspend fun updateTitle(runId: String, sessionId: String, title: String): DialogueSessionDto =
        decode(service.updateDialogueSessionTitle(runId, sessionId, title))

    private fun decode(payload: JsonObject): DialogueSessionDto =
        json.decodeFromJsonElement(payload)

    private fun normalizeLimit(limit: Int): Int = limit.coerceIn(1, 200)

    private fun normalize(query: String, sort: String): Pair<String, String> {
        val normalizedQuery = query.trim().take(120)
        val normalizedSort = sort.trim().takeIf { it in setOf("recent", "title") } ?: "recent"
        return normalizedQuery to normalizedSort
    }
}

/** 服务层 `SessionsPage` → 契约层 `SessionsResponse`：字段一一对应，无需再过 JSON。 */
private fun SessionsPage.toSessionsResponse(): SessionsResponse = SessionsResponse(
    items = items,
    total = total,
    hasMore = hasMore,
)
