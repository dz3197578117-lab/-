package top.wkbin.zaomeng.data.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.data.api.GeneratePluginDraftRequest
import top.wkbin.zaomeng.data.api.InspectPluginPackageRequest
import top.wkbin.zaomeng.data.api.InstallPluginPackageRequest
import top.wkbin.zaomeng.data.api.PackagePluginDraftRequest
import top.wkbin.zaomeng.data.api.PluginBuilderValidationDto
import top.wkbin.zaomeng.data.api.PluginChatActionRequest
import top.wkbin.zaomeng.data.api.PluginChatActionResponse
import top.wkbin.zaomeng.data.api.PluginConfigResponse
import top.wkbin.zaomeng.data.api.PluginDto
import top.wkbin.zaomeng.data.api.PluginLogsResponse
import top.wkbin.zaomeng.data.api.PluginPackageInspectionDto
import top.wkbin.zaomeng.data.api.PluginTemporaryNpcGeneratorRequest
import top.wkbin.zaomeng.data.api.PluginTemporaryNpcGeneratorResponse
import top.wkbin.zaomeng.data.api.PluginsResponse
import top.wkbin.zaomeng.data.api.UninstallPluginResponse
import top.wkbin.zaomeng.data.api.ValidatePluginDraftRequest
import top.wkbin.zaomeng.ktor.services.PluginBuilderService
import top.wkbin.zaomeng.ktor.services.PluginOperationsService
import top.wkbin.zaomeng.ktor.services.PluginService

/**
 * 插件：**进程内直连**（原 `KtorPluginClient`，端点拆在 PluginRoutes /
 * PluginOperationsRoute / PluginBuilderRoute 三个文件里）。
 *
 * 三处不再需要「序列化 → 反序列化」的地方，直接构造 DTO：
 * - `config` / `updateConfig`：原路由是 `mapOf("config" to ...)`，直接构造
 *   [PluginConfigResponse] 即可；
 * - `validateBuilder` / `generateBuilder`：服务本来就返回
 *   [PluginBuilderValidationDto]；
 * - `packageBuilder`：服务返回 `BuiltPluginPackage(filename, bytes)`，
 *   调用方（安装试用 / 导出草稿）只需要字节，因此这里直接返回 `ByteArray`，
 *   原先那条「HTTP 响应体 → 再取一次 body()」的路没有了。
 */
class PluginClient(
    private val plugins: PluginService,
    private val operations: PluginOperationsService,
    private val builder: PluginBuilderService,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun list(): PluginsResponse = decode(plugins.list())

    /** 原路由的 refresh 就是再取一次 list（服务端会重新合并内置与第三方清单）。 */
    suspend fun refresh(): PluginsResponse = decode(plugins.list())

    suspend fun enable(pluginId: String): PluginDto = decode(plugins.setEnabled(pluginId, true))

    suspend fun disable(pluginId: String): PluginDto = decode(plugins.setEnabled(pluginId, false))

    suspend fun config(pluginId: String): PluginConfigResponse =
        PluginConfigResponse(config = plugins.getConfig(pluginId))

    suspend fun updateConfig(pluginId: String, config: JsonObject): PluginConfigResponse =
        PluginConfigResponse(config = plugins.updateConfig(pluginId, config))

    suspend fun logs(pluginId: String): PluginLogsResponse = decode(plugins.logs(pluginId))

    suspend fun uninstall(pluginId: String): UninstallPluginResponse = decode(plugins.uninstall(pluginId))

    suspend fun inspect(request: InspectPluginPackageRequest): PluginPackageInspectionDto =
        decode(operations.inspect(request.filename, request.contentBase64))

    suspend fun install(token: String, request: InstallPluginPackageRequest): PluginDto =
        decode(operations.install(token, request.confirmPermissions, request.allowUpdate))

    suspend fun validateBuilder(request: ValidatePluginDraftRequest): PluginBuilderValidationDto =
        builder.validate(request.draft)

    suspend fun generateBuilder(request: GeneratePluginDraftRequest): PluginBuilderValidationDto =
        builder.generate(request.description)

    /** 打包插件草稿：直接返回 zip 字节。 */
    suspend fun packageBuilder(request: PackagePluginDraftRequest): ByteArray =
        builder.packagePlugin(request.draft).bytes

    suspend fun chatAction(
        runId: String,
        sessionId: String,
        pluginId: String,
        actionId: String,
        request: PluginChatActionRequest,
    ): PluginChatActionResponse = decode(
        operations.invokeChatAction(
            runId = runId,
            sessionId = sessionId,
            pluginId = pluginId,
            actionId = actionId,
            seedText = request.seedText,
            direction = request.direction,
            selection = request.selection,
        ),
    )

    suspend fun temporaryNpcGenerator(
        runId: String,
        sessionId: String,
        pluginId: String,
        generatorId: String,
        request: PluginTemporaryNpcGeneratorRequest,
    ): PluginTemporaryNpcGeneratorResponse = decode(
        operations.invokeNpcGenerator(runId, sessionId, pluginId, generatorId, request.direction),
    )

    suspend fun setEnhancerState(
        runId: String,
        sessionId: String,
        pluginId: String,
        enhancerId: String,
        enabled: Boolean,
    ): DialogueSessionDto = decode(
        operations.setEnhancerState(runId, sessionId, pluginId, enhancerId, enabled),
    )

    private inline fun <reified T> decode(payload: JsonObject): T =
        json.decodeFromJsonElement(payload)
}
