from __future__ import annotations

import os
import sys
import tempfile
import unittest
from pathlib import Path

_REPO = Path(__file__).resolve().parents[2]
_SCRIPTS_AI = _REPO / "scripts" / "ai"
if str(_SCRIPTS_AI) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS_AI))

import build_knowledge  # noqa: E402
import build_rag_index  # noqa: E402
import promote_candidates  # noqa: E402
from npdev_ai_common import cards_dir, knowledge_dir  # noqa: E402


class KnowledgePathsTest(unittest.TestCase):
    def test_knowledge_dir_is_the_committed_npdevmcp_directory(self):
        self.assertTrue((knowledge_dir(_REPO) / "platform-status.json").is_file())
        self.assertTrue(cards_dir(_REPO).is_dir())

    def test_load_cards_reads_the_real_cards(self):
        cards, errors = build_knowledge.load_cards(_REPO)
        self.assertEqual([], errors)
        self.assertGreaterEqual(len(cards), 10, "the 16 committed cards must load")

    def test_zero_cards_is_an_error_not_a_vacuous_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            cards, errors = build_knowledge.load_cards(Path(tmp))
            self.assertEqual([], cards)
            self.assertTrue(errors, "a missing/empty cards dir must fail --validate-only")

    def test_rag_index_emits_card_chunks(self):
        chunks = build_rag_index.chunk_cards(_REPO)
        self.assertTrue(any(c["objectType"] == "knowledge-card" for c in chunks))
        self.assertTrue(any("tenant-default-403" in c["id"] for c in chunks))

    def test_promote_candidates_sees_existing_error_fix_signatures(self):
        self.assertTrue(promote_candidates._existing_card_signatures(_REPO))

    def test_no_reader_joins_the_old_root_knowledge_path(self):
        for name in ("build_knowledge.py", "build_rag_index.py", "promote_candidates.py"):
            text = (_SCRIPTS_AI / name).read_text(encoding="utf-8")
            self.assertNotIn('root / "knowledge"', text, name)
            self.assertNotIn("root / 'knowledge'", text, name)


if __name__ == "__main__":
    unittest.main()
