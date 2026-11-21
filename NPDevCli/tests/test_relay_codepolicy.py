"""REG-354: what code a browser AI may add, decided by ONE per-machine setting (never | pages | all, default never).

Part 1 the setting itself (where it is read from, that nothing can make it looser by accident). Part 2 the kinds of code and what the
scan says about them. Part 3 whole answers through ingest -> apply -> undo -> remove: with `never` the code is quarantined and nothing
changes; with `pages` or `all` each accepted file needs its own confirmation, is recorded with where it came from, and goes away with Undo.

Run with: python -m unittest NPDevCli.tests.test_relay_codepolicy -v
"""
from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE))

import npdev_relay_apply as apply_mod  # noqa: E402
import npdev_relay_codepolicy as codepolicy  # noqa: E402
import npdev_relay_engine as engine  # noqa: E402
import npdev_relay_session as sessions  # noqa: E402
from test_relay_files import SplitAnswerCase, snapshot  # noqa: E402
from test_relay_apply import manifest  # noqa: E402
from test_relay_pages import PAGE, mixed  # noqa: E402
from test_relay_themes import seed  # noqa: E402

JS = "document.getElementById('go').addEventListener('click', function () { alert('hello'); });\n"
JS_FETCH = "fetch('/api/x/orders').then(function (r) { return r.json(); });\nlocalStorage.setItem('seen', '1');\n"
HTML_WITH_SCRIPT = "<!doctype html><html><body><h1>Hi</h1><script>console.log(1)</script></body></html>\n"
JAVA = "package x; public class Hello { void run() { Runtime.getRuntime(); } }\n"


class SettingTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        env = {"NPDEV_MANAGER_HOME": self.tmp.name}
        patcher = mock.patch.dict(os.environ, env)
        patcher.start()
        self.addCleanup(patcher.stop)
        os.environ.pop(codepolicy.ENV_VAR, None)

    def test_with_nothing_set_the_answer_is_never(self):
        self.assertEqual(codepolicy.code_mode(), "never")
        self.assertEqual(codepolicy.where_set(), "default")

    def test_the_settings_file_sets_it_and_keeps_every_other_key(self):
        (Path(self.tmp.name) / "settings.json").write_text(json.dumps({"theme": "dark", "relay": {"other": 1}}), encoding="utf-8")
        codepolicy.set_code_mode("all")
        data = json.loads((Path(self.tmp.name) / "settings.json").read_text(encoding="utf-8"))
        self.assertEqual(data, {"theme": "dark", "relay": {"other": 1, "codeFromBrowserAi": "all"}})
        self.assertEqual((codepolicy.code_mode(), codepolicy.where_set()), ("all", "file"))

    def test_the_environment_wins_over_the_file(self):
        codepolicy.set_code_mode("all")
        with mock.patch.dict(os.environ, {codepolicy.ENV_VAR: "pages"}):
            self.assertEqual((codepolicy.code_mode(), codepolicy.where_set()), ("pages", "env"))

    def test_anything_unreadable_or_unknown_can_only_make_it_stricter(self):
        (Path(self.tmp.name) / "settings.json").write_text("{ not json", encoding="utf-8")
        self.assertEqual(codepolicy.code_mode(), "never")
        (Path(self.tmp.name) / "settings.json").write_text(json.dumps({"relay": {"codeFromBrowserAi": "EVERYTHING"}}), encoding="utf-8")
        self.assertEqual(codepolicy.code_mode(), "never")
        with mock.patch.dict(os.environ, {codepolicy.ENV_VAR: "yes please"}):
            self.assertEqual(codepolicy.code_mode(), "never")

    def test_a_mode_that_does_not_exist_is_refused_and_writes_nothing(self):
        with self.assertRaises(ValueError):
            codepolicy.set_code_mode("sometimes")
        self.assertFalse((Path(self.tmp.name) / "settings.json").exists())


