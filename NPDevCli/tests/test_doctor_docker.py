"""`npdev doctor`'s Docker check, for an app whose engine NPDev would containerize.

THE DEFECT THIS PINS. Docker is how NPDev CREATES a database for you. It is not how an app REACHES
one. Before this, an app on Postgres/MySQL/SQL Server on a machine with no Docker got a hard FAIL --
"NPDev creates its database in a container, so `npdev db start` cannot work without it" -- which is
true about `npdev db start` and false about the machine. A managed instance, or a PostgreSQL the
user installed themselves years ago, makes that app perfectly runnable with no Docker anywhere.
Doctor's exit code said the machine was broken when it was not.

THE FIX'S OWN HAZARD, which is why the second assertion block exists. The remedy is a port probe --
but a probe knows only that SOMETHING accepted a TCP connection on that port. It does not know it is
your database. So the message must state the inference and name both branches; otherwise this trades
one confident wrong answer for another, and this codebase has already shipped a doctor that reported
a missing database as a *credentials* failure. The assertions below check the message, not just the
status, because the status alone was never the whole defect.

Run with:
    python -m unittest NPDevCli.tests.test_doctor_docker -v
"""

from __future__ import annotations

import argparse
import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402


class DockerPresentCheckTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory(prefix="npdev-doctor-docker-")
        self.app = Path(self._tmp.name)
        self._write_app()
        self._real_which = npdev_cli.shutil.which
        self._real_probe = npdev_cli._probe_tcp
        self._real_db_checks = npdev_cli._database_checks
        # The five database checks are a separate surface with their own reasons; left live they
        # would run a real JDBC probe against localhost:5432 from a unit test, which is both slow
        # and dependent on what happens to be running on the machine.
        npdev_cli._database_checks = lambda app_path: []

    def tearDown(self) -> None:
        npdev_cli.shutil.which = self._real_which
        npdev_cli._probe_tcp = self._real_probe
        npdev_cli._database_checks = self._real_db_checks
        self._tmp.cleanup()

    def _write_app(self, engine: str = "Postgres", port: int = 5432) -> None:
        (self.app / "db.definition.json").write_text(json.dumps({
            "database": {
                "engine": engine,
                "host": "localhost",
                "port": port,
                "databaseName": "myapp",
                "username": "npdev",
                "password": "npdev",
            }
        }, indent=2), encoding="utf-8")

    def _docker_check(self, *, docker_installed: bool, port_answers: bool) -> dict:
        real_which = self._real_which

        def fake_which(name, *args, **kwargs):
            if name == "docker" and not docker_installed:
                return None
            return real_which(name, *args, **kwargs)

        self.probed: list[tuple[str, int]] = []

        def fake_probe(host, port, timeout=3.0):
            self.probed.append((host, port))
            return None if port_answers else OSError("connection refused")

        npdev_cli.shutil.which = fake_which
        npdev_cli._probe_tcp = fake_probe

        buffer = io.StringIO()
        with contextlib.redirect_stdout(buffer):
            npdev_cli.run_doctor(argparse.Namespace(app=str(self.app), json=True))
        payload = json.loads(buffer.getvalue())
        matches = [c for c in payload["checks"] if c["id"] == "docker-present"]
        self.assertEqual(len(matches), 1, "docker-present must be reported exactly once")
        return matches[0]

    def test_no_docker_but_something_is_serving_is_a_warning_that_states_the_inference(self) -> None:
        check = self._docker_check(docker_installed=False, port_answers=True)

        self.assertEqual(check["status"], "warn",
                         "an externally-provisioned database is not a broken machine")
        self.assertEqual(self.probed, [("localhost", 5432)],
                         "the probe must use THIS app's declared host/port, not a default")

        detail = check["detail"]
        # Both branches named, neither assumed.
        self.assertIn("Something is already serving on localhost:5432", detail)
        # The engine's own externalName from the registry ("Postgres"), the same string every other
        # doctor message uses -- not a prettier synonym invented here.
        self.assertIn("if that is your own Postgres", detail)
        self.assertIn("npdev db test-connection", detail)
        self.assertIn("install Docker or free the port", detail)
        # The inference must not harden into a claim about what is on that port.
        self.assertNotIn("your database is running", detail.lower())
        self.assertNotIn("postgresql is running", detail.lower())

    def test_a_warning_does_not_fail_the_run(self) -> None:
        # The point of the whole change: "doctor says broken" becomes "doctor says fine" for a
        # machine that is fine. A warn that still exited 1 would have changed nothing that matters.
        npdev_cli.shutil.which = self._real_which

        def fake_which(name, *args, **kwargs):
            return None if name == "docker" else self._real_which(name, *args, **kwargs)

        npdev_cli.shutil.which = fake_which
        npdev_cli._probe_tcp = lambda host, port, timeout=3.0: None

        buffer = io.StringIO()
        with contextlib.redirect_stdout(buffer):
            npdev_cli.run_doctor(argparse.Namespace(app=str(self.app), json=True))
        payload = json.loads(buffer.getvalue())
        docker = next(c for c in payload["checks"] if c["id"] == "docker-present")

        self.assertEqual(docker["status"], "warn")
        self.assertNotIn("docker-present", [c["id"] for c in payload["checks"] if c["status"] == "fail"])

    def test_no_docker_and_nothing_listening_still_fails(self) -> None:
        check = self._docker_check(docker_installed=False, port_answers=False)

        self.assertEqual(check["status"], "fail",
                         "no Docker and no server is exactly the case the check exists for")
        self.assertIn("Nothing is listening on localhost:5432", check["detail"])
        self.assertIn("db.definition.json", check["fix"])

    def test_docker_present_is_unchanged(self) -> None:
        check = self._docker_check(docker_installed=True, port_answers=False)

        self.assertEqual(check["status"], "pass")
        self.assertEqual(check["expected"], "required")
        self.assertEqual(self.probed, [], "no probe is needed when Docker is right there")

    def test_h2local_never_reaches_the_probe(self) -> None:
        self._write_app(engine="H2Local")

        check = self._docker_check(docker_installed=False, port_answers=False)

        self.assertEqual(check["status"], "warn")
        self.assertEqual(check["expected"], "optional",
                         "H2Local needs no server at all -- Docker is optional in the plain sense")
        self.assertEqual(self.probed, [], "an engine with no server has no port to probe")

    def test_a_non_default_port_is_the_one_probed(self) -> None:
        self._write_app(engine="MySQL", port=3307)

        check = self._docker_check(docker_installed=False, port_answers=True)

        self.assertEqual(self.probed, [("localhost", 3307)])
        self.assertIn("localhost:3307", check["detail"])
        self.assertIn("your own MySQL", check["detail"])


if __name__ == "__main__":
    unittest.main()
