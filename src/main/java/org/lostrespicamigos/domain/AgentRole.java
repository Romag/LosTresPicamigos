package org.lostrespicamigos.domain;

import java.util.Locale;

public enum AgentRole {
    PLAN,
    IMPLEMENT,
    REVIEW,
    GENERAL;

    public static AgentRole parse(String value) {
        return value == null ? GENERAL : valueOf(value.toUpperCase(Locale.ROOT).replace('-', '_'));
    }
}
