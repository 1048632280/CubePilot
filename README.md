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

项目包含四目标矩阵工作流。推送到 `main`/`master`、创建 Pull Request，或在 Actions 页面手动运行 `Build Minecraft server mods`，都会在 GitHub 云端编译并测试共享核心。成功后可从工作流运行记录下载四个独立 Artifact，Artifact 内的 JAR 文件名带有项目版本号。无需在本地安装 Java/Gradle 或运行构建。

## 安装与配置

将匹配版本的 JAR 放入服务端 `mods/`，使用对应加载器启动。Mod 加载时只会创建 `config/cubepilot/cubepilot.toml` 和目录，不读取 API 配置、不创建 API 客户端，也不会发起网络请求。旧版 `cubepilot.properties` 不再读取。编辑 TOML 后，在游戏内使用 `/cp reload`（OP 2）即可让 CubePilot 重新读取自身配置，无需重启服务端；重载失败时会保留上一份有效配置。设置文件中的 API Key 仅作为环境变量缺失时的回退选项；请限制配置目录的操作系统访问权限。

```toml
[api]
endpoint = "https://api.openai.com/v1/chat/completions"
model = "gpt-4o-mini"
key = ""

[command]
offer-add-to-auto-allow = true

[command.auto-allow]
roots = []
subcommands = []
exact = []
```

Mod 首次启动生成的完整 TOML 带有逐项中文注释。默认自动白名单为空；不要为了方便直接允许宽泛的一级命令。

配置自定义 API 地址时使用 HTTPS；只有回环地址（localhost、127.0.0.1、::1）可使用 HTTP。端点必须兼容 OpenAI Chat Completions API。

## 游戏内指令

所有别名都需要 OP 2 级权限：

- `/cp <问题>`、`/cp ask <问题>`：AI 百科问答与服务器上下文问答。
- `/cp status`：查看简要版本、加载器、难度、在线人数和白名单状态。
- `/cp snapshot`：查看运行状态、`server.properties` 安全字段及白名单配置/日志/崩溃文件路径；不会读取清单中文件的内容。
- `/cp mods`：查看已加载 Mod。
- `/cp reload`：重新读取 CubePilot 自身的 `config/cubepilot/cubepilot.toml`，不调用 API；不会热加载其他 Mod 的配置。
- `/cp files [目录]`：列出只读允许目录。
- `/cp read <相对路径>`：读取并做常见凭据脱敏。
- `/cp analyze <路径>`：使用本地规则离线分析日志、崩溃报告或配置，不调用外部 API。
- `/cp config <自然语言问题>`：显式触发对配置白名单的广泛只读扫描，由 AI 自行识别 Mod、文件和配置项。
- `/cp inspect <路径>`、`/cp diagnose <路径> <问题>`：让 AI 分析日志、崩溃报告或配置文件。
- `/cpilot` 和 `/cpa` 是相同功能的别名。

默认只允许读取 `config/`、`defaultconfigs/`、`world/serverconfig/`、`logs/`、`crash-reports/`、`server.properties`，以及列出 `mods/` 中的 JAR 名称。`/cp snapshot` 只提取 `server.properties` 中明确列出的非敏感字段，并只枚举白名单目录下文本文件的路径，不保存快照或修改服务端文件；`/cp config` 只在玩家显式请求后广泛扫描配置摘要，不提供写回能力。普通 `/cp` 不会隐式扫描配置，也不后台监视。`/cp analyze` 只做本地规则诊断，识别异常堆栈、Mixin/Mod 冲突、端口绑定、卡顿、内存耗尽和部分配置风险，不把规则命中当作确定根因，也不会自动修复。所有处理都由玩家显式输入 `/cp`、`/cpilot` 或 `/cpa` 后触发，不后台监视、不持续上传日志。快照、配置查询和诊断操作本身会按审计要求写入 CubePilot 审计日志。拒绝路径穿越、符号链接、CubePilot 自身凭据目录、密钥/证书、玩家数据、世界存档和超限文件。外部百科检索暂不启用。

## 命令建议安全边界

自动执行白名单支持一级命令、二级命令和完整命令精确匹配；完整命令可通过建议旁的按钮经过两次确认后加入。`offer-add-to-auto-allow = false` 时不会显示或询问加入白名单的按钮，只显示允许的手动建议。永久禁止名单和可调整禁止名单优先于自动白名单；四值安全项分别控制自动执行与 CubePilot 手动建议。默认禁止高危命令自动执行，并禁止使用 `@a`、`@e`、复杂 NBT/组件、批量目标及指向其他玩家的参数。`/cp ask` 命中自动白名单且执行成功时只显示一条“已执行”消息；未自动执行的命令才显示可点击的手动建议。`/cp config`、`/cp inspect` 和 `/cp diagnose` 是只读入口，即使模型误返回命令也不会执行。AI 回复格式错误时会提示重试，不会把原始 JSON 当作答案或执行其中内容。CubePilot 不拦截玩家自己输入的任何 Minecraft 或 Mod 命令；自动执行仍受该玩家权限和服务器命令语法检查约束。

只读文件和日志内容被当作不可信文本，不作为模型指令；发往 API 前会限制上下文大小并脱敏常见凭据格式。在线玩家名称不会发送给模型。审计日志位于 `config/cubepilot/audit.jsonl`，包含请求开始/结束、文件访问及建议/拒绝动作，不记录完整提问、文件内容或 API Key。审计文件由模组创建和追加，不会被文件读取接口开放；首次创建的 CubePilot 配置目录和配置文件会尽可能限制为仅服务端操作系统账户可读。

## 开源参考

参考仓库、下载的技能资料和研究快照存放在本地 `temp/research/`，该目录已加入 Git 忽略列表，不会进入构建产物。设计参考了 MIT 项目 MC-AIAdmin 的权限/文件隔离思路、Minecraft AI Assistant 的只读与隐私边界，以及 MIT/CC0 Fabric MCP 项目的服务端 API 和异步调度结构。CubePilot 未复制这些项目的源代码。
