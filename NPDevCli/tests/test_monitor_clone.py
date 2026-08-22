"""Tests for `npdev monitor clone` / `clone-remove` (R3.8 -- isolated instances for parallel testers).

Two layers, same split the rest of this test package uses:
  - Pure-function tests for `_detect_datasource_sharing`, the one judgment call this feature makes
    about whether a file copy can actually isolate a given app's database (ground truth is the
    BAKED `application-npdev-db.properties`, never `_ops/resolved-db-plan.json` -- see the long
    comment in `npdev_monitor.py` above the clone section for why).
  - Filesystem tests for `clone_app`/`remove_clone`/`reserve_clone_port` against synthetic app
    trees, and a couple of thin CLI-wiring tests through `npdev_cli.main`. All of them isolate the
    port-reservation registry via `NPDEV_MANAGER_HOME` pointed at a throwaway temp directory, so a
    run on a real developer machine never touches (or is confused by) the real registry.

The live, two-simultaneous-processes proof (write through one clone, read through the origin and a
second clone, confirm neither sees the other's row) was done by hand against the real
`npdev-canary` sample app -- that is not repeatable in a fast unit suite and is reported separately,
not asserted here.
"""

from __future__ import annotations

import json
import os
import sys
import threading
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import npdev_cli
import npdev_monitor


def _relative_h2_props(db_name: str = "demo") -> str:
    return (
        "npdev.database.engine=H2Local\n"
        f"npdev.database.requested-name={db_name}\n"
        f"npdev.database.resolved-name={db_name}\n"
        "npdev.database.data-root=data\n"
        f"spring.datasource.url=jdbc:h2:file:./data/{db_name};MODE=PostgreSQL;DB_CLOSE_ON_EXIT=FALSE\n"
        "spring.datasource.driver-class-name=org.h2.Driver\n"
        "spring.datasource.username=sa\n"
        "spring.datasource.password=\n"
    )


def _absolute_h2_props(shared_dir: str, db_name: str = "demo") -> str:
    return (
        "npdev.database.engine=H2Local\n"
        f"npdev.database.data-root={shared_dir}\n"
        f"spring.datasource.url=jdbc:h2:file:{shared_dir}/{db_name};MODE=PostgreSQL\n"
        "spring.datasource.driver-class-name=org.h2.Driver\n"
    )


def _postgres_props() -> str:
    return (
        "npdev.database.engine=Postgres\n"
        "spring.datasource.url=jdbc:postgresql://localhost:5432/finalexec\n"
        "spring.datasource.driver-class-name=org.postgresql.Driver\n"
    )


def make_clonable_app(root: Path, name: str = "demo-app", *, port: int = 8099,
                      db_props: str | None = None, legacy_shared: bool = True) -> Path:
    """A synthetic app tree with the two `_ops` layouts this module patches: an outer
    `_ops/app-plan.json` + `_ops/resolved-db-plan.json` (the AppGen wrapper level, always present)
    and, when `legacy_shared`, a SECOND `App/_ops/resolved-db-plan.json` plus a `Run-FinalApp.ps1`
    carrying the OLD generator's literal `--server.port=<N>` -- the shape `npdev-canary` still has on
    this machine, and the one collision no JSON field alone catches."""
    app = root / name
    final_app = (app / "App") if legacy_shared else app
    (app / "_ops").mkdir(parents=True)
    (final_app / "_ops").mkdir(parents=True, exist_ok=True)

    app_plan = {
        "appId": name, "appName": name, "outRoot": str(app), "appRoot": str(final_app),
        "serverPort": port, "apiKey": "dev-key", "springProfiles": "dev,trial",
        "baseUrl": f"http://localhost:{port}", "webSourceDir": "",
    }
    (app / "_ops" / "app-plan.json").write_text(json.dumps(app_plan), encoding="utf-8")

    outer_plan = {
        "engine": "H2Local", "appId": name, "serverPort": port, "apiKey": "dev-key", "hostPort": 9092,
        "resolvedDataRoot": str(final_app / "data"), "jdbcUrl": "", "resolvedDatabaseName": "demo",
        "appRoot": str(final_app),
    }
    (app / "_ops" / "resolved-db-plan.json").write_text(json.dumps(outer_plan), encoding="utf-8")

    if legacy_shared:
        inner_plan = {
            "appId": name, "serverPort": port, "engine": "H2Local", "physicalDatabase": True,
            "finalAppPath": ".", "resolvedDataRoot": "data", "requestedDatabaseName": "demo",
            "resolvedDatabaseName": "demo", "databaseInstanceId": name,
            "jdbcUrl": "jdbc:h2:file:./data/demo;MODE=PostgreSQL", "containerName": "",
            "hostPort": 0, "dbeaver": {"database": "data/demo", "host": "", "port": 0},
        }
        (final_app / "_ops" / "resolved-db-plan.json").write_text(json.dumps(inner_plan), encoding="utf-8")
        run_script = (
            "$plan = Get-Content -Raw (Join-Path $PSScriptRoot 'resolved-db-plan.json') | ConvertFrom-Json\n"
            f"java -jar (Join-Path $appRoot 'build\\libs\\FinalExec.jar') --server.port={port} "
            "\"--spring.profiles.active=dev\"\n"
        )
        (final_app / "_ops" / "Run-FinalApp.ps1").write_text(run_script, encoding="utf-8")
        (final_app / "_ops" / "run-final-app.sh").write_text(
            f'java -jar "$APP_ROOT/build/libs/FinalExec.jar" --server.port={port} "--spring.profiles.active=$PROFILE"\n',
            encoding="utf-8")

    props_dir = final_app / "npdev-generated" / "src" / "main" / "resources"
    props_dir.mkdir(parents=True, exist_ok=True)
    (props_dir / "application-npdev-db.properties").write_text(
        db_props if db_props is not None else _relative_h2_props(), encoding="utf-8")

    for spared in ("data", "logs", "secrets"):
        d = final_app / spared
        d.mkdir(parents=True, exist_ok=True)
        (d / "placeholder.txt").write_text("origin-only content", encoding="utf-8")

    build_libs = final_app / "build" / "libs"
    build_libs.mkdir(parents=True, exist_ok=True)
    (build_libs / "FinalExec.jar").write_bytes(b"not-a-real-jar")

    return app


