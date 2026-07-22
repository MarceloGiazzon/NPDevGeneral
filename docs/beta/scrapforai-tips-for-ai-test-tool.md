# Using ScrapForAI as a test tool — practical tips for an AI project

A field guide for any project (and any AI agent) that wants to use Marcelo's
**ScrapForAI** exploration runner as an automated browser-verification tool. These
are hard-won, hands-on tips — the things that turn "fighting the tool" into "using
it well." The authoritative API contract is always `GET /v1/schema` (it serves the
JSON Schemas for the request and the result); this doc is the practical layer on top.

---

## 1. What it is, and when to reach for it

ScrapForAI is a **security-first HTTP gateway over Playwright**. You POST a
**declarative JSON "routine"** (a list of browser steps); it runs them in a real
(headless) browser and returns **structured evidence** — per-step pass/fail, console
errors, page errors, network failures, unexpected external requests, screenshots,
trace, DOM text, and any values you extracted.

**Reach for it when** you need deterministic, replayable, machine-assertable
verification of a *rendered web UI* — i.e. the things HTTP/API tests cannot see:
client-side rendering, JS console/page errors, navigation, forms, and
create/edit-through-the-UI write paths. It's ideal as a CI sidecar or an agent's
"perceive + act on a web page" tool.

**Don't reach for it when** you need an autonomous agent that *decides* what to
click from natural language, or vision/coordinate-based acting, or to drive a
constantly-changing third-party UI. It's a deterministic **executor**, not a
planner — the intelligence stays in your caller.

## 2. Mental model (three pillars)

1. **Declarative routine = contract.** You don't write Playwright code; you emit
   JSON validated against a published schema. That makes runs reproducible and easy
   for an LLM to author/repair.
2. **Evidence = the assertion surface.** The return value is a JSON evidence bundle.
   You branch/assert on it (status, `consoleErrors`, `pageErrors`, etc.), not on a
   human-readable report.
3. **Security boundary.** It treats the caller as untrusted: SSRF origin allowlist,
   secret redaction, and `evaluate` gating. Configure these deliberately.

## 3. Quick start (and the two failures everyone hits first)

Run it as a service and POST to it. Endpoints: `POST /v1/explorations/run`,
`POST /v1/explorations/inspect-dom`, `GET /v1/schema` and `GET /health` (these two
need no auth). Everything else needs `Authorization: Bearer <SCRAPFORAI_API_KEY>`.

Minimum env: `SCRAPFORAI_API_KEY` (**≥16 chars**) and `ALLOWED_TARGET_ORIGINS`
(**at least one origin, required**).

> **First failure #1 — SSRF allowlist.** Targets on `localhost`/private IPs/cloud
> metadata are **blocked** unless the *exact origin* is in `ALLOWED_TARGET_ORIGINS`.
> If your first `goto` returns HTTP 400 `TargetUrlNotAllowed`, this is why. Set it to
> your app's origin (e.g. `http://localhost:8080`). Also put your app's origin in
> `ALLOWED_RESOURCE_ORIGINS` so its own JS/CSS aren't flagged as
> `unexpectedExternalRequests`.

> **First failure #2 — IPv4 vs `localhost`.** The server may bind IPv4 only; Windows
> (and some setups) resolve `localhost` to IPv6 `::1` first, so your client can't
> connect. **Call the scraper over `127.0.0.1`**, not `localhost`. (This is about
> reaching the *scraper*; the *target* origin string must still match your allowlist
> exactly, e.g. `http://localhost:8080`.)

Other useful env knobs (defaults in parentheses): `PORT` (3000), `HOST` (0.0.0.0),
`STEP_TIMEOUT_MS` (10000), `JOB_TIMEOUT_MS` (60000), `MAX_STEPS_PER_JOB` (50),
`MAX_PARALLEL_JOBS` (2 → excess returns 429), `ALLOW_EVALUATE` (false),
`ARTIFACT_DIR` (where screenshots/traces/results are written),
`ARTIFACT_RETENTION_HOURS` (4), `PLAYWRIGHT_BROWSER` (chromium).

> **Tip:** Don't mutate a checked-in `.env`. The server uses dotenv, which **does
> not override already-set process env** — so pass overrides as process env (and, if
> you want total control, launch from a working directory that has no `.env`).

## 4. The verification loop

1. **Discover** with `POST /v1/explorations/inspect-dom` (`{ targetUrl, waitUntil,
   includeHiddenControls, includeConsole, includeNetwork }`). It returns `title`,
   `bodyText`, `forms[]`, and `visibleControls[]`/`allControls[]` — each control with
   ready-made candidate `selectors`, `name`, `type`, `labelContext`. Use this to
   learn real selectors instead of guessing.
