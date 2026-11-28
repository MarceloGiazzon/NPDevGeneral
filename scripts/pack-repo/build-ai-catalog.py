#!/usr/bin/env python3
r"""FORM P7-T1: build the AI catalog -- L0 index lines and L1 cards -- from packs, handoffs and verdicts.

    python scripts/pack-repo/build-ai-catalog.py [--workshop <root>] [--out <dir>] [--no-builtin]

Sources (JSON only, never markdown):
  * the built-in packs `NPDevContract/packs/<id>/pack.json` (trust `verified`: each was accepted by a booted probe);
  * the PackWorkshop (`npdev pack workshop`): the highest version of every pack that has a verdict and was not rejected.
    Trust comes from the verdict: probed -> verified, reviewed/published -> certified, anything lower -> draft.
    A workshop pack replaces a built-in of the same id only when its version is higher.
  * `NPDevContract/pack-catalog/pack-roadmap.json` supplies kind / area / need / requires for roadmap ids.

Output (the NPR layout), default `<Build root>/npr-catalog/catalog/`:
    l0.txt            one line per pack: id|kind|area|trust|need|requires:a,b     (<= 40 tokens a line, <= 8000 in all)
    cards/<id>.json   L1 card, schema NPDevContract/schemas/ai-catalog/card.schema.json (<= 350 tokens)
    build-report.json what was included, what was skipped and why, token counts

Token estimate = characters / 4 (the plan's rule). A card that is over budget is trimmed (extend points, screens,
functions, events, then lists are cut) and, if it still does not fit, the build FAILS naming the pack: a budget is a
hard limit, never silently exceeded. Exit 0 = built, 1 = a budget or schema failure, 2 = usage.
"""
from __future__ import annotations

import argparse
import json
import math
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "NPDevCli"))
sys.path.insert(0, str(REPO / "scripts" / "ai"))  # build_root(): the repo's one Build-root resolver

L0_LINE_TOKENS = 40
L0_TOTAL_TOKENS = 8000
CARD_TOKENS = 350
BUDGET_MEMBER_TOKENS = 4  # the `budgetTokens` member a card carries about itself
NEED_MAX = 90
NPR_URL = "git+https://github.com/MarceloGiazzon/NPR.git"
NPR_RAW = "https://raw.githubusercontent.com/MarceloGiazzon/NPR/main/catalog"
RUNG_TRUST = {"probed": "verified", "populated": "verified", "browsed": "verified", "reviewed": "certified", "published": "certified"}
SKIPPED_BUILTINS = {"project-tracker-demo"}  # same exclusion as build-pack-catalog-view.py: a demo export, not a catalog pack
ROLE_TOKEN = re.compile(r"role\('([A-Za-z0-9_-]+)'\)")


def tokens(text: str) -> int:
    return math.ceil(len(text) / 4)


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def semver(text) -> tuple:
    try:
        return tuple(int(x) for x in str(text).split("."))
    except ValueError:
        return ()


def one_line(text: str) -> str:
    return re.sub(r"\s+", " ", str(text or "")).strip().replace("|", "/")


def cut(text: str, limit: int) -> str:
    text = one_line(text)
    if len(text) <= limit:
        return text
    return text[: limit - 3].rstrip(" ,;:-") + "..."


def first_sentence(text: str) -> str:
    text = one_line(text)
    match = re.match(r"^(.+?[.!?])(\s|$)", text)
    return match.group(1) if match else text


def need_of(doc: dict, road: dict | None) -> str:
    if road and road.get("need"):
        return one_line(road["need"])
    meta = doc.get("metadata") or {}
    return first_sentence(meta.get("summary") or doc.get("description") or doc.get("pack", ""))


def requires_of(doc: dict, road: dict | None) -> list[str]:
    deps = [d["pack"] for d in doc.get("packs", []) if isinstance(d, dict) and "pack" in d]
    return deps or list((road or {}).get("requires", []))


