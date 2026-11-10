# Changelog

Format: see `docs/RELEASE_PROCESS.md`. Dates are release-tag dates, not commit dates.

## [Unreleased]

Landed on `main` AFTER the `beta1.20` tag (`6ca4edfb`) and therefore **not** in that
release. Ships in the next tag.

### Added
- **`npdev ai relay`** -- use a chat AI in a browser, no API key and no MCP: `start`, `prompt`,
  `ingest`, `apply`, `undo`, `status`, `list`, `abort`. It composes the measured prompt, sorts
  whatever comes back into one outcome (ready, cut off, left parts out, asked questions, out of
  quota, ...) with the next message to paste, validates it, runs the authoring gate for a change
  (a rename, removal or security change needs the owner's explicit yes), applies with a backup and
  undoes byte for byte. The Manager's Prompter now shows this same prompt and has a paste-back box.
  See `docs/AUTHORING_WITH_AI.md`.
- **Use a browser AI** (Manager 0.13.24) -- a new screen under My apps for the same job with no API
  key: say what you want, COPY one text, give it to any chat AI you already use, paste its answer
  back, read what it would change in plain words, tick the box a removal or rename asks for, and
  Apply; a change can be undone. Ask AI on Home and Edit now lead here, and the API-key screen is
  one button away on both, carrying the request across. `npdev ai relay apply` now refuses a new
  app's name that is not one plain folder name (`../x`, `a/b`, `C:x`).
- **Use a browser AI takes the answer three more ways** (Manager 0.13.25) -- on step 3, Ctrl+V works
  anywhere (no need to click the box first), a Choose a file button opens the system dialog for the
  file the AI made (`.json`, `.txt`, `.md`, `.zip`), and dropping that file on the window works too.
  All three go through the same check as a pasted answer and tell the CLI how they arrived
  (`paste`, `file-picker`, `drop`).
- **Use a browser AI finishes the job** (Manager 0.13.26) -- after Apply the app is generated, built,
  started and waited for until it answers, with one live sentence per stage, then Open the app. A
  change to an app that is running stops it first (a running app holds its own files). A failed build
  or start says so in a sentence, keeps the reason under See what happened and offers another try;
  Undo makes Update the app now available again. `npdev ai relay apply --parent-dir D` now puts a new
  app in `D/<name>/<name>`, the folder layout New app makes, so two apps never share one `ArtifactNP`.
- **Use a browser AI: follow-ups and a history** (Manager 0.13.27) -- "Take it to another AI" (free
  limit reached) and "Copy for a new conversation" (going in circles) now copy the CLI's
  `next.freshChatPrompt`, the whole request for a new chat; every other follow-up button still copies
  `next.prompt`, the short message for the conversation already open. A small list under the answer
  box, drawn from `npdev ai relay status`, shows the rounds so far (cut off, mistakes to fix, new
  conversation, waiting for the answer).
- **Use a browser AI: changing the app you just made works** (Manager 0.13.28) -- four defects found
  driving the real window, none visible to the mocked render check. (1) `npdev ai relay` found the
  model of a Manager-made app (its `<name>-app` folder, the model beside it) when starting a change
  but not when checking or applying one, so every answer came back as "the app changed since" and
  nothing could be applied; start, check, apply and undo now share one resolver. (2) A change to a
  running app regenerated it before stopping it, and Windows refused to replace the folder the
  running app held open ("The app could not be built"); it is now stopped first. (3) After creating an
  app, Make another change listed the apps read before the new one existed, and switching to the
  API-key screen and back brought the other screen's older app choice with it; starting over now
  re-reads the list and the switch carries the chosen app once the other screen has re-read its own.
  (4) An app this window started that then stopped by itself (the schema guard refuses to boot a
  destructive change, on purpose) stayed in the Manager's table of running apps, so every later start
  answered "already running from this window" and nothing ran, even after Undo; an exited process is
  now forgotten.
- **Use a browser AI: a second app on the same address says so** (Manager 0.13.29) -- generated apps
  share port 8080 by default, so making a second app while the first runs could not start. The
  screen waited three minutes and said only "started but has not answered yet"; it now stops waiting
  once the probe has said another app holds the port for 30 seconds (a new app needs a while before it
  even tries the port, and the other one may just be stopping) and says in one sentence which problem
  it is and what to do (stop the other app in Running apps, then try again); the app's own
  explanation stays under See what happened. Try again on a new app that is already running now stops
  it before regenerating it, as a change always did.
- **Use a browser AI: the answer is picked up when you copy it** (Manager 0.13.30) -- while step 3 waits,
  the Manager looks at the clipboard once a second, so pressing the chat AI's own Copy button is the
  whole job: no switching back, no paste. Only the Rust side reads the clipboard (`arboard`, text only,
  `src/relay_watch.rs`); the page starts and stops it and always shows a notice with a Stop button.
  A text is looked at only if it carries this conversation's `NPDEV-BEGIN <code>` marker, or carries no
  marker and is a JSON object with `concepts`; anything else is dropped on the spot (not stored, not
  logged, not even its length -- only a SHA-256 of the last text stays in memory). A match goes to
  `npdev ai relay ingest --source clipboard-watch` and is drawn exactly like a pasted answer; the
  prompt echo and a wrong-conversation answer stay silent. It stops when the step is left, the answer
  is ready, the conversation ends, or the switch **Watch the clipboard while waiting for an AI**
  (Settings > AI, on by default) is turned off. A clipboard that stays unreachable for ten seconds
  (a locked-down desktop, another program holding it) turns the notice into "cannot watch the clipboard
  on this computer, paste the answer" instead of claiming to watch. New events `relay-answer` and
  `relay-watch-state`.
- **Use a browser AI: what the answer would change, in Portuguese** -- the preview sentences and the
  removal/rename warnings were English in a Portuguese window. `npdev ai relay` now writes them in
  the conversation's language (`npdev_relay_preview_pt.py`, from each line's `code` and `params`, so
  the words change but what a tick confirms does not); other languages are untouched, and a line it
  cannot write keeps its English text. Model names and the AI's own words stay as they are.
