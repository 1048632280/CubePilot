# CubePilot

CubePilot 是一个只运行在 Minecraft Java 原生专用服务端上的 AI 管理辅助模组。Agent 直接读取服务端 API，不会以玩家身份登录。命令默认只作为可点击的聊天建议；只有管理员显式配置到自动白名单、且通过安全规则的命令，才可能由服务器使用发起请求玩家的权限自动执行。

## 支持目标

| GitHub Artifact | JAR 文件名（当前版本） | Minecraft | 加载器 | Java |
|---|---|---:|---|---:|
| `cubepilot-fabric-1.20.1` | `cubepilot-fabric-1.20.1.jar` | 1.20.1 | Fabric | 17 |
| `cubepilot-forge-1.20.1` | `cubepilot-forge-1.20.1.jar` | 1.20.1 | Forge | 17 |
| `cubepilot-fabric-1.21.1` | `cubepilot-fabric-1.21.1.jar` | 1.21.1 | Fabric | 21 |
| `cubepilot-neoforge-1.21.1` | `cubepilot-neoforge-1.21.1.jar` | 1.21.1 | NeoForge | 21 |

不支持 Forge 1.21.1、Bukkit/Paper 插件服务器或客户端安装。

## 通过 GitHub Actions 构建

项目包含四目标矩阵工作流。推送到 `main`/`master`、创建 Pull Request，或在 Actions 页面手动运行 `Build Minecraft server mods`，都会在 GitHub 云端运行共享核心测试、构建四个目标、检查 JAR 必要入口和元数据，并启动对应专用服务端进行冒烟验证。成功后可从工作流运行记录下载四个独立 Artifact。无需在本地安装 Java/Gradle 或运行构建。

## 安装与配置

将匹配版本的 JAR 放入服务端 `mods/`，使用对应加载器启动。Mod 加载时只会创建 `config/cubepilot/cubepilot.toml` 和目录，不读取 API 配置、不创建 API 客户端，也不会发起网络请求。旧版 `cubepilot.properties` 不再读取。编辑 TOML 后，在游戏内使用 `/cp reload`（OP 2）即可让 CubePilot 重新读取自身配置，无需重启服务端；重载失败时会保留上一份有效配置。设置文件中的 API Key 仅作为环境变量缺失时的回退选项；请限制配置目录的操作系统访问权限。

```toml
[api]
endpoint = "https://api.openai.com/v1/chat/completions"
model = "gpt-4o-mini"
key = ""
timeout-seconds = 45
max-response-bytes = 2097152

[command]
offer-add-to-auto-allow = true

[command.auto-allow]
roots = []
subcommands = []
exact = []
```

Mod 首次启动生成的完整 TOML 带有逐项中文注释。默认自动白名单为空；不要为了方便直接允许宽泛的一级命令。

配置自定义 API 地址时使用 HTTPS；只有回环地址（localhost、127.0.0.1、::1）可使用 HTTP。端点必须兼容 OpenAI Chat Completions API。

所有模型请求均等待完整响应、解析和安全核验结束后再向玩家显示；不会逐段发送尚未验证的文本。

## 游戏内指令

所有别名都需要 OP 2 级权限：

- `/cp <问题>`：统一 AI 入口，自动识别普通问答、配置查询、服务端诊断或命令/NBT 请求。
- `/cp ask <问题>`：直接进行普通 AI 问答，不额外执行意图分类。
- `/cp status`：查看简要版本、加载器、难度、在线人数和白名单状态。
- `/cp reload`：重新读取 CubePilot 自身的 `config/cubepilot/cubepilot.toml`，不调用 API；不会热加载其他 Mod 的配置。
- `/cp analyze <问题>`：自动检索允许目录中的日志、崩溃报告和配置，由 AI 选择相关证据进行诊断；路径可选，仅作参考。
- `/cp config <自然语言问题>`：显式触发对配置白名单的广泛只读扫描，由 AI 自行识别 Mod、文件和配置项。
- `/cubepilot`、`/cpilot` 和 `/cpa` 是相同功能的别名。

只有统一入口 `/cp <问题>` 及匹配的聊天前缀会先发送一次短请求识别意图，再调用对应功能；显式的 `/cp ask`、`/cp config` 和 `/cp analyze` 不进行意图分类。普通聊天不会发给模型。所有 AI 入口共用玩家级 8 秒请求冷却。

