package org.lostrespicamigos.retention;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lostrespicamigos.config.JsonSupport;
import org.lostrespicamigos.config.PicamigosConfig;
import org.lostrespicamigos.domain.*;
import org.lostrespicamigos.run.RunStore;
import org.lostrespicamigos.workflow.WorkflowStore;
import org.lostrespicamigos.workspace.GitWorkspaceManager;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetentionServiceTest {
    @TempDir Path temporary;

    @Test
    void removesOnlyExpiredTerminalRecords() throws Exception {
        Instant now = Instant.parse("2026-06-22T00:00:00Z");
        PicamigosConfig config = new PicamigosConfig(temporary.resolve("home"), temporary, null,
                Map.of(AgentId.CODEX, "codex", AgentId.CLAUDE, "claude", AgentId.ANTIGRAVITY, "agy"),
                false, false, false, 1024, 7);
        RunStore runs = new RunStore(config.home(), JsonSupport.createMapper());
        WorkflowStore workflows = new WorkflowStore(config.home(), JsonSupport.createMapper());
        UUID expiredRun = saveRun(runs, now.minus(Duration.ofDays(8)), RunStatus.SUCCEEDED);
        UUID recentRun = saveRun(runs, now.minus(Duration.ofDays(1)), RunStatus.SUCCEEDED);
        UUID activeRun = saveRun(runs, now.minus(Duration.ofDays(8)), RunStatus.RUNNING);
        UUID expiredWorkflow = saveWorkflow(workflows, now.minus(Duration.ofDays(8)), WorkflowStatus.SUCCEEDED);
        UUID activeWorkflow = saveWorkflow(workflows, now.minus(Duration.ofDays(8)), WorkflowStatus.RUNNING);

        RetentionService.CleanupReport report = new RetentionService(config, runs, workflows,
                new GitWorkspaceManager(config)).purgeExpired(now);

        assertEquals(1, report.removedRuns());
        assertEquals(1, report.removedWorkflows());
        assertEquals(0, report.failures());
        assertFalse(runs.load(expiredRun).isPresent());
        assertTrue(runs.load(recentRun).isPresent());
        assertTrue(runs.load(activeRun).isPresent());
        assertFalse(workflows.load(expiredWorkflow).isPresent());
        assertTrue(workflows.load(activeWorkflow).isPresent());
    }

    private UUID saveRun(RunStore store, Instant timestamp, RunStatus status) throws Exception {
        UUID id = UUID.randomUUID();
        AgentRequest request = new AgentRequest(AgentId.CLAUDE, AgentRole.GENERAL, "task", temporary,
                AccessMode.READ_ONLY, IsolationMode.DIRECT, SessionSpec.fresh(), Duration.ofMinutes(1), false);
        Instant completed = RunRecord.isTerminal(status) ? timestamp : null;
        RunRecord record = new RunRecord(id, ProcessHandle.current().pid(), AgentId.CLAUDE, AgentRole.GENERAL,
                status, temporary.toString(), null, null, timestamp, timestamp, completed, null, 0, status.name());
        store.create(record, request);
        return id;
    }

    private UUID saveWorkflow(WorkflowStore store, Instant timestamp, WorkflowStatus status) throws Exception {
        UUID id = UUID.randomUUID();
        Instant completed = status == WorkflowStatus.RUNNING ? null : timestamp;
        store.save(new WorkflowRecord(id, ProcessHandle.current().pid(), WorkflowType.REVIEW_PANEL, status,
                "complete", List.of(), timestamp, completed, status.name()));
        return id;
    }
}
