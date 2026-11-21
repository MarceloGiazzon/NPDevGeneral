"""REG-355: a Java class a browser AI wrote for a FLOW is added through the platform's hash-pinned extension seam.

With the machine setting at `all`, `extensions/<Name>.java` is accepted (one confirmation), checked against the class contract and
against the model (a flow must name it, a role must exist), written together with its `untrusted-extension-manifest.json` entry
(the sha256 made by the relay) in one all-or-nothing step, and removed with Undo or `remove-code`.
"""
from __future__ import annotations

import hashlib
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
sys.path.insert(0, str(Path(__file__).resolve().parent))

import npdev_relay_apply as apply_mod  # noqa: E402
import npdev_relay_codepolicy as codepolicy  # noqa: E402
import npdev_relay_engine as engine  # noqa: E402
import npdev_relay_extensions as ext  # noqa: E402
import npdev_relay_session as sessions  # noqa: E402
from test_relay_files import SplitAnswerCase, snapshot  # noqa: E402
from test_relay_apply import manifest  # noqa: E402
from test_relay_pages import mixed  # noqa: E402
from test_relay_themes import seed  # noqa: E402

PATH = "extensions/Greeter.java"
GOOD = """import java.util.Map;

public final class Greeter {
    public Map<String, Object> execute(NPDevProcedureContext ctx) {
        return Map.of("tenant", ctx.tenantId());
    }
}
"""


def model_with(flow_path=PATH, roles=True) -> dict:
    model = seed()
    if roles:
        model["roles"] = [{"name": "admin", "concepts": {}}]
    if flow_path:
        model["flows"] = [{"name": "GreetFlow", "description": "Says hello from a Java class.",
                           "steps": [{"name": "placeholder", "type": "return", "value": "$input"}],
                           "metadata": {"untrustedExtensionEntrypoint": flow_path}}]
    return model


class ContractUnitTests(unittest.TestCase):
    def test_only_a_plain_extensions_java_path_is_a_class(self):
        self.assertTrue(ext.is_extension_path("extensions/Greeter.java"))
        for bad in ("extensions/sub/Greeter.java", "extensions/Greeter.txt", "web/Greeter.java", "extensions/1Bad.java", "Greeter.java"):
            self.assertFalse(ext.is_extension_path(bad), bad)

    def test_the_kind_and_the_matrix(self):
        self.assertEqual(codepolicy.kind_of(PATH), "java-extension")
        self.assertFalse(codepolicy.accepts("never", "java-extension") or codepolicy.accepts("pages", "java-extension"))
        self.assertTrue(codepolicy.accepts("all", "java-extension"))
        self.assertEqual(len(codepolicy.confirmations(PATH, "java-extension")), 1)   # not a web file: one tick
        self.assertNotIn("java-extension", codepolicy.NOT_WRITTEN_YET)               # capabilities and hooks still are

    def test_a_good_class_bound_to_one_flow_and_a_role_has_no_errors(self):
        self.assertEqual(ext.check({PATH: GOOD}, model_with(), {}, None)["errors"], [])

    def test_each_way_to_break_the_contract_is_said_in_words(self):
        def errors(text=GOOD, **kw):
            return " | ".join(ext.check({PATH: text}, model_with(**kw), {}, None)["errors"])

        self.assertIn("public final class Greeter", errors(GOOD.replace("Greeter", "Other")))
        self.assertIn("execute(NPDevProcedureContext ctx)", errors(GOOD.replace("execute", "run")))
        self.assertIn("none does", errors(flow_path=None))
        self.assertIn("declares no role", errors(roles=False))
        self.assertIn("too long", errors(GOOD + " " * ext.MAX_CHARS))
        dangling = " | ".join(ext.check({}, model_with("extensions/Missing.java"), {}, None)["errors"])
        self.assertIn("extensions/Missing.java", dangling)

    def test_a_flow_may_name_a_class_the_app_already_has_at_any_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "trusted-source").mkdir()
            (Path(tmp) / "trusted-source" / "Old.java").write_text("x", encoding="utf-8")
            self.assertEqual(ext.check({}, model_with("trusted-source/Old.java"), {}, tmp)["errors"], [])

    def test_the_role_is_admin_when_there_is_one_else_the_first_never_none_guessed(self):
        self.assertEqual(ext.role_for([{"name": "clerk"}, {"name": "Admin"}]), "Admin")
        self.assertEqual(ext.role_for([{"name": "clerk"}, {"name": "boss"}]), "clerk")
        self.assertIsNone(ext.role_for([]))

    def test_the_manifest_entry_carries_a_hash_made_here_and_keeps_the_other_entries(self):
        with tempfile.TemporaryDirectory() as tmp:
            other = {"entryId": "keep", "kind": "widget", "relativePath": "w.js", "sha256": "0" * 64}
            (Path(tmp) / ext.MANIFEST).write_text(json.dumps({"schemaVersion": ext.MANIFEST_SCHEMA, "entries": [other]}), encoding="utf-8")
            doc = json.loads(ext.manifest_text(tmp, [{"path": PATH, "content": GOOD}], model_with(), {}))
        by_path = {e["relativePath"]: e for e in doc["entries"]}
        self.assertEqual(by_path["w.js"], other)
        entry = by_path[PATH]
        self.assertEqual(entry["sha256"], hashlib.sha256(GOOD.encode("utf-8")).hexdigest())
        self.assertEqual((entry["kind"], entry["className"], entry["method"], entry["requiredRole"], entry["runtimeBinding"]),
                         ("flow", "Greeter", "execute", "admin", "flow:GreetFlow"))


