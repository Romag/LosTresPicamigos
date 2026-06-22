package org.lostrespicamigos.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoryLockTest {
    @TempDir Path temporary;

    @Test
    void serializesMutationsForTheSameRepository() throws Exception {
        Path repository = Files.createDirectory(temporary.resolve("repo"));
        Path locks = temporary.resolve("locks");
        CountDownLatch attempting = new CountDownLatch(1);
        AtomicBoolean acquired = new AtomicBoolean(false);

        RepositoryLock first = RepositoryLock.acquire(locks, repository);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Future<Void> waiting = executor.submit(() -> {
                attempting.countDown();
                try (RepositoryLock nested = RepositoryLock.acquire(locks, repository)) {
                    acquired.set(true);
                }
                return null;
            });
            try {
                assertTrue(attempting.await(1, TimeUnit.SECONDS));
                Thread.sleep(100);
                assertFalse(acquired.get());
            } finally {
                first.close();
            }
            waiting.get(2, TimeUnit.SECONDS);
            assertTrue(acquired.get());
        }
    }
}
