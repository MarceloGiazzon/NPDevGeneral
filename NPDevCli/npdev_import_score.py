"""IMP-3: score an imported draft model against a hand-built oracle model.

`npdev import score --model <draft model.json> --oracle <hand-built model.json> [--out score.json]`

The oracle may keep its concepts in `{"$ref": "concepts/X.json"}` files next to it (WmsOffice does).
Matching is deliberately dumb and the numbers are reported as they come out -- the point of the
scorecard is an HONEST gap list, not a flattering one, so nothing here is tuned to the oracle:

* concepts match when their names agree after lower-casing, dropping non-alphanumerics and a trailing
  plural "s" (Portuguese "-ns" -> "-m": the oracle writes Armazem where the legacy table is Armazens);
* fields of matched concepts match "exactly" when their normalized names are equal, or "by affix" when one
  normalized name ends with the other (the importer keeps the legacy column prefix, `armNome`, where the
  hand-built model says `nome`); both tiers are reported separately;
* a reference counts as recovered when a field of the matched imported concept references the imported
  twin of the target concept the oracle's field references.
"""
from __future__ import annotations

import json
import re
import sys
import unicodedata
from pathlib import Path


def _norm(name: str) -> str:
    text = unicodedata.normalize("NFKD", name or "").encode("ascii", "ignore").decode("ascii")
    return re.sub(r"[^a-z0-9]", "", text.lower())


def _concept_key(name: str) -> str:
    key = _norm(name)
    if key.endswith("ns") and len(key) > 4:
        return key[:-2] + "m"          # Portuguese plural: armazens -> armazem, itens -> item
    return key[:-1] if key.endswith("s") and len(key) > 3 else key


def _load_concepts(model_path: Path) -> list[dict]:
    model = json.loads(model_path.read_text(encoding="utf-8-sig"))
    concepts = []
    for entry in model.get("concepts", []):
        if isinstance(entry, dict) and "$ref" in entry:
            concepts.append(json.loads((model_path.parent / entry["$ref"]).read_text(encoding="utf-8-sig")))
        else:
            concepts.append(entry)
    return concepts


def _fields(concept: dict) -> list[dict]:
    return [f for f in concept.get("fields", []) if f.get("name") not in ("id",)]


def _ref_target(field: dict) -> str | None:
    ref = field.get("reference")
    if isinstance(ref, dict):
        return ref.get("target")
    return ref if isinstance(ref, str) else None


def _affix_match(a: str, b: str) -> bool:
    return len(min(a, b, key=len)) >= 3 and (a.endswith(b) or b.endswith(a))


def _ratio(num: int, den: int) -> float | None:
    return round(num / den, 3) if den else None


def _enum_values(field: dict) -> set[str] | None:
    """Normalized value names of an enum field, or None when the field is not an enum."""
    if field.get("type") != "enum":
        return None
    raw = field.get("values") or field.get("enumValues") or []
    return {_norm(v.get("value", "") if isinstance(v, dict) else str(v)) for v in raw}


def _score_enums(matched: list[str], draft_by_key: dict, oracle_by_key: dict) -> dict:
    """Enum fields of matched concepts: found by field name (exact or affix), then how many of the oracle's
    values the draft's enum carries. A draft enum on a field the oracle does not make an enum is not counted
    as a hit, and the draft's total is reported beside it so over-production is visible."""
    oracle_total = found = values_exact = 0
    value_recall_sum = 0.0
    for key in matched:
        d_enums = {_norm(f["name"]): _enum_values(f) for f in _fields(draft_by_key[key]) if _enum_values(f) is not None}
        for field in _fields(oracle_by_key[key]):
            o_values = _enum_values(field)
            if o_values is None:
                continue
            oracle_total += 1
            name = _norm(field["name"])
            hit = name if name in d_enums else next((d for d in d_enums if _affix_match(name, d)), None)
            if hit is None:
                continue
            found += 1
            d_values = d_enums[hit]
            overlap = len(o_values & d_values)
            value_recall_sum += overlap / len(o_values) if o_values else 0.0
            values_exact += 1 if o_values == d_values else 0
    return {
        "oracleEnumFieldsInMatchedConcepts": oracle_total, "foundByFieldName": found,
        "recallByFieldName": _ratio(found, oracle_total),
        "valueSetsIdentical": values_exact,
        "meanValueRecallOfFound": round(value_recall_sum / found, 3) if found else None,
    }


