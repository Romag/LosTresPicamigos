package org.lostrespicamigos.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.lostrespicamigos.domain.WorkflowRecord;
import org.lostrespicamigos.domain.WorkflowStatus;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public final class WorkflowStore {
    private final Path directory;
    private final ObjectMapper mapper;

    public WorkflowStore(Path home, ObjectMapper mapper) throws IOException {
        this.directory = home.resolve("workflows");
        this.mapper = mapper;
        Files.createDirectories(directory);
        recoverAbandoned();
    }

    public synchronized void save(WorkflowRecord record) throws IOException {
        Path workflowDirectory = directory.resolve(record.workflowId().toString());
        Files.createDirectories(workflowDirectory);
        Path marker = workflowDirectory.resolve(".picamigos-owned");
        if (!Files.exists(marker)) Files.writeString(marker, "workflow\n");
        Path target = workflowDirectory.resolve("state.json");
        Path temporary = workflowDirectory.resolve("state.json.tmp-" + UUID.randomUUID());
        mapper.writeValue(temporary.toFile(), record);
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public Optional<WorkflowRecord> load(UUID id) {
        Path path = directory.resolve(id.toString()).resolve("state.json");
        if (!Files.isRegularFile(path)) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(path.toFile(), WorkflowRecord.class));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private void recoverAbandoned() throws IOException {
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(Files::isDirectory).toList()) {
                Path state = path.resolve("state.json");
                if (!Files.isRegularFile(state)) continue;
                WorkflowRecord record;
                try {
                    record = mapper.readValue(state.toFile(), WorkflowRecord.class);
                } catch (IOException ignored) {
                    continue;
                }
                boolean ownerAlive = ProcessHandle.of(record.ownerProcessId()).map(ProcessHandle::isAlive).orElse(false);
                if (record.status() == WorkflowStatus.RUNNING && !ownerAlive) {
                    save(new WorkflowRecord(record.workflowId(), record.ownerProcessId(), record.type(), WorkflowStatus.ABORTED,
                            record.stage(), record.runIds(), record.createdAt(), Instant.now(),
                            "Server stopped before workflow completion"));
                }
            }
        }
    }
}
