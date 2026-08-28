"""C3b (Cold Clone Audit): Python ports of the six PowerShell page generators
(scripts/appgen/New-*Page.ps1) that `npdev generate app`/`npdev dev` shell out to via
`npdev_cli._emit_static_pages`. Without these, a Linux/macOS machine with no PowerShell installed
got an app whose info.html linked six pages that 404 -- C3a made that warning honest; this closes
the gap it was honest ABOUT.

Each PS1 script has the same shape: gather some data (file reads, JSON building), embed it into a
static HTML+CSS+JS template (all client-side rendering -- the template performs no server-side data
substitution beyond a handful of `__TOKEN__` placeholders), write the file. The templates below were
extracted BYTE-FOR-BYTE from each script's PowerShell here-string (the `$tpl = @'...'@` block) into
`static_page_templates/*.html.tpl`, so this module owns only the thin data-gathering layer that
differs between the two -- never a hand-retyped copy of thousands of lines of markup/JS that could
silently diverge from what PowerShell emits.

Where a template needs live data (`app-tree.json`, `app-tree-v2.json`, `app-files.json`,
`verification.json`), this module writes that sibling JSON file exactly as the PS1 script does --
the template's own JS fetches it at page-load time, so nothing in the HTML itself needs to change
between engines.

Verified against the real PowerShell scripts (2026-08-28, ran both against the same fixture app):
every emitted `.html` is content-identical modulo line-ending style, which is not a portability bug
here -- `[Environment]::NewLine` inside a PowerShell here-string is itself platform-dependent
(`\r\n` on Windows, `\n` on a Linux/macOS `pwsh`), so "matches Windows PowerShell's CRLF" was never
the right bar for a POSIX-machine target. `newline=""` is used on every `write_text`/`read_text`
call that touches a template or a SOURCE file's own content specifically to opt OUT of Python's
platform-default LF<->CRLF translation, so this module's output is deterministic across platforms
instead of silently varying with whatever OS runs `npdev generate app`. One genuine (favorable)
divergence found in the same verification: PowerShell's ConvertFrom-Json/ConvertTo-Json round-trip
collapses a single-element JSON array into a bare scalar/object (a real, long-standing PowerShell
quirk); this module's `json` round-trip does not have that bug, so a `Concept.invariants` array of
length 1 in model.json is emitted as an array here, faithfully, rather than unwrapped.
"""

from __future__ import annotations

import json
import re
from datetime import datetime, timezone
from pathlib import Path

_TEMPLATES_DIR = Path(__file__).resolve().parent / "static_page_templates"

_TEXT_EXTENSIONS = {
    ".json", ".java", ".kt", ".groovy", ".js", ".ts", ".mjs", ".css", ".html", ".htm",
    ".md", ".txt", ".yml", ".yaml", ".xml", ".properties", ".sql", ".csv",
}
_EXCLUDED_DIR_NAMES = {"build", "target", "node_modules", ".git", "bin", "obj"}


def _load_template(name: str) -> str:
    return (_TEMPLATES_DIR / name).read_text(encoding="utf-8")


def _read_text_no_translation(path: Path, errors: str | None = None) -> str:
    """Read UTF-8 text without Python's universal-newline translation (LF<->CRLF stays whatever
    is actually on disk) -- the same intent as ``read_text(newline="")``, but that keyword was
    only added to ``Path.read_text`` in Python 3.13 (``write_text`` has had it since 3.10). CI runs
    3.12, so a bare ``read_text(newline="")`` call raises TypeError there while passing on a 3.13+
    dev machine. ``Path.open`` has accepted ``newline`` since Python 3.6.
    """
    with path.open("r", encoding="utf-8", errors=errors, newline="") as f:
        return f.read()


def _read_json_file(path: Path):
    if not path.is_file():
        raise FileNotFoundError(f"JSON file not found: {path}")
    return json.loads(path.read_text(encoding="utf-8"))


