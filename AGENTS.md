# Repository instructions

Read `docs/IMPLEMENTATION_PLAN.md` before changing production code.

## Required workflow

1. Work from a focused issue or one work package from the implementation plan.
2. Add or update tests with every behavior change.
3. Run `./mvnw verify` (`mvnw.cmd verify` on Windows) before handing off.
4. Keep provider-specific flags inside the corresponding adapter.
5. Keep process execution shell-free and argument-list based.
6. Preserve raw provider stdout, stderr, and exit status.
7. Never add API-key authentication, token extraction, generic command execution, network MCP transport, automatic Git push/merge/reset, or recursive delegation.

## Architecture boundaries

- `agent`: provider contracts and adapters only.
- `process`: process lifecycle and I/O only; no provider policy.
- `run`: persistence, locks, and run state.
- `workspace`: Git repository and worktree isolation.
- `workflow`: deterministic orchestration only; no semantic synthesis.
- `mcp`: MCP schemas and transport mapping only.

Use typed records and enums. Do not replace domain types with `Map<String, Object>` or untyped `Object` payloads.
