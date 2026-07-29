#!/usr/bin/env python3
"""F3.4 (docs/NEXT_EXECUTION_PLAN.md P4.3) -- infer a first-draft panel.json provenance manifest
from an existing hand-written screen, against a real bundle response (schemas/panel-provenance
.schema.json / schemas/ui-contract.schema.json).

This is the human producer: a hand-written screen has no compiler to stamp provenance the way a
generated one does (see AutoPanelExpander / CompiledMetadataCanonicalJson#toPanelCatalog), so this
script infers a draft that a human then reviews and confirms.

Bug found and fixed against REAL captured F2.1/F2.2 output (2026-07-28), before this script had ever
been run: panel-action invocation ids use a COLON separator --
`panelAction:<panel>:<action>` (CompiledMetadataCanonicalJson#panelActionInvocation) -- not the DOT
this script originally assumed (`panelAction:<panel>.<action>`), which would have silently produced
zero panel-action `invokes` on every real screen that calls one. Also: the flow-invocation lookup
assumed a `body.template.flowName` field that does not exist on a real flow invocation entry (the
real shape is `body: {"shape": "flowInput", "inputFields": [...]}` -- see
CompiledMetadataCanonicalJson#flowInvocation); harmless in practice because the code already fell
back to deriving the flow name from the invocation id (`"flow:" + flowName`), but the dead lookup is
removed here rather than left as a misleading comment on how the id is actually derived.

Design rule: `confirmed: false`. An inferred manifest is a HYPOTHESIS. The impact gate
(check-panel-provenance-impact.py, F4) enforces only confirmed manifests, so this script may be
wrong without breaking anyone's build -- and produces `unresolved`/`_evidence` precisely so a human
can confirm quickly rather than guess.

Read-only apart from the .panel.json files it writes NEXT TO THE SCREEN. Point --out-dir elsewhere
to keep the source tree clean.

    python bootstrap-panel-provenance.py bundle.json web/inventario.html
    python bootstrap-panel-provenance.py bundle.json web/*.html --out-dir ../drafts
"""
from __future__ import annotations

import argparse
import datetime
import json
import re
import sys
from pathlib import Path

API_PATH    = re.compile(r"""[`'"](/api/[A-Za-z0-9/_{}.$-]+)""")
FLOW_ROUTE  = re.compile(r"""/api/(?:v1/)?flows/([A-Za-z0-9_]+)/execute""")
FLOW_BODY   = re.compile(r"""flowName\s*[:=]\s*[`'"]([A-Za-z0-9_]+)""")
PANEL_NAME  = re.compile(r"""panelName\s*[:=]\s*[`'"]([A-Za-z0-9_]+)""")
ACTION_NAME = re.compile(r"""actionName\s*[:=]\s*[`'"]([A-Za-z0-9_]+)""")
INPUT_BLOCK = re.compile(r"""(?:input|body|payload)\s*:\s*\{(.*?)\}""", re.S)
JSON_KEY    = re.compile(r"""([A-Za-z_][A-Za-z0-9_]*)\s*:""")
IDENT       = re.compile(r"""[A-Za-z_][A-Za-z0-9_]{2,}""")
POSTPUT     = re.compile(r"""method\s*:\s*[`'"](POST|PUT|PATCH|DELETE)[`'"]""", re.I)


