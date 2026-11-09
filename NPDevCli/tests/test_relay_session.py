"""npdev_relay_session: the per-user store of one browser-AI relay conversation.

Run with: python -m unittest NPDevCli.tests.test_relay_session -v
"""
from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

import npdev_jsonschema  # noqa: E402
import npdev_relay_ingest as ri  # noqa: E402
import npdev_relay_session as rs  # noqa: E402

SCHEMA = json.loads((HERE.parents[1] / "schemas" / "ai" / "ai-relay-session.schema.json").read_text(encoding="utf-8"))
POLICY = ri.load_policy()


class SessionTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        patcher = mock.patch.dict("os.environ", {"NPDEV_AI_CONFIG": str(self.base / "ai.json")})
        patcher.start()
        self.addCleanup(patcher.stop)

    def make(self, **overrides):
        args = dict(intent="create", ask="A tiny CRM", target="chatgpt", language="Portuguese (Brazil)",
                    app_dir=None, phase="mvp")
        args.update(overrides)
        return rs.create(**args)

    def assertSchemaValid(self, session):
        self.assertEqual(npdev_jsonschema.validate(SCHEMA, session), [])
        try:
            import jsonschema  # dev machines only
        except ImportError:
            return
        self.assertEqual([e.message for e in jsonschema.Draft202012Validator(SCHEMA).iter_errors(session)], [])


class RootAndSchemaTests(SessionTestCase):
    def test_root_follows_the_ai_config_folder(self):
        self.assertEqual(rs.root(), self.base / "ai-relay")
        session = self.make()
        self.assertEqual(rs.session_dir(session["code"]), self.base / "ai-relay" / session["code"])
        self.assertEqual(rs.round_dir(session, 2), self.base / "ai-relay" / session["code"] / "round-2")
        self.assertEqual(rs.round_dir(session["code"], 1).name, "round-1")

    def test_schema_enums_match_the_constants(self):
        props = SCHEMA["properties"]
        self.assertEqual(tuple(props["intent"]["enum"]), rs.INTENTS)
        self.assertEqual(tuple(props["target"]["enum"]), rs.TARGETS)
        self.assertEqual(tuple(props["phase"]["enum"]), rs.PHASES)
        round_props = props["rounds"]["items"]["properties"]
        self.assertEqual(tuple(round_props["promptKind"]["enum"]), rs.PROMPT_KINDS)
        self.assertEqual(tuple(round_props["delivery"]["enum"]), rs.DELIVERIES)
        self.assertEqual(set(round_props["answerSource"]["enum"]) - {None}, set(rs.ANSWER_SOURCES))
        self.assertEqual(SCHEMA["properties"]["schemaVersion"]["const"], rs.SCHEMA_VERSION)

    def test_a_code_cannot_escape_the_root(self):
        for bad in ("..", "../x", "ab", "ABCDE", "AB/D", "I0O1", ""):
            with self.subTest(code=bad), self.assertRaises(rs.RelaySessionError):
                rs.session_dir(bad)

    def test_code_is_case_insensitive(self):
        session = self.make()
        self.assertEqual(rs.load(session["code"].lower())["code"], session["code"])


