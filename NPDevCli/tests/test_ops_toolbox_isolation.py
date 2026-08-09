"""QUAL-3: an app's operations toolbox must belong to THAT app.

WHAT THIS PINS, AND WHY IT IS NOT "the path resolver returns a path"
--------------------------------------------------------------------
`OperationalRunbookEmitter` used to write `_ops` beside the FinalApp
(`finalAppRoot.getParent().resolve("_ops")`), which makes the toolbox a property of the PARENT
DIRECTORY rather than of the app. `npdev init D:\\Apps\\my-app` generates into `D:\\Apps\\my-app-app`,
so the toolbox landed at `D:\\Apps\\_ops`; a second app in the same folder wrote THE SAME file.
`resolved-db-plan.json` is what all five scripts read, so after the second generation every script
described the second app -- and `npdev db reset` "for" the first one removed the second one's
container and deleted its data root, reporting success.

Measured RED before the fix (two apps generated into one folder):

    npdev db status --app <app-a>   ->   [qual3 | Postgres | .../app-b-app]

Two apps in one folder is not an exotic setup. It is what evaluating the product looks like, and it
is the first thing a second machine does.

THE TRAP THIS FILE EXISTS FOR
-----------------------------
The reader keeps a fallback so apps generated BEFORE the fix keep working. A fallback consulted
first would hand a newly-generated app the old SHARED toolbox -- the bug, reintroduced inside its
own fix. So the ordering is pinned here, not just the happy path, and so is the fact that using the
legacy location is ANNOUNCED rather than silent.

Stdlib only. Run with:
    python -m unittest NPDevCli.tests.test_ops_toolbox_isolation -v
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402


class OpsToolboxIsolationTest(unittest.TestCase):
    def _tree(self, root: Path) -> None:
        """`npdev init`'s own layout: the model directory and its generated `-app` sibling."""
        (root / "app-a").mkdir(parents=True, exist_ok=True)
        (root / "app-a-app").mkdir(parents=True, exist_ok=True)
        (root / "app-b").mkdir(parents=True, exist_ok=True)
        (root / "app-b-app").mkdir(parents=True, exist_ok=True)

    def test_app_local_toolbox_is_found_from_the_model_directory(self):
        """`--app <model dir>` must find the toolbox inside that app's OWN FinalApp."""
        with TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._tree(root)
            (root / "app-a-app" / "_ops").mkdir()

            found = npdev_cli._find_ops_root(str(root / "app-a"))

            self.assertIsNotNone(found)
            path, is_legacy = found
            self.assertEqual(path, root / "app-a-app" / "_ops")
            self.assertFalse(is_legacy)

    def test_app_local_toolbox_is_found_from_the_finalapp_directory(self):
        """`--app <FinalApp dir>` is equally reasonable and must resolve to the same toolbox."""
        with TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._tree(root)
            (root / "app-a-app" / "_ops").mkdir()

            found = npdev_cli._find_ops_root(str(root / "app-a-app"))

            self.assertIsNotNone(found)
            path, is_legacy = found
            self.assertEqual(path, root / "app-a-app" / "_ops")
            self.assertFalse(is_legacy)

    def test_two_apps_in_one_folder_get_different_toolboxes(self):
        """THE regression. Before the fix both of these resolved to the one shared `<parent>/_ops`."""
        with TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._tree(root)
            (root / "app-a-app" / "_ops").mkdir()
            (root / "app-b-app" / "_ops").mkdir()

            a, _ = npdev_cli._find_ops_root(str(root / "app-a"))
            b, _ = npdev_cli._find_ops_root(str(root / "app-b"))

            self.assertNotEqual(a, b, "two apps in one folder must not share an operations toolbox")

    def test_legacy_shared_toolbox_still_works_and_is_flagged(self):
        """An app generated before the fix keeps working -- and says which toolbox it is using."""
        with TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._tree(root)
            (root / "_ops").mkdir()  # the pre-QUAL-3 shared location, and nothing app-local

            found = npdev_cli._find_ops_root(str(root / "app-a"))

            self.assertIsNotNone(found)
            path, is_legacy = found
            self.assertEqual(path, root / "_ops")
            self.assertTrue(is_legacy, "using the shared toolbox must be reported, never silent")

    def test_app_local_wins_when_both_exist(self):
        """The trap: a pre-fix and a post-fix app coexisting in one folder.

        If the legacy fallback were consulted first -- or merely preferred when it happens to
        exist -- a freshly generated app would be handed the old SHARED plan, which is the exact
        defect this change removes. The app-local toolbox must win, and quietly.
        """
        with TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._tree(root)
            (root / "_ops").mkdir()                     # legacy shared, left behind
            (root / "app-a-app" / "_ops").mkdir()       # app-a regenerated since the fix

            path, is_legacy = npdev_cli._find_ops_root(str(root / "app-a"))
            self.assertEqual(path, root / "app-a-app" / "_ops")
            self.assertFalse(is_legacy)

            # app-b has NOT been regenerated, so it still falls back -- and is still flagged.
            path_b, is_legacy_b = npdev_cli._find_ops_root(str(root / "app-b"))
            self.assertEqual(path_b, root / "_ops")
            self.assertTrue(is_legacy_b)

    def test_no_toolbox_at_all_is_none_not_a_guess(self):
        with TemporaryDirectory() as tmp:
            root = Path(tmp)
            self._tree(root)
            self.assertIsNone(npdev_cli._find_ops_root(str(root / "app-a")))


if __name__ == "__main__":
    unittest.main()
