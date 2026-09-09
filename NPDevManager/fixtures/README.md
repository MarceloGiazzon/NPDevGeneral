# Manager fixtures — what each one is, and how it was captured

MONITOR_PLAN G1, and `npdev.rs`'s standing rule: **fixtures are CAPTURED, not written.** A
hand-written fixture is a guess about the shape the CLI emits, and the whole reason stub mode exists
is to build the UI against what the CLI *actually* returns.

Every file here is verbatim stdout from a real command. None has been edited by hand — not to
shorten it, not to tidy it, not to make a screen look better. Where a state could not be produced
honestly, it is listed under "not covered" rather than fabricated.

## Doctor / install / database (M2–M15)

| File | Captured from |
|---|---|
| `doctor-all-green.json` | `npdev doctor --json` on a healthy machine |
| `doctor-missing-java.json`, `doctor-no-jars.json` | the same, with the named thing genuinely absent |
| `doctor-wrong-java.json`, `doctor-acceptable-newer-java.json` | **the documented exception** — hand-authored (deps-and-java W1.7), because no Java 11/22 was installed to capture from. Every other file here is a live capture. |
| `db-test-connection-ok.json` | `npdev db test-connection --json` against a real Postgres container |
| `db-test-connection-refused.json` | the same command pointed at port 59999 with nothing listening |
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

**Not covered:** an `npdev host deploy` success carrying an `"npdev"`-sourced `requiredEnv` entry.
Those only appear for a Postgres/MySQL/SqlServer-profiled deployment (`npdev_host.py`'s
`_ENGINE_RUNTIME_PROFILES`), and this machine's local test profile keeps Postgres/MySQL disabled
(`scripts/policy/local-test-profile.json`). The M11 rendering code still reads the split from the
`source` field generically — it does not assume either group is non-empty.

`host.definition.json` on the source app was reset to `rung: 1, target: null` after capture so the
app is left in the same state `host-check-clean.json` was captured from.

## Re-capturing

Fixtures go stale when a CLI contract changes; that is what they are for. Re-capture by running the
command in the table against a real app — never by editing the JSON. If a state cannot be produced
on the machine you are on, add it to a "not covered" list like the one above instead of writing it.
