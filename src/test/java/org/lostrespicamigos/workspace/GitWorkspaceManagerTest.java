package org.lostrespicamigos.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lostrespicamigos.config.PicamigosConfig;
import org.lostrespicamigos.domain.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GitWorkspaceManagerTest {
    @TempDir Path temporary;

    @Test
    void snapshotIncludesTrackedDirtyDiffAndDoesNotMutateSource() throws Exception {
        Path repository = temporary.resolve("repo");
        Files.createDirectories(repository);
        git(repository, "init", "-b", "main");
        git(repository, "config", "user.email", "test@example.com");
        git(repository, "config", "user.name", "Test");
        Files.writeString(repository.resolve("file.txt"), "base\n");
        git(repository, "add", "file.txt");
        git(repository, "commit", "-m", "base");
        Files.writeString(repository.resolve("file.txt"), "changed\n");

        GitWorkspaceManager manager = new GitWorkspaceManager(config(repository));
        AgentRequest request = new AgentRequest(AgentId.CLAUDE, AgentRole.REVIEW, "Review", repository.toAbsolutePath(),
                AccessMode.READ_ONLY, IsolationMode.SNAPSHOT, SessionSpec.fresh(), Duration.ofMinutes(1), false);
        Path snapshot;
        try (WorkspaceLease lease = manager.prepare(request, UUID.randomUUID())) {
            snapshot = lease.directory();
            assertTrue(Files.isRegularFile(snapshot.resolveSibling(snapshot.getFileName() + ".picamigos-owned")));
            assertEquals("changed\n", normalize(Files.readString(snapshot.resolve("file.txt"))));
            Files.writeString(snapshot.resolve("file.txt"), "reviewer changed this\n");
        }

        assertEquals("changed\n", normalize(Files.readString(repository.resolve("file.txt"))));
        assertFalse(Files.exists(snapshot));
        assertFalse(Files.exists(snapshot.resolveSibling(snapshot.getFileName() + ".picamigos-owned")));
    }

    @Test
    void implementationMetadataDoesNotPolluteTheWorktree() throws Exception {
        Path repository = temporary.resolve("implementation-repo");
        Files.createDirectories(repository);
        git(repository, "init", "-b", "main");
        git(repository, "config", "user.email", "test@example.com");
        git(repository, "config", "user.name", "Test");
        Files.writeString(repository.resolve("file.txt"), "base\n");
        git(repository, "add", "file.txt");
        git(repository, "commit", "-m", "base");

        GitWorkspaceManager manager = new GitWorkspaceManager(config(repository));
        AgentRequest request = new AgentRequest(AgentId.CLAUDE, AgentRole.IMPLEMENT, "Implement", repository.toAbsolutePath(),
                AccessMode.WORKSPACE_WRITE, IsolationMode.WORKTREE, SessionSpec.fresh(), Duration.ofMinutes(1), false);

        try (WorkspaceLease lease = manager.prepare(request, UUID.randomUUID())) {
            assertFalse(Files.exists(lease.directory().resolve(".picamigos-worktree")));
            assertTrue(Files.isRegularFile(lease.directory().resolveSibling(
                    lease.directory().getFileName() + ".picamigos-owned")));
            assertEquals("", gitOutput(lease.directory(), "status", "--porcelain"));
            String branch = lease.branch();
            Path directory = lease.directory();
            assertTrue(manager.removeManagedWorktree(directory));
            assertFalse(Files.exists(directory));
            assertFalse(Files.exists(directory.resolveSibling(directory.getFileName() + ".picamigos-owned")));
            assertEquals(branch, gitOutput(repository, "branch", "--list", branch).replace("* ", "").strip());
        }
    }

    private PicamigosConfig config(Path repository) {
        return new PicamigosConfig(temporary.resolve("home"), repository.toAbsolutePath(), null,
                Map.of(AgentId.CODEX, "codex", AgentId.CLAUDE, "claude", AgentId.ANTIGRAVITY, "agy"),
                false, false, false, 1024 * 1024);
    }

    private void git(Path directory, String... args) throws Exception {
        gitOutput(directory, args);
    }

    private String gitOutput(Path directory, String... args) throws Exception {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(java.util.List.of(args));
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), output);
        return output.strip();
    }

    private String normalize(String value) {
        return value.replace("\r\n", "\n");
    }
}
