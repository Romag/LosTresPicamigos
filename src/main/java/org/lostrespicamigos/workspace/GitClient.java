package org.lostrespicamigos.workspace;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

final class GitClient {
    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    Result run(Path directory, List<String> arguments) throws IOException, InterruptedException {
        return run(directory, arguments, null);
    }

    Result run(Path directory, List<String> arguments, byte[] stdin) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(arguments);
        Process process = new ProcessBuilder(command).directory(directory.toFile()).start();
        if (stdin == null) process.getOutputStream().close();
        else try (var output = process.getOutputStream()) { output.write(stdin); }

        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            Future<byte[]> stdout = executor.submit(() -> read(process.getInputStream()));
            Future<byte[]> stderr = executor.submit(() -> read(process.getErrorStream()));
            if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                org.lostrespicamigos.process.ProcessTree.terminate(process.toHandle(), Duration.ofSeconds(1));
                throw new IOException("Git command timed out: " + String.join(" ", arguments));
            }
            try {
                return new Result(process.exitValue(), stdout.get(), new String(stderr.get(), StandardCharsets.UTF_8));
            } catch (java.util.concurrent.ExecutionException e) {
                throw new IOException("Failed to read Git output", e.getCause());
            }
        }
    }

    Result require(Path directory, List<String> arguments) throws IOException, InterruptedException {
        Result result = run(directory, arguments);
        if (result.exitCode() != 0) throw new IOException("Git command failed: " + result.stderr().strip());
        return result;
    }

    private byte[] read(java.io.InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        input.transferTo(output);
        return output.toByteArray();
    }

    record Result(int exitCode, byte[] stdout, String stderr) {
        String stdoutText() { return new String(stdout, StandardCharsets.UTF_8); }
    }
}
