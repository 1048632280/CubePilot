package com.cubepilot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DiagnosticAnalyzerTest {
    @Test
    void detectsCrashAndModConflictSignalsWithoutSuggestingChanges() {
        DiagnosticReport report = DiagnosticAnalyzer.analyze("crash-reports/crash-1.txt",
                "java.lang.NoSuchMethodError: mod.example.Api.run\n"
                        + "Caused by: java.lang.OutOfMemoryError: Java heap space\n");

        assertEquals("crash-report", report.category());
        assertEquals("CRITICAL", report.severity());
        assertTrue(report.findings().stream().anyMatch(f -> f.code().equals("MOD_OR_PLUGIN_CONFLICT")));
        assertTrue(report.findings().stream().anyMatch(f -> f.code().equals("MEMORY_EXHAUSTION")));
        assertTrue(report.toText().contains("不会修改"));
    }

    @Test
    void detectsConfigurationRisksAndHidesSecretEvidence() {
        DiagnosticReport report = DiagnosticAnalyzer.analyze("config/example/server.toml",
                "enable-command-block=false password=super-secret-value\n"
                        + "online-mode=false\n");

        assertEquals("configuration", report.category());
        assertEquals("WARNING", report.severity());
        assertTrue(report.findings().stream().anyMatch(f -> f.code().equals("COMMAND_BLOCK_DISABLED")));
        assertTrue(report.findings().stream().anyMatch(f -> f.code().equals("OFFLINE_MODE")));
        assertFalse(report.toText().contains("super-secret-value"));
    }

    @Test
    void producesInformationalReportForCleanText() {
        DiagnosticReport report = DiagnosticAnalyzer.analyze("logs/latest.log", "Server started\nDone\n");

        assertEquals("log", report.category());
        assertEquals("INFO", report.severity());
        assertTrue(report.findings().isEmpty());
        assertTrue(report.toText().contains("未发现内置规则"));
    }

    @Test
    void limitsEvidenceAndFindings() {
        String repeated = "ERROR " + "x".repeat(400) + "\n";
        DiagnosticReport report = DiagnosticAnalyzer.analyze("logs/latest.log", repeated.repeat(30));

        assertTrue(report.findings().size() <= 12);
        assertTrue(report.findings().stream().allMatch(f -> f.evidence().length() <= 220));
    }
}
