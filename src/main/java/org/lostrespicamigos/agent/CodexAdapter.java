package org.lostrespicamigos.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.lostrespicamigos.domain.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class CodexAdapter implements AgentAdapter {
    private final ObjectMapper mapper;

    public CodexAdapter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override public AgentId id() { return AgentId.CODEX; }
    @Override public List<String> versionArguments() { return List.of("--version"); }
    @Override public List<String> capabilities() { return List.of("jsonl", "stdin", "resume", "read-only", "workspace-write"); }

    @Override
    public AgentCommand buildCommand(Path executable, AgentRequest request, Path effectiveDirectory) {
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.add("exec");
        if (request.session().mode() != SessionMode.NEW) {
            command.add("resume");
            command.add(request.session().mode() == SessionMode.CONTINUE ? "--last" : request.session().id());
        }
        command.add("--json");
        command.add("--sandbox");
        command.add(request.access() == AccessMode.READ_ONLY ? "read-only" : "workspace-write");
        command.add("-");
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
                if ("thread.started".equals(node.path("type").asText())) sessionId = textOrNull(node, "thread_id");
                if ("item.completed".equals(node.path("type").asText())
                        && "agent_message".equals(node.path("item").path("type").asText())) {
                    finalText = node.path("item").path("text").asText("");
                }
            } catch (Exception e) {
                warnings.add("Unparseable Codex JSONL line: " + abbreviate(line));
            }
        }
        if (result.outputLimitExceeded()) warnings.add("Provider output exceeded the configured limit");
        return new AgentResult(finalText, sessionId, warnings, result.stdout(), result.stderr(), result.exitCode());
    }

    private String textOrNull(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        return value == null || value.isBlank() ? null : value;
    }

    private String abbreviate(String line) {
        return line.length() <= 160 ? line : line.substring(0, 160) + "...";
    }
}
