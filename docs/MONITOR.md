# The Monitor and the Scrap Manager

Two screens in the [NPDev Manager](MANAGER.md), and the CLI verbs underneath them.

**The Monitor** answers "what is on this machine, and is it alive?" for every generated app.
**The Scrap Manager** answers "does this app still work in a real browser, and did that change?"

Everything either screen can do, a terminal can do first. That is not a style preference: the
Manager is a window onto `npdev`, so a capability that exists only in the window is a capability a
terminal user does not have, and a fixture the Manager's stub mode shows is a real CLI answer that
was captured rather than a shape somebody guessed.

---

## 1. `npdev monitor` — discovery, probing, engine detection, logs

Read-only. `scan` and `probe` never modify an app.

| Command | What it answers |
|---|---|
| `npdev monitor scan --paths "<p1>;<p2>" [--depth 4] [--json]` | every generated app under those paths |
| `npdev monitor probe --app-dir <d> [--include-info] [--json]` | one app — the Monitor's refresh unit |
| `npdev monitor engine [--port 3010] [--root <r>] [--json]` | where the ScrapForAI engine is, if anywhere |
| `npdev monitor logs --app-dir <d> [--source app\|ops\|manager\|all] [--tail N] [--follow]` | what an app actually printed |
| `npdev monitor logs export --app-dir <d> --out <zip>` | one file you can send to whoever is helping |
| `npdev monitor ops --app-dir <d> --script <key> [--confirm <token>]` | run one of the app's own `_ops` scripts |

### How an app is recognised

By **contents**, never by name (REG-144). A directory is a generated app when it has an `_ops`
directory **and** either a `.npdev-root` marker (the marker pair) or `_ops/resolved-db-plan.json`.

The second rule is not a weakening. Measured 2026-08-10: nothing had ever emitted `.npdev-root` into
a generated app — it exists once, at this repo's own root — so a marker-pair-only scan found **zero**
apps, including every app a tester already had. The generator now writes the marker (making the
documented invariant true from here on), and discovery accepts the resolved plan as well, which is
strictly stronger evidence anyway: a plan names the appId, the engine and the port, where a marker is
an empty assertion. Every record reports which rule matched, in `discoveredBy`.

A directory with `.npdev-root` and no `_ops` is **not** an app and is not listed.

### Generated facts vs probed facts

`info.json` (emitted with the app) carries only what survives being copied to another machine: URLs,
monitoring endpoints, flows, concepts, the DB engine *name*. It never carries an absolute path.

Everything machine-specific — the jar, the `_ops` toolbox, the model, the DB file, the super-user key
file, the port, the PID, health, container state — comes from `probe`, at display time. Baking those
into generated output is precisely the defect PORT-1 removed from six emitters.

### Logs (three sources, one surface)

1. `<app>/logs/app-<timestamp>.log` — the app's own stdout+stderr, written by `_ops/Run-FinalApp.ps1`.
2. `<app>/logs/ops-<script>-<timestamp>.log` — every `_ops` script run through `npdev` or the Manager.
3. The Manager's own log, under its home directory.

`<app>/logs/` joins `<app>/data/` on the spared-from-wipe list, so a rebuild does not destroy the
evidence of why the last run failed.

**`export` is the deliverable.** It produces one zip containing the app + ops logs, the probe
snapshot, the app's `info.json`, `resolved-db-plan.json` **with credentials redacted**, and the last
few exploration runs. Redaction is not optional: the plan carries a database password and the whole
point of an export is that it leaves the machine.

---

## 2. `npdev explore` — browser explorations

| Command | What it does |
|---|---|
| `npdev explore list --app-dir <d>` | definitions + run history, newest first |
| `npdev explore show --app-dir <d> --run <id>` | one full run record with resolved blob paths |
| `npdev explore validate --file <routine.json>` | schema check against the pinned engine schema, plus lint |
| `npdev explore preflight --app-dir <d>` | each precondition as its own row |
| `npdev explore run --app-dir <d> --file <routine.json>` | run it, judge it, record it |
| `npdev explore record --from-file <result.json>` | record a result produced by another driver |
| `npdev explore accept --app-dir <d> --run <id>` | make this run the baseline |
| `npdev explore pin --app-dir <d> --run <id> [--ledger REG-nn]` | keep its evidence indefinitely |
| `npdev explore prune --app-dir <d>` | blob retention |
| `npdev explore context --app-dir <d>` | the assistant's context pack |

### The routine schema is the engine's, pinned

`schemas/ai/scrapforai-routine.schema.json` is a **pin** of what a running ScrapForAI engine served
at `GET /v1/schema/routine.request.json`. It is never hand-written and never hand-edited.

Why: the engine defines 32 actions; the 42-routine corpus uses 16. A schema induced from the corpus
would reject the other 16 and would miss every constraint examples cannot show — `label` ≤ 160,
`selector` ≤ 500, `value` ≤ 5000, `stepId` ≤ 80, `timeoutMs` ≤ `STEP_TIMEOUT_MS`, and `evaluate`/`watch`
gated behind `ALLOW_EVALUATE`. All five were found by being **rejected at runtime**, never by reading
a routine.