- **Use a browser AI: the answer is picked up when you save it** (Manager 0.13.31) -- some chat AIs
  hand the answer over as a file (a `model.json`, a zip) instead of text to copy. While step 3 waits
  the Manager now also looks at the Downloads folder every two seconds, so saving the file is the whole
  job. Only the Rust side reads it (`src/relay_downloads.rs`, `src/relay_watch.rs`): files that appeared
  after the waiting began (created or changed, so a browser's old `Last-Modified` does not hide one),
  top level only, `.json` `.zip` `.txt` `.md`, up to the CLI's own size limits, and only once two polls
  in a row saw the same size (a download still being written is never read). A file is kept only if its
  text carries this conversation's `NPDEV-BEGIN <code>` marker or is a JSON object with one of the
  protocol's keys (`concepts`, `upsert`, `remove`, `renames`, ...); a zip needs such a `model.json`,
  `npdev-elements.json` or `npdev-submission.json` inside, read in memory and never extracted. Every
  other file is forgotten (nothing stored or logged), and the Manager never moves, renames or deletes
  anything. A match goes to `npdev ai relay ingest --file --source downloads-watch` and is drawn like
  a pasted answer, saying "Got the answer from your Downloads folder". The same answer arriving by
  Copy and by Download within ten seconds is checked once. One notice, one Stop and one switch cover
  both places -- the switch is now **Watch the clipboard and the Downloads folder while waiting for
  an AI**, so a person who had turned the clipboard watching off keeps it off -- and `relay-watch-state`
  carries `sources`, so the notice names only the places really being read.
- **Use a browser AI: a conversation left half-way comes back from Home** (Manager 0.13.32) -- the CLI
  already kept every conversation on disk, but a window closed in the middle of one had no way back in.
  Home now asks `npdev ai relay list` once it is drawn (the CLI takes one to three seconds while the
  Manager starts, so Home does not wait for it; a failure means no card) and, when a conversation is still
  open, makes it the next step: **You have a conversation with an AI in
  progress -- Continue**, with the person's own request (cut to 80 characters) under it. Continue opens
  the screen and lands on the step the conversation is really at, from `relay status` and the new
  `relay_stored_prompt` command (`npdev ai relay prompt --code`, the latest round's stored text, nothing
  composed again): an answer that was READY and never applied goes to its preview; an answer that was
  not ready goes to step 3 with the same sentence and the message to send that it had; a conversation
  never answered goes to step 2 with the text to copy again. Coming back to the conversation the window
  is already in changes nothing, and one that cannot be read, or was finished meanwhile, says so in a
  sentence and leaves a fresh step 1. A conversation nobody touched for 14 days is left alone (it
  stays on disk): one forgotten chat would otherwise be the next step for good, hiding every other
  card. A re-read of the app list no longer turns an open change into a
  create. Home's card text now fills `{app}`/`{ask}`/... with function replacers, so a request or an
  app name containing `$&` shows as typed.
- **Agent Access** (`agentAccess` model block): a generated app serves a standard MCP endpoint
  (`/api/mcp`) so Claude Code/Desktop or any MCP client can use its data as tools, and optionally
  runs a Telegram bot linking a Telegram account to an app user for plain-language chat -- every
  tool call executes through the app's own REST API as that user, so existing role permissions and
  row-level `access` rules decide what happens, never a second permission system. See
  `docs/reference/AGENT_ACCESS.md`.
- **Agent Access over WhatsApp**: `agentAccess.channels.whatsapp` now runs a WhatsApp Cloud API
  channel next to Telegram -- Meta delivers to `/api/hooks/agent/whatsapp` (signature-checked), a
  user links with `link <code>` from the Connect page, and writes still wait for a Confirm button.
- **Verification panel** (`npdev verify --panel`): one inventory of what verifies this system --
  name, category, command, last result, last run, last duration -- rendered as a Kanban and a table.
  A new Manager tab for the repo, and a READ-ONLY `verification.html` emitted into every generated
  app. One contract (`npdev-verification-panel.v1`), two producers, one renderer.
- **`npdev probe`**: an occasional runtime diagnostic that boots a generated app under the JaCoCo
  agent and reports EXECUTION REACH -- what a real session actually executed, including code
  delivered as prebuilt jars that build-time coverage structurally cannot see. Deliberately never
  called "coverage", and it never writes a coverage floor.
- **`npdev verify --run <id>`**: run a declared verification item from the Manager. ID-only input
  resolved from the cadence ledger, executed through the existing controlled-command runner. No HTTP
  surface; the generated app's panel has no execute path at all.

### Fixed
- The **Beta 0 release gate** had never completed a run since April, for three stacked reasons in its
  own plumbing, none of them a defect in NPDev: `npm --prefix` ENOENTing on Windows so the schema
  validator never started (gate 1 of 27); the gate installing Java 21 while the builds request a
  Java 17 toolchain (gate 8); and a hygiene script deleting `NPDevRuntimeHost/build.gradle` -- a
  TRACKED file -- which dirtied a clean checkout and blocked release eligibility outright.
- `docker-linux-proof` now records SKIPPED with a reason on a non-Linux Docker daemon instead of
  failing. It builds a Linux image, so on a Windows-container daemon it cannot run at all. Skipped is
  never reported as passed.
- `runtimehost-core/gradlew.bat` was committed with CRLF in the object database, so it read as
  modified on every checkout since the Gradle wrapper bump. Renormalized.

## [beta1.20] - 2026-08-26

Scoped to the 214 commits between `beta1.19` (`522b0c8c`, 2026-08-15) and this tag (`6ca4edfb`).
Every releases since `beta1.8` landed with no `CHANGELOG.md` entry (recorded as a known gap in
`scripts/policy/changelog-versions.json`); this entry does not attempt to reconstruct that backlog
retroactively, only to bring the file current from here forward. Highlights, not an exhaustive
commit-by-commit list — see `git log beta1.19..beta1.20` for that.

### Added
- New `npdev` CLI verbs: `impact` (change-preview report over the existing migration-diff/
  inspect-usage/pack-diff backends, R1.6), `bench` (per-app latency probe with saved baselines,
  R3.7), `service` (install/uninstall wrappers around the guarded launchers, R9.6/R8.4),
  `package`/`upgrade` and `pack verify` (R9.5/R8.3), `pack search` + NPR catalog (R8.4), `monitor
  hotswap` and `monitor clone` (isolated instances for parallel testers, R3.8), `inspect bonds
  --diagram` (self-contained ER-diagram HTML/SVG), and pixel-diff visual regression with
  incremental dev-loop builds (R3.6/R1.2/R9.8).
- Pack system: an `extends` keyword on `pack.json` (PACK-10, R8.11), sealed-pack jar building with
  real REST-layer CRUD wiring (BUILD-2), Ed25519 pack signing + a trust policy (PACK-8/R8.7), OCI
  Distribution API v2 pull, `pack publish --push` with digest pinning and shared multi-app ingress,
  transitive pack dependencies with mutable tag pinning, pack-declared seed data (R8.8), pack role
  bindings and document bands (PACK-9/R8.9, R5.7), and additive pack extensions with collision/
  sealedness refusal (R8.11).
- DSL: effective-dated values (`concept.temporal` + `asOf` resolution, R5.8), `concept.softDelete`
  (R5.4), declarative `sequences[]`/`nextNumber()` (R5.3), cross-concept invariants at aggregate
  scope (R4.4), per-locale label maps (R5.6), predicate grammar v2 — OR/IN/contains/null tests/
  reference paths (R4.3), inbound `webhooks[]` with HMAC (R6.2) and a real outbound webhook adapter
  (R6.1), document rendering with real MIME mail (R6.3), and a cross-app messaging event bridge
  (R6.4).
- UI: bulk selection and bulk actions on every concept grid (R7.8, with batched PATCH/DELETE
  endpoints), a generic CSV import wizard (R7.6), keyboard-first entry in modal/picker/workbench
  grid (R7.9), a responsive pass on grid/forms/workbench/sidebar (R7.10), dashboard gadgets that
  drill through to a pre-filtered grid (R7.5), an async-flow-execution rail (R7.7), and a routine
  recorder in the generated shell (R3.9).
- Ops: scheduled backups with retention and an observability profile (R9.9/R9.10), model-wide
  reference index + orphan validation + `rename --cascade` (xref), and server-enforced field-level
  access (R5.5).
- Quality: JaCoCo coverage ratchet baseline across all four Java builds (R3), Dependabot + CodeQL +
  workflow-permissions hardening (R2).

### Changed
- **`NPDevKernel/adapters/expression-cel` renamed to `runtime-support`** (RUN-5, 598 references
  across settings/build files, CI workflows, policy JSON, docs and ledger) — the module never had a
  CEL library dependency; the old name was simply wrong. Zero functional change.
- `EDIT-12`/`R10.3`: the frozen `npdev-templates/static-react/` bundle was deleted (owner decision)
  now that `static/model-authoring.html` covers starter templates and all seven scaffolding
  actions — a generated app no longer serves `/npdev-ui-react/` at all. See `BREAKING.md`.
- `RUN-24`/`RUN-22`: `npdev dev` now hot-swaps a `METADATA_ONLY` model edit with no app restart,
  and the served UI manifest patches in place — proven live by a real browser routine, not staged.

### Fixed
- **R5.2/RUN-1 (the last one): a concept's uniqueness check no longer loads the whole tenant table
  into memory to compare in the JVM.** `ConceptStore#existsUnique` pushes a candidate-narrowing `WHERE`
  down to SQL first; every row it returns is still re-checked in Java against the canonical rule, so a
  dialect formatting wrinkle can only ever cost a harmless false positive. Measured 7.4x on 100k rows.
- **RUN-11/R9.3: a `MigrationMutex` now stops two instances booting against the same database from
  both running the schema migration at once** — closes a real hole where a concurrently-applied,
  half-finished migration could leave a database in an inconsistent, half-migrated state.
- **REG-193 (found live, not staged): a platform-added internal table now self-heals onto an
  already-migrated database.** Internal-table `CREATE TABLE` was emitted only into Flyway's
  one-time `V1__` script, so a newly-registered internal table appeared on fresh databases and was
  silently absent from existing ones — hit in the wild via `npdev_cron_fire_claim`, causing an HTTP
  503 on three app/config combinations.
- REG-195: access rules using `$user.roles.contains(...)` no longer always deny.
- RUN-4/R2.6: capability calls (`external-ai-http`, `mail-smtp`) now carry an enforced deadline via
  a context-propagating executor, closing a case where a hung adapter call never timed out.
- RUN-3/R8b: `NULLS FIRST` ordering now routes through `SqlDialect` instead of being hardcoded,
  fixing a cross-engine ordering divergence.
- QUAL-22/R2.7: the cron scheduler no longer hard-requires a `DataSource`, and each cron window is
  claimed so two instances cannot double-fire it.
- SCALE-2: the JVM's 255-constructor-parameter ceiling no longer silently caps a model at 255
  concepts with a compile failure nobody had read (`SCALE-1`'s nightly ladder had been red every
  night since its first run).
- QUAL-12/13/14, REG-182/183: `engine-support.yml` now runs on every push to `main`, not only a tag
  or manual dispatch — it had gone dark for 15 merges, including the one that shipped a
  non-executable `gradlew` into `beta1.18`. `run-runtimehost-core/gradlew` itself is restored to
  executable, which is what unblocked this release's own CI.
- Security: `fast-uri` bumped 3.1.4 → 3.1.5 in `json-schema-validator` (audit finding).
- Manager: an ops-lock leak, Flyway/DB-identity test gaps, and a JWT cookie-auth NPE.

## [beta1.8] - 2026-08-08

Everything below is scoped to the 12 commits that landed on `main` between `beta1.7`
(`6a87d8e`) and this tag, plus ROUND2_PLAN.md's R1 work landing directly before the tag — not a
retroactive pass over the much larger `[Unreleased]` backlog below, which predates this entry and
is tracked separately.

### Added
- Per-app Java level (`config.json`'s `build.javaVersion`) and declared third-party dependencies
  (`build.repositories[]`/`build.dependencies[]`) for generated apps (deps-and-java/PLAN.md,
  REG-140/REG-141). `AppDependenciesEmitterTest` added as regression cover for the dependency
  emitter, which had shipped with none (ROUND2_PLAN.md R1a).
- Semantic-behavior-writeback shipped for real (REG-138) — `execute()` now actually mutates the
  model instead of the excluded-by-default stub.

### Fixed
- REG-139: blank-page-on-fresh-boot crash in the editor's `ModelEditorPanel`, three-layer fix,
  verified live (RED/GREEN).
- Editor: removed dead structural-writeback code; fixed an asset-copy bug that dropped JS chunks.
- A generated app's "step0" zero-setup trial profile could fail to boot at all
  (`UnsatisfiedDependencyException` for `DataSource`, then a second one for a duplicate
  `TraceStore`/`FlowInstanceStore` bean) whenever its model resolved to the InMemory engine at
  generation time — exactly the case for any app generated without a live database connection, and
  exactly the path the Manager's "New app" zero-setup flow depends on. Found and fixed as part of
  the Gradle/Spring Boot bump below; see `REG-143`.

### Behavior changes
- **`build.javaVersion` widened from `enum [17, 21]` to a floor-only `minimum: 17` — any integer
  >= 17 is now accepted, with no upper bound (ROUND2_PLAN.md R1c).** Not a breaking change (every
  config valid under the old enum stays valid). To make values above 21 actually buildable, not
  just schema-accepted, `NPDevRuntimeHost`'s own bundled Gradle wrapper (copied into every
  generated app) was bumped 8.5 → 9.5.1, `foojay-resolver-convention` 0.8.0 → 1.0.0,
  `org.springframework.boot` plugin 3.3.2 → 3.5.16 (its `bootJar` task called a Copy API method
  Gradle 9 changed — the only thing in the whole corpus that caught this was the generator's own
  packaged-app boot-proof tests, which are also the only tests that run `bootJar` at all), ArchUnit
  1.3.0 → 1.4.2, and Mockito 5.11.0 → 5.23.0 (with an explicit Byte Buddy 1.17.7 override) — all
  three of the last found live to fail against Java 25's class file format otherwise. Platform
  modules (dsl/kernel/generator/adapters/runtimehost source) are unaffected; only the template
  shipped inside generated apps moved. See `BREAKING.md` and ledger `REG-143`.

### Chore
- Stale branches cleared, `docs/MANAGER.md` made discoverable from the README (FINAL_PLAN.md
  F1+F2).
- REG139_PLAN.md I2/I3: delete stale branch, sync remaining Java 17+ fixtures.

## [Unreleased]

### Added
- LNCH-12: scheduled/background flow execution (`flow.schedule`, cron-triggered, ControlPanel
  visibility at `/api/admin/cron-schedules`).
- LNCH-16: optimistic locking on concept writes (`row_version`, compare-and-swap through
  `ConceptGateway`, 409-with-current-record conflict contract). See `docs/reference/OPTIMISTIC_LOCKING.md`.
- LNCH-17: flow transaction-boundary contract — `onFailure` compensation steps (saga pattern,
  crash-resumable), documented atomicity/durability guarantees. See
  `docs/architecture/FLOW_TRANSACTION_CONTRACT.md`.
- LNCH-10 slice 1: CSV export (`GET /api/concepts/{concept}/export.csv`, streaming/paged, an
  Export CSV button on every generated grid). See `docs/reference/CSV_EXPORT.md`.
- LNCH-21: `docs/architecture/APP_UPGRADE_CONTRACT.md` — the platform-owned/app-owned boundary in
  a generated FinalApp, written down precisely for the first time.
- LNCH-22: `docs/DSL_REFERENCE.md` (generated from the schema —
  `scripts/docs/generate_dsl_reference.py`), `docs/TUTORIAL_FIRST_APP.md` (golden-path tutorial,
  gate-tested by reusing the `simple-contact-intake` sample).
- LNCH-23: `LICENSE` (Apache-2.0, copyright Marcelo Giazzon), `docs/RELEASE_PROCESS.md`,
  `docs/adr/ADR-0007-distribution-model.md`, `scripts/quality/run-release-checklist-gate.ps1`.
  License, telemetry ("none at launch"), and distribution model ("self-hosted, source-first")
  decisions ratified 2026-07-17; trademark clearance remains open (a preliminary search found a
  real naming collision — see ADR-0007 §5 — a professional search is still required).
- `docs/adr/ADR-0006-authoring-path.md`: the AI-first, editor-secondary authoring-path decision
  (LNCH-18); `docs/NON_AUTHOR_FRICTION_LOG_TEMPLATE.md` for recording the DoD's still-outstanding
  non-author test run.
- `docs/CONFIGURATION.md` (LNCH-22): full reference for every `npdev.*`/`NPDEV_*` startup-config
  property, matching the exact anchor IDs `StartupValidator`'s error messages already link to.
- `scripts/quality/run-app-upgrade-contract-gate.ps1` (LNCH-21): proves live that a `web/`
  customization survives two full regenerations byte-identical — the gap
  `docs/architecture/APP_UPGRADE_CONTRACT.md` had flagged as "true by construction but not yet
  proven by a test."

### Behavior changes
- **`npdev doctor`'s `git-present` check is a `warn`, not a `fail`.** Its detail named two reasons
  that were both false -- git is not needed to clone NPDev on the Manager's path (versions arrive as
  a zip) and, since the fix above, is not needed by `npdev init` either. A hard failure took the
  Manager's whole Ready screen red on a machine that works perfectly well, and
  `_scrapforai_check`'s own docstring already wrote the rule: a doctor that goes red over an
  optional tool teaches people to ignore red. The check id is unchanged.
