package org.lostrespicamigos.retention;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnedDirectoryCleanerTest {
    @TempDir Path temporary;

    @Test
    void deletesOnlyMarkedDirectChildren() throws Exception {
        Path owned = Files.createDirectory(temporary.resolve("owned"));
        Files.writeString(owned.resolve(".picamigos-owned"), "run");
        Files.writeString(owned.resolve("artifact.txt"), "data");
        Path unowned = Files.createDirectory(temporary.resolve("unowned"));
        Path nested = Files.createDirectories(temporary.resolve("parent").resolve("nested"));
        Files.writeString(nested.resolve(".picamigos-owned"), "run");

        OwnedDirectoryCleaner.deleteDirectChild(temporary, owned, ".picamigos-owned");

        assertFalse(Files.exists(owned));
        assertThrows(SecurityException.class,
                () -> OwnedDirectoryCleaner.deleteDirectChild(temporary, unowned, ".picamigos-owned"));
        assertThrows(SecurityException.class,
                () -> OwnedDirectoryCleaner.deleteDirectChild(temporary, nested, ".picamigos-owned"));
    }

    @Test
    void doesNotFollowLinksInsideAnOwnedDirectory() throws Exception {
        Path outside = Files.writeString(temporary.resolve("outside.txt"), "keep");
        Path owned = Files.createDirectory(temporary.resolve("linked"));
        Files.writeString(owned.resolve(".picamigos-owned"), "run");
        try {
            Files.createSymbolicLink(owned.resolve("outside-link"), outside);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symbolic links are unavailable: " + e.getMessage());
        }

        OwnedDirectoryCleaner.deleteDirectChild(temporary, owned, ".picamigos-owned");

        assertTrue(Files.isRegularFile(outside));
    }
}
