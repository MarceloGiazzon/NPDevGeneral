#!/usr/bin/env python3
r"""C1 (docs/CORPUS_INTEGRITY_PLAN.md): does every model.json in the corpus still parse against the
REAL validator (JsonModelParser + SemanticValidator, via the `validateModel` Gradle task and
ModelValidatorMain), not a heuristic grep for retired `flowStep.type` values?

The corpus is `AppGen/apps/**/definition/model.json` (CLAUDE.md Layer 2, non-git) plus
`NPDevSamples/**/Input/model.json` (this repo's own DSL volume-regression corpus). Nothing
previously checked that either stayed valid as the schema evolved -- REG-63 found two silently
broken models by accident, weeks after the break, while authoring an unrelated manifest. This
script is both C1 (one-off ground truth ahead of the dsl_v2_migration.py codemod run) and, wired
blocking into run-ai-knowledge-gate.ps1, C4 (the gate that makes sure there is never an 18th).

Passes --project-cache-dir explicitly (npdev-gradlew.ps1's own trick, replicated rather than
delegated to it): the plain wrapper puts Gradle's project cache inside the source tree (the exact
NPDevRuntimeHost/.gradle workspace-slimness violation hit earlier this same programme), and
validate-corpus.py runs the DSL module's JavaExec task once per model in the corpus -- 29
invocations is enough for that to matter. Calls gradlew.bat directly rather than spawning a nested
`pwsh -File npdev-gradlew.ps1 ...`: that nested invocation reproducibly mangles `-Pkey=value`
arguments (pwsh's own CLI parser eats the `-P...=D:` prefix before the script ever sees it, turning
`-PmodelPath=<drive>:\...\model.json` into a bare positional arg gradle then tries to run as a task
name)
-- confirmed by calling the wrapper directly (`& .\npdev-gradlew.ps1 ...`, which works) versus
through a spawned `pwsh -File` child process (which does not), matching CLAUDE.md's own documented
"native executable eats a leading -" class of gotcha.

Exit 0 (all models parse, or every failure is allowlisted with a reason + REG id), 1 otherwise.

    python validate-corpus.py
    python validate-corpus.py --appgen-root D:\\WorkSpace\\NPDev\\AppGen\\apps
    python validate-corpus.py --json corpus-report.json
"""
from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_APPGEN_ROOT = Path(r"D:\WorkSpace\NPDev\AppGen\apps")
DEFAULT_SAMPLES_ROOT = REPO_ROOT / "NPDevSamples"
ALLOWLIST_PATH = REPO_ROOT / "scripts" / "quality" / "corpus-parse-allowlist.json"
ROLES_PATH = REPO_ROOT / "scripts" / "quality" / "corpus-roles.json"
GRADLEW = REPO_ROOT / ("gradlew.bat" if sys.platform == "win32" else "gradlew")
CORPUS_ROLES = ("dsl-fixture", "engine-variant", "repro-case", "showcase")


def project_cache_dir() -> Path:
    """Mirrors npdev-gradlew.ps1's own cache placement: NPDEV_BUILD_ROOT if set, else
    <parent-of-NPDev_General>/Build, under gradle-cache/<repo-dir-name> -- keeps Gradle's project
    cache out of the source tree without spawning that wrapper as a nested process (see module
    docstring for why the nested-pwsh route was abandoned)."""
    import os
    external_root = Path(os.environ["NPDEV_BUILD_ROOT"]) if os.environ.get("NPDEV_BUILD_ROOT") \
        else REPO_ROOT.parent / "Build"
    return external_root / "gradle-cache" / REPO_ROOT.name


def find_models(appgen_root: Path, samples_root: Path) -> list[tuple[str, Path]]:
    """Returns (label, path) pairs, label = a stable human-readable corpus id."""
    models: list[tuple[str, Path]] = []
    if appgen_root.exists():
        for p in sorted(appgen_root.rglob("model.json")):
            rel = p.relative_to(appgen_root).parts  # e.g. ('_official', 'AuxScreen', 'definition', 'model.json')
            app = "/".join(rel[:-2]) if len(rel) > 2 else rel[0]
            models.append((f"AppGen/apps/{app}", p))
    if samples_root.exists():
        for p in sorted(samples_root.rglob("model.json")):
            rel = p.relative_to(samples_root).parts
            app = "/".join(rel[:-2]) if len(rel) > 2 else rel[0]
            models.append((f"NPDevSamples/{app}", p))
    return models


def load_allowlist() -> dict:
    if not ALLOWLIST_PATH.exists():
        return {}
    data = json.loads(ALLOWLIST_PATH.read_text(encoding="utf-8"))
    return data.get("cleared", {})


def load_roles() -> dict:
    if not ROLES_PATH.exists():
        return {}
    data = json.loads(ROLES_PATH.read_text(encoding="utf-8"))
    return data.get("roles", {})


def content_hash(path: Path) -> str:
    """F5: sha256 of the raw file bytes -- used to find byte-identical model bodies (an
    engine-variant family, or an undocumented duplicate) independent of the corpusRole a human
    assigned. This is what originally caught reg39-healthy-control being a WmsOffice clone."""
    return hashlib.sha256(path.read_bytes()).hexdigest()


