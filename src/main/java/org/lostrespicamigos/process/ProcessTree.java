package org.lostrespicamigos.process;

import java.time.Duration;
import java.util.Comparator;

public final class ProcessTree {
    private ProcessTree() {
    }

    public static void terminate(ProcessHandle root, Duration grace) {
        root.descendants().sorted(Comparator.comparingInt(ProcessTree::depth).reversed())
                .forEach(ProcessHandle::destroy);
        root.destroy();
        long deadline = System.nanoTime() + grace.toNanos();
        while (root.isAlive() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        root.descendants().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        if (root.isAlive()) root.destroyForcibly();
    }

    private static int depth(ProcessHandle handle) {
        int depth = 0;
        for (ProcessHandle current = handle; current.parent().isPresent(); current = current.parent().orElseThrow()) depth++;
        return depth;
    }
}
