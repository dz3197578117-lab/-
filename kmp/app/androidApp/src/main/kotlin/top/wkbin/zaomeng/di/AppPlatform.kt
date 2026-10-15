package top.wkbin.zaomeng.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import okio.Path
import top.wkbin.zaomeng.backend.AppServices
import top.wkbin.zaomeng.backend.BackendController
import top.wkbin.zaomeng.platform.DistillationForeground
import top.wkbin.zaomeng.platform.NovelConversionForeground
import top.wkbin.zaomeng.platform.SecureKeyValueStore

/** 平台应用依赖：各端入口提供，Koin 模块据此组装。 */
interface AppPlatform {
    /**
     * 进程内后端服务容器（单模块化后客户端与服务端同进程）。
     * 内嵌 HTTP 与进程内直连共用同一份服务图，避免出现两套状态。
     */
    val services: AppServices

    val backendController: BackendController

    val secureStore: SecureKeyValueStore

    val dataStore: DataStore<Preferences>

    /** 应用私有文件目录（背景图等用户产生的持久文件；Android filesDir / 桌面用户目录）。 */
    val filesDir: Path

    val cacheDir: Path

    val distillationForeground: DistillationForeground

    val novelConversionForeground: NovelConversionForeground
}
