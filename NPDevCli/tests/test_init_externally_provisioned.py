"""R9.8 / STOR-15 regression: `npdev init --externally-provisioned` is the ONLY supported-authoring
writer of `database.externallyProvisioned` in a scaffolded app's `db.definition.json` -- the flag
STOR-14's `Reset` refusal (and every other `_ops` operation) branches on.

STOR-15 (closed 2026-08-11, ledger/items/STOR-15.yml) already implemented and live-verified this at
the time, but left it with ZERO automated regression coverage in `NPDevCli/tests` -- a grep for
`externally_provisioned`/`externallyProvisioned` across this directory before this file found nothing
outside `test_doctor_docker.py`'s unrelated `docker_external` doctor-check test. A silent regression
here (the flag stops reaching the written file, or starts being accepted for an embedded engine) would
not fail any existing test: the app would scaffold "successfully" and `Reset` would silently start
deleting a data root the user does not own -- exactly STOR-15's own "a guard that cannot be switched on
is indistinguishable from no guard" shape, one layer up.

This is filesystem-level, exercising the real `npdev_cli.run_init` against a real temp directory (the
same shape `test_engines.py::ScaffoldWithoutGitTest` uses) -- not a mock of `npdev_engines`, since the
whole point is that the CLI flag reaches the file `_ops`' generated scripts actually read. A live,
end-to-end proof that the refusal fires against a real reachable server (not just an unreachable
port) is `npdev db reset` run against a hand-started TCP listener declaring
`externallyProvisioned: true` -- exercised manually for this item, not re-encoded as a unit test here
because the STOR-14 refusal message itself lives in generated PowerShell (`OperationalRunbookEmitter`,
outside NPDevCli's surface).

Run with:
    python -m unittest NPDevCli.tests.test_init_externally_provisioned -v
"""

from __future__ import annotations

import argparse
import json
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402


def _init_args(target: Path, **overrides) -> argparse.Namespace:
    """The subset of `init`'s parsed Namespace `run_init` actually reads (build_parser's `init`
    subparser, npdev_cli.py) -- constructed directly rather than through argparse, the same pattern
    `test_seed_cli.py` uses for its own CLI-verb tests."""
    defaults = dict(
        name=str(target), from_sample=None, json=True,
        engine="h2local", db_host=None, db_port=None, db_user=None, db_password=None,
        externally_provisioned=False,
    )
    defaults.update(overrides)
    return argparse.Namespace(**defaults)


class InitExternallyProvisionedTest(unittest.TestCase):
    def test_flag_reaches_db_definition_json_for_a_server_engine(self):
        with TemporaryDirectory(prefix="npdev-init-extprov-") as tmp:
            target = Path(tmp) / "myapp"
            args = _init_args(target, engine="postgres", db_host="127.0.0.1", db_port=55432,
                              db_user="proofuser", db_password="proofpass",
                              externally_provisioned=True)

            rc = npdev_cli.run_init(args)

            self.assertEqual(0, rc)
            db_def_path = target / "db.definition.json"
            self.assertTrue(db_def_path.exists())
            written = json.loads(db_def_path.read_text(encoding="utf-8"))
            self.assertIs(
                True, written["database"]["externallyProvisioned"],
                "STOR-14's Reset refusal, and every other _ops operation, branches on this exact "
                "key -- if `--externally-provisioned` stops reaching it, Reset silently goes back "
                "to deleting a data root the user does not own.")

    def test_flag_omitted_is_written_as_an_explicit_false_for_a_server_engine(self):
        """F3 (Cold Clone Audit, P0) supersedes STOR-15's original "written only when TRUE": the
        generator now REFUSES a server-engine db.definition.json that omits the key at all, rather
        than silently treating an absent key as false -- that silent default was exactly what let
        Reset delete a server the user had provisioned themselves. `npdev init` is where the
        true/false decision actually gets made, so it must make it durable in the file either way,
        not just when the answer is true."""
        with TemporaryDirectory(prefix="npdev-init-extprov-") as tmp:
            target = Path(tmp) / "myapp"
            args = _init_args(target, engine="postgres", db_host="127.0.0.1", db_port=55432,
                              db_user="u", db_password="p", externally_provisioned=False)

            rc = npdev_cli.run_init(args)

            self.assertEqual(0, rc)
            written = json.loads((target / "db.definition.json").read_text(encoding="utf-8"))
            self.assertIs(False, written["database"]["externallyProvisioned"])

    def test_flag_stays_absent_for_an_embedded_engine(self):
        """h2local has no server for anyone to have provisioned, so the key is meaningless there --
        the Java loader defaults it to false for embedded engines regardless (see
        UserDatabaseDefinitionLoader.requireExternallyProvisioned), and writing it would only invite
        the false impression that it does something for this engine."""
        with TemporaryDirectory(prefix="npdev-init-extprov-") as tmp:
            target = Path(tmp) / "myapp"
            args = _init_args(target, engine="h2local", externally_provisioned=False)

            rc = npdev_cli.run_init(args)

            self.assertEqual(0, rc)
            db_def_path = target / "db.definition.json"
            if db_def_path.exists():
                written = json.loads(db_def_path.read_text(encoding="utf-8"))
                self.assertNotIn("externallyProvisioned", written["database"])

    def test_flag_is_refused_for_an_embedded_engine_before_anything_is_written(self):
        """h2local's database is a file belonging to this app alone -- there is no server for anyone
        to have provisioned, so the combination is refused outright, and refused BEFORE the
        half-scaffolded-directory trap STOR-15's own resolution measured and fixed."""
        with TemporaryDirectory(prefix="npdev-init-extprov-") as tmp:
            target = Path(tmp) / "myapp"
            args = _init_args(target, engine="h2local", externally_provisioned=True)

            with self.assertRaises(npdev_cli.CliError):
                npdev_cli.run_init(args)

            self.assertFalse(
                target.exists(),
                "refusing --externally-provisioned for an embedded engine must leave NO directory "
                "behind -- a half-scaffolded app with a non-zero exit is the trap STOR-15 fixed.")


if __name__ == "__main__":
    unittest.main()
