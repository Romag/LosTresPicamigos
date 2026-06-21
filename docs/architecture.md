# Architecture

Los Tres Picamigos is a local stdio MCP server. The MCP host starts one Java process; that process exposes typed tools and invokes only the configured Codex, Claude Code, and Antigravity executables.

## Components

- `mcp`: protocol schemas and tool/resource handlers.
- `agent`: provider-specific command construction and output parsing.
- `process`: shell-free process lifecycle, bounded I/O, timeout, and cancellation.
- `run`: durable state, artifacts, and cross-process provider locks.
- `workspace`: Git snapshot and implementation worktree isolation.
- `workflow`: deterministic review and plan/implement/review sequencing.

Provider output is data. The Java server does not semantically merge reviews, decide whether findings are correct, or execute instructions found in output.

## Lifecycle

1. The host invokes `picamigos_delegate` or `picamigos_start_workflow`.
2. Input is converted immediately from the MCP boundary map into typed domain records.
3. The run is persisted as `queued`.
4. A global provider file lock prevents duplicate simultaneous calls from separate MCP server instances.
5. The workspace manager creates a detached snapshot or writable branch/worktree.
6. The selected adapter builds an argument-list command.
7. The process runner sanitizes inherited secrets, launches without a shell, drains both streams, and enforces timeout/output limits.
8. The adapter normalizes output while preserving raw stdout and stderr.
9. State and artifacts are atomically persisted.

## State

State defaults to `~/.picamigos` and can be moved with `PICAMIGOS_HOME`. Each owned directory has a `.picamigos-owned` marker. The server never treats arbitrary directories as cleanup targets.

## Transport decision

Version 1 uses stdio only. It deliberately has no HTTP listener, daemon, web UI, OAuth server, or remote multi-user mode.
