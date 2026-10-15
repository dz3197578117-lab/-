# AGENTS.md

本文档适用于仓库根目录及所有子目录，供自动化编码代理和协作者执行开发、测试与交付任务时使用。若子目录存在更具体的 `AGENTS.md`，以距离目标文件最近的文档为准。

## 项目定位

造梦将中文小说人物蒸馏为人物包、关系和记忆，并提供多角色对话体验。

- `kmp/`：当前主实现。**纯 Android 单模块应用**（`kmp/app/androidApp`），
  Jetpack Compose + Room，业务服务在**同一进程内直接调用**（不再有内嵌 HTTP 服务端）。
- `zaomeng-skill/`：OpenClaw / ClawHub skill 包。
- `src/`、`src/web/`：不再维护的 Python/Web 旧实现，仅用于行为对照和兼容测试。除非任务明确要求，不要在这里实现新功能。
- `prompts/`：可编辑的提示词源文件。

## 工程结构

工程是**单模块**的：`kmp/settings.gradle.kts` 只 `include(":app:androidApp")`。

```text
kmp/app/androidApp/
  src/main/kotlin/top/wkbin/zaomeng/
    app/            应用入口（Application / MainActivity / AppUpdater）
    data/           Repository 实现与领域门面
      api/            DTO 与契约（跨层稳定形状）
      local/          各领域的**进程内**客户端（直接调服务，无 HTTP）
      library/ update/ 对外网络：在线书库下载、更新检查
    ktor/           业务服务层（service / features / providers / models / utils）
    db/             Room 数据库、DAO 与 DomainStore
    domain/         用例与 Gateway 契约
    feature/*       按业务拆分的 Screen/ViewModel（bookshelf、chat、persona 等）
    platform/       平台能力（日志、安全存储、HTTP 引擎、诊断采集）
    di/             Koin 装配
  src/main/assets/  打包的提示词副本
  src/test/kotlin/  单元测试
```

依赖统一通过 `kmp/gradle/libs.versions.toml` 管理。新增依赖前先确认是否已有版本目录项，不要在模块脚本中随意硬编码版本。

### 为什么服务层还在 `ktor/` 包下

包名是历史遗留：这里以前是内嵌的 Ktor 服务端，现在已经没有 HTTP 服务端了，
`ktor/` 下只剩业务服务与探针（`ktor/client/` 也已随平台层收敛）。
改名会牵动几百个文件与测试，收益仅是名字好看，因此**暂不改名**——
但读代码时要知道：`ktor.services.*` 是**进程内业务服务**，不是「服务端」。

## 开发原则

1. 新功能优先落在本实现，不要同时维护 Python 旧后端，除非任务要求行为对齐。
2. **不引入 `expect/actual`**：工程只有一个 Android 目标，平台能力用接口 + 单实现表达。
3. 客户端调用服务走 `data/local/` 的进程内客户端：
   服务返回 `JsonObject` 时用与原先一致的 `Json` 配置解码；服务已返回类型化 DTO 时直接返回。
   **不要重新引入 HTTP/SSE 往返**。
4. 保持协程非阻塞。文件、数据库和网络阻塞操作应进入平台 IO dispatcher；不要在 UI 线程执行大文件解析。
5. 不要复制大型原文或生成型静态资源。优先使用内容寻址、缓存、索引或已有资源引用。
6. 对现有脏工作区保持克制：不要覆盖、还原或删除与当前任务无关的用户改动。

## 后端与存储

- 生产持久化统一经过 `StorageService`、`DocumentStore` 和 `DomainStore`，不要绕过它们直接操作应用数据库。
- 所有 run/session 标识必须经过 `PathSafety` 校验；禁止拼接未经验证的外部路径。
- 写入业务文档使用原子写接口。新增文档路径时确认 Room 文档同步、缓存失效和级联删除语义。
- 会话生成路径只读取有界的近期 transcript。完整历史通过归档/分页路径读取，不要重新把无限增长的历史塞回生成 manifest。
- 数据结构或 Room schema 变化必须提供相应迁移或明确的破坏性升级策略，并补充测试。不要静默丢弃用户数据。
- API 密钥只通过 `ModelApiKeyService` 和平台安全存储访问，禁止写入 manifest、日志、测试快照或仓库文件。

