"""Hand-made pages a browser AI sends (REG-323 T4.3b): `web/*.html|css|svg` and `pages.json`, checked BEFORE they are written.

What a page is: a file under `web/` beside model.json, mounted into the generated app's static folder at generation and
served same-origin; `pages.json` (`[{path, label, requiredRole?, ordinal?}]`, `path` relative to `web/`) gives an html page its
entry in the app's menu (the generator's CompanionPagesMenuEmitter). `web/theme.css` is injected into every screen by the shell.

Why a check of our own, the same reason as for themes: the generator FAILS GENERATION of the whole app when a web file has a
name the platform ships (`shell.js`, `info.html`, anything under `themes/` ...), so a page that slipped through would turn a chat
answer into an app that no longer builds. The names come from relay-ingest-policy.json (`pages.reservedFiles` /
`reservedDirectories`, pinned to FinalAppAssembler.java by twin-pair rule web-asset-reserved-names-two-place).

What is NOT done here: code. A page with a <script>, an on* handler or a javascript: link -- and an svg or css that carries the same
-- is quarantined by ingest (reason `<kind>-has-script`) and never reaches this module; running JS is T4.4's decision.

Cases an AI gets wrong, each answered in plain words and tested: a page named like a platform file, a page nobody links (a WARNING:
it is reachable only by its address), a pages.json entry whose page was not sent, `web/x.html` written in the entry's path, a
missing label, a role the model does not declare, a pages.json that holds only the NEW page (the existing entries are KEPT: the
file is merged by path, never replaced), an existing pages.json that is not JSON, a page that loads things from another site
(warning), a css that restyles every screen (warning). Stdlib only; nothing is written here.

`menu.json` (the tree the same emitter reads beside model.json: `{label, kind?, target?, ordinal?, requiredRole?, children?}`) is
handled here too, because its PAGE nodes point at the same web files. Same rules: it is MERGED, never replaced -- a node is
identified the way the emitter keys it (kind + target, or kind + label for a header), a node already in the app is updated in
place (keys it carries and the AI did not send are kept, its children merged the same way), a new one is appended. Cases an AI gets
wrong: a PAGE node whose page was not sent, a kind the menu does not know (INTERNAL is the seeder's own marker), a node with no
label, a PAGE/BUSINESS node with no target, `url` or `items` where `target` or `children` was meant (the emitter ignores them:
warning), a BUSINESS target that is neither a concept nor a panel of the model (warning), an existing menu.json that is not JSON.
"""
from __future__ import annotations

import json
import re
from pathlib import Path

POLICY_PATH = Path(__file__).resolve().parent / "relay-ingest-policy.json"
PAGES_FILE = "pages.json"
MENU_FILE = "menu.json"
HTML = (".html", ".htm")
WEB_SUFFIXES = HTML + (".css", ".svg")


def load_policy() -> dict:
    return json.loads(POLICY_PATH.read_text(encoding="utf-8"))["pages"]


def is_web_path(path) -> bool:
    return isinstance(path, str) and path.startswith("web/") and path.lower().endswith(WEB_SUFFIXES) and len(path) > 4


def is_pages_file(path) -> bool:
    return path == PAGES_FILE


def is_menu_file(path) -> bool:
    return path == MENU_FILE


def is_page_file(path) -> bool:
    return is_web_path(path) or is_pages_file(path) or is_menu_file(path)


def reserved_problem(path: str, policy: dict) -> str | None:
    """A sentence when `web/<rel>` has a name the platform ships (the generator refuses the whole app for it), else None."""
    rel = path[len("web/"):]
    if rel in policy["reservedFiles"]:
        return (f'{path}: "{rel}" is a file the platform ships and a web file may not have its name - give the page another '
                "name (for the whole-app style use web/theme.css)")
    top = rel.split("/")[0]
    if top in policy["reservedDirectories"]:
        return f'{path}: the folder "{top}" belongs to the platform - put the page directly under web/ or in a folder of its own'
    return None


