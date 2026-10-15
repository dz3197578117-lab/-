package top.wkbin.zaomeng.ktor.services

import okio.Path
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.data.api.RuntimeLogDto
import top.wkbin.zaomeng.platform.RuntimeLogSource
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 回归测试：运行日志快照必须可用、按请求夹紧，且凭据在离开服务前就被遮蔽。
 *
 * 原为 `RuntimeLogRouteTest`，经 `testApplication` 打 `GET /diagnostics/runtime-log`
 * 并断言**序列化后的文本**。HTTP 层移除后改为直接断言 [DiagnosticsService.buildRuntimeLogSnapshot]
 * 的返回值——同样钉住「来源标识可识别 / 行数夹紧 / 凭据遮蔽」这三件事，
 * 而且比断言文本更直接（不再依赖序列化格式）。
 */
class RuntimeLogSnapshotTest {

    private class FakeRuntimeLogSource(private val snapshot: RuntimeLogDto) : RuntimeLogSource {
        override val source: String = snapshot.source

        override fun snapshot(maxLines: Int): RuntimeLogDto =
            snapshot.copy(lines = snapshot.lines.takeLast(maxLines))
    }

    private fun newService(snapshot: RuntimeLogDto): DiagnosticsService {
        val directory: Path = createTempDirectory("zaomeng-runtime-log").toString().toPath()
        return DiagnosticsService(
            storageRoot = directory,
            storageService = StorageService(directory),
            runtimeLogSource = FakeRuntimeLogSource(snapshot),
        )
    }

    private fun snapshot() = RuntimeLogDto(
        source = RuntimeLogDto.SOURCE_LOGCAT_FILE,
        available = true,
        capturedAt = "2026-10-12T20:00:00+08:00",
        totalLines = 12,
        lines = listOf(
            "10-12 20:00:00.000 I Zaomeng.Dialogue: 对话生成完成",
            "10-12 20:00:01.000 E Zaomeng.Llm: api_key=abcdef123456",
            "line-3",
            "line-4",
        ),
    )

    @Test
    fun `runtime log snapshot is redacted and clamped`() {
        val diagnostics = newService(snapshot())

        val dto = diagnostics.buildRuntimeLogSnapshot(maxLines = 2)

        assertTrue(dto.available, "快照应可用")
        assertEquals(RuntimeLogDto.SOURCE_LOGCAT_FILE, dto.source, "应带来源标识")
        assertFalse(
            dto.lines.any { it.contains("abcdef123456") },
            "凭据必须已在服务端遮蔽：${dto.lines}",
        )
        assertTrue(dto.lines.any { it.contains("line-4") }, "应返回尾部日志：${dto.lines}")
        assertFalse(
            dto.lines.any { it.contains("对话生成完成") },
            "超出请求行数的更早日志不应返回：${dto.lines}",
        )
    }
}
