# Sample browser-verification methodology (ScrapForAI)

**Status:** proven green on two samples — `restaurant-saas-multitenant`
(Increment 1) and `superuser-admin-console` (Increment 2, the
`internal.tables=true` headline sample: identity/workspace packs + Store + Box
View + ordinary business CRUD). Increment 2 also found and fixed a real generator
bug (see §"Findings worth keeping" in the handoff doc §10, and the lookup-picker
note in §7 below) — exactly the outcome this methodology is for. The fix was then
swept against restaurant (regenerate + reboot + re-run both its browser and HTTP
demonstrations) with zero regression. Increment 3 (schema-evolution browser demo,
on `superuser-admin-console`) is also DONE and green — see
`demonstrate-schema-evolution.ps1`, a self-contained lifecycle script (the only
one of the demonstrate-* scripts that starts/stops/regenerates the app itself).
Increment 4 (S0–S8 promotion-lifecycle browser demo) is also DONE and green —
this one required adding a real new "Promotion" admin panel to the generated UI
first (mirroring how Store/Box View were added), then verifying through it; see
`demonstrate-promotion-lifecycle.ps1` and the gotchas below (routine-folder
isolation, and an "expected console error" allowance in `Assert-RoutineGreen`).
A follow-up cleanup pass also closed Increment 4's one remaining gap — a role-
gated rejection that the sample's own trial-mode UI session can't reach — by
mixing in an HTTP step: issue a real, lesser-privileged credential via the
platform's own admin API, attempt the gated action with it directly over HTTP,
then assert the resulting rejection is visible in the next browser routine's
rendered history. Worth reusing whenever a sample's *only* available UI session
can't naturally reach some permission tier.
**Branch:** `beta1-vision-spine`.

## Why

The existing `demonstrate-*`/`verify-*` sample scripts are HTTP-only
(`Invoke-RestMethod`). They prove the backend, but **structurally cannot** touch
the generated vanilla-JS manifest UI: super-user nav, admin tables,
Menu/Preferences panels, Store, Box View, conditional fields, widgets, and the
create/edit-through-the-form write path. This methodology adds a real (headless)
browser layer using Marcelo's **ScrapForAI** exploration runner
(`D:\WorkSpace\ScrapForAILegacy`, referenced in place — not vendored) and asserts
on the structured evidence it returns: console errors, page errors, network
failures, unexpected external requests, screenshots. Those signals are the
iteration feedback that turns each run into a fix-and-re-run loop.

## Components (all under `NPDevSamples/scripts/`)

- `browser/scrapforai-harness.ps1` — the reusable, dot-sourced library.
  Functions: `Initialize-ScrapForAI`, `Start-ScrapForAI`/`Stop-ScrapForAI`,
  `Invoke-ScrapRoutine` (POST a routine, inject runtime `-Variables`),
  `Invoke-ScrapInspectDom`, `Assert-RoutineGreen`, `Save-RoutineEvidence`,
  plus `Get-Prop` (StrictMode-safe optional-property reader).
- `<sample>/browser-routines/*.json` — committed, version-controlled exploration
  routines (one scenario each). They use our private `targetPath` field (default
  `/npdev-business-ui/`) plus the scraper's own `steps`/`options`; the harness
  injects the full `targetUrl` so routines stay port-agnostic.
- `<sample>/demonstrate-browser.ps1` — orchestrator. Verifies the app is up,
  starts the scraper, runs every routine, asserts green, writes evidence.

## How to run

```powershell
# restaurant-saas-multitenant (Increment 1)
pwsh -NoProfile -File NPDevSamples/scripts/generate-sample-app.ps1 -SampleId restaurant-saas-multitenant
pwsh -NoProfile -File NPDevSamples/scripts/run-sample-app.ps1 -SampleId restaurant-saas-multitenant
pwsh -NoProfile -File NPDevSamples/scripts/restaurant-saas-multitenant/demonstrate-browser.ps1

# superuser-admin-console (Increment 2 -- internal.tables=true headline sample)
pwsh -NoProfile -File NPDevSamples/scripts/generate-sample-app.ps1 -SampleId superuser-admin-console
pwsh -NoProfile -File NPDevSamples/scripts/run-sample-app.ps1 -SampleId superuser-admin-console
pwsh -NoProfile -File NPDevSamples/scripts/superuser-admin-console/demonstrate-browser.ps1
```