class CreateTests(SessionTestCase):
    def test_create_writes_a_valid_session(self):
        session = self.make(interview=True, knowledge_sha256="ab" * 32)
        self.assertSchemaValid(session)
        self.assertEqual((session["state"], session["rounds"], session["trail"]), ("waiting-answer", [], []))
        self.assertIsNone(session["appDir"])
        self.assertTrue(session["interview"])
        on_disk = json.loads((rs.session_dir(session["code"]) / "session.json").read_text(encoding="utf-8"))
        self.assertEqual(on_disk, session)
        self.assertEqual(rs.load(session["code"]), session)

    def test_create_validates_the_enums(self):
        for field, value in (("intent", "delete"), ("target", "bing"), ("phase", "f9")):
            with self.subTest(field=field), self.assertRaises(rs.RelaySessionError):
                self.make(**{field: value})
        self.assertFalse(rs.root().exists())

    def test_change_session_keeps_the_base_model(self):
        text = '{"concepts": []}\r\n'
        session = self.make(intent="change", app_dir=str(self.base / "app"), base_model_text=text)
        saved = (rs.session_dir(session["code"]) / "base-model.json").read_bytes()
        self.assertEqual(saved, text.encode("utf-8"))
        import hashlib
        self.assertEqual(session["baseModelSha256"], hashlib.sha256(saved).hexdigest())
        self.assertSchemaValid(session)

    def test_code_collisions_are_regenerated(self):
        first = self.make()
        codes = iter([first["code"], first["code"], "K7Q2"])
        with mock.patch.object(ri, "new_session_code", lambda: next(codes)):
            second = self.make()
        self.assertEqual(second["code"], "K7Q2")
        self.assertNotEqual(first["code"], second["code"])

    def test_no_free_code_raises(self):
        first = self.make()
        with mock.patch.object(ri, "new_session_code", lambda: first["code"]):
            with self.assertRaises(rs.RelaySessionError):
                self.make()

    def test_creation_is_logged(self):
        session = self.make()
        events = self.events(session["code"])
        self.assertEqual([e["kind"] for e in events], ["session-created"])
        self.assertIsNone(events[0]["round"])

    def events(self, code):
        text = (rs.session_dir(code) / "transcript.jsonl").read_text(encoding="utf-8")
        return [json.loads(line) for line in text.splitlines()]


def answer_for(session, round_number, *, files, outcome="READY", findings=None, validation=None):
    result = {"schemaVersion": ri.SCHEMA_VERSION, "protocol": ri.PROTOCOL, "phase": "mvp",
              "session": {"expected": session["code"], "expectedRound": round_number,
                          "found": session["code"], "foundRound": round_number},
              "outcome": outcome, "files": files, "findings": findings or [], "normalization": [], "prose": ""}
    if validation is not None:
        result["validation"] = validation
    return result


def file_entry(path, content, status="allowed"):
    return {"path": path, "status": status, "reason": None if status == "allowed" else "code", "kind": "json",
            "labelSource": "info-string", "fenced": True, "repairs": [], "bytes": len(content.encode("utf-8")),
            "sha256": "0" * 64, "content": content}


