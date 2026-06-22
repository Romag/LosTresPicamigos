package org.lostrespicamigos.workflow;

import org.lostrespicamigos.domain.*;
import org.lostrespicamigos.run.RunService;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

public final class WorkflowService implements AutoCloseable {
    private static final int MAX_HANDOFF_CHARACTERS = 65_536;
    private final RunService runs;
    private final WorkflowStore store;
    private final ExecutorService executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    public WorkflowService(RunService runs, WorkflowStore store) {
        this.runs = runs;
        this.store = store;
    }

    public WorkflowRecord startReviewPanel(Path workingDirectory, String task, List<AgentId> reviewers,
                                           Duration timeout) throws IOException {
        if (reviewers == null || reviewers.isEmpty() || reviewers.size() > 2) {
            throw new IllegalArgumentException("review-panel requires one or two reviewers");
        }
        if (new LinkedHashSet<>(reviewers).size() != reviewers.size()) {
            throw new IllegalArgumentException("reviewers must be distinct");
        }
        reviewers.forEach(runs::validateTarget);
        UUID id = UUID.randomUUID();
        List<UUID> runIds = new ArrayList<>();
        WorkflowRecord record = new WorkflowRecord(id, ProcessHandle.current().pid(), OwnerProcess.currentStartInstant(),
                WorkflowType.REVIEW_PANEL,
                WorkflowStatus.RUNNING, "starting", runIds, Instant.now(), null, "Starting independent reviewers");
        store.save(record);
        try {
            for (AgentId reviewer : reviewers) {
                AgentRequest request = new AgentRequest(reviewer, AgentRole.REVIEW, task, workingDirectory,
                        AccessMode.READ_ONLY, IsolationMode.SNAPSHOT, SessionSpec.fresh(), timeout, false);
                runIds.add(runs.start(request).runId());
                record = record.update(WorkflowStatus.RUNNING, "review", runIds, "Reviewers running independently");
                store.save(record);
            }
        } catch (IOException | RuntimeException e) {
            store.save(record.update(WorkflowStatus.FAILED, "starting", runIds, "Could not start every reviewer: " + e.getMessage()));
            throw e;
        }
        WorkflowRecord runningRecord = record;
        executor.submit(() -> monitorPanel(runningRecord));
        return runningRecord;
    }

    public WorkflowRecord startPlanImplementReview(Path workingDirectory, String task, AgentId planner,
                                                   AgentId implementer, AgentId reviewer, Duration timeout) throws IOException {
        if (planner == implementer || implementer == reviewer) {
            throw new IllegalArgumentException("The implementer must differ from the planner and reviewer");
        }
        runs.validateTarget(planner);
        runs.validateTarget(implementer);
        runs.validateTarget(reviewer);
        UUID id = UUID.randomUUID();
        WorkflowRecord record = new WorkflowRecord(id, ProcessHandle.current().pid(), OwnerProcess.currentStartInstant(),
                WorkflowType.PLAN_IMPLEMENT_REVIEW,
                WorkflowStatus.RUNNING, "starting", List.of(), Instant.now(), null, "Starting planner");
        store.save(record);
        try {
            RunRecord planning = runs.start(new AgentRequest(planner, AgentRole.PLAN, task, workingDirectory,
                    AccessMode.READ_ONLY, IsolationMode.SNAPSHOT, SessionSpec.fresh(), timeout, false));
            record = record.update(WorkflowStatus.RUNNING, "plan", List.of(planning.runId()), "Planner running");
            store.save(record);
        } catch (IOException | RuntimeException e) {
            store.save(record.update(WorkflowStatus.FAILED, "starting", List.of(), "Could not start planner: " + e.getMessage()));
            throw e;
        }
        WorkflowRecord runningRecord = record;
        executor.submit(() -> continuePlanImplementReview(runningRecord, workingDirectory, task, implementer, reviewer, timeout));
        return runningRecord;
    }

    public Optional<WorkflowRecord> status(UUID id) {
        return store.load(id);
    }