默认只允许读取 `config/`、`defaultconfigs/`、`world/serverconfig/`、`logs/`、`crash-reports/`、`server.properties`，以及列出 `mods/` 中的 JAR 名称。普通问答不会扫描配置；`/cp config` 和 `/cp analyze` 只在玩家显式请求后读取对应白名单内容，不提供写回能力。聊天前缀触发默认关闭；开启后仅匹配配置数组中的前缀才进入相同意图路由，普通聊天不会发送给模型。所有处理均由玩家显式命令或前缀触发，不后台监视、不持续上传日志。快照、配置查询和诊断操作按审计要求记录。拒绝路径穿越、符号链接、CubePilot 自身凭据目录、密钥/证书、玩家数据、世界存档和超限文件。外部百科检索暂不启用。

## 命令建议安全边界

自动执行白名单支持一级命令、二级命令和完整命令精确匹配；完整命令可通过建议旁的按钮经过两次确认后加入。`offer-add-to-auto-allow = false` 时不会显示或询问加入白名单的按钮，只显示允许的手动建议。永久禁止名单和可调整禁止名单优先于自动白名单；四值安全项分别控制自动执行与 CubePilot 手动建议。默认禁止高危命令自动执行，并禁止使用 `@a`、`@e`、复杂 NBT/组件、批量目标及指向其他玩家的参数。命令成功时只显示一条“已执行”消息；未自动执行的命令才显示可点击的手动建议。`/cp config` 和 `/cp analyze` 是只读入口，即使模型误返回命令也不会执行。AI 回复格式错误时会提示重试，不会把原始 JSON 当作答案或执行其中内容。CubePilot 不拦截玩家自己输入的任何 Minecraft 或 Mod 命令；自动执行仍受该玩家权限和服务器命令语法检查约束。

只读文件和日志内容被当作不可信文本，不作为模型指令；发往 API 前会限制上下文大小并脱敏常见凭据格式。模型响应默认限制为 2 MiB，可通过 `api.max-response-bytes` 调整，超限会立即停止读取。在线玩家名称不会发送给模型。审计事件记录在 `config/cubepilot/audit.jsonl`；请求链输入、实际发送的 Prompt、模型 API 可见响应、命令建议和执行结果另记在 `trace.jsonl`。`logging.trace-enabled = false` 可关闭请求链日志，`logging.max-field-characters` 控制单字段长度。两者采用 UTF-8 JSON Lines，记录按 1 MiB 轮转并最多保留三个归档。写盘通过有界单线程后台队列串行完成，不阻塞 Minecraft 主线程；队列满时丢弃部分日志并限频告警。日志会脱敏常见凭据，不记录模型隐藏思维链；请求链日志可能含玩家问题和模型回复，请限制服务端配置目录的系统访问权限。日志文件不开放给 CubePilot 的只读扫描。

`files.max-scan-files` 和 `files.max-scan-bytes` 分别限制一次 `config/analyze` 请求实际读取的文件数量和总字节数；达到上限时会在上下文中明确标记，模型不得声称已检查全部文件。

CubePilot 在内存中按玩家分别保留最近 6 轮对话，30 分钟无请求后过期；每次送给模型的历史最多约 9000 字符。上下文包含已回复内容、候选命令和宿主执行状态，不跨玩家共享；`/cp reload` 会保留当前会话，但服务端重启后不会恢复。只有显式 `/cp` 或配置的聊天前缀请求进入会话。

命令候选生成后会额外调用一次模型进行独立完整性核验，逐条对照原请求及服务器快照；未确认完整满足的候选不会执行或显示。之后仍需通过白名单、安全策略及当前版本 Brigadier 解析。由于任意 Mod 的自定义命令语义不一定能从语法树推断，核验仍可能保守拒绝或误判；不能视为对所有 Mod 命令语义的数学保证。该核验会增加命令请求的 API 调用次数和等待时间。

## 开源参考

参考仓库、下载的技能资料和研究快照存放在本地 `temp/research/`，该目录已加入 Git 忽略列表，不会进入构建产物。设计参考了 MIT 项目 MC-AIAdmin 的权限/文件隔离思路、Minecraft AI Assistant 的只读与隐私边界，以及 MIT/CC0 Fabric MCP 项目的服务端 API 和异步调度结构。CubePilot 未复制这些项目的源代码。
