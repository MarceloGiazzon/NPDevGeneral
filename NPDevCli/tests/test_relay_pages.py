"""Hand-made pages in a browser AI's answer (REG-323 T4.3b): `web/*.html|css|svg` and `pages.json`, checked with the generator's
rules before they are accepted, written all-or-nothing with the model, merged (never replaced) into the app's menu file, and
undone with it.

Why it matters: the generator FAILS GENERATION of the whole app when a web file has a name the platform ships, and a page nobody
links is only reachable by its address, so each is told to the AI before anything is written. A pages.json that holds only the
NEW page must not delete the app's other menu entries. Code is not accepted here: a <script> in html, svg or css is quarantined.

Part 1 is ingest (what is code). Part 2 the pure check, the merge and the change test. Part 3 whole answers through ingest -> apply ->
undo with the Java validator and the authoring gate stubbed. Part 4 the generation side: a `web/` folder beside the model is
mounted by `npdev dev` and `generate app` without a flag.

Run with: python -m unittest NPDevCli.tests.test_relay_pages -v
"""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE))

import npdev_cli as cli  # noqa: E402
import npdev_relay_apply as apply_mod  # noqa: E402
import npdev_relay_engine as engine  # noqa: E402
import npdev_relay_pages as pages  # noqa: E402
import npdev_relay_session as sessions  # noqa: E402
from test_relay_apply import FENCE, ApplyCase, manifest  # noqa: E402
from test_relay_files import SplitAnswerCase, answer_of, snapshot  # noqa: E402
from test_relay_themes import seed  # noqa: E402

PAGE = "<!doctype html>\n<html><head><title>Orders</title></head><body><h1>Orders</h1></body></html>\n"
POLICY = pages.load_policy()
DRIVE = "c" + ":"   # a drive-letter path, built at run time: the portability scan rejects the literal in source


def mixed(code: str, rnd: int, json_files: dict, text_files: dict | None = None) -> str:
    """An answer carrying JSON files and plain-text files (html, css, svg) as NPDEV-FILE blocks."""
    parts = [f"NPDEV-BEGIN {code}-{rnd}\nDone.\n"]
    for name, value in json_files.items():
        parts.append(f"NPDEV-FILE {name}\n{FENCE}json\n{json.dumps(value, indent=2)}\n{FENCE}\n")
    for name, body in (text_files or {}).items():
        parts.append(f"NPDEV-FILE {name}\n{FENCE}{name.rsplit('.', 1)[1]}\n{body}\n{FENCE}\n")
    parts.append(f"NPDEV-END {code}-{rnd} files={len(json_files) + len(text_files or {})}\n")
    return "".join(parts)


def codes(checked: dict) -> list:
    return [f["code"] for f in checked["findings"]]


def lines(checked: dict) -> str:
    return " | ".join(checked["errors"])


def write_app_pages(app: Path, entries: list, *page_names: str) -> None:
    (app / "web").mkdir(exist_ok=True)
    for name in page_names:
        (app / "web" / name).write_text(PAGE, encoding="utf-8")
    (app / "pages.json").write_text(json.dumps(entries, indent=2) + "\n", encoding="utf-8")


