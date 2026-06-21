package org.lostrespicamigos.domain;

import java.util.List;

public record AgentHealth(
        AgentId agent,
        boolean available,
        String executable,
        String version,
        List<String> capabilities,
        String problem,
        String remediation) {
}
