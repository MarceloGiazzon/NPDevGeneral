"""`npdev migrate business-packs-2`: move a consumer model onto the single-owner built-in packs.

sales 2.0.0, purchasing 2.0.0 and inventory 2.0.0 stopped keeping their own copy of who the other
party is and what the product is. The organization now lives only in the contacts pack
(contacts::Organization) and the product only in the new products pack (products::Product);
sales::Customer, sales::SalesItem, purchasing::Supplier and purchasing::PurchaseItem became
satellites that point back at them, and inventory::Product became inventory::StockItem (a
satellite of products::Product). See BREAKING.md (2026-11-12).

What this rewrites in a model that imports any of the three packs:
  1. `<inventory>::Product` -> `<inventory>::StockItem`, everywhere it appears as a string value or
     an object key (role grants, reference targets, query/panel concepts).
  2. Role grants: a role that may act on Customer/Supplier gets the same rights on
     contacts::Organization, and one that may act on SalesItem/PurchaseItem/StockItem gets them on
     products::Product -- creating a customer now means creating the organization first.
  3. With --write and a local pack copy next to the model (`packs/<id>/pack.json`, the convention the
     resolver itself uses): refreshes the sales/purchasing/inventory copies from the new built-in
     version and adds the contacts/products copies the new versions depend on.

What it does NOT do, and says so in its notes:
  - Existing database rows are not converted. A Customer row's name/taxId/email have no column any
    more; re-seed, or create the organizations and products first and point the satellites at them.
  - `itemId` on SalesOrderLine/PurchaseOrderLine is now `productId` and points at a product, not a
    price-list item. A model that names that field itself is reported for review, not rewritten,
    because the same name may belong to the model's own concepts.

Idempotent: a second run changes nothing.
"""
from __future__ import annotations

import json
import re
import shutil
from dataclasses import dataclass, field
from pathlib import Path

INVOLVED = ("sales", "purchasing", "inventory")
DEPENDENCIES = {"sales": ("contacts", "products"), "purchasing": ("contacts", "products"), "inventory": ("products",)}
DEFAULT_PACKS_SOURCE = Path(__file__).resolve().parent.parent / "NPDevContract" / "packs"
_REF_RE = re.compile(r"(?:^|[/\\])packs[/\\]([^/\\]+)[/\\]pack\.json$")
_FROM_RE = re.compile(r"//packs/([^@/]+)@")


@dataclass
class MigrationResult:
    changed: bool = False
    changes: list = field(default_factory=list)
    notes: list = field(default_factory=list)

    def change(self, text: str) -> None:
        self.changed = True
        self.changes.append(text)


def pack_aliases(doc: dict) -> dict:
    """Real pack id -> the qualifier its concepts carry in this model (the app's own `as`, else the id)."""
    out = {}
    for ref in doc.get("packs") or []:
        if not isinstance(ref, dict):
            continue
        pid = ref.get("pack")
        m = _REF_RE.search(str(ref.get("$ref") or "")) or _FROM_RE.search(str(ref.get("from") or ""))
        if m:
            pid = m.group(1)
        if pid:
            out[pid] = ref.get("as") or pid
    return out


def _rename_everywhere(node, old: str, new: str, counter: list):
    if isinstance(node, dict):
        items = [(k, _rename_everywhere(v, old, new, counter)) for k, v in node.items()]
        node.clear()
        for k, v in items:  # rebuilt in place so the renamed key keeps its position
            if k == old:
                counter[0] += 1
                k = new
            node[k] = v
        return node
    if isinstance(node, list):
        return [_rename_everywhere(x, old, new, counter) for x in node]
    if node == old:
        counter[0] += 1
        return new
    return node


def _mentions(node, needle: str) -> bool:
    if isinstance(node, dict):
        return any(k == needle or _mentions(v, needle) for k, v in node.items())
    if isinstance(node, list):
        return any(_mentions(x, needle) for x in node)
    return node == needle


