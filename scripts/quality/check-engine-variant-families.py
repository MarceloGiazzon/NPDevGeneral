#!/usr/bin/env python3
r"""F6 (docs/FINAL_OPEN_ITEMS_PLAN.md): the 4 `simple-user-registry-*` models (3 under AppGen/apps
plus NPDevSamples' own copy) and the `p77-hookproof`/`p77-hookproof-pg` pair have byte-identical
model bodies, differing only in config.json/db.definition.json engine settings -- a DSL change
touches N files to prove one shape, and nothing previously asserted they STAY identical, so a fix
applied to one silently would not propagate to the others.

Recommendation (b) taken over (a): assert sameness via this gate rather than merging the family into
one `$ref`-shared model.json (a real behavioral-risk change to how those apps load, for zero benefit
over this check -- the goal is *knowing* they agree, not saving three files). Decision recorded in
docs/ACCEPTED_BOUNDARIES.md.

Families are declared in corpus-roles.json's `engineVariantFamilies` (hand-reviewed membership --
sameness is asserted, but which models belong to a family is a human judgment, same as corpusRole
itself). Reads model.json bytes directly; no Gradle/JVM involved, unlike validate-corpus.py.

    python check-engine-variant-families.py
    python check-engine-variant-families.py --calibrate
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import tempfile
from pathlib import Path


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


REPO_ROOT = Path(__file__).resolve().parents[2]
APPGEN_ROOT = _default_appgen_root()
SAMPLES_ROOT = REPO_ROOT / "NPDevSamples"
ROLES_PATH = REPO_ROOT / "scripts" / "quality" / "corpus-roles.json"


def label_to_path(label: str) -> Path:
    """Mirrors validate-corpus.py's find_models() label convention in reverse."""
    if label.startswith("AppGen/apps/"):
        return APPGEN_ROOT / label[len("AppGen/apps/"):] / "definition" / "model.json"
    if label.startswith("NPDevSamples/"):
        return SAMPLES_ROOT / label[len("NPDevSamples/"):] / "Input" / "model.json"
    raise ValueError(f"unrecognized corpus label shape: {label}")


def load_families(roles_path: Path) -> dict[str, list[str]]:
    if not roles_path.exists():
        return {}
    data = json.loads(roles_path.read_text(encoding="utf-8"))
    return data.get("engineVariantFamilies", {})


def check_families(families: dict[str, list[str]], appgen_present: bool) -> tuple[list[str], list[str]]:
    """Returns (failures, skipped-notes). A label under `AppGen/apps/` is only ever "missing" -- and
    therefore a failure -- when AppGen_ROOT itself exists but that specific model doesn't (a real
    problem). When AppGen_ROOT is entirely absent (a bare CI checkout, per CLAUDE.md's layering:
    `AppGen/apps` is not a git repo and is never part of this checkout), every AppGen-rooted label is
    simply unverifiable here, not missing -- lumping the two together made this check fail
    UNCONDITIONALLY on every bare-checkout run, since neither declared family (simple-user-registry:
    3 of 4 members under AppGen/apps; p77-hookproof: both members under AppGen/apps) has enough
    NPDevSamples-only members to compare on CI. Found live on PR #7 (2026-07-29), the first real
    GitHub Actions run of this gate since T3 removed its `paths:` filter."""
    failures = []
    skipped = []
    for family, labels in families.items():
        hashes: dict[str, str] = {}
        missing: list[str] = []
        unverifiable = 0
        for label in labels:
            path = label_to_path(label)
            if not path.is_file():
                if label.startswith("AppGen/apps/") and not appgen_present:
                    unverifiable += 1
                else:
                    missing.append(label)
                continue
            hashes[label] = hashlib.sha256(path.read_bytes()).hexdigest()
        if missing:
            failures.append(f"family '{family}': missing model(s), cannot compare: {missing}")
            continue
        if len(hashes) < 2:
            skipped.append(
                f"family '{family}': only {len(hashes)} of {len(labels)} member(s) resolvable here "
                f"({unverifiable} unverifiable, AppGen/apps not present) -- nothing to compare"
            )
            continue
        distinct = set(hashes.values())
        if len(distinct) > 1:
            by_hash: dict[str, list[str]] = {}
            for label, h in hashes.items():
                by_hash.setdefault(h, []).append(label)
            groups = " vs. ".join(str(v) for v in by_hash.values())
            failures.append(f"family '{family}' has diverged -- not byte-identical: {groups}")
    return failures, skipped