class DetectDatasourceSharing(unittest.TestCase):
    """`_detect_datasource_sharing` is the ONE gate deciding whether `clone_app` is allowed to
    proceed. Ground truth is the baked properties file, not the `_ops` plan -- these assert each
    of the shapes measured on real apps on this machine."""

    def test_relative_h2_is_safe(self):
        with TemporaryDirectory() as tmp:
            app = make_clonable_app(Path(tmp))
            safe, reason = npdev_monitor._detect_datasource_sharing(app)
            self.assertTrue(safe, reason)

    def test_absolute_h2_shared_path_is_unsafe(self):
        with TemporaryDirectory() as tmp:
            shared = str(Path(tmp) / "shared-databases")
            app = make_clonable_app(Path(tmp), db_props=_absolute_h2_props(shared))
            safe, reason = npdev_monitor._detect_datasource_sharing(app)
            self.assertFalse(safe)
            self.assertIn("ABSOLUTE", reason)

    def test_network_jdbc_url_is_unsafe(self):
        with TemporaryDirectory() as tmp:
            app = make_clonable_app(Path(tmp), db_props=_postgres_props())
            safe, reason = npdev_monitor._detect_datasource_sharing(app)
            self.assertFalse(safe)
            self.assertIn("network", reason)

    def test_missing_properties_file_is_unsafe(self):
        with TemporaryDirectory() as tmp:
            app = Path(tmp) / "bare"
            (app / "_ops").mkdir(parents=True)
            safe, reason = npdev_monitor._detect_datasource_sharing(app)
            self.assertFalse(safe)
            self.assertIn("no application-npdev-db.properties", reason)


