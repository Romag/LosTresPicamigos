package org.lostrespicamigos.retention;

import org.lostrespicamigos.config.PicamigosConfig;
import org.lostrespicamigos.domain.RunRecord;
import org.lostrespicamigos.domain.WorkflowRecord;
import org.lostrespicamigos.domain.WorkflowStatus;
import org.lostrespicamigos.run.RunStore;
import org.lostrespicamigos.workflow.WorkflowStore;
import org.lostrespicamigos.workspace.GitWorkspaceManager;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

public final class RetentionService {
    private final Duration retention;
    private final RunStore runs;
    private final WorkflowStore workflows;
    private final GitWorkspaceManager workspaces;

    public RetentionService(PicamigosConfig config, RunStore runs, WorkflowStore workflows,
                            GitWorkspaceManager workspaces) {
        this.retention = Duration.ofDays(config.retentionDays());
        this.runs = runs;
        this.workflows = workflows;
        this.workspaces = workspaces;
    }

    public CleanupReport purgeExpired(Instant now) {
        Instant cutoff = now.minus(retention);
        int removedRuns = 0;
        int removedWorkflows = 0;
        int removedWorktrees = 0;
        int failures = 0;
        try {
            for (RunRecord run : runs.list()) {
                if (!RunRecord.isTerminal(run.status()) || run.completedAt() == null || !run.completedAt().isBefore(cutoff)) continue;
                try {
                    if (run.effectiveDirectory() != null) {
                        workspaces.removeManagedWorktree(Path.of(run.effectiveDirectory()));
                    }
                    runs.delete(run.runId());
                    removedRuns++;
                } catch (Exception e) {
                    failures++;
                    System.err.println("Picamigos retention preserved run " + run.runId() + ": " + e.getMessage());
                }
            }
            for (WorkflowRecord workflow : workflows.list()) {
                if (workflow.status() == WorkflowStatus.RUNNING || workflow.completedAt() == null
                        || !workflow.completedAt().isBefore(cutoff)) continue;
                try {
                    workflows.delete(workflow.workflowId());
                    removedWorkflows++;
                } catch (Exception e) {
                    failures++;
                    System.err.println("Picamigos retention preserved workflow " + workflow.workflowId() + ": " + e.getMessage());
                }
            }
            for (Path worktree : workspaces.expiredManagedWorktrees(cutoff)) {
                try {
                    if (workspaces.removeManagedWorktree(worktree)) removedWorktrees++;
                } catch (Exception e) {
                    failures++;
                    System.err.println("Picamigos retention preserved worktree " + worktree + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            failures++;
            System.err.println("Picamigos retention scan failed: " + e.getMessage());
        }
        return new CleanupReport(removedRuns, removedWorkflows, removedWorktrees, failures);
    }

    public record CleanupReport(int removedRuns, int removedWorkflows, int removedWorktrees, int failures) {
    }
}