class GeneratorFixtureTests(unittest.TestCase):
    """The generator's UntrustedExtensionRelayWrittenTest generates an app from this folder; here it is pinned to the relay's own
    code, so the manifest the generator accepts is the one this module writes (and not a hand-kept copy)."""

    FIXTURE = Path(__file__).resolve().parents[2] / "NPDevGenerator" / "generator" / "src" / "test" / "resources" / "relay-java-extension"

    def test_the_fixture_is_exactly_what_the_relay_writes_for_it(self):
        java = (self.FIXTURE / PATH).read_bytes()
        model = json.loads((self.FIXTURE / "model.json").read_text(encoding="utf-8"))
        with tempfile.TemporaryDirectory() as empty:
            made = ext.manifest_text(empty, [{"path": PATH, "content": java.decode("utf-8")}], model, {})
        self.assertEqual(made.encode("utf-8"), (self.FIXTURE / ext.MANIFEST).read_bytes())
        self.assertEqual(ext.check({PATH: java.decode("utf-8")}, model, {}, None)["errors"], [])


class JavaAnswerTests(SplitAnswerCase):
    """Whole answers with phase f4 and the machine setting at `all`."""

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

    def by_path(self, result: dict, path: str) -> dict:
        return next(f for f in result["files"] if f["path"] == path)

    def ingest(self, app: Path, model: dict, texts: dict):
        code = self.start(app, ask="Add a flow written in Java")
        named = {"model.json": model, "npdev-submission.json": manifest()}
        return code, engine.ingest(code, mixed(code, 1, named, texts), source="paste")

    def test_a_class_is_confirmed_written_with_its_manifest_entry_and_undone(self):
        app = self.make_app()
        before = snapshot(app)
        with self.mode("all"):
            code, result = self.ingest(app, model_with(), {PATH: GOOD})
            self.assertEqual(result["outcome"], "READY", (result["findings"], result.get("errorLines")))
            self.assertEqual(self.by_path(result, PATH)["code"]["kind"], "java-extension")
            ticks = [w for w in result["preview"]["warnings"] if w["code"].startswith("code")]
            self.assertEqual([w["code"] for w in ticks], codepolicy.confirmations(PATH, "java-extension"))
            self.assertIn("runs on the app's server", ticks[0]["text"])
            self.assertEqual(snapshot(app), before)
            refused = apply_mod.apply_round(sessions.load(code), None, [])
            self.assertEqual((refused["ok"], refused["error"]), (False, "confirmation-required"))
            done = apply_mod.apply_round(sessions.load(code), None, codepolicy.confirmations(PATH, "java-extension"))
            self.assertTrue(done["ok"], done)
        self.assertEqual(done["files"], ["browser-ai-code.json", PATH, ext.MANIFEST, "model.json"])
        written = (app / PATH).read_bytes()
        self.assertEqual(written.decode("utf-8"), GOOD)
        entry = json.loads((app / ext.MANIFEST).read_text(encoding="utf-8"))["entries"][0]
        self.assertEqual(entry["sha256"], hashlib.sha256(written).hexdigest())   # the hash the generator will check
        self.assertEqual(json.loads((app / "model.json").read_text(encoding="utf-8"))["flows"][0]["metadata"]
                         ["untrustedExtensionEntrypoint"], PATH)
        undone = apply_mod.undo(sessions.load(code))
        self.assertTrue(undone["ok"], undone)
        self.assertEqual(snapshot(app), before)    # the class, its manifest entry, its record and the flow all go with Undo

    def test_remove_code_takes_the_class_and_its_manifest_entry_and_says_which_flow_still_names_it(self):
        app = self.make_app()
        with self.mode("all"):
            code, _ = self.ingest(app, model_with(), {PATH: GOOD})
            done = apply_mod.apply_round(sessions.load(code), None, codepolicy.confirmations(PATH, "java-extension"))
            self.assertTrue(done["ok"], done)
        removed = codepolicy.remove_code(app)
        self.assertEqual(removed["removed"], [PATH])
        self.assertFalse((app / PATH).exists())
        self.assertFalse((app / ext.MANIFEST).exists())          # the last entry: the whole manifest goes
        self.assertEqual(removed["javaExtensions"][PATH], {"manifest": "removed", "stillNamedBy": ["model.json"]})

    def test_an_answer_that_breaks_the_contract_is_sent_back_and_nothing_is_written(self):
        app = self.make_app()
        before = snapshot(app)
        with self.mode("all"):
            _, result = self.ingest(app, model_with(flow_path=None), {PATH: GOOD})
        self.assertEqual(result["outcome"], "NEEDS_FIX")
        self.assertIn("exactly one flow must name it", " ".join(result["errorLines"]))
        self.assertEqual(snapshot(app), before)

    def test_with_pages_or_never_a_class_is_quarantined(self):
        for mode in ("never", "pages"):
            with self.mode(mode):
                _, result = self.ingest(self.make_app(mode), model_with(), {PATH: GOOD})
            self.assertEqual(self.by_path(result, PATH)["status"], "quarantine", mode)

if __name__ == "__main__":
    unittest.main()
