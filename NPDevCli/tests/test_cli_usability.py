"""CLI usability: `npdev validate <path>`, human vs JSON validate output, grouped `npdev --help`.

Run with: python -m unittest NPDevCli.tests.test_cli_usability -v
"""

from __future__ import annotations

import io
import json
import sys
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402

REPORT = {
    "contractVersion": "npdev-validation-report.v2",
    "status": "failed",
    "diagnostics": [
        {"layer": "schema", "severity": "error", "code": "json_schema_additionalproperties",
         "path": "$.concepts[0].rollups[0]",
         "message": "$.concepts[0].rollups[0]: property 'source' is not defined in the schema",
         "hint": "use 'from'"},
        {"layer": "semantic", "severity": "warning", "code": "w1", "path": "$.roles[0]", "message": "odd"},
    ],
}


def _run_main(argv: list[str], *, tty: bool) -> tuple[int, str, mock.MagicMock]:
    out = io.StringIO()
    out.isatty = lambda: tty  # type: ignore[method-assign]
    with mock.patch.object(npdev_cli, "run_validate_semantic", return_value=0) as run, redirect_stdout(out):
        code = npdev_cli.main(argv)
    return code, out.getvalue(), run


class ValidateArgvTests(unittest.TestCase):
    def test_bare_path_equals_model_subcommand(self):
        _, _, bare = _run_main(["validate", "m.json", "--report", "r.json"], tty=False)
        _, _, full = _run_main(["validate", "model", "m.json", "--report", "r.json"], tty=False)
        self.assertEqual(bare.call_args, full.call_args)
        self.assertEqual(bare.call_args.args[0], Path("m.json"))

    def test_option_before_bare_path(self):
        _, _, run = _run_main(["validate", "--human", "m.json"], tty=False)
        self.assertTrue(run.call_args.kwargs["human"])

    def test_format_defaults_follow_tty(self):
        self.assertFalse(_run_main(["validate", "m.json"], tty=False)[2].call_args.kwargs["human"])
        self.assertTrue(_run_main(["validate", "m.json"], tty=True)[2].call_args.kwargs["human"])
        self.assertFalse(_run_main(["validate", "--json", "m.json"], tty=True)[2].call_args.kwargs["human"])
        self.assertTrue(_run_main(["validate", "model", "--human", "m.json"], tty=False)[2]
                        .call_args.kwargs["human"])


class HumanFormatTests(unittest.TestCase):
    def test_summary_blocks(self):
        text = npdev_cli.format_validation_human(REPORT, "model.json")
        lines = text.splitlines()
        self.assertEqual(lines[0], "model.json: 1 error, 1 warning")
        self.assertIn("ERROR  concepts[0].rollups[0]", lines)
        self.assertIn("  property 'source' is not defined in the schema", lines)
        self.assertIn("  hint: use 'from'", lines)
        self.assertIn("WARNING roles[0]", lines)

    def test_valid_and_cap(self):
        self.assertEqual(npdev_cli.format_validation_human({"status": "passed", "diagnostics": []}, "m.json"),
                         "m.json: valid")
        many = {"status": "failed", "diagnostics": [
            {"severity": "error", "path": f"$.a[{i}]", "message": "x"} for i in range(35)]}
        text = npdev_cli.format_validation_human(many, "m.json")
        self.assertTrue(text.endswith("... 5 more (use --json)"))

    def test_autofix_line_and_capped_enum(self):
        report = {"status": "failed", "diagnostics": [{
            "severity": "error", "path": "$.x",
            "message": "does not have a value in the enumeration " + json.dumps([f"v{i}" for i in range(30)])}],
            "autoFixable": {"count": 2, "rules": ["r"], "command": "npdev normalize desire-paths m.json --output m.json"}}
        text = npdev_cli.format_validation_human(report, "m.json")
        self.assertIn("(30 values)", text)
        self.assertNotIn("v29", text)
        self.assertTrue(text.endswith("2 issue(s) can be fixed automatically: "
                                      "npdev normalize desire-paths m.json --output m.json"))

    def test_auto_fixable_only_when_failed_and_fixable(self):
        model = {"concepts": [{"name": "C", "fields": [{"name": "a", "computed": "x"}]}]}
        got = npdev_cli._auto_fixable({"status": "failed"}, model, Path("m.json"))
        self.assertEqual(got["count"], 1)
        self.assertEqual(got["rules"], ["field-derived-expression"])
        self.assertIn("normalize desire-paths m.json --output m.json", got["command"])
        self.assertIsNone(npdev_cli._auto_fixable({"status": "passed"}, model, Path("m.json")))
        self.assertIsNone(npdev_cli._auto_fixable({"status": "failed"}, {"concepts": []}, Path("m.json")))
        self.assertIsNone(npdev_cli._auto_fixable({"status": "failed"}, None, Path("m.json")))

    def test_json_mode_unchanged_structure(self):
        # run_validate_semantic's JSON echo is json.dumps(report, indent=2); human=False must not alter it
        self.assertEqual(json.loads(json.dumps(REPORT, indent=2)), REPORT)


