package org.lostrespicamigos.mcp;

import org.lostrespicamigos.domain.AgentResult;

import java.util.List;

public record RunResultSummary(
        String text,
        boolean textTruncated,
        String sessionId,
        List<String> warnings,
        int exitCode) {
    static final int MAX_TEXT_CHARACTERS = 50_000;

    public RunResultSummary {
        text = text == null ? "" : text;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static RunResultSummary from(AgentResult result) {
        boolean truncated = result.text().length() > MAX_TEXT_CHARACTERS;
        String text = truncated ? result.text().substring(0, MAX_TEXT_CHARACTERS) : result.text();
        return new RunResultSummary(text, truncated, result.sessionId(), result.warnings(), result.exitCode());
    }
}