class KindsAndScanTests(unittest.TestCase):
    def test_which_kind_is_which(self):
        self.assertEqual(codepolicy.kind_of("web/app.js"), "js-page")
        self.assertEqual(codepolicy.kind_of("web/app.mjs"), "js-page")
        self.assertEqual(codepolicy.kind_of("capabilities/Hello.java"), "java-capability")
        self.assertEqual(codepolicy.kind_of("conversion-hooks/x.java"), "conversion-hook")
        self.assertEqual(codepolicy.kind_of("web/a.html", "html-has-script"), "page-script")
        self.assertIsNone(codepolicy.kind_of("web/a.html"))

    def test_the_matrix_never_pages_all(self):
        for kind in codepolicy.KINDS:
            self.assertFalse(codepolicy.accepts("never", kind))
        self.assertTrue(codepolicy.accepts("pages", "js-page") and codepolicy.accepts("pages", "page-script"))
        self.assertFalse(codepolicy.accepts("pages", "java-capability") or codepolicy.accepts("pages", "conversion-hook"))
        for kind in codepolicy.KINDS:
            self.assertTrue(codepolicy.accepts("all", kind))
        self.assertFalse(codepolicy.accepts("all", None) or codepolicy.accepts("all", "binary"))

    def test_the_scan_says_what_the_code_does_and_stays_quiet_about_plain_code(self):
        self.assertEqual([p["id"] for p in codepolicy.scan(JS_FETCH, "js-page")], ["storage", "network"])
        self.assertEqual(codepolicy.scan(JS, "js-page"), [])
        self.assertEqual([p["id"] for p in codepolicy.scan(JAVA, "java-capability")], ["programs"])
        self.assertTrue(all(p["pt"] and p["en"] for p in codepolicy.scan(JS_FETCH, "js-page")))

    def test_a_web_file_needs_two_confirmations_and_java_one(self):
        web = codepolicy.confirmations("web/a.js", "js-page")
        self.assertEqual([c.split(":")[0] for c in web], ["code", "code-public"])
        self.assertEqual(len(codepolicy.confirmations("capabilities/H.java", "java-capability")), 1)
        for code in web + codepolicy.confirmations("web/my page (1).js", "js-page"):   # what the Manager's Rust side will pass on
            self.assertTrue(all(c.isalnum() or c in "_-:.>" for c in code), code)
        self.assertNotEqual(web, codepolicy.confirmations("web/b.js", "js-page"))

    def test_the_prompt_sentence_exists_only_when_code_is_accepted(self):
        self.assertEqual(codepolicy.briefing("never"), "")
        self.assertIn("JavaScript", codepolicy.briefing("pages"))
        self.assertIn("Do not send Java", codepolicy.briefing("all"))   # accepted by the relay, not writable yet (REG-355)


