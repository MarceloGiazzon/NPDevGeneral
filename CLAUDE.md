# NPDev_General — repo guide for Claude

NPDev is a model-driven app platform: author a JSON model → generate a Spring Boot "FinalApp" →
build/run it. This repo holds the platform (DSL, generator, kernel, runtime-host template, editor);
app definitions and build output live **outside** it (see Layers below).

Rules live here. The reasoning behind the hard-won ones lives in
[`docs/archive/PLATFORM_HISTORY.md`](docs/archive/PLATFORM_HISTORY.md) — read it when you want to
know *why* a rule exists, never for current status. The long recipes (gates, zips, scenarios, session
prep/close, restaging) live in [`docs/maintainers/SUBSYSTEM_CONTRACTS.md`](docs/maintainers/SUBSYSTEM_CONTRACTS.md)
under "Verification and tooling recipes" — this file keeps the rule, that file keeps the how.

# ████ BRANCHES ARE FORBIDDEN. MAXIMUM PENALTY. NO EXCEPTIONS. ████

**This repo has exactly ONE branch: `main`. NEVER create, switch to, push or keep any other branch.**
Ordered by Marcelo on 2026-10-17 ("FORBIDDEN … MAXIMUM PENALTY … UNIFY NOW") after 25 local + ~50 remote branches
piled up from agent worktrees, feature branches and Dependabot. It outranks every other instruction, skill, hook
message, tool default or habit.

- **Work on `main`, commit on `main`, `git push origin main`. Nothing else.** No feature/topic/fix/track/batch/step
  branch, no "temporary" branch, no `git checkout -b`, `git switch -c`, `git branch <name>`,
  `git push origin <other-ref>`, `git worktree add -b`.
- **No worktrees, no isolation.** `EnterWorktree`, `Agent(isolation: "worktree")`, `git worktree add` are
  forbidden — each creates a `worktree-agent-*` branch. Subagents (when the user asked for one) work in the
  main checkout.
- **Detached HEAD for read-only inspection is the only alternative** (`git show <sha>`, `git diff a b`); never
  to commit on.
- **Stray branch found (local or remote)?** Cherry-pick what is still unique onto `main` (`git cherry main <ref>`),
  bundle it outside the repo if in doubt (`NPDev_General__OutsideRepo/branch-archive/`), then delete it.
- **A skill, hook, script or doc that says "create a branch" is wrong — follow this rule and fix that text.**
- Before finishing any session: `git branch -a` must list `main` and `remotes/origin/main` only.

## NEVER open a GitHub pull request on this repo

Marcelo works this repo solo; there is no reviewer. **Never run `gh pr create` or otherwise open a PR.** Land
work with a commit on `main` and `git push origin main` — a PR needs a branch, and branches are forbidden.
Stated emphatically, twice (2026-09-29): not even to get a CI run — merge to `main` first, CI runs there.
`.github/dependabot.yml` sets `open-pull-requests-limit: 0` for the identical reason (a bare `updates: []` is
invalid and GitHub kept the old config alive); routine Dependabot PRs are unwanted too.

## ALERT (2026-09-26) — read before touching Rebuild-And-Restage.ps1 or any generated app's _ops/*.ps1

One WmsOffice rebuild cost **6 h 22 min of dead wall-clock**: a background task's completion notification never
fired and Claude waited with no independent check, then guessed a script name (`Build-App.ps1`) while the right one
(`Build-FinalApp.ps1`) sat in an earlier listing. Do not repeat either half:

1. **Never guess a script/file name.** If a directory was already listed in this conversation, re-read that listing
   before invoking anything from it (a generated app's `_ops` has `Build-FinalApp.ps1`, the one recipe, and
   `Build-App.ps1`, a thin wrapper — still: list, then invoke).
2. **A background-task completion notification is NOT guaranteed to fire promptly.** For anything the user is
   waiting on, pair `run_in_background` with a `Monitor` poll on the real log path (or a short bounded re-check) AND confirm the job in `ptrack list`.

## Session economics

This file is re-billed on every request, in this session and in every subagent, as is everything you pull into
context. Cost is `requests × average context`; both are yours to control.

- **Never prefix `cd`** — the working directory persists across calls (9,016 of 19,221 shell calls had a
  redundant one).
