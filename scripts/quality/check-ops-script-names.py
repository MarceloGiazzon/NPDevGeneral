#!/usr/bin/env python3
"""C1 (LNCH-22 cold-clone audit): every `_ops\\<name>` / `$ops\\<name>` script referenced in
content/*.yml must be a script the runbook emitter actually writes.

WHY THIS EXISTS
---------------
docs/NPDEV_USER_MANUAL.md and docs/NPDEV_CONCEPTS_DEEP_DIVE.md are generated from content/*.yml
and teach the reader a ready-to-paste ops sequence. Three of its script names had never been
emitted by OperationalRunbookEmitter (Build-App.ps1, Test-App.ps1, Status-App.ps1 -- the real
names are Build-FinalApp.ps1, Smoke-Test.ps1, Status-Environment.ps1), so a reader following the
block got "is not recognized as the name of a cmdlet" -- which reads as a broken generation, not a
stale doc. The emitter's write() list is this check's ground truth; a doc that cites a script it
never writes is the twin-pair defect family (REG-89/104/112) in its purest form.

WHY IT READS THE EMITTER, NOT A CHECKER-OWNED LIST
---------------------------------------------------
A list maintained here would be a third copy of the truth, and the defect is exactly a copy
drifting from the origin. The emitted set is parsed fresh from
OperationalRunbookEmitter.java's literal `write(opsRoot.resolve("<name>"))` calls on every run --
same discipline as check-twin-pair-consistency.py, which reads its pairs out of the registry
rather than remembering them.

SCOPE
-----
Only `*.ps1` / `*.sh` names are validated. The `_ops` folder also contains runtime artifacts
(app.out.log, resolved-db-plan.json, README_RUNBOOK.md) that a doc may legitimately name without
them being emitted toolbox scripts; requiring those to be emitted would fail on honest prose.

USAGE
-----
    python scripts/quality/check-ops-script-names.py            # check the repo
    python scripts/quality/check-ops-script-names.py --calibrate  # self-test on temp files
"""
from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
EMITTER = REPO_ROOT / "NPDevGenerator" / "generator" / "src" / "main" / "java" / "com" / "npdev" / "generator" / "dbconfig" / "OperationalRunbookEmitter.java"
CONTENT_DIR = REPO_ROOT / "content"

# A `write(opsRoot.resolve("Name"))` / `writeExecutable(opsRoot.resolve("Name"))` /
# `writeJson(opsRoot.resolve("Name"))` literal. The JSON plan and README_RUNBOOK.md are emitted
# too but are not validated (see docstring SCOPE).
_EMIT_RESOLVE = re.compile(r"""opsRoot\.resolve\("([^"]+)"\)""")

# Both spellings docs actually use: `_ops\Start-App.ps1` (code spans, tables) and
# `& "$ops\Start-App.ps1"` (PowerShell fences). Backslash or forward slash. Only .ps1/.sh match,
# so `_ops\app.out.log` and `secrets\api-key.env` can never be flagged.
_OPS_REF = re.compile(r"""(?:_ops|\$ops)[\\/]([A-Za-z0-9_.-]+\.(?:ps1|sh))""")


def emitted_script_names(emitter_source: str) -> set[str]:
    return {name for name in _EMIT_RESOLVE.findall(emitter_source) if name.endswith((".ps1", ".sh"))}


def documented_script_names(root: Path) -> list[tuple[str, str]]:
    """(name, relative doc path) for every ops-script reference in content/*.yml."""
    found: list[tuple[str, str]] = []
    for yml in sorted(root.glob("*.yml")):
        text = yml.read_text(encoding="utf-8")
        for name in _OPS_REF.findall(text):
            found.append((name, yml.name))
    return found


def check(root: Path, emitter_path: Path) -> tuple[set[str], list[str]]:
    emitted = emitted_script_names(emitter_path.read_text(encoding="utf-8"))
    problems: list[str] = []
    for name, doc in documented_script_names(root):
        if name not in emitted:
            problems.append(f"{doc} references `{name}`, which OperationalRunbookEmitter never writes")
    return emitted, problems


def calibrate() -> bool:
    """Prove the checker separates a phantom name from a real one on synthetic tempfiles, so it
    cannot pass merely because the repo happens to be clean today (same discipline as
    check-twin-pair-consistency.py's run_self_test)."""
    ok = True
    with tempfile.TemporaryDirectory(prefix="npdev-ops-names-calibrate-") as tmp:
        tmp_dir = Path(tmp)
        content_dir = tmp_dir / "content"
        content_dir.mkdir()

        emitter = tmp_dir / "OperationalRunbookEmitter.java"
        emitter.write_text(
            "write(opsRoot.resolve(\"Build-FinalApp.ps1\"), buildFinalAppScript());\n"
            "write(opsRoot.resolve(\"Smoke-Test.ps1\"), smokeTestScript());\n",
            encoding="utf-8",
        )
        # Diverged: a phantom name cited in the doc, absent from the emitter.
        (content_dir / "manual.yml").write_text(
            "& \"$ops\\Build-App.ps1\"\n", encoding="utf-8")
        _, diverged = check(content_dir, emitter)
        # Consistent: the same doc, real names only.
        (content_dir / "manual.yml").write_text(
            "& \"$ops\\Build-FinalApp.ps1\"\n& \"$ops\\Smoke-Test.ps1\"\n", encoding="utf-8")
        _, consistent = check(content_dir, emitter)

        if not diverged or consistent:
            print("SELF-TEST FAILED: check-ops-script-names.py did not separate a phantom "
                  "_ops/<name> from a real one")
            ok = False
        else:
            print("Self-test: phantom ops script name detected, real names pass -- OK")

        # Scope guard: a runtime artifact must not be flagged as an unknown script.
        (content_dir / "manual.yml").write_text(
            "the app's stdout log (`_ops\\app.out.log`)\n", encoding="utf-8")
        _, runtime_artifact = check(content_dir, emitter)
        if runtime_artifact:
            print("SELF-TEST FAILED: `_ops\\app.out.log` is a runtime artifact, not a toolbox "
                  "script, and must not be flagged")
            ok = False
    return ok


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--calibrate", action="store_true",
                        help="run the self-test on synthetic tempfiles and exit")
    args = parser.parse_args()

    if args.calibrate:
        return 0 if calibrate() else 1

    if not EMITTER.is_file():
        print(f"check-ops-script-names.py: emitter not found at {EMITTER.relative_to(REPO_ROOT)}")
        return 1

    _, problems = check(CONTENT_DIR, EMITTER)
    if problems:
        print("Ops-script references that no emitter writes:")
        for problem in sorted(problems):
            print(f"  - {problem}")
        print()
        print("Fix the DOCUMENT: rename the reference to the emitted script "
              "(see OperationalRunbookEmitter's write() list), do not add a checker exception.")
        return 1

    print(f"OK: every _ops/$ops script named in content/*.yml is emitted by the runbook emitter "
          f"({len(documented_script_names(CONTENT_DIR))} references checked).")
    return 0


if __name__ == "__main__":
    sys.exit(main())