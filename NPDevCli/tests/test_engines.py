"""Tests for the engine registry and `npdev init --engine` (storage/FULL_SUPPORT_PLAN.md W5.1, E9).

The two properties worth pinning are not "the flag parses":

  1. **Every engine writes a db.definition.json that satisfies user-db-definition.schema.json.**
     That schema makes host/port/username/password CONDITIONALLY required per engine, so a server
     engine scaffolded without them produces a file that fails its own contract -- and, before W6.1,
     nothing would have told the user until the app failed to boot. This is the whole value of
     `--engine` over "hand-edit db.definition.json": if it can emit an invalid file, it has not
     removed the problem, it has hidden it.

  2. **An experimental engine says so.** BREAKING.md calling MySQL "selectable but NOT supported" is
     not the user being told; the notice has to arrive at the point of choice. Asserted as a property
     of the registry rather than of one message, so adding a seventh engine cannot quietly skip it.

Stdlib-only apart from jsonschema, which the repo already depends on for its schema gates.

Run with:
    python -m unittest NPDevCli.tests.test_engines -v
"""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_engines  # noqa: E402


def _repo_root() -> Path:
    """By CONTENTS, never by directory name (REG-144)."""
    for candidate in Path(__file__).resolve().parents:
        if all((candidate / m).is_dir()
               for m in ("NPDevContract", "NPDevGenerator", "NPDevKernel")):
            return candidate
    raise AssertionError("could not identify the repo root by contents")


class EngineRegistryTest(unittest.TestCase):

    def test_every_engine_produces_a_schema_valid_db_definition(self):
        from jsonschema import Draft202012Validator

        schema_path = _repo_root() / "schemas" / "ai" / "user-db-definition.schema.json"
        validator = Draft202012Validator(json.loads(schema_path.read_text(encoding="utf-8")))

        for key in npdev_engines.engine_keys():
            definition = npdev_engines.db_definition_for(key, database_name="probe_db")
            errors = [e.message for e in validator.iter_errors(definition)]
            self.assertEqual(
                [], errors,
                f"`npdev init --engine {key}` would write a db.definition.json that fails its own "
                f"schema: {errors}. A scaffold that emits an invalid file has not removed the "
                f"hand-editing problem, it has hidden it."
            )

    def test_engine_external_names_match_the_generator_enum(self):
        # DatabaseEngine.parse() is what actually reads database.engine, and it matches on
        # externalName. A registry entry the generator cannot parse would scaffold an app that
        # refuses to generate -- with a message about an unsupported engine the user just picked
        # from a supported list.
        source = (_repo_root() / "NPDevGenerator" / "generator" / "src" / "main" / "java"
                  / "com" / "npdev" / "generator" / "dbconfig" / "DatabaseEngine.java")
        text = source.read_text(encoding="utf-8")
        for key in npdev_engines.engine_keys():
            external = npdev_engines.ENGINES[key]["externalName"]
            self.assertIn(
                f'"{external}"', text,
                f"engine '{key}' claims externalName '{external}', which DatabaseEngine.java does "
                f"not declare -- DatabaseEngine.parse() would refuse a db.definition.json this CLI "
                f"just wrote."
            )

    def test_engine_providers_are_all_in_the_config_schema_enum(self):
        # The other half of the same trap: config.json's database.provider is now enforced (W6.1),
        # so an engine whose provider is missing from that enum would scaffold an app whose two
        # files contradict each other -- one valid, one not.
        schema = json.loads((_repo_root() / "NPDevContract" / "schemas" / "config.schema.json")
                            .read_text(encoding="utf-8"))
        allowed = schema["properties"]["database"]["properties"]["provider"]["enum"]
        for key in npdev_engines.engine_keys():
            self.assertIn(npdev_engines.ENGINES[key]["provider"], allowed,
                          f"engine '{key}' uses a config.json provider the schema rejects")

    def test_experimental_engines_carry_an_honesty_notice_and_supported_ones_do_not(self):
        for key in npdev_engines.engine_keys():
            status = npdev_engines.ENGINES[key]["status"]
            notice = npdev_engines.honesty_notice(key)
            if status == "supported":
                self.assertIsNone(notice, f"'{key}' is supported and needs no warning")
            else:
                self.assertIsNotNone(
                    notice,
                    f"'{key}' is '{status}' and must say so AT THE POINT OF CHOICE. An interface "
                    f"that silently offers it is the silent-answer defect wearing a dropdown."
                )
                self.assertIn("EXPERIMENTAL", notice)

    def test_server_engines_get_connection_fields_and_others_do_not(self):
        server = npdev_engines.db_definition_for("postgres", database_name="d")["database"]
        self.assertEqual("localhost", server["host"])
        self.assertEqual(5432, server["port"])

        local = npdev_engines.db_definition_for("h2local", database_name="d")["database"]
        self.assertNotIn("host", local,
                         "an unnecessary host on an H2Local app is noise that reads like configuration")
        self.assertNotIn("port", local)

    def test_unknown_engine_names_the_alternatives(self):
        with self.assertRaises(ValueError) as raised:
            npdev_engines.resolve("sqlite")
        self.assertIn("sqlite", str(raised.exception))
        self.assertIn("postgres", str(raised.exception))

    def test_external_name_spelling_is_accepted(self):
        # A user copying `"engine": "MySQL"` out of their own db.definition.json and passing it to
        # --engine is doing something reasonable.
        self.assertEqual("mysql", npdev_engines.resolve("MySQL")["key"])
        self.assertEqual("h2local", npdev_engines.resolve("H2Local")["key"])


