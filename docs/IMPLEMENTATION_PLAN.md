# Los Tres Picamigos — Implementation Plan

## 1. Recommended design

Build a framework-free Java 21 MCP server distributed as one executable JAR.

Each coding agent runs its own stdio instance of the server:

```text
Host agent
  │ MCP over stdio
  ▼
Los Tres Picamigos
  ├── Codex CLI adapter
  ├── Claude Code CLI adapter
  └── Antigravity CLI adapter
        │
        ▼
  Isolated subprocess + Git workspace
```

The host agent remains the coordinator. The server invokes only adjacent agents, captures their results, and returns them through MCP. It must not pretend to understand, merge, or adjudicate the agents' natural-language conclusions.

Use the official MCP Java SDK directly, without Spring Boot, Quarkus, Micronaut, or another application framework. Use stdio as the only v1 MCP transport. All protocol output goes to stdout; all diagnostics go to stderr.

## 2. Verified CLI assumptions

Implement adapters from explicit capability contracts, not remembered flags.

- Codex: use `codex exec --json`; it emits JSONL, supports sandbox selection, stdin prompts, and session resumption. It reuses saved CLI authentication, so no OpenAI API key is needed.
- Claude Code: use `claude -p --output-format stream-json --verbose`. Claude supports JSON/stream-JSON output, session IDs, resumption, schema-constrained results, and permission modes.
- Antigravity: locally verified `agy 1.0.7` supports `--print`, `--continue`, `--conversation`, `--sandbox`, `--print-timeout`, and `--dangerously-skip-permissions`. It does not advertise structured JSON output, so its adapter must treat stdout as plain text.
- Antigravity supports custom stdio MCP servers through `~/.gemini/config/mcp_config.json`.

The first implementation milestone must include a diagnostic command that reports missing or broken provider launchers cleanly rather than failing during delegation.

## 3. Product scope

### v1 objectives

The completed server shall:

1. Let Codex, Claude Code, or Antigravity invoke either of the other agents.
2. Use existing CLI subscription authentication only.
3. Support planning, implementation, review, and general consultation roles.
4. Run long operations asynchronously and expose status, cancellation, and results.
5. Isolate reviewers from the user's live working tree.
6. Isolate implementation agents in dedicated Git worktrees by default.
7. Run two independent reviewers concurrently.
8. Preserve raw stdout, stderr, exit status, timing, and provider session ID.
9. Work on Windows, macOS, and Linux.
10. Coordinate concurrent server instances using filesystem locks.
11. Never push, merge, reset, or delete user work.
12. Be developed publicly through issues, branches, pull requests, and CI.

### Explicitly out of scope for v1

- Vendor APIs, API keys, direct model SDKs, or token extraction.
- Pooling accounts or bypassing provider quotas or terms.
- Browser, desktop, IDE, or terminal UI automation.
- A web UI or custom TUI.
- Remote or multi-user hosting.
- Streamable HTTP transport or listening network ports.
- A permanent background daemon.
- Automatic login or credential installation.
- Automatic GitHub PR creation, pushing, merging, rebasing, or branch deletion.
- Automatic semantic merging of implementations.
- Automatic resolution or deduplication of conflicting review findings.
- A general workflow language or arbitrary command runner.
- Native binaries, installers, GraalVM images, Docker images, Homebrew, Winget, or Scoop packages.
- Shared cross-provider transcript conversion beyond stored provider session IDs.
- Monitoring or predicting subscription quota.
- Supporting coding agents other than the three named providers.

## 4. MCP interface

### 4.1 `picamigos_doctor`

Checks configuration without consuming model quota. `runSmokeTests=true` may send a harmless prompt and must never be the default.

Input:

```json
{
  "workingDirectory": "/absolute/project/path",
  "runSmokeTests": false
}
```

Return one record per provider with availability, executable path, version, capabilities, problem, and remediation.

### 4.2 `picamigos_delegate`

Starts one adjacent-agent operation and returns immediately.

```json
{
  "agent": "claude",
  "role": "review",
  "task": "Review HEAD against origin/main for correctness...",
  "workingDirectory": "/absolute/project/path",
  "access": "read-only",
  "isolation": "snapshot",
  "session": { "mode": "new" },
  "timeoutSeconds": 1800
}
```

Enums:

- `role`: `plan`, `implement`, `review`, `general`
- `access`: `read-only`, `workspace-write`
- `isolation`: `snapshot`, `worktree`, `direct`
- `session.mode`: `new`, `continue`, `resume`

Defaults:

- `plan` and `review`: read-only snapshot.
- `implement`: writable dedicated worktree.
- `general`: read-only snapshot.
- Timeout: 30 minutes.
- Output limit: 10 MiB per stream.

Reject invalid combinations such as `role=review` with write access. Return a UUID run ID, status, selected agent, and artifact resource URI.

### 4.3 `picamigos_run_status`

