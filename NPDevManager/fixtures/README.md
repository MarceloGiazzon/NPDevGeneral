# Manager fixtures — what each one is, and how it was captured

MONITOR_PLAN G1, and `npdev.rs`'s standing rule: **fixtures are CAPTURED, not written.** A
hand-written fixture is a guess about the shape the CLI emits, and the whole reason stub mode exists
is to build the UI against what the CLI *actually* returns.

Most files here are verbatim stdout from a real command, not edited to shorten, tidy or flatter a screen. Some are hand-authored
or trimmed — `NPDevManager/src/npdev.rs` records which (for example `prompter-generate.json` is hand-authored). Where a state could not be produced
honestly, it is listed under "not covered" rather than fabricated.

## Doctor / install / database (M2–M15)

| File | Captured from |
|---|---|
| `doctor-all-green.json` | `npdev doctor --json` on a healthy machine |
| `doctor-missing-java.json`, `doctor-no-jars.json` | the same, with the named thing genuinely absent |
| `doctor-wrong-java.json`, `doctor-acceptable-newer-java.json` | hand-authored (deps-and-java W1.7), because no Java 11/22 was installed to capture from. They are not the only hand-written fixtures here (see above). |
| `db-test-connection-ok.json` | `npdev db test-connection --json` against a real Postgres container |
| `db-test-connection-refused.json` | the same command pointed at port 59999 with nothing listening |
| `db-detect.json` | `npdev db detect --json`, produced by the CLI's own `detect()` with injected `docker ps` rows (no Docker daemon on the authoring machine) -- shape is the CLI's, the two containers are made up |
| `init-result.json` | `npdev init --json` |
| `engines.json` | `python NPDevCli/npdev_cli.py engines --json` on 2026-08-12 — six engines, all `supported`, two carrying real caveats (MySQL's implicit DDL commit, SQL Server's row-cap/NVARCHAR differences). Feeds the Ready screen's engine panel and the New-app dropdown in stub mode. |
| `setup-events.jsonl`, `dev-events.jsonl` | the JSON Lines streams of `npdev setup --json` / `npdev dev --json` |

## The Monitor (MONITOR_PLAN B1), captured 2026-08-10

| File | Captured from | Covers |
|---|---|---|
| `monitor-scan-mixed.json` | `npdev monitor scan --paths "<5 real app dirs>" --depth 1 --json` | **running** (identity confirmed), **port-conflict** (a different app holds the port), **stopped** ×3, engines H2Local / H2Server / MySQL |
| `monitor-scan-empty.json` | the same command against an empty directory | the "no apps here" state, `ok: true`, `apps: []` |
| `monitor-probe.json` | `npdev monitor probe --app-dir <live app> --include-info --json` | one running app with its generated `info.json` inlined — the inspector's data |
| `monitor-engine-running.json` | `npdev monitor engine --port 3000 --json` with the engine really listening | D9 step 1, `via: service-probe` |
| `monitor-engine-stopped.json` | `npdev monitor engine --port 3010 --json`, engine installed but not listening | D9 step 3, `via: derived-candidate` |
| `monitor-engine-missing.json` | the same command with `NPDEV_ROOT` pointed at an isolated empty directory, so the derived candidates genuinely resolve to nothing | D9 step 4, `state: not-found` |
| `monitor-logs.json` | `npdev monitor logs --app-dir <live app> --tail 40 --json` | the app's real stdout, plus the "nothing written yet" detail for the ops and manager sources |

**Not covered by `monitor-scan-mixed.json`, and deliberately not faked:** `health: error` (an app
answering `/actuator/health` with a non-UP body) and `health: unknown` (a resolved plan naming no
`serverPort`). Both render through the same code path as the three states that ARE covered — only the
`health` string differs — but nobody should read this file as proof that all five states were seen.

## The Scrap Manager (MONITOR_PLAN D1), captured 2026-08-10

All from real routine runs against a live generated app, driven by a real ScrapForAI engine.

