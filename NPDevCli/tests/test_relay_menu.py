"""The menu tree in a browser AI's answer (REG-323 F4): `menu.json`, checked with the rules the generator's menu emitter and the menu
seeder apply, MERGED (never replaced) into the app's own menu.json, written all-or-nothing with the model and undone with it.

Why it matters: a PAGE node whose page was not sent is a dead menu entry, a kind the seeder does not know is a menu row nobody can
open, and a menu.json that holds only the NEW node must not delete the app's other entries. The AI is told each of these in plain
words before anything is written. The pages half of the same module is in test_relay_pages.py; seeds are in test_relay_seeds.py.

Part 1 the pure check. Part 2 the merge and the change test. Part 3 whole answers through ingest -> apply -> undo with the Java
validator and the authoring gate stubbed.

Run with: python -m unittest NPDevCli.tests.test_relay_menu -v
"""
from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE))

import npdev_relay_apply as apply_mod  # noqa: E402
import npdev_relay_engine as engine  # noqa: E402
import npdev_relay_pages as pages  # noqa: E402
import npdev_relay_session as sessions  # noqa: E402
from test_relay_apply import manifest  # noqa: E402
from test_relay_files import SplitAnswerCase, snapshot  # noqa: E402
from test_relay_pages import PAGE, codes, lines, mixed, write_app_pages  # noqa: E402
from test_relay_themes import seed  # noqa: E402

POLICY = pages.load_policy()
ORDERS = {"label": "Orders", "kind": "PAGE", "target": "orders.html"}
SHOP = {"label": "Shop", "children": [{"label": "Customers", "target": "Customer"}, ORDERS]}


def check(menu, *web_pages, model=None, app_dir=None) -> dict:
    files = {"menu.json": menu if isinstance(menu, str) else json.dumps(menu)}
    files.update({f"web/{name}": PAGE for name in web_pages})
    return pages.check(files, seed() if model is None else model, app_dir)


def write_app_menu(app: Path, menu, *page_names: str) -> None:
    (app / "web").mkdir(exist_ok=True)
    for name in page_names:
        (app / "web" / name).write_text(PAGE, encoding="utf-8")
    (app / "menu.json").write_text(menu if isinstance(menu, str) else json.dumps(menu, indent=2) + "\n", encoding="utf-8")