def _stem_label(rel: str) -> str:
    stem = rel.rsplit("/", 1)[-1].rsplit(".", 1)[0]
    return re.sub(r"[-_\s]+", " ", stem).strip().title() or rel


def _normal_path(raw) -> tuple:
    """(the entry's path as the menu wants it -- relative to web/ --, whether it had to be rewritten) or (None, False)."""
    if not isinstance(raw, str) or not raw.strip():
        return None, False
    text = raw.strip().replace("\\", "/")
    changed = False
    while True:
        for prefix in ("./", "web/", "/web/"):
            if text.startswith(prefix):
                text, changed = text[len(prefix):], True
                break
        else:
            return (text, changed) if text else (None, False)


def entry_from(raw, index: int, policy: dict) -> tuple:
    """(entry or None, [errors], [findings]) for one pages.json item."""
    errors: list = []
    findings: list = []
    where = f"pages.json entry {index}"
    if not isinstance(raw, dict):
        return None, [f"{where} is not an object - use {{\"path\": \"page.html\", \"label\": \"Page\"}}"], findings
    path, rewritten = _normal_path(raw.get("path"))
    segments = (path or "").split("/")
    if path is None:
        errors.append(f'{where} needs a "path" (the page file, relative to web/, like "orders.html")')
    elif path.startswith("/") or any(seg in ("", ".", "..") or ":" in seg for seg in segments) or not path.lower().endswith(HTML):
        errors.append(f'{where}: "{path}" must be a plain relative path of an html page under web/')
        path = None
    elif rewritten:
        findings.append({"code": "pages-path-normalized", "severity": "info", "detail": {"path": path}})
    label = raw.get("label")
    if isinstance(label, str) and label.strip():
        label = label.strip()
        if len(label) > policy["maxLabelChars"]:
            errors.append(f'{where}: the label is longer than {policy["maxLabelChars"]} characters')
    elif path is not None:
        label = _stem_label(path)
        findings.append({"code": "pages-label-derived", "severity": "info", "detail": {"path": path, "label": label}})
    else:
        label = None
    role = raw.get("requiredRole")
    if role is not None and not (isinstance(role, str) and role.strip()):
        errors.append(f"{where}: requiredRole must be a role name or null")
    ordinal = raw.get("ordinal")
    if ordinal is not None and (isinstance(ordinal, bool) or not isinstance(ordinal, int) or ordinal < 0):
        errors.append(f"{where}: ordinal must be a whole number, 0 or more")
    if errors or path is None:
        return None, errors, findings
    entry = {"path": path, "label": label}
    if isinstance(role, str):
        entry["requiredRole"] = role.strip()
    if ordinal is not None:
        entry["ordinal"] = ordinal
    return entry, errors, findings


def _items(value):
    """The list of raw entries in a pages.json value, or None: a list, {"pages": [...]}, or one object."""
    if isinstance(value, dict) and isinstance(value.get("pages"), list):
        return value["pages"]
    if isinstance(value, dict) and "path" in value:
        return [value]
    return value if isinstance(value, list) else None


def parse_entries(text: str, policy: dict | None = None) -> tuple:
    """(entries, errors, findings) of the AI's pages.json. Never raises."""
    policy = policy or load_policy()
    try:
        items = _items(json.loads(text))
    except ValueError as exc:
        return [], [f"pages.json is not valid JSON: {str(exc)[:120]}"], []
    if items is None:
        return [], ['pages.json must be a list like [{"path": "orders.html", "label": "Orders"}]'], []
    entries: list = []
    errors: list = []
    findings: list = []
    for index, raw in enumerate(items):
        entry, errs, finds = entry_from(raw, index, policy)
        errors += errs
        findings += finds
        if entry is not None:
            entries = [e for e in entries if e["path"] != entry["path"]] + [entry]   # the same page twice: the last wins
    return entries, errors, findings


