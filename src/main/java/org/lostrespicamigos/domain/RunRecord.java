package org.lostrespicamigos.domain;

import java.time.Instant;
import java.util.UUID;

public record RunRecord(
        UUID runId,
        long ownerProcessId,
        AgentId agent,
        AgentRole role,
        RunStatus status,
        String workingDirectory,
        String effectiveDirectory,
        String branch,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        Long processId,
        Integer exitCode,
        String message) {

    public RunRecord transition(RunStatus newStatus, String newEffectiveDirectory, String newBranch,
                                Long newProcessId, Integer newExitCode, String newMessage) {
        Instant now = Instant.now();
        return new RunRecord(runId, ownerProcessId, agent, role, newStatus, workingDirectory,
                newEffectiveDirectory == null ? effectiveDirectory : newEffectiveDirectory,
                newBranch == null ? branch : newBranch,
                createdAt,
                startedAt == null && newStatus == RunStatus.RUNNING ? now : startedAt,
                isTerminal(newStatus) ? now : completedAt,
                newProcessId == null ? processId : newProcessId,
                newExitCode,
                newMessage);
    }

    public static boolean isTerminal(RunStatus status) {
        return status == RunStatus.SUCCEEDED || status == RunStatus.FAILED || status == RunStatus.TIMED_OUT
                || status == RunStatus.CANCELLED || status == RunStatus.ABORTED;
    }
}