Return status, timestamps, process ID, bounded output tails, workspace/branch information, and provider session ID. Status values are `queued`, `running`, `succeeded`, `failed`, `timed_out`, `cancelled`, and `aborted`.

### 4.4 `picamigos_run_result`

Return the normalized result, raw artifact URIs, exit code, and parser warnings. Never silently discard malformed structured output.

### 4.5 `picamigos_cancel_run`

Terminate the process tree using `ProcessHandle.descendants()`, wait a short grace period, and forcibly terminate survivors. Cancellation must never delete branches or reset files.

### 4.6 `picamigos_start_workflow`

Support exactly two v1 workflows:

- `review-panel`: run one or two adjacent reviewers concurrently against the same immutable snapshot. Return both independent reviews and do not claim consensus.
- `plan-implement-review`: run a planner, pass its plan to an implementer, then have a reviewer inspect the resulting branch. Stop after a failed stage unless the request explicitly asks for review of partial work.

### 4.7 Resources

Expose large artifacts as MCP resources:

```text
picamigos://runs/{runId}/request
picamigos://runs/{runId}/result
picamigos://runs/{runId}/stdout
picamigos://runs/{runId}/stderr
picamigos://workflows/{workflowId}/summary
```

Tool responses should contain summaries and URIs instead of dumping megabytes into the host model's context.

## 5. Java project structure

Use Maven Wrapper and a single production module initially:

```text
los-tres-picamigos/
├── .github/
│   ├── workflows/ci.yml
│   ├── workflows/release.yml
│   └── ISSUE_TEMPLATE/
├── docs/
│   ├── architecture.md
│   ├── adapter-contract.md
│   ├── mcp-interface.md
│   ├── security.md
│   ├── development.md
│   └── adr/
├── src/main/java/org/lostrespicamigos/
│   ├── Main.java
│   ├── mcp/
│   ├── agent/
│   ├── process/
│   ├── config/
│   ├── run/
│   ├── workspace/
│   ├── workflow/
│   └── security/
├── src/test/java/...
├── AGENTS.md
├── CONTRIBUTING.md
├── SECURITY.md
├── LICENSE
├── README.md
├── mvnw
├── mvnw.cmd
└── pom.xml
```

Core types include `AgentAdapter`, `AgentRequest`, `AgentCommand`, `AgentResult`, and `RunRecord`. Use typed records and enums rather than maps or `Object` payloads.

Dependencies:

- Official MCP Java SDK, pinned to a stable release.
- Jackson through the SDK or an explicitly pinned Jackson BOM.
- SLF4J with a minimal stderr backend.
- JUnit 5.
- No Lombok.
- No dependency-injection framework.
- No embedded database in v1.

## 6. Persistent run model

Default data location:

```text
${PICAMIGOS_HOME:-~/.picamigos}/
├── locks/
├── runs/{runId}/
│   ├── request.json
│   ├── state.json
│   ├── stdout.log
│   ├── stderr.log
│   └── result.json
└── worktrees/
```

Requirements:

- Write state through a temporary file plus atomic move.
- On startup, mark abandoned `running` records as `aborted`.
- Use `FileChannel.tryLock()` for cross-process provider locks.
- Default to one active invocation per provider.
- Never retry a completed model request automatically.
- Make retention configurable; default to seven days.
- Cleanup only directories with a Picamigos ownership marker beneath the canonical configured home.
- Never follow symbolic links during cleanup.

## 7. Agent adapters

### Codex

Use `codex exec --json --sandbox <policy> -`, pass prompts through stdin, parse JSONL incrementally, capture `thread.started.thread_id`, extract the final agent message, and support `codex exec resume <session-id>`. Do not use `danger-full-access` or deprecated `--full-auto`.

### Claude Code

Use `claude -p --output-format stream-json --verbose` after contract tests confirm stdin behavior. Capture session IDs, support resume, map review to plan permission mode, and define a narrowly tested writable implementation mode. Do not use bypass permissions by default.

### Antigravity

Use `agy --print --print-timeout <duration>`. Treat stdout as text. Support `--conversation` and `--continue`. Determine stdin versus argument prompts through contract tests. Do not invent a JSON flag. Dangerous permission bypass requires both global and per-request opt-ins.

## 8. Workspace and Git isolation

### Read-only snapshot

For planning and review:

1. Verify the directory is a Git repository.
2. Resolve the repository root and reject symlink escapes.
3. Create a detached temporary worktree at the selected commit.
4. For dirty review, generate and apply a binary diff.
5. Copy untracked files only when explicitly requested and within configured limits.
6. Run the reviewer in the temporary worktree.
7. Record but discard reviewer changes with the temporary worktree.
8. Never modify the source worktree.

Document limitations for submodules, Git LFS, ignored files, and large untracked files.

### Implementation worktree

1. Acquire a repository write-workflow lock.
2. Create `picamigos/<workflow-id>/<agent>` from the requested base.
3. Create a dedicated worktree under Picamigos home.
4. Run exactly one writable agent in it.
5. Record resulting commits and working-tree diff.
6. Leave the branch intact.
7. Remove only temporary worktrees after explicit cleanup.
8. Never push, merge, reset, stash, or clean.

