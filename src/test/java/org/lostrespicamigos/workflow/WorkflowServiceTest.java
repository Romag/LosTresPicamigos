package org.lostrespicamigos.workflow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lostrespicamigos.config.JsonSupport;
import org.lostrespicamigos.domain.*;
import org.lostrespicamigos.run.RunOperations;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowServiceTest {
    @TempDir Path temporary;

    @Test
    void propagatesAnExternallyCancelledChildRun() throws Exception {
        FakeRuns runs = new FakeRuns();
        WorkflowStore store = new WorkflowStore(temporary, JsonSupport.createMapper());
        try (WorkflowService workflows = new WorkflowService(runs, store)) {
            WorkflowRecord workflow = workflows.startReviewPanel(temporary, "Review", List.of(AgentId.CLAUDE),
                    Duration.ofMinutes(1));

            runs.finish(workflow.runIds().getFirst(), RunStatus.CANCELLED);

            WorkflowRecord completed = awaitTerminal(workflows, workflow.workflowId());
            assertEquals(WorkflowStatus.CANCELLED, completed.status());
            assertTrue(completed.message().contains("cancelled"));
        }
    }

    @Test
    void cancelsChildRunsWithoutMonitorOverwrite() throws Exception {
        FakeRuns runs = new FakeRuns();
        WorkflowStore store = new WorkflowStore(temporary, JsonSupport.createMapper());
        try (WorkflowService workflows = new WorkflowService(runs, store)) {
            WorkflowRecord workflow = workflows.startReviewPanel(temporary, "Review", List.of(AgentId.CLAUDE),
                    Duration.ofMinutes(1));

            assertTrue(workflows.cancel(workflow.workflowId()));
            Thread.sleep(400);

            assertEquals(WorkflowStatus.CANCELLED, workflows.status(workflow.workflowId()).orElseThrow().status());
            assertEquals(1, workflows.cleanup(workflow.workflowId()));
        }
    }

    private WorkflowRecord awaitTerminal(WorkflowService workflows, UUID id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            WorkflowRecord record = workflows.status(id).orElseThrow();
            if (record.status() != WorkflowStatus.RUNNING) return record;
            Thread.sleep(25);
        }
        throw new AssertionError("Workflow did not reach a terminal state");
    }

    private static final class FakeRuns implements RunOperations {
        private final ConcurrentHashMap<UUID, RunRecord> records = new ConcurrentHashMap<>();

        @Override
        public RunRecord start(AgentRequest request) {
            UUID id = UUID.randomUUID();
            RunRecord record = new RunRecord(id, ProcessHandle.current().pid(), OwnerProcess.currentStartInstant(),
                    request.agent(), request.role(), RunStatus.RUNNING, request.workingDirectory().toString(),
                    request.workingDirectory().toString(), null, Instant.now(), Instant.now(), null,
                    null, null, "Running");
            records.put(id, record);
            return record;
        }

        @Override
        public Optional<RunRecord> status(UUID runId) {
            return Optional.ofNullable(records.get(runId));
        }

        @Override
        public Optional<AgentResult> result(UUID runId) {
            return Optional.empty();
        }

        @Override
        public boolean cancel(UUID runId) {
            return finish(runId, RunStatus.CANCELLED);
        }

        @Override
        public boolean cleanup(UUID runId) {
            return records.containsKey(runId);
        }

        @Override
        public void validateTarget(AgentId agent) {
        }

        @Override
        public int maxTaskCharacters(AgentId agent) {
            return Integer.MAX_VALUE;
        }

        boolean finish(UUID id, RunStatus status) {
            return records.computeIfPresent(id, (ignored, record) -> record.transition(status, null, null,
                    null, status == RunStatus.SUCCEEDED ? 0 : -1, status.name())).status() == status;
        }
    }
}
