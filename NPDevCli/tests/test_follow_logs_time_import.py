"""REG-191: `time` must be importable at module level so every wait loop in `npdev_cli`
(`npdev monitor logs --follow`, `npdev monitor engine-start`'s readiness loop, build/boot deadlines)
resolves `time.sleep`/`time.monotonic` instead of raising `NameError`.

The reported symptom was `npdev monitor engine-start` crashing, but the same class of defect also
left `_follow_logs` (`npdev monitor logs --follow`) calling `time.sleep(0.4)` with no binding at all.
This test reaches that wait loop; it would `NameError` (or fail to patch `npdev_cli.time`) the moment
the module-level import regresses.

Run with:
    python -m unittest NPDevCli.tests.test_follow_logs_time_import -v
"""

from __future__ import annotations

import argparse
import sys
import time
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402


class FollowLogsTimeImportTest(unittest.TestCase):
    def test_module_level_time_import_is_present(self):
        # `npdev_cli.time` must be the stdlib `time` module -- the binding every bare `time.` use in
        # the file depends on. Absence of this attribute is the root cause of the NameError class.
        self.assertIs(npdev_cli.time, time)

    def test_follow_logs_reaches_its_wait_loop_without_name_error(self):
        with TemporaryDirectory() as tmp:
            log = Path(tmp) / "app.log"
            log.write_text("line one\n", encoding="utf-8")
            args = argparse.Namespace(source="app", tail=50, json=False)

            with mock.patch.object(npdev_cli.npdev_monitor, "_log_files", return_value=[log]), \
                 mock.patch.object(npdev_cli.npdev_monitor, "_tail", return_value=[]), \
                 mock.patch.object(npdev_cli.time, "sleep", side_effect=KeyboardInterrupt):
                # _follow_logs opens the file, seeks to end, reads an empty line, then calls
                # time.sleep(0.4) -- patched here to raise KeyboardInterrupt (which the function
                # catches) so the infinite loop terminates deterministically. A NameError on `time`
                # would propagate and fail the test; patching npdev_cli.time.sleep would
                # AttributeError without the module-level import.
                rc = npdev_cli._follow_logs(log, args)

            self.assertEqual(rc, 0)


if __name__ == "__main__":
    unittest.main()
