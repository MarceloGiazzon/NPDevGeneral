# Getting Started

Start from the repository root. On Linux and macOS, use the portable `npdev` entrypoint for the core maturity workflow:

```sh
./npdev --version
./npdev validate model NPDevContract/dsl/resources/Models/canonical-demo/model.json
./npdev normalize ai-model golden-ai-scenarios/base-ai-loop/ai-model.json
./npdev generate app --model NPDevContract/dsl/resources/Models/canonical-demo/model.json --config NPDevContract/dsl/resources/Models/canonical-demo/config.json --output build/npdev-generated
./npdev report bootstrap
```

`validate model` runs full structural + semantic validation by default (it shells out to Gradle, so
expect a few seconds); pass `--structural-only` for a fast JSON-Schema-only check that skips Gradle
entirely -- its success message says explicitly that semantic checks did not run, so it is never
mistaken for the full check. `--semantic` still works as a documented no-op alias.

When running from another directory, set `NPDEV_ROOT` to the workspace root before invoking `npdev`.

```sh
export NPDEV_ROOT="$(pwd)"
./npdev validate model NPDevContract/dsl/resources/Models/canonical-demo/model.json
```

On Windows, use `npdev.bat` with the same arguments:

```bat
npdev.bat --version
npdev.bat validate model NPDevContract\dsl\resources\Models\canonical-demo\model.json
```

PowerShell scripts under `scripts/quality` are retained for compatibility and report production, but portable commands should be preferred for core model validation, AI model normalization, app generation, and report bootstrap.
