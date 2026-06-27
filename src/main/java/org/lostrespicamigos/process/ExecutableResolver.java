package org.lostrespicamigos.process;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class ExecutableResolver {
    private final Map<String, String> environment;
    private final boolean windows;

    public ExecutableResolver() {
        this(System.getenv(), isWindows());
    }

    ExecutableResolver(Map<String, String> environment) {
        this(environment, isWindows());
    }

    ExecutableResolver(Map<String, String> environment, boolean windows) {
        this.environment = environment;
        this.windows = windows;
    }

    public Resolution resolve(String configuredCommand) {
        if (configuredCommand == null || configuredCommand.isBlank()) {
            return Resolution.missing("No command configured");
        }

        Path configuredPath = Path.of(configuredCommand);
        if (configuredPath.isAbsolute() || configuredCommand.contains("/") || configuredCommand.contains("\\")) {
            return validate(configuredPath.toAbsolutePath().normalize());
        }

        for (Path candidate : candidates(configuredCommand)) {
            Resolution resolution = validate(candidate);
            if (resolution.available()) return resolution;
        }

        return Resolution.missing("Executable '" + configuredCommand + "' was not found on PATH or in a known installation location");
    }

    private List<Path> candidates(String command) {
        List<Path> candidates = new ArrayList<>();
        String[] extensions = windows ? new String[]{".exe", ".com", ".cmd", ".bat", ""} : new String[]{""};
        String pathValue = environment.getOrDefault("PATH", environment.getOrDefault("Path", ""));
        for (String directory : pathValue.split(java.io.File.pathSeparator)) {
            if (directory.isBlank()) continue;
            for (String extension : extensions) candidates.add(Path.of(directory, command + extension));
        }

        if (windows) {
            String localAppData = environment.get("LOCALAPPDATA");
            String appData = environment.get("APPDATA");
            if (localAppData != null && command.equalsIgnoreCase("agy")) {
                candidates.add(Path.of(localAppData, "agy", "bin", "agy.exe"));
            }
            if (appData != null && command.equalsIgnoreCase("claude")) {
                candidates.add(Path.of(appData, "npm", "node_modules", "@anthropic-ai", "claude-code", "bin", "claude.exe"));
                candidates.add(Path.of(appData, "npm", "claude.cmd"));
            }
        }
        return candidates;
    }

    private Resolution validate(Path candidate) {
        if (!Files.isRegularFile(candidate)) return Resolution.missing("Not found: " + candidate);
        String lower = candidate.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".ps1")) {
            return Resolution.missing("PowerShell launcher is not supported: " + candidate);
        }
        if (!windows && (lower.endsWith(".cmd") || lower.endsWith(".bat"))) {
            return Resolution.missing("Windows launcher is not supported on this platform: " + candidate);
        }
        if (!windows && !Files.isExecutable(candidate)) {
            return Resolution.missing("File is not executable: " + candidate);
        }
        return new Resolution(Optional.of(candidate.toAbsolutePath().normalize()), null);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    public record Resolution(Optional<Path> executable, String problem) {
        static Resolution missing(String problem) {
            return new Resolution(Optional.empty(), problem);
        }

        public boolean available() {
            return executable.isPresent();
        }
    }
}
