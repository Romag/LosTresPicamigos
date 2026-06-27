package org.lostrespicamigos.run;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lostrespicamigos.config.JsonSupport;
import org.lostrespicamigos.domain.*;
import org.lostrespicamigos.process.ProcessRunner;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RunStoreTest {
    @TempDir Path temporary;

    @Test
    void persistsAndRecoversRunArtifacts() throws Exception {
        RunStore store = new RunStore(temporary, JsonSupport.createMapper());
        UUID id = UUID.randomUUID();
        AgentRequest request = new AgentRequest(AgentId.CODEX, AgentRole.GENERAL, "Inspect",
                temporary.toAbsolutePath(), AccessMode.READ_ONLY, IsolationMode.DIRECT,
                SessionSpec.fresh(), null, false);
        RunRecord record = new RunRecord(id, Long.MAX_VALUE, Instant.EPOCH,
                AgentId.CODEX, AgentRole.GENERAL, RunStatus.RUNNING,
                temporary.toString(), temporary.toString(), null, Instant.now(), Instant.now(), null,
                123L, null, "Running");
        store.create(record, request);
        AgentResult result = new AgentResult("done", "session", List.of(), "raw-out", "raw-err", 0);
        store.saveResult(id, result);

        assertEquals("done", store.loadResult(id).orElseThrow().text());
        assertEquals("raw-out", store.tail(id, "stdout", 100));

        store.recoverAbandoned();
        assertEquals(RunStatus.ABORTED, store.load(id).orElseThrow().status());
    }

    @Test
    void streamsOutputThroughAnOpenWriterBeforeFinalResultRewrite() throws Exception {
        RunStore store = new RunStore(temporary, JsonSupport.createMapper());
        UUID id = UUID.randomUUID();
        AgentRequest request = new AgentRequest(AgentId.CODEX, AgentRole.GENERAL, "Inspect",
                temporary.toAbsolutePath(), AccessMode.READ_ONLY, IsolationMode.DIRECT,
                SessionSpec.fresh(), null, false);
        RunRecord record = new RunRecord(id, ProcessHandle.current().pid(), OwnerProcess.currentStartInstant(),
                AgentId.CODEX, AgentRole.GENERAL, RunStatus.RUNNING,
                temporary.toString(), temporary.toString(), null, Instant.now(), Instant.now(), null,
                123L, null, "Running");
        store.create(record, request);

        try (RunStore.OutputWriter writer = store.openOutputWriter(id)) {
            byte[] stdout = "first-second".getBytes(StandardCharsets.UTF_8);
            writer.append(ProcessRunner.Stream.STDOUT, stdout, 5);
            writer.append(ProcessRunner.Stream.STDOUT, stdout, stdout.length);
            byte[] stderr = "progress".getBytes(StandardCharsets.UTF_8);
            writer.append(ProcessRunner.Stream.STDERR, stderr, stderr.length);
        }

        assertEquals("firstfirst-second", store.tail(id, "stdout", 100));
        assertEquals("progress", store.tail(id, "stderr", 100));

        store.saveResult(id, new AgentResult("done", "session", List.of(), "final-out", "final-err", 0));

        assertEquals("final-out", store.tail(id, "stdout", 100));
        assertEquals("final-err", store.tail(id, "stderr", 100));
    }

    @Test
    void doesNotAbortARunOwnedByAnotherLiveServerProcess() throws Exception {
        RunStore store = new RunStore(temporary, JsonSupport.createMapper());
        UUID id = UUID.randomUUID();
        AgentRequest request = new AgentRequest(AgentId.CLAUDE, AgentRole.REVIEW, "Review",
                temporary.toAbsolutePath(), AccessMode.READ_ONLY, IsolationMode.DIRECT,
                SessionSpec.fresh(), null, false);
        RunRecord record = new RunRecord(id, ProcessHandle.current().pid(), OwnerProcess.currentStartInstant(),
                AgentId.CLAUDE, AgentRole.REVIEW,
                RunStatus.RUNNING, temporary.toString(), temporary.toString(), null, Instant.now(), Instant.now(),
                null, null, null, "Running");
        store.create(record, request);

        store.recoverAbandoned();

        assertEquals(RunStatus.RUNNING, store.load(id).orElseThrow().status());
    }

    @Test
    void abortsWhenALivePidHasADifferentStartInstant() throws Exception {
        RunStore store = new RunStore(temporary, JsonSupport.createMapper());
        UUID id = UUID.randomUUID();
        AgentRequest request = new AgentRequest(AgentId.CLAUDE, AgentRole.REVIEW, "Review",
                temporary.toAbsolutePath(), AccessMode.READ_ONLY, IsolationMode.DIRECT,
                SessionSpec.fresh(), null, false);
        RunRecord record = new RunRecord(id, ProcessHandle.current().pid(), Instant.EPOCH,
                AgentId.CLAUDE, AgentRole.REVIEW, RunStatus.RUNNING, temporary.toString(),
                temporary.toString(), null, Instant.now(), Instant.now(), null, null, null, "Running");
        store.create(record, request);

        store.recoverAbandoned();

        assertEquals(RunStatus.ABORTED, store.load(id).orElseThrow().status());
    }
}