## 模型输出与流式对话

- 多角色对话的主协议是 NDJSON：每行一个完整对象，至少包含非空 `speaker` 与 `message`，可选 `inner_thought`。
- 对话解析器需兼容旧 JSON 数组和 `responses` 包装对象，但兼容逻辑是兜底，不应成为提示词主协议。
- NDJSON 整体不是单个 JSON 对象，因此对话请求不要同时声明 `response_format=json_object`。只返回单个对象的其他模型任务应继续使用原生 JSON 结构约束。
- 流式链路必须从模型响应体到应用 UI 全程增量读取。禁止使用会把响应缓存到 EOF 的调用方式，也不要为了视觉效果伪造模型打字速度。
- 第一个可见 `message` 增量应立即进入 UI；后续可做很短的帧级合批以减少 Compose 重组。
- 解析失败重试可以保留，但不能用纯文本兜底掩盖多人场景的结构错误。
- 诊断日志默认使用长度限制，并记录阶段耗时、TTFT、重试次数等指标。

**例外（用户明确要求，2.1.13 起）**：诊断允许在**最终失败**等必要场景记录**完整提示词与完整模型输出**，
按**工程上限**截断（单字段上限 + 文件字节上限 + 超限轮转），用于事后复盘生成质量；
完整载荷**只在 `tier=full` 的导出**中出现，默认 `compact` 档不含。

**无论何时都不得记录密钥**：API Key / 令牌 / 签名口令**绝不**进入日志、事件、导出或测试快照——
这是**凭证安全**，不是隐私偏好（诊断文件是要发给开发者看的）。
脱敏必须发生在**写入侧**（每个 detail 值过 `LogRedactor`），不能只依赖导出时遮蔽。

## 提示词同步

内置提示词同时存在于**两处**，修改任何一处都必须同步另一处：

- `prompts/**` —— 可编辑的源
- `kmp/app/androidApp/src/main/assets/**` —— 应用打包副本（`PromptLoader` 读取）

（Compose Resources 那一份随 Compose Multiplatform 一起移除；桌面端已不存在。）

只改其中一处会出现「改了提示词但运行时不生效」。跑 `./scripts/check-prompt-sync.sh` 做双向校验：
正向查源→副本逐字节一致，反向查副本里有没有 `prompts/` 不存在的孤儿文件。
该脚本目前是**手动门禁**（未接入 `dev_checks.py` 或任何 workflow），改动提示词后必须自己跑。

结构化输出提示必须明确：输出形状、必填字段、是否允许 Markdown 围栏、是否允许前后缀文字。新增 JSON 模型任务时，在调用处启用提供商支持的结构化约束，并为不支持该能力的兼容端保留严格提示词和解析校验。

## UI 与 API

- DTO 位于 `data/api/`；服务与客户端字段需要同步变更并保持合理默认值。
- **没有 HTTP 接口文档了**：内嵌服务端已移除，客户端与服务在同一进程内直接调用。
  改动服务方法的返回形状时，同步改 `data/local/` 下对应的进程内客户端：
  它负责把服务的 `JsonObject` 解码成 DTO，两端字段必须一起动。
- 历史 HTTP 接口文档见 `docs/archive/server-api.md`（**已废弃，仅供对照旧 Python 实现**）。
- 聊天完成事件优先返回轻量 session 和本轮增量，不要在每次发送后重新传输完整 transcript。
- ViewModel 状态更新应保持幂等，尤其注意断线重试、operation ID、流式 reset/complete 和历史分页合并。
- Compose 列表元素应使用稳定 key；避免在高频 delta 上进行昂贵的全列表重建。

## 对外描述面同步