So the corpus is a *conformance test* against the pin, run by
`scripts/quality/check-routine-corpus-conformance.py` in the AI-knowledge gate. **A rejection there is
a defect in the routine.** To re-pin after an engine upgrade, start the engine and run:

```
python scripts/quality/pin-routine-schema.py --port <engine port>
```

It refuses to derive a schema from the engine's source when no engine is answering. A schema that is
a reading of the code is not the contract the engine enforces, and the difference is invisible until
something is rejected.

A checked-in **routine file** is not byte-identical to an engine **request**: it carries NPDev's own
`targetPath` (so routines stay port-agnostic) where the engine requires an absolute `targetUrl`. One
composer builds the request — used by `validate`, by `run`, and by the conformance gate — so "valid
here" means "runs in the harness".

### One definition of green

Green = the routine's steps passed **and** zero console/page/external errors, minus a declared
allowlist. It is implemented once, in the CLI, and applied to every driver: the PowerShell harness
and the Playwright reporter record *through* `npdev explore record` rather than each judging for
itself. Two implementations of "green" is how green quietly comes to mean different things.

The allowlist is mandatory — every NPDev app emits a `theme.css` 404 when it declares no custom theme
and a 401 on the pre-auth first load, so with no allowlist every routine is red forever. It is also
**narrow, conditional and audited**:

- the `theme.css` 404 is excused **only when the app declares no custom theme**;
- the 401 **only on the first navigation**;
- never "all 404s";
- per-app overrides live in `_ops/exploration-config.json`;
- and **every excuse is recorded on the run** in `verdict.excused`, with the rule that forgave it.

That last point is the whole design. An excuse nobody can see outlives the reason it was written for
— structurally the same defect as a `continue-on-error` that silently swallows four commands.

### Storage and retention

```
<app-definition>/explorations/*.json          definition (the truth)
<app>/_ops/explorations/*.json                read-only mirror, carried by the app
<app>/_ops/exploration-runs/runs.jsonl        append-only index, one line per run
<app>/_ops/exploration-runs/<runId>/run.json  the full record
<app>/_ops/exploration-runs/blobs/<sha256>    content-addressed screenshots
```

Platform-scoped runs (the editor e2e suites, which test NPDev rather than an app) live under
`<Build>/npdev-explorations/platform/<suite>/` — same schema, same screen, a scope filter.

**Records are never deleted.** `runs.jsonl` and every `run.json` are text and stay forever. Only
blobs are pruned: the last 10 runs per scenario, plus every red run under 30 days, plus anything
`pin`ned or linked to a ledger item. A prune prints what it removed *and* what it kept and why —
a silent prune is how evidence disappears without anyone deciding to lose it.

### Attribution

Every run carries three hashes: `definition.contentSha256`, `target.modelSha256` and
`target.platform`. With those, a red run is attributable rather than investigated — "same routine,
same model, new platform" names the culprit.

---

## 3. The Manager screens

**Monitor** — a card per app (or a dense list; `⊞`/`☰` toggles). Status tint from `probe`. Per card:
Open URL, Start/Stop, Test DB, the runbook strip, the inspector, Logs, and "Explore this app". Cards
are tagged with their `origin` — a registered Manager app, or one found by scanning an inspect path.
Inspect paths are editable in the window and persist in `manager.json`.

**Scrap Manager** — app picker → run history → run detail (verdict, identity, evidence with excused
errors shown rather than hidden, per-step timeline, screenshots) → create/validate/play.

The engine is **discovered, not configured**: a running service on `127.0.0.1:3010` first (which
proves it exists without knowing where it lives, and is the only signal that finds an engine someone
started by hand), then a declared root, then candidates derived from this machine's own layout. Only
if all of that fails does the screen ask for anything — and then it says plainly that the engine is
not installed rather than failing silently. No path literal appears anywhere in the detection.

---

## 4. Checking the screens

Two things cover the two halves, and neither covers the other:

```
NPDevManager --selftest                              the Tauri commands, headless
node scripts/quality/check-manager-ui-render.mjs     what the screens actually DRAW
```

The second renders `monitor.js` and `scrap.js` in a real browser against the captured fixtures and
asserts 20 things about the result — that a port conflict is drawn as its own state rather than as
running, that probed rows are visually distinct from generated ones, that the failing step is
highlighted, that Play stays disabled until the CLI says VALID, and that neither screen logs a
console error. It found two defects on its first run: the card wall squeezed into a 470px column,
and Playwright's raw ANSI codes printed through the middle of a failure message.

It needs Playwright, which is not a dependency of this repo; it resolves one from the machine and
SKIPS with a reason rather than failing when there is none.

## 5. Related

- [MANAGER.md](MANAGER.md) — the Manager as a whole
- [FLOWS.md](FLOWS.md) — the durable flow engine
- [UI_CONTRACT.md](UI_CONTRACT.md) — the frontend contract