class CodeAnswerTests(SplitAnswerCase):
    """Whole answers with phase f4: the machine's setting decides what happens to code."""

    def setUp(self):
        super().setUp()
        self.home = tempfile.TemporaryDirectory()
        self.addCleanup(self.home.cleanup)
        patcher = mock.patch.dict(os.environ, {"NPDEV_MANAGER_HOME": self.home.name})
        patcher.start()
        self.addCleanup(patcher.stop)
        os.environ.pop(codepolicy.ENV_VAR, None)

    def mode(self, mode: str):
        return mock.patch.dict(os.environ, {codepolicy.ENV_VAR: mode})

    def ingest(self, app: Path, texts: dict):
        code = self.start(app, ask="Add a page that says hello")
        named = {"model.json": seed(), "npdev-submission.json": manifest()}
        return code, engine.ingest(code, mixed(code, 1, named, texts), source="paste")

    def by_path(self, result: dict, path: str) -> dict:
        return next(f for f in result["files"] if f["path"] == path)

    def test_with_never_code_is_quarantined_and_nothing_is_written(self):
        app = self.make_app()
        before = snapshot(app)
        with self.mode("never"):
            code, result = self.ingest(app, {"web/app.js": JS})
        self.assertEqual(self.by_path(result, "web/app.js")["status"], "quarantine")
        self.assertNotIn("code", self.by_path(result, "web/app.js"))
        self.assertIn("code-quarantined", [f["code"] for f in result["findings"]])
        self.assertFalse(any(w["code"].startswith("code") for w in (result.get("preview") or {}).get("warnings", [])))
        self.assertEqual(snapshot(app), before)

    def test_with_pages_a_js_page_needs_both_confirmations_then_is_written_recorded_and_undone(self):
        app = self.make_app()
        before = snapshot(app)
        with self.mode("pages"):
            code, result = self.ingest(app, {"web/app.js": JS_FETCH})
            self.assertEqual(result["outcome"], "READY", (result["findings"], result.get("errorLines")))
            entry = self.by_path(result, "web/app.js")
            self.assertEqual((entry["status"], entry["code"]["kind"]), ("allowed", "js-page"))
            ticks = {w["code"]: w for w in result["preview"]["warnings"] if w["code"].startswith("code")}
            want = codepolicy.confirmations("web/app.js", "js-page")
            self.assertEqual(sorted(ticks), sorted(want))
            self.assertTrue(all(w["needsConfirmation"] for w in ticks.values()))
            self.assertIn("talks to a server", ticks[want[0]]["text"])                 # what it does, in words
            self.assertEqual(snapshot(app), before)                                   # nothing is written before the owner applies

            refused = apply_mod.apply_round(sessions.load(code), None, [])
            self.assertEqual((refused["ok"], refused["error"]), (False, "confirmation-required"))
            self.assertEqual(refused["missing"], sorted(want))
            half = apply_mod.apply_round(sessions.load(code), None, [want[0]])
            self.assertEqual(half["missing"], [want[1]])
            self.assertEqual(snapshot(app), before)

            done = apply_mod.apply_round(sessions.load(code), None, want)
            self.assertTrue(done["ok"], done)
        self.assertEqual(done["files"], ["browser-ai-code.json", "web/app.js", "model.json"])   # model.json last
        self.assertEqual((app / "web" / "app.js").read_text(encoding="utf-8"), JS_FETCH)
        record = json.loads((app / "browser-ai-code.json").read_text(encoding="utf-8"))
        item = record["files"][0]
        self.assertEqual((item["path"], item["kind"], item["origin"], item["conversation"]), ("web/app.js", "js-page", "browser-ai", code))
        self.assertEqual(len(item["sha256"]), 64)
        self.assertEqual(item["scan"], ["storage", "network"])
        undone = apply_mod.undo(sessions.load(code))
        self.assertTrue(undone["ok"], undone)
        self.assertEqual(snapshot(app), before)                                       # the code and its record go with Undo

    def test_a_html_page_with_a_script_is_code_too(self):
        app = self.make_app()
        with self.mode("pages"):
            code, result = self.ingest(app, {"web/hi.html": HTML_WITH_SCRIPT})
            self.assertEqual(self.by_path(result, "web/hi.html")["code"]["kind"], "page-script")
            self.assertEqual(apply_mod.apply_round(sessions.load(code), None, [])["error"], "confirmation-required")
        with self.mode("never"):
            _, quarantined = self.ingest(self.make_app("other"), {"web/hi.html": HTML_WITH_SCRIPT})
        self.assertEqual(self.by_path(quarantined, "web/hi.html")["status"], "quarantine")

    def test_a_page_without_a_script_never_asks_for_a_code_confirmation(self):
        app = self.make_app()
        with self.mode("all"):
            code, result = self.ingest(app, {"web/plain.html": PAGE})
            self.assertNotIn("code", self.by_path(result, "web/plain.html"))
            self.assertTrue(apply_mod.apply_round(sessions.load(code), None, [])["ok"])
        self.assertFalse((app / "browser-ai-code.json").exists())

    def test_java_is_quarantined_with_pages_and_sent_back_with_a_reason_with_all(self):
        with self.mode("pages"):
            _, result = self.ingest(self.make_app("first"), {"capabilities/Hello.java": JAVA})
        self.assertEqual(self.by_path(result, "capabilities/Hello.java")["status"], "quarantine")
        app = self.make_app()
        before = snapshot(app)
        with self.mode("all"):
            _, result = self.ingest(app, {"capabilities/Hello.java": JAVA})
        self.assertEqual(self.by_path(result, "capabilities/Hello.java")["code"]["kind"], "java-capability")
        self.assertEqual(result["outcome"], "NEEDS_FIX")
        self.assertIn("cannot be added to an app from here yet", " ".join(result["errorLines"]))
        self.assertEqual(snapshot(app), before)

    def test_a_result_that_carries_accepted_code_still_matches_the_result_schema(self):
        try:
            import jsonschema
        except ImportError:
            self.skipTest("jsonschema not installed")
        import npdev_relay_ingest as ri
        schema = json.loads((HERE.parents[1] / "schemas" / "ai" / "ai-relay-ingest-result.schema.json").read_text(encoding="utf-8"))
        raw = mixed("ABCD", 1, {"model.json": seed()}, {"web/app.js": JS_FETCH})
        with self.mode("pages"):
            result = ri.ingest_text(raw, session="ABCD", expected_round=1, policy=ri.load_policy(), phase="f4")
        self.assertEqual(self.by_path(result, "web/app.js")["code"]["kind"], "js-page")
        errors = [e.message for e in jsonschema.Draft202012Validator(schema).iter_errors(result)]
        self.assertEqual(errors, [])

    def test_code_is_never_accepted_outside_phase_f4(self):
        import npdev_relay_ingest as ri
        raw = mixed("ABCD", 1, {"model.json": seed()}, {"web/app.js": JS})
        with self.mode("all"):
            result = ri.ingest_text(raw, session="ABCD", expected_round=1, policy=ri.load_policy(), phase="mvp")
        self.assertNotIn("allowed", [f["status"] for f in result["files"] if f["path"] == "web/app.js"])

    def test_remove_code_takes_the_files_and_the_record_out_and_never_follows_a_path_out_of_the_app(self):
        app = self.make_app()
        with self.mode("pages"):
            code, _ = self.ingest(app, {"web/app.js": JS})
            apply_mod.apply_round(sessions.load(code), None, codepolicy.confirmations("web/app.js", "js-page"))
        outside = Path(self.home.name) / "outside.txt"
        outside.write_text("keep me", encoding="utf-8")
        record = json.loads((app / "browser-ai-code.json").read_text(encoding="utf-8"))
        record["files"].append({"path": "../" + Path(self.home.name).name + "/outside.txt", "kind": "js-page"})
        (app / "browser-ai-code.json").write_text(json.dumps(record), encoding="utf-8")
        out = codepolicy.remove_code(app)
        self.assertEqual(out["removed"], ["web/app.js"])
        self.assertFalse((app / "web" / "app.js").exists())
        self.assertEqual(outside.read_text(encoding="utf-8"), "keep me")
        self.assertEqual(len(out["kept"]), 1)


if __name__ == "__main__":
    unittest.main()