def _resolve_refs(node, base_dir: Path):
    """POSIX twin of Resolve-Refs: a `{"$ref": "..."}` node is replaced by the loaded file (itself
    recursively resolved); sibling keys on the $ref node are preserved unless the loaded file
    already declares them. `$schema` is dropped, matching the PowerShell original."""
    if node is None or isinstance(node, (str, bool, int, float)):
        return node
    if isinstance(node, dict):
        ref = node.get("$ref")
        if isinstance(ref, str) and ref:
            ref_path = (base_dir / ref.replace("\\", "/")).resolve()
            if ref_path.is_file():
                loaded = _read_json_file(ref_path)
                resolved = _resolve_refs(loaded, ref_path.parent)
                if not isinstance(resolved, dict):
                    resolved = {"value": resolved}
                for key, value in node.items():
                    if key != "$ref" and key not in resolved:
                        resolved[key] = value
                return resolved
        out = {}
        for key, value in node.items():
            if key == "$schema":
                continue
            out[key] = _resolve_refs(value, base_dir)
        return out
    if isinstance(node, list):
        return [_resolve_refs(item, base_dir) for item in node]
    return node


def _load_optional_json(definition_dir: Path, name: str):
    path = definition_dir / name
    if path.is_file():
        return _resolve_refs(_read_json_file(path), definition_dir)
    return None


def _detect_definition_dir(app_folder: Path) -> Path:
    """AppGen apps keep model.json/config.json/etc. under a `definition/` subfolder; a `npdev
    init`/`npdev dev`-created app keeps them flat at the app root. Prefer `definition/model.json`
    when it exists, matching New-AppTreePage.ps1's own auto-detection."""
    candidate = app_folder / "definition"
    if (candidate / "model.json").is_file():
        return candidate
    return app_folder


def _resolve_app_id(app_id: str, config: dict | None, model: dict | None, app_folder: Path) -> str:
    if app_id:
        return app_id
    scenario_name = ((config or {}).get("scenario") or {}).get("name") if config else None
    if scenario_name:
        return str(scenario_name)
    model_name = (model or {}).get("model")
    if model_name:
        return str(model_name)
    return app_folder.name


def _now_local_iso() -> str:
    return datetime.now().astimezone().isoformat()


def _now_utc_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def _iter_files(root: Path, extensions: set[str] | None = None, exclude_dir_names: set[str] | None = None):
    if not root.is_dir():
        return
    for path in sorted(root.rglob("*")):
        if not path.is_file():
            continue
        if extensions is not None and path.suffix.lower() not in extensions:
            continue
        if exclude_dir_names and any(part in exclude_dir_names for part in path.relative_to(root).parts[:-1]):
            continue
        yield path


# ---------------------------------------------------------------------------------------------
# control-panel.html
# ---------------------------------------------------------------------------------------------

def emit_control_panel_page(static_dir: Path, app_id: str, port: int, out_root: Path) -> Path:
    static_dir.mkdir(parents=True, exist_ok=True)
    base = f"http://localhost:{port}"
    key_file_path = str(out_root / "_ops" / "SUPER_USER_KEY.txt")
    html = (_load_template("control-panel.html.tpl")
            .replace("__APP__", app_id)
            .replace("__BASE__", base)
            .replace("__KEYFILEPATH__", key_file_path))
    dest = static_dir / "control-panel.html"
    dest.write_text(html, encoding="utf-8", newline="")
    (out_root / "control-panel.html").write_text(html, encoding="utf-8", newline="")
    return dest


# ---------------------------------------------------------------------------------------------
# agent-prompter.html / properties.html -- static templates, __APP__ only, no data-gathering
# ---------------------------------------------------------------------------------------------

def emit_agent_prompter_page(static_dir: Path, app_id: str = "") -> Path:
    static_dir.mkdir(parents=True, exist_ok=True)
    html = _load_template("agent-prompter.html.tpl").replace("__APP__", app_id)
    dest = static_dir / "agent-prompter.html"
    dest.write_text(html, encoding="utf-8", newline="")
    return dest