def kind_of(doc: dict, road: dict | None) -> str:
    if road and road.get("kind"):
        return road["kind"]
    if not doc.get("concepts"):
        return "capability" if doc.get("capabilities") else "data-type"
    return "module"


def roles_of(doc: dict) -> list[str]:
    text = json.dumps(doc)
    found = set(ROLE_TOKEN.findall(text)) | set((doc.get("requires") or {}).get("roles", []))
    return sorted(found)


def l0_line(entry: dict) -> str:
    """id|kind|area|trust|need|requires:a,b -- the need shrinks until the line fits its token budget."""
    need = cut(entry["need"], NEED_MAX)
    while True:
        line = "|".join([entry["id"], entry["kind"], entry["area"], entry["trust"], need, "requires:" + ",".join(entry["requires"])])
        if tokens(line) <= L0_LINE_TOKENS or len(need) <= 12:
            return line
        need = cut(need, len(need) - 8)


def concept_text(concept: dict) -> str:
    lifecycle = concept.get("lifecycle") or {}
    status_field = lifecycle.get("statusField")
    keys = [f["name"] for f in concept.get("fields", [])
            if f.get("required") and not f.get("id") and f.get("name") != status_field
            and f.get("type") not in ("uuid", "reference", "boolean", "json")][:3]
    parts = list(keys)
    states = [s["value"] for s in lifecycle.get("states", []) if isinstance(s, dict) and "value" in s][:6]
    if status_field and states:
        parts.append(f"{status_field}:" + "|".join(states))
    return f"{concept['name']}({', '.join(parts)})"


def role_text(token: str, doc: dict) -> str:
    writes, reads = [], []
    for concept in doc.get("concepts", []):
        access = concept.get("access") or {}
        if f"role('{token}')" in str(access.get("write", "")):
            writes.append(concept["name"])
        if f"role('{token}')" in str(access.get("read", "")):
            reads.append(concept["name"])
    bits = []
    if writes:
        bits.append("writes " + ", ".join(writes[:3]))
    if reads:
        bits.append("reads " + ", ".join(reads[:3]))
    return f"{token} -> " + ("; ".join(bits) if bits else "see the pack's access rules")


def flow_text(flow: dict) -> str:
    inp = flow.get("input")
    inp_text = inp.get("concept") if isinstance(inp, dict) and inp.get("concept") else (
        ",".join(inp) if isinstance(inp, list) else (inp if isinstance(inp, str) else ""))
    steps = [s.get("name", "") for s in flow.get("steps", []) if isinstance(s, dict)][:3]
    return f"{flow.get('name')}({inp_text}) -> {' > '.join(s for s in steps if s)}"


def function_text(fn) -> str:
    if isinstance(fn, dict):
        params = fn.get("params") or fn.get("parameters") or []
        names = [p.get("name", "") if isinstance(p, dict) else str(p) for p in params]
        return f"{fn.get('name')}({', '.join(n for n in names if n)})"
    return str(fn)


def transitive_needs(entry: dict, by_id: dict[str, dict] | None) -> tuple[list[str], list[str]]:
    """(roles to bind, capabilities to declare) for this pack AND every pack it requires, transitively.
    Composition refuses an unbound role or an undeclared capability of ANY pack in the graph ("pack 'person' (via app -> scheduling
    -> person) references role('personManager'), which the app does not declare"; "pack 'pix-charge' (via app -> payments ->
    pix-charge) requires capabilitie 'pixProvider', which the app does not declare in provides.capabilities"), ONE per validator
    round -- so a card that lists only the pack's own needs sends the AI into a repair loop that a two-round cap cannot finish
    (clinic bench 2026-11-27: scheduling -> person, payments -> pix-charge)."""
    roles: set[str] = set()
    caps: set[str] = set()
    seen: set[str] = set()
    todo = [entry]
    while todo:
        current = todo.pop()
        if current["id"] in seen:
            continue
        seen.add(current["id"])
        roles.update(roles_of(current["doc"]))
        required = current["doc"].get("requires")
        if isinstance(required, dict):
            caps.update(c for c in required.get("capabilities", []) if isinstance(c, str))
        todo.extend(by_id[dep] for dep in current.get("requires", []) if by_id and dep in by_id)
    return sorted(roles), sorted(caps)


