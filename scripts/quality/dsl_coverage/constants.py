"""Where the corpus lives, and the flow step types the detectors scan for."""
from __future__ import annotations

import os
from pathlib import Path

def _repo_root() -> Path:
    """Identify the repo by its CONTENTS, never by a parent count or a directory name (REG-144).

    The original line here was `parents[2]`, correct while this lived in `scripts/quality/`. Moving
    it one directory deeper made it resolve to `scripts/`, so the corpus root became
    `scripts/NPDevSamples` -- which does not exist, so zero models were scanned and 44 features
    reported ZERO coverage. The gate FAILED rather than passing emptily, which is the good version
    of this mistake, and the before/after output diff caught it either way. A parent count is a
    fixed-depth assumption; this is not.
    """
    here = Path(__file__).resolve()
    for candidate in here.parents:
        if all((candidate / module).is_dir()
               for module in ("NPDevContract", "NPDevGenerator", "NPDevKernel")):
            return candidate
    raise SystemExit("could not identify the repo root by contents")


def _default_appgen_root() -> Path:
    """Layer 2 (app definitions) lives OUTSIDE the repo and is not a git repo, so CI never has it.
    Resolve it from the environment, then by walking up from this repo root looking for a sibling
    AppGen/apps -- by CONTENTS, never by assuming a drive letter (REG-144)."""
    from_env = os.environ.get("NPDEV_APPGEN_APPS")
    if from_env:
        return Path(from_env).expanduser().resolve()
    here = Path(__file__).resolve()
    for ancestor in here.parents:
        candidate = ancestor.parent / "AppGen" / "apps"
        if candidate.is_dir():
            return candidate
        if (ancestor / "NPDevContract").is_dir() and (ancestor / "NPDevKernel").is_dir():
            # the repo root, identified by contents -- stop walking
            return ancestor.parent / "AppGen" / "apps"
    return Path("AppGen") / "apps"


REPO_ROOT = _repo_root()
DEFAULT_APPGEN_ROOT = _default_appgen_root()
DEFAULT_SAMPLES_ROOT = REPO_ROOT / "NPDevSamples"
ALLOWLIST_PATH = REPO_ROOT / "scripts" / "quality" / "dsl-coverage-allowlist.json"

FLOW_STEP_TYPES = (
    "invariantCheck", "capabilityCall", "generatedAction", "emitEvent", "scheduleEvent",
    "return", "branch", "awaitEvent", "createConcept", "updateConcept", "map", "forEach",
    "callProcedure",
)

