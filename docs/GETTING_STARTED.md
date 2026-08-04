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

## Other useful commands

```sh
./npdev normalize ai-model golden-ai-scenarios/base-ai-loop/ai-model.json
./npdev report bootstrap
```

`normalize ai-model` is the first step of the AI-authoring loop (`docs/ai/AI_KNOWLEDGE_LOOP_AND_TOOLING_PLAN.md`).
`./npdev report bootstrap` regenerates the maintainer status reports under `scripts/reports/out/`.

`npdev verify --tier T0|T1|T2|T3` is the one entry point for this repo's verification tiers, from
an inner-loop syntax/schema check (T0, ~1s) up through a full canary build+boot+smoke (T1, a few
minutes) to the complete gate suite (T2) and release-readiness evidence (T3) -- see
`scripts/quality/run-fast-gate.ps1`'s own doc comment for what each tier actually covers.

## Authoring in the editor

`NPDevEditor/ui-react` is the browser-based authoring UI (concepts, fields, flows, panels) as an
alternative to hand-editing model JSON. From `NPDevEditor/ui-react`: `npm install` once, then
`npm run dev` starts a local dev server (Vite prints the URL, typically `http://localhost:5173`).

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