| File | Captured from | Covers |
|---|---|---|
| `explore-list.json` | `npdev explore list --app-dir <live app> --json` | two definitions, five runs, mixed verdicts |
| `explore-run-green.json` | `npdev explore show --run <a passing run> --json` | 5 passed steps, a stored screenshot blob, `verdict.green: true` |
| `explore-run-red.json` | `npdev explore show --run <a failing run> --json` | `failedStepIndex: 1`, the engine's real `TimeoutError`, evidence persisted |
| `explore-preflight.json` | `npdev explore preflight --app-dir <live app> --json` | four passing precondition rows (D4) |
| `explore-validate-ok.json` | `npdev explore validate --file <a real routine> --json` | valid, with lint info rows |
| `explore-validate-bad.json` | the same, against a routine with `clickk` and a `waitForSelector` missing `state` | the two message shapes the Validate button shows verbatim |

The red run is genuinely red: the routine waits for `#this-element-does-not-exist`. Nothing was
edited to make it fail.

## The Share screen (NPDEV_MANAGER_SHARE_IMPLEMENTATION_PLAN M2), captured 2026-09-09

All against `D:\WorkSpace\NPDev\Build\generated-finalapps\wmsoffice\App`, an H2Server-engine app.
`cloudflared` was installed for this capture (`scoop install cloudflared`) so the live state could be
captured honestly rather than left "not covered".

