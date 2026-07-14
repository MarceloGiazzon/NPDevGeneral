# AI Knowledge Loop & Iteration Tooling — Assessment + Implementation Plan

> **Status:** IMPLEMENTED (all five phases, 2026-07-13). This remains the design record; see the
> "Implementation status" box below for what landed. · **Created:** 2026-07-13 · **Branch:** `beta1-vision-spine`

> ## Implementation status (2026-07-13)
> All five phases are built and verified:
> - **Phase 1** — `schemas/ai/knowledge-card.schema.json`, 9 seed cards in `knowledge/cards/`,
>   `scripts/ai/extract_platform_status.py` → `knowledge/platform-status.json` (33 items),
>   `scripts/ai/build_knowledge.py`, gate `scripts/quality/run-ai-knowledge-gate.ps1` (green).
> - **Phase 2** — `build_rag_index.py` now chunks cards; server ranker boosts them ×1.5. Verified:
>   `npdev_search_examples "tenant default 403"` returns the card #1.
> - **Phase 3** — `scripts/ai/failure_signatures.py` normalizer + `failure-index.json` (23 sigs) +
>   new `npdev_search_fix` tool. Verified: exact-signature hit on a panel-unknown-entity diagnostic.
> - **Phase 3+ (live capture, 2026-07-13)** — the validate/fix loop is now instrumented in
>   `NPDevCli/npdev_cli.py` (`_capture_validation`, the chokepoint MCP `npdev_validate` + eval + direct
>   CLI all route through): each semantic validation is journaled per model identity, and when a prior
>   diagnostic disappears it writes a `{resolved diagnostics, model diff}` candidate under
>   `<Build>/npdev-ai/capture/` (best-effort, failure-isolated, `NPDEV_AI_CAPTURE=0` kills it, never
>   writes the repo). `scripts/ai/promote_candidates.py` clusters recurring candidates by signature,
>   drops ones already carded, and writes **draft** cards to the Build area for human `git mv` review.
>   Verified end-to-end on synthetic cycles: failed→passed emits a candidate with the right signature +
>   diff; two occurrences of a novel signature promote to a schema-valid draft card; an already-carded
>   signature is correctly skipped as "covered".
> - **Phase 4** — `npdev_check_support` tool over `capabilities.json`. Verified: `ARCH-10b`→DONE
>   (exact), `panel orderBy`→orderBy items (keyword), nonsense→explicit `unknown`.
> - **Phase 5** — `scripts/appgen/Rebuild-And-Restage.ps1` + tracked skills `rebuild-app` /
>   `verify-in-browser` under `.claude/skills/` (un-ignored). Wrapper not yet run against a live app.
>
> **Decisions taken:** skills home = `.claude/skills/` via a targeted `!.claude/skills/` un-ignore
> (so they function as real Claude Code skills and are tracked). Live error→fix pair capture is now
> built (Phase 3+ above). **CI trigger resolved:** rather than flip the heavy ~120-min
> `workflow_dispatch`-only workflows (the `BOND-B4` tension), the fast pure-Python gate runs
> automatically on PRs via `.github/workflows/ai-knowledge-gate.yml` (path-filtered to the knowledge
> substrate). **Real-path proof:** the capture path was exercised through the real Gradle validator
> (fail→fix on a sample model) — which surfaced that the validator emits a generic
> `semantic_validation_error` code with unquoted identifiers in the message; the normalizer now falls
> back to a keyword-templatized message signature for generic codes, so real diagnostics cluster
> name-agnostically (`flow <id> step <id>: references unknown capability <id>`).

> **Status:** Proposal / hand-off plan · **Created:** 2026-07-13 · **Branch at capture:** `beta1-vision-spine`
> **Audience:** an implementing AI agent (and its human reviewer) picking this up cold.
> **Scope:** four ideas about (a) closing the loop between dev-session knowledge and the corpora an
> external authoring AI retrieves from, and (b) codifying the maintainer's own build/verify iteration
> loop. This document assesses each idea against the *actual current code*, corrects a few premises,
> then specifies a phased, verifiable implementation.

---

## 0. TL;DR

- The four ideas are sound in spirit. Three of them (memory→corpus merge, failure-signature retrieval,
  proactive capability checks) share **one missing substrate**: a machine-readable, single-sourced
  store of *durable platform findings* (gotchas, gaps, constraints, error→fix pairs). Build that
  substrate once (Phase 1) and ideas 1–3 become "index it" / "expose it" on top of it.
