"""S13 (NPDEV_MEGA_ROADMAP.md): pins the capability-docs tooling contract -- one command emits the
three capability docs against a running app, screenshots captured through Playwright when available
and honestly placeholder'd when not; HTML is self-contained (data: URI images).

Stdlib-only (unittest). The capture half needs a browser, so it is asserted via the placeholder
path and the renderer with fake capture results. Run with:
    python -m unittest NPDevCli.tests.test_capture_capability_docs -v
"""

from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

_SPEC = importlib.util.spec_from_file_location(
    "capture_capability_docs", REPO_ROOT / "scripts" / "docs" / "capture-capability-docs.py")
docs_mod = importlib.util.module_from_spec(_SPEC)
assert _SPEC.loader is not None
_SPEC.loader.exec_module(docs_mod)


class RenderDocContractTest(unittest.TestCase):
    def test_placeholder_doc_is_emitted_without_a_browser(self):
        capability = docs_mod.CAPABILITIES[0]
        results = [("/orders", False, "no browser requested (--no-browser)")]
        html = docs_mod.render_doc(capability, "http://localhost:8084", results, Path("shots"))

        self.assertIn(capability["title"], html)
        self.assertIn("not captured", html)
        self.assertIn("no browser requested", html)
        self.assertTrue(html.rstrip().endswith("</html>"))

    def test_captured_result_embeds_a_data_uri_image(self):
        import base64
        import tempfile
        from pathlib import Path

        capability = docs_mod.CAPABILITIES[1]
        with tempfile.TemporaryDirectory(prefix="npdev-shots-") as tmp:
            shots = Path(tmp)
            (shots / "shot-0.png").write_bytes(b"\x89PNG fake")
            results = [("/login.html", True, "HTTP 200, shot-0.png")]
            html = docs_mod.render_doc(capability, "http://localhost:8084", results, shots)

            expected = "data:image/png;base64," + base64.b64encode(b"\x89PNG fake").decode("ascii")
            self.assertIn(expected, html)
            self.assertIn("captured", html)

    def test_every_capability_names_routes_for_a_non_specialist(self):
        for capability in docs_mod.CAPABILITIES:
            self.assertTrue(capability["routes"], capability["id"])
            self.assertIn("definition", capability)
            self.assertTrue(capability["definition"].strip(), capability["id"])


if __name__ == "__main__":
    unittest.main()