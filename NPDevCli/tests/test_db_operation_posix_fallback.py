"""D2 (Cold Clone Audit): `npdev db <op>` must fall back to the POSIX `_ops` script when no
PowerShell is on PATH, instead of refusing outright -- and must still PREFER PowerShell when one is
available, so behaviour on every machine this already worked on is unchanged.

Mocks `subprocess.run` rather than executing real scripts: this repo's test suite runs on both
Windows and POSIX CI agents, and a `.sh` file's shebang is not something `subprocess.run` honours
on native Windows. What matters here is the COMMAND `run_db_operation` builds, not the process it
spawns -- OperationalRunbookEmitterPosixEnvironmentTest (NPDevGenerator) is what proves the emitted
`.sh` files are themselves valid shell.

Run with:
    python -m unittest NPDevCli.tests.test_db_operation_posix_fallback -v
"""

from __future__ import annotations

import argparse
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402


class FakeCompletedProcess:
    def __init__(self, returncode=0, stdout="ok\n", stderr=""):
        self.returncode = returncode
        self.stdout = stdout
        self.stderr = stderr


def _make_ops_root(tmp: Path, *, ps1_names: list[str] = (), sh_names: list[str] = ()) -> Path:
    ops_root = tmp / "myapp-app" / "_ops"
    ops_root.mkdir(parents=True)
    (ops_root / "resolved-db-plan.json").write_text("{}", encoding="utf-8")
    for name in ps1_names:
        (ops_root / name).write_text("# stub\n", encoding="utf-8")
    for name in sh_names:
        (ops_root / name).write_text("#!/bin/sh\necho stub\n", encoding="utf-8")
    return ops_root


def _args(app_path: Path, db_command: str, confirm: str | None = None, json_output: bool = True) -> argparse.Namespace:
    return argparse.Namespace(app=str(app_path), db_command=db_command, confirm=confirm, json=json_output)


class DbOperationPosixFallbackTest(unittest.TestCase):
    def test_prefers_powershell_when_available(self):
        with TemporaryDirectory(prefix="npdev-db-op-") as tmp:
            tmp_path = Path(tmp)
            ops_root = _make_ops_root(
                tmp_path, ps1_names=["Status-Environment.ps1"], sh_names=["status-environment.sh"])

            with patch.object(npdev_cli, "_find_powershell", return_value="pwsh"), \
                 patch.object(npdev_cli.subprocess, "run", return_value=FakeCompletedProcess()) as mock_run:
                npdev_cli.run_db_operation(_args(tmp_path / "myapp", "status"))

            command = mock_run.call_args[0][0]
            self.assertEqual("pwsh", command[0])
            self.assertIn(str(ops_root / "Status-Environment.ps1"), command)

    def test_falls_back_to_posix_script_when_no_powershell(self):
        with TemporaryDirectory(prefix="npdev-db-op-") as tmp:
            tmp_path = Path(tmp)
            ops_root = _make_ops_root(
                tmp_path, ps1_names=["Status-Environment.ps1"], sh_names=["status-environment.sh"])

            with patch.object(npdev_cli, "_find_powershell", return_value=None), \
                 patch.object(npdev_cli.subprocess, "run", return_value=FakeCompletedProcess()) as mock_run:
                npdev_cli.run_db_operation(_args(tmp_path / "myapp", "status"))

            command = mock_run.call_args[0][0]
            self.assertEqual([str(ops_root / "status-environment.sh")], command)

    def test_reset_passes_the_confirmation_token_positionally_on_posix(self):
        with TemporaryDirectory(prefix="npdev-db-op-") as tmp:
            tmp_path = Path(tmp)
            ops_root = _make_ops_root(
                tmp_path, ps1_names=["Reset-Environment.ps1"], sh_names=["reset-environment.sh"])

            with patch.object(npdev_cli, "_find_powershell", return_value=None), \
                 patch.object(npdev_cli.subprocess, "run", return_value=FakeCompletedProcess()) as mock_run:
                npdev_cli.run_db_operation(_args(
                    tmp_path / "myapp", "reset", confirm=npdev_cli._DB_RESET_CONFIRMATION))

            command = mock_run.call_args[0][0]
            self.assertEqual(
                [str(ops_root / "reset-environment.sh"), npdev_cli._DB_RESET_CONFIRMATION], command)

    def test_reset_still_refused_locally_without_the_token_before_any_script_runs(self):
        with TemporaryDirectory(prefix="npdev-db-op-") as tmp:
            tmp_path = Path(tmp)
            _make_ops_root(tmp_path, ps1_names=["Reset-Environment.ps1"], sh_names=["reset-environment.sh"])

            with patch.object(npdev_cli, "_find_powershell", return_value=None), \
                 patch.object(npdev_cli.subprocess, "run") as mock_run:
                with self.assertRaises(npdev_cli.CliError):
                    npdev_cli.run_db_operation(_args(tmp_path / "myapp", "reset", confirm="wrong"))

            mock_run.assert_not_called()

    def test_neither_script_present_names_regeneration_as_the_fix(self):
        with TemporaryDirectory(prefix="npdev-db-op-") as tmp:
            tmp_path = Path(tmp)
            _make_ops_root(tmp_path)  # no scripts written at all

            with patch.object(npdev_cli, "_find_powershell", return_value=None):
                with self.assertRaises(npdev_cli.CliError) as raised:
                    npdev_cli.run_db_operation(_args(tmp_path / "myapp", "status"))

            self.assertIn("regenerate", str(raised.exception).lower())

    def test_powershell_present_but_only_posix_script_exists_still_falls_back(self):
        """An app generated before D2 landed has PowerShell but the app's own toolbox predates
        item 19 -- not the scenario this guards, but the mirror image is: PowerShell IS on this
        machine, yet only the POSIX script survived on disk (e.g. hand-pruned). The PS1 file being
        absent, not PowerShell being absent, must be what triggers the fallback."""
        with TemporaryDirectory(prefix="npdev-db-op-") as tmp:
            tmp_path = Path(tmp)
            ops_root = _make_ops_root(tmp_path, sh_names=["status-environment.sh"])

            with patch.object(npdev_cli, "_find_powershell", return_value="pwsh"), \
                 patch.object(npdev_cli.subprocess, "run", return_value=FakeCompletedProcess()) as mock_run:
                npdev_cli.run_db_operation(_args(tmp_path / "myapp", "status"))

            command = mock_run.call_args[0][0]
            self.assertEqual([str(ops_root / "status-environment.sh")], command)


if __name__ == "__main__":
    unittest.main()
