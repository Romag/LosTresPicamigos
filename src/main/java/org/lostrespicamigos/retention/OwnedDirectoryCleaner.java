package org.lostrespicamigos.retention;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

public final class OwnedDirectoryCleaner {
    private OwnedDirectoryCleaner() {
    }

    public static void deleteDirectChild(Path root, Path target, String markerName) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (!normalizedRoot.equals(normalizedTarget.getParent()) || Files.isSymbolicLink(normalizedTarget)
                || !Files.isDirectory(normalizedTarget, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(normalizedTarget.resolve(markerName), LinkOption.NOFOLLOW_LINKS)) {
            throw new SecurityException("Refusing to delete an unowned directory: " + target);
        }
        Files.walkFileTree(normalizedTarget, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
