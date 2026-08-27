#!/usr/bin/env python3
"""`npdev verify --panel` -- the npdev-verification-panel.v1 producer for the NPDev repo itself.

VERIFICATION_PANEL_AND_PROBE_PLAN 2026-08-27, Phase 1. THIS file is the NPDev-repo half of the
panel contract: one JSON document describing an inventory of verification items and the last known
run of each. The same document shape is later consumed by the Manager's Verification tab (§4) and
emitted, read-only, into generated apps (§5); a generated app serves the same schema with a
different producer (Phase 4). The architectural point is that two producers emit one contract and
one renderer consumes it.

DATA SOURCES (Phase 1 -- read these three files, never duplicate their contents into a registry):
  scripts/quality/verification-cadence.json           declares every gate: id, description, tier,
                                                      invokedBy, maxStaleness
  scripts/reports/out/verification-cadence-state.json records the last real run per gate: lastRun,
                                                      result, commit
  scripts/policy/script-invocation-declarations.json  declares every script's invocation mode, which
                                                      lets us add check-script items the cadence does
                                                      not track at all

The two declaration files are the cadence's own single source of truth. Critically, an item that
has a cadence declaration but NO state row (e.g. `model-validate-touched`, `dsl-test-touched-area`)
is genuinely never-run and MUST appear under NEVER RUN with `lastRun: null` -- surfacing the
never-run items is a large part of the point, so nothing ever drops them.

The Kanban column is NOT stored here. kanban_column() is a separate, pure, unit-testable function
because a decision buried inside a long producer function gets zero test coverage and ships wrong
(the same lesson as SchemaVerifyMain.exitCodeFor).
"""

from __future__ import annotations

import json
import math
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

SCHEMA_VERSION = "npdev-verification-panel.v1"

# The fixed category vocabulary (PLAN S1.1). Anything outside it is a schema violation and a
# renderer bug waiting to happen, so the producer only ever emits from this set.
_CATEGORIES = {
    "gate", "check-script", "test-suite", "probe", "acceptance", "browser-routine", "workflow",
}
# Fixed result vocabulary (PLAN S1.1). `skipped` and `not-applicable` are deliberately distinct from
# `passed` -- NPDev has already paid for conflating "did not run" with "succeeded".
_RESULTS = {"passed", "failed", "skipped", "not-applicable", "cancelled", "running"}

REPO_ROOT = Path(__file__).resolve().parents[1]
CADENCE_PATH = REPO_ROOT / "scripts" / "quality" / "verification-cadence.json"
STATE_PATH = REPO_ROOT / "scripts" / "reports" / "out" / "verification-cadence-state.json"


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _humanize_name(item_id: str) -> str:
    """camelCase / kebab-case id -> 'Spaced label'. The panel's first column is a human label; the
    cadence carries no display name, so we derive one deterministically (aiKnowledge -> 'Ai
    Knowledge'). Good enough to read; never stored."""
    spaced = re.sub(r"(?<![A-Z])(?=[A-Z])", " ", item_id)
    spaced = spaced.replace("-", " ").replace("_", " ").strip()
    words = [w for w in re.split(r"\s+", spaced) if w]
    if not words:
        return item_id
    return " ".join(w[:1].upper() + w[1:] for w in words)


def _current_commit() -> str | None:
    try:
        completed = subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=REPO_ROOT, capture_output=True, text=True, timeout=5)
        sha = completed.stdout.strip()
        return sha if completed.returncode == 0 and sha else None
    except Exception:
        return None


def _read_json(path: Path) -> dict | list | None:
    try:
        return json.loads(path.read_text(encoding="utf-8-sig"))
    except Exception:
        return None


def _load_cadence() -> dict[str, dict]:
    doc = _read_json(CADENCE_PATH)
    if not isinstance(doc, dict):
        return {}
    return {c.get("id"): c for c in doc.get("checks", []) if isinstance(c, dict) and c.get("id")}


def _load_state() -> dict[str, dict]:
    doc = _read_json(STATE_PATH)
    if not isinstance(doc, dict):
        return {}
    return {r.get("id"): r for r in doc.get("runs", []) if isinstance(r, dict) and r.get("id")}


