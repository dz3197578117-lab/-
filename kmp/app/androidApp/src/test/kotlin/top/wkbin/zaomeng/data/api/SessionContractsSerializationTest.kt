package top.wkbin.zaomeng.data.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 回归测试：会话列表响应必须走类型化 DTO，且字段名保持线上兼容。
 *
 * 原为 `SessionsRouteSerializationTest`：当时线上问题是「用 `mapOf("items" to List<SessionListItem>)`
 * 响应时 Ktor 序列化不了自定义类型 → 500」，所以测试通过 `testApplication` 打一个临时端点。
 *
 * HTTP 层移除后，「服务端能不能序列化」不再是风险点，但**另一半价值仍然成立**：
 * 服务层产出的 JSON 必须能解码进客户端 DTO，且字段名是 `session_id` / `has_more`
 * 这种线上兼容的 snake_case——这正是进程内 `SessionClient` 现在依赖的契约。
 * 因此这里改为纯序列化契约测试：不再需要起服务器。
 */
class SessionContractsSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun page() = SessionsPageResponse(
        items = listOf(SessionListItem(sessionId = "s1", runId = "run-1")),
        total = 1,
        hasMore = false,
    )

    @Test
    fun `typed sessions page response round-trips`() {
        val text = json.encodeToString(SessionsPageResponse.serializer(), page())

        val decoded = json.decodeFromString(SessionsPageResponse.serializer(), text)

        assertEquals("s1", decoded.items.first().sessionId)
        assertEquals(1, decoded.total)
        assertTrue(!decoded.hasMore)
    }

    @Test
    fun `typed response includes wire compatible fields`() {
        val text = json.encodeToString(SessionsPageResponse.serializer(), page())

        assertTrue(text.contains("\"items\""), text)
        assertTrue(text.contains("\"session_id\":\"s1\""), text)
        assertTrue(text.contains("\"has_more\":false"), text)
    }

    @Test
    fun `service-shaped list json decodes into the client contract`() {
        // 服务层 SessionsPage 的形状（数据类字段 items/total/hasMore），
        // 去掉字段名映射后就是线上 JSON —— 这里验证它确实能解成客户端 DTO。
        val wire = """
            {"items":[{"session_id":"s1","run_id":"run-1"}],"total":1,"has_more":false}
        """.trimIndent()

        val decoded = json.decodeFromString(SessionsResponse.serializer(), wire)

        assertEquals("s1", decoded.items.first().sessionId)
        assertEquals(1, decoded.total)
        assertTrue(!decoded.hasMore)
    }
}
