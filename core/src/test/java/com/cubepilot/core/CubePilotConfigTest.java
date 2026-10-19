package com.cubepilot.core;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CubePilotConfigTest {
    @TempDir
    Path serverRoot;

    @Test
    void rejectsCredentialsEmbeddedInEndpointUrl() throws IOException {
        Path directory = Files.createDirectories(serverRoot.resolve("config/cubepilot"));
        Files.writeString(directory.resolve("cubepilot.properties"),
                "api.endpoint=https://user:password@example.com/v1/chat/completions\n");

        assertThrows(IllegalArgumentException.class, () -> CubePilotConfig.load(serverRoot));
    }
}