def build_card(entry: dict, by_id: dict[str, dict] | None = None) -> tuple[dict | None, str | None]:
    doc = entry["doc"]
    provides: dict = {}
    concepts = [concept_text(c) for c in doc.get("concepts", []) if isinstance(c, dict) and "name" in c]
    if concepts:
        provides["concepts"] = concepts
    roles = [role_text(t, doc) for t in roles_of(doc)]
    if roles:
        provides["roles"] = roles
    flows = [flow_text(f) for f in doc.get("flows", []) if isinstance(f, dict) and "name" in f]
    if flows:
        provides["flows"] = flows
    events = [e["name"] for e in doc.get("events", []) if isinstance(e, dict) and "name" in e]
    if events:
        provides["events"] = events
    functions = [function_text(f) for f in doc.get("functions", [])]
    if functions:
        provides["functions"] = functions
    # D-1: a concept's back-office pages are a screens[] entry's `admin` block (kind list); the legacy autoPanels[] still counts.
    admin_pages = [{"name": s.get("name"), **s["admin"]} for s in doc.get("screens", []) if isinstance(s, dict) and isinstance(s.get("admin"), dict)]
    screens = [f"{p.get('name')} {p.get('route', '')}".strip() for p in list(doc.get("autoPanels", [])) + admin_pages if isinstance(p, dict)]
    if screens:
        provides["screens"] = screens
    points = [p.get("name", str(p)) if isinstance(p, dict) else str(p) for p in doc.get("extensionPoints", [])]
    if points:
        provides["extendPoints"] = points
    tokens_in_use, capabilities_needed = transitive_needs(entry, by_id)
    parts = []
    if tokens_in_use:
        parts.append('"roleBindings": {' + ", ".join(f'"{t}": "<YourRole>"' for t in tokens_in_use) + "}")
    if capabilities_needed:   # an app-level list of names, the shape provides.capabilities takes (the workshop consumer writes the same)
        parts.append('"capabilities": [' + ", ".join(f'"{c}"' for c in capabilities_needed) + "]")
    binding = (', "provides": {' + ", ".join(parts) + "}") if parts else ""
    use = f'{{"from": "{NPR_URL}//packs/{entry["id"]}@{entry["id"]}-{entry["version"]}"{binding}}}'
    limit = entry.get("limit") or "no limit was recorded for this pack"
    card = {"id": entry["id"], "version": entry["version"], "trust": entry["trust"], "need": cut(entry["need"], 120),
            "provides": provides, "use": cut(use, 600), "limit": cut(limit, 200)}
    if entry["requires"]:
        card["requires"] = entry["requires"]
    return fit_budget(card)


def _size(card: dict) -> int:
    return tokens(json.dumps(card, separators=(",", ":")))


def fit_budget(card: dict) -> tuple[dict | None, str | None]:
    """Trim in a fixed order until the card fits CARD_TOKENS; None + a reason when it cannot."""
    provides = card["provides"]
    # the budgetTokens member itself is part of the card (a raw size of 347 became 351 and failed the build with nothing trimmed)
    for key in ("extendPoints", "screens", "functions", "events"):
        if _size(card) + BUDGET_MEMBER_TOKENS > CARD_TOKENS and key in provides:
            del provides[key]
    for key in ("flows", "roles", "concepts"):
        while _size(card) + BUDGET_MEMBER_TOKENS > CARD_TOKENS and len(provides.get(key, [])) > 1:
            provides[key].pop()
    card["budgetTokens"] = _size(card) + BUDGET_MEMBER_TOKENS
    if card["budgetTokens"] > CARD_TOKENS:
        return None, f"card is {card['budgetTokens']} tokens after trimming (limit {CARD_TOKENS})"
    return card, None