2. **Author** a routine targeting those selectors.
3. **Run** it; read the evidence.
4. **Fix** (your app, or the routine) and re-run. Commit routines + a small evidence
   summary so they're regression assets, not one-offs.

## 5. Authoring routines — tips

**Step palette (by category):**
- Navigate: `goto` (supports the literal `$targetUrl` for the request's target),
  `reload`, `waitForLoadState`, `waitForUrlContains`.
- Interact: `fill`, `click`, `doubleClick`, `hover`, `press` (key), `selectOption`,
  `check`, `uncheck`.
- Synchronize: `waitForSelector` (state), `waitForResponse` (urlContains/method/
  status), `waitForTimeout`, `waitForElapsed` (relative to a prior step's `id`).
  Every step also supports `waitFor`/`afterWaitFor` pre/post conditions and a
  `frame` selector.
- Assert: `assertVisible`/`assertHidden`/`assertEnabled`/`assertDisabled`,
  `assertTextContains`, `assertUrlContains`, `assertCount` (operator + count).
- Capture: `screenshot` (name), `collect` (`["console","network","domText","url",
  "pageErrors"]`), `extractText`, `extractAttribute`.
- Scripted (gated by `ALLOW_EVALUATE`): `evaluate`, `watch` (sampled evaluate).

**Selectors must be single-match.** A selector that resolves to >1 element throws a
strict-mode error. Prefer ids, `[name="..."]`, or stable attributes; scope to a
container or the visible element when several similar nodes exist.

**Prefer waits over sleeps.** `waitForSelector`/`waitForResponse`/`afterWaitFor` are
deterministic; `waitForTimeout` is a last resort and makes runs flaky/slow.

**Keep secrets and per-run values out of the JSON.** Use `value` |
`valueFromCredential` | `valueFromVariable` (exactly one per value step). Pass
`credentials`/`variables` in the request; **credentials are redacted** from the
evidence and logs. Inject per-run-unique values (e.g. a timestamped record key) via
`variables` so reruns don't collide on unique constraints.

**Make routines portable.** Start with `goto $targetUrl` and inject `targetUrl` at
call time, so the same routine runs against any environment/port.

**SPA auth pattern.** Each job is a fresh browser context (no cookies/localStorage
carried over — see §8), so authenticate inside every routine. If the app reads a
token from a field/localStorage: `fill` the field → `reload` → wait for a
post-auth element. Don't assume a prior routine "logged you in."

**Assert post-state, not just the action.** A `click` "passing" only means the click
happened. Assert the *consequence* (an element appears/changes, a response arrives,
a modal closes). Example pattern: if a form modal closes only on a successful
submit, `waitForSelector <modal> state=detached` is a reliable success signal.

## 6. Reading the evidence — recommended pass/fail policy

The result has top-level `status` (`passed`/`failed`), `failedStepIndex?`, `error?`,
`steps[]`, `extracted{}`, and an `evidence{}` bundle. HTTP status mirrors it: **200**
passed, **500** ran-but-failed, **400** validation/SSRF, **401** missing/bad token,
**429** too many parallel jobs. So always read the body, even on 500.

Suggested gate (what counts as a real failure):
- **Hard fail:** `status != "passed"`; any `evidence.pageErrors` (uncaught JS
  exceptions); any `evidence.consoleErrors` (almost always a real client bug); any
  `evidence.unexpectedExternalRequests` (the UI calling a non-allowlisted origin —
  a strong signal).
- **Review, don't auto-fail:** `evidence.networkFailures` (4xx/5xx). Benign cases
  exist (e.g. a `favicon.ico` 404). Make it strict per-routine when you know the
  surface should be clean.
- **Evidence to keep:** `evidence.screenshots` (names/paths under `ARTIFACT_DIR`),
  `evidence.domTextSnapshot` (set `collectDomOnFailure: true` for failure triage),
  `extracted` (your `extractText`/`evaluate` outputs).

This signal taxonomy is the whole point: `consoleErrors`/`pageErrors` catch the
class of bug that HTTP/API tests structurally can't.

## 7. The expensive-to-learn gotchas (don't re-learn these)

- **`timeoutMs` per step/inspect can't exceed `STEP_TIMEOUT_MS`.** Schema rejects it
  with a 400. For cold-cache SPA loads, raise `STEP_TIMEOUT_MS`/`JOB_TIMEOUT_MS`
  (e.g. 30000/120000) rather than passing a big per-step `timeoutMs`.
- **`assertTextContains` sees rendered `innerText`, not DOM text.** It reflects CSS
  `text-transform` and visibility — e.g. a header styled uppercase asserts as
  "ADMIN", not "Admin". Prefer asserting visibility, or match the rendered casing.
- **Strict locators** (repeat of §5 because it bites): single-match selectors only.
- **Each job is isolated** (§8): no session/cookie/localStorage continuity between
  routines.
- **`inspect-dom` historically broke under `tsx`** with `ReferenceError: __name is
  not defined` (esbuild `keepNames` injects a `__name` helper that's absent in the
  browser). If you see this, the runner needs the one-line `__name` shim in its
  Playwright context setup (`context.addInitScript({ content: 'globalThis.__name =
  globalThis.__name || function (v) { return v; };' })`) or to run its compiled
  `dist` via node. Routine actions and string `evaluate` steps are unaffected.
- **`evaluate`/`watch` run arbitrary JS in the page** and are gated behind
  `ALLOW_EVALUATE`. They're great for discovery/assertions, but enable them only in
  trusted/dev contexts.

## 8. Limitations / footguns to design around

- **No cross-call session state.** One job = one fresh browser context. Anything
  stateful (login, multi-page flows) must live within a single routine.
- **Brittle to DOM churn.** No self-healing/semantic selectors; if the UI changes,
  re-`inspect-dom` and update. Best on stable/generated DOMs.
- **Single-node scale.** In-process job counter, `MAX_PARALLEL_JOBS` cap (429 on
  excess), ephemeral artifacts, one shared API key. Serialize or scale out yourself
  for heavy load; it's an internal-tool-grade service.
- **Capped evidence.** Console/network/DOM-text/evaluate results are size-capped and
  truncated by design — don't rely on it to exfiltrate large payloads.

## 9. Security tips (the differentiator — use it, don't disable it)

- Keep `ALLOWED_TARGET_ORIGINS` **narrow** — exactly the origins you test. Never
  wildcard it in a shared environment; that's the SSRF guard.
- Rely on **credential redaction**: pass secrets via `credentials`/`variables`, not
  inline in steps, so they're scrubbed from evidence and logs.
- `inspect-dom` defaults to `redactSensitiveValues: true` and warns when you request
  `includeRenderedHtml` (it can leak hidden state). Request rendered HTML only when
  you truly need it.
- Treat `ALLOW_EVALUATE=true` as a privilege: only in environments where you control
  both the routines and the target.

## 10. CI / automation integration

- Run the scraper as a **sidecar service**; boot your app; POST routines; parse the
  JSON; gate the build on the §6 policy. Deterministic and replayable, so it's
  regression-grade.
- Point `ARTIFACT_DIR` at a CI-collectable path (screenshots/trace make failures
  triageable); rely on `ARTIFACT_RETENTION_HOURS` for cleanup, or wipe per run.
- Commit your `*.json` routines next to the app; treat them like tests. Inject
  `targetUrl`/`variables` per environment so one routine set covers dev/staging.

## 11. Minimal end-to-end example

A routine (`smoke.json`) — load a page, confirm a known element rendered with no
client errors, and snapshot:

```json
{
  "scenarioName": "smoke",
  "targetUrl": "http://localhost:8080/",
  "options": { "headless": true, "screenshots": "always", "collectDomOnFailure": true },
  "steps": [
    { "action": "goto", "url": "$targetUrl" },
    { "action": "waitForSelector", "selector": "#app", "state": "visible" },
    { "action": "assertVisible", "selector": "#app" },
    { "action": "screenshot", "name": "loaded" },
    { "action": "collect", "what": ["domText", "url"] }
  ]
}
```

Run it and gate on the evidence (bash + `jq`):

```bash
curl -s http://127.0.0.1:3000/v1/explorations/run \
  -H "Authorization: Bearer $SCRAPFORAI_API_KEY" \
  -H 'content-type: application/json' \
  -d @smoke.json |
  jq '{status,
       failedStep: .failedStepIndex,
       console: (.evidence.consoleErrors|length),
       page: (.evidence.pageErrors|length),
       external: (.evidence.unexpectedExternalRequests|length)}'
```

Treat the run as green only if `status=="passed"` and `console`, `page`, and
`external` are all `0`.

---

**One-line summary:** ScrapForAI shines as a deterministic, security-bounded,
evidence-producing browser verifier. Allowlist your origin, talk to it over
`127.0.0.1`, discover with `inspect-dom`, author single-match-selector routines that
assert post-state, and gate on `consoleErrors`/`pageErrors`/`unexpectedExternalRequests`.
The schema endpoint is the source of truth; this doc is the experience around it.
