# Group B Implementation Plan — un-defer and complete ALL deferred items

> **STATUS: HISTORICAL** — last changed 2026-07-24; its completion state has **not** been re-verified. Treat nothing here as an open commitment: check `docs/NPDEV_OPEN_ITEMS_REGISTER.md` (authoritative) or `docs/OPEN_ITEMS_SNAPSHOT.md` before acting on any item.


> **Written:** 2026-07-24 · **Branch:** `beta1-vision-spine` · **Repo root:** `D:\WorkSpace\NPDev\NPDev_General`
>
> **Owner decisions captured 2026-07-24 (drive this plan):**
> 1. **REG-25** → **on-write normalization + a migration tool** (not a forced in-place data migration).
> 2. **REG-23** → **config-driven cutover** (`npdev.auth.jwt.reject-tokens-without-tv-after=<date>`).
> 3. **AW-P2** → **implement the FK auto-Prompt** (generator auto-emits a picker per reference field).
> 4. **ARCH-upload P6** → **follow the GeneXus reference** for WmsOffice's "Add Doctos"/NFe attach.
>
> **Honest framing.** These five are un-equal in risk. Ordered by *ascending risk*:
> **REG-23** (small, config-gated) · **AW-P2** (generator feature, additive) · **REG-25** (touch every
> write site + a tool) · **ARCH-upload P6** (app-scope, GeneXus fidelity research) · **REG-6** (the
> dangerous one — a behavior-preserving rewrite in the single most-fixed file). Do them in that order;
> **REG-6 last, and only with the full proof matrix each step.**
>
> **Nature of each task:** REG-23/AW-P2/REG-25/REG-6 are **platform code** (a careful agent can execute
> with the specs below). ARCH-upload P6 is **app work** (layer 2, WmsOffice) that needs GeneXus-export
> reading. All are **RED-first + verify-locally-then-commit**. Do not push unless the owner asks.

---

## Global rules
1. Absolute paths. PowerShell shell (Bash where marked). Never `git add .`; commit only the named files.
2. Never edit under `D:\WorkSpace\NPDev\Build` or any `npdev-generated` folder. Slimness hook runs on
   commit; if it fails run `pwsh -File scripts\hygiene\clean-workspace-state.ps1` then retry once.
3. **RED-first every change** — reproduce the current behavior (the "before") before fixing.
4. **model.schema.json is 4-copy mirrored** (`NPDevContract/schemas/`, `.../schemas/authoring/`,
   `NPDevContract/dsl/src/main/resources/schema/`, `NPDevContract/dsl/resources/Schemas/`) — any schema
   edit (AW-P2, ARCH-upload if it touches the model) must mirror to all four; a conformance test pins it.
5. After kernel/adapter/generator/RuntimeHost Java changes, rebuild with the **`rebuild-app`** skill
   (`Rebuild-And-Restage.ps1`) before booting a generated app; verify UI/runtime with **`verify-in-browser`**.
6. If reality diverges from a spec below, STOP and report — don't improvise a different design.

---

## TASK 1 — REG-23: config-driven revocation of `tv`-less JWT tokens (LOW risk; ~½ session)

**Decision:** config-driven `reject-tokens-without-tv-after` date; unset = today's lenient behavior.

**What already exists (do not rebuild):** the `tv` (token-version) mechanism is live —
`JwtSigner` stamps `tv` on every mint; `IdentityRoleLookup.tokenVersion(...)`
(`NPDevKernel/adapters/expression-cel/.../runtime/support/IdentityRoleLookup.java`) resolves the live
version; it is checked on **both** claim→context paths:
- RuntimeHost: `IdentityAwareContextResolver` (`NPDevRuntimeHost/.../auth/IdentityAwareContextResolver.java:67`).
- Kernel: `GeneratedCrudRuntimeSupport` (`NPDevKernel/adapters/expression-cel/.../runtime/support/GeneratedCrudRuntimeSupport.java`).
The **only** gap: a token with **no `tv` claim** (legacy, pre-`tv`) skips the version check entirely.