- **`engine-support.yml` and `storage-dialect-conformance.yml` now run on `push: tags`.**
  `release_candidate.py` requires a run of both AT THE TAG'S EXACT SHA, and nothing produced one --
  the first is dispatch-only and the second's `paths:` filter matched nothing on a typical release
  commit. The RC gate refused twice on 2026-08-10, ~20 minutes each time. (GitHub does not apply
  path filters to tag pushes, which is why the conformance one is a `tags:` key inside the existing
  `push:` entry rather than a second trigger.) **Unverified until a real tag fires it.**
- **LNCH-1 T1 (data-integrity fix): an upgrade no longer relaxes `NOT NULL` on the platform-managed
  columns, and repairs databases an earlier build already loosened.** Every fingerprint-changing boot
  used to strip `NOT NULL` from `version`, `row_version` and `tenant_id`, because they appear in the
  manifest's full column set but never in its model-derived *required* set (only `id` escaped, via the
  live primary-key read). A `NULL` `tenant_id` makes a row invisible to every tenant-scoped read; a
  `NULL` `row_version` silently defeats LNCH-16's compare-and-swap. On the next boot, any already-
  loosened platform column is backfilled (`NULL`s only — real values are never overwritten) to its
  platform default and restored to `NOT NULL`, recorded as a `TIGHTEN_PLATFORM_COLUMNS` row in
  `npdev_schema_history`. **Operators upgrading an app built before this change will see one repair
  pass on the next boot.** Verified live on real Postgres with real data.