def emit_properties_admin_page(static_dir: Path, app_id: str = "") -> Path:
    static_dir.mkdir(parents=True, exist_ok=True)
    html = _load_template("properties.html.tpl").replace("__APP__", app_id)
    dest = static_dir / "properties.html"
    dest.write_text(html, encoding="utf-8", newline="")
    return dest


# ---------------------------------------------------------------------------------------------
# app-tree.html + app-tree.json (+ app-files.json)
# ---------------------------------------------------------------------------------------------

def _gather_code_sources(definition_dir: Path, app_folder: Path):
    cap_sources: dict[str, str] = {}
    cap_dir = definition_dir / "capabilities"
    if cap_dir.is_dir():
        for plugin_dir in sorted(p for p in cap_dir.iterdir() if p.is_dir()):
            plugin_path = plugin_dir / "capability.plugin.json"
            if not plugin_path.is_file():
                continue
            plugin = _read_json_file(plugin_path)
            src_root_rel = ((plugin.get("implementation") or {}).get("sourceRoot"))
            if not src_root_rel:
                continue
            src_root = definition_dir / str(src_root_rel).replace("\\", "/")
            if not src_root.is_dir():
                continue
            for src_file in _iter_files(src_root, {".java", ".kt", ".groovy"}):
                key = f"{plugin.get('capability')} / {src_file.name}"
                cap_sources[key] = _read_text_no_translation(src_file)

    widget_sources: dict[str, str] = {}
    widgets_dir = definition_dir / "widgets"
    if widgets_dir.is_dir():
        for src_file in _iter_files(widgets_dir, {".js", ".ts", ".css"}):
            if src_file.parent != widgets_dir:
                continue
            widget_sources[src_file.name] = _read_text_no_translation(src_file)

    web_sources: dict[str, str] = {}
    web_dir = app_folder / "web"
    if web_dir.is_dir():
        for src_file in _iter_files(web_dir, {".html", ".htm", ".css", ".js"}):
            rel = src_file.relative_to(web_dir).as_posix()
            web_sources[rel] = _read_text_no_translation(src_file)

    return cap_sources, widget_sources, web_sources


def _gather_project_files(app_folder: Path) -> list[dict]:
    files = []
    for path in _iter_files(app_folder, _TEXT_EXTENSIONS, _EXCLUDED_DIR_NAMES):
        rel = path.relative_to(app_folder).as_posix()
        files.append({"path": rel, "content": _read_text_no_translation(path, errors="replace")})
    return files


def emit_app_tree_page(app_folder: Path, static_dir: Path, app_id: str = "") -> Path:
    definition_dir = _detect_definition_dir(app_folder)
    model_path = definition_dir / "model.json"
    config_path = definition_dir / "config.json"
    if not model_path.is_file():
        raise FileNotFoundError(f"Required path not found: {model_path}")
    model = _read_json_file(model_path)
    config = _read_json_file(config_path) if config_path.is_file() else None
    app_id = _resolve_app_id(app_id, config, model, app_folder)
    resolved_model = _resolve_refs(model, definition_dir)

    cap_sources, widget_sources, web_sources = _gather_code_sources(definition_dir, app_folder)

    sections: dict[str, object] = {}
    if config is not None:
        sections["Config"] = config
    sections["Model"] = resolved_model
    pages = _load_optional_json(definition_dir, "pages.json")
    if pages is not None:
        sections["Pages"] = pages
    menu = _load_optional_json(definition_dir, "menu.json")
    if menu is not None:
        sections["Menu"] = menu
    db = _load_optional_json(definition_dir, "db.definition.json")
    if db is not None:
        sections["Database"] = db
    trusted_sources = _load_optional_json(definition_dir, "trusted-source-manifest.json")
    if trusted_sources is not None:
        sections["TrustedSources"] = trusted_sources

    seeds_dir = definition_dir / "seeds"
    if seeds_dir.is_dir():
        seed_obj = {p.name: _resolve_refs(_read_json_file(p), seeds_dir)
                    for p in _iter_files(seeds_dir, {".json"})}
        if seed_obj:
            sections["Seeds"] = seed_obj

    src_groups = {}
    if cap_sources:
        src_groups["Capabilities"] = cap_sources
    if widget_sources:
        src_groups["Widgets"] = widget_sources
    if web_sources:
        src_groups["Web Pages"] = web_sources
    if src_groups:
        sections["SourceCode"] = src_groups

    doc = {
        "schemaVersion": "npdev-app-tree.v2",
        "appId": app_id,
        "generatedAt": _now_local_iso(),
        "sections": sections,
    }

    static_dir.mkdir(parents=True, exist_ok=True)
    (static_dir / "app-tree.json").write_text(json.dumps(doc, indent=2), encoding="utf-8", newline="")

    files_doc = {
        "schemaVersion": "npdev-app-files.v1",
        "appId": app_id,
        "root": app_id,
        "generatedAt": _now_local_iso(),
        "files": _gather_project_files(app_folder),
    }
    (static_dir / "app-files.json").write_text(json.dumps(files_doc, indent=2), encoding="utf-8", newline="")

    html = _load_template("app-tree.html.tpl").replace("__APP__", app_id)
    dest = static_dir / "app-tree.html"
    dest.write_text(html, encoding="utf-8", newline="")
    return dest


