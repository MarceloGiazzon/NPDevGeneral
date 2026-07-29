# Corpus Integrity Plan — REG-63 is 17 models, not 2

> **STATUS: EXECUTED (2026-07-29), except C11 which is owner-only.** Written against
> `beta1-vision-spine` @ `1997939` (tree clean, all pushed, repo public, tag `beta1.2`, ledger
> authoritative). C1–C10 all done same day; see the Definition of Done and each `ledger/items/*.yml`
> for the evidence trail (REG-63, REG-64, REG-62, REG-35, REG-34).
>
> **Staged outside the repo.** Move in with:
> ```powershell
> Move-Item "<scratchpad>\CORPUS_INTEGRITY_PLAN.md" "D:\WorkSpace\NPDev\NPDev_General\docs\CORPUS_INTEGRITY_PLAN.md"
> ```
>
> **Scope:** the 5 findings from the 2026-07-29 re-analysis (N1–N5), the 4 open ledger items,
> and one finding that emerged while scoping them and outranks all of the above.
>
> Facts are **MEASURED** (git, source, filesystem, gates, suites — 2026-07-29) or **PROPOSED**.

---

## C1/C2 — measured results (2026-07-29, implementation pass)

**C1 confirmed the headline exactly: 17/29 failed the real validator, 12/29 passed** (new
`scripts/quality/validate-corpus.py`, built for this — runs the real `validateModel` Gradle task
per model, not a heuristic). Full RED evidence:
`NPDev_General__OutsideRepo\corpus-validate-PRE-C2-2026-07-29.json`.

**Three divergences from the heuristic table — this doc's own "treat divergence as the interesting
result" test, and all three were real:**

1. **`pack-sample`'s failure has nothing to do with retired `flowStep.type` values.** Its actual
   error: `Pack $ref escapes the model root: ../../packs/pos/pack.json` — a pre-existing `$ref`
   path issue (it referenced a *shared* `AppGen/apps/packs/` directory one level above all apps;
   every other pack-using app, e.g. WmsOffice/reg39-healthy-control, uses a *local* copy under its
   own `definition/packs/`). Fixed by copying `pos`/`mail` into `pack-sample`'s own
   `definition/packs/` and rewriting the two `$ref`s — unrelated to `dsl_v2_migration.py`. A second,
   downstream error then surfaced once the `$ref` resolved: the model's own `capabilities` array
   redeclared `persistence`, which the `pos` pack already contributes — a genuine duplicate,
   removed.
2. **`invoice-bonds-demo` and `reg39-healthy-control` also carry a retired top-level `orchestrations`
   key** (this repo's schema has only ever accepted `orchestrationRules` — checked via
   `git log -S'"orchestrationRules"'`, present at the very first baseline commit — so this predates
   even this repo's own history rather than being a DSL-2.0-era rename). Invisible to the original
   2.A.6 corpus scan because that scan never covered `AppGen/apps`. Fixed: `dsl_v2_migration.py`
   gained a 5th design principle (top-level key rename, ambiguity-checked the same way as every
   other alias here) — see `NPDevCli/dsl_v2_migration.py` and its test file.
3. **Why the codemod never ran against `AppGen/apps`: a documented, deliberate decision, not a
   bug.** `docs/DSL2_AND_DECOMPOSITION_PLAN.md`'s own Definition of Done (line 759) records
   `AppGen/apps` as "deliberately deferred (owner's call... non-git external directory)" when 2.A.4
   ran the real migration across every *git-tracked* tree (`NPDevSamples`, `golden-ai-scenarios`,
   RuntimeHost fixtures). The deferred item just never got a tracking item to come back to — which
   is exactly the systemic gap C4 exists to close permanently.

**C2 applied cleanly: 19 files changed across AppGen/apps, 0 ambiguities.** Verified tier by tier
per this doc's own risk ordering:

| Tier | Apps | Result |
|---|---|---|
| 1 — samples (13) | invoice-bonds-demo, ledger1-red-repro, lnch1-rehearsal, npdev_split_model_sample_app, p77-hookproof(-pg), pack-sample, reg39-healthy-control, simple-consumer-h2server, simple-user-registry-{h2local,h2local-freshdb,inmemory,postgres} | All 13/13 parse + `-GenerateOnly` succeeds |
| 2 — WordLab/AuxScreen/Pigmentampa | single retired value each (`validate`) | All 3 generate + build + boot; health `UP` (DB/eventStore/scheduler all `UP`) |
| 3 — Claude (done last) | 4 retired values incl. `waitForEvent` | Generated clean; **first build FAILED** — see below |

**A 4th, real finding — not a DSL-2.0 issue at all: a Java compile failure in Claude Support
Desk**, `variable tenantId is already defined in class SupportTicket` /
`incompatible types: String cannot be converted to UUID`. Root cause: the platform auto-injects a
reserved `tenant_id` column on every generated entity (added after this app was authored); Claude
is explicitly documented in its own model metadata as *"a pre-platform-tenancy... sample"* with a
hand-modeled `Tenant` reference field also named `tenantId` — the exact collision
`SchemaRealizationEmitter.RESERVED_BUSINESS_COLUMN_NAMES`'s guard was written to catch (its own
comment names this scenario) — but that guard runs at DB-schema-realization time, which is *after*
Java compilation, so the model author never sees its friendly message, only a raw compiler error.
Fixed at the data level (renamed the field `tenantId` → `tenantIdRef` in Claude's model, the exact
rename the guard's own message suggests; event-payload fields of the same name left alone since
they don't collide with a persisted-entity column). **Not fixed**: the entity-emitter's missing
equivalent guard (the SQL-layer guard exists; the Java-entity-layer sibling does not) — filed as a
new gap rather than expanding this pass's scope; see Definition of Done.

**Claude then built, generated, and booted clean.** All 4 previously-broken official apps
(AuxScreen, Claude, Pigmentampa, WordLab) now regenerate; **R-G2 is genuinely 15/15** — AuxScreen's
`aux-screen.panel.json` and Pigmentampa's `pigmentampa-editor.panel.json` (both authored fresh via
`bootstrap-panel-provenance.py` + hand review against their live bundles, since neither could ever
be bootstrapped before) both pass `check-panel-provenance-impact.py` live, 0 problems.

