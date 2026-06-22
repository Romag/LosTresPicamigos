package org.lostrespicamigos.workspace;

import org.lostrespicamigos.config.PicamigosConfig;
import org.lostrespicamigos.domain.AccessMode;
import org.lostrespicamigos.domain.AgentRequest;
import org.lostrespicamigos.domain.IsolationMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class GitWorkspaceManager {
    private final PicamigosConfig config;
    private final GitClient git = new GitClient();

    public GitWorkspaceManager(PicamigosConfig config) {
        this.config = config;
    }

    public WorkspaceLease prepare(AgentRequest request, UUID runId) throws IOException, InterruptedException {
        Path source = request.workingDirectory().toRealPath();
        Path allowed = config.allowedRoot().toRealPath();
        Path managedWorktrees = config.home().resolve("worktrees").toAbsolutePath().normalize();
        boolean managed = source.getParent() != null && source.getParent().equals(managedWorktrees)
                && Files.isRegularFile(ownershipMarker(source), LinkOption.NOFOLLOW_LINKS);
        if (!source.startsWith(allowed) && !managed) {
            throw new SecurityException("workingDirectory is outside the configured root: " + allowed);
        }
        if (request.isolation() == IsolationMode.DIRECT) {
            if (request.access() == AccessMode.WORKSPACE_WRITE && !config.allowDirectWrites()) {
                throw new SecurityException("Direct writable delegation is disabled");
            }
            return new WorkspaceLease(source, null, List.of(), null);
        }

        Path repository = repositoryRoot(source);
        Path worktrees = config.home().resolve("worktrees");
        Files.createDirectories(worktrees);
        Path destination = worktrees.resolve(runId.toString());
        if (Files.exists(destination)) throw new IOException("Workspace destination already exists: " + destination);

        if (request.isolation() == IsolationMode.WORKTREE) {
            String branch = "picamigos/" + runId.toString().substring(0, 8) + "/" + request.agent().value();
            try (RepositoryLock ignored = repositoryLock(repository)) {
                git.require(repository, List.of("worktree", "add", "-b", branch, destination.toString(), "HEAD"));
            }
            writeOwnershipMarker(destination, repository);
            return new WorkspaceLease(destination, branch, List.of(), null);
        }

        try (RepositoryLock ignored = repositoryLock(repository)) {
            git.require(repository, List.of("worktree", "add", "--detach", destination.toString(), "HEAD"));
        }
        writeOwnershipMarker(destination, repository);
        List<String> warnings = new ArrayList<>();
        GitClient.Result diff = git.require(repository, List.of("diff", "--binary", "HEAD"));
        if (diff.stdout().length > 0) {
            GitClient.Result applied = git.run(destination, List.of("apply", "--whitespace=nowarn", "-"), diff.stdout());
            if (applied.exitCode() != 0) {
                cleanup(repository, destination);
                throw new IOException("Could not apply the working tree diff to the review snapshot: " + applied.stderr());
            }
        }
        String untracked = git.require(repository, List.of("ls-files", "--others", "--exclude-standard")).stdoutText().strip();
        if (!untracked.isBlank()) warnings.add("Untracked files are not included in this snapshot: " + untracked.lines().limit(10).toList());
        return new WorkspaceLease(destination, null, warnings, () -> cleanup(repository, destination));
    }

    public boolean removeManagedWorktree(Path destination) throws IOException, InterruptedException {
        Path worktrees = config.home().resolve("worktrees").toAbsolutePath().normalize();
        Path normalized = destination.toAbsolutePath().normalize();
        Path marker = ownershipMarker(normalized);
        if (!worktrees.equals(normalized.getParent()) || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) return false;
        if (Files.isSymbolicLink(normalized)) throw new SecurityException("Refusing to remove a symbolic-link worktree");
        Path repository = Path.of(Files.readString(marker).strip()).toRealPath();
        if (!repository.startsWith(config.allowedRoot().toRealPath())) {
            throw new SecurityException("Managed worktree repository is outside the configured root");
        }
        try (RepositoryLock ignored = repositoryLock(repository)) {
            GitClient.Result result = git.run(repository, List.of("worktree", "remove", "--force", normalized.toString()));
            if (result.exitCode() != 0) throw new IOException("Git could not remove the managed worktree: " + result.stderr().strip());
        }
        Files.deleteIfExists(marker);
        return true;
    }

    public List<Path> expiredManagedWorktrees(Instant cutoff) throws IOException {
        Path worktrees = config.home().resolve("worktrees").toAbsolutePath().normalize();
        if (!Files.isDirectory(worktrees, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (var entries = Files.list(worktrees)) {
            return entries.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().endsWith(".picamigos-owned"))
                    .filter(path -> {
                        try {
                            return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff);
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .map(path -> path.resolveSibling(path.getFileName().toString().replaceFirst("\\.picamigos-owned$", "")))
                    .toList();
        }
    }

    private Path repositoryRoot(Path directory) throws IOException, InterruptedException {
        GitClient.Result result = git.require(directory, List.of("rev-parse", "--show-toplevel"));
        return Path.of(result.stdoutText().strip()).toRealPath();
    }

    private void cleanup(Path repository, Path destination) {
        try (RepositoryLock ignored = repositoryLock(repository)) {
            GitClient.Result result = git.run(repository, List.of("worktree", "remove", "--force", destination.toString()));
            if (result.exitCode() == 0) Files.deleteIfExists(ownershipMarker(destination));
            else System.err.println("Picamigos could not remove temporary review worktree " + destination + ": " + result.stderr());
        } catch (Exception e) {
            System.err.println("Picamigos could not remove temporary review worktree " + destination + ": " + e.getMessage());
        }
    }

    private RepositoryLock repositoryLock(Path repository) throws IOException {
        return RepositoryLock.acquire(config.home().resolve("locks"), repository);
    }

    private void writeOwnershipMarker(Path destination, Path repository) throws IOException {
        Files.writeString(ownershipMarker(destination), repository.toString());
    }

    private Path ownershipMarker(Path destination) {
        return destination.resolveSibling(destination.getFileName() + ".picamigos-owned");
    }
}
