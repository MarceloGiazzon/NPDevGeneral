#!/usr/bin/env python3
"""Renders docs/maintainers/ROADMAP_BOUNDARY_POLICY.md, MATURITY_CLOSURE_LEDGER.md and
POST_BETA0_HUMAN_ACTION_REGISTER.md from scripts/policy/maturity-max-roadmap-policy.json.

WHY THIS EXISTS
---------------
md-zero-2026-08-11 PLAN.md Phase 3. Before this, run-maturity-max-roadmap-boundary-check.ps1 read
all three docs' raw text (~20 separate .Contains()/-match assertions) to confirm they still said the
right things -- and the right things were ALSO duplicated as hardcoded PowerShell array literals in
that same script, a third copy of data the policy JSON already carried. All three had already drifted
from each other in wording (see scripts/policy/maturity-max-roadmap-policy.json's own `why` field for
specifics). This script is the single place that turns the policy JSON into the three documents; nothing
downstream reads the documents as data anymore -- run-maturity-max-roadmap-boundary-check.ps1 checks
the JSON directly.

STALENESS DETECTION MOVED TO GIT, NOT --check (Phase 7, PLAN-11-to-4.md Item 2)
--------------------------------------------------------------------------------
`--check` used to read each CURRENTLY COMMITTED .md back off disk to catch a hand-edit that was
never regenerated -- a script reading markdown content, one level removed from the docs themselves
(found while building check-no-markdown-reads.py). This script now only ever WRITES; staleness
detection moves to the caller, using git to diff bytes without this process opening the .md itself:

    python scripts/docs/generate_maturity_max_roadmap_docs.py
    git diff --exit-code -- docs/maintainers/ROADMAP_BOUNDARY_POLICY.md docs/maintainers/MATURITY_CLOSURE_LEDGER.md docs/maintainers/POST_BETA0_HUMAN_ACTION_REGISTER.md

USAGE
-----
    python scripts/docs/generate_maturity_max_roadmap_docs.py            # write all 3 docs
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

_HERE = Path(__file__).resolve().parent
_REPO_ROOT = _HERE.parent.parent
POLICY_PATH = _REPO_ROOT / "scripts" / "policy" / "maturity-max-roadmap-policy.json"

ROADMAP_BOUNDARY_POLICY_PATH = _REPO_ROOT / "docs" / "maintainers" / "ROADMAP_BOUNDARY_POLICY.md"
MATURITY_CLOSURE_LEDGER_PATH = _REPO_ROOT / "docs" / "maintainers" / "MATURITY_CLOSURE_LEDGER.md"
HUMAN_ACTION_REGISTER_PATH = _REPO_ROOT / "docs" / "maintainers" / "POST_BETA0_HUMAN_ACTION_REGISTER.md"


def render_roadmap_boundary_policy(p: dict) -> str:
    lines = [
        "# NPDev Full Maturity Closure Roadmap Boundary Policy",
        "",
        "> **GENERATED FILE — do not hand-edit.** Source: `scripts/policy/maturity-max-roadmap-policy.json`.",
        "> Regenerate with `python scripts/docs/generate_maturity_max_roadmap_docs.py`.",
        "",
        f"This policy governs the {p['roadmapName']}. It is a finite maturity-maximization roadmap "
        "with hard closure. It is not a source for automatic new phases, checkpoints, epics, or "
        "another open-ended analysis cycle.",
        "",
        "The authoritative human-provided roadmap input for Checkpoint 0 is:",
        "",
        "```text",
        p["authoritativeRoadmapInput"],
        "```",
        "",
        "Checkpoint evidence preserves the exact roadmap input and SHA-256 hash under `artifacts/roadmap/`.",
        "",
        "## Maturity Baseline and Target",
        "",
        "All Checkpoint 0 evidence uses these normalized values:",
        "",
        "| Measure | Value |",
        "|---|---:|",
        f"| Current overall maturity | {p['currentMaturity']['label']} |",
        f"| Target maturity | {p['targetMaturity']['label']} |",
        "",
        "If a source roadmap copy contains an older target, Checkpoint 0 records that as a source "
        f"inconsistency and normalizes generated evidence to `{p['targetMaturity']['label']}`.",
        "",
        "## Authoritative Checkpoint List",
        "",
        f"The roadmap contains exactly these {p['checkpointCount']} checkpoints:",
        "",
    ]
    for cp in p["checkpoints"]:
        scripts_suffix = ""
        if cp["scripts"]:
            scripts_suffix = " (" + ", ".join(f"`{s}`" for s in cp["scripts"]) + ")"
        lines.append(f"{cp['number']}. {cp['name']}{scripts_suffix}")
    lines += [
        "",
        "No checkpoint may be added, removed, renamed, split, merged, or reordered without explicit "
        "human approval. Checkpoint numbers without a script named above either have no dedicated "
        "per-checkpoint script (verification is manual/narrative) or their script lives outside this "
        "per-checkpoint naming convention -- absence here is not itself evidence the checkpoint is "
        "unverified.",
        "",
        "This policy document's own boundary rules (the checkpoint list, the no-new-roadmap rule, the "
        "closure definition) are themselves checked against reality by "
        "`scripts/quality/run-maturity-max-roadmap-boundary-check.ps1`, which reads "
        "`scripts/policy/maturity-max-roadmap-policy.json` directly -- never this rendered file.",
        "",
        "## Beta0 Tag Rule",
        "",
        "The existing `beta0` tag is immutable for this maturity-closure cycle. Automation and agents "
        "must not move, recreate, delete, reinterpret, retag, or redefine the existing `beta0` tag. "
        "This roadmap starts after Beta0 has already been tagged and pushed.",
        "",
        "Beta0 evidence must use the peeled tag commit, not the tag object. The accepted commands are:",
        "",
        "```powershell",
        "git rev-parse beta0^{}",
        "git rev-list -n 1 beta0",
        "```",
        "",
        "`beta0-verified` may be declared only when the peeled `beta0` commit matches the commit "
        "recorded in Beta0 closure evidence.",
        "",
        "## No-New-Roadmap Rule",
        "",
        "This roadmap is not allowed to grow itself. New findings do not create new phases, "
        "checkpoints, roadmaps, release gates, or broad V1 feature tracks automatically. Any proposal "
        "to add or reshape scope requires explicit human approval before implementation.",
        "",
        "## Finding Classification Taxonomy",
        "",
        "Every new issue found during implementation must be classified as exactly one of:",
        "",
        "| Classification | Meaning | Action |",
        "|---|---|---|",
    ]
    for c in p["allowedNewFindingClassifications"]:
        lines.append(f"| `{c['value']}` | {c['meaning']} | {c['action']} |")
    lines += [
        "",
        "Findings with any other classification are invalid for this roadmap. Failures must not be "
        "hidden by reclassification unless the checkpoint explicitly permits that decision.",
        "",
        "## Checkpoint Evidence Requirements",
        "",
        "Cursor local runs must produce each checkpoint evidence bundle under:",
        "",
        "```text",
        p["evidencePathPolicy"]["cursorLocalDefault"],
        "```",
        "",
        "Cloud/Codex fallback locations are:",
        "",
        "```text",
        f"$env:{p['evidencePathPolicy']['cloudFallbackEnvironmentVariable']}",
        p["evidencePathPolicy"]["repoRelativeFallback"],
        "```",
        "",
        "Each checkpoint bundle must include:",
        "",
        "```text",
    ]
    lines += p["checkpointEvidenceRequirements"]
    lines += [
        "```",
        "",
        "Checkpoint 0 additionally includes:",
        "",
        "```text",
    ]
    lines += p["checkpoint0AdditionalEvidence"]
    lines += [
        "```",
        "",
        f"The main review zip must remain under {p['evidencePathPolicy']['maxZipSizeMB']} MB. Bulky "
        f"artifacts such as {p['evidencePathPolicy']['bulkyArtifactsProse']} must be omitted or split "
        "into a secondary archive with manifest entries for size, SHA-256 hash, and review impact.",
        "",
        "Every checkpoint summary and result must state what the checkpoint does not solve.",
        "",
        "## Closure Definition",
        "",
        "This roadmap is complete only when:",
        "",
    ]
    for i, item in enumerate(p["closureDefinition"], start=1):
        lines.append(f"{i}. {item}")
    lines += [
        "",
        "Closure does not mean NPDev has no future work. Closure means the specific findings "
        "integrated into this bounded roadmap have been addressed, validated, or explicitly "
        "classified outside this bounded scope.",
        "",
        "## Dirty Worktree Handling",
        "",
        "Existing uncommitted work is preserved. Dirty worktree state is recorded as evidence only. "
        "It is not treated as a Beta0 retag action and is not an automatic Checkpoint 0 blocker.",
        "",
        "## Checkpoint 0 Does Not Solve",
        "",
    ]
    lines += [f"- {item}" for item in p["checkpoint0DoesNotSolve"]]
    lines.append("")
    return "\n".join(lines)


def render_maturity_closure_ledger(p: dict) -> str:
    lines = [
        "# NPDev Maturity Closure Ledger",
        "",
        "> **GENERATED FILE — do not hand-edit.** Source: `scripts/policy/maturity-max-roadmap-policy.json`.",
        "> Regenerate with `python scripts/docs/generate_maturity_max_roadmap_docs.py`.",
        "",
        f"This ledger records the bounded maturity-closure contract for the {p['roadmapName']}.",
        "",
        "## Baseline and Target",
        "",
        "| Measure | Value |",
        "|---|---:|",
        f"| Current overall maturity | {p['currentMaturity']['label']} |",
        f"| Target maturity | {p['targetMaturity']['label']} |",
        "",
        "Checkpoint 0 normalizes generated evidence to these values. If the human-provided roadmap "
        "source includes an older target, it is recorded as a source inconsistency and not "
        "propagated into CP0-generated evidence.",
        "",
        "## Closure Contract",
        "",
        f"The roadmap contains exactly {p['checkpointCount']} checkpoints. Checkpoints may not be "
        "added, removed, renamed, split, merged, or reordered without explicit human approval.",
        "",
        "Every new finding must use exactly one allowed classification:",
        "",
        "| Classification | Closure handling |",
        "|---|---|",
    ]
    for c in p["allowedNewFindingClassifications"]:
        lines.append(f"| `{c['value']}` | {c['action']}. |")
    lines += [
        "",
        "## Beta0 Truth",
        "",
        "Checkpoint 0 verifies Beta0 using the peeled tag commit from `git rev-parse beta0^{}` or "
        "`git rev-list -n 1 beta0`. Beta0 may not be retagged, moved, deleted, recreated, or "
        "reinterpreted by this roadmap.",
        "",
        f"The repository state is declared by `{p['beta0StateTruthReportPath']}` as one of:",
        "",
        "| State | Meaning |",
        "|---|---|",
    ]
    for s in p["beta0RepositoryStates"]:
        lines.append(f"| `{s['value']}` | {s['meaning']} |")
    lines += [
        "",
        "## Checkpoint 0 Does Not Solve",
        "",
        " ".join(p["checkpoint0DoesNotSolve"]),
        "",
    ]
    return "\n".join(lines)


def render_human_action_register(p: dict) -> str:
    lines = [
        "# Post-Beta0 Human Action Register",
        "",
        "> **GENERATED FILE — do not hand-edit.** Source: `scripts/policy/maturity-max-roadmap-policy.json`",
        "> (`humanActionRegister`). Regenerate with `python scripts/docs/generate_maturity_max_roadmap_docs.py`.",
        "",
        "This register separates AI-executable maturity work from actions that require repository "
        "administration, independent review, product judgment, or real human participation. These "
        "items are not represented as completed by automation.",
        "",
        "**AI-delegable? column (added per ADR-0009, P9).** Whether an external AI (no "
        "repo/filesystem/shell/network access, verdict recorded as `external-ai-verdict`, never "
        "`independent-human-role`) could stand in for this row. ❌ is permanent for E5/E6/E7-shaped "
        "items — see `docs/adr/ADR-0009-external-ai-delegation.md` §\"honesty contract\" items 1-2. A "
        "✅/⚠️ here is not a claim any of these rows have actually been delegated; it only records "
        "whether the mechanism could apply.",
        "",
        "| Action | Owner | Status | Evidence path | Blocking status | AI-delegable? | Notes |",
        "| --- | --- | --- | --- | --- | --- | --- |",
    ]
    for row in p["humanActionRegister"]:
        lines.append(
            f"| {row['action']} | {row['owner']} | {row['status']} | {row['evidencePath']} | "
            f"{row['blockingStatus']} | {row['aiDelegable']} | {row['notes']} |"
        )
    lines += [
        "",
        "No human-only action is represented as AI-completed. If a human owner chooses to make any "
        "non-blocking item release-blocking later, that must be recorded as a separate human decision "
        "rather than as an automatic roadmap expansion.",
        "",
    ]
    return "\n".join(lines)


TARGETS = [
    (ROADMAP_BOUNDARY_POLICY_PATH, render_roadmap_boundary_policy),
    (MATURITY_CLOSURE_LEDGER_PATH, render_maturity_closure_ledger),
    (HUMAN_ACTION_REGISTER_PATH, render_human_action_register),
]


def main(_argv: list[str]) -> int:
    policy = json.loads(POLICY_PATH.read_text(encoding="utf-8"))
    for path, renderer in TARGETS:
        rendered = renderer(policy)
        path.write_text(rendered, encoding="utf-8")
        print(f"wrote {path.relative_to(_REPO_ROOT).as_posix()}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
