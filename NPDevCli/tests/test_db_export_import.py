"""`npdev db export` / `npdev db import` -- data mobility (ListaSementes.txt). Mocks `subprocess.run`
rather than executing real Java, same rationale as test_db_operation_posix_fallback.py: what matters
here is the COMMAND `run_db_export`/`run_db_import` build (URL resolution from resolved-db-plan.json,
flag plumbing), not the java process itself -- the Java side is proven by
DataExportImportRoundTripH2Test (NPDevRuntimeHost/runtimehost-core).

Run with:
    python -m unittest NPDevCli.tests.test_db_export_import -v
"""

from __future__ import annotations

import argparse
import json
import sys
import unittest
import zipfile
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402


class FakeCompletedProcess:
    def __init__(self, returncode=0, stdout="ok\n", stderr=""):
        self.returncode = returncode
        self.stdout = stdout
        self.stderr = stderr


def _make_app_root(tmp: Path, *, plan: dict | None) -> Path:
    app_root = tmp / "myapp"
    ops_root = app_root / "_ops"
    ops_root.mkdir(parents=True)
    if plan is not None:
        (ops_root / "resolved-db-plan.json").write_text(json.dumps(plan), encoding="utf-8")
    libs_dir = tmp / "libs"
    libs_dir.mkdir()
    fat_jar = tmp / "app.jar"
    with zipfile.ZipFile(fat_jar, "w") as archive:
        archive.writestr("BOOT-INF/lib/.keep", "")
    return app_root, libs_dir, fat_jar


def _export_args(app_root: Path, **overrides) -> argparse.Namespace:
    base = dict(app=str(app_root), url=None, db_user=None, db_password=None,
                out=str(app_root.parent / "export-out"), format="csv", scope="business", tables=None,
                json=False)
    base.update(overrides)
    return argparse.Namespace(**base)


def _import_args(app_root: Path, **overrides) -> argparse.Namespace:
    base = dict(app=str(app_root), url=None, db_user=None, db_password=None,
                input_dir=str(app_root.parent / "export-out"), format="csv", apply=False, force=False,
                json=False)
    base.update(overrides)
    return argparse.Namespace(**base)