- **LNCH-1 T2: the platform `version` column is now additive-eligible, so a table missing it
  self-heals.** Previously `version` was declared by the manifest but never added by any migration, so
  a table missing it produced an `UNKNOWN` delta item — which, since closeout C1, refuses the boot
  unless an itemized token authorizing a **whole-schema wipe** is supplied. This removed the most
  likely real-world trigger of a total data-loss event. `version` and `row_version` remain separate
  columns. A consequence worth knowing: the missing-column route to the whole-schema recreation is now
  unreachable from any diff the current generator can emit; the path is deliberately kept, and kept
  token-gated, as a safety net for hand-modified schemas, pre-T2 manifests, and future non-additive
  column kinds — see `docs/SCHEMA_EVOLUTION.md`.
- `BuildInfoEmitter`'s `npdev.generator.version` (embedded in every generated app's
  `npdev-build-info.properties`) now reads `git describe --tags --always` against the platform's
  real release tags instead of a hardcoded `"0.1.0"` literal, falling back to that literal only
  when there's no git checkout to read.
- `JdbcBusinessConceptStore` now joins the ambient Spring transaction (via `DataSourceUtils`)
  instead of always opening an independent auto-committing connection. A generated concept's
  `create`/`update` service method (`@Transactional`) now genuinely rolls back its kernel-gateway
  write if a later step in the same method (e.g. the JPA persist) fails — previously these were
  two independent writes. No model-schema change; a pure correctness fix, but noted here since it
  changes observable rollback behavior under failure.

