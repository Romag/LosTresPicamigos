package org.lostrespicamigos.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lostrespicamigos.config.JsonSupport;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class McpStdioIT {
    @TempDir Path temporary;

    @Test
    void initializesAndListsTheExpectedToolsOverRealStdio() throws Exception {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        Path jar = Path.of("target", "picamigos-mcp-0.1.0-SNAPSHOT-all.jar").toAbsolutePath();
        Path root = Path.of("").toAbsolutePath();
        Process process = new ProcessBuilder(javaExecutable.toString(), "-jar", jar.toString(), "--host", "codex",
                "--home", temporary.toString(), "--root", root.toString()).start();
        StringBuilder stderr = new StringBuilder();
        Thread stderrReader = Thread.ofVirtual().start(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                reader.lines().forEach(line -> stderr.append(line).append('\n'));
            } catch (Exception ignored) {
            }
        });

        ObjectMapper mapper = JsonSupport.createMapper();
        try (var writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
             var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
             var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            send(writer, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"integration-test\",\"version\":\"1.0\"}}}");
            JsonNode initialize = readLine(reader, executor, Duration.ofSeconds(10), stderr);
            assertEquals(1, initialize.path("id").asInt());
            assertEquals("los-tres-picamigos", initialize.path("result").path("serverInfo").path("name").asText());

            send(writer, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}");
            send(writer, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
            JsonNode tools = readLine(reader, executor, Duration.ofSeconds(10), stderr);
            assertEquals(2, tools.path("id").asInt(), tools.toPrettyString());
            Set<String> names = java.util.stream.StreamSupport.stream(tools.path("result").path("tools").spliterator(), false)
                    .map(node -> node.path("name").asText()).collect(Collectors.toSet());
            assertTrue(names.containsAll(Set.of("picamigos_doctor", "picamigos_delegate", "picamigos_run_status",
                    "picamigos_run_result", "picamigos_cancel_run", "picamigos_list_runs",
                    "picamigos_cleanup_run", "picamigos_start_workflow", "picamigos_workflow_status")), names.toString());

            String escapedRoot = mapper.writeValueAsString(root.toString());
            send(writer, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"picamigos_doctor\",\"arguments\":{\"workingDirectory\":"
                    + escapedRoot + ",\"runSmokeTests\":false}}}");
            JsonNode doctor = readLine(reader, executor, Duration.ofSeconds(30), stderr);
            assertEquals(3, doctor.path("id").asInt(), doctor.toPrettyString());
            assertFalse(doctor.path("result").path("isError").asBoolean(), doctor.toPrettyString());
            assertEquals(3, doctor.path("result").path("structuredContent").size(), doctor.toPrettyString());
        } finally {
            process.getOutputStream().close();
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
            stderrReader.join(2000);
        }
    }

    private void send(BufferedWriter writer, String message) throws Exception {
        writer.write(message);
        writer.newLine();
        writer.flush();
    }

    private JsonNode readLine(BufferedReader reader, java.util.concurrent.ExecutorService executor,
                              Duration timeout, StringBuilder stderr) throws Exception {
        String line = executor.submit(reader::readLine).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        assertNotNull(line, "Server closed stdout. stderr:\n" + stderr);
        return JsonSupport.createMapper().readTree(line);
    }
}
