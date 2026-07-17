# Generated-app upgrade contract (LNCH-21)

When the platform ships vNext, how does an existing FinalApp adopt it? This document writes down
the boundary that was previously informal, based on how the pipeline actually behaves today (not
how an earlier draft of this doc assumed it behaved — see "Correction" below).

## The three ownership zones

| Zone | Lives where | Regeneration behavior |
|---|---|---|
| **Platform-owned, hash-verified** | `<FinalApp>/npdev-generated/` | Fully rewritten every regeneration; hash-checked at every boot (`StrictExecutionValidator`) — a mismatch is a **hard boot failure** in governed mode, not a warning. |
| **Platform-owned, unverified** | Everything else `FinalAppAssembler` copies from the RuntimeHost template (the app's Spring Boot Java sources, build files, `application-ai-beta-local.yml`, etc.) | Fully rewritten every regeneration (`deleteBeforeMount` wipes the entire FinalApp root first). No hash check — nothing here is meant to be hand-edited either, it is just not enforced the way `npdev-generated/` is. |
| **App-owned** | `apps/<App>/web/`, `apps/<App>/definition/{pages.json,menu.json}` — **outside the FinalApp output tree entirely**, in the AppGen app-definition source (layer 2 of this repo's [[source_of_truth_layers]]) | Never touched by generation. Re-mounted (copied in fresh) into the newly-regenerated FinalApp's `src/main/resources/static/` (for `web/`) and a companion seed file (for the menu/pages) on every build — see `Build-NpdevApp.ps1` steps 4b/4c. |

## Correction: this is re-mount, not in-place preservation

An earlier assumption (baked into this LNCH item's original "why") was that `web/` lives *inside*
the FinalApp output and survives regeneration by being skipped/preserved in place. That is not
what happens. `FinalAppAssembler.assemble()` (via `Build-NpdevApp.ps1`'s `deleteBeforeMount`)
**deletes and fully recreates the entire FinalApp root on every single build** — there is no
skip-if-exists logic anywhere in the assembler. What actually makes app customization survive a
regeneration is that its *source* never lived inside the wiped tree to begin with: `apps/<App>/
web/` is a separate directory the build script copies **from**, after the wipe, not a directory
inside the output the build script preserves.

This is a safer design than in-place preservation would have been (no risk of a stale copy
silently diverging from its source, no merge conflicts to resolve), and it means the DoD below is
already true by construction for any customization authored the intended way — but it was worth
writing down precisely, since a hand-edit made directly inside a built `App/` folder (rather than
in the AppGen app definition) is destroyed with no warning on the next `Rebuild-And-Restage.ps1`
run.

**Never hand-edit inside `<FinalApp>/npdev-generated/`** — every file there is walked, SHA-256'd
per-file plus a combined tree hash, and written to `npdev-generated/src/main/resources/npdev/
support/generated-folder.signature.properties` at generation time
(`GeneratedFolderSignatureEmitter`); `StrictExecutionValidator` recomputes and diffs that same
hash at every boot in governed mode. A single edited byte anywhere in that tree fails startup with
a `StrictExecutionViolationException`, not a soft warning — this is the mechanism behind the
`mapa-armazem.html`-class "signature mismatch" incidents already logged in
`knowledge/cards/hash-guarded-npdev-generated.json`.

## Platform version pairing

Every generated FinalApp already carries `npdev.generator.version` in `npdev-build-info.properties`
(`BuildInfoEmitter`, emitted at final-app-assembly time) plus the exact platform commit/branch
that generated it (`npdev.commit`/`npdev.generator.tag`). This is real, existing provenance — not
something this document is introducing — surfaced today via `GeneratedBuildInfoLogger` (a boot
log line) and `NpdevBuildInfoInfoContributor` (`/actuator/info`).

**What does not exist yet**: nothing reads that version to *gate* anything. `StrictExecutionValidator`'s
hash check is purely content-based (did the bytes change), not version-aware (is this app on an
older platform version than what would regenerate it). There is currently no code path that says
"this FinalApp was built on platform 0.1.0 and the installed generator is 0.3.0, so warn/block/
require an explicit upgrade step."

## The compatibility rule (policy, not yet code-enforced)

Adopted as platform policy, to be enforced by tooling in a future increment rather than today:

- **Model-schema changes** (a new/changed `$defs` shape in `model.schema.json`) require a
  `dslVersion`/`schemaVersion` bump — already the case; `$defs` additions this session
  (`conceptAccess`, `flowSchedule`, `onFailure`) were additive to the same `1.0.0` schema version,
  which is correct for additive, backward-compatible fields (an old model.json with none of these
  fields still validates and compiles identically). A **breaking** schema change (removing a
  field, changing a type, tightening a previously-optional shape to required) would need a
  `dslVersion` bump, not just an additive `$defs` entry.
- **Runtime *behavior* changes** (the platform doing something differently for the same model —
  e.g. a bug fix that changes generated SQL, a stricter validator, a new default) are not
  currently versioned at all. Adopted rule: a behavior change that could alter a running app's
  observable output requires either a `dslVersion` bump (if the model shape itself needs to
  change to opt in) or an explicit entry in a future `RELEASE_NOTES.md`/changelog (LNCH-23) under
  a "behavior changes" heading, so an upgrader can audit what changed between the version their
  app was generated on and the version they're upgrading to.
- **The generator major version** (currently hardcoded `"0.1.0"`, `BuildInfoEmitter.
  GENERATOR_VERSION`) should track the platform's own release tags (LNCH-23's release process)
  rather than staying a hardcoded literal — flagged as a follow-up, not fixed in this doc's own
  commit, since it touches the release-tagging mechanism LNCH-23 is defining.

## `RegenerationPolicy.CUSTOM_STUBS` — a dormant mechanism worth knowing about

`RegenerationPolicy` (`NPDevGenerator/generator/.../strategy/RegenerationPolicy.java`) already has
a `CUSTOM_STUBS` mode whose `canOverwrite()` returns `false` — a real, already-built
never-overwrite-an-existing-file policy that `GeneratedSourceWriter.writeRelative()` already
honors. **No current production call site uses it** (every real emitter constructs the default
always-overwrite `Mode.GENERATED`). If a future increment wants true in-place-preserved
platform-owned-but-author-customizable files (as opposed to today's app-owned-external-source
re-mount model), this is the existing seam to wire up — not a new mechanism to invent.

## DoD

> A FinalApp generated on version N upgrades to N+1 with local `web/` customizations intact,
> proven in the release gate.

**True today, by construction**, for the intended customization path (`apps/<App>/web/`,
`apps/<App>/definition/*.json`) — since that source never lives inside the wiped-and-regenerated
output tree, any regeneration (version N to N+1, or same-version to same-version) re-mounts it
fresh. **Not yet proven by an automated test or wired into a release gate** — no existing test
asserts this end to end (`RegenerationEvolutionSafetyTest` proves determinism of the generator's
own compiled artifacts, not preservation of app-owned files across a full `Build-NpdevApp.ps1`
run). Flagged as the concrete remaining increment: a gate test that runs `Build-NpdevApp.ps1`
twice against the same app definition with a `web/` customization present, asserting the
customization file is byte-identical in the output both times and the app still boots.
