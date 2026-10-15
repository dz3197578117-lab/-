package top.wkbin.zaomeng.backend

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 进程内后端控制器。
 *
 * **它取代了原来的 `LocalBackendController`**：以前这里会起一个 Ktor CIO 服务器
 * （监听 127.0.0.1 随机端口、装鉴权与可观测性插件、注册几十条路由），
 * 客户端再通过 localhost HTTP/SSE 调回同一个进程。单模块化后这层往返没有任何收益，
 * 于是整条链路被删除，这里只保留**启动生命周期**这一件事。
 *
 * 保留它的理由是 UI 契约：书架页会根据 [state] 显示「正在准备 / 就绪 / 失败 + 重试」，
 * 前台服务也会在 [BackendState.Failed] 时调 [retry]。所以启动语义必须与以前一致：
 * - [start] 幂等，构建进程内服务图（会打开 Room 数据库、初始化存储与各服务）；
 * - 成功 → [BackendState.Ready]；抛错 → [BackendState.Failed]（消息原样给用户看）；
 * - [retry] 清掉启动标记后可重来。
 *
 * 注意：服务图本身是**懒构建**的（[AppServices.graph]），所以「先探测状态、后真正用服务」
 * 不会被重复初始化；这里显式 [AppServices.warmUp] 只是把「可能失败的初始化」
 * 提前到用户能看到失败原因的时刻，而不是等到第一次对话才炸。
 */
@OptIn(ExperimentalAtomicApi::class)
class InProcessBackendController(
    private val services: AppServices,
) : BackendController {
    private val mutableState = MutableStateFlow<BackendState>(BackendState.Idle)

    override val state: StateFlow<BackendState> = mutableState.asStateFlow()

    /**
     * 幂等启动标记。
     *
     * 用 `AtomicBoolean.compareAndSet` 而不是「先判断再赋值」：语义与双重检查锁一致，
     * 只有第一个调用方真正启动，其余直接返回。
     */
    private val started = AtomicBoolean(false)

    override fun start() {
        if (!started.compareAndSet(expectedValue = false, newValue = true)) return
        mutableState.value = BackendState.Starting("正在准备本地服务…")
        try {
            services.warmUp()
            mutableState.value = BackendState.Ready("进程内服务已就绪")
        } catch (error: Throwable) {
            mutableState.value = BackendState.Failed(error.message ?: "本地服务初始化失败")
        }
    }

    override fun retry() {
        started.store(false)
        start()
    }
}
