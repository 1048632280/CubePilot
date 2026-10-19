package com.cubepilot.core;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Properties;

public final class CubePilotConfig {
    private static final String DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions";
    private static final String DEFAULT_MODEL = "gpt-4o-mini";
    private static final List<String> DEFAULT_COMMAND_ROOTS = List.of(
            "give", "effect", "enchant", "tp", "teleport", "time", "weather",
            "difficulty", "gamerule", "experience", "xp");

    private final Path directory;
    private final Properties properties;

    private CubePilotConfig(Path directory, Properties properties) {
        this.directory = directory;
        this.properties = properties;
    }

    public static CubePilotConfig load(Path serverRoot) throws IOException {
        Path root = serverRoot.toAbsolutePath().normalize().toRealPath();
        Path directory = root.resolve("config").resolve("cubepilot").normalize();
        if (!directory.startsWith(root)) {
            throw new IOException("CubePilot 配置目录不能位于服务端根目录之外");
        }
        createDirectoriesWithoutLinks(root, directory);
        Path configPath = directory.resolve("cubepilot.properties");
        if (Files.isSymbolicLink(configPath)) {
            throw new IOException("CubePilot 配置文件不能是符号链接");
        }
        if (Files.notExists(configPath)) {
            try {
                writeDefaults(configPath);
            } catch (FileAlreadyExistsException ignored) {
                // 并发首次启动时，使用先创建配置文件的那个实例。
            }
        }
        if (!Files.isRegularFile(configPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("CubePilot 配置路径不是普通文件");
        }

        Properties properties = new Properties();
        try (Reader reader = new InputStreamReader(
                Files.newInputStream(configPath, LinkOption.NOFOLLOW_LINKS), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        validateEndpoint(properties.getProperty("api.endpoint", DEFAULT_ENDPOINT).trim());
        return new CubePilotConfig(directory, properties);
    }

    private static void createDirectoriesWithoutLinks(Path root, Path directory) throws IOException {
        Path current = root;
        for (Path component : root.relativize(directory)) {
            current = current.resolve(component);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("CubePilot 配置目录路径不能包含符号链接或普通文件");
                }
            } else {
                createPrivateDirectory(current);
            }
        }
    }

    private static void createPrivateDirectory(Path path) throws IOException {
        try {
            Files.createDirectory(path, PosixFilePermissions.asFileAttribute(EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE)));
        } catch (UnsupportedOperationException ignored) {
            Files.createDirectory(path);
        }
    }

    private static void writeDefaults(Path path) throws IOException {
        String defaults = "# CubePilot 自有设置；本模组不会修改其他服务端配置文件。\n"
                + "# 建议通过 CUBEPILOT_API_KEY 环境变量提供密钥。\n"
                + "api.endpoint=" + DEFAULT_ENDPOINT + "\n"
                + "api.model=" + DEFAULT_MODEL + "\n"
                + "api.key=\n"
                + "api.timeout-seconds=45\n"
                + "# 模型命令建议允许的根命令；命令只会填入聊天框，不会由模组执行。\n"
                + "command.allow-roots=" + String.join(",", DEFAULT_COMMAND_ROOTS) + "\n"
                + "files.max-bytes=16384\n";
        try {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
        } catch (UnsupportedOperationException ignored) {
            Files.createFile(path);
        }
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            writer.write(defaults);
        }
    }

    private static void validateEndpoint(String endpoint) {
        URI uri = URI.create(endpoint);
        String scheme = uri.getScheme();
        String host = uri.getHost();
        String normalizedHost = host == null ? "" : host.replace("[", "").replace("]", "");
        boolean loopback = "localhost".equalsIgnoreCase(normalizedHost)
                || "127.0.0.1".equals(normalizedHost)
                || "::1".equals(normalizedHost);
        if (host == null || uri.getUserInfo() != null || !("https".equalsIgnoreCase(scheme)
                || (loopback && "http".equalsIgnoreCase(scheme)))) {
            throw new IllegalArgumentException("API 地址必须使用 HTTPS；仅允许本机回环地址使用 HTTP");
        }
    }

    public Path directory() {
        return directory;
    }

    public String endpoint() {
        return properties.getProperty("api.endpoint", DEFAULT_ENDPOINT).trim();
    }

    public String model() {
        return properties.getProperty("api.model", DEFAULT_MODEL).trim();
    }

    public String apiKey() {
        String environmentKey = System.getenv("CUBEPILOT_API_KEY");
        if (environmentKey != null && !environmentKey.isBlank()) {
            return environmentKey.trim();
        }
        return properties.getProperty("api.key", "").trim();
    }

    public int timeoutSeconds() {
        try {
            int value = Integer.parseInt(properties.getProperty("api.timeout-seconds", "45").trim());
            return Math.max(5, Math.min(value, 120));
        } catch (NumberFormatException ignored) {
            return 45;
        }
    }

    public int maxFileBytes() {
        try {
            int value = Integer.parseInt(properties.getProperty("files.max-bytes", "16384").trim());
            return Math.max(1024, Math.min(value, 65536));
        } catch (NumberFormatException ignored) {
            return 16384;
        }
    }

    public List<String> allowedCommandRoots() {
        String raw = properties.getProperty("command.allow-roots", String.join(",", DEFAULT_COMMAND_ROOTS));
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> value.matches("[a-z0-9_]+"))
                .distinct()
                .toList();
    }
}
