# ADR-0009 External AI Delegation — Egress, Redaction, and What an AI Verdict May and May Not Close

## Status

**APPROVED WITH CONDITIONS — ratified 2026-07-26, D3 resolved 2026-07-27** (see decision block
below, which is authoritative). Originally drafted per `PLAN_EXTERNAL_AI_REVIEW_2026-07-26.md`,
promoted to `docs\archive/programme-history/EXTERNAL_AI_DELEGATION_PLAN.md` with `STATUS: ACTIVE` on owner approval — that
promotion is complete, not pending. D1, D2, D3, D6, D7 are answered (D1/D2/D6/D7 on 2026-07-26, D3
on 2026-07-27 — see the decision table); **D4 and D5 remain open**. P0-P9 are built and verified,
including real vendor calls under D3's authorization (see `archive/programme-history/EXTERNAL_AI_DELEGATION_PLAN.md` and
`archive/programme-history/REG48_50_CLOSURE_PLAN.md` for the full run history).

## Context

Several of this project's own documents say, in effect, "a person outside the project must do this
step": an independent adversarial security review (`archive/programme-history/EXTERNAL_SECURITY_REVIEW_BRIEF.md`), REG-17's
third-party reproduction, the cold-start authoring test, an audit sign-off, and — the insight that
promotes this from a maintainer script to a platform feature — the runtime's own `MANUAL_REVIEW`
schema-impact items and the `ACCEPTED_BOUNDARIES.md` family of "stop and ask a human" boundaries in
**every app anyone builds on NPDev**. Both are the same shape: a governed delegation of a
human-judgment step to an external AI —

> redacted pack in → external model → structured verdict out → filed, never auto-applied →
> provenance recorded

NPDev's own review becomes instance #1 of its own feature rather than a one-off chore whose
machinery is thrown away once the review is done.

## The rule, stated plainly

**An external AI model may render a verdict on a redacted pack. It never gets filesystem, repo,
shell, or network access to the host, and its verdict is never recorded as a human decision.**

Concretely, mirroring `ConceptGateway.authorizeWrite()`'s fail-closed default: the kernel port that
mediates this (`ExternalAiCapabilityContract`, sibling of `PersistenceCapabilityContract` in
`CapabilityContractCatalog`) **denies egress and throws by default**. Nothing leaves any app until
its author opts in, per app, per mission, per vendor — the model-surface field
`externalAi.egress` defaults to `denied`.

## What it authorizes

- Building a redacted, git-pinned pack from the platform's own source (for NPDev's own missions
  M1–M6) or from an app's own model/emitted-code/records (for the product feature's M7 and beyond),
  and sending that pack — and only that pack — to an external AI vendor once an app or the project
  has explicitly opted in.