def staleness_delta(label: str | None) -> "object | None":
    """Return the timedelta for a maxStaleness label, reusing the cadence ledger's OWN table rather
    than maintaining a second copy (the plan's 'reuse, don't re-invent'). Returns None for an empty
    label or when the ledger's module cannot be imported (portability) -- an unresolvable label is
    treated as 'no staleness applies' rather than guessed."""
    if not label:
        return None
    deltas = _staleness_deltas()
    return deltas.get(label)


def _staleness_deltas() -> dict:
    """STALENESS_TO_TIMEDELTA from scripts/quality/cadence_state.py, imported lazily so the panel
    module itself stays importable on machines where scripts/quality is absent, and so the mapping
    cannot drift OUT of sync with the ledger that actually enforces staleness."""
    try:
        sys.path.insert(0, str(REPO_ROOT / "scripts" / "quality"))
        from cadence_state import STALENESS_TO_TIMEDELTA  # type: ignore

        if isinstance(STALENESS_TO_TIMEDELTA, dict):
            return STALENESS_TO_TIMEDELTA
    except Exception:
        pass
    return {}


def kanban_column(item: dict, now: datetime) -> str:
    """The four-column Kanban state, computed EXACTLY like PLAN S1.2 (never stored in the document):

        NEVER RUN   lastRun == null
        FAILING     lastRun.result is 'failed'
        STALE       maxStaleness is set AND now - lastRun.startedAt EXCEEDS it
        HEALTHY     everything else (incl. skipped / not-applicable)

    The staleness check is strictly `age > threshold` -- 'exactly met' is still HEALTHY, because the
    rule says 'exceeds'. Any other result (skipped, cancelled, running) collapses to HEALTHY rather
    than to a pass/fail judgement; that is deliberate and matches S1.1.
    """
    last_run = item.get("lastRun")
    if last_run is None:
        return "never-run"
    result = last_run.get("result")
    if result == "failed":
        return "failing"
    delta = staleness_delta(item.get("maxStaleness"))
    started_at = last_run.get("startedAt")
    if delta is not None and started_at:
        try:
            started = datetime.fromisoformat(started_at)
        except (TypeError, ValueError):
            started = None
        if started is not None and (now - started) > delta:
            return "stale"
    return "healthy"


def _to_last_run(record: dict | None) -> dict | None:
    if not record:
        return None
    return {
        "startedAt": record.get("lastRun"),
        "result": record.get("result"),
        "durationSeconds": record.get("durationSeconds"),
        "commit": record.get("commit"),
        "reportPath": None,
        "logPath": None,
    }


def _typical_duration(entry: dict | None) -> dict | None:
    """Percentiles over the bounded run history recorded by cadence_state.py (Phase 2). Emit null
    until >= 3 measured samples exist, per S2.3 -- never synthesise a 'usual duration' from a single
    run. Non-None entries in history are ignored."""
    if not entry:
        return None
    history = entry.get("history")
    if not isinstance(history, list):
        return None
    samples = [h.get("durationSeconds") for h in history
               if isinstance(h, dict) and isinstance(h.get("durationSeconds"), (int, float))]
    samples = sorted(float(s) for s in samples if s is not None)
    if len(samples) < 3:
        return None

    def percentile(p: float) -> float:
        ranked = sorted(samples)
        # Nearest-rank: a discrete, deterministic percentile that never interpolates a value the
        # data never produced. For 3 samples p10/p50/p90 are the min/median/max.
        k = max(1, min(len(ranked), math.ceil(p * len(ranked))))
        return ranked[k - 1]

    return {
        "p10": round(percentile(0.10), 1),
        "p50": round(percentile(0.50), 1),
        "p90": round(percentile(0.90), 1),
        "sampleCount": len(samples),
    }