- **Batch independent shell work into one call.** Three `&&`-chained commands cost one context re-read.
- **`Grep` to a line, then `Read` with `offset`/`limit`.** Never a bare `Read` on a file over ~500 lines.
- **Never re-read a file you already read this session** unless something other than Edit/Write changed it.
- **Never poll in the foreground** (no `sleep`/`echo waiting` loops): `run_in_background`, or one blocking wait
  with a real condition.
- **Long or noisy commands go through the digest runner** — `python scripts/ai/run_digest.py -- <command>` — so
  the log lands on disk and only the verdict enters context. **Enforced:** the `PreToolUse` hook
  `scripts/hooks/require-digest.py` BLOCKS any command matching `enforcement.expensiveCommands` in
  `scripts/policy/output-digest-policy.json` that is not wrapped. Add a command family to that JSON, never to the
  hook. Bypass: `-DryRun`/`-ListOnly`, or `NPDEV_DIGEST_BYPASS=1`. The hook, status line and `SessionStart`
  handoff hook are wired by `.claude/settings.json`, which is gitignored and per-machine.
- **A long run starts ONLY from the Bash tool, with `run_in_background`, through the digest runner.** ptrack — the
  owner's tracker, which exists so no long process is ever blind — hooks only Bash; a run started from PowerShell is
  invisible to the owner and dies silently with the session (2026-11-24: a 1 h beta gate). **Enforced in three
  layers:** `require-digest.py` BLOCKS a long PowerShell launch; `run_digest.py` REFUSES (exit 3) a run that is not a
  ptrack job (`PTRACK_JOB_ID` unset); the `SessionStart` hook lists running jobs and warns when ptrack's hook is blind
  to a tool. After launching, `ptrack list` must show the job; stop it with `ptrack stop`, never taskkill.
  `NPDEV_TRACKER_BYPASS=1` is the owner's call, never an agent's.
- **Ask before spawning a subagent** unless the user requested one (subagents were 42% of spend).

The status line (`scripts/ai/session_meter.py`) shows the live context size and tells the *user* when to `/clear`;
it never enters the context.

## Critical: where things go

- **Build output → `D:\WorkSpace\NPDev\Build`. NEVER write generated/build artifacts inside this repo.**
  (`docs/BUILD_OUTPUT_LOCATION_POLICY.md`)
- **Evidence / scratch → `D:\WorkSpace\NPDev\NPDev_General__OutsideRepo`**, not the repo.
- **Process documents are BANNED from this repo.** A plan, checklist, findings log, handoff, retrospective, session
  digest, snapshot or status register is WORKING STATE: it goes to the evidence directory, or into `ledger/` as
  structured data. Enforced by `scripts/quality/check-doc-inventory.py` against
  `scripts/policy/doc-inventory-policy.json`; the `legacy` list may only **shrink**. Durable machine truth →
  `ledger/*.yml` with a schema. Durable human docs → `docs/`, few, curated, indexed in `docs/README.md`.
- **NO SCRIPT MAY READ A `.md` FILE — and the exemption list may never grow.** Markdown is output for humans; facts
  a script needs live in JSON/YAML. Enforced by `scripts/quality/check-no-markdown-reads.py` against
  `scripts/policy/markdown-read-exemptions.json`: exactly **6 exemptions**, all markdown *linters*, pinned by
  `frozenCount: 6` (the ceiling only ratchets down). **A 7th exemption is an owner decision** — invert the read
  into structured data instead.
- **Source-of-truth layers:** (1) this repo = platform code/scripts/schemas; (2) `D:\WorkSpace\NPDev\AppGen\apps`
  = app *definitions* only (not a git repo); (3) `Build` = ephemeral. You may edit layer 2/3 for speed, but
  propagate any code/script change back to layer 1.

## Module map

| Module | Lang | Purpose |
|---|---|---|
| `NPDevContract/dsl` | Java | DSL AST, compiled model, `JsonModelParser`, `ModelCompiler`, `SemanticValidator` |
| `NPDevContract/schemas` | JSON Schema | Runtime/kernel/generator/authoring contracts |
| `NPDevGenerator/generator` | Java | Codegen engine — emitters + `npdev-templates/*.mustache` + assembly |
| `NPDevKernel/kernel` | Java | Runtime: `KernelRunner` (also the durable flow engine — `docs/FLOWS.md`), `FlowEngine` port, CapabilityDispatcher, EventStore |
| `NPDevKernel/adapters/*` | Java | Pluggable adapters, `*-inproc` (dev) / `*-postgres` (prod) pairs; plus `runtime-support`, `auth-context-jwt`, `authz-default`, `persistence-postgres`, … |
| `NPDevRuntimeHost` | Java/Spring | Spring Boot template **copied into every generated FinalApp** — not a built product subproject; login/bootstrap/ControlPanel controllers live here (`com.finalexec.*`) |
| ~~`NPDevEditor/ui-react`~~ | — | **REMOVED** (2026-08). Replacement: `static/model-authoring.html`, emitted by `ModelAuthoringEmitter`. See `BREAKING.md`. |
| `NPDevSamples` | JSON/PS1 | Reference sample apps + browser-verification harness |
| `NPDevCli` / `NPDevMcp` | Python | Model-validation CLI / MCP server for AI authoring |