- Recording the AI's response as a structured, typed verdict, filed as a finding.
- Using that verdict to satisfy the *not-the-author* half of judgments that benefit from an
  independent perspective — e.g., REG-17's reproduction requirement, or an Impact Report's
  `MANUAL_REVIEW` convertibility question (E8, the rev-2 insight — see the plan's §1).

## What it does NOT authorize

- **Auto-applying** anything a verdict recommends. Findings are filed, never auto-applied — this
  is enforced in code (the P8 gate), not left to convention.
- Treating a verdict as sign-off, approval, or **independent human review**. Every verdict is
  recorded with the distinct, unambiguous status **`external-ai-verdict (vendor/model, no repo
  access)`** — a status string that cannot be confused with `independent-human-review` in any
  ledger, knowledge card, or generated artefact. This applies to the product feature too: an app
  author must not be able to produce an artefact that *reads like* a human approval.
- Closing E5 (real participant sessions — an AI persona is not a participant), E6 (checkpoint
  approval / branch protection / repo admin authority), or E7 (owner policy decisions, including
  the REG-17 DoD ruling itself — "the executor must never make this call"). An AI may draft the
  decision brief; it does not make the decision.
- A **fourth independent derivation of "what is sensitive."** Redaction extends the existing
  `EventRedactionPolicy` / `ExecutionRedactionPolicy` / `TraceRedactionPolicy` family
  (`com.npdev.kernel.ports`, adapter `tracing-redaction-default`) as an additional consumer of one
  rule source — not a new policy that could drift from it, which is exactly the eight-passes debt
  REG-6 already paid off once.

## The honesty contract

1. A verdict's status is always `external-ai-verdict (vendor/model, no repo access)` — never
   `independent-human-review`, never `sign-off`.
2. It does not close E5, E6, or E7 (recorded permanently non-delegable in
   `POST_BETA0_HUMAN_ACTION_REGISTER.md`'s "AI-delegable?" column, added in P9).
3. For REG-17 it satisfies only the not-the-author half of the DoD; the ruling stays the owner's
   (D4).
4. **Pack curation is a declared limitation.** Every verdict records what the reviewer was and was
   not shown, and the calibration score (P4) travels with every "no findings" result — a green
   verdict from a channel proven low-yield is not dressed up as assurance.
5. Findings are filed, never auto-applied.
6. Egress is a publish action: default `denied`, per-app opt-in, per-vendor, with the redacted pack
   reviewable before it is sent.
7. Raw transcripts live at
   `D:\WorkSpace\NPDev\NPDev_General__OutsideRepo\external-ai-review\<date>\raw\`, never in the
   repo.

## The residual risks, stated honestly

- **Vendor lock / single-model blind spots.** Two internal reviews of this project have already
  found two different bugs from two different reviewers; one external model has its own blind
  spots too — the same structural flaw, moved one layer out. Mitigation: multi-vendor by design
  for security missions (≥2 vendors), per D1.
- **An AI verdict laundered into assurance** is the failure this ADR exists to prevent. Rule 1
  above is enforced by the P8 gate (`run-external-ai-gate.ps1` + a `check-register-consistency.py`
  extension), not left as a documentation convention alone.
- **"No findings" is not proof of absence.** P4's calibration run (against the pre-fix parents of
  known bugs LNCH13-F1 and R3-F2) is the control: if the model finds neither known bug, the channel
  is recorded low-yield, not "clean."

## P4 addendum — NVIDIA model ceiling for this account (2026-07-27)

After the real P4 calibration runs (0/4, see the plan's P4 row), an attempt was made to re-run
`M0-CALIB-LNCH13` against a stronger NVIDIA model in case `meta/llama-3.3-70b-instruct` itself was
the low-yield variable. Three candidates were tried against the real, configured API key —
`nvidia/llama-3.1-nemotron-70b-instruct`, `moonshotai/kimi-k2.6` (twice, including a user-supplied
"confirmed working" request shape) — and all three failed identically: `404 Function '<id>': Not
found for account '<account-id>'`. A direct query of NVIDIA Build's own `GET /v1/models` endpoint
against the same key confirmed both rejected model IDs are nonetheless *listed* in the account's
catalog response — meaning that endpoint reflects NVIDIA's public catalog, not this account's actual
invocation entitlements, and there is no API-visible way to tell the two apart in advance. Rather
than keep spending real vendor calls on further guesses, the owner decided (2026-07-27) to accept
`meta/llama-3.3-70b-instruct` as this account's practical ceiling and let the original P4 result
stand as-is. **This is an account-provisioning limit, not a finding about model capability or about
the calibration methodology** — it does not change the P4 low-yield verdict or loosen honesty rule 4.
The three failed re-calibration attempts produced pack files but no verdicts (no vendor response was
received), left at
`D:\WorkSpace\NPDev\NPDev_General__OutsideRepo\external-ai-review\packs-recalib\` for provenance.

## Decision block

```
Decision: [ ] APPROVED   [x] APPROVED WITH CONDITIONS   [ ] REJECTED   [ ] PENDING
Owner: Marcelo Giazzon
Date:  2026-07-26 (D3 resolved 2026-07-27)
Conditions: D4/D5 remain open (see table). Real vendor network calls are now policy-authorized
            (D3) with working API keys in place (D1/D2), but each ACTUAL send still requires an
            explicit go-ahead at the moment it happens (honesty rule 6 -- egress is a publish
            action, not something a resolved policy pre-authorizes silently forever).
```

| # | Question | Status |
|---|---|---|
| D1 | Which external vendor(s)? | **ANSWERED 2026-07-26, REVISED 2026-07-26: NVIDIA Build (`nvidia`, OpenAI-compatible via `https://integrate.api.nvidia.com/v1/chat/completions`) + Google Gemini** — replaces the original "OpenAI, Google Gemini, xAI Grok" answer; owner does not hold OpenAI/xAI accounts, provided a real NVIDIA Build key (named `NVIDIABuild-Autogen-25`) and a real Gemini key instead. Down from three vendors to two — the multi-vendor mitigation (§6) still holds with two, just with a smaller sample |
| D2 | Transport: API key or manual paste? | **ANSWERED 2026-07-26: API key integration** (`external-ai-http` is the primary transport; `external-ai-inproc` paste-transport still built as the fail-safe/offline twin, mirroring the `mail-inproc`/`mail-smtp` pair) |
| D3 | Egress authorization for NPDev's own source | **ANSWERED 2026-07-27: approved, per the plan's own drafted recommendation** — code excerpts (the curated `packContents` a mission profile already declares) may be sent to the D1 vendors; `.env`/keys/DB dumps remain hard-blocked regardless, enforced by the sanitizer (secret-content-patterns.json) independent of this decision. Rationale unchanged from the plan: ADR-0007 already ratified Apache-2.0 source-first distribution, so this code is destined to be public — low IP risk in a vendor seeing curated excerpts of it early. This unblocks P4 (calibration) and, once P4 actually completes, P5 (M1–M6) — see the mission run records for each mission's remaining individual blockers |
| D4 | REG-17 DoD ruling — does M4 close REG-17 or only advance it? | PENDING |
| D5 | E5 real participants — permanently open, or schedule sessions? | PENDING |
| D6 | Feature scope: missions only (M1–M7), or also a general flow step? | **ANSWERED 2026-07-26: missions only** — no per-record flow-step primitive in this pass |
| D7 | Does the product feature ship in beta1, or after NPDev's own review closes? | **ANSWERED 2026-07-26: build P0–P9 together** — owner chose not to defer P6/P7, overriding the plan's own "P0–P5 now, P6–P7 later" recommendation |

P1 (`ExternalAiCapabilityContract`, `CapabilityContractCatalog` registration, `external-ai-inproc`,
`external-ai-http`) is built on these answers — RED-first proof that `submitPack` denies by default,
plus both adapters' own test suites, all green (`NPDevKernel/kernel`,
`NPDevKernel/adapters/external-ai-inproc`, `NPDevKernel/adapters/external-ai-http`). The HTTP
adapter is config-driven (`ExternalAiVendorProfile`) with default public endpoints for the two D1
vendors (revised); it fails closed with a distinct error code both when a mission names an unconfigured
vendor and when the configured API-key environment variable is unset. With D1-D3 now all answered and
real keys configured, no policy gate remains against a real send -- the only remaining gate is the
explicit go-ahead at the moment of each actual call, which this ADR's honesty rule 6 makes a
standing requirement, not a one-time approval.