def current_entries(app_dir) -> tuple:
    """(the app's pages.json entries exactly as they are, None) -- or ([], a sentence) when the file cannot be read as a list."""
    if not app_dir:
        return [], None
    path = Path(app_dir) / PAGES_FILE
    if not path.is_file():
        return [], None
    try:
        items = _items(json.loads(path.read_text(encoding="utf-8")))
    except (OSError, ValueError):
        items = None
    if items is None or not all(isinstance(i, dict) for i in items):
        return [], ("the pages.json already in the app is not a list of pages, so it cannot be added to - "
                    "fix or remove it before sending pages")
    return list(items), None


def merge(current: list, new_entries: list) -> list:
    """The existing entries kept in order, an entry for a page already listed replaced in place, new ones appended."""
    out = [dict(item) for item in current]
    for entry in new_entries:
        for index, item in enumerate(out):
            if _normal_path(item.get("path"))[0] == entry["path"]:
                out[index] = entry
                break
        else:
            out.append(entry)
    return out


def _dump(entries: list) -> str:
    return json.dumps(entries, indent=2, ensure_ascii=False) + "\n"


def merged_text(app_dir, answer_text: str) -> str:
    """What pages.json must contain after this answer: the app's entries plus the answer's, by path. ValueError (plain
    words) when either file cannot be read."""
    entries, errors, _ = parse_entries(answer_text)
    current, problem = current_entries(app_dir)
    if errors or problem:
        raise ValueError((errors or [problem])[0])
    return _dump(merge(current, entries))


# --------------------------------------------------------------------------- menu.json

def _menu_roots(value):
    """The root nodes of a menu.json value -- a list, one object (the emitter reads it as a single root), or {"menu": [...]} --
    or None when it is none of those."""
    if isinstance(value, dict) and isinstance(value.get("menu"), list) and "label" not in value:
        return value["menu"]
    if isinstance(value, list):
        return value
    return [value] if isinstance(value, dict) else None


def _effective_kind(node: dict) -> str:
    """The kind the emitter gives a node: its own, else GROUP when it has children, else BUSINESS."""
    kind = node.get("kind")
    children = node.get("children")
    return str(kind).upper() if isinstance(kind, str) and kind.strip() else ("GROUP" if isinstance(children, list) and children else "BUSINESS")


def _identity(node: dict) -> tuple:
    """How CompanionPagesMenuEmitter keys a node: kind + target, or kind + label for a header with no target."""
    kind = _effective_kind(node)
    target = node.get("target")
    target = target.strip() if isinstance(target, str) else ""
    if kind == "PAGE":
        target = _normal_path(target)[0] or ""
    label = node.get("label")
    return kind, target or (label.strip() if isinstance(label, str) else "")


def _children(node: dict) -> list:
    children = node.get("children") if isinstance(node, dict) else None
    return [c for c in children if isinstance(c, dict)] if isinstance(children, list) else []


def _walk(nodes):
    for node in nodes:
        if isinstance(node, dict):
            yield node
            yield from _walk(_children(node))


def _page_targets(nodes) -> set:
    return {_normal_path(n.get("target"))[0] for n in _walk(nodes) if _effective_kind(n) == "PAGE"} - {None}


