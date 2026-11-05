"""Tests for `npdev db provision` (the New-app screen's "create a database container for me").

What is worth pinning:

  1. **The recipe is the app toolbox's recipe.** Image, engine arguments (MySQL's utf8mb4 -- without it
     emoji are silently truncated) and the SQL Server env must equal `engine-profiles.json`, the file
     rule E15 says engine facts live in. This copy exists because the CLI cannot read a generator
     resource; this test is what stops it drifting.
  2. **Nothing it creates can collide or leak.** Free port, free name, published on 127.0.0.1 only, a
     generated password that satisfies SQL Server's strong-password policy.
  3. **It never lies about ready, and never leaves wreckage.** A container that exits while starting, or
     never answers, is removed and reported with Docker's own log lines.

Run with:
    python -m unittest NPDevCli.tests.test_db_provision -v
"""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_db_provision as dp  # noqa: E402
import npdev_engines  # noqa: E402

REPO = Path(__file__).resolve().parents[2]
PROFILES = REPO / "NPDevGenerator" / "generator" / "src" / "main" / "resources" / "npdev" / "engine-profiles.json"
PROFILE_NAME = {"postgres": "Postgres", "mysql": "MySQL", "sqlserver": "SqlServer"}


class FakeDocker:
    """Records every call; `script` maps the first docker word to a (code, out, err) or a callable."""

    def __init__(self, ps_rows=(), image_present=True, ready_after=0, state="running"):
        self.calls: list[list[str]] = []
        self.ps_rows = list(ps_rows)
        self.image_present = image_present
        self.ready_after = ready_after
        self.state = state
        self.exec_calls = 0

    def __call__(self, args, timeout):
        self.calls.append(list(args))
        verb = args[0]
        if verb == "ps":
            return 0, "\n".join(json.dumps(r) for r in self.ps_rows), ""
        if verb == "image":
            return (0 if self.image_present else 1), "", ""
        if verb == "pull":
            return 0, "", ""
        if verb == "run":
            return 0, "abc123\n", ""
        if verb == "exec":
            self.exec_calls += 1
            return (0 if self.exec_calls > self.ready_after else 1), "", ""
        if verb == "inspect":
            return 0, self.state + "\n", ""
        if verb == "logs":
            return 0, "line-one\nline-two\n", ""
        if verb == "rm":
            return 0, "", ""
        raise AssertionError(f"unexpected docker call {args}")

    def verbs(self):
        return [c[0] for c in self.calls]


def _provision(engine="mysql", docker=None, listening=lambda h, p, t: False, **kw):
    return dp.provision(engine, docker=docker or FakeDocker(), listening=listening,
                        sleep=lambda s: None, clock=_Clock(), **kw)


class _Clock:
    """Advances 10s per read so a never-ready loop reaches its deadline without sleeping."""

    def __init__(self):
        self.now = 0.0

    def __call__(self):
        self.now += 10.0
        return self.now


