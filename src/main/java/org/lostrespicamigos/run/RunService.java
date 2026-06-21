package org.lostrespicamigos.run;

import org.lostrespicamigos.agent.AgentAdapter;
import org.lostrespicamigos.agent.AgentRegistry;
import org.lostrespicamigos.config.PicamigosConfig;
import org.lostrespicamigos.domain.*;
import org.lostrespicamigos.process.ExecutableResolver;
import org.lostrespicamigos.process.ProcessRunner;
import org.lostrespicamigos.process.ProcessTree;
import org.lostrespicamigos.workspace.GitWorkspaceManager;
import org.lostrespicamigos.workspace.WorkspaceLease;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class RunService implements AutoCloseable {
    private final PicamigosConfig config;
    private final AgentRegistry registry;
    private final ExecutableResolver executableResolver;
    private final ProcessRunner processRunner;
    private final RunStore store;
    private final GitWorkspaceManager workspaceManager;
    private final ExecutorService executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    private final Map<UUID, ActiveRun> active = new ConcurrentHashMap<>();

    public RunService(PicamigosConfig config, AgentRegistry registry, ExecutableResolver executableResolver,
                      ProcessRunner processRunner, RunStore store, GitWorkspaceManager workspaceManager) throws IOException {
        this.config = config;
        this.registry = registry;
        this.executableResolver = executableResolver;
        this.processRunner = processRunner;
        this.store = store;
        this.workspaceManager = workspaceManager;
        this.store.recoverAbandoned();
    }

    public RunRecord start(AgentRequest request) throws IOException {
        validateRequest(request);
        UUID runId = UUID.randomUUID();
        RunRecord record = new RunRecord(runId, ProcessHandle.current().pid(), request.agent(), request.role(), RunStatus.QUEUED,
                request.workingDirectory().toString(), null, null, Instant.now(), null, null,
                null, null, "Queued");
        store.create(record, request);
        ActiveRun activeRun = new ActiveRun();
        active.put(runId, activeRun);
        Future<?> future = executor.submit(() -> execute(record, request, activeRun));
        activeRun.future.set(future);
        return record;
    }

    public Optional<RunRecord> status(UUID runId) {
        return store.load(runId);
    }

    public Optional<AgentResult> result(UUID runId) {
        return store.loadResult(runId);
    }

    public List<RunRecord> list() throws IOException {
        return store.list();
    }

    public boolean cancel(UUID runId) throws IOException {
        Optional<RunRecord> current = store.load(runId);
        if (current.isEmpty() || RunRecord.isTerminal(current.get().status())) return false;
        ActiveRun activeRun = active.get(runId);
        if (activeRun == null) return false;
        activeRun.cancelled.set(true);
        ProcessHandle handle = activeRun.process.get();
        if (handle != null && handle.isAlive()) ProcessTree.terminate(handle, Duration.ofSeconds(2));
        return true;
    }

    public List<AgentHealth> doctor(Path workingDirectory, boolean runSmokeTests) {
        List<AgentHealth> values = new ArrayList<>();
        try {
            Path realWorkingDirectory = workingDirectory.toRealPath();
            Path allowed = config.allowedRoot().toRealPath();
            if (!realWorkingDirectory.startsWith(allowed)) {
                throw new SecurityException("Doctor workingDirectory is outside the configured root: " + allowed);
            }
            workingDirectory = realWorkingDirectory;
        } catch (IOException e) {
            throw new IllegalArgumentException("Doctor workingDirectory is not accessible: " + workingDirectory, e);
        }
        for (AgentAdapter adapter : registry.all()) {
            String configured = config.commands().get(adapter.id());
            ExecutableResolver.Resolution resolution = executableResolver.resolve(configured);
            if (!resolution.available()) {
                values.add(new AgentHealth(adapter.id(), false, configured, null, adapter.capabilities(),
                        resolution.problem(), "Install or repair the CLI, or set PICAMIGOS_" + adapter.id().name() + "_COMMAND"));
                continue;
            }
            Path executable = resolution.executable().orElseThrow();
            try {
                List<String> versionCommand = new ArrayList<>();
                versionCommand.add(executable.toString());
                versionCommand.addAll(adapter.versionArguments());
                ProcessExecutionResult version = processRunner.executeSimple(versionCommand, workingDirectory,
                        Duration.ofSeconds(15), 128 * 1024);
                String versionText = firstNonBlank(version.stdout(), version.stderr());
                boolean available = version.exitCode() == 0;
                String problem = available ? null : "Version command exited with " + version.exitCode() + ": " + version.stderr().strip();
                if (available && runSmokeTests) {
                    AgentRequest smoke = new AgentRequest(adapter.id(), AgentRole.GENERAL,
                            "Reply with exactly PICAMIGOS_OK. Do not inspect or modify files.", workingDirectory.toAbsolutePath(),
                            AccessMode.READ_ONLY, IsolationMode.DIRECT, SessionSpec.fresh(), Duration.ofMinutes(2), false);
                    ProcessExecutionResult executed = processRunner.execute(adapter.buildCommand(executable, smoke, workingDirectory),
                            workingDirectory, smoke.timeout(), config.maxOutputBytes(), ignored -> { }, new AtomicBoolean(false));
                    AgentResult parsed = adapter.parse(executed);
                    if (executed.exitCode() != 0 || !parsed.text().contains("PICAMIGOS_OK")) {
                        available = false;
                        problem = "Smoke test failed: " + firstNonBlank(parsed.text(), executed.stderr());
                    }
                }
                values.add(new AgentHealth(adapter.id(), available, executable.toString(), versionText,
                        adapter.capabilities(), problem, available ? null : "Run the CLI manually and repair authentication or installation"));
            } catch (Exception e) {
                values.add(new AgentHealth(adapter.id(), false, executable.toString(), null, adapter.capabilities(),
                        e.getMessage(), "Run the CLI manually and verify that non-interactive mode works"));
            }
        }
        return List.copyOf(values);
    }

    public String tail(UUID runId, String stream, int maxCharacters) throws IOException {
        return store.tail(runId, stream, maxCharacters);
    }

    public Path artifact(UUID runId, String name) {
        return store.artifact(runId, name);
    }

    private void execute(RunRecord initial, AgentRequest request, ActiveRun activeRun) {
        WorkspaceLease lease = null;
        AtomicReference<RunRecord> latest = new AtomicReference<>(initial);
        try (ProviderLock ignored = ProviderLock.acquire(config.home().resolve("locks"), request.agent())) {
            if (activeRun.cancelled.get()) {
                complete(initial, RunStatus.CANCELLED, null, null, null, null, "Cancelled before launch", null);
                return;
            }
            ExecutableResolver.Resolution resolution = executableResolver.resolve(config.commands().get(request.agent()));
            if (!resolution.available()) throw new IOException(resolution.problem());
            lease = workspaceManager.prepare(request, initial.runId());
            RunRecord running = initial.transition(RunStatus.RUNNING, lease.directory().toString(), lease.branch(),
                    null, null, "Running");
            latest.set(running);
            store.save(running);
            AgentAdapter adapter = registry.get(request.agent());
            ProcessExecutionResult process = processRunner.execute(
                    adapter.buildCommand(resolution.executable().orElseThrow(), request, lease.directory()),
                    lease.directory(), request.timeout(), config.maxOutputBytes(),
                    handle -> {
                        activeRun.process.set(handle);
                        try {
                            RunRecord withProcess = running.transition(RunStatus.RUNNING, null, null, handle.pid(), null, "Running");
                            latest.set(withProcess);
                            store.save(withProcess);
                        } catch (IOException e) {
                            System.err.println("Could not persist process id: " + e.getMessage());
                        }
                    }, activeRun.cancelled,
                    (stream, bytes, length) -> store.appendOutput(initial.runId(), stream, bytes, length));
            AgentResult parsed = adapter.parse(process);
            if (!lease.warnings().isEmpty()) {
                List<String> warnings = new ArrayList<>(parsed.warnings());
                warnings.addAll(lease.warnings());
                parsed = new AgentResult(parsed.text(), parsed.sessionId(), warnings, parsed.rawStdout(), parsed.rawStderr(), parsed.exitCode());
            }
            RunStatus status = process.cancelled() ? RunStatus.CANCELLED
                    : process.timedOut() ? RunStatus.TIMED_OUT
                    : process.exitCode() == 0 ? RunStatus.SUCCEEDED : RunStatus.FAILED;
            complete(latest.get(), status, lease.directory().toString(), lease.branch(), activeRun.process.get(),
                    process.exitCode(), status.name().toLowerCase().replace('_', ' '), parsed);
        } catch (Exception e) {
            try {
                complete(latest.get(), activeRun.cancelled.get() ? RunStatus.CANCELLED : RunStatus.FAILED,
                        lease == null ? null : lease.directory().toString(), lease == null ? null : lease.branch(),
                        activeRun.process.get(), null, e.getMessage(),
                        new AgentResult("", null, List.of(e.getMessage()), "", "", -1));
            } catch (IOException persistenceFailure) {
                System.err.println("Could not persist failed run " + initial.runId() + ": " + persistenceFailure.getMessage());
            }
        } finally {
            if (lease != null) lease.close();
            active.remove(initial.runId());
        }
    }

    private void complete(RunRecord base, RunStatus status, String directory, String branch, ProcessHandle handle,
                          Integer exitCode, String message, AgentResult result) throws IOException {
        if (result != null) store.saveResult(base.runId(), result);
        store.save(base.transition(status, directory, branch, handle == null ? null : handle.pid(), exitCode, message));
    }

    private void validateRequest(AgentRequest request) {
        validateTarget(request.agent());
        if (request.isolation() == IsolationMode.DIRECT && request.access() == AccessMode.WORKSPACE_WRITE
                && !config.allowDirectWrites()) throw new SecurityException("Direct writes are disabled");
        if (request.allowDangerousPermissions() && !config.allowDangerousPermissions()) {
            throw new SecurityException("Dangerous permission bypass is disabled by server configuration");
        }
    }

    public void validateTarget(AgentId agent) {
        int depth = Integer.parseInt(System.getenv().getOrDefault("PICAMIGOS_DELEGATION_DEPTH", "0"));
        if (depth > 0) throw new SecurityException("Nested Picamigos delegation is prohibited");
        if (config.hostAgent() != null && config.hostAgent() == agent && !config.allowSelfInvocation()) {
            throw new SecurityException("Cannot invoke the configured host agent: " + agent.value());
        }
    }

    private String firstNonBlank(String first, String second) {
        String value = first == null || first.isBlank() ? second : first;
        if (value == null) return "";
        String stripped = value.strip();
        return stripped.length() <= 500 ? stripped : stripped.substring(0, 500) + "...";
    }

    @Override
    public void close() {
        for (ActiveRun run : active.values()) {
            run.cancelled.set(true);
            ProcessHandle handle = run.process.get();
            if (handle != null && handle.isAlive()) ProcessTree.terminate(handle, Duration.ofSeconds(1));
        }
        executor.close();
    }

    private static final class ActiveRun {
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final AtomicReference<ProcessHandle> process = new AtomicReference<>();
        final AtomicReference<Future<?>> future = new AtomicReference<>();
    }
}