def calibrate() -> int:
    """Must FAIL when a family member's content diverges, PASS when all members match -- same
    required-controls discipline as this repo's other --calibrate scripts."""
    ok = True
    with tempfile.TemporaryDirectory(prefix="npdev-engine-variant-calibrate-") as tmp:
        tmp_path = Path(tmp)
        a = tmp_path / "a.json"
        b_same = tmp_path / "b-same.json"
        b_diff = tmp_path / "b-diff.json"
        a.write_text('{"namespace": "x", "concepts": []}', encoding="utf-8")
        b_same.write_text('{"namespace": "x", "concepts": []}', encoding="utf-8")
        b_diff.write_text('{"namespace": "x", "concepts": [{"name": "New"}]}', encoding="utf-8")

        def hash_of(p: Path) -> str:
            return hashlib.sha256(p.read_bytes()).hexdigest()

        def report(label: str, members: list[Path], expect_fail: bool) -> None:
            nonlocal ok
            distinct = {hash_of(p) for p in members}
            fired = len(distinct) > 1
            passed = fired == expect_fail
            ok = ok and passed
            print(f"  [{'PASS' if passed else 'FAIL'}] {label} ({'fired' if fired else 'silent'})")

        print("Calibration -- must catch a diverged family member, pass a matching one:")
        report("all members byte-identical", [a, b_same], expect_fail=False)
        report("one member diverged", [a, b_diff], expect_fail=True)

    # Must catch the real bug found live on PR #7 (2026-07-29): an AppGen-rooted family member being
    # unresolvable ONLY because AppGen_ROOT itself is absent (a bare CI checkout) must be SKIPPED,
    # not treated as "missing" -- both real declared families (simple-user-registry, p77-hookproof)
    # had this fail unconditionally on every CI run before the fix, since neither has 2+ members
    # resolvable from NPDevSamples alone.
    def report_families(label: str, families: dict[str, list[str]], appgen_present: bool, expect_fail: bool) -> None:
        nonlocal ok
        failures, _skipped = check_families(families, appgen_present)
        fired = bool(failures)
        passed = fired == expect_fail
        ok = ok and passed
        print(f"  [{'PASS' if passed else 'FAIL'}] {label} ({'fired' if fired else 'silent'})")
        for f in failures:
            print(f"           {f}")

    print("Calibration -- an AppGen-rooted member missing ONLY because AppGen/apps is absent must be skipped, not failed:")
    report_families(
        "family with only an AppGen-rooted member, AppGen/apps absent (bare CI checkout shape)",
        {"synthetic": ["AppGen/apps/does-not-exist-anywhere"]},
        appgen_present=False,
        expect_fail=False,
    )
    report_families(
        "family with a genuinely missing NPDevSamples-rooted member (always resolvable in-repo)",
        {"synthetic": ["NPDevSamples/does-not-exist-anywhere"]},
        appgen_present=False,
        expect_fail=True,
    )

    if not ok:
        print("\nFAIL: at least one control did not behave as required.", file=sys.stderr)
        return 1
    print("\nOK: all controls behave correctly.")
    return 0


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--roles", default=str(ROLES_PATH))
    ap.add_argument("--calibrate", action="store_true")
    args = ap.parse_args(argv[1:])

    if args.calibrate:
        return calibrate()

    families = load_families(Path(args.roles))
    if not families:
        print(f"Engine-variant family check: no families declared in {args.roles} -- nothing to check.")
        return 0

    appgen_present = APPGEN_ROOT.exists()
    if not appgen_present:
        print(f"Engine-variant family check: {APPGEN_ROOT} not present on this checkout -- "
              f"AppGen-side family members cannot be checked (expected on a bare CI checkout).")

    failures, skipped = check_families(families, appgen_present)
    print(f"Engine-variant family check: {len(families)} family(ies) declared "
          f"({', '.join(families.keys())}).")
    for note in skipped:
        print(f"  SKIPPED: {note}")
    if failures:
        print("\nFAIL: the following famil(y/ies) have diverged:", file=sys.stderr)
        for f in failures:
            print(f"  - {f}", file=sys.stderr)
        print("\nEither the divergence is a real, intended change (propagate it to every family "
              "member, or split the model out of the family in corpus-roles.json with a reason), "
              "or it is a fix that only reached one member by accident.", file=sys.stderr)
        return 1
    print("OK: every declared engine-variant family is internally byte-identical.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
