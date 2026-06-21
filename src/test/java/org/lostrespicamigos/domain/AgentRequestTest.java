package org.lostrespicamigos.domain;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AgentRequestTest {
    @Test
    void implementationDefaultsToWritableWorktree() {
        AgentRequest request = new AgentRequest(AgentId.CLAUDE, AgentRole.IMPLEMENT, "Implement it",
                Path.of("C:/project").toAbsolutePath(), null, null, null, null, false);

        assertEquals(AccessMode.WORKSPACE_WRITE, request.access());
        assertEquals(IsolationMode.WORKTREE, request.isolation());
        assertEquals(SessionMode.NEW, request.session().mode());
    }

    @Test
    void reviewCannotRequestWriteAccess() {
        assertThrows(IllegalArgumentException.class, () -> new AgentRequest(AgentId.CODEX, AgentRole.REVIEW,
                "Review", Path.of("C:/project").toAbsolutePath(), AccessMode.WORKSPACE_WRITE,
                IsolationMode.WORKTREE, SessionSpec.fresh(), null, false));
    }

    @Test
    void resumeRequiresSessionId() {
        assertThrows(IllegalArgumentException.class, () -> new SessionSpec(SessionMode.RESUME, null));
    }
}
