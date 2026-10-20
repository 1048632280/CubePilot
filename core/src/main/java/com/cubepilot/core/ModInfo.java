package com.cubepilot.core;

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

}
