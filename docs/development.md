# Development

## Requirements

- Java 21 or newer
- Git
- Maven is supplied through the wrapper

## Verification

```shell
./mvnw verify
```

Windows PowerShell:

```powershell
.\mvnw.cmd verify
```

The test suite uses a Java fake agent and temporary Git repositories. It does not call real models.

## Work packages

Choose one independently assignable package from [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md). Keep changes inside the documented package boundaries and update tests first when changing established behavior.

## Commit discipline

- Commit continuously in small, atomic units; do not wait until an entire repository, feature set, or multi-stage task is complete.
- Give every commit one coherent purpose that can be reviewed and reverted independently.
- Keep the repository buildable and internally consistent at each commit boundary.
- Use a short, simple imperative present-tense subject, for example `Add Codex JSONL parser` or `Fix Unix wrapper permissions`.
- Split tests, infrastructure, provider adapters, workflows, and documentation into separate commits when they are independently meaningful.
- Treat a whole-repository implementation in one commit as unacceptable; reconstruct or split the history before handoff when necessary.

## Real-agent contract checks

Real-agent checks are manual and opt-in. Confirm that the selected test is safe and that consuming subscription quota is intended before running it. Never enable them in GitHub Actions.

## Release artifact

`mvn package` produces:

```text
target/picamigos-mcp-<version>-all.jar
```
