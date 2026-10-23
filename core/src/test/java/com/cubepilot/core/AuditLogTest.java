package com.cubepilot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
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
        assertTrue(AuditLog.awaitPendingWrites(5, TimeUnit.SECONDS));

        String first = Files.readString(directory.resolve("audit.jsonl"));
        assertTrue(first.contains("\"actor\":\"Admin\""));
        assertTrue(first.contains("\"action\":\"command.suggested\""));
        assertTrue(first.contains("[已隐藏]"));
        assertFalse(first.contains("top-secret"));

        String detail = "x".repeat(1900);
        for (int index = 0; index < 700; index++) {
            audit.record("Admin", "request.complete", detail);
        }
        assertTrue(AuditLog.awaitPendingWrites(10, TimeUnit.SECONDS));

        assertTrue(Files.isRegularFile(directory.resolve("audit.1.jsonl")));
        assertTrue(Files.size(directory.resolve("audit.jsonl")) <= 1024L * 1024L);
    }

    @Test
    void recordsTraceFieldsWithoutSecrets() throws IOException {
        AuditLog audit = new AuditLog(directory);
        audit.recordTrace("req-1", "Admin", "model.output", "api.key=top-secret", "answer=ok");
        assertTrue(AuditLog.awaitPendingWrites(5, TimeUnit.SECONDS));

        String content = Files.readString(directory.resolve("trace.jsonl"));
        assertTrue(content.contains("\"request_id\":\"req-1\""));
        assertTrue(content.contains("\"output\":\"answer=ok\""));
        assertTrue(content.contains("[已隐藏]"));
        assertFalse(content.contains("top-secret"));
    }

    @Test
    void canDisableTraceWithoutDisablingAudit() throws IOException {
        AuditLog audit = new AuditLog(directory, false, 1024);
        audit.record("Admin", "command.executed", "ok");
        audit.recordTrace("req-2", "Admin", "model.response", "input", "hidden");
        assertTrue(AuditLog.awaitPendingWrites(5, TimeUnit.SECONDS));

        assertTrue(Files.isRegularFile(directory.resolve("audit.jsonl")));
        assertFalse(Files.exists(directory.resolve("trace.jsonl")));
    }

    @Test
    void marksTruncatedFields() throws IOException {
        AuditLog audit = new AuditLog(directory, true, 1024);
        audit.recordTrace("req-3", "Admin", "long", "x".repeat(2000), "ok");
        assertTrue(AuditLog.awaitPendingWrites(5, TimeUnit.SECONDS));

        String content = Files.readString(directory.resolve("trace.jsonl"));
        assertTrue(content.contains("已截断"));
    }
}
