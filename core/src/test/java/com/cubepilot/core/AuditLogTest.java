package com.cubepilot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AuditLogTest {
    @Test
    void redactsDottedApiKeyAndHandlesLongTextWithNullCharacters() {
        String secret = "api.key=top-secret";
        String redacted = AuditLog.redact(secret);

        assertTrue(redacted.contains("[已隐藏]"));
        assertFalse(redacted.contains("top-secret"));
        assertEquals("ab", AuditLog.safeDiagnosticText("a\u0000b"));
    }
}
