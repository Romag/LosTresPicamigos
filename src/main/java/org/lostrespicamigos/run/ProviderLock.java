package org.lostrespicamigos.run;

import org.lostrespicamigos.domain.AgentId;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class ProviderLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private ProviderLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static ProviderLock acquire(Path lockDirectory, AgentId agent) throws IOException {
        Files.createDirectories(lockDirectory);
        Path path = lockDirectory.resolve(agent.value() + ".lock");
        FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (java.nio.channels.OverlappingFileLockException e) {
            lock = null;
        }
        if (lock == null) {
            channel.close();
            throw new ProviderBusyException(agent.value() + " already has an active Picamigos run");
        }
        channel.truncate(0);
        channel.write(java.nio.ByteBuffer.wrap((ProcessHandle.current().pid() + System.lineSeparator()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        channel.force(true);
        return new ProviderLock(channel, lock);
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }

    public static final class ProviderBusyException extends IOException {
        public ProviderBusyException(String message) {
            super(message);
        }
    }
}
