"""REG-182, 2026-08-15: `npdev run app` and `npdev dev` both spawned `java -jar
... --spring.profiles.active=dev` with no API-key provisioning at all, so both died inside
StartupValidator the moment T1/C2 removed the `dev` profile's seeded key -- see
`npdev_cli.ensure_api_key`'s own docstring for the full defect. These tests pin the fix: the
provisioner's own contract (generation, idempotency, REG-157's "present but unusable" rule), and
that `dev_loop.boot()` actually calls it before spawning the JVM, not just that the function exists.
"""

from __future__ import annotations

import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import dev_loop  # noqa: E402
import npdev_cli  # noqa: E402


class _Env:
    """Set env vars for the duration of a block and put every one of them back, present or absent.

    Deliberately a self-contained copy of test_java_launcher.py's own helper rather than an
    import across test modules -- unittest discovery does not guarantee `tests/` itself is on
    sys.path (only NPDevCli/ is, added above), and a cross-test-file import is one more thing to
    keep working as either file's own path-setup churns.
    """

    def __init__(self, **values: str | None):
        self._values = values
        self._saved: dict[str, str | None] = {}

    def __enter__(self):
        for key, value in self._values.items():
            self._saved[key] = os.environ.get(key)
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value
        return self

    def __exit__(self, *_exc):
        for key, value in self._saved.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value
        return False


class EnsureApiKeyTest(unittest.TestCase):

    def test_generates_a_fresh_key_and_exports_both_env_spellings(self):
        with tempfile.TemporaryDirectory(prefix="npdev-apikey-") as temp:
            app_root = Path(temp)
            with _Env(NPDEV_AUTH_API_KEYS=None, NPDEV_AUTH_APIKEYS=None):
                key = npdev_cli.ensure_api_key(app_root)

                self.assertTrue(key, "a live credential must come back, not empty/None")
                key_file = app_root / "secrets" / "api-key.env"
                self.assertTrue(key_file.exists())
                contents = key_file.read_text(encoding="utf-8")
                self.assertEqual(contents, f"NPDEV_AUTH_API_KEYS={key}=dev:developer:admin")

                self.assertEqual(os.environ.get("NPDEV_AUTH_API_KEYS"), f"{key}=dev:developer:admin")
                self.assertEqual(os.environ.get("NPDEV_AUTH_APIKEYS"), f"{key}=dev:developer:admin",
                                  "T4: both spellings must be exported, same as every other "
                                  "launcher's provisioner")

    def test_idempotent_a_second_call_reuses_the_same_key(self):
        with tempfile.TemporaryDirectory(prefix="npdev-apikey-") as temp:
            app_root = Path(temp)
            with _Env(NPDEV_AUTH_API_KEYS=None, NPDEV_AUTH_APIKEYS=None):
                first = npdev_cli.ensure_api_key(app_root)
                second = npdev_cli.ensure_api_key(app_root)
                self.assertEqual(first, second,
                                  "a pre-existing usable key file must never be overwritten")

    def test_reg157_shape_present_but_unusable_file_is_treated_as_absent(self):
        """A key file that exists but has no non-comment '=' line (empty, comment-only) must be
        regenerated rather than crash the caller with an unparsed None -- REG-157's own shape."""
        with tempfile.TemporaryDirectory(prefix="npdev-apikey-") as temp:
            app_root = Path(temp)
            secrets_dir = app_root / "secrets"
            secrets_dir.mkdir()
            (secrets_dir / "api-key.env").write_text("", encoding="utf-8")
            with _Env(NPDEV_AUTH_API_KEYS=None, NPDEV_AUTH_APIKEYS=None):
                key = npdev_cli.ensure_api_key(app_root)
                self.assertTrue(key)
                self.assertEqual(
                    os.environ.get("NPDEV_AUTH_API_KEYS"), f"{key}=dev:developer:admin")

    def test_different_apps_get_different_keys(self):
        with tempfile.TemporaryDirectory(prefix="npdev-apikey-a-") as temp_a, \
             tempfile.TemporaryDirectory(prefix="npdev-apikey-b-") as temp_b:
            with _Env(NPDEV_AUTH_API_KEYS=None, NPDEV_AUTH_APIKEYS=None):
                key_a = npdev_cli.ensure_api_key(Path(temp_a))
                key_b = npdev_cli.ensure_api_key(Path(temp_b))
                self.assertNotEqual(key_a, key_b)


class DevLoopBootProvisionsKeyTest(unittest.TestCase):
    """REG-182's other half: `dev_loop.boot()` must provision a key BEFORE it spawns the JVM, via
    the same dependency-injected `cli` module reference `java_launcher()` already uses (W1.3)."""

    class _SpyCli:
        """A fake `java` binary that is not real java (Popen must fail after it's invoked, not
        before) plus a call-recording ensure_api_key -- proves ORDER (key provisioned, then a boot
        attempt happens) without needing a real JVM."""

        def __init__(self, fake_java: str):
            self.fake_java = fake_java
            self.ensure_api_key_calls: list[Path] = []

        def java_launcher(self):
            return self.fake_java

        def ensure_api_key(self, app_root: Path) -> str:
            self.ensure_api_key_calls.append(app_root)
            return "spy-key"

    def test_boot_calls_ensure_api_key_before_spawning_java(self):
        with tempfile.TemporaryDirectory(prefix="npdev-devloop-") as temp:
            root = Path(temp)
            options = dev_loop.DevOptions(
                model=root / "model.json", config=root / "config.json", output=root / "app")
            options.output.mkdir(parents=True, exist_ok=True)
            options.state_dir.mkdir(parents=True, exist_ok=True)

            # Not a real `java`, so the eventual Popen fails -- that failure is expected and
            # irrelevant here; what matters is whether ensure_api_key ran before it was attempted.
            not_java = root / "not-a-real-java"
            spy = self._SpyCli(str(not_java))

            with self.assertRaises(OSError):
                dev_loop.boot(options, root / "app.jar", spy)

            self.assertEqual(spy.ensure_api_key_calls, [options.output],
                              "boot() must provision the key for the app it is about to start, "
                              "before the java -jar Popen call")


if __name__ == "__main__":
    unittest.main()
