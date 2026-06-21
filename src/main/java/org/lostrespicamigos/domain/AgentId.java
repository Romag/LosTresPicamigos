package org.lostrespicamigos.domain;

import java.util.Locale;

public enum AgentId {
    CODEX("codex"),
    CLAUDE("claude"),
    ANTIGRAVITY("antigravity");

    private final String value;

    AgentId(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static AgentId parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("agent is required");
        }
        String normalized = value.toLowerCase(Locale.ROOT).replace('-', '_');
        if (normalized.equals("agy") || normalized.equals("gemini")) {
            return ANTIGRAVITY;
        }
        for (AgentId agent : values()) {
            if (agent.value.equals(normalized) || agent.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return agent;
            }
        }
        throw new IllegalArgumentException("Unsupported agent: " + value);
    }
}