class RoundTests(SessionTestCase):
    def test_add_round_stores_the_prompt_and_waits(self):
        session = self.make()
        prompt = "NPDEV-PROMPT " + session["code"] + "-1\nolá\nNPDEV-PROMPT-END " + session["code"] + "-1\n"
        entry = rs.add_round(session, prompt, "round1", delivery="paste")
        self.assertEqual(entry["round"], 1)
        self.assertEqual(entry["promptBytes"], len(prompt.encode("utf-8")))
        stored = (rs.round_dir(session, 1) / "prompt.txt").read_bytes()
        self.assertEqual(stored, prompt.encode("utf-8"))      # no newline translation
        self.assertSchemaValid(rs.load(session["code"]))
        second = rs.add_round(session, "again", "same-chat")
        self.assertEqual(second["round"], 2)
        self.assertNotIn("delivery", second)
        self.assertEqual(len(rs.load(session["code"])["rounds"]), 2)

    def test_add_round_rejects_unknown_kinds_and_abandoned_sessions(self):
        session = self.make()
        with self.assertRaises(rs.RelaySessionError):
            rs.add_round(session, "x", "other")
        rs.abandon(session["code"])
        with self.assertRaises(rs.RelaySessionError):
            rs.add_round(rs.load(session["code"]), "x", "round1")

    def test_record_ready_answer_keeps_raw_result_and_files(self):
        session = self.make()
        rs.add_round(session, "p", "round1")
        files = [file_entry("model.json", '{"a": 1}'),
                 file_entry("web/app.js", "alert(1)", status="quarantine"),
                 {"path": "config.json", "status": "denied", "reason": "config-file-denied", "kind": "json",
                  "labelSource": None, "fenced": True, "repairs": [], "bytes": 2, "sha256": "0" * 64}]
        raw = "raw answer é\r\nline"
        result = answer_for(session, 1, files=files)
        entry = rs.record_answer(session, 1, raw, result, "paste")
        directory = rs.round_dir(session, 1)
        self.assertEqual((directory / "answer-raw.txt").read_bytes(), raw.encode("utf-8"))
        self.assertEqual(json.loads((directory / "result.json").read_text(encoding="utf-8")), result)
        self.assertEqual((directory / "files" / "model.json").read_text(encoding="utf-8"), '{"a": 1}')
        self.assertTrue((directory / "files" / "web" / "app.js").is_file())
        self.assertFalse((directory / "files" / "config.json").exists())
        self.assertEqual((entry["outcome"], entry["answerSource"], entry["errorSignature"]), ("READY", "paste", None))
        reloaded = rs.load(session["code"])
        self.assertEqual((reloaded["state"], reloaded["trail"]), ("ready-to-apply", []))
        self.assertSchemaValid(reloaded)

    def test_failed_answer_goes_on_the_trail(self):
        session = self.make()
        rs.add_round(session, "p", "round1")
        result = answer_for(session, 1, files=[], outcome="NEEDS_FIX",
                            validation={"diagnostics": [{"severity": "error", "code": "unknown_ref", "path": "a.b"}]})
        entry = rs.record_answer(session, 1, "raw", result, "clipboard-watch")
        self.assertRegex(entry["errorSignature"], "^[0-9a-f]{12}$")
        reloaded = rs.load(session["code"])
        self.assertEqual(reloaded["state"], "answer-received")
        self.assertEqual(reloaded["trail"], ["NEEDS_FIX:" + entry["errorSignature"]])
        self.assertSchemaValid(reloaded)

    def test_non_answers_are_logged_and_change_nothing(self):
        session = self.make()
        rs.add_round(session, "p", "round1")
        for outcome in sorted(rs.NOT_AN_ANSWER):
            with self.subTest(outcome=outcome):
                rs.record_answer(session, 1, "echo", answer_for(session, 1, files=[], outcome=outcome), "paste")
        reloaded = rs.load(session["code"])
        self.assertEqual(reloaded["state"], "waiting-answer")
        self.assertNotIn("outcome", reloaded["rounds"][0])
        self.assertFalse((rs.round_dir(session, 1) / "answer-raw.txt").exists())
        kinds = [json.loads(line)["kind"] for line in
                 (rs.session_dir(session["code"]) / "transcript.jsonl").read_text(encoding="utf-8").splitlines()]
        self.assertEqual(kinds.count("answer-ignored"), len(rs.NOT_AN_ANSWER))

    def test_unsafe_file_paths_are_never_written(self):
        session = self.make()
        rs.add_round(session, "p", "round1")
        traversal = "..\\..\\escaped.json"
        files = [file_entry("../escaped.json", "{}"), file_entry(traversal, "{}"), file_entry("a/../../b.json", "{}"),
                 file_entry("/abs.json", "{}"), file_entry("sub/ok.json", "{}")]
        rs.record_answer(session, 1, "raw", answer_for(session, 1, files=files), "drop")
        self.assertFalse((rs.session_dir(session["code"]) / "escaped.json").exists())
        self.assertFalse((self.base / "ai-relay" / "escaped.json").exists())
        self.assertEqual(sorted(p.name for p in (rs.round_dir(session, 1) / "files").rglob("*") if p.is_file()),
                         ["ok.json"])

    def test_record_answer_checks_round_and_source(self):
        session = self.make()
        rs.add_round(session, "p", "round1")
        with self.assertRaises(rs.RelaySessionError):
            rs.record_answer(session, 2, "x", answer_for(session, 2, files=[]), "paste")
        with self.assertRaises(rs.RelaySessionError):
            rs.record_answer(session, 1, "x", answer_for(session, 1, files=[]), "telepathy")

    def test_real_ingest_output_round_trips(self):
        session = self.make()
        rs.add_round(session, "p", "round1")
        code = session["code"]
        raw = (f"NPDEV-BEGIN {code}-1\nNPDEV-FILE model.json\n```json\n{{\"concepts\": []}}\n```\nNPDEV-END {code}-1 files=1\n")
        result = ri.ingest_text(raw, session=code, expected_round=1, policy=POLICY)
        rs.record_answer(session, 1, raw, result, "paste")
        stored = (rs.round_dir(session, 1) / "files" / "model.json").read_text(encoding="utf-8")
        self.assertEqual(json.loads(stored), {"concepts": []})
        self.assertEqual(rs.load(code)["rounds"][0]["outcome"], result["outcome"])


