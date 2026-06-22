package org.lostrespicamigos.agent;

import org.lostrespicamigos.domain.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class AntigravityAdapter implements AgentAdapter {
    private static final int MAX_ARGUMENT_PROMPT = 24_000;
    private static final int MAX_TASK_CHARACTERS = 23_000;

    @Override public AgentId id() { return AgentId.ANTIGRAVITY; }
    @Override public List<String> versionArguments() { return List.of("--version"); }
    @Override public List<String> capabilities() { return List.of("plain-text", "print", "continue", "conversation", "sandbox"); }
    @Override public int maxTaskCharacters() { return MAX_TASK_CHARACTERS; }

    @Override
    public AgentCommand buildCommand(Path executable, AgentRequest request, Path effectiveDirectory) {
        String prompt = PromptContract.compose(request, effectiveDirectory);
        if (prompt.length() > MAX_ARGUMENT_PROMPT) {
            throw new IllegalArgumentException("Antigravity prompt exceeds the safe command-line limit; stdin support is not yet verified");
        }
        List<String> command = new ArrayList<>(List.of(executable.toString(), "--print", "--print-timeout",
                request.timeout().toSeconds() + "s"));
        if (request.access() == AccessMode.READ_ONLY) command.add("--sandbox");
        if (request.session().mode() == SessionMode.CONTINUE) command.add("--continue");
        if (request.session().mode() == SessionMode.RESUME) command.addAll(List.of("--conversation", request.session().id()));
        if (request.allowDangerousPermissions()) command.add("--dangerously-skip-permissions");
        return new AgentCommand(command, PromptTransport.ARGUMENT, prompt, Map.of("NO_COLOR", "1"));
    }

    @Override
    public AgentResult parse(ProcessExecutionResult result) {
        List<String> warnings = new ArrayList<>();
        if (result.outputLimitExceeded()) warnings.add("Provider output exceeded the configured limit");
        return new AgentResult(result.stdout().strip(), null, warnings, result.stdout(), result.stderr(), result.exitCode());
    }
}