Direct writable isolation is disabled by default and requires an explicit configuration opt-in.

## 9. Prompt contracts

Every delegated prompt contains the role, deliverable, exact working directory, access restrictions, original task, previous-stage artifact, verification requirements, and explicit instructions not to delegate, push, merge, reset, or alter unrelated files.

Reviewers should return findings with severity, title, file, line, evidence, recommendation, verification performed, and uncertainties. Codex and Claude may receive provider-supported schemas. Antigravity receives the schema in its prompt and may return plain text. Preserve that text rather than fabricating JSON.

## 10. Security requirements and prohibitions

Implementors must obey all of the following:

- Do not accept arbitrary executable names or shell command templates through MCP.
- Do not expose a generic command execution tool.
- Do not construct commands as one shell string.
- Use `ProcessBuilder` argument lists.
- Do not invoke shell interpreters except for a separately reviewed, unavoidable launcher shim.
- Do not pass API keys, access tokens, cookies, or authentication files through tool arguments.
- Do not log environment variables or copy vendor credential files.
- Do not call the configured host agent by default.
- Propagate `PICAMIGOS_DELEGATION_DEPTH` and reject any inherited depth greater than zero.
- Do not allow child agents to invoke Picamigos recursively.
- Do not run two writable agents in one worktree.
- Do not retry quota errors, permission denials, or completed model calls.
- Do not silently switch providers after failure.
- Do not treat subagent output as executable instructions.
- Do not claim reviewer agreement without host-agent analysis.
- Do not suppress stderr or non-zero exit codes.
- Do not silently truncate prompts or results.
- Do not bind a network port in v1.
- Do not add telemetry, vector databases, RAG, a chat UI, or an orchestration DSL.

## 11. Assignable work packages

### P0 — Repository and decision records

Initialize the repository, Maven Wrapper, Java 21 settings, license, README, AGENTS.md, and architecture decision records.

### P1 — CLI capability spike

Capture sanitized version/help fixtures and prove prompt transport, output, session, exit, timeout, and permission behavior. Record unsupported capabilities explicitly.

### P2 — Domain and configuration model

Implement typed records/enums, command-line and environment configuration, validation, `PICAMIGOS_HOME`, and host-agent configuration.

### P3 — Cross-platform process executor

Implement `ProcessBuilder` execution, stream draining, stdin, timeout, process-tree cancellation, output limits, environment sanitization, executable resolution, and a fake Java agent.

### P4 — Run store and locks

Implement atomic state files, output artifacts, crash recovery, provider locks, and retention cleanup.

### P5/P6/P7 — Provider adapters

Implement and test Codex, Claude, and Antigravity adapters independently against P1 contracts.

### P8 — MCP server surface

Implement stdio bootstrap, doctor, delegate, status, result, cancel, resources, schemas, and protocol tests.

### P9 — Git workspace manager

Implement repository validation, review snapshots, dirty diff transfer, implementation worktrees, canonical path checks, and safe cleanup.

### P10 — Review panel

Implement parallel reviewers, immutable equivalent snapshots, independent results, and partial-failure reporting.

### P11 — Plan/implement/review

Implement the stage machine, bounded handoff, stop/continue behavior, branch metadata, and final review.

### P12 — Hardening

Test malformed output, Unicode, Windows paths, shell metacharacters, large I/O, hangs, cancellation, recovery, concurrency, stale locks, symlink escapes, and recursion.

### P13 — Documentation

Document installation for all three MCP hosts, workflows, security boundaries, quota usage, troubleshooting, and uninstallation.

### P14 — CI and release

Add Windows/macOS/Linux Java 21 CI, CodeQL, dependency review, reproducible JAR, checksums, semantic releases, and no real-agent calls in CI.

## 12. Testing strategy

Use a Java `FakeAgentMain`, not shell scripts, to simulate text, JSONL, stderr, failures, malformed JSON, delays, hangs, child processes, large output, and session IDs cross-platform.

Real-provider tests are opt-in, run in a temporary Git repository, state that quota is consumed, and never run in public CI.

## 13. GitHub workflow

1. Initialize this directory as a standalone repository.
2. Create `los-tres-picamigos` as a public GitHub repository after owner and remote are known.
3. Use one issue per work package.
4. Use feature branches and pull requests into protected `main`.
5. Require the three-platform CI matrix and review.
6. Squash-merge and tag semantic releases.
7. Never force-push or delete protected branches.

## 14. v1 completion criteria

Version 1.0 is complete only when every host can call both adjacent agents, review-panel runs two independent reviewers, implementations use isolated worktrees, reviews cannot mutate the source workspace, cancellation works cross-platform, no API key is required, fake-agent CI passes on all operating systems, real opt-in contracts pass, and the public repository has complete installation and security documentation.
