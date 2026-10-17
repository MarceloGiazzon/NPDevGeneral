"""OPS-12: `npdev upgrade --app` orchestrator (npdev_app_upgrade.upgrade_app).

Every subprocess / run_app / validator call is patched: what this file owns is the ORDER of the phases,
the backup contents, and the rollback decisions -- not whether Gradle or the JVM work (the manual
end-to-end upgrade of a real app proves that).

Run with:
    python -m unittest discover -s NPDevCli/tests -p "test_app_upgrade.py" -v
"""

from __future__ import annotations

import argparse
import json
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_app_upgrade  # noqa: E402
import npdev_cli  # noqa: E402


class FakeApp:
    """A generated-app tree plus a fake `run_app` that behaves like the real one on disk: the
    stop_after_build call REGENERATES (wipes everything but data/logs/secrets, writes a new jar)."""

    def __init__(self, root: Path, engine: str = "H2Local"):
        self.root = root / "demo-app"
        self.engine = engine
        (self.root / "_ops").mkdir(parents=True)
        (self.root / "_ops" / "marker.txt").write_text("old-ops", encoding="utf-8")
        (self.root / "build" / "libs").mkdir(parents=True)
        (self.root / "build" / "libs" / "FinalExec-0.1.0.jar").write_bytes(b"OLD-JAR")
        (self.root / "npdev-generated").mkdir()
        (self.root / "npdev-generated" / "compiled-model.json").write_text("old-model", encoding="utf-8")
        (self.root / "data").mkdir()
        (self.root / "data" / "app.mv.db").write_bytes(b"customer-1")
        (self.root / "data" / "backups").mkdir()
        (self.root / "data" / "backups" / "older.txt").write_text("keep", encoding="utf-8")
        self.model = root / "model.json"
        self.model.write_text("{}", encoding="utf-8")
        (root / "config.json").write_text("{}", encoding="utf-8")
        self.calls: list[dict] = []
        self.boot_jars: list[bytes] = []
        self.build_ok = True
        self.new_boot_ok = True
        self.old_boot_ok = True

    def record(self, *, listening: bool = False) -> dict:
        return {"isAppRoot": True, "finalAppRoot": str(self.root), "name": "demo", "port": 8299,
                "engine": self.engine, "dataRoot": str(self.root / "data"), "listening": listening,
                "modelPath": str(self.model)}

    def run_app(self, ns: argparse.Namespace) -> dict:
        self.calls.append({"stop_after_build": ns.stop_after_build, "skip": ns.skip_generate_and_build})
        if ns.stop_after_build:
            for child in list(self.root.iterdir()):
                if child.name not in ("data", "logs", "secrets"):
                    if child.is_dir():
                        import shutil
                        shutil.rmtree(child)
                    else:
                        child.unlink()
            if not self.build_ok:
                return {"ok": False, "phase": "BUILD",
                        "diagnostics": [{"code": "BUILD_FAILED", "message": "boom"}]}
            (self.root / "build" / "libs").mkdir(parents=True)
            jar = self.root / "build" / "libs" / "FinalExec-0.1.0.jar"
            jar.write_bytes(b"NEW-JAR")
            (self.root / "npdev-generated").mkdir()
            (self.root / "npdev-generated" / "compiled-model.json").write_text("new-model", encoding="utf-8")
            return {"ok": True, "phase": "BUILD", "jarPath": str(jar), "diagnostics": []}
        # a BOOT: the new JVM "migrates" the embedded data before it fails, the old one just boots
        jar = (self.root / "build" / "libs" / "FinalExec-0.1.0.jar").read_bytes()
        self.boot_jars.append(jar)
        ok = self.new_boot_ok if jar == b"NEW-JAR" else self.old_boot_ok
        if jar == b"NEW-JAR":
            (self.root / "data" / "app.mv.db").write_bytes(b"half-migrated")
        if not ok:
            return {"ok": False, "phase": "BOOT", "diagnostics": [{"code": "BOOT_FAILED", "message": "x"}],
                    "logExcerpt": "boom"}
        return {"ok": True, "phase": "READY", "baseUrl": "http://127.0.0.1:8299", "diagnostics": [],
                "openUrl": "http://127.0.0.1:8299/", "apiKeyFile": str(self.root / "secrets" / "api-key.env")}


def _args(app: FakeApp, **overrides) -> argparse.Namespace:
    values = dict(app=str(app.root), model=str(app.model), config=None, port=8299, profile="dev",
                  stop=False, allow_destructive=False, skip_db_backup=False, timeout=900,
                  output=None, baseline_model=None, keep_running=False, clean=False,
                  require_db_definition=False)
    values.update(overrides)
    return argparse.Namespace(**values)