class DbExportImportTest(unittest.TestCase):
    def test_export_builds_export_main_command_from_resolved_db_plan(self):
        with TemporaryDirectory(prefix="npdev-db-export-") as tmp:
            tmp_path = Path(tmp)
            app_root, libs_dir, fat_jar = _make_app_root(
                tmp_path, plan={"physicalDatabase": True, "jdbcUrl": "jdbc:h2:mem:test", "username": "sa", "password": ""})

            with patch.object(npdev_cli, "_default_runtimehost_libs_dir", return_value=libs_dir), \
                 patch.object(npdev_cli, "java_launcher", return_value="java"), \
                 patch.object(npdev_cli, "_finalexec_fat_jar_for", return_value=fat_jar), \
                 patch.object(npdev_cli.subprocess, "run", return_value=FakeCompletedProcess()) as mock_run:
                exit_code = npdev_cli.run_db_export(_export_args(app_root))

            self.assertEqual(0, exit_code)
            command = mock_run.call_args[0][0]
            self.assertEqual("java", command[0])
            self.assertIn("com.finalexec.db.ExportMain", command)
            self.assertIn("--url", command)
            self.assertIn("jdbc:h2:mem:test", command)
            self.assertIn("--scope", command)
            self.assertIn("business", command)
            self.assertIn("--format", command)
            self.assertIn("csv", command)
            self.assertIn("--user", command)
            self.assertIn("sa", command)
            self.assertNotIn("--password", command)  # empty password in the plan is falsy -- not passed

    def test_export_passes_explicit_tables(self):
        with TemporaryDirectory(prefix="npdev-db-export-") as tmp:
            tmp_path = Path(tmp)
            app_root, libs_dir, fat_jar = _make_app_root(
                tmp_path, plan={"physicalDatabase": True, "jdbcUrl": "jdbc:h2:mem:test"})

            with patch.object(npdev_cli, "_default_runtimehost_libs_dir", return_value=libs_dir), \
                 patch.object(npdev_cli, "java_launcher", return_value="java"), \
                 patch.object(npdev_cli, "_finalexec_fat_jar_for", return_value=fat_jar), \
                 patch.object(npdev_cli.subprocess, "run", return_value=FakeCompletedProcess()) as mock_run:
                npdev_cli.run_db_export(_export_args(app_root, tables="widgets,orders"))

            command = mock_run.call_args[0][0]
            self.assertIn("--tables", command)
            self.assertIn("widgets,orders", command)

    def test_export_no_physical_database_is_a_clean_noop(self):
        with TemporaryDirectory(prefix="npdev-db-export-") as tmp:
            tmp_path = Path(tmp)
            app_root, _libs_dir, _fat_jar = _make_app_root(tmp_path, plan={"physicalDatabase": False})

            with patch.object(npdev_cli.subprocess, "run") as mock_run:
                exit_code = npdev_cli.run_db_export(_export_args(app_root))

            self.assertEqual(0, exit_code)
            mock_run.assert_not_called()

    def test_export_missing_plan_and_no_url_refuses(self):
        with TemporaryDirectory(prefix="npdev-db-export-") as tmp:
            tmp_path = Path(tmp)
            app_root, _libs_dir, _fat_jar = _make_app_root(tmp_path, plan=None)

            with patch.object(npdev_cli.subprocess, "run") as mock_run:
                exit_code = npdev_cli.run_db_export(_export_args(app_root))

            self.assertEqual(2, exit_code)
            mock_run.assert_not_called()

    def test_import_dry_run_omits_apply_and_force(self):
        with TemporaryDirectory(prefix="npdev-db-import-") as tmp:
            tmp_path = Path(tmp)
            app_root, libs_dir, fat_jar = _make_app_root(
                tmp_path, plan={"physicalDatabase": True, "jdbcUrl": "jdbc:h2:mem:target"})

            with patch.object(npdev_cli, "_default_runtimehost_libs_dir", return_value=libs_dir), \
                 patch.object(npdev_cli, "java_launcher", return_value="java"), \
                 patch.object(npdev_cli, "_finalexec_fat_jar_for", return_value=fat_jar), \
                 patch.object(npdev_cli.subprocess, "run", return_value=FakeCompletedProcess()) as mock_run:
                npdev_cli.run_db_import(_import_args(app_root))

            command = mock_run.call_args[0][0]
            self.assertIn("com.finalexec.db.ImportMain", command)
            self.assertNotIn("--apply", command)
            self.assertNotIn("--force", command)

    def test_import_apply_and_force_are_passed_through(self):
        with TemporaryDirectory(prefix="npdev-db-import-") as tmp:
            tmp_path = Path(tmp)
            app_root, libs_dir, fat_jar = _make_app_root(
                tmp_path, plan={"physicalDatabase": True, "jdbcUrl": "jdbc:h2:mem:target"})

            with patch.object(npdev_cli, "_default_runtimehost_libs_dir", return_value=libs_dir), \
                 patch.object(npdev_cli, "java_launcher", return_value="java"), \
                 patch.object(npdev_cli, "_finalexec_fat_jar_for", return_value=fat_jar), \
                 patch.object(npdev_cli.subprocess, "run", return_value=FakeCompletedProcess()) as mock_run:
                npdev_cli.run_db_import(_import_args(app_root, apply=True, force=True))

            command = mock_run.call_args[0][0]
            self.assertIn("--apply", command)
            self.assertIn("--force", command)

    def test_import_explicit_url_overrides_resolved_plan(self):
        with TemporaryDirectory(prefix="npdev-db-import-") as tmp:
            tmp_path = Path(tmp)
            app_root, libs_dir, fat_jar = _make_app_root(
                tmp_path, plan={"physicalDatabase": True, "jdbcUrl": "jdbc:h2:mem:target"})

            with patch.object(npdev_cli, "_default_runtimehost_libs_dir", return_value=libs_dir), \
                 patch.object(npdev_cli, "java_launcher", return_value="java"), \
                 patch.object(npdev_cli, "_finalexec_fat_jar_for", return_value=fat_jar), \
                 patch.object(npdev_cli.subprocess, "run", return_value=FakeCompletedProcess()) as mock_run:
                npdev_cli.run_db_import(_import_args(
                    app_root, url="jdbc:postgresql://host/db", db_user="admin", db_password="secret"))

            command = mock_run.call_args[0][0]
            self.assertIn("jdbc:postgresql://host/db", command)
            self.assertIn("--user", command)
            self.assertIn("admin", command)
            self.assertIn("--password", command)
            self.assertIn("secret", command)

    def test_json_flag_wraps_raw_output_in_cli_result_envelope(self):
        with TemporaryDirectory(prefix="npdev-db-export-") as tmp:
            tmp_path = Path(tmp)
            app_root, libs_dir, fat_jar = _make_app_root(
                tmp_path, plan={"physicalDatabase": True, "jdbcUrl": "jdbc:h2:mem:test"})

            with patch.object(npdev_cli, "_default_runtimehost_libs_dir", return_value=libs_dir), \
                 patch.object(npdev_cli, "java_launcher", return_value="java"), \
                 patch.object(npdev_cli, "_finalexec_fat_jar_for", return_value=fat_jar), \
                 patch.object(npdev_cli.subprocess, "run",
                               return_value=FakeCompletedProcess(returncode=0, stdout="wrote 2 table(s)\n")), \
                 patch("builtins.print") as mock_print:
                exit_code = npdev_cli.run_db_export(_export_args(app_root, json=True))

            self.assertEqual(0, exit_code)
            printed = mock_print.call_args[0][0]
            envelope = json.loads(printed)
            self.assertEqual("npdev-cli-result.v1", envelope["schemaVersion"])
            self.assertEqual("db export", envelope["command"])
            self.assertTrue(envelope["ok"])
            self.assertIn("wrote 2 table(s)", envelope["output"])


if __name__ == "__main__":
    unittest.main()