class RecipeMatchesEngineProfiles(unittest.TestCase):
    def setUp(self):
        self.profiles = json.loads(PROFILES.read_text(encoding="utf-8"))["engines"]

    def test_every_containerized_engine_has_a_recipe_and_nothing_else_does(self):
        containerized = {k for k in npdev_engines.engine_keys() if npdev_engines.resolve(k)["containerized"]}
        self.assertEqual(containerized, set(dp.CONTAINERS))

    def test_image_port_and_engine_arguments_equal_the_profile(self):
        for key, spec in dp.CONTAINERS.items():
            profile = self.profiles[PROFILE_NAME[key]]
            self.assertEqual(profile["image"], spec["image"], key)
            self.assertEqual(profile["defaultPort"], spec["containerPort"], key)
            self.assertEqual(profile["extraRunArgs"], spec["runArgs"], key)
            self.assertEqual(npdev_engines.resolve(key)["port"], spec["containerPort"], key)

    def test_every_variable_the_profile_sets_for_the_server_is_set_here_too(self):
        # The profile also sets a per-app user for Postgres/MySQL; a fresh container here has only the
        # admin account. Everything else (SQL Server's EULA/PID, the database) must still be present.
        admin_only = {"POSTGRES_USER", "MYSQL_USER", "MYSQL_PASSWORD"}
        for key, spec in dp.CONTAINERS.items():
            profile_env = set(self.profiles[PROFILE_NAME[key]]["containerEnv"])
            self.assertEqual(set(), (profile_env - admin_only) - set(spec["env"]), key)

    def test_admin_user_and_ready_timeout_equal_the_profile(self):
        for key, spec in dp.CONTAINERS.items():
            profile = self.profiles[PROFILE_NAME[key]]
            if profile["adminUser"] != "{username}":  # Postgres' admin is whatever POSTGRES_USER says
                self.assertEqual(profile["adminUser"], spec["adminUser"], key)
            self.assertEqual(profile["readyProbe"]["timeoutSeconds"], spec["ready"]["timeoutSeconds"], key)

    def test_readiness_goes_through_tcp_not_the_unix_socket(self):
        # Postgres and MySQL both start a temporary socket-only server to initialise, then restart for real;
        # a probe over the socket answers early. (Found live: pg_isready passed, then 'connection refused'.)
        for key in ("postgres", "mysql"):
            self.assertIn("127.0.0.1", dp.CONTAINERS[key]["ready"]["exec"], key)

    def test_mysql_is_utf8mb4(self):
        self.assertIn("--character-set-server=utf8mb4", dp.CONTAINERS["mysql"]["runArgs"])


class Password(unittest.TestCase):
    def test_satisfies_sql_server_policy_every_time(self):
        for _ in range(200):
            pw = dp.new_password()
            self.assertEqual(24, len(pw))
            self.assertTrue(pw.isalnum())
            self.assertTrue(any(c.isupper() for c in pw) and any(c.islower() for c in pw)
                            and any(c.isdigit() for c in pw), pw)

    def test_not_repeated(self):
        self.assertEqual(200, len({dp.new_password() for _ in range(200)}))


class Choosing(unittest.TestCase):
    def test_default_port_when_free(self):
        self.assertEqual(3306, dp.pick_port(3306, None, set(), lambda h, p, t: False))

    def test_skips_a_listening_port_and_a_port_a_stopped_container_published(self):
        self.assertEqual(3308, dp.pick_port(3306, None, {3307}, lambda h, p, t: p == 3306))

    def test_requested_port_that_is_busy_is_a_refusal_not_a_substitution(self):
        with self.assertRaises(dp.ProvisionError) as ctx:
            dp.pick_port(3306, 3306, {3306}, lambda h, p, t: False)
        self.assertEqual("port-in-use", ctx.exception.code)

    def test_name_counts_up(self):
        self.assertEqual("npdev-mysql", dp.pick_name("mysql", None, set()))
        self.assertEqual("npdev-mysql-3", dp.pick_name("mysql", None, {"npdev-mysql", "npdev-mysql-2"}))

    def test_requested_name_taken_or_malformed_is_refused(self):
        with self.assertRaises(dp.ProvisionError) as taken:
            dp.pick_name("mysql", "mine", {"mine"})
        self.assertEqual("name-taken", taken.exception.code)
        with self.assertRaises(dp.ProvisionError) as bad:
            dp.pick_name("mysql", "-rm -rf", set())
        self.assertEqual("bad-name", bad.exception.code)