def load_bundle(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def field_index(bundle: dict) -> dict[str, str]:
    """lowercased unique leaf name -> canonical 'Concept.fieldPath'.

    Ambiguous leaves (same name on two concepts) are DROPPED rather than guessed -- a wrong
    canonical name in a confirmed manifest would fail the gate for the wrong reason.
    """
    seen: dict[str, int] = {}
    idx: dict[str, str] = {}
    for f in bundle.get("fields", []):
        leaf = str(f.get("fieldPath", "")).split(".")[-1].lower()
        if not leaf:
            continue
        seen[leaf] = seen.get(leaf, 0) + 1
        idx[leaf] = f'{f.get("concept")}.{f.get("fieldPath")}'
    return {k: v for k, v in idx.items() if seen.get(k) == 1}


def invocation_index(bundle: dict) -> tuple[dict[str, str], set[str]]:
    """(flowName -> invocation id, all invocation ids).

    Flow-invocation ids are `"flow:" + flowName` (CompiledMetadataCanonicalJson#flowInvocation) --
    derived directly from the id, not from a body template field (no such field exists).
    """
    by_flow: dict[str, str] = {}
    ids: set[str] = set()
    for i in bundle.get("invocations", []):
        iid = i.get("id", "")
        ids.add(iid)
        if i.get("kind") == "flow" and iid.startswith("flow:"):
            by_flow[iid.split(":", 1)[-1]] = iid
    return by_flow, ids


def infer(screen: Path, bundle: dict) -> dict:
    src = screen.read_text(encoding="utf-8", errors="ignore")
    fields = field_index(bundle)
    by_flow, all_ids = invocation_index(bundle)

    calls = sorted(set(API_PATH.findall(src)))
    flows = sorted(set(FLOW_ROUTE.findall(src)) | set(FLOW_BODY.findall(src)))
    panels = sorted(set(PANEL_NAME.findall(src)))
    actions = sorted(set(ACTION_NAME.findall(src)))

    evidence: dict[str, str] = {}
    reads: set[str] = set()
    writes: set[str] = set()
    unresolved: set[str] = set()

    for tok in sorted(set(IDENT.findall(src))):
        low = tok.lower()
        if low in fields:
            reads.add(fields[low])
            evidence.setdefault(fields[low], f"identifier '{tok}' appears in the screen")

    mutating = bool(POSTPUT.search(src)) or bool(flows)
    for blk in INPUT_BLOCK.findall(src):
        for key in JSON_KEY.findall(blk):
            low = key.lower()
            if low in fields:
                canon = fields[low]
                (writes if mutating else reads).add(canon)
                evidence[canon] = f"'{key}' appears in a request-body literal"
            elif len(key) > 3:
                unresolved.add(key)

    invokes: list[str] = []
    for f in flows:
        if f in by_flow:
            invokes.append(by_flow[f])
            evidence[by_flow[f]] = f"calls /api/flows/{f}/execute"
        else:
            candidate = f"flow:{f}"
            if candidate in all_ids:
                invokes.append(candidate)
                evidence[candidate] = f"calls /api/flows/{f}/execute"
            else:
                unresolved.add(f"flow:{f}")
    # BUG FIX 2026-07-28: colon, not dot -- CompiledMetadataCanonicalJson#panelActionInvocation
    # emits `"panelAction:" + panel.name() + ":" + action.name()`. The dot form never matched
    # anything real, so `invokes` silently missed every panel-action call before this fix.
    for p in panels:
        for a in actions:
            cid = f"panelAction:{p}:{a}"
            if cid in all_ids:
                invokes.append(cid)
                evidence[cid] = f"panelName '{p}' + actionName '{a}'"

    reads -= writes

    return {
        "schemaVersion": "npdev-panel-provenance.v1",
        "panel": "".join(w.capitalize() for w in re.split(r"[-_ ]", screen.stem)),
        "screen": screen.as_posix(),
        "screenClass": None,          # fill from docs/SCREEN_TAXONOMY.md's classification (F1)
        "producer": "human",
        "generatedFrom": {
            "modelHash": bundle.get("modelHash", ""),
            "generatedAt": datetime.datetime.now(datetime.timezone.utc)
                            .isoformat(timespec="seconds").replace("+00:00", "Z"),
            "generator": "bootstrap-panel-provenance.py",
            "bundleScope": bundle.get("scope", {}),
        },
        "reads": sorted(reads),
        "writes": sorted(writes),
        "invokes": sorted(set(invokes)),
        "calls": calls,
        "slotOf": None,               # ADR-0004 L5 layoutSlot
        "confirmed": False,           # the gate ignores unconfirmed manifests -- by design
        "unresolved": sorted(unresolved),
        "_evidence": evidence,
        "_note": ("DRAFT. Review reads/writes, resolve `unresolved`, set screenClass from the "
                  "taxonomy, then set confirmed=true. Delete _evidence once confirmed."),
    }


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("bundle")
    ap.add_argument("screens", nargs="+")
    ap.add_argument("--out-dir", help="write drafts here instead of beside the screen")
    args = ap.parse_args(argv[1:])

    bundle = load_bundle(Path(args.bundle))
    out_dir = Path(args.out_dir) if args.out_dir else None
    if out_dir:
        out_dir.mkdir(parents=True, exist_ok=True)

    rc = 0
    for raw in args.screens:
        screen = Path(raw)
        if not screen.is_file():
            print(f"skip (not a file): {screen}", file=sys.stderr)
            rc = 1
            continue
        manifest = infer(screen, bundle)
        target = (out_dir / f"{screen.stem}.panel.json") if out_dir \
            else screen.with_suffix(".panel.json")
        target.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        print(f'{target.name:<34} reads={len(manifest["reads"]):<3} '
              f'writes={len(manifest["writes"]):<3} invokes={len(manifest["invokes"]):<3} '
              f'calls={len(manifest["calls"]):<3} unresolved={len(manifest["unresolved"])}')
    return rc


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
