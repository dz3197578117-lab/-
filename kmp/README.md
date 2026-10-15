<p align="center">
  <img src="../docs/images/zaomeng_logo.png" alt="造梦" width="120">
</p>

# 造梦 Android 应用

Jetpack Compose 单模块 Android 应用，业务服务与数据库都在**同一进程内**，数据全部保存在本机，
无需部署中心服务器。

> ⚠️ **升级提示（重要）**
>
> 2.0.0 与旧版 Android（1.5.0 及更早）数据**不兼容**：本地数据不会自动迁移，安装 2.0.0 后旧书卷/会话将不可见。
> **升级前请先在旧版中导出书卷包（`.zaomeng-run.zip`）备份**，安装 2.0.0 后通过“导入书卷包”恢复。

## 平台

当前只支持 Android（`arm64-v8a`）。桌面端与 Compose Multiplatform 已移除；
工程不再是 KMP 多模块，也不是跨平台工程。

## 技术栈

- Jetpack Compose、Material 3、Navigation3、ViewModel、Paging 3
- Room 3（SQLite 持久化）、DataStore（偏好存储）、Koin（DI）
- kotlinx.serialization / coroutines / datetime、okio
- Ktor **客户端**：仅用于对外网络（模型 API、在线书库、更新检查、端侧 llama-server）
- FileKit（文件选择）、snakeyaml-engine-kmp（YAML）

> 内嵌的 Ktor **服务端**（CIO + 路由 + SSE）已整体移除。客户端与服务在同一进程内
> 直接调用，不再有 `localhost` 端口、鉴权与 HTTP 往返。详见
> [`docs/architecture.md`](../docs/architecture.md)。

## 模块结构

单模块：`settings.gradle.kts` 只 `include(":app:androidApp")`。

```
kmp/app/androidApp/src/main/kotlin/top/wkbin/zaomeng/
├── app/          # 应用入口（Application / MainActivity / 更新器）
├── data/         # Repository 实现与领域门面
│   ├── api/      #   DTO 与跨层契约
│   ├── local/    #   各领域的进程内客户端（直接调服务）
│   ├── library/  #   在线书库下载（对外网络）
│   └── update/   #   更新检查（对外网络）
├── ktor/         # 业务服务层（services / features / providers / models / utils）
├── db/           # Room 数据库、DAO 与 DomainStore
├── domain/       # 用例与 Gateway 契约
├── feature/      # 各业务 Screen / ViewModel
├── platform/     # 平台能力（日志、安全存储、HTTP 引擎、诊断采集）
└── di/           # Koin 装配
```

## 功能

- 书卷：导入（TXT / EPUB / 书卷包）、蒸馏、增量蒸馏、导出书卷包
- 会话：搜索、多选删除、场景推荐、自动标题
- 聊天：流式回复、断线恢复、分支、@ 提及、读心、群聊旁观
- 卡库：场景卡 / 自设卡 / 开局模板
- 人物：资料校对、AI 补全、头像裁剪、关系图谱、世界时间线
- 在线书库：社区书卷包浏览与下载
- 插件：内置插件、声明式第三方插件管理，以及无需编写 JSON 的插件工坊（制作、安装试用、ZIP 导出）
- 设置：模型配置、主题（深浅 / 动态取色）、字号与紧凑模式、应用更新

## 构建与运行

需要 JDK 21+ 与 Android SDK。

### 一条命令构建 APK

```bash
# 在仓库根目录执行（不是 kmp/ 里）
./scripts/build-android-apk.sh           # debug 包（默认）
./scripts/build-android-apk.sh release   # release 包（需要 kmp/keystore.properties 才会签名）
```

脚本会自动探测 `JAVA_HOME` 与 Android SDK，并绕过 arm64 主机上 AGP 自带 aapt2
是 x86-64 可执行文件的问题；构建完成后打印产物路径与签名指纹。
可用 `JAVA_HOME` / `ANDROID_HOME` / `AAPT2_OVERRIDE` 显式覆盖探测结果。

### 不用本地环境

推送到 `main`（改动 `kmp/**`）或手动触发 **Actions → Build APK**，
就能在该次运行的 **Artifacts** 区域下载 APK，**不需要配置任何 secret**。
签名规则与限制见 [`docs/release-android.md`](../docs/release-android.md)。

### Gradle 直接调用

```bash
# 编译
./gradlew :app:androidApp:compileDebugKotlin

# 单元测试
./gradlew :app:androidApp:testDebugUnitTest

# Android debug APK
./gradlew :app:androidApp:assembleDebug
```

在 aarch64 主机上需要额外指定 aapt2（否则 AGP 自带的 x86-64 aapt2 无法执行）：

```bash
./gradlew :app:androidApp:assembleDebug -Pandroid.aapt2FromMavenOverride=/root/.dsh-aapt2/aapt2
```

## 截图

| 手机端 |
| --- |
| <img src="../docs/images/mobile.jpg" width="240" alt="手机端"> |
