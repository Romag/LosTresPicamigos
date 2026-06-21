package org.lostrespicamigos.mcp;

import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceTemplateSpecification;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.ResourceTemplate;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import org.lostrespicamigos.run.RunService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RunResource {
    private static final Pattern URI = Pattern.compile("picamigos://runs/([0-9a-fA-F-]{36})/(request|state|result|stdout|stderr)");
    private static final Map<String, String> FILES = Map.of(
            "request", "request.json", "state", "state.json", "result", "result.json",
            "stdout", "stdout.log", "stderr", "stderr.log");
    private final RunService runs;

    public RunResource(RunService runs) {
        this.runs = runs;
    }

    public SyncResourceTemplateSpecification specification() {
        ResourceTemplate template = ResourceTemplate.builder("picamigos://runs/{runId}/{artifact}", "picamigos-run-artifact")
                .title("Picamigos run artifact")
                .description("Persisted request, state, result, stdout, or stderr for one run")
                .mimeType("text/plain")
                .build();
        return new SyncResourceTemplateSpecification(template, (exchange, request) -> {
            try {
                Matcher matcher = URI.matcher(request.uri());
                if (!matcher.matches()) throw new IllegalArgumentException("Invalid Picamigos run artifact URI");
                UUID runId = UUID.fromString(matcher.group(1));
                String artifact = matcher.group(2);
                String text = Files.readString(runs.artifact(runId, FILES.get(artifact)), StandardCharsets.UTF_8);
                String mime = artifact.equals("stdout") || artifact.equals("stderr") ? "text/plain" : "application/json";
                return new ReadResourceResult(List.of(new TextResourceContents(request.uri(), mime, text)));
            } catch (Exception e) {
                throw new IllegalArgumentException("Cannot read run artifact: " + e.getMessage(), e);
            }
        });
    }
}
