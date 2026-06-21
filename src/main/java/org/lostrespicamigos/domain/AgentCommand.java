package org.lostrespicamigos.domain;

import java.util.List;
import java.util.Map;

public record AgentCommand(
        List<String> command,
        PromptTransport promptTransport,
        String prompt,
        Map<String, String> environment) {

    public AgentCommand {
        command = List.copyOf(command);
        if (command.isEmpty()) throw new IllegalArgumentException("command cannot be empty");
        promptTransport = promptTransport == null ? PromptTransport.STDIN : promptTransport;
        environment = environment == null ? Map.of() : Map.copyOf(environment);
    }
}
