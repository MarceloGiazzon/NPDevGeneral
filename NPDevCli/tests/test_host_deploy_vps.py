"""OPS-9: `npdev host deploy --ssh user@host` -- bundle contents, first .env, remote script, dry run.

No ssh is ever spawned: subprocess.run is patched to fail the test if anything reaches it.
"""

from __future__ import annotations

import contextlib
import io
import json
import re
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402
import npdev_vps  # noqa: E402

ENV_EXAMPLE = """# example
DB_NAME=npdev_app
DB_USER=npdev
DB_PASSWORD=change-me-to-a-real-secret
APP_PORT=8080
# NPDEV_PUBLIC_HOST=app.example.com
# NPDEV_ACME_EMAIL=you@example.com
NPDEV_AUTH_APIKEYS=change-me=prod:deploy:ADMIN
"""


def _write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def _fake_app(root: Path, engine: str = "Postgres") -> Path:
    _write(root / "Dockerfile", "FROM x\nCOPY build/libs/FinalExec-0.1.0.jar app.jar\nCOPY npdev-generated ./npdev-generated\n")
    _write(root / "docker-compose.yml", "services: {}\n")
    _write(root / "deploy" / "Caddyfile", ":443 {}\n")
    _write(root / "npdev-generated" / "x.json", "{}")
    _write(root / "build" / "libs" / "FinalExec-0.1.0.jar", "jar")
    _write(root / "build" / "libs" / "FinalExec-0.1.0-plain.jar", "plain")
    _write(root / "data" / "db.mv.db", "db")
    _write(root / "secrets" / "api-key.env", "SECRET")
    _write(root / ".env.example", ENV_EXAMPLE)
    _write(root / "_ops" / "resolved-db-plan.json", json.dumps({"appId": "shop", "engine": engine}))
    return root


