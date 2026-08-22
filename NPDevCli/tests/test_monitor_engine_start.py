"""REG-192(a): `npdev monitor engine-start` must let a caller OPT IN to the engine's `evaluate` step
(ALLOW_EVALUATE=true) rather than hardcoding it off, so a routine can stub window.prompt/confirm.

`evaluate` is genuinely powerful, so it stays off unless asked for -- the honest surface is an opt-in
flag, not a silent `false` that forces every evaluate-using routine to start the engine by hand.

Run with:
    python -m unittest NPDevCli.tests.test_monitor_engine_start -v
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_monitor  # noqa: E402


class MonitorEngineStartEnvTest(unittest.TestCase):
    def test_allow_evaluate_is_off_by_default(self):
        env = npdev_monitor.engine_start_env(3010, ["http://a"], "k", "artifacts")
        self.assertEqual("false", env["ALLOW_EVALUATE"])

    def test_allow_evaluate_can_be_opted_in(self):
        env = npdev_monitor.engine_start_env(
            3010, ["http://a"], "k", "artifacts", allow_evaluate=True)
        self.assertEqual("true", env["ALLOW_EVALUATE"])

    def test_opt_in_does_not_disturb_the_rest_of_the_environment(self):
        env = npdev_monitor.engine_start_env(
            3010, ["http://a", "http://b"], "k", "artifacts", allow_evaluate=True)
        self.assertEqual("3010", env["PORT"])
        self.assertEqual("http://a,http://b", env["ALLOWED_TARGET_ORIGINS"])


if __name__ == "__main__":
    unittest.main()
