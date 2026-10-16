"""NEW: NPDevMcp/tests/test_ai_contract_schemas.py

Pins the AI-facing custom-panel / custom-procedure schemas (served to AI authors by
npdev_get_schema, build_core_context.SCHEMAS and derive_constrained_schemas.DEFAULT_SCHEMAS) to
(a) the shipped scenario assets and (b) the DSL they must lower into. jsonschema is already a hard
requirement of scripts/quality/check-config-schema.py (installed: 4.26.0).
"""
from __future__ import annotations

import json
import unittest
from pathlib import Path

from jsonschema import Draft202012Validator

_REPO = Path(__file__).resolve().parents[2]
_AI = _REPO / "schemas" / "ai"
_SCENARIOS = _REPO / "NPDevSamples" / "ai-scenarios"
_FIXTURES = _REPO / "scripts" / "quality" / "fixtures" / "json-schema-validator"


def _load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def _errors(schema_name: str, instance: dict) -> list[str]:
    validator = Draft202012Validator(_load(_AI / f"{schema_name}.schema.json"))
    return [f"{list(e.path)}: {e.message}" for e in validator.iter_errors(instance)]


class AiContractSchemasTest(unittest.TestCase):
    def test_every_scenario_custom_panel_validates(self):
        found = sorted(_SCENARIOS.glob("*/custom-panel.json"))
        self.assertTrue(found, "expected at least create-users-panel-procedure/custom-panel.json")
        for path in found:
            self.assertEqual([], _errors("custom-panel", _load(path)), path.as_posix())

    def test_every_scenario_custom_procedure_validates(self):
        found = sorted(_SCENARIOS.glob("*/custom-procedure.json"))
        self.assertTrue(found)
        for path in found:
            self.assertEqual([], _errors("custom-procedure", _load(path)), path.as_posix())

    def test_custom_panel_layout_enum_equals_the_dsl_panel_layout_enum(self):
        dsl = _load(_REPO / "NPDevContract" / "schemas" / "model.schema.json")
        dsl_enum = dsl["$defs"]["panelLayout"]["properties"]["type"]["enum"]
        ai_enum = _load(_AI / "custom-panel.schema.json")["properties"]["layout"]["properties"]["type"]["enum"]
        self.assertEqual(sorted(dsl_enum), sorted(ai_enum))

    def test_unsafe_fixtures_still_rejected(self):
        self.assertTrue(_errors("custom-panel", _load(_FIXTURES / "custom-panel-unsafe-invalid.json")))
        self.assertTrue(_errors("custom-procedure", _load(_FIXTURES / "custom-procedure-unsafe-invalid.json")))


if __name__ == "__main__":
    unittest.main()
