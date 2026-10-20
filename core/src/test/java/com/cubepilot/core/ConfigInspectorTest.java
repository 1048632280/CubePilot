package com.cubepilot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigInspectorTest {
    @TempDir
    Path serverRoot;

    @Test
    void extractsJsonPathsAndHidesSensitiveKeys() {
        ConfigInspector.ConfigDocument document = ConfigInspector.inspect("config/example.json",
                "{\"spawn\":{\"radius\":8},\"api_key\":\"secret\",\"items\":[\"a\",\"b\"]}");

        assertEquals("JSON", document.format());
        assertEquals("8", find(document.entries(), "spawn.radius").value());
        assertEquals("[已隐藏]", find(document.entries(), "api_key").value());
        assertEquals("\"a\"", find(document.entries(), "items[0]").value());
    }

    @Test
    void extractsYamlTomlAndPropertiesKeys() {
        ConfigInspector.ConfigDocument yaml = ConfigInspector.inspect("config/example.yaml",
                "server:\n  view-distance: 8\n  password: hidden\n");
        ConfigInspector.ConfigDocument toml = ConfigInspector.inspect("config/example.toml",
                "[server]\nview-distance = 10\n");
        ConfigInspector.ConfigDocument properties = ConfigInspector.inspect("config/example.properties",
                "mode=survival\nmax-players=10\n");

        assertEquals("8", find(yaml.entries(), "server.view-distance").value());
        assertEquals("[已隐藏]", find(yaml.entries(), "server.password").value());
        assertEquals("10", find(toml.entries(), "server.view-distance").value());
        assertEquals("survival", find(properties.entries(), "mode").value());
    }

    @Test
    void locatesOnlyMatchingModConfigFiles() throws IOException {
        Path config = Files.createDirectories(serverRoot.resolve("config"));
        Files.writeString(config.resolve("examplemod.toml"), "enabled=true\n");
        Files.writeString(config.resolve("examplemod-client.toml"), "view-distance=8\n");
        Files.writeString(config.resolve("othermod.toml"), "enabled=false\n");
        Files.createDirectories(serverRoot.resolve("config/cubepilot"));
        Files.writeString(serverRoot.resolve("config/cubepilot/cubepilot.properties"), "api.key=hidden\n");
        ReadOnlyFileAccess access = new ReadOnlyFileAccess(serverRoot, 16384);

        assertEquals(List.of("config/examplemod-client.toml", "config/examplemod.toml"),
                access.findModConfigurationFiles("examplemod"));
        assertTrue(access.findModConfigurationFiles("othermod").contains("config/othermod.toml"));
        assertFalse(access.findModConfigurationFiles("cubepilot").stream()
                .anyMatch(path -> path.contains("cubepilot")));
    }

    private static ConfigInspector.Entry find(List<ConfigInspector.Entry> entries, String path) {
        return entries.stream().filter(entry -> entry.path().equals(path)).findFirst().orElseThrow();
    }
}
