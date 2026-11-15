"""FORM P6-T3: `npdev pack workshop test` -- the status ladder (valid, composes, secure) and its verdict + feedback."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

CLI_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(CLI_DIR))
import npdev_pack_workshop as ws  # noqa: E402


def _pack(pack="leads", version="1.0.0", **extra):
    doc = {"dslVersion": "1.0.0", "pack": pack, "version": version,
           "concepts": [{"name": "Lead", "access": {"read": "true", "write": "true"},
                         "fields": [{"name": "id", "type": "uuid", "id": True, "required": True}]}]}
    doc.update(extra)
    return doc


def _put(root: Path, doc: dict, area="crm", handoff=None) -> Path:
    folder = root / "packs" / area / doc["pack"] / doc["version"]
    folder.mkdir(parents=True)
    (folder / "pack.json").write_text(json.dumps(doc), encoding="utf-8")
    if handoff is not None:
        (folder / "handoff.json").write_text(json.dumps(handoff), encoding="utf-8")
    return folder


def _ok_report(model, report_path):
    return {"status": "passed", "diagnostics": []}


def _update_ok(model):
    return True, ""


class LadderTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.addCleanup(self._tmp.cleanup)

    def run_ladder(self, pack="leads", version=None, validate=_ok_report, update=_update_ok):
        return ws.test_pack(None, self.root, pack, version, stamp="20261114-100000", validate=validate, update=update)

    def steps(self, verdict):
        return {s["step"]: s["result"] for s in verdict["steps"]}

    def test_clean_pack_reaches_secure_and_says_rung_four_was_not_run(self):
        _put(self.root, _pack())
        verdict = self.run_ladder()
        self.assertEqual(verdict["status"], "secure")
        self.assertEqual(self.steps(verdict), {"static": "pass", "compose": "pass", "access-lint": "pass", "behavior-probes": "skipped"})
        written = json.loads((Path(verdict["_run"]) / "verdict.json").read_text(encoding="utf-8"))
        self.assertEqual(written["status"], "secure")
        self.assertNotIn("_run", written)

    def test_static_failure_rejects_and_writes_feedback_lines(self):
        _put(self.root, _pack(signature="x"))
        verdict = self.run_ladder()
        self.assertEqual(verdict["status"], "rejected")
        self.assertEqual(self.steps(verdict), {"static": "fail"})
        self.assertTrue(any("signature" in line for line in verdict["feedbackForAi"]))

    def test_secret_in_pack_is_rejected(self):
        _put(self.root, _pack(description="password = hunter2hunter2hunter2 token=AKIAABCDEFGHIJKLMNOP"))
        verdict = self.run_ladder()
        self.assertEqual(verdict["status"], "rejected")

    def test_handoff_checklist_answered_no_is_rejected(self):
        handoff = {"schemaVersion": "npdev-pack-handoff.v1", "pack": "leads", "version": "1.0.0",
                   "checklist": [{"id": "own-record-write-null-owner", "answer": "no"}], "limits": ["x"]}
        _put(self.root, _pack(), handoff=handoff)
        verdict = self.run_ladder()
        self.assertEqual(verdict["status"], "rejected")
        self.assertIn("own-record-write-null-owner", verdict["feedbackForAi"][0])

    def test_validator_error_rejects_at_compose_with_the_diagnostic(self):
        _put(self.root, _pack())

        def failing(model, report_path):
            return {"status": "failed", "diagnostics": [{"severity": "error", "path": "concepts[0]", "message": "bad thing",
                                                          "suggestedFix": "do this"}]}
        verdict = self.run_ladder(validate=failing)
        self.assertEqual(verdict["status"], "rejected")
        self.assertEqual(self.steps(verdict), {"static": "pass", "compose": "fail"})
        self.assertEqual(verdict["feedbackForAi"], ["concepts[0] - bad thing - do this"])

    def test_no_validator_on_this_machine_is_not_a_rejection(self):
        _put(self.root, _pack())
        verdict = self.run_ladder(validate=lambda model, report: None)
        self.assertEqual(verdict["status"], "valid")
        self.assertEqual(self.steps(verdict)["compose"], "skipped")

    def test_missing_dependency_is_rejected_naming_it(self):
        _put(self.root, _pack(packs=[{"pack": "ghost-pack", "version": "1.0.0"}]))
        verdict = self.run_ladder()
        self.assertEqual(verdict["status"], "rejected")
        self.assertIn("ghost-pack", verdict["feedbackForAi"][0])

    def test_dependency_in_workshop_is_composed_and_lock_updated(self):
        _put(self.root, _pack("base-thing"))
        _put(self.root, _pack("leads", packs=[{"pack": "base-thing", "version": "1.0.0"}]))
        seen = []
        verdict = self.run_ladder(update=lambda model: (seen.append(model.name) or True, ""))
        self.assertEqual(verdict["status"], "secure")
        self.assertEqual(seen, ["model.json"])
        model = json.loads((Path(verdict["_run"]) / "compose" / "model.json").read_text(encoding="utf-8"))
        self.assertEqual([p["as"] for p in model["packs"]], ["base-thing", "leads"])

    def test_unresolvable_composition_is_rejected(self):
        _put(self.root, _pack("base-thing"))
        _put(self.root, _pack("leads", packs=[{"pack": "base-thing", "version": "1.0.0"}]))
        verdict = self.run_ladder(update=lambda model: (False, "line one\nversion conflict on base-thing"))
        self.assertEqual(verdict["status"], "rejected")
        self.assertIn("version conflict", verdict["feedbackForAi"][0])

    def test_unqualified_rollup_is_a_lint_error(self):
        doc = _pack()
        doc["concepts"][0]["rollups"] = [{"field": "total", "from": "LeadLine"}]
        _put(self.root, doc)
        verdict = self.run_ladder()
        self.assertEqual(verdict["status"], "rejected")
        self.assertEqual(self.steps(verdict)["access-lint"], "fail")
        self.assertIn("leads::LeadLine", verdict["feedbackForAi"][0])

    def test_own_record_write_without_null_alternative_is_a_lint_error(self):
        doc = _pack()
        doc["concepts"][0]["access"] = {"read": "true", "write": "role('rep') && owner == $user.id"}
        _put(self.root, doc)
        verdict = self.run_ladder()
        self.assertEqual(verdict["status"], "rejected")
        self.assertIn("owner == null", verdict["feedbackForAi"][0])

    def test_concept_without_access_is_a_warning_not_a_failure(self):
        doc = _pack()
        del doc["concepts"][0]["access"]
        _put(self.root, doc)
        verdict = self.run_ladder()
        self.assertEqual(verdict["status"], "secure")
        self.assertTrue(verdict["feedbackForAi"][0].startswith("(warning) Lead.access"))

    def test_highest_version_is_tested_by_default_and_a_named_one_on_request(self):
        _put(self.root, _pack(version="1.2.0"))
        _put(self.root, _pack(version="1.10.0"))
        self.assertEqual(self.run_ladder()["version"], "1.10.0")
        self.assertEqual(self.run_ladder(version="1.2.0")["version"], "1.2.0")

    def test_unknown_pack_is_refused(self):
        with self.assertRaises(ws.WorkshopError):
            self.run_ladder(pack="nothing")


if __name__ == "__main__":
    unittest.main()
