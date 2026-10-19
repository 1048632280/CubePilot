package com.cubepilot.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 对已读取的服务端文本做保守的本地规则分析；不会访问文件或执行任何命令。
 */
public final class DiagnosticAnalyzer {
    private static final int MAX_FINDINGS = 12;
    private static final int MAX_EVIDENCE_LENGTH = 180;
    private static final Pattern OOM = Pattern.compile(
            "OutOfMemoryError|Java heap space|GC overhead limit exceeded", Pattern.CASE_INSENSITIVE);
    private static final Pattern MOD_CONFLICT = Pattern.compile(
            "NoSuchMethodError|NoClassDefFoundError|ClassNotFoundException|ModLoadingException"
                    + "|Mixin[^\\r\\n]*(?:failed|error|apply)|incompatible|duplicate",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PORT = Pattern.compile(
            "Address already in use|Failed to bind|bind.*(?:port|address)", Pattern.CASE_INSENSITIVE);
    private static final Pattern WATCHDOG = Pattern.compile(
            "Can't keep up|server tick|watchdog|server thread", Pattern.CASE_INSENSITIVE);
    private static final Pattern ERROR = Pattern.compile(
            "\\b(?:ERROR|FATAL|Exception|Caused by|Stack trace)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMAND_BLOCK = Pattern.compile(
            "(?:enable-command-block|command[-_ ]?blocks?)\\s*[:=]\\s*false", Pattern.CASE_INSENSITIVE);
    private static final Pattern OFFLINE_MODE = Pattern.compile(
            "(?:online-mode|online_mode)\\s*[:=]\\s*false", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(?:api[._ -]?key|password|passwd|secret|token|authorization|private[._ -]?key)");

    private DiagnosticAnalyzer() {
    }

    public static DiagnosticReport analyze(String path, String content) {
        String safePath = path == null ? "" : path.trim();
        String text = content == null ? "" : content;
        String category = categoryForPath(safePath);
        List<DiagnosticReport.Finding> findings = new ArrayList<>();
        String[] lines = text.split("\\R", -1);
        for (int index = 0; index < lines.length && findings.size() < MAX_FINDINGS; index++) {
            String line = lines[index];
            addMatching(findings, "CRITICAL", "MEMORY_EXHAUSTION",
                    "检测到 JVM 内存耗尽迹象；应检查堆大小、实体/区块负载和相关 Mod。", OOM, line, index);
            addMatching(findings, "ERROR", "MOD_OR_PLUGIN_CONFLICT",
                    "检测到类、Mixin 或 Mod 加载冲突迹象；应核对 Mod 版本、加载器和依赖。",
                    MOD_CONFLICT, line, index);
            addMatching(findings, "ERROR", "PORT_BIND_FAILURE",
                    "检测到端口绑定失败；应检查端口占用、地址配置和其他服务进程。", PORT, line, index);
            addMatching(findings, "WARNING", "SERVER_TICK_LAG",
                    "检测到服务器线程卡顿或 Watchdog 迹象；应检查主线程负载和最近加载的 Mod。",
                    WATCHDOG, line, index);
            if (category.equals("log") || category.equals("crash-report")) {
                addMatching(findings, "ERROR", "ERROR_MARKER",
                        "检测到错误或异常堆栈标记；需要结合前后文确认根因。", ERROR, line, index);
            }
            if (category.equals("configuration")) {
                addMatching(findings, "WARNING", "COMMAND_BLOCK_DISABLED",
                        "配置禁用了命令方块；依赖命令方块的玩法或自动化可能无法工作。",
                        COMMAND_BLOCK, line, index);
                addMatching(findings, "WARNING", "OFFLINE_MODE",
                        "配置关闭了在线认证；这会改变身份验证和安全边界，请确认是否为有意设置。",
                        OFFLINE_MODE, line, index);
            }
        }

        String severity = highestSeverity(findings);
        String summary = findings.isEmpty()
                ? "未发现内置规则能够确认的异常。"
                : "发现 " + findings.size() + " 项需要人工核查的信号；CubePilot 不会自动修复。";
        return new DiagnosticReport(safePath, category, severity, summary, findings);
    }

    public static String categoryForPath(String path) {
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT).replace('\\', '/');
        if (lower.contains("crash-report") || lower.endsWith(".crash")) {
            return "crash-report";
        }
        if (lower.contains("log") || lower.endsWith(".log")) {
            return "log";
        }
        if (lower.startsWith("config/") || lower.contains("/config/") || lower.equals("server.properties")
                || lower.endsWith(".yaml") || lower.endsWith(".yml") || lower.endsWith(".toml")
                || lower.endsWith(".json") || lower.endsWith(".cfg") || lower.endsWith(".config")
                || lower.endsWith(".conf") || lower.endsWith(".ini") || lower.endsWith(".properties")) {
            return "configuration";
        }
        return "text";
    }

    private static void addMatching(List<DiagnosticReport.Finding> findings, String severity, String code,
            String message, Pattern pattern, String line, int lineNumber) {
        if (findings.size() >= MAX_FINDINGS || !pattern.matcher(line).find()) {
            return;
        }
        String evidence = evidence(line, lineNumber);
        findings.add(new DiagnosticReport.Finding(severity, code, message, evidence));
    }

    private static String evidence(String line, int lineNumber) {
        String safe = AuditLog.safeDiagnosticText(line).replaceAll("[\\r\\n]", " ").trim();
        if (SECRET.matcher(safe).find()) {
            safe = "（该行包含敏感字段，证据已隐藏）";
        } else if (safe.length() > MAX_EVIDENCE_LENGTH) {
            safe = safe.substring(0, MAX_EVIDENCE_LENGTH) + "…";
        }
        return "第 " + (lineNumber + 1) + " 行 " + safe;
    }

    private static String highestSeverity(List<DiagnosticReport.Finding> findings) {
        for (DiagnosticReport.Finding finding : findings) {
            if (finding.severity().equals("CRITICAL")) {
                return "CRITICAL";
            }
        }
        for (DiagnosticReport.Finding finding : findings) {
            if (finding.severity().equals("ERROR")) {
                return "ERROR";
            }
        }
        return findings.isEmpty() ? "INFO" : "WARNING";
    }
}
