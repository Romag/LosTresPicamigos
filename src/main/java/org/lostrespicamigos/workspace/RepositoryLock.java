package org.lostrespicamigos.workspace;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

final class RepositoryLock implements AutoCloseable {
    private static final ConcurrentHashMap<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

    private final ReentrantLock jvmLock;
    private final FileChannel channel;
    private final FileLock fileLock;

    private RepositoryLock(ReentrantLock jvmLock, FileChannel channel, FileLock fileLock) {
        this.jvmLock = jvmLock;
        this.channel = channel;
        this.fileLock = fileLock;
    }

    static RepositoryLock acquire(Path lockDirectory, Path repository) throws IOException {
        Files.createDirectories(lockDirectory);
        Path lockPath = lockDirectory.resolve("repo-" + repositoryKey(repository) + ".lock");
        ReentrantLock jvmLock = JVM_LOCKS.computeIfAbsent(lockPath, ignored -> new ReentrantLock());
        jvmLock.lock();
        FileChannel channel = null;
        try {
            channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock fileLock = channel.lock();
            return new RepositoryLock(jvmLock, channel, fileLock);
        } catch (IOException | RuntimeException e) {
            if (channel != null) channel.close();
            jvmLock.unlock();
            throw e;
        }
    }

    private static String repositoryKey(Path repository) throws IOException {
        try {
            byte[] bytes = repository.toRealPath().toString().getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    @Override
    public void close() throws IOException {
        try {
            fileLock.release();
        } finally {
            try {
                channel.close();
            } finally {
                jvmLock.unlock();
            }
        }
    }
}