class VpsModuleTest(unittest.TestCase):
    def test_bundle_ships_what_the_dockerfile_needs_and_nothing_secret(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            app = _fake_app(Path(tmp))
            blob = npdev_vps.build_bundle(app, None)
            with tarfile.open(fileobj=io.BytesIO(blob), mode="r:gz") as tar:
                names = tar.getnames()
            self.assertIn("build/libs/FinalExec-0.1.0.jar", names)
            self.assertIn("npdev-generated/x.json", names)
            self.assertIn("Dockerfile", names)
            self.assertIn("deploy/Caddyfile", names)
            self.assertNotIn("build/libs/FinalExec-0.1.0-plain.jar", names)
            self.assertFalse([n for n in names if n.startswith("data") or n.startswith("secrets")], names)
            self.assertNotIn(".env", names)
            self.assertEqual(sorted(npdev_vps.bundle_names(app)), sorted(n for n in names if not n.endswith("npdev-generated") and n not in ("deploy",)))

    def test_bundle_carries_env_only_when_given(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            app = _fake_app(Path(tmp))
            blob = npdev_vps.build_bundle(app, "A=1\n")
            with tarfile.open(fileobj=io.BytesIO(blob), mode="r:gz") as tar:
                member = tar.getmember(".env")
                self.assertEqual(member.mode, 0o600)
                self.assertEqual(tar.extractfile(member).read(), b"A=1\n")

    def test_first_env_fills_secrets_and_proxy_variables(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            app = _fake_app(Path(tmp))
            text = npdev_vps.first_env(app, public_host="app.example.com", acme_email="me@example.com")
            self.assertRegex(text, r"(?m)^NPDEV_AUTH_APIKEYS=[0-9a-f]{64}=prod:deploy:ADMIN$")
            self.assertNotIn("change-me", text)
            password = re.search(r"(?m)^DB_PASSWORD=(\S+)$", text).group(1)
            self.assertGreaterEqual(len(password), 24)
            self.assertIn("NPDEV_PUBLIC_HOST=app.example.com\n", text)
            self.assertIn("NPDEV_ACME_EMAIL=me@example.com\n", text)
            self.assertNotIn("# NPDEV_PUBLIC_HOST", text)
            # two calls never share a secret
            again = npdev_vps.first_env(app, public_host=None, acme_email=None)
            self.assertNotEqual(password, re.search(r"(?m)^DB_PASSWORD=(\S+)$", again).group(1))
            self.assertIn("# NPDEV_PUBLIC_HOST=app.example.com", again)

    def test_remote_script_and_ssh_command(self) -> None:
        script = npdev_vps.remote_script("shop", ["proxy"], backup_service=True)
        self.assertIn("--profile proxy", script)
        self.assertIn("up -d --build", script)
        self.assertIn("docker compose --profile backup up -d backup", script)
        self.assertNotIn("backup-verify", script)
        self.assertNotIn("--profile backup up -d backup", npdev_vps.remote_script("shop", ["proxy"]))
        self.assertEqual(npdev_vps.ssh("u@h", script, dry_run=True), ["ssh", "-o", "BatchMode=yes", "u@h", script])

    def test_app_id_is_sanitised_for_the_remote_path(self) -> None:
        self.assertEqual(npdev_vps.safe_app_id("a b;rm -rf /"), "a-b-rm--rf")
        self.assertEqual(npdev_vps.safe_app_id(""), "app")


class HostDeployDryRunTest(unittest.TestCase):
    def _run(self, argv: list[str]) -> tuple[int, str, str]:
        out, err = io.StringIO(), io.StringIO()
        with mock.patch("subprocess.run", side_effect=AssertionError("ssh must not be spawned")), \
                contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = npdev_cli.main(argv)
        return code, out.getvalue(), err.getvalue()

    def test_dry_run_lists_files_and_ssh_command_and_touches_nothing(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            app = _fake_app(Path(tmp))
            code, out, _ = self._run(["host", "deploy", "--app", str(app), "--ssh", "u@h", "--domain", "app.example.com",
                                      "--acme-email", "me@example.com", "--dry-run"])
            self.assertEqual(code, 0)
            self.assertIn("build/libs/FinalExec-0.1.0.jar", out)
            self.assertIn("npdev-generated/  (1 files)", out)
            self.assertIn("ssh -o BatchMode=yes u@h", out)
            self.assertIn("--profile backup up -d backup", out)  # Postgres gets the sidecar
            self.assertNotIn("data/db.mv.db", out)
            self.assertNotIn("secrets/", out)
            self.assertNotIn("api-key.env", out)
            self.assertFalse((app / "secrets" / "deploy-u_h.env").exists())

    def _fake_ssh(self, has_env: bool):
        calls = []

        def fake_run(command, input=None, capture_output=False, timeout=None):
            calls.append((command, input))
            if "test -f" in command[-1]:
                return mock.Mock(returncode=0, stdout=b"yes" if has_env else b"no", stderr=b"")
            return mock.Mock(returncode=0, stdout=b"app running", stderr=b"")
        return calls, fake_run

    def test_real_run_creates_env_once_and_never_prints_secrets(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            app = _fake_app(Path(tmp))
            calls, fake_run = self._fake_ssh(has_env=False)
            out, err = io.StringIO(), io.StringIO()
            with mock.patch("shutil.which", return_value="ssh"), mock.patch("subprocess.run", side_effect=fake_run),                     contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                code = npdev_cli.main(["host", "deploy", "--app", str(app), "--ssh", "u@h"])
            self.assertEqual(code, 0, err.getvalue())
            self.assertEqual(len(calls), 2)
            with tarfile.open(fileobj=io.BytesIO(calls[1][1]), mode="r:gz") as tar:
                self.assertIn(".env", tar.getnames())
                env_text = tar.extractfile(".env").read().decode()
            local = app / "secrets" / "deploy-u_h.env"
            self.assertEqual(local.read_text(encoding="utf-8"), env_text)
            password = re.search(r"(?m)^DB_PASSWORD=(\S+)$", env_text).group(1)
            self.assertNotIn(password, out.getvalue() + err.getvalue())
            self.assertIn(str(local), out.getvalue())

    def test_existing_server_env_is_never_overwritten(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            app = _fake_app(Path(tmp))
            calls, fake_run = self._fake_ssh(has_env=True)
            out, err = io.StringIO(), io.StringIO()
            with mock.patch("shutil.which", return_value="ssh"), mock.patch("subprocess.run", side_effect=fake_run),                     contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                code = npdev_cli.main(["host", "deploy", "--app", str(app), "--ssh", "u@h"])
            self.assertEqual(code, 0, err.getvalue())
            with tarfile.open(fileobj=io.BytesIO(calls[1][1]), mode="r:gz") as tar:
                self.assertNotIn(".env", tar.getnames())
            self.assertFalse((app / "secrets" / "deploy-u_h.env").exists())

    def test_h2_apps_get_no_backup_sidecar(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            app = _fake_app(Path(tmp), engine="H2Local")
            code, out, _ = self._run(["host", "deploy", "--app", str(app), "--ssh", "u@h", "--dry-run"])
            self.assertEqual(code, 0)
            self.assertNotIn("--profile backup", out)

    def test_domain_without_email_is_refused(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            app = _fake_app(Path(tmp))
            code, _, err = self._run(["host", "deploy", "--app", str(app), "--ssh", "u@h", "--domain", "a.example.com", "--dry-run"])
            self.assertNotEqual(code, 0)
            self.assertIn("--acme-email", err)

    def test_unbuilt_app_is_refused(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            app = _fake_app(Path(tmp))
            for jar in (app / "build" / "libs").glob("*.jar"):
                jar.unlink()
            code, _, err = self._run(["host", "deploy", "--app", str(app), "--ssh", "u@h", "--dry-run"])
            self.assertNotEqual(code, 0)
            self.assertIn("Build-FinalApp.ps1", err)


if __name__ == "__main__":
    unittest.main()
