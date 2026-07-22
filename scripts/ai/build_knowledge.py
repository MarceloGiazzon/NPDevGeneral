#!/usr/bin/env python3
"""Single fan-out builder for the NPDev AI knowledge substrate.

Reads the committed sources of truth --
  - knowledge/cards/*.json          (durable findings: gotcha|gap|constraint|error-fix|recipe)
  - knowledge/platform-status.json  (derived projection of the gaps ledger)
  - golden-ai-scenarios/*/          (negative scenarios: broken model + expected failure class)
-- validates them, and fans them out into the three build artifacts the MCP tools consume, under
<Build>/npdev-ai/ (never committed, per the build-output policy):

  - rag-index.json       (via build_rag_index -- now includes knowledge cards)  [idea 1]
  - failure-index.json   (signature -> precedent fix)                            [idea 2]
  - capabilities.json    (platform status items + constraint/gap cards)          [idea 3]

Usage:
    python scripts/ai/build_knowledge.py            # build everything
    python scripts/ai/build_knowledge.py --validate-only   # just check cards, no artifacts
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

import build_rag_index
from failure_signatures import normalize
from npdev_ai_common import ai_out_dir, repo_root

CARD_TYPES = {"gotcha", "gap", "constraint", "error-fix", "recipe"}
CARD_STATUS = {"active", "superseded"}


# ---------------------------------------------------------------------------
# Card loading + lightweight (dependency-free) validation
# ---------------------------------------------------------------------------

def load_cards(root: Path) -> tuple[list[dict[str, Any]], list[str]]:
    cards_dir = root / "knowledge" / "cards"
    cards: list[dict[str, Any]] = []
    errors: list[str] = []
    if not cards_dir.exists():
        return cards, errors
    ids: set[str] = set()
    for path in sorted(cards_dir.glob("*.json")):
        rel = path.relative_to(root).as_posix()
        try:
            card = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            errors.append(f"{rel}: invalid JSON: {exc}")
            continue
        for problem in _validate_card(card):
            errors.append(f"{rel}: {problem}")
        cid = card.get("id")
        if cid in ids:
            errors.append(f"{rel}: duplicate card id '{cid}'")
        if isinstance(cid, str):
            ids.add(cid)
        cards.append(card)
    return cards, errors


def _validate_card(card: dict[str, Any]) -> list[str]:
    problems: list[str] = []
    if card.get("schemaVersion") != "knowledge-card.v1":
        problems.append("schemaVersion must be 'knowledge-card.v1'")
    for key in ("id", "title", "body"):
        if not isinstance(card.get(key), str) or not card[key].strip():
            problems.append(f"missing/blank required string '{key}'")
    if card.get("type") not in CARD_TYPES:
        problems.append(f"type must be one of {sorted(CARD_TYPES)}")
    if card.get("status", "active") not in CARD_STATUS:
        problems.append(f"status must be one of {sorted(CARD_STATUS)}")
    if isinstance(card.get("body"), str) and len(card["body"]) > 4000:
        problems.append("body exceeds 4000 chars (RAG chunk cap)")
    if card.get("type") == "error-fix":
        if not card.get("signature"):
            problems.append("type 'error-fix' requires a non-empty 'signature'")
        if not card.get("fix"):
            problems.append("type 'error-fix' requires a non-empty 'fix'")
    if card.get("status") == "superseded" and not card.get("supersededBy"):
        problems.append("status 'superseded' requires 'supersededBy'")
    return problems


def active(cards: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [c for c in cards if c.get("status", "active") == "active"]


# ---------------------------------------------------------------------------
# Artifact 2: failure-signature index
# ---------------------------------------------------------------------------

def build_failure_index(root: Path, cards: list[dict[str, Any]]) -> dict[str, Any]:
    by_sig: dict[str, dict[str, Any]] = {}

    def add(signature: str, message: str, fix: str, source: str) -> None:
        sig = normalize(signature)
        if not sig:
            return
        entry = by_sig.setdefault(sig, {"signature": sig, "examples": []})
        entry["examples"].append({"message": message, "fix": fix, "sourceRef": source})

    # (a) error-fix knowledge cards -- authored signature + real fix.
    for card in cards:
        if card.get("type") == "error-fix" and card.get("signature") and card.get("fix"):
            add(card["signature"], card["title"], card["fix"],
                "; ".join(card.get("sourceRefs") or []) or f"card:{card.get('id')}")

    # (b) negative golden scenarios -- coarse class signatures pointing at the broken example.
    scen_dir = root / "golden-ai-scenarios"
    for manifest_path in sorted(scen_dir.glob("*/scenario.manifest.json")):
        try:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            continue
        if manifest.get("kind") != "negative":
            continue
        sid = manifest.get("scenarioId", manifest_path.parent.name)
        expected_class = ""
        eb_name = (manifest.get("files") or {}).get("expectedBehavior")
        if eb_name:
            eb_path = manifest_path.parent / eb_name
            if eb_path.exists():
                with __import__("contextlib").suppress(json.JSONDecodeError):
                    expected_class = json.loads(eb_path.read_text(encoding="utf-8")).get("expectedClass", "")
        stage = manifest.get("expectedFailureStage", "")
        signature = (expected_class or sid).replace("_", " ").replace("-", " ")
        fix = (
            f"This class of model is rejected at the '{stage or 'validation'}' stage. "
            f"See golden-ai-scenarios/{sid}/ for a minimal broken example; correct the model so it no "
            f"longer trips this rule, then re-validate."
        )
        add(signature, expected_class or sid, fix, f"golden-ai-scenarios/{sid}")

    entries = sorted(by_sig.values(), key=lambda e: e["signature"])
    return {"schemaVersion": "failure-index.v1", "count": len(entries), "signatures": entries}


# ---------------------------------------------------------------------------
# Artifact 3: capabilities (platform status + constraint/gap cards)
# ---------------------------------------------------------------------------

def build_capabilities(root: Path, cards: list[dict[str, Any]]) -> dict[str, Any]:
    status_path = root / "knowledge" / "platform-status.json"
    status = json.loads(status_path.read_text(encoding="utf-8")) if status_path.exists() else {"items": []}
    constraint_cards = [
        {
            "id": c["id"],
            "type": c["type"],
            "title": c["title"],
            "body": c["body"],
            "keywords": c.get("keywords", []),
            "appliesTo": c.get("appliesTo", []),
            "sourceRefs": c.get("sourceRefs", []),
        }
        for c in cards
        if c.get("type") in ("gap", "constraint")
    ]
    return {
        "schemaVersion": "capabilities.v1",
        "generatedFrom": ["knowledge/platform-status.json", "knowledge/cards/*.json"],
        "items": status.get("items", []),
        "cards": constraint_cards,
    }


# ---------------------------------------------------------------------------

def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--validate-only", action="store_true")
    args = parser.parse_args(argv)

    root = repo_root()
    cards, errors = load_cards(root)
    if errors:
        sys.stderr.write("knowledge-card validation FAILED:\n  - " + "\n  - ".join(errors) + "\n")
        return 1
    live = active(cards)

    if args.validate_only:
        print(f"validated {len(cards)} card(s), {len(live)} active -- OK")
        return 0

    out = ai_out_dir()

    failure_index = build_failure_index(root, cards)
    (out / "failure-index.json").write_text(
        json.dumps(failure_index, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    capabilities = build_capabilities(root, cards)
    (out / "capabilities.json").write_text(
        json.dumps(capabilities, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    rag_rc = build_rag_index.main([])  # writes rag-index.json (now card-aware, see build_rag_index)

    cand_dir = ai_out_dir("capture", "candidates")
    pending = len(list(cand_dir.glob("*.json"))) if cand_dir.exists() else 0
    if pending:
        sys.stderr.write(
            f"note: {pending} validate/fix capture candidate(s) pending -- run "
            "python scripts/ai/promote_candidates.py to review draft cards.\n")

    print(json.dumps({
        "cards": {"total": len(cards), "active": len(live)},
        "failureIndex": {"signatures": failure_index["count"], "path": str(out / "failure-index.json")},
        "capabilities": {"items": len(capabilities["items"]), "cards": len(capabilities["cards"]),
                          "path": str(out / "capabilities.json")},
        "ragIndexExit": rag_rc,
    }, indent=2))
    return rag_rc


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