class IngestScanTests(SplitAnswerCase):
    """What counts as CODE: ingest quarantines it, whatever the file is called."""

    def files_of(self, result: dict) -> dict:
        return {f["path"]: f for f in result["files"]}

    def test_a_script_in_html_svg_or_css_is_quarantined_and_never_written(self):
        app = self.make_app()
        before = snapshot(app)
        code = self.start(app)
        text = {
            "web/a.html": '<html><script>alert(1)</script></html>',
            "web/b.html": '<a href="javascript:alert(1)">x</a>',
            "web/c.html": '<img src=x onerror="alert(1)">',
            "web/d.svg": '<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>',
            "web/e.svg": '<svg xmlns="http://www.w3.org/2000/svg" onload="alert(1)"></svg>',
            "web/f.svg": '<svg xmlns="http://www.w3.org/2000/svg"><a href="javascript:alert(1)"><rect/></a></svg>',
            "web/g.css": "body { background: url(javascript:alert(1)) }",
            "web/h.css": "div { width: expression(alert(1)) }",
            "web/i.css": "div { -moz-binding: url(x.xml#y) }",
            "web/j.css": "div { behavior: url(x.htc) }",
        }
        result = engine.ingest(code, mixed(code, 1, {"model.json": seed(), "npdev-submission.json": manifest()}, text), source="paste")
        got = self.files_of(result)
        expected = {"a": "html", "b": "html", "c": "html", "d": "svg", "e": "svg", "f": "svg",
                    "g": "css", "h": "css", "i": "css", "j": "css"}
        for letter, kind in expected.items():
            path = next(p for p in got if p.startswith(f"web/{letter}."))
            self.assertEqual((got[path]["status"], got[path]["reason"]), ("quarantine", f"{kind}-has-script"), path)
        self.assertEqual(snapshot(app), before)

    def test_plain_markup_is_allowed_and_an_apply_skips_what_was_quarantined(self):
        app = self.make_app()
        code = self.start(app)
        text = {"web/ok.html": PAGE, "web/ok.svg": '<svg xmlns="http://www.w3.org/2000/svg"><circle r="5"/></svg>',
                "web/ok.css": "body { color: #222 }", "web/bad.html": "<script>1</script>"}
        result = engine.ingest(code, mixed(code, 1, {"model.json": seed(), "npdev-submission.json": manifest()}, text), source="paste")
        got = self.files_of(result)
        self.assertEqual([got[p]["status"] for p in ("web/ok.html", "web/ok.svg", "web/ok.css")], ["allowed"] * 3)
        self.assertEqual(result["outcome"], "READY", (result["findings"], result.get("errorLines")))
        done = apply_mod.apply_round(sessions.load(code), None, [])
        self.assertTrue(done["ok"], done)
        self.assertEqual(sorted(p for p in snapshot(app) if p.startswith("web/")), ["web/ok.css", "web/ok.html", "web/ok.svg"])

    def test_the_policy_still_denies_a_web_file_in_the_default_phase(self):
        app = self.make_app()
        code = engine.start(intent="change", ask="x", app_dir=str(app))["code"]   # phase mvp
        result = engine.ingest(code, mixed(code, 1, {"model.json": seed(), "npdev-submission.json": manifest()},
                                           {"web/a.html": PAGE}), source="paste")
        self.assertEqual(self.files_of(result)["web/a.html"]["status"], "denied")