def _menu_node(raw, where: str, depth: int, policy: dict, state: dict):
    """One menu node, checked and normalised (kind upper-case, a PAGE target relative to web/); problems go to `state`."""
    menu = policy["menu"]
    errors, findings = state["errors"], state["findings"]
    if not isinstance(raw, dict):
        errors.append(f'{where} is not an object - use {{"label": "Orders", "kind": "PAGE", "target": "orders.html"}}')
        return None
    state["nodes"] += 1
    if state["nodes"] == menu["maxNodes"] + 1:
        errors.append(f'menu.json has more than {menu["maxNodes"]} nodes - a menu that big needs splitting')
    if depth == menu["maxDepth"] + 1:
        errors.append(f'{where} is nested more than {menu["maxDepth"]} levels deep')
    node = dict(raw)
    ignored = sorted(k for k in raw if k not in menu["nodeKeys"])
    if ignored:
        findings.append({"code": "menu-key-ignored", "severity": "warning", "detail": {"node": where, "keys": ignored[:5]}})
    label = raw.get("label")
    if not (isinstance(label, str) and label.strip()):
        errors.append(f'{where} needs a "label" (the words shown in the menu)')
    else:
        node["label"] = label.strip()
        if len(node["label"]) > policy["maxLabelChars"]:
            errors.append(f'{where}: the label is longer than {policy["maxLabelChars"]} characters')
    children = raw.get("children")
    if children is not None and not isinstance(children, list):
        errors.append(f'{where}: "children" must be a list of menu nodes')
        children = None
    if isinstance(children, list) and depth <= menu["maxDepth"]:
        node["children"] = [c for i, item in enumerate(children)
                            if (c := _menu_node(item, f"{where} > child {i}", depth + 1, policy, state)) is not None]
    kind = raw.get("kind")
    if kind is not None:
        shown = kind.strip().upper() if isinstance(kind, str) else ""
        if shown == "INTERNAL":
            errors.append(f'{where}: the kind INTERNAL belongs to the platform - use one of {", ".join(menu["kinds"])}')
        elif shown not in menu["kinds"]:
            errors.append(f'{where}: "{kind}" is not a menu kind - use one of {", ".join(menu["kinds"])}')
        else:
            if shown != kind:
                findings.append({"code": "menu-normalized", "severity": "info", "detail": {"node": where, "what": "kind", "to": shown}})
            node["kind"] = shown
    effective = _effective_kind(node)
    target = raw.get("target")
    if target is not None and not isinstance(target, str):
        errors.append(f'{where}: "target" must be text')
    elif effective == "PAGE":
        path, rewritten = _normal_path(target)
        segments = (path or "").split("/")
        if path is None:
            errors.append(f'{where}: a PAGE node needs a "target" (the page file, relative to web/, like "orders.html")')
        elif path.startswith("/") or any(seg in ("", ".", "..") or ":" in seg for seg in segments) or not path.lower().endswith(HTML):
            errors.append(f'{where}: "{path}" must be a plain relative path of an html page under web/')
        else:
            if rewritten:
                findings.append({"code": "menu-normalized", "severity": "info", "detail": {"node": where, "what": "target", "to": path}})
            node["target"] = path
    elif effective == "BUSINESS" and not (isinstance(target, str) and target.strip()):
        errors.append(f'{where}: a BUSINESS node needs a "target" (the name of a concept or panel it opens) - '
                      'or give it "children" to make it a group header')
    elif isinstance(target, str):
        node["target"] = target.strip()
    role = raw.get("requiredRole")
    if role is not None and not (isinstance(role, str) and role.strip()):
        errors.append(f"{where}: requiredRole must be a role name or null")
    ordinal = raw.get("ordinal")
    if ordinal is not None and (isinstance(ordinal, bool) or not isinstance(ordinal, int) or ordinal < 0):
        errors.append(f"{where}: ordinal must be a whole number, 0 or more")
    return node


def parse_menu(text: str, policy: dict | None = None) -> tuple:
    """(root nodes normalised, errors, findings) of the AI's menu.json. Never raises."""
    policy = policy or load_policy()
    try:
        roots = _menu_roots(json.loads(text))
    except (ValueError, RecursionError) as exc:
        return [], [f"menu.json is not valid JSON: {str(exc)[:120]}"], []
    if roots is None:
        return [], ['menu.json must be a menu node or a list of them, like [{"label": "Orders", "kind": "PAGE", "target": "orders.html"}]'], []
    state = {"errors": [], "findings": [], "nodes": 0}
    out = [n for i, raw in enumerate(roots) if (n := _menu_node(raw, f"menu.json entry {i}", 1, policy, state)) is not None]
    return out, state["errors"], state["findings"]