def migrate_document(doc: dict) -> MigrationResult:
    result = MigrationResult()
    aliases = pack_aliases(doc)
    present = [p for p in INVOLVED if p in aliases]
    if not present:
        return result
    q = lambda pid: aliases.get(pid, pid)  # noqa: E731 -- a dependency not imported directly keeps its own id

    if "inventory" in present:
        counter = [0]
        _rename_everywhere(doc, f"{q('inventory')}::Product", f"{q('inventory')}::StockItem", counter)
        if counter[0]:
            result.change(f"renamed {q('inventory')}::Product -> {q('inventory')}::StockItem ({counter[0]} place(s))")

    mirror = {}
    if "sales" in present:
        mirror[f"{q('sales')}::Customer"] = f"{q('contacts')}::Organization"
        mirror[f"{q('sales')}::SalesItem"] = f"{q('products')}::Product"
    if "purchasing" in present:
        mirror[f"{q('purchasing')}::Supplier"] = f"{q('contacts')}::Organization"
        mirror[f"{q('purchasing')}::PurchaseItem"] = f"{q('products')}::Product"
    if "inventory" in present:
        mirror[f"{q('inventory')}::StockItem"] = f"{q('products')}::Product"

    for role in doc.get("roles") or []:
        grants = role.get("concepts") if isinstance(role, dict) else None
        if not isinstance(grants, dict):
            continue
        for src, dst in mirror.items():
            verbs = grants.get(src)
            if not isinstance(verbs, list):
                continue
            have = grants.get(dst) if isinstance(grants.get(dst), list) else []
            missing = [v for v in verbs if v not in have]
            if missing:
                grants[dst] = have + missing
                result.change(f"role {role.get('name')}: granted {missing} on {dst} (mirrors {src})")

    if ("sales" in present or "purchasing" in present) and _mentions(doc, "itemId"):
        result.notes.append("the model names a field `itemId`: SalesOrderLine/PurchaseOrderLine.itemId is now "
                            "`productId` (a products::Product). Review each use; it was not rewritten.")
    result.notes.append("existing database rows are not converted: create organizations and products first, "
                        "then point Customer/Supplier/price-list/stock items at them, or re-seed.")
    return result


def refresh_pack_copies(model_path: Path, doc: dict, packs_source: Path, write: bool) -> MigrationResult:
    """Refresh local `packs/<id>/pack.json` copies next to the model; add missing dependency copies."""
    result = MigrationResult()
    packs_dir = model_path.parent / "packs"
    present = [p for p in INVOLVED if p in pack_aliases(doc) and (packs_dir / p / "pack.json").is_file()]
    wanted = list(present)
    for p in present:
        wanted.extend(d for d in DEPENDENCIES[p] if d not in wanted)
    for pid in wanted:
        src = packs_source / pid / "pack.json"
        dst = packs_dir / pid / "pack.json"
        if not src.is_file():
            result.notes.append(f"no built-in copy of '{pid}' under {packs_source}; add packs/{pid}/pack.json by hand")
            continue
        new = json.loads(src.read_text(encoding="utf-8"))
        if dst.is_file():
            old = json.loads(dst.read_text(encoding="utf-8"))
            if pid in INVOLVED and str(old.get("version", "")).startswith("1."):
                if "$schema" in old:
                    new["$schema"] = old["$schema"]
                if write:
                    dst.write_text(json.dumps(new, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
                result.change(f"refreshed packs/{pid}/pack.json {old.get('version')} -> {new.get('version')}")
            continue
        sibling = packs_dir / present[0] / "pack.json"
        schema = json.loads(sibling.read_text(encoding="utf-8")).get("$schema") if sibling.is_file() else None
        if schema:
            new["$schema"] = schema
        if write:
            dst.parent.mkdir(parents=True, exist_ok=True)
            dst.write_text(json.dumps(new, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        result.change(f"added packs/{pid}/pack.json {new.get('version')} (dependency of {', '.join(p for p in present if pid in DEPENDENCIES[p])})")
    if result.changed:
        result.notes.append("the pack graph now has transitive dependencies, which need an npdev.lock: run "
                            f"`npdev pack update --model {model_path}` after this migration")
    return result


def find_models(inputs) -> list:
    files = []
    for raw in inputs:
        p = Path(raw).expanduser().resolve()
        if p.is_dir():
            files.extend(sorted(x for x in p.rglob("model.json") if "packs" not in x.parts))
        elif p.is_file():
            files.append(p)
        else:
            raise FileNotFoundError(str(p))
    return files


def run(inputs, write: bool, packs_source: Path | None = None, report: Path | None = None, out=print) -> int:
    packs_source = packs_source or DEFAULT_PACKS_SOURCE
    try:
        files = find_models(inputs)
    except FileNotFoundError as exc:
        out(f"npdev migrate business-packs-2: input not found: {exc}")
        return 2
    entries, changed = [], 0
    verb = "CHANGED" if write else "WOULD CHANGE"
    for f in files:
        try:
            doc = json.loads(f.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            out(f"  [SKIP] {f}: {exc}")
            continue
        if not isinstance(doc, dict):
            continue
        r = migrate_document(doc)
        copies = refresh_pack_copies(f, doc, packs_source, write)
        changes = r.changes + copies.changes
        notes = (r.notes + copies.notes) if changes else []
        for c in changes:
            out(f"  [{verb}] {f}: {c}")
        for n in notes:
            out(f"  [NOTE] {f}: {n}")
        if r.changed and write:
            f.write_text(json.dumps(doc, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        changed += bool(changes)
        entries.append({"file": str(f), "changed": bool(changes), "changes": changes, "notes": notes})
    out(f"\n{len(files)} model(s) scanned: {changed} {'changed' if write else 'would change'}")
    if not write and changed:
        out("Dry run -- pass --write to apply.")
    if report:
        report.parent.mkdir(parents=True, exist_ok=True)
        report.write_text(json.dumps(entries, indent=2) + "\n", encoding="utf-8")
        out(f"Report written: {report}")
    return 0
