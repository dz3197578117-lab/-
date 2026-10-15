package top.wkbin.zaomeng.backend

import top.wkbin.zaomeng.ktor.KtorServiceGraph
import top.wkbin.zaomeng.platform.ServerPlatform

/**
 * 进程内后端服务容器。
 *
 * 单模块化之后，客户端与「服务端」在同一个进程里，原先经 localhost HTTP/SSE 的往返
 * 变成进程内直接调用，因此需要一个**进程内共享**的服务图实例：
 *
 * - **必须是同一个实例**：服务图持有 Room 数据库、StorageService、诊断事件日志等
 *   有状态对象。若内嵌 Ktor 服务与进程内调用各自 new 一个，就会出现两个数据库连接、
 *   两份日志缓冲，症状是「写入看不到、诊断丢事件」这类极难排查的问题。
 * - **懒构造**：`by lazy` 保证只在真正有人用时才建库，应用冷启动不必为它付代价。
 *
 * 它由 [InProcessBackendController] 在启动时预热（[warmUp]），也是应用内**唯一**的
 * 服务图来源——客户端不再经 localhost HTTP 往返，直接从这里取服务。
 */
class AppServices(private val graphProvider: () -> KtorServiceGraph) {

    /**
     * 生产用法：由平台提供 [ServerPlatform]，服务图在首次使用时构建。
     */
    constructor(serverPlatform: ServerPlatform) : this({ KtorServiceGraph(serverPlatform) })

    /** 服务图：进程内唯一实例。 */
    val graph: KtorServiceGraph by lazy(graphProvider)

    /** 触发构建（需要预热时使用，例如让探针在后台先跑一轮）。 */
    fun warmUp() {
        graph
    }
}
