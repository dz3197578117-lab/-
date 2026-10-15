package top.wkbin.zaomeng.data.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import top.wkbin.zaomeng.data.api.ChatSearchResultDto
import top.wkbin.zaomeng.data.api.DialogueDirectorRequest
import top.wkbin.zaomeng.data.api.DialogueReplyRequest
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.data.api.DialogueStreamEvent
import top.wkbin.zaomeng.data.api.MemoryQualityReportDto
import top.wkbin.zaomeng.data.api.PlotEventPresetDto
import top.wkbin.zaomeng.data.api.SceneTensionDto
import top.wkbin.zaomeng.data.api.SwitchDialogueSceneRequest
import top.wkbin.zaomeng.data.api.UpdateDialogueBranchMetaRequest
import top.wkbin.zaomeng.data.api.UpdateDialogueRelationLockRequest
import top.wkbin.zaomeng.data.api.UpsertDialogueMemoryRequest
import top.wkbin.zaomeng.ktor.services.ContentRejectedException
import top.wkbin.zaomeng.ktor.services.DialogueAdvancedService
import top.wkbin.zaomeng.ktor.services.DialogueStreamService
import top.wkbin.zaomeng.ktor.services.StorageService
import top.wkbin.zaomeng.ktor.services.leanSession
import top.wkbin.zaomeng.ktor.services.transcriptByTurnId
import top.wkbin.zaomeng.ktor.services.transcriptSize
import top.wkbin.zaomeng.ktor.services.withTranscriptCount

/**
 * 多角色对话：**进程内直连**（原 `KtorDialogueClient`）。
 *
 * 覆盖三个路由文件（`DialogueAdvancedRoute` / `SuggestionsRoute` / `DialogueStreamRoute`）。
 * 这里的对应关系有两处**不是 1:1**，都按原样保留：
 *
 * 1. **响应整形**：`DialogueAdvancedRoute` 的 `runRoute` 会统一给 JsonObject 响应套一层
 *    `withTranscriptCount(...)`，而 memory-quality 三个端点与 `runRouteGeneric`
 *    （tension / events）**不套**。所以下面按方法区分，不能一律套或一律不套。
 * 2. **SSE 整条链路消失**：以前是「服务端 `Flow<StreamEvent>` → SSE 编码器编码
 *    `event:`/`data:` 文本 → HTTP → 客户端逐行解析 → DialogueStreamEvent」。
 *    两侧本来就都在这一个进程里，中间那段文本编解码纯属自找麻烦，
 *    现在直接映射成 [DialogueStreamEvent]。
 */