def current_menu(app_dir) -> tuple:
    """(the app's menu.json root nodes exactly as they are, None) -- or ([], a sentence) when the file is not a menu."""
    if not app_dir:
        return [], None
    path = Path(app_dir) / MENU_FILE
    if not path.is_file():
        return [], None
    try:
        roots = _menu_roots(json.loads(path.read_text(encoding="utf-8")))
    except (OSError, ValueError, RecursionError):
        roots = None
    if roots is None or not all(isinstance(r, dict) for r in roots):
        return [], ("the menu.json already in the app is not a menu, so it cannot be added to - "
                    "fix or remove it before sending a menu")
    return list(roots), None


def merge_menu(current: list, new_nodes: list) -> list:
    """The existing nodes kept in order; a node with the same identity is updated in place (its keys, the AI's winning; the
    keys the AI did not send kept; the children merged the same way); new nodes come last."""
    out = [dict(node) for node in current]
    for node in new_nodes:
        for index, old in enumerate(out):
            if _identity(old) == _identity(node):
                merged = {**old, **{k: v for k, v in node.items() if k != "children"}}
                if "children" in old or "children" in node:
                    merged["children"] = merge_menu(_children(old), _children(node))
                out[index] = merged
                break
        else:
            out.append(dict(node))
    return out


def merged_menu_text(app_dir, answer_text: str) -> str:
    """What menu.json must contain after this answer: the app's nodes plus the answer's. ValueError (plain words) when either
    file cannot be read."""
    roots, errors, _ = parse_menu(answer_text)
    current, problem = current_menu(app_dir)
    if errors or problem:
        raise ValueError((errors or [problem])[0])
    return _dump(merge_menu(current, roots))


def _model_targets(model) -> set | None:
    """The names a BUSINESS node may open (concepts and panels), or None when the model cannot say (a name may come from a
    fragment, a pack or an auto panel)."""
    if not isinstance(model, dict) or any(model.get(k) for k in ("packs", "fragments", "contexts", "autoPanels")):
        return None
    names: set = set()
    for key in ("concepts", "panels"):
        items = model.get(key) or []
        if not isinstance(items, list) or any(not (isinstance(i, dict) and isinstance(i.get("name"), str)) for i in items):
            return None
        names |= {i["name"] for i in items}
    return names or None


def _role_names(model) -> set | None:
    roles = model.get("roles") if isinstance(model, dict) else None
    if not isinstance(roles, list):
        return None
    return {r.get("name") if isinstance(r, dict) else r for r in roles}


def _hosts(text: str, patterns: list) -> list:
    found: set = set()
    for pattern in patterns:
        for match in pattern.finditer(text):
            found.add(match.group(1).lower()[:80])
    return sorted(found)[:5]


def _exists(app_dir, rel: str) -> bool:
    return bool(app_dir) and (Path(app_dir) / "web" / rel).is_file()