Evidence summaries land in `Output/RunOutput/browser/` (small, commit-friendly);
screenshots/traces land **outside the repo** under
`D:\WorkSpace\NPDev\Build\scrapforai-artifacts\<sample>\` (binary, per the
build-output policy).

## The iteration loop (per feature surface)

1. **Discover** real selectors against the booted app with `inspect-dom`
   (`Invoke-ScrapInspectDom` in the harness) — it returns `forms[]`/
   `allControls[]`/`visibleControls[]` with ready-made candidate selectors. The
   esbuild/tsx `ReferenceError: __name is not defined` bug that used to block it
   is fixed (see gotchas) by a one-line `addInitScript` shim in
   ScrapForAILegacy's `playwrightContext.ts`. For form fields specifically
   (inspect-dom snapshots the page as-loaded, not after opening a modal), open the
   form first via a routine, then use a string `evaluate` step (`ALLOW_EVALUATE`
   on) that enumerates `#modalRoot form [name]` — that's how every form in both
   samples so far was discovered. Stable hooks the renderer gives you:
   - nav links: `#sideNav a[href="#concept-<Name>"]`, plus
     `#concept-__store__` / `#concept-__boxview__` / `#concept-__preferences__`.
   - panels: `<section class="concept-panel" id="#concept-<Name>">` with
     `.panel-header h2`, `.panel-actions button`, `table.records`.
   - form controls: `[name="<field>"]` inside `#modalRoot form`; submit button
     text is `Create` (create) / `Save` (edit). The modal closes **only** on a
     successful submit, so `waitForSelector #modalRoot form state=detached` is a
     reliable create-success signal.
   - the static shell: `#apiKey`, `#sideNav`, `#app`, `#navToggle`, `#status`.
2. **Author** a routine: auth preamble (`goto` → wait `#apiKey` → `fill #apiKey`
   = `api-dev` → `reload` → wait a super-user-only link like
   `#sideNav a[href="#concept-__store__"]`), then the scenario, then
   `screenshot` + `collect ["domText","url"]`.
3. **Run & read evidence.** `Assert-RoutineGreen` hard-fails on
   `status != passed`, any `pageErrors`, any `consoleErrors`, or any
   `unexpectedExternalRequests`. Network 4xx/5xx are reported but only hard-fail
   with `-StrictNetwork` (benign favicon 404s would otherwise red a run).
4. **Fix** in the generator/template/model (never the generated output),
   regenerate, re-run. Commit the routine + evidence summary.

## Gotchas (learned building Increment 1 — do not re-learn)

- **Talk to the scraper over `127.0.0.1`, never `localhost`.** The server binds
  IPv4; Windows resolves `localhost` to `::1` first, so a `localhost` poll never
  connects. The harness already uses `127.0.0.1`.
- **SSRF allowlist.** The scraper blocks localhost/private targets unless the
  exact origin is in `ALLOWED_TARGET_ORIGINS`. `Start-ScrapForAI` sets it from
  the app's origin automatically; a routine to a different origin 400s at `goto`.
- **We never touch `ScrapForAILegacy\.env`.** The harness launches from a cwd
  without a `.env` and passes all config as process env (dotenv does not override
  existing env), so Marcelo's checked-in `.env` is untouched.
- **`inspect-dom` was broken under tsx** (`ReferenceError: __name is not defined`
  — esbuild `keepNames` injects a `__name` helper undefined in the browser). Fixed
  in ScrapForAILegacy by a one-line shim in `playwrightContext.ts`:
  `context.addInitScript({ content: 'globalThis.__name = globalThis.__name ||
  function (v) { return v; };' })`. If you're pointed at an older checkout without
  this fix, `inspect-dom` and any compiled-function `page.evaluate` will fail, but
  routine actions (locators) and string `evaluate` steps are unaffected.
