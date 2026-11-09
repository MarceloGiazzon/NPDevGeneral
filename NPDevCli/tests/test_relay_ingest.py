"""Proves npdev_relay_ingest against the case corpus, plus unit tests the corpus cannot express.

Run with: python -m unittest NPDevCli.tests.test_relay_ingest -v
Regenerate the corpus after editing it: python NPDevCli/tests/fixtures/relay/build_cases.py
"""
from __future__ import annotations

import json
import random
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

import npdev_relay_ingest as ri  # noqa: E402

CASES = json.loads((HERE / "fixtures" / "relay" / "relay-ingest-cases.json").read_text(encoding="utf-8"))["cases"]
POLICY = ri.load_policy()


def run_case(case: dict) -> dict:
    return ri.ingest_text(case["answer"], session=case.get("session", "K7Q2"),
                          expected_round=case.get("round", 1), policy=POLICY, phase=case.get("phase", "mvp"))


class CorpusTests(unittest.TestCase):
    def test_every_case(self):
        for case in CASES:
            with self.subTest(case=case["name"]):
                result = run_case(case)
                expect = case["expect"]
                codes = [f["code"] for f in result["findings"]]
                self.assertEqual(result["outcome"], expect["outcome"], f"findings={codes}")
                if "files" in expect:
                    got = sorted([f["path"], f["status"]] for f in result["files"])
                    self.assertEqual(got, sorted(expect["files"]))
                for code in expect.get("findings", []):
                    self.assertIn(code, codes)
                for code in expect.get("notFindings", []):
                    self.assertNotIn(code, codes)

    def test_corpus_covers_every_outcome(self):
        outcomes = {c["expect"]["outcome"] for c in CASES}
        every = {ri.PROMPT_ECHO, ri.WRONG_SESSION, ri.STALE_ROUND, ri.LIMIT_REACHED, ri.FILE_LINK_ONLY,
                 ri.QUESTIONS, ri.NO_FILES, ri.TRUNCATED, ri.ELIDED, ri.BAD_JSON, ri.PARTIAL, ri.PARSED}
        self.assertEqual(every - outcomes, set())


class SchemaTests(unittest.TestCase):
    def test_every_result_matches_the_result_schema(self):
        try:
            import jsonschema  # dev machines only; the Manager's private Python has no third-party packages
        except ImportError:
            self.skipTest("jsonschema not installed")
        schema_path = HERE.parents[1] / "schemas" / "ai" / "ai-relay-ingest-result.schema.json"
        self.assertTrue(schema_path.is_file(), "schemas/ai/ai-relay-ingest-result.schema.json is missing")
        schema =json.loads(schema_path.read_text(encoding="utf-8"))
        validator = jsonschema.Draft202012Validator(schema)
        for case in CASES:
            with self.subTest(case=case["name"]):
                errors = [e.message for e in validator.iter_errors(run_case(case))]
                self.assertEqual(errors, [])


class PathTests(unittest.TestCase):
    def test_absolute_and_drive_paths_are_denied(self):
        drive = "C" + ":" + "/Windows/model.json"   # built at run time: no drive literal in the file
        for raw in (drive, "/etc/model.json", "\\\\server\\share\\model.json"):
            with self.subTest(raw=raw):
                _, status, reason = ri.check_path(raw, POLICY, "f4")
                self.assertEqual((status, reason), ("denied", "absolute-path"))

    def test_backslashes_and_prefixes_normalise(self):
        self.assertEqual(ri.check_path("definition\\model.json", POLICY, "mvp")[:2], ("model.json", "allowed"))
        self.assertEqual(ri.check_path("./fragments/a.json", POLICY, "f4")[:2], ("fragments/a.json", "allowed"))

    def test_protected_dirs_denied_in_every_phase(self):
        for phase in ("mvp", "f4"):
            for raw in ("secrets/db.json", "data/x.json", "logs/a.json"):
                self.assertEqual(ri.check_path(raw, POLICY, phase)[1], "denied")


class SessionCodeTests(unittest.TestCase):
    def test_codes_use_the_unambiguous_alphabet(self):
        for _ in range(200):
            code = ri.new_session_code()
            self.assertEqual(len(code), 4)
            self.assertTrue(set(code) <= set(ri.SESSION_ALPHABET))


class MutationTests(unittest.TestCase):
    """Cheap fuzz (plan section 6): a truncated answer must NEVER come out PARSED."""

    def test_random_truncations_never_parse(self):
        ready = next(c for c in CASES if c["name"] == "ready-full-protocol")["answer"]
        start = ready.index("NPDEV-FILE model.json")
        end = ready.index("NPDEV-FILE npdev-submission.json")
        rng = random.Random(7)
        for _ in range(300):
            cut = rng.randint(start + 30, end - 8)  # cut inside the model block
            result = ri.ingest_text(ready[:cut], session="K7Q2", expected_round=1, policy=POLICY)
            self.assertEqual(result["outcome"], ri.TRUNCATED, f"cut at {cut}")

    def test_cut_anywhere_is_truncated_or_flags_the_missing_end(self):
        """A cut that lands exactly BETWEEN two complete files cannot be told apart from an AI that
        forgot the END line. Contract: such a result may be PARSED, but it MUST carry
        end-marker-missing, and the CLI stage after ingest (npdev_relay.py) must then require the
        files the intent needs (the manifest for a change) before READY."""
        ready = next(c for c in CASES if c["name"] == "ready-full-protocol")["answer"]
        start = ready.index("NPDEV-FILE model.json")
        stop = ready.index("NPDEV-END")
        for cut in range(start, stop):
            result = ri.ingest_text(ready[:cut], session="K7Q2", expected_round=1, policy=POLICY)
            codes = [f["code"] for f in result["findings"]]
            if result["outcome"] != ri.TRUNCATED:
                self.assertEqual(result["outcome"], ri.PARSED, f"cut at {cut}: {codes}")
                self.assertIn("end-marker-missing", codes, f"cut at {cut}")

    def test_parsed_files_carry_content_and_hash(self):
        result = run_case(next(c for c in CASES if c["name"] == "ready-full-protocol"))
        for f in result["files"]:
            self.assertTrue(f["content"].endswith("\n"))
            self.assertEqual(len(f["sha256"]), 64)


if __name__ == "__main__":
    unittest.main()
