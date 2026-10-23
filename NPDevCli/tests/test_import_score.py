"""`npdev import score` / `npdev import genexus` -- IMP-3 / IMP-2. The enum half of the scorecard is the part
IMP-2 moves, so it is pinned here on models small enough to read; `import genexus` is checked for the command it
builds (the Java side is proven by GeneXusEnricherTest).

Run with:
    python -m unittest NPDevCli.tests.test_import_score -v
"""

from __future__ import annotations

import argparse
import json
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import MagicMock, patch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402
import npdev_import_score  # noqa: E402


def _write(path: Path, concepts: list[dict]) -> Path:
    path.write_text(json.dumps({"concepts": concepts}), encoding="utf-8")
    return path


def _enum(name: str, values: list) -> dict:
    return {"name": name, "type": "enum", "values": values}


class EnumScoreTest(unittest.TestCase):
    def test_enum_fields_are_matched_by_name_and_values_are_compared(self) -> None:
        with TemporaryDirectory() as tmp:
            root = Path(tmp)
            oracle = _write(root / "oracle.json", [
                {"name": "Area", "fields": [_enum("tipo", ["Recebimento", "Expedicao", "Avarias"]),
                                           _enum("funcao", ["A", "B"]), {"name": "nome", "type": "string"}]},
                {"name": "Lonely", "fields": [_enum("x", ["A"])]},
            ])
            draft = _write(root / "draft.json", [
                {"name": "Areas", "fields": [_enum("areaTipo", ["Recebimento", {"value": "Expedicao", "label": "Exp."}]),
                                            {"name": "funcao", "type": "string"},
                                            _enum("extra", ["Q"])]},
            ])
            enums = npdev_import_score.score(draft, oracle)["enums"]
        self.assertEqual(3, enums["oracle"])
        self.assertEqual(2, enums["draft"])
        self.assertEqual(2, enums["oracleEnumFieldsInMatchedConcepts"])
        self.assertEqual(1, enums["foundByFieldName"])          # areaTipo ~ tipo by affix; funcao is not an enum in the draft
        self.assertEqual(0.5, enums["recallByFieldName"])
        self.assertEqual(0, enums["valueSetsIdentical"])
        self.assertEqual(0.667, enums["meanValueRecallOfFound"])

    def test_no_enums_in_the_draft_scores_zero_found(self) -> None:
        with TemporaryDirectory() as tmp:
            root = Path(tmp)
            oracle = _write(root / "oracle.json", [{"name": "Area", "fields": [_enum("tipo", ["A"])]}])
            draft = _write(root / "draft.json", [{"name": "Area", "fields": [{"name": "tipo", "type": "integer"}]}])
            enums = npdev_import_score.score(draft, oracle)["enums"]
        self.assertEqual(0, enums["foundByFieldName"])
        self.assertIsNone(enums["meanValueRecallOfFound"])


class ImportGeneXusCommandTest(unittest.TestCase):
    def _args(self, tmp: Path, module_xml: str | None) -> argparse.Namespace:
        return argparse.Namespace(draft=str(tmp / "draft"), tables_xml=str(tmp / "t.xml"), module_xml=module_xml,
                                  out=str(tmp / "out"))

    def test_builds_the_enrich_command_with_resolved_paths(self) -> None:
        with TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            completed = MagicMock(returncode=0, stdout="done\n", stderr="")
            with patch.object(npdev_cli, "_default_ai_tools_jar", return_value=Path("ai-tools.jar")), \
                    patch.object(npdev_cli, "java_launcher", return_value="java"), \
                    patch.object(npdev_cli.subprocess, "run", return_value=completed) as run:
                code = npdev_cli.run_import_genexus(self._args(tmp_path, str(tmp_path / "m.xml")))
            command = run.call_args.args[0]
        self.assertEqual(0, code)
        self.assertIn("com.npdev.generator.importer.EnrichGeneXusMain", command)
        self.assertEqual(str((tmp_path / "m.xml").resolve()), command[command.index("--module-xml") + 1])
        self.assertEqual(str((tmp_path / "draft").resolve()), command[command.index("--draft") + 1])

    def test_module_xml_is_optional_and_a_missing_jar_exits_two(self) -> None:
        with TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            completed = MagicMock(returncode=0, stdout="", stderr="")
            with patch.object(npdev_cli, "_default_ai_tools_jar", return_value=Path("ai-tools.jar")), \
                    patch.object(npdev_cli, "java_launcher", return_value="java"), \
                    patch.object(npdev_cli.subprocess, "run", return_value=completed) as run:
                npdev_cli.run_import_genexus(self._args(tmp_path, None))
            self.assertNotIn("--module-xml", run.call_args.args[0])
            with patch.object(npdev_cli, "_default_ai_tools_jar", return_value=None):
                self.assertEqual(2, npdev_cli.run_import_genexus(self._args(tmp_path, None)))



class ImportDbRenameFlagsTest(unittest.TestCase):
    """IMP-GAPS-1: `npdev import db` passes the rename-policy choice through to IntrospectDbMain."""

    def _command(self, tmp: Path, **extra) -> list:
        args = argparse.Namespace(url="jdbc:h2:mem:x", out=str(tmp / "out"), db_user=None, db_password=None,
                                  schema=None, namespace="n", driver_jar=[], **extra)
        completed = MagicMock(returncode=0, stdout="", stderr="")
        with patch.object(npdev_cli, "_default_ai_tools_jar", return_value=Path("ai-tools.jar")), \
                patch.object(npdev_cli, "java_launcher", return_value="java"), \
                patch.object(npdev_cli, "_default_runtimehost_libs_dir", return_value=None), \
                patch.object(npdev_cli.subprocess, "run", return_value=completed) as run:
            self.assertEqual(0, npdev_cli.run_import_db(args))
        return run.call_args.args[0]

    def test_default_passes_no_rename_flag(self) -> None:
        with TemporaryDirectory() as tmp:
            command = self._command(Path(tmp), rename_policy=None, no_rename=False)
        self.assertNotIn("--no-rename", command)
        self.assertNotIn("--rename-policy", command)

    def test_no_rename_and_a_policy_file_are_passed_through(self) -> None:
        with TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            self.assertIn("--no-rename", self._command(tmp_path, rename_policy=None, no_rename=True))
            command = self._command(tmp_path, rename_policy=str(tmp_path / "p.json"), no_rename=False)
        self.assertEqual(str((tmp_path / "p.json").resolve()), command[command.index("--rename-policy") + 1])

    def test_the_two_flags_exclude_each_other(self) -> None:
        with self.assertRaises(SystemExit), patch("sys.stderr"):
            npdev_cli.build_parser().parse_args(["import", "db", "--url", "u", "--out", "o", "--no-rename",
                                                 "--rename-policy", "p.json"])


if __name__ == "__main__":
    unittest.main()
