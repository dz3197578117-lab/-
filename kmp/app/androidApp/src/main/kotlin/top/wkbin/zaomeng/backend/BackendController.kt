package top.wkbin.zaomeng.backend

import kotlinx.coroutines.flow.StateFlow

/** 后端生命周期控制器：由 [InProcessBackendController] 实现（进程内服务，无 HTTP 服务端）。 */
interface BackendController {
    val state: StateFlow<BackendState>

    fun start()

    fun retry()
}
