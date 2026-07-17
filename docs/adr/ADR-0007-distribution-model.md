# ADR-0007 Distribution Model, License, and Naming (LNCH-23)

## Status

**Partially ratified — 2026-07-17.** Drafted per `docs/LAUNCH_READINESS_GAPS.md` LNCH-23; decisions
1-4 below were reviewed and confirmed by the project owner on 2026-07-17. Decision 5 (trademark)
remains genuinely open — it requires a real professional search this session cannot perform, not
just a sign-off. See each decision's own status line below.

## Context

LNCH-23 bundles five unglamorous but launch-blocking decisions: license, distribution model,
telemetry/crash reporting, versioned release process, and a naming/trademark check. None of these
are code problems; all of them block anyone outside this session from legally or practically
evaluating the platform.

## Decisions

### 1. License — Apache-2.0 (ratified 2026-07-17)

A `LICENSE` file (Apache-2.0 full text) has been added at the repo root, copyright held by
Marcelo Giazzon (individual). Apache-2.0 is a common default for a platform aiming at broad
adoption and outside contribution: permissive, includes an explicit patent grant (relevant for a
code-generation platform, where "did the generator's output infringe something" is a real
question worth a clear answer), and is well understood by enterprises evaluating whether they can
adopt a dependency.

### 2. Distribution model — self-hosted, source-first (ratified 2026-07-17)

Given today's architecture — a generator that emits a Spring Boot FinalApp the operator then
builds/deploys themselves (LNCH-7's Docker-Compose-first posture), no multi-tenant SaaS control
plane, no installer — **self-hosted, source-first** is the only distribution model that matches
what actually exists. Concretely:

- Distribution unit: this monorepo (or eventually a released subset of it — see LNCH-20's
  cross-platform cleanup and the packaging work ADR-0006 flags for the MCP/CLI authoring loop).
- No SaaS control plane exists or is proposed here; standing one up is a substantial, separate
  future decision, not implied by this ADR.
- An "installer" in the near term means: clone/download a tagged release, run the documented
  build (`docs/GETTING_STARTED.md`), generate an app, deploy via the LNCH-7 Docker Compose
  stack. A packaged, versioned MCP/CLI/knowledge-corpus distribution (ADR-0006's P0 slice) is
  the nearest-term concrete "installable unit" on the roadmap, not a platform installer.
- This determines LNCH-7's final shape (already built to this posture — Postgres-first Docker
  Compose, no SaaS assumptions baked in) and the support surface (issue tracker / docs, not a
  hosted support desk).

Whether a future SaaS/managed offering is ever pursued is explicitly out of scope for this
decision — it doesn't foreclose that, it just states the launch-day model.

### 3. Telemetry / crash reporting — none at launch (ratified 2026-07-17)

**Decision: ship without telemetry or crash reporting for the initial release.** Reasoning:

- No consent-first telemetry pipeline exists today, and building one properly (opt-in, clearly
  disclosed, a real privacy posture) is its own scoped feature, not a launch-checklist afterthought.
- The platform already has a strong internal observability story for a *deployed* app
  (LNCH-8: correlation IDs, flow-outcome metrics, gated actuator) — what's explicitly absent is
  *platform-usage* telemetry (how many people generated an app, which features got used) sent
  back to the maintainers.
- Consequence, stated honestly: launch feedback is anecdotal (issues, direct reports) until a
  consent-first telemetry feature is deliberately built as its own increment. This is a real cost,
  accepted deliberately rather than silently.
- If/when telemetry is built: opt-in (not opt-out), disclosed in `docs/`, and covering aggregate
  usage signals only — never model content or generated-app data — is the baseline any future
  proposal should start from.

### 4. Versioned release process — see `docs/RELEASE_PROCESS.md`

Formalized as its own document rather than folded into this ADR, since it's a process description
that will itself evolve. Summary: tag → gate → changelog → artifact, building on
`run-beta-release-gate.ps1` (the existing seed) and the beta0 tag-immutability precedent already
written into the maturity ledger.

### 5. Naming/trademark check — "NPDev"

**Not resolved by this ADR — and a preliminary search found a real, concrete naming collision
worth your attention before this goes further.**

A real trademark clearance requires a professional search (USPTO/equivalent trademark databases,
common-law usage search) that this session cannot perform. What this ADR does instead: records
the check as an explicit, tracked open item, plus reports what a preliminary web search (2026-07-17)
actually turned up — not a substitute for real clearance, but more than "no obvious conflict":

- **"NP DEV Soluções em T.I."** — a Brazilian IT services/software company, live at
  `www.npdev.com.br`, offering IT maintenance/support, network consulting, infrastructure
  development, and web systems. Same "NP DEV"/"NPDev" name, same broad industry (software/IT
  services), different country/market. This is not a confirmed registered-trademark conflict —
  it's a real, currently-operating business using a near-identical name in an adjacent space,
  which is exactly the kind of thing a professional search would need to assess for actual legal
  risk (registered mark? common-law rights in Brazil vs. elsewhere? likelihood-of-confusion
  analysis for a different specific product category?).
- No results surfaced any conflicting product specifically in the "low-code application
  development platform" category (Mendix/Appian/OutSystems/etc. — none use this name), which is
  the more specific market this platform would actually compete in.

**Before any public launch announcement, this needs an actual trademark search, and the
`npdev.com.br` finding specifically should be reviewed with that search** — flagged here as a
release-blocking checklist item in `docs/RELEASE_PROCESS.md`, not resolved by this preliminary
pass.

## Consequences

- `docs/RELEASE_PROCESS.md` and a lightweight release-checklist gate
  (`scripts/quality/run-release-checklist-gate.ps1`) enforce the mechanical parts of this ADR
  (LICENSE present, CHANGELOG entry present, HEAD tagged) — they cannot enforce trademark
  clearance, which remains a human step this ADR surfaces rather than automates away.
- Decisions 1-4 are ratified as of 2026-07-17 and should be treated as the platform's actual
  position going forward, not a draft. Decision 5 (trademark) is genuinely still open — not a
  formality, a real search has not happened — and remains a release-blocking checklist item.