---

# Part 0 — The headline

## 0.1 REG-63 understates its own blast radius by 8×

REG-63 (OPEN, MEDIUM) says AuxScreen and Pigmentampa carry a pre-stabilization flow-step shape the
current schema rejects, and asks — correctly — *"needs a check of how many other AppGen apps (if any
beyond these two) still carry this shape."*

**MEASURED, 2026-07-29 — I ran that check. It is 17 of 29 models**, including **4 of the 5 official
apps**:

| Model | Retired `flowStep.type` values |
|---|---|
| `_official/AuxScreen` | `validate` |
| `_official/Claude` | `callCapability`, `if`, `validate`, `waitForEvent` |
| `_official/Pigmentampa` | `validate` |
| `_official/WordLab` | `validate` |
| `invoice-bonds-demo` | `validate` |
| `npdev_split_model_sample_app` | `capability`, `event`, `invariant` |
| `ledger1-red-repro`, `lnch1-rehearsal`, `p77-hookproof`, `p77-hookproof-pg`, `pack-sample`, `reg39-healthy-control`, `simple-consumer-h2server`, `simple-user-registry-h2local`, `simple-user-registry-h2local-freshdb`, `simple-user-registry-inmemory`, `simple-user-registry-postgres` | `enforceInvariants` |

**`_official/WmsOffice` is the only official app that is clean** — which is exactly why it regenerated
fine during F5-V.2 and why nothing noticed.

> Method note: I first scanned for both retired *type* aliases and field-level shape, which flagged
> 26/29 — including WmsOffice, which demonstrably regenerates. That was over-flagging: `input`/
> `output` remain valid field names. The table above counts **only retired `flowStep.type` enum
> values**, which the schema rejects outright. WmsOffice drops out, matching observed reality.

## 0.2 What this means

**2.A.4's acceptance criterion did not hold.** The DSL 2.0 plan said:

> *"2.A.4 Regenerate all 20 app definitions (the corpus IS the regression test)"*

The codemod shipped (`NPDevCli/dsl_v2_migration.py`, with tests). **It was never run across the
corpus.** Shipping a codemod and applying it are different acts, and only the first happened.

`BREAKING.md`'s standing rule — *every breaking change ships its codemod in the same commit* — was
honoured in letter. The intent behind it (the corpus stays buildable) was not.

**Consequences, in order:**
1. **4 of 5 official apps cannot be regenerated** with the current toolchain. Existing built/running
   instances are unaffected; this blocks *regeneration*, which is how every platform change reaches
   an app.
