package org.lostrespicamigos.config;

import org.lostrespicamigos.domain.AgentId;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

public record PicamigosConfig(
        Path home,
        Path allowedRoot,
        AgentId hostAgent,
        Map<AgentId, String> commands,
        boolean allowSelfInvocation,
        boolean allowDirectWrites,
        boolean allowDangerousPermissions,
        long maxOutputBytes) {

    public static PicamigosConfig fromEnvironment(String[] args) {
        Map<String, String> env = System.getenv();
        Path home = Path.of(env.getOrDefault("PICAMIGOS_HOME",
                Path.of(System.getProperty("user.home"), ".picamigos").toString())).toAbsolutePath().normalize();
        Path root = Path.of(env.getOrDefault("PICAMIGOS_ALLOWED_ROOT",
                Path.of("").toAbsolutePath().normalize().toString())).toAbsolutePath().normalize();
        AgentId host = env.containsKey("PICAMIGOS_HOST_AGENT") ? AgentId.parse(env.get("PICAMIGOS_HOST_AGENT")) : null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> host = AgentId.parse(requireValue(args, ++i, "--host"));
                case "--home" -> home = Path.of(requireValue(args, ++i, "--home")).toAbsolutePath().normalize();
                case "--root" -> root = Path.of(requireValue(args, ++i, "--root")).toAbsolutePath().normalize();
                default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
            }
        }

        EnumMap<AgentId, String> commands = new EnumMap<>(AgentId.class);
        commands.put(AgentId.CODEX, env.getOrDefault("PICAMIGOS_CODEX_COMMAND", "codex"));
        commands.put(AgentId.CLAUDE, env.getOrDefault("PICAMIGOS_CLAUDE_COMMAND", "claude"));
        commands.put(AgentId.ANTIGRAVITY, env.getOrDefault("PICAMIGOS_ANTIGRAVITY_COMMAND", "agy"));

        return new PicamigosConfig(home, root, host, Map.copyOf(commands),
                Boolean.parseBoolean(env.getOrDefault("PICAMIGOS_ALLOW_SELF", "false")),
                Boolean.parseBoolean(env.getOrDefault("PICAMIGOS_ALLOW_DIRECT_WRITES", "false")),
                Boolean.parseBoolean(env.getOrDefault("PICAMIGOS_ALLOW_DANGEROUS_PERMISSIONS", "false")),
                parsePositiveLong(env.getOrDefault("PICAMIGOS_MAX_OUTPUT_BYTES", "10485760")));
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) throw new IllegalArgumentException(option + " requires a value");
        return args[index];
    }

    private static long parsePositiveLong(String value) {
        long result = Long.parseLong(value);
        if (result <= 0) throw new IllegalArgumentException("PICAMIGOS_MAX_OUTPUT_BYTES must be positive");
        return result;
    }
}