**Steps:**
1. **RED-first.** Write a test that mints/forges a JWT with **no `tv` claim** and shows it is accepted
   (revocation not enforced) on both paths today.
2. Add config `npdev.auth.jwt.reject-tokens-without-tv-after` (an ISO date/instant; **default empty**).
   Wire it through the same config surface `StartupValidator`/auth uses. Add a `StartupValidator` check
   that the value, if set, parses.
3. In **both** paths, when a token lacks `tv`: if `reject-tokens-without-tv-after` is set **and** the
   token's `iat`/issue time is at/after that instant → reject (401, a `tv-required` reason code
   consistent with the existing granular JWT error codes). If unset → today's behavior (accept). The
   flip must be **atomic across both paths** — implement the decision in one shared helper
   (`IdentityRoleLookup` is the natural home, already used by both) so they cannot diverge.
4. **GREEN.** Two tests: (a) unset config → tv-less token still accepted (no breakage); (b) config set
   to a past date → tv-less token rejected on **both** paths; (c) a `tv`-bearing token is unaffected by
   the flag.
5. Rebuild + run the RuntimeHost gate (`run-runtimehost-gate.ps1`) and the auth IT suite
   (`JwtAuthExternalBetaIT`) — green.

**DoD:** with the flag unset, behavior is identical to today; with it set, tv-less tokens are rejected
consistently on both claim→context paths; `tv`-bearing tokens unaffected; documented in
`docs/CONFIGURATION.md` (+ the relaxed-binding gotcha note if the property name hyphenates).

**Commit:** `feat(REG-23): config-driven rejection of tv-less JWT tokens (both claim->context paths); default off`
(files: `IdentityRoleLookup.java`, `IdentityAwareContextResolver.java`, `GeneratedCrudRuntimeSupport.java`,
the auth config + `StartupValidator`, `docs/CONFIGURATION.md`, `docs/NPDEV_OPEN_ITEMS_REGISTER.md`).

**Caveat:** a half-applied flag is worse than none — the shared-helper single-decision-point is the
whole point. Do not implement the check in two places.

---

## TASK 2 — AW-P2: FK auto-Prompt on generated forms (LOW-MED risk; additive generator feature; ~1 session)

**Decision:** implement the auto-Prompt — every reference (FK) field on a generated form auto-gets a
picker/lookup button wired to the referenced concept, reusing the existing bandPicker return-mapping.

**What exists:** the picker/bandPicker mechanism is in `business-ui-app.mustache` and
`workbench-page.html.mustache` (`openBandPicker`, `returnMapping`). Today an author wires a picker
manually; AW-P2 makes the generator emit one automatically for `reference`-typed fields.

**Steps:**
1. **RED-first.** Generate an app with a concept that has a `reference` field (e.g. `Invoice.userId ->
   User`) and confirm the generated form renders a **plain input** for the FK (no picker) — the "before".
2. In the generator's form emission (`business-ui-app.mustache` / the emitter that feeds it), for each
   field where `type == reference`: emit the picker button + a hidden id input, wired to the referenced
   concept's list, reusing the existing `openBandPicker` + `returnMapping` (id → the FK field, a display
   column → a read-only label). Keep it **opt-out**: an explicit author-provided picker/`bandPicker`
   for that field wins (don't double-emit).
3. If a model/manifest flag is needed to carry the referenced-concept + display column into the UI
   manifest, add it to the generated UI manifest (`BusinessUiEmitter`) — **no model.schema change**
   should be needed (reference target already exists in the model); if one is, mirror all 4 copies.
4. **GREEN (browser):** rebuild the app (`rebuild-app`), then with **`verify-in-browser`**: open the
   form, click the auto-emitted FK picker, select a referenced row, confirm the FK id is set + the
   display label shows, and save persists the correct id.
5. Run the generator gate (`run-generator-gate.ps1`) + frontend gate — green.

**DoD:** a generated form auto-renders a working picker for every `reference` field (unless the author
supplied one), verified live in a browser (pick → id set → save persists); generator + frontend gates
green. Update the AW-P2 roadmap entry from "re-scoped out" to "implemented".

**Commit:** `feat(AW-P2): generator auto-emits an FK picker for reference fields (opt-out if author-wired)`
(files: the form mustache/emitter, `BusinessUiEmitter`, roadmap entry).

**Caveat:** this is net-new UI on **every** generated app's forms — the browser verification (not a
unit test) is the real proof, and the opt-out path (author-supplied picker) must be preserved.

