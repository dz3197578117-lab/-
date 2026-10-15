package top.wkbin.zaomeng.platform

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 进程内日志环形缓冲测试。
 *
 * 关注三件事：有界（不会吃光内存）、按物理行入环（多行堆栈不会算成一行）、
 * 尾部语义正确（诊断导出取到的就是最近发生的事）。
 */
class RuntimeLogBufferTest {

    @BeforeTest
    fun setUp() {
        RuntimeLogBuffer.clear()
    }

    @AfterTest
    fun tearDown() {
        RuntimeLogBuffer.clear()
        RuntimeLogBuffer.configure(RuntimeLogBuffer.DEFAULT_MAX_LINES)
    }

    @Test
    fun `keeps only the configured number of lines`() {
        RuntimeLogBuffer.configure(5)
        repeat(20) { RuntimeLogBuffer.append('I', "Test", "line-$it") }

        assertEquals(5, RuntimeLogBuffer.size(), "环大小必须被封顶")
        val tail = RuntimeLogBuffer.tail(5)
        assertEquals(listOf("line-15", "line-16", "line-17", "line-18", "line-19"), tail.map { it.substringAfter(": ") })
    }

    @Test
    fun `counts physical lines for multiline throwables`() {
        RuntimeLogBuffer.configure(200)
        val throwable = IllegalStateException("boom")
        RuntimeLogBuffer.append('E', "Test", "request failed", throwable)

        val text = RuntimeLogBuffer.text()
        assertTrue(text.contains("E/Test: request failed"), "应包含首行")
        assertTrue(text.contains("IllegalStateException"), "应包含异常类型")
        assertTrue(text.contains("RuntimeLogBufferTest"), "应包含堆栈帧")
        assertTrue(RuntimeLogBuffer.size() > 2, "多行堆栈必须按物理行入环，实际 ${RuntimeLogBuffer.size()}")
    }

    @Test
    fun `reports cumulative line count independent of ring size`() {
        // 断言**增量**而不是绝对值：单元测试合并到同一个 JVM 后，先跑的用例可能留下
        // 后台协程仍在写这个全局缓冲（PlatformLog 直接落在这里），绝对值会被并发写入干扰。
        // 本用例真正要守的性质是「环被裁剪不影响累计计数」，增量断言恰好表达这一点。
        RuntimeLogBuffer.configure(3)
        val before = RuntimeLogBuffer.totalAppended()
        repeat(10) { RuntimeLogBuffer.append('D', "Test", "m$it") }
        assertEquals(3, RuntimeLogBuffer.size(), "环大小必须被封顶")
        assertTrue(
            RuntimeLogBuffer.totalAppended() - before >= 10L,
            "累计行数不应被裁剪影响（增量 ${RuntimeLogBuffer.totalAppended() - before}）",
        )
    }

    @Test
    fun `tail returns requested bound and empty buffer yields empty text`() {
        RuntimeLogBuffer.clear()
        assertEquals("", RuntimeLogBuffer.text(), "空缓冲应返回空文本")
        repeat(10) { RuntimeLogBuffer.append('D', "Test", "m$it") }
        assertEquals(4, RuntimeLogBuffer.tail(4).size, "tail 应按请求行数返回")
    }

    @Test
    fun `truncates overlong messages`() {
        RuntimeLogBuffer.configure(50)
        RuntimeLogBuffer.append('D', "Test", "x".repeat(10_000))
        val line = RuntimeLogBuffer.tail(1).single()
        assertTrue(line.length < 4_000, "单条消息必须被截断，实际 ${line.length}")
    }
}
