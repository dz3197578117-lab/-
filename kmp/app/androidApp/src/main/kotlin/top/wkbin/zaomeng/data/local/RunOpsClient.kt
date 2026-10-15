package top.wkbin.zaomeng.data.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import top.wkbin.zaomeng.data.api.CreateCrossoverSpaceRequest
import top.wkbin.zaomeng.data.api.EstimateSamplingRequest
import top.wkbin.zaomeng.data.api.ExtractMainCharactersDto
import top.wkbin.zaomeng.data.api.ExtractMainCharactersRequest
import top.wkbin.zaomeng.data.api.NovelChaptersResponse
import top.wkbin.zaomeng.data.api.RedistillSuggestionsDto
import top.wkbin.zaomeng.data.api.RestartRunRequest
import top.wkbin.zaomeng.data.api.RunManifestDto
import top.wkbin.zaomeng.data.api.SamplingPlanDto
import top.wkbin.zaomeng.data.api.SuggestRedistillSegmentsRequest
import top.wkbin.zaomeng.ktor.services.NovelChapterIndexService
import top.wkbin.zaomeng.ktor.services.RunOperationsService
import top.wkbin.zaomeng.ktor.services.StorageService

/**
 * 书卷操作（估算 / 主角抽取 / 分章 / 跨书卷共演 / 导出 / 重蒸馏 / 续跑）：**进程内直连**
 * （原 `KtorRunOpsClient`）。
 *
 * 两个端点在别的路由文件里，这里一并对应：
 * - `listNovelChapters` → `NovelChapterRoute`：先取 manifest（缺失即 NoSuchElementException），
 *   再交给 [NovelChapterIndexService.list]（首次访问会切分并缓存）。
 * - `exportRun` → 原先返回 HTTP 响应、由仓库层 `streamChannelToTempFile` 落盘；
 *   而服务端 [RunOperationsService.exportRunPackage] **本来就是生成 `ByteArray`**。
 *   进程内直接返回「字节 + 文件名」，仓库层一次写入即可，省掉通道与两层拷贝。
 *
 * `exportRunPackage` 的 `builtin` 参数沿用原路由默认值 `false`。
 */
class RunOpsClient(
    private val runOperations: RunOperationsService,
    private val novelChapters: NovelChapterIndexService,
    private val storage: StorageService,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun estimateSampling(request: EstimateSamplingRequest): SamplingPlanDto = decode(
        runOperations.estimate(
            charCount = request.charCount,
            sentenceCount = request.sentenceCount,
            characterCount = request.characterCount,
            maxSentences = request.maxSentences,
            maxChars = request.maxChars,
        ),
    )

    suspend fun extractMainCharacters(request: ExtractMainCharactersRequest): ExtractMainCharactersDto =
        decode(
            runOperations.extractMainCharacters(
                novelName = request.novelName,
                novelContentBase64 = request.novelContentBase64,
                maxCharacters = request.maxCharacters,
            ),
        )

    /** 按已存在书卷提取主角：服务端直接读书卷自带原文，无需上传正文。 */
    suspend fun extractRunCharacters(runId: String, maxCharacters: Int): ExtractMainCharactersDto =
        decode(runOperations.extractMainCharactersFromRun(runId, maxCharacters))

    /** 原文分章列表：首次访问由服务端切分并缓存。 */
    suspend fun listNovelChapters(runId: String): NovelChaptersResponse = decode(
        novelChapters.list(
            storage.readRunManifest(runId) ?: throw NoSuchElementException("Run not found"),
        ),
    )

    suspend fun createCrossoverSpace(request: CreateCrossoverSpaceRequest): RunManifestDto = decode(
        runOperations.createCrossoverSpace(
            title = request.title,
            worldSetting = request.worldSetting,
            participants = request.participants.map { it.runId to it.character },
        ),
    )

    /** 导出书卷包：直接返回「zip 字节 + 文件名」。 */
    suspend fun exportRun(runId: String, includeDialogue: Boolean = true): Pair<ByteArray, String> =
        runOperations.exportRunPackage(runId, builtin = false, includeDialogue = includeDialogue)

    suspend fun refreshRun(runId: String): RunManifestDto = decode(runOperations.refresh(runId))

    suspend fun redistill(runId: String, request: RestartRunRequest): RunManifestDto = decode(
        runOperations.redistill(
            runId = runId,
            characters = request.characters,
            novelName = request.novelName,
            novelContentBase64 = request.novelContentBase64,
            maxSentences = request.maxSentences,
            maxChars = request.maxChars,
        ),
    )

    suspend fun resumeDistill(runId: String): RunManifestDto = decode(runOperations.resumeDistill(runId))

    suspend fun suggestRedistill(
        runId: String,
        request: SuggestRedistillSegmentsRequest,
    ): RedistillSuggestionsDto = decode(
        runOperations.suggestRedistillSegments(runId, request.character, request.maxSegments),
    )

    private inline fun <reified T> decode(payload: JsonObject): T =
        json.decodeFromJsonElement(payload)
}