class ClonePortReservation(unittest.TestCase):
    """Port races -- the item's own non-negotiable. Two clones made back-to-back (or truly
    concurrently) must never receive the same port, even though NEITHER has started listening yet,
    which is why a bare bind-test cannot be the whole mechanism (see `reserve_clone_port`'s
    docstring)."""

    def _home(self, tmp: str):
        return mock.patch.dict(os.environ, {"NPDEV_MANAGER_HOME": str(Path(tmp) / "npdev-home")})

    def test_sequential_calls_never_collide(self):
        with TemporaryDirectory() as tmp, self._home(tmp):
            first = npdev_monitor.reserve_clone_port(
                clone_path=Path(tmp) / "clone-a", purpose="serverPort", port_range=(23100, 23120))
            second = npdev_monitor.reserve_clone_port(
                clone_path=Path(tmp) / "clone-b", purpose="serverPort", port_range=(23100, 23120))
            self.assertNotEqual(first, second)

    def test_concurrent_calls_never_collide(self):
        # The scenario the item names explicitly: several `clone` calls in flight AT THE SAME TIME,
        # none of them listening on their candidate yet (so a plain bind-test cannot tell them
        # apart) -- only the file-locked registry can. Real threads, real file lock, real sockets.
        with TemporaryDirectory() as tmp, self._home(tmp):
            results: list[int] = []
            errors: list[Exception] = []
            lock = threading.Lock()

            def worker(i: int) -> None:
                try:
                    port = npdev_monitor.reserve_clone_port(
                        clone_path=Path(tmp) / f"clone-{i}", purpose="serverPort",
                        port_range=(23200, 23260))
                    with lock:
                        results.append(port)
                except Exception as exc:  # pragma: no cover - failure path surfaced via assertion
                    with lock:
                        errors.append(exc)

            threads = [threading.Thread(target=worker, args=(i,)) for i in range(12)]
            for t in threads:
                t.start()
            for t in threads:
                t.join(timeout=15)

            self.assertEqual(errors, [])
            self.assertEqual(len(results), 12)
            self.assertEqual(len(set(results)), 12, f"duplicate port(s) handed out: {results}")

    def test_exact_port_conflict_is_refused(self):
        with TemporaryDirectory() as tmp, self._home(tmp):
            npdev_monitor.reserve_clone_port(
                clone_path=Path(tmp) / "clone-a", purpose="serverPort", exact_port=23301)
            with self.assertRaises(RuntimeError):
                npdev_monitor.reserve_clone_port(
                    clone_path=Path(tmp) / "clone-b", purpose="serverPort", exact_port=23301)

    def test_release_only_removes_that_clones_reservations(self):
        with TemporaryDirectory() as tmp, self._home(tmp):
            clone_a = Path(tmp) / "clone-a"
            clone_b = Path(tmp) / "clone-b"
            npdev_monitor.reserve_clone_port(clone_path=clone_a, purpose="serverPort", exact_port=23310)
            npdev_monitor.reserve_clone_port(clone_path=clone_b, purpose="serverPort", exact_port=23311)
            removed = npdev_monitor.release_clone_ports(clone_a)
            self.assertEqual(removed, 1)
            # clone_a's port is free again; clone_b's is still held.
            npdev_monitor.reserve_clone_port(clone_path=clone_a, purpose="serverPort", exact_port=23310)
            with self.assertRaises(RuntimeError):
                npdev_monitor.reserve_clone_port(clone_path=Path(tmp) / "clone-c",
                                                 purpose="serverPort", exact_port=23311)


