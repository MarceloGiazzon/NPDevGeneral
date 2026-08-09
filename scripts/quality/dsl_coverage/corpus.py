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
    NPDevSamples/**/Output/ must never enter the tracked corpus; see that function's own docstring).

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
            if "Output" in p.relative_to(samples_root).parts:
                continue
            rel = p.relative_to(samples_root).parts
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
    """S4 (roadmap B27): every detector above only ever saw the ROOT model.json's own top-level
    arrays -- a feature declared exclusively inside a contexts[] fragment file (like S4's own
    groupBy-join corpus witness, which lives in dsl-conformance-max's shipping.json context, not
    its root model.json) was invisible to this whole gate, silently. Not full resolution (no $ref
    composition, no contextName:: qualification, no pack merging) -- just enough of a shallow
    array-union to make feature DETECTION see contexts[]-declared content, the same gap class this
    gate's own history is full of (checks that only look at the root document)."""
    contexts = model.get("contexts")
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