### Changed
- **LNCH-1 closeout C1 (operator-visible): the whole-schema recreation now requires an itemized
  acknowledgment token.** Hardening X4.4 established that destroying *one* table's data needs a
  token; the whole-schema recreation destroys *every* table's data and was still reachable on the
  deprecated blanket `destructiveAllowed` flag alone — the most destructive operation in the system
  had the weakest authorization requirement. On a blanket-posture app, a boot whose delta report
  cannot be executed item by item (any `UNKNOWN` item) now **refuses** instead of dropping and
  recreating every table. The refusal names the unexplainable item(s), states plainly that
  proceeding would destroy all data in every table, and prints the token that authorizes it.
  **The blanket flag now authorizes exactly two things: a surgical `DROP_COLUMN` and a
  `NARROW_TYPE`.** Dev/CI loops that relied on "recreate everything on boot" should delete the
  database between runs or use a `freshdb`-style definition — note that `RecreateOnAppStart` is
  inert and is *not* an escape hatch. See `docs/SCHEMA_EVOLUTION.md`.
- LNCH-1 closeout C3: `AppGen\apps\simple-user-registry-h2local` now ships the **recommended**
  `schemaLifecycle` posture (`KeepExistingIfCompatible` + `allowDestructiveRecreate: false`) as a
  copyable worked example, and the authoring docs no longer pair `allowDestructiveRecreate: false`
  with the `I_UNDERSTAND_TABLE_DATA_WILL_BE_DELETED` confirmation string. Existing definitions are
  untouched; most still carry the deprecated blanket posture for backward compatibility, which the
  docs now state explicitly is history rather than a recommendation.
