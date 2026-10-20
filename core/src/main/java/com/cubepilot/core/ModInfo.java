package com.cubepilot.core;

import java.util.Locale;

/** 已加载 Mod 的最小元数据，用于自然语言配置定位。 */
public record ModInfo(String id, String name, String version) {
    public ModInfo {
        id = id == null ? "" : id.trim();
        name = name == null ? "" : name.trim();
        version = version == null ? "" : version.trim();
    }

    public String displayText() {
        String versionText = version.isBlank() ? "" : " " + version;
        if (name.isBlank() || name.equalsIgnoreCase(id)) {
            return id + versionText;
        }
        return id + " (" + name + ")" + versionText;
    }

    public boolean isValidId() {
        return id.matches("[A-Za-z0-9_.-]{1,64}");
    }

    public boolean matches(String query) {
        String normalizedQuery = normalize(query);
        if (normalizedQuery.isBlank()) {
            return false;
        }
        return containsNormalized(normalizedQuery, id)
                || containsNormalized(normalizedQuery, name);
    }

    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static boolean containsNormalized(String query, String candidate) {
        String normalizedCandidate = normalize(candidate);
        return !normalizedCandidate.isBlank() && query.contains(normalizedCandidate);
    }
}
