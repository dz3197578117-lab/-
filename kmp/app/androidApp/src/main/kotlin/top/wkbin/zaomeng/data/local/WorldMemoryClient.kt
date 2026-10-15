package top.wkbin.zaomeng.data.local

import top.wkbin.zaomeng.data.api.DeleteStatusDto
import top.wkbin.zaomeng.data.api.SaveWorldFactRequest
import top.wkbin.zaomeng.data.api.WorldFactDto
import top.wkbin.zaomeng.data.api.WorldMemoryDto
import top.wkbin.zaomeng.ktor.services.WorldMemoryService

/**
 * 世界记忆（时间线 / 剧情事实）：**进程内直连**（原 `KtorWorldMemoryClient`）。
 *
 * 这一域最省事：[WorldMemoryService] 的返回值本来就是**类型化 DTO**
 * （不是 JsonObject），所以 HTTP 路径上的「序列化 → 反序列化」可以整体删掉，
 * 直接返回服务结果即可，连 Json 配置都不需要。
 *
 * 参数语义与原先一致：`factId` 为空表示新建（走 POST），非空表示更新（走 PUT）——
 * 这个分支在服务里由 `saveFact` 自己处理，调用方无需再区分。
 */
class WorldMemoryClient(
    private val service: WorldMemoryService,
) {
    suspend fun get(runId: String): WorldMemoryDto = service.get(runId)

    suspend fun save(runId: String, factId: String, payload: SaveWorldFactRequest): WorldFactDto =
        service.saveFact(runId, factId, payload)

    suspend fun delete(runId: String, factId: String): DeleteStatusDto =
        service.deleteFact(runId, factId)
}