class CheckTests(unittest.TestCase):
    def test_a_menu_whose_pages_are_sent_is_clean_and_menu_json_counts_as_a_page_file(self):
        checked = check([ORDERS], "orders.html")
        self.assertEqual(checked["errors"], [])
        self.assertEqual(codes(checked), ["page-added"])   # linked by the menu: no page-not-in-menu
        self.assertTrue(pages.is_page_file("menu.json"))
        self.assertFalse(pages.is_page_file("menus.json"))

    def test_a_node_with_children_is_a_group_and_one_without_a_kind_opens_a_concept_or_panel(self):
        checked = check(SHOP, "orders.html")
        self.assertEqual(checked["errors"], [])
        self.assertEqual(pages._effective_kind(SHOP), "GROUP")
        self.assertEqual(pages._effective_kind({"label": "x", "target": "Customer"}), "BUSINESS")

    def test_the_ways_an_ai_writes_a_node_are_understood_and_said(self):
        roots, errors, findings = pages.parse_menu(json.dumps({"menu": [{"label": " Orders ", "kind": "page", "target": "web/orders.html"}]}))
        self.assertEqual(errors, [])
        self.assertEqual(roots, [{"label": "Orders", "kind": "PAGE", "target": "orders.html"}])
        self.assertEqual(sorted(f["detail"]["what"] for f in findings if f["code"] == "menu-normalized"), ["kind", "target"])
        single, errs, _ = pages.parse_menu(json.dumps(ORDERS))   # one object is one root, as the emitter reads it
        self.assertEqual((single, errs), ([ORDERS], []))

    def test_a_kind_the_menu_does_not_have_is_sent_back_and_internal_belongs_to_the_platform(self):
        self.assertIn('"widget" is not a menu kind - use one of GROUP, BUSINESS, PAGE', lines(check([{"label": "x", "kind": "widget"}])))
        internal = lines(check([{"label": "x", "kind": "internal", "target": "npdev:x"}]))
        self.assertIn("INTERNAL belongs to the platform", internal)

    def test_a_node_needs_a_label_and_a_page_or_concept_to_open(self):
        self.assertIn('needs a "label"', lines(check([{"kind": "PAGE", "target": "orders.html"}], "orders.html")))
        self.assertIn('needs a "label"', lines(check([{"label": "  ", "target": "Customer"}])))
        self.assertIn('a PAGE node needs a "target"', lines(check([{"label": "Orders", "kind": "PAGE"}])))
        business = lines(check([{"label": "Customers"}]))
        self.assertIn('a BUSINESS node needs a "target"', business)
        self.assertIn("group header", business)   # the way out is said
        self.assertIn("longer than", lines(check([{"label": "x" * (POLICY["maxLabelChars"] + 1), "target": "Customer"}])))

    def test_a_page_target_must_be_a_plain_relative_html_path(self):
        drive_path = "c" + ":/x.html"   # built at run time: the portability scan rejects a drive-letter path in source
        for bad in ("../x.html", "x.txt", "/abs.html", "a//b.html", drive_path):
            self.assertIn("must be a plain relative path of an html page", lines(check([{"label": "x", "kind": "PAGE", "target": bad}])), bad)

    def test_a_page_node_for_a_page_that_was_not_sent_is_sent_back_and_one_already_in_the_app_is_fine(self):
        self.assertIn('links the page "ghost.html" but there is no web/ghost.html', lines(check([{**ORDERS, "target": "ghost.html"}])))
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "web").mkdir()
            (Path(tmp) / "web" / "orders.html").write_text(PAGE, encoding="utf-8")
            self.assertEqual(check([ORDERS], app_dir=tmp)["errors"], [])

    def test_a_key_the_emitter_ignores_is_a_warning_naming_it(self):
        checked = check([{"label": "Orders", "kind": "PAGE", "target": "orders.html", "url": "orders.html", "icon": "x"}], "orders.html")
        self.assertEqual(checked["errors"], [])
        ignored = next(f for f in checked["findings"] if f["code"] == "menu-key-ignored")
        self.assertEqual(ignored["detail"]["keys"], ["icon", "url"])
        self.assertIn("menu-key-ignored", codes(check([{"label": "Shop", "items": [{"label": "x", "target": "Customer"}]}])))

    def test_children_must_be_a_list_and_bad_ones_are_named_by_position(self):
        self.assertIn('"children" must be a list', lines(check([{"label": "Shop", "children": {"label": "x"}}])))
        nested = lines(check([{"label": "Shop", "children": [{"label": "ok", "target": "Customer"}, {"label": "bad"}]}]))
        self.assertIn("menu.json entry 0 > child 1", nested)

    def test_a_business_target_that_is_neither_a_concept_nor_a_panel_is_a_warning(self):
        self.assertEqual(codes(check([{"label": "Clients", "target": "Client"}])), ["menu-target-unknown"])
        self.assertEqual(codes(check([{"label": "Customers", "target": "Customer"}])), [])
        panel = {**seed(), "panels": [{"name": "Dashboard"}]}
        self.assertEqual(codes(check([{"label": "Home", "target": "Dashboard"}], model=panel)), [])
        for hidden in ({"packs": [{"from": "x"}]}, {"fragments": [{"$ref": "fragments/a.json"}]}, {"autoPanels": [{"concept": "Customer"}]}):
            self.assertEqual(codes(check([{"label": "Clients", "target": "Client"}], model={**seed(), **hidden})), [], hidden)

    def test_a_role_the_model_does_not_declare_is_a_warning_only_when_the_model_declares_roles(self):
        node = [{"label": "Customers", "target": "Customer", "requiredRole": "boss"}]
        self.assertEqual(codes(check(node)), [])
        self.assertEqual(codes(check(node, model={**seed(), "roles": [{"name": "clerk"}]})), ["menu-role-unknown"])
        self.assertEqual(codes(check(node, model={**seed(), "roles": [{"name": "boss"}]})), [])
        self.assertIn("requiredRole must be a role name", lines(check([{"label": "x", "target": "Customer", "requiredRole": 3}])))
        for bad in (-1, True, "1", 1.5):
            self.assertIn("ordinal must be a whole number", lines(check([{"label": "x", "target": "Customer", "ordinal": bad}])), bad)

    def test_not_json_and_not_a_menu_are_refused_in_words(self):
        self.assertIn("not valid JSON", lines(check("{broken")))
        self.assertIn("must be a menu node or a list of them", lines(check('"orders"')))
        self.assertIn("is not an object", lines(check(["orders.html"])))

    def test_a_menu_that_is_too_deep_or_too_big_is_refused_and_the_edge_is_allowed(self):
        def nest(levels):
            node = {"label": "leaf", "target": "Customer"}
            for index in range(levels - 1):
                node = {"label": f"g{index}", "children": [node]}
            return node

        self.assertEqual(check([nest(POLICY["menu"]["maxDepth"])])["errors"], [])
        self.assertIn("nested more than", lines(check([nest(POLICY["menu"]["maxDepth"] + 1)])))
        wide = [{"label": f"n{i}", "target": "Customer"} for i in range(POLICY["menu"]["maxNodes"])]
        self.assertEqual(check(wide)["errors"], [])
        self.assertIn("more than 200 nodes", lines(check(wide + [{"label": "one more", "target": "Customer"}])))

    def test_a_page_the_apps_own_menu_already_links_is_not_reported_as_unlinked(self):
        with tempfile.TemporaryDirectory() as tmp:
            write_app_menu(Path(tmp), [{"label": "Group", "children": [ORDERS]}])   # links orders.html; the page is not there yet
            linked = pages.check({"web/orders.html": PAGE}, seed(), tmp)
            self.assertEqual(codes(linked), ["page-added"])
            fresh = pages.check({"web/other.html": PAGE}, seed(), tmp)
            self.assertIn("page-not-in-menu", codes(fresh))

    def test_an_existing_menu_that_is_not_a_menu_is_sent_back_with_the_way_out(self):
        with tempfile.TemporaryDirectory() as tmp:
            write_app_menu(Path(tmp), "{broken")
            self.assertIn("cannot be added to", lines(check([ORDERS], "orders.html", app_dir=tmp)))
            write_app_menu(Path(tmp), '"text"')
            self.assertIn("cannot be added to", lines(check([ORDERS], "orders.html", app_dir=tmp)))