def check(answer_files: dict, model, app_dir=None) -> dict:
    """`answer_files` is {path: text} of the answer's files; only web/*, pages.json and menu.json are read. Returns {"errors": [lines for
    the AI], "findings": [{code, severity, detail}], "pages": [web paths the answer adds or replaces]}. Nothing is written."""
    policy = load_policy()
    flags = re.I | re.M
    patterns = [re.compile(p["regex"], flags) for p in policy["externalReferencePatterns"]]
    errors: list = []
    findings: list = []
    web = {path: text for path, text in answer_files.items() if is_web_path(path)}
    for path in sorted(web):
        problem = reserved_problem(path, policy)
        if problem:
            errors.append(problem)
            continue
        hosts = _hosts(web[path], patterns)
        if hosts:
            findings.append({"code": "page-external-reference", "severity": "warning", "detail": {"path": path, "hosts": hosts}})
        if path[len("web/"):] in policy["globalCssFiles"]:
            findings.append({"code": "page-css-global", "severity": "warning", "detail": {"path": path}})

    current, problem = current_entries(app_dir)
    listed = {_normal_path(item.get("path"))[0] for item in current}
    current_roots, menu_problem = current_menu(app_dir)
    listed |= _page_targets(current_roots)
    if MENU_FILE in answer_files:
        roots, errs, finds = parse_menu(answer_files[MENU_FILE], policy)
        errors += errs
        findings += finds
        if menu_problem:
            errors.append(menu_problem)
        roles, known = _role_names(model), _model_targets(model)
        for node in _walk(roots):
            kind, target = _effective_kind(node), node.get("target")
            if kind == "PAGE" and target and f"web/{target}" not in web and not _exists(app_dir, target):
                errors.append(f'menu.json links the page "{target}" but there is no web/{target} - send that page too, or take the node out')
            if kind == "BUSINESS" and target and known is not None and target not in known:
                findings.append({"code": "menu-target-unknown", "severity": "warning", "detail": {"target": target}})
            if roles is not None and node.get("requiredRole") and node["requiredRole"] not in roles:
                findings.append({"code": "menu-role-unknown", "severity": "warning",
                                 "detail": {"node": node.get("label"), "role": node["requiredRole"]}})
        listed |= _page_targets(roots)
    if PAGES_FILE in answer_files:
        entries, errs, finds = parse_entries(answer_files[PAGES_FILE], policy)
        errors += errs
        findings += finds
        if problem:
            errors.append(problem)
        roles = _role_names(model)
        for entry in entries:
            rel = entry["path"]
            if f"web/{rel}" not in web and not _exists(app_dir, rel):
                errors.append(f'pages.json lists "{rel}" but there is no web/{rel} - send that page too, or take the entry out')
            if roles is not None and entry.get("requiredRole") and entry["requiredRole"] not in roles:
                findings.append({"code": "page-role-unknown", "severity": "warning",
                                 "detail": {"path": rel, "role": entry["requiredRole"]}})
        listed |= {e["path"] for e in entries}
    for path in sorted(web):
        rel = path[len("web/"):]
        replaced = _exists(app_dir, rel)
        findings.append({"code": "page-replaced" if replaced else "page-added", "severity": "info", "detail": {"path": path}})
        if path.lower().endswith(HTML) and not replaced and rel not in listed:
            findings.append({"code": "page-not-in-menu", "severity": "warning", "detail": {"path": path}})
    return {"errors": errors, "findings": findings, "pages": sorted(web)}


def _same_text(a: bytes, b: bytes) -> bool:
    return a.replace(b"\r\n", b"\n") == b.replace(b"\r\n", b"\n")


def changed(app_dir, answer_files: dict) -> list:
    """The answer's page files that differ from the app's now: a web file that is new or has other bytes (line endings
    aside), or a pages.json whose merge would change the app's. A page-only answer leaves model.json identical and is still
    a change."""
    out: list = []
    for path, text in sorted((answer_files or {}).items()):
        if is_web_path(path):
            try:
                if not _same_text((Path(app_dir) / path).read_bytes(), text.encode("utf-8")):
                    out.append(path)
            except OSError:
                out.append(path)
        elif is_pages_file(path):
            try:
                current, _ = current_entries(app_dir)
                if merge(current, parse_entries(text)[0]) != current:
                    out.append(path)
            except (ValueError, OSError):
                out.append(path)
        elif is_menu_file(path):
            try:
                current, _ = current_menu(app_dir)
                if merge_menu(current, parse_menu(text)[0]) != current:
                    out.append(path)
            except (ValueError, OSError):
                out.append(path)
    return out


def prepare(root, extra: dict) -> dict:
    """The files to write: `extra` with pages.json and menu.json each replaced by its merge with the app's CURRENT file (the
    one on disk now, not the one the answer was checked against), or left out when the merge changes nothing. ValueError when
    one cannot be merged; nothing has been written yet."""
    out = dict(extra)
    for name, current_of, merged_of in ((PAGES_FILE, current_entries, merged_text), (MENU_FILE, current_menu, merged_menu_text)):
        if name not in extra:
            continue
        current, _ = current_of(root)
        text = merged_of(root, extra[name])
        if json.loads(text) != current:
            out[name] = text
        else:
            del out[name]
    return out
