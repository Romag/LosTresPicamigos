package org.lostrespicamigos.domain;

import java.time.Duration;

public record ProcessExecutionResult(
        int exitCode,
        String stdout,
        String stderr,
        Duration duration,
        boolean timedOut,
        boolean cancelled,
        boolean outputLimitExceeded) {
}
