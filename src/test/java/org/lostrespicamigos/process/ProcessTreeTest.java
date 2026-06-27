package org.lostrespicamigos.process;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ProcessTreeTest {
    @Test
    void terminatesDescendantProcesses() throws Exception {
        Process process = new ProcessBuilder(fakeCommand(List.of("spawn-child", "10000")))
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        Long childPid = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            childPid = readChildPid(reader);
            ProcessHandle child = ProcessHandle.of(childPid).orElseThrow();
            assertTrue(process.toHandle().isAlive());
            assertTrue(child.isAlive());

            ProcessTree.terminate(process.toHandle(), Duration.ofSeconds(2));

            assertProcessStops(process.toHandle());
            assertProcessStops(child);
        } finally {
            ProcessTree.terminate(process.toHandle(), Duration.ofMillis(200));
            if (childPid != null) {
                ProcessHandle.of(childPid).filter(ProcessHandle::isAlive).ifPresent(ProcessHandle::destroyForcibly);
            }
        }
    }

    private long readChildPid(BufferedReader reader) throws Exception {
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            String line = executor.submit(reader::readLine).get(5, TimeUnit.SECONDS);
            assertNotNull(line, "fake agent did not report a child PID");
            assertTrue(line.startsWith("CHILD:"), line);
            return Long.parseLong(line.substring("CHILD:".length()));
        }
    }

    private void assertProcessStops(ProcessHandle handle) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (handle.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertFalse(handle.isAlive(), "process stayed alive: " + handle.pid());
    }

    private List<String> fakeCommand(List<String> fakeArguments) {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        List<String> command = new ArrayList<>(List.of(java.toString(), "-cp",
                Path.of("target", "test-classes").toAbsolutePath().toString(), FakeAgentMain.class.getName()));
        command.addAll(fakeArguments);
        return command;
    }
}
