# Provider adapter contract

Provider flags must remain in their adapter classes. Update this document and adapter fixtures whenever a verified CLI release changes behavior.

## Codex

Expected invocation:

```text
codex exec --json --sandbox read-only|workspace-write -
```

The prompt is written to stdin. JSONL is parsed for `thread.started` and completed `agent_message` items. Resume uses `codex exec resume` and must be checked by the opt-in real-agent contract suite after CLI upgrades.

Reference: <https://developers.openai.com/codex/noninteractive>

## Claude Code

Expected invocation:

```text
claude -p --output-format stream-json --verbose --permission-mode plan|acceptEdits
```

The prompt is written to stdin. Stream records are inspected for `session_id`, assistant text, and the terminal `result` event. A shell-only npm shim is not launched; doctor reports it unless the corresponding native executable can be resolved.

Reference: <https://code.claude.com/docs/en/cli-reference>

## Antigravity

Locally verified against `agy 1.0.7`:

```text
agy --print --print-timeout 300s [--sandbox] <prompt>
```

The installed help advertises no JSON output mode. Stdout therefore remains plain text. Prompts are arguments until stdin support is verified; a strict length guard prevents operating-system command-line overflow. Resume uses `--conversation`; continuation uses `--continue`.

Reference: <https://antigravity.google/docs/cli-reference>

## Contract-test policy

Real CLI tests are opt-in because they consume subscription quota. Public CI uses `FakeAgentMain` and must never authenticate to a provider.
