package org.lostrespicamigos.domain;

import java.time.Instant;

public final class OwnerProcess {
    private OwnerProcess() {
    }

    public static Instant currentStartInstant() {
        return ProcessHandle.current().info().startInstant().orElse(null);
    }

    public static boolean matches(long processId, Instant expectedStartInstant) {
        if (expectedStartInstant == null) return false;
        return ProcessHandle.of(processId)
                .filter(ProcessHandle::isAlive)
                .flatMap(handle -> handle.info().startInstant())
                .map(expectedStartInstant::equals)
                .orElse(false);
    }
}
