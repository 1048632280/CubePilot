package com.cubepilot.minecraft;

import com.cubepilot.core.AssistantReply;
import com.cubepilot.core.AuditLog;
import com.cubepilot.core.CommandPolicy;
import com.cubepilot.core.ConfigInspector;
import com.cubepilot.core.CubePilotAgent;
import com.cubepilot.core.CubePilotTasks;
import com.cubepilot.core.DiagnosticAnalyzer;
import com.cubepilot.core.DiagnosticReport;
import com.cubepilot.core.ModInfo;
import com.cubepilot.core.ReadOnlyFileAccess;
import com.cubepilot.core.ServerSnapshot;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class CubePilotCommands {
    private static final List<String> ROOTS = List.of("cp", "cpilot", "cpa");
    private static final String PREFIX = "[CubePilot] ";
    private static final int MAX_CONFIG_CONTEXT_CHARS = 44000;
    private static final long ALLOW_CONFIRM_TTL_MILLIS = 120_000L;
    private static final Map<String, PendingAllow> PENDING_ALLOWS = new ConcurrentHashMap<>();
    private static final String ASK_TASK_PROMPT =
            "任务：简短回答百科或服务器问题。只给最终结论；需要命令时只给必要命令，不解释执行过程。";
    private static final String CONFIG_TASK_PROMPT =
            "任务：只读定位 Mod 配置。自行从扫描证据识别 Mod；answer 分段输出，每项依次写 Mod、文件路径、行号/键、当前值、手工改法。最多 8 行，每行不超过 160 字；证据不足就只说未找到。不要输出命令。";
    private static final String INSPECT_TASK_PROMPT =
            "任务：只读分析指定文件。answer 只写结论、最多 3 条证据和 1 条建议；最多 6 行，每行不超过 160 字。不要输出思考过程。";
    private static final String DIAGNOSE_TASK_PROMPT =
            "任务：只读诊断指定文件。按‘结论；证据；建议’分段，最多 6 行，每行不超过 160 字；不确定时明确写不确定。";

    private CubePilotCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
            String loader, List<ModInfo> mods) {
        for (String root : ROOTS) {
            dispatcher.register(createRoot(root, loader, mods));
        }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> createRoot(String root,
            String loader, List<ModInfo> mods) {
        return Commands.literal(root)
                .requires(source -> source.getEntity() instanceof ServerPlayer && source.hasPermission(2))
                .executes(context -> help(context.getSource()))
                .then(Commands.literal("help").executes(context -> help(context.getSource())))
                .then(Commands.literal("status").executes(context -> status(context, loader, mods)))
                .then(Commands.literal("snapshot").executes(context -> showSnapshot(context, loader, mods)))
                .then(Commands.literal("mods").executes(context -> showMods(context, mods)))
                .then(Commands.literal("reload").executes(CubePilotCommands::reload))
                .then(Commands.literal("allow")
                        .then(Commands.literal("add")
                                .then(Commands.argument("token", StringArgumentType.word())
                                        .executes(context -> requestAllowConfirmation(context,
                                                StringArgumentType.getString(context, "token")))))
                        .then(Commands.literal("confirm")
                                .then(Commands.argument("token", StringArgumentType.word())
                                        .executes(context -> confirmAllow(context,
                                                StringArgumentType.getString(context, "token"))))))
                .then(Commands.literal("files")
                        .executes(context -> listFiles(context, ""))
                        .then(Commands.argument("path", StringArgumentType.greedyString())
                                .executes(context -> listFiles(context,
                                        StringArgumentType.getString(context, "path")))))
                .then(Commands.literal("read")
                        .then(Commands.argument("path", StringArgumentType.greedyString())
                                .executes(context -> readFile(context,
                                        StringArgumentType.getString(context, "path")))))
                .then(Commands.literal("analyze")
                        .then(Commands.argument("path", StringArgumentType.word())
                                .executes(context -> analyze(context,
                                        StringArgumentType.getString(context, "path")))))
                .then(Commands.literal("config")
                        .then(Commands.argument("request", StringArgumentType.greedyString())
                                .executes(context -> configRequest(context, loader, mods,
                                        StringArgumentType.getString(context, "request")))))
                .then(Commands.literal("inspect")
                        .then(Commands.argument("path", StringArgumentType.word())
                                .executes(context -> inspect(context, loader, mods,
                                        StringArgumentType.getString(context, "path"),
                                        "请读取并解释这个服务端文件，指出异常或值得注意的设置。",
                                        INSPECT_TASK_PROMPT))))
                .then(Commands.literal("diagnose")
                        .then(Commands.argument("path", StringArgumentType.word())
                                .then(Commands.argument("question", StringArgumentType.greedyString())
                                        .executes(context -> inspect(context, loader, mods,
                                                StringArgumentType.getString(context, "path"),
                                                StringArgumentType.getString(context, "question"),
                                                DIAGNOSE_TASK_PROMPT)))))
                .then(Commands.literal("ask")
                        .then(Commands.argument("question", StringArgumentType.greedyString())
                                .executes(context -> ask(context, loader, mods,
                                        StringArgumentType.getString(context, "question"), ""))))
                .then(Commands.argument("question", StringArgumentType.greedyString())
                        .executes(context -> ask(context, loader, mods,
                                StringArgumentType.getString(context, "question"), "")));
    }

    private static int help(CommandSourceStack source) {
        send(source, "命令别名：/cp、/cpilot、/cpa（需要 OP 2 级权限）");
        send(source, "/cp <问题> 或 /cp ask <问题>：查询百科/服务器状态并生成命令建议");
        send(source, "/cp status | mods：查看简要服务器状态或已加载 Mod");
        send(source, "/cp reload：重新读取 CubePilot 自身 cubepilot.toml，不调用 API");
        send(source, "/cp snapshot：查看运行时状态、安全设置和只读文件路径清单");
        send(source, "/cp files [目录]：列出只读白名单文件；/cp read <相对路径>：读取并脱敏");
        send(source, "/cp analyze <路径>：离线规则分析日志、崩溃报告或配置，不调用 API");
        send(source, "/cp config <自然语言问题>：自动识别 Mod，扫描只读配置并回答当前值与手工修改方法");
        send(source, "/cp inspect <路径>：让 AI 分析白名单文件；/cp diagnose <路径> <问题>：分析指定日志/配置");
        send(source, "命令命中自动白名单且通过全部安全检查时会执行；其他允许显示的建议需点击后手动回车。");
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> context,
            String loader, List<ModInfo> mods) {
        try {
            ServerSnapshot snapshot = runtimeSnapshot(context.getSource().getServer(), loader, mods);
            for (String line : snapshot.toStatusText().split("\\n")) {
                send(context.getSource(), line);
            }
            return 1;
        } catch (IllegalArgumentException exception) {
            return fail(context.getSource(), "无法读取服务器状态：" + safeMessage(exception));
        }
    }

    private static int reload(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        try {
            CubePilotAgent agent = CubePilotAgent.reload();
            agent.audit().record(source.getTextName(), "config.reload", "success");
            send(source, "已重新加载 CubePilot 配置；后续请求将使用新配置。");
            send(source, "API 地址：" + agent.config().endpoint());
            send(source, "模型：" + agent.config().model());
            return 1;
        } catch (IOException | IllegalArgumentException exception) {
            auditDenied(source, "config.reload", "cubepilot.toml", exception);
            return fail(source, "重新加载失败，旧配置仍在使用：" + safeMessage(exception));
        }
    }

    private static int requestAllowConfirmation(CommandContext<CommandSourceStack> context, String token) {
        CommandSourceStack source = context.getSource();
        PendingAllow pending = consumePending(token, source, PendingStage.OFFER);
        if (pending == null) {
            return fail(source, "白名单请求无效、已使用、已过期，或不属于当前操作者。");
        }
        CommandPolicy.Decision decision = currentDecision(source, pending.command());
        if (!decision.offerAddToAutoAllow()) {
            return fail(source, "当前配置已不允许把这条命令加入自动白名单，请重新发起 AI 请求。");
        }
        pruneExpiredPendingAllows();
        String confirmationToken = newToken();
        PENDING_ALLOWS.put(confirmationToken, new PendingAllow(
                actorId(source), pending.command(), System.currentTimeMillis() + ALLOW_CONFIRM_TTL_MILLIS,
                PendingStage.CONFIRM));
        Component confirm = Component.literal("[确认写入精确自动白名单]")
                .withStyle(ChatFormatting.RED)
                .withStyle(style -> style.withClickEvent(new ClickEvent(
                        ClickEvent.Action.RUN_COMMAND, "/cp allow confirm " + confirmationToken)));
        send(source, "即将把以下完整命令写入 CubePilot 自身配置；本次不会执行该命令：");
        send(source, pending.command());
        source.sendSuccess(() -> confirm, false);
        return 1;
    }

    private static int confirmAllow(CommandContext<CommandSourceStack> context, String token) {
        CommandSourceStack source = context.getSource();
        PendingAllow pending = consumePending(token, source, PendingStage.CONFIRM);
        if (pending == null) {
            return fail(source, "确认请求无效、已使用、已过期，或不属于当前操作者。");
        }
        try {
            CommandPolicy.Decision decision = currentDecision(source, pending.command());
            if (!decision.offerAddToAutoAllow()) {
                return fail(source, "当前配置已不允许把这条命令加入自动白名单，请重新发起 AI 请求。");
            }
            boolean added = CubePilotAgent.addExactAutoAllowedCommand(pending.command());
            CubePilotAgent current = CubePilotAgent.get();
            current.audit().record(source.getTextName(), "command.auto-allow.add",
                    "command=" + pending.command() + " result=" + (added ? "added" : "already-present"));
            send(source, added
                    ? "已写入精确自动白名单并重新加载配置；该命令从后续请求开始可自动执行。"
                    : "该完整命令已经在自动白名单中，配置未重复写入。");
            return 1;
        } catch (IOException | IllegalArgumentException exception) {
            auditDenied(source, "command.auto-allow.add", pending.command(), exception);
            return fail(source, "写入或重载失败，原配置和旧运行配置已保留：" + safeMessage(exception));
        }
    }

    private static PendingAllow consumePending(String token, CommandSourceStack source, PendingStage expectedStage) {
        PendingAllow pending = PENDING_ALLOWS.remove(token);
        if (pending == null || pending.stage() != expectedStage
                || pending.expiresAtMillis() < System.currentTimeMillis()
                || !pending.actor().equals(actorId(source))) {
            return null;
        }
        return pending;
    }

    private static String newToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static void pruneExpiredPendingAllows() {
        long now = System.currentTimeMillis();
        PENDING_ALLOWS.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() < now);
    }

    private static CommandPolicy.Decision currentDecision(CommandSourceStack source, String command) {
        try {
            CubePilotAgent agent = CubePilotAgent.get();
            List<String> players = source.getServer().getPlayerList().getPlayers().stream()
                    .map(player -> player.getGameProfile().getName())
                    .toList();
            return CommandPolicy.evaluate(command, agent.config(), source.getTextName(), players);
        } catch (IOException | IllegalArgumentException exception) {
            return new CommandPolicy.Decision(null, false, false, false,
                    "当前配置不可用", List.of());
        }
    }

    private static String actorId(CommandSourceStack source) {
        return source.getEntity() == null ? "" : source.getEntity().getUUID().toString();
    }

    private static int showSnapshot(CommandContext<CommandSourceStack> context,
            String loader, List<ModInfo> mods) {
        CommandSourceStack source = context.getSource();
        try {
            CubePilotAgent agent = CubePilotAgent.get();
            send(source, "正在后台生成只读服务器快照；不会阻塞服务器线程。");
            snapshotAsync(source.getServer(), loader, mods, agent.files())
                    .whenComplete((snapshot, error) -> source.getServer().execute(() -> {
                        if (error != null) {
                            send(source, "无法生成只读服务器快照：" + shortText(safeMessage(error), 160));
                            return;
                        }
                        agent.audit().record(source.getTextName(), "snapshot.capture",
                                "settings=" + snapshot.serverSettings().size()
                                        + " files=" + snapshot.configurationFiles().size());
                        sendBoundedLines(source, snapshot.toPromptText(), 120, 240);
                    }));
            return 1;
        } catch (IOException | IllegalArgumentException exception) {
            return fail(source, "无法生成只读服务器快照：" + safeMessage(exception));
        }
    }

    private static int showMods(CommandContext<CommandSourceStack> context, List<ModInfo> mods) {
        send(context.getSource(), "已加载 Mod（" + mods.size() + " 个）：");
        List<String> visible = mods.stream().limit(200).map(ModInfo::displayText).toList();
        for (int start = 0; start < visible.size(); start += 12) {
            send(context.getSource(), String.join(", ", visible.subList(start, Math.min(start + 12, visible.size()))));
        }
        if (mods.size() > visible.size()) {
            send(context.getSource(), "另有 " + (mods.size() - visible.size()) + " 个 Mod 未显示。");
        }
        send(context.getSource(), "仅支持 Fabric/Forge/NeoForge 原生服务器；Bukkit/Paper 插件不在支持范围内。");
        return 1;
    }

    private static int listFiles(CommandContext<CommandSourceStack> context, String path) {
        CommandSourceStack source = context.getSource();
        try {
            CubePilotAgent agent = CubePilotAgent.get();
            List<String> entries = agent.files().list(path);
            agent.audit().record(source.getTextName(), "file.list", path.isBlank() ? "." : path);
            send(source, "只读文件列表：" + (path.isBlank() ? "." : path));
            if (entries.isEmpty()) {
                send(source, "没有可显示的文件或子目录。");
            }
            for (int start = 0; start < entries.size(); start += 8) {
                send(source, String.join(" | ", entries.subList(start, Math.min(start + 8, entries.size()))));
            }
            return 1;
        } catch (IOException | IllegalArgumentException exception) {
            auditDenied(source, "file.list", path, exception);
            return fail(source, exception.getMessage());
        }
    }

    private static int readFile(CommandContext<CommandSourceStack> context, String path) {
        CommandSourceStack source = context.getSource();
        try {
            CubePilotAgent agent = CubePilotAgent.get();
            String contents = agent.files().read(path);
            agent.audit().record(source.getTextName(), "file.read", path);
            send(source, "只读文件内容（已做常见凭据脱敏）：" + path);
            sendBoundedLines(source, contents, 40, 240);
            return 1;
        } catch (IOException | IllegalArgumentException exception) {
            auditDenied(source, "file.read", path, exception);
            return fail(source, exception.getMessage());
        }
    }

    private static int inspect(CommandContext<CommandSourceStack> context, String loader,
            List<ModInfo> mods, String path, String question, String taskPrompt) {
        CommandSourceStack source = context.getSource();
        CubePilotAgent agent;
        try {
            agent = CubePilotAgent.get();
        } catch (IOException | IllegalArgumentException exception) {
            auditDenied(source, "file.inspect", path, exception);
            return fail(source, exception.getMessage());
        }
        send(source, "正在后台读取并分析文件；不会阻塞服务器线程。");
        CubePilotTasks.supplyAsync(() -> {
            try {
                String contents = agent.files().read(path);
                DiagnosticReport report = DiagnosticAnalyzer.analyze(path, contents);
                return new DiagnosticInput(contents, report);
            } catch (IOException | IllegalArgumentException exception) {
                throw new CompletionException(exception);
            }
        }).whenComplete((input, error) -> source.getServer().execute(() -> {
            if (error != null) {
                auditDenied(source, "file.inspect", path, error);
                send(source, "文件分析失败：" + shortText(safeMessage(error), 160));
                return;
            }
            DiagnosticReport report = input.report();
            agent.audit().record(source.getTextName(), "file.inspect",
                    "path=" + path + " category=" + report.category()
                            + " severity=" + report.severity()
                            + " findings=" + report.findings().size());
            ask(context, loader, mods, question,
                    "本地只读规则诊断摘要：\n" + report.toText()
                            + "\n\n文件路径：" + path + "\n文件内容（凭据已脱敏）：\n" + input.contents(),
                    taskPrompt, false);
        }));
        return 1;
    }

    private static int analyze(CommandContext<CommandSourceStack> context, String path) {
        CommandSourceStack source = context.getSource();
        CubePilotAgent agent;
        try {
            agent = CubePilotAgent.get();
        } catch (IOException | IllegalArgumentException exception) {
            auditDenied(source, "file.analyze", path, exception);
            return fail(source, exception.getMessage());
        }
        send(source, "正在后台分析文件；不会阻塞服务器线程。");
        CubePilotTasks.supplyAsync(() -> {
            try {
                String contents = agent.files().read(path);
                return new DiagnosticInput(contents, DiagnosticAnalyzer.analyze(path, contents));
            } catch (IOException | IllegalArgumentException exception) {
                throw new CompletionException(exception);
            }
        }).whenComplete((input, error) -> source.getServer().execute(() -> {
            if (error != null) {
                auditDenied(source, "file.analyze", path, error);
                send(source, "文件分析失败：" + shortText(safeMessage(error), 160));
                return;
            }
            DiagnosticReport report = input.report();
            agent.audit().record(source.getTextName(), "file.analyze",
                    "path=" + path + " category=" + report.category()
                            + " severity=" + report.severity()
                            + " findings=" + report.findings().size());
            sendBoundedLines(source, report.toText(), 80, 240);
        }));
        return 1;
    }

    private static int configRequest(CommandContext<CommandSourceStack> context, String loader,
            List<ModInfo> mods, String request) {
        CommandSourceStack source = context.getSource();
        String question = request == null ? "" : request.trim();
        if (question.isBlank() || question.length() > 4000) {
            return fail(source, "配置问题不能为空且长度不能超过 4000 个字符。");
        }
        return askDiscoveredModConfig(context, loader, mods, question);
    }

    private static int askDiscoveredModConfig(CommandContext<CommandSourceStack> context, String loader,
            List<ModInfo> mods, String question) {
        CommandSourceStack source = context.getSource();
        try {
            CubePilotAgent agent = CubePilotAgent.get();
            agent.audit().record(source.getTextName(), "config.auto-discover",
                    "question_length=" + question.length());
            send(source, "正在异步扫描全部白名单 Mod 配置，并交给模型判断；不会读取凭据或修改文件。");
            boolean useStreaming = agent.config().streaming();
            StreamDisplay display = useStreaming ? new StreamDisplay(source) : null;
            snapshotAsync(source.getServer(), loader, mods, agent.files()).thenCompose(snapshot ->
                CubePilotTasks.supplyAsync(() -> {
                try {
                    return discoverAllConfigContext(agent.files(), mods, question);
                } catch (IOException exception) {
                    throw new CompletionException(exception);
                }
                }).thenCompose(attachedContext -> {
                agent.audit().record(source.getTextName(), "config.scan.complete",
                        "context_length=" + attachedContext.length());
                return useStreaming
                        ? agent.askStreaming(question, attachedContext, snapshot, MAX_CONFIG_CONTEXT_CHARS,
                                CONFIG_TASK_PROMPT, display::accept)
                        : agent.ask(question, attachedContext, snapshot, MAX_CONFIG_CONTEXT_CHARS,
                                CONFIG_TASK_PROMPT);
                })).whenComplete((reply, error) -> source.getServer().execute(() -> {
                if (display != null) {
                    display.flush();
                }
                if (error != null) {
                    agent.audit().record(source.getTextName(), "config.request.error", safeMessage(error));
                    send(source, "配置分析失败：" + shortText(safeMessage(error), 160));
                    return;
                }
                agent.audit().record(source.getTextName(), "config.request.complete",
                        "answer_length=" + reply.answer().length());
                renderReply(source, agent, reply, false, display != null && display.hasSent());
                }));
            return 1;
        } catch (IOException | IllegalArgumentException exception) {
            auditDenied(source, "config.auto-discover", question, exception);
            return fail(source, "自动检索配置失败：" + safeMessage(exception));
        }
    }

    /* 已由无关键词的广泛扫描替代。
    private static String discoverConfigContext(ReadOnlyFileAccess files, List<ModInfo> mods,
            String question) throws IOException {
        List<ConfigCandidate> candidates = new ArrayList<>();
        for (ModInfo mod : mods) {
            if (!mod.isValidId()) {
                continue;
            }
            List<String> paths = files.findModConfigurationFiles(mod.id(), mod.name());
            for (String path : paths) {
                try {
                    String contents = files.read(path);
                    ConfigInspector.ConfigDocument document = ConfigInspector.inspect(path, contents);
                    int score = (mod.matches(question) ? 1000 : 0)
                            + relevance(question, path, document);
                    candidates.add(new ConfigCandidate(mod, path, document, contents, score));
                } catch (IOException ignored) {
                    // 单个文件不可读时继续尝试其他候选，不扩大白名单范围。
                }
            }
        }

        candidates.sort(Comparator.comparingInt(ConfigCandidate::score).reversed()
                .thenComparing(ConfigCandidate::path));
        if (candidates.isEmpty()) {
            List<String> fallbackPaths = files.searchConfigurationFiles(question, 8);
            if (fallbackPaths.isEmpty()) {
                fallbackPaths = files.configurationInventorySnapshot().stream()
                        .filter(path -> path.startsWith("config/") || path.startsWith("defaultconfigs/")
                                || path.startsWith("world/serverconfig/"))
                        .limit(12).toList();
            }
            for (String path : fallbackPaths) {
                String contents = files.read(path);
                ConfigInspector.ConfigDocument document = ConfigInspector.inspect(path, contents);
                candidates.add(new ConfigCandidate(null, path, document, contents,
                        relevance(question, path, document)));
            }
        }

        StringBuilder context = new StringBuilder("自然语言配置自动检索上下文（只读）：\n")
                .append("用户问题：").append(question)
                .append("\n已加载 Mod 候选（ID / 显示名 / 版本）：\n");
        mods.stream().limit(160).forEach(mod -> context.append("- ").append(mod.displayText()).append('\n'));
        if (candidates.isEmpty()) {
            context.append("未从允许目录找到明显匹配的配置文件。请明确说明未找到证据，不能编造路径或键名。\n");
            return context.toString();
        }
        context.append("\n按 Mod 名称、文件名、配置键和值排序的候选文件（最多 6 个）：\n");
        for (ConfigCandidate candidate : candidates.stream().limit(6).toList()) {
            if (candidate.mod() != null) {
                context.append("候选 Mod：").append(candidate.mod().displayText()).append('\n');
            }
            context.append(candidate.document().toSummary()).append('\n');
            context.append("脱敏原文（用于核对行和值，只读）：\n");
            int remaining = 18000 - context.length();
            if (remaining <= 0) {
                break;
            }
            context.append(candidate.contents(), 0, Math.min(candidate.contents().length(),
                    Math.min(6000, remaining))).append('\n');
        }
        context.append("请只根据这些证据回答：准确指出 Mod、文件路径、键路径和当前值；"
                + "给出手工修改示例和重启/备份提醒；没有证据时明确说未找到。\n");
        return context.toString();
    }

    */
    private static String discoverAllConfigContext(ReadOnlyFileAccess files, List<ModInfo> mods,
            String question) throws IOException {
        List<String> paths = files.configurationFilesSnapshot();
        StringBuilder context = new StringBuilder("Mod 配置广泛扫描上下文（只读、未做关键词筛选）：\n")
                .append("用户问题：").append(question)
                .append("\n已加载 Mod（ID / 显示名 / 版本）：\n");
        mods.stream().limit(160).forEach(mod -> context.append("- ").append(mod.displayText()).append('\n'));
        context.append("\n扫描目录：config/、defaultconfigs/、world/serverconfig/")
                .append("\n发现配置文件：").append(paths.size()).append(" 个\n");
        if (paths.isEmpty()) {
            context.append("未发现可读取的配置文件。请明确说明没有证据，不能编造路径或键名。\n");
            return context.toString();
        }

        context.append("全部文件路径：\n");
        paths.forEach(path -> context.append("- ").append(path).append('\n'));
        int footerReserve = 500;
        int available = Math.max(0, MAX_CONFIG_CONTEXT_CHARS - context.length() - footerReserve);
        int perFileBudget = Math.max(180, Math.min(6000, available / paths.size()));
        context.append("\n各文件配置摘要（每个文件公平分配上下文，不按问题相关度排序）：\n");
        for (String path : paths) {
            if (context.length() >= MAX_CONFIG_CONTEXT_CHARS - footerReserve) {
                break;
            }
            appendConfigSummary(context, files, path, perFileBudget);
        }
        context.append("请由模型自行判断用户指的是哪个 Mod、文件和设置，不要依赖预设关键词。"
                + "只根据所给证据回答：指出文件路径、行号、键路径和当前值，给出手工修改示例；"
                + "证据不足时明确说明，不得编造。\n");
        return context.toString();
    }

    private static void appendConfigSummary(StringBuilder context, ReadOnlyFileAccess files,
            String path, int budget) {
        int start = context.length();
        context.append("\n文件：").append(path).append('\n');
        try {
            String contents = files.read(path);
            ConfigInspector.ConfigDocument document = ConfigInspector.inspect(path, contents);
            context.append("格式：").append(document.format()).append('\n');
            if (document.entries().isEmpty()) {
                context.append("（没有识别到结构化键值，提供脱敏原文节选）\n");
                String excerpt = contents.replaceAll("[\\r\\n]+", " ").trim();
                int remaining = Math.max(0, budget - (context.length() - start));
                if (!excerpt.isEmpty() && remaining > 0) {
                    context.append(excerpt, 0, Math.min(excerpt.length(), remaining)).append('\n');
                }
            } else {
                for (ConfigInspector.Entry entry : document.entries()) {
                    if (context.length() - start >= budget) {
                        context.append("…\n");
                        break;
                    }
                    context.append("- ");
                    if (entry.line() > 0) {
                        context.append("第").append(entry.line()).append("行 ");
                    }
                    context.append(entry.path()).append(" = ").append(entry.value()).append('\n');
                }
            }
            if (context.length() - start > budget) {
                context.setLength(start + budget);
                context.append("…\n");
            }
        } catch (IOException exception) {
            context.append("读取失败：").append(safeMessage(exception)).append('\n');
            if (context.length() - start > budget) {
                context.setLength(start + budget);
            }
        }
    }

    private static int ask(CommandContext<CommandSourceStack> context, String loader,
            List<ModInfo> mods, String question, String attachedContext) {
        return ask(context, loader, mods, question, attachedContext, ASK_TASK_PROMPT, true);
    }

    private static int ask(CommandContext<CommandSourceStack> context, String loader,
            List<ModInfo> mods, String question, String attachedContext, String taskPrompt) {
        return ask(context, loader, mods, question, attachedContext, taskPrompt, true);
    }

    private static int ask(CommandContext<CommandSourceStack> context, String loader,
            List<ModInfo> mods, String question, String attachedContext,
            String taskPrompt, boolean allowCommands) {
        CommandSourceStack source = context.getSource();
        if (question == null || question.isBlank() || question.length() > 4000) {
            return fail(source, "问题不能为空且长度不能超过 4000 个字符。");
        }
        CubePilotAgent agent;
        try {
            agent = CubePilotAgent.get();
        } catch (IOException | IllegalArgumentException exception) {
            return fail(source, "CubePilot 配置读取失败：" + safeMessage(exception));
        }
        agent.audit().record(source.getTextName(), "request.start", "question_length=" + question.length());
        send(source, "正在联系配置的 OpenAI 兼容 API；等待期间服务器线程不会被阻塞。");
        boolean useStreaming = !allowCommands && agent.config().streaming();
        StreamDisplay display = useStreaming ? new StreamDisplay(source) : null;
        snapshotAsync(source.getServer(), loader, mods, agent.files())
                .thenCompose(snapshot -> useStreaming
                        ? agent.askStreaming(question, attachedContext, snapshot, 16000, taskPrompt,
                                display::accept)
                        : agent.ask(question, attachedContext, snapshot, 16000, taskPrompt))
                .whenComplete((reply, error) -> source.getServer().execute(() -> {
            if (display != null) {
                display.flush();
            }
            if (error != null) {
                agent.audit().record(source.getTextName(), "request.error", safeMessage(error));
                send(source, "请求失败：" + shortText(safeMessage(error), 180));
                return;
            }
            agent.audit().record(source.getTextName(), "request.complete", "answer_length=" + reply.answer().length());
            renderReply(source, agent, reply, allowCommands, display != null && display.hasSent());
        }));
        return 1;
    }

    private static void renderReply(CommandSourceStack source, CubePilotAgent agent,
            AssistantReply reply, boolean allowCommands) {
        renderReply(source, agent, reply, allowCommands, false);
    }

    private static void renderReply(CommandSourceStack source, CubePilotAgent agent,
            AssistantReply reply, boolean allowCommands, boolean answerAlreadySent) {
        if (!(source.getEntity() instanceof ServerPlayer player)
                || source.getServer().getPlayerList().getPlayer(player.getUUID()) != player) {
            agent.audit().record(source.getTextName(), "request.response.discarded", "requesting player is offline");
            return;
        }
        if (!allowCommands) {
            if (!reply.commandSuggestions().isEmpty()) {
                agent.audit().record(source.getTextName(), "command.rejected",
                        "read-only request returned command suggestions");
            }
            if (!answerAlreadySent) {
                sendCompactLines(source, reply.answer(), 8, 180);
            }
            return;
        }
        List<String> onlinePlayers = source.getServer().getPlayerList().getPlayers().stream()
                .map(onlinePlayer -> onlinePlayer.getGameProfile().getName())
                .toList();
        List<String> manualCommands = new ArrayList<>();
        List<String> offerCommands = new ArrayList<>();
        String rejectionReason = null;
        for (String suggestion : reply.commandSuggestions()) {
            CommandPolicy.Decision decision = CommandPolicy.evaluate(
                    suggestion, agent.config(), source.getTextName(), onlinePlayers);
            if (!decision.valid()) {
                agent.audit().record(source.getTextName(), "command.rejected", decision.reason());
                if (rejectionReason == null) {
                    rejectionReason = decision.reason();
                }
                continue;
            }
            String command = decision.command();
            String raw = command.substring(1);
            ParseResults<CommandSourceStack> parsed = source.getServer().getCommands()
                    .getDispatcher().parse(raw, source);
            if (parsed.getReader().canRead() || !parsed.getExceptions().isEmpty()) {
                String reason = parseFailure(parsed);
                agent.audit().record(source.getTextName(), "command.rejected", reason);
                if (rejectionReason == null) {
                    rejectionReason = reason;
                }
                continue;
            }

            if (decision.automatic()) {
                try {
                    int result = source.getServer().getCommands().getDispatcher().execute(parsed);
                    agent.audit().record(source.getTextName(), "command.auto-executed",
                            "command=" + command + " result=" + result + " reason=" + decision.reason());
                    send(source, "已执行：" + shortText(command, 180));
                    return;
                } catch (CommandSyntaxException | RuntimeException exception) {
                    agent.audit().record(source.getTextName(), "command.auto-error",
                            "command=" + command + " error=" + safeMessage(exception));
                    send(source, "执行失败：" + shortText(safeMessage(exception), 160));
                    return;
                }
            }

            if (decision.manualAllowed()) {
                manualCommands.add(command);
                agent.audit().record(source.getTextName(), "command.suggested",
                        "command=" + command + " reason=" + decision.reason());
            }
            if (decision.offerAddToAutoAllow()) {
                offerCommands.add(command);
            } else if (!decision.manualAllowed() && rejectionReason == null) {
                rejectionReason = decision.reason();
            }
        }

        if (!manualCommands.isEmpty() || !offerCommands.isEmpty()) {
            sendCompactLines(source, reply.answer(), 8, 180);
            for (String command : manualCommands) {
                Component clickable = Component.literal("建议：[点击填入后回车] " + shortText(command, 220))
                        .withStyle(ChatFormatting.GREEN)
                        .withStyle(style -> style.withClickEvent(
                                new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command)));
                source.sendSuccess(() -> clickable, false);
            }
            for (String command : offerCommands) {
                pruneExpiredPendingAllows();
                String token = newToken();
                PENDING_ALLOWS.put(token, new PendingAllow(actorId(source), command,
                        System.currentTimeMillis() + ALLOW_CONFIRM_TTL_MILLIS, PendingStage.OFFER));
                Component add = Component.literal("按钮：[加入精确自动白名单]")
                        .withStyle(ChatFormatting.YELLOW)
                        .withStyle(style -> style.withClickEvent(new ClickEvent(
                                ClickEvent.Action.RUN_COMMAND, "/cp allow add " + token)));
                source.sendSuccess(() -> add, false);
            }
            send(source, "以上建议未执行。");
        } else if (!reply.commandSuggestions().isEmpty()) {
            send(source, "命令未执行：" + shortText(
                    rejectionReason == null ? "未通过安全或语法检查" : rejectionReason, 160));
        } else {
            sendCompactLines(source, reply.answer(), 8, 180);
        }
    }

    private static ServerSnapshot runtimeSnapshot(MinecraftServer server, String loader, List<ModInfo> mods) {
        return new ServerSnapshot(Instant.now(), SharedConstants.getCurrentVersion().getName(), loader,
                server.overworld().getDifficulty().getDisplayName().getString(),
                server.getPlayerList().getPlayerCount(), server.getPlayerList().isUsingWhitelist(),
                Map.of(), List.of(), modTexts(mods));
    }

    private static CompletableFuture<ServerSnapshot> snapshotAsync(MinecraftServer server, String loader,
            List<ModInfo> mods, ReadOnlyFileAccess files) {
        String difficulty = server.overworld().getDifficulty().getDisplayName().getString();
        int playerCount = server.getPlayerList().getPlayerCount();
        boolean whitelist = server.getPlayerList().isUsingWhitelist();
        List<String> modTexts = modTexts(mods);
        return CubePilotTasks.supplyAsync(() -> {
            try {
                return new ServerSnapshot(Instant.now(), SharedConstants.getCurrentVersion().getName(), loader,
                        difficulty, playerCount, whitelist, files.readServerSettingsSnapshot(),
                        files.configurationInventorySnapshot(), modTexts);
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        });
    }

    private static List<String> modTexts(List<ModInfo> mods) {
        return mods.stream().map(ModInfo::displayText).toList();
    }

    private static void sendBoundedLines(CommandSourceStack source, String text, int maxLines, int maxLineLength) {
        String[] lines = text.split("\\R", -1);
        int count = Math.min(lines.length, maxLines);
        for (int index = 0; index < count; index++) {
            String line = lines[index];
            if (line.length() > maxLineLength) {
                line = line.substring(0, maxLineLength) + "…";
            }
            send(source, line.isEmpty() ? " " : line);
        }
        if (lines.length > count) {
            send(source, "（内容过长，后续已省略）");
        }
    }

    /** 将模型说明按短行发送，避免聊天窗口只显示半句或出现空白行。 */
    private static void sendCompactLines(CommandSourceStack source, String text,
            int maxLines, int maxLineLength) {
        if (text == null || text.isBlank()) {
            send(source, "AI 未返回文字说明。");
            return;
        }
        int sent = 0;
        boolean truncated = false;
        for (String rawLine : text.replace('\r', '\n').split("\\n")) {
            String remaining = rawLine.trim();
            while (!remaining.isEmpty()) {
                if (sent >= maxLines) {
                    truncated = true;
                    break;
                }
                if (remaining.length() <= maxLineLength) {
                    send(source, remaining);
                    sent++;
                    break;
                }
                int cut = findWrapPoint(remaining, maxLineLength);
                send(source, remaining.substring(0, cut).trim() + "…");
                sent++;
                remaining = remaining.substring(cut).trim();
            }
            if (truncated) {
                break;
            }
        }
        if (truncated) {
            send(source, "（内容已截断）");
        }
        if (sent == 0) {
            send(source, "AI 未返回可显示内容。");
        }
    }

    private static int findWrapPoint(String text, int maxLength) {
        int limit = Math.min(maxLength, text.length());
        for (int index = limit; index > Math.max(20, limit / 2); index--) {
            char current = text.charAt(index - 1);
            if (Character.isWhitespace(current) || "，。；：、！？.!?;:".indexOf(current) >= 0) {
                return index;
            }
        }
        return limit;
    }

    private static String parseFailure(ParseResults<CommandSourceStack> parsed) {
        if (!parsed.getExceptions().isEmpty()) {
            CommandSyntaxException exception = parsed.getExceptions().values().iterator().next();
            return "命令语法错误：" + shortText(exception.getMessage(), 140);
        }
        return "命令语法未完整解析";
    }

    private static int fail(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(PREFIX + (message == null ? "操作失败" : message)));
        return 0;
    }

    private static void send(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(PREFIX + message), false);
    }

    private static void auditDenied(CommandSourceStack source, String action, String path, Throwable error) {
        try {
            CubePilotAgent.get().audit().record(source.getTextName(), action + ".denied",
                    "path=" + path + " error=" + safeMessage(error));
        } catch (IOException | IllegalArgumentException ignored) {
            // 配置不可用时没有可写的审计目标。
        }
    }

    private static String safeMessage(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (message == null || message.isBlank()) {
            message = cause.getClass().getSimpleName();
        }
        String safe = AuditLog.safeDiagnosticText(message).replaceAll("[\\r\\n]", " ");
        return safe.length() <= 500 ? safe : safe.substring(0, 500);
    }

    private static String shortText(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return "未知原因";
        }
        String compact = text.replaceAll("[\\r\\n]+", " ").trim();
        return compact.length() <= maxLength ? compact : compact.substring(0, Math.max(1, maxLength - 1)) + "…";
    }

    private record DiagnosticInput(String contents, DiagnosticReport report) {
    }

    /** 在服务端线程分段发送已确认的 answer 文字，不展示原始 JSON。 */
    private static final class StreamDisplay {
        private final CommandSourceStack source;
        private final StringBuilder pending = new StringBuilder();
        private int totalCharacters;
        private boolean truncated;
        private boolean sent;

        private StreamDisplay(CommandSourceStack source) {
            this.source = source;
        }

        private void accept(String text) {
            if (text == null || text.isEmpty()) return;
            String chunk = null;
            synchronized (this) {
                if (truncated) return;
                int remaining = 4000 - totalCharacters;
                if (remaining <= 0) {
                    pending.append("\n（流式回答过长，后续已省略）");
                    truncated = true;
                } else {
                    String accepted = text.length() <= remaining ? text : text.substring(0, remaining);
                    pending.append(accepted);
                    totalCharacters += accepted.length();
                    if (accepted.length() < text.length()) {
                        pending.append("\n（流式回答过长，后续已省略）");
                        truncated = true;
                    }
                }
                if (pending.length() >= 120 || containsSentenceEnd(pending)) {
                    chunk = takeChunk();
                    sent = true;
                }
            }
            if (chunk != null) {
                String output = chunk;
                source.getServer().execute(() -> sendCompactLines(source, output, 2, 180));
            }
        }

        private void flush() {
            String chunk;
            synchronized (this) {
                chunk = takeChunk();
                if (chunk != null && !chunk.isBlank()) sent = true;
            }
            if (chunk != null && !chunk.isBlank()) {
                sendCompactLines(source, chunk, 2, 180);
            }
        }

        private synchronized boolean hasSent() {
            return sent;
        }

        private String takeChunk() {
            if (pending.isEmpty()) return null;
            String chunk = pending.toString().trim();
            pending.setLength(0);
            return chunk.isEmpty() ? null : chunk;
        }

        private static boolean containsSentenceEnd(CharSequence text) {
            for (int index = 0; index < text.length(); index++) {
                if ("\n。！？.!?".indexOf(text.charAt(index)) >= 0) return true;
            }
            return false;
        }
    }

    private record PendingAllow(String actor, String command, long expiresAtMillis, PendingStage stage) {
    }

    private enum PendingStage {
        OFFER,
        CONFIRM
    }
}