---

## TASK 3 — REG-25: normalize tenant on write + a canonicalization tool (MED risk; ~1 session)

**Decision:** normalize (lowercase) at all write sites going forward + ship a one-time migration tool;
**no forced in-place migration** of live data.

**What exists:** several paths already lowercase the tenant (`DefaultConceptGateway`,
`KernelRunner.normalizeTenantOrDefault`, `LoginThrottle`, `IdentityProvisioning`, `FileUploadController`),
but **stored** `tenant_id` in the tenant registry + business tables is not canonicalized, and some
write sites don't normalize — so the real shape is an **inconsistency between write sites**.

**Steps:**
1. **RED-first.** Reproduce the fragmentation: create the same logical tenant with two casings
   (`Acme` vs `acme`) through two different write paths and show they land in **different** isolation
   buckets (two `tenant_id` values). Capture this as the "before".
2. **Reconcile every write site.** Find all places that persist a `tenant_id` (registry insert, business
   concept writes via the gateway, api-credential rows, event/audit rows, seed paths) and route each
   through **one** canonicalizer (`normalizeTenant` — lowercase + trim, already the de-facto shape).
   Audit: `grep -rn "tenant_id\|tenantId" --include=*.java` across `NPDevKernel` + `NPDevRuntimeHost`
   and confirm each *write* passes through the canonicalizer; the reserved-`default` sentinel handling
   (REG-24) must be preserved.
3. **Ship the migration tool** (does NOT run automatically): `scripts/ops/canonicalize-tenant-ids.ps1`
   with `-DryRun` (default) and `-Apply`. It lowercases `tenant_id` across the tenant registry AND every
   business table's `tenant_id` column, **detecting and reporting collisions** (two casings that would
   merge into one bucket — the operator must resolve those deliberately). Document it in
   `docs/DEPLOYMENT.md` / `docs/SCHEMA_EVOLUTION.md` as a per-deployment operator step.
4. **GREEN.** Repeat the step-1 scenario: both casings now land in the **same** bucket (write-time
   normalization). Add a tenant-isolation test asserting mixed-case writes converge. Run the tool in
   `-DryRun` against a seeded H2 app and confirm the report is correct.
5. RuntimeHost gate + `TenantIsolationAttackTest`/`TenantIsolationE2EIT` — green.

**DoD:** all `tenant_id` write sites normalize (verified by the mixed-case convergence test); a
documented, collision-aware `-DryRun/-Apply` migration tool exists and is proven on a seeded DB; no
forced in-place migration; isolation suites green. Flip the REG-25 register entry to DONE (on-write) +
note the tool.

**Commit:** `fix(REG-25): normalize tenant_id at all write sites + ship canonicalize-tenant-ids ops tool (collision-aware, dry-run default)`
(files: the write-site Java, `scripts/ops/canonicalize-tenant-ids.ps1`, `docs/DEPLOYMENT.md`, register).

**Caveat:** normalization changes bucket identity for **new** mixed-case writes — that's the fix, but
verify it does not collide with an existing differently-cased bucket in a live app (the tool's
collision report is the safety net). Do NOT auto-run the tool on boot.

---

## TASK 4 — ARCH-upload P6: WmsOffice "Add Doctos"/NFe attach per GeneXus reference (app-scope; ~1 session)

