package org.lostrespicamigos.domain;

import java.util.Locale;

public enum AccessMode {
    READ_ONLY,
    WORKSPACE_WRITE;

    public static AccessMode parse(String value, AgentRole role) {
        if (value == null) {
            return role == AgentRole.IMPLEMENT ? WORKSPACE_WRITE : READ_ONLY;
        }
        return valueOf(value.toUpperCase(Locale.ROOT).replace('-', '_'));
    }
}