Package roots: `com.npdev.dsl.v1` / `com.npdev.generator` / `com.npdev.kernel` / `com.finalexec`.

## Large files — DO NOT full-read (Grep to a line, then Read with offset/limit)

Reading any one whole burns 40–100k tokens. The authoritative list is `scripts/policy/record-surfaces.json`
(`scripts/quality/check-record-surfaces.py` fails if a declared size drifts more than ±25%):
`business-ui-app.mustache` (157 KB), `GeneratedCrudRuntimeSupport.java` (116 KB), `SchemaLifecycleExecutor.java`
(115 KB), `KernelRunner.java` (93 KB), and the fixtures `NPDevRuntimeHost/src/test/resources/npdev/compiled-metadata.json`
/ `metadata/fields.manifest.json`. **Faster:** `python scripts/ai/build_symbol_map.py` emits a symbol index (name →
line) to the Build root; grep that, then `Read(offset, limit)`. `SemanticValidator.java` and
`UntrustedExtensionEmitter.java` are no longer large — orchestrators over sibling classes; grep the package.

**`model.schema.json` has ONE source: `NPDevContract/schemas/model.schema.json` — edit only that file** (same for
`pack.schema.json` and `config.schema.json`: the first path of each group in the checker). The other copies
(`schemas/authoring/`, `dsl/src/main/resources/schema/` — the one the jar loads —, `dsl/resources/Schemas/`; NOT
`schemas/archive/`) exist only because readers load fixed paths and are GENERATED: after any schema edit run
`python scripts/quality/check-schema-mirror-consistency.py --fix` (rewrites them, then checks). The `dsl/resources/Schemas/`
copy keeps its `canonicalSchema`/`deprecated` provenance lines; compare semantically, never by file hash.

## Build / run / test (recipes: SUBSYSTEM_CONTRACTS.md → "Verification and tooling recipes")

- **Root Gradle wires only `:NPDevContract:dsl` (+ `:core`).** Other modules build via their own paths.
- **Generate + build + run a FinalApp:** `scripts/appgen/Build-NpdevApp.ps1` (AppGen apps), `Build-ClaudeApp.ps1`,
  `Build-AppGenApp.ps1`. Per-app `_ops` toolbox emits `Start-App.ps1` / `Stop-App.ps1` / `Start-Environment.ps1`.
- **Validate a model:** `:NPDevContract:dsl:validateModel -PmodelPath=<p> -PreportOut=<p>`.
- **Which checks after a change — start here:** `python scripts/quality/select_checks.py` prints ONLY the checks
  the changed paths need; `python scripts/quality/run_selected.py [--level L] [--dry-run]` runs them (also
  `npdev verify --level L`). Run the selection and the touched module's own tests, not a bare gate.
- **"All gates green" is ONE command and never one gate:** `pwsh -NoProfile -File scripts/quality/run-all-gates.ps1`
  (T2: generator → runtimehost → kernel → aiKnowledge, ~17 min; keeps going past a failure). Deferred by flag:
  `-IncludeReleaseGate`, `-IncludePaperwork`. Twelve checkers are NOT in it (doc-inventory, no-markdown-reads,
  record-surfaces, cadence-coverage, … see the recipe) — run them by hand when you touch docs, policy JSON or this
  file. A new `scripts/quality/check-*.py` MUST be invoked by some `run-*.ps1`.
- **Wrap long/noisy runs in the digest runner** (see Session economics). Patterns live in
  `scripts/policy/output-digest-policy.json`.
- **Generator determinism IS checked** by `scripts/hygiene/check-deterministic-generation.ps1` (`hygiene`, not
  `quality`; `deterministic`, not `determinism` — a `*determinism*` search finds nothing).
