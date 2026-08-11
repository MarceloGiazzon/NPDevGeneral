# Getting Started

This is the actual first hour: validate a model, generate an app from it, run it, then change the
model and regenerate to see the change land. Start from the repository root. On Linux and macOS,
use the portable `npdev` entrypoint; on Windows use `npdev.bat` with the same arguments (examples
below use `./npdev` — swap in `npdev.bat` and backslash paths on Windows).

Requires Java 17 and, for the run step, Docker.

## 1. Validate a model

```sh
./npdev --version
./npdev validate model NPDevContract/dsl/resources/Models/canonical-demo/model.json
```

`validate model` runs full structural + semantic validation by default (it shells out to Gradle,
so expect a few seconds) and exits non-zero with a diagnostic report if the model has a real
defect. Pass `--structural-only` for a fast JSON-Schema-only check that skips Gradle entirely --
useful as an inner-loop syntax check, but its success message says explicitly that semantic checks
did not run, so it can't be mistaken for the full check. `--semantic` still works too, as a
documented no-op alias (it's already the default).

## 2. Generate and run an app

```sh
./npdev generate app \
  --model NPDevContract/dsl/resources/Models/canonical-demo/model.json \
  --config NPDevContract/dsl/resources/Models/canonical-demo/config.json \
  --output /path/outside/this/repo/canonical-demo-app
cd /path/outside/this/repo/canonical-demo-app
cp .env.example .env    # set NPDEV_AUTH_APIKEYS at minimum
docker compose up
```

The output directory is a complete, buildable Spring Boot project with its own README describing
that specific app (namespace, version, where the admin UI/REST API/login route are). Point
`--output` somewhere outside this repo -- generated apps are not meant to live inside
`NPDev_General` (see `docs/BUILD_OUTPUT_LOCATION_POLICY.md`). Full deployment options
(Postgres-first production path, env-var reference, the mail-catcher profile) are in
`docs/DEPLOYMENT.md`.

## 3. Change the model, regenerate

Edit the model you generated from -- add a field, add a concept, anything small -- then re-run
step 1 (validate; catch mistakes before regenerating) and step 2's `generate app` command again
against the same `--output`. The generator diffs the new shape against what's already there
(schema evolution, not drop-and-recreate) -- see `docs/DATABASES_AND_MIGRATIONS.md` for how
renames and destructive changes are handled.

Once you have done that by hand once, stop doing it by hand:

```sh
npdev dev
```

`npdev dev` generates, builds, boots, and then watches your model -- every save re-runs the whole
sequence and restarts the app. It validates first, so a typo prints an error and leaves the running
app alone rather than taking it down.

**Now build something of your own.** `canonical-demo` is a fixed fixture -- copying it, changing
it, and giving it a database that actually keeps your data is the real next step:
`docs/YOUR_FIRST_APP.md`.

## Other useful commands

```sh
./npdev doctor
./npdev normalize ai-model golden-ai-scenarios/base-ai-loop/ai-model.json
./npdev report bootstrap
```

`doctor` checks this machine is ready -- Java 17 and `JAVA_HOME` agreement, Python, git, disk
space, and whether the runtimehost jars (`./npdev setup`) and AI knowledge index are staged; safe
to run any time. `normalize ai-model` is the first step of the AI-authoring loop
(`docs/ai/AI_KNOWLEDGE_LOOP_AND_TOOLING_PLAN.md`). `./npdev report bootstrap` regenerates the
maintainer status reports under `scripts/reports/out/`.

`./npdev init my-app` scaffolds a new app directory (model/config/a real database/README/git
history) instead of copying `canonical-demo` by hand -- see `docs/YOUR_FIRST_APP.md`. `./npdev mcp
install --client claude-code` (or `claude-desktop`, or `--print`) connects an AI client to NPDev's
MCP tools -- see `docs/AUTHORING_WITH_AI.md`.

### Driving NPDev from a program, not a terminal

`doctor`, `setup`, and `init` all accept `--json`, which is an *additive* second output mode --
the human summary printed without the flag is unchanged (byte-for-byte), so this is safe to add to
an existing script without breaking anyone reading the terminal output. Pass `--json` and nothing
but a machine-readable object (or, for `setup`, a stream of them) goes to stdout; any narration
moves to stderr instead.

- **`npdev doctor --json`** emits one `npdev-cli-result.v1` object with a `checks` array -- one
  record per check (`id`, `status`: `pass`/`fail`/`warn`, `found`, `expected`, `detail`, `fix`,
  `fixCommand`), including the ones that passed (the human summary only ever prints failures and
  warnings, so passing checks are otherwise invisible to a caller). `java-present`/`java-version`
  resolve `JAVA_HOME` before falling back to PATH, matching Gradle's own resolution order -- a
  private JDK works even with nothing on PATH at all.
- **`npdev setup --json`** emits one `npdev-cli-event.v1` object per line (JSON Lines) as each
  phase (`jars`, `knowledge-index`) starts and finishes, ending with one `npdev-cli-result.v1`
  object. `setup` tries a prebuilt `runtimehost-libs-<tag>.zip` Release asset first on a tagged
  commit (checksum-verified against a sibling `SHA256SUMS`), and always falls back to a full local
  build -- untagged commit, no asset published, download failure, checksum mismatch, or
  `--build-local` passed. Either way, the result object's `jarsSource` field (and a trailing
  `Jars source:` line in the human output) says which path was actually taken.
