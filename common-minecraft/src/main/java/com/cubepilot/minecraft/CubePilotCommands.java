package com.cubepilot.minecraft;

import com.cubepilot.core.AssistantReply;
import com.cubepilot.core.AuditLog;
import com.cubepilot.core.CommandPolicy;
import com.cubepilot.core.ConfigInspector;
import com.cubepilot.core.CubePilotAgent;
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
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

public final class CubePilotCommands {
    private static final List<String> ROOTS = List.of("cp", "cpilot", "cpa");
    private static final String PREFIX = "[CubePilot] ";
    private static final int MAX_CONFIG_CONTEXT_CHARS = 44000;

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
                .requires(source -> source.hasPermission(2))
                .executes(context -> help(context.getSource()))
                .then(Commands.literal("help").executes(context -> help(context.getSource())))
                .then(Commands.literal("status").executes(context -> status(context, loader, mods)))
                .then(Commands.literal("snapshot").executes(context -> showSnapshot(context, loader, mods)))
                .then(Commands.literal("mods").executes(context -> showMods(context, mods)))
                .then(Commands.literal("reload").executes(CubePilotCommands::reload))
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
                                        "请读取并解释这个服务端文件，指出异常或值得注意的设置。"))))
                .then(Commands.literal("diagnose")
                        .then(Commands.argument("path", StringArgumentType.word())
                                .then(Commands.argument("question", StringArgumentType.greedyString())
                                        .executes(context -> inspect(context, loader, mods,
                                                StringArgumentType.getString(context, "path"),
                                                StringArgumentType.getString(context, "question"))))))
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
        send(source, "/cp reload：重新读取 CubePilot 自身 cubepilot.properties，不调用 API");
        send(source, "/cp snapshot：查看运行时状态、安全设置和只读文件路径清单");
        send(source, "/cp files [目录]：列出只读白名单文件；/cp read <相对路径>：读取并脱敏");
        send(source, "/cp analyze <路径>：离线规则分析日志、崩溃报告或配置，不调用 API");
        send(source, "/cp config <自然语言问题>：自动识别 Mod，扫描只读配置并回答当前值与手工修改方法");
        send(source, "/cp inspect <路径>：让 AI 分析白名单文件；/cp diagnose <路径> <问题>：分析指定日志/配置");
        send(source, "命令建议只会显示为可点击的聊天文本：点击填入命令框后，必须由你手动按回车执行。");
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
            auditDenied(source, "config.reload", "cubepilot.properties", exception);
            return fail(source, "重新加载失败，旧配置仍在使用：" + safeMessage(exception));
        }
    }

    private static int showSnapshot(CommandContext<CommandSourceStack> context,
            String loader, List<ModInfo> mods) {
        CommandSourceStack source = context.getSource();
        try {
            CubePilotAgent agent = CubePilotAgent.get();
            ServerSnapshot snapshot = snapshot(source.getServer(), loader, mods, agent.files());
            agent.audit().record(source.getTextName(), "snapshot.capture",
                    "settings=" + snapshot.serverSettings().size()
                            + " files=" + snapshot.configurationFiles().size());
            sendBoundedLines(source, snapshot.toPromptText(), 120, 240);
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
            List<ModInfo> mods, String path, String question) {
        CommandSourceStack source = context.getSource();
        try {
            CubePilotAgent agent = CubePilotAgent.get();
            String contents = agent.files().read(path);
            DiagnosticReport report = DiagnosticAnalyzer.analyze(path, contents);
            agent.audit().record(source.getTextName(), "file.inspect",
                    "path=" + path + " category=" + report.category()
                            + " severity=" + report.severity()
                            + " findings=" + report.findings().size());
            return ask(context, loader, mods, question,
                    "本地只读规则诊断摘要：\n" + report.toText()
                            + "\n\n文件路径：" + path + "\n文件内容（凭据已脱敏）：\n" + contents);
        } catch (IOException | IllegalArgumentException exception) {
            auditDenied(source, "file.inspect", path, exception);
            return fail(source, exception.getMessage());
        }
    }

    private static int analyze(CommandContext<CommandSourceStack> context, String path) {
        CommandSourceStack source = context.getSource();
        try {
            CubePilotAgent agent = CubePilotAgent.get();
            String contents = agent.files().read(path);
            DiagnosticReport report = DiagnosticAnalyzer.analyze(path, contents);
            agent.audit().record(source.getTextName(), "file.analyze",
                    "path=" + path + " category=" + report.category()
                            + " severity=" + report.severity()
                            + " findings=" + report.findings().size());
            sendBoundedLines(source, report.toText(), 80, 240);
            return 1;
        } catch (IOException | IllegalArgumentException exception) {
            auditDenied(source, "file.analyze", path, exception);
            return fail(source, exception.getMessage());
        }
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
            ServerSnapshot snapshot = snapshot(source.getServer(), loader, mods, agent.files());
            agent.audit().record(source.getTextName(), "config.auto-discover",
                    "question_length=" + question.length());
            send(source, "正在异步扫描全部白名单 Mod 配置，并交给模型判断；不会读取凭据或修改文件。");
            CompletableFuture.supplyAsync(() -> {
                try {
                    return discoverAllConfigContext(agent.files(), mods, question);
                } catch (IOException exception) {
                    throw new CompletionException(exception);
                }
            }).thenCompose(attachedContext -> {
                agent.audit().record(source.getTextName(), "config.scan.complete",
                        "context_length=" + attachedContext.length());
                return agent.ask("请从广泛扫描的只读配置中自行识别 Mod 和相关设置，并回答：" + question,
                        attachedContext, snapshot, MAX_CONFIG_CONTEXT_CHARS);
            }).whenComplete((reply, error) -> source.getServer().execute(() -> {
                if (error != null) {
                    agent.audit().record(source.getTextName(), "config.request.error", safeMessage(error));
                    send(source, "配置分析失败：" + safeMessage(error));
                    return;
                }
                agent.audit().record(source.getTextName(), "config.request.complete",
                        "answer_length=" + reply.answer().length());
                renderReply(source, agent, reply);
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
        ServerSnapshot snapshot;
        try {
            snapshot = snapshot(source.getServer(), loader, mods, agent.files());
        } catch (IOException | IllegalArgumentException exception) {
            return fail(source, "无法生成服务器上下文：" + safeMessage(exception));
        }
        agent.audit().record(source.getTextName(), "request.start", "question_length=" + question.length());
        send(source, "正在联系配置的 OpenAI 兼容 API；等待期间服务器线程不会被阻塞。");
        agent.ask(question, attachedContext, snapshot).whenComplete((reply, error) -> source.getServer().execute(() -> {
            if (error != null) {
                agent.audit().record(source.getTextName(), "request.error", safeMessage(error));
                send(source, "请求失败：" + safeMessage(error));
                return;
            }
            agent.audit().record(source.getTextName(), "request.complete", "answer_length=" + reply.answer().length());
            renderReply(source, agent, reply);
        }));
        return 1;
    }

    private static void renderReply(CommandSourceStack source, CubePilotAgent agent, AssistantReply reply) {
        sendBoundedLines(source, reply.answer(), 20, 1000);
        int accepted = 0;
        for (String suggestion : reply.commandSuggestions()) {
            String command = CommandPolicy.validateSuggestion(suggestion, agent.allowedCommandRoots());
            if (command == null) {
                agent.audit().record(source.getTextName(), "command.rejected", "policy");
                continue;
            }
            String raw = command.substring(1);
            ParseResults<CommandSourceStack> parsed = source.getServer().getCommands()
                    .getDispatcher().parse(raw, source);
            if (parsed.getReader().canRead() || !parsed.getExceptions().isEmpty()) {
                agent.audit().record(source.getTextName(), "command.rejected", "parse");
                continue;
            }
            Component clickable = Component.literal("  [点击填入，不会自动执行] " + command)
                    .withStyle(ChatFormatting.GREEN)
                    .withStyle(style -> style.withClickEvent(
                            new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command)));
            source.sendSuccess(() -> clickable, false);
            agent.audit().record(source.getTextName(), "command.suggested", command);
            accepted++;
        }
        if (!reply.commandSuggestions().isEmpty() && accepted == 0) {
            send(source, "模型给出的命令未通过白名单或语法检查，因此没有显示。");
        } else if (accepted > 0) {
            send(source, "命令尚未执行：点击建议填入聊天框，并由你检查后手动按回车；执行时使用你的玩家权限。");
        }
    }

    private static ServerSnapshot runtimeSnapshot(MinecraftServer server, String loader, List<ModInfo> mods) {
        return new ServerSnapshot(Instant.now(), SharedConstants.getCurrentVersion().getName(), loader,
                server.overworld().getDifficulty().getDisplayName().getString(),
                server.getPlayerList().getPlayerCount(), server.getPlayerList().isUsingWhitelist(),
                Map.of(), List.of(), modTexts(mods));
    }

    private static ServerSnapshot snapshot(MinecraftServer server, String loader, List<ModInfo> mods,
            ReadOnlyFileAccess files) throws IOException {
        String difficulty = server.overworld().getDifficulty().getDisplayName().getString();
        return new ServerSnapshot(Instant.now(), SharedConstants.getCurrentVersion().getName(), loader, difficulty,
                server.getPlayerList().getPlayerCount(), server.getPlayerList().isUsingWhitelist(),
                files.readServerSettingsSnapshot(), files.configurationInventorySnapshot(), modTexts(mods));
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

    private static int fail(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(PREFIX + (message == null ? "操作失败" : message)));
        return 0;
    }

    private static void send(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(PREFIX + message), false);
    }

    private static void auditDenied(CommandSourceStack source, String action, String path, Exception error) {
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
}
