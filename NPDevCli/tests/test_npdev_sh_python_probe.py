"""The `npdev` shell launcher must skip a `python3` that is on PATH but cannot run.

On Windows, Git Bash resolves `python3` to the Microsoft Store stub (WindowsApps\\python3.exe) before any
real interpreter. The stub exists, so `command -v python3` succeeds, then it exits 49 "Python was not
found". The launcher used to `exec` it anyway, so `./npdev` exited 49 on a machine that has a working
`python`, and `run-portable-tooling-check.ps1` (which runs `bash -lc ./npdev ...`) failed all five of
its commands locally while passing in CI.

The fix probes each candidate with `-c ""` before choosing it. This test puts a `python3` that exits 49
in front of PATH, and a `python` that forwards to this interpreter, then expects `./npdev --version` to
succeed through the second one.

Run with:
    python -m unittest NPDevCli.tests.test_npdev_sh_python_probe -v
"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

_REPO_ROOT = Path(__file__).resolve().parent.parent.parent
_NPDEV = _REPO_ROOT / "npdev"


def _shim(directory: Path, name: str, body: str) -> None:
    path = directory / name
    path.write_text("#!/bin/sh\n" + body + "\n", encoding="utf-8", newline="\n")
    path.chmod(0o755)


@unittest.skipUnless(shutil.which("sh"), "needs a POSIX sh to run the launcher")
class NpdevShellLauncherPythonProbeTest(unittest.TestCase):
    def test_an_unusable_python3_is_skipped_for_a_working_python(self):
        with tempfile.TemporaryDirectory() as tmp:
            fake_bin = Path(tmp)
            _shim(fake_bin, "python3", "exit 49")
            _shim(fake_bin, "python", f'exec "{Path(sys.executable).as_posix()}" "$@"')
            env = dict(os.environ, PATH=str(fake_bin) + os.pathsep + os.environ.get("PATH", ""))
            result = subprocess.run(
                ["sh", str(_NPDEV), "--version"],
                cwd=_REPO_ROOT, env=env, capture_output=True, text=True, check=False,
            )
        self.assertEqual(
            result.returncode, 0,
            msg=f"./npdev must fall through a python3 that exits 49; got {result.returncode}\n"
                f"stdout: {result.stdout}\nstderr: {result.stderr}",
        )
        self.assertNotIn("python3 or python is required", result.stderr)


if __name__ == "__main__":
    unittest.main()