def score(draft_path: Path, oracle_path: Path) -> dict:
    draft = _load_concepts(draft_path)
    oracle = _load_concepts(oracle_path)
    draft_by_key = {_concept_key(c["name"]): c for c in draft}
    oracle_by_key = {_concept_key(c["name"]): c for c in oracle}
    matched = sorted(set(draft_by_key) & set(oracle_by_key))
    twin = {oracle_by_key[k]["name"]: draft_by_key[k]["name"] for k in matched}

    exact_hit = affix_hit = 0
    oracle_fields = draft_fields = 0
    per_concept = []
    for key in matched:
        o_fields, d_fields = _fields(oracle_by_key[key]), _fields(draft_by_key[key])
        o_names = {_norm(f["name"]) for f in o_fields}
        d_names = {_norm(f["name"]) for f in d_fields}
        exact = o_names & d_names
        affix = {o for o in o_names - exact if any(_affix_match(o, d) for d in d_names - exact)}
        exact_hit += len(exact)
        affix_hit += len(affix)
        oracle_fields += len(o_names)
        draft_fields += len(d_names)
        per_concept.append({
            "oracle": oracle_by_key[key]["name"], "draft": draft_by_key[key]["name"],
            "oracleFields": len(o_names), "draftFields": len(d_names),
            "exact": len(exact), "byAffix": len(affix),
        })

    oracle_refs = recovered = 0
    for key in matched:
        d_ref_targets = {_ref_target(f) for f in _fields(draft_by_key[key]) if _ref_target(f)}
        for field in _fields(oracle_by_key[key]):
            target = _ref_target(field)
            if field.get("type") != "reference" or not target:
                continue
            oracle_refs += 1
            if target in twin and twin[target] in d_ref_targets:
                recovered += 1
    oracle_refs_total = sum(1 for c in oracle for f in _fields(c) if f.get("type") == "reference")
    oracle_enums = sum(1 for c in oracle for f in _fields(c) if f.get("type") == "enum" or f.get("enumValues"))
    draft_enums = sum(1 for c in draft for f in _fields(c) if f.get("type") == "enum" or f.get("enumValues"))

    return {
        "schemaVersion": "npdev-import-score.v1",
        "note": "Numbers as measured; the importer was not tuned to this oracle. Names differ in the oracle "
                "(hand-renamed fields, merged or dropped legacy tables), so low field numbers are partly a "
                "naming gap, not only a modelling gap.",
        "concepts": {
            "draft": len(draft), "oracle": len(oracle), "matched": len(matched),
            "precision": _ratio(len(matched), len(draft)), "recall": _ratio(len(matched), len(oracle)),
            "oracleOnly": sorted(oracle_by_key[k]["name"] for k in set(oracle_by_key) - set(draft_by_key)),
        },
        "fieldsOfMatchedConcepts": {
            "oracleFields": oracle_fields, "draftFields": draft_fields,
            "exactMatches": exact_hit, "affixMatches": affix_hit,
            "recallExact": _ratio(exact_hit, oracle_fields),
            "recallExactOrAffix": _ratio(exact_hit + affix_hit, oracle_fields),
            "precisionExactOrAffix": _ratio(exact_hit + affix_hit, draft_fields),
        },
        "references": {
            "oracleTotal": oracle_refs_total, "oracleBetweenMatchedConcepts": oracle_refs,
            "recovered": recovered, "recallAmongMatched": _ratio(recovered, oracle_refs),
        },
        "enums": {"oracle": oracle_enums, "draft": draft_enums, **_score_enums(matched, draft_by_key, oracle_by_key),
                  "note": "draft is the total the importer produced; the found/recall numbers count only enum fields "
                          "of matched concepts that the hand-built model also makes an enum."},
        "perConcept": per_concept,
    }


def run(args) -> int:
    result = score(Path(args.model), Path(args.oracle))
    text = json.dumps(result, indent=2) + "\n"
    if args.out:
        Path(args.out).write_text(text, encoding="utf-8")
    c, f, r = result["concepts"], result["fieldsOfMatchedConcepts"], result["references"]
    print(f"concepts: {c['matched']} matched of {c['draft']} drafted / {c['oracle']} in the oracle "
          f"(precision {c['precision']}, recall {c['recall']})")
    print(f"fields of matched concepts: recall {f['recallExact']} exact, {f['recallExactOrAffix']} exact-or-affix; "
          f"precision {f['precisionExactOrAffix']}")
    print(f"references: {r['recovered']} of {r['oracleBetweenMatchedConcepts']} between matched concepts "
          f"({r['oracleTotal']} in the oracle)")
    e = result["enums"]
    print(f"enums: oracle {e['oracle']}, draft {e['draft']}; of {e['oracleEnumFieldsInMatchedConcepts']} oracle enum fields "
          f"in matched concepts {e['foundByFieldName']} found by field name, {e['valueSetsIdentical']} with identical values, "
          f"mean value recall {e['meanValueRecallOfFound']}")
    if not args.out:
        print(text, end="")
    return 0


if __name__ == "__main__":
    sys.exit("run through `npdev import score`")
