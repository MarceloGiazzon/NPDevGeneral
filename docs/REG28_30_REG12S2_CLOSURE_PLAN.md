# Closure Plan — REG-28 / REG-29 / REG-30 + REG-12 Slice 2 (unblocking the PDF plan)

> **Status:** APPROVED PLAN — not started.
> **Written:** 2026-07-22, against `main` at `beta1.1` (dev on `beta1-vision-spine`).
> **Scope.** The bounded engineering remainder that needs no owner decision: the three
> schema-migration loose ends found by this session's verification (**REG-28/29/30**), and **REG-12
> Slice 2** (the print stylesheet / print render mode). Completing Slice 2 **unblocks the existing
> REG-12 Slice 3 plan** (`docs/REG12_DOCUMENT_EXPORT_PLAN.md`), since that plan's core design is
> "the print HTML/CSS Slice 2 produces *is* the PDF renderer's input."
> **Audience.** An AI implementation session (or human) with no project history. Follow phase by
> phase. Every file/method named is grounded (cited) or marked **VERIFY** — line numbers drift.
> **This plan does not start implementation.** It is the design + phasing.

---

## 1. Read before touching anything

1. This document, end to end.
2. `docs/NPDEV_OPEN_ITEMS_REGISTER.md` §3.4 (REG-28/29/30 — the findings, verbatim) and §2.4 (REG-12).
3. `docs/REG7_REG8_EXTERNAL_DB_AND_MIGRATION_MARKING_PLAN.md` — the plan that *built* the mark/claim
   machinery these items harden; its §3 guardrails still bind.
4. `docs/REG12_DOCUMENT_EXPORT_PLAN.md` — the PDF plan Slice 2 unblocks (read its §1 design decision).
5. `docs/LNCH1_PROGRAMME_RETROSPECTIVE.md` §6 — reproduce-RED-first, live>suite, fixtures mirror
   production. `SchemaLifecycleExecutor` is the platform's most adversarially-reviewed subsystem;
   treat every change to it as capable of creating the next finding.

---

## 2. Guardrails (binding)

1. **Reproduce RED first** for every behavioral change; a green test that was never red proves nothing.
2. **Both schema-lifecycle proof matrices stay green with unchanged expectations** for existing
   scenarios (`SchemaLifecycleExecutorProofMatrixTest` H2, `...PostgresProofMatrixTest`). New behavior
   = new scenario, never an edited existing expectation.
3. **Self-bootstrapped tables** (mark, claim) keep the never-throw-on-missing-table read discipline;
   any schema change to them is a `CREATE TABLE IF NOT EXISTS` migration that tolerates the old shape.
4. **A schema change to `npdev_schema_migration_mark` must also be excluded from the delta report** —
   `SchemaDeltaReport.ALWAYS_EXCLUDED_TABLES` already lists it; keep it there (guardrail against the
   self-bootstrapped table being seen as an "unexplained extra").
5. **Live > suite** for anything touching real migration (REG-28). The frontend Slice 2 is verified in
   a **real browser** against a **real generated app** (ScrapForAI), not just template inspection.
6. **The generated frontend bundle `app.js` (407 KB) is generated — never hand-edit.** Slice 2 changes
   go in the **templates/emitters**, then regenerate. Same for `business-ui-app.mustache` (147 KB,
   never full-read — Grep to the toolbar).
