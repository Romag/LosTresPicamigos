package org.lostrespicamigos.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExecutableResolverTest {
    @TempDir Path temporary;

    @Test
    void resolvesWindowsNpmCommandShims() throws Exception {
        Path shim = temporary.resolve("claude.cmd");
        Files.writeString(shim, "@echo off\n", StandardCharsets.UTF_8);

        ExecutableResolver.Resolution resolution = new ExecutableResolver(pathEnvironment(), true).resolve("claude");

        assertTrue(resolution.available(), resolution.problem());
        assertEquals(shim.toAbsolutePath().normalize(), resolution.executable().orElseThrow());
    }

    @Test
    void prefersNativeWindowsExecutablesBeforeCommandShims() throws Exception {
        Path executable = temporary.resolve("claude.exe");
        Path shim = temporary.resolve("claude.cmd");
        Files.writeString(executable, "", StandardCharsets.UTF_8);
        Files.writeString(shim, "@echo off\n", StandardCharsets.UTF_8);

        ExecutableResolver.Resolution resolution = new ExecutableResolver(pathEnvironment(), true).resolve("claude");

        assertTrue(resolution.available(), resolution.problem());
        assertEquals(executable.toAbsolutePath().normalize(), resolution.executable().orElseThrow());
    }

    @Test
    void rejectsPowerShellLaunchers() throws Exception {
        Path shim = temporary.resolve("claude.ps1");
        Files.writeString(shim, "Write-Output claude\n", StandardCharsets.UTF_8);

        ExecutableResolver.Resolution resolution = new ExecutableResolver(pathEnvironment(), true)
                .resolve(shim.toString());

        assertFalse(resolution.available());
        assertTrue(resolution.problem().contains("PowerShell"), resolution.problem());
    }

    @Test
    void rejectsWindowsCommandShimsOnOtherPlatforms() throws Exception {
        Path shim = temporary.resolve("claude.cmd");
        Files.writeString(shim, "@echo off\n", StandardCharsets.UTF_8);

        ExecutableResolver.Resolution resolution = new ExecutableResolver(pathEnvironment(), false)
                .resolve(shim.toString());

        assertFalse(resolution.available());
        assertTrue(resolution.problem().contains("Windows launcher"), resolution.problem());
    }

    private Map<String, String> pathEnvironment() {
        return Map.of("PATH", temporary.toString());
    }
}
