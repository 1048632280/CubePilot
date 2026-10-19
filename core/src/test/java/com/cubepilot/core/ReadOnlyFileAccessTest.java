package com.cubepilot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
        assertThrows(IOException.class, () -> access.read("config/cubepilot/cubepilot.properties"));
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
}