- **Selectors are strict.** `frame.locator(sel)` errors if it matches >1 element.
  Use single-match selectors (the `href`-based nav selectors above); scope panel
  interactions to the visible panel by id (`#concept-<Name> ...`).
- **`assertTextContains` sees rendered text, not DOM text.** `innerText` reflects
  CSS `text-transform`, so the `.nav-group-header` "Admin" asserts as **"ADMIN"**.
  Prefer asserting element visibility, or match the rendered casing.
- **`STEP_TIMEOUT_MS`/`JOB_TIMEOUT_MS`** default low (10s/60s) without the `.env`;
  the harness sets 30s/120s for cold-cache SPA loads.
- **Per-run unique values.** Create-through-UI routines use `valueFromVariable`
  names/codes that `demonstrate-browser.ps1` fills with a timestamp
  (`UITEST-<stamp>`), so reruns never collide on a unique constraint.
- **A reference field's lookup picker used to destroy its parent form (real bug,
  fixed).** `business-ui-app.mustache`'s `openModal()`/`closeModal()` wipe
  `#modalRoot.innerHTML` wholesale; the picker dialog (the "Browse…" button on any
  `reference`-typed field) used to reuse that same root, so opening it during a
  create/edit form destroyed the form instantly. Fixed by giving the picker its
  own root (`#pickerModalRoot`, added in `business-ui-index.mustache`) via
  `openModalInto`/`closeModalIn`. If a generated app predates this fix, any create
  form with a reference field will silently vanish the moment you click Browse… —
  regenerate against current generator source.
- **The lookup picker's search box is debounced ~250ms client-side.** A routine
  that `fill`s `.picker-dialog .picker-search` and immediately waits for
  `.picker-records tbody tr.picker-row` can catch the picker's *unfiltered* first
  render (especially on a dev DB with leftover rows from prior runs) and then fail
  with a Playwright strict-mode "resolved to N elements" error on the click. Add a
  `waitForTimeout` (≥600ms) after the fill, then `assertCount operator="="
  count=1` on the row locator before clicking, so the click target is
  unambiguous and the picker has definitely re-rendered filtered.

## Reference implementations