| File | Captured from | Covers |
|---|---|---|
| `host-check-needs-fixing.json` | `npdev host check --app <app> --json`, before any `--fix` ran | 6 `fix` findings, 1 `warn`, 5 `ok` — the state "Fix N things, then share" is built on |
| `host-check-clean.json` | the same command after `host check --fix`, with the app and its H2 TCP server both running | every finding `ok`, `counts.fix: 0` |
| `host-status-down.json` | `npdev host status --app <app> --json` with no tunnel running | `up: false`, `state: {}` |
| `host-status-up.json` | the same command while `npdev host share` had a real cloudflared quick tunnel open | `up: true`, `state.routedApps` as the array of `{name, slug, port}` (not a count — see the `host share` fix this fixture drove, npdev_cli.py `run_host_share`) |
| `host-deploy-mismatch.json` | `npdev host deploy --app <app> --json` with `host.definition.json` hand-set to `target: render-neon` (Postgres-only) against this H2 app | `ok: false`, `code: "ENGINE_MISMATCH"`, the three-step fix `detail` |
| `host-deploy-written.json` | `npdev host plan --target render-h2 --rung 3 --yes` (render-h2 requires no particular engine) then `npdev host deploy --json` | `written[]` (two file chips), `requiredEnv` with two `"you"`-sourced vars |
| `host-targets.json` | `npdev host plan --app <app> --list-targets --json` | the ladder's 7 targets, each annotated `compatible`/`incompatibleReason` against this app's H2Server engine -- see the `--list-targets` flag this fixture drove, npdev_cli.py `_run_host_plan_list_targets` |
| `host-keys-list.json` | `npdev host keys --app <app> --json` | one masked key, `{masked, tenant, actor, role}` -- never the raw value |
| `host-keys-new.json` | `npdev host keys --app <app> --new --json` | the raw key exactly once, immediately after minting (M9's one exception to "never render a key in full") |
| `providers-list.json` | `npdev providers list --json` | store status + each AI provider's key SOURCE (vault/env/missing/none-needed) -- never a key |
| `providers-ok.json` | `npdev providers add <id> --key-stdin --json` (also remove / export-env in fake mode) | `{ok, provider, store}` |
| `providers-test.json` | `npdev providers test <id>` | the key authenticated: model count and seconds, no key |

**Not covered:** an `npdev host deploy` success carrying an `"npdev"`-sourced `requiredEnv` entry.
Those only appear for a Postgres/MySQL/SqlServer-profiled deployment (`npdev_host.py`'s
`_ENGINE_RUNTIME_PROFILES`), and this machine's local test profile keeps Postgres/MySQL disabled
(`scripts/policy/local-test-profile.json`). The M11 rendering code still reads the split from the
`source` field generically — it does not assume either group is non-empty.

`host.definition.json` on the source app was reset to `rung: 1, target: null` after capture so the
app is left in the same state `host-check-clean.json` was captured from.

## Packs (W3.2), captured 2026-09-14

| File | Captured from | Covers |
|---|---|---|
| `pack-list.json` | `python NPDevCli/npdev_cli.py pack list --model D:\WorkSpace\NPDev\AppGen\apps\_experiments\WmsOfficePackTest\definition\model.json` | Three real resolved packs: two local (`identity`, `workspace`, no `signature` field — nothing to sign), one remote and unsigned (`user`, from the real NPR repo, `signature.status: "unsigned"` after a real `pack update --allow-unsigned` run) |
| `pack-list-deprecated.json` | The same command against a throwaway local app+pack built only to produce this fixture honestly (no real published pack was deprecated to get this): a local pack `oldwidgets`, deprecated via a real `npdev pack deprecate --write` call (W3.1) naming `newwidgets@2.0.0` as successor, then resolved with a real `npdev pack add` | The deprecated-banner state on a pack row |
| `pack-why.json` | `python NPDevCli/npdev_cli.py pack why --model <same WmsOfficePackTest model> identity` | `identity`'s one real dependent (`user needs ^1.0 via app -> user`) |

**A real bug was caught capturing these, not merely a UI gap:** `_enrich_pack_list_report` and
`_check_deprecated_packs` (npdev_cli.py) both resolved a LOCAL pack's `sourcePath` against the
CLI process's current working directory instead of the model's own directory — a relative
`sourcePath` (the normal shape for a local pack) silently found nothing whenever those differed,
which running `pack list` from the repo root against an app living elsewhere always does. Fixed
by `_resolve_pack_source_path` before any fixture here was captured; the deprecated-fixture
capture above is what surfaced it live.

## AI Providers tab (AIP-4), captured 2026-10-22

Every `ai-*.json` is verbatim `python NPDevCli/npdev_cli.py ai <command> --json` run with
`NPDEV_AI_CONFIG` and `NPDEV_AI_USAGE_LOG` pointing at a scratch overlay, never the real one. The overlay
was built with real `npdev ai` writes: an `lmstudio` provider (local, keyless) with two models, one
disabled; a `local-coder` profile falling back to `fast`; `bind prompter local-coder,deep`;
`spend allow-paid review`; `spend cap 20`. Only the scratch paths were replaced with `/home/user/...`.

| File | Captured from | Covers |
|---|---|---|
| `ai-providers.json` | `ai providers --json` | shipped + yours, kind, protocol, key source (never a key) |
| `ai-models.json` | `ai models --all --json` | prices, effort levels, an enabled and a disabled model of yours |
| `ai-profiles.json`, `ai-bindings.json` | `ai profiles --json`, `ai bindings --json` | fallback chains; a binding you changed (`overridden`) |
| `ai-spend.json` | `ai spend --json` | one paid opt-in, a monthly cap |
| `ai-usage.json` | `ai usage --json` | **over a seeded usage log**: no key or local LLM was available to make real calls, so the 13 log records were written with `npdev_ai_client.UsageLog.append`; the summary itself is the real CLI's |
| `ai-detect.json` | `ai detect --json` | nothing answering -- the honest empty state |
| `ai-test.json` | `ai test local-coder --json` | a real failure: nothing on :1234 and no keys for the fallbacks |
| `ai-app.json` | `ai app --app-dir <scratch App> --model NPDevSamples/dsl-conformance-max/Input/model.json --json` | a model opting into gemini (key set in the app's `agent-proxy.env`) and groq (no key anywhere): one ready, one not |
| `ai-write-ok.json` | `ai providers set lmstudio --default-model ... --json` | the answer every change gets |
| `ai-write-refused.json` | `ai profiles add bad --provider nope --json` | a refused change (not wired into fake mode; kept as the error shape) |

Not covered: a successful `ai test` and a `detect` that finds a server (neither could be produced here).

## Re-capturing

Fixtures go stale when a CLI contract changes; that is what they are for. Re-capture by running the
command in the table against a real app — never by editing the JSON. If a state cannot be produced
on the machine you are on, add it to a "not covered" list like the one above instead of writing it.