class MergeTests(unittest.TestCase):
    def test_a_new_node_goes_last_and_a_node_already_there_is_updated_in_place_keeping_what_the_ai_did_not_send(self):
        current = [{"label": "Home", "kind": "PAGE", "target": "home.html", "ordinal": 5, "requiredRole": "clerk"},
                   {"label": "Reports", "target": "Report", "extra": "kept"}]
        new = [{"label": "Report list", "kind": "BUSINESS", "target": "Report"}, ORDERS, {"label": "Home", "kind": "PAGE", "target": "home.html"}]
        merged = pages.merge_menu(current, new)
        self.assertEqual([n["label"] for n in merged], ["Home", "Report list", "Orders"])
        self.assertEqual(merged[0], current[0])                                   # nothing new was said about Home
        self.assertEqual(merged[1], {"label": "Report list", "target": "Report", "extra": "kept", "kind": "BUSINESS"})
        self.assertEqual(current[1]["label"], "Reports")                         # the input is not changed

    def test_a_group_is_found_by_its_label_and_its_children_are_merged_the_same_way(self):
        current = [{"label": "Shop", "children": [{"label": "Customers", "target": "Customer", "ordinal": 1}]}]
        new = [{"label": "Shop", "children": [{"label": "Orders", "target": "Order"}, {"label": "Clients", "target": "Customer"}]}]
        merged = pages.merge_menu(current, new)
        self.assertEqual(len(merged), 1)
        self.assertEqual(merged[0]["children"], [{"label": "Clients", "target": "Customer", "ordinal": 1}, {"label": "Orders", "target": "Order"}])

    def test_a_group_the_ai_names_without_children_keeps_the_apps_children(self):
        current = [{"label": "Shop", "children": [{"label": "Customers", "target": "Customer"}]}]
        merged = pages.merge_menu(current, [{"label": "Shop", "ordinal": 9, "kind": "GROUP"}])
        self.assertEqual(merged, [{"label": "Shop", "children": [{"label": "Customers", "target": "Customer"}], "ordinal": 9, "kind": "GROUP"}])

    def test_a_page_is_the_same_page_whether_the_target_says_web_or_not(self):
        current = [{"label": "Orders", "kind": "PAGE", "target": "web/orders.html"}]
        merged = pages.merge_menu(current, [{"label": "Orders (new)", "kind": "PAGE", "target": "orders.html"}])
        self.assertEqual(len(merged), 1)
        self.assertEqual(merged[0]["label"], "Orders (new)")

    def test_merged_text_reads_the_apps_file_and_an_unreadable_one_is_a_sentence_not_a_loss(self):
        with tempfile.TemporaryDirectory() as tmp:
            write_app_menu(Path(tmp), ORDERS)   # a single object: the emitter reads it as one root
            text = pages.merged_menu_text(tmp, json.dumps([{"label": "Customers", "target": "Customer"}]))
            self.assertEqual([n["label"] for n in json.loads(text)], ["Orders", "Customers"])   # written back as a list
            write_app_menu(Path(tmp), "{broken")
            with self.assertRaisesRegex(ValueError, "cannot be added to"):
                pages.merged_menu_text(tmp, json.dumps([ORDERS]))
            with self.assertRaisesRegex(ValueError, 'needs a "label"'):
                pages.merged_menu_text(tmp, json.dumps([{"target": "Customer"}]))

    def test_prepare_swaps_in_both_merges_and_leaves_a_file_out_when_nothing_would_change(self):
        with tempfile.TemporaryDirectory() as tmp:
            app = Path(tmp)
            write_app_pages(app, [{"path": "a.html", "label": "A"}], "a.html")
            write_app_menu(app, [ORDERS], "orders.html")
            same = {"pages.json": json.dumps([{"path": "a.html", "label": "A"}]), "menu.json": json.dumps([ORDERS]), "web/x.html": PAGE}
            self.assertEqual(pages.prepare(app, same), {"web/x.html": PAGE})
            more = {"pages.json": json.dumps([{"path": "b.html", "label": "B"}]), "menu.json": json.dumps([{"label": "Customers", "target": "Customer"}])}
            out = pages.prepare(app, more)
            self.assertEqual([e["path"] for e in json.loads(out["pages.json"])], ["a.html", "b.html"])
            self.assertEqual([n["label"] for n in json.loads(out["menu.json"])], ["Orders", "Customers"])
            self.assertEqual(pages.prepare(app, {"web/x.html": PAGE}), {"web/x.html": PAGE})


