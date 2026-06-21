# Installation and host configuration

Build the shaded JAR with `./mvnw verify`, then use its absolute path in each host. Start one server per repository root so delegated paths remain bounded.

## Codex

Add this to the relevant Codex `config.toml`:

```toml
[mcp_servers.picamigos]
command = "java"
args = ["-jar", "/absolute/path/picamigos-mcp-0.1.0-SNAPSHOT-all.jar", "--host", "codex", "--root", "/absolute/project/root"]
required = true
```

## Claude Code

Configure a stdio MCP server whose command is `java` and whose arguments are:

```text
-jar
/absolute/path/picamigos-mcp-0.1.0-SNAPSHOT-all.jar
--host
claude
--root
/absolute/project/root
```

Use Claude Code's supported MCP configuration command or checked-in project MCP configuration. Do not place credentials in the Picamigos entry.

## Antigravity

Add an entry to `~/.gemini/config/mcp_config.json`:

```json
{
  "mcpServers": {
    "picamigos": {
      "command": "java",
      "args": [
        "-jar",
        "/absolute/path/picamigos-mcp-0.1.0-SNAPSHOT-all.jar",
        "--host",
        "antigravity",
        "--root",
        "/absolute/project/root"
      ]
    }
  }
}
```

## Executable overrides

Use absolute native executable paths when PATH discovery is unreliable:

```text
PICAMIGOS_CODEX_COMMAND
PICAMIGOS_CLAUDE_COMMAND
PICAMIGOS_ANTIGRAVITY_COMMAND
```

On Windows, the server intentionally refuses `.cmd` and `.bat` launchers. Point it at the provider's native `.exe` instead.

## First check

Call `picamigos_doctor` with `runSmokeTests=false`. Only use smoke tests when spending a small amount of each subscription quota is acceptable.