- LNCH-1 remediation R1: a destructive-acknowledgment token for a dropped concept (`DROP_TABLE`) no
  longer includes the live row count in its hash, so a token copied from `-PlanOnly` now matches the
  executor's boot-time token on the first attempt (previously impossible for a concept drop). Both
  token producers also route every SQL-type string through one shared normalizer
  (`SqlTypeNormalization`). **Any acknowledgment token computed before this change no longer
  matches** — re-run `Build-NpdevApp.ps1 -PlanOnly` to obtain the new token (a stale token simply
  refuses the boot with the new expected token printed; no data is at risk).

### Fixed
- **The first ten minutes on a machine that is not the author's** (close-the-gaps-2026-08-10 Wave 1).
  Four defects that only appear on a machine the NPDev Manager was actually built for, none of them
  reachable by any gate this repo runs, because every gate runs where git and java are already
  installed:
  - `npdev init` called `git init` unconditionally. With git absent that raised `FileNotFoundError`,
    which `main()` catches nowhere, so the Manager's **Create** button died with a raw traceback at
    the very first step -- on the machine `docs/MANAGER.md` advertised as needing "no git". The
    scaffold never needed git: every file is written before that call. It now reports the missing
    repository, corrects the README sentence that promised one, returns `gitInitialised: false`, and
    exits 0.
  - The built app was launched with a bare `["java", "-jar", ...]` at both launch sites
    (`npdev run app`'s BOOT phase and `dev_loop.boot`). The Manager passes its private JDK as
    `JAVA_HOME` only -- Gradle honours that and a bare `java` cannot see it -- so generate and build
    succeeded on the private JDK and the app then failed to start with the working JDK sitting
    unused, printing nothing, because `Popen`'s exception went to a stream the Manager discards. Both
    sites now use `java_launcher()` (JAVA_HOME first, PATH second, `None` reported as a diagnostic),
    and `npdev doctor` consumes the same function so the two can no longer disagree.
  - `HANDOVER.md` 2.6, the step it calls "the whole product", asked the tester to rename a field --
    the one edit NPDev refuses by design (`ACCEPTED_BOUNDARIES.md` B1). It now asks for an added
    field and says why.
  - `docs/INSTALL_ON_A_NEW_MACHINE.md`, `docs/MANAGER.md` and `HANDOVER.md` described controls that
    do not exist ("Doctor" button, an Apps-screen Run button, **Refresh**/**Start** where the window
    says **Re-check**/**▶ Run**), invented six database labels none of which is in the picker, and
    promised a Reset refusal that `STOR-15` shows nothing can switch on. Re-audited row by row
    against the running UI and CLI; the code half is filed, not papered over.
- **LNCH-1 T5 (`GATE-OBS-1`): `run-runtimehost-gate.ps1`'s exit code is truthful again.** The gate had
  exited 1 for four consecutive rounds on one check, `runtime-surface-reports-current`, while
  contradicting itself: it already passed `-PendingOk` to the surface-evidence step for exactly those
  package-namespace convergence checks, then re-read the same reports in the observability step and
  failed on them. The six named checks are now accepted as advisory, and **only** when they are the
  sole failures — any other failing sub-check still fails the gate, naming it. Build-time allowlist
  enforcement was and remains green. The governance realignment is tracked separately as
  `GATE-OBS-1a`, still open and needing an owner.
- **LNCH-1 T9.2: `LNCH-1-B8`'s refusal printed an unactionable remedy.** It advised rebuilding
  "without `-PlanOnly`", but the guard covers `-PlanOnly` *or* `-Upgrade`, so an `-Upgrade` run hit the
  same refusal and the instruction looped. It now says to rebuild with neither flag.
- **LNCH-1 closeout C4 (`LNCH-1-B8`): a failed `-Upgrade` no longer degrades the next migration plan
  into a false "fresh install".** `Build-NpdevApp.ps1` reads the previous compiled model from the
  output root and then wipes that root; a run that failed after the wipe destroyed the model, so the
  next `-PlanOnly` reported *"Fresh install — no previous compiled model to diff against"* **and
  exited 0** — the documented script-friendly "safe to proceed" gate signal — for a database that
  may need a destructive change. The snapshot is now preserved durably beside the plan echoes (keyed
  by definition folder, since several shipped definitions deliberately share one `scenario.name`),
  and a plan requested with no model available but durable evidence of a prior deployment now
  **refuses** with an actionable message instead of degrading. `GeneratorMain` gained an opt-in
  `--requirePreviousCompiledModel` so other callers get the same protection.
- LNCH-1 closeout C7.1: the RuntimeHost gate's `health-indicator-coverage` check no longer fails
  vacuously. It scraped `NpdevRuntimeModeConfig` for `public <Type> postgresXxx(...)` beans, a
  naming convention that no longer exists (they are `jdbcXxx`), so it matched zero surfaces and
  reported a coverage gap that did not exist.
- **LNCH-1 X1 (critical, operator-visible): a destructive upgrade authorized only by the blanket
  `destructiveAllowed` flag no longer wipes the whole schema.** On any app with
  `allowDestructiveRecreate: true` — the shape of every shipped app definition — a destructive change
  fell through to the whole-schema recreation even when every item in the delta report was
  surgically executable. Because that path drops the tables the *new* manifest lists, dropping a
  concept destroyed the data of every concept still in the model, while the orphaned table the
  upgrade was meant to remove survived. Authorization and execution strategy are now separate:
  authorization decides *whether* destruction may happen, and the report's content decides *how* —
  the whole-schema recreation is reached only when the report contains an `UNKNOWN` item. This is
  strictly less destructive in every case it changes. The blanket-flag deprecation warning now also
  itemizes exactly what is about to be executed.
- LNCH-1 X2: a table dropped from the model but still physically present (it survived a wipe, a
  crash, or a refusal) no longer loses its NPDev-ownership record on the next boot, which had made
  it permanently un-droppable. `ownedBusinessTables` is now `(previous ∪ current manifest) ∩ live
  tables`.
- LNCH-1 X3: the schema-ahead-of-build detector (which refuses an old jar redeployed against a
  database a newer build already migrated) was effectively inert — it skipped every
  additive-eligible column, which in a real manifest is nearly every column. It now also fires when
  a missing column coincides with an *unexplained extra* live column on the same table (the
  signature of a rename by a newer build), and reports an entirely-absent table once instead of
  column by column. A pure column *drop* by a newer build remains undetectable — documented in
  `docs/SCHEMA_EVOLUTION.md#refusals-and-rollback`.
- LNCH-1-B7: dropping a concept now actually drops its table. Previously `-PlanOnly` previewed a
  `DROP_TABLE` and demanded an acknowledgment token, but at boot `classify()` only enumerated
  manifest-declared tables, so the orphaned table was invisible, the boot classified as
  safe-additive, and the destructive path (and the token check) was never entered -- the table and
  its rows survived and the acknowledgment was never consumed. The executor now records the business
  tables it owns (`npdev_schema_metadata.ownedBusinessTables`) on every successful boot and acts only
  on orphans it can prove it created, so a table an operator added by hand in the same schema is
  never dropped. Apps with no ownership recorded yet keep the previous behaviour for one boot.
- LNCH-1 remediation R8: `scripts/quality/run-stateful-additive-migrations-check.ps1` could not parse
  at all (its `findings` strings used `\"`, which PowerShell does not treat as an escape) — so the gate
  had been dead, not merely reporting oddities. Fixed, and its permanently-red steps were either
  replaced with a real assertion (`old-migration-authority-quarantined`, a direct filesystem invariant)
  or removed with recorded rationale (a task that does not exist; CLI flags that subcommand never had).
- LNCH-1 remediation R6: a stale `renamedFrom` marker (one naming a column the previous model no
  longer has — the "renamed twice, marker never updated" case, which silently degrades a rename into
  a destructive drop) now surfaces a `WARNINGS` block in the `-PlanOnly` preview and a WARN log line
  at boot. Documented the marker lifecycle in docs/SCHEMA_EVOLUTION.md.
- LNCH-1 remediation R4: every mutating migration pass (table rename, column rename, NOT NULL
  relaxation, type widening, required-field backfill) now writes a write-before-execute
  `npdev_schema_history` row carrying the step name and per-item detail; the unique-precheck refusal
  row now records the violating tuples with a `UNIQUE_PRECHECK` label instead of an empty row.
- LNCH-1 remediation R3: a schema-ahead-of-build detector now refuses a fingerprint-MATCH boot whose
  live database is missing a core (non-additive) column this build requires — the "redeployed the
  old jar against a database a newer build already migrated" case, which previously booted deceptively
  clean and then failed at runtime. Recovery is roll-forward or restore (see docs/SCHEMA_EVOLUTION.md).
- LNCH-1 remediation R2: a new required field added in the SAME upgrade as an acknowledged
  destructive item is now backfilled-and-tightened (or refused if it has no literal default) on that
  boot, instead of being silently skipped and left permanently nullable. Enforcement moved to a
  single `afterMigrate` call site every boot path crosses.
- A pre-existing gap where a `forEach` flow step's loop body (`collectionRef`/`itemKey`/
  `loopSteps`/`maxLoopIterations`) was silently dropped by the canonical-JSON round trip every
  generated app's `NPDevModelProvider` reads at boot — found while wiring LNCH-17's
  `onFailureSteps` through the same writer/reader.
- CSV export header repeating the `id` column twice (LNCH-10 slice 1).

## Prior history

Everything before this entry was tracked via `docs/LAUNCH_READINESS_GAPS.md`,
`docs/OPEN_GAPS_AND_ROADMAP.md`, and `docs/MATURITY_CLOSURE_LEDGER.md` rather than a changelog —
this file starts fresh from here forward. See those documents for the full history of Waves 1-4
and the platform's earlier maturity checkpoints.
