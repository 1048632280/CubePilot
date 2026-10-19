package com.cubepilot.minecraft;

import com.cubepilot.core.AssistantReply;
import com.cubepilot.core.AuditLog;
import com.cubepilot.core.CommandPolicy;
import com.cubepilot.core.CubePilotAgent;
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

    private CubePilotCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
            String loader, List<String> mods) {
        for (String root : ROOTS) {
            dispatcher.register(createRoot(root, loader, mods));
        }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> createRoot(String root,
            String loader, List<String> mods) {
        return Commands.literal(root)
                .requires(source -> source.hasPermission(2))
                .executes(context -> help(context.getSource()))
                .then(Commands.literal("help").executes(context -> help(context.getSource())))
                .then(Commands.literal("status").executes(context -> status(context, loader, mods)))
                .then(Commands.literal("snapshot").executes(context -> showSnapshot(context, loader, mods)))
                .then(Commands.literal("mods").executes(context -> showMods(context, mods)))
                .then(Commands.literal("files")
                        .executes(context -> listFiles(context, ""))
                        .then(Commands.argument("path", StringArgumentType.greedyString())
                                .executes(context -> listFiles(context,
                                        StringArgumentType.getString(context, "path")))))
                .then(Commands.literal("read")
                        .then(Commands.argument("path", StringArgumentType.greedyString())
                                .executes(context -> readFile(context,
                                        StringArgumentType.getString(context, "path")))))
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
        send(source, "/cp snapshot：查看运行时状态、安全设置和只读文件路径清单");
        send(source, "/cp files [目录]：列出只读白名单文件；/cp read <相对路径>：读取并脱敏");
        send(source, "/cp inspect <路径>：让 AI 分析白名单文件；/cp diagnose <路径> <问题>：分析指定日志/配置");
        send(source, "命令建议只会显示为可点击的聊天文本：点击填入命令框后，必须由你手动按回车执行。");
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> context,
            String loader, List<String> mods) {
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

    private static int showSnapshot(CommandContext<CommandSourceStack> context,
            String loader, List<String> mods) {
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

    private static int showMods(CommandContext<CommandSourceStack> context, List<String> mods) {
        send(context.getSource(), "已加载 Mod（" + mods.size() + " 个）：");
        List<String> visible = mods.stream().limit(200).toList();
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
            List<String> mods, String path, String question) {
        CommandSourceStack source = context.getSource();
        try {
            CubePilotAgent agent = CubePilotAgent.get();
            String contents = agent.files().read(path);
            agent.audit().record(source.getTextName(), "file.inspect", path);
            return ask(context, loader, mods, question,
                    "文件路径：" + path + "\n文件内容（凭据已脱敏）：\n" + contents);
        } catch (IOException | IllegalArgumentException exception) {
            auditDenied(source, "file.inspect", path, exception);
            return fail(source, exception.getMessage());
        }
    }

    private static int ask(CommandContext<CommandSourceStack> context, String loader,
            List<String> mods, String question, String attachedContext) {
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

    private static ServerSnapshot runtimeSnapshot(MinecraftServer server, String loader, List<String> mods) {
        return new ServerSnapshot(Instant.now(), SharedConstants.getCurrentVersion().getName(), loader,
                server.overworld().getDifficulty().getDisplayName().getString(),
                server.getPlayerList().getPlayerCount(), server.getPlayerList().isUsingWhitelist(),
                Map.of(), List.of(), mods);
    }

    private static ServerSnapshot snapshot(MinecraftServer server, String loader, List<String> mods,
            ReadOnlyFileAccess files) throws IOException {
        String difficulty = server.overworld().getDifficulty().getDisplayName().getString();
        return new ServerSnapshot(Instant.now(), SharedConstants.getCurrentVersion().getName(), loader, difficulty,
                server.getPlayerList().getPlayerCount(), server.getPlayerList().isUsingWhitelist(),
                files.readServerSettingsSnapshot(), files.configurationInventorySnapshot(), mods);
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
