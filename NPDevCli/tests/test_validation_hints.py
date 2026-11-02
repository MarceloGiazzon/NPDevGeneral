"""Validation hints (npdev_validation_hints): a 'did you mean' line beside raw JSON-Schema diagnostics,
driven only by NPDevCli/desire-path-aliases.json. Fixtures are the real diagnostics from bench runs.

Run with: python -m unittest NPDevCli.tests.test_validation_hints -v
"""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_validation_hints as vh  # noqa: E402


def _diag(code: str, path: str, message: str) -> dict:
    return {"code": code, "path": path, "message": f"{path}: {message}"}


EXTRA = "property 'source' is not defined in the schema and the schema does not allow additional properties"


class HintTests(unittest.TestCase):
    def test_aliased_extra_key(self):
        hint = vh.hint_for(_diag("json_schema_additionalproperties", "$.concepts[0].rollups[0]", EXTRA))
        self.assertIn("'source' is not a rollup key -- use 'from'", hint)
        self.assertIn('"fn": "sum"', hint)
        self.assertIn("via = the child's reference field", hint)

    def test_unknown_extra_key_suggests_closest_or_lists_keys(self):
        near = vh.hint_for(_diag("json_schema_additionalproperties", "$.concepts[0].rollups[0]",
                                 "property 'vias' is not defined in the schema"))
        self.assertIn("did you mean 'via'", near)
        far = vh.hint_for(_diag("json_schema_additionalproperties", "$.concepts[0].rollups[0]",
                                "property 'zzzzzz' is not defined in the schema"))
        self.assertIn("Keys: field, from, via, fn, of", far)

    def test_required_missing_names_key_and_renames_alias_from_model(self):
        diag = _diag("json_schema_required", "$.orchestrations[0].actions[0]", "required property 'map' not found")
        model = {"orchestrations": [{"actions": [{"type": "create", "data": {"title": "x"}}]}]}
        self.assertIn("rename 'data' to 'map'", vh.hint_for(diag, model))
        plain = vh.hint_for(diag)
        self.assertIn("required 'map' is missing", plain)
        self.assertNotIn("rename", plain)

    def test_enum_value_from_model_and_from_message(self):
        diag = _diag("json_schema_enum", "$.concepts[0].fields[0].type", "does not have a value in the enumeration [a, b]")
        model = {"concepts": [{"fields": [{"name": "t", "type": "text"}]}]}
        self.assertIn("'text' is not a field type -- use 'string'", vh.hint_for(diag, model))
        diag2 = dict(diag, message="value 'bool' does not have a value in the enumeration [a]")
        self.assertIn("use 'boolean'", vh.hint_for(diag2))
        self.assertIsNone(vh.hint_for(diag))  # no model, nothing in the message: nothing to say

    def test_no_match_gives_no_hint(self):
        self.assertIsNone(vh.hint_for(_diag("json_schema_required", "$.concepts[0]", "required property 'name' not found")))
        self.assertIsNone(vh.hint_for(_diag("some_semantic_code", "$.concepts[0].rollups[0]", "bad")))
        self.assertIsNone(vh.hint_for({}))

    def test_add_hints_dedupes_the_example_per_path(self):
        report = {"diagnostics": [
            _diag("json_schema_additionalproperties", "$.concepts[0].rollups[0]", EXTRA),
            _diag("json_schema_required", "$.concepts[0].rollups[0]", "required property 'from' not found"),
            _diag("json_schema_required", "$.concepts[0].rollups[0]", "required property 'from' not found"),
            _diag("json_schema_required", "$.concepts[1].rollups[0]", "required property 'from' not found"),
            _diag("other", "$.x", "m"),
        ]}
        vh.add_hints(report)
        d = report["diagnostics"]
        self.assertIn("Example:", d[0]["hint"])
        self.assertNotIn("Example:", d[1]["hint"])
        self.assertNotIn("hint", d[2])  # exact duplicate collapsed
        self.assertIn("Example:", d[3]["hint"])  # a different object shows its own example
        self.assertNotIn("hint", d[4])

    def test_aliases_come_only_from_the_json_table(self):
        table = {"objects": [{"id": "widget", "path": "$.widgets[]", "canonicalKeys": ["size"],
                              "keyAliases": {"big": "size"}}]}
        with mock.patch.object(vh, "_table", table["objects"]):
            hint = vh.hint_for(_diag("json_schema_additionalproperties", "$.widgets[2]", "property 'big' is not defined"))
            self.assertIn("'big' is not a widget key -- use 'size'", hint)
            # the real table's rollup alias is gone with the table: only the generic schema hint is left
            gone = vh.hint_for(_diag("json_schema_additionalproperties", "$.concepts[0].rollups[0]", EXTRA))
            self.assertNotIn("use 'from'", gone)
        source = Path(vh.__file__).read_text(encoding="utf-8")
        for alias in ("'source'", "'varchar'", "'data'"):
            self.assertNotIn(alias, source)
        self.assertTrue(json.loads(vh.ALIASES_FILE.read_text(encoding="utf-8"))["objects"])


class GenericHintTests(unittest.TestCase):
    def test_unaliased_key_lists_allowed_keys_and_nearest(self):
        hint = vh.hint_for(_diag("json_schema_additionalproperties", "$.concepts[0].lifecycle",
                                 "property 'statusFeld' is not defined in the schema"))
        self.assertIn("did you mean 'statusField'?", hint)
        self.assertIn("Allowed here: statusField, states, transitions", hint)

    def test_allowed_list_is_capped(self):
        hint = vh.hint_for(_diag("json_schema_additionalproperties", "$.concepts[0].fields[2]",
                                 "property 'zzzz' is not defined in the schema"))
        self.assertNotIn("did you mean", hint)
        self.assertTrue(hint.endswith(", ...."), hint[-40:])
        self.assertLessEqual(hint.count(","), vh.ALLOWED_CAP + 1)

    def test_table_entry_keeps_priority(self):
        hint = vh.hint_for(_diag("json_schema_additionalproperties", "$.concepts[0].fields[2]",
                                 "property 'computed' is not defined in the schema"))
        self.assertIn("use 'derivedExpression'", hint)
        self.assertNotIn("Allowed here", hint)

    def test_flow_step_shapes_come_from_the_table(self):
        hint = vh.hint_for(_diag("json_schema_additionalproperties", "$.flows[0].steps[0]",
                                 "property 'concept' is not defined in the schema"))
        self.assertIn("use 'scope'", hint)
        model = {"flows": [{"steps": [{"type": "saveConcept"}]}]}
        enum = _diag("json_schema_enum", "$.flows[0].steps[0].type", "does not have a value in the enumeration []")
        self.assertIn("use 'createConcept'", vh.hint_for(enum, model))

    def test_unknown_path_has_no_generic_hint(self):
        self.assertIsNone(vh.hint_for(_diag("json_schema_additionalproperties", "$.nope[0]", "property 'x' is not defined")))

    def test_enum_hint_and_message_are_capped(self):
        values = [f"value{i}" for i in range(30)]
        message = "does not have a value in the enumeration " + json.dumps(values)
        model = {"x": "value3x"}
        hint = vh.hint_for(_diag("json_schema_enum", "$.x", message), model)
        self.assertIn("did you mean", hint)
        self.assertTrue(hint.endswith("value11, ...."))
        short = vh.shorten_enum_message(message)
        self.assertIn("(30 values)", short)
        self.assertNotIn("value12", short)
        small = 'in the enumeration ["a", "b"]'
        self.assertEqual(vh.shorten_enum_message(small), small)


if __name__ == "__main__":
    unittest.main()