7. **Build output → `Build\`; evidence → `NPDev_General__OutsideRepo`.** Small bounded commits, one per
   phase/sub-slice, `Verified:` line. No `git add -A`.

---

## 3. Phase map (ordered by risk — safest/most-critical first)

| Phase | Item | Type | Why this order |
|---|---|---|---|
| P1 | **REG-29** | Test-only | Safest (no behavior change) and it pins the most safety-critical property — a refusal must not wedge the DB. Do it before touching the mark store. |
| P2 | **REG-28 + REG-30** | BUG (data-integrity) | Both live in `MigrationMarkStore`; the REG-28 from→to binding + a uniqueness constraint naturally *folds in* REG-30. Do together. |
| P3 | **REG-12 Slice 2** | Frontend | Independent; sequenced last because finishing it **unblocks** `docs/REG12_DOCUMENT_EXPORT_PLAN.md` (Slice 3). |

---

## 4. Phase P1 — REG-29: prove the claim is released on a refusal (test-only)

**The finding (register §3.4).** `SchemaLifecycleExecutor.migrate` acquires a migration claim
(`MigrationClaimStore.claim`) and releases it in a `finally` around the whole migration body — so a
refusal thrown *inside* `beforeMigrate` (Trigger C, or destructive-without-token) still releases the
boot's own claim. **The code is correct; there is no test for it.** The existing
`SchemaLifecycleExecutorMigrationClaimTest` covers: clean-run release, refusal *at acquisition* (a
pre-existing OTHER instance's claim), and virgin-boot-never-claims — but **not** a refusal thrown
*while this boot holds its own claim*. That wedge-risk property (a recoverable refusal must not leave a
claim that bricks every future boot) is unverified.

**Steps.**
1. Add a test to `SchemaLifecycleExecutorMigrationClaimTest` that drives the **full `migrate` entry
   point** (the claim lives there, not in `beforeMigrate` — see the test class's own javadoc), with
   state that makes the boot: (a) acquire a claim (a non-blank stored fingerprint), and (b) throw a
   refusal from inside the body. The cleanest refusal to trigger is **REG-8 Trigger C** — seed
   `npdev_schema_history` so `databaseMigratedPastThisBuild` fires (an APPLIED row for this build's
   fingerprint at T1, a later APPLIED row for a different fingerprint at T2, stored pointer = the
   newer one). Reuse the seeding helpers from `SchemaLifecycleExecutorDatabaseMigratedPastBuildTest`.
2. Assert two things: the `migrate` call throws the expected `IllegalStateException` (refusal), **and**
   after it, `MigrationClaimStore.current(dataSource)` is **empty** — the boot's own claim was
   released by the `finally` despite the refusal.
3. **RED-first check:** temporarily neutralize the `finally`'s release (comment it in a scratch copy),
   confirm the new test FAILS (claim left behind), then restore — proving the test actually catches
   the wedge.
4. Add a `@DisplayName` that states the property precisely (e.g. "a refusal thrown while this boot
   holds its claim still releases it — a recoverable refusal must not wedge the database").

**DoD.** The new test passes; the RED-check confirmed it fails without the release; both proof matrices
unchanged. REG-29 → CLOSED. **No production code changes** — this is a coverage fix for correct code.

---

## 5. Phase P2 — REG-28 + REG-30: bind the migration mark to a from→to pair

**The findings (register §3.4).** `MigrationMarkStore` (`npdev_schema_migration_mark`) records only
`(id, marked_fingerprint, marked_at_utc, marked_by, note)` — the **target** fingerprint, with **no
`from` binding and no expiry** (REG-28). `findMatching(markedFingerprint)` matches on
`marked_fingerprint` alone; the boot path (`beforeMigrate` → `findMatching(manifest.schemaFingerprint())`
→ `applyMigrationMark`) will silently fast-forward the **first** future boot whose target is X, from
*whatever* the DB is actually at — skipping all migration/classify/Trigger-C passes. And `consume`
deletes by `id` only, so **duplicate marks for the same fingerprint each survive one consume** (REG-30).

**The fix (one change resolves both).** Bind the mark to the operator's intended **`from → to`
transition**, and only honor it when the live stored fingerprint equals that `from`:

1. **Schema:** add a `from_fingerprint TEXT` column to `npdev_schema_migration_mark`
   (`CREATE TABLE IF NOT EXISTS` handles new installs; for an existing table, an idempotent
   `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` guarded by a "column exists" check — same portable
   UPDATE-then-INSERT discipline the executor uses elsewhere, no engine-specific syntax). A mark with
   a NULL `from_fingerprint` (pre-upgrade rows) is treated as "unbound" — see step 4's compatibility
   note.
2. **`insert`** gains a `fromFingerprint` parameter. Its two callers supply it:
   - the ControlPanel endpoint (`SchemaAcknowledgmentController` `.../mark-done`, **VERIFY** exact
     method) — the operator pastes both the current (`from`) and target (`to`) fingerprints, which the
     `-PlanOnly`/`-Upgrade` migration plan already prints as a pair;
   - the `Mark` record gains `fromFingerprint`.
3. **`findMatching`** becomes `findMatching(dataSource, fromFingerprint, toFingerprint)` — `WHERE
   from_fingerprint = ? AND marked_fingerprint = ?`. The boot path passes **both**: `from = the live
   stored fingerprint`, `to = manifest.schemaFingerprint()`. A leftover mark for `to=X` can no longer
   fire unless the DB is genuinely at the `from` the operator recorded — closing REG-28.
4. **REG-30 folds in:** add a **unique constraint on `(from_fingerprint, marked_fingerprint)`** so a
   duplicate mark for the same transition can't be inserted twice; `consume` deletes by `id` as now,
   and there is at most one row per transition to consume. (Alternatively, `consume` deletes all rows
   matching the transition — pick the constraint, it's cleaner and prevents the duplicate at the source.)
5. **Backward-compat for a NULL-`from` (pre-fix) mark:** decide explicitly (owner-adjacent, but a safe
   default): treat a NULL `from_fingerprint` mark as matching **only** when the operator re-submits it
   post-upgrade, OR ignore unbound marks entirely (safest — a fresh beta has none). Document the choice.
6. **CLI parity note:** if/when the `-MarkMigrationDone` CLI flag is added (REG-7.2 left it as a
   ControlPanel-first follow-up), it must pass `from` too.

**Tests (RED-first).**
- REG-28: a mark for `(from=A → to=X)` does **not** fire when the DB is at `Z` (stored=Z), and **does**
  fire when stored=A. The old behavior (mark for X fires from any stored) is the RED to reproduce first.
- REG-30: inserting the same `(from,to)` twice is rejected by the constraint (or consume removes both);
  after one consume, no leftover fires a second time.
- Existing `SchemaLifecycleExecutorMigrationMarkTest` scenarios stay green (update them to pass `from`,
  which is a fixture change, not an expectation change).

**Verify live** (guardrail #5): a real boot rehearsal — mark a real `A→X` on a running app, confirm it
fast-forwards only when the DB is actually at A. Record under `NPDev_General__OutsideRepo`.

**DoD.** Mark is bound to a transition; a stale/duplicate mark cannot fast-forward an unrelated boot;
new tests + a live rehearsal prove it; matrices unchanged. REG-28 and REG-30 → CLOSED.
Update `docs/SCHEMA_EVOLUTION.md`'s "marking a migration as done" section to show the from→to form.

---

## 6. Phase P3 — REG-12 Slice 2: print stylesheet + print render mode (unblocks the PDF plan)

**The goal (register §2.4).** A **print stylesheet** plus a **print render mode** for a declared panel
— pure frontend — so a grid (the GeneXus/WMS pick-list / packing-slip need) can be printed cleanly.
**This is the input the Slice 3 PDF renderer consumes** (`docs/REG12_DOCUMENT_EXPORT_PLAN.md` §1), so
build the print HTML/CSS as a **first-class, server-reachable artifact**, not just an `@media print`
afterthought — the PDF phase renders the *same* HTML server-side.

**Orientation.**
- `NPDevRuntimeHost/.../api/ConceptQueryController.java` — `exportCsv` / `@GetMapping("/{concept}/export.csv")`:
  the filtered/sorted data path and the toolbar precedent a "Print" affordance sits beside.
- `NPDevGenerator/.../npdev-templates/business-ui-app.mustache` (147 KB — **Grep to the grid toolbar**,
  don't full-read): where the CSV export button is emitted; add a "Print" button next to it.
- The generated `app.js` bundle (407 KB) is **generated — never hand-edit**; change the templates/emitters.
- Slice 2's stylesheet should live where the generator emits app CSS (**VERIFY** — likely alongside the
  business-ui `style.css` / the theming tokens; see the theming-tokens work).

**Steps.**
1. Emit a **print stylesheet** (`print.css` or an `@media print` block scoped to a print container)
   that renders a declared panel's grid as a clean printable document: header/title, column layout,
   borders, sensible page-break rules, no app chrome (nav/sidebar hidden). Use the platform theming
   tokens so it inherits brand where sensible.
2. Add a **print render mode** for a declared panel — a route/toggle that renders *just* the panel's
   filtered/sorted data into a bare print container (the HTML the print.css targets). Cover the
   WMS-class need first: a pick-list/packing-slip shape (title + line items + totals), not a generic
   "print this page."
3. Add a **"Print" affordance** to the grid toolbar next to the CSV export button (VERIFY exact
   mustache location), wired to the print render mode / `window.print()`.
4. **Design for Slice 3 reuse:** keep the print HTML self-contained and its CSS inlinable, so the PDF
   renderer can consume the identical markup server-side. Note the seam in a code comment pointing at
   `docs/REG12_DOCUMENT_EXPORT_PLAN.md`.

**Verify** (guardrail #5): regenerate a real app with a declared panel, open it in a **real browser**
(ScrapForAI), trigger print mode, and confirm the printed/print-preview output is a clean grid document
with correct page breaks and no app chrome. Record evidence.

**DoD.** A declared panel can be printed with a dedicated print render mode, verified live in a real
browser; the print HTML/CSS is structured for server-side reuse. REG-12 Slice 2 → DONE. **REG-12 Slice
3 is now unblocked** — `docs/REG12_DOCUMENT_EXPORT_PLAN.md` P2/P3 can render this same HTML to PDF.

---

## 7. What this plan closes, and what remains after

**Closed by this plan:** REG-28, REG-29, REG-30 (schema-migration hardening), REG-12 Slice 2 (print),
and the **unblocking** of the REG-12 Slice 3 PDF plan.

**Explicitly still open after** (not in scope here): REG-12 Slice 3 itself (its own plan,
`docs/REG12_DOCUMENT_EXPORT_PLAN.md`, now unblocked); REG-6 (`ColumnFacts`, ~40% — its own effort);
the three latent items flagged this session (`application-wmsoffice.yml` `D:/` JWT paths;
packaged-app-test adapter-list fragility; `platform-status.json` drift automation); and the human-gated
external-tester run (`docs/EXTERNAL_TESTER_COLDSTART.md`).

**Effort:** P1 well under a session (one test); P2 ~a session (schema + 2 callers + tests + live
rehearsal); P3 ~a session (frontend + real-browser verify). None is a discovery process — all are
known-shape.

---

*Companion documents: `docs/NPDEV_OPEN_ITEMS_REGISTER.md` §3.4 / §2.4 · `docs/REG12_DOCUMENT_EXPORT_PLAN.md`
(Slice 3, unblocked by P3) · `docs/REG7_REG8_EXTERNAL_DB_AND_MIGRATION_MARKING_PLAN.md` (built the
machinery P1/P2 harden) · `docs/LNCH1_PROGRAMME_RETROSPECTIVE.md` §6 (the discipline).*
