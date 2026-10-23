package com.cubepilot.minecraft;

import com.cubepilot.core.AssistantReply;
import com.cubepilot.core.AgentIntent;
import com.cubepilot.core.AuditLog;
import com.cubepilot.core.CommandPolicy;
import com.cubepilot.core.CommandVerification;
import com.cubepilot.core.ConfigInspector;
import com.cubepilot.core.DiagnosticAnalyzer;
import com.cubepilot.core.DiagnosticReport;
import com.cubepilot.core.CubePilotAgent;
import com.cubepilot.core.CubePilotTasks;
import com.cubepilot.core.ModInfo;
import com.cubepilot.core.PromptTemplates;
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
import java.util.concurrent.TimeUnit;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class CubePilotCommands {
    private static final List<String> ROOTS = List.of("cp", "cpa", "cpilot", "cubepilot");
    private static final String PREFIX = "[CubePilot] ";
    private static final int MAX_CONFIG_CONTEXT_CHARS = 44000;
    private static final long ALLOW_CONFIRM_TTL_MILLIS = 120_000L;
    private static final long CLARIFICATION_TTL_MILLIS = 120_000L;
    /** 服务端聊天窗口按像素显示；保守限制可避免客户端再次把中文拆成单字行。 */
    private static final int MAX_DISPLAY_WIDTH = 48;
    private static final Map<String, PendingAllow> PENDING_ALLOWS = new ConcurrentHashMap<>();
    private static final Map<UUID, PendingClarification> PENDING_CLARIFICATIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> AI_REQUEST_COOLDOWNS = new ConcurrentHashMap<>();
    private static final long AI_REQUEST_COOLDOWN_MILLIS = TimeUnit.SECONDS.toMillis(8);
    private CubePilotCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
            String loader, List<ModInfo> mods) {
        for (String root : ROOTS) {
            dispatcher.register(createRoot(root, loader, mods));
        }
    }

    /** 匹配并处理已配置的聊天 AI 前缀时返回 true。 */
    public static boolean onChatMessage(MinecraftServer server, ServerPlayer player, String message,
            String loader, List<ModInfo> mods) {
        if (message == null || message.isBlank()) return false;
        try {
            CubePilotAgent agent = CubePilotAgent.get();
            if (!agent.config().chatTriggerEnabled()) return false;
            if (!player.createCommandSourceStack().hasPermission(agent.config().chatPermissionLevel())) return false;
            String normalized = message.stripLeading();
            String prefix = agent.config().chatPrefixes().stream()
                    .filter(candidate -> normalized.regionMatches(true, 0, candidate, 0, candidate.length()))
                    .max(java.util.Comparator.comparingInt(String::length)).orElse(null);
            if (prefix == null) return false;
            String question = normalized.substring(prefix.length()).trim();
            if (question.isBlank()) {
                server.execute(() -> send(player.createCommandSourceStack(), "请在前缀后输入问题。"));
                return true;
            }
            if (question.length() > 4000) {
                server.execute(() -> send(player.createCommandSourceStack(), "问题过长，请控制在 4000 个字符以内。"));
                return true;
            }
            server.execute(() -> {
                route(player.createCommandSourceStack(), loader, mods, question, "");
            });
            return true;
        } catch (IOException | IllegalArgumentException exception) {
            return false;
        }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> createRoot(String root,
            String loader, List<ModInfo> mods) {
        return Commands.literal(root)
                .requires(source -> source.getEntity() instanceof ServerPlayer && source.hasPermission(2))
                .executes(context -> help(context.getSource()))
                .then(Commands.literal("help").executes(context -> help(context.getSource())))
                .then(Commands.literal("status").executes(context -> status(context, loader, mods)))
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
                .then(Commands.literal("analyze")
                        .then(Commands.argument("question", StringArgumentType.greedyString())
                                .executes(context -> analyzeRequest(context.getSource(), loader, mods,
                                        StringArgumentType.getString(context, "question")))))
                .then(Commands.literal("config")
                        .then(Commands.argument("request", StringArgumentType.greedyString())
                                .executes(context -> configRequest(context.getSource(), loader, mods,
                                        StringArgumentType.getString(context, "request")))))
                .then(Commands.literal("ask")
                        .then(Commands.argument("question", StringArgumentType.greedyString())
                                .executes(context -> askRequest(context.getSource(), loader, mods,
                                        StringArgumentType.getString(context, "question")))))
                .then(Commands.argument("question", StringArgumentType.greedyString())
                        .executes(context -> route(context.getSource(), loader, mods,
                                StringArgumentType.getString(context, "question"), "")));
    }

    private static int help(CommandSourceStack source) {
        sendCompactLines(source, "CubePilot（权限：OP 2；别名：cp、cpa、cpilot、cubepilot）"
                + "\nhelp：显示帮助"
                + "\nstatus：查看服务器状态"
                + "\nreload：重载 CubePilot 配置"
                + "\nconfig：只读检索 Mod 配置"
                + "\nanalyze：只读分析日志与崩溃"
                + "\nask：AI 问答；问题入口：自动识别类型"
                + "\n命令按白名单自动执行，其余提供手动建议。", 9, 180);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> context,
            String loader, List<ModInfo> mods) {
        try {
            ServerSnapshot snapshot = runtimeSnapshot(context.getSource().getServer(), loader, mods);
            sendCompactLines(context.getSource(), snapshot.toStatusText(), 12, 180);
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
            sendCompactLines(source, "已重新加载配置。\nAPI：" + agent.config().endpoint()
                    + "\n模型：" + agent.config().model(), 3, 180);
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
        sendCompactLines(source, "即将写入自动白名单（本次不执行）：\n" + pending.command(), 3, 180);
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

    /** 所有会调用模型的玩家入口共用冷却；内部路由转发不得再次调用。 */
    private static boolean acquireAiRequest(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return true;
        long now = System.currentTimeMillis();
        AI_REQUEST_COOLDOWNS.entrySet().removeIf(
                entry -> now - entry.getValue() >= TimeUnit.MINUTES.toMillis(10));
        Long previous = AI_REQUEST_COOLDOWNS.putIfAbsent(player.getUUID(), now);
        if (previous == null) return true;
        if (now - previous >= AI_REQUEST_COOLDOWN_MILLIS
                && AI_REQUEST_COOLDOWNS.replace(player.getUUID(), previous, now)) {
            return true;
        }
        send(source, "请求太频繁，请稍后再试。");
        return false;
    }

    private static int analyzeRequest(CommandSourceStack source, String loader,
            List<ModInfo> mods, String question) {
        return analyzeRequest(source, loader, mods, question, true);
    }

    private static int analyzeRequest(CommandSourceStack source, String loader,
            List<ModInfo> mods, String question, boolean announce) {
        String request = question == null ? "" : question.trim();
        if (request.isBlank() || request.length() > 4000) {
            return fail(source, "诊断问题不能为空且长度不能超过 4000 个字符。");
        }
        if (announce && !acquireAiRequest(source)) return 0;
        return analyzeRequest(source, loader, mods, request, announce, UUID.randomUUID().toString());
    }

    private static int analyzeRequest(CommandSourceStack source, String loader,
            List<ModInfo> mods, String question, boolean announce, String requestId) {
        String request = question == null ? "" : question.trim();
        if (request.isBlank() || request.length() > 4000) {
            return fail(source, "诊断问题不能为空且长度不能超过 4000 个字符。");
        }
        CubePilotAgent agent;
        try {
            agent = CubePilotAgent.get();
        } catch (IOException | IllegalArgumentException exception) {
            auditDenied(source, "file.analyze", request, exception);
            return fail(source, exception.getMessage());
        }
        if (announce) send(source, "正在处理，请稍候…");
        agent.audit().recordTrace(requestId, actorId(source), "analyze.input", request, "开始只读扫描");
        CompletableFuture<ServerSnapshot> snapshot = snapshotAsync(
                source.getServer(), loader, mods, agent.files());
        CubePilotTasks.supplyAsync(() -> {
            try {
                return discoverAnalysisContext(agent.files(), request, agent.config().maxScanFiles(),
                        agent.config().maxScanBytes());
            } catch (IOException | IllegalArgumentException exception) {
                throw new CompletionException(exception);
            }
        }).thenCombine(snapshot, AnalysisContext::new)
                .thenCompose(context -> agent.ask(actorId(source), request, context.text(), context.snapshot(), 44000,
                        PromptTemplates.ANALYZE, requestId))
                .whenComplete((reply, error) -> source.getServer().execute(() -> {
            if (error != null) {
                auditDenied(source, "file.analyze", request, error);
                send(source, "诊断失败：" + shortText(safeMessage(error), 160));
                return;
            }
            agent.audit().record(source.getTextName(), "file.analyze", "question_length=" + request.length());
            agent.audit().recordTrace(requestId, actorId(source), "analyze.output", request, replySummary(reply));
            agent.remember(actorId(source), request, reply, "已回复");
            renderReply(source, agent, reply, false, false, request, requestId);
        }));
        return 1;
    }

    private record AnalysisContext(String text, ServerSnapshot snapshot) {
    }

    private static String discoverAnalysisContext(ReadOnlyFileAccess files, String question,
            int maxFiles, int maxBytes) throws IOException {
        StringBuilder context = new StringBuilder("服务端问题诊断上下文（只读）：\n用户问题：")
                .append(question).append("\n可供模型自行判断的文件：\n");
        ReadOnlyFileAccess.FileInventory inventory = files.configurationInventory();
        List<String> paths = inventory.paths();
        if (inventory.truncated()) {
            context.append("清单状态：已截断，仅代表扫描到的部分文件。\n");
        }
        ReadOnlyFileAccess.ScanSession scan = files.newScan(maxFiles, maxBytes);
        boolean limited = false;
        for (String path : paths) {
            if (!(path.startsWith("logs/") || path.startsWith("crash-reports/")
                    || path.startsWith("config/") || path.startsWith("defaultconfigs/")
                    || path.startsWith("world/serverconfig/"))) {
                continue;
            }
            context.append("\n文件：").append(path).append('\n');
            try {
                String contents = scan.read(path);
                DiagnosticReport localReport = DiagnosticAnalyzer.analyze(path, contents);
                if (!localReport.findings().isEmpty()) {
                    context.append("本地规则诊断：\n");
                    for (DiagnosticReport.Finding finding : localReport.findings()) {
                        context.append("- [").append(finding.severity()).append("] ")
                                .append(finding.code()).append("：").append(finding.message());
                        if (!finding.evidence().isBlank()) {
                            context.append("；").append(finding.evidence());
                        }
                        context.append('\n');
                    }
                }
                context.append("原文节选：\n");
                context.append(contents, 0, Math.min(contents.length(), 3500)).append('\n');
                if (contents.length() > 3500) context.append("（该文件节选已截断）\n");
            } catch (ReadOnlyFileAccess.ScanLimitException exception) {
                limited = true;
                break;
            } catch (IOException exception) {
                context.append("读取失败：").append(safeMessage(exception)).append('\n');
            }
            if (context.length() >= 42000) {
                limited = true;
                break;
            }
        }
        if (limited) {
            context.append("\n扫描已达到本次请求上限，后续文件未读取；不得据此声称已检查全部文件。\n");
        }
        context.append("扫描统计：尝试文件数=").append(scan.filesVisited())
                .append("，读取字节=").append(scan.bytesRead()).append('\n');
        context.append("文件清单及原文均有边界限制，不是服务器全部文件。\n");
        context.append("\n仅使用找到的文件证据，不要猜测路径或结论。\n");
        return context.toString();
    }

    private static int configRequest(CommandSourceStack source, String loader,
            List<ModInfo> mods, String request) {
        String question = request == null ? "" : request.trim();
        if (question.isBlank() || question.length() > 4000) {
            return fail(source, "配置问题不能为空且长度不能超过 4000 个字符。");
        }
        if (!acquireAiRequest(source)) return 0;
        return askDiscoveredModConfig(source, loader, mods, question, true);
    }

    private static int route(CommandSourceStack source, String loader,
            List<ModInfo> mods, String question, String attachedContext) {
        if (question == null || question.isBlank() || question.length() > 4000) {
            return fail(source, "问题不能为空且长度不能超过 4000 个字符。");
        }
        if (!acquireAiRequest(source)) return 0;
        try {
            CubePilotAgent agent = CubePilotAgent.get();
            send(source, "正在处理，请稍候…");
            ServerPlayer player = source.getEntity() instanceof ServerPlayer serverPlayer ? serverPlayer : null;
            PendingClarification pending = player == null ? null
                    : PENDING_CLARIFICATIONS.remove(player.getUUID());
            if (pending != null) {
                if (pending.expiresAtMillis() > System.currentTimeMillis()) {
                    String combined = pending.question() + "\n玩家补充：" + question;
                    return askDirect(source, loader, mods, combined, attachedContext,
                            PromptTemplates.commandFor(SharedConstants.getCurrentVersion().getName()), true,
                            false);
                }
            }
            String requestId = UUID.randomUUID().toString();
            agent.audit().recordTrace(requestId, actorId(source), "request.input", question,
                    "conversation_turns=" + agent.conversationSize(actorId(source)));
            agent.classify(actorId(source), question, requestId).whenComplete((intent, error) -> source.getServer().execute(() -> {
                if (error != null || intent == null || intent == AgentIntent.UNKNOWN) {
                    String reason = error == null ? "分类器返回无效意图" : safeMessage(error);
                    agent.audit().record(source.getTextName(), "request.route.error", reason);
                    agent.audit().recordTrace(requestId, actorId(source), "classifier.error", question,
                            reason);
                    send(source, "请求分类失败，请重试或使用明确的子命令。");
                    return;
                }
                AgentIntent selected = intent == null ? AgentIntent.ASK : intent;
                agent.audit().recordTrace(requestId, actorId(source), "classifier.result", question,
                        selected.name());
                switch (selected) {
                    case CONFIG -> askDiscoveredModConfig(source, loader, mods, question, false, requestId);
                    case ANALYZE -> analyzeRequest(source, loader, mods, question, false, requestId);
                    case ASK -> askDirect(source, loader, mods, question, attachedContext, PromptTemplates.ASK, false,
                            false, requestId);
                    case COMMAND -> askDirect(source, loader, mods, question, attachedContext,
                            PromptTemplates.commandFor(SharedConstants.getCurrentVersion().getName()),
                            true, false, requestId);
                    case UNKNOWN -> send(source, "请求分类失败，请重试或使用明确的子命令。");
                }
            }));
            return 1;
        } catch (IOException | IllegalArgumentException exception) {
            return fail(source, "无法识别请求类型：" + safeMessage(exception));
        }
    }

    private static int askDiscoveredModConfig(CommandSourceStack source, String loader,
            List<ModInfo> mods, String question, boolean announce) {
        return askDiscoveredModConfig(source, loader, mods, question, announce, UUID.randomUUID().toString());
    }

    private static int askDiscoveredModConfig(CommandSourceStack source, String loader,
            List<ModInfo> mods, String question, boolean announce, String requestId) {
        try {
            CubePilotAgent agent = CubePilotAgent.get();
            agent.audit().record(source.getTextName(), "config.auto-discover",
                    "question_length=" + question.length());
            agent.audit().recordTrace(requestId, actorId(source), "config.input", question, "开始广泛只读扫描");
            if (announce) send(source, "正在处理，请稍候…");
            snapshotAsync(source.getServer(), loader, mods, agent.files()).thenCompose(snapshot ->
                CubePilotTasks.supplyAsync(() -> {
                try {
                    return discoverAllConfigContext(agent.files(), mods, question,
                            agent.config().maxScanFiles(), agent.config().maxScanBytes());
                } catch (IOException exception) {
                    throw new CompletionException(exception);
                }
                }).thenCompose(attachedContext -> {
                agent.audit().record(source.getTextName(), "config.scan.complete",
                        "context_length=" + attachedContext.length());
                return agent.ask(actorId(source), question, attachedContext, snapshot, MAX_CONFIG_CONTEXT_CHARS,
                        PromptTemplates.CONFIG, requestId);
                })).whenComplete((reply, error) -> source.getServer().execute(() -> {
                if (error != null) {
                    agent.audit().record(source.getTextName(), "config.request.error", safeMessage(error));
                    send(source, "配置分析失败：" + shortText(safeMessage(error), 160));
                    return;
                }
                agent.audit().record(source.getTextName(), "config.request.complete",
                        "answer_length=" + reply.answer().length());
                agent.audit().recordTrace(requestId, actorId(source), "config.output", question, replySummary(reply));
                agent.remember(actorId(source), question, reply, "已回复");
                renderReply(source, agent, reply, false, false, question, requestId);
                }));
            return 1;
        } catch (IOException | IllegalArgumentException exception) {
            auditDenied(source, "config.auto-discover", question, exception);
            return fail(source, "自动检索配置失败：" + safeMessage(exception));
        }
    }

    private static String discoverAllConfigContext(ReadOnlyFileAccess files, List<ModInfo> mods,
            String question, int maxFiles, int maxBytes) throws IOException {
        ReadOnlyFileAccess.FileInventory inventory = files.configurationInventory();
        List<String> paths = inventory.paths().stream()
                .filter(path -> path.startsWith("config/") || path.startsWith("defaultconfigs/")
                        || path.startsWith("world/serverconfig/"))
                .toList();
        StringBuilder context = new StringBuilder("Mod 配置广泛扫描上下文（只读、未做关键词筛选）：\n")
                .append("用户问题：").append(question)
                .append("\n已加载 Mod（ID / 显示名 / 版本）：\n");
        mods.stream().limit(160).forEach(mod -> context.append("- ").append(mod.displayText()).append('\n'));
        context.append("\n扫描目录：config/、defaultconfigs/、world/serverconfig/")
                .append("\n发现配置文件：").append(paths.size()).append(" 个\n")
                .append("文件清单状态：")
                .append(inventory.truncated() ? "已截断，不代表全部文件" : "未触发清单上限")
                .append('\n');
        if (paths.isEmpty()) {
            context.append("未发现可读取的配置文件。请明确说明没有证据，不能编造路径或键名。\n");
            return context.toString();
        }

        context.append("全部文件路径：\n");
        paths.forEach(path -> context.append("- ").append(path).append('\n'));
        int footerReserve = 500;
        int available = Math.max(0, MAX_CONFIG_CONTEXT_CHARS - context.length() - footerReserve);
        int perFileBudget = Math.max(180, Math.min(6000, available / Math.max(1, Math.min(paths.size(), maxFiles))));
        context.append("\n各文件配置摘要（每个文件公平分配上下文，不按问题相关度排序）：\n");
        ReadOnlyFileAccess.ScanSession scan = files.newScan(maxFiles, maxBytes);
        boolean limited = false;
        for (String path : paths) {
            if (context.length() >= MAX_CONFIG_CONTEXT_CHARS - footerReserve) {
                limited = true;
                break;
            }
            try {
                appendConfigSummary(context, scan, path, perFileBudget);
            } catch (ReadOnlyFileAccess.ScanLimitException exception) {
                limited = true;
                break;
            }
        }
        if (limited) {
            context.append("\n扫描已达到本次请求上限，后续文件未读取；不得据此声称已检查全部文件。\n");
        }
        context.append("扫描统计：尝试文件数=").append(scan.filesVisited())
                .append("，读取字节=").append(scan.bytesRead()).append('\n');
        context.append("文件清单及摘要均有边界限制，不是服务器全部文件或完整原文。\n");
        context.append("请自行判断用户指的是哪个 Mod 和设置，只使用这里提供的证据。\n");
        return context.toString();
    }

    private static void appendConfigSummary(StringBuilder context, ReadOnlyFileAccess.ScanSession scan,
            String path, int budget) throws ReadOnlyFileAccess.ScanLimitException {
        int start = context.length();
        context.append("\n文件：").append(path).append('\n');
        try {
            String contents = scan.read(path);
            ConfigInspector.ConfigDocument document = ConfigInspector.inspect(path, contents);
            context.append("格式：").append(document.format()).append('\n');
            for (String warning : document.warnings()) {
                if (context.length() - start >= budget) {
                    context.append("…\n");
                    break;
                }
                context.append("解析警告：").append(warning).append('\n');
            }
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
        } catch (ReadOnlyFileAccess.ScanLimitException exception) {
            context.setLength(start);
            throw exception;
        } catch (IOException exception) {
            context.append("读取失败：").append(safeMessage(exception)).append('\n');
            if (context.length() - start > budget) {
                context.setLength(start + budget);
            }
        }
    }

    private static int askDirect(CommandSourceStack source, String loader,
            List<ModInfo> mods, String question, String attachedContext) {
        return askDirect(source, loader, mods, question, attachedContext, PromptTemplates.ASK, true, true);
    }

    private static int askRequest(CommandSourceStack source, String loader,
            List<ModInfo> mods, String question) {
        if (question == null || question.isBlank() || question.length() > 4000) {
            return fail(source, "问题不能为空且长度不能超过 4000 个字符。");
        }
        if (!acquireAiRequest(source)) return 0;
        return askDirect(source, loader, mods, question, "", PromptTemplates.ASK, false);
    }

    private static int askDirect(CommandSourceStack source, String loader,
            List<ModInfo> mods, String question, String attachedContext, String taskPrompt) {
        return askDirect(source, loader, mods, question, attachedContext, taskPrompt, true, true);
    }

    private static int askDirect(CommandSourceStack source, String loader,
            List<ModInfo> mods, String question, String attachedContext,
            String taskPrompt, boolean allowCommands) {
        return askDirect(source, loader, mods, question, attachedContext, taskPrompt, allowCommands, true);
    }

    private static int askDirect(CommandSourceStack source, String loader,
            List<ModInfo> mods, String question, String attachedContext,
            String taskPrompt, boolean allowCommands, boolean announce) {
        return askDirect(source, loader, mods, question, attachedContext, taskPrompt, allowCommands,
                announce, UUID.randomUUID().toString());
    }

    private static int askDirect(CommandSourceStack source, String loader,
            List<ModInfo> mods, String question, String attachedContext,
            String taskPrompt, boolean allowCommands, boolean announce, String requestId) {
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
        agent.audit().recordTrace(requestId, actorId(source), "request.input",
                "task_prompt=" + taskPrompt + "\nquestion=" + question,
                attachedContext == null ? "" : attachedContext);
        if (announce) send(source, "正在处理，请稍候…");
        snapshotAsync(source.getServer(), loader, mods, agent.files())
                .thenCompose(snapshot -> {
                    CompletableFuture<AssistantReply> generated = agent.ask(actorId(source), question,
                            attachedContext, snapshot, 16000, taskPrompt, requestId);
                    if (!allowCommands) return generated;
                    return generated.thenCompose(reply -> {
                        if (reply.commandSuggestions().isEmpty() || reply.clarificationRequired()) {
                            return CompletableFuture.completedFuture(reply);
                        }
                        String evidence = "服务器快照：\n" + snapshot.toPromptText()
                                + "\n请求附加上下文：\n" + (attachedContext == null ? "" : attachedContext);
                        return agent.verifyCommands(actorId(source), question,
                                reply.commandSuggestions(), evidence,
                                SharedConstants.getCurrentVersion().getName(), requestId).thenApply(verifications -> {
                            List<String> verifiedCommands = new ArrayList<>();
                            List<String> missing = new ArrayList<>();
                            for (CommandVerification verification : verifications) {
                                String candidate = verification.command();
                                agent.audit().recordTrace(requestId, actorId(source), "command.verification",
                                        candidate, verificationSummary(verification));
                                if (verification.complete()) {
                                    verifiedCommands.add(candidate);
                                } else {
                                    missing.addAll(verification.missingRequirements());
                                    if (verification.missingRequirements().isEmpty()
                                            && !verification.reason().isBlank()) {
                                        missing.add(verification.reason());
                                    }
                                }
                            }
                            if (!verifiedCommands.isEmpty()) {
                                return new AssistantReply(reply.answer(), verifiedCommands,
                                        reply.clarificationRequired());
                            }
                            String details = missing.isEmpty() ? "" : "（" + shortText(String.join("、", missing), 120) + "）";
                            return new AssistantReply("未能确认候选命令完整满足请求，未执行。" + details, List.of());
                        });
                    });
                })
                .whenComplete((reply, error) -> source.getServer().execute(() -> {
            if (error != null) {
                agent.audit().record(source.getTextName(), "request.error", safeMessage(error));
                agent.audit().recordTrace(requestId, actorId(source), "model.error", question,
                        safeMessage(error));
                send(source, "请求失败：" + shortText(safeMessage(error), 180));
                return;
            }
            agent.audit().record(source.getTextName(), "request.complete", "answer_length=" + reply.answer().length());
            agent.audit().recordTrace(requestId, actorId(source), "model.output", question,
                    replySummary(reply));
            agent.remember(actorId(source), question, reply, "已收到回复");
            renderReply(source, agent, reply, allowCommands, false, question, requestId);
        }));
        return 1;
    }

    private static void renderReply(CommandSourceStack source, CubePilotAgent agent,
            AssistantReply reply, boolean allowCommands) {
        renderReply(source, agent, reply, allowCommands, false);
    }

    private static void renderReply(CommandSourceStack source, CubePilotAgent agent,
            AssistantReply reply, boolean allowCommands, boolean answerAlreadySent) {
        renderReply(source, agent, reply, allowCommands, answerAlreadySent, "");
    }

    private static void renderReply(CommandSourceStack source, CubePilotAgent agent,
            AssistantReply reply, boolean allowCommands, boolean answerAlreadySent, String requestQuestion) {
        renderReply(source, agent, reply, allowCommands, answerAlreadySent, requestQuestion, "");
    }

    private static void renderReply(CommandSourceStack source, CubePilotAgent agent,
            AssistantReply reply, boolean allowCommands, boolean answerAlreadySent, String requestQuestion,
            String requestId) {
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
        if (reply.clarificationRequired()) {
            pruneExpiredClarifications();
            PENDING_CLARIFICATIONS.put(player.getUUID(), new PendingClarification(
                    requestQuestion, System.currentTimeMillis() + CLARIFICATION_TTL_MILLIS));
            sendCompactLines(source, shortText(reply.answer(), 160) + "\n2 分钟内回复即可。", 2, 180);
            agent.updateConversationOutcome(actorId(source), "等待补充信息");
            return;
        }
        List<String> onlinePlayers = source.getServer().getPlayerList().getPlayers().stream()
                .map(onlinePlayer -> onlinePlayer.getGameProfile().getName())
                .toList();
        List<String> manualCommands = new ArrayList<>();
        List<String> offerCommands = new ArrayList<>();
        List<ValidatedCommand> automaticCommands = new ArrayList<>();
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
            ValidatedCommand validated = validateForRuntime(source, command);
            if (!validated.valid()) {
                String reason = validated.reason();
                agent.audit().record(source.getTextName(), "command.rejected", reason);
                if (rejectionReason == null) {
                    rejectionReason = reason;
                }
                continue;
            }

            if (decision.automatic()) {
                automaticCommands.add(validated);
                continue;
            }

            if (decision.manualAllowed()) {
                manualCommands.add(command);
                agent.audit().record(source.getTextName(), "command.suggested",
                        "command=" + command + " reason=" + decision.reason());
                agent.audit().recordTrace(requestId, actorId(source), "command.suggested", requestQuestion,
                        command);
            }
            if (decision.offerAddToAutoAllow()) {
                offerCommands.add(command);
            } else if (!decision.manualAllowed() && rejectionReason == null) {
                rejectionReason = decision.reason();
            }
        }

        List<String> executedCommands = new ArrayList<>();
        List<Component> executedDescriptions = new ArrayList<>();
        String executionError = null;
        for (ValidatedCommand automatic : automaticCommands) {
            String command = automatic.command();
            try {
                int result = source.getServer().getCommands().getDispatcher().execute(automatic.parsed());
                executedCommands.add(command);
                executedDescriptions.add(executedDescription(automatic));
                agent.audit().record(source.getTextName(), "command.auto-executed",
                        "command=" + command + " result=" + result + " reason=命中自动执行白名单");
                agent.audit().recordTrace(requestId, actorId(source), "command.executed", command,
                        "result=" + result);
            } catch (CommandSyntaxException | RuntimeException exception) {
                executionError = shortText(safeMessage(exception), 160);
                agent.audit().record(source.getTextName(), "command.auto-error",
                        "command=" + command + " error=" + executionError);
                agent.audit().recordTrace(requestId, actorId(source), "command.execution-error", command,
                        executionError);
                break;
            }
        }
        if (!executedCommands.isEmpty()) {
            sendExecuted(source, executedDescriptions);
            agent.updateConversationOutcome(actorId(source), "已成功执行："
                    + executedCommands.stream().map(CubePilotCommands::readableCommand)
                            .collect(java.util.stream.Collectors.joining("、")));
        }
        if (executionError != null) {
            send(source, "执行失败：" + executionError);
            agent.updateConversationOutcome(actorId(source), "执行失败：" + executionError);
        }

        if (!manualCommands.isEmpty() || !offerCommands.isEmpty()) {
            if (reply.answer() != null && !reply.answer().isBlank()) {
                sendCompactLines(source, reply.answer(), 8, 180);
            }
            for (String command : manualCommands) {
                Component clickable = Component.literal("手动执行：[点击输入]")
                        .withStyle(ChatFormatting.YELLOW)
                        .withStyle(style -> style
                                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                        Component.literal(command))));
                source.sendSuccess(() -> clickable, false);
            }
            agent.updateConversationOutcome(actorId(source), "等待玩家手动执行");
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
        } else if (executedCommands.isEmpty() && executionError == null
                && !reply.commandSuggestions().isEmpty()) {
            send(source, "命令未执行：" + shortText(
                    rejectionReason == null ? "未通过安全或语法检查" : rejectionReason, 120));
            agent.audit().recordTrace(requestId, actorId(source), "command.rejected", requestQuestion,
                    rejectionReason == null ? "未通过安全或语法检查" : rejectionReason);
            agent.updateConversationOutcome(actorId(source), "命令被安全策略或语法检查拒绝");
        } else if (executedCommands.isEmpty() && executionError == null) {
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
                ReadOnlyFileAccess.FileInventory inventory = files.configurationInventory();
                return new ServerSnapshot(Instant.now(), SharedConstants.getCurrentVersion().getName(), loader,
                        difficulty, playerCount, whitelist, files.readServerSettingsSnapshot(),
                        inventory.paths(), inventory.truncated(), modTexts);
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
        sendCompactLines(source, text, maxLines, maxLineLength, true);
    }

    private static void sendCompactLines(CommandSourceStack source, String text,
            int maxLines, int maxLineLength, boolean includePrefix) {
        if (text == null || text.isBlank()) {
            sendReplyLine(source, "AI 未返回文字说明。", includePrefix);
            return;
        }
        int sent = 0;
        boolean truncated = false;
        boolean prefixPending = includePrefix;
        for (String rawLine : text.replace("\r\n", "\n").replace('\r', '\n').split("\\n")) {
            String remaining = rawLine.trim();
            if (remaining.isEmpty()) continue;
            while (!remaining.isEmpty()) {
                if (sent >= maxLines) {
                    truncated = true;
                    break;
                }
                if (displayWidth(remaining) <= MAX_DISPLAY_WIDTH && remaining.length() <= maxLineLength) {
                    sendReplyLine(source, remaining, prefixPending);
                    prefixPending = false;
                    sent++;
                    break;
                }
                int availableWidth = Math.max(20, MAX_DISPLAY_WIDTH - (prefixPending ? 14 : 0));
                int cut = findWrapPoint(remaining, maxLineLength, availableWidth);
                sendReplyLine(source, remaining.substring(0, cut).trim() + "…", prefixPending);
                prefixPending = false;
                sent++;
                remaining = remaining.substring(cut).trim();
            }
            if (truncated) {
                break;
            }
        }
        if (truncated) {
            sendReplyLine(source, "（内容已截断）", prefixPending);
        }
        if (sent == 0) {
            sendReplyLine(source, "AI 未返回可显示内容。", prefixPending);
        }
    }

    private static int findWrapPoint(String text, int maxLength, int maxWidth) {
        int limit = Math.min(maxLength, text.length());
        int width = 0;
        int preferred = -1;
        for (int index = 0; index < text.length(); index++) {
            width += charWidth(text.charAt(index));
            if (Character.isWhitespace(text.charAt(index))
                    || "，。；：、！？.!?;:）】》。".indexOf(text.charAt(index)) >= 0) {
                preferred = index + 1;
            }
            if (index + 1 >= limit || width >= maxWidth) {
                break;
            }
        }
        if (preferred > 8) {
            return preferred;
        }
        return Math.max(1, Math.min(limit, firstSafeBreak(text, limit, maxWidth)));
    }

    private static int firstSafeBreak(String text, int limit, int maxWidth) {
        int width = 0;
        for (int index = 0; index < text.length() && index < limit; index++) {
            width += charWidth(text.charAt(index));
            if (width > maxWidth) return Math.max(1, index);
        }
        return Math.min(limit, text.length());
    }

    private static int displayWidth(String text) {
        int width = 0;
        for (int index = 0; index < text.length(); index++) width += charWidth(text.charAt(index));
        return width;
    }

    private static int charWidth(char value) {
        return value >= 0x2E80 ? 2 : 1;
    }

    private static String parseFailure(ParseResults<CommandSourceStack> parsed) {
        if (!parsed.getExceptions().isEmpty()) {
            CommandSyntaxException exception = parsed.getExceptions().values().iterator().next();
            return "命令语法错误：" + shortText(exception.getMessage(), 140);
        }
        return "命令语法未完整解析";
    }

    /** 将成功执行结果压缩为玩家可读的对象描述，避免把整段 NBT 再刷到聊天栏。 */
    private static String readableCommand(String command) {
        String clean = command == null ? "" : command.stripLeading().replaceFirst("^/", "");
        String[] parts = clean.split("\\s+");
        if (parts.length == 0 || parts[0].isBlank()) return "操作";
        if (parts[0].equalsIgnoreCase("give") && parts.length >= 3) {
            String item = parts[2].split("[\\[\\{]", 2)[0];
            return "物品 " + item;
        }
        if (parts[0].equalsIgnoreCase("summon") && parts.length >= 2) {
            return "生物 " + parts[1];
        }
        return parts[0] + " 操作";
    }

    private static Component executedDescription(ValidatedCommand validated) {
        Component item = createItemDisplay(validated);
        if (item != null) return item;
        return Component.literal(readableCommand(validated.command())).withStyle(ChatFormatting.AQUA)
                .withStyle(style -> style.withHoverEvent(new HoverEvent(
                        HoverEvent.Action.SHOW_TEXT, Component.literal("由 CubePilot 自动执行"))));
    }

    /** 从 Brigadier 已解析参数构造物品悬停；版本 API 不兼容时安全退回普通说明。 */
    private static Component createItemDisplay(ValidatedCommand validated) {
        String clean = validated.command().stripLeading().replaceFirst("^/", "");
        if (!clean.regionMatches(true, 0, "give ", 0, 5)) return null;
        try {
            Object context = validated.parsed().getContext();
            Object itemInput = null;
            int count = 1;
            while (context != null) {
                Object arguments = context.getClass().getMethod("getArguments").invoke(context);
                if (arguments instanceof Map<?, ?> map) {
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        Object parsedArgument = entry.getValue();
                        Object value = parsedArgument.getClass().getMethod("getResult").invoke(parsedArgument);
                        if ("count".equals(entry.getKey()) && value instanceof Number number) {
                            count = number.intValue();
                        } else if (value != null && value.getClass().getSimpleName().equals("ItemInput")) {
                            itemInput = value;
                        }
                    }
                }
                Object child = context.getClass().getMethod("getChild").invoke(context);
                context = child == context ? null : child;
            }
            if (itemInput == null) return null;
            Object itemStack = itemInput.getClass().getMethod("createItemStack", int.class, boolean.class)
                    .invoke(itemInput, count, false);
            Object hoverName = itemStack.getClass().getMethod("getHoverName").invoke(itemStack);
            if (!(hoverName instanceof Component itemName)) return null;
            Class<?> infoClass = Class.forName("net.minecraft.network.chat.HoverEvent$ItemStackInfo");
            Object itemInfo = null;
            for (java.lang.reflect.Constructor<?> constructor : infoClass.getConstructors()) {
                if (constructor.getParameterCount() == 1
                        && constructor.getParameterTypes()[0].isInstance(itemStack)) {
                    itemInfo = constructor.newInstance(itemStack);
                    break;
                }
            }
            if (itemInfo == null) return null;
            for (java.lang.reflect.Constructor<?> constructor : HoverEvent.class.getConstructors()) {
                if (constructor.getParameterCount() == 2
                        && constructor.getParameterTypes()[0] == HoverEvent.Action.class) {
                    HoverEvent hover = (HoverEvent) constructor.newInstance(HoverEvent.Action.SHOW_ITEM, itemInfo);
                    return itemName.copy().withStyle(ChatFormatting.AQUA)
                            .withStyle(style -> style.withHoverEvent(hover));
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // 版本内部结构不同不会影响命令执行，只退回不含命令/NBT 的文本悬停。
        }
        return null;
    }

    private static void sendExecuted(CommandSourceStack source, List<Component> descriptions) {
        Component result = Component.literal("已执行：").withStyle(ChatFormatting.WHITE);
        for (int index = 0; index < descriptions.size(); index++) {
            if (index > 0) result = result.copy().append(Component.literal("、").withStyle(ChatFormatting.WHITE));
            result = result.copy().append(descriptions.get(index));
        }
        Component completed = Component.literal(PREFIX).withStyle(ChatFormatting.DARK_AQUA).append(result);
        source.sendSuccess(() -> completed, false);
    }

    private static String replySummary(AssistantReply reply) {
        if (reply == null) return "";
        return "answer=" + shortText(reply.answer(), 4000)
                + ";commands=" + String.join(" | ", reply.commandSuggestions())
                + ";clarification=" + reply.clarificationRequired();
    }

    private static String verificationSummary(CommandVerification verification) {
        return "complete=" + verification.complete()
                + ";missing=" + String.join(" | ", verification.missingRequirements())
                + ";reason=" + verification.reason();
    }

    /** 使用当前服务器版本注册的原生命令参数解析器校验完整命令，覆盖该版本的物品 NBT/组件解析。 */
    private static ValidatedCommand validateForRuntime(CommandSourceStack source, String command) {
        ParseResults<CommandSourceStack> parsed = source.getServer().getCommands()
                .getDispatcher().parse(command.substring(1), source);
        if (parsed.getReader().canRead() || !parsed.getExceptions().isEmpty()) {
            return new ValidatedCommand(command, null, false,
                    "Minecraft " + SharedConstants.getCurrentVersion().getName() + " 命令参数校验失败："
                            + parseFailure(parsed));
        }
        return new ValidatedCommand(command, parsed, true, "");
    }

    private record ValidatedCommand(String command, ParseResults<CommandSourceStack> parsed,
            boolean valid, String reason) {
    }

    private static int fail(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(PREFIX).withStyle(ChatFormatting.DARK_AQUA)
                .append(Component.literal(message == null ? "操作失败" : message).withStyle(ChatFormatting.RED)));
        return 0;
    }

    private static void send(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(PREFIX).withStyle(ChatFormatting.DARK_AQUA)
                .append(Component.literal(message).withStyle(ChatFormatting.WHITE)), false);
    }

    private static void sendReplyLine(CommandSourceStack source, String message, boolean includePrefix) {
        Component line = includePrefix
                ? Component.literal(PREFIX).withStyle(ChatFormatting.DARK_AQUA)
                        .append(Component.literal(message).withStyle(ChatFormatting.WHITE))
                : Component.literal(message).withStyle(ChatFormatting.WHITE);
        source.sendSuccess(() -> line, false);
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
        if (error == null) return "未知原因";
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

    private record PendingAllow(String actor, String command, long expiresAtMillis, PendingStage stage) {
    }

    private static void pruneExpiredClarifications() {
        long now = System.currentTimeMillis();
        PENDING_CLARIFICATIONS.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
    }

    private record PendingClarification(String question, long expiresAtMillis) {
    }

    private enum PendingStage {
        OFFER,
        CONFIRM
    }
}