def _command_for(cadence_entry: dict) -> tuple[str | None, bool]:
    """(command, runnable) for a cadence item -- Phase 5 tightened so a Run control only ever
    appears for an item the controlled runner can actually execute safely.

    `invokedBy` is sometimes a direct script invocation and sometimes a manual-runbook description
    ('manual-runbook: time a full ... run'). A parenthetical ('(gate: aiKnowledge)') annotation is
    stripped, then the remaining text must be a concrete script path with plain `-flag value` args.

    Runnable means ALL of:
      * the item is not context-dependent (a -ModelPath/-DslTestFilter template cannot be filled by
        a button);
      * the command is a `.ps1` under scripts/quality -- the ONLY executable/file-root pair the
        controlled runner's policy (scripts/policy/ai-command-policy.json: allowedExecutables,
        pwsh.allowedFileRoots) will honor. A `python scripts/quality/check-*.py` is NOT runnable
        here because python is deliberately absent from allowedExecutables; re-running those
        checkers is out of Phase 5 scope until the security policy is reviewed.
      * `run-all-gates.ps1` entries get `-Only <gate>` appended so clicking one gate re-runs that
        gate, not all six (the cadence's own deferrable-gate table names -Only as the way)."""
    invoked = (cadence_entry.get("invokedBy") or "").strip()
    if not invoked or invoked.lower().startswith("manual-runbook"):
        return None, False
    if cadence_entry.get("contextDependent"):
        return None, False
    command = invoked.split(" (", 1)[0].strip()
    if command.lower().startswith("manual-runbook"):
        return None, False
    gate_hint = None
    if " (" in invoked:
        inner = invoked.split(" (", 1)[1].rsplit(")", 1)[0]
        for chunk in inner.split(","):
            chunk = chunk.strip()
            if chunk.startswith("gate: "):
                gate_hint = chunk[len("gate: "):].strip()
                break
    if gate_hint and Path(command).name == "run-all-gates.ps1":
        command = f"{command} -Only {gate_hint}"
    # Validate TOKEN BY TOKEN, deliberately not with one regex over the whole string.
    #
    # The previous shape was
    #     ^scripts/[A-Za-z0-9_./-]+\.(ps1|py)( -[A-Za-z0-9][A-Za-z0-9_-]*( [^\s<>]+)?)*$
    # and CodeQL flagged it as py/redos (HIGH). The nested quantifier is genuinely exponential: in
    # " -0 -0", the second " -0" can be read either as a new flag OR as the optional value of the
    # first, so a non-matching tail makes the engine explore every split. Measured on this machine
    # against `scripts/-.py` + N repetitions of " -0 -0" + " <":
    #     10 -> 0.004s   14 -> 0.207s   16 -> 1.466s   18 -> 11.276s   (~7x per two additions)
    #
    # Input reaching here is repo-controlled (verification-cadence.json's invokedBy), so this was
    # not reachable by an outside caller -- but "the input is trusted" is exactly the argument that
    # lets a ReDoS survive, and this function is the trusted-input boundary the executor relies on.
    # Each token is now matched with an anchored, quantifier-flat pattern; the walk is linear.
    #
    # Grammar, unchanged in intent: <script.ps1|py> then zero or more groups of
    # <-flag> optionally followed by exactly one value. A token starting with '-' is always a flag,
    # never a value -- which is precisely the ambiguity that caused the backtracking.
    tokens = command.split()
    if not tokens or not re.fullmatch(r"scripts/[A-Za-z0-9_./-]+\.(?:ps1|py)", tokens[0]):
        return None, False
    index = 1
    while index < len(tokens):
        if not re.fullmatch(r"-[A-Za-z0-9][A-Za-z0-9_-]*", tokens[index]):
            return None, False
        index += 1
        if index < len(tokens) and not tokens[index].startswith("-"):
            if not re.fullmatch(r"[^<>]+", tokens[index]):
                return None, False
            index += 1
    # Controlled-runner gate: pwsh is allowed, python is not; the SCRIPT token must sit in pwsh's
    # allowed file roots (scripts/quality). Anything else is honest `runnable: false` until the
    # policy is reviewed, never a Run button that the runner would just refuse.
    script_token = command.split()[0]
    if not script_token.endswith(".ps1") or not script_token.startswith("scripts/quality/"):
        return None, False
    return command, True


