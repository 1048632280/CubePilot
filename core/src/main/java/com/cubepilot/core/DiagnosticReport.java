package com.cubepilot.core;

import java.util.List;

/**
 * 只读诊断结果；报告只包含规则摘要和经过脱敏、截断的证据，不包含可执行操作。
 */
public record DiagnosticReport(
        String path,
        String category,
        String severity,
        String summary,
        List<Finding> findings) {

    public DiagnosticReport {
        path = path == null ? "" : path;
        category = category == null ? "unknown" : category;
        severity = severity == null ? "INFO" : severity;
        summary = summary == null ? "" : summary;
        findings = List.copyOf(findings);
    }

    public String toText() {
        StringBuilder text = new StringBuilder("本地只读诊断\n")
                .append("文件：").append(path)
                .append("\n类型：").append(category)
                .append("\n最高严重度：").append(severity)
                .append("\n结论：").append(summary);
        if (findings.isEmpty()) {
            text.append("\n发现：未命中内置风险规则；仍建议结合完整上下文人工确认。");
            return text.toString();
        }
        text.append("\n发现（").append(findings.size()).append("）：");
        for (Finding finding : findings) {
            text.append("\n- [").append(finding.severity()).append("] ")
                    .append(finding.code()).append("：").append(finding.message());
            if (!finding.evidence().isBlank()) {
                text.append("（证据：").append(finding.evidence()).append("）");
            }
        }
        text.append("\n说明：这是只读辅助诊断，不会修改配置、日志、崩溃报告或 Mod 文件。");
        return text.toString();
    }

    public record Finding(String severity, String code, String message, String evidence) {
        public Finding {
            severity = severity == null ? "INFO" : severity;
            code = code == null ? "UNKNOWN" : code;
            message = message == null ? "" : message;
            evidence = evidence == null ? "" : evidence;
        }
    }
}
