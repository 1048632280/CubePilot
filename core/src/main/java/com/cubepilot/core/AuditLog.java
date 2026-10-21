package com.cubepilot.core;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.EnumSet;
import java.util.regex.Pattern;

public final class AuditLog {
    private static final long MAX_FILE_BYTES = 1024L * 1024L;
    private static final int ARCHIVE_COUNT = 3;
    private static final Object FILE_LOCK = new Object();
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?im)([\\\"']?(?:api[._ -]?key|password|passwd|secret|token|authorization|private[._ -]?key)[\\\"']?\\s*[:=]\\s*[\\\"']?)([^\\r\\n,;}\\\"']+)([\\\"']?)");
    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)Bearer\\s+[^\\s\\\"']+");
    private static final Pattern URL_CREDENTIALS = Pattern.compile("(?i)(https?://)[^/@\\s:]+:[^/@\\s]+@");

    private final Path path;
    private final Path tracePath;

    public AuditLog(Path directory) {
        this.path = directory.resolve("audit.jsonl");
        this.tracePath = directory.resolve("trace.jsonl");
    }

    public void record(String actor, String action, String detail) {
        JsonObject row = new JsonObject();
        row.addProperty("time", Instant.now().toString());
        row.addProperty("actor", safeField(actor, 128));
        row.addProperty("action", safeField(action, 128));
        row.addProperty("detail", safeField(detail, 2048));
        write(path, row);
    }

    /** 记录一次请求链的输入输出摘要；不记录模型隐藏思维链，只记录可审计文本。 */
    public void recordTrace(String requestId, String actor, String action, String input, String output) {
        JsonObject row = new JsonObject();
        row.addProperty("time", Instant.now().toString());
        row.addProperty("request_id", safeField(requestId, 64));
        row.addProperty("actor", safeField(actor, 128));
        row.addProperty("action", safeField(action, 128));
        row.addProperty("input", safeField(input, 12000));
        row.addProperty("output", safeField(output, 12000));
        write(tracePath, row);
    }

    private void write(Path target, JsonObject row) {
        synchronized (FILE_LOCK) {
            try {
                ensureLogFile(target);
                byte[] encoded = (row + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
                rotateIfNeeded(target, encoded.length);
                Files.write(target, encoded, StandardOpenOption.APPEND, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS);
            } catch (IOException exception) {
                System.err.println("[CubePilot] 审计日志写入失败：" + exception.getClass().getSimpleName());
            }
        }
    }

    private void ensureLogFile(Path target) throws IOException {
        Path directory = target.getParent();
        if (directory == null || Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("审计日志目录无效");
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("审计日志路径不是普通文件");
            }
            return;
        }
        try {
            Files.createFile(target);
            restrictFilePermissions(target);
        } catch (java.nio.file.FileAlreadyExistsException ignored) {
            if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("审计日志路径不是普通文件");
            }
        }
    }

    private void rotateIfNeeded(Path target, int incomingBytes) throws IOException {
        if (Files.size(target) + incomingBytes <= MAX_FILE_BYTES) {
            return;
        }
        for (int index = 1; index <= ARCHIVE_COUNT; index++) {
            Path candidate = archive(target, index);
            if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(candidate)
                            || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("审计日志归档路径不是普通文件");
            }
        }
        Path oldest = archive(target, ARCHIVE_COUNT);
        Files.deleteIfExists(oldest);
        for (int index = ARCHIVE_COUNT - 1; index >= 1; index--) {
            Path source = archive(target, index);
            if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                Files.move(source, archive(target, index + 1), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.move(target, archive(target, 1), StandardCopyOption.REPLACE_EXISTING);
        Files.createFile(target);
        restrictFilePermissions(target);
    }

    private Path archive(Path target, int index) {
        String name = target.getFileName().toString();
        String base = name.endsWith(".jsonl") ? name.substring(0, name.length() - 6) : name;
        return target.resolveSibling(base + "." + index + ".jsonl");
    }

    private static void restrictFilePermissions(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(file, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Windows 等平台由操作系统 ACL 管理文件权限。
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
