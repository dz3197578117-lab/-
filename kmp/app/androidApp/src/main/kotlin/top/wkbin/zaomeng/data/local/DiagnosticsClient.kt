package top.wkbin.zaomeng.data.local

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.Sink
import okio.buffer
import okio.use
import top.wkbin.zaomeng.data.api.DiagnosticsOverviewDto
import top.wkbin.zaomeng.data.api.FeatureProbeReportDto
import top.wkbin.zaomeng.data.api.RuntimeCaptureStateDto
import top.wkbin.zaomeng.data.api.RuntimeLogClearResultDto
import top.wkbin.zaomeng.data.api.RuntimeLogDto
import top.wkbin.zaomeng.ktor.services.DiagnosticsService

/**
 * 运行诊断：**进程内直连**（原 `KtorDiagnosticsClient`）。
 *
 * 唯一需要小心的是 `export`：以前是服务端把 [DiagnosticsService.buildDiagnosticsReport]
 * 的结果交给 Ktor 的 ContentNegotiation 序列化，客户端把响应体**原始字节**写进文件。
 * 进程内没有 HTTP 了，所以这里必须**自己完成那次序列化**，而且 Json 配置要与服务端
 * 逐项一致（`prettyPrint` / `isLenient` / `ignoreUnknownKeys` / `encodeDefaults`），
 * 否则导出的诊断文件字节会变——那是一份要发给开发者复盘的交付物，不该因为
 * 「顺手换了个序列化配置」而悄悄改变形状。
 *
 * 另外保留原实现的两条判断：
 * - 报告为空直接失败（否则用户只得到一个空白 JSON，看不出哪里出了问题）；
 * - `clearRuntimeLog` 把 DTO 原样返回。原先服务端在「清除失败且平台支持」时回 500，
 *   但响应体就是同一个 DTO，客户端 `body<...>()` 并不看状态码、照样解出来返回，
 *   所以这里直接返回服务结果与旧行为一致（`cleared`/`supported` 由调用方判断）。
 */
class DiagnosticsClient(
    private val diagnostics: DiagnosticsService,
    /**
     * 「重新执行自检探针」的执行体。
     *
     * 这里收一个窄函数而不是整个 `FeatureProbeExecutor`：客户端只需要「怎么重跑」，
     * 不需要知道探针注册表、执行器等内部结构——依赖更窄，也让本类可以在单测里
     * 用一句 lambda 构造，不必搭出整条探针链路。
     */
    private val refreshProbes: suspend () -> FeatureProbeReportDto,
) {
    /** 与服务端 ContentNegotiation 逐项一致的配置（导出文件字节稳定）。 */
    private val json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * 拉取诊断报告并写入 [destination]。
     *
     * [runId] / [sessionId] 是「最近打开的会话」，作为现场上下文带上；服务端不知道 UI 状态，
     * 报告里会标注这是客户端上报而不是当前正在生成的会话。
     */
    suspend fun export(
        destination: Sink,
        runId: String = "",
        sessionId: String = "",
        tier: String = "compact",
    ): Long {
        val report = diagnostics.buildDiagnosticsReport(runId = runId, sessionId = sessionId, tier = tier)
        val bytes = json.encodeToString(report).encodeToByteArray()
        if (bytes.isEmpty()) {
            throw IllegalStateException("诊断报告为空（0 字节），导出未完成。")
        }
        destination.buffer().use { it.write(bytes) }
        return bytes.size.toLong()
    }

    /** 拉取运行日志快照（已脱敏）。行数上限由服务端夹紧。 */
    suspend fun runtimeLog(maxLines: Int, runId: String = "", sessionId: String = ""): RuntimeLogDto =
        diagnostics.buildRuntimeLogSnapshot(maxLines = maxLines, runId = runId, sessionId = sessionId)

    /** 「运行诊断」概览：自检 + 可读问题清单 + 事件统计 + 模型连通性（不带日志正文）。 */
    suspend fun overview(runId: String = "", sessionId: String = ""): DiagnosticsOverviewDto =
        diagnostics.buildDiagnosticsOverview(runId = runId, sessionId = sessionId)

    /** 重新执行功能自检探针（显式「重新自检」）。 */
    suspend fun refreshFeatureProbes(): FeatureProbeReportDto =
        diagnostics.refreshFeatureProbes(refreshProbes)

    /** 采集开关状态（轻量端点，供应用内悬浮窗与「运行日志」页共用）。 */
    suspend fun captureState(): RuntimeCaptureStateDto = diagnostics.runtimeCaptureState()

    /** 启停持续采集（服务端侧幂等，状态由平台持久化）。 */
    suspend fun setCaptureEnabled(enabled: Boolean): RuntimeCaptureStateDto =
        diagnostics.setRuntimeLogCapture(enabled)

    /** 清除已采集的运行日志与崩溃/卡顿记录。 */
    suspend fun clearRuntimeLog(): RuntimeLogClearResultDto = diagnostics.clearRuntimeLog()
}
