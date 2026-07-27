# External AI Delegation — programme + platform feature

> **STATUS: ACTIVE** — 2026-07-27. Promoted from
> `D:\WorkSpace\NPDev\NPDev_General__OutsideRepo\external-ai-review\PLAN_EXTERNAL_AI_REVIEW_2026-07-26.md`
> on owner approval (D1/D2/D6/D7 answered 2026-07-26, D3 answered 2026-07-27 — see §7). **All of P0-P9
> are now DONE and verified, including P5 (M1-M6 actually run against real vendors, 2026-07-27).**
> **P4's real calibration result: 0/4 (2 missions x 2 vendors) found their
> target known bug.** Per this plan's own rule, both channels are recorded LOW-YIELD, not dressed up
> as assurance — see the P4 row below. **P5's real missions found the opposite of low-yield: 3 real,
> code-verified security gaps (REG-48/49/50) that calibration's synthetic bugs never modeled**, plus a
> real bug in the P8 gate itself, found because M6's own audit was skeptical enough to flag it — see
> the P5/P8 rows below and `docs/adr/ADR-0009-external-ai-delegation.md`.

## Progress as of 2026-07-27

| Phase | Status | Evidence |
|---|---|---|
| P0 — ADR-0009 + 4 contract schemas | **DONE** | `docs/adr/ADR-0009-external-ai-delegation.md`; `NPDevContract/schemas/external-ai-{mission,pack,verdict,run}.schema.json`, all validate |
| P1 — Kernel port + adapters, default denies | **DONE** | `ExternalAiCapabilityContract` (fail-closed default, RED-first proof green); `external-ai-inproc`, `external-ai-http` adapters, both test suites green |
| P2 — Pack core: redaction + sanitizer + chunker | **DONE** | `SensitiveKeyPolicy` consolidation (fixed a real drift bug: Trace policy was silently missing `authorization`); `secret-content-patterns.json` |
| P3 — Platform producer | **DONE** | `scripts/external-review/build-review-pack.py` + `missions.json` (9 missions); verified live incl. a real git-pinned pack against LNCH13-F1's actual pre-fix commit |
| P4 — Calibration control | **DONE — LOW-YIELD result** | Both calibration missions actually run against both D1 vendors (2026-07-27): `M0-CALIB-LNCH13` x {nvidia meta/llama-3.3-70b-instruct, gemini gemini-3.5-flash}, `M0-CALIB-R3F2` x same two. **0/4 runs correctly identified their target known bug.** NVIDIA hallucinated one CRITICAL finding not grounded in the pack (M0-CALIB-LNCH13); Gemini never hallucinated but was inconsistent run-to-run about which real (non-target) issue it surfaced, including once correctly finding R3-F2's bug on the *other* mission's pack (same vulnerable code, present there because R3-F2 wasn't fixed yet at that earlier commit) -- a genuine, evidenced finding, just not a match for that mission's own target. Full reasoning in each verdict's `calibrationScore.note` (`NPDev_General__OutsideRepo\external-ai-review\packs\M0-CALIB-*\*-verdict.json`). **Per this plan's own rule: both channels are recorded low-yield for this vulnerability class, not treated as assurance for any future "no findings" result from M1-M6.** A follow-up attempt to re-calibrate against a stronger NVIDIA model found this account's actual invocation entitlements are narrower than NVIDIA Build's public `/v1/models` catalog (3 candidate models 404'd); owner accepted `meta/llama-3.3-70b-instruct` as this key's practical ceiling on 2026-07-27 and let the 0/4 result stand — see ADR-0009's "P4 addendum" section |
| P5 — NPDev's own missions M1→M6 | **DONE — all 6 actually run, real real findings (2026-07-27)** | Real vendor calls, real code, real results — not a smoke test. **M1-SEC-GENCODE**: real generated Java sliced from the live `wmsoffice` FinalApp (`Movimento`/`LocalArmazenagem`/`CrossDocking`, all touched by real custom flows); gemini found 2 real issues (1 CONFIRMED HIGH — filed **REG-49**; 1 already-labeled MEDIUM DoS scan), nvidia found 0. **M2-SEC-ROWAUTHZ**: gemini found 1 real HIGH bug in `DefaultConceptGateway.delete()` (CONFIRMED, filed **REG-48**), nvidia found 0. **M3-SEC-TENANT**: gemini found 3 real issues (all CONFIRMED: CRITICAL+HIGH filed as **REG-50**, MEDIUM case-mismatch noted in REG-50), nvidia found 2 (same SQLi root cause as REG-50, plus one weaker LOW claim). **M4-REPRO-BLIND**: nvidia's command plan correctly flagged real ambiguities (no clone URL, no build prerequisites, Linux/PowerShell friction) — RUN, but the mission's own container-execution half is explicitly out of scope for this session (recorded via `details`/`note`, not silently implied closed). **M5-COLDSTART-DOCS**: nvidia authored a real `model.json` blind from the tutorial docs alone (zero repo access) and it passed the platform's own real validator (status: warning, only missing UI labels) — correctly matched the tutorial's actual target concept. **M6-AUDIT-VERDICT**: nvidia's audit of P0-P9's own claims vs a real evidence bundle was appropriately skeptical — distinguished "exists" from "proven to work," correctly flagged zero evidence had been given for the P2 claim. **None of the 3 real security findings were hallucinated** — every one verified by re-reading the actual source, a higher bar than trusting the vendor. Full verdicts/evidence: `NPDev_General__OutsideRepo/external-ai-review/packs/M{1,2,3,4,5,6}-*/`; run records `docs/external-ai-review/runs/M{1..6}-*.json`; findings filed at `docs/NPDEV_OPEN_ITEMS_REGISTER.md`'s new REG-48/49/50 section (OPEN — code-verified, RED-first test not yet written, per this plan's own P5 rule) |
| P6 — Product surfaces | **DONE** | `ReviewPackBuilder` (Java, byte-identical `manifestSha256` to the Python producer — 3 golden-hash tests); `NpdevExternalAiConfig` + `ReviewAdminController` (ControlPanel page `/api/admin/review/view`, compile-verified against a real generated FinalApp — see note below); `npdev review pack\|ingest` CLI; `npdev_build_review_pack`/`npdev_ingest_review_verdict` MCP tools, tested end-to-end over real JSON-RPC; adapter-registration checklist updated (3 proof-test lists) |
| P7 — Model schema surface | **DONE** | `sensitive` (field) + `externalAi.egress`/`vendors` (app) mirrored across all 4 `model.schema.json` copies; full Java chain (parser/AST/compiler/compiled-model/canonical-JSON writer+reader/`ModelResolver`/`SemanticValidator`); verified by the reflective `CanonicalJsonRoundTripCompletenessTest` ratchet + 5 new focused validator tests |
| P8 — Quality gate | **DONE — now actually proven green (2026-07-27)** | `scripts/quality/run-external-ai-gate.ps1`; `check-register-consistency.py`'s new `mission_run_coverage_gaps()`; found and fixed a real pre-existing gap in the shared AJV schema validator (draft-07 schemas silently couldn't validate). **Second real bug found running the gate for real, prompted directly by M6's own audit flagging "P8 exists but isn't proven to work":** `run-external-ai-gate.ps1`'s per-mission `Set-Content -Encoding UTF8` wrote a BOM, which the Node validator's `JSON.parse` choked on for every single mission — the gate's step 2/3 had never actually passed. Fixed both the script (`-Encoding utf8NoBOM`) and hardened `validate-json-schema.mjs`'s `readJson` to strip a leading BOM regardless of caller. Gate now genuinely exits 0 |
| P9 — Ledgers | **DONE** | This promotion; `docs/OPEN_GAPS_AND_ROADMAP.md` ADR-0009 row; `docs/POST_BETA0_HUMAN_ACTION_REGISTER.md` AI-delegable column; `knowledge/cards/recipe-external-ai-review-feature.json`; `docs/NPDEV_OPEN_ITEMS_REGISTER.md`'s new REG-48/49/50 section |

**Note on P6 verification depth.** `NpdevExternalAiConfig`/`ReviewAdminController` depend on
`com.npdev.generated.runtime.service.RuntimeContextService`, which only exists inside an assembled
FinalApp (NPDevRuntimeHost cannot be compiled standalone from this repo). Verified by copying both
files into a previously-generated sample app (`auxscreen`), restaging the three new adapter jars via
`scripts/runtimehost/sync-runtimehost-libs.ps1 -BuildLocalJars`, and running a real `compileJava` —
`BUILD SUCCESSFUL`. The copies were removed afterward; nothing was left behind in that sample app.

**D1/D2/D6/D7 answers (2026-07-26).** D1: NVIDIA Build + Google Gemini (revised same day from the
original "OpenAI, Google Gemini, xAI Grok" answer — owner supplied real NVIDIA Build and Gemini
keys instead). D2: API key integration (`external-ai-http` is the
primary transport; `external-ai-inproc` paste-transport still built as the offline/fail-safe twin).
D6: missions only — no general flow-step primitive in this pass. D7: build P0–P9 together, overriding
the plan's own "P0–P5 now, P6–P7 later" recommendation. D3, D4, D5 remain open — see
`docs/adr/ADR-0009-external-ai-delegation.md`'s decision block.

---

## 0. The reframing (what changed in rev 2)

Rev 1 treated "get an outsider to attack NPDev" as a one-off chore. It is actually an instance of a
general primitive the platform should own:

> **A governed delegation of a human-judgment step to an external AI.**
> `redacted pack in → external model → structured verdict out → filed, never auto-applied → provenance recorded`

Two instances, one contract:

| | Instance 1 — **platform (dogfood)** | Instance 2 — **product (the feature)** |
|---|---|---|
| Who asks | NPDev maintainers | any app author building on NPDev |
| What is judged | NPDev's own source, docs, gates | the author's own app — its model, emitted code, records |
| Missions | adversarial security review, blind reproduction, cold-start friction, audit verdict | *the same classes*, parameterised by their model — plus per-record judgments in flows |
| Surface | `npdev review` CLI + `scripts\external-review\` | ControlPanel page + CLI + a flow step + MCP tools |

**What makes this NPDev-shaped rather than "just call an LLM API":** the platform already owns the
model, so it knows what a field *means* — which is exactly what you need to redact correctly, to
build a pack that is complete, and to check a verdict against a declared contract. The governance
(fail-closed egress, calibration scoring, findings-not-fixes, provenance that never launders an AI
answer into a human sign-off) is the product.

**Hard invariant, unchanged and now architectural:** the external AI gets **no** filesystem, repo,
shell, or network access to the host. Its only input is the pack; its only output is text. In the
product this is guaranteed by construction — the adapter is the *only* egress path, and its default
denies.

---

## 1. Inventory — every "external person" citation found

Scanned `docs\**`, `knowledge\**`, `scripts\**` under `D:\WorkSpace\NPDev\NPDev_General`, and
`D:\WorkSpace\NPDev\NPDev_General__OutsideRepo\**`.

| # | Case | Cited at | What the person was for | Delegable? |
|---|---|---|---|---|
| **E1** | **Independent adversarial security review** | `docs\EXTERNAL_SECURITY_REVIEW_BRIEF.md` §0 "the one open item that cannot be closed from inside the project"; `THREAD_SUMMARY_2026-07-24_26.md` §4 row 1 | Attack surfaces 1–6, falsify claims C1–C6 | ✅ **YES** — an AI with no repo access is a *purer* independent reviewer than a person with a clone |
| **E2** | **REG-17** third-party reproduction, 2 of 4 gates | `docs\NPDEV_OPEN_ITEMS_REGISTER.md:996-998`; `docs\FINAL_FOUR_CLOSURE_PLAN.md:95-96,115,214`; `docs\REG17_ONION_HARVEST_PLAN.md:196-222` | Clone + build + run gates on foreign hardware | ⚠️ **SPLIT** — foreign hardware already supplied by GH runners (2026-07-24); the AI supplies not-the-author blindness |
| **E3** | **REG-13 / REG-14** cold-start authoring + docs test | `docs\EXTERNAL_TESTER_COLDSTART.md:3-12`; `docs\adr\ADR-0006-authoring-path.md:92` | Genuine first-contact friction | ✅ **YES** as a *strengthening re-run* — closed 2026-07-22, but by a same-sandbox repo-reading subagent the register itself calls "the subagent-as-tester approximation" (`:993`) |
| **E4** | Independent audit **sign-off** | `docs\POST_BETA0_HUMAN_ACTION_REGISTER.md:10` | Verdict on the evidence bundle | ⚠️ AI does the **review**; the **sign-off** stays the owner's |
| **E5** | Real participant sessions | `:11` | Product/UX feedback from real users | ❌ **NO** — an AI persona is not a participant |
| **E6** | Checkpoint approval · branch protection | `:7-9`; `docs\ROADMAP_BOUNDARY_POLICY.md:74` | Authority + repo admin | ❌ **NO** |
| **E7** | Owner decisions, incl. the **REG-17 DoD ruling** | `docs\REG17_ONION_HARVEST_PLAN.md:210-215` "the executor must never make this call" | Policy choices | ❌ **NO** — AI may write the decision brief |
| **E8** | Runtime `MANUAL_REVIEW` + operator conversion SQL | `docs\IMPACT_REPORTS.md:31`; ADR-0008; `ACCEPTED_BOUNDARIES.md` B8/B14 | An **end-user operator** | 🔄 **RE-CLASSIFIED IN REV 2** — was "out of scope". These are exactly instance #2: a generated app's human-judgment step. They become the feature's **first product use-case** |
| **E9** | Professional trademark search | `docs\adr\ADR-0007-distribution-model.md:6-7,74-111` | Legal clearance | ✅ Already resolved N/A by owner 2026-07-23 (REG-15 DONE) |

**E8 is the rev-2 insight.** `IMPACT_REPORTS.md:31` says a narrowing conversion is flagged
`MANUAL_REVIEW` "because convertibility needs a human", and `ACCEPTED_BOUNDARIES.md` has a family of
"stop and ask a human" boundaries. Those are the *product's* human-action steps — the same shape as
E1–E4, one layer out. A feature that answers them is worth more than a script that answers ours.

---

## 2. Where it lives — real extension points

| Layer | Artefact | Note |
|---|---|---|
| Contract | `NPDevContract\schemas\external-ai-pack.schema.json`, `external-ai-verdict.schema.json`, `external-ai-run.schema.json`, `external-ai-mission.schema.json` | Follows the `impact-report.schema.json` precedent |
| Kernel port | `NPDevKernel\kernel\src\main\java\com\npdev\kernel\ports\ExternalAiCapabilityContract.java` + registration in `CapabilityContractCatalog` | Sibling of `PersistenceCapabilityContract` |
| Redaction | **extend the existing family** — `EventRedactionPolicy` / `ExecutionRedactionPolicy` / `TraceRedactionPolicy` in `com.npdev.kernel.ports`, adapter `NPDevKernel\adapters\tracing-redaction-default` | ⚠️ **Do not add a 4th independent derivation of "what is sensitive"** — that is precisely the eight-passes debt REG-6 just paid off. One rule source, four consumers |
| Adapters | `NPDevKernel\adapters\external-ai-inproc` (air-gapped: writes pack to disk, ingests a pasted verdict) · `external-ai-http` (vendor endpoint) | Mirrors the `mail-inproc` / `mail-smtp` pair convention |
| Pack storage | reuse `file-store-inproc` / `file-store-objectstore` | Packs are files; do not invent storage |
| Runtime host | new `com.finalexec.review` package + `ControlPanelReviewController` | Precedent: the seed feature (`SeedDataService` + `DataSeedAdminController` + generic `seed-data.html`) |
| Surfaces | ControlPanel page · `npdev review pack|ingest|calibrate` CLI · optional flow step | The Impact Report's proven three-surface pattern (boot / CLI / ControlPanel) |
| MCP | `npdev_build_review_pack`, `npdev_ingest_review_verdict` | Joins `npdev_search_fix` / `npdev_check_support` |
| Model surface | field `sensitive: true`; app-level `externalAi.egress: denied\|packOnly\|apiEnabled` (**default `denied`**) | ⚠️ **`model.schema.json` is duplicated in 4 places** — every edit mirrors to all four |
| Governance | **ADR-0009 — External AI delegation: egress, redaction, and what an AI verdict may and may not close** | This project ratifies by ADR (0002…0008). A feature that sends user data to a third party needs one |
| Platform producer | `scripts\external-review\build-review-pack.py` (git-pinned via `git show <rev>:<path>`) | Only the platform side needs history access — for calibration at pre-fix commits |

**Fail closed.** `ExternalAiCapabilityContract`'s default implementation **denies egress and throws**,
exactly like `ConceptGateway.authorizeWrite()` — the thread's own lesson #5, and for the same reason:
a permissive default *is* the bug. Nothing leaves any app until its author opts in per app, per
mission, per vendor.

---

## 3. Mission profiles

| Mission | Instance | Closes | Pack contents (git-pinned, redacted) | Deliberately **excluded** |
|---|---|---|---|---|
| `M0-CALIB-LNCH13` | platform | *calibration* | emitted Java at the **pre-fix parent** of the LNCH13-F1 CRITICAL | every findings doc, the register, the fix |
| `M0-CALIB-R3F2` | platform | *calibration* | bond/junction endpoints at the **pre-fix parent** of R3-F2 | same |
| `M1-SEC-GENCODE` | both | E1 s.1 | 2–3 concepts' emitted Java incl. one with custom flows (~5–8k lines sliced from ~75k) | `REG16_*` docs, the register, the thread summary |
| `M2-SEC-ROWAUTHZ` | both | E1 s.2 | `DefaultConceptGateway.java` (595 ln), `ConceptGateway.java`, the semantic policy, `ACCEPTED_BOUNDARIES.md` | our conclusions about them |
| `M3-SEC-TENANT` | both | E1 s.3 | `ExecutionContext`, `TenantIsolationPolicy`, one `*-postgres` adapter's SQL | same |
| `M4-REPRO-BLIND` | platform | E2 | `GETTING_STARTED.md` + build-script text only; AI emits a command plan, a **clean Linux container / GH runner** executes it | the repo, prior CI logs, known-issue lists |
| `M5-COLDSTART-DOCS` | both | E3 | `TUTORIAL_FIRST_APP.md` + `DSL_REFERENCE.md` only; AI authors `model.json` blind, we build and relay errors verbatim | everything else, per the cold-start kit's own rule |
| `M6-AUDIT-VERDICT` | both | E4 | evidence bundle + explicit claim list | our verdicts |
| `M7-IMPACT-CONVERT` | **product** | **E8** | an Impact Report's `MANUAL_REVIEW` item + the column's shape/sample distribution (values redacted) | — |

`M7` is the proof the feature is a feature: the *same machinery* answers "is this column
convertible?" for an app operator that answers "is this endpoint exploitable?" for us.

**Anti-batching preserved:** one surface per round, per the six-round REG-16-resid discipline.

All 9 missions are defined in `scripts/external-review/missions.json` and validate against
`external-ai-mission.schema.json`. Each currently has an honest `NOT_RUN` record at
`docs/external-ai-review/runs/<mission-id>.json` — no mission is silently unaccounted for (P8's own
gate enforces this).

---

## 4. Phases

Contract-first, so nothing built for NPDev's own review is thrown away when it becomes the feature.
See "Progress as of 2026-07-27" above for current status; the phase table below is the original plan.

| Phase | Deliverable | Verification | Effort |
|---|---|---|---|
| **P0** | **ADR-0009** + the four contract schemas + the honesty vocabulary (`external-ai-verdict` is a distinct status from `independent-human-review`) | Schemas validate; new doc carries a tense marker | S |
| **P1** | Kernel port + `CapabilityContractCatalog` registration + adapter pair, **default denies** | RED-first: prove the default throws before any adapter opts in — the `authorizeWrite` proof shape | M |
| **P2** | Pack core: model-driven redaction (**extending the existing policy family, one rule source**), sanitizer that hard-fails on secret patterns, deterministic chunker + sha256 manifest | Second build → identical hash; zero secret hits; a pack containing our own findings doc = **build failure** | M |
| **P3** | Platform producer `scripts\external-review\` (git-pinned, can target historical commits) | Builds all 9 missions | M |
| **P4** | **CALIBRATION — the control.** Run `M0-CALIB-*` at the pre-fix parents of LNCH13-F1 / R3-F2 | Score found / missed / hallucinated. If the model finds **neither** known bug, record the channel **low-yield** and never dress its silence up as assurance | M |
| **P5** | NPDev's own missions M1→M2→M3, one per round; then M4, M5, M6 | Every finding gets a **RED-first local reproduction before any fix**. Unreproducible ≠ dismissed — filed `UNCONFIRMED` | L |
| **P6** | **Product surfaces**: `com.finalexec.review` service, ControlPanel page, `npdev review` CLI, MCP tools, generator emission | **Conformance test:** platform producer and in-app producer emit a **byte-identical pack** for the same input — the parity-probe discipline from the schema rebuild's Phase 3 | L |
| **P7** | Model surface: `sensitive: true`, `externalAi.egress` (default `denied`); **mirror `model.schema.json` to all 4 copies**; `M7-IMPACT-CONVERT` wired to the Impact Report | `SemanticValidator` rejects egress-enabled with no vendor configured; 4-copy mirror check green | M |
| **P8** | `run-external-ai-gate.ps1` + `check-register-consistency.py` extension | The new instrument asserts **its own scope**: a mission profile with no run record and no explicit `NOT_RUN` reason fails — the lesson from all 7 blind spots | M |
| **P9** | Ledgers: register rows; `POST_BETA0_HUMAN_ACTION_REGISTER.md` gains an honest **"AI-delegable?"** column; knowledge card; `docs\` feature doc | `run-ai-knowledge-gate.ps1` green | S |

**Order rationale.** P4 before P5 is non-negotiable: without the control, "no findings" from an
external model is indistinguishable from a model that cannot read Java — the exact failure lesson #1
describes ("a green check means the rule I encoded, over the scope I declared, found nothing").
P0–P2 before P3 costs little and prevents re-deriving the abstraction later — "pay principal, not
interest".

---

## 5. Honesty contract (into ADR-0009 + the feature doc)

1. A verdict is recorded as **`external-ai-verdict (vendor/model, no repo access)`** — never as
   "independent human review", never as "sign-off". **This applies to the product too:** an app
   author must not be able to produce an artefact that reads like a human approval.
2. It **does not close** E5 (participants), E6 (authority/admin), or E7 (owner decisions).
3. For E2 it satisfies the *not-the-author* half of REG-17's DoD only; the ruling stays the owner's.
4. **Pack curation is a declared limitation.** Every verdict records what the reviewer was and was
   not shown, and the calibration score travels **with** every "no findings" result.
5. Findings are **filed, never auto-applied** — the brief's own §5 rule, now enforced in code.
6. **Egress is a publish action.** Default `denied`, per-app opt-in, per-vendor, with the redacted
   pack reviewable *before* it is sent.
7. Raw transcripts → `D:\WorkSpace\NPDev\NPDev_General__OutsideRepo\external-ai-review\<date>\raw\`,
   never in the repo.

---

## 6. Known costs and hazards (stated up front)

| Hazard | Why it matters | Mitigation |
|---|---|---|
| A **4th independent redaction derivation** | Exactly the REG-6 eight-passes debt | One rule source in the existing policy family; the pack policy is a consumer, not a re-derivation |
| **4-way `model.schema.json` mirror** | `sensitive` + `externalAi` are new model keys | P7 mirrors all four copies; the existing consolidation check covers it |
| Findings storage as a **built-in pack** | `internal.tables` / `packs.included` are unusable for AppGen apps, and REG-39's stale-pack-copy hazard is fresh | Store review runs in runtime-host internal tables (the schema-history/seed precedent), **not** a business pack |
| Vendor lock / single-model blind spots | Two internal reviews found two different bugs; one external model has its own shared blind spots — the same structural flaw moved one layer out | Multi-vendor by design: ≥2 vendors for security missions |
| Pack size | ~75k emitted Java lines per app | Curated slices (~5–8k lines); the pack builder budgets and chunks |
| An AI verdict laundered into assurance | The failure this whole programme exists to prevent | Rule 1 above, enforced by the gate in P8 |

---

## 7. Owner decisions (D1–D7)

| # | Question | Answer |
|---|---|---|
| **D1** | Which external model(s)? | **Two (revised 2026-07-26): NVIDIA Build + Google Gemini** — replaces the original "OpenAI, Google Gemini, xAI Grok" |
| **D2** | Transport: API key or manual paste? | **API key integration** (`external-ai-http` primary; `external-ai-inproc` still built as the offline twin) |
| **D3** | Egress authorization for **NPDev's own** source | **ANSWERED 2026-07-27: approved**, per this plan's own recommendation — code excerpts (curated `packContents`) may be sent; `.env`/keys/DB dumps stay hard-blocked by the sanitizer regardless. Real vendor calls still each need their own explicit go-ahead (honesty rule 6) |
| **D4** | **REG-17 DoD ruling (E7)** | **PENDING** |
| **D5** | **E5** real participants — permanently open, or schedule sessions? | **PENDING** (recorded permanently open until answered) |
| **D6** | **Feature scope**: missions only, or also a general flow step? | **Missions only** — the flow step is explicitly deferred |
| **D7** | Does the product feature ship in **beta1**, or after NPDev's own review closes? | **Build P0–P9 together** — owner chose not to defer P6/P7 |
