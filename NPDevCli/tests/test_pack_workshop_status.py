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

    def test_populated_sits_between_probed_and_browsed_and_is_optional(self):
        base = [_step("static"), _step("compose"), _step("access-lint"), _step("behavior-probes")]
        self.assertLess(ws.RUNGS.index("probed"), ws.RUNGS.index("populated"))
        self.assertLess(ws.RUNGS.index("populated"), ws.RUNGS.index("browsed"))
        rejected = lambda *extra: ws.highest_rung({"status": "rejected", "steps": base + list(extra)})  # noqa: E731
        self.assertEqual(rejected(_step("populate"), _step("exposure-matrix", "fail")), "populated")
        self.assertEqual(rejected(_step("populate", "fail")), "probed")
        self.assertEqual(rejected(_step("populate", "skipped"), _step("exposure-matrix", "fail")), "probed")
        # a pack with no demo/ skips the rung: the rungs ABOVE it are still reachable and still counted
        self.assertEqual(rejected(_step("populate", "skipped"), _step("screens"), _step("owner-review", "fail")), "browsed")
        self.assertEqual(ws.highest_rung({"status": "populated", "steps": []}), "populated")

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

    def run_with_results(self, stamp="20261114-100000", screens=True):
        """A probed run with a probe result, an exposure matrix, a log holding a throwaway key, and (optionally) screens."""
        folder = self.root / "packs" / "crm" / "alpha" / "1.0.0"
        probe = {"users": [{"name": "boss", "role": "MANAGER"}, {"name": "rep", "role": "REP"}],
                 "steps": [{"name": "boss approves", "as": "boss", "method": "PUT", "path": "/api/q/1", "expect": {"status": 200}},
                           {"name": "rep cannot approve", "as": "rep", "method": "PUT", "path": "/api/q/1", "expect": {"status": [403, 404]}}]}
        (folder / "probes" / "p.json").write_text(json.dumps(probe), encoding="utf-8")
        self.verdict("alpha", "probed", [_step("static"), _step("compose"), _step("access-lint"), _step("behavior-probes")],
                     ["(warning) Quote.notes - consider a rule"], stamp=stamp)
        run = self.root / "runs" / "alpha@1.0.0" / stamp
        (run / "probes.json").write_text(json.dumps({"p.json": [{"name": "boss approves", "as": "boss", "ok": True, "detail": ""},
                                                                {"name": "rep cannot approve", "as": "rep", "ok": True, "detail": ""}]}),
                                         encoding="utf-8")
        (run / "exposure-matrix.json").write_text(json.dumps({"matrix": {"q": {"boss": {"read": 200, "rows": 1}, "rep": {"read": 403, "rows": 0}}},
                                                              "violations": [], "warnings": []}), encoding="utf-8")
        (run / "consumer" / "logs").mkdir(parents=True)
        (run / "consumer" / "logs" / "start.log").write_text("keys probe-" + "ab" * 16 + "=dev:boss:MANAGER\nstarted\n", encoding="utf-8")
        if screens:
            (run / "screens").mkdir()
            (run / "screens" / "boss-home.png").write_bytes(b"\x89PNG fake")
            (run / "screens" / "manifest.json").write_text(json.dumps([
                {"user": "boss", "role": "MANAGER", "file": "boss-home.png", "screen": "Home", "rows": 3},
                {"user": "boss", "navLinks": 4, "consoleErrors": []}]), encoding="utf-8")
        return run

    def test_promote_builds_evidence_before_the_confirmation_and_stages_it(self):
        self.pack("alpha")
        run = self.run_with_results()
        with self.assertRaises(ws.WorkshopError) as ctx:
            ws.promote_pack(self.root, "alpha", "1.0.0", False)
        message = str(ctx.exception)
        self.assertIn("evidence.html", message)
        self.assertIn("2/2 probe steps passed", message)
        self.assertIn("1/1 expected refusals held", message)
        page = (run / "evidence" / "evidence.html").read_text(encoding="utf-8")
        self.assertIn("rep cannot approve", page)
        self.assertIn("403 or 404", page)
        self.assertIn("screens/boss-home.png", page)
        self.assertIn("consider a rule", page)
        self.assertFalse((self.root / "npr-staging").exists())

        result = ws.promote_pack(self.root, "alpha", "1.0.0", True)
        evidence = Path(result["staging"]) / "evidence"
        for name in ("evidence.html", "evidence.json", "probe-plan.json", "probes.json", "exposure-matrix.json", "test-verdict.json",
                     "logs/start.log", "screens/boss-home.png", "screens/manifest.json"):
            self.assertTrue((evidence / name).is_file(), name)
        log = (evidence / "logs" / "start.log").read_text(encoding="utf-8")
        self.assertNotIn("ab" * 16, log)
        self.assertIn("probe-<scrubbed>", log)
        plan = json.loads((evidence / "probe-plan.json").read_text(encoding="utf-8"))
        self.assertEqual([(p["role"], p["expected"], p["ok"]) for p in plan], [("MANAGER", 200, True), ("REP", [403, 404], True)])
        summary = json.loads((evidence / "evidence.json").read_text(encoding="utf-8"))["summary"]
        self.assertEqual((summary["screenshots"], summary["exposureRoutes"], summary["rung"]), (1, 1, "probed"))
        review = {s["step"]: s for s in json.loads((Path(result["staging"]) / "verdict.json").read_text(encoding="utf-8"))["steps"]}
        self.assertEqual(review["owner-review"]["evidence"], "evidence/evidence.html")

    def test_a_populated_run_puts_the_demo_load_in_the_evidence_and_the_demo_folder_in_the_staging(self):
        folder = self.pack("alpha")
        (folder / "demo" / "cache").mkdir(parents=True)
        (folder / "demo" / "mockuper.yaml").write_text("schema_version: 2\n", encoding="utf-8")
        (folder / "demo" / "cache" / "k1.json").write_text("{}", encoding="utf-8")
        run = self.run_with_results(screens=False)
        populate = run / "populate"
        populate.mkdir()
        key = "probe-" + "cd" * 16
        summary = {"rows": 4, "tables": {"Quote": 3, "Note": 1}, "byIdentity": {"rep1": 2, "mgr": 2}, "refusals": 0, "requests": 4,
                   "countsChecked": {"/api/q": {"table": "Quote", "before": 2, "after": 5, "created": 3}},
                   "line": "4 row(s) in 2 table(s) by 2 identities, 0 refusals"}
        (populate / "summary.json").write_text(json.dumps(summary), encoding="utf-8")
        (populate / "created.jsonl").write_text(json.dumps({"table": "Quote", "as": "rep1", "note": key}) + "\n", encoding="utf-8")
        (populate / "api-docs.json").write_text("{}", encoding="utf-8")   # big and not evidence: it is not copied
        result = ws.promote_pack(self.root, "alpha", "1.0.0", True)
        self.assertIn("demo data: 4 row(s) in 2 table(s) by 2 identities, 0 refusals", result["summary"])
        evidence = Path(result["staging"]) / "evidence"
        self.assertTrue((evidence / "populate" / "summary.json").is_file())
        self.assertFalse((evidence / "populate" / "api-docs.json").exists())
        self.assertNotIn(key, (evidence / "populate" / "created.jsonl").read_text(encoding="utf-8"))
        page = (evidence / "evidence.html").read_text(encoding="utf-8")
        self.assertIn("Demo data", page)
        self.assertIn("/api/q", page)
        data = json.loads((evidence / "evidence.json").read_text(encoding="utf-8"))["summary"]
        self.assertEqual((data["demoRows"], data["demoIdentities"], data["demoRefusals"]), (4, 2, 0))
        for name in ("demo/mockuper.yaml", "demo/cache/k1.json"):
            self.assertTrue((Path(result["staging"]) / name).is_file(), name)

    def test_promote_again_takes_evidence_from_the_test_run_not_the_promote_run(self):
        self.pack("alpha")
        self.run_with_results(screens=False)
        ws.promote_pack(self.root, "alpha", "1.0.0", True)
        result = ws.promote_pack(self.root, "alpha", "1.0.0", True)
        self.assertIn("2/2 probe steps passed", result["summary"])
        self.assertIn("no screenshots", result["summary"])
        page = (Path(result["staging"]) / "evidence" / "evidence.html").read_text(encoding="utf-8")
        self.assertIn("--screens", page)

    def test_rejected_pack_cannot_be_promoted_even_if_it_once_passed(self):
        self.pack("alpha")
        self.verdict("alpha", "rejected", [_step("static"), _step("compose"), _step("access-lint"), _step("behavior-probes", "fail")],
                     ["x"])
        with self.assertRaises(ws.WorkshopError):
            ws.promote_pack(self.root, "alpha", "1.0.0", True)


if __name__ == "__main__":
    unittest.main()
