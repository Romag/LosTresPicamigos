package org.lostrespicamigos.agent;

import org.lostrespicamigos.domain.AgentCommand;
import org.lostrespicamigos.domain.AgentId;
import org.lostrespicamigos.domain.AgentRequest;
import org.lostrespicamigos.domain.AgentResult;
import org.lostrespicamigos.domain.ProcessExecutionResult;

import java.nio.file.Path;
import java.util.List;

public interface AgentAdapter {
    AgentId id();

    List<String> versionArguments();

    List<String> capabilities();

    default int maxTaskCharacters() {
        return Integer.MAX_VALUE;
    }

    AgentCommand buildCommand(Path executable, AgentRequest request, Path effectiveDirectory);

    AgentResult parse(ProcessExecutionResult result);
}
