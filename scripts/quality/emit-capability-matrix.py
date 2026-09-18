#!/usr/bin/env python3
r"""S12 (NPDEV_MEGA_ROADMAP.md, Track B): emit the app x capability matrix with a witness column.

The existing per-feature roll-up (check-dsl-coverage.py: "feature X is used by N models") answers
"is every feature covered?" -- it does NOT answer the question a capability matrix should: for a
GIVEN sample app, which capabilities does it exercise, and is each one guarded by a TEST class or a
script CHECK anywhere in the repo? A capability present in a model but guarded by nothing is a
capability that will break silently -- the witness column makes that visible.

    python emit-capability-matrix.py
    python emit-capability-matrix.py --out <dir>     # writes matrix.json + matrix.html
    python emit-capability-matrix.py --appgen-root <p> --samples-root <p>

Output (written to scripts/reports/out/capability-matrix/ by default; --out overrides):
  matrix.json   - machine-readable: {schemaVersion, apps:[{label,path,used:[...,...]}],
                   features:[{name,corpusUsers,witness:[testFiles]}]}
  matrix.html   - the human view: rows = sample apps, columns = capabilities, cells used/unused,
                   and a WITNESS cell per feature marking whether any test file or check script in
                   the repo mentions it (keyword = the feature's last dotted segment).

Per the roadmap's checker budget this is deliberately NOT a gate -- it is a report emitter. It
never fails a build; a human (or a later session) turns a witness hole into an action.

Witness detection is honest about being approximate: it greps test/check SOURCES for the feature's
keywords (last dotted segment, plus the full dotted name) and reports which files matched. A feature
whose name matches only incidentally is over-reported in the witness column, never under-reported --
the column says "something covers this", the source list lets a reader judge whether the match is
real. The --witness-files argument replaces the default test/check universe for a narrow run.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from dsl_coverage.constants import (  # noqa: E402
    DEFAULT_APPGEN_ROOT, DEFAULT_SAMPLES_ROOT, REPO_ROOT,
)
from dsl_coverage.corpus import _merge_context_fragments, find_models  # noqa: E402
from dsl_coverage.features import FEATURE_DETECTORS  # noqa: E402

SCHEMA_VERSION = "npdev-capability-matrix.v1"
DEFAULT_OUT = REPO_ROOT / "scripts" / "reports" / "out" / "capability-matrix"


def _feature_keywords(feature: str) -> list[str]:
    """Keywords used for witness grep: the full feature name plus its last dotted segment. The last
    segment is the strongest signal ('balances' for 'aggregate.balances'); the full name is a
    secondary signal that matches tests which quote the DSL key verbatim."""
    keywords = [feature]
    if "." in feature:
        keywords.append(feature.rsplit(".", 1)[1])
    return keywords


_WITNESS_UNIVERSE_CACHE: dict[str, str] | None = None


def _is_witness_source(rel: str) -> bool:
    """Is this file a WITNESS source -- a test class, a CLI test module, or a quality/hygiene/
    policy check script? Deliberately NOT every .py under scripts/: the detector table that DEFINES
    the features (dsl_coverage/features.py) and this very emitter would match every feature name
    verbatim, turning the witness column into a tautology. reports/out copies are outputs, not
    witnesses, either. A real witness quotes a feature because it EXERCISES it, not because it
    named it."""
    if rel.endswith("Test.java"):
        return True
    if "/tests/" in rel and rel.endswith(".py") and Path(rel).name.startswith("test_"):
        return True
    if rel.startswith(("scripts/quality/", "scripts/hygiene/", "scripts/policy/")):
        if rel.startswith(("scripts/quality/dsl_coverage/", "scripts/quality/emit-capability-matrix.py")):
            return False
        if "/out/" in rel or rel.endswith("out.py"):
            return False
        return True
    return False


def _witness_universe() -> dict[str, str]:
    """{repo-relative path: text} for every WITNESS SOURCE in the repo, read ONCE per process
    (cached) -- the per-feature search reuses it, and repeated builds in one process (test suites)
    do not re-walk the tree. Heavy build dirs are skipped by content, not by a hardcoded list
    (REG-144): any directory named .git/.gradle/build/node_modules/target/Output is not a test
    source."""
    global _WITNESS_UNIVERSE_CACHE
    if _WITNESS_UNIVERSE_CACHE is not None:
        return _WITNESS_UNIVERSE_CACHE
    skipped = {".git", ".gradle", "build", "node_modules", "target", "Output", "__pycache__", ".venv"}
    universe: dict[str, str] = {}
    for root_dir, dirs, files in Path(REPO_ROOT).walk():
        dirs[:] = [d for d in dirs if d not in skipped]
        for name in files:
            path = root_dir / name
            rel = str(path.relative_to(REPO_ROOT)).replace("\\", "/")
            if not _is_witness_source(rel):
                continue
            try:
                universe[rel] = path.read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue
    _WITNESS_UNIVERSE_CACHE = universe
    return universe


def _witness_files(keywords: list[str], universe: dict[str, str] | None = None) -> list[str]:
    """Files in the witness universe that mention ANY keyword. Returns repo-relative paths, sorted;
    the caller records them per feature so a reader can judge whether a match is real."""
    universe = universe if universe is not None else _witness_universe()
    patterns = re.compile(r"|".join(re.escape(k) for k in keywords))
    matches = [rel for rel, text in universe.items() if patterns.search(text)]
    return sorted(matches)


def build_matrix(appgen_root: Path, samples_root: Path) -> dict:
    """The app x capability matrix: per app the features it uses; per feature the corpus users AND
    the witness files. Pure function -- no I/O beyond reading corpus models -- so it is directly
    unit-testable."""
    models = find_models(appgen_root, samples_root)

    app_features: list[dict] = []
    feature_witness: dict[str, list[str]] = {}
    feature_users: dict[str, int] = {f: 0 for f in FEATURE_DETECTORS}

    for label, path in models:
        try:
            model = json.loads(path.read_text(encoding="utf-8-sig"))
        except json.JSONDecodeError:
            continue
        if not isinstance(model, dict):
            continue
        model = _merge_context_fragments(model, path.parent)
        used = []
        for feature, detector in FEATURE_DETECTORS.items():
            try:
                if detector(model):
                    used.append(feature)
                    feature_users[feature] += 1
            except Exception:
                continue
        app_features.append({"label": label, "path": str(path), "used": used})

    witness_universe = _witness_universe()
    features = []
    for feature in sorted(FEATURE_DETECTORS):
        keywords = _feature_keywords(feature)
        features.append({
            "name": feature,
            "corpusUsers": feature_users[feature],
            "keywords": keywords,
            "witness": _witness_files(keywords, witness_universe),
        })

    features.sort(key=lambda f: f["name"])
    app_features.sort(key=lambda a: a["label"])
    return {
        "schemaVersion": SCHEMA_VERSION,
        "apps": app_features,
        "features": features,
        "counts": {
            "apps": len(app_features),
            "capabilities": len(features),
            "capabilitiesWithWitness": sum(1 for f in features if f["witness"]),
            "capabilitiesWithoutWitness": sum(1 for f in features if not f["witness"]),
        },
    }


def render_html(matrix: dict) -> str:
    """Self-contained HTML (inline CSS, no external assets) so a human can just open it."""
    apps = matrix["apps"]
    feat_names = [f["name"] for f in matrix["features"]]
    used_by_app = {a["label"]: set(a["used"]) for a in apps}
    witness_by_feature = {f["name"]: f["witness"] for f in matrix["features"]}

    head = f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8" />
<title>NPDev capability matrix ({len(apps)} apps x {len(feat_names)} capabilities)</title>
<style>
  body {{ font-family: -apple-system, 'Segoe UI', Roboto, sans-serif; margin: 24px; color: #222; }}
  h1 {{ font-size: 20px; }}
  p.subtitle {{ color: #666; font-size: 13px; max-width: 900px; }}
  table {{ border-collapse: collapse; margin-top: 12px; font-size: 12px; }}
  th, td {{ border: 1px solid #ddd; padding: 3px 6px; white-space: nowrap; }}
  th {{ background: #f4f4f4; position: sticky; top: 0; }}
  td.app {{ font-weight: 600; }}
  td.used {{ background: #1f7a33; color: white; text-align: center; }}
  td.unused {{ background: #fafafa; }}
  td.witness-yes {{ background: #e8f4ec; color: #1f7a33; text-align: center; }}
  td.witness-no {{ background: #fdecea; color: #c0392b; text-align: center; font-weight: 700; }}
  td.count {{ text-align: right; color: #555; }}
</style></head><body>
<h1>NPDev capability matrix</h1>
<p class="subtitle">{len(apps)} sample app(s) x {len(feat_names)} tracked DSL capability/capabilities.
Cells = the app uses the capability. The <b>witness</b> column marks, per capability, whether ANY test
class or check script in the repo mentions it ({matrix['counts']['capabilitiesWithoutWitness']}
capabilities have no witness at all -- present in the corpus but guarded by nothing).</p>
<table>
<thead><tr><th>App \\ Capability</th>
{''.join(f'<th>{name}</th>' for name in feat_names)}
<th>WITNESS</th></tr></thead>
<tbody>"""
    rows = []
    for app in apps:
        label = app["label"]
        cells = []
        for name in feat_names:
            cls = "used" if name in used_by_app.get(label, set()) else "unused"
            cells.append(f'<td class="{cls}">{"•" if cls == "used" else ""}</td>')
        witness_yes = sum(1 for name in feat_names if name in used_by_app.get(label, set())
                          and witness_by_feature.get(name))
        witness_no = sum(1 for name in feat_names if name in used_by_app.get(label, set())
                         and not witness_by_feature.get(name))
        witness_cell = (
            f'<td class="witness-yes" title="{witness_yes} used capability/capabilities with witness">{witness_yes}</td>'
            if witness_no == 0 else
            f'<td class="witness-no" title="{witness_no} used capability/capabilities with NO witness">{witness_no}!</td>'
        )
        rows.append(
            f'<tr><td class="app">{label}</td>{"".join(cells)}{witness_cell}</tr>'
        )
    rows.append('<tr><td class="app">Witness</td>' +
                ''.join(
                    f'<td class="{"witness-yes" if witness_by_feature[name] else "witness-no"}'
                    f'" title="{", ".join(witness_by_feature[name]) or "no test/check mentions this capability"}">'
                    f'{"✓" if witness_by_feature[name] else "—"}</td>'
                    for name in feat_names) +
                '<td class="count"></td></tr>')
    body = "\n".join(rows)
    tail = "</tbody></table></body></html>\n"
    return head + body + tail


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--appgen-root", default=str(DEFAULT_APPGEN_ROOT))
    ap.add_argument("--samples-root", default=str(DEFAULT_SAMPLES_ROOT))
    ap.add_argument("--out", default=str(DEFAULT_OUT))
    args = ap.parse_args(argv[1:])

    matrix = build_matrix(Path(args.appgen_root), Path(args.samples_root))

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / "matrix.json").write_text(json.dumps(matrix, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    (out / "matrix.html").write_text(render_html(matrix), encoding="utf-8")

    counts = matrix["counts"]
    print(f"Capability matrix: {counts['apps']} app(s) x {counts['capabilities']} capability/capabilities -> {out}")
    print(f"  capabilities with a witness:  {counts['capabilitiesWithWitness']}")
    print(f"  capabilities with NO witness: {counts['capabilitiesWithoutWitness']}")
    if counts["capabilitiesWithoutWitness"]:
        for f in matrix["features"]:
            if not f["witness"]:
                print(f"    - {f['name']}  (corpus users: {f['corpusUsers']})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))