**Decision:** follow the GeneXus reference. This is **layer-2 app work** on WmsOffice, using existing
platform primitives — NOT a platform change.

**What exists (platform, all proven live):** `FileStoreContract` port, `file-store-inproc` +
`file-store-objectstore` (S3, `S3ObjectStoreFileStoreAdapter` + MinIO test) adapters, the `file` DSL
field type, `FileUploadController` (`POST/GET/DELETE /api/files`, tenant-isolated), and the generated
upload/download widget. **Nothing platform-side needs building.**

**Reference:** the GeneXus source-of-truth export at
`D:\WorkSpace\WmsOffice\OriginalArtifacts\WmsLabs_Mod_GX17.xml` (+ the attributes/subtypes XML) — read
the "Add Doctos" / NFe-attachment screen + attribute definitions to define fields, allowed content
types, and which concept(s) documents attach to.

**Steps:**
1. Read the GeneXus export for the doc-attachment surface: which entity documents attach to (e.g. a
   receipt/NFe on an inbound movement), the file metadata fields (filename, type, size, date, uploader),
   and any list/preview UI.
2. In the **WmsOffice app definition** (`D:\WorkSpace\NPDev\AppGen\apps\_official\WmsOffice\definition\model.json`,
   layer 2), add: a `file`-typed field (or a `Documento` concept with a `file` field + FK to the target)
   with `contentTypes`/`maxSizeBytes` per the reference; wire the upload/download widget onto the
   relevant screen; if attachments are a list, use the nested-panel/dataSource primitive.
3. Choose the file-store provider: `file-store-inproc` for the local WmsOffice build; document the
   `npdev.filestore.provider=objectstore` switch (S3) for a real deployment.
4. **GREEN (live):** regenerate + build WmsOffice (`Rebuild-And-Restage.ps1` for the `_official/WmsOffice`
   app), boot it, and with **`verify-in-browser`**: upload a document on the target screen, confirm it
   stores (tenant-prefixed), lists, and downloads back byte-identical. Cross-check the stored object via
   the file-store (inproc dir or MinIO bucket).
5. Record the outcome in the WmsOffice app notes + flip the ARCH-upload P6 roadmap residual to DONE.

**DoD:** WmsOffice has a working document-attach surface matching the GeneXus reference's fields/behavior,
verified live (upload → list → download round-trip, tenant-isolated); no platform code changed. Update
`docs/OPEN_GAPS_AND_ROADMAP.md` (ARCH-upload P6) + the WmsOffice memory/notes.

**Commit:** app definition is **layer 2 (not this git repo)** — commit only the doc/roadmap update here:
`docs(ARCH-upload P6): WmsOffice doc-attach wired per GeneXus reference (app-scope, uses existing file primitives)`.
Propagate any platform-script change (none expected) back to layer 1 per CLAUDE.md.

