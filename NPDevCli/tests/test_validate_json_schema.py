"""Tests for validate_json_schema's REG-165 fix: a crashed validator subprocess must surface its
own stderr instead of reading as an unexplained "0 errors, still failed", and a present-but-broken
node_modules/ajv install must self-heal instead of being silently trusted.

Stdlib-only (unittest + unittest.mock), matching this repo's convention for CLI-adjacent tests.
    python -m unittest NPDevCli.tests.test_validate_json_schema -v
"""

from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402


class _FakeCompletedProcess:
    def __init__(self, stdout: str, stderr: str, returncode: int):
        self.stdout = stdout
        self.stderr = stderr
        self.returncode = returncode


class ValidateJsonSchemaCrashDetailTest(unittest.TestCase):
    """A crashed subprocess (empty stdout) must surface stderr, not a bare, unexplained failure."""

    def test_empty_stdout_with_stderr_surfaces_the_crash_text(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            validator_root = root / "scripts" / "quality" / "json-schema-validator"
            ajv_dir = validator_root / "node_modules" / "ajv"
            ajv_dir.mkdir(parents=True)
            (ajv_dir / "index.js").write_text("module.exports = {};\n", encoding="utf-8")
            (validator_root / "validate-json-schema.mjs").write_text("// stub\n", encoding="utf-8")
            schema = root / "schema.json"
            instance = root / "instance.json"
            schema.write_text("{}", encoding="utf-8")
            instance.write_text("{}", encoding="utf-8")

            crash_stderr = (
                "file:///validate-json-schema.mjs:1\nError: Cannot find package 'ajv'\n"
                "    at some/stack/frame\n"
            )

            with patch.object(npdev_cli, "repo_root", return_value=root), \
                 patch.object(subprocess, "run", return_value=_FakeCompletedProcess("", crash_stderr, 1)):
                with self.assertRaises(npdev_cli.CliError) as ctx:
                    npdev_cli.validate_json_schema(schema, instance)

            message = str(ctx.exception)
            self.assertIn("validator subprocess crashed", message)
            self.assertIn("Cannot find package 'ajv'", message)

    def test_real_validation_failure_still_reports_field_detail_not_stderr(self):
        """A genuine schema failure (real errors[], clean exit) must keep reporting field-level
        detail -- the stderr fallback must never mask real validation output."""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            validator_root = root / "scripts" / "quality" / "json-schema-validator"
            ajv_dir = validator_root / "node_modules" / "ajv"
            ajv_dir.mkdir(parents=True)
            (ajv_dir / "index.js").write_text("module.exports = {};\n", encoding="utf-8")
            (validator_root / "validate-json-schema.mjs").write_text("// stub\n", encoding="utf-8")
            schema = root / "schema.json"
            instance = root / "instance.json"
            schema.write_text("{}", encoding="utf-8")
            instance.write_text("{}", encoding="utf-8")

            stdout = (
                '{"status": "failed", "errors": '
                '[{"path": "/concepts/0/name", "keyword": "required", "message": "is missing"}]}'
            )

            with patch.object(npdev_cli, "repo_root", return_value=root), \
                 patch.object(subprocess, "run", return_value=_FakeCompletedProcess(stdout, "", 1)):
                with self.assertRaises(npdev_cli.CliError) as ctx:
                    npdev_cli.validate_json_schema(schema, instance)

            message = str(ctx.exception)
            self.assertIn("/concepts/0/name", message)
            self.assertNotIn("validator subprocess crashed", message)


class ValidateJsonSchemaSelfHealTest(unittest.TestCase):
    """A present-but-broken node_modules/ajv (no index.js) must trigger a reinstall, not be
    silently trusted as already-installed."""

    def test_corrupted_ajv_install_triggers_npm_install(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            validator_root = root / "scripts" / "quality" / "json-schema-validator"
            corrupted_ajv_dir = validator_root / "node_modules" / "ajv"
            corrupted_ajv_dir.mkdir(parents=True)
            (corrupted_ajv_dir / "LICENSE").write_text("MIT\n", encoding="utf-8")
            (validator_root / "validate-json-schema.mjs").write_text("// stub\n", encoding="utf-8")
            schema = root / "schema.json"
            instance = root / "instance.json"
            schema.write_text("{}", encoding="utf-8")
            instance.write_text("{}", encoding="utf-8")

            install_calls = []

            def fake_run(cmd, **kwargs):
                if "install" in cmd:
                    install_calls.append(cmd)
                    return _FakeCompletedProcess("", "", 0)
                return _FakeCompletedProcess('{"status": "passed"}', "", 0)

            with patch.object(npdev_cli, "repo_root", return_value=root), \
                 patch.object(npdev_cli.shutil, "which", return_value="npm"), \
                 patch.object(subprocess, "run", side_effect=fake_run):
                npdev_cli.validate_json_schema(schema, instance)

            self.assertEqual(len(install_calls), 1, "a corrupted ajv install must trigger exactly one npm install")


if __name__ == "__main__":
    unittest.main()
