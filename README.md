# Los Tres Picamigos

A local, Java-based MCP server that lets Codex, Claude Code, and Antigravity CLI delegate planning, implementation, and review work to one another through their installed command-line interfaces.

The project uses existing CLI subscription authentication. It does not accept API keys, extract tokens, or expose arbitrary command execution.

The complete design and implementation boundaries are in [docs/IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md).

## Status

Functional initial implementation. It provides stdio MCP, nine tools, asynchronous delegation, provider diagnostics, persistent bounded run artifacts, cancellation, explicit worktree cleanup, seven-day retention, Git snapshot/worktree isolation, and review plus plan/implement/review workflows.

See [installation and host configuration](docs/installation.md) to connect the JAR to each agent.

## Build

Requirements: Java 21 or newer.

```shell
./mvnw verify
```

On Windows:

```powershell
.\mvnw.cmd verify
```

The executable artifact is `target/picamigos-mcp-0.1.0-SNAPSHOT-all.jar`.

## License

Apache License 2.0.
