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

    public AuditLog(Path directory) {
        this.path = directory.resolve("audit.jsonl");
    }

    public void record(String actor, String action, String detail) {
        JsonObject row = new JsonObject();
        row.addProperty("time", Instant.now().toString());
        row.addProperty("actor", safeField(actor, 128));
        row.addProperty("action", safeField(action, 128));
        row.addProperty("detail", safeField(detail, 2048));
        synchronized (FILE_LOCK) {
            try {
                ensureLogFile();
                byte[] encoded = (row + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
                rotateIfNeeded(encoded.length);
                Files.write(path, encoded, StandardOpenOption.APPEND, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS);
            } catch (IOException exception) {
                System.err.println("[CubePilot] 审计日志写入失败：" + exception.getClass().getSimpleName());
            }
        }
    }

    private void ensureLogFile() throws IOException {
        Path directory = path.getParent();
        if (directory == null || Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("审计日志目录无效");
        }
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("审计日志路径不是普通文件");
            }
            return;
        }
        try {
            Files.createFile(path);
            restrictFilePermissions(path);
        } catch (java.nio.file.FileAlreadyExistsException ignored) {
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("审计日志路径不是普通文件");
            }
        }
    }

    private void rotateIfNeeded(int incomingBytes) throws IOException {
        if (Files.size(path) + incomingBytes <= MAX_FILE_BYTES) {
            return;
        }
        for (int index = 1; index <= ARCHIVE_COUNT; index++) {
            Path candidate = archive(index);
            if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(candidate)
                            || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("审计日志归档路径不是普通文件");
            }
        }
        Path oldest = archive(ARCHIVE_COUNT);
        Files.deleteIfExists(oldest);
        for (int index = ARCHIVE_COUNT - 1; index >= 1; index--) {
            Path source = archive(index);
            if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                Files.move(source, archive(index + 1), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.move(path, archive(1), StandardCopyOption.REPLACE_EXISTING);
        Files.createFile(path);
        restrictFilePermissions(path);
    }

    private Path archive(int index) {
        return path.resolveSibling("audit." + index + ".jsonl");
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
