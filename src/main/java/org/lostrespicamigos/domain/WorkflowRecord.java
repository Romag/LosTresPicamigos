package org.lostrespicamigos.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WorkflowRecord(
        UUID workflowId,
        long ownerProcessId,
        WorkflowType type,
        WorkflowStatus status,
        String stage,
        List<UUID> runIds,
        Instant createdAt,
        Instant completedAt,
        String message) {

    public WorkflowRecord {
        runIds = runIds == null ? List.of() : List.copyOf(runIds);
    }

    public WorkflowRecord update(WorkflowStatus newStatus, String newStage, List<UUID> newRunIds, String newMessage) {
        return new WorkflowRecord(workflowId, ownerProcessId, type, newStatus, newStage, newRunIds, createdAt,
                newStatus == WorkflowStatus.RUNNING ? null : Instant.now(), newMessage);
    }
}