# ---------------------------------------------------------------------------------------------
# app-tree-v2.html + app-tree-v2.json (+ app-files.json) -- the categorized (GeneXus-KB-style) tree
# ---------------------------------------------------------------------------------------------

_MODEL_KEY_TO_BUCKET = {
    "concepts": "Concepts", "domainTypes": "Concepts", "aggregates": "Concepts",
    "autoPanels": "Panels", "panels": "Panels", "selectors": "Panels",
    "documents": "Panels", "guidePages": "Panels",
    "flows": "Procedures", "procedures": "Procedures", "orchestrationRules": "Procedures",
    "orchestrations": "Procedures", "conversions": "Procedures",
    "packs": "Features", "fragments": "Features", "contexts": "Features", "provides": "Features",
    "roles": "Features", "propertyScopes": "Features", "properties": "Features",
    "capabilities": "Features", "customCapabilities": "Features", "bindings": "Features",
    "events": "Features", "queries": "Features", "ruleProfiles": "Features",
    "schemaVersion": "ProjectGeneral", "dslVersion": "ProjectGeneral", "namespace": "ProjectGeneral",
    "model": "ProjectGeneral", "version": "ProjectGeneral", "metadata": "ProjectGeneral",
    "settings": "ProjectGeneral", "externalAi": "ProjectGeneral",
}


