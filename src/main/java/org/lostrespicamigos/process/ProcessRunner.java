package org.lostrespicamigos.process;

import org.lostrespicamigos.domain.AgentCommand;
import org.lostrespicamigos.domain.ProcessExecutionResult;
import org.lostrespicamigos.domain.PromptTransport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class ProcessRunner {
    private static final Set<String> SAFE_SECRET_NAMES = Set.of();

    public ProcessExecutionResult execute(AgentCommand agentCommand, Path workingDirectory, Duration timeout,
                                          long maxOutputBytes, Consumer<ProcessHandle> onStart,
                                          AtomicBoolean cancelled) throws IOException, InterruptedException {
        return execute(agentCommand, workingDirectory, timeout, maxOutputBytes, onStart, cancelled, OutputListener.NOOP);
    }

    public ProcessExecutionResult execute(AgentCommand agentCommand, Path workingDirectory, Duration timeout,
                                          long maxOutputBytes, Consumer<ProcessHandle> onStart,
                                          AtomicBoolean cancelled, OutputListener outputListener)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(agentCommand.command());
        if (agentCommand.promptTransport() == PromptTransport.ARGUMENT) command.add(agentCommand.prompt());

        ProcessBuilder builder = new ProcessBuilder(command).directory(workingDirectory.toFile());
        sanitizeEnvironment(builder.environment());
        builder.environment().putAll(agentCommand.environment());
        int depth = Integer.parseInt(System.getenv().getOrDefault("PICAMIGOS_DELEGATION_DEPTH", "0"));
        builder.environment().put("PICAMIGOS_DELEGATION_DEPTH", Integer.toString(depth + 1));

        Instant started = Instant.now();
        Process process = builder.start();
        onStart.accept(process.toHandle());
        if (cancelled.get() && process.isAlive()) {
            ProcessTree.terminate(process.toHandle(), Duration.ofSeconds(2));
        }

        if (agentCommand.promptTransport() == PromptTransport.STDIN) {
            try (var stdin = process.getOutputStream()) {
                stdin.write(agentCommand.prompt().getBytes(StandardCharsets.UTF_8));
            }
        } else {
            process.getOutputStream().close();
        }

        AtomicBoolean outputLimitExceeded = new AtomicBoolean(false);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> stdout = executor.submit(() -> readBounded(process.getInputStream(), maxOutputBytes,
                    outputLimitExceeded, (bytes, length) -> outputListener.onOutput(Stream.STDOUT, bytes, length)));
            Future<String> stderr = executor.submit(() -> readBounded(process.getErrorStream(), maxOutputBytes,
                    outputLimitExceeded, (bytes, length) -> outputListener.onOutput(Stream.STDERR, bytes, length)));
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            boolean timedOut = !finished && !cancelled.get();
            if (!finished) ProcessTree.terminate(process.toHandle(), Duration.ofSeconds(2));
            String out = getFuture(stdout);
            String err = getFuture(stderr);
            int exitCode = process.isAlive() ? -1 : process.exitValue();
            return new ProcessExecutionResult(exitCode, out, err, Duration.between(started, Instant.now()),
                    timedOut, cancelled.get(), outputLimitExceeded.get());
        }
    }

    public ProcessExecutionResult executeSimple(List<String> command, Path workingDirectory, Duration timeout,
                                                long maxOutputBytes) throws IOException, InterruptedException {
        return execute(new AgentCommand(command, PromptTransport.STDIN, "", Map.of()), workingDirectory, timeout,
                maxOutputBytes, ignored -> { }, new AtomicBoolean(false));
    }

    private String readBounded(InputStream stream, long limit, AtomicBoolean exceeded, ByteListener listener) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(limit, 64 * 1024));
        byte[] buffer = new byte[8192];
        long seen = 0;
        int read;
        while ((read = stream.read(buffer)) >= 0) {
            long remaining = limit - seen;
            if (remaining > 0) {
                int retained = (int) Math.min(read, remaining);
                output.write(buffer, 0, retained);
                listener.onBytes(buffer, retained);
            }
            seen += read;
            if (seen > limit) exceeded.set(true);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private String getFuture(Future<String> future) throws InterruptedException, IOException {
        try {
            return future.get();
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof IOException io) throw io;
            throw new IOException("Failed to collect process output", e.getCause());
        }
    }

    private void sanitizeEnvironment(Map<String, String> environment) {
        environment.keySet().removeIf(name -> {
            String upper = name.toUpperCase(Locale.ROOT);
            if (SAFE_SECRET_NAMES.contains(upper)) return false;
            return upper.contains("TOKEN") || upper.contains("SECRET") || upper.contains("PASSWORD")
                    || upper.contains("API_KEY") || upper.contains("ACCESS_KEY") || upper.equals("GITHUB_TOKEN");
        });
    }

    public enum Stream { STDOUT, STDERR }

    @FunctionalInterface
    public interface OutputListener {
        OutputListener NOOP = (stream, bytes, length) -> { };
        void onOutput(Stream stream, byte[] bytes, int length) throws IOException;
    }

    @FunctionalInterface
    private interface ByteListener {
        void onBytes(byte[] bytes, int length) throws IOException;
    }
}