class CloneApp(unittest.TestCase):
    def _home(self, tmp: str):
        return mock.patch.dict(os.environ, {"NPDEV_MANAGER_HOME": str(Path(tmp) / "npdev-home")})

    def test_clone_is_isolated_and_identity_rewritten(self):
        with TemporaryDirectory() as tmp, self._home(tmp):
            origin = make_clonable_app(Path(tmp) / "origin-root", port=8099)
            dest_root = Path(tmp) / "clones"
            result = npdev_monitor.clone_app(origin, dest_root, port_range=(23400, 23420))

            self.assertTrue(result["ok"])
            self.assertNotEqual(result["port"], 8099)
            self.assertTrue(23400 <= result["port"] <= 23420)
            clone_dir = Path(result["cloneDir"])
            self.assertTrue(clone_dir.is_dir())

            # The literal port collision (npdev-canary's own shape) is fixed in BOTH launcher scripts.
            run_ps1 = (clone_dir / "App" / "_ops" / "Run-FinalApp.ps1").read_text(encoding="utf-8")
            self.assertIn(f"--server.port={result['port']}", run_ps1)
            self.assertNotIn("--server.port=8099", run_ps1)
            run_sh = (clone_dir / "App" / "_ops" / "run-final-app.sh").read_text(encoding="utf-8")
            self.assertIn(f"--server.port={result['port']}", run_sh)

            # The JSON plans agree with the launcher scripts.
            outer_plan = json.loads((clone_dir / "_ops" / "app-plan.json").read_text(encoding="utf-8"))
            self.assertEqual(outer_plan["serverPort"], result["port"])
            self.assertEqual(Path(outer_plan["appRoot"]), clone_dir / "App")
            inner_plan = json.loads(
                (clone_dir / "App" / "_ops" / "resolved-db-plan.json").read_text(encoding="utf-8"))
            self.assertEqual(inner_plan["serverPort"], result["port"])

            # data/logs/secrets are EMPTY in the clone -- the origin's placeholder content did not
            # travel across, matching CLAUDE.md's spared-on-regen list.
            for spared in ("data", "logs", "secrets"):
                spared_dir = clone_dir / "App" / spared
                self.assertTrue(spared_dir.is_dir())
                self.assertEqual(list(spared_dir.iterdir()), [])
            # ... and the ORIGIN's own placeholder content is untouched.
            origin_data = (origin / "App" / "data" / "placeholder.txt").read_text(encoding="utf-8")
            self.assertEqual(origin_data, "origin-only content")

            # A generic build artifact travels across unmodified (never rewritten as text).
            jar = clone_dir / "App" / "build" / "libs" / "FinalExec.jar"
            self.assertEqual(jar.read_bytes(), b"not-a-real-jar")

            # The clone is identifiable as a clone, and `probe_app` surfaces it the same way
            # `monitor scan` would.
            info = json.loads((clone_dir / "_ops" / "npdev-clone.json").read_text(encoding="utf-8"))
            self.assertEqual(info["originAppDir"], str(origin.resolve()))
            self.assertEqual(info["originPort"], 8099)
            record = npdev_monitor.probe_app(clone_dir)
            self.assertIsNotNone(record["clone"])
            self.assertEqual(record["clone"]["originAppDir"], str(origin.resolve()))

            # ... while the ORIGIN itself is not a clone of anything.
            origin_record = npdev_monitor.probe_app(origin)
            self.assertIsNone(origin_record["clone"])

    def test_two_clones_in_a_row_get_different_ports(self):
        with TemporaryDirectory() as tmp, self._home(tmp):
            origin = make_clonable_app(Path(tmp) / "origin-root", port=8099)
            dest_root = Path(tmp) / "clones"
            first = npdev_monitor.clone_app(origin, dest_root, port_range=(23500, 23520))
            second = npdev_monitor.clone_app(origin, dest_root, port_range=(23500, 23520))
            self.assertNotEqual(first["port"], second["port"])
            self.assertNotEqual(first["cloneDir"], second["cloneDir"])

    def test_refuses_unsafe_datasource_and_leaves_no_directory(self):
        with TemporaryDirectory() as tmp, self._home(tmp):
            shared = str(Path(tmp) / "shared-databases")
            origin = make_clonable_app(Path(tmp) / "origin-root", db_props=_absolute_h2_props(shared))
            dest_root = Path(tmp) / "clones"
            with self.assertRaises(ValueError) as ctx:
                npdev_monitor.clone_app(origin, dest_root)
            self.assertIn("refusing to clone", str(ctx.exception))
            self.assertFalse(dest_root.exists() and any(dest_root.iterdir()))

    def test_refuses_non_app_directory(self):
        with TemporaryDirectory() as tmp, self._home(tmp):
            not_an_app = Path(tmp) / "not-an-app"
            not_an_app.mkdir()
            with self.assertRaises(ValueError):
                npdev_monitor.clone_app(not_an_app, Path(tmp) / "clones")

    def test_invalid_explicit_name_is_refused(self):
        with TemporaryDirectory() as tmp, self._home(tmp):
            origin = make_clonable_app(Path(tmp) / "origin-root")
            with self.assertRaises(ValueError):
                npdev_monitor.clone_app(origin, Path(tmp) / "clones", name="../escape")

    def test_existing_destination_is_refused(self):
        with TemporaryDirectory() as tmp, self._home(tmp):
            origin = make_clonable_app(Path(tmp) / "origin-root")
            dest_root = Path(tmp) / "clones"
            (dest_root / "taken").mkdir(parents=True)
            with self.assertRaises(FileExistsError):
                npdev_monitor.clone_app(origin, dest_root, name="taken")

    def test_failed_reservation_rolls_back_partial_copy(self):
        # An explicit --port that is already taken must fail BEFORE any directory is left behind --
        # a half-cloned directory would be picked up by `monitor scan` as a real, broken app.
        with TemporaryDirectory() as tmp, self._home(tmp):
            origin = make_clonable_app(Path(tmp) / "origin-root")
            dest_root = Path(tmp) / "clones"
            npdev_monitor.reserve_clone_port(
                clone_path=Path(tmp) / "someone-else", purpose="serverPort", exact_port=23601)
            with self.assertRaises(RuntimeError):
                npdev_monitor.clone_app(origin, dest_root, name="conflict", port=23601)
            self.assertFalse((dest_root / "conflict").exists())