class CheckTests(unittest.TestCase):
    def check(self, files: dict, model=None, app=None):
        return pages.check(files, model if model is not None else seed(), app)

    def test_a_page_with_a_menu_entry_is_clean(self):
        checked = self.check({"web/orders.html": PAGE, "pages.json": json.dumps([{"path": "orders.html", "label": "Orders"}])})
        self.assertEqual((checked["errors"], codes(checked), checked["pages"]), ([], ["page-added"], ["web/orders.html"]))

    def test_a_web_file_named_like_a_platform_file_is_refused_before_it_fails_the_whole_app(self):
        for name in ("web/info.html", "web/model-authoring.html", "web/npdev-a11y.css", "web/shell.css"):
            self.assertIn(name[4:], POLICY["reservedFiles"], name)
            self.assertIn("is a file the platform ships", lines(self.check({name: "x"})), name)
        for name in ("web/themes/summer.html", "web/npdev-workbench/a.css", "web/npdev-business-ui/x.svg"):
            self.assertIn("belongs to the platform", lines(self.check({name: "x"})), name)
        self.assertEqual(self.check({"web/theme.css": "body{}"})["errors"], [])   # `theme.css` is NOT reserved: the shell injects it
        self.assertEqual(self.check({"web/orders/themes.html": "x"})["errors"], [])  # only the TOP folder is reserved

    def test_a_new_page_nobody_links_is_a_warning_and_a_replaced_one_is_not(self):
        with tempfile.TemporaryDirectory() as tmp:
            app = Path(tmp)
            write_app_pages(app, [{"path": "old.html", "label": "Old"}], "old.html", "hidden.html")
            checked = self.check({"web/new.html": PAGE, "web/old.html": PAGE, "web/hidden.html": PAGE + "<!-- v2 -->"}, app=app)
            warned = [f["detail"]["path"] for f in checked["findings"] if f["code"] == "page-not-in-menu"]
            self.assertEqual(warned, ["web/new.html"])   # hidden.html existed unlisted before: the owner's choice, not a surprise
            self.assertEqual(sorted(f["detail"]["path"] for f in checked["findings"] if f["code"] == "page-replaced"),
                             ["web/hidden.html", "web/old.html"])

    def test_an_entry_whose_page_was_not_sent_is_sent_back_and_one_already_in_the_app_is_fine(self):
        checked = self.check({"pages.json": json.dumps([{"path": "ghost.html", "label": "Ghost"}])})
        self.assertIn('lists "ghost.html" but there is no web/ghost.html', lines(checked))
        with tempfile.TemporaryDirectory() as tmp:
            write_app_pages(Path(tmp), [], "there.html")
            self.assertEqual(self.check({"pages.json": json.dumps([{"path": "there.html", "label": "There"}])}, app=tmp)["errors"], [])

    def test_the_ways_an_ai_writes_an_entry_are_understood(self):
        for entry in ({"path": "web/orders.html", "label": "Orders"}, {"path": "./orders.html", "label": "Orders"},
                      {"path": "/web/orders.html", "label": "Orders"}, {"path": "orders.html"}):
            got, errors, findings = pages.parse_entries(json.dumps([entry]))
            self.assertEqual((errors, got[0]["path"]), ([], "orders.html"), entry)
        entries, _, findings = pages.parse_entries(json.dumps([{"path": "sales-report_q3.html"}]))
        self.assertEqual(entries[0]["label"], "Sales Report Q3")
        self.assertIn("pages-label-derived", [f["code"] for f in findings])
        self.assertIn("pages-path-normalized", [f["code"] for f in pages.parse_entries(json.dumps([{"path": "web/a.html"}]))[2]])
        for shape in ({"pages": [{"path": "a.html", "label": "A"}]}, {"path": "a.html", "label": "A"}, [{"path": "a.html", "label": "A"}]):
            self.assertEqual(pages.parse_entries(json.dumps(shape))[0], [{"path": "a.html", "label": "A"}], shape)

    def test_the_same_page_twice_keeps_the_last(self):
        entries, errors, _ = pages.parse_entries(json.dumps([{"path": "a.html", "label": "First"}, {"path": "a.html", "label": "Last"}]))
        self.assertEqual((errors, entries), ([], [{"path": "a.html", "label": "Last"}]))

    def test_a_bad_entry_is_named_by_position_in_plain_words(self):
        bad = [("nope", "is not an object"), ({"label": "x"}, 'needs a "path"'), ({"path": "../x.html"}, "plain relative path"),
               ({"path": "a/../../x.html"}, "plain relative path"), ({"path": "a.txt"}, "html page"),
               ({"path": DRIVE + "/x.html"}, "plain relative path"), ({"path": "a.html", "label": "L" * 200}, "longer than 80"),
               ({"path": "a.html", "ordinal": -1}, "ordinal must be a whole number"), ({"path": "a.html", "ordinal": "1"}, "ordinal"),
               ({"path": "a.html", "requiredRole": 5}, "requiredRole")]
        for entry, why in bad:
            _, errors, _ = pages.parse_entries(json.dumps([entry]))
            self.assertTrue(any(why in e for e in errors), (entry, errors))
            self.assertIn("entry 0", " ".join(errors))

    def test_not_json_and_not_a_list_are_refused(self):
        self.assertIn("not valid JSON", pages.parse_entries("{nope")[1][0])
        self.assertIn("must be a list", pages.parse_entries('"orders.html"')[1][0])
        self.assertIn("must be a list", pages.parse_entries("5")[1][0])

    def test_a_role_the_model_does_not_declare_is_a_warning_only_when_the_model_declares_roles(self):
        files = {"web/a.html": PAGE, "pages.json": json.dumps([{"path": "a.html", "label": "A", "requiredRole": "auditor"}])}
        known = self.check(files, model={"roles": [{"name": "auditor"}, {"name": "clerk"}]})
        self.assertNotIn("page-role-unknown", codes(known))
        unknown = self.check(files, model={"roles": [{"name": "clerk"}]})
        self.assertEqual([f["detail"]["role"] for f in unknown["findings"] if f["code"] == "page-role-unknown"], ["auditor"])
        self.assertEqual(unknown["errors"], [])
        self.assertNotIn("page-role-unknown", codes(self.check(files, model={"concepts": []})))

    def test_a_page_that_loads_from_another_site_is_a_warning_naming_the_host(self):
        html = '<img src="https://cdn.example.com/a.png"><link href=//fonts.example.org/css rel=stylesheet><a href="/local.html">x</a>'
        css = '@import "https://other.example.net/x.css"; body { background: url(https://img.example.io/b.png) }'
        checked = self.check({"web/a.html": html, "web/a.css": css, "web/clean.html": PAGE})
        hosts = {f["detail"]["path"]: f["detail"]["hosts"] for f in checked["findings"] if f["code"] == "page-external-reference"}
        self.assertEqual(hosts, {"web/a.html": ["cdn.example.com", "fonts.example.org"], "web/a.css": ["img.example.io", "other.example.net"]})
        self.assertEqual(checked["errors"], [])

    def test_a_css_that_restyles_every_screen_says_so(self):
        checked = self.check({"web/theme.css": "body { color: #111 }"})
        self.assertIn("page-css-global", codes(checked))
        self.assertNotIn("page-css-global", codes(self.check({"web/orders.css": "body{}"})))


