"""W1.3, 2026-08-10: the app must be STARTED with the Java it was BUILT with.

THE DEFECT, and why nothing here could see it
---------------------------------------------
`npdev run app`'s BOOT phase and `dev_loop.boot` both spawned a bare `["java", "-jar", ...]`, which
resolves through PATH and nothing else. The NPDev Manager's whole M3 thesis is that its private JDK
is handed to child processes as `JAVA_HOME` alone -- it never touches PATH, the registry, or any
system setting (`NPDevManager/src/npdev.rs`). Gradle honours JAVA_HOME, so validate/generate/build
all succeeded on the private JDK; the final step then looked somewhere the private JDK is not.

On a machine with no system Java the Run screen printed `restart ...` then `stopped.` and NOTHING
else, because `Popen` raised `FileNotFoundError` into a stderr stream the Manager discards. Silent,
at the last step, with a working JDK sitting right there unused -- which is why it had never been
reported by anyone.

These tests manipulate JAVA_HOME and PATH directly rather than mocking the resolver, because the
thing under test IS the resolution order.
"""

from __future__ import annotations

import os
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import dev_loop  # noqa: E402
import npdev_cli  # noqa: E402


class _Env:
    """Set env vars for the duration of a block and put every one of them back, present or absent."""

    def __init__(self, **values: str | None):
        self._values = values
        self._saved: dict[str, str | None] = {}

    def __enter__(self):
        for key, value in self._values.items():
            self._saved[key] = os.environ.get(key)
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value
        return self

    def __exit__(self, *_exc):
        for key, value in self._saved.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value
        return False


def _fake_jdk(root: Path) -> Path:
    """A directory shaped like a JDK. The launcher tests only ever ask whether the binary EXISTS."""
    binary = root / "bin" / ("java.exe" if os.name == "nt" else "java")
    binary.parent.mkdir(parents=True, exist_ok=True)
    binary.write_text("not a real jvm", encoding="utf-8")
    return binary


def _path_without_java() -> str:
    exe = "java.exe" if os.name == "nt" else "java"
    return os.pathsep.join(d for d in os.environ.get("PATH", "").split(os.pathsep)
                           if d and not (Path(d) / exe).exists())


class JavaLauncherTest(unittest.TestCase):

    def test_java_home_wins_over_path(self):
        with tempfile.TemporaryDirectory(prefix="npdev-jdk-") as temp:
            binary = _fake_jdk(Path(temp))
            with _Env(JAVA_HOME=temp):
                self.assertEqual(str(binary), npdev_cli.java_launcher(),
                                 "the Manager passes its private JDK as JAVA_HOME and nothing else; "
                                 "preferring PATH here is what made the private JDK unusable at the "
                                 "one step that matters")

    def test_falls_back_to_path_when_java_home_is_not_a_jdk(self):
        """A JAVA_HOME pointing at a directory with no bin/java is a misconfiguration, not a veto.

        Refusing outright would make this fix a NEW way to fail on a machine that used to work --
        the fix must be additive for everyone who already had a working PATH java.
        """
        if shutil.which("java") is None:
            self.skipTest("no java on PATH, so there is no fallback to observe")
        with tempfile.TemporaryDirectory(prefix="npdev-notjdk-") as temp:
            with _Env(JAVA_HOME=temp):
                self.assertEqual(shutil.which("java"), npdev_cli.java_launcher())

    def test_returns_none_when_there_is_no_java_anywhere(self):
        with _Env(JAVA_HOME=None, PATH=_path_without_java()):
            if shutil.which("java") is not None:
                self.skipTest("java still resolves after scrubbing PATH -- this test must not pass "
                              "by accident")
            self.assertIsNone(npdev_cli.java_launcher(),
                              "None is what lets the caller report a diagnostic instead of letting "
                              "Popen raise into a discarded stream")


class DevLoopBootWithoutJavaTest(unittest.TestCase):
    """The RED shape, pinned: boot with no java must fail with a REASON, not an empty log."""

    class _CliWithNoJava:
        @staticmethod
        def java_launcher():
            return None

    def test_boot_writes_a_reason_into_the_log_it_points_at(self):
        with tempfile.TemporaryDirectory(prefix="npdev-devloop-") as temp:
            root = Path(temp)
            options = dev_loop.DevOptions(
                model=root / "model.json", config=root / "config.json", output=root / "app")
            options.output.mkdir(parents=True, exist_ok=True)

            app = dev_loop.boot(options, root / "app.jar", self._CliWithNoJava())

            self.assertIsNone(app, "no java means no app -- and the previous build must survive")
            self.assertTrue(options.app_log.exists(),
                            "run_cycle tells the user 'boot failed -- see <log>'; before this fix "
                            "that log did not exist at all, because Popen raised past its creation")
            text = options.app_log.read_text(encoding="utf-8")
            self.assertIn("no Java runtime found", text)
            self.assertIn("JAVA_HOME", text)


class DoctorGitIsOptionalTest(unittest.TestCase):
    """W1.2's other half: `git-present` warns, it does not fail.

    docs/MANAGER.md advertises a machine with no git and the Manager never installs one, while this
    check used to take the whole Ready screen red. `_scrapforai_check`'s docstring already states the
    rule this now follows -- "a doctor that goes red over an optional tool teaches people to ignore
    red" -- and MANAGER.md's own check table has described git as history-only the entire time.
    """

    def test_git_present_check_is_a_warning_when_git_is_absent(self):
        with _Env(PATH=os.pathsep.join(
                d for d in os.environ.get("PATH", "").split(os.pathsep)
                if d and not (Path(d) / ("git.exe" if os.name == "nt" else "git")).exists())):
            if shutil.which("git") is not None:
                self.skipTest("git still resolves after scrubbing PATH -- this test must not pass "
                              "by accident")
            git_check = npdev_cli._git_present_check()

        self.assertEqual("warn", git_check["status"])
        self.assertNotIn("required", (git_check["detail"] or "").lower(),
                         "the old detail claimed git was required to clone NPDev and for `npdev "
                         "init`; neither is true, and a wrong reason is worse than none")

    def test_git_present_still_passes_when_git_is_installed(self):
        if shutil.which("git") is None:
            self.skipTest("git is not installed here")
        self.assertEqual("pass", npdev_cli._git_present_check()["status"])


if __name__ == "__main__":
    unittest.main()
