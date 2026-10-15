package top.wkbin.zaomeng.data.local

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.data.api.FeatureProbeReportDto
import top.wkbin.zaomeng.ktor.services.DiagnosticsEventLog
import top.wkbin.zaomeng.ktor.services.DiagnosticsService
import top.wkbin.zaomeng.ktor.services.StorageService
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 回归测试：诊断导出必须真的产出一份非空 JSON。
 *
 * 线上症状是「导出后 JSON 文件是空白」。原测试（`DiagnosticsExportRouteTest`）通过
 * `testApplication` 打 HTTP，把「服务端序列化 → 客户端读字节」两段都钉住。
 *
 * 去掉 HTTP 后，**序列化这一步搬进了 [DiagnosticsClient]**（自己用与服务端逐项一致的
 * Json 配置编码），所以测试也跟着搬到这里——断言基本没动，仍然钉住「导出为空」这个症状，
 * 而且现在覆盖的正是新引入的那段编码逻辑。
 */
class DiagnosticsClientTest {

    private fun newServices(directory: Path): DiagnosticsService {
        val storage = StorageService(directory)
        val log = DiagnosticsEventLog(directory / "diagnostics-events.jsonl")
        log.record(
            category = "dialogue.parse_failed",
            message = "Model reply did not contain usable character responses.",
            details = mapOf("output_excerpt" to "{\"speaker\":\"assistant\",\"message\":\"...\"}"),
        )
        return DiagnosticsService(
            storageRoot = directory,
            storageService = storage,
            eventLog = log,
            onDeviceSnapshot = { mapOf("state" to "running", "model_file" to "demo.gguf") },
        )
    }

    private fun newDirectory(): Path = createTempDirectory("zaomeng-diag-client").toString().toPath()

    private fun newClient(diagnostics: DiagnosticsService) = DiagnosticsClient(
        diagnostics = diagnostics,
        // 导出路径不触碰探针，这里给一个明确不会用到的实现即可。
        refreshProbes = { error("导出不应触发探针重跑") },
    )

    @Test
    fun `export writes a non-empty json report`() {
        val directory = newDirectory()
        val diagnostics = newServices(directory)
        val file = directory / "zaomeng-report.json"

        // runTest 在 JVM 上返回 TestResult（即 Unit），拿不到块内返回值，用变量接。
        var written = 0L
        runTest {
            written = newClient(diagnostics).export(FileSystem.SYSTEM.sink(file))
        }

        assertTrue(written > 0, "导出字节数不应为 0")
        val text = FileSystem.SYSTEM.read(file) { readUtf8() }
        assertTrue(text.isNotBlank(), "导出内容不应为空")

        val json = Json.parseToJsonElement(text).jsonObject
        assertEquals("zaomeng_diagnostics", json["kind"].toString().trim('"'))
        assertTrue("recentEvents" in json, "报告应包含 recentEvents")
        assertTrue("onDevice" in json, "报告应包含 onDevice 快照")
        assertTrue(
            text.contains("Model reply did not contain usable character responses."),
            "事件内容应出现在报告里",
        )
    }

    @Test
    fun `export keeps the server-side json shape`() {
        val directory = newDirectory()
        val diagnostics = newServices(directory)
        val file = directory / "zaomeng-report.json"

        runTest {
            newClient(diagnostics).export(FileSystem.SYSTEM.sink(file))
        }

        val text = FileSystem.SYSTEM.read(file) { readUtf8() }
        // 服务端 ContentNegotiation 用的是 prettyPrint = true；导出文件是给人看与发给开发者复盘的，
        // 换掉序列化配置会让文件形状悄悄变化，所以这里也钉一下。
        assertTrue(text.contains("\n"), "导出应保持 prettyPrint 的多行形状")
    }
}
