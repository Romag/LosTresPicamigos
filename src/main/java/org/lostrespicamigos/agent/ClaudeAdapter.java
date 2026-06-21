package org.lostrespicamigos.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.lostrespicamigos.domain.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ClaudeAdapter implements AgentAdapter {
    private final ObjectMapper mapper;

    public ClaudeAdapter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override public AgentId id() { return AgentId.CLAUDE; }
    @Override public List<String> versionArguments() { return List.of("--version"); }
    @Override public List<String> capabilities() { return List.of("stream-json", "stdin", "resume", "permission-modes"); }

    @Override
    public AgentCommand buildCommand(Path executable, AgentRequest request, Path effectiveDirectory) {
        List<String> command = new ArrayList<>(List.of(executable.toString(), "-p", "--output-format", "stream-json", "--verbose"));
        if (request.session().mode() == SessionMode.CONTINUE) command.add("--continue");
        if (request.session().mode() == SessionMode.RESUME) command.addAll(List.of("--resume", request.session().id()));
        command.addAll(List.of("--permission-mode", request.access() == AccessMode.READ_ONLY ? "plan" : "acceptEdits"));
        return new AgentCommand(command, PromptTransport.STDIN, PromptContract.compose(request, effectiveDirectory),
                Map.of("NO_COLOR", "1"));
    }

    @Override
    public AgentResult parse(ProcessExecutionResult result) {
        String sessionId = null;
        String finalText = "";
        List<String> warnings = new ArrayList<>();
        for (String line : result.stdout().lines().toList()) {
            if (line.isBlank()) continue;
            try {
                JsonNode node = mapper.readTree(line);
                if (node.hasNonNull("session_id")) sessionId = node.get("session_id").asText();
                if ("result".equals(node.path("type").asText()) && node.has("result")) finalText = node.get("result").asText("");
                if (finalText.isBlank() && "assistant".equals(node.path("type").asText())) {
                    String extracted = extractAssistantText(node);
                    if (!extracted.isBlank()) finalText = extracted;
                }
            } catch (Exception e) {
                warnings.add("Unparseable Claude stream line: " + abbreviate(line));
            }
        }
        if (result.outputLimitExceeded()) warnings.add("Provider output exceeded the configured limit");
        return new AgentResult(finalText, sessionId, warnings, result.stdout(), result.stderr(), result.exitCode());
    }

    private String extractAssistantText(JsonNode node) {
        StringBuilder text = new StringBuilder();
        JsonNode content = node.path("message").path("content");
        if (content.isArray()) {
            for (JsonNode item : content) if ("text".equals(item.path("type").asText())) text.append(item.path("text").asText());
        }
        return text.toString();
    }

    private String abbreviate(String line) { return line.length() <= 160 ? line : line.substring(0, 160) + "..."; }
}
