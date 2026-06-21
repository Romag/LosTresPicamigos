package org.lostrespicamigos.domain;

import java.util.Locale;

public enum IsolationMode {
    SNAPSHOT,
    WORKTREE,
    DIRECT;

    public static IsolationMode parse(String value, AgentRole role) {
        if (value == null) {
            return role == AgentRole.IMPLEMENT ? WORKTREE : SNAPSHOT;
        }
        return valueOf(value.toUpperCase(Locale.ROOT).replace('-', '_'));
    }
}
