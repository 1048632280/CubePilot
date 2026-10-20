# CubePilot

CubePilot 是一个只运行在 Minecraft Java 原生专用服务端上的 AI 管理辅助模组。Agent 直接读取服务端 API，不以玩家身份登录；AI 生成的 Minecraft 命令只作为可点击的聊天建议填入玩家命令框，必须由管理员检查并手动按回车执行。

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

将匹配版本的 JAR 放入服务端 `mods/`，使用对应加载器启动。首次运行后编辑 `config/cubepilot/cubepilot.properties`，或为服务端进程配置 `CUBEPILOT_API_KEY` 环境变量。设置文件中的 API Key 仅作为环境变量缺失时的回退选项；请限制配置目录的操作系统访问权限。

```properties
api.endpoint=https://api.openai.com/v1/chat/completions
api.model=gpt-4o-mini
api.key=
api.timeout-seconds=45
command.allow-roots=give,effect,enchant,tp,teleport,time,weather,difficulty,gamerule,experience,xp
files.max-bytes=16384
```

配置自定义 API 地址时使用 HTTPS；只有回环地址（localhost、127.0.0.1、::1）可使用 HTTP。端点必须兼容 OpenAI Chat Completions API。

## 游戏内指令

所有别名都需要 OP 2 级权限：

- `/cp <问题>`、`/cp ask <问题>`：AI 百科问答与服务器上下文问答。
- `/cp status`：查看简要版本、加载器、难度、在线人数和白名单状态。
- `/cp snapshot`：查看运行状态、`server.properties` 安全字段及白名单配置/日志/崩溃文件路径；不会读取清单中文件的内容。
- `/cp mods`：查看已加载 Mod。
- `/cp files [目录]`：列出只读允许目录。
- `/cp read <相对路径>`：读取并做常见凭据脱敏。
- `/cp analyze <路径>`：使用本地规则离线分析日志、崩溃报告或配置，不调用外部 API。
- `/cp config <Mod ID>`：在 `config/` 中定位该 Mod 的候选配置并解析键路径；追加问题可让 AI 说明如何手工修改。
- `/cp inspect <路径>`、`/cp diagnose <路径> <问题>`：让 AI 分析日志、崩溃报告或配置文件。
- `/cpilot` 和 `/cpa` 是相同功能的别名。

默认只允许读取 `config/`、`logs/`、`crash-reports/`、`server.properties`，以及列出 `mods/` 中的 JAR 名称。`/cp snapshot` 只提取 `server.properties` 中明确列出的非敏感字段，并只枚举白名单目录下文本文件的路径，不保存快照或修改服务端文件；`/cp config` 只定位和解析配置键，不提供写回能力。普通 `/cp` 问题只有同时提到已加载 Mod ID 和配置意图时，才会在本次请求中自动定位该 Mod 配置，不会后台扫描。`/cp analyze` 只做本地规则诊断，识别异常堆栈、Mixin/Mod 冲突、端口绑定、卡顿、内存耗尽和部分配置风险，不把规则命中当作确定根因，也不会自动修复。所有处理都由玩家显式输入 `/cp`、`/cpilot` 或 `/cpa` 后触发，不后台监视、不持续上传日志。快照、配置查询和诊断操作本身会按审计要求写入 CubePilot 审计日志。拒绝路径穿越、符号链接、CubePilot 自身凭据目录、密钥/证书、玩家数据、世界存档和超限文件。外部百科检索暂不启用。

## 命令建议安全边界

模型返回受约束的 JSON 建议。CubePilot 只展示通过命令根白名单和 Brigadier 语法检查的建议，并使用聊天的 `SUGGEST_COMMAND` 点击动作填入文本；模组不分派、不执行建议命令，也不替玩家提权。请仔细检查目标选择器、数量和参数后再按回车。命令根白名单可进一步缩小，危险命令无论配置如何都被拒绝。

只读文件和日志内容被当作不可信文本，不作为模型指令；发往 API 前会限制上下文大小并脱敏常见凭据格式。在线玩家名称不会发送给模型。审计日志位于 `config/cubepilot/audit.jsonl`，包含请求开始/结束、文件访问及建议/拒绝动作，不记录完整提问、文件内容或 API Key。审计文件由模组创建和追加，不会被文件读取接口开放；首次创建的 CubePilot 配置目录和配置文件会尽可能限制为仅服务端操作系统账户可读。

## 开源参考

参考仓库、下载的技能资料和研究快照存放在本地 `temp/research/`，该目录已加入 Git 忽略列表，不会进入构建产物。设计参考了 MIT 项目 MC-AIAdmin 的权限/文件隔离思路、Minecraft AI Assistant 的只读与隐私边界，以及 MIT/CC0 Fabric MCP 项目的服务端 API 和异步调度结构。CubePilot 未复制这些项目的源代码。