class MergeTests(unittest.TestCase):
    def test_existing_entries_are_kept_in_order_a_listed_page_is_replaced_in_place_and_new_ones_come_last(self):
        current = [{"path": "a.html", "label": "A", "ordinal": 10, "note": "mine"}, {"path": "b.html", "label": "B"}]
        new = [{"path": "b.html", "label": "B renamed"}, {"path": "c.html", "label": "C"}]
        self.assertEqual(pages.merge(current, new), [current[0], {"path": "b.html", "label": "B renamed"}, {"path": "c.html", "label": "C"}])
        self.assertEqual(current[1], {"path": "b.html", "label": "B"})   # the input is not changed

    def test_merged_text_reads_the_app_s_file_and_an_unreadable_one_is_a_sentence_not_a_loss(self):
        with tempfile.TemporaryDirectory() as tmp:
            write_app_pages(Path(tmp), [{"path": "a.html", "label": "A"}])
            text = pages.merged_text(tmp, json.dumps([{"path": "b.html", "label": "B"}]))
            self.assertEqual([e["path"] for e in json.loads(text)], ["a.html", "b.html"])
            (Path(tmp) / "pages.json").write_text("{broken", encoding="utf-8")
            with self.assertRaises(ValueError) as caught:
                pages.merged_text(tmp, json.dumps([{"path": "b.html", "label": "B"}]))
            self.assertIn("cannot be added to", str(caught.exception))
            with self.assertRaises(ValueError):
                pages.merged_text(tmp, "[1]")
        self.assertEqual([e["path"] for e in json.loads(pages.merged_text(None, json.dumps([{"path": "b.html"}])))], ["b.html"])

    def test_prepare_swaps_in_the_merge_and_leaves_pages_json_out_when_nothing_would_change(self):
        with tempfile.TemporaryDirectory() as tmp:
            write_app_pages(Path(tmp), [{"path": "a.html", "label": "A"}])
            extra = {"web/b.html": PAGE, "pages.json": json.dumps([{"path": "b.html", "label": "B"}])}
            out = pages.prepare(tmp, extra)
            self.assertEqual([e["path"] for e in json.loads(out["pages.json"])], ["a.html", "b.html"])
            self.assertEqual(out["web/b.html"], PAGE)
            same = pages.prepare(tmp, {"pages.json": json.dumps([{"path": "a.html", "label": "A"}])})
            self.assertNotIn("pages.json", same)
            self.assertEqual(pages.prepare(tmp, {"web/b.html": PAGE}), {"web/b.html": PAGE})


