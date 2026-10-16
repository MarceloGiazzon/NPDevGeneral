"""`npdev why` answers from ledger/boundaries/*.yml (AUD-4).

The classification of a boundary lives in the ledger; accepted-boundaries.json only owns the
user-facing text. Before this, the JSON carried its own hand-kept `kind` and drifted (B6 POSTURAL
vs LIFTED, B28) and lacked B32 entirely.
"""

from __future__ import annotations

import argparse
import io
import json
import os
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402

REAL_ROOT = Path(__file__).resolve().parents[2]


def _fresh_boundaries() -> list:
    npdev_cli._ACCEPTED_BOUNDARIES_CACHE = None
    return npdev_cli._load_accepted_boundaries()


def _why(token: str) -> tuple[int, str]:
    npdev_cli._ACCEPTED_BOUNDARIES_CACHE = None
    out = io.StringIO()
    with redirect_stdout(out):
        code = npdev_cli.run_why(argparse.Namespace(token=token, json=False))
    return code, out.getvalue()


def _boundaries_under(root: Path) -> list:
    previous = os.environ.get("NPDEV_ROOT")
    os.environ["NPDEV_ROOT"] = str(root)
    try:
        return _fresh_boundaries()
    finally:
        if previous is None:
            del os.environ["NPDEV_ROOT"]
        else:
            os.environ["NPDEV_ROOT"] = previous


def _write_boundaries_json(root: Path, entries: list) -> None:
    schemas = root / "NPDevContract" / "schemas"
    schemas.mkdir(parents=True)
    (schemas / "accepted-boundaries.json").write_bytes(
        json.dumps({"boundaries": entries}).encode("utf-8"))


class WhyFromLedgerTest(unittest.TestCase):
    def tearDown(self) -> None:
        npdev_cli._ACCEPTED_BOUNDARIES_CACHE = None

    def test_boundary_missing_from_the_json_is_returned_with_the_ledger_title(self) -> None:
        by_id = {b["id"]: b for b in _fresh_boundaries()}
        ledger = npdev_cli._ledger_boundary_classifications()
        self.assertIn("B32", ledger)
        self.assertIn("B32", by_id)
        self.assertEqual(by_id["B32"]["userFacingText"], ledger["B32"]["title"])
        self.assertTrue(by_id["B32"]["userFacingText"].startswith("layered-complexity"))

    def test_why_prints_the_ledger_classification_for_b6_and_b32(self) -> None:
        for token in ("B6", "B32"):
            code, output = _why(token)
            self.assertEqual(0, code)
            self.assertIn(f"{token} -- LIFTED", output)

    def test_every_json_kind_matches_the_ledger_so_the_fallback_cannot_drift(self) -> None:
        path = REAL_ROOT / "NPDevContract" / "schemas" / "accepted-boundaries.json"
        raw = json.loads(path.read_text(encoding="utf-8"))["boundaries"]
        ledger = npdev_cli._ledger_boundary_classifications()
        drift = {b["id"]: (b["kind"], ledger[b["id"]]["classification"])
                 for b in raw if b["id"] in ledger and b["kind"] != ledger[b["id"]]["classification"]}
        self.assertEqual({}, drift, "accepted-boundaries.json kind differs from ledger classification")

    def test_every_ledger_boundary_is_answerable(self) -> None:
        by_id = {b["id"] for b in _fresh_boundaries()}
        self.assertEqual(set(), set(npdev_cli._ledger_boundary_classifications()) - by_id)

    def test_ledger_wins_over_the_json_and_a_qualifier_survives(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _write_boundaries_json(root, [
                {"id": "B1", "kind": "POSTURAL", "userFacingText": "json text", "workaround": "w",
                 "enforcingDiagnosticCodes": []}])
            ledger = root / "ledger" / "boundaries"
            ledger.mkdir(parents=True)
            (ledger / "B1.yml").write_bytes(
                b'id: B1\ntitle: "ledger title"\nclassification: LIFTED, PARTIAL\n')
            (ledger / "B2.yml").write_bytes(
                b'id: B2\ntitle: "only in ledger \\"quoted\\""\nclassification: HITTABLE\n')
            by_id = {b["id"]: b for b in _boundaries_under(root)}
        self.assertEqual("LIFTED, PARTIAL", by_id["B1"]["kind"])
        self.assertEqual("json text", by_id["B1"]["userFacingText"])
        self.assertEqual("HITTABLE", by_id["B2"]["kind"])
        self.assertEqual('only in ledger "quoted"', by_id["B2"]["userFacingText"])

    def test_json_kind_is_the_fallback_when_there_is_no_ledger(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _write_boundaries_json(root, [
                {"id": "B1", "kind": "POSTURAL", "userFacingText": "t", "workaround": "w",
                 "enforcingDiagnosticCodes": []}])
            boundaries = _boundaries_under(root)
        self.assertEqual([("B1", "POSTURAL")], [(b["id"], b["kind"]) for b in boundaries])


if __name__ == "__main__":
    unittest.main()
