"""FORM P6-T3: `npdev pack workshop test` -- the status ladder (valid, composes, secure) and its verdict + feedback."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from unittest import mock
from pathlib import Path

CLI_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(CLI_DIR))
import npdev_pack_workshop as ws  # noqa: E402


def _pack(pack="leads", version="1.0.0", **extra):
    doc = {"dslVersion": "3.0.0", "pack": pack, "version": version,
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


def _no_boot(cli, consumer, env):
    raise ws.BootUnavailable("test: no boot here")


class _FakeCli:
    """Just enough of the CLI module for assemble_consumer: the Build root."""

    def __init__(self, root: Path):
        self._build = root / "Build"

    def _ai_build_root(self):
        return self._build


class LadderTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.addCleanup(self._tmp.cleanup)

    def run_ladder(self, pack="leads", version=None, validate=_ok_report, update=_update_ok):
        return ws.test_pack(None, self.root, pack, version, stamp="20261114-100000", validate=validate, update=update,
                            derive_probes=False)

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

    def test_field_named_access_key_holding_an_object_is_not_a_secret(self):
        doc = _pack()
        doc["concepts"][0]["fields"].append({"name": "accessKey", "type": "string"})
        doc["domainTypes"] = [{"name": "Chave", "accessKey": {"digits": 44}}]
        _put(self.root, doc)
        self.assertEqual(self.run_ladder()["status"], "secure")

    def test_scalar_secret_assignment_is_still_caught_next_to_object_properties(self):
        _put(self.root, _pack(description='config {"accessKey": {"a": 1}} then api_key = "sk_live_abcdefghijklmnop1234"'))
        self.assertEqual(self.run_ladder()["status"], "rejected")

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

    def test_compose_failure_also_carries_the_lint_fix(self):
        doc = _pack()
        doc["concepts"][0]["rollups"] = [{"field": "total", "from": "LeadLine"}]
        _put(self.root, doc)

        def failing(model, report_path):
            return {"status": "failed", "diagnostics": [{"severity": "error", "path": "x", "message": "unknown concept 'LeadLine'"}]}
        verdict = self.run_ladder(validate=failing)
        self.assertEqual(self.steps(verdict)["compose"], "fail")
        self.assertEqual(len(verdict["feedbackForAi"]), 2)
        self.assertIn("leads::LeadLine", verdict["feedbackForAi"][1])

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

    def test_roles_in_the_scratch_model_grant_every_composed_packs_concepts(self):
        base = _pack("base-thing")
        base["concepts"][0]["name"] = "Address"
        _put(self.root, base)
        _put(self.root, _pack("leads", packs=[{"pack": "base-thing", "version": "1.0.0"}],
                              requires={"roles": ["salesRep"]}))
        verdict = self.run_ladder()
        model = json.loads((Path(verdict["_run"]) / "compose" / "model.json").read_text(encoding="utf-8"))
        self.assertEqual(sorted(model["roles"][0]["concepts"]), ["base-thing::Address", "leads::Lead"])

    def test_unresolvable_composition_is_rejected(self):
        _put(self.root, _pack("base-thing"))
        _put(self.root, _pack("leads", packs=[{"pack": "base-thing", "version": "1.0.0"}]))
        verdict = self.run_ladder(update=lambda model: (False, "line one\nversion conflict on base-thing"))
        self.assertEqual(verdict["status"], "rejected")
        self.assertIn("version conflict", verdict["feedbackForAi"][0])

    def test_a_schema_refusal_reaches_the_authoring_ai_with_its_real_problems(self):
        # REG-361: `npdev pack update` answers with a JSON document; the workshop used to keep its last line ("}")
        # and tell the AI only "the dependencies could not be resolved together". (The wave-1 packs themselves are
        # not yet re-authored: REG-361 stays open for that; this pins only the feedback text.)
        refusal = json.dumps({
            "status": "failed",
            "error": "Model schema validation failed for X:\r\n"
                     " - $.concepts[1]: $.concepts[1]: property 'truthLevel' is not defined in the schema and the schema "
                     "does not allow additional properties Suggested fix: Adjust the 'truthLevel' property so it matches the canonical schema.\r\n"
                     " - $.dslVersion: $.dslVersion: must be the constant value '3.0.0' Suggested fix: Update the model so it satisfies the schema.\r\n"
                     " - $: $: property 'autoPanels' is not defined in the schema",
        }, indent=2)
        _put(self.root, _pack("base-thing"))
        _put(self.root, _pack("leads", packs=[{"pack": "base-thing", "version": "1.0.0"}]))
        verdict = self.run_ladder(update=lambda model: (False, refusal))
        self.assertEqual(verdict["status"], "rejected")
        feedback = verdict["feedbackForAi"]
        self.assertEqual(len(feedback), 3, feedback)
        self.assertTrue(feedback[0].startswith("$.concepts[1] - property 'truthLevel' is not defined"), feedback[0])
        self.assertIn("Adjust the 'truthLevel' property", feedback[0])
        self.assertIn("must be the constant value '3.0.0'", feedback[1])
        self.assertIn("'autoPanels'", feedback[2])
        self.assertFalse(any("could not be resolved together" in line for line in feedback),
                         "a schema refusal must not be reported as an unresolvable dependency")

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

    # ---- rung 4: probes ------------------------------------------------------------------------------------------

    def _probe(self, **over):
        doc = {"schemaVersion": "npdev-pack-probe.v1", "pack": "leads",
               "users": [{"name": "rep", "role": "REP"}, {"name": "plain"}],
               "steps": [{"name": "rep creates", "as": "rep", "method": "POST", "path": "/api/leads",
                          "body": {"code": "L-${rand}"}, "expect": {"status": 201}, "save": {"leadId": "id"}},
                         {"name": "plain denied", "as": "plain", "method": "GET", "path": "/api/leads/${leadId}",
                          "expect": {"status": 403}}]}
        doc.update(over)
        return doc

    def _put_probe(self, folder: Path, doc, name="basic.json"):
        (folder / "probes").mkdir(exist_ok=True)
        (folder / "probes" / name).write_text(json.dumps(doc), encoding="utf-8")

    def _boot(self, record):
        def boot(cli, consumer, env):
            record["consumer"], record["env"], record["stopped"] = consumer, env, False

            def stop():
                record["stopped"] = True
            return "http://probe.invalid", stop
        return boot

    def ladder4(self, boot, call, version=None):
        return ws.test_pack(_FakeCli(self.root), self.root, "leads", version, stamp="20261114-100000", validate=_ok_report,
                            update=_update_ok, boot=boot, probe_call=call)

    def test_no_probes_means_skipped_and_never_probed(self):
        _put(self.root, _pack())
        verdict = self.run_ladder()
        self.assertEqual(verdict["status"], "secure")
        self.assertIn("no probes", [s for s in verdict["steps"] if s["step"] == "behavior-probes"][0]["detail"])

    def test_probes_pass_reaches_probed_and_stops_the_app(self):
        folder = _put(self.root, _pack())
        self._put_probe(folder, self._probe())
        record = {}
        answers = iter([(201, {"id": "n1"}), (403, {"error": "no"})])
        verdict = self.ladder4(self._boot(record), lambda key, method, path, body: next(answers))
        self.assertEqual(verdict["status"], "probed")
        self.assertTrue(record["stopped"])
        self.assertEqual(self.steps(verdict)["behavior-probes"], "pass")
        self.assertEqual(self.steps(verdict)["exposure-matrix"], "skipped")
        self.assertIn("=dev:rep:REP;", record["env"]["NPDEV_AUTH_API_KEYS"])
        self.assertEqual(record["env"]["NPDEV_AUTH_API_KEYS"], record["env"]["NPDEV_AUTH_APIKEYS"])

    # ---- rung 4 without the authoring AI: probes derived from the pack's own declarations ---------------------------

    def _derivable_pack(self):
        doc = _pack()
        doc["concepts"][0]["access"] = {"read": "$user.roles.contains(role('rep')) || $user.roles.contains('ADMIN')",
                                        "write": "$user.roles.contains(role('rep'))"}
        doc["concepts"][0]["fields"].append({"name": "code", "type": "string", "required": True, "maxLength": 40})
        return _put(self.root, doc)

    def _boot_with_metadata(self, record):
        def boot(cli, consumer, env):
            meta = Path(consumer["outputRoot"]) / "App" / "npdev-generated" / "src" / "main" / "resources" / "npdev"
            meta.mkdir(parents=True)
            (meta / "compiled-metadata.json").write_text(json.dumps({"catalogs": {"invocations": [
                {"kind": "createDirect", "concept": "leads::Lead", "path": "/api/concepts/leads_leads"},
                {"kind": "read", "concept": "leads::Lead", "path": "/api/concepts/leads_leads/{id}"}]}}), encoding="utf-8")
            record["env"] = env
            return "http://probe.invalid", lambda: None
        return boot

    @staticmethod
    def _app(admit_decoy=False, refuse_everything=False):
        """A fake app that follows the pack's rule: only REP may create, REP and ADMIN may read; the decoy is refused."""
        def call(key, method, path, body):
            who = "rep" if key.startswith("probe-") and call.rep_key == key else "decoy"
            if refuse_everything:
                return 422, {"code": "invariant_failed"}
            if who == "rep":
                return (201, {"id": "row1"}) if method == "POST" else (200, {"id": "row1"})
            return (201, {"id": "row2"}) if (admit_decoy and method == "POST") else (403, {"error": "no"})
        call.rep_key = None
        return call

    def _derived_run(self, call):
        record = {}
        boot = self._boot_with_metadata(record)

        def spy(cli, consumer, env):
            # the keys are 'probe-<hex>=dev:<name>:<ROLE>' pairs; remember which key is the rep's
            for pair in env["NPDEV_AUTH_API_KEYS"].split(";"):
                key, ident = pair.split("=", 1)
                if ident.startswith("dev:rep:"):
                    call.rep_key = key
            return boot(cli, consumer, env)
        verdict = ws.test_pack(_FakeCli(self.root), self.root, "leads", None, stamp="20261114-100000", validate=_ok_report,
                               update=_update_ok, boot=spy, probe_call=call)
        return verdict, record

    def test_a_pack_with_no_probes_gets_derived_ones_and_reaches_probed(self):
        self._derivable_pack()
        verdict, record = self._derived_run(self._app())
        self.assertEqual(verdict["status"], "probed")
        detail = [s for s in verdict["steps"] if s["step"] == "behavior-probes"][0]["detail"]
        self.assertIn("DERIVED", detail)
        self.assertIn("=dev:rep:REP", record["env"]["NPDEV_AUTH_API_KEYS"])
        self.assertIn("=dev:decoy:PROBEDECOY", record["env"]["NPDEV_AUTH_API_KEYS"])
        written = list((self.root / "runs" / "leads@1.0.0" / "20261114-100000" / "derived-probes").glob("*.json"))
        self.assertEqual([p.name for p in written], ["derived-Lead.json"])
        self.assertEqual(json.loads(written[0].read_text(encoding="utf-8"))["steps"][0]["path"], "/api/concepts/leads_leads")

    def test_a_pack_that_admits_the_decoy_role_is_rejected_with_the_rule_named(self):
        self._derivable_pack()
        verdict, _ = self._derived_run(self._app(admit_decoy=True))
        self.assertEqual(verdict["status"], "rejected")
        self.assertTrue(verdict["feedbackForAi"][0].startswith("derived probe derived-Lead.json"))
        self.assertIn("access rules", verdict["feedbackForAi"][0])

    def test_a_derived_probe_that_cannot_build_a_row_is_inconclusive_not_a_rejection(self):
        self._derivable_pack()
        verdict, _ = self._derived_run(self._app(refuse_everything=True))
        self.assertEqual(verdict["status"], "secure")
        detail = [s for s in verdict["steps"] if s["step"] == "behavior-probes"][0]
        self.assertEqual(detail["result"], "skipped")
        self.assertIn("inconclusive", detail["detail"])

    # ---- rung 5: screens (opt-in) ----------------------------------------------------------------------------------

    def _shoot(self, record, fail=None):
        import npdev_pack_screens as screens

        def shoot(base_url, out_dir, users, keys):
            record["shot"] = {"base": base_url, "users": [u["name"] for u in users], "keys": dict(keys)}
            if fail:
                raise screens.ScreensUnavailable(fail)
            rows = []
            for u in users:
                (out_dir / f"{u['name']}-home.png").write_bytes(b"png")
                rows.append({"user": u["name"], "role": u.get("role"), "screen": "home", "file": f"{u['name']}-home.png"})
                rows.append({"user": u["name"], "role": u.get("role"), "navLinks": 1, "consoleErrors": []})
            (out_dir / "manifest.json").write_text(json.dumps(rows), encoding="utf-8")
        return shoot

    def ladder5(self, boot, call, shoot, version=None):
        return ws.test_pack(_FakeCli(self.root), self.root, "leads", version, stamp="20261114-100000", validate=_ok_report,
                            update=_update_ok, boot=boot, probe_call=call, screens=True, shoot=shoot)

    def test_screens_after_passing_probes_reach_browsed_and_write_a_gallery_without_keys(self):
        folder = _put(self.root, _pack())
        self._put_probe(folder, self._probe())
        record, shot = {}, {}
        answers = iter([(201, {"id": "n1"}), (403, {"error": "no"})])
        verdict = self.ladder5(self._boot(record), lambda key, method, path, body: next(answers), self._shoot(shot))
        self.assertEqual(verdict["status"], "browsed")
        self.assertTrue(record["stopped"])
        self.assertEqual(shot["shot"]["users"], ["rep", "plain"])
        step = [s for s in verdict["steps"] if s["step"] == "screens"][0]
        self.assertEqual((step["result"], step["evidence"]), ("pass", "screens/gallery.html"))
        run = Path(verdict["_run"])
        self.assertIn("rep", (run / "screens" / "gallery.html").read_text(encoding="utf-8"))
        for file in run.rglob("*"):   # the identities' keys must never be written into the run folder
            if file.is_file() and file.suffix in (".json", ".html", ".txt"):
                text = file.read_text(encoding="utf-8", errors="ignore")
                self.assertFalse(any(k in text for k in shot["shot"]["keys"].values()), file.name)

    def test_screens_without_probes_photograph_one_identity_per_role_and_do_not_claim_probed(self):
        _put(self.root, _pack())
        record, shot = {}, {}
        verdict = self.ladder5(self._boot(record), None, self._shoot(shot))
        self.assertEqual(verdict["status"], "secure")
        steps = self.steps(verdict)
        self.assertEqual((steps["behavior-probes"], steps["screens"]), ("skipped", "pass"))
        self.assertTrue(record["stopped"])
        self.assertTrue(shot["shot"]["users"])
        self.assertIn("not claimed", [s for s in verdict["steps"] if s["step"] == "screens"][0]["detail"])

    def test_no_engine_means_skipped_never_rejected(self):
        folder = _put(self.root, _pack())
        self._put_probe(folder, self._probe())
        answers = iter([(201, {"id": "n1"}), (403, {"error": "no"})])
        verdict = self.ladder5(self._boot({}), lambda key, method, path, body: next(answers),
                               self._shoot({}, fail="the ScrapForAI engine is not installed on this machine"))
        self.assertEqual(verdict["status"], "probed")
        step = [s for s in verdict["steps"] if s["step"] == "screens"][0]
        self.assertEqual(step["result"], "skipped")
        self.assertIn("engine is not installed", step["detail"])

    def test_failed_probe_means_no_screens_and_the_app_is_still_stopped(self):
        folder = _put(self.root, _pack())
        self._put_probe(folder, self._probe())
        record, shot = {}, {}
        answers = iter([(201, {"id": "n1"}), (200, {"leak": True})])
        verdict = self.ladder5(self._boot(record), lambda key, method, path, body: next(answers), self._shoot(shot))
        self.assertEqual(verdict["status"], "rejected")
        self.assertTrue(record["stopped"])
        self.assertNotIn("shot", shot)

    def test_not_booted_skips_the_screens_step_too(self):
        _put(self.root, _pack())
        verdict = ws.test_pack(_FakeCli(self.root), self.root, "leads", None, stamp="20261114-100000", validate=_ok_report,
                               update=_update_ok, boot=_no_boot, screens=True, shoot=self._shoot({}))
        self.assertEqual(verdict["status"], "secure")
        self.assertEqual(self.steps(verdict)["screens"], "skipped")

    def _with_dependency(self):
        _put(self.root, _pack("base-thing"))
        return _put(self.root, _pack("leads", packs=[{"pack": "base-thing", "version": "1.0.0"}]))

    def test_a_consumer_with_dependencies_is_locked_beside_its_own_model(self):
        self._with_dependency()
        locked = []

        def update(model):
            locked.append(model)
            return True, ""

        shot = {}
        verdict = ws.test_pack(_FakeCli(self.root), self.root, "leads", None, stamp="20261114-100000", validate=_ok_report,
                               update=update, boot=self._boot({}), screens=True, shoot=self._shoot(shot))
        self.assertEqual(verdict["status"], "secure")
        self.assertEqual([m.parent.name for m in locked], ["compose", "definition"])   # rung 2, then the consumer itself
        self.assertEqual(self.steps(verdict)["screens"], "pass")

    def test_a_refused_consumer_lock_is_skipped_never_rejected(self):
        self._with_dependency()
        answers = iter([(True, ""), (False, "lock refused")])
        verdict = ws.test_pack(_FakeCli(self.root), self.root, "leads", None, stamp="20261114-100000", validate=_ok_report,
                               update=lambda model: next(answers), boot=self._boot({}), screens=True, shoot=self._shoot({}))
        self.assertEqual(verdict["status"], "secure")
        steps = {s["step"]: s for s in verdict["steps"]}
        self.assertEqual((steps["behavior-probes"]["result"], steps["screens"]["result"]), ("skipped", "skipped"))
        self.assertIn("lock refused", steps["behavior-probes"]["detail"])

    def test_the_engine_is_searched_from_the_repo_and_a_running_one_is_found_again_by_files(self):
        import npdev_monitor
        import npdev_pack_screens as screens
        calls = []

        def detect(port=3010, configured_root=None, workspace_root=None, search_derived=True):
            calls.append((port, workspace_root is not None))
            if port == 3010:
                return {"found": True, "state": "running", "root": None}
            return {"found": True, "state": "installed-stopped", "root": str(self.root)}

        with mock.patch.object(npdev_monitor, "detect_engine", detect):
            self.assertEqual(screens.find_engine_root(), self.root)
        self.assertEqual(calls, [(3010, True), (0, True)])
        with mock.patch.object(npdev_monitor, "detect_engine", lambda **kw: {"found": False, "root": None}):
            with self.assertRaises(screens.ScreensUnavailable):
                screens.find_engine_root()

    def test_default_users_follow_the_roles_the_pack_names(self):
        import npdev_pack_screens as screens
        self.assertEqual(screens.default_users(["REP", "MANAGER"]),
                         [{"name": "rep-user", "role": "REP"}, {"name": "manager-user", "role": "MANAGER"}])
        self.assertEqual(screens.default_users([]), [{"name": "tester", "role": "USER"}])

    def test_gallery_escapes_what_the_app_printed(self):
        import npdev_pack_screens as screens
        page = screens.gallery_html("t<b>", [{"user": "u", "role": "R", "screen": "<script>", "file": "a.png"},
                                              {"user": "u", "role": "R", "navLinks": 1, "consoleErrors": ["<img onerror=x>"]}])
        self.assertNotIn("<script>", page)
        self.assertNotIn("<img onerror", page)

    def test_failed_probe_step_rejects_with_file_step_and_identity(self):
        folder = _put(self.root, _pack())
        self._put_probe(folder, self._probe())
        record = {}
        answers = iter([(201, {"id": "n1"}), (200, {"leak": True})])
        verdict = self.ladder4(self._boot(record), lambda key, method, path, body: next(answers))
        self.assertEqual(verdict["status"], "rejected")
        self.assertTrue(record["stopped"])
        self.assertIn("probes/basic.json step 'plain denied' (as plain)", verdict["feedbackForAi"][0])
        self.assertIn("status 200, wanted 403", verdict["feedbackForAi"][0])

    def test_malformed_probe_is_rejected_before_any_boot(self):
        folder = _put(self.root, _pack())
        self._put_probe(folder, self._probe(schemaVersion="wrong"))
        booted = []
        verdict = self.ladder4(lambda *a: booted.append(1), None)
        self.assertEqual(verdict["status"], "rejected")
        self.assertEqual(booted, [])
        self.assertTrue(verdict["feedbackForAi"][0].startswith("probes/basic.json: probe"))

    def test_probe_for_another_pack_and_conflicting_roles_are_rejected(self):
        folder = _put(self.root, _pack())
        self._put_probe(folder, self._probe(pack="other"))
        self.assertIn("'other'", self.ladder4(None, None)["feedbackForAi"][0])
        self._put_probe(folder, self._probe())
        self._put_probe(folder, self._probe(users=[{"name": "rep", "role": "BOSS"}, {"name": "plain"}]), "second.json")
        verdict = self.ladder4(None, None, version="1.0.0")
        self.assertIn("identity 'rep'", verdict["feedbackForAi"][0])

    def test_default_boot_is_skipped_not_rejected_and_the_consumer_is_assembled(self):
        folder = _put(self.root, _pack())
        self._put_probe(folder, self._probe())
        verdict = ws.test_pack(_FakeCli(self.root), self.root, "leads", None, stamp="20261114-100000", validate=_ok_report, update=_update_ok,
                            boot=_no_boot)
        self.assertEqual(verdict["status"], "secure")
        step = [s for s in verdict["steps"] if s["step"] == "behavior-probes"][0]
        self.assertEqual(step["result"], "skipped")
        definition = Path(verdict["_run"]) / "consumer" / "definition"
        config = json.loads((definition / "config.json").read_text(encoding="utf-8"))
        model = json.loads((definition / "model.json").read_text(encoding="utf-8"))
        self.assertEqual(config["scenario"]["name"], "packprobe-leads")
        self.assertIn("generated-finalapps/packprobe-leads", config["scenario"]["outputRoot"])
        self.assertGreater(config["runtime"]["serverPort"], 0)
        self.assertEqual(model["roles"], [{"name": "REP", "concepts": {"leads::Lead": ["read", "create", "update", "delete"]}}])
        self.assertTrue((definition / "packs" / "leads" / "pack.json").is_file())
        self.assertTrue((definition / "db.definition.json").is_file())

    def test_example_model_wins_over_the_generated_one(self):
        folder = _put(self.root, _pack())
        self._put_probe(folder, self._probe())
        example = {"namespace": "x.example", "dslVersion": "3.0.0", "version": "1.0", "packs": [{"$ref": "packs/leads/pack.json", "as": "leads"}]}
        (folder / "example-model.json").write_text(json.dumps(example), encoding="utf-8")
        verdict = ws.test_pack(_FakeCli(self.root), self.root, "leads", None, stamp="20261114-100000", validate=_ok_report, update=_update_ok,
                            boot=_no_boot)
        model = json.loads((Path(verdict["_run"]) / "consumer" / "definition" / "model.json").read_text(encoding="utf-8"))
        self.assertEqual(model["namespace"], "x.example")

    def test_unknown_pack_is_refused(self):
        with self.assertRaises(ws.WorkshopError):
            self.run_ladder(pack="nothing")


if __name__ == "__main__":
    unittest.main()
