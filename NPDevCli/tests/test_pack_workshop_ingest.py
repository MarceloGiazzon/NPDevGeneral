"""FORM P6-T2: `npdev pack workshop ingest` -- strict, immutable-version, hostile-zip-safe, duplicate-aware."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

CLI_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(CLI_DIR))
import npdev_pack_workshop as ws  # noqa: E402


def _handoff(pack, version):
    return {"schemaVersion": "npdev-pack-handoff.v1", "pack": pack, "version": version,
            "checklist": [{"id": "own-record-write-null-owner", "answer": "yes", "evidence": "invariants"}],
            "limits": ["does not do X"]}


def _batch(*packs, batch_id="w9-test"):
    return {"schemaVersion": "npdev-pack-batch.v1", "batchId": batch_id,
            "author": {"kind": "browser-ai", "model": "test-model"}, "guideVersion": "g1",
            "packs": [{"pack": p, "version": v, "roadmapId": p} for p, v in packs]}


def _zip(path: Path, members: dict) -> Path:
    with zipfile.ZipFile(path, "w") as zf:
        for name, content in members.items():
            zf.writestr(name, content if isinstance(content, (bytes, str)) else json.dumps(content))
    return path


def _good(tmp: Path, name="npdev-packs__w9-test__20261115-0900.zip", version="1.0.0", extra=None):
    members = {"batch.json": _batch(("leads", version)),
               "packs/leads/pack.json": {"pack": "leads", "version": version, "description": "d"},
               "packs/leads/handoff.json": _handoff("leads", version)}
    members.update(extra or {})
    return _zip(tmp / name, members)


class IngestTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp = Path(self._tmp.name)
        self.root = self.tmp / "workshop"

    def tearDown(self):
        self._tmp.cleanup()

    def test_a_conforming_delivery_is_placed_by_area_and_recorded(self):
        out = ws.ingest_zip(self.root, _good(self.tmp))
        self.assertEqual(out["status"], "ingested")
        self.assertTrue((self.root / "packs" / "sales" / "leads" / "1.0.0" / "pack.json").is_file())
        self.assertTrue((self.root / "packs" / "sales" / "leads" / "1.0.0" / "handoff.json").is_file())
        batch = json.loads((self.root / "batches" / "w9-test.json").read_text(encoding="utf-8"))
        self.assertEqual(batch["deliveries"][0]["packs"][0]["status"], "delivered")

    def test_the_same_bytes_twice_is_a_reported_no_op(self):
        zip_path = _good(self.tmp)
        ws.ingest_zip(self.root, zip_path)
        self.assertEqual(ws.ingest_zip(self.root, zip_path)["status"], "duplicate")
        batch = json.loads((self.root / "batches" / "w9-test.json").read_text(encoding="utf-8"))
        self.assertEqual(len(batch["deliveries"]), 1)

    def test_a_new_version_is_a_new_folder(self):
        ws.ingest_zip(self.root, _good(self.tmp))
        out = ws.ingest_zip(self.root, _good(self.tmp, name="npdev-packs__w9-test__20261115-1000.zip", version="1.0.1"))
        self.assertTrue(out["packs"][0]["placed"])
        self.assertTrue((self.root / "packs" / "sales" / "leads" / "1.0.1").is_dir())

    def test_same_version_with_different_content_is_refused_and_nothing_is_written(self):
        ws.ingest_zip(self.root, _good(self.tmp))
        changed = _good(self.tmp, name="npdev-packs__w9-test__20261115-1100.zip",
                        extra={"packs/leads/pack.json": {"pack": "leads", "version": "1.0.0", "description": "CHANGED"}})
        inbox_before = sorted(p.name for p in (self.root / "inbox").iterdir())
        with self.assertRaises(ws.WorkshopError) as raised:
            ws.ingest_zip(self.root, changed)
        self.assertIn("immutable", str(raised.exception))
        self.assertEqual(sorted(p.name for p in (self.root / "inbox").iterdir()), inbox_before)

    def test_a_delivery_without_batch_json_is_refused_unless_legacy_is_named(self):
        zip_path = _zip(self.tmp / "old.zip", {"packs/leads/pack.json": {"pack": "leads", "version": "1.0.0"}})
        with self.assertRaises(ws.WorkshopError) as raised:
            ws.ingest_zip(self.root, zip_path)
        self.assertIn("--legacy", str(raised.exception))
        out = ws.ingest_zip(self.root, zip_path, legacy_batch="w1-old")
        self.assertTrue(out["legacy"])
        self.assertTrue(any("no handoff" in w for w in out["warnings"]))

    def test_a_pack_missing_its_handoff_is_refused(self):
        zip_path = _zip(self.tmp / "d.zip", {"batch.json": _batch(("leads", "1.0.0")),
                                             "packs/leads/pack.json": {"pack": "leads", "version": "1.0.0"}})
        with self.assertRaises(ws.WorkshopError) as raised:
            ws.ingest_zip(self.root, zip_path)
        self.assertIn("handoff.json", str(raised.exception))

    def test_a_pack_not_named_in_the_batch_is_refused(self):
        zip_path = _good(self.tmp, extra={"packs/rogue/pack.json": {"pack": "rogue", "version": "1.0.0"}})
        with self.assertRaises(ws.WorkshopError) as raised:
            ws.ingest_zip(self.root, zip_path)
        self.assertIn("rogue", str(raised.exception))

    def test_pack_json_that_disagrees_with_the_batch_is_refused(self):
        zip_path = _good(self.tmp, extra={"packs/leads/pack.json": {"pack": "leads", "version": "2.0.0"}})
        with self.assertRaises(ws.WorkshopError) as raised:
            ws.ingest_zip(self.root, zip_path)
        self.assertIn("2.0.0", str(raised.exception))

    def test_an_invalid_handoff_is_refused_with_the_failing_path(self):
        bad = _handoff("leads", "1.0.0")
        bad["checklist"][0]["answer"] = "maybe"
        zip_path = _good(self.tmp, extra={"packs/leads/handoff.json": bad})
        with self.assertRaises(ws.WorkshopError) as raised:
            ws.ingest_zip(self.root, zip_path)
        self.assertIn("handoff", str(raised.exception))

    def test_path_traversal_is_refused(self):
        zip_path = _good(self.tmp, extra={"packs/leads/../../evil.txt": "x"})
        with self.assertRaises(ws.WorkshopError) as raised:
            ws.ingest_zip(self.root, zip_path)
        self.assertIn("unsafe", str(raised.exception))
        self.assertFalse((self.root / "inbox").exists())

    def test_a_name_that_does_not_match_the_batch_is_a_warning_not_a_refusal(self):
        out = ws.ingest_zip(self.root, _good(self.tmp, name="whatever.zip"))
        self.assertEqual(out["status"], "ingested")
        self.assertTrue(any("zip name" in w for w in out["warnings"]))

    def test_an_unknown_roadmap_id_files_under_unsorted_with_a_note(self):
        batch = _batch(("leads", "1.0.0"))
        batch["packs"][0]["roadmapId"] = "not-in-the-roadmap"
        zip_path = _good(self.tmp, extra={"batch.json": batch})
        out = ws.ingest_zip(self.root, zip_path)
        self.assertEqual(out["packs"][0]["area"], "unsorted")
        self.assertTrue(any("pack-roadmap" in w for w in out["warnings"]))

    def test_the_three_schemas_load(self):
        for name in ("batch", "handoff", "verdict"):
            self.assertIn("$id", ws._load_schema(name))


if __name__ == "__main__":
    unittest.main()
