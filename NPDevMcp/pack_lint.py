"""Static lint for mistakes in model/pack JSON that the schema and semantic validators cannot see.

Pure functions over a parsed model (or wrapped pack) -- no I/O. The validation service appends these
diagnostics to the validator's own report, so an AI authoring a pack through the hosted validator is
told about them in the same answer. Every rule exists because a delivered pack really had the defect
(browser-AI wave 1, 2026-11-12); `docs/PACK_DESIGN.md` states the rules in prose.

Codes:
  LINT_OWNER_WRITE_NO_BLANK  (error)   own-record write rule refuses a create that leaves the owner blank
  LINT_CHILD_LESS_RESTRICTED (warning) child readable by roles that cannot read its row-restricted parent
  LINT_DERIVED_OPTIONAL      (warning) derivedExpression over an optional number
  LINT_UNENFORCED_PROMISE    (warning) summary promises isolation/immutability with nothing enforcing it
  LINT_NON_ENGLISH           (warning) non-ASCII text (everything in a model is English)
"""

from __future__ import annotations

import re
from typing import Any

_OWNER_WRITE = re.compile(r"&&\s*(\w+)\s*==\s*\$user\.id")
_ROW_RESTRICTED = re.compile(r"&&\s*\w+\s*==\s*(\$user\.id|false|true)")
_ROW_FILTER = re.compile(r"&&\s*\w+\s*==")
_IDENT = re.compile(r"[A-Za-z_]\w*")
_PROMISE = re.compile(r"separate|isolated|segregat|immutable|append-only|cannot be (edited|deleted)", re.I)
_NUMERIC = ("decimal", "integer")


def _diag(severity: str, code: str, path: str, message: str, fix: str) -> dict:
    return {"severity": severity, "code": code, "path": path, "message": message, "suggestedFix": fix,
            "layer": "lint"}


def _concepts(model: dict) -> "list[dict]":
    raw = model.get("concepts")
    return [c for c in raw if isinstance(c, dict) and isinstance(c.get("name"), str)] if isinstance(raw, list) else []


def _fields(concept: dict) -> "list[dict]":
    raw = concept.get("fields")
    return [f for f in raw if isinstance(f, dict) and isinstance(f.get("name"), str)] if isinstance(raw, list) else []


def _access(concept: dict, key: str) -> str:
    acc = concept.get("access")
    value = acc.get(key) if isinstance(acc, dict) else None
    return value if isinstance(value, str) else ""


def _owner_write(concept: dict, out: list) -> None:
    write = _access(concept, "write")
    for fld in sorted(set(_OWNER_WRITE.findall(write))):
        if re.search(rf"\b{re.escape(fld)}\s*==\s*null", write):
            continue
        out.append(_diag(
            "error", "LINT_OWNER_WRITE_NO_BLANK", f"/concepts/{concept['name']}/access/write",
            f"{concept['name']}.access.write requires '{fld} == $user.id' but has no '{fld} == null' alternative. "
            f"The row check runs before field defaults are applied, so a create that leaves {fld} blank is refused "
            f"even when {fld} has defaultExpression $user.id.",
            f"Add \"|| $user.roles.contains(role('<role>')) && {fld} == null\" to the write rule (see the leave pack)."))


def _children(model: dict, concept: dict, out: list) -> None:
    if not _ROW_RESTRICTED.search(_access(concept, "read")):
        return
    for child in _concepts(model):
        for fld in _fields(child):
            ref = fld.get("reference")
            if fld.get("type") != "reference" or not isinstance(ref, dict) or ref.get("target") != concept["name"]:
                continue
            if _ROW_FILTER.search(_access(child, "read")):
                continue
            out.append(_diag(
                "warning", "LINT_CHILD_LESS_RESTRICTED", f"/concepts/{child['name']}/access/read",
                f"{child['name']} points to {concept['name']}, whose read rule restricts rows, but {child['name']} has no row "
                f"restriction of its own: anyone who may read {child['name']} sees children of rows they cannot see.",
                f"Copy the restricting field (owner login or flag) onto {child['name']} and use it in its access.read, "
                f"or state the limit in metadata.reviewNotes."))


def _derived(concept: dict, out: list) -> None:
    fields = {f["name"]: f for f in _fields(concept)}
    for fld in fields.values():
        expr = fld.get("derivedExpression")
        if not isinstance(expr, str):
            continue
        for name in dict.fromkeys(_IDENT.findall(expr)):
            used = fields.get(name)
            if used and not used.get("required") and used.get("type") in _NUMERIC and "default" not in used \
                    and "defaultExpression" not in used:
                out.append(_diag(
                    "warning", "LINT_DERIVED_OPTIONAL", f"/concepts/{concept['name']}/fields/{fld['name']}",
                    f"{concept['name']}.{fld['name']} derivedExpression uses optional number '{name}': an empty value makes "
                    f"the result null or an error.",
                    f"Make '{name}' required with default 0, or guard the expression."))


def _promise(model: dict, out: list) -> None:
    meta = model.get("metadata")
    summary = meta.get("summary") if isinstance(meta, dict) else None
    if not isinstance(summary, str):
        return
    for sentence in summary.split(". "):
        if _PROMISE.search(sentence):
            out.append(_diag(
                "warning", "LINT_UNENFORCED_PROMISE", "/metadata/summary",
                f"The summary claims '{sentence[:80]}'. Make sure an access rule, invariant or terminal lifecycle state "
                f"enforces it; a reviewNotes sentence does not.",
                "Enforce it in the model, or reword the summary to say what the model really does."))
            return


def _language(model: Any, out: list) -> None:
    def walk(value: Any, path: str) -> bool:
        if isinstance(value, str):
            for ch in value:
                if ord(ch) > 127:
                    out.append(_diag(
                        "warning", "LINT_NON_ENGLISH", path or "/",
                        f"Non-ASCII character U+{ord(ch):04X} in '{value[:60]}'. Everything in a model is English and unaccented.",
                        "Translate it (for example 'correction letter (CC-e)', 'refund')."))
                    return True
        elif isinstance(value, dict):
            for key, item in value.items():
                if walk(item, f"{path}/{key}"):
                    return True
        elif isinstance(value, list):
            for index, item in enumerate(value):
                if walk(item, f"{path}/{index}"):
                    return True
        return False

    walk(model, "")


def lint_model(model: Any) -> "list[dict]":
    """All lint diagnostics for one parsed model, in a stable order. Never raises on odd shapes."""
    if not isinstance(model, dict):
        return []
    out: "list[dict]" = []
    for concept in _concepts(model):
        _owner_write(concept, out)
        _children(model, concept, out)
        _derived(concept, out)
    _promise(model, out)
    _language(model, out)
    return out


def merge_into_report(report: dict, diagnostics: "list[dict]") -> dict:
    """Return a copy of a validation report with the lint diagnostics appended and the summary/status recomputed."""
    if not diagnostics:
        return report
    merged = dict(report)
    merged["diagnostics"] = [d for d in (report.get("diagnostics") or []) if isinstance(d, dict)] + diagnostics
    summary = dict(report.get("summary") if isinstance(report.get("summary"), dict) else {})
    summary["errors"] = int(summary.get("errors", 0) or 0) + sum(1 for d in diagnostics if d["severity"] == "error")
    summary["warnings"] = int(summary.get("warnings", 0) or 0) + sum(1 for d in diagnostics if d["severity"] == "warning")
    merged["summary"] = summary
    merged["status"] = "failed" if summary["errors"] else ("warning" if summary["warnings"] else "passed")
    return merged
