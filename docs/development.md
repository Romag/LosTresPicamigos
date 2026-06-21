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

## Real-agent contract checks

Real-agent checks are manual and opt-in. Confirm that the selected test is safe and that consuming subscription quota is intended before running it. Never enable them in GitHub Actions.

## Release artifact

`mvn package` produces:

```text
target/picamigos-mcp-<version>-all.jar
```