- **`npdev init my-app --json`** emits one object with `created.directory` (always absolute --
  a calling program has no shared working directory to resolve a relative path against),
  `created.files`, `gitInitialised`, and `nextCommand`. `gitInitialised` is `false` on a machine
  with no git — the scaffold still succeeds and `gitIdentityNotice` carries the sentence explaining
  what is missing.

This exists for the same reason `npdev run app`/`npdev verify` already print structured JSON: an
AI agent driving NPDev through MCP, CI asserting on fields instead of grepping sentences, or a
future GUI (the NPDev Manager) all need a result they can parse, not prose meant for a human.

`npdev verify --tier T0|T1|T2|T3` is the one entry point for this repo's verification tiers, from
an inner-loop syntax/schema check (T0, ~1s) up through a full canary build+boot+smoke (T1, a few
minutes) to the complete gate suite (T2) and release-readiness evidence (T3) -- see
`scripts/quality/run-fast-gate.ps1`'s own doc comment for what each tier actually covers.

## Authoring in the editor

The browser-based authoring UI (concepts, fields, flows, panels) as an alternative to hand-editing
model JSON is already built into every app you generate -- open **`/npdev-ui-react/`** on your
running app (e.g. `http://localhost:8080/npdev-ui-react/`). No separate install or server needed.

`npm run dev` inside `NPDevEditor/ui-react` (Vite, typically `http://localhost:5173`) is the
**editor's own development server** -- for someone changing the editor's source code, not for
authoring a model. Run against a bare `npm run dev` it has no generated app to talk to and shows
"unavailable" everywhere; that is expected for that workflow, not a bug in the one above.

## When running from another directory

Set `NPDEV_ROOT` to the workspace root before invoking `npdev`.

```sh
export NPDEV_ROOT="$(pwd)"
./npdev validate model NPDevContract/dsl/resources/Models/canonical-demo/model.json
```

## PowerShell scripts

PowerShell scripts under `scripts/quality` (`run-all-gates.ps1`, `run-fast-gate.ps1`, and friends)
are the primary maintainer path for this repo's own development, not a legacy compatibility layer
-- `npdev verify` above shells out to them directly. The portable `npdev`/`npdev.bat` commands in
this doc are what an app author or an AI-authoring agent needs; the PowerShell scripts are what a
platform maintainer runs to validate a change to NPDev itself.