下列位置都在说「造梦现在能做什么」，因此**每次加能力或发版都会过期**，必须一起更新；
漏掉任意一处就会出现「仓库里写了、用户看到的地方还没有」或反过来的错位。

| 面 | 位置 | 能否自动校验 |
|---|---|---|
| README「本版改动」表 | `README.md`（英文版 `README.en.md` 同步） | ❌ 只能人工看 |
| 能力域清单（按域汇总 + 每项「起始版本」） | `docs/features.md` | ❌ 只能人工看 |
| 网站结构化数据里的版本 | `website/index.html` 的 JSON-LD `softwareVersion` | ❌ **没有任何脚本管它**，纯手工 |
| 逐版本细节 | `CHANGELOG.md` + `.github/release-notes/v<版本>.md` | ✅ `scripts/check-release-ready.sh` |
| 仓库页 About 描述 / topics | GitHub 设定；**权威文案在 `docs/maintainer-setup.md` 第七节**，改完必须重新 PATCH 到 GitHub | ❌ 只存在 GitHub 上 |

两条硬规则：

- **不要在这些地方写死版本号**。README 已声明「当前版本以 CHANGELOG 顶部为准」；
  同理 `docs/changes-from-upstream.md` 里的「新增 / 修改 / 删除」计数是**某一轮的快照**，
  不要再把它当实时数字引用（README 已改为只指向该文档）。
- **未发布的能力**在「起始版本」栏统一写「未发布」，切版本号时再替换。
  提前写一个还没发布的号，会让这几处与 CHANGELOG 立刻互相矛盾。

## 验证命令

命令默认从 `kmp/` 目录运行，使用 `./gradlew`。需要 JDK 21+。

本机（aarch64 容器）需要显式指定，否则构建会因 aapt2 / NDK 架构不匹配失败：

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64
export ANDROID_HOME=/opt/android-sdk
./gradlew <task> -Pandroid.aapt2FromMavenOverride=/root/.dsh-aapt2/aapt2
```

常用任务：

```bash
./gradlew :app:androidApp:compileDebugKotlin    # 只编译
./gradlew :app:androidApp:testDebugUnitTest     # 单元测试（全量 400+）
./gradlew :app:androidApp:assembleDebug         # 出 APK
```

若明确修改旧 Python 基线、脚本或仓库级检查，再从仓库根目录运行：

```bash
python scripts/dev_checks.py
```

测试范围应与风险相称：解析器、存储、幂等、并发、提示词协议和 token 预算变更必须补回归测试。提交前至少运行 `git diff --check`。

**注意**：`testDebugUnitTest` 为 UP-TO-DATE 时不会重跑，结果是上一次的。
判断「这轮测试是否真的跑过」要看任务状态与结果文件时间戳，不要只看 `BUILD SUCCESSFUL`。

## 安全与仓库卫生

- 永远不要提交 `*.jks`、`*.keystore`、`keystore.properties`、`local.properties`、`.env*`、数据库、应用私有目录或 Gradle/IDE 缓存。
- 根目录 `android/` 是本机生成的项目镜像，包含签名配置，已被忽略；正式 Android 源码位于 `kmp/app/androidApp`。
- 不要提交模型 API 密钥、签名密码、个人访问令牌或用户数据库导出。
- 不要用破坏性 Git 命令清理用户工作区。只在用户明确要求时创建提交、推送或改写历史。
- 提交前检查 `git status`、`git diff --check` 和暂存文件列表；提交信息应概括行为变化，而不是工具操作。

## 完成标准

任务只有在以下条件满足后才算完成：

1. 实现落在正确的主模块，未无意修改旧实现或用户文件。
2. 服务的返回形状与 `data/local/` 进程内客户端解码保持一致。
3. 关键失败路径有结构校验、边界处理和回归测试。
4. 相关测试和编译任务通过；无法在本机运行的平台验证已明确说明。
5. 没有密钥、构建产物或大型重复资源进入版本控制。