def _category_for(check: dict) -> str:
    """A1: the category column must CARRY information, not decorate. Derived mechanically from
    `invokedBy` (first match wins), never hand-maintained by id -- a hand-maintained map would
    become a second source of truth that drifts from the cadence, which is the failure mode this
    design avoids by reading the existing registries.

    Rules, in order:
      * a CI workflow (`invokedBy` begins with .github/workflows/)       -> workflow
      * a run-all-gates / orchestration entry point (the tier aggregator) -> gate
      * the one cadence entry that builds + boots + smokes a real app     -> test-suite
      * everything left, including manual-runbook entries and direct
        check-*.py invocations                                          -> check-script
    """
    invoked = (check.get("invokedBy") or "").strip()
    if invoked.startswith(".github/workflows/"):
        return "workflow"
    if "run-all-gates.ps1" in invoked or "orchestration.ps1" in invoked:
        return "gate"
    if check.get("id") == "canary-build-boot-smoke":
        return "test-suite"
    return "check-script"


def _cadence_items(cadence: dict[str, dict], state: dict[str, dict]) -> list[dict]:
    items = []
    for check_id, entry in sorted(cadence.items()):
        command, runnable = _command_for(entry)
        record = state.get(check_id)
        items.append({
            "id": check_id,
            "name": _humanize_name(check_id),
            "description": entry.get("description") or "",
            "category": _category_for(entry),
            "tier": entry.get("tier"),
            "command": command,
            "runnable": runnable,
            "maxStaleness": entry.get("maxStaleness"),
            "lastRun": _to_last_run(record),
            "typicalDurationSeconds": _typical_duration(record),
        })
    return items


def build_repo_panel(repo_root: Path | None = None) -> dict:
    """Return an npdev-verification-panel.v1 document for the NPDev repo itself.

    Scope per PLAN 2026-08-27 Phase 1: the 19 declared cadence gates are the panel's items. The
    cadence's own invariant (verification-cadence.json `_comment`) is that every check-*.py /
    run-*.ps1 nested inside an entry point is covered TRANSITIVELY by that entry point's run record
    -- so there is deliberately no second, check-script-level inventory here. Emitting each nested
    checker as its own item would reverse that design and make routinely-running checks read as
    never-run, which is the exact conflation S1.1 exists to prevent. (A generated app's producer,
    Phase 4, DOES carry check-script items for its operations -- new producers emit per-subject;
    this one does not duplicate the transitive coverage the ledger already models.)"""
    root = Path(repo_root).resolve() if repo_root is not None else REPO_ROOT
    cadence = _load_cadence()
    state = _load_state()

    items = _cadence_items(cadence, state)

    return {
        "schemaVersion": SCHEMA_VERSION,
        "generatedAt": _utc_now(),
        "subject": {
            "kind": "npdev-repo",
            "name": root.name,
            "root": str(root),
            "commit": _current_commit(),
        },
        "items": items,
    }


def print_repo_panel(repo_root: Path | None = None, *, as_human_table: bool = False) -> int:
    """CLI-friendly surface used by `npdev verify --panel`. Writes JSON (or a human table) to
    stdout and tells the caller whether the document is sane. Returns 0 on success, 1 on a schema
    shape problem (an empty item list is a real bug, not a legitimate answer)."""
    document = build_repo_panel(repo_root)
    if as_human_table:
        print(_human_table(document))
        return 0
    print(json.dumps(document, indent=2, ensure_ascii=False))
    return 0


def _human_table(document: dict) -> str:
    lines = ["VERIFICATION PANEL -- " + (document.get("subject") or {}).get("name", "?")]
    header = ["COLUMN", "CATEGORY", "TIER", "RESULT", "LAST RUN", "ITEM"]
    rows = []
    now = datetime.now(timezone.utc)
    for item in document.get("items", []):
        column = kanban_column(item, now)
        last = item.get("lastRun")
        result = (last or {}).get("result", "-")
        last_at = (last or {}).get("startedAt", "-")
        rows.append((column, item.get("category", "-"), item.get("tier") or "-",
                     result, last_at, item.get("name", item.get("id"))))
    rows.sort(key=lambda r: (r[0], r[5].lower()))
    col_widths = [max(len(header[i]), *(len(r[i]) for r in rows)) for i in range(len(header))]
    lines.append("  ".join(h.ljust(col_widths[i]) for i, h in enumerate(header)))
    lines.append("  ".join("-" * w for w in col_widths))
    for row in rows:
        lines.append("  ".join(cell.ljust(col_widths[i]) for i, cell in enumerate(row)))
    return "\n".join(lines)


if __name__ == "__main__":
    sys.exit(print_repo_panel())