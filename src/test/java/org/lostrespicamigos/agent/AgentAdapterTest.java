package org.lostrespicamigos.agent;

import org.junit.jupiter.api.Test;
import org.lostrespicamigos.config.JsonSupport;
import org.lostrespicamigos.domain.*;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class AgentAdapterTest {
    private final Path workspace = Path.of("C:/workspace").toAbsolutePath();

    @Test
    void codexUsesJsonlStdinAndReadOnlySandbox() {
        CodexAdapter adapter = new CodexAdapter(JsonSupport.createMapper());
        AgentRequest request = request(AgentId.CODEX, AgentRole.REVIEW, AccessMode.READ_ONLY);

        AgentCommand command = adapter.buildCommand(Path.of("codex"), request, workspace);

        assertEquals(PromptTransport.STDIN, command.promptTransport());
        assertTrue(command.command().containsAll(java.util.List.of("exec", "--json", "--sandbox", "read-only", "-")));
        assertTrue(command.prompt().contains("Do not invoke Los Tres Picamigos"));
    }

    @Test
    void codexParsesSessionAndFinalMessage() {
        CodexAdapter adapter = new CodexAdapter(JsonSupport.createMapper());
        String output = """
                {"type":"thread.started","thread_id":"session-1"}
                {"type":"item.completed","item":{"type":"agent_message","text":"Review complete"}}
                """;

        AgentResult result = adapter.parse(new ProcessExecutionResult(0, output, "", Duration.ZERO, false, false, false));

        assertEquals("session-1", result.sessionId());
        assertEquals("Review complete", result.text());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void claudeParsesStreamResult() {
        ClaudeAdapter adapter = new ClaudeAdapter(JsonSupport.createMapper());
        String output = """
                {"type":"system","session_id":"550e8400-e29b-41d4-a716-446655440000"}
                {"type":"result","result":"Implemented and verified"}
                """;

        AgentResult result = adapter.parse(new ProcessExecutionResult(0, output, "", Duration.ZERO, false, false, false));

        assertEquals("550e8400-e29b-41d4-a716-446655440000", result.sessionId());
        assertEquals("Implemented and verified", result.text());
    }

    @Test
    void antigravityUsesPlainTextAndArgumentPrompt() {
        AntigravityAdapter adapter = new AntigravityAdapter();
        AgentCommand command = adapter.buildCommand(Path.of("agy"), request(AgentId.ANTIGRAVITY, AgentRole.REVIEW,
                AccessMode.READ_ONLY), workspace);

        assertEquals(PromptTransport.ARGUMENT, command.promptTransport());
        assertTrue(command.command().contains("--sandbox"));
        assertFalse(command.command().contains("--dangerously-skip-permissions"));
        AgentResult result = adapter.parse(new ProcessExecutionResult(0, "Looks good\n", "", Duration.ZERO, false, false, false));
        assertEquals("Looks good", result.text());
    }

    private AgentRequest request(AgentId agent, AgentRole role, AccessMode access) {
        return new AgentRequest(agent, role, "Do the task", workspace, access,
                access == AccessMode.READ_ONLY ? IsolationMode.SNAPSHOT : IsolationMode.WORKTREE,
                SessionSpec.fresh(), Duration.ofMinutes(5), false);
    }
}
