package com.cubepilot.core;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class CubePilotConfig {
    public static final String FILE_NAME = "cubepilot.toml";

    private static final String DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions";
    private static final String DEFAULT_MODEL = "gpt-4o-mini";
    private static final int DEFAULT_TIMEOUT_SECONDS = 45;
    private static final int DEFAULT_MAX_FILE_BYTES = 16384;

    private final Path directory;
    private final String endpoint;
    private final String model;
    private final String apiKey;
    private final int timeoutSeconds;
    private final int maxFileBytes;
    private final boolean offerAddToAutoAllow;
    private final CommandRules autoAllow;
    private final CommandRules autoDeny;
    private final Map<SafetyRule, Integer> safetyModes;

    private CubePilotConfig(Path directory, Map<String, Object> values) {
        this.directory = directory;
        this.endpoint = stringValue(values, "api.endpoint", DEFAULT_ENDPOINT).trim();
        this.model = stringValue(values, "api.model", DEFAULT_MODEL).trim();
        this.apiKey = stringValue(values, "api.key", "").trim();
        this.timeoutSeconds = boundedInt(values, "api.timeout-seconds", DEFAULT_TIMEOUT_SECONDS, 5, 120);
        this.maxFileBytes = boundedInt(values, "files.max-bytes", DEFAULT_MAX_FILE_BYTES, 1024, 65536);
        this.offerAddToAutoAllow = booleanValue(values, "command.offer-add-to-auto-allow", true);
        this.autoAllow = commandRules(values, "command.auto-allow");
        this.autoDeny = commandRules(values, "command.auto-deny");

        Map<SafetyRule, Integer> modes = new EnumMap<>(SafetyRule.class);
        for (SafetyRule rule : SafetyRule.values()) {
            int mode = intValue(values, "command.safety." + rule.configKey(), 3);
            if (mode < 0 || mode > 3) {
                throw new IllegalArgumentException("安全规则 " + rule.configKey() + " 只能填写 0、1、2 或 3");
            }
            modes.put(rule, mode);
        }
        this.safetyModes = Collections.unmodifiableMap(modes);

        if (model.isBlank()) {
            throw new IllegalArgumentException("api.model 不能为空");
        }
        validateEndpoint(endpoint);
    }

    /** 只确保 CubePilot 自身配置目录和默认文件存在，不读取配置，也不连接 API。 */
    public static Path ensureExists(Path serverRoot) throws IOException {
        Path root = serverRoot.toAbsolutePath().normalize().toRealPath();
        Path directory = root.resolve("config").resolve("cubepilot").normalize();
        if (!directory.startsWith(root)) {
            throw new IOException("CubePilot 配置目录不能位于服务端根目录之外");
        }
        createDirectoriesWithoutLinks(root, directory);
        Path configPath = directory.resolve(FILE_NAME);
        if (Files.isSymbolicLink(configPath)) {
            throw new IOException("CubePilot 配置文件不能是符号链接");
        }
        if (Files.notExists(configPath)) {
            try {
                Files.writeString(configPath, defaultContents(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                restrictFilePermissions(configPath);
            } catch (FileAlreadyExistsException ignored) {
                // 并发首次启动时，使用先创建配置文件的那个实例。
            }
        }
        if (!Files.isRegularFile(configPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("CubePilot 配置路径不是普通文件");
        }
        return configPath;
    }

    public static CubePilotConfig load(Path serverRoot) throws IOException {
        Path configPath = ensureExists(serverRoot);
        return parse(configPath.getParent(), Files.readString(configPath, StandardCharsets.UTF_8));
    }

    /** 将完整命令加入精确自动执行白名单；候选内容先解析通过，再原子替换原文件。 */
    public static boolean addExactAutoAllowedCommand(Path serverRoot, String rawCommand) throws IOException {
        Path configPath = ensureExists(serverRoot);
        String command = CommandPolicy.normalizeForRule(rawCommand);
        if (command == null) {
            throw new IllegalArgumentException("无法把无效命令加入自动执行白名单");
        }
        String original = Files.readString(configPath, StandardCharsets.UTF_8);
        CubePilotConfig current = parse(configPath.getParent(), original);
        if (current.autoAllow.exact().contains(command)) {
            return false;
        }
        String candidate = insertExactRule(original, command);
        parse(configPath.getParent(), candidate);
        atomicWrite(configPath, candidate);
        return true;
    }

    static String readRaw(Path serverRoot) throws IOException {
        return Files.readString(ensureExists(serverRoot), StandardCharsets.UTF_8);
    }

    static void restore(Path serverRoot, String contents) throws IOException {
        atomicWrite(ensureExists(serverRoot), contents);
    }

    private static CubePilotConfig parse(Path directory, String contents) {
        return new CubePilotConfig(directory, parseToml(contents));
    }

    private static Map<String, Object> parseToml(String contents) {
        Map<String, Object> result = new LinkedHashMap<>();
        String section = "";
        String[] lines = contents.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            String clean = stripComment(lines[index]).trim();
            if (clean.isEmpty()) {
                continue;
            }
            if (clean.startsWith("[") && clean.endsWith("]") && !clean.startsWith("[[")) {
                section = clean.substring(1, clean.length() - 1).trim();
                if (!section.matches("[A-Za-z0-9_.-]+")) {
                    throw new IllegalArgumentException("TOML 第 " + (index + 1) + " 行的分区名称无效");
                }
                continue;
            }
            int equals = indexOfUnquoted(clean, '=');
            if (equals <= 0) {
                throw new IllegalArgumentException("TOML 第 " + (index + 1) + " 行缺少有效的键值分隔符");
            }
            String key = clean.substring(0, equals).trim();
            if (!key.matches("[A-Za-z0-9_-]+")) {
                throw new IllegalArgumentException("TOML 第 " + (index + 1) + " 行的配置项名称无效");
            }
            String rawValue = clean.substring(equals + 1).trim();
            if (rawValue.startsWith("[") && !isClosedArray(rawValue)) {
                StringBuilder array = new StringBuilder(rawValue);
                while (++index < lines.length) {
                    array.append('\n').append(stripComment(lines[index]));
                    if (isClosedArray(array.toString())) {
                        break;
                    }
                }
                rawValue = array.toString().trim();
            }
            String fullKey = section.isEmpty() ? key : section + "." + key;
            if (result.putIfAbsent(fullKey, parseTomlValue(rawValue, index + 1)) != null) {
                throw new IllegalArgumentException("TOML 配置项重复：" + fullKey);
            }
        }
        return result;
    }

    private static Object parseTomlValue(String raw, int lineNumber) {
        String value = raw.trim();
        if (value.startsWith("[")) {
            if (!isClosedArray(value)) {
                throw new IllegalArgumentException("TOML 第 " + lineNumber + " 行的数组没有闭合");
            }
            String body = value.substring(1, value.lastIndexOf(']'));
            List<String> entries = new ArrayList<>();
            for (String entry : splitArray(body)) {
                if (!entry.isBlank()) {
                    entries.add(parseString(entry.trim(), lineNumber));
                }
            }
            return List.copyOf(entries);
        }
        if (value.startsWith("\"") || value.startsWith("'")) {
            return parseString(value, lineNumber);
        }
        if ("true".equals(value) || "false".equals(value)) {
            return Boolean.parseBoolean(value);
        }
        try {
            return Integer.parseInt(value.replace("_", ""));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("TOML 第 " + lineNumber + " 行的值类型不受支持");
        }
    }

    private static String parseString(String raw, int lineNumber) {
        String value = raw.trim();
        if (value.length() < 2) {
            throw new IllegalArgumentException("TOML 第 " + lineNumber + " 行的字符串没有闭合");
        }
        char quote = value.charAt(0);
        if ((quote != '"' && quote != '\'') || value.charAt(value.length() - 1) != quote) {
            throw new IllegalArgumentException("TOML 第 " + lineNumber + " 行的字符串没有闭合");
        }
        String body = value.substring(1, value.length() - 1);
        if (quote == '\'') {
            return body;
        }
        StringBuilder decoded = new StringBuilder();
        for (int index = 0; index < body.length(); index++) {
            char current = body.charAt(index);
            if (current != '\\') {
                decoded.append(current);
                continue;
            }
            if (++index >= body.length()) {
                throw new IllegalArgumentException("TOML 第 " + lineNumber + " 行包含无效转义");
            }
            char escaped = body.charAt(index);
            decoded.append(switch (escaped) {
                case '"' -> '"';
                case '\\' -> '\\';
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case 'b' -> '\b';
                case 'f' -> '\f';
                default -> throw new IllegalArgumentException("TOML 第 " + lineNumber + " 行包含无效转义");
            });
        }
        return decoded.toString();
    }

    private static List<String> splitArray(String body) {
        List<String> entries = new ArrayList<>();
        StringBuilder entry = new StringBuilder();
        boolean quoted = false;
        char quote = 0;
        boolean escaped = false;
        for (int index = 0; index < body.length(); index++) {
            char current = body.charAt(index);
            if (quoted) {
                entry.append(current);
                if (escaped) {
                    escaped = false;
                } else if (current == '\\' && quote == '"') {
                    escaped = true;
                } else if (current == quote) {
                    quoted = false;
                }
            } else if (current == '"' || current == '\'') {
                quoted = true;
                quote = current;
                entry.append(current);
            } else if (current == ',') {
                entries.add(entry.toString());
                entry.setLength(0);
            } else {
                entry.append(current);
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("TOML 数组包含未闭合的字符串");
        }
        entries.add(entry.toString());
        return entries;
    }

    private static String stripComment(String line) {
        boolean quoted = false;
        char quote = 0;
        boolean escaped = false;
        for (int index = 0; index < line.length(); index++) {
            char current = line.charAt(index);
            if (quoted) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\' && quote == '"') {
                    escaped = true;
                } else if (current == quote) {
                    quoted = false;
                }
            } else if (current == '"' || current == '\'') {
                quoted = true;
                quote = current;
            } else if (current == '#') {
                return line.substring(0, index);
            }
        }
        return line;
    }

    private static int indexOfUnquoted(String value, char target) {
        boolean quoted = false;
        char quote = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (!quoted && (current == '"' || current == '\'')) {
                quoted = true;
                quote = current;
            } else if (quoted && current == quote && (index == 0 || value.charAt(index - 1) != '\\')) {
                quoted = false;
            } else if (!quoted && current == target) {
                return index;
            }
        }
        return -1;
    }

    private static boolean isClosedArray(String value) {
        boolean quoted = false;
        char quote = 0;
        boolean escaped = false;
        int depth = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (quoted) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\' && quote == '"') {
                    escaped = true;
                } else if (current == quote) {
                    quoted = false;
                }
            } else if (current == '"' || current == '\'') {
                quoted = true;
                quote = current;
            } else if (current == '[') {
                depth++;
            } else if (current == ']' && --depth == 0) {
                return value.substring(index + 1).trim().isEmpty();
            }
        }
        return false;
    }

    private static CommandRules commandRules(Map<String, Object> values, String prefix) {
        return new CommandRules(normalizedRules(listValue(values, prefix + ".roots"), RuleKind.ROOT),
                normalizedRules(listValue(values, prefix + ".subcommands"), RuleKind.SUBCOMMAND),
                normalizedRules(listValue(values, prefix + ".exact"), RuleKind.EXACT));
    }

    private static Set<String> normalizedRules(List<String> values, RuleKind kind) {
        Set<String> rules = new LinkedHashSet<>();
        for (String value : values) {
            String normalized = CommandPolicy.normalizeForRule(value);
            if (normalized == null) {
                throw new IllegalArgumentException("命令规则无效：" + value);
            }
            int words = CommandPolicy.topLevelWords(normalized).size();
            if ((kind == RuleKind.ROOT && words != 1) || (kind == RuleKind.SUBCOMMAND && words != 2)) {
                throw new IllegalArgumentException(kind == RuleKind.ROOT
                        ? "一级命令数组只能填写一个命令词：" + value
                        : "二级命令数组必须填写两个命令词：" + value);
            }
            rules.add(kind == RuleKind.EXACT ? normalized : normalized.toLowerCase(Locale.ROOT));
        }
        return Collections.unmodifiableSet(rules);
    }

    private static String insertExactRule(String contents, String command) {
        String marker = "[command.auto-allow]";
        int sectionStart = contents.indexOf(marker);
        if (sectionStart < 0) {
            throw new IllegalArgumentException("配置中缺少 [command.auto-allow] 分区");
        }
        int sectionEnd = contents.indexOf("\n[", sectionStart + marker.length());
        if (sectionEnd < 0) {
            sectionEnd = contents.length();
        }
        String section = contents.substring(sectionStart, sectionEnd);
        int keyOffset = findKeyOffset(section, "exact");
        if (keyOffset < 0) {
            throw new IllegalArgumentException("配置中缺少 command.auto-allow.exact 数组");
        }
        int arrayStart = contents.indexOf('[', sectionStart + keyOffset);
        int arrayEnd = findArrayEnd(contents, arrayStart);
        String escaped = command.replace("\\", "\\\\").replace("\"", "\\\"");
        int lastValue = lastArrayContentOffset(contents, arrayStart, arrayEnd);
        String updated = contents;
        if (lastValue >= 0 && contents.charAt(lastValue) != ',') {
            updated = contents.substring(0, lastValue + 1) + "," + contents.substring(lastValue + 1);
            arrayEnd++;
        }
        return updated.substring(0, arrayEnd) + "\n    \"" + escaped + "\",\n" + updated.substring(arrayEnd);
    }

    private static int lastArrayContentOffset(String contents, int arrayStart, int arrayEnd) {
        int lastContent = -1;
        char quote = 0;
        boolean escaped = false;
        boolean comment = false;
        for (int index = arrayStart + 1; index < arrayEnd; index++) {
            char current = contents.charAt(index);
            if (comment) {
                if (current == '\n' || current == '\r') {
                    comment = false;
                }
                continue;
            }
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\' && quote == '"') {
                    escaped = true;
                } else if (current == quote) {
                    quote = 0;
                }
                lastContent = index;
            } else if (current == '"' || current == '\'') {
                quote = current;
                lastContent = index;
            } else if (current == '#') {
                comment = true;
            } else if (!Character.isWhitespace(current)) {
                lastContent = index;
            }
        }
        return lastContent;
    }

    private static int findKeyOffset(String section, String wantedKey) {
        int offset = 0;
        for (String line : section.split("\n", -1)) {
            String clean = stripComment(line).trim();
            int equals = indexOfUnquoted(clean, '=');
            if (equals > 0 && clean.substring(0, equals).trim().equals(wantedKey)) {
                return offset + line.indexOf(wantedKey);
            }
            offset += line.length() + 1;
        }
        return -1;
    }

    private static int findArrayEnd(String contents, int start) {
        if (start < 0) {
            throw new IllegalArgumentException("配置中的 exact 数组格式无效");
        }
        boolean quoted = false;
        boolean escaped = false;
        for (int index = start + 1; index < contents.length(); index++) {
            char current = contents.charAt(index);
            if (quoted) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    quoted = false;
                }
            } else if (current == '"') {
                quoted = true;
            } else if (current == ']') {
                return index;
            }
        }
        throw new IllegalArgumentException("配置中的 exact 数组没有闭合");
    }

    private static void atomicWrite(Path target, String contents) throws IOException {
        if (Files.isSymbolicLink(target)) {
            throw new IOException("CubePilot 配置文件不能是符号链接");
        }
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.writeString(temporary, contents, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            restrictFilePermissions(temporary);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
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
                try {
                    Files.createDirectory(current, PosixFilePermissions.asFileAttribute(EnumSet.of(
                            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE)));
                } catch (UnsupportedOperationException ignored) {
                    Files.createDirectory(current);
                }
            }
        }
    }

    private static void restrictFilePermissions(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Windows 等不支持 POSIX 权限的平台由操作系统 ACL 管理。
        }
    }

    private static String defaultContents() {
        return "# CubePilot 主配置文件。文件编码必须保持为 UTF-8。\n"
                + "# 修改完成后，在游戏内由 OP 2 级及以上玩家执行 /cp reload 即可生效。\n"
                + "# 重载只读取 CubePilot 自身配置，不会调用模型 API，也不会热加载其他 Mod。\n"
                + "# 重载失败时继续使用上一份有效配置；请根据聊天提示检查本文件。\n\n"
                + banner("1. 大模型 API")
                + "[api]\n"
                + "# OpenAI Chat Completions 兼容接口的完整地址。公网地址必须使用 HTTPS；仅本机回环地址可使用 HTTP。\n"
                + "endpoint = \"" + DEFAULT_ENDPOINT + "\"\n"
                + "# 请求使用的模型名，可填写兼容接口提供商支持的模型，例如 DeepSeek 的相应模型名。\n"
                + "model = \"" + DEFAULT_MODEL + "\"\n"
                + "# API Key。更推荐通过服务端进程的 CUBEPILOT_API_KEY 环境变量提供；环境变量优先。\n"
                + "key = \"\"\n"
                + "# 请求超时秒数，有效范围 5～120；超出范围会被限制到最近的边界值。\n"
                + "timeout-seconds = 45\n\n"
                + banner("2. 只读文件扫描")
                + "[files]\n"
                + "# 单个配置、日志或崩溃报告允许读取的最大字节数，有效范围 1024～65536。\n"
                + "# 此设置不会扩大目录白名单，CubePilot 自身配置和凭据仍禁止通过聊天读取。\n"
                + "max-bytes = 16384\n\n"
                + banner("3. 自动执行与白名单按钮")
                + "[command]\n"
                + "# true：未命中自动白名单、但具备自动执行资格时，同时显示“加入自动白名单”按钮。\n"
                + "#       第一次点击只显示二次确认；确认后才写入当前完整命令并立即重载。\n"
                + "# false：只输出可点击的手动建议，不显示或询问任何写入白名单的按钮。\n"
                + "# 无论此项为何值，CubePilot 都不会拦截玩家自己输入的任何原版或 Mod 命令。\n"
                + "offer-add-to-auto-allow = true\n\n"
                + banner("4. 自动执行白名单")
                + "# 只有命中下列任意数组，并通过永久禁令、可调整禁令、安全项、权限和语法检查的命令才会自动执行。\n"
                + "# roots：一级命令数组。填写 \"time\" 会允许 time 下的全部二级及后续参数，请谨慎使用。\n"
                + "# subcommands：二级命令数组。填写 \"time set\" 会允许 time set 后的全部参数。\n"
                + "# exact：完整命令数组，只允许与整条命令完全一致的内容；按钮确认后也只写入此数组。\n"
                + "# 数组必须一项一行，命令不需要开头的 /。默认全部为空，因此默认不自动执行任何模型命令。\n"
                + "[command.auto-allow]\n"
                + "roots = [\n"
                + "    # \"time\",\n"
                + "]\n"
                + "subcommands = [\n"
                + "    # \"weather clear\",\n"
                + "]\n"
                + "exact = [\n"
                + "    # \"time set day\",\n"
                + "]\n\n"
                + banner("5. 可调整的自动执行禁止名单")
                + "# 命中此处任意规则的命令永远不会自动执行，即使它同时命中自动白名单。\n"
                + "# 它仍可按第 7 节安全项的设置显示为手动建议，由玩家检查后自行回车。\n"
                + "# roots、subcommands、exact 的匹配层级与上一节完全相同；默认不额外禁止。\n"
                + "[command.auto-deny]\n"
                + "roots = [\n"
                + "    # \"gamemode\",\n"
                + "]\n"
                + "subcommands = [\n"
                + "    # \"weather thunder\",\n"
                + "]\n"
                + "exact = [\n"
                + "    # \"gamerule keepInventory true\",\n"
                + "]\n\n"
                + banner("6. 内置永久禁止自动执行（只读说明）")
                + "# 下列数组仅说明代码中的安全底线，不是可编辑配置项；修改或删除本注释不会解除限制。\n"
                + "# 这些命令仍可能作为手动建议显示；CubePilot 不会拦截玩家自己输入的命令。\n"
                + "# permanent-auto-deny = [\"stop\", \"op\", \"deop\", \"ban\", \"ban-ip\", \"pardon\", \"pardon-ip\", \"whitelist\", \"kick\", \"execute\", \"function\", \"schedule\", \"reload\", \"fill\", \"clone\", \"setblock\", \"summon\", \"kill\", \"data\", \"forceload\", \"publish\", \"transfer\", \"debug\", \"jfr\", \"perf\", \"place\", \"spreadplayers\", \"cp\", \"cpilot\", \"cpa\"]\n"
                + "# 此外，包含命令方块等特殊管理物品或危险数据的 give 命令也不会自动执行。\n\n"
                + banner("7. 自动与手动建议安全项")
                + "# 每个安全项独立配置，数值含义完全相同：\n"
                + "# 0 = 关闭此项限制；自动执行和 CubePilot 手动建议都不因这一项被阻止。\n"
                + "# 1 = 只禁止 AI 自动执行；仍可显示为点击后由玩家手动回车的建议。\n"
                + "# 2 = 只禁止 CubePilot 输出手动建议；命中自动白名单时仍可自动执行。\n"
                + "# 3 = 同时禁止自动执行和 CubePilot 输出手动建议（默认值）。\n"
                + "# “手动建议”只指 CubePilot 生成的可点击文本，本模组从不拦截玩家自行输入的命令。\n"
                + "# 多项规则可能同时命中；命令必须通过所有已启用的相应检查。\n"
                + "[command.safety]\n"
                + "# 单独控制 @a。即使本项为 0，@a 仍可能被 multiple-targets 或 other-players 限制。\n"
                + "all-players-selector = 3\n"
                + "# 单独控制 @e。即使本项为 0，@e 仍可能被 multiple-targets 限制。\n"
                + "all-entities-selector = 3\n"
                + "# 控制 NBT、1.21.1 物品组件及类似的花括号或方括号复杂数据；选择器方括号不计入此项。\n"
                + "complex-nbt = 3\n"
                + "# 控制可能命中多个对象的选择器，例如没有 limit=1 的 @a、@e。\n"
                + "multiple-targets = 3\n"
                + "# 控制明显指向发起请求玩家之外对象的参数，例如 @p、@r 或在线的其他玩家名。\n"
                + "# 这是保守的通用检查；无法可靠判断的 Mod 自定义参数仍需依靠精确白名单和玩家权限。\n"
                + "other-players = 3\n";
    }

    private static String banner(String title) {
        return "# ============================================================\n"
                + "# =                                                          =\n"
                + "# =  " + title + "\n"
                + "# =                                                          =\n"
                + "# ============================================================\n\n";
    }

    private static void validateEndpoint(String endpoint) {
        URI uri = URI.create(endpoint);
        String scheme = uri.getScheme();
        String host = uri.getHost();
        String normalizedHost = host == null ? "" : host.replace("[", "").replace("]", "");
        boolean loopback = "localhost".equalsIgnoreCase(normalizedHost)
                || "127.0.0.1".equals(normalizedHost) || "::1".equals(normalizedHost);
        if (host == null || uri.getUserInfo() != null || !("https".equalsIgnoreCase(scheme)
                || (loopback && "http".equalsIgnoreCase(scheme)))) {
            throw new IllegalArgumentException("API 地址必须使用 HTTPS；仅允许本机回环地址使用 HTTP");
        }
    }

    private static String stringValue(Map<String, Object> values, String key, String fallback) {
        Object value = values.get(key);
        if (value == null) return fallback;
        if (!(value instanceof String text)) throw new IllegalArgumentException(key + " 必须是字符串");
        return text;
    }

    private static boolean booleanValue(Map<String, Object> values, String key, boolean fallback) {
        Object value = values.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Boolean flag)) throw new IllegalArgumentException(key + " 必须是 true 或 false");
        return flag;
    }

    private static int intValue(Map<String, Object> values, String key, int fallback) {
        Object value = values.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Integer number)) throw new IllegalArgumentException(key + " 必须是整数");
        return number;
    }

    private static int boundedInt(Map<String, Object> values, String key, int fallback, int minimum, int maximum) {
        return Math.max(minimum, Math.min(intValue(values, key, fallback), maximum));
    }

    @SuppressWarnings("unchecked")
    private static List<String> listValue(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) return List.of();
        if (!(value instanceof List<?> list) || list.stream().anyMatch(entry -> !(entry instanceof String))) {
            throw new IllegalArgumentException(key + " 必须是字符串数组");
        }
        return (List<String>) list;
    }

    public Path directory() { return directory; }
    public String endpoint() { return endpoint; }
    public String model() { return model; }

    public String apiKey() {
        String environmentKey = System.getenv("CUBEPILOT_API_KEY");
        return environmentKey != null && !environmentKey.isBlank() ? environmentKey.trim() : apiKey;
    }

    public int timeoutSeconds() { return timeoutSeconds; }
    public int maxFileBytes() { return maxFileBytes; }
    public boolean offerAddToAutoAllow() { return offerAddToAutoAllow; }
    public CommandRules autoAllow() { return autoAllow; }
    public CommandRules autoDeny() { return autoDeny; }
    public int safetyMode(SafetyRule rule) { return safetyModes.get(rule); }

    public enum SafetyRule {
        ALL_PLAYERS_SELECTOR("all-players-selector"),
        ALL_ENTITIES_SELECTOR("all-entities-selector"),
        COMPLEX_NBT("complex-nbt"),
        MULTIPLE_TARGETS("multiple-targets"),
        OTHER_PLAYERS("other-players");

        private final String configKey;
        SafetyRule(String configKey) { this.configKey = configKey; }
        public String configKey() { return configKey; }
    }

    public record CommandRules(Set<String> roots, Set<String> subcommands, Set<String> exact) {
    }

    private enum RuleKind { ROOT, SUBCOMMAND, EXACT }
}
