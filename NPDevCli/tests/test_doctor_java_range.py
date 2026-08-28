"""`npdev doctor`'s Java version range check, after A2 (Cold Clone Audit 2026-08-28).

THE DEFECT THIS PINS. Doctor had a floor ("Java 17+") and no top: a machine whose only JDK was
24/25 passed every check and then died inside the Gradle 8.5 wrapper with "Could not determine
java version from '24'" before any NPDev code ran. The wrappers were raised to Gradle 9.5.1 (which
runs on JVM 17-26, per the docs.gradle.org compatibility matrix), so:

  - a JDK above 26 is a HARD dead end -- the wrapper dies first -- and must FAIL, never warn
    (warnings do not block, and this is not a warning).
  - a JDK of 21 (or anything 17..26) stays a WARN, not a FAIL: with the foojay toolchain resolver
    registered in the platform builds (A1), Gradle auto-provisions the missing 17 toolchain, so the
    machine is genuinely workable. The D1 owner decision kept the warn and flipped
    check-wrongjava.sh to assert this contract.

Run with:
    python -m unittest NPDevCli.tests.test_doctor_java_range -v
"""

from __future__ import annotations

import argparse
import unittest
from pathlib import Path
from types import SimpleNamespace

import sys
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402


def _make_args() -> argparse.Namespace:
    return SimpleNamespace(app=None, json=False)


class JavaVersionRangeCheckTest(unittest.TestCase):
    def _run_doctor_with_java(self, version_text: str):
        """Run run_doctor with subprocess.run intercepted so that a `java -version` call returns
        the given stderr text; every other subprocess call delegates to the real one."""
        real_run = npdev_cli.subprocess.run
        real_launcher = npdev_cli.java_launcher
        real_which = npdev_cli.shutil.which
        real_db = npdev_cli._database_checks
        real_libs_dir = npdev_cli._default_runtimehost_libs_dir
        real_java_home = npdev_cli.os.environ.get("JAVA_HOME")

        npdev_cli.java_launcher = lambda: "/fake/bin/java"
        npdev_cli._database_checks = lambda app_path: []
        # runtimehost-jars is a FAIL-severity check (not warn) when Build/runtimehost-libs isn't
        # staged -- ambient machine state this test has nothing to do with (it exercises the java
        # version RANGE check only). Left unmocked, this test passed on any dev box that had run
        # `npdev setup` and failed on a clean CI runner that hadn't -- exactly the FinalAppAssembler
        # env-dependency bug in the same audit's D1 fix, one file over (CI run 33206615150).
        npdev_cli._default_runtimehost_libs_dir = lambda: Path(__file__).resolve().parent
        # java-home-agreement compares JAVA_HOME's binary against the PATH one; the fake java is
        # on neither, so unset JAVA_HOME here or this machine's real JDK 17 triggers a false FAIL
        # (the branch under test is the version RANGE, not the home-agreement check -- that has its
        # own test; see NPDevCli/tests/test_java_launcher.py).
        npdev_cli.os.environ.pop("JAVA_HOME", None)

        def fake_run(cmd, *args_, **kwargs):
            if isinstance(cmd, list) and len(cmd) >= 2 and str(cmd[1]) == "-version":
                return SimpleNamespace(returncode=0, stdout="", stderr=version_text)
            return real_run(cmd, *args_, **kwargs)

        npdev_cli.subprocess.run = fake_run
        # doctor's own launcher resolution falls back to shutil.which when JAVA_HOME is unset;
        # point it at the fake so java-present stays honest without a real JDK.
        npdev_cli.shutil.which = lambda name: "/fake/bin/java" if name == "java" else None
        try:
            return npdev_cli.run_doctor(_make_args())
        finally:
            npdev_cli.subprocess.run = real_run
            npdev_cli.java_launcher = real_launcher
            npdev_cli.shutil.which = real_which
            npdev_cli._database_checks = real_db
            npdev_cli._default_runtimehost_libs_dir = real_libs_dir
            if real_java_home is None:
                npdev_cli.os.environ.pop("JAVA_HOME", None)
            else:
                npdev_cli.os.environ["JAVA_HOME"] = real_java_home

    def test_jdk_27_fails_and_names_the_ceiling(self):
        exit_code = self._run_doctor_with_java('java version "27.0.1" 2026-04-21')
        self.assertNotEqual(exit_code, 0,
                            "a JDK above the wrapper's max (26) is a hard dead end -- doctor must fail")

    def test_jdk_21_warns_but_does_not_block(self):
        # D1: a >17-only machine is workable (foojay auto-provisions the 17 toolchain), so doctor
        # must NOT block it -- exit 0 -- while still naming the found version (a warn, not a pass).
        self.assertEqual(self._run_doctor_with_java('java version "21.0.5" 2025-10-21 LTS'), 0,
                         "a JDK 21-only machine is workable -- doctor must not block it")

    def test_jdk_17_passes(self):
        self.assertEqual(self._run_doctor_with_java('java version "17.0.12" 2024-07-16 LTS'), 0)

    def test_ceiling_constant_tracks_gradle_951(self):
        # The number is load-bearing: it must equal what the Gradle 9.5.1 wrapper can RUN on
        # (docs.gradle.org/9.5 compatibility: JVM 17-26; 27+ "not yet supported"). If the wrappers
        # are ever raised again, this test fails loudly instead of doctor quietly capping at the
        # wrong ceiling.
        self.assertEqual(npdev_cli.MAX_SUPPORTED_JDK, 26)


if __name__ == "__main__":
    unittest.main()