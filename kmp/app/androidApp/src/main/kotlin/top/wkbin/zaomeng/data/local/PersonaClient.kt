package top.wkbin.zaomeng.data.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import top.wkbin.zaomeng.data.api.DeleteStatusDto
import top.wkbin.zaomeng.data.api.PersonaAvatarDto
import top.wkbin.zaomeng.data.api.PersonaEvolutionChangeDto
import top.wkbin.zaomeng.data.api.PersonaEvolutionProposalDto
import top.wkbin.zaomeng.data.api.PersonaQualityOverviewDto
import top.wkbin.zaomeng.data.api.PersonaQualityReportDto
import top.wkbin.zaomeng.data.api.PersonaRepairProposalDto
import top.wkbin.zaomeng.data.api.PersonaReviewDto
import top.wkbin.zaomeng.data.api.StoryRecapDto
import top.wkbin.zaomeng.data.api.SuggestPersonaFieldResponse
import top.wkbin.zaomeng.ktor.services.PersonaService

/**
 * 人物档案：**进程内直连**（原 `KtorPersonaClient`）。
 *
 * [PersonaService] 的返回值绝大多数**本来就是类型化 DTO**，所以这一域几乎没有解码成本。
 * 三处需要显式对齐旧语义的地方：
 *
 * 1. `saveReview` 客户端传 `JsonObject`，服务收 `Map<String,String>`——
 *    原路由做了 `mapValues { it.value.jsonPrimitive.content }`，这里照做。
 * 2. `getAvatar` 旧契约是「没有头像返回 null」（原实现在 404 时返回 null），
 *    而服务是抛 [NoSuchElementException]。这里显式捕获转成 null，保持调用方语义。
 * 3. `suggestField` 的 `currentFields` 恒为空：客户端发的
 *    `data.api.SuggestPersonaFieldRequest` 只有 `field` 一个字段，
 *    服务端那份 `ktor.models` 的同名 DTO 才带 `current_fields`（默认空 map）。
 *    因此进程内传 `emptyMap()` 与经 HTTP 时逐字节等价。
 *
 * 上传头像不再需要 multipart：过去是「打包 multipart → 服务端拆 part 取 file」，
 * 进程内直接把字节交给 [PersonaService.saveAvatar]。
 */
class PersonaClient(
    private val service: PersonaService,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun getReview(runId: String, character: String): PersonaReviewDto =
        service.getReview(runId, character)

    suspend fun saveReview(runId: String, character: String, fields: JsonObject): PersonaReviewDto =
        service.saveReview(runId, character, fields.mapValues { it.value.jsonPrimitive.content })

    suspend fun getQuality(runId: String, character: String): PersonaQualityReportDto =
        service.getQualityReport(runId, character)

    suspend fun getRepairProposal(runId: String, character: String): PersonaRepairProposalDto =
        service.getRepairProposal(runId, character)

    /** 一次拉取全书卷的人物档案质量总览（服务端聚合，无需逐人请求）。 */
    suspend fun getQualityOverview(runId: String): PersonaQualityOverviewDto =
        service.qualityOverview(runId)

    suspend fun delete(runId: String, character: String): DeleteStatusDto =
        service.deletePersona(runId, character)

    suspend fun uploadAvatar(runId: String, character: String, bytes: ByteArray): PersonaAvatarDto =
        service.saveAvatar(runId, character, bytes)

    /** 没有头像时返回 null（与旧契约一致）。 */
    suspend fun getAvatar(runId: String, character: String): ByteArray? = try {
        service.getAvatarBytes(runId, character)
    } catch (_: NoSuchElementException) {
        null
    }

    suspend fun suggestField(runId: String, character: String, field: String): SuggestPersonaFieldResponse =
        json.decodeFromJsonElement(service.suggestField(runId, character, field, emptyMap()))

    suspend fun getEvolutionProposal(
        runId: String,
        character: String,
        recap: StoryRecapDto? = null,
    ): PersonaEvolutionProposalDto =
        service.generateEvolutionProposal(runId, character, recap)

    suspend fun applyEvolution(
        runId: String,
        character: String,
        changes: List<PersonaEvolutionChangeDto>,
    ): PersonaReviewDto =
        service.applyEvolution(runId, character, changes)
}