def emit_app_tree_v2_page(app_folder: Path, static_dir: Path, app_id: str = "") -> Path:
    definition_dir = _detect_definition_dir(app_folder)
    model_path = definition_dir / "model.json"
    config_path = definition_dir / "config.json"
    if not model_path.is_file():
        raise FileNotFoundError(f"Required path not found: {model_path}")
    model = _read_json_file(model_path)
    config = _read_json_file(config_path) if config_path.is_file() else None
    app_id = _resolve_app_id(app_id, config, model, app_folder)
    resolved_model = _resolve_refs(model, definition_dir)
    if not isinstance(resolved_model, dict):
        resolved_model = {}

    cap_sources, widget_sources, web_sources = _gather_code_sources(definition_dir, app_folder)

    trusted_proc_src: dict[str, str] = {}
    trusted_panel_src: dict[str, str] = {}
    trusted_dir = definition_dir / "trusted-source"
    proc_dir = trusted_dir / "procedure"
    panel_dir = trusted_dir / "panel"
    if proc_dir.is_dir():
        for f in _iter_files(proc_dir, {".java"}):
            if f.parent == proc_dir:
                trusted_proc_src[f.name] = _read_text_no_translation(f)
    if panel_dir.is_dir():
        for f in _iter_files(panel_dir, {".html"}):
            if f.parent == panel_dir:
                trusted_panel_src[f.name] = _read_text_no_translation(f)

    pages = _load_optional_json(definition_dir, "pages.json")
    menu = _load_optional_json(definition_dir, "menu.json")
    db = _load_optional_json(definition_dir, "db.definition.json")
    trusted_sources = _load_optional_json(definition_dir, "trusted-source-manifest.json")
    smoke_plan = _load_optional_json(definition_dir, "smoke-plan.json")

    seed_obj: dict[str, object] = {}
    seeds_dir = definition_dir / "seeds"
    if seeds_dir.is_dir():
        for f in _iter_files(seeds_dir, {".json"}):
            seed_obj[f.name] = _resolve_refs(_read_json_file(f), seeds_dir)

    obj_concepts: dict[str, object] = {}
    obj_panels: dict[str, object] = {}
    obj_procs: dict[str, object] = {}
    features: dict[str, object] = {}
    proj_general: dict[str, object] = {}
    other_model: dict[str, object] = {}
    for key, value in resolved_model.items():
        bucket = _MODEL_KEY_TO_BUCKET.get(key)
        if bucket == "Concepts":
            obj_concepts[key] = value
        elif bucket == "Panels":
            obj_panels[key] = value
        elif bucket == "Procedures":
            obj_procs[key] = value
        elif bucket == "Features":
            features[key] = value
        elif bucket == "ProjectGeneral":
            proj_general[key] = value
        else:
            other_model[key] = value

    if menu is not None:
        obj_panels["Menu (navigation)"] = menu
    if pages is not None:
        obj_panels["Pages (companion screens)"] = pages
    if widget_sources:
        obj_panels["Widget source"] = widget_sources
    if web_sources:
        obj_panels["Web page source"] = web_sources
    if trusted_panel_src:
        obj_panels["Trusted panel source"] = trusted_panel_src

    if cap_sources:
        obj_procs["Capability source"] = cap_sources
    if trusted_proc_src:
        obj_procs["Trusted procedure source"] = trusted_proc_src

    objects: dict[str, object] = {}
    if obj_concepts:
        objects["Concepts"] = obj_concepts
    if obj_panels:
        objects["Panels"] = obj_panels
    if obj_procs:
        objects["Procedures"] = obj_procs

    proj_general_out: dict[str, object] = {}
    if config is not None:
        proj_general_out["App Config"] = config
    if proj_general:
        proj_general_out["Model Overview"] = proj_general
    configs: dict[str, object] = {}
    if proj_general_out:
        configs["Project General"] = proj_general_out
    if db is not None:
        configs["DB Engines"] = db

    tests: dict[str, object] = {}
    if trusted_sources is not None:
        tests["Checks"] = trusted_sources
    if smoke_plan is not None:
        tests["Scripts"] = smoke_plan
    if seed_obj:
        tests["Seed Data"] = seed_obj

    sections: dict[str, object] = {}
    if objects:
        sections["Objects"] = objects
    if features:
        sections["Features"] = features
    if configs:
        sections["Configs"] = configs
    if tests:
        sections["Tests"] = tests
    if other_model:
        sections["Other"] = other_model

    doc = {
        "schemaVersion": "npdev-app-tree.v3",
        "appId": app_id,
        "generatedAt": _now_local_iso(),
        "sections": sections,
    }

    static_dir.mkdir(parents=True, exist_ok=True)
    (static_dir / "app-tree-v2.json").write_text(json.dumps(doc, indent=2), encoding="utf-8", newline="")

    files_doc = {
        "schemaVersion": "npdev-app-files.v1",
        "appId": app_id,
        "root": app_id,
        "generatedAt": _now_local_iso(),
        "files": _gather_project_files(app_folder),
    }
    (static_dir / "app-files.json").write_text(json.dumps(files_doc, indent=2), encoding="utf-8", newline="")

    html = _load_template("app-tree-v2.html.tpl").replace("__APP__", app_id)
    dest = static_dir / "app-tree-v2.html"
    dest.write_text(html, encoding="utf-8", newline="")
    return dest


