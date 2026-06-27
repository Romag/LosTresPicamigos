package org.lostrespicamigos.run;

import org.lostrespicamigos.domain.AgentId;
import org.lostrespicamigos.domain.AgentRequest;
import org.lostrespicamigos.domain.AgentResult;
import org.lostrespicamigos.domain.RunRecord;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

public interface RunOperations {
    RunRecord start(AgentRequest request) throws IOException;

    Optional<RunRecord> status(UUID runId);

    Optional<AgentResult> result(UUID runId);

    boolean cancel(UUID runId) throws IOException;

    boolean cleanup(UUID runId) throws IOException, InterruptedException;

    void validateTarget(AgentId agent);

    int maxTaskCharacters(AgentId agent);
}