class SignatureTests(unittest.TestCase):
    def base(self, **extra):
        result = {"outcome": "NEEDS_FIX", "findings": [], "files": []}
        result.update(extra)
        return result

    def test_ready_has_no_signature(self):
        self.assertIsNone(rs.error_signature({"outcome": "READY", "findings": []}))

    def test_order_wording_and_noise_do_not_matter(self):
        a = self.base(findings=[{"code": "x", "severity": "error"}, {"code": "y", "severity": "warning"}],
                      validation={"diagnostics": [{"severity": "error", "code": "c1", "path": "p1", "message": "one"},
                                                  {"severity": "error", "code": "c2", "path": "p2"}]})
        b = self.base(findings=[{"code": "y", "severity": "warning"}, {"code": "x", "severity": "error"},
                                {"code": "n", "severity": "info"}],
                      validation={"diagnostics": [{"severity": "error", "code": "c2", "path": "p2"},
                                                  {"severity": "error", "code": "c1", "path": "p1", "message": "uno"},
                                                  {"severity": "warning", "code": "w", "path": "q"}]})
        self.assertEqual(rs.error_signature(a), rs.error_signature(b))

    def test_a_different_failure_has_a_different_signature(self):
        one = self.base(validation={"diagnostics": [{"severity": "error", "code": "c1", "path": "p1"}]})
        other_path = self.base(validation={"diagnostics": [{"severity": "error", "code": "c1", "path": "p2"}]})
        other_outcome = dict(one, outcome="TRUNCATED")
        signatures = {rs.error_signature(x) for x in (one, other_path, other_outcome, self.base())}
        self.assertEqual(len(signatures), 4)


class ListAndAbandonTests(SessionTestCase):
    def test_list_open_hides_closed_and_damaged_sessions(self):
        self.assertEqual(rs.list_open(), [])
        keep = self.make()
        gone = self.make()
        done = self.make()
        rs.abandon(gone["code"])
        done["state"] = "done"
        rs.save(done)
        damaged = rs.root() / "ZZZZ"
        damaged.mkdir()
        (damaged / "session.json").write_text("{not json", encoding="utf-8")
        (rs.root() / "not-a-code").mkdir()
        self.assertEqual([s["code"] for s in rs.list_open()], [keep["code"]])

    def test_abandon_keeps_the_files_and_is_idempotent(self):
        session = self.make()
        rs.add_round(session, "p", "round1")
        first = rs.abandon(session["code"])
        self.assertEqual(first["state"], "abandoned")
        self.assertTrue((rs.round_dir(session, 1) / "prompt.txt").is_file())
        rs.abandon(session["code"])
        kinds = [json.loads(line)["kind"] for line in
                 (rs.session_dir(session["code"]) / "transcript.jsonl").read_text(encoding="utf-8").splitlines()]
        self.assertEqual(kinds.count("abandoned"), 1)

    def test_load_unknown_code(self):
        with self.assertRaises(rs.SessionNotFound):
            rs.load("K7Q2")

    def test_damaged_session_is_reported_plainly(self):
        session = self.make()
        (rs.session_dir(session["code"]) / "session.json").write_text("{nope", encoding="utf-8")
        with self.assertRaises(rs.RelaySessionError):
            rs.load(session["code"])

    def test_writes_leave_no_temporary_files(self):
        session = self.make()
        rs.add_round(session, "p", "round1")
        leftovers = [p for p in rs.root().rglob("*.tmp")]
        self.assertEqual(leftovers, [])


if __name__ == "__main__":
    unittest.main()
