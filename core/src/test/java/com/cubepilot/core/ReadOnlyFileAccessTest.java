package com.cubepilot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReadOnlyFileAccessTest {
    @TempDir
    Path serverRoot;

    @Test
    void readsOnlyAllowedTextFilesAndRedactsSecrets() throws IOException {
        Path config = Files.createDirectories(serverRoot.resolve("config")).resolve("example.toml");
        Files.writeString(config, "api_key = \"test-secret\"\nmode = \"survival\"\n");
        ReadOnlyFileAccess access = new ReadOnlyFileAccess(serverRoot, 16384);

        String result = access.read("config/example.toml");
        assertTrue(result.contains("[已隐藏]"));
        assertTrue(result.contains("survival"));
    }

    @Test
    void blocksTraversalAndSensitiveFiles() throws IOException {
        Files.writeString(serverRoot.resolve("secret.json"), "{}\n");
        ReadOnlyFileAccess access = new ReadOnlyFileAccess(serverRoot, 16384);

        assertThrows(IOException.class, () -> access.read("../secret.json"));
        assertThrows(IOException.class, () -> access.read("secret.json"));
        assertThrows(IOException.class, () -> access.read("config/cubepilot/cubepilot.toml"));
    }

    @Test
    void restrictsDirectoryListingToAllowedRoots() throws IOException {
        Files.createDirectories(serverRoot.resolve("world"));
        Files.createDirectories(serverRoot.resolve("logs"));
        ReadOnlyFileAccess access = new ReadOnlyFileAccess(serverRoot, 16384);

        assertThrows(IOException.class, () -> access.list("world"));
        assertEquals(List.of(), access.list("logs"));
    }

    @Test
    void readsConfigExtensionAndOnlyListsModJars() throws IOException {
        Path settings = Files.createDirectories(serverRoot.resolve("config/example"))
                .resolve("server.config");
        Files.writeString(settings, "mode=survival\n");
        Path mods = Files.createDirectories(serverRoot.resolve("mods"));
        Files.writeString(mods.resolve("example.jar"), "jar");
        Files.writeString(mods.resolve("notes.txt"), "not a mod");

        ReadOnlyFileAccess access = new ReadOnlyFileAccess(serverRoot, 16384);

        assertEquals("mode=survival\n", access.read("config/example/server.config"));
        assertEquals(List.of("example.jar"), access.list("mods"));
    }

    @Test
    void serverSettingsSnapshotIncludesOnlyExplicitlyAllowedProperties() throws IOException {
        Path properties = serverRoot.resolve("server.properties");
        String original = "online-mode=true\nserver-ip=192.0.2.1\nrcon.password=hidden-value\n"
                + "max-players=12\nmotd=Example server\nwhite-list=true\n";
        Files.writeString(properties, original);
        ReadOnlyFileAccess access = new ReadOnlyFileAccess(serverRoot, 16384);

        Map<String, String> snapshot = access.readServerSettingsSnapshot();

        assertEquals(Map.of("max-players", "12", "online-mode", "true", "white-list", "true"), snapshot);
        assertEquals(original, Files.readString(properties));
    }

    @Test
    void configurationInventoryListsPathsOnlyAndExcludesSensitiveFiles() throws IOException {
        Path config = Files.createDirectories(serverRoot.resolve("config/example"));
        Files.writeString(config.resolve("server.toml"), "do-not-include-content");
        Files.writeString(config.resolve("secret.json"), "secret");
        Path cubePilotConfig = Files.createDirectories(serverRoot.resolve("config/cubepilot"));
        Files.writeString(cubePilotConfig.resolve("cubepilot.toml"), "[api]\nkey = \"secret\"\n");
        Path archivedConfig = Files.createDirectories(serverRoot.resolve("config/cubepilot-archive"));
        Files.writeString(archivedConfig.resolve("settings.json"), "{}");
        Path logs = Files.createDirectories(serverRoot.resolve("logs"));
        Files.writeString(logs.resolve("latest.log"), "log content");
        Files.writeString(logs.resolve("usercache.json"), "private data");
        Path crashes = Files.createDirectories(serverRoot.resolve("crash-reports"));
        Files.writeString(crashes.resolve("crash-1.txt"), "crash content");
        ReadOnlyFileAccess access = new ReadOnlyFileAccess(serverRoot, 16384);

        List<String> inventory = access.configurationInventorySnapshot();

        assertEquals(List.of("config/cubepilot-archive/settings.json", "config/example/server.toml",
                "crash-reports/crash-1.txt", "logs/latest.log"), inventory);
        assertTrue(inventory.stream().noneMatch(path -> path.contains("secret") || path.contains("usercache")));
        assertTrue(inventory.stream().noneMatch(path -> path.contains("do-not-include-content")));
        assertEquals("{}", access.read("config/cubepilot-archive/settings.json"));
        assertThrows(IOException.class, () -> access.read("config/cubepilot/cubepilot.toml"));
    }
}