def load_builtin(roadmap: dict[str, dict]) -> list[dict]:
    entries = []
    for pack_json in sorted((REPO / "NPDevContract" / "packs").glob("*/pack.json")):
        doc = read_json(pack_json)
        if doc["pack"] in SKIPPED_BUILTINS:
            continue
        road = roadmap.get(doc["pack"])
        entries.append({"id": doc["pack"], "version": str(doc["version"]), "doc": doc, "trust": "verified", "source": "builtin",
                        "kind": kind_of(doc, road), "area": (road or {}).get("area") or doc.get("category", "other"),
                        "need": need_of(doc, road), "requires": requires_of(doc, road), "limit": first_review_note(doc)})
    return entries


def first_review_note(doc: dict) -> str:
    notes = (doc.get("metadata") or {}).get("reviewNotes")
    if isinstance(notes, list) and notes:
        return one_line(notes[0])
    if isinstance(notes, str):
        return first_sentence(notes)
    return ""


def load_workshop(root: Path, roadmap: dict[str, dict], skipped: list[dict]) -> list[dict]:
    import npdev_pack_workshop as ws
    entries = []
    seen = {(p, v): a for p, v, a in ws.list_workshop_packs(root)}
    newest: dict[str, tuple[str, str]] = {}
    for (pack, version), area in seen.items():
        if pack not in newest or ws._semver(version) > ws._semver(newest[pack][0]):
            newest[pack] = (version, area)
    for pack, (version, area) in sorted(newest.items()):
        found = ws.latest_verdict(root, pack, version)
        if found is None:
            skipped.append({"id": pack, "version": version, "why": "never tested (no verdict)"})
            continue
        verdict = found[0]
        if verdict["status"] == "rejected":
            skipped.append({"id": pack, "version": version, "why": f"rejected at {ws.failed_step(verdict)}"})
            continue
        folder = ws.find_pack_dir(root, pack, version)
        doc = read_json(folder / "pack.json")
        road = roadmap.get(pack)
        handoff = read_json(folder / "handoff.json") if (folder / "handoff.json").is_file() else {}
        limits = handoff.get("limits") or []
        entries.append({"id": pack, "version": version, "doc": doc, "trust": RUNG_TRUST.get(verdict["status"], "draft"),
                        "source": "workshop", "kind": kind_of(doc, road), "area": (road or {}).get("area") or area or "other",
                        "need": need_of(doc, road), "requires": requires_of(doc, road),
                        "limit": one_line(limits[0]) if limits else first_review_note(doc)})
    return entries


def build(workshop: Path | None, include_builtin: bool) -> tuple[list[dict], list[dict], list[str]]:
    """(entries, skipped, problems). Entries are sorted by area then id; problems are budget/schema failures."""
    road_path = REPO / "NPDevContract" / "pack-catalog" / "pack-roadmap.json"
    roadmap = {p["id"]: p for p in read_json(road_path).get("packs", []) if isinstance(p, dict) and "id" in p} if road_path.is_file() else {}
    skipped: list[dict] = []
    chosen: dict[str, dict] = {}
    for entry in (load_builtin(roadmap) if include_builtin else []):
        chosen[entry["id"]] = entry
    if workshop is not None and (workshop / "packs").is_dir():
        for entry in load_workshop(workshop, roadmap, skipped):
            known = chosen.get(entry["id"])
            if known is None or semver(entry["version"]) > semver(known["version"]):
                chosen[entry["id"]] = entry
    import npdev_jsonschema
    schema = read_json(REPO / "NPDevContract" / "schemas" / "ai-catalog" / "card.schema.json")
    problems: list[str] = []
    entries = []
    for entry in sorted(chosen.values(), key=lambda e: (e["area"], e["id"])):
        card, why = build_card(entry, chosen)
        if card is None:
            problems.append(f"{entry['id']}: {why}")
            continue
        errors = npdev_jsonschema.validate(schema, card)
        if errors:
            problems.append(f"{entry['id']}: card is not valid: {errors[0].get('path')}: {errors[0].get('message')}")
            continue
        entry["card"] = card
        entry["l0"] = l0_line(entry)
        entries.append(entry)
    total = sum(tokens(e["l0"]) for e in entries)
    if total > L0_TOTAL_TOKENS:
        problems.append(f"the L0 index is {total} tokens (limit {L0_TOTAL_TOKENS})")
    return entries, skipped, problems


