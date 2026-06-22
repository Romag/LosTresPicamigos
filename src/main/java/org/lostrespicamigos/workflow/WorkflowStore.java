package org.lostrespicamigos.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.lostrespicamigos.domain.WorkflowRecord;
import org.lostrespicamigos.domain.WorkflowStatus;
import org.lostrespicamigos.domain.OwnerProcess;
import org.lostrespicamigos.retention.OwnedDirectoryCleaner;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Optional;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

    public List<WorkflowRecord> list() throws IOException {
        List<WorkflowRecord> records = new ArrayList<>();
        try (var paths = Files.list(directory)) {
            paths.filter(path -> Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .forEach(path -> {
                        try {
                            load(UUID.fromString(path.getFileName().toString())).ifPresent(records::add);
                        } catch (IllegalArgumentException ignored) {
                            // Ignore unrelated directories; only UUID-named owned records are eligible.
                        }
                    });
        }
        records.sort(Comparator.comparing(WorkflowRecord::createdAt).reversed());
        return List.copyOf(records);
    }

    public synchronized void delete(UUID id) throws IOException {
        OwnedDirectoryCleaner.deleteDirectChild(directory, directory.resolve(id.toString()), ".picamigos-owned");
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
                boolean ownerAlive = OwnerProcess.matches(record.ownerProcessId(), record.ownerStartedAt());
                if (record.status() == WorkflowStatus.RUNNING && !ownerAlive) {
                    save(new WorkflowRecord(record.workflowId(), record.ownerProcessId(), record.ownerStartedAt(),
                            record.type(), WorkflowStatus.ABORTED,
                            record.stage(), record.runIds(), record.createdAt(), Instant.now(),
                            "Server stopped before workflow completion"));
                }
            }
        }
    }
}