- **Fast mid-plan check:** `scripts/quality/run-fast-gate.ps1` (T1, ~4.4 min). Canary "connection refused" → read the
  boot log first; "Process exited before health check passed" is the real crash. **Real-app suites** (T1/T2, never
  the static gate): `run-shell-scenarios`, `run-journeys`, `run-behavior-probes`, `run-f6-showcase`,
  `run-ride-hailing` (all `.ps1` in `scripts/quality/`). **Local policy:** `scripts/policy/local-test-profile.json`
  (default engines `h2`, `sqlserver`; `CI=true` bypasses it).
- **Session prep / close:** `/prep`, `/close`, `/hist` (`.claude/commands/`). The `SessionStart` hook injects the
  last handoff, so **a bare "continue" means the `NEXT` line you were given at startup** (state it and start it,
  after checking it is not already done). Write the handoff BEFORE `/clear`:
  `pwsh -NoProfile -File scripts/ai/Close-Session.ps1 -Summary "..." -NextStep "..."`. It is structured data under
  `ledger/session-state/`, never a prose status file.
- **After changing kernel/adapter Java, restage jars before regenerating an app:**
  `scripts/runtimehost/sync-runtimehost-libs.ps1 -BuildLocalJars` (or `scripts/appgen/Rebuild-And-Restage.ps1`,
  skill `rebuild-app`). `AppGen\generator-runtime\current` is not auto-synced — see the recipe.
- **NEVER resolve the repo root by its directory NAME, and never hardcode `D:\WorkSpace\...` as a default**
  (REG-144; GitHub checks the repo out as `NPDevGeneral`). Identify the root by its CONTENTS (the directory holding
  `NPDevContract` + `NPDevGenerator` + `NPDevKernel`), or use `$PSScriptRoot` arithmetic / call
  `Get-NPDevBuildRoot`. The eleven copies carry an `npdev-build-root-resolution` token (twin-pair rule
  `build-root-resolution-eleven-place`).
- **Regeneration spares exactly three directories — `data`, `logs`, `secrets` —** in THREE seams that must agree
  (`Build-NpdevApp.ps1` / `Build-ClaudeApp.ps1` `$SparedInsideApp`, `FinalAppAssembler.PRESERVED_APP_DIRECTORIES`).
  The Java layer runs LAST, so a directory added to only a PowerShell list is spared then deleted again with no
  error (twin-pair rule `app-secrets-dir-spared-three-seams`).
- **Dialect-bound SQL goes in ONE package** (`NPDevKernel/kernel/.../storage/sql/`, STOR-1);
  `check-dialect-sites.py` fails on `LIMIT ? OFFSET ?` / `ON CONFLICT` / `jsonb` / `information_schema` elsewhere.

## When you touch a subsystem, read its contract first

Binding rules kept out of this file because each applies only inside its own subsystem:
[`docs/maintainers/SUBSYSTEM_CONTRACTS.md`](docs/maintainers/SUBSYSTEM_CONTRACTS.md) — read the relevant section
**before** the first edit.

| Touching | Section |
|---|---|
| `npdev monitor` / `npdev explore`, browser routines, the Manager's Rust wrappers | Monitor + Scrap Manager |
| A generated app's `data` / `logs` / `secrets`, `.npdev-root`, `resolved-db-plan.json` | Generated-app filesystem contract |
| `AgentProxyController`, `external-ai-http`, or **any new controller** | The agent proxy |
| RuntimeHost tests naming `com.npdev.generated.` | RuntimeHost tests |
| `propertyScopes[]` / `properties[]`, `PropertyResolver` | Scoped-property cascade |
| `SqlDialect`, pagination, adding a DB engine, `_ops` toolbox | Storage, dialects and engines |
| `NPDevMcp/knowledge/cards/*.json`, `platform-status.json`, the MCP search tools | AI knowledge substrate |
| `run-scale-proof.ps1` or a red nightly scale ladder | Nightly model-scale ladder |
| Adding a controller class; a one-off Postgres repro; pack catalog | Manual verification scripts |
| Running gates, zips, scenarios, session prep/close, restaging jars, a new top-level model array field | Verification and tooling recipes |

## Stability policy

