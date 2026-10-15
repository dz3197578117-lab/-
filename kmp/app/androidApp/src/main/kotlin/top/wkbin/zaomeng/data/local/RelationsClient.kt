package top.wkbin.zaomeng.data.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import top.wkbin.zaomeng.data.api.RelationDetailsDto
import top.wkbin.zaomeng.data.api.UpdateRelationDetailRequest
import top.wkbin.zaomeng.ktor.services.RelationsService

/**
 * 人物关系：**进程内直连**。
 *
 * 原先这份逻辑是 `KtorRelationsClient`——发 HTTP 到内嵌服务、再把响应体解码成 DTO。
 * 单模块化后客户端与服务端同进程，这层 HTTP 往返（含监听端口、SSE、鉴权）没有任何收益，
 * 因此改为直接调用 [RelationsService]。
 *
 * 关键点：服务端本来就返回 [kotlinx.serialization.json.JsonObject]（原始 JSON），
 * HTTP 路径的 DTO 也是由客户端解码得到的。所以这里**沿用同一套解码**，
 * 等于把「服务端 → 序列化 → 网络 → 反序列化」压成「服务端 JsonObject → 反序列化」，
 * DTO 形状与解码配置完全不变，上层 Repository / ViewModel 一行都不用改。
 *
 * Json 配置与原客户端逐项一致（`ignoreUnknownKeys` / `isLenient`），
 * 以免出现「换了个实现，某些字段突然解不出来」的行为漂移。
 */
class RelationsClient(
    private val service: RelationsService,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun get(runId: String): RelationDetailsDto =
        json.decodeFromJsonElement(service.list(runId))

    suspend fun update(
        runId: String,
        pairKey: String,
        request: UpdateRelationDetailRequest,
    ): RelationDetailsDto = json.decodeFromJsonElement(
        service.update(
            runId = runId,
            pairKey = pairKey,
            trust = request.trust,
            affection = request.affection,
            hostility = request.hostility,
            ambiguity = request.ambiguity,
            relationshipType = request.relationshipType,
            relationChange = request.relationChange,
            conflictPoint = request.conflictPoint,
            typicalInteraction = request.typicalInteraction,
        ),
    )
}