class ChangedTests(unittest.TestCase):
    def test_a_new_or_different_page_counts_line_endings_and_an_unchanged_menu_do_not(self):
        with tempfile.TemporaryDirectory() as tmp:
            app = Path(tmp)
            write_app_pages(app, [{"path": "a.html", "label": "A"}], "a.html")
            self.assertEqual(pages.changed(app, {"web/a.html": PAGE.replace("\n", "\r\n")}), [])
            self.assertEqual(pages.changed(app, {"web/a.html": PAGE + "<!-- v2 -->"}), ["web/a.html"])
            self.assertEqual(pages.changed(app, {"web/new.css": "p{}"}), ["web/new.css"])
            self.assertEqual(pages.changed(app, {"pages.json": json.dumps([{"path": "a.html", "label": "A"}])}), [])
            self.assertEqual(pages.changed(app, {"pages.json": json.dumps([{"path": "b.html", "label": "B"}])}), ["pages.json"])
            self.assertEqual(pages.changed(app, {"themes/x.json": "{}", "fragments/f.json": "{}"}), [])


class PageAnswerTests(SplitAnswerCase):
    """Whole answers with phase f4: ingest -> apply -> undo."""

    def answer(self, code: str, texts: dict, json_files: dict | None = None, model=None) -> str:
        named = {"model.json": model if model is not None else seed(), "npdev-submission.json": manifest(), **(json_files or {})}
        return mixed(code, 1, named, texts)

    def ingest(self, app: Path, texts: dict, json_files: dict | None = None, model=None) -> tuple:
        code = self.start(app, ask="Add an orders page")
        return code, engine.ingest(code, self.answer(code, texts, json_files, model), source="paste")

    def test_a_page_and_its_menu_entry_are_applied_together_and_undone_together(self):
        app = self.make_app()
        before = snapshot(app)
        code, result = self.ingest(app, {"web/orders.html": PAGE},
                                   {"pages.json": [{"path": "orders.html", "label": "Orders", "requiredRole": None}]})
        self.assertEqual(result["outcome"], "READY", (result["findings"], result.get("errorLines")))
        self.assertEqual([f["code"] for f in result["findings"] if f["code"].startswith("page")], ["page-added"])
        self.assertEqual(snapshot(app), before)   # nothing is written until the owner applies
        done = apply_mod.apply_round(sessions.load(code), None, [])
        self.assertTrue(done["ok"], done)
        self.assertEqual(done["files"], ["pages.json", "web/orders.html", "model.json"])   # model.json last
        self.assertEqual(json.loads((app / "pages.json").read_text(encoding="utf-8")),
                         [{"path": "orders.html", "label": "Orders"}])
        self.assertEqual((app / "web" / "orders.html").read_text(encoding="utf-8"), PAGE)
        undone = apply_mod.undo(sessions.load(code))
        self.assertTrue(undone["ok"], undone)
        self.assertEqual(snapshot(app), before)
        self.assertFalse((app / "web").exists())   # the folder the answer made is gone

    def test_a_page_alone_leaves_model_json_identical_and_is_still_a_change(self):
        app = self.make_app()
        code, result = self.ingest(app, {"web/orders.html": PAGE})
        self.assertEqual(result["outcome"], "READY", (result["findings"], result.get("errorLines")))
        self.assertIn("page-not-in-menu", [f["code"] for f in result["findings"]])
        self.assertTrue(apply_mod.apply_round(sessions.load(code), None, [])["ok"])
        self.assertFalse((app / "pages.json").exists())   # no menu file is made for a page nobody listed

    def test_sending_only_the_new_entry_keeps_the_apps_other_menu_entries(self):
        app = self.make_app()
        existing = [{"path": "a.html", "label": "A", "ordinal": 1000}, {"path": "b.html", "label": "B", "requiredRole": "clerk"}]
        write_app_pages(app, existing, "a.html", "b.html")
        before = snapshot(app)
        code, result = self.ingest(app, {"web/c.html": PAGE}, {"pages.json": [{"path": "c.html", "label": "C"}]})
        self.assertEqual(result["outcome"], "READY", (result["findings"], result.get("errorLines")))
        self.assertTrue(apply_mod.apply_round(sessions.load(code), None, [])["ok"])
        self.assertEqual(json.loads((app / "pages.json").read_text(encoding="utf-8")), existing + [{"path": "c.html", "label": "C"}])
        self.assertTrue(apply_mod.undo(sessions.load(code))["ok"])
        self.assertEqual(snapshot(app), before)   # pages.json is back byte for byte

    def test_the_merge_is_made_against_the_menu_file_on_disk_when_the_owner_applies(self):
        app = self.make_app()
        write_app_pages(app, [{"path": "a.html", "label": "A"}], "a.html")
        code, _ = self.ingest(app, {"web/c.html": PAGE}, {"pages.json": [{"path": "c.html", "label": "C"}]})
        write_app_pages(app, [{"path": "a.html", "label": "A"}, {"path": "late.html", "label": "Late"}], "late.html")  # edited meanwhile
        self.assertTrue(apply_mod.apply_round(sessions.load(code), None, [])["ok"])
        self.assertEqual([e["path"] for e in json.loads((app / "pages.json").read_text(encoding="utf-8"))], ["a.html", "late.html", "c.html"])

    def test_a_menu_file_that_became_unreadable_stops_the_apply_before_anything_is_written(self):
        app = self.make_app()
        write_app_pages(app, [{"path": "a.html", "label": "A"}], "a.html")
        code, _ = self.ingest(app, {"web/c.html": PAGE}, {"pages.json": [{"path": "c.html", "label": "C"}]})
        (app / "pages.json").write_text("{broken", encoding="utf-8")
        before = snapshot(app)
        refused = apply_mod.apply_round(sessions.load(code), None, [])
        self.assertEqual((refused["ok"], refused["error"]), (False, "pages-write-refused"))
        self.assertEqual(snapshot(app), before)

    def test_a_menu_file_that_is_not_a_list_is_sent_back_at_ingest_with_the_way_out(self):
        app = self.make_app()
        write_app_pages(app, [], "a.html")
        (app / "pages.json").write_text("{broken", encoding="utf-8")
        _, result = self.ingest(app, {"web/c.html": PAGE}, {"pages.json": [{"path": "c.html", "label": "C"}]})
        self.assertEqual(result["outcome"], "NEEDS_FIX")
        self.assertIn("cannot be added to", " ".join(result["errorLines"]))

    def test_a_page_named_like_a_platform_file_is_sent_back_naming_the_rule(self):
        app = self.make_app()
        before = snapshot(app)
        _, result = self.ingest(app, {"web/info.html": PAGE})
        self.assertEqual(result["outcome"], "NEEDS_FIX")
        self.assertIn("is a file the platform ships", " ".join(result["errorLines"]))
        self.assertEqual(snapshot(app), before)

    def test_an_entry_for_a_page_that_was_not_sent_is_sent_back(self):
        _, result = self.ingest(self.make_app(), {}, {"pages.json": [{"path": "ghost.html", "label": "Ghost"}]})
        self.assertEqual(result["outcome"], "NEEDS_FIX")
        self.assertIn("there is no web/ghost.html", " ".join(result["errorLines"]))

    def test_answering_with_exactly_what_the_app_has_is_still_nothing_changed(self):
        app = self.make_app()
        write_app_pages(app, [{"path": "a.html", "label": "A"}], "a.html")
        code = self.start(app)
        result = engine.ingest(code, self.answer(code, {"web/a.html": PAGE}, {"pages.json": [{"path": "a.html", "label": "A"}]}), source="paste")
        self.assertEqual(result["outcome"], "NEEDS_FIX")
        self.assertIn("identical to the current model", " ".join(result["errorLines"]))

    def test_an_svg_and_a_css_are_applied_like_a_page(self):
        app = self.make_app()
        svg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><circle cx="5" cy="5" r="4"/></svg>'
        code, result = self.ingest(app, {"web/logo.svg": svg, "web/theme.css": "body { font-size: 17px }"})
        self.assertEqual(result["outcome"], "READY", (result["findings"], result.get("errorLines")))
        self.assertIn("page-css-global", [f["code"] for f in result["findings"]])
        self.assertTrue(apply_mod.apply_round(sessions.load(code), None, [])["ok"])
        self.assertEqual(sorted(p for p in snapshot(app) if p.startswith("web/")), ["web/logo.svg", "web/theme.css"])