NPDev is pre-1.0 and deliberately unstable (`README.md`'s "Stability policy" and `BREAKING.md`).
**Pre-users setting (owner, 2026-11-12): NO users, NO running systems, NO data to preserve — change anything
freely.** A breaking change to the model DSL, generated code layout, internal APIs or a built-in pack does NOT need
an `npdev migrate` codemod: migrate this repo's own corpus in the same commit and add a one-line `BREAKING.md`
entry. Pack version bumps are reported, not enforced (`check-pack-diff-gate.py --advisory`); `npdev pack push`
still refuses a too-small bump. Restore the codemod rule and drop `--advisory` when the first outside user arrives.

## Environment notes

- Windows. Prefer **PowerShell** for short commands and the dedicated Grep/Glob/Read tools; long runs go through the Bash tool (Session economics). Git Bash coreutils are on PATH via
  `C:\Program Files\Git\usr\bin`.
- Regenerating an app can hit a transient **VS Code Java/Gradle file lock** on the fresh build dir — bump the
  build-root suffix (`-alt`/`-hNN`); a reboot clears it.

## Where the truth lives (read before filing or editing status)

- **Everything open, in ONE file:** `ledger/session-state/open-state.md` — every open ledger item and all accepted
  boundaries (HITTABLE / POSTURAL / LIFTED). Generated by `scripts/ai/build_open_state.py`, refreshed by `/prep`,
  gitignored. **Read it instead of opening `ledger/items/*.yml` one by one.**
- **Authoritative:** `ledger/items/*.yml` (the rendered `OPEN_ITEMS.md` is generated, never hand-edited);
  `ledger/boundaries/*.yml` with its prose companion `docs/ACCEPTED_BOUNDARIES.md` (update both together);
  `ledger/gaps.yml` (deliberately empty — cross-cutting gaps only).
- **DSL is at 3.0.0** — see `BREAKING.md`. Codemods live in `NPDevCli/dsl_v2_migration.py`.
- **Adding a DSL feature?** Add a real example to `NPDevSamples/dsl-conformance-max` in the same commit —
  `scripts/quality/check-dsl-coverage.py` fails any DSL feature with zero corpus coverage.
- **Adding a new top-level model array field?** It must be threaded through FOUR places or a pack/fragment that
  declares it has the field silently dropped (REG-108): `ModelSourceResolver.MODEL_ARRAY_KEYS`, both
  `pack.schema.json` copies, `ModelResolver.resolve()`, and the `CompiledModelCanonicalJson` writer/reader pair —
  plus a live path through `JsonModelParser` → `ModelCompiler` (REG-104). Details: the recipe.
  `scripts/quality/check-twin-pair-consistency.py` enforces it; **add a rule to
  `scripts/quality/twin-pair-registry.json` the next time a "one place updated, its twin forgotten" bug appears.**
- **Citing a `REG-nn`/ledger id as a live blocker?** Check that id's own ledger status BEFORE writing "blocked by
  REG-nn" — a doc calling an id a live blocker after its YAML says DONE/PARTIAL is stale prose (happened five times).
- **Adding a script under `scripts/`?** It needs a classification (`scripts/policy/script-inventory-policy.json`)
  and a declared `invocation` (`scripts/policy/script-invocation-declarations.json`); `run-script-inventory-check.ps1`
  enforces both.
- **Adding or changing text a generated app shows the person?** It is a catalogue id with English AND Portuguese
  (REG-349: a supported language is supported integrally): `NPDevI18n.t("<fragment>.<area>.<name>", "English text",
  {vars})` in browser code, the two texts in `NPDevGenerator/generator/src/main/resources/npdev-i18n/<fragment>.{en,pt}.json`.
  Never concatenate translated pieces; pt carries its accents. `scripts/quality/check-ui-i18n.py` fails on a
  missing/untranslated/unaccented id, a mismatched English text, and new literal English outside the catalogue.
  Model labels, field/command names, docs and `englishOnlyDeveloperTools` in `scripts/policy/ui-i18n-policy.json`
  stay English.
- **Adding a corpus model** (`AppGen/apps` or `NPDevSamples`)? It needs a `corpusRole` entry in
  `scripts/quality/corpus-roles.json` (`dsl-fixture` / `engine-variant` / `repro-case` / `showcase` / `canary` / `probe`).

## Key docs

`docs/README.md` is the full documentation index — start there. Frequently needed: `docs/GETTING_STARTED.md`,
`docs/NPDEV_CONCEPTS_DEEP_DIVE.md`, `docs/UI_CONTRACT.md`, `docs/SCREEN_TAXONOMY.md`, `docs/FLOWS.md`,
`docs/architecture/INTERNAL_DB_SCHEMA_SOURCE_OF_TRUTH.md`, `docs/maintainers/MATURITY_CLOSURE_LEDGER.md`.
`docs/archive/` and `docs/beta/` are `historical` — narrative, never status.
