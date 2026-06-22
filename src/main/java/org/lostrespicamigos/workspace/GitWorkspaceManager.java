package org.lostrespicamigos.workspace;

import org.lostrespicamigos.config.PicamigosConfig;
import org.lostrespicamigos.domain.AccessMode;
import org.lostrespicamigos.domain.AgentRequest;
import org.lostrespicamigos.domain.IsolationMode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class GitWorkspaceManager {
    private static final int MAX_UNTRACKED_FILES = 10;
    private static final long MAX_UNTRACKED_BYTES = 100 * 1024;
    private final PicamigosConfig config;
    private final GitClient git = new GitClient();

    public GitWorkspaceManager(PicamigosConfig config) {
        this.config = config;
    }

    public WorkspaceLease prepare(AgentRequest request, UUID runId) throws IOException, InterruptedException {
        Path source = request.workingDirectory().toRealPath();
        Path allowed = config.allowedRoot().toRealPath();
        Path managedWorktrees = config.home().resolve("worktrees").toAbsolutePath().normalize();
        boolean managed = managedWorktreeRoot(source, managedWorktrees).isPresent();
        if (!source.startsWith(allowed) && !managed) {
            throw new SecurityException("workingDirectory is outside the configured root: " + allowed);
        }
        if (request.isolation() == IsolationMode.DIRECT) {
            if (request.access() == AccessMode.WORKSPACE_WRITE && !config.allowDirectWrites()) {
                throw new SecurityException("Direct writable delegation is disabled");
            }
            return new WorkspaceLease(source, null, null, List.of(), null);
        }

        Path repository = repositoryRoot(source);
        Path requestedSubdirectory = repository.relativize(source);
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
            List<String> warnings = new ArrayList<>();
            transferUntracked(repository, destination, request.includeUntracked(), warnings);
            Path effectiveDirectory = effectiveDirectory(destination, requestedSubdirectory);
            return new WorkspaceLease(effectiveDirectory, destination, branch, warnings, null);
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
        transferUntracked(repository, destination, request.includeUntracked(), warnings);
        Path effectiveDirectory = effectiveDirectory(destination, requestedSubdirectory);
        return new WorkspaceLease(effectiveDirectory, destination, null, warnings, () -> cleanup(repository, destination));
    }

    public boolean removeManagedWorktree(Path destination) throws IOException, InterruptedException {
        Path worktrees = config.home().resolve("worktrees").toAbsolutePath().normalize();
        Path normalized = managedWorktreeRoot(destination.toAbsolutePath().normalize(), worktrees).orElse(null);
        if (normalized == null) return false;
        Path marker = ownershipMarker(normalized);
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

    private Optional<Path> managedWorktreeRoot(Path candidate, Path worktrees) {
        if (!candidate.startsWith(worktrees)) return Optional.empty();
        Path relative = worktrees.relativize(candidate);
        if (relative.getNameCount() == 0) return Optional.empty();
        Path root = worktrees.resolve(relative.getName(0)).toAbsolutePath().normalize();
        return Files.isRegularFile(ownershipMarker(root), LinkOption.NOFOLLOW_LINKS)
                ? Optional.of(root) : Optional.empty();
    }

    private Path effectiveDirectory(Path worktreeRoot, Path requestedSubdirectory) throws IOException {
        Path effective = worktreeRoot.resolve(requestedSubdirectory).normalize();
        if (!effective.startsWith(worktreeRoot) || !Files.isDirectory(effective, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Requested repository subdirectory is absent from the isolated worktree: "
                    + requestedSubdirectory);
        }
        return effective;
    }

    private void transferUntracked(Path repository, Path destination, boolean includeUntracked,
                                   List<String> warnings) throws IOException, InterruptedException {
        byte[] output = git.require(repository,
                List.of("ls-files", "--others", "--exclude-standard", "-z")).stdout();
        List<String> relativePaths = java.util.Arrays.stream(new String(output, StandardCharsets.UTF_8).split("\u0000"))
                .filter(path -> !path.isEmpty()).toList();
        if (relativePaths.isEmpty()) return;
        if (!includeUntracked) {
            warnings.add("Untracked files are not included: " + relativePaths.stream().limit(10).toList());
            return;
        }

        int copiedFiles = 0;
        long copiedBytes = 0;
        List<String> skipped = new ArrayList<>();
        for (String relativePath : relativePaths) {
            Path source = repository.resolve(relativePath).normalize();
            Path target = destination.resolve(relativePath).normalize();
            if (!source.startsWith(repository) || !target.startsWith(destination)
                    || Files.isSymbolicLink(source) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                    || !source.toRealPath().startsWith(repository)) {
                skipped.add(relativePath + " (unsafe type or path)");
                continue;
            }
            long size = Files.size(source);
            if (copiedFiles >= MAX_UNTRACKED_FILES || copiedBytes + size > MAX_UNTRACKED_BYTES) {
                skipped.add(relativePath + " (copy limit)");
                continue;
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                skipped.add(relativePath + " (target exists)");
                continue;
            }
            Files.createDirectories(target.getParent());
            Files.copy(source, target);
            copiedFiles++;
            copiedBytes += size;
        }
        if (!skipped.isEmpty()) warnings.add("Some untracked files were not copied: " + skipped.stream().limit(10).toList());
    }
}
