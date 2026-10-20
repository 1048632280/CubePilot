package com.cubepilot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CubePilotConfigTest {
    @TempDir
    Path serverRoot;

    @Test
    void ensureExistsCreatesOnlyDefaultConfigWithoutValidatingContents() throws IOException {
        Path configPath = CubePilotConfig.ensureExists(serverRoot);

        assertEquals(serverRoot.resolve("config/cubepilot/cubepilot.properties"), configPath);
        assertTrue(Files.isRegularFile(configPath));
        assertTrue(Files.readString(configPath).contains("api.endpoint=https://api.openai.com/v1/chat/completions"));

        Files.writeString(configPath, "api.endpoint=http://remote.example.invalid/v1/chat/completions\n");
        assertEquals(configPath, CubePilotConfig.ensureExists(serverRoot));
        assertFalse(Files.readString(configPath).contains("api.endpoint=https://api.openai.com"));
    }

    @Test
    void rejectsCredentialsEmbeddedInEndpointUrl() throws IOException {
        Path directory = Files.createDirectories(serverRoot.resolve("config/cubepilot"));
        Files.writeString(directory.resolve("cubepilot.properties"),
                "api.endpoint=https://user:password@example.com/v1/chat/completions\n");

        assertThrows(IllegalArgumentException.class, () -> CubePilotConfig.load(serverRoot));
    }
}