class UpgradeAppTest(unittest.TestCase):
    def setUp(self):
        self._tmp = TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.app = FakeApp(Path(self._tmp.name))
        self.report = {"status": "passed", "diagnostics": []}
        self.impact = 0
        self.listening = False
        self.service = False
        self.stop_calls = 0

    def _upgrade(self, **overrides):
        app = self.app

        def fake_validate(model_path, report_out, quiet=False):
            Path(report_out).write_text(json.dumps(self.report), encoding="utf-8")
            return 2 if self.report.get("status") == "failed" else 0

        def fake_stop(cli, app_root, record, wait_seconds=60):
            self.stop_calls += 1
            self.listening = False
            return True

        patches = [
            mock.patch.object(npdev_cli.npdev_monitor, "probe_app",
                              side_effect=lambda *a, **k: app.record(listening=self.listening)),
            mock.patch.object(npdev_cli, "_is_port_in_use", side_effect=lambda port: self.listening),
            mock.patch.object(npdev_cli, "java_launcher", return_value="java"),
            mock.patch.object(npdev_cli, "ensure_api_key", return_value="k"),
            mock.patch.object(npdev_cli, "run_app", side_effect=app.run_app),
            mock.patch.object(npdev_cli, "run_validate_semantic", side_effect=fake_validate),
            mock.patch.object(npdev_app_upgrade, "impact_exit_code", side_effect=lambda *a, **k: self.impact),
            mock.patch.object(npdev_app_upgrade, "service_installed", side_effect=lambda *a, **k: self.service),
            mock.patch.object(npdev_app_upgrade, "_stop_app", side_effect=fake_stop),
        ]
        for p in patches:
            p.start()
            self.addCleanup(p.stop)
        return npdev_app_upgrade.upgrade_app(_args(app, **overrides), npdev_cli)

    def _backups(self):
        return sorted(p for p in (self.app.root / "data" / "backups").glob("pre-upgrade-*"))

    def test_running_app_without_stop_is_refused_and_nothing_is_copied(self):
        self.listening = True
        result = self._upgrade()
        self.assertEqual(result["outcome"], "REFUSED")
        self.assertFalse(result["ok"])
        self.assertEqual(result["diagnostics"][0]["code"], "APP_RUNNING")
        self.assertEqual(self._backups(), [])
        self.assertEqual(self.app.calls, [])

    def test_running_app_with_stop_is_stopped_then_upgraded(self):
        self.listening = True
        result = self._upgrade(stop=True)
        self.assertEqual(self.stop_calls, 1)
        self.assertEqual(result["outcome"], "UPGRADED")

    def test_installed_service_is_refused(self):
        self.service = True
        result = self._upgrade()
        self.assertEqual(result["outcome"], "REFUSED")
        self.assertEqual(result["diagnostics"][0]["code"], "SERVICE_INSTALLED")
        self.assertEqual(self.app.calls, [])

    def test_happy_path_backs_up_code_and_data_and_ends_upgraded(self):
        result = self._upgrade()
        self.assertEqual(result["outcome"], "UPGRADED")
        self.assertTrue(result["ok"])
        self.assertEqual(result["phase"], "READY")
        self.assertEqual(result["baseUrl"], "http://127.0.0.1:8299")
        backups = self._backups()
        self.assertEqual(len(backups), 1)
        self.assertEqual(result["backupDir"], str(backups[0]))
        self.assertEqual((backups[0] / "code" / "FinalExec-0.1.0.jar").read_bytes(), b"OLD-JAR")
        self.assertEqual((backups[0] / "data" / "app.mv.db").read_bytes(), b"customer-1")
        self.assertTrue((backups[0] / "code" / "npdev-generated" / "compiled-model.json").is_file())
        self.assertFalse((backups[0] / "data" / "backups").exists(), "backups must not be copied into itself")
        self.assertEqual(self.app.boot_jars, [b"NEW-JAR"])
        self.assertEqual([c["stop_after_build"] for c in self.app.calls], [True, False])
        self.assertEqual(result["apiKeyFile"], str(self.app.root / "secrets" / "api-key.env"))  # a pointer, never the key

    def test_destructive_impact_without_flag_is_refused_and_original_jar_is_back(self):
        self.impact = 3
        result = self._upgrade()
        self.assertEqual(result["outcome"], "REFUSED")
        self.assertIn("DESTRUCTIVE_SCHEMA_CHANGE", [d["code"] for d in result["diagnostics"]])
        self.assertEqual((self.app.root / "build" / "libs" / "FinalExec-0.1.0.jar").read_bytes(), b"OLD-JAR")
        self.assertEqual((self.app.root / "npdev-generated" / "compiled-model.json").read_text(), "old-model")
        self.assertEqual(self.app.boot_jars, [b"OLD-JAR"], "only the restored old version was booted")

    def test_destructive_impact_with_flag_proceeds(self):
        self.impact = 3
        result = self._upgrade(allow_destructive=True)
        self.assertEqual(result["outcome"], "UPGRADED")

    def test_needs_attention_impact_continues_with_a_diagnostic(self):
        self.impact = 2
        result = self._upgrade()
        self.assertEqual(result["outcome"], "UPGRADED")
        self.assertIn("NEEDS_ATTENTION", [d["code"] for d in result["diagnostics"]])

    def test_boot_failure_restores_data_and_old_jar_and_ends_rolled_back(self):
        self.app.new_boot_ok = False
        result = self._upgrade()
        self.assertEqual(result["outcome"], "ROLLED_BACK")
        self.assertFalse(result["ok"])
        self.assertEqual(self.app.boot_jars, [b"NEW-JAR", b"OLD-JAR"])
        self.assertEqual((self.app.root / "data" / "app.mv.db").read_bytes(), b"customer-1",
                         "the half-migrated embedded database must be put back")
        self.assertTrue((self.app.root / "data" / "backups" / "older.txt").is_file(), "older backups survive")
        self.assertEqual((self.app.root / "_ops" / "marker.txt").read_text(), "old-ops")

    def test_rollback_failed_when_the_old_version_will_not_boot_either(self):
        self.app.new_boot_ok = False
        self.app.old_boot_ok = False
        result = self._upgrade()
        self.assertEqual(result["outcome"], "ROLLBACK_FAILED")
        self.assertIn("OLD_VERSION_DID_NOT_BOOT", [d["code"] for d in result["diagnostics"]])
        self.assertTrue(result["backupDir"])

    def test_build_failure_rolls_back(self):
        self.app.build_ok = False
        result = self._upgrade()
        self.assertEqual(result["outcome"], "ROLLED_BACK")
        self.assertEqual(self.app.boot_jars, [b"OLD-JAR"])

    def test_validation_errors_refuse_before_any_copy_and_suggest_the_codemod(self):
        self.report = {"status": "failed", "diagnostics": [
            {"severity": "error", "code": "RETIRED_ALIAS", "message": "flowStep.type 'waitForEvent' is a retired alias"}]}
        result = self._upgrade()
        self.assertEqual(result["outcome"], "REFUSED")
        self.assertEqual(result["phase"], "VALIDATE")
        fixes = [d.get("suggestedFix") or "" for d in result["diagnostics"]]
        self.assertTrue(any("npdev migrate dsl-2" in f for f in fixes))
        self.assertEqual(self._backups(), [])
        self.assertEqual(self.app.calls, [])

    def test_server_engine_without_backup_script_is_refused_before_any_build(self):
        self.app.engine = "Postgres"
        result = self._upgrade()
        self.assertEqual(result["outcome"], "REFUSED")
        self.assertEqual(result["diagnostics"][0]["code"], "NO_DB_BACKUP")
        self.assertIn("--skip-db-backup", result["diagnostics"][0]["suggestedFix"])
        self.assertEqual(self.app.calls, [])

    def test_server_engine_with_skip_flag_upgrades_and_never_touches_data(self):
        self.app.engine = "Postgres"
        result = self._upgrade(skip_db_backup=True)
        self.assertEqual(result["outcome"], "UPGRADED")
        self.assertFalse((Path(result["backupDir"]) / "data").exists())

    def test_server_engine_boot_failure_prints_restore_command_but_does_not_restore(self):
        self.app.engine = "Postgres"
        (self.app.root / "deploy").mkdir()
        (self.app.root / "deploy" / "backup.sh").write_text("#!/bin/sh\n", encoding="utf-8")
        (self.app.root / "backups").mkdir()
        dump = self.app.root / "backups" / "demo-1.sql"
        dump.write_text("-- dump", encoding="utf-8")
        completed = mock.Mock(returncode=0, stdout=f"Done: {dump}\n", stderr="")
        self.app.new_boot_ok = False
        with mock.patch.object(npdev_app_upgrade.shutil, "which", return_value="bash"), \
                mock.patch.object(npdev_app_upgrade, "backup_server_data", return_value=completed):
            result = self._upgrade()
        self.assertEqual(result["outcome"], "ROLLED_BACK")
        manual = [d for d in result["diagnostics"] if d["code"] == "DB_RESTORE_MANUAL"]
        self.assertEqual(len(manual), 1)
        self.assertIn("deploy/restore.sh", manual[0]["message"])
        self.assertIn("--yes", manual[0]["message"])
        self.assertTrue((Path(result["backupDir"]) / "db-dump" / "demo-1.sql").is_file())

    def test_not_an_app_is_refused(self):
        with mock.patch.object(npdev_cli.npdev_monitor, "probe_app",
                               return_value={"isAppRoot": False, "detail": "not a generated NPDev app"}):
            result = npdev_app_upgrade.upgrade_app(_args(self.app), npdev_cli)
        self.assertEqual(result["outcome"], "REFUSED")
        self.assertEqual(result["diagnostics"][0]["code"], "NOT_AN_APP")


class UpgradeParserTest(unittest.TestCase):
    def test_package_mode_is_unchanged_and_app_mode_needs_one_of_them(self):
        with mock.patch.object(npdev_cli, "run_upgrade", return_value=0) as legacy:
            self.assertEqual(npdev_cli.main(["upgrade", "--package", "p", "--target", "t"]), 0)
            legacy.assert_called_once()
        with mock.patch.object(npdev_app_upgrade, "upgrade_app", return_value={"ok": True}) as new:
            with mock.patch("builtins.print"):
                self.assertEqual(npdev_cli.main(["upgrade", "--app", "x"]), 0)
            new.assert_called_once()
        with mock.patch("builtins.print"), mock.patch("sys.stderr"):
            self.assertNotEqual(npdev_cli.main(["upgrade"]), 0)


if __name__ == "__main__":
    unittest.main()
