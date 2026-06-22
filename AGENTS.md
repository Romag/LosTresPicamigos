# Repository instructions

Read `docs/IMPLEMENTATION_PLAN.md` before changing production code.

## Required workflow

1. Work from a focused issue or one work package from the implementation plan.
2. Add or update tests with every behavior change.
3. Make small, atomic commits throughout the work. Each commit must represent one coherent, independently reviewable change and leave the repository in a valid state.
4. Write short, simple commit subjects in the imperative present tense, such as `Add provider lock tests` or `Fix Unix wrapper permissions`.
5. Never accumulate an entire repository, feature set, or multi-stage implementation into one giant commit. Treat that as a process failure and split the work before committing.
6. Run `./mvnw verify` (`mvnw.cmd verify` on Windows) before handing off.
7. Keep provider-specific flags inside the corresponding adapter.
8. Keep process execution shell-free and argument-list based.
9. Preserve raw provider stdout, stderr, and exit status.
10. Never add API-key authentication, token extraction, generic command execution, network MCP transport, automatic Git push/merge/reset, or recursive delegation.

## Architecture boundaries

- `agent`: provider contracts and adapters only.
- `process`: process lifecycle and I/O only; no provider policy.
- `run`: persistence, locks, and run state.
- `workspace`: Git repository and worktree isolation.
- `workflow`: deterministic orchestration only; no semantic synthesis.
- `mcp`: MCP schemas and transport mapping only.

Use typed records and enums. Do not replace domain types with `Map<String, Object>` or untyped `Object` payloads.
