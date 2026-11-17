"""FORM P7-T2: npdev_pack_search / npdev_pack_card read the generated AI catalog (build_root()/npr-catalog/catalog).

Same convention as test_server_tools.py: NPDEV_BUILD_ROOT points at a temp dir holding a hand-built catalog in the real
shape scripts/pack-repo/build-ai-catalog.py writes, so the suite needs no build step. Run with:
    python -m unittest discover -s NPDevMcp/tests -p "test_pack_catalog_tools.py" -v
"""

from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import server  # noqa: E402

L0 = "\n".join([
    "leads|module|sales|draft|Capture prospects and know where they came from|requires:contacts",
    "quotes|module|sales|certified|Offer prices to a prospect and track the answer|requires:leads,products",
    "pix-key|data-type|finance|verified|Validate Pix (instant payment) keys|requires:",
    "payables|module|finance|verified|Schedule what the company has to pay|requires:contacts",
]) + "\n"


def _payload(result: dict) -> dict:
    return json.loads(result["content"][0]["text"])


class PackCatalogTools(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.root = Path(self._tmp.name)
        patcher = patch.dict(os.environ, {"NPDEV_BUILD_ROOT": str(self.root)})
        patcher.start()
        self.addCleanup(patcher.stop)

    def build_catalog(self):
        catalog = self.root / "npr-catalog" / "catalog"
        (catalog / "cards").mkdir(parents=True)
        (catalog / "l0.txt").write_text(L0, encoding="utf-8")
        (catalog / "cards" / "leads.json").write_text(json.dumps({"id": "leads", "version": "1.0.1", "trust": "draft"}), encoding="utf-8")

    def test_tools_are_registered_with_schemas(self):
        names = {t["name"]: t for t in server.TOOLS}
        self.assertEqual(names["npdev_pack_search"]["inputSchema"]["required"], ["need"])
        self.assertEqual(names["npdev_pack_card"]["inputSchema"]["required"], ["id"])

    def test_search_before_the_catalog_is_built_names_the_command_to_run(self):
        result = server.tool_pack_search({"need": "leads"})
        self.assertTrue(result.get("isError"))
        self.assertIn("build-ai-catalog.py", result["content"][0]["text"])

    def test_search_needs_a_need(self):
        self.build_catalog()
        self.assertTrue(server.tool_pack_search({"need": "  "}).get("isError"))

    def test_search_ranks_by_the_need_words_and_returns_l0_lines(self):
        self.build_catalog()
        found = _payload(server.tool_pack_search({"need": "track the prospects and the quote answer"}))
        self.assertEqual(found["matches"][0].split("|")[0], "quotes")
        self.assertTrue(all(m.count("|") == 5 for m in found["matches"]))
        self.assertIn("npdev_pack_card", found["next"])

    def test_search_filters_by_area_and_respects_limit_and_prefers_trust_on_ties(self):
        self.build_catalog()
        finance = _payload(server.tool_pack_search({"need": "pay money", "area": "finance"}))["matches"]
        self.assertTrue(finance and all("|finance|" in m for m in finance))
        both = _payload(server.tool_pack_search({"need": "contacts", "limit": 1}))["matches"]
        self.assertEqual(len(both), 1)
        tie = _payload(server.tool_pack_search({"need": "requires contacts"}))["matches"]
        self.assertEqual(tie[0].split("|")[0], "payables")  # equal score, verified beats draft

    def test_search_with_no_match_is_an_empty_list_not_an_error(self):
        self.build_catalog()
        result = server.tool_pack_search({"need": "zeppelin"})
        self.assertFalse(result.get("isError"))
        self.assertEqual(_payload(result)["matches"], [])

    def test_card_returns_the_generated_card_verbatim(self):
        self.build_catalog()
        self.assertEqual(_payload(server.tool_pack_card({"id": "leads"}))["version"], "1.0.1")

    def test_card_refuses_path_tricks_and_unknown_ids_with_a_suggestion(self):
        self.build_catalog()
        for bad in ("../secrets", "a/b", "", "Leads", "leads.json"):
            self.assertTrue(server.tool_pack_card({"id": bad}).get("isError"), bad)
        missing = server.tool_pack_card({"id": "lead"})
        self.assertTrue(missing.get("isError"))
        self.assertIn("did you mean: leads", missing["content"][0]["text"])

    def test_card_before_the_catalog_is_built_is_actionable(self):
        result = server.tool_pack_card({"id": "leads"})
        self.assertTrue(result.get("isError"))
        self.assertIn("build-ai-catalog.py", result["content"][0]["text"])


if __name__ == "__main__":
    unittest.main()
