package com.cubepilot.core;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.regex.Pattern;

public final class AuditLog {
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?im)([\\\"']?(?:api[._ -]?key|password|passwd|secret|token|authorization|private[._ -]?key)[\\\"']?\\s*[:=]\\s*[\\\"']?)([^\\r\\n,;}\\\"']+)([\\\"']?)");
    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)Bearer\\s+[^\\s\\\"']+");
    private static final Pattern URL_CREDENTIALS = Pattern.compile("(?i)(https?://)[^/@\\s:]+:[^/@\\s]+@");

    private final Path path;

    public AuditLog(Path directory) {
        this.path = directory.resolve("audit.jsonl");
    }

    public synchronized void record(String actor, String action, String detail) {
        JsonObject row = new JsonObject();
        row.addProperty("time", Instant.now().toString());
        row.addProperty("actor", safeField(actor, 128));
        row.addProperty("action", safeField(action, 128));
        row.addProperty("detail", safeField(detail, 2048));
        try {
            Files.writeString(path, row + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
        } catch (IOException exception) {
            System.err.println("[CubePilot] 审计日志写入失败：" + exception.getClass().getSimpleName());
        }
    }

    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String safe = SECRET_ASSIGNMENT.matcher(text).replaceAll("$1[已隐藏]$3");
        safe = BEARER_TOKEN.matcher(safe).replaceAll("Bearer [已隐藏]");
        safe = URL_CREDENTIALS.matcher(safe).replaceAll("$1[已隐藏]@");
        return safe;
    }

    public static String safeDiagnosticText(String text) {
        String safe = redact(text).replace("\u0000", "");
        return safe.length() <= 16000 ? safe : safe.substring(0, 16000);
    }

    private static String safeField(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String safe = safeDiagnosticText(value).replaceAll("[\\r\\n]", " ");
        return safe.length() <= maxLength ? safe : safe.substring(0, maxLength);
    }
}
