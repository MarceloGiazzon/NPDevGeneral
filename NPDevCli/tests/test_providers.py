"""npdev providers: vault, key resolution order, connectivity check, export into an app's secrets."""
from __future__ import annotations

import json
import sys
import tempfile
import unittest
import urllib.error
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import npdev_providers as providers  # noqa: E402


class ReversibleProtector:
    """Not secure -- only proves the vault stores what the protector returns, never the plaintext."""

    def protect(self, data: bytes) -> bytes:
        return data[::-1] + b"#"

    def unprotect(self, data: bytes) -> bytes:
        return data[:-1][::-1]


class _Response:
    def __init__(self, payload, status=200):
        self.status, self._payload = status, payload

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False

    def read(self):
        return json.dumps(self._payload).encode()


class VaultTests(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.vault = providers.Vault(self.dir / "v.json", ReversibleProtector())

    def test_roundtrip_and_no_plaintext_on_disk(self):
        self.vault.set("nvidia", "nvapi-SECRET-123")
        self.assertEqual(self.vault.get("nvidia"), "nvapi-SECRET-123")
        self.assertNotIn("SECRET", (self.dir / "v.json").read_text(encoding="utf-8"))
        self.assertIn("nvidia", self.vault.ids())

    def test_remove(self):
        self.vault.set("groq", "k")
        self.assertTrue(self.vault.remove("groq"))
        self.assertFalse(self.vault.remove("groq"))
        self.assertIsNone(self.vault.get("groq"))

    def test_rejects_keys_with_whitespace_or_newline(self):
        for bad in ("", " k", "k\n"):
            with self.assertRaises(providers.ProviderError):
                self.vault.set("groq", bad)

    @unittest.skipUnless(sys.platform == "win32", "DPAPI is Windows-only")
    def test_real_dpapi_roundtrip(self):
        vault = providers.Vault(self.dir / "d.json")
        vault.set("gemini", "AIza-real-secret")
        self.assertEqual(vault.get("gemini"), "AIza-real-secret")
        self.assertNotIn("AIza-real-secret", (self.dir / "d.json").read_text(encoding="utf-8"))


class ResolutionTests(unittest.TestCase):
    def test_environment_beats_vault_and_keyless_provider_returns_none(self):
        vault = providers.Vault(Path(tempfile.mkdtemp()) / "v.json", ReversibleProtector())
        vault.set("nvidia", "from-vault")
        self.assertEqual(providers.resolve_key("nvidia", vault, {}), "from-vault")
        self.assertEqual(providers.resolve_key("nvidia", vault, {"NPDEV_EXTERNALAI_NVIDIA_API_KEY": "from-env"}), "from-env")
        self.assertIsNone(providers.resolve_key("ollama", vault, {}))

    def test_unknown_provider_names_the_known_ones(self):
        with self.assertRaises(providers.ProviderError) as ctx:
            providers.get_provider("nope")
        self.assertIn("groq", str(ctx.exception))

    def test_import_env_copies_only_catalogued_keys(self):
        vault = providers.Vault(Path(tempfile.mkdtemp()) / "v.json", ReversibleProtector())
        got = providers.import_env(vault, {"NPDEV_EXTERNALAI_GEMINI_API_KEY": "g", "UNRELATED_API_KEY": "x"})
        self.assertEqual(got, ["gemini"])
        self.assertEqual(vault.get("gemini"), "g")


class CheckProviderTests(unittest.TestCase):
    def test_ok_lists_models_and_never_returns_the_key(self):
        def opener(request, timeout):
            self.assertEqual(request.headers["Authorization"], "Bearer sekret")
            return _Response({"data": [{"id": "m1"}, {"id": "m2"}]})
        result = providers.check_provider("groq", "sekret", opener=opener)
        self.assertTrue(result["ok"])
        self.assertEqual(result["models"], 2)
        self.assertNotIn("sekret", json.dumps(result))

    def test_missing_key_explains_both_ways_to_supply_it(self):
        result = providers.check_provider("groq", None)
        self.assertFalse(result["ok"])
        self.assertIn("NPDEV_EXTERNALAI_GROQ_API_KEY", result["error"])

    def test_http_failure_reports_status_only(self):
        def opener(request, timeout):
            raise urllib.error.HTTPError(request.full_url, 401, "no", {}, None)
        result = providers.check_provider("groq", "sekret", opener=opener)
        self.assertFalse(result["ok"])
        self.assertIn("HTTP 401", result["error"])
        self.assertNotIn("sekret", json.dumps(result))


class ExportEnvTests(unittest.TestCase):
    def app(self):
        root = Path(tempfile.mkdtemp())
        (root / "secrets").mkdir()
        return root

    def test_writes_managed_block_and_preserves_other_lines_idempotently(self):
        root = self.app()
        (root / "secrets" / "agent-proxy.env").write_text("TELEGRAM_BOT_TOKEN=abc\n", encoding="utf-8")
        env = {"NPDEV_EXTERNALAI_NVIDIA_API_KEY": "nv"}
        for _ in range(2):
            providers.export_env(root, ["nvidia"], providers.Vault(root / "v.json", ReversibleProtector()), env)
        text = (root / "secrets" / "agent-proxy.env").read_text(encoding="utf-8")
        self.assertIn("TELEGRAM_BOT_TOKEN=abc", text)
        self.assertEqual(text.count(providers.MANAGED_BEGIN), 1)
        self.assertIn("NPDEV_EXTERNALAI_NVIDIA_API_KEY=nv", text)
        self.assertIn("NPDEV_EXTERNALAI_PROVIDER=http", text)

    def test_reports_same_variable_set_outside_the_block(self):
        root = self.app()
        (root / "secrets" / "agent-proxy.env").write_text("NPDEV_EXTERNALAI_NVIDIA_API_KEY=old\n", encoding="utf-8")
        result = providers.export_env(root, ["nvidia"], providers.Vault(root / "v.json", ReversibleProtector()),
                                      {"NPDEV_EXTERNALAI_NVIDIA_API_KEY": "nv"})
        self.assertEqual(result["shadowedByLinesOutsideBlock"], ["NPDEV_EXTERNALAI_NVIDIA_API_KEY"])

    def test_refuses_providers_the_app_adapter_does_not_speak_and_non_apps(self):
        root = self.app()
        vault = providers.Vault(root / "v.json", ReversibleProtector())
        with self.assertRaises(providers.ProviderError):
            providers.export_env(root, ["groq"], vault, {"NPDEV_EXTERNALAI_GROQ_API_KEY": "k"})
        with self.assertRaises(providers.ProviderError):
            providers.export_env(Path(tempfile.mkdtemp()), ["nvidia"], vault, {"NPDEV_EXTERNALAI_NVIDIA_API_KEY": "k"})


class CatalogueTests(unittest.TestCase):
    def test_catalogue_invariants(self):
        seen = set()
        for entry in providers.load_catalogue():
            self.assertNotIn(entry["id"], seen)
            seen.add(entry["id"])
            self.assertIn(entry["kind"], ("free", "local", "paid"))
            if entry["keyEnvVar"]:
                self.assertEqual(entry["keyEnvVar"], f"NPDEV_EXTERNALAI_{entry['id'].upper()}_API_KEY")
            self.assertNotRegex(json.dumps(entry), r"(sk-|nvapi-|AIza)[A-Za-z0-9]{8,}")


if __name__ == "__main__":
    unittest.main()
