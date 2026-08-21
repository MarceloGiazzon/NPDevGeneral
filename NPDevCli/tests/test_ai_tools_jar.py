"""R1.1, 2026-08-18: the warm standalone validator, and the fallback that must survive it.

`npdev validate model --semantic` forked the Gradle wrapper to reach ModelValidatorMain, and every
classifyModelChange call did the same for ModelChangeClassifierMain. Both are pure
stdlib+Jackson+dsl -- no Spring, no database, no codegen -- so the Gradle layer was buying a
classpath and nothing else, at a measured median 4.61s per validation against 2.24s for
`java -cp npdev-ai-tools.jar <Main>` (8 interleaved A/B pairs on canonical-demo; process sampling
counted 0 new Gradle processes per call on the fast path against 3 on the old one).

The whole risk of that change lives in ONE property: a checkout that has never run
sync-runtimehost-libs.ps1 has no staged jar, and must keep working exactly as it did before. So
these tests pin the ABSENCE branch as hard as the presence branch, at every level -- jar discovery,
command construction, and the Gradle property names the fallback still has to spell correctly.

They never launch a JVM: what is under test is which command gets built, not what it prints. The
byte-identical-report proof is a live measurement (see _default_ai_tools_jar's own docstring), not
something a unit test can assert.
"""

from __future__ import annotations

import os
import re
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

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


def _staged_jar(build_root: Path) -> Path:
    """A file where the sync script stages the real one. Discovery only ever asks is_file()."""
    jar = build_root / "ai-tools" / "npdev-ai-tools.jar"
    jar.parent.mkdir(parents=True, exist_ok=True)
    jar.write_bytes(b"not a real jar")
    return jar


class DefaultAiToolsJarTest(unittest.TestCase):

    def test_absent_jar_resolves_to_none_so_callers_fall_back(self):
        with tempfile.TemporaryDirectory(prefix="npdev-ai-tools-") as temp:
            with _Env(NPDEV_BUILD_ROOT=temp, NPDEV_AI_TOOLS_JAR=None):
                self.assertIsNone(npdev_cli._default_ai_tools_jar(),
                                  "a fresh checkout has never run sync-runtimehost-libs.ps1; None "
                                  "is what routes every caller back to the unchanged Gradle path")

    def test_staged_jar_is_found_under_the_build_root(self):
        with tempfile.TemporaryDirectory(prefix="npdev-ai-tools-") as temp:
            jar = _staged_jar(Path(temp))
            with _Env(NPDEV_BUILD_ROOT=temp, NPDEV_AI_TOOLS_JAR=None):
                self.assertEqual(jar, npdev_cli._default_ai_tools_jar(),
                                 "must agree with sync-runtimehost-libs.ps1's staging path, "
                                 "<BuildRoot>/ai-tools/npdev-ai-tools.jar")

    def test_no_hardcoded_absolute_default_when_the_build_root_is_elsewhere(self):
        """REG-131/REG-144: an unresolvable input is None, never this machine's own path.

        _default_runtimehost_libs_dir once carried a <drive>:/WorkSpace/... fallback that silently
        pointed everyone else's checkout at a directory only the author had.
        """
        with tempfile.TemporaryDirectory(prefix="npdev-empty-root-") as temp:
            with _Env(NPDEV_BUILD_ROOT=temp, NPDEV_AI_TOOLS_JAR=None):
                self.assertIsNone(npdev_cli._default_ai_tools_jar())

    def test_explicit_override_wins_over_the_build_root(self):
        with tempfile.TemporaryDirectory(prefix="npdev-ai-tools-") as temp:
            root = Path(temp)
            _staged_jar(root)                                   # the one discovery would find
            elsewhere = root / "elsewhere" / "npdev-ai-tools.jar"
            elsewhere.parent.mkdir(parents=True, exist_ok=True)
            elsewhere.write_bytes(b"not a real jar")
            with _Env(NPDEV_BUILD_ROOT=temp, NPDEV_AI_TOOLS_JAR=str(elsewhere)):
                self.assertEqual(elsewhere, npdev_cli._default_ai_tools_jar())

    def test_override_pointing_nowhere_forces_the_gradle_path_back_on(self):
        """The documented escape hatch: one env var takes the whole fast path out of service."""
        with tempfile.TemporaryDirectory(prefix="npdev-ai-tools-") as temp:
            _staged_jar(Path(temp))
            with _Env(NPDEV_BUILD_ROOT=temp,
                      NPDEV_AI_TOOLS_JAR=str(Path(temp) / "no-such-file.jar")):
                self.assertIsNone(npdev_cli._default_ai_tools_jar(),
                                  "a staged jar must not win over an explicit override")