def readme_section(entries: list[dict]) -> str:
    """FORM P7-T4: the NPR README section that tells a browser AI where the catalog files are. Staged beside the
    catalog for the owner to copy into the NPR README; this script never publishes."""
    example = entries[0]["id"] if entries else "<id>"
    parts = [
        "## Pack catalog for AIs",
        "",
        f"{len(entries)} reusable packs, described in two small levels so an AI can pick one without reading it.",
        "",
        "1. **Index** - one line per pack (`id|kind|area|trust|need|requires:...`, trust is `draft`, `verified` or `certified`):",
        f"   {NPR_RAW}/l0.txt",
        "2. **Card** - about 300 tokens per pack: concepts with key fields and states, roles to bind, flows, events, screens, "
        "the exact `packs[]` line to consume it, and its main limit:",
        f"   {NPR_RAW}/cards/<id>.json  (for example {NPR_RAW}/cards/{example}.json)",
        "",
        "Read the index, pick the packs that match the need, read only their cards, then put the `use` line of each card "
        "in the model's `packs[]`. Prefer a catalog pack to re-modelling what it already covers.",
        "",
    ]
    return "\n".join(parts)


def write_output(out: Path, entries: list[dict], skipped: list[dict], problems: list[str]) -> Path:
    catalog = out / "catalog"
    cards = catalog / "cards"
    if cards.exists():
        for old in cards.glob("*.json"):
            old.unlink()
    cards.mkdir(parents=True, exist_ok=True)
    (catalog / "l0.txt").write_text("\n".join(e["l0"] for e in entries) + ("\n" if entries else ""), encoding="utf-8")
    for e in entries:
        (cards / f"{e['id']}.json").write_text(json.dumps(e["card"], indent=2) + "\n", encoding="utf-8")
    (out / "README.catalog.md").write_text(readme_section(entries), encoding="utf-8")
    report = {"packs": [{"id": e["id"], "version": e["version"], "trust": e["trust"], "source": e["source"],
                         "l0Tokens": tokens(e["l0"]), "cardTokens": e["card"]["budgetTokens"]} for e in entries],
              "l0TotalTokens": sum(tokens(e["l0"]) for e in entries), "skipped": skipped, "problems": problems}
    path = catalog / "build-report.json"
    path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return path


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--workshop", default=None, help="PackWorkshop root (default: $NPDEV_PACK_WORKSHOP, else PackWorkshop next to the Build root)")
    ap.add_argument("--no-workshop", action="store_true", help="built-in packs only")
    ap.add_argument("--no-builtin", action="store_true", help="workshop packs only")
    ap.add_argument("--out", default=None, help="output folder (default: <Build root>/npr-catalog)")
    args = ap.parse_args(argv)
    from npdev_ai_common import build_root
    out = Path(args.out) if args.out else build_root() / "npr-catalog"
    workshop = None
    if not args.no_workshop:
        import os
        env = os.environ.get("NPDEV_PACK_WORKSHOP")
        workshop = Path(args.workshop) if args.workshop else (Path(env) if env else build_root().parent / "PackWorkshop")
    entries, skipped, problems = build(workshop, not args.no_builtin)
    report = write_output(out, entries, skipped, problems)
    print(f"catalog: {len(entries)} pack(s), L0 {sum(tokens(e['l0']) for e in entries)} tokens -> {out / 'catalog'}")
    for s in skipped:
        print(f"  skipped {s['id']}@{s['version']}: {s['why']}")
    for p in problems:
        print(f"  PROBLEM {p}", file=sys.stderr)
    print(f"report: {report}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
