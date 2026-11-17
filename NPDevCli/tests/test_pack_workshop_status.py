"""FORM P6-T5: `npdev pack workshop status | feedback | promote`."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

CLI_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(CLI_DIR))
import npdev_pack_workshop as ws  # noqa: E402


def _step(name, result="pass", detail=""):
    return {"step": name, "result": result, **({"detail": detail} if detail else {})}


class StatusTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.addCleanup(self._tmp.cleanup)

    def pack(self, pack, version="1.0.0", area="crm"):
        folder = self.root / "packs" / area / pack / version
        folder.mkdir(parents=True)
        (folder / "pack.json").write_text(json.dumps({"pack": pack, "version": version}), encoding="utf-8")
        (folder / "probes").mkdir()
        (folder / "probes" / "p.json").write_text("{}", encoding="utf-8")
        (folder / "handoff.json").write_text("{}", encoding="utf-8")
        return folder

    def verdict(self, pack, status, steps, feedback=None, version="1.0.0", stamp="20261114-100000"):
        run = self.root / "runs" / f"{pack}@{version}" / stamp
        run.mkdir(parents=True)
        doc = {"schemaVersion": "npdev-pack-verdict.v1", "pack": pack, "version": version, "status": status, "steps": steps}
        if feedback:
            doc["feedbackForAi"] = feedback
        (run / "verdict.json").write_text(json.dumps(doc), encoding="utf-8")

    def batch(self, batch_id, *packs):
        (self.root / "batches").mkdir(exist_ok=True)
        entry = {"batchId": batch_id, "deliveries": [{"packs": [{"pack": p, "version": "1.0.0", "area": "crm", "status": "delivered"}
                                                                for p in packs]}]}
        (self.root / "batches" / f"{batch_id}.json").write_text(json.dumps(entry), encoding="utf-8")

    def test_rung_of_rejected_verdict_is_the_highest_climbed(self):
        steps = [_step("static"), _step("compose"), _step("access-lint", "fail")]
        self.assertEqual(ws.highest_rung({"status": "rejected", "steps": steps}), "composes")
        self.assertEqual(ws.highest_rung({"status": "rejected", "steps": [_step("static", "fail")]}), "delivered")
        self.assertEqual(ws.highest_rung({"status": "probed", "steps": []}), "probed")
        self.assertEqual(ws.highest_rung(None), "delivered")

    def test_status_table_untested_rejected_and_probed(self):
        for name in ("alpha", "beta", "gamma"):
            self.pack(name)
        self.verdict("beta", "rejected", [_step("static"), _step("compose", "fail")], ["concepts[0] - bad - fix"])
        self.verdict("gamma", "probed", [_step("static"), _step("compose"), _step("access-lint"), _step("behavior-probes")])
        rows = {r["pack"]: r for r in ws.status_rows(self.root)}
        self.assertEqual((rows["alpha"]["rung"], rows["alpha"]["stamp"]), ("delivered", ""))
        self.assertEqual((rows["beta"]["rung"], rows["beta"]["stoppedAt"]), ("valid", "compose"))
        self.assertEqual(rows["gamma"]["rung"], "probed")
        table = ws.format_status(list(rows.values()))
        self.assertIn("not tested yet", table)
        self.assertIn("REJECTED at compose: concepts[0] - bad - fix", table)

    def test_newest_run_wins(self):
        self.pack("alpha")
        self.verdict("alpha", "rejected", [_step("static", "fail")], ["old"], stamp="20261114-090000")
        self.verdict("alpha", "valid", [_step("static")], stamp="20261114-110000")
        self.assertEqual(ws.status_rows(self.root)[0]["rung"], "valid")

    def test_status_by_batch_and_unknown_batch(self):
        self.pack("alpha")
        self.pack("beta")
        self.batch("w1-crm", "alpha")
        self.assertEqual([r["pack"] for r in ws.status_rows(self.root, "w1-crm")], ["alpha"])
        with self.assertRaises(ws.WorkshopError):
            ws.status_rows(self.root, "w9-none")

    def test_feedback_text_separates_fixes_from_advice_and_names_clean_packs(self):
        for name in ("alpha", "beta", "gamma"):
            self.pack(name)
        self.batch("w1-crm", "alpha", "beta", "gamma")
        self.verdict("alpha", "rejected", [_step("static", "fail")], ["pack.json signature - remove it", "(warning) Lead.access - add a rule"])
        self.verdict("beta", "secure", [_step("static"), _step("compose"), _step("access-lint")], ["(warning) summary claims X"])
        text = ws.feedback_text(self.root, "w1-crm")
        self.assertIn("## alpha@1.0.0: REJECTED at static (reached: delivered)", text)
        self.assertIn("- pack.json signature - remove it", text)
        self.assertIn("Advice (not blocking):\n- Lead.access - add a rule", text)
        self.assertIn("## beta@1.0.0: SECURE - no fix required", text)
        self.assertIn("## gamma@1.0.0: not tested yet", text)
        path, _ = ws.write_feedback(self.root, "w1-crm")
        self.assertTrue(path.name.startswith("w1-crm__") and path.is_file())

    def test_feedback_skips_versions_superseded_by_a_later_one_in_the_batch(self):
        self.pack("alpha")
        self.pack("alpha", "1.0.1")
        (self.root / "batches").mkdir()
        entry = {"batchId": "w1-crm", "deliveries": [{"packs": [
            {"pack": "alpha", "version": "1.0.0", "area": "crm"}, {"pack": "alpha", "version": "1.0.1", "area": "crm"}]}]}
        (self.root / "batches" / "w1-crm.json").write_text(json.dumps(entry), encoding="utf-8")
        text = ws.feedback_text(self.root, "w1-crm")
        self.assertIn("alpha@1.0.1", text)
        self.assertNotIn("alpha@1.0.0", text)

    def test_promote_refuses_until_probed_and_until_confirmed(self):
        self.pack("alpha")
        with self.assertRaises(ws.WorkshopError) as ctx:
            ws.promote_pack(self.root, "alpha", "1.0.0", True)
        self.assertIn("'delivered'", str(ctx.exception))
        self.verdict("alpha", "secure", [_step("static"), _step("compose"), _step("access-lint")])
        with self.assertRaises(ws.WorkshopError) as ctx:
            ws.promote_pack(self.root, "alpha", "1.0.0", True)
        self.assertIn("'secure'", str(ctx.exception))
        self.verdict("alpha", "probed", [_step("static"), _step("compose"), _step("access-lint"), _step("behavior-probes")],
                     stamp="20261114-120000")
        with self.assertRaises(ws.WorkshopError) as ctx:
            ws.promote_pack(self.root, "alpha", "1.0.0", False)
        self.assertIn("--confirm", str(ctx.exception))
        self.assertFalse((self.root / "npr-staging").exists())

    def test_promote_stages_pack_probes_verdict_and_records_the_review(self):
        self.pack("alpha")
        self.verdict("alpha", "probed", [_step("static"), _step("compose"), _step("access-lint"), _step("behavior-probes")])
        result = ws.promote_pack(self.root, "alpha", "1.0.0", True)
        stage = Path(result["staging"])
        for name in ("pack.json", "handoff.json", "verdict.json", "probes/p.json"):
            self.assertTrue((stage / name).is_file(), name)
        staged = json.loads((stage / "verdict.json").read_text(encoding="utf-8"))
        self.assertEqual(staged["status"], "reviewed")
        self.assertEqual({s["step"]: s["result"] for s in staged["steps"]}["owner-review"], "pass")
        self.assertEqual({s["step"]: s["result"] for s in staged["steps"]}["publish"], "skipped")
        self.assertEqual(ws.status_rows(self.root)[0]["rung"], "reviewed")

    def test_rejected_pack_cannot_be_promoted_even_if_it_once_passed(self):
        self.pack("alpha")
        self.verdict("alpha", "rejected", [_step("static"), _step("compose"), _step("access-lint"), _step("behavior-probes", "fail")],
                     ["x"])
        with self.assertRaises(ws.WorkshopError):
            ws.promote_pack(self.root, "alpha", "1.0.0", True)


if __name__ == "__main__":
    unittest.main()
