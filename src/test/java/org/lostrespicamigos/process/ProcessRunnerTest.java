package org.lostrespicamigos.process;

import org.junit.jupiter.api.Test;
import org.lostrespicamigos.domain.AgentCommand;
import org.lostrespicamigos.domain.PromptTransport;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ProcessRunnerTest {
    private final ProcessRunner runner = new ProcessRunner();

    @Test
    void drainsBothStreamsAndWritesPromptToStdin() throws Exception {
        AgentCommand command = fakeCommand(List.of("echo"), PromptTransport.STDIN, "hello");
        java.io.ByteArrayOutputStream liveStdout = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream liveStderr = new java.io.ByteArrayOutputStream();
        var result = runner.execute(command, Path.of("").toAbsolutePath(), Duration.ofSeconds(10),
                1024, ignored -> { }, new AtomicBoolean(false), (stream, bytes, length) -> {
                    if (stream == ProcessRunner.Stream.STDOUT) liveStdout.write(bytes, 0, length);
                    else liveStderr.write(bytes, 0, length);
                });

        assertEquals(0, result.exitCode());
        assertEquals("OUT:hello", result.stdout());
        assertEquals("progress", result.stderr());
        assertEquals(result.stdout(), liveStdout.toString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(result.stderr(), liveStderr.toString(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void reportsTimeoutAndTerminatesProcess() throws Exception {
        AgentCommand command = fakeCommand(List.of("sleep", "10000"), PromptTransport.STDIN, "");
        var result = runner.execute(command, Path.of("").toAbsolutePath(), Duration.ofMillis(100),
                1024, ignored -> { }, new AtomicBoolean(false));

        assertTrue(result.timedOut());
    }

    @Test
    void boundsOutputWithoutAllocatingTheWholeStream() throws Exception {
        AgentCommand command = fakeCommand(List.of("flood", "50000"), PromptTransport.STDIN, "");
        var result = runner.execute(command, Path.of("").toAbsolutePath(), Duration.ofSeconds(10),
                1000, ignored -> { }, new AtomicBoolean(false));

        assertTrue(result.outputLimitExceeded());
        assertEquals(1000, result.stdout().length());
    }

    private AgentCommand fakeCommand(List<String> fakeArguments, PromptTransport transport, String prompt) {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        List<String> command = new ArrayList<>(List.of(java.toString(), "-cp",
                Path.of("target", "test-classes").toAbsolutePath().toString(), FakeAgentMain.class.getName()));
        command.addAll(fakeArguments);
        return new AgentCommand(command, transport, prompt, Map.of());
    }
}