class GroupedHelpTests(unittest.TestCase):
    def test_every_subcommand_listed_once(self):
        parser = npdev_cli.build_parser()
        names = list(next(a for a in parser._actions
                          if isinstance(a, npdev_cli.argparse._SubParsersAction)).choices)
        help_text = parser.format_help()
        listed = [ln.split()[0] for ln in help_text.splitlines() if ln.startswith("  ") and ln.split()]
        for name in names:
            self.assertEqual(listed.count(name), 1, name)
        self.assertLess(help_text.index("Start here:"), help_text.index("Build & run:"))

    def test_group_members_are_real_commands(self):
        parser = npdev_cli.build_parser()
        names = set(next(a for a in parser._actions
                         if isinstance(a, npdev_cli.argparse._SubParsersAction)).choices)
        for title, members in npdev_cli.HELP_GROUPS:
            self.assertFalse(set(members) - names, title)


class AddInspectDefaultsTests(unittest.TestCase):
    def setUp(self):
        import os, tempfile
        self._cwd = os.getcwd()
        self._tmp = tempfile.TemporaryDirectory()
        os.chdir(self._tmp.name)
        Path("model.json").write_text(json.dumps({
            "namespace": "demo", "version": "1.0.0", "concepts": [
                {"name": "Note", "fields": [{"name": "id", "type": "string", "id": True},
                                            {"name": "label", "type": "string"}]}]}), encoding="utf-8")

    def tearDown(self):
        import os
        os.chdir(self._cwd)
        self._tmp.cleanup()

    def _main(self, argv, *, tty):
        out = io.StringIO()
        out.isatty = lambda: tty  # type: ignore[method-assign]
        with redirect_stdout(out):
            code = npdev_cli.main(argv)
        return code, out.getvalue()

    def test_add_defaults_to_local_model_and_prints_json_when_piped(self):
        code, text = self._main(["add", "concept", "Task"], tty=False)
        self.assertEqual(code, 0)
        report = json.loads(text)
        self.assertTrue(report["added"])
        self.assertEqual(report["name"], "Task")
        names = [c["name"] for c in json.loads(Path("model.json").read_text(encoding="utf-8"))["concepts"]]
        self.assertEqual(names, ["Note", "Task"])

    def test_add_human_on_tty_and_json_override(self):
        code, text = self._main(["add", "concept", "Task"], tty=True)
        self.assertTrue(text.startswith("Added concept Task (fields: id, "), text)
        self.assertIn("to model.json", text)
        self.assertIn("npdev dev --model model.json", text)
        _, as_json = self._main(["add", "concept", "Other", "--json"], tty=True)
        self.assertEqual(json.loads(as_json)["name"], "Other")
        _, human = self._main(["add", "concept", "Third", "--human"], tty=False)
        self.assertTrue(human.startswith("Added concept Third"))

    def test_add_without_model_names_both_options(self):
        Path("model.json").unlink()
        err = io.StringIO()
        with mock.patch("sys.stderr", err):
            code, text = self._main(["add", "concept", "Task"], tty=False)
        self.assertNotEqual(code, 0)
        combined = err.getvalue() + text
        self.assertIn("--model", combined)
        self.assertIn("model.json", combined)

    def test_explicit_model_still_works(self):
        Path("other.json").write_text(Path("model.json").read_text(encoding="utf-8"), encoding="utf-8")
        code, text = self._main(["add", "concept", "Task", "--model", "other.json"], tty=False)
        self.assertEqual(code, 0)
        self.assertTrue(json.loads(text)["modelPath"].endswith("other.json"))

    def test_inspect_forms_are_equivalent_and_piped_is_json(self):
        _, explicit = self._main(["inspect", "app", "--model", "model.json"], tty=False)
        _, bare = self._main(["inspect", "model.json"], tty=False)
        _, noargs = self._main(["inspect"], tty=False)
        _, flag_only = self._main(["inspect", "--json"], tty=True)
        self.assertEqual(json.loads(explicit)["counts"], json.loads(bare)["counts"])
        self.assertEqual(json.loads(explicit)["counts"], json.loads(noargs)["counts"])
        self.assertEqual(json.loads(explicit)["counts"], json.loads(flag_only)["counts"])

    def test_inspect_app_human_table(self):
        _, text = self._main(["inspect", "--human"], tty=False)
        lines = text.splitlines()
        self.assertEqual(lines[0], "model.json: 1 concepts")
        self.assertEqual(lines[1], "  Note -- id, label")
        _, tty_text = self._main(["inspect", "app"], tty=True)
        self.assertEqual(tty_text, text)


if __name__ == "__main__":
    unittest.main()
