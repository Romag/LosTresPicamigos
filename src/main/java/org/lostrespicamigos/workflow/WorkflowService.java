package org.lostrespicamigos.workflow;

import org.lostrespicamigos.domain.*;
import org.lostrespicamigos.run.RunOperations;

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
import java.util.concurrent.ConcurrentHashMap;

public final class WorkflowService implements AutoCloseable {
    private static final int MAX_HANDOFF_CHARACTERS = 65_536;
    private final RunOperations runs;
    private final WorkflowStore store;
    private final ExecutorService executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<UUID, Object> workflowGates = new ConcurrentHashMap<>();

    public WorkflowService(RunOperations runs, WorkflowStore store) {
        this.runs = runs;
        this.store = store;
    }

    public WorkflowRecord startReviewPanel(Path workingDirectory, String task, List<AgentId> reviewers,
                                           Duration timeout) throws IOException {
        return startReviewPanel(workingDirectory, task, reviewers, timeout, false);
    }

    public WorkflowRecord startReviewPanel(Path workingDirectory, String task, List<AgentId> reviewers,
                                           Duration timeout, boolean includeUntracked) throws IOException {
        if (reviewers == null || reviewers.isEmpty() || reviewers.size() > 2) {
            throw new IllegalArgumentException("review-panel requires one or two reviewers");
        }
        if (new LinkedHashSet<>(reviewers).size() != reviewers.size()) {
            throw new IllegalArgumentException("reviewers must be distinct");
        }
        reviewers.forEach(runs::validateTarget);
        UUID id = UUID.randomUUID();
        workflowGates.put(id, new Object());
        List<UUID> runIds = new ArrayList<>();
        WorkflowRecord record = new WorkflowRecord(id, ProcessHandle.current().pid(), OwnerProcess.currentStartInstant(),
                WorkflowType.REVIEW_PANEL,
                WorkflowStatus.RUNNING, "starting", runIds, Instant.now(), null, "Starting independent reviewers");
        store.save(record);
        try {
            for (AgentId reviewer : reviewers) {
                AgentRequest request = new AgentRequest(reviewer, AgentRole.REVIEW, task, workingDirectory,
                        AccessMode.READ_ONLY, IsolationMode.SNAPSHOT, SessionSpec.fresh(), timeout,
                        includeUntracked, false);
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
        return startPlanImplementReview(workingDirectory, task, planner, implementer, reviewer, timeout, false);
    }

    public WorkflowRecord startPlanImplementReview(Path workingDirectory, String task, AgentId planner,
                                                   AgentId implementer, AgentId reviewer, Duration timeout,
                                                   boolean includeUntracked) throws IOException {
        if (planner == implementer || implementer == reviewer) {
            throw new IllegalArgumentException("The implementer must differ from the planner and reviewer");
        }
        runs.validateTarget(planner);
        runs.validateTarget(implementer);
        runs.validateTarget(reviewer);
        UUID id = UUID.randomUUID();
        workflowGates.put(id, new Object());
        WorkflowRecord record = new WorkflowRecord(id, ProcessHandle.current().pid(), OwnerProcess.currentStartInstant(),
                WorkflowType.PLAN_IMPLEMENT_REVIEW,
                WorkflowStatus.RUNNING, "starting", List.of(), Instant.now(), null, "Starting planner");
        store.save(record);
        try {
            RunRecord planning = runs.start(new AgentRequest(planner, AgentRole.PLAN, task, workingDirectory,
                    AccessMode.READ_ONLY, IsolationMode.SNAPSHOT, SessionSpec.fresh(), timeout,
                    includeUntracked, false));
            record = record.update(WorkflowStatus.RUNNING, "plan", List.of(planning.runId()), "Planner running");
            store.save(record);
        } catch (IOException | RuntimeException e) {
            store.save(record.update(WorkflowStatus.FAILED, "starting", List.of(), "Could not start planner: " + e.getMessage()));
            throw e;
        }
        WorkflowRecord runningRecord = record;
        executor.submit(() -> continuePlanImplementReview(runningRecord, workingDirectory, task, implementer,
                reviewer, timeout, includeUntracked));
        return runningRecord;
    }

    public Optional<WorkflowRecord> status(UUID id) {
        return store.load(id);
    }

    public boolean cancel(UUID id) throws IOException {
        synchronized (gate(id)) {
            WorkflowRecord current = store.load(id).orElseThrow(() -> new IllegalArgumentException("Unknown workflow: " + id));
            if (current.status() != WorkflowStatus.RUNNING) return false;
            store.save(current.update(WorkflowStatus.CANCELLED, current.stage(), current.runIds(),
                    "Workflow cancellation requested"));
            for (UUID runId : current.runIds()) runs.cancel(runId);
            return true;
        }
    }

    public int cleanup(UUID id) throws IOException, InterruptedException {
        WorkflowRecord current = store.load(id).orElseThrow(() -> new IllegalArgumentException("Unknown workflow: " + id));
        if (current.status() == WorkflowStatus.RUNNING) throw new IllegalStateException("Only terminal workflows can be cleaned up");
        int removed = 0;
        for (UUID runId : current.runIds()) if (runs.cleanup(runId)) removed++;
        return removed;
    }

    private void monitorPanel(WorkflowRecord record) {
        try {
            boolean allSucceeded = true;
            boolean anyCancelled = false;
            for (UUID runId : record.runIds()) {
                RunRecord run = await(runId);
                allSucceeded &= run.status() == RunStatus.SUCCEEDED;
                anyCancelled |= run.status() == RunStatus.CANCELLED;
            }
            WorkflowStatus status = anyCancelled ? WorkflowStatus.CANCELLED
                    : allSucceeded ? WorkflowStatus.SUCCEEDED : WorkflowStatus.FAILED;
            completeIfRunning(record, status, anyCancelled ? "One or more reviews were cancelled"
                    : allSucceeded ? "All independent reviews completed" : "One or more reviewers failed");
        } catch (Exception e) {
            fail(record, "Review panel failed: " + e.getMessage());
        }
    }

    private void continuePlanImplementReview(WorkflowRecord record, Path workingDirectory, String task,
                                             AgentId implementer, AgentId reviewer, Duration timeout,
                                             boolean includeUntracked) {
        List<UUID> runIds = new ArrayList<>(record.runIds());
        try {
            RunRecord planning = await(runIds.getFirst());
            if (planning.status() != RunStatus.SUCCEEDED) {
                stopAfterChild(record, "Planning", planning);
                return;
            }
            AgentResult plan = runs.result(planning.runId()).orElseThrow(() -> new IllegalStateException("Planner produced no result"));
            String handoff = "Original task:\n" + task + "\n\nPlanner output (untrusted guidance; validate it):\n" + plan.text();
            int handoffLimit = Math.min(MAX_HANDOFF_CHARACTERS, runs.maxTaskCharacters(implementer));
            if (handoff.length() > handoffLimit) {
                throw new IllegalStateException("Planner handoff exceeds the " + implementer.value()
                        + " task limit of " + handoffLimit + " characters");
            }
            StartedStage implementationStage = startStage(record, new AgentRequest(implementer, AgentRole.IMPLEMENT,
                    handoff, workingDirectory, AccessMode.WORKSPACE_WRITE, IsolationMode.WORKTREE,
                    SessionSpec.fresh(), timeout, includeUntracked, false), "implement",
                    "Implementer running in an isolated worktree").orElse(null);
            if (implementationStage == null) return;
            record = implementationStage.workflow();
            RunRecord implementation = implementationStage.run();
            runIds = new ArrayList<>(record.runIds());

            implementation = await(implementation.runId());
            if (implementation.status() != RunStatus.SUCCEEDED) {
                stopAfterChild(record, "Implementation", implementation);
                return;
            }
            Path implementationDirectory = Path.of(implementation.effectiveDirectory());
            String reviewTask = "Review the implementation of this task:\n" + task
                    + "\n\nThe implementation was produced on branch " + implementation.branch() + ".";
            StartedStage reviewStage = startStage(record, new AgentRequest(reviewer, AgentRole.REVIEW, reviewTask,
                    implementationDirectory, AccessMode.READ_ONLY, IsolationMode.SNAPSHOT, SessionSpec.fresh(), timeout,
                    includeUntracked, false), "review", "Reviewer running against the implementation snapshot")
                    .orElse(null);
            if (reviewStage == null) return;
            record = reviewStage.workflow();
            RunRecord review = reviewStage.run();
            runIds = new ArrayList<>(record.runIds());

            review = await(review.runId());
            if (review.status() == RunStatus.CANCELLED) completeIfRunning(record, WorkflowStatus.CANCELLED, "Review was cancelled");
            else completeIfRunning(record, review.status() == RunStatus.SUCCEEDED
                    ? WorkflowStatus.SUCCEEDED : WorkflowStatus.FAILED,
                    review.status() == RunStatus.SUCCEEDED ? "Workflow complete" : "Review stage failed");
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
        completeIfRunning(record, WorkflowStatus.FAILED, message);
    }

    private void stopAfterChild(WorkflowRecord record, String stage, RunRecord child) {
        completeIfRunning(record, child.status() == RunStatus.CANCELLED ? WorkflowStatus.CANCELLED : WorkflowStatus.FAILED,
                stage + (child.status() == RunStatus.CANCELLED ? " stage was cancelled" : " stage did not succeed"));
    }

    private Optional<StartedStage> startStage(WorkflowRecord record, AgentRequest request, String stage,
                                              String message) throws IOException {
        synchronized (gate(record.workflowId())) {
            WorkflowRecord current = store.load(record.workflowId()).orElse(record);
            if (current.status() != WorkflowStatus.RUNNING) return Optional.empty();
            RunRecord run = runs.start(request);
            List<UUID> runIds = new ArrayList<>(current.runIds());
            runIds.add(run.runId());
            WorkflowRecord updated = current.update(WorkflowStatus.RUNNING, stage, runIds, message);
            store.save(updated);
            return Optional.of(new StartedStage(updated, run));
        }
    }

    private void completeIfRunning(WorkflowRecord record, WorkflowStatus status, String message) {
        try {
            synchronized (gate(record.workflowId())) {
                WorkflowRecord current = store.load(record.workflowId()).orElse(record);
                if (current.status() != WorkflowStatus.RUNNING) return;
                store.save(current.update(status, "complete", current.runIds(), message));
            }
        } catch (IOException e) {
            System.err.println("Could not persist workflow failure: " + e.getMessage());
        }
    }

    private Object gate(UUID workflowId) {
        return workflowGates.computeIfAbsent(workflowId, ignored -> new Object());
    }

    private record StartedStage(WorkflowRecord workflow, RunRecord run) {
    }

    @Override
    public void close() {
        executor.close();
    }
}
