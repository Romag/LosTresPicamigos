package org.lostrespicamigos.domain;

import java.util.List;

public record AgentResult(
        String text,
        String sessionId,
        List<String> warnings,
        String rawStdout,
        String rawStderr,
        int exitCode) {

    public AgentResult {
        text = text == null ? "" : text;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        rawStdout = rawStdout == null ? "" : rawStdout;
        rawStderr = rawStderr == null ? "" : rawStderr;
    }
}
