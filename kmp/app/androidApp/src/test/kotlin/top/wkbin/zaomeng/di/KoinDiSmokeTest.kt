package top.wkbin.zaomeng.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okio.Path.Companion.toPath
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import androidx.room3.RoomDatabase
import top.wkbin.zaomeng.backend.AppServices
import top.wkbin.zaomeng.db.ZaomengDatabase
import top.wkbin.zaomeng.db.getDatabaseBuilder
import top.wkbin.zaomeng.platform.PromptSource
import top.wkbin.zaomeng.platform.ServerPlatform
import top.wkbin.zaomeng.backend.BackendController
import top.wkbin.zaomeng.backend.BackendState
import top.wkbin.zaomeng.data.CardRepository
import top.wkbin.zaomeng.data.DialogueRepository
import top.wkbin.zaomeng.data.ModelSettingsRepository
import top.wkbin.zaomeng.data.PersonaRepository
import top.wkbin.zaomeng.data.RunRepository
import top.wkbin.zaomeng.data.SessionRepository
import top.wkbin.zaomeng.data.library.OnlineLibraryRepository
import top.wkbin.zaomeng.data.preferences.AppPreferencesRepository
import top.wkbin.zaomeng.domain.chat.ChatSessionGateway
import top.wkbin.zaomeng.domain.distill.DistillPlanningGateway
import top.wkbin.zaomeng.domain.run.RunReviewGateway
import top.wkbin.zaomeng.domain.sessions.CreateDialogueSessionGateway
import top.wkbin.zaomeng.domain.sessions.DeleteDialogueSessionGateway
import top.wkbin.zaomeng.platform.DistillationForeground
import top.wkbin.zaomeng.platform.NovelConversionForeground
import top.wkbin.zaomeng.platform.SecureKeyValueStore

/**
 * DI 冒烟：确保平台服务与数据层定义都能解析（防止运行期 NoDefinitionFound）。
 *
 * 这里**刻意不使用 AndroidAppPlatform**：
 *   - 它需要 Context，会把用例绑死到 Robolectric；
 *   - 而 Robolectric 明确不支持 Linux/aarch64
 *     （`The Robolectric native runtime is not supported on Linux (aarch64)`），本机根本跑不起来。
 *
 * 该用例真正要守的是「Koin 图能装配完整」，与平台实现无关，用桩最合适，
 * 也让它在任何机器上都可运行。平台实现本身由真机/CI 的仪器测试覆盖。
 */
class KoinDiSmokeTest : KoinComponent {

    private class StubSecureStore : SecureKeyValueStore {
        private val values = mutableMapOf<String, String>()
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
        override fun entries(): Map<String, String> = values.toMap()
    }

    private class StubBackendController : BackendController {
        override val state: StateFlow<BackendState> = MutableStateFlow(BackendState.Idle)
        override fun start() = Unit
        override fun retry() = Unit
    }

    private class StubDistillationForeground : DistillationForeground {
        override fun start() = Unit
        override fun stopAll() = Unit
        override fun hasNotificationPermission(): Boolean = true
    }

    private class StubNovelConversionForeground : NovelConversionForeground {
        override fun start(runId: String, sessionId: String, title: String): Boolean = true
        override fun hasNotificationPermission(): Boolean = true
    }

    /**
     * 最小可用的 [ServerPlatform]：数据根指向临时目录，提示词源为空实现，
     * 数据库用**按路径构建**的 Room 构建器（不依赖 Android Context）。
     *
     * 去掉 HTTP 层之后，Repository 直接依赖进程内服务图，因此「DI 图能否装配」
     * 与「服务图能否构建」已经是一件事——这里让冒烟测试真的把它建出来，
     * 而不是绕过它。构建失败会在本用例里直接暴露，而不是等到真机首次调用。
     */
    private class StubServerPlatform : ServerPlatform {
        override val dataRoot: okio.Path = createTempDirectory("zaomeng-di-data").toString().toPath()

        override val promptSource: PromptSource = object : PromptSource {
            override fun read(relativePath: String): Pair<String, Long>? = null
            override fun lastModified(relativePath: String): Long? = null
        }

        override fun secureStore(): SecureKeyValueStore = StubSecureStore()

        override fun databaseBuilder(): RoomDatabase.Builder<ZaomengDatabase> =
            getDatabaseBuilder(dataRoot / "zaomeng.db")
    }

    private class StubAppPlatform : AppPlatform {
        override val services: AppServices = AppServices(StubServerPlatform())
        override val backendController: BackendController = StubBackendController()
        override val secureStore: SecureKeyValueStore = StubSecureStore()
        override val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        ) { File(createTempDirectory("zaomeng-di").toFile(), "test.preferences_pb") }
        override val filesDir: okio.Path = createTempDirectory("zaomeng-di-files").toString().toPath()
        override val cacheDir: okio.Path = createTempDirectory("zaomeng-di-cache").toString().toPath()
        override val distillationForeground: DistillationForeground = StubDistillationForeground()
        override val novelConversionForeground: NovelConversionForeground = StubNovelConversionForeground()
    }

    @Test
    fun `koin graph resolves platform services and data layer`() {
        startKoin { modules(sharedAppModules(StubAppPlatform())) }

        val distillation: DistillationForeground by inject()
        val conversion: NovelConversionForeground by inject()
        val backend: BackendController by inject()
        val secureStore: SecureKeyValueStore by inject()
        val appPlatform: AppPlatform by inject()
        val preferences: AppPreferencesRepository by inject()
        val onlineLibrary: OnlineLibraryRepository by inject()
        val chatGateway: ChatSessionGateway by inject()
        val distillGateway: DistillPlanningGateway by inject()
        val runReviewGateway: RunReviewGateway by inject()
        val createSessionGateway: CreateDialogueSessionGateway by inject()
        val deleteSessionGateway: DeleteDialogueSessionGateway by inject()
        val runRepository: RunRepository by inject()
        val sessionRepository: SessionRepository by inject()
        val dialogueRepository: DialogueRepository by inject()
        val personaRepository: PersonaRepository by inject()
        val modelSettingsRepository: ModelSettingsRepository by inject()
        val cardRepository: CardRepository by inject()

        assertNotNull(distillation)
        assertNotNull(conversion)
        assertNotNull(backend)
        assertNotNull(secureStore)
        assertNotNull(appPlatform)
        assertNotNull(preferences)
        assertNotNull(onlineLibrary)
        assertNotNull(chatGateway)
        assertNotNull(distillGateway)
        assertNotNull(runReviewGateway)
        assertNotNull(createSessionGateway)
        assertNotNull(deleteSessionGateway)
        assertNotNull(runRepository)
        assertNotNull(sessionRepository)
        assertNotNull(dialogueRepository)
        assertNotNull(personaRepository)
        assertNotNull(modelSettingsRepository)
        assertNotNull(cardRepository)
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
    }
}
