package org.lostrespicamigos.workspace;

import java.nio.file.Path;
import java.util.List;

public record WorkspaceLease(Path directory, String branch, List<String> warnings, Runnable cleanup) implements AutoCloseable {
    public WorkspaceLease {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        cleanup = cleanup == null ? () -> { } : cleanup;
    }

    @Override
    public void close() {
        cleanup.run();
    }
}
