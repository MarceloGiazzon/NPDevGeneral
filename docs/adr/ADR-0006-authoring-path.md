# ADR-0006 Authoring Path: AI-First, Editor-Secondary

## Status

Proposed — 2026-07-17. Drafted per `docs/LAUNCH_READINESS_GAPS.md` LNCH-18. Ratification (the
"one non-author completes the DoD run") is outstanding — see DoD section below.

## Context

"Low-code platform" is a claim about *who can author an app*, not just about how much
hand-written code a generated app avoids. NPDev has never actually tested that claim end to end
for a human. Every real app built on this platform so far — Claude Support Desk, Pigmentampa,
WordLab, AuxScreen, WmsOffice, every LNCH-verification scratch sample this session — was authored
as JSON, by an AI, through the MCP `npdev_search_examples`/`npdev_search_fix`/`npdev_check_support`
validate→fix→generate loop (`NPDevMcp`, `NPDevCli`, the RAG/knowledge-card corpus under
`knowledge/`). The React editor (`NPDevEditor/ui-react`, 30+ panels, Playwright E2E) has real
coverage for individual panels but has never carried one full app from empty to a generated,
running FinalApp.

Two coherent positions exist. Not choosing is the failure mode this ADR exists to close:

- **(a) Editor-complete.** The editor authors 100% of what the schema allows: every field type,
  widget, bond, invariant, flow step, lifecycle transition, access rule, schedule — with
  validation errors phrased for a non-engineer, undo, and live preview. This is a large,
  open-ended UI investment, and the schema keeps growing (bonds truth-edges, aggregates,
  documents are all still active fronts) — editor-complete risks chasing schema parity forever
  rather than ever actually finishing.
- **(b) AI-first, editor-secondary.** The AI authoring loop — conversational app-building
  grounded by the MCP tools, the RAG corpus, the knowledge-card corpus, and schema-constrained
  repair — *is* the primary authoring UX. The editor is repositioned as the inspection/
  refinement surface (browse a compiled model, tweak a panel, verify what the AI produced), not
  the from-scratch authoring path.

## Decision

**(b) AI-first, editor-secondary.** This is the doc's own recommendation and — decisively — it is
what has already been proven, blind, on this exact platform: every sample app this session and
prior sessions was built through this loop, by an AI session with no more privileged access to
the codebase than an external user's AI assistant would have via the MCP server.

It is also more differentiated. A "does the editor have as many panels as GeneXus/OutSystems"
race is a race NPDev starts years behind in; a genuinely good AI-native authoring loop, verified
end to end rather than merely plausible, is not a race that has been run by the established
low-code incumbents yet.

### What this means concretely

- **The MCP/CLI/knowledge-corpus loop is the P0 authoring surface**, not a developer tool
  bolted onto a "real" editor. It needs to be packaged, versioned, and installable the same way
  any product surface would be — not left as "clone the monorepo and run a Python script."
- **The editor's job changes, not its value.** It remains genuinely useful for inspecting a
  compiled model, understanding what the AI built, and making small hand-authored refinements —
  but authoring a *new* app from nothing is not asked of it, and its 30+ panels are not required
  to reach schema parity to call this platform's authoring story complete.
- **Unsupervised generation without verification is how low-code tools earn a bad reputation.**
  The P0 slice pairs the authoring loop with automatic, real verification of what got built —
  `verify-in-browser`-style checks, not just "the model validated against the schema." The
  `npdev_check_support` capability-gate tool (already built, see `docs/ai/
  AI_KNOWLEDGE_LOOP_AND_TOOLING_PLAN.md`) is exactly the right seed for "tell the author honestly
  what this platform can and cannot yet build," rather than silently generating something broken.

### P0 slice (what ships to make this real, not just declared)

1. **Packaging** — `NPDevMcp` + `NPDevCli` + the knowledge-corpus build (`scripts/ai/
   build_knowledge.py`) become an installable, versioned unit (a packaged Python distribution or
   equivalent), not "clone this monorepo and run scripts from source." Out of scope for this ADR
   commit itself — tracked as the concrete next increment once ratified (see Follow-ups).
2. **A guided "describe your app" front door** — the first-touch experience for someone who has
   never used NPDev: a documented, minimal prompt/workflow that gets a first working app out of
   the MCP loop without them first having to learn the DSL. LNCH-22's tutorial (golden-path,
   gate-tested) is the natural home for this once written.
3. **Automatic verification of what the AI built** — every sample-app verification this session
   (LNCH-12/16/17/10 and everything before) already does exactly this: regenerate, build, boot,
   curl/verify real behavior, not just "did it compile." Formalizing this as a *user-facing*
   step (not just an internal session discipline) is the remaining gap.

### Editor's future scope, explicitly bounded

The editor is not frozen or deprecated. Panel work continues where it earns its keep: inspecting
a model an AI produced, small targeted edits, and any workflow where a human genuinely wants
direct manipulation over a conversational loop (e.g., visual layout of a Panel/Aggregate
region — see ADR-0004/ADR-0005). What changes is that **editor panel completeness is no longer
the metric this platform's authoring story is judged by.**

## DoD (per LNCH-18, not yet satisfied by this ADR alone)

> One non-author (someone who is not Marcelo and not this assistant's session) takes a new app
> from description to running, verified FinalApp through the chosen path, and the friction log
> from that run becomes the next roadmap increment.

This ADR records the decision and its rationale; it does **not** by itself satisfy the DoD, which
requires a real external human running the actual loop. That run — and the packaging work in P0
slice item 1 above, which the run likely depends on to be practical for someone without repo
access — is the concrete next step once this ADR is ratified. Recorded here as an explicit open
item rather than silently marked done.

**When someone runs this test**: use `docs/NON_AUTHOR_FRICTION_LOG_TEMPLATE.md` to structure and
record the session — it's the concrete "how" behind this DoD's "friction log" requirement.

## Consequences

- Every future authoring-experience investment (docs, tooling, error messages — see LNCH-22)
  should default to "does this help the AI-loop path" over "does this add an editor panel,"
  unless a specific editor gap is blocking something the AI loop cannot do at all.
- The editor's Playwright E2E suite and panel work remain valuable but are explicitly not the
  measure of "can a stranger build an app here" going forward — that measure is the MCP loop's
  own verified DoD above.
- LNCH-22's golden-path tutorial and DSL reference should be written *for* the AI-loop path
  first (what does a human need to know to prompt an AI effectively, and to read what it
  produced) rather than as an editor walkthrough.
