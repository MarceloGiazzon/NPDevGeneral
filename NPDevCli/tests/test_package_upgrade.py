"""Tests for R9.5's `npdev package` + `npdev upgrade` (npdev_cli.py: `run_package`, `run_upgrade`,
`_find_runnable_jar`, `_app_datasource_summary`, `_read_package_manifest`, `_hash_tree`).

PREMISE CHECKED BEFORE WRITING ANY OF THIS (see npdev_cli.py's own R9.5 module comment for the
full detail):
  1. Running an already-built app needs only a JRE + PowerShell (`java -jar <FinalExec-*.jar>`,
     read verbatim out of a real `App/_ops/Start-App.ps1`) -- never the Gradle wrapper, never a
     JDK. `package`/`upgrade` close the PACKAGING gap, not a "cannot run without a build toolchain"
     gap that did not actually exist for an already-built app.
  2. `resolved-db-plan.json` is NOT what a running app connects to -- `application-npdev-db.
     properties`, compiled into the built jar, is. Confirmed on a real sample under
     `D:\\WorkSpace\\NPDev\\Build\\generated-finalapps\\r94-simple-user-registry` before writing
     any of this code. `package` therefore never edits the plan to "retarget" a database (it
     wouldn't do anything); its manifest reports a REDACTED datasource summary purely for
     information.
  3. Regeneration spares exactly `data`/`logs`/`secrets` (CLAUDE.md's three-seam rule) -- `upgrade`
     must respect the same three and invent no fourth. Proven below with an actual before/after
     byte-hash comparison of those three directories across an in-place upgrade, not merely
     asserted.

Stdlib-only (unittest). Run with:
    python -m unittest NPDevCli.tests.test_package_upgrade -v
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import tempfile
import unittest
import zipfile
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

import sys
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402
import npdev_monitor  # noqa: E402


def _build_app(root: Path, *, jar_name: str = "FinalExec-1.0.0.jar", jar_content: bytes = b"jar-bytes-v1",
               server_port: int = 0, db_url: str = "jdbc:h2:file:./data/npdev_sample;MODE=PostgreSQL",
               engine: str = "H2Local", java_version: str = "17") -> Path:
    """A minimal synthetic FinalApp tree -- just enough for `npdev_monitor.discovery_rule`/
    `probe_app` to recognize it and for `run_package`/`run_upgrade` to find what they look for.
    Mirrors the REAL layout read from a built sample app before writing this test (see this
    module's own docstring)."""
    (root / "_ops").mkdir(parents=True, exist_ok=True)
    plan = {
        "appId": root.name, "serverPort": server_port, "defaultSpringProfiles": "dev",
        "engine": engine, "storageMode": "jdbc", "username": "sa", "password": "super-secret-pw",
        "host": "localhost", "hostPort": 0, "resolvedDatabaseName": "npdev_sample",
        "requestedDatabaseName": "npdev_sample", "physicalDatabase": True,
    }
    (root / "_ops" / "resolved-db-plan.json").write_text(json.dumps(plan, indent=2), encoding="utf-8")
    for script in ("Start-App.ps1", "Stop-App.ps1", "Status-App.ps1",
                   "Start-Environment.ps1", "Stop-Environment.ps1"):
        (root / "_ops" / script).write_text(f"# {script} for {root.name}\n", encoding="utf-8")
    (root / ".npdev-root").write_text("", encoding="utf-8")
    (root / "gradle.properties").write_text(f"npdevAppJavaVersion={java_version}\n", encoding="utf-8")

    libs = root / "build" / "libs"
    libs.mkdir(parents=True, exist_ok=True)
    (libs / jar_name).write_bytes(jar_content)
    (libs / jar_name.replace(".jar", "-plain.jar")).write_bytes(b"PLAIN-JAR-NOT-RUNNABLE")

    props_dir = root / "build" / "resources" / "main"
    props_dir.mkdir(parents=True, exist_ok=True)
    (props_dir / "application-npdev-db.properties").write_text(
        f"npdev.database.engine={engine}\nspring.datasource.url={db_url}\n"
        f"spring.datasource.username=sa\nspring.datasource.password=super-secret-pw\n",
        encoding="utf-8")

    (root / "data").mkdir(parents=True, exist_ok=True)
    (root / "data" / "npdev_sample.mv.db").write_bytes(b"DATABASE-BYTES-DO-NOT-LOSE-ME")
    (root / "logs").mkdir(parents=True, exist_ok=True)
    (root / "logs" / "app-20260101T000000Z.log").write_text("boot ok\n", encoding="utf-8")
    (root / "secrets").mkdir(parents=True, exist_ok=True)
    (root / "secrets" / "api-key.env").write_text("NPDEV_AUTH_API_KEYS=realkey=dev:developer:admin\n",
                                                    encoding="utf-8")
    (root / "secrets" / "agent-proxy.env.example").write_text("# ANTHROPIC_API_KEY=\n", encoding="utf-8")
    return root


class RunPackageUnitTest(unittest.TestCase):
    def test_package_bundles_runnable_jar_excludes_plain_jar(self):
        with tempfile.TemporaryDirectory() as tmp:
            app_root = _build_app(Path(tmp) / "app")
            out_dir = Path(tmp) / "out"
            args = argparse.Namespace(app=str(app_root), out=str(out_dir), zip=False, force=False)
            with redirect_stdout(io.StringIO()) as out:
                code = npdev_cli.run_package(args)
            self.assertEqual(0, code, out.getvalue())

            jar = out_dir / "app" / "build" / "libs" / "FinalExec-1.0.0.jar"
            self.assertTrue(jar.is_file())
            self.assertEqual(b"jar-bytes-v1", jar.read_bytes())
            self.assertFalse((out_dir / "app" / "build" / "libs" / "FinalExec-1.0.0-plain.jar").exists())

    def test_package_never_ships_source_data_logs_or_real_secrets(self):
        with tempfile.TemporaryDirectory() as tmp:
            app_root = _build_app(Path(tmp) / "app")
            out_dir = Path(tmp) / "out"
            args = argparse.Namespace(app=str(app_root), out=str(out_dir), zip=False, force=False)
            with redirect_stdout(io.StringIO()):
                npdev_cli.run_package(args)

            self.assertEqual([], list((out_dir / "app" / "data").iterdir()))
            self.assertEqual([], list((out_dir / "app" / "logs").iterdir()))
            self.assertFalse((out_dir / "app" / "secrets" / "api-key.env").exists(),
                              "a real secret must never be bundled into a distributable package")
            self.assertTrue((out_dir / "app" / "secrets" / "agent-proxy.env.example").exists(),
                             "a .example template is safe and useful to ship")

    def test_package_redacts_the_db_plan_password(self):
        with tempfile.TemporaryDirectory() as tmp:
            app_root = _build_app(Path(tmp) / "app")
            out_dir = Path(tmp) / "out"
            args = argparse.Namespace(app=str(app_root), out=str(out_dir), zip=False, force=False)
            with redirect_stdout(io.StringIO()):
                npdev_cli.run_package(args)

            plan = json.loads((out_dir / "app" / "_ops" / "resolved-db-plan.json").read_text(encoding="utf-8"))
            self.assertEqual("<redacted>", plan["password"])
            self.assertEqual("sa", plan["username"], "only the credential itself is redacted, not everything")

    def test_manifest_reports_java_version_and_redacted_database_summary(self):
        with tempfile.TemporaryDirectory() as tmp:
            app_root = _build_app(
                Path(tmp) / "app", java_version="21",
                db_url="jdbc:postgresql://dbhost:5432/npdev?password=super-secret-pw&user=sa",
                engine="Postgres")
            out_dir = Path(tmp) / "out"
            args = argparse.Namespace(app=str(app_root), out=str(out_dir), zip=False, force=False)
            with redirect_stdout(io.StringIO()):
                npdev_cli.run_package(args)

            manifest = json.loads((out_dir / npdev_cli.PACKAGE_MANIFEST_NAME).read_text(encoding="utf-8"))
            self.assertEqual(npdev_cli.PACKAGE_SCHEMA_VERSION, manifest["schemaVersion"])
            self.assertEqual("21", manifest["javaVersionRequired"])
            self.assertEqual("Postgres", manifest["database"]["engine"])
            self.assertNotIn("super-secret-pw", manifest["database"]["url"])
            self.assertIn("<redacted>", manifest["database"]["url"])
            self.assertEqual(sorted(npdev_monitor._CLONE_SPARED_DIR_NAMES), manifest["sparedDirectories"])
            self.assertEqual(f"sha256:{hashlib.sha256(b'jar-bytes-v1').hexdigest()}", manifest["jarSha256"])

    def test_zip_mode_produces_a_zip_with_the_manifest_and_jar(self):
        with tempfile.TemporaryDirectory() as tmp:
            app_root = _build_app(Path(tmp) / "app")
            out_zip = Path(tmp) / "out.zip"
            args = argparse.Namespace(app=str(app_root), out=str(out_zip), zip=False, force=False)
            with redirect_stdout(io.StringIO()):
                npdev_cli.run_package(args)
            self.assertTrue(out_zip.is_file())
            with zipfile.ZipFile(out_zip) as zf:
                names = zf.namelist()
            self.assertIn(npdev_cli.PACKAGE_MANIFEST_NAME, names)
            self.assertIn("app/build/libs/FinalExec-1.0.0.jar", names)

    def test_existing_out_without_force_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            app_root = _build_app(Path(tmp) / "app")
            out_dir = Path(tmp) / "out"
            out_dir.mkdir()
            args = argparse.Namespace(app=str(app_root), out=str(out_dir), zip=False, force=False)
            with self.assertRaises(npdev_cli.CliError):
                npdev_cli.run_package(args)

    def test_app_with_no_jar_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            app_root = Path(tmp) / "app"
            (app_root / "_ops").mkdir(parents=True)
            (app_root / "_ops" / "resolved-db-plan.json").write_text(
                json.dumps({"appId": "app", "serverPort": 0}), encoding="utf-8")
            args = argparse.Namespace(app=str(app_root), out=str(Path(tmp) / "out"), zip=False, force=False)
            with self.assertRaises(npdev_cli.CliError):
                npdev_cli.run_package(args)

    def test_not_a_generated_app_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            args = argparse.Namespace(app=tmp, out=str(Path(tmp) / "out"), zip=False, force=False)
            with self.assertRaises(npdev_cli.CliError):
                npdev_cli.run_package(args)


class RunUpgradeUnitTest(unittest.TestCase):
    def _package(self, tmp: Path, **build_kwargs) -> Path:
        app_root = _build_app(tmp / f"src-{len(build_kwargs)}-{build_kwargs.get('jar_content', b'')!r}"[:120], **build_kwargs)
        out_dir = tmp / (app_root.name + "-package")
        with redirect_stdout(io.StringIO()):
            npdev_cli.run_package(argparse.Namespace(app=str(app_root), out=str(out_dir), zip=False, force=False))
        return out_dir

    def test_fresh_install_creates_a_running_layout(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            package = self._package(tmp_path, jar_content=b"v1")
            target = tmp_path / "installed"
            with redirect_stdout(io.StringIO()) as out:
                code = npdev_cli.run_upgrade(argparse.Namespace(package=str(package), target=str(target), force=False))
            report = json.loads(out.getvalue())
            self.assertEqual(0, code, out.getvalue())
            self.assertTrue(report["freshInstall"])
            self.assertTrue((target / "build" / "libs" / "FinalExec-1.0.0.jar").is_file())
            self.assertTrue((target / "_ops" / "Start-App.ps1").is_file())
            self.assertEqual([], list((target / "data").iterdir()))

    def test_in_place_upgrade_preserves_data_logs_secrets_byte_for_byte(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            package_v1 = self._package(tmp_path, jar_content=b"v1")
            target = tmp_path / "installed"
            with redirect_stdout(io.StringIO()):
                npdev_cli.run_upgrade(argparse.Namespace(package=str(package_v1), target=str(target), force=False))

            # Simulate real usage: the installed app accumulates its OWN data/logs/secrets,
            # independent of anything `package`/`upgrade` ever wrote.
            (target / "data" / "real-runtime.mv.db").write_bytes(b"REAL RUNTIME DATA")
            (target / "logs" / "app-real-run.log").write_text("real boot log\n", encoding="utf-8")
            (target / "secrets" / "api-key.env").write_text("NPDEV_AUTH_API_KEYS=liveKey=dev:developer:admin\n",
                                                              encoding="utf-8")
            # And a site-specific DB plan with a REAL password that must survive the upgrade.
            plan_path = target / "_ops" / "resolved-db-plan.json"
            site_plan = json.loads(plan_path.read_text(encoding="utf-8"))
            site_plan["password"] = "THE-REAL-SITE-PASSWORD"
            plan_path.write_text(json.dumps(site_plan), encoding="utf-8")

            before = npdev_cli._hash_tree(target / "data")
            before_logs = npdev_cli._hash_tree(target / "logs")
            before_secrets = npdev_cli._hash_tree(target / "secrets")
            self.assertTrue(before)  # sanity: there really is something to lose

            package_v2 = self._package(tmp_path, jar_content=b"v2-newer-build")
            with redirect_stdout(io.StringIO()) as out:
                code = npdev_cli.run_upgrade(argparse.Namespace(package=str(package_v2), target=str(target), force=False))
            report = json.loads(out.getvalue())

            self.assertEqual(0, code, out.getvalue())
            self.assertFalse(report["freshInstall"])
            self.assertTrue(report["dataPreserved"], report)
            self.assertEqual(before, npdev_cli._hash_tree(target / "data"))
            self.assertEqual(before_logs, npdev_cli._hash_tree(target / "logs"))
            self.assertEqual(before_secrets, npdev_cli._hash_tree(target / "secrets"))

            # The jar was actually swapped to the new build...
            jars = list((target / "build" / "libs").glob("FinalExec-*.jar"))
            self.assertEqual(1, len(jars), "no stale jar left behind")
            self.assertEqual(b"v2-newer-build", jars[0].read_bytes())

            # ...but the site-specific DB plan (with its real password) was left alone, never
            # overwritten by the package's own redacted copy.
            self.assertEqual("THE-REAL-SITE-PASSWORD",
                              json.loads(plan_path.read_text(encoding="utf-8"))["password"])
            self.assertFalse(report["wroteFreshDbPlan"])

            # Two `run_upgrade` calls happened in this test (the initial fresh install, then this
            # in-place upgrade), so the history accumulates two entries -- append-only, per install.
            history = json.loads((target / "_ops" / npdev_cli.UPGRADE_HISTORY_NAME).read_text(encoding="utf-8"))
            self.assertEqual(2, len(history["entries"]))
            self.assertTrue(history["entries"][-1]["dataPreserved"])

    def test_upgrade_refuses_a_running_target_without_force(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            package_v1 = self._package(tmp_path, jar_content=b"v1")
            target = tmp_path / "installed"
            with redirect_stdout(io.StringIO()):
                npdev_cli.run_upgrade(argparse.Namespace(package=str(package_v1), target=str(target), force=False))

            package_v2 = self._package(tmp_path, jar_content=b"v2")
            running_record = {"isAppRoot": True, "finalAppRoot": str(target), "opsDir": str(target / "_ops"),
                              "listening": True, "pid": 12345, "port": 8317}
            with mock.patch.object(npdev_cli.npdev_monitor, "probe_app", return_value=running_record):
                with self.assertRaises(npdev_cli.CliError) as ctx:
                    npdev_cli.run_upgrade(argparse.Namespace(package=str(package_v2), target=str(target), force=False))
            self.assertIn("running", str(ctx.exception))

            # --force overrides the guard.
            with mock.patch.object(npdev_cli.npdev_monitor, "probe_app", return_value=running_record):
                with redirect_stdout(io.StringIO()) as out:
                    code = npdev_cli.run_upgrade(argparse.Namespace(package=str(package_v2), target=str(target), force=True))
            self.assertEqual(0, code, out.getvalue())

    def test_zip_package_can_be_upgraded_from(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            app_root = _build_app(tmp_path / "src", jar_content=b"zipped")
            out_zip = tmp_path / "package.zip"
            with redirect_stdout(io.StringIO()):
                npdev_cli.run_package(argparse.Namespace(app=str(app_root), out=str(out_zip), zip=True, force=False))
            target = tmp_path / "installed"
            with redirect_stdout(io.StringIO()) as out:
                code = npdev_cli.run_upgrade(argparse.Namespace(package=str(out_zip), target=str(target), force=False))
            self.assertEqual(0, code, out.getvalue())
            self.assertTrue((target / "build" / "libs" / "FinalExec-1.0.0.jar").is_file())

    def test_package_without_manifest_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            not_a_package = Path(tmp) / "junk"
            not_a_package.mkdir()
            with self.assertRaises(npdev_cli.CliError):
                npdev_cli.run_upgrade(argparse.Namespace(package=str(not_a_package),
                                                          target=str(Path(tmp) / "t"), force=False))


if __name__ == "__main__":
    unittest.main()