class DialogueClient(
    private val dialogueStream: DialogueStreamService,
    private val advanced: DialogueAdvancedService,
    private val storage: StorageService,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ------------------------------------------------------------------
    // 流式回复
    // ------------------------------------------------------------------

    /**
     * 流式回复：直接产出一串 [DialogueStreamEvent]，与经 SSE 时**事件序列逐项一致**。
     *
     * 边界语义（这些是经 SSE 时由「JSON 键总会被写入」决定的，不能想当然补默认值）：
     * - `role` / `field` / `text` 在服务端**总是写键**，所以客户端解析时的
     *   `?: "character"` / `?: "message"` 默认值**永远不会触发**——空串必须原样传下去；
     * - `reset` 的 `message` 同理（`event.text` 为空也不能替换成「正在重新整理回复…」）；
     * - 服务端 try 块**包含** complete 的构造，因此「构造 complete 时抛错」也要转成
     *   [DialogueStreamEvent.Failure]，而不是抛出去；
     * - [CancellationException] **必须原样抛出**：取消是控制流，不是「生成失败」，
     *    吞掉它会让用户切页面后仍收到一条失败事件。
     */
    fun streamReply(
        runId: String,
        sessionId: String,
        payload: DialogueReplyRequest,
    ): Flow<DialogueStreamEvent> = flow {
        emit(DialogueStreamEvent.Status(phase = "generating", message = "正在生成回复"))
        try {
            dialogueStream.replyDialogueTurnStream(
                runId = runId,
                sessionId = sessionId,
                message = payload.message,
                messageKind = payload.messageKind,
                pacing = payload.pacing,
                speakerOverride = payload.speakerOverride,
                includeInnerThoughts = payload.includeInnerThoughts,
                operationId = payload.operationId,
                suppressTranscriptMessage = payload.suppressTranscriptMessage,
                includeModelReasoning = payload.includeModelReasoning,
            ).collect { event ->
                val eventName = event.kind.ifBlank { "delta" }
                if (eventName == "reset") {
                    emit(DialogueStreamEvent.Reset(message = event.text))
                } else {
                    emit(
                        DialogueStreamEvent.Delta(
                            index = event.index,
                            speaker = event.speaker,
                            role = event.role,
                            text = event.text,
                            field = event.field,
                        ),
                    )
                }
            }
            val session = storage.getDialogueSession(runId, sessionId)
            emit(
                DialogueStreamEvent.Complete(
                    session = decodeSession(
                        if (payload.includeTranscript) withTranscriptCount(session) else leanSession(session),
                    ),
                    // 进程内不存在「重放」：HTTP 重试才会出现，这里恒为 false（与原实现一致）。
                    replayed = false,
                    appendedTranscript = if (payload.includeTranscript) {
                        emptyList()
                    } else {
                        transcriptByTurnId(session, payload.operationId).map {
                            json.decodeFromJsonElement(it)
                        }
                    },
                    transcriptCount = transcriptSize(session),
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            emit(
                DialogueStreamEvent.Failure(
                    message = error.message ?: "Dialogue reply failed",
                    // 提供方内容审核拒绝不是应用故障，重试只会再被拒一次。
                    retryable = error !is ContentRejectedException,
                ),
            )
        }
    }

    // ------------------------------------------------------------------
    // 对话高级功能（原 DialogueAdvancedRoute）
    // ------------------------------------------------------------------

    suspend fun searchSession(
        runId: String,
        sessionId: String,
        query: String,
        limit: Int,
    ): List<ChatSearchResultDto> = json.decodeFromJsonElement(advanced.search(runId, sessionId, query, limit))

    suspend fun recoverSession(runId: String, sessionId: String, force: Boolean): DialogueSessionDto =
        decodeSession(withTranscriptCount(advanced.recover(runId, sessionId, force)))

    suspend fun suggestReply(runId: String, sessionId: String, seedText: String, direction: String): String =
        advanced.suggestDialogue(runId, sessionId, seedText, direction)
            .let { body -> body["suggestion"]?.let { json.decodeFromJsonElement<String>(it) }.orEmpty() }

    suspend fun correctLatest(runId: String, sessionId: String): DialogueSessionDto =
        decodeSession(withTranscriptCount(advanced.correctLatest(runId, sessionId)))

    suspend fun deepReview(runId: String, sessionId: String): DialogueSessionDto =
        decodeSession(withTranscriptCount(advanced.deepReview(runId, sessionId)))

    suspend fun directorOptions(
        runId: String,
        sessionId: String,
        goal: String,
        action: String,
        force: Boolean = false,
    ): JsonObject {
        val request = DialogueDirectorRequest(goal = goal, action = action, forceExecute = force)
        return withTranscriptCount(
            advanced.directDialogue(
                runId = runId,
                sessionId = sessionId,
                goal = request.goal,
                action = request.action,
                optionCount = request.optionCount,
                forceExecute = request.forceExecute,
            ),
        )
    }

    /** `runRouteGeneric`：这条端点**不套** `withTranscriptCount`。 */
    suspend fun getSceneTension(runId: String, sessionId: String): SceneTensionDto =
        advanced.getSceneTension(runId, sessionId)

    /** `runRouteGeneric`：不套 `withTranscriptCount`。`category` 为空表示不过滤。 */
    suspend fun getPresetEvents(
        runId: String,
        sessionId: String,
        category: String? = null,
    ): List<PlotEventPresetDto> = advanced.getPresetEvents(category)

    suspend fun branchFromTurn(runId: String, sessionId: String, turnId: String): DialogueSessionDto =
        decodeSession(withTranscriptCount(advanced.branchFromTurn(runId, sessionId, turnId)))

    suspend fun branchFromScene(runId: String, sessionId: String, sceneIndex: Int): DialogueSessionDto =
        decodeSession(withTranscriptCount(advanced.branchFromScene(runId, sessionId, sceneIndex)))

    suspend fun updateBranchMeta(
        runId: String,
        sessionId: String,
        label: String?,
        isMainline: Boolean?,
        lockedEventIds: List<String>?,
    ): DialogueSessionDto {
        val request = UpdateDialogueBranchMetaRequest(
            label = label,
            isMainline = isMainline,
            lockedEventIds = lockedEventIds,
        )
        return decodeSession(
            withTranscriptCount(
                advanced.updateBranchMeta(
                    runId = runId,
                    sessionId = sessionId,
                    label = request.label,
                    isMainline = request.isMainline,
                    lockedEventIds = request.lockedEventIds,
                ),
            ),
        )
    }

    suspend fun setRelationLock(
        runId: String,
        sessionId: String,
        pairKey: String,
        locked: Boolean,
    ): DialogueSessionDto {
        val request = UpdateDialogueRelationLockRequest(pairKey = pairKey, locked = locked)
        return decodeSession(
            withTranscriptCount(advanced.setRelationLock(runId, sessionId, request.pairKey, request.locked)),
        )
    }

    suspend fun saveMemory(
        runId: String,
        sessionId: String,
        memoryId: String,
        text: String,
        category: String,
        pinned: Boolean,
        enabled: Boolean,
    ): DialogueSessionDto {
        val request = UpsertDialogueMemoryRequest(
            text = text,
            category = category,
            pinned = pinned,
            enabled = enabled,
        )
        return decodeSession(
            withTranscriptCount(
                advanced.saveMemory(
                    runId = runId,
                    sessionId = sessionId,
                    memoryId = memoryId,
                    text = request.text,
                    category = request.category,
                    pinned = request.pinned,
                    enabled = request.enabled,
                ),
            ),
        )
    }

    suspend fun deleteMemory(runId: String, sessionId: String, memoryId: String): DialogueSessionDto =
        decodeSession(withTranscriptCount(advanced.deleteMemory(runId, sessionId, memoryId)))

    /** memory-quality 三个端点由路由直接 respond，**不套** `withTranscriptCount`。 */
    suspend fun getMemoryQuality(runId: String, sessionId: String): MemoryQualityReportDto =
        advanced.memoryQuality(runId, sessionId)

    suspend fun updateAutomaticMemoryStatus(
        runId: String,
        sessionId: String,
        memoryId: String,
        status: String,
    ): MemoryQualityReportDto = advanced.updateAutomaticMemoryStatus(runId, sessionId, memoryId, status)

    suspend fun mergeDuplicateMemories(runId: String, sessionId: String): MemoryQualityReportDto =
        advanced.mergeDuplicateMemories(runId, sessionId)

    suspend fun switchScene(
        runId: String,
        sessionId: String,
        sceneCardId: String,
        transitionMessage: String,
        autoContinue: Boolean,
    ): DialogueSessionDto {
        val request = SwitchDialogueSceneRequest(
            sceneCardId = sceneCardId,
            transitionMessage = transitionMessage,
            autoContinue = autoContinue,
        )
        return decodeSession(
            withTranscriptCount(
                advanced.switchScene(
                    runId = runId,
                    sessionId = sessionId,
                    sceneCardId = request.sceneCardId,
                    sceneProfile = request.sceneProfile,
                    transitionMessage = request.transitionMessage,
                    autoContinue = request.autoContinue,
                ),
            ),
        )
    }

    suspend fun recommendScene(runId: String, sessionId: String): JsonObject =
        withTranscriptCount(advanced.recommendScene(runId, sessionId))

    private fun decodeSession(payload: JsonObject): DialogueSessionDto =
        json.decodeFromJsonElement(payload)
}
