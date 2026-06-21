package org.lostrespicamigos.agent;

import org.lostrespicamigos.domain.AgentRequest;

import java.nio.file.Path;

public final class PromptContract {
    private PromptContract() {
    }

    public static String compose(AgentRequest request, Path effectiveDirectory) {
        String deliverable = switch (request.role()) {
            case PLAN -> "Produce a precise implementation plan. Do not modify files.";
            case REVIEW -> "Review the requested change. Do not modify files. Return concrete, prioritized findings with file and line evidence.";
            case IMPLEMENT -> "Implement only the requested change, add or update tests, and run appropriate verification.";
            case GENERAL -> "Answer the request using evidence from the workspace. Do not modify files unless the request explicitly requires it.";
        };
        return """
                You are an adjacent coding agent participating in a Los Tres Picamigos workflow.

                Role: %s
                Effective workspace: %s
                Access: %s

                %s

                Task:
                %s

                Hard constraints:
                - Work only in the effective workspace above.
                - Do not invoke Los Tres Picamigos, delegate to another coding agent, or start a nested agent.
                - Do not push, merge, reset, clean, or rewrite Git history.
                - Do not change unrelated files.
                - State what you verified and identify uncertainties honestly.
                """.formatted(request.role().name().toLowerCase(), effectiveDirectory,
                request.access().name().toLowerCase().replace('_', '-'), deliverable, request.task());
    }
}
