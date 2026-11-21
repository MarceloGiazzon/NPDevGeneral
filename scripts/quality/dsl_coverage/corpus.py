"""Which models count as corpus, and how a model is assembled before detection runs."""
from __future__ import annotations

import json
from pathlib import Path

from .constants import ALLOWLIST_PATH

# storage/PROBE_APPS.md: probes are FIXTURES serving one storage conformance vector each --
# deliberately the fewest fields that work, with no panels, flows, roles or UI. They must NOT count
# as DSL coverage.
#
# The failure this prevents is subtle and one-directional. p4-constraints declares a unique field and
# an index because vectors I2/I3 need a realized schema to introspect. If that counted as coverage,
# this gate would report `unique` and `indexes` as exercised by the corpus -- and a change breaking
# them everywhere they are ACTUALLY used would pass, because a probe that only ever has its catalog
# read would not notice. A narrow fixture satisfying a breadth check is a breadth check that has
# stopped working.
PROBE_LABEL_PREFIX = "NPDevSamples/probes/"


def find_models(appgen_root: Path, samples_root: Path) -> list[tuple[str, Path]]:
    """Mirrors validate-corpus.py's own find_models() label convention exactly -- including its
    Output-dir exclusion (docs/CLOSEOUT_PLAN.md G2 aftermath: a generated model.json copy under
    NPDevSamples/**/Output/ must never enter the tracked corpus; see that function's own docstring)
    and its NPDevSamples/ai-scenarios/ exclusion (Wave 1.3 of NPDEV_FEATURE_PLAN_2026-09-24.md:
    several of those `model.json` fixtures are DELIBERATELY invalid, exercising the AI-authoring
    refusal path, not DSL corpus members).

    Storage probes are excluded here rather than in validate-corpus.py: they ARE corpus members (they
    must parse, and they carry a `probe` corpusRole), they simply are not evidence of DSL coverage."""
    models: list[tuple[str, Path]] = []
    if appgen_root.exists():
        for p in sorted(appgen_root.rglob("model.json")):
            if "Output" in p.relative_to(appgen_root).parts:
                continue
            rel = p.relative_to(appgen_root).parts
            app = "/".join(rel[:-2]) if len(rel) > 2 else rel[0]
            models.append((f"AppGen/apps/{app}", p))
    if samples_root.exists():
        for p in sorted(samples_root.rglob("model.json")):
            rel_parts = p.relative_to(samples_root).parts
            if "Output" in rel_parts or "ai-scenarios" in rel_parts:
                continue
            rel = rel_parts
            app = "/".join(rel[:-2]) if len(rel) > 2 else rel[0]
            label = f"NPDevSamples/{app}"
            if label.startswith(PROBE_LABEL_PREFIX):
                continue
            models.append((label, p))
    return models


def load_allowlist() -> dict:
    if not ALLOWLIST_PATH.exists():
        return {}
    return json.loads(ALLOWLIST_PATH.read_text(encoding="utf-8")).get("cleared", {})


def _merge_context_fragments(model: dict, base_dir: Path) -> dict:
    """The shallow view every detector reads: contexts[] arrays unioned in (``_union_context_fragments``), then FORM
    P5-T9e's derived ``aggregates`` view -- an aggregate is its root concept's ``composition`` now, so the detectors that
    were written against the old top-level ``aggregates[]`` keep reading ``{name, root, collections, ...}`` entries."""
    return _derive_admin_backoffice(_derive_screen_panels(_derive_admin_autopanels(
        _derive_aggregates(_union_context_fragments(model, base_dir), base_dir))))


def authoring_back_office(model: dict):
    """D-1 phase 4: the back office is the ``admin`` block of one experiences[] entry. The block the AUTHORING model
    declares, in the shape of the old root ``backOffice`` object (``{locale, strings, defaultRoute, navigation, pages}``),
    or None. A legacy root ``backOffice`` (fixtures only; the parser refuses the key) is returned as is."""
    experiences = model.get("experiences")
    if isinstance(experiences, list):
        for experience in experiences:
            if isinstance(experience, dict) and isinstance(experience.get("admin"), dict):
                return experience["admin"]
    legacy = model.get("backOffice")
    return legacy if isinstance(legacy, dict) else None


