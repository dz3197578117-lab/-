package top.wkbin.zaomeng.data.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import top.wkbin.zaomeng.data.api.ReusableCardDto
import top.wkbin.zaomeng.ktor.services.CardsManagementService
import top.wkbin.zaomeng.ktor.services.CardsService

/**
 * 可复用卡片（场景卡 / 自设卡 / 开场预设）：**进程内直连**（原 `KtorCardsClient`）。
 *
 * 端点与服务的对应关系（原路由拆在两个文件里，这里合并）：
 * - `generate` → `CardsService.generateSceneCard()/generateSelfCard()`（`CardRoutes`）
 * - 其余        → `CardsManagementService`（`CardsManagementRoute`）
 *
 * `list` 原先由路由包成 `{"items":[...]}`、客户端再取 `items` 解码；
 * 进程内直接把服务返回的 JsonArray 解成列表，结果一致。
 */
class CardsClient(
    private val cardsService: CardsService,
    private val cardsManagement: CardsManagementService,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun generate(kind: String): ReusableCardDto = decodeCard(
        when (kind) {
            "scene" -> cardsService.generateSceneCard()
            "self" -> cardsService.generateSelfCard()
            // 原先只有 scene/self 有 generate 端点，其它 kind 会走到 404；
            // 进程内用明确异常表达同一件事，而不是让调用方去解析 HTTP 状态码。
            else -> throw IllegalArgumentException("不支持生成该类型的卡片：$kind")
        },
    )

    suspend fun list(kind: String): List<ReusableCardDto> =
        json.decodeFromJsonElement(cardsManagement.list(kind))

    suspend fun get(kind: String, cardId: String): ReusableCardDto =
        decodeCard(cardsManagement.get(kind, cardId))

    suspend fun save(kind: String, cardId: String, fields: JsonObject): ReusableCardDto =
        decodeCard(cardsManagement.save(kind, cardId, fields))

    suspend fun delete(kind: String, cardId: String) {
        cardsManagement.delete(kind, cardId)
    }

    suspend fun recommend(mode: String, participants: List<String>): JsonObject =
        cardsManagement.recommend(mode, participants)

    private fun decodeCard(payload: JsonObject): ReusableCardDto =
        json.decodeFromJsonElement(payload)
}