def validate_one(label: str, model_path: Path, report_dir: Path) -> dict:
    report_path = report_dir / (label.replace("/", "__") + ".report.json")
    cmd = [
        str(GRADLEW),
        "--project-cache-dir", str(project_cache_dir()),
        ":NPDevContract:dsl:validateModel",
        f"-PmodelPath={model_path}",
        f"-PreportOut={report_path}",
        "--console=plain", "-q",
    ]
    proc = subprocess.run(cmd, cwd=REPO_ROOT, capture_output=True, text=True, timeout=300)

    if not report_path.exists():
        return {
            "label": label, "model": str(model_path), "status": "crashed",
            "firstError": (proc.stdout + proc.stderr).strip()[-800:] or f"exit {proc.returncode}, no report written",
        }

    report = json.loads(report_path.read_text(encoding="utf-8"))
    diagnostics = report.get("diagnostics", [])
    first_error = None
    for d in diagnostics:
        if d.get("severity") == "error":
            loc = f" ({d['path']})" if d.get("path") else ""
            first_error = f"[{d.get('layer')}] {d.get('message')}{loc}"
            break
    return {
        "label": label, "model": str(model_path), "status": report.get("status", "unknown"),
        "errors": report.get("summary", {}).get("errors", 0),
        "warnings": report.get("summary", {}).get("warnings", 0),
        "firstError": first_error,
    }


def run(appgen_root: Path, samples_root: Path) -> tuple[list[dict], int]:
    models = find_models(appgen_root, samples_root)
    allowlist = load_allowlist()
    roles = load_roles()
    results = []
    with tempfile.TemporaryDirectory(prefix="npdev-corpus-validate-") as tmp:
        report_dir = Path(tmp)
        for label, path in models:
            result = validate_one(label, path, report_dir)
            result["allowlisted"] = label in allowlist
            result["allowlistReason"] = allowlist.get(label, {}).get("why")
            result["corpusRole"] = roles.get(label)
            result["contentHash"] = content_hash(path)
            results.append(result)
    return results, len(models)


def print_table(results: list[dict]) -> None:
    width = max((len(r["label"]) for r in results), default=10)
    print(f"{'model'.ljust(width)}  parses?  role            first error")
    print(f"{'-' * width}  -------  --------------  ----------")
    for r in results:
        ok = r["status"] in ("passed", "warning")
        mark = "yes" if ok else ("ALLOWED" if r["allowlisted"] else "NO")
        role = r.get("corpusRole") or "MISSING"
        err = "" if ok else (r.get("firstError") or "")
        print(f"{r['label'].ljust(width)}  {mark.ljust(7)}  {role.ljust(14)}  {err}")


def print_role_summary(results: list[dict]) -> None:
    """F5: the corpus mixes DSL coverage with a 4-way engine fan-out; report both counts so the
    number that actually matters for a schema change (the dsl-fixture count, and the distinct-body
    count) isn't buried inside a flat model total."""
    by_role: dict[str, int] = {}
    for r in results:
        role = r.get("corpusRole") or "MISSING"
        by_role[role] = by_role.get(role, 0) + 1
    distinct_bodies = len({r["contentHash"] for r in results})
    role_parts = " | ".join(f"{by_role.get(role, 0)} {role}" for role in CORPUS_ROLES)
    missing = by_role.get("MISSING", 0)
    print(f"\n{len(results)} models | {distinct_bodies} distinct bodies | {role_parts}"
          + (f" | {missing} MISSING role" if missing else ""))


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--appgen-root", default=str(DEFAULT_APPGEN_ROOT))
    ap.add_argument("--samples-root", default=str(DEFAULT_SAMPLES_ROOT))
    ap.add_argument("--json", default=None, help="also write the full result set to this path")
    args = ap.parse_args(argv[1:])

    appgen_root = Path(args.appgen_root)
    samples_root = Path(args.samples_root)

    if not appgen_root.exists():
        print(f"Corpus validator: {appgen_root} not present on this checkout -- skipping AppGen/apps "
              f"(expected on a bare CI checkout, which has no external Layer-2 workspace).")

    results, count = run(appgen_root, samples_root)
    print(f"Corpus validator: {count} model(s) found ({appgen_root} + {samples_root}).\n")
    print_table(results)
    print_role_summary(results)

    if args.json:
        Path(args.json).write_text(json.dumps(results, indent=2), encoding="utf-8")
        print(f"\nFull results written to {args.json}")

    failing = [r for r in results if r["status"] not in ("passed", "warning") and not r["allowlisted"]]
    missing_role = [r for r in results if not r.get("corpusRole")]
    passing = count - len(failing)
    print(f"\n{passing}/{count} parse (or are allowlisted with a reason + REG id).")
    if failing:
        print(f"\nFAIL: {len(failing)} model(s) do not parse and are not allowlisted:", file=sys.stderr)
        for r in failing:
            print(f"  - {r['label']}: {r.get('firstError')}", file=sys.stderr)
        print(f"\nTo allowlist a genuine, reviewed exception, add an entry keyed by the corpus label "
              f"to {ALLOWLIST_PATH} with a 'why' and a REG id -- never pre-clear speculatively.",
              file=sys.stderr)
    if missing_role:
        print(f"\nFAIL: {len(missing_role)} model(s) have no corpusRole (F5, docs/FINAL_OPEN_ITEMS_PLAN.md) "
              f"-- no silent default:", file=sys.stderr)
        for r in missing_role:
            print(f"  - {r['label']}", file=sys.stderr)
        print(f"\nAdd an entry keyed by the corpus label to {ROLES_PATH}, one of {CORPUS_ROLES}.",
              file=sys.stderr)
    if failing or missing_role:
        return 1
    print("OK: every corpus model parses (or is a reviewed, allowlisted exception), and every model "
          "declares a corpusRole.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