class AiToolsCommandTest(unittest.TestCase):

    def test_no_jar_means_no_direct_command(self):
        with tempfile.TemporaryDirectory(prefix="npdev-ai-tools-") as temp:
            with _Env(NPDEV_BUILD_ROOT=temp, NPDEV_AI_TOOLS_JAR=None):
                self.assertIsNone(npdev_cli._ai_tools_command(
                    npdev_cli.AI_TOOLS_VALIDATOR_MAIN, ["model.json"]))

    def test_direct_command_names_the_jar_the_main_class_and_the_args_in_order(self):
        if npdev_cli.java_launcher() is None:
            self.skipTest("no java on this machine, so there is no direct command to build")
        with tempfile.TemporaryDirectory(prefix="npdev-ai-tools-") as temp:
            jar = _staged_jar(Path(temp))
            with _Env(NPDEV_BUILD_ROOT=temp, NPDEV_AI_TOOLS_JAR=None):
                command = npdev_cli._ai_tools_command(
                    npdev_cli.AI_TOOLS_VALIDATOR_MAIN, ["model.json", "--out", "report.json"])

        self.assertIsNotNone(command)
        self.assertEqual([npdev_cli.java_launcher(), "-cp", str(jar),
                          "com.npdev.dsl.v1.cli.ModelValidatorMain",
                          "model.json", "--out", "report.json"], command)

    def test_no_java_anywhere_means_no_direct_command(self):
        """java_launcher() returning None is an ordinary state, not a failure -- Gradle finds its
        own JVM through the toolchain, so the fallback still works on a machine this cannot."""
        with tempfile.TemporaryDirectory(prefix="npdev-ai-tools-") as temp:
            _staged_jar(Path(temp))
            scrubbed = os.pathsep.join(
                d for d in os.environ.get("PATH", "").split(os.pathsep)
                if d and not (Path(d) / ("java.exe" if os.name == "nt" else "java")).exists())
            with _Env(NPDEV_BUILD_ROOT=temp, NPDEV_AI_TOOLS_JAR=None,
                      JAVA_HOME=None, PATH=scrubbed):
                if shutil.which("java") is not None:
                    self.skipTest("java still resolves after scrubbing PATH -- this test must not "
                                  "pass by accident")
                self.assertIsNone(npdev_cli._ai_tools_command(
                    npdev_cli.AI_TOOLS_VALIDATOR_MAIN, ["model.json"]))


class ClassifierCommandTest(unittest.TestCase):
    """_classifier_command is the one place the two spellings of the same call are translated."""

    ARGS = ["--current", "c.json", "--baseline", "b.json", "--out", "r.json"]

    def test_falls_back_to_the_unchanged_gradle_property_form(self):
        with tempfile.TemporaryDirectory(prefix="npdev-ai-tools-") as temp:
            with _Env(NPDEV_BUILD_ROOT=temp, NPDEV_AI_TOOLS_JAR=None):
                resolved = npdev_cli._classifier_command(npdev_cli.repo_root(), self.ARGS)

        self.assertIsNotNone(resolved, "the real checkout has NPDevGenerator/gradlew")
        command, cwd = resolved
        joined = " ".join(command)
        self.assertIn(":generator:classifyModelChange", joined)
        self.assertIn("--no-daemon", joined)
        # The property NAMES are the contract with build.gradle's own task registration -- these
        # three are exactly what the pre-R1.1 code sent, character for character.
        self.assertIn("-PcurrentPath=c.json", command)
        self.assertIn("-PbaselinePath=b.json", command)
        self.assertIn("-PreportOut=r.json", command)
        self.assertEqual(npdev_cli.repo_root() / "NPDevGenerator", cwd)

    def test_uses_the_jar_when_one_is_staged(self):
        if npdev_cli.java_launcher() is None:
            self.skipTest("no java on this machine")
        with tempfile.TemporaryDirectory(prefix="npdev-ai-tools-") as temp:
            jar = _staged_jar(Path(temp))
            with _Env(NPDEV_BUILD_ROOT=temp, NPDEV_AI_TOOLS_JAR=None):
                command, _cwd = npdev_cli._classifier_command(npdev_cli.repo_root(), self.ARGS)

        self.assertEqual([npdev_cli.java_launcher(), "-cp", str(jar),
                          "com.npdev.generator.schemaevolution.ModelChangeClassifierMain",
                          *self.ARGS], command)
        self.assertNotIn("gradlew", " ".join(command).lower(),
                         "the whole point of R1.1 is that this call spawns no Gradle process")

    def test_every_flag_the_java_main_accepts_has_a_gradle_property_mapping(self):
        """Drift guard, the twin-pair shape this repo already uses elsewhere.

        The direct path passes the classifier's own flags; the Gradle path has to translate each one
        into the -P name classifyModelChange reads. A flag added to the Java main and used from here
        without a mapping would raise KeyError only on machines with no staged jar -- i.e. never on
        the machine that added it. Read from the Java source so the two cannot drift silently.
        """
        source = (npdev_cli.repo_root() / "NPDevGenerator" / "generator" / "src" / "main" / "java"
                  / "com" / "npdev" / "generator" / "schemaevolution"
                  / "ModelChangeClassifierMain.java").read_text(encoding="utf-8")
        accepted = set(re.findall(r'case "(--[A-Za-z]+)"', source))

        self.assertTrue(accepted, "failed to parse the classifier's own flag list")
        self.assertEqual(set(), accepted - set(npdev_cli._CLASSIFIER_GRADLE_PROPERTIES),
                         "ModelChangeClassifierMain accepts a flag _CLASSIFIER_GRADLE_PROPERTIES "
                         "cannot translate for the Gradle fallback")


if __name__ == "__main__":
    unittest.main()