**Caveat:** if reading the GeneXus export reveals a genuine **platform** gap (a primitive WmsOffice
needs that doesn't exist), STOP and file it as a new platform item — do not stretch the app work into
un-scoped platform work.

---

## TASK 5 — REG-6: migrate the remaining set-algebra passes to `ColumnFacts` (HIGH risk; ~2–4 sessions; DO LAST)

**Decision (implicit in "implement ALL"):** complete the full "every pass reads `ColumnFacts`" purity.

**Reality check first.** This is the **single most-fixed file** in the codebase:
`NPDevRuntimeHost/src/main/java/com/finalexec/db/SchemaLifecycleExecutor.java` (**3,318 lines**, 6
`ColumnFacts` uses today). The dangerous half is **already closed + CI-guarded**; this task is purity
with **high regression risk and low residual value**. It is a **behavior-preserving refactor** — any
test whose expectation changes means the refactor changed semantics and must be reworked.

**Prerequisite:** Docker running (the Postgres proof matrix is Testcontainers-based). Confirm before
starting: `docker info`.

**Steps (one pass at a time — NEVER batch):**
1. **Baseline GREEN.** Capture the current proof matrices as the immutable reference:
   - H2: `SchemaLifecycleExecutorProofMatrixTest` (run via `run-runtimehost-gate.ps1` or the RuntimeHost
     `test` task).
   - Postgres: `SchemaLifecycleExecutorPostgresProofMatrixTest` (via the assembled app's `integrationTest`
     with Docker up — same recipe REG-2/Fix A used).
   Record pass counts (register cites H2 41/41 + Postgres 25/25; re-confirm the current numbers). These
   are the invariant — they must be **identical** after every step.
2. **Enumerate the set-algebra passes** still doing their own set math over raw manifest maps (the
   register §1.6 names them: additive diff, required diff, and the other passes not yet migrated — the
   relax/schema-ahead/bond *semantic* ones are already done). List them explicitly before touching code.
3. **Migrate ONE pass** to read `columnFactsFor(manifest, table)` instead of re-deriving. Keep every
   existing test green — **no expectation change allowed**.
4. **Re-run BOTH matrices** (H2 + Postgres). If any count/hash changes → the refactor changed behavior →
   revert that pass and rework. Only proceed to the next pass when both matrices are byte-identical to
   baseline.
5. Repeat 3–4 per pass. When all set-algebra passes read the projection, **collapse the platform-column
   sets** into `ColumnFacts.isPlatformManaged` (retire the pinning conformance tests that only exist to
   guard duplicate copies — keep the emitter-side reserved-name validation, a different job). Update the
   class-header REG-6 directive from "deferred" to "done".
6. Final: full RuntimeHost gate + both matrices + generator conformance — all green, all counts
   unchanged.

**DoD:** every semantic/set-algebra pass reads `ColumnFacts`; the duplicate platform-column sets are
collapsed; H2 + Postgres proof matrices **unchanged** from baseline (behavior-preserving proven); the
REG-6 directive + register entry flip to fully-done.

**Commit (per pass, small + bounded):** `refactor(REG-6): migrate <pass-name> to ColumnFacts (behavior-preserving; H2+PG matrices unchanged)`.

**Caveats (the big ones):**
- **This is the one that can silently break schema migrations** for every generated app. The matrices
  are the only thing standing between a refactor and a data-loss regression — never skip the Postgres
  run "because H2 passed."
- If a pass genuinely **cannot** be expressed via `ColumnFacts` without a semantic change, that pass is
  a legitimate exception — document why in a code comment rather than force it. "Every pass reads it" is
  the goal, not a suicide pact.
- Budget realistically: 2–4 sessions, gated on Docker + Postgres matrix runtime.

---

## Suggested order & honest expectation
1. **REG-23** (½ session, config-gated, safe).
2. **AW-P2** (1 session, additive, browser-verified).
3. **REG-25** (1 session, write-site audit + tool).
4. **ARCH-upload P6** (1 session, app-scope, GeneXus reading).
5. **REG-6** (2–4 sessions, high-risk, last, full matrices).

**Total: ~5.5–7.5 sessions.** Tasks 1–4 are genuinely worth doing (each closes a real, bounded gap).
**Task 5 remains the one I'd still counsel caution on** even while implementing it — it's the only Group
B item where the *risk of the fix* exceeds the *risk of the gap*. If mid-migration the matrices ever
resist staying identical, that's the signal the deferral was right; stop and re-confirm scope.

## STOP rules
STOP and report if: a RED-first repro doesn't reproduce the described "before" · a proof-matrix count
changes during REG-6 · a REG-23 flip behaves differently on the two paths · the REG-25 tool reports
collisions on the target DB · ARCH-upload P6 reveals a real platform gap · a schema edit isn't mirrored
to all 4 `model.schema.json` copies · you're tempted to batch REG-6 passes or skip the Postgres matrix.
Do not push — the owner decides that.