class ChangedTests(unittest.TestCase):
    def test_a_new_node_counts_and_the_same_menu_in_other_clothes_does_not(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(pages.changed(tmp, {"menu.json": json.dumps([ORDERS])}), ["menu.json"])
            write_app_menu(Path(tmp), [ORDERS], "orders.html")
            same = {"menu.json": json.dumps({"menu": [{"label": "Orders", "kind": "page", "target": "web/orders.html"}]})}
            self.assertEqual(pages.changed(tmp, same), [])
            self.assertEqual(pages.changed(tmp, {"menu.json": json.dumps([{**ORDERS, "ordinal": 3}])}), ["menu.json"])
            self.assertEqual(pages.changed(tmp, {"menu.json": "{broken"}), [])   # nothing parses: the check says what is wrong


class MenuAnswerTests(SplitAnswerCase):
    """Whole answers with phase f4: ingest -> apply -> undo."""

    def ingest(self, app: Path, menu, texts: dict | None = None, json_files: dict | None = None) -> tuple:
        code = self.start(app, ask="Add an orders page to the menu")
        named = {"model.json": seed(), "npdev-submission.json": manifest(), "menu.json": menu, **(json_files or {})}
        return code, engine.ingest(code, mixed(code, 1, named, texts), source="paste")

    def test_a_page_and_its_menu_node_are_applied_together_and_undone_together(self):
        app = self.make_app()
        before = snapshot(app)
        code, result = self.ingest(app, [SHOP], {"web/orders.html": PAGE})
        self.assertEqual(result["outcome"], "READY", (result["findings"], result.get("errorLines")))
        self.assertEqual(snapshot(app), before)
        done = apply_mod.apply_round(sessions.load(code), None, [])
        self.assertTrue(done["ok"], done)
        self.assertEqual(done["files"], ["menu.json", "web/orders.html", "model.json"])
        self.assertEqual(json.loads((app / "menu.json").read_text(encoding="utf-8")), [SHOP])
        undone = apply_mod.undo(sessions.load(code))
        self.assertTrue(undone["ok"], undone)
        self.assertEqual(snapshot(app), before)
        self.assertFalse((app / "web").exists())

    def test_a_menu_alone_leaves_model_json_identical_and_is_still_a_change(self):
        app = self.make_app()
        code, result = self.ingest(app, [{"label": "Customers", "target": "Customer"}])
        self.assertEqual(result["outcome"], "READY", (result["findings"], result.get("errorLines")))
        self.assertTrue(apply_mod.apply_round(sessions.load(code), None, [])["ok"])
        self.assertTrue((app / "menu.json").is_file())

    def test_sending_only_the_new_node_keeps_the_apps_other_nodes(self):
        app = self.make_app()
        existing = [{"label": "Shop", "children": [{"label": "Customers", "target": "Customer"}]}]
        write_app_menu(app, existing)
        before = snapshot(app)
        code, result = self.ingest(app, [{"label": "Shop", "children": [{"label": "Orders", "target": "Order"}]}])
        self.assertEqual(result["outcome"], "READY", (result["findings"], result.get("errorLines")))
        self.assertTrue(apply_mod.apply_round(sessions.load(code), None, [])["ok"])
        written = json.loads((app / "menu.json").read_text(encoding="utf-8"))
        self.assertEqual([c["label"] for c in written[0]["children"]], ["Customers", "Orders"])
        self.assertTrue(apply_mod.undo(sessions.load(code))["ok"])
        self.assertEqual(snapshot(app), before)

    def test_the_merge_is_made_against_the_menu_on_disk_when_the_owner_applies(self):
        app = self.make_app()
        write_app_menu(app, [{"label": "Home", "target": "Customer"}])
        code, result = self.ingest(app, [{"label": "Orders", "target": "Order"}])
        self.assertEqual(result["outcome"], "READY", (result["findings"], result.get("errorLines")))
        write_app_menu(app, [{"label": "Home", "target": "Customer"}, {"label": "Added meanwhile", "target": "Report", "ordinal": 3}])
        self.assertTrue(apply_mod.apply_round(sessions.load(code), None, [])["ok"])
        self.assertEqual([n["label"] for n in json.loads((app / "menu.json").read_text(encoding="utf-8"))],
                         ["Home", "Added meanwhile", "Orders"])   # what appeared after the check is kept, the new node comes last

    def test_a_menu_that_became_unreadable_stops_the_apply_before_anything_is_written(self):
        app = self.make_app()
        code, result = self.ingest(app, [{"label": "Orders", "target": "Order"}])
        self.assertEqual(result["outcome"], "READY", (result["findings"], result.get("errorLines")))
        write_app_menu(app, "{broken")
        before = snapshot(app)
        refused = apply_mod.apply_round(sessions.load(code), None, [])
        self.assertEqual((refused["ok"], refused["error"]), (False, "pages-write-refused"))
        self.assertEqual(snapshot(app), before)

    def test_a_menu_with_a_dead_page_node_is_sent_back_and_nothing_is_written(self):
        app = self.make_app()
        before = snapshot(app)
        _, result = self.ingest(app, [{"label": "Ghost", "kind": "PAGE", "target": "ghost.html"}])
        self.assertEqual(result["outcome"], "NEEDS_FIX")
        self.assertIn("there is no web/ghost.html", " ".join(result["errorLines"]))
        self.assertEqual(snapshot(app), before)

    def test_answering_with_the_menu_the_app_already_has_is_nothing_changed(self):
        app = self.make_app()
        write_app_menu(app, [{"label": "Orders", "target": "Order"}])
        _, result = self.ingest(app, [{"label": "Orders", "target": "Order"}])
        self.assertEqual(result["outcome"], "NEEDS_FIX")
        self.assertIn("identical to the current model", " ".join(result["errorLines"]))

    def test_the_default_phase_still_denies_a_menu(self):
        app = self.make_app()
        code = engine.start(intent="change", ask="x", app_dir=str(app))["code"]   # phase mvp
        named = {"model.json": seed(), "npdev-submission.json": manifest(), "menu.json": [ORDERS]}
        result = engine.ingest(code, mixed(code, 1, named), source="paste")
        self.assertEqual({f["path"]: f["status"] for f in result["files"]}["menu.json"], "denied")


if __name__ == "__main__":
    unittest.main()
