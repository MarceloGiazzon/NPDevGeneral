"""Where the corpus lives, and the flow step types the detectors scan for."""
from __future__ import annotations

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


REPO_ROOT = _repo_root()
DEFAULT_APPGEN_ROOT = Path(r"D:\WorkSpace\NPDev\AppGen\apps")
DEFAULT_SAMPLES_ROOT = REPO_ROOT / "NPDevSamples"
ALLOWLIST_PATH = REPO_ROOT / "scripts" / "quality" / "dsl-coverage-allowlist.json"

FLOW_STEP_TYPES = (
    "invariantCheck", "capabilityCall", "generatedAction", "emitEvent", "scheduleEvent",
    "return", "branch", "awaitEvent", "createConcept", "updateConcept", "map", "forEach",
    "callProcedure",
)