2. The corpus is **not** a regression test for DSL 2.0 — it never exercised it.
3. It blocked R-G2 (two apps' manifests) — which is how it was found, by accident, weeks later.
4. Anything downstream that needs a fresh bundle (F2.2/F3/F4 provenance for those apps, `npdev
   generate screen`) is unreachable for 4 of 5 apps.

## 0.3 The class problem behind it

**Nothing checks that the corpus still parses.** The repo now has gates for register consistency,
narrative drift, tree/ledger agreement, strikethrough contradiction, test-task coverage, schema
mirroring, and panel-manifest validity — an unusually thorough set — and **no gate notices that 17
models stopped being valid.**

This is the same shape as **N5** (no link-checker; two doc moves, two rounds of dangling links). Both
are *"a thing that used to work, silently stopped, and nothing looked."*

---

# Part 1 — 🔴 Fix the corpus (2–3 days)

## C1 · Establish ground truth with the real validator · S 3 hr

My scan is a heuristic over `flowStep.type`. The authority is `JsonModelParser.parse()`.

```powershell
# per model, the real check
.\gradlew :NPDevContract:dsl:validateModel `
  -PmodelPath=<abs path to model.json> -PreportOut=<abs path to report.json>
```

Write `scripts/quality/validate-corpus.py` to run it across every `model.json` under
`AppGen/apps/**` and `NPDevSamples/**`, and emit a table: `model · parses? · first error`.

**Acceptance.** A definitive list. Expect ~17 failures; **treat any divergence from my table as the
interesting result** — it means the failure mode is broader or narrower than retired type aliases.

## C2 ★ · Extend and run the codemod · M 1–1.5 days

`NPDevCli/dsl_v2_migration.py` already exists with tests. Two questions:

1. **Does it cover these shapes?** The retired values in play are `validate`, `enforceInvariants`,
   `invariant`, `capability`, `callCapability`, `event`, `if`, `waitForEvent` → and per REG-63 also
   the top-level `input`/`out` step properties. Confirm coverage; extend where missing.
2. **Why did it not run?** Worth one minute of history — if it was gated behind a flag or a path that
   silently matched nothing, that mechanism will bite again.

Then run it, in this order — **easiest blast radius first**:

```
1. samples/test apps (13)   ← low stakes, proves the codemod on volume
2. _official/WordLab, AuxScreen, Pigmentampa  (single retired value each: `validate`)
3. _official/Claude          ← 4 retired values incl. `waitForEvent`; do last, most to go wrong
```

**Per-model acceptance — the byte-identical rule from the original 2.A.3 plan:**

```
before = compile(model)     # only possible where the model still parses
migrate(model)
after  = compile(model)
assert before == after      # the DSL changed; the MEANING did not
```

For the 17 that do **not** currently parse, `before` cannot be computed — so the acceptance is
weaker and must be stated honestly: **the model parses after migration, generates, builds, and boots**
(`Build-NpdevApp.ps1 -GenerateOnly` at minimum; a full build for the 4 official apps).

## C3 · Re-close R-G2 for the two unblocked apps · S 1 hr

Once AuxScreen and Pigmentampa regenerate, produce a live bundle for each and confirm the manifest
for their single screen (`aux-screen.html`, `pigmentampa-editor.html`). Coverage goes **13/15 → 15/15**.

Then update REG-63: close it, and correct its "AuxScreen and Pigmentampa" framing to the real 17 —
the ledger entry should record the actual scope for anyone reading it later.

---

# Part 2 — 🔴 Close the class, not the instance (1 day)

Two gates. Both are the "nothing looked" pattern; both are cheap.

## C4 ★ · Corpus-parse gate · S 4 hr

Promote C1's script to a blocking gate in `run-ai-knowledge-gate.ps1`:

> **Every `model.json` in the corpus must parse against the current schema.**

- Allowlist entries are permitted **only** with a written reason and a REG id — the
  `security-pattern-sweep-allowlist.json` precedent.
- Fails on a model that stops parsing, which is exactly what nobody noticed for 17 models.
- **Calibrate it:** RED against the pre-C2 corpus (17 failures), GREEN after. That RED run is free
  evidence and should be captured before the migration.

> This gate is the real deliverable of this plan. C2 fixes today's 17; C4 is why there is never
> an 18th.

## C5 · Markdown link gate (N5) · S 2 hr

MEASURED — **8 broken links today**, 6 of them created by archiving the 13 process docs:

| File | Broken target |
|---|---|
| `docs/REG16_POSTGRES_ADAPTER_SQL_ADVERSARIAL_REVIEW.md` | `ONE_PLAN_CLOSE_EVERYTHING.md` |
| `docs/SECURITY_PATTERN_SWEEP_2026-07.md` | `ONE_PLAN_CLOSE_EVERYTHING.md` |
| `docs/archive/programme-history/REG16_CODEGEN_OUTPUT_ADVERSARIAL_REVIEW.md` | `../../ONE_PLAN_CLOSE_EVERYTHING.md` |
| `docs/archive/programme-history/REG16_EXPORT_PDF_ADVERSARIAL_REVIEW.md` | `../../ONE_PLAN_CLOSE_EVERYTHING.md` |
| `docs/archive/programme-history/REG16_FLOW_ORCHESTRATION_ADVERSARIAL_REVIEW.md` | `../../ONE_PLAN_CLOSE_EVERYTHING.md` |
| `docs/EXTERNAL_SECURITY_REVIEW_BRIEF.md` | `../../ACCEPTED_BOUNDARIES.md`, `../../GETTING_STARTED.md` *(pre-existing, from an earlier reorg)* |
| `docs/architecture/CSRF_POSTURE.md` | `NPDEV_BOX_OBJECT_THREAT_MODEL.md` *(pre-existing since `c534003`; the target was never written)* |

**Two doc reorganizations, two rounds of dangling links, both found by ad-hoc checks.** ~30 lines:
walk every `.md`, resolve relative links, fail on any that do not resolve. Wire blocking.

For `CSRF_POSTURE.md` → a document that was never written: either write a stub or remove the link —
do not allowlist a promise.

---

# Part 3 — 🟡 Small items (~half a day)

| ID | Item | Effort | Note |
|---|---|---|---|
| **C6** | **N2** — ~15 stale `docs/<archived>.md` paths in script docstrings and Java comments | 30 min | **Verified NOT a gate hole**: all three script hits (`security-pattern-sweep.py`, `check-register-consistency.py`, `run-ai-knowledge-gate.ps1`) are docstrings, not file reads. Purely cosmetic; sweep with a path rewrite |
| **C7** | **N4** — PR-gate runtime after promoting 2 Postgres adapters | watch | Job cap `timeout-minutes: 60`; step maxima now sum to ~110 (caps, not expectations). **Check the first real PR run's wall clock.** If it approaches 60, raise the cap rather than dropping the adapters — R-P3's evidence-based scoping was right |
| **C8** | **REG-62** — `allowedActions` untyped CSV-in-metadata | 4 hr | LOW, open. Corpus pre-check still holds: **0 of 29** models use it, so the typed schema needs no codemod and no `BREAKING.md` entry. Cheapest security-shaped win available |
| **C9** | **REG-35** — `postBeta0MaturityCheck` missing-vs-invalid conflation | S | LOW, PROCESS. The PowerShell twin was fixed as REG-32; this is the Gradle-native one, same bug class |
| **C10** | **REG-34** — Windows CI runs Linux-container Testcontainers tests | S | LOW, PARTIAL. Already mitigated with `@DisabledOnOs(WINDOWS)`; residual is scope, not breakage |

---

# Part 4 — ⬥ C11 · Three real conversations (owner)

Unchanged, unblocked, and now with a sharper argument than last time.

**The case got stronger this week, twice:**
- **B20** (bounded contexts) explicitly defers its own trigger to *"P6.3's outreach conversations."*
- **REG-63** is what happens when the only feedback loop is internal: a corpus-wide break sat
  undetected until a manifest task tripped over it weeks later. **External users find this class
  immediately** — they regenerate an app on day one.

Smallest useful version is unchanged: description + topics; the staged pitch (SDD framing, three
differentiators, honest UI limitation); **three conversations, not a broadcast**; friction recorded in
`NON_AUTHOR_FRICTION_LOG_TEMPLATE.md` (already moved back into `docs/` for this).

> **Do C11 after C2.** Handing someone a repo where 4 of 5 sample apps will not regenerate is a bad
> first hour, and first hours are the whole point of the exercise.

---

# Part 5 — Sequencing

```
DAY 1        C1  corpus validator sweep -> ground truth            [3 hr]
             C4  capture the RED calibration run BEFORE migrating  [1 hr]

DAY 2-3      C2  extend + run the codemod  ★★                      [1-1.5 d]
                 samples (13) -> WordLab/AuxScreen/Pigmentampa -> Claude
             C3  re-close R-G2 -> 15/15; correct REG-63's scope    [1 hr]

DAY 4        C4  corpus-parse gate, wired blocking, GREEN  ★       [3 hr]
             C5  markdown link gate + fix the 8                    [2 hr]

DAY 5        C6 stale paths · C8 REG-62 · C9 REG-35 · C10 REG-34
             C7 read the first PR-gate wall clock

THEN ⬥       C11 three conversations
```

## Why this order

**C1 before C2** — never migrate against a heuristic. My 17 is a scan; the validator is the authority,
and the delta between them is itself information.

**C4's RED capture before C2** — the pre-migration corpus is a free, perfect calibration fixture for
the gate. Migrate first and you have to synthesize one.

**C2 before C3 and C11** — everything downstream (manifests, bundles, `npdev generate screen`, a
stranger's first hour) needs those apps to regenerate.

**C4 is the item that matters in six months.** C2 fixes 17 models once. C4 is why the count never
grows again — and it would have caught this the day DSL 2.0 landed.

## Risk register

| Risk | Likelihood | Mitigation |
|---|---|---|
| The codemod does not cover all 8 retired values / the `input`-`out` shape | **High** | C1 gives the exact list first; extend before running. Claude's 4 values are the stress case — do it last |
| Migration changes model *meaning*, not just syntax | **Medium** | Byte-identical compiled-model assert where `before` is computable; generate+build+boot where it is not |
| A "fixed" model regenerates but behaves differently | **Medium** | For the 4 official apps, boot and smoke-test — WmsOffice's F5-V.2 scenario is the template |
| C4 is added with a broad allowlist and becomes decorative | **Medium** | Allowlist entries require a written reason **and** a REG id. Review the allowlist at the end of C2, not during |
| The 13 sample apps are considered not worth migrating | **Medium** | They are the DSL's only volume regression corpus. If some are genuinely dead, **delete** them — do not leave them broken and unlisted |
| C11 slips again | **High** | It has slipped every plan so far. It is 3 conversations, not a project |

## Definition of done

- [x] **C1** every corpus model has a recorded parse verdict from the real validator (`scripts/quality/validate-corpus.py`; 12/29 pre-migration, matching the 17-failure headline exactly, with 2 genuine divergences chased down — pack-sample's unrelated $ref-escape bug, the pre-baseline top-level `orchestrations` key)
- [x] **C2** every model parses (29/29); the 4 official apps generate, build and boot (health UP, all NPDev subsystems UP); meaning preserved where assertable (0 ambiguities from `npdev migrate dsl-2 --write`, 19 files changed); a genuinely new bug found regenerating Claude (tenant_id column collision) fixed at the data level, not papered over
- [x] **C3** manifest coverage **15/15** — AuxScreen's `aux-screen.panel.json` and Pigmentampa's `pigmentampa-editor.panel.json` authored fresh and confirmed live (0 problems, `check-panel-provenance-impact.py` against each app's real bundle); REG-63 closed with its true 17-model scope recorded; new REG-64 filed for the entity-emitter's missing reserved-column guard (found, not fixed — a real but separate gap)
- [x] **C4** corpus-parse gate blocking and GREEN in `run-ai-knowledge-gate.ps1` (check 11/12), calibrated RED (17 failures, captured before C2 touched anything) then GREEN (29/29) after
- [x] **C5** 0 broken markdown links (`check-markdown-links.py`, check 12/12) — fixed all 8 the plan predicted plus a genuine 9th (`NPDevContract/docs/PACKS-AND-BONDS-PLAN-REVIEW.md`'s wrong relative-path depth) and corrected one stale claim (`CSRF_POSTURE.md`'s target doc exists, just under `docs/security/` not `docs/architecture/`)
- [x] **C6–C10** stale paths swept (exactly the ~15 predicted, 3 script + 12 Java comment hits); REG-62 (allowedActions typed, cross-reference check consciously deferred — real prerequisite, not a shortcut), REG-34 (re-audited live: no Testcontainers test reachable from the Windows job today, closed DONE), REG-35 (both stated bugs fixed + an incidental validateBoundaryLocks false positive found verifying it) all closed; C7 (PR-gate wall clock) has no PR to observe yet — stays a standing watch-item, not actionable this pass
- [ ] **C11** three people outside this machine have regenerated an app, and what they hit is written down — owner-only, unchanged from every prior plan

---

*Companions: `ledger/items/REG-63.yml` (scope correction), `REG-64.yml` (new gap, entity-emitter
reserved-column guard), `REG-62.yml` (allowedActions typed), `REG-35.yml`/`REG-34.yml` (closed) ·
`docs/REMEDIATION_PLAN.md` (predecessor) · `BREAKING.md` (the codemod rule this tested) ·
`NPDevCli/dsl_v2_migration.py` (+ its test file) · `scripts/quality/validate-corpus.py` (C1/C4) ·
`scripts/quality/check-markdown-links.py` (C5) · `docs/SCREEN_TAXONOMY.md` (C3) ·
`docs/ACCEPTED_BOUNDARIES.md` (B20 → C11).*