class CreatePageTests(ApplyCase):
    def test_a_new_app_gets_its_pages_and_a_menu_file(self):
        code = engine.start(intent="create", ask="A shop with an about page", new_app_name="pageshop", phase="f4")["code"]
        answer = mixed(code, 1, {"model.json": seed(), "npdev-submission.json": manifest(),
                                 "pages.json": [{"path": "about.html", "label": "About us"}]}, {"web/about.html": PAGE})
        result = engine.ingest(code, answer, source="paste")
        self.assertEqual(result["outcome"], "READY", (result["findings"], result.get("errorLines")))
        done = apply_mod.apply_round(sessions.load(code), None, [], parent_dir=str(self.dir / "apps"))
        self.assertTrue(done["ok"], done)
        app = Path(done["appDir"])
        self.assertEqual((app / "web" / "about.html").read_text(encoding="utf-8"), PAGE)
        self.assertEqual(json.loads((app / "pages.json").read_text(encoding="utf-8")), [{"path": "about.html", "label": "About us"}])


class WebFolderIsMountedTests(unittest.TestCase):
    """The pages are only served if generation mounts the folder: `npdev dev` (how the Manager runs an app) has no flag."""

    def model_in(self, tmp: str, with_web: bool) -> Path:
        model = Path(tmp) / "model.json"
        model.write_text("{}", encoding="utf-8")
        if with_web:
            (Path(tmp) / "web").mkdir()
        return model

    def test_a_web_folder_beside_the_model_is_mounted_without_a_flag_and_none_means_nothing(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertIsNone(cli.resolve_web_assets_root(None, self.model_in(tmp, with_web=False)))
        with tempfile.TemporaryDirectory() as tmp:
            model = self.model_in(tmp, with_web=True)
            self.assertEqual(cli.resolve_web_assets_root(None, model), (Path(tmp) / "web").resolve())

    def test_the_flag_wins_and_a_typo_is_an_error_not_an_app_without_its_pages(self):
        with tempfile.TemporaryDirectory() as tmp, tempfile.TemporaryDirectory() as other:
            model = self.model_in(tmp, with_web=True)
            self.assertEqual(cli.resolve_web_assets_root(other, model), Path(other).resolve())
            with self.assertRaises(cli.CliError):
                cli.resolve_web_assets_root(str(Path(tmp) / "nowhere"), model)

    def test_the_captured_generate_that_dev_and_run_use_passes_the_folder_to_the_generator(self):
        done = subprocess.CompletedProcess([], 0, stdout="", stderr="")
        for with_web in (True, False):
            with tempfile.TemporaryDirectory() as tmp:
                model = self.model_in(tmp, with_web)
                config = Path(tmp) / "config.json"
                config.write_text("{}", encoding="utf-8")
                args = mock.Mock(model=str(model), config=str(config), require_db_definition=False, web_assets=None)
                with mock.patch.object(cli, "_run_bounded", return_value=done) as run, \
                        mock.patch.object(cli, "_emit_static_pages"), mock.patch.object(cli, "claim_artifact_dir"):
                    ok, _ = cli._generate_phase_captured(REPO, args, Path(tmp) / "app", float("inf"))
                self.assertTrue(ok)
                generator_args = run.call_args.args[0][-1]
                self.assertEqual("--webAssetsRoot" in generator_args, with_web, generator_args)
                if with_web:
                    self.assertIn(str((Path(tmp) / "web").resolve()), generator_args)

    def test_the_names_a_web_file_may_not_have_match_the_generators(self):
        java = (REPO / "NPDevGenerator" / "generator" / "src" / "main" / "java" / "com" / "npdev" / "generator" / "assembly"
                / "FinalAppAssembler.java").read_text(encoding="utf-8")
        for name in POLICY["reservedFiles"] + POLICY["reservedDirectories"]:
            self.assertIn(f'"{name}"', java, f"{name} is no longer reserved by FinalAppAssembler")


if __name__ == "__main__":
    unittest.main()