def _derive_admin_backoffice(model: dict) -> dict:
    """D-1 phase 4: the detectors written against the old root ``backOffice`` keep reading it from this view."""
    admin = authoring_back_office(model)
    if admin is None or model.get("backOffice") is admin:
        return model
    derived = dict(model)
    derived["backOffice"] = admin
    return derived


def _derive_admin_autopanels(model: dict) -> dict:
    """D-1 phase 1: a concept's generated back-office pages are a ``screens[]`` entry's ``admin`` block now, so the
    detectors written against the old top-level ``autoPanels[]`` keep reading ``{name, concept, <admin body>}`` entries."""
    screens = model.get("screens")
    if not isinstance(screens, list):
        return model
    lifted = []
    for screen in screens:
        if isinstance(screen, dict) and isinstance(screen.get("admin"), dict):
            lifted.append({"name": screen.get("name"), "concept": screen.get("concept"), **screen["admin"]})
    if not lifted:
        return model
    derived = dict(model)
    derived["autoPanels"] = list(model.get("autoPanels") or []) + lifted
    return derived


def authoring_panels(model: dict) -> list:
    """D-1 phase 3: a hand-written panel is a ``screens[]`` entry of kind ``custom`` carrying a ``panel`` block. Every
    panel the AUTHORING model declares, in the shape of the old top-level ``panels[]`` entries (``{name, route,
    dataSources, layout, actions, ...}``): a legacy root ``panels[]`` entry (fixtures only; the parser refuses the key)
    plus each ``screens[].panel`` lifted to ``{"name": <screen name>, **panel}``. The screen supplies ``name``."""
    panels = [p for p in model["panels"] if isinstance(p, dict)] if isinstance(model.get("panels"), list) else []
    screens = model.get("screens")
    if isinstance(screens, list):
        for screen in screens:
            if isinstance(screen, dict) and isinstance(screen.get("panel"), dict):
                panels.append({"name": screen.get("name"), **screen["panel"]})
    return panels


def _derive_screen_panels(model: dict) -> dict:
    """D-1 phase 3: a hand-written panel is a ``screens[].panel`` block now, so the detectors written against the old
    top-level ``panels[]`` keep reading ``{name, route, dataSources, layout, actions, ...}`` entries."""
    panels = authoring_panels(model)
    if not panels:
        return model
    derived = dict(model)
    derived["panels"] = panels
    return derived


def _derive_aggregates(model: dict, base_dir: Path) -> dict:
    concepts = model.get("concepts")
    if not isinstance(concepts, list):
        return model
    aggregates = []
    for entry in concepts:
        if isinstance(entry, dict) and set(entry) == {"$ref"} and isinstance(entry["$ref"], str):
            path = (base_dir / entry["$ref"]).resolve()
            try:
                entry = json.loads(path.read_text(encoding="utf-8-sig"))
            except (OSError, json.JSONDecodeError):
                continue
        if isinstance(entry, dict) and isinstance(entry.get("composition"), dict):
            aggregates.append({"name": entry.get("name"), "root": entry.get("name"), **entry["composition"]})
    if not aggregates:
        return model
    derived = dict(model)
    derived["aggregates"] = aggregates
    return derived


def _union_context_fragments(model: dict, base_dir: Path) -> dict:
    """S4 (roadmap B27): every detector above only ever saw the ROOT model.json's own top-level
    arrays -- a feature declared exclusively inside a contexts[] fragment file (like S4's own
    groupBy-join corpus witness, which lives in dsl-conformance-max's shipping.json context, not
    its root model.json) was invisible to this whole gate, silently. Not full resolution (no $ref
    composition, no contextName:: qualification, no pack merging) -- just enough of a shallow
    array-union to make feature DETECTION see contexts[]-declared content, the same gap class this
    gate's own history is full of (checks that only look at the root document)."""
    contexts = model.get("modules")
    if not isinstance(contexts, list):
        return model
    merged = dict(model)
    for entry in contexts:
        if not isinstance(entry, dict):
            continue
        ref = entry.get("$ref")
        if not isinstance(ref, str):
            continue
        fragment_path = (base_dir / ref).resolve()
        if not fragment_path.is_file():
            continue
        try:
            fragment = json.loads(fragment_path.read_text(encoding="utf-8-sig"))
        except json.JSONDecodeError:
            continue
        if not isinstance(fragment, dict):
            continue
        for key, value in fragment.items():
            if not isinstance(value, list):
                continue
            merged[key] = (merged.get(key) or []) + value
    return merged
