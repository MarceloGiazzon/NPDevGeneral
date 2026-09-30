"""Tests for `npdev migrate runtimehost-packages` (supported-core RuntimeHost classes moved out of
`.internal`, 2026-09-30).

Stdlib-only (unittest). Run with:
    python -m unittest NPDevCli.tests.test_migrate_runtimehost_packages -v
"""

from __future__ import annotations

import argparse
import io
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402

SOURCE = """package com.example.ext;

import com.finalexec.api.internal.ModelSyncStatusController;
import com.finalexec.api.internal.RuntimeRefreshController;
import com.finalexec.npdev.service.internal.SemanticBehaviorWriteBackService;
import com.finalexec.npdev.service.internal.SemanticBehaviorWriteBackServiceTest;

class Uses {
    String name = "com.finalexec.npdev.service.internal.FlowBuilderService";
}
"""


def _run(inputs: list[str], write: bool) -> str:
    out = io.StringIO()
    with redirect_stdout(out):
        code = npdev_cli.run_migrate_runtimehost_packages(argparse.Namespace(input=inputs, write=write))
    assert code == 0
    return out.getvalue()


class MigrateRuntimeHostPackagesTest(unittest.TestCase):
    def test_rewrites_only_moved_classes(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "src" / "Uses.java"
            source.parent.mkdir(parents=True)
            source.write_text(SOURCE, encoding="utf-8")

            output = _run([tmp], write=False)
            self.assertIn("WOULD CHANGE", output)
            self.assertEqual(SOURCE, source.read_text(encoding="utf-8"))

            _run([tmp], write=True)
            rewritten = source.read_text(encoding="utf-8")
            self.assertIn("import com.finalexec.api.ModelSyncStatusController;", rewritten)
            self.assertIn("import com.finalexec.npdev.service.SemanticBehaviorWriteBackService;", rewritten)
            self.assertIn('"com.finalexec.npdev.service.FlowBuilderService"', rewritten)
            self.assertIn("import com.finalexec.api.internal.RuntimeRefreshController;", rewritten)
            self.assertIn("import com.finalexec.npdev.service.internal.SemanticBehaviorWriteBackServiceTest;", rewritten)

            self.assertIn("0 of 1", _run([tmp], write=True))


if __name__ == "__main__":
    unittest.main()
