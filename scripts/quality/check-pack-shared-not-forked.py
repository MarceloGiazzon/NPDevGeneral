#!/usr/bin/env python3
"""Cross-application pack reuse proof (Path A P4.2).

P4.1 gave packs a public/private boundary; the remaining Phase 4 exit criterion is that pack reuse
across applications is a *gate result*, not an assertion in a plan document. This script is that
gate: it walks the in-repo corpus (NPDevSamples, plus the first-party pack registry at
NPDevContract/packs -- ModelSourceResolver forbids a `$ref` from escaping an app's own model root,
so NPDevContract/packs is never imported directly; it is the canonical copy every consuming app's
own local copy is supposed to mirror byte-for-byte) for pack.json files, groups them by their
declared (pack name, version), and for every group consumed by more than one application:

  - if every app's pack content hashes identically -> genuine reuse, reported as SHARED.
  - if the content differs while name+version claim to be the same -> FORKED: the pack was copied
    and hand-edited instead of being updated at its one source and re-versioned. This is exactly the
    failure mode the plan's cross-app reuse proof exists to catch, so it is a hard failure.

With --strict, zero SHARED groups is also a failure: the reuse proof itself must have at least one
live witness, or the gate is checking nothing. The current witnesses are (1) NPDevSamples/dsl-
conformance-max + pack9-role-binding-a + pack9-role-binding-b, all three importing the identical
in-git `labeling` pack (see those apps' model.json `purpose` fields), and (2) NPDevContract/packs/
identity mirrored into NPDevSamples/probes/p6-satellite-extension.

Unlike check-pack-coverage.py, the external AppGen apps tree is NOT scanned by default: it is layer
2 (app definitions), lives outside this repo, is not version-controlled with it, and its content
varies by machine and by whatever a developer happens to have checked out. A gate whose verdict
depends on that would fail or pass depending on who runs it, which is precisely the flakiness a
cross-app reuse *proof* cannot have (confirmed live: comparing the in-repo `identity` pack against
AppGen's copy flags a real, unrelated, environment-specific divergence that has nothing to do with
whether in-repo packs are shared correctly). Pass --appgen-root to opt into scanning it for manual,
local investigation; the gate never does.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path


def _app_label(root: Path, pack_json: Path) -> str:
    """The application a pack.json belongs to: everything before the packs/<name>/ segment. A root
    that IS itself a packs registry (e.g. NPDevContract/packs) has no such segment -- label it by
    the pack's own directory instead of repeating the full 'pack.json' filename."""
    rel = pack_json.relative_to(root).as_posix()
    idx = rel.find("/packs/")
    if idx != -1:
        prefix = rel[:idx]
    else:
        prefix = pack_json.parent.name
    return f"{root.name}/{prefix}" if prefix else root.name


def _digest(data: dict) -> str:
    """Content digest, excluding `$schema`: that pointer is relative to each pack's own directory
    depth (e.g. an app three levels deeper than another needs a longer `../..` chain to the same
    schema file), so two byte-for-byte-identical packs consumed at different nesting depths would
    otherwise report a false fork on that field alone -- confirmed live comparing
    NPDevContract/packs/identity against NPDevSamples/probes/p6-satellite-extension's copy, which
    differ ONLY in `$schema` and are identical in every field that actually matters."""
    content = {k: v for k, v in data.items() if k != "$schema"}
    canonical = json.dumps(content, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def scan(roots: list[Path], include_probes: bool) -> dict[tuple[str, str], list[tuple[str, str, str]]]:
    """(pack name, version) -> list of (app label, rel path, digest)."""
    groups: dict[tuple[str, str], list[tuple[str, str, str]]] = {}
    for root in roots:
        if not root.exists():
            continue
        for pack_json in sorted(root.rglob("pack.json")):
            rel = pack_json.relative_to(root).as_posix()
            if "Output" in rel or ".claude" in rel or "/worktrees/" in rel:
                continue
            if rel.startswith("probes/") and not include_probes:
                continue
            try:
                data = json.loads(pack_json.read_text(encoding="utf-8-sig"))
            except (OSError, json.JSONDecodeError):
                continue
            if not isinstance(data, dict):
                continue
            name = data.get("pack")
            version = data.get("version")
            if not name or not version:
                continue
            digest = _digest(data)
            key = (name, version)
            groups.setdefault(key, []).append((_app_label(root, pack_json), rel, digest))
    return groups


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--samples-root", default=str(Path(__file__).resolve().parents[2] / "NPDevSamples"))
    ap.add_argument("--packs-root", default=str(Path(__file__).resolve().parents[2] / "NPDevContract" / "packs"))
    ap.add_argument("--appgen-root", default=None, help="opt-in only -- layer 2, outside the repo, not scanned by default")
    ap.add_argument("--probes", action="store_true", help="include NPDevSamples/probes as witnesses")
    ap.add_argument("--strict", action="store_true", help="exit 1 if there is no live cross-app reuse witness")
    args = ap.parse_args(argv[1:])

    roots = [Path(args.samples_root), Path(args.packs_root)]
    if args.appgen_root:
        roots.append(Path(args.appgen_root))
    groups = scan(roots, include_probes=args.probes)

    print(f"Pack shared-not-forked check: roots={[str(r) for r in roots]}, probes={'on' if args.probes else 'off'}\n")

    shared: list[tuple[str, str, list[str]]] = []
    forked: list[tuple[str, str, list[tuple[str, str, str]]]] = []
    for (name, version), entries in sorted(groups.items()):
        apps = sorted({app for app, _rel, _digest in entries})
        if len(apps) < 2:
            continue
        digests = {digest for _app, _rel, digest in entries}
        if len(digests) == 1:
            shared.append((name, version, apps))
        else:
            forked.append((name, version, entries))

    if shared:
        print("SHARED (identical content across apps -- genuine reuse):")
        for name, version, apps in shared:
            print(f"  {name}@{version}  used by {len(apps)} apps: {apps}")
    else:
        print("SHARED: none found.")

    if forked:
        print("\nFORKED (same declared name+version, DIFFERENT content -- copy-edited, not shared):")
        for name, version, entries in forked:
            print(f"  {name}@{version}:")
            for app, rel, digest in entries:
                print(f"    {app}  ({rel})  sha256={digest[:12]}...")

    if forked:
        print(
            "\nFAIL: a pack was forked instead of shared -- bump its version on a real change, "
            "or fix the app whose copy drifted.",
            file=sys.stderr,
        )
        return 1

    if args.strict and not shared:
        print(
            "\nFAIL: --strict given and no pack has a live cross-app reuse witness "
            "(need >=2 apps declaring the same pack name+version with identical content).",
            file=sys.stderr,
        )
        return 1

    print("\nOK: no forked packs detected.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
