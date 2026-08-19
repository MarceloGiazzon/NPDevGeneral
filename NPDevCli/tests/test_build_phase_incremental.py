"""R1.2 (incremental dev-loop builds): `_build_phase`'s `clean` parameter and its automatic
clean-build fallback.

Before this item, `_build_phase` always ran `gradlew --no-daemon --console=plain clean build -x
test` -- a fresh single-use JVM and a full recompile on every `npdev dev` save. The dev loop now
defaults to `clean=False`: an incremental `build -x test` on the generated app's own warm Gradle
daemon (`gradle.properties` already sets `org.gradle.daemon=true` -- see
`NPDevRuntimeHost/gradle.properties`'s REG-10 comment, copied verbatim into every generated
FinalApp), falling back to a clean build automatically -- and only once -- if the incremental build
fails. `run_app`'s one-shot GENERATE->BUILD->BOOT keeps the untouched default (`clean=True`), so
what is pinned here is that the ORIGINAL command is byte-for-byte unchanged for every existing
caller, and that the new incremental/fallback path never leaves a caller to remember a flag.

`_run_bounded` is stubbed rather than a real `gradlew` invoked: what this file owns is the command
`_build_phase` CONSTRUCTS and the fallback control flow, not whether Gradle itself succeeds -- that
is exactly what `scripts/hygiene/check-deterministic-generation.ps1` and a live `npdev dev` session
already prove for the real wrapper.

Run with:
    python -m unittest NPDevCli.tests.test_build_phase_incremental -v
"""

from __future__ import annotations

import os
import subprocess
import sys
import time
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402


def _completed(returncode: int, command: list[str]) -> subprocess.CompletedProcess:
    return subprocess.CompletedProcess(command, returncode, stdout=f"ran: {' '.join(command)}\n", stderr="")


def _make_app_root(tmp: Path) -> Path:
    """Just enough of a generated app for `_build_phase` to proceed past its own existence checks --
    a wrapper file (never executed; `_run_bounded` is stubbed) and a pre-existing jar so
    `_find_jar` succeeds on a "successful" stubbed build."""
    app_root = tmp / "app"
    app_root.mkdir()
    wrapper_name = "gradlew.bat" if os.name == "nt" else "gradlew"
    (app_root / wrapper_name).write_text("", encoding="utf-8")
    libs = app_root / "build" / "libs"
    libs.mkdir(parents=True)
    (libs / "FinalExec-0.1.0.jar").write_bytes(b"")
    return app_root


class BuildPhaseIncrementalTest(unittest.TestCase):
    def test_clean_default_keeps_the_original_command_byte_for_byte(self):
        """`run_app` (and anyone else who does not pass `clean=`) must see the EXACT command that
        shipped before this item -- a fresh checkout's one-shot build is not the surface R1.2 changes."""
        with TemporaryDirectory() as tmp:
            app_root = _make_app_root(Path(tmp))
            calls: list[list[str]] = []

            def fake_run_bounded(command, cwd, deadline, **kwargs):
                calls.append(command)
                return _completed(0, command)

            with mock.patch.object(npdev_cli, "_run_bounded", side_effect=fake_run_bounded):
                ok, _output, jar, fell_back = npdev_cli._build_phase(app_root, time.monotonic() + 60)

            self.assertTrue(ok)
            self.assertIsNotNone(jar)
            self.assertFalse(fell_back)
            self.assertEqual(1, len(calls))
            self.assertEqual(
                ["--no-daemon", "--console=plain", "clean", "build", "-x", "test"], calls[0][1:])

    def test_incremental_default_drops_clean_and_no_daemon(self):
        with TemporaryDirectory() as tmp:
            app_root = _make_app_root(Path(tmp))
            calls: list[list[str]] = []

            def fake_run_bounded(command, cwd, deadline, **kwargs):
                calls.append(command)
                return _completed(0, command)

            with mock.patch.object(npdev_cli, "_run_bounded", side_effect=fake_run_bounded):
                ok, _output, jar, fell_back = npdev_cli._build_phase(
                    app_root, time.monotonic() + 60, clean=False)

            self.assertTrue(ok)
            self.assertIsNotNone(jar)
            self.assertFalse(fell_back)
            self.assertEqual(1, len(calls))
            self.assertEqual(["--console=plain", "build", "-x", "test"], calls[0][1:])

    def test_a_failed_incremental_build_falls_back_to_clean_automatically(self):
        with TemporaryDirectory() as tmp:
            app_root = _make_app_root(Path(tmp))
            calls: list[list[str]] = []

            def fake_run_bounded(command, cwd, deadline, **kwargs):
                calls.append(command)
                # First call (incremental) fails; the automatic retry (clean) succeeds.
                return _completed(1 if len(calls) == 1 else 0, command)

            with mock.patch.object(npdev_cli, "_run_bounded", side_effect=fake_run_bounded):
                ok, output, jar, fell_back = npdev_cli._build_phase(
                    app_root, time.monotonic() + 60, clean=False)

            self.assertTrue(
                ok, "the automatic clean-build fallback must recover from a stale incremental "
                    "failure without the caller doing anything")
            self.assertIsNotNone(jar)
            self.assertTrue(fell_back)
            self.assertEqual(2, len(calls), "must retry exactly once, not loop")
            self.assertNotIn("clean", calls[0][1:])
            self.assertIn("clean", calls[1][1:])
            self.assertNotIn(
                "--no-daemon", calls[1][1:],
                "the fallback still belongs to the incremental session -- it must keep using the "
                "warm daemon, not fork a fifth JVM on top of an already-failed cycle")
            self.assertIn("incremental build failed", output)

    def test_a_failed_clean_build_never_retries(self):
        """`clean=True` is the caller already asking for a clean build -- a second one would not fix
        anything the first did not, so there must be exactly one attempt, same as before this item."""
        with TemporaryDirectory() as tmp:
            app_root = _make_app_root(Path(tmp))
            calls: list[list[str]] = []

            def fake_run_bounded(command, cwd, deadline, **kwargs):
                calls.append(command)
                return _completed(1, command)

            with mock.patch.object(npdev_cli, "_run_bounded", side_effect=fake_run_bounded):
                ok, _output, jar, fell_back = npdev_cli._build_phase(app_root, time.monotonic() + 60)

            self.assertFalse(ok)
            self.assertIsNone(jar)
            self.assertFalse(fell_back)
            self.assertEqual(1, len(calls))


if __name__ == "__main__":
    unittest.main()
