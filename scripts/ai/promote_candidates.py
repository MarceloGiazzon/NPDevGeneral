#!/usr/bin/env python3
"""Promote recurring validate/fix capture candidates into DRAFT knowledge cards for human review.

The validate loop (NPDevCli/npdev_cli.py `_capture_validation`) logs raw {resolved diagnostics, model
diff} candidates under <Build>/npdev-ai/capture/candidates/ every time a real correction lands -- from
you, the eval harness, or an external MCP agent alike. This tool turns that demand signal into card
proposals:

  1. cluster candidates by resolved failure signature,
  2. drop signatures already covered by an existing knowledge/cards/*.json error-fix card,
  3. for each remaining signature seen >= --threshold times, write a DRAFT card to
     <Build>/npdev-ai/capture/drafts/ (NEVER into the repo -- promotion is a human `git mv`).

So card-writing becomes driven by what actually failed, not by what someone remembered to write down.

Usage:
    python scripts/ai/promote_candidates.py                 # write drafts for recurring patterns
    python scripts/ai/promote_candidates.py --list          # show clusters + counts, write nothing
    python scripts/ai/promote_candidates.py --threshold 3   # require 3+ occurrences (default 2)
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any

from failure_signatures import normalize
from npdev_ai_common import build_root, repo_root


def _load_candidates(root: Path) -> list[dict[str, Any]]:
    cand_dir = build_root() / "npdev-ai" / "capture" / "candidates"
    out: list[dict[str, Any]] = []
    if not cand_dir.exists():
        return out
    for path in sorted(cand_dir.glob("*.json")):
        try:
            out.append(json.loads(path.read_text(encoding="utf-8")))
        except json.JSONDecodeError:
            continue
    return out


def _existing_card_signatures(root: Path) -> set[str]:
    cards_dir = root / "knowledge" / "cards"
    sigs: set[str] = set()
    if not cards_dir.exists():
        return sigs
    for path in cards_dir.glob("*.json"):  # non-recursive: _drafts/ excluded by design
        try:
            card = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            continue
        if card.get("type") == "error-fix" and card.get("signature"):
            sigs.add(normalize(card["signature"]))
    return sigs


def _cluster(candidates: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    """Group by resolved signature -> {count, models, diagnostics, fixes, diffs}."""
    clusters: dict[str, dict[str, Any]] = defaultdict(
        lambda: {"count": 0, "models": set(), "diagnostics": [], "fixes": [], "diffSamples": []})
    for cand in candidates:
        diags = {(_sig_of(d)): d for d in cand.get("resolvedDiagnostics", []) if isinstance(d, dict)}
        for sig in cand.get("resolvedSignatures", []):
            c = clusters[sig]
            c["count"] += 1
            c["models"].add(cand.get("modelKey", "?"))
            diag = diags.get(sig)
            if diag:
                c["diagnostics"].append(diag)
                if diag.get("suggestedFix"):
                    c["fixes"].append(diag["suggestedFix"])
            if len(c["diffSamples"]) < 3 and cand.get("diff"):
                c["diffSamples"].append(cand["diff"][:8])
    return clusters


def _sig_of(diag: dict[str, Any]) -> str:
    code = diag.get("code")
    if code:
        return f"code:{code}"
    return normalize(diag.get("message", ""), diag.get("path"),
                     diag.get("concept"), diag.get("field")) or "unknown"


def _representative(cluster: dict[str, Any]) -> dict[str, Any]:
    return cluster["diagnostics"][0] if cluster["diagnostics"] else {}


def _card_signature(rep: dict[str, Any]) -> str:
    return normalize(rep.get("message", ""), rep.get("path"), rep.get("concept"), rep.get("field"))


def _slug(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")[:48] or "capture"


def _most_common(values: list[str]) -> str | None:
    if not values:
        return None
    counts: dict[str, int] = defaultdict(int)
    for v in values:
        counts[v] += 1
    return max(counts, key=counts.get)


def _draft_card(sig: str, cluster: dict[str, Any]) -> dict[str, Any]:
    rep = _representative(cluster)
    code = rep.get("code")
    base = code or _card_signature(rep) or sig.replace("code:", "")
    fix = _most_common(cluster["fixes"]) or (
        "Correct the model so this diagnostic no longer fires; see the captured diffs under "
        "<Build>/npdev-ai/capture/candidates for how prior instances were resolved.")
    keywords = sorted({t for t in re.split(r"[^a-z0-9]+", (rep.get("message", "") + " " + (code or "")).lower())
                       if len(t) > 1})[:12]
    return {
        "schemaVersion": "knowledge-card.v1",
        "id": f"fix-{_slug(base)}",
        "type": "error-fix",
        "title": (rep.get("message") or base)[:200],
        "body": (
            f"DRAFT proposed by promote_candidates.py -- REVIEW BEFORE PROMOTING.\n\n"
            f"Seen {cluster['count']} time(s) across {len(cluster['models'])} model(s). "
            f"Diagnostic code: {code or '(none)'}. "
            f"This error was resolved in the captured runs; verify the fix below is the general "
            f"remedy, tighten the wording, then `git mv` this file into knowledge/cards/."
        ),
        "keywords": keywords,
        "appliesTo": [rep.get("concept")] if rep.get("concept") else [],
        "signature": _card_signature(rep) or sig,
        "fix": fix,
        "sourceRefs": ["capture", code] if code else ["capture"],
        "status": "active",
    }


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--threshold", type=int, default=2, help="Min occurrences to propose (default 2).")
    parser.add_argument("--list", action="store_true", dest="list_only", help="Show clusters, write nothing.")
    args = parser.parse_args(argv)

    root = repo_root()
    candidates = _load_candidates(root)
    if not candidates:
        print("no capture candidates found (run some validate/fix cycles first).")
        return 0

    clusters = _cluster(candidates)
    covered = _existing_card_signatures(root)

    ranked = sorted(clusters.items(), key=lambda kv: kv[1]["count"], reverse=True)
    print(f"{len(candidates)} candidate(s) -> {len(clusters)} signature cluster(s):\n")
    proposals: list[tuple[str, dict[str, Any]]] = []
    for sig, cluster in ranked:
        rep = _representative(cluster)
        card_sig = _card_signature(rep) or sig
        is_covered = card_sig in covered
        below = cluster["count"] < args.threshold
        flag = "covered" if is_covered else ("below-threshold" if below else "PROPOSE")
        print(f"  [{flag:16}] x{cluster['count']:<3} {sig}")
        if flag == "PROPOSE":
            proposals.append((sig, cluster))

    if args.list_only:
        print(f"\n--list: {len(proposals)} would be proposed at threshold {args.threshold}.")
        return 0

    if not proposals:
        print(f"\nnothing to propose at threshold {args.threshold} (raise/lower with --threshold).")
        return 0

    drafts_dir = build_root() / "npdev-ai" / "capture" / "drafts"
    drafts_dir.mkdir(parents=True, exist_ok=True)
    written = []
    for sig, cluster in proposals:
        card = _draft_card(sig, cluster)
        path = drafts_dir / f"{card['id']}.json"
        path.write_text(json.dumps(card, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        written.append(str(path))

    print(f"\nwrote {len(written)} draft card(s) to {drafts_dir}:")
    for w in written:
        print(f"  {w}")
    print("\nReview each, then promote the good ones into the repo, e.g.:")
    print(f"  <review> && move a draft into {root / 'knowledge' / 'cards'} && python scripts/ai/build_knowledge.py")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
