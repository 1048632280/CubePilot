package com.cubepilot.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

public final class ReadOnlyFileAccess {
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".yaml", ".yml", ".toml", ".json", ".txt", ".cfg", ".config", ".conf", ".ini",
            ".properties", ".log", ".md", ".crash");
    private static final Set<String> DENIED_NAMES = Set.of(
            ".env", "credentials", "secrets", "server-icon.png", "usercache.json", "whitelist.json",
            "ops.json", "banned-ips.json", "banned-players.json");
    private static final Set<String> ALLOWED_ROOTS = Set.of(
            "config", "defaultconfigs", "logs", "crash-reports");
    private static final Set<String> SNAPSHOT_PROPERTY_KEYS = Set.of(
            "allow-flight", "allow-nether", "difficulty", "enable-command-block", "enforce-whitelist",
            "force-gamemode", "gamemode", "generate-structures", "hardcore", "max-players", "max-tick-time",
            "online-mode", "pvp", "spawn-protection", "simulation-distance", "view-distance", "white-list");
    private static final int MAX_INVENTORY_FILES = 80;
    private static final int MAX_INVENTORY_ENTRIES_VISITED = 512;
    private static final int MAX_INVENTORY_DEPTH = 5;
    private static final String CUBEPILOT_CONFIG_PATH = "config/cubepilot";

    private final Path serverRoot;
    private final int maxFileBytes;

    public ReadOnlyFileAccess(Path serverRoot, int maxFileBytes) {
        this.serverRoot = serverRoot.toAbsolutePath().normalize();
        this.maxFileBytes = Math.max(1, Math.min(maxFileBytes, 65536));
    }

    public List<String> list(String relativePath) throws IOException {
        Path directory = resolve(relativePath, true);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("指定路径不是目录");
        }
        List<String> entries = new ArrayList<>();
        boolean rootListing = relativePath == null || relativePath.isBlank() || relativePath.trim().equals(".");
        boolean modsListing = relativePath != null && relativePath.trim().replace('\\', '/').equalsIgnoreCase("mods");
        try (Stream<Path> children = Files.list(directory)) {
            for (Path child : children.sorted(Comparator.comparing(path -> path.getFileName().toString())).limit(100).toList()) {
                if (Files.isSymbolicLink(child)) {
                    continue;
                }
                String name = child.getFileName().toString();
                if (isDeniedName(name)) {
                    continue;
                }
                if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    if (!rootListing || ALLOWED_ROOTS.contains(name.toLowerCase(Locale.ROOT))
                            || name.equalsIgnoreCase("world") || name.equalsIgnoreCase("mods")) {
                        entries.add(name + "/");
                    }
                } else if (Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)
                        && (modsListing ? name.toLowerCase(Locale.ROOT).endsWith(".jar")
                        : (rootListing && name.equalsIgnoreCase("server.properties")) || isAllowedFile(name))) {
                    entries.add(name);
                }
            }
        }
        return List.copyOf(entries);
    }

    public String read(String relativePath) throws IOException {
        Path file = resolve(relativePath, false);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("指定路径不是普通文件");
        }
        long size = Files.size(file);
        if (size > maxFileBytes) {
            throw new IOException("文件超过读取上限（" + maxFileBytes + " 字节）");
        }
        byte[] bytes;
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(maxFileBytes + 1);
        }
        if (bytes.length > maxFileBytes) {
            throw new IOException("文件超过读取上限（" + maxFileBytes + " 字节）");
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        return AuditLog.redact(text);
    }

    public Map<String, String> readServerSettingsSnapshot() throws IOException {
        Path propertiesPath = serverRoot.resolve("server.properties");
        if (Files.notExists(propertiesPath, LinkOption.NOFOLLOW_LINKS)) {
            return Map.of();
        }
        if (Files.isSymbolicLink(propertiesPath)
                || !Files.isRegularFile(propertiesPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("server.properties 不是普通文件，已跳过快照读取");
        }

        Properties properties = new Properties();
        properties.load(new StringReader(read("server.properties")));
        Map<String, String> safeSettings = new TreeMap<>();
        for (String key : SNAPSHOT_PROPERTY_KEYS) {
            String value = properties.getProperty(key);
            if (value != null && !value.isBlank()) {
                String safeValue = AuditLog.safeDiagnosticText(value)
                        .replaceAll("\\p{Cntrl}", " ")
                        .trim();
                if (!safeValue.isEmpty()) {
                    safeSettings.put(key, safeValue.substring(0, Math.min(safeValue.length(), 160)));
                }
            }
        }
        return Collections.unmodifiableMap(safeSettings);
    }

    public List<String> configurationInventorySnapshot() {
        List<String> files = new ArrayList<>();
        int[] visitedEntries = {0};
        for (String root : List.of("config", "defaultconfigs", "world/serverconfig", "logs", "crash-reports")) {
            Path directory = serverRoot.resolve(root);
            if (isSafeDirectory(directory) && Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                collectInventory(directory, root, 0, visitedEntries, files);
            }
            if (files.size() >= MAX_INVENTORY_FILES || visitedEntries[0] >= MAX_INVENTORY_ENTRIES_VISITED) {
                break;
            }
        }
        files.sort(String::compareTo);
        return List.copyOf(files.subList(0, Math.min(files.size(), MAX_INVENTORY_FILES)));
    }

    /** 仅返回配置目录中的白名单文件，供一次性的模型配置分析使用。 */
    public List<String> configurationFilesSnapshot() {
        return configurationInventorySnapshot().stream()
                .filter(ReadOnlyFileAccess::isConfigurationPath)
                .toList();
    }

    private static void collectInventory(Path directory, String relativePath, int depth,
            int[] visitedEntries, List<String> files) {
        if (depth > MAX_INVENTORY_DEPTH || files.size() >= MAX_INVENTORY_FILES
                || visitedEntries[0] >= MAX_INVENTORY_ENTRIES_VISITED) {
            return;
        }
        if (isCubePilotConfigPath(relativePath)) {
            return;
        }
        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                if (++visitedEntries[0] > MAX_INVENTORY_ENTRIES_VISITED) {
                    return;
                }
                if (Files.isSymbolicLink(child)) {
                    continue;
                }
                String name = child.getFileName().toString();
                if (isDeniedName(name)) {
                    continue;
                }
                String childPath = relativePath + "/" + name;
                if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    collectInventory(child, childPath, depth + 1, visitedEntries, files);
                } else if (Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS) && isAllowedFile(name)) {
                    files.add(childPath);
                    if (files.size() >= MAX_INVENTORY_FILES) {
                        return;
                    }
                }
            }
        } catch (IOException ignored) {
            // 无法读取的目录不阻止其他目录进入快照。
        }
    }

    private Path resolve(String relativePath, boolean directory) throws IOException {
        String requested = relativePath == null || relativePath.isBlank() ? "." : relativePath.trim();
        if (requested.indexOf('\0') >= 0 || requested.contains(":") || requested.startsWith("/") || requested.startsWith("\\")) {
            throw new IOException("只允许读取服务端白名单目录中的相对路径");
        }
        String portable = requested.replace('\\', '/');
        Path relative;
        try {
            relative = Path.of(portable).normalize();
        } catch (RuntimeException exception) {
            throw new IOException("路径格式无效");
        }
        if (relative.isAbsolute() || relative.startsWith("..")) {
            throw new IOException("禁止越出服务端目录");
        }

        Path resolved = serverRoot.resolve(relative).normalize();
        if (!resolved.startsWith(serverRoot)) {
            throw new IOException("禁止越出服务端目录");
        }
        String normalized = serverRoot.relativize(resolved).toString().replace('\\', '/');
        if (normalized.isEmpty() || normalized.equals(".")) {
            if (!directory) {
                throw new IOException("请指定允许读取的文件路径");
            }
        } else {
            String first = normalized.split("/")[0].toLowerCase(Locale.ROOT);
            boolean serverProperties = normalized.equalsIgnoreCase("server.properties");
            boolean modsDirectory = first.equals("mods") && directory;
            if (!serverProperties && !isAllowedRoot(normalized, directory) && !modsDirectory) {
                throw new IOException("路径不在允许读取的目录中（config、defaultconfigs、world/serverconfig、logs、crash-reports、server.properties）");
            }
            if (first.equals("config") && isCubePilotConfigPath(normalized)) {
                throw new IOException("CubePilot 自身配置目录包含凭据，禁止通过聊天读取");
            }
            if (!directory && !serverProperties && !isAllowedFile(resolved.getFileName().toString())) {
                throw new IOException("此文件扩展名不在读取白名单中");
            }
        }

        Path current = serverRoot;
        for (Path component : serverRoot.relativize(resolved)) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("禁止通过符号链接访问文件");
            }
        }
        if (Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) {
            Path real = resolved.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!real.startsWith(serverRoot.toRealPath(LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("禁止越出服务端目录");
            }
        }
        return resolved;
    }

    private static boolean isAllowedFile(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (isDeniedName(lower)) {
            return false;
        }
        if (lower.endsWith(".secret") || lower.endsWith(".pem") || lower.endsWith(".key")
                || lower.endsWith(".p12") || lower.endsWith(".jks") || lower.endsWith(".dat")) {
            return false;
        }
        int dot = lower.lastIndexOf('.');
        return dot >= 0 && ALLOWED_EXTENSIONS.contains(lower.substring(dot));
    }

    private static boolean isCubePilotConfigPath(String relativePath) {
        String normalized = relativePath.toLowerCase(Locale.ROOT);
        return normalized.equals(CUBEPILOT_CONFIG_PATH)
                || normalized.startsWith(CUBEPILOT_CONFIG_PATH + "/");
    }

    private static boolean isConfigurationPath(String path) {
        String normalized = path.toLowerCase(Locale.ROOT).replace('\\', '/');
        return normalized.startsWith("config/") || normalized.startsWith("defaultconfigs/")
                || normalized.startsWith("world/serverconfig/");
    }

    private boolean isSafeDirectory(Path directory) {
        if (!directory.startsWith(serverRoot)) {
            return false;
        }
        Path current = serverRoot;
        for (Path component : serverRoot.relativize(directory)) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAllowedRoot(String normalized, boolean directory) {
        String lower = normalized.toLowerCase(Locale.ROOT).replace('\\', '/');
        if (ALLOWED_ROOTS.stream().anyMatch(root -> lower.equals(root) || lower.startsWith(root + "/"))) {
            return true;
        }
        return lower.equals("world/serverconfig")
                || lower.startsWith("world/serverconfig/");
    }

    private static boolean isDeniedName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (DENIED_NAMES.contains(lower)) {
            return true;
        }
        String compact = lower.replace(".", "").replace("_", "").replace("-", "");
        return lower.contains("secret") || lower.contains("credential") || lower.contains("password")
                || lower.contains("token") || lower.contains("private") || compact.contains("apikey");
    }
}