class RemoveClone(unittest.TestCase):
    def _home(self, tmp: str):
        return mock.patch.dict(os.environ, {"NPDEV_MANAGER_HOME": str(Path(tmp) / "npdev-home")})

    def test_refuses_directory_without_marker(self):
        with TemporaryDirectory() as tmp, self._home(tmp):
            origin = make_clonable_app(Path(tmp) / "origin-root")
            with self.assertRaises(ValueError):
                npdev_monitor.remove_clone(origin)
            self.assertTrue(origin.is_dir())  # never touched

    def test_refuses_while_port_is_listening(self):
        with TemporaryDirectory() as tmp, self._home(tmp):
            origin = make_clonable_app(Path(tmp) / "origin-root")
            result = npdev_monitor.clone_app(origin, Path(tmp) / "clones", port_range=(23700, 23710))
            with mock.patch.object(npdev_monitor, "_tcp_open", return_value=True):
                with self.assertRaises(RuntimeError):
                    npdev_monitor.remove_clone(Path(result["cloneDir"]))
            self.assertTrue(Path(result["cloneDir"]).is_dir())

    def test_removes_and_releases_port(self):
        with TemporaryDirectory() as tmp, self._home(tmp):
            origin = make_clonable_app(Path(tmp) / "origin-root")
            result = npdev_monitor.clone_app(origin, Path(tmp) / "clones", port_range=(23720, 23730))
            clone_dir = Path(result["cloneDir"])
            removed = npdev_monitor.remove_clone(clone_dir)
            self.assertTrue(removed["ok"])
            self.assertFalse(clone_dir.exists())
            self.assertEqual(removed["portsReleased"], 1)
            # The port is free again for a new clone.
            second = npdev_monitor.clone_app(origin, Path(tmp) / "clones",
                                             name="reuse", port=result["port"])
            self.assertEqual(second["port"], result["port"])

            # The origin is completely unaffected by the whole create-then-remove cycle.
            self.assertTrue(origin.is_dir())
            self.assertTrue((origin / "App" / "data" / "placeholder.txt").is_file())


class CloneCliWiring(unittest.TestCase):
    """Thin smoke tests through `npdev_cli.main` -- the JSON contract `_run_monitor_clone`/
    `_run_monitor_clone_remove` promise, and that a caller error comes back as `ok: false` / exit 2
    rather than a traceback."""

    def _home(self, tmp: str):
        return mock.patch.dict(os.environ, {"NPDEV_MANAGER_HOME": str(Path(tmp) / "npdev-home")})

    def test_clone_and_clone_remove_round_trip(self):
        with TemporaryDirectory() as tmp, self._home(tmp):
            origin = make_clonable_app(Path(tmp) / "origin-root")
            dest_root = Path(tmp) / "clones"
            buf_out = []
            with mock.patch("builtins.print", side_effect=lambda *a, **k: buf_out.append(" ".join(map(str, a)))):
                code = npdev_cli.main(["monitor", "clone", "--app-dir", str(origin),
                                       "--dest-root", str(dest_root), "--json"])
            self.assertEqual(code, 0)
            payload = json.loads(buf_out[-1])
            self.assertTrue(payload["ok"])
            clone_dir = payload["cloneDir"]

            buf_out.clear()
            with mock.patch("builtins.print", side_effect=lambda *a, **k: buf_out.append(" ".join(map(str, a)))):
                code = npdev_cli.main(["monitor", "clone-remove", "--clone-dir", clone_dir, "--json"])
            self.assertEqual(code, 0)
            payload = json.loads(buf_out[-1])
            self.assertTrue(payload["ok"])
            self.assertFalse(Path(clone_dir).exists())

    def test_clone_of_unsafe_app_is_ok_false_exit_2(self):
        with TemporaryDirectory() as tmp, self._home(tmp):
            shared = str(Path(tmp) / "shared-databases")
            origin = make_clonable_app(Path(tmp) / "origin-root", db_props=_absolute_h2_props(shared))
            buf_out = []
            with mock.patch("builtins.print", side_effect=lambda *a, **k: buf_out.append(" ".join(map(str, a)))):
                code = npdev_cli.main(["monitor", "clone", "--app-dir", str(origin),
                                       "--dest-root", str(Path(tmp) / "clones"), "--json"])
            self.assertEqual(code, 2)
            payload = json.loads(buf_out[-1])
            self.assertFalse(payload["ok"])
            self.assertIn("refusing to clone", payload["error"]["message"])


if __name__ == "__main__":
    unittest.main()