# ---------------------------------------------------------------------------------------------
# verification.html + verification.json
# ---------------------------------------------------------------------------------------------

def _to_verification_id(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9_-]", "-", value)


def _human_name(identifier: str) -> str:
    spaced = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", identifier)
    spaced = re.sub(r"[-_]", " ", spaced)
    spaced = re.sub(r"\s+", " ", spaced).strip()
    if not spaced:
        return identifier
    if spaced == spaced.lower():
        return spaced[:1].upper() + spaced[1:]
    return spaced


def emit_verification_panel_page(static_dir: Path, ops_dir: Path, app_id: str = "") -> Path:
    static_dir.mkdir(parents=True, exist_ok=True)
    items: list[dict] = []

    if ops_dir.is_dir():
        for script in sorted(ops_dir.glob("*.ps1")):
            item_id = _to_verification_id(script.stem)
            items.append({
                "id": item_id,
                "name": _human_name(script.stem),
                "description": (f"Emitted app operation {ops_dir}\\{script.name}; read-only here "
                                 "(re-run via the generated Run/Start/Stop scripts, never from this "
                                 "page)."),
                "category": "check-script",
                "tier": None,
                "command": script.name,
                "runnable": False,
                "maxStaleness": None,
                "lastRun": None,
            })

    runs: list[dict] = []
    runs_path = ops_dir / "exploration-runs" / "runs.jsonl"
    if runs_path.is_file():
        try:
            parsed = []
            for line in runs_path.read_text(encoding="utf-8").splitlines():
                if line.strip():
                    parsed.append(json.loads(line))
            runs = sorted(parsed, key=lambda r: r.get("startedAt") or "")
        except (json.JSONDecodeError, OSError):
            runs = []

    routines_dir = ops_dir / "explorations"
    if routines_dir.is_dir():
        for routine in sorted(routines_dir.glob("*.json")):
            item_id = _to_verification_id(routine.stem)
            routine_name = routine.name
            scenario = routine.stem
            matching = [
                run for run in runs
                if (str((run.get("definition") or {}).get("path") or "").replace("\\", "/").rsplit("/", 1)[-1]
                    == routine_name)
                or ((run.get("definition") or {}).get("scenarioName") == scenario)
            ]
            last_run = None
            if matching:
                run = matching[-1]
                status = run.get("status")
                result = status if status in ("passed", "failed", "running", "skipped", "cancelled") else "skipped"
                duration_ms = run.get("durationMs")
                last_run = {
                    "startedAt": run.get("startedAt") or _now_local_iso(),
                    "result": result,
                    "durationSeconds": round(duration_ms / 1000.0, 2) if duration_ms is not None else None,
                    "commit": None,
                    "reportPath": None,
                    "logPath": None,
                }
            items.append({
                "id": item_id,
                "name": _human_name(routine.stem),
                "description": (f"Browser routine {ops_dir}\\explorations\\{routine.name}; "
                                 "last-known result read from exploration-runs/runs.jsonl at emit "
                                 "time."),
                "category": "browser-routine",
                "tier": None,
                "command": None,
                "runnable": False,
                "maxStaleness": None,
                "lastRun": last_run,
            })

    document = {
        "schemaVersion": "npdev-verification-panel.v1",
        "generatedAt": _now_utc_iso(),
        "subject": {
            "kind": "generated-app",
            "name": app_id,
            "root": str(ops_dir.resolve()),
            "commit": None,
        },
        "items": items,
    }
    body = json.dumps(document, indent=2)
    (static_dir / "verification.json").write_text(body, encoding="utf-8", newline="")

    # Escape for embedding inside a <script> string in the HTML (a literal '</script>' inside JSON
    # would terminate the tag); also escape the JS line-separator characters JSON may contain.
    js_blob = body.replace("<", "\\u003c").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

    html = (_load_template("verification.html.tpl")
            .replace("__APP__", app_id)
            .replace("__BLOB__", js_blob))
    dest = static_dir / "verification.html"
    dest.write_text(html, encoding="utf-8", newline="")
    return dest