Two permanent samples now exercise this methodology end-to-end — read their
`browser-routines/*.json` for concrete, working examples of every pattern above
(including the lookup-picker discover→fill→debounce-wait→assertCount→click
sequence used for both a plain business reference field and a composed-pack bond):
- `NPDevSamples/scripts/restaurant-saas-multitenant/browser-routines/` (Increment 1)
- `NPDevSamples/scripts/superuser-admin-console/browser-routines/` (Increment 2)
- `NPDevSamples/scripts/superuser-admin-console/browser-routines/evo-*.json` +
  `demonstrate-schema-evolution.ps1` (Increment 3 — schema evolution) is the
  reference for a self-contained lifecycle script that boots, populates, mutates
  the model, regenerates, reboots, and verifies, all in one run. Key patterns
  worth copying:
  - **Mutate the model in memory, never via a hand-maintained duplicate file.**
    Read `Input/model.json` raw, `ConvertFrom-Json`, append the new field to the
    target concept's `fields` array, `ConvertTo-Json -Depth 30`, write. Always
    restore the ORIGINAL raw text (not a re-serialized round-trip) in a
    `try`/`finally`, so the checked-in model is never left mutated even on
    failure and the restore is byte-perfect regardless of JSON formatting.
  - **Delete the sample's persisted DB file at the start of the script** so every
    run starts from a guaranteed-fresh schema. For H2Local, that file lives at
    `D:\WorkSpace\NPDev\Build\databases\<sampleId>\<databaseName>.mv.db`
    (resolved by `UserDatabaseDefinitionLoader.resolveAppId`/`resolveWorkspaceRoot`
    — `appId` is the sample's folder name, completely outside the sample's own
    Input/Output tree, so regeneration never touches it on its own).
  - **Assert the safe-additive vs. destructive path via the boot log**, not just
    the data. `SchemaLifecycleExecutor` (`NPDevRuntimeHost/src/main/java/com/
    finalexec/db/SchemaLifecycleExecutor.java`) prints to stdout (captured by
    redirecting the boot process's stdout to a file): safe-additive contains
    `"skipping destructive recreation"`; a destructive recreate contains
    `"NPDev destructive schema recreation"`; a brand-new DB contains `"no stored
    schema fingerprint found"`. Grep for these substrings.
  - **Starting/stopping the app yourself** (rather than assuming it's already
    running, like the other demonstrate-* scripts do) needs `Start-Process
    -FilePath gradlew.bat -ArgumentList <ONE pre-quoted string>` — NOT an array of
    separate arguments. `gradlew.bat` runs via `cmd.exe`, and an array element
    containing an embedded space (`--args="--spring.profiles.active=X
    --server.port=Y"` has one) gets silently split into two cmd-line tokens,
    which gradle then misreads as an unknown top-level `--server.port` option.
    Build the whole `--no-daemon bootRun "--args=..."` line as one string, exactly
    like `run-sample-app.ps1`'s direct `&` invocation does.
  - **Prove "the new column is usable" by editing an OLD record**, not a new one:
    open the pre-evolution row's edit form, confirm the new field renders on it,
    fill+save, then do a full page `reload` (not just a panel refresh) before
    re-checking — proves a real server round-trip, not client-side cache.

## Adding this to a new sample

1. Author the model/config (`internal.tables=true` if it needs the super-user
   surfaces), generate, and boot it (profiles `dev,trial`, real `db.definition.json`).
2. Create `<sample>/browser-routines/*.json` for its surfaces, discovering
   selectors via the loop above — copy the closest existing routine as a starting
   point rather than writing from scratch.
3. Copy an existing `demonstrate-browser.ps1`, change the `SampleId`/`BaseUrl`/
   shared variables, and reuse the harness unchanged.

**Increment 4 reference** — `browser-routines/promotion/*.json` +
`demonstrate-promotion-lifecycle.ps1`. Extra patterns worth copying:
- **Adding a brand-new synthetic admin panel** (not just verifying an existing
  one) follows the exact Store/Box View recipe in `business-ui-app.mustache`: a
  `{ conceptName: "__xyz__" }` sentinel constant, a `state.xyz` bucket, `showXyz()`
  + `loadXyz()`/`renderXyzPanel()` functions, a mount in `render()`'s
  `if (state.isSuperUser) {...}` block, a click-dispatch branch in `addLink`'s
  handler, and a nav link in the `if (state.isSuperUser) {...}` block of
  `renderNav()`. No `BusinessUiEmitter`/manifest changes needed — these panels are
  entirely client-side, driven by `/api/me`'s `isSuperUser` flag.
- **Keep state-dependent routines (anything requiring a fresh/specific DB state)
  in their OWN subfolder**, never in the flat `browser-routines/` directory that
  `demonstrate-browser.ps1` globs. `Get-ChildItem -Filter "*.json"` without
  `-Recurse` won't descend into subfolders, so this isolation is automatic once
  you put them there — but a flat routine that assumes a fresh-DB starting
  condition (e.g. `S0_IDEA`, or a field that only exists post-migration) WILL get
  picked up and fail if left in the shared folder. This was a near-miss this
  session, caught before it could bite.
- **A routine that deliberately triggers a rejected (4xx/5xx) HTTP call** will
  always show 1+ "console errors" under the default policy — Chrome logs a failed
  `fetch()`'s status to the console even when application code catches it
  correctly. Use `Assert-RoutineGreen -AllowConsoleErrorSubstrings @("responded
  with a status of 400")` (or similar) for that routine only; leave every other
  routine on the full-strength default.

**Next (Increment 5, not yet started):** no further items are currently named in
the handoff doc's original plan — the original 3-item plan (super-user sample,
schema-evolution, S0–S8 promotion) is now fully covered. Revisit
`beta1-vision-spine-status-and-handoff.md` §5/§6 for what, if anything, comes next.