- **Correct three premises before building** (see §1): the RAG corpus is `build_rag_index.py`'s output,
  *not* `golden-ai-scenarios`; the gaps ledger is human markdown that changes fast; and `.claude/` is
  git-ignored so skills need a tracked home.
- **Recommended sequence:** Phase 1 (knowledge substrate) → Phase 2 (RAG merge, idea 1) → Phase 3
  (failure-signature index, idea 2) → Phase 4 (proactive capability tool, idea 3). Phase 5 (maintainer
  skills, idea 4) is independent and can run in parallel — it's the fastest personal ROI.
- **Guiding constraint:** every derived artifact must be *generated from a single source and
  CI-checked for staleness*. A hand-maintained "supported features" list will lie within days.

---

## 1. Ground truth (read this before touching anything)

Facts established by reading the code on 2026-07-13. Cited so the implementer can re-verify.

### 1.1 What the external authoring AI actually retrieves from
- The MCP tool `npdev_search_examples` ([NPDevMcp/server.py:172](../../NPDevMcp/server.py#L172)) reads
  **`<Build>/npdev-ai/rag-index.json`** and does keyword/count ranking (`_rank_chunks`,
  [server.py:211](../../NPDevMcp/server.py#L211)) over chunk fields `title/text/keywords/objectType/source`.
- That index is produced by [scripts/ai/build_rag_index.py](../../scripts/ai/build_rag_index.py) from
  **only**: `docs/NPDEV_CONCEPTS_DEEP_DIVE.md`, `docs/NPDEV_USER_MANUAL.md`,
  `docs/ai/AUTHORING_FOR_AI.md`, and `NPDevSamples/*/Input/model.json` (chunked per concept/flow/panel/
  procedure/orchestration/event).
- **`golden-ai-scenarios/` is the eval/regression corpus, not the retrieval corpus.** Each scenario is
  a `scenario.manifest.json` + model(s) + `expected-behavior.json` (+ for negatives, an expected
  diagnostic). It is consumed by the eval harness, never surfaced to an authoring agent through search.
- **Neither `docs/OPEN_GAPS_AND_ROADMAP.md` (the gaps ledger) nor the maintainer's `~/.claude/.../memory/`
  files are read by any of the above.** They are three disconnected knowledge streams.

### 1.2 What the validate loop already produces (free signal for idea 2)
- `npdev_validate` → CLI `validate model --semantic` → `run_validate_semantic`
  ([NPDevCli/npdev_cli.py:588](../../NPDevCli/npdev_cli.py#L588)) emits a typed
  **`npdev-validation-report.v2`** with per-diagnostic `path / concept / field / suggestedFix`.
- The **negative golden scenarios already pair a broken model with its expected diagnostic** (e.g.
  `custom-panel-invalid-binding`, `workflow-invalid-transition`, `panel-unknown-entity`). That is a
  ready-made seed for a "failure signature → fix" index — no new capture plumbing required for v1.

### 1.3 The gaps ledger is fast-moving and human-shaped
- [docs/OPEN_GAPS_AND_ROADMAP.md](../../docs/OPEN_GAPS_AND_ROADMAP.md) has a machine-*parseable*
  priority table (§1) with stable IDs (`BUG-*`, `ARCH-*`, `BOND-*`, `AW-*`) and a status vocabulary
  (`OPEN/PARTIAL/NEEDS-VERIFY/DONE/BOUNDARY`). But the *detail* is prose, and it churns: all six design
  boundaries in §6 were lifted to features in a single day (2026-07-13). Any capability list copied by
  hand into the MCP server would be wrong within a release.

### 1.4 Skills have no tracked home yet
- `.claude/skills/` does not exist, and `.claude/` is in `.gitignore` (HYG-1, commit `f075f5f`) because
  it accumulates per-session permission logs. Project skills must therefore live somewhere **tracked**
  (recommended: `scripts/skills/` or a targeted `!.claude/skills/` un-ignore), or they won't be shared.

---

## 2. Assessment of each idea

### Idea 1 — Merge dev-session memory into the retrieval corpus  ✅ endorse, with a two-tier correction
**What's right:** the highest-leverage gap is real. An external agent re-discovers `ARCH-15`
("tenant `default` 403s all flow auth") or a widget-compatibility rule by burning a validate→fix cycle,
even though the answer already exists in maintainer memory. Publishing durable findings into what
retrieval actually reads removes that churn.

**What to correct:** the proposal says promote findings *to golden scenarios*. That's too heavy for most
findings — a golden scenario is ~7 files (model + manifest + expected-behavior + verification). A
build-ops gotcha like the `-RuntimeHostLibsDir` mismatch is **not authoring-scenario-shaped** and can't
be expressed as one. Split into two tiers:
- **Knowledge cards** (lightweight, the majority): one structured record per durable finding →
  indexed into the RAG corpus so `npdev_search_examples` surfaces it.
- **Golden scenarios** (heavyweight, the minority): only for findings that describe an *authoring
  behavior* worth a regression test (a model shape that should pass/fail). Promotion is a deliberate,
  occasional step — not the default path.

Memory `MEMORY.md` stays the *staging area*; the knowledge-card store is the *published* form.

### Idea 2 — Retrieve by failure signature, not schema section  ✅ endorse, start cheap
**What's right:** precedent-based retrieval ("you hit error X; here's how it was resolved last time")
is strictly more useful than reference retrieval for a self-correction loop, and the loop already emits
the exact (diagnostic → suggestedFix) pairing.

**What to correct / de-risk:** the proposal implies logging live (error → corrected-diff) pairs, which
needs new capture plumbing around the loop. Don't start there. **v1 harvests signal that already
exists**: (a) the `suggestedFix` fields on real validation reports, and (b) the negative golden
scenarios (broken model + expected diagnostic). Both are on disk today. Add live-diff capture only in a
later increment if v1 proves valuable. Also: **normalize signatures** — template out concept/field
names ("concept `<C>` references unknown concept `<T>`") so distinct instances collapse to one key.

### Idea 3 — Proactive "is X supported?" MCP tool  ✅ endorse, but derive the answer
**What's right:** moving a failure from expensive runtime validation to a one-call pre-check is the right
economics, and it's a natural extension of the existing typed tools.

**What to correct — this is the important one:** the answer source **must be derived, not authored**, or
it will hand agents confidently-wrong answers (see §1.3). Two derivation sources already exist:
- The **JSON schemas** define what is *authorable* — and `npdev_get_schema` already lets an agent check
  "does `model.schema` allow a `forEach` flow step?" So the *positive* space is largely covered.
- The genuinely missing surface is the **negative space**: places where the schema accepts something the
  *runtime* doesn't honor (the class of bug the gaps ledger tracks — e.g. old `ARCH-10b`, panel
  `orderBy` accepted but unapplied). Expose *that*, sourced from a machine-readable projection of the
  ledger keyed on its existing stable IDs — not a fresh hand-written list.

So idea 3 is best framed as **"expose the gaps ledger as a queryable tool, backed by a
machine-readable, CI-checked projection of it."**

### Idea 4 — Skills for the maintainer's own loop  ✅ endorse, fix the home + prefer a script core
**What's right:** the build/restage/generator-runtime three-cache-sync sequence and the ScrapForAI
verification gotchas are re-derived every session; codifying them kills a recurring class of "stale jar"
and "wrong locator" failures.

**What to correct:** (a) skills need a **tracked** home (§1.4). (b) A Claude "skill" is a markdown
instruction file — good for *judgment* (when to verify, which gotchas to watch), but the
three-cache-sync is better as a **single idempotent wrapper script** the skill *invokes*, so it's
runnable by a human too and can't drift from prose. Build the script first; the skill wraps it.

### Cross-cutting recommendation
Ideas 1–3 all read/write the same durable findings. Give them **one pipeline**: a single
`knowledge/` source-of-truth directory + one builder that fans out into (RAG chunks, failure-signature
index, capability projection). Don't build three independent readers of three formats.

---

## 3. Target architecture

```
   ┌─────────────────────────────┐        staging (human, per-session)
   │  ~/.claude/.../memory/*.md   │  ───────────────────────────────────┐
   └─────────────────────────────┘                                      │  promote durable findings
                                                                        ▼
   docs/OPEN_GAPS_AND_ROADMAP.md ──(machine table §1)──►  knowledge/platform-status.json
                                                                        │
   knowledge/cards/*.json  (structured findings: gotcha│gap│constraint│error-fix)
                                                                        │
                                    scripts/ai/build_knowledge.py  (single fan-out builder)
                                                                        │
                 ┌──────────────────────────────┬────────────────────────────────┐
                 ▼                               ▼                                ▼
    <Build>/npdev-ai/rag-index.json   <Build>/npdev-ai/failure-index.json   <Build>/npdev-ai/capabilities.json
       (idea 1: cards merged in)        (idea 2: signature → fix)              (idea 3: derived status)
                 │                               │                                │
                 ▼                               ▼                                ▼
        npdev_search_examples          npdev_search_fix (NEW)            npdev_check_support (NEW)
```

Everything under `<Build>/npdev-ai/` is a build artifact (never committed — Build-output policy). The
committed sources of truth are `knowledge/cards/*.json`, `knowledge/platform-status.json`, and the
ledger. A CI gate asserts the derived artifacts are reproducible and the status projection matches the
ledger's §1 table.

---

## 4. Phased implementation plan

Each phase is independently shippable and independently verifiable. File paths are concrete; adjust
names only if they collide with something the implementer discovers.

### Phase 1 — Knowledge substrate (enables ideas 1–3)

**Goal:** one committed, schema-validated store of durable findings + one machine-readable platform-
status projection, plus a single builder skeleton.

**Tasks**
1. **Card schema.** Add `schemas/ai/knowledge-card.schema.json`. Fields (draft):
   - `id` (kebab, stable), `type` (`gotcha|gap|constraint|error-fix|recipe`),
     `title`, `body` (markdown, ≤4000 chars to match existing RAG chunk cap),
     `keywords[]`, `appliesTo[]` (object types / subsystems, e.g. `panel`, `flow`, `build-ops`),
     `signature` (nullable; normalized error template, for `type:error-fix`),
     `fix` (nullable; the resolution text/snippet, for `type:error-fix`),
     `sourceRefs[]` (ledger IDs, file paths, or commit hashes),
     `status` (`active|superseded`), `supersededBy` (nullable id).
   - Register it the same way other `schemas/ai/*.schema.json` are (no code change needed;
     `npdev_list_schemas` auto-discovers by glob).
2. **Seed cards.** Create `knowledge/cards/` and author ~10 seed cards by *promoting existing memory*:
   at minimum `tenant-default-403` (ARCH-15), `runtimehost-libs-dir-mismatch`, `generator-runtime-cache-refresh`,
   `hash-guarded-npdev-generated`, the widget-compatibility rules, `scrapforai-localhost-127` gotcha.
   Each cites its `sourceRefs` (ledger ID and/or memory slug).
3. **Platform-status projection.** Add `knowledge/platform-status.json`: one entry per ledger §1 row
   `{id, title, category, status, priority, notes}`. Author a generator
   `scripts/ai/extract_platform_status.py` that parses the §1 markdown table of
   `docs/OPEN_GAPS_AND_ROADMAP.md` into this JSON, so it can be regenerated (not hand-drifted). The
   committed file is the generator's output.
4. **Builder skeleton.** Add `scripts/ai/build_knowledge.py` that (for now) validates every card against
   the card schema and copies `platform-status.json` → `<Build>/npdev-ai/capabilities.json`. Reuse
   `npdev_ai_common.ai_out_dir()`.
5. **CI staleness gate.** Extend an existing quality gate (or add
   `scripts/quality/run-ai-knowledge-gate.ps1`) that runs `extract_platform_status.py` and fails if the
   committed `platform-status.json` differs from freshly-extracted — i.e. the ledger and the projection
   drifted.

**Acceptance / verify**
- `python scripts/ai/build_knowledge.py` exits 0; every seed card validates.
- Re-running `extract_platform_status.py` produces a byte-identical `platform-status.json` (gate green).
- `npdev_list_schemas` lists `knowledge-card`.

### Phase 2 — Merge cards into RAG (idea 1)

**Goal:** `npdev_search_examples` surfaces knowledge cards alongside doc/sample chunks.

**Tasks**
1. In [build_rag_index.py](../../scripts/ai/build_rag_index.py), add a `chunk_cards()` pass that reads
   `knowledge/cards/*.json` (skip `status:superseded`) and emits chunks with
   `objectType:"knowledge-card"`, `title`, `keywords` (card `keywords[]` + tokenized title),
   `text` (card `body`), `source` (card path). Keep the existing doc/sample passes unchanged.
2. Fold this into `build_knowledge.py` so one command builds both indexes (call `build_rag_index.main()`),
   or have the knowledge gate invoke both. Avoid two competing writers of `rag-index.json`.
3. Bump the RAG ranker to lightly *boost* `knowledge-card` chunks (e.g. ×1.5) so a directly-relevant
   gotcha outranks an incidental doc keyword match. Keep it a constant, documented in code.

**Acceptance / verify**
- Rebuild the index; `npdev_search_examples` with query `"tenant default 403"` returns the
  `tenant-default-403` card in the top result (drive it via a JSON-RPC frame to `server.py`, the
  established test pattern).
- `byType` count in the builder output includes `knowledge-card: N`.

### Phase 3 — Failure-signature index + `npdev_search_fix` tool (idea 2)

**Goal:** an agent that received diagnostic X can retrieve the precedent fix in one call.

**Tasks**
1. **Signature normalizer.** Add `scripts/ai/failure_signatures.py` with `normalize(message, path)` →
   a template string: lower-case, replace concrete concept/field/identifier tokens (anything matching
   the model's identifier patterns, or the values in `concept`/`field`) with `<C>`/`<F>`/`<ID>`. Unit-test it.
2. **v1 corpus (no new capture):** in `build_knowledge.py`, build `<Build>/npdev-ai/failure-index.json`
   from two on-disk sources:
   - every negative `golden-ai-scenarios/*/` (read its expected diagnostic + the corrective note in its
     manifest/expected-behavior), and
   - every `type:error-fix` knowledge card.
   Index entries: `{signature, examples:[{message, fix, sourceRef}]}`.
3. **New MCP tool `npdev_search_fix`** in `server.py`: input `{message, path?, limit?}`; normalize the
   incoming message, look up by signature (exact first, then keyword fallback via the existing
   `_rank_chunks`-style scorer over `message`+`fix`), return ranked precedents. Mirror the existing tool
   registration/handler pattern exactly (`TOOLS` entry + `TOOL_HANDLERS` entry + `tool_search_fix`).
4. **Later increment (optional, gated):** live pair capture. Wrap the eval loop
   ([run_ai_authoring_eval.py](../../scripts/ai/run_ai_authoring_eval.py)) so that when a model goes
   `failed → passed` across two validate calls, it appends the (diagnostics, model-diff) pair to a
   capture log that feeds new `error-fix` cards. Keep this opt-in; do not block v1 on it.

**Acceptance / verify**
- Feed `server.py` a `tools/call npdev_search_fix` frame with a message copied verbatim from a negative
  golden scenario's expected diagnostic → the matching scenario's fix is the top precedent.
- Normalizer unit tests: two same-shape errors with different names collapse to one signature.

### Phase 4 — `npdev_check_support` tool (idea 3)

**Goal:** cheap pre-check for "does the platform support / correctly honor X" before an agent commits to
a model shape.

**Tasks**
1. **New MCP tool `npdev_check_support`** in `server.py`: input `{feature}` (free text or a ledger ID).
   Resolution order:
   - exact ledger-ID hit in `<Build>/npdev-ai/capabilities.json` → return `{id, status, notes,
     sourceRef}`;
   - else keyword match against `platform-status.json` titles/notes + `constraint`/`gap` knowledge cards;
   - else fall back to a schema probe hint ("no known gap; check `npdev_get_schema` for authorability").
   Register with the same pattern as the other tools.
2. **Never fabricate a positive.** If nothing matches, say "unknown — not a tracked gap" rather than
   "supported". The tool reports *known gaps/constraints*; absence of a gap is not a guarantee.
3. Document in the tool description that status reflects the ledger as of the last index build, and that
   `DONE`/`BOUNDARY` are as authoritative as `OPEN`.

**Acceptance / verify**
- `npdev_check_support {feature:"ARCH-10b"}` → returns `DONE` with the orderBy note.
- `npdev_check_support {feature:"panel orderBy"}` → keyword-resolves to the same entry.
- `npdev_check_support {feature:"something totally unrelated"}` → explicit "unknown / not tracked".

### Phase 5 — Maintainer iteration skills (idea 4, independent — can start immediately)

**Goal:** codify the two loops the maintainer re-derives every session, in a *tracked* location.

**Tasks**
1. **Tracked home.** Either create `scripts/skills/` (plain, obviously tracked) or add `!.claude/skills/`
   to `.gitignore` and put skills under `.claude/skills/`. Recommend `scripts/skills/` to avoid
   re-including a directory that otherwise holds per-session noise. Decide once, note it in `CLAUDE.md`.
2. **Rebuild-and-restage wrapper script** — the higher-value half. Add
   `scripts/appgen/Rebuild-And-Restage.ps1` that runs, in order, with a *single* shared
   `-RuntimeHostLibsDir` argument threaded through both callees:
   - `scripts/runtimehost/sync-runtimehost-libs.ps1 -BuildLocalJars -RuntimeHostLibsDir <dir>`
   - `AppGen/generator-runtime/prepare-npdev-generator-runtime.ps1 -RuntimeRoot <root>`
   - `scripts/appgen/Build-NpdevApp.ps1 ... -RuntimeHostLibsDir <dir>`
   Make it idempotent and fail-fast; echo which cache each step refreshed. This directly kills the
   "stale jar because the two default dirs differ" class (documented in CLAUDE.md and the ledger §0).
3. **Skill: `rebuild-app`** (markdown) that explains *when* to run the wrapper (after any
   kernel/adapter/generator Java change), the VS Code file-lock workaround (bump `-alt`/`-hNN`), and
   the hash-guarded `npdev-generated/` "never post-edit" rule.
4. **Skill: `verify-in-browser`** (markdown) codifying the ScrapForAI loop: harness location
   (`NPDevSamples/scripts/browser/scrapforai-harness.ps1` and the external
   `D:\WorkSpace\ScrapForAILegacy`), the durable gotchas (`127.0.0.1` not `localhost`, SSRF allowlist,
   strict locators, debounced search, `#apiKey` fill+reload before asserting), and the
   `Assert-RoutineGreen` "console.error = failure (but theme.css 404 / pre-auth 401 are benign)" nuance.
   Reference the existing memory entries as the authoritative source so the skill and memory can't drift.

**Acceptance / verify**
- `Rebuild-And-Restage.ps1` on a trivial kernel change produces a running app whose jar timestamp is
  newer than the change (prove the stale-jar path is closed).
- Each skill is tracked by git (`git status` shows it, not ignored).

---

## 5. Recommended sequence & effort

| Phase | Idea | Depends on | Rough size | Personal vs. external value |
|---|---|---|---|---|
| 1 | substrate | — | M | enabling |
| 2 | 1 (RAG merge) | 1 | S | external authoring |
| 3 | 2 (failure index) | 1 | M | external authoring |
| 4 | 3 (capability tool) | 1 | S | external authoring |
| 5 | 4 (maintainer skills) | — | S–M | **maintainer, immediate** |

Start **Phase 5 in parallel with Phase 1** — it's independent, tracked-home is a 5-minute decision, and
the rebuild wrapper pays back on the very next app build. Land Phases 2–4 in order once the substrate
exists.

## 6. Risks & guardrails

- **Staleness is the whole game.** Every derived artifact (`capabilities.json`, `platform-status.json`,
  both indexes) must be regenerated by a script and CI-checked against its source. No hand-edited
  derived files. The Phase-1 gate is non-negotiable.
- **Don't let `npdev_check_support` lie by omission.** "Unknown" must never render as "supported."
- **Build-output policy.** All indexes/artifacts under `<Build>/npdev-ai/`; only `knowledge/` sources
  and the ledger are committed. (`docs/BUILD_OUTPUT_LOCATION_POLICY.md`.)
- **Schema-mirror rule does *not* apply here** — `knowledge-card.schema.json` is an `schemas/ai/` doc,
  not `model.schema.json`, so it lives in one place. Do not fan it into the four model-schema mirrors.
- **Keep the retrieval ranker dependency-free** for now (matches the existing zero-dep MCP design);
  leave a `vector` slot on chunks so embeddings can be added later without changing consumers, exactly
  as `build_rag_index.py` already notes.
- **Promotion stays deliberate.** Memory → card is a human/agent judgement call, not an automatic sync;
  auto-dumping every session note into the corpus would pollute retrieval. The bar is *durable and
  generally-applicable*.

## 7. Open decisions for the maintainer

1. **Skills home:** `scripts/skills/` (recommended) vs. un-ignoring `.claude/skills/`?
2. **Live pair capture (Phase 3 later increment):** worth the plumbing, or is the on-disk v1 corpus
   (golden negatives + error-fix cards) enough for now?
3. **CI trigger:** the AI-knowledge staleness gate — run it in the existing manual `workflow_dispatch`
   workflows, or wire a lightweight `pull_request` trigger? (Same open question as `BOND-B4` in the
   ledger; decide once for both.)
```