class Provisioning(unittest.TestCase):
    def test_mysql_end_to_end_arguments(self):
        docker = FakeDocker()
        result = _provision("mysql", docker, app_name="My Shop", password="Pw1Pw1Pw1Pw1Pw1Pw1Pw1Pw")
        run = next(c for c in docker.calls if c[0] == "run")
        self.assertIn("127.0.0.1:3306:3306", run)  # never published to the LAN
        self.assertIn("unless-stopped", run)
        self.assertIn("MYSQL_DATABASE=my_shop", run)
        self.assertIn("MYSQL_ROOT_PASSWORD=Pw1Pw1Pw1Pw1Pw1Pw1Pw1Pw", run)
        self.assertEqual("mysql:8.4", run[run.index("mysql:8.4")])
        self.assertEqual(["--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci"],
                         run[run.index("mysql:8.4") + 1:])
        self.assertEqual({"engine": "mysql", "name": "npdev-mysql", "image": "mysql:8.4", "host": "127.0.0.1",
                          "port": 3306, "user": "root", "password": "Pw1Pw1Pw1Pw1Pw1Pw1Pw1Pw",
                          "database": "my_shop", "restartPolicy": "unless-stopped",
                          "imagePulled": False, "portChanged": False}, result)

    def test_database_name_is_the_one_init_gives_the_app(self):
        for name in ["my-app", "My App", "  ", "9lives", "ação"]:
            self.assertEqual(npdev_engines.database_name_for_app(name), _provision(app_name=name)["database"], name)

    def test_image_is_pulled_only_when_missing(self):
        have, missing = FakeDocker(image_present=True), FakeDocker(image_present=False)
        _provision("postgres", have)
        result = _provision("postgres", missing)
        self.assertNotIn("pull", have.verbs())
        self.assertIn("pull", missing.verbs())
        self.assertTrue(result["imagePulled"])

    def test_busy_default_port_moves_to_the_next_free_one_and_says_so(self):
        rows = [{"Names": "other", "Image": "mysql:5.7", "State": "exited", "Ports": "0.0.0.0:3306->3306/tcp"}]
        result = _provision("mysql", FakeDocker(ps_rows=rows))
        self.assertEqual(3307, result["port"])
        self.assertTrue(result["portChanged"])

    def test_existing_container_name_is_not_reused(self):
        rows = [{"Names": "npdev-mysql", "Image": "mysql:8.4", "State": "running", "Ports": ""}]
        self.assertEqual("npdev-mysql-2", _provision("mysql", FakeDocker(ps_rows=rows))["name"])

    def test_sqlserver_creates_its_database_after_ready_and_uses_sa(self):
        docker = FakeDocker()
        result = _provision("sqlserver", docker, app_name="crm")
        self.assertEqual("sa", result["user"])
        last_exec = [c for c in docker.calls if c[0] == "exec"][-1]
        self.assertIn("IF DB_ID('crm') IS NULL CREATE DATABASE [crm]", last_exec)
        # The password reaches sqlcmd through the environment, not the command line it shows in `ps`.
        self.assertTrue(any(a.startswith("SQLCMDPASSWORD=") for a in last_exec))
        self.assertNotIn("-P", last_exec)

    def test_waits_for_ready(self):
        docker = FakeDocker(ready_after=3)
        _provision("postgres", docker)
        self.assertEqual(4, docker.exec_calls)

    def test_never_ready_removes_the_container_and_shows_the_logs(self):
        docker = FakeDocker(ready_after=10_000)
        with self.assertRaises(dp.ProvisionError) as ctx:
            _provision("mysql", docker)
        self.assertEqual("not-ready", ctx.exception.code)
        self.assertIn("line-two", str(ctx.exception))
        self.assertIn(["rm", "-f", "npdev-mysql"], docker.calls)

    def test_container_that_exits_while_starting_fails_at_once(self):
        docker = FakeDocker(ready_after=10_000, state="exited")
        with self.assertRaises(dp.ProvisionError) as ctx:
            _provision("sqlserver", docker)
        self.assertEqual("not-ready", ctx.exception.code)
        self.assertEqual(1, docker.exec_calls)  # did not wait out the 180s
        self.assertIn(["rm", "-f", "npdev-sqlserver"], docker.calls)

    def test_docker_down_is_its_own_error(self):
        def down(args, timeout):
            return 1, "", "Cannot connect to the Docker daemon"

        with self.assertRaises(dp.ProvisionError) as ctx:
            _provision("mysql", down)
        self.assertEqual("docker-unavailable", ctx.exception.code)

    def test_h2_and_unknown_engines_are_refused(self):
        with self.assertRaises(dp.ProvisionError) as h2:
            _provision("h2local")
        self.assertEqual("not-containerized", h2.exception.code)
        with self.assertRaises(dp.ProvisionError) as nope:
            _provision("oracle")
        self.assertEqual("unknown-engine", nope.exception.code)

    def test_nothing_is_run_when_the_port_is_refused(self):
        docker = FakeDocker()
        with self.assertRaises(dp.ProvisionError):
            _provision("mysql", docker, port=3306, listening=lambda h, p, t: True)
        self.assertNotIn("run", docker.verbs())


if __name__ == "__main__":
    unittest.main()
