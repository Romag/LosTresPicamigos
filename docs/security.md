# Security model

## Trust boundaries

- MCP clients are allowed to request only predefined Picamigos operations.
- Repository contents and provider output are untrusted data.
- Provider CLIs retain their own authentication and permission systems.
- The Java server never reads, copies, or exports provider credential files.

## Enforced controls

- No generic command-execution tool.
- No user-supplied shell template.
- `ProcessBuilder` argument lists; no shell for provider processes.
- Secret-like environment variables are removed before child launch.
- Maximum timeout is two hours.
- Stdout and stderr are independently bounded.
- One active process per provider across server instances.
- Descendant processes are terminated on timeout, cancellation, or shutdown.
- Any inherited delegation depth is rejected through `PICAMIGOS_DELEGATION_DEPTH`.
- The configured host agent cannot invoke itself by default.
- Working directories must be beneath the configured root or be Picamigos-owned implementation worktrees.
- Review and planning use disposable detached Git worktrees.
- Direct writable operation is disabled by default.
- Antigravity permission bypass requires both server-level and request-level opt-in.

## Residual risks

Provider sandboxes differ. A writable coding agent may execute repository code and dependency hooks. Worktree isolation protects the source checkout from accidental edits but is not an operating-system security boundary.

Run artifacts can contain source code and prompts. Protect `PICAMIGOS_HOME`, choose an appropriate retention policy, and do not share its contents casually. Retention defaults to seven days and only deletes marked, direct children without following symbolic links.

## Reporting

Do not open a public issue containing credentials, private source, prompts, or run logs. Follow [SECURITY.md](../SECURITY.md).
