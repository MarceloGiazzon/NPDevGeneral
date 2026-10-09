"""Tests for `npdev migrate procedure-args-order` (#34: procedure step args bind in declared key
order now; this one-shot pass rewrites an existing model into the alphabetical order it bound in
before, so it keeps binding exactly as it did).

Stdlib-only (unittest). Run with:
    python -m unittest NPDevCli.tests.test_migrate_procedure_args_order -v
"""

from __future__ import annotations

import argparse
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402
from dsl_v2_migration import migrate_procedure_args_order  # noqa: E402


def _model(args: dict, nested_args: dict | None = None) -> dict:
    step = {"name": "call", "type": "capabilityCall", "capability": "fiscal", "operation": "importar", "args": args}
    if nested_args is not None:
        step = {"name": "guard", "type": "if", "condition": "$input", "then": [
            {"name": "inner", "type": "capabilityCall", "capability": "fiscal", "operation": "importar",
             "args": nested_args}]}
    return {"namespace": "t", "dslVersion": "1.0.0", "version": "1.0",
            "procedures": [{"name": "P", "steps": [step]}]}


class MigrateProcedureArgsOrderTest(unittest.TestCase):
    def test_reorders_args_into_the_old_alphabetical_binding(self):
        doc = _model({"input": "$input", "produtosConhecidos": "$p", "chavesJaImportadas": "$c"})
        result = migrate_procedure_args_order(doc)
        self.assertTrue(result.changed)
        self.assertEqual(["chavesJaImportadas", "input", "produtosConhecidos"],
                         list(doc["procedures"][0]["steps"][0]["args"]))

    def test_sort_is_case_insensitive_like_the_old_compiler(self):
        doc = _model({"b": 1, "A": 2})
        migrate_procedure_args_order(doc)
        self.assertEqual(["A", "b"], list(doc["procedures"][0]["steps"][0]["args"]))

    def test_recurses_into_nested_steps(self):
        doc = _model({}, nested_args={"zeta": 1, "alpha": 2})
        result = migrate_procedure_args_order(doc)
        self.assertTrue(result.changed)
        self.assertEqual(["alpha", "zeta"], list(doc["procedures"][0]["steps"][0]["then"][0]["args"]))

    def test_already_alphabetical_is_unchanged(self):
        doc = _model({"input": "$input", "numeros": "$n", "produtos": "$p"})
        self.assertFalse(migrate_procedure_args_order(doc).changed)

    def test_cli_dry_run_leaves_file_and_write_keeps_crlf(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "model.json"
            original = json.dumps(_model({"to": "$e", "body": "x"}), indent=2).replace("\n", "\r\n") + "\r\n"
            path.write_bytes(original.encode("utf-8"))
            ns = argparse.Namespace(input=[str(path)], write=False, report=None)
            self.assertEqual(0, npdev_cli.run_migrate_procedure_args_order(ns))
            self.assertEqual(original.encode("utf-8"), path.read_bytes())

            ns.write = True
            self.assertEqual(0, npdev_cli.run_migrate_procedure_args_order(ns))
            raw = path.read_bytes()
            self.assertIn(b"\r\n", raw)
            self.assertNotIn(b"\n\n", raw.replace(b"\r\n", b"\r"))
            self.assertEqual(["body", "to"], list(json.loads(raw)["procedures"][0]["steps"][0]["args"]))


if __name__ == "__main__":
    unittest.main()
