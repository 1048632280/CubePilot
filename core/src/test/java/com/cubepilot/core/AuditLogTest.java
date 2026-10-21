package com.cubepilot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

class AuditLogTest {
    @TempDir
    Path directory;

    @Test
    void redactsDottedApiKeyAndHandlesLongTextWithNullCharacters() {
        String secret = "api.key=top-secret";
        String redacted = AuditLog.redact(secret);

        assertTrue(redacted.contains("[已隐藏]"));
        assertFalse(redacted.contains("top-secret"));
        assertEquals("ab", AuditLog.safeDiagnosticText("a\u0000b"));
    }

    @Test
    void persistsRedactedStructuredEventsAndRotatesOldFiles() throws IOException {
        AuditLog audit = new AuditLog(directory);
        audit.record("Admin", "command.suggested", "api.key=top-secret");

        String first = Files.readString(directory.resolve("audit.jsonl"));
        assertTrue(first.contains("\"actor\":\"Admin\""));
        assertTrue(first.contains("\"action\":\"command.suggested\""));
        assertTrue(first.contains("[已隐藏]"));
        assertFalse(first.contains("top-secret"));

        String detail = "x".repeat(1900);
        for (int index = 0; index < 700; index++) {
            audit.record("Admin", "request.complete", detail);
        }

        assertTrue(Files.isRegularFile(directory.resolve("audit.1.jsonl")));
        assertTrue(Files.size(directory.resolve("audit.jsonl")) <= 1024L * 1024L);
    }
}