class ScaffoldGitHistoryTest(unittest.TestCase):
    """`npdev init` must not die because git has no identity -- found in CI, run 31272295843.

    Not a CI quirk. `git commit` exits 128 with "Author identity unknown" on a machine with no
    user.name/user.email, and a machine with no git identity is precisely a FRESH machine -- the one
    this command exists for. Before the fix, the scaffold left files written, no repository, and a
    git error for the user to decode; the Manager shells straight to this command, so it failed
    there too, on a first run.

    The test reproduces the exact precondition (an environment where git can find no identity)
    rather than mocking the failure, because "a fix verified only on the author's machine can still
    be wrong" is a lesson this repo has already paid for.
    """

    def test_first_commit_succeeds_with_no_git_identity_configured(self):
        import os
        import subprocess
        import tempfile

        import npdev_cli

        if shutil_which("git") is None:
            self.skipTest("git is not installed")

        with tempfile.TemporaryDirectory(prefix="npdev-gitless-") as temp:
            target = Path(temp) / "app"
            target.mkdir()
            (target / "model.json").write_text("{}", encoding="utf-8")

            # HOME/XDG/GIT_CONFIG_* all redirected at an empty directory, plus the explicit
            # GIT_CONFIG_GLOBAL=/dev/null equivalent: this is what makes git genuinely identity-less
            # even on a developer machine whose real ~/.gitconfig has a name in it.
            empty = Path(temp) / "empty-home"
            empty.mkdir()
            env = dict(os.environ)
            env.update({
                "HOME": str(empty), "USERPROFILE": str(empty), "XDG_CONFIG_HOME": str(empty),
                "GIT_CONFIG_GLOBAL": str(empty / "nonexistent-gitconfig"),
                "GIT_CONFIG_SYSTEM": str(empty / "nonexistent-gitconfig"),
                "GIT_CONFIG_NOSYSTEM": "1",
                "GIT_AUTHOR_NAME": "", "GIT_AUTHOR_EMAIL": "",
                "GIT_COMMITTER_NAME": "", "GIT_COMMITTER_EMAIL": "",
            })

            original = os.environ.copy()
            try:
                os.environ.update(env)
                result = npdev_cli._scaffold_git_history(target)
            finally:
                os.environ.clear()
                os.environ.update(original)

            log = subprocess.run(["git", "log", "--oneline"], cwd=target,
                                 capture_output=True, text=True)
            self.assertEqual(0, log.returncode,
                             f"the scaffold must end with a real repository: {log.stderr}")
            self.assertIn("npdev init: scaffold app", log.stdout,
                          "the promised first commit is missing -- a scaffold with no history is a "
                          "trap, not a convenience")
            self.assertTrue(result.initialised,
                            "a repository WAS created here; only the identity was substituted")
            self.assertIsNotNone(
                result.notice,
                "when an identity is SUBSTITUTED the user must be told; a tool that commits under a "
                "name nobody chose, silently, is the bigger surprise")
            self.assertIn("git config --global user.name", result.notice)


class ScaffoldWithoutGitTest(unittest.TestCase):
    """`npdev init` on a machine with NO GIT AT ALL -- W1.2, 2026-08-10.

    docs/MANAGER.md advertises "a machine that has nothing on it", and the Manager installs a private
    JDK and a private Python and never installs git. So `subprocess.run(["git", ...])` raises
    FileNotFoundError, `main()` catches only CliError/CalledProcessError, and the Create button dies
    with a raw traceback at the very first step. Invisible from any machine that has git -- which is
    every machine this repo is developed on.

    PATH is scrubbed rather than git mocked, for the same reason the identity test above reproduces
    its precondition: a fix verified against a stub can be wrong about the real failure.
    """

    def _run_init_without_git(self, target: Path):
        import os
        import shutil

        import npdev_cli

        kept = [d for d in os.environ.get("PATH", "").split(os.pathsep)
                if d and not (Path(d) / ("git.exe" if os.name == "nt" else "git")).exists()]
        original = os.environ.get("PATH", "")
        try:
            os.environ["PATH"] = os.pathsep.join(kept)
            if shutil.which("git") is not None:
                self.skipTest("git still resolves after scrubbing PATH -- this test would prove "
                              "nothing (it must not pass by accident)")
            return npdev_cli._scaffold_git_history(target)
        finally:
            os.environ["PATH"] = original

    def test_scaffold_succeeds_and_says_there_is_no_repository(self):
        import tempfile

        with tempfile.TemporaryDirectory(prefix="npdev-nogit-") as temp:
            target = Path(temp) / "app"
            target.mkdir()
            (target / "model.json").write_text("{}", encoding="utf-8")

            result = self._run_init_without_git(target)

            self.assertFalse(result.initialised,
                             "there is no git, so there is no repository -- reporting one would be "
                             "the lie the notice exists to prevent")
            self.assertIsNotNone(result.notice, "a missing repository must be REPORTED, not silent")
            self.assertIn("git is not installed", result.notice)
            self.assertFalse((target / ".git").exists())
            self.assertTrue((target / "model.json").exists(),
                            "the scaffold itself never needed git -- every file was written before "
                            "this ran")


def shutil_which(name):
    import shutil
    return shutil.which(name)

if __name__ == "__main__":
    unittest.main()