    private void monitorPanel(WorkflowRecord record) {
        try {
            boolean allSucceeded = true;
            for (UUID runId : record.runIds()) {
                RunRecord run = await(runId);
                allSucceeded &= run.status() == RunStatus.SUCCEEDED;
            }
            store.save(record.update(allSucceeded ? WorkflowStatus.SUCCEEDED : WorkflowStatus.FAILED,
                    "complete", record.runIds(), allSucceeded ? "All independent reviews completed" : "One or more reviewers failed"));
        } catch (Exception e) {
            fail(record, "Review panel failed: " + e.getMessage());
        }
    }

    private void continuePlanImplementReview(WorkflowRecord record, Path workingDirectory, String task,
                                             AgentId implementer, AgentId reviewer, Duration timeout) {
        List<UUID> runIds = new ArrayList<>(record.runIds());
        try {
            RunRecord planning = await(runIds.getFirst());
            if (planning.status() != RunStatus.SUCCEEDED) {
                fail(record, "Planning stage did not succeed");
                return;
            }
            AgentResult plan = runs.result(planning.runId()).orElseThrow(() -> new IllegalStateException("Planner produced no result"));
            String handoff = "Original task:\n" + task + "\n\nPlanner output (untrusted guidance; validate it):\n" + plan.text();
            int handoffLimit = Math.min(MAX_HANDOFF_CHARACTERS, runs.maxTaskCharacters(implementer));
            if (handoff.length() > handoffLimit) {
                throw new IllegalStateException("Planner handoff exceeds the " + implementer.value()
                        + " task limit of " + handoffLimit + " characters");
            }
            RunRecord implementation = runs.start(new AgentRequest(implementer, AgentRole.IMPLEMENT, handoff,
                    workingDirectory, AccessMode.WORKSPACE_WRITE, IsolationMode.WORKTREE, SessionSpec.fresh(), timeout, false));
            runIds.add(implementation.runId());
            record = record.update(WorkflowStatus.RUNNING, "implement", runIds, "Implementer running in an isolated worktree");
            store.save(record);

            implementation = await(implementation.runId());
            if (implementation.status() != RunStatus.SUCCEEDED) {
                fail(record, "Implementation stage did not succeed");
                return;
            }
            Path implementationDirectory = Path.of(implementation.effectiveDirectory());
            String reviewTask = "Review the implementation of this task:\n" + task
                    + "\n\nThe implementation was produced on branch " + implementation.branch() + ".";
            RunRecord review = runs.start(new AgentRequest(reviewer, AgentRole.REVIEW, reviewTask,
                    implementationDirectory, AccessMode.READ_ONLY, IsolationMode.SNAPSHOT, SessionSpec.fresh(), timeout, false));
            runIds.add(review.runId());
            record = record.update(WorkflowStatus.RUNNING, "review", runIds, "Reviewer running against the implementation snapshot");
            store.save(record);

            review = await(review.runId());
            store.save(record.update(review.status() == RunStatus.SUCCEEDED ? WorkflowStatus.SUCCEEDED : WorkflowStatus.FAILED,
                    "complete", runIds, review.status() == RunStatus.SUCCEEDED ? "Workflow complete" : "Review stage failed"));
        } catch (Exception e) {
            fail(record, "Workflow failed: " + e.getMessage());
        }
    }

    private RunRecord await(UUID runId) throws InterruptedException {
        while (true) {
            RunRecord record = runs.status(runId).orElseThrow(() -> new IllegalStateException("Run disappeared: " + runId));
            if (RunRecord.isTerminal(record.status())) return record;
            Thread.sleep(250);
        }
    }

    private void fail(WorkflowRecord record, String message) {
        try {
            WorkflowRecord current = store.load(record.workflowId()).orElse(record);
            store.save(current.update(WorkflowStatus.FAILED, current.stage(), current.runIds(), message));
        } catch (IOException e) {
            System.err.println("Could not persist workflow failure: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        executor.close();
    }
}
