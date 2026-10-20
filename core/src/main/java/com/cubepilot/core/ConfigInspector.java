package com.cubepilot.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 解析常见 Mod 配置的键路径和值；只生成说明数据，不提供写回能力。
 */
public final class ConfigInspector {
    private static final int MAX_ENTRIES = 80;
    private static final int MAX_DEPTH = 16;
    private static final int MAX_VALUE_LENGTH = 240;
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i)(?:api[._ -]?key|password|passwd|secret|token|authorization|private[._ -]?key)");

    private ConfigInspector() {
    }

    public static ConfigDocument inspect(String path, String content) {
        String safePath = path == null ? "" : path.trim();
        String text = content == null ? "" : content;
        String format = formatForPath(safePath);
        List<Entry> entries = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        try {
            switch (format) {
                case "JSON" -> {
                    flattenJson("", JsonParser.parseString(text), entries, warnings, 0);
                    for (int index = 0; index < entries.size(); index++) {
                        Entry entry = entries.get(index);
                        if (entry.line() == 0) {
                            entries.set(index, new Entry(entry.path(), entry.value(),
                                    findJsonLine(text, entry.path())));
                        }
                    }
                }
                case "YAML" -> parseLines(text, entries, warnings, true, false);
                case "TOML" -> parseLines(text, entries, warnings, false, true);
                default -> parseLines(text, entries, warnings, false, false);
            }
        } catch (RuntimeException exception) {
            warnings.add("无法完整解析为 " + format + "，已保留可识别的文本键值；请人工确认语法。" );
            if (entries.isEmpty()) {
                parseLines(text, entries, warnings, false, false);
            }
        }
        entries.sort(Comparator.comparing(Entry::path));
        if (entries.size() > MAX_ENTRIES) {
            entries = new ArrayList<>(entries.subList(0, MAX_ENTRIES));
            warnings.add("键数量超过上限，仅展示前 " + MAX_ENTRIES + " 项。" );
        }
        return new ConfigDocument(safePath, format, entries, warnings);
    }

    public static String formatForPath(String path) {
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".json")) {
            return "JSON";
        }
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) {
            return "YAML";
        }
        if (lower.endsWith(".toml")) {
            return "TOML";
        }
        if (lower.endsWith(".properties") || lower.endsWith(".cfg") || lower.endsWith(".config")
                || lower.endsWith(".conf") || lower.endsWith(".ini")) {
            return "PROPERTIES";
        }
        return "TEXT";
    }

    private static void flattenJson(String prefix, JsonElement element, List<Entry> entries,
            List<String> warnings, int depth) {
        if (entries.size() >= MAX_ENTRIES) {
            return;
        }
        if (depth > MAX_DEPTH) {
            warnings.add("JSON 嵌套层级超过上限，部分键未展示。" );
            return;
        }
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> member : element.getAsJsonObject().entrySet()) {
                String path = prefix.isEmpty() ? member.getKey() : prefix + "." + member.getKey();
                flattenJson(path, member.getValue(), entries, warnings, depth + 1);
                if (entries.size() >= MAX_ENTRIES) {
                    return;
                }
            }
        } else if (element.isJsonArray()) {
            for (int index = 0; index < element.getAsJsonArray().size(); index++) {
                String path = prefix + "[" + index + "]";
                flattenJson(path, element.getAsJsonArray().get(index), entries, warnings, depth + 1);
                if (entries.size() >= MAX_ENTRIES) {
                    return;
                }
            }
        } else {
                addEntry(entries, prefix, element.toString(), 0);
        }
    }

    private static void parseLines(String text, List<Entry> entries, List<String> warnings,
            boolean yaml, boolean toml) {
        Deque<Section> sections = new ArrayDeque<>();
        String[] lines = text.split("\\R", -1);
        for (int index = 0; index < lines.length && entries.size() < MAX_ENTRIES; index++) {
            String raw = lines[index];
            String trimmed = raw.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")
                    || trimmed.equals("---") || trimmed.equals("...")) {
                continue;
            }
            if (toml && trimmed.startsWith("[") && trimmed.endsWith("]")) {
                String section = trimmed.substring(1, trimmed.length() - 1).trim();
                sections.clear();
                sections.push(new Section(0, section));
                continue;
            }
            int separator = findSeparator(trimmed, yaml ? ':' : '=');
            if (separator < 0 && !toml) {
                separator = findSeparator(trimmed, ':');
            }
            if (separator < 1) {
                if (yaml && (trimmed.startsWith("-") || trimmed.endsWith(":"))) {
                    continue;
                }
                warnings.add("第 " + (index + 1) + " 行不是可识别的键值格式。" );
                continue;
            }
            String key = trimmed.substring(0, separator).trim();
            String value = trimmed.substring(separator + 1).trim();
            if (key.isEmpty()) {
                continue;
            }
            int indent = raw.indexOf(trimmed);
            if (yaml) {
                while (!sections.isEmpty() && indent <= sections.peek().indent()) {
                    sections.pop();
                }
                sections.push(new Section(indent, key));
            }
            String path = joinSections(sections, key, yaml || toml);
            addEntry(entries, path, value, index + 1);
        }
        if (entries.size() >= MAX_ENTRIES) {
            warnings.add("键数量达到上限，后续内容未展示。" );
        }
    }

    private static int findSeparator(String line, char expected) {
        boolean quoted = false;
        char quote = 0;
        for (int index = 0; index < line.length(); index++) {
            char current = line.charAt(index);
            if ((current == '\'' || current == '"') && (index == 0 || line.charAt(index - 1) != '\\')) {
                if (!quoted) {
                    quoted = true;
                    quote = current;
                } else if (quote == current) {
                    quoted = false;
                }
            } else if (!quoted && current == expected) {
                return index;
            }
        }
        return -1;
    }

    private static String joinSections(Deque<Section> sections, String key, boolean yaml) {
        if (!yaml || sections.isEmpty()) {
            return key;
        }
        List<String> names = new ArrayList<>();
        for (Section section : sections) {
            names.add(section.name());
        }
        java.util.Collections.reverse(names);
        if (!names.isEmpty() && names.get(names.size() - 1).equals(key)) {
            names.remove(names.size() - 1);
        }
        names.add(key);
        return String.join(".", names);
    }

    private static void addEntry(List<Entry> entries, String path, String rawValue, int line) {
        if (path == null || path.isBlank() || entries.size() >= MAX_ENTRIES) {
            return;
        }
        String value = rawValue == null ? "" : rawValue.trim();
        if (SENSITIVE_KEY.matcher(path).find()) {
            value = "[已隐藏]";
        } else {
            value = AuditLog.redact(value).replaceAll("[\\r\\n]", " ");
            if (value.length() > MAX_VALUE_LENGTH) {
                value = value.substring(0, MAX_VALUE_LENGTH) + "…";
            }
        }
        entries.add(new Entry(path, value, line));
    }

    private static int findJsonLine(String text, String path) {
        if (text == null || path == null || path.isBlank()) {
            return 0;
        }
        String key = path.replaceAll("\\[\\d+\\]$", "");
        int separator = Math.max(key.lastIndexOf('.'), key.lastIndexOf(']'));
        String leaf = separator >= 0 ? key.substring(separator + 1) : key;
        if (leaf.isBlank()) {
            return 0;
        }
        String quoted = "\"" + java.util.regex.Pattern.quote(leaf) + "\"";
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(quoted).matcher(text);
        if (!matcher.find()) {
            return 0;
        }
        int line = 1;
        for (int index = 0; index < matcher.start(); index++) {
            if (text.charAt(index) == '\n') {
                line++;
            }
        }
        return line;
    }

    public record ConfigDocument(String path, String format, List<Entry> entries, List<String> warnings) {
        public ConfigDocument {
            entries = List.copyOf(entries);
            warnings = List.copyOf(warnings);
        }

        public String toSummary() {
            StringBuilder summary = new StringBuilder("配置文件：").append(path)
                    .append("\n格式：").append(format)
                    .append("\n可识别设置（").append(entries.size()).append("）：");
            if (entries.isEmpty()) {
                summary.append("\n（没有识别到键值；请让 AI 根据脱敏原文进一步判断）");
            } else {
                for (Entry entry : entries) {
                    summary.append("\n- ").append(entry.path()).append(" = ").append(entry.value());
                    if (entry.line() > 0) {
                        summary.append("（第").append(entry.line()).append("行）");
                    }
                }
            }
            for (String warning : warnings) {
                summary.append("\n解析提示：").append(warning);
            }
            return summary.toString();
        }
    }

    public record Entry(String path, String value, int line) {
        public Entry(String path, String value) {
            this(path, value, 0);
        }
    }

    private record Section(int indent, String name) {
    }
}
