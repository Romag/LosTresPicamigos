package org.lostrespicamigos.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.lostrespicamigos.domain.AgentRequest;
import org.lostrespicamigos.domain.AgentResult;
import org.lostrespicamigos.domain.RunRecord;
import org.lostrespicamigos.domain.RunStatus;
import org.lostrespicamigos.domain.OwnerProcess;
import org.lostrespicamigos.retention.OwnedDirectoryCleaner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class RunStore {
    private final Path runsDirectory;
    private final ObjectMapper mapper;

    public RunStore(Path home, ObjectMapper mapper) throws IOException {
        this.runsDirectory = home.resolve("runs");
        this.mapper = mapper;
        Files.createDirectories(runsDirectory);
    }

    public synchronized void create(RunRecord record, AgentRequest request) throws IOException {
        Path directory = directory(record.runId());
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(".picamigos-owned"), "run\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
        RequestArtifact artifact = new RequestArtifact(request.agent().value(), request.role().name(), request.task(),
                request.workingDirectory().toString(), request.access().name(), request.isolation().name(),
                request.session().mode().name(), request.session().id(), request.timeout().toSeconds(),
                request.allowDangerousPermissions());
        writeAtomic(directory.resolve("request.json"), artifact);
        Files.write(directory.resolve("stdout.log"), new byte[0], StandardOpenOption.CREATE_NEW);
        Files.write(directory.resolve("stderr.log"), new byte[0], StandardOpenOption.CREATE_NEW);
        save(record);
    }

    public synchronized void save(RunRecord record) throws IOException {
        writeAtomic(directory(record.runId()).resolve("state.json"), record);
    }

    public synchronized void saveResult(UUID runId, AgentResult result) throws IOException {
        Path directory = directory(runId);
        writeAtomic(directory.resolve("result.json"), result);
        Files.writeString(directory.resolve("stdout.log"), result.rawStdout(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.writeString(directory.resolve("stderr.log"), result.rawStderr(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    public synchronized void appendOutput(UUID runId, org.lostrespicamigos.process.ProcessRunner.Stream stream,
                                          byte[] bytes, int length) throws IOException {
        String name = stream == org.lostrespicamigos.process.ProcessRunner.Stream.STDOUT ? "stdout.log" : "stderr.log";
        Files.write(directory(runId).resolve(name), java.util.Arrays.copyOf(bytes, length),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public Optional<RunRecord> load(UUID runId) {
        return read(directory(runId).resolve("state.json"), RunRecord.class);
    }

    public Optional<AgentResult> loadResult(UUID runId) {
        return read(directory(runId).resolve("result.json"), AgentResult.class);
    }

    public List<RunRecord> list() throws IOException {
        if (!Files.exists(runsDirectory)) return List.of();
        List<RunRecord> values = new ArrayList<>();
        try (var directories = Files.list(runsDirectory)) {
            directories.filter(Files::isDirectory).forEach(path -> read(path.resolve("state.json"), RunRecord.class).ifPresent(values::add));
        }
        values.sort(Comparator.comparing(RunRecord::createdAt).reversed());
        return List.copyOf(values);
    }

    public synchronized void recoverAbandoned() throws IOException {
        for (RunRecord record : list()) {
            boolean ownerAlive = OwnerProcess.matches(record.ownerProcessId(), record.ownerStartedAt());
            if ((record.status() == RunStatus.RUNNING || record.status() == RunStatus.QUEUED) && !ownerAlive) {
                save(record.transition(RunStatus.ABORTED, null, null, null, record.exitCode(),
                        "Server stopped before the run reached a terminal state"));
            }
        }
    }

    public String tail(UUID runId, String stream, int maxCharacters) throws IOException {
        if (!stream.equals("stdout") && !stream.equals("stderr")) throw new IllegalArgumentException("stream must be stdout or stderr");
        Path path = directory(runId).resolve(stream + ".log");
        if (!Files.exists(path)) return "";
        String text = Files.readString(path, StandardCharsets.UTF_8);
        return text.length() <= maxCharacters ? text : text.substring(text.length() - maxCharacters);
    }

    public Path artifact(UUID runId, String name) {
        if (!List.of("request.json", "state.json", "result.json", "stdout.log", "stderr.log").contains(name)) {
            throw new IllegalArgumentException("Unsupported artifact name");
        }
        return directory(runId).resolve(name);
    }

    public synchronized void delete(UUID runId) throws IOException {
        OwnedDirectoryCleaner.deleteDirectChild(runsDirectory, directory(runId), ".picamigos-owned");
    }

    private Path directory(UUID runId) {
        return runsDirectory.resolve(runId.toString());
    }

    private <T> Optional<T> read(Path path, Class<T> type) {
        if (!Files.isRegularFile(path)) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(path.toFile(), type));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private void writeAtomic(Path target, Object value) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        mapper.writeValue(temporary.toFile(), value);
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record RequestArtifact(String agent, String role, String task, String workingDirectory, String access,
                                   String isolation, String sessionMode, String sessionId, long timeoutSeconds,
                                   boolean allowDangerousPermissions) {
    }
}
