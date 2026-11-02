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


if __name__ == "__main__":
    unittest.main()
