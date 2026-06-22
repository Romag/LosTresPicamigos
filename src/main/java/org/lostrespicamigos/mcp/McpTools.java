package org.lostrespicamigos.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import org.lostrespicamigos.config.PicamigosConfig;
import org.lostrespicamigos.domain.*;
import org.lostrespicamigos.run.RunService;
import org.lostrespicamigos.workflow.WorkflowService;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class McpTools {
    private final RunService runs;
    private final PicamigosConfig config;
    private final ObjectMapper mapper;
    private final WorkflowService workflows;

    public McpTools(RunService runs, WorkflowService workflows, PicamigosConfig config, ObjectMapper mapper) {
        this.runs = runs;
        this.workflows = workflows;
        this.config = config;
        this.mapper = mapper;
    }

    public List<SyncToolSpecification> all() {
        return List.of(doctor(), delegate(), status(), result(), cancel(), cleanup(), listRuns(), startWorkflow(), workflowStatus());
    }

    private SyncToolSpecification doctor() {
        Map<String, Object> schema = objectSchema(Map.of(
                "workingDirectory", stringProperty("Absolute project directory; defaults to the server root"),
                "runSmokeTests", booleanProperty("Run a quota-consuming prompt through each CLI")), List.of());
        return specification("picamigos_doctor", "Check installed CLI paths, versions, and capabilities without model calls by default",
                schema, true, arguments -> {
                    Path directory = path(arguments, "workingDirectory", config.allowedRoot());
                    boolean smoke = bool(arguments, "runSmokeTests", false);
                    return runs.doctor(directory, smoke);
                });
    }

    private SyncToolSpecification delegate() {
        Map<String, Object> session = objectSchema(Map.of(
                "mode", enumProperty("new", "continue", "resume"),
                "id", stringProperty("Provider session identifier; required for resume")), List.of());
        Map<String, Object> schema = objectSchema(Map.of(
                "agent", enumProperty("codex", "claude", "antigravity"),
                "role", enumProperty("plan", "implement", "review", "general"),
                "task", stringProperty("Complete task for the adjacent agent"),
                "workingDirectory", stringProperty("Absolute project directory"),
                "access", enumProperty("read-only", "workspace-write"),
                "isolation", enumProperty("snapshot", "worktree", "direct"),
                "session", session,
                "timeoutSeconds", integerProperty(1, 7200),
                "allowDangerousPermissions", booleanProperty("Explicit per-run opt-in; also requires server opt-in")),
                List.of("agent", "task", "workingDirectory"));
        return specification("picamigos_delegate", "Start an asynchronous planning, implementation, review, or consultation run",
                schema, false, arguments -> {
                    AgentRequest request = request(arguments);
                    RunRecord record = runs.start(request);
                    return Map.of("runId", record.runId().toString(), "status", record.status().name().toLowerCase(),
                            "agent", record.agent().value(), "artifactUri", "picamigos://runs/" + record.runId() + "/result");
                });
    }

    private SyncToolSpecification status() {
        Map<String, Object> schema = objectSchema(Map.of(
                "runId", stringProperty("Run UUID"),
                "tailCharacters", integerProperty(0, 50000)), List.of("runId"));
        return specification("picamigos_run_status", "Read persisted run state and bounded stdout/stderr tails", schema, true,
                arguments -> {
                    UUID id = uuid(arguments, "runId");
                    RunRecord record = runs.status(id).orElseThrow(() -> new IllegalArgumentException("Unknown run: " + id));
                    int tail = integer(arguments, "tailCharacters", 8000);
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("run", record);
                    response.put("stdoutTail", runs.tail(id, "stdout", tail));
                    response.put("stderrTail", runs.tail(id, "stderr", tail));
                    return response;
                });
    }

    private SyncToolSpecification result() {
        Map<String, Object> schema = objectSchema(Map.of("runId", stringProperty("Run UUID")), List.of("runId"));
        return specification("picamigos_run_result", "Read a completed run result and raw artifact URIs", schema, true,
                arguments -> {
                    UUID id = uuid(arguments, "runId");
                    AgentResult value = runs.result(id).orElseThrow(() -> new IllegalStateException("Result is not available for run " + id));
                    return Map.of("result", RunResultSummary.from(value),
                            "resultUri", "picamigos://runs/" + id + "/result",
                            "stdoutUri", "picamigos://runs/" + id + "/stdout",
                            "stderrUri", "picamigos://runs/" + id + "/stderr",
                            "requestUri", "picamigos://runs/" + id + "/request");
                });
    }

    private SyncToolSpecification cancel() {
        Map<String, Object> schema = objectSchema(Map.of("runId", stringProperty("Run UUID")), List.of("runId"));
        return specification("picamigos_cancel_run", "Cancel an active provider process tree without changing Git history", schema, false,
                arguments -> Map.of("runId", string(arguments, "runId", true),
                        "cancellationRequested", runs.cancel(uuid(arguments, "runId"))));
    }

    private SyncToolSpecification cleanup() {
        Map<String, Object> schema = objectSchema(Map.of("runId", stringProperty("Terminal run UUID")), List.of("runId"));
        return specification("picamigos_cleanup_run",
                "Remove a terminal run's owned implementation worktree while preserving its branch and run artifacts",
                schema, false, true, arguments -> {
                    UUID id = uuid(arguments, "runId");
                    return Map.of("runId", id.toString(), "worktreeRemoved", runs.cleanup(id), "branchPreserved", true);
                });
    }

    private SyncToolSpecification listRuns() {
        Map<String, Object> schema = objectSchema(Map.of(), List.of());
        return specification("picamigos_list_runs", "List recent persisted runs", schema, true,
                arguments -> Map.of("runs", runs.list().stream().limit(100).toList()));
    }

    private SyncToolSpecification startWorkflow() {
        Map<String, Object> reviewers = Map.of("type", "array", "items", enumProperty("codex", "claude", "antigravity"),
                "minItems", 1, "maxItems", 2, "uniqueItems", true);
        Map<String, Object> schema = objectSchema(Map.of(
                "type", enumProperty("review-panel", "plan-implement-review"),
                "workingDirectory", stringProperty("Absolute project directory"),
                "task", stringProperty("Workflow task and acceptance criteria"),
                "reviewers", reviewers,
                "planner", enumProperty("codex", "claude", "antigravity"),
                "implementer", enumProperty("codex", "claude", "antigravity"),
                "reviewer", enumProperty("codex", "claude", "antigravity"),
                "timeoutSeconds", integerProperty(1, 7200)), List.of("type", "workingDirectory", "task"));
        return specification("picamigos_start_workflow", "Start a review panel or plan-implement-review workflow",
                schema, false, arguments -> {
                    WorkflowType type = WorkflowType.parse(string(arguments, "type", true));
                    Path directory = Path.of(string(arguments, "workingDirectory", true)).toAbsolutePath();
                    String task = string(arguments, "task", true);
                    Duration timeout = Duration.ofSeconds(integer(arguments, "timeoutSeconds", 1800));
                    WorkflowRecord record;
                    if (type == WorkflowType.REVIEW_PANEL) {
                        Object raw = arguments.get("reviewers");
                        if (!(raw instanceof List<?> list)) throw new IllegalArgumentException("reviewers is required for review-panel");
                        List<AgentId> agents = list.stream().map(value -> AgentId.parse(value.toString())).toList();
                        record = workflows.startReviewPanel(directory, task, agents, timeout);
                    } else {
                        record = workflows.startPlanImplementReview(directory, task,
                                AgentId.parse(string(arguments, "planner", true)),
                                AgentId.parse(string(arguments, "implementer", true)),
                                AgentId.parse(string(arguments, "reviewer", true)), timeout);
                    }
                    return Map.of("workflowId", record.workflowId().toString(), "status", record.status().name().toLowerCase(),
                            "stage", record.stage(), "runIds", record.runIds());
                });
    }

    private SyncToolSpecification workflowStatus() {
        Map<String, Object> schema = objectSchema(Map.of("workflowId", stringProperty("Workflow UUID")), List.of("workflowId"));
        return specification("picamigos_workflow_status", "Read workflow stage and child run IDs", schema, true,
                arguments -> workflows.status(UUID.fromString(string(arguments, "workflowId", true)))
                        .orElseThrow(() -> new IllegalArgumentException("Unknown workflow")));
    }

    private SyncToolSpecification specification(String name, String description, Map<String, Object> schema,
                                                boolean readOnly, ThrowingFunction handler) {
        return specification(name, description, schema, readOnly, false, handler);
    }

    private SyncToolSpecification specification(String name, String description, Map<String, Object> schema,
                                                boolean readOnly, boolean destructive, ThrowingFunction handler) {
        Tool tool = Tool.builder(name, schema)
                .description(description)
                .annotations(ToolAnnotations.builder().readOnlyHint(readOnly).destructiveHint(destructive)
                        .idempotentHint(readOnly).openWorldHint(false).build())
                .build();
        return SyncToolSpecification.builder().tool(tool).callHandler((exchange, request) -> {
            try {
                Object structured = handler.apply(request.arguments() == null ? Map.of() : request.arguments());
                String text = mapper.writeValueAsString(structured);
                return CallToolResult.builder().addTextContent(text).structuredContent(structured).isError(false).build();
            } catch (Exception e) {
                Map<String, Object> error = Map.of("error", e.getClass().getSimpleName(),
                        "message", e.getMessage() == null ? "Unknown error" : e.getMessage());
                try {
                    return CallToolResult.builder().addTextContent(mapper.writeValueAsString(error))
                            .structuredContent(error).isError(true).build();
                } catch (Exception serializationFailure) {
                    return CallToolResult.builder().addTextContent("Picamigos error: " + error.get("message")).isError(true).build();
                }
            }
        }).build();
    }

    private AgentRequest request(Map<String, Object> arguments) {
        AgentRole role = AgentRole.parse(string(arguments, "role", false));
        AccessMode access = AccessMode.parse(string(arguments, "access", false), role);
        IsolationMode isolation = IsolationMode.parse(string(arguments, "isolation", false), role);
        SessionSpec sessionSpec = SessionSpec.fresh();
        Object rawSession = arguments.get("session");
        if (rawSession instanceof Map<?, ?> raw) {
            String mode = raw.get("mode") == null ? null : raw.get("mode").toString();
            String id = raw.get("id") == null ? null : raw.get("id").toString();
            sessionSpec = new SessionSpec(SessionMode.parse(mode), id);
        }
        return new AgentRequest(AgentId.parse(string(arguments, "agent", true)), role,
                string(arguments, "task", true), Path.of(string(arguments, "workingDirectory", true)).toAbsolutePath(),
                access, isolation, sessionSpec, Duration.ofSeconds(integer(arguments, "timeoutSeconds", 1800)),
                bool(arguments, "allowDangerousPermissions", false));
    }

    private Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private Map<String, Object> stringProperty(String description) { return Map.of("type", "string", "description", description); }
    private Map<String, Object> booleanProperty(String description) { return Map.of("type", "boolean", "description", description); }
    private Map<String, Object> enumProperty(String... values) { return Map.of("type", "string", "enum", List.of(values)); }
    private Map<String, Object> integerProperty(int minimum, int maximum) { return Map.of("type", "integer", "minimum", minimum, "maximum", maximum); }

    private String string(Map<String, Object> arguments, String key, boolean required) {
        Object value = arguments.get(key);
        if (value == null) {
            if (required) throw new IllegalArgumentException(key + " is required");
            return null;
        }
        String text = value.toString();
        if (required && text.isBlank()) throw new IllegalArgumentException(key + " cannot be blank");
        return text;
    }

    private Path path(Map<String, Object> arguments, String key, Path defaultValue) {
        String value = string(arguments, key, false);
        return value == null ? defaultValue : Path.of(value).toAbsolutePath();
    }

    private boolean bool(Map<String, Object> arguments, String key, boolean defaultValue) {
        Object value = arguments.get(key);
        return value == null ? defaultValue : value instanceof Boolean b ? b : Boolean.parseBoolean(value.toString());
    }

    private int integer(Map<String, Object> arguments, String key, int defaultValue) {
        Object value = arguments.get(key);
        return value == null ? defaultValue : value instanceof Number n ? n.intValue() : Integer.parseInt(value.toString());
    }

    private UUID uuid(Map<String, Object> arguments, String key) { return UUID.fromString(string(arguments, key, true)); }

    @FunctionalInterface
    private interface ThrowingFunction {
        Object apply(Map<String, Object> arguments) throws Exception;
    }
}
