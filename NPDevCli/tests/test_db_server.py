"""Tests for `npdev db provision --where private|service` (a database server without Docker).

What is worth pinning, without downloading or running a real server:

  1. **The download is the pinned one or nothing.** A byte that does not match the published sha256 is
     refused; an archive path that escapes the target folder is refused.
  2. **The Windows-service window asks Windows for permission and runs exactly one winget package**, with
     the generated password and port, and a declined prompt is reported as such.
  3. **Engines without a Docker-free recipe say so** (MySQL, SQL Server) instead of failing later.
  4. **The result has the Docker result's shape**, so the Manager fills the form the same way, and a
     second app on the same service only creates its database.

Run with:
    python -m unittest NPDevCli.tests.test_db_server -v
"""

from __future__ import annotations

import hashlib
import io
import os
import subprocess
import sys
import tarfile
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_db_server as ds  # noqa: E402
from npdev_db_provision import ProvisionError  # noqa: E402


def _jar(members: dict[str, bytes]) -> bytes:
    tar_bytes = io.BytesIO()
    with tarfile.open(fileobj=tar_bytes, mode="w:xz") as tar:
        for name, data in members.items():
            info = tarfile.TarInfo(name)
            info.size = len(data)
            tar.addfile(info, io.BytesIO(data))
    jar = io.BytesIO()
    with zipfile.ZipFile(jar, "w") as z:
        z.writestr("META-INF/MANIFEST.MF", "x")
        z.writestr("postgres-windows-x86_64.txz", tar_bytes.getvalue())
    return jar.getvalue()


class Home(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.env = mock.patch.dict(os.environ, {"NPDEV_MANAGER_HOME": self.tmp.name})
        self.env.start()

    def tearDown(self):
        self.env.stop()
        self.tmp.cleanup()


class DownloadTests(Home):
    def test_a_download_that_does_not_match_its_checksum_is_refused(self):
        with self.assertRaises(ProvisionError) as ctx:
            ds.download_verified("u", "0" * 64, fetch=lambda u: b"tampered")
        self.assertEqual("download-corrupt", ctx.exception.code)

    def test_a_matching_download_is_returned(self):
        data = b"ok"
        self.assertEqual(data, ds.download_verified("u", hashlib.sha256(data).hexdigest(), fetch=lambda u: data))

    def test_the_pinned_package_is_a_maven_central_url_with_a_sha256(self):
        self.assertTrue(ds.PG_PRIVATE["url"].startswith("https://repo1.maven.org/maven2/"))
        self.assertRegex(ds.PG_PRIVATE["sha256"], r"^[0-9a-f]{64}$")

    def test_unpack_lays_out_bin_under_the_target(self):
        dest = Path(self.tmp.name) / "pg"
        ds.unpack_postgres_jar(_jar({"bin/postgres.exe": b"x", "lib/a.dll": b"y"}), dest)
        self.assertTrue((dest / "bin" / "postgres.exe").exists())
        self.assertFalse(dest.with_name("pg.partial").exists())

    def test_an_archive_path_escaping_the_folder_is_refused(self):
        with self.assertRaises(ProvisionError):
            ds.unpack_postgres_jar(_jar({"../evil.txt": b"x"}), Path(self.tmp.name) / "pg")
        self.assertFalse((Path(self.tmp.name) / "evil.txt").exists())


class EngineTests(Home):
    def test_mysql_and_sql_server_say_docker_is_the_way_for_now(self):
        for engine in ("mysql", "sqlserver"):
            with self.assertRaises(ProvisionError) as ctx:
                ds.provision_private(engine, app_name="a", fetch=lambda u: b"")
            self.assertEqual("no-docker-free-option", ctx.exception.code)
            self.assertIn("Docker", ctx.exception.fix)


class ServiceTests(Home):
    def test_the_window_asks_for_administrator_and_installs_exactly_the_postgres_package(self):
        script = ds.service_install_script("Abc123", 5433)
        self.assertIn("-Verb RunAs", script)
        self.assertIn("-Wait", script)
        self.assertIn("--id PostgreSQL.PostgreSQL.16", script)
        self.assertIn("--superpassword Abc123", script)
        self.assertIn("--serverport 5433", script)
        self.assertEqual(1, script.count("winget install"))

    @unittest.skipUnless(os.name == "nt", "a Windows service exists only on Windows")
    def test_installs_once_then_only_creates_each_apps_database(self):
        calls = []

        def runner(cmd, **kw):
            calls.append(cmd)
            return subprocess.CompletedProcess(cmd, 0, "", "")

        answering = {"on": False}

        def listening(host, port, timeout=0.5):
            if port == 5432 and calls:  # the server answers once the installer ran
                answering["on"] = True
            return answering["on"] and port == 5432

        with mock.patch.object(ds, "_listening", listening):
            first = ds.provision_service("postgres", app_name="Shop One", runner=runner)
            second = ds.provision_service("postgres", app_name="Shop Two", runner=runner)
        installs = [c for c in calls if c[0] == "powershell"]
        creates = [c for c in calls if c[0].endswith("psql.exe")]
        self.assertEqual(1, len(installs), "the service is installed once")
        self.assertEqual(2, len(creates), "each app gets its database")
        self.assertEqual(first["password"], second["password"], "one server, one admin password")
        for key in ("engine", "name", "host", "port", "user", "password", "database", "restartPolicy"):
            self.assertIn(key, first)
        self.assertEqual("shop_one", first["database"])
        self.assertEqual("127.0.0.1", first["host"])
        self.assertNotIn("password", str(ds.status()), "status never shows a password")

    @unittest.skipUnless(os.name == "nt", "a Windows service exists only on Windows")
    def test_a_declined_admin_prompt_is_reported_as_such(self):
        def runner(cmd, **kw):
            return subprocess.CompletedProcess(cmd, 1, "", "This operation was canceled by the user.")

        with mock.patch.object(ds, "_listening", lambda *a, **k: False):
            with self.assertRaises(ProvisionError) as ctx:
                ds.provision_service("postgres", app_name="a", runner=runner)
        self.assertEqual("admin-declined", ctx.exception.code)


if __name__ == "__main__":
    unittest.main()
