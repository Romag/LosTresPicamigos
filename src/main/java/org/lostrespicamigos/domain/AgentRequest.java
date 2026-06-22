package org.lostrespicamigos.domain;

import java.nio.file.Path;
import java.time.Duration;

public record AgentRequest(
        AgentId agent,
        AgentRole role,
        String task,
        Path workingDirectory,
        AccessMode access,
        IsolationMode isolation,
        SessionSpec session,
        Duration timeout,
        boolean includeUntracked,
        boolean allowDangerousPermissions) {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(30);

    public AgentRequest(AgentId agent, AgentRole role, String task, Path workingDirectory, AccessMode access,
                        IsolationMode isolation, SessionSpec session, Duration timeout,
                        boolean allowDangerousPermissions) {
        this(agent, role, task, workingDirectory, access, isolation, session, timeout, false,
                allowDangerousPermissions);
    }

    public AgentRequest {
        if (agent == null) throw new IllegalArgumentException("agent is required");
        role = role == null ? AgentRole.GENERAL : role;
        if (task == null || task.isBlank()) throw new IllegalArgumentException("task is required");
        if (workingDirectory == null || !workingDirectory.isAbsolute()) {
            throw new IllegalArgumentException("workingDirectory must be absolute");
        }
        access = access == null ? (role == AgentRole.IMPLEMENT ? AccessMode.WORKSPACE_WRITE : AccessMode.READ_ONLY) : access;
        isolation = isolation == null ? (role == AgentRole.IMPLEMENT ? IsolationMode.WORKTREE : IsolationMode.SNAPSHOT) : isolation;
        session = session == null ? SessionSpec.fresh() : session;
        timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofHours(2)) > 0) {
            throw new IllegalArgumentException("timeout must be between one second and two hours");
        }
        if (role == AgentRole.REVIEW && access != AccessMode.READ_ONLY) {
            throw new IllegalArgumentException("review role must be read-only");
        }
        if (access == AccessMode.WORKSPACE_WRITE && isolation == IsolationMode.SNAPSHOT) {
            throw new IllegalArgumentException("writable requests cannot use snapshot isolation");
        }
    }
}
