"""User profiles: whose keys pay for which AI work (npdev_user_profiles + its two seams in the AI client and CLI)."""
from __future__ import annotations

import argparse
import contextlib
import io
import json
import os
import sys
import tempfile
import unittest
import urllib.error
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import npdev_ai  # noqa: E402
import npdev_ai_client as client  # noqa: E402
import npdev_ai_config as aicfg  # noqa: E402
import npdev_providers  # noqa: E402
import npdev_user_profiles as users  # noqa: E402

SECRET = "test-key-0123456789abcdef"
OPENAI_OK = {"choices": [{"message": {"content": "OK"}}], "usage": {"prompt_tokens": 10, "completion_tokens": 2}}


def doc(**profiles) -> dict:
    return {"schemaVersion": users.SCHEMA_VERSION, "active": None,
            "profiles": {name: {"displayName": name, "aiRoles": roles} for name, roles in profiles.items()}}


class Isolated(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        patcher = mock.patch.dict(os.environ, {users.PATH_ENV: str(self.dir / "user-profiles.json")})
        patcher.start()
        self.addCleanup(patcher.stop)
        os.environ.pop(users.ACTIVE_ENV, None)


class ResolutionTests(unittest.TestCase):
    def test_nobody_active_and_no_role_owner_means_the_main_key(self):
        self.assertEqual(users.resolve_account("bench", doc(marcelo=[], irmao=["review"]), {}), (None, None))
        self.assertEqual(users.resolve_account(None, doc(), {}), (None, None))

    def test_a_role_uses_the_keys_of_its_owner(self):
        document = doc(marcelo=["bench"], irmao=["review"])
        self.assertEqual(users.resolve_account("bench", document, {})[0], "marcelo")
        self.assertEqual(users.resolve_account("review", document, {})[0], "irmao")
        self.assertEqual(users.resolve_account("tester", document, {})[0], None)

    def test_the_active_profile_overrides_every_role(self):
        document = {**doc(marcelo=["bench"], irmao=[]), "active": "irmao"}
        self.assertEqual(users.resolve_account("bench", document, {})[0], "irmao")

    def test_the_environment_overrides_the_file(self):
        document = {**doc(marcelo=[], irmao=[]), "active": "marcelo"}
        self.assertEqual(users.resolve_account("bench", document, {users.ACTIVE_ENV: "irmao"})[0], "irmao")

    def test_an_unknown_active_profile_fails_closed(self):
        with self.assertRaises(users.UserProfileError):
            users.resolve_account("bench", doc(marcelo=[]), {users.ACTIVE_ENV: "ghost"})

    def test_a_role_in_two_profiles_is_refused(self):
        with self.assertRaises(users.UserProfileError):
            users.resolve_account("bench", doc(a=["bench"], b=["bench"]), {})


class EditTests(Isolated):
    def test_add_use_assign_unassign_remove(self):
        document = users.read()
        users.add(document, "irmao", "Meu irmao")
        users.add(document, "marcelo")
        users.assign_role(document, "irmao", "review")
        users.use(document, "irmao")
        self.assertEqual(users.owner_of(document, "review"), "irmao")
        with self.assertRaises(users.UserProfileError):   # a role has one owner
            users.assign_role(document, "marcelo", "review")
        self.assertEqual(users.unassign_role(document, "review"), "irmao")
        users.assign_role(document, "marcelo", "review")
        users.remove(document, "irmao")
        self.assertIsNone(document["active"])   # removing the active profile goes back to anonymous

    def test_ids_and_roles_are_checked(self):
        document = users.read()
        for bad in ("", "Marcelo", "a b", "x" * 33, "-x", "a:b"):
            with self.assertRaises(users.UserProfileError, msg=bad):
                users.add(document, bad)
        users.add(document, "ok")
        with self.assertRaises(users.UserProfileError):
            users.assign_role(document, "ok", "not-a-feature")

    def test_unknown_keys_of_a_profile_survive_a_write(self):
        file = self.dir / "user-profiles.json"
        file.write_text(json.dumps({"schemaVersion": users.SCHEMA_VERSION, "profiles":
                                    {"a": {"displayName": "A", "aiRoles": [], "theme": "dark", "paths": {"build": "/x"}}}}),
                        encoding="utf-8")
        document = users.read()
        users.assign_role(document, "a", "bench")
        users.write(document)
        saved = json.loads(file.read_text(encoding="utf-8"))["profiles"]["a"]
        self.assertEqual((saved["theme"], saved["paths"], saved["aiRoles"]), ("dark", {"build": "/x"}, ["bench"]))

    def test_a_foreign_file_is_refused_not_overwritten(self):
        file = self.dir / "user-profiles.json"
        file.write_text('{"hello": 1}', encoding="utf-8")
        with self.assertRaises(users.UserProfileError):
            users.read()
        self.assertEqual(file.read_text(encoding="utf-8"), '{"hello": 1}')


class CliTests(Isolated):
    def run_cli(self, *argv) -> tuple[int, str]:
        parser = argparse.ArgumentParser()
        npdev_ai.add_arguments(parser.add_subparsers(dest="ai_command"))
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
            code = npdev_ai.run(parser.parse_args([*argv, "--json"]))
        return code, out.getvalue()

    def test_the_whole_flow_through_the_cli(self):
        self.assertEqual(self.run_cli("users", "add", "irmao")[0], 0)
        self.assertEqual(self.run_cli("users", "assign", "irmao", "review")[0], 0)
        code, text = self.run_cli("users", "use", "irmao")
        self.assertEqual(code, 0, text)
        code, text = self.run_cli("users")
        listed = json.loads(text)
        self.assertEqual((listed["active"], listed["profiles"][0]["aiRoles"]), ("irmao", ["review"]))
        self.assertEqual(self.run_cli("users", "anonymous")[0], 0)
        self.assertIsNone(json.loads(self.run_cli("users")[1])["active"])

    def fake_vault(self):
        stored = {}

        class Vault:
            store = type("S", (), {"name": "fake"})()

            def set(self, key_id, key):
                stored[key_id] = key

        return stored, Vault

    def test_add_stores_the_keys_under_the_profile_through_a_hidden_prompt(self):
        stored, vault = self.fake_vault()
        with mock.patch("npdev_providers.Vault", vault), mock.patch("getpass.getpass", side_effect=[SECRET + "g", SECRET + "r"]) as ask:
            code, text = self.run_cli("users", "add", "irmao", "--key", "gemini", "--key", "groq")
        self.assertEqual(code, 0, text)
        self.assertEqual(stored, {"gemini:irmao": SECRET + "g", "groq:irmao": SECRET + "r"})
        self.assertNotIn(SECRET, text)   # the answer never echoes a key
        self.assertIn("irmao", json.loads(self.run_cli("users")[1])["profiles"][0]["id"])
        self.assertEqual(ask.call_count, 2)

    def test_an_unknown_provider_creates_nothing_and_asks_nothing(self):
        stored, vault = self.fake_vault()
        with mock.patch("npdev_providers.Vault", vault), mock.patch("getpass.getpass") as ask:
            code, text = self.run_cli("users", "add", "irmao", "--key", "no-such-provider")
        self.assertEqual(code, 2, text)
        ask.assert_not_called()
        self.assertEqual((stored, json.loads(self.run_cli("users")[1])["profiles"]), ({}, []))

    def test_an_empty_key_is_refused_and_the_profile_is_not_created(self):
        class Vault:
            def set(self, key_id, key):
                raise npdev_providers.ProviderError("the key is empty")

        with mock.patch("npdev_providers.Vault", Vault), mock.patch("getpass.getpass", return_value=""):
            code, _ = self.run_cli("users", "add", "irmao", "--key", "gemini")
        self.assertEqual(code, 2)
        self.assertEqual(json.loads(self.run_cli("users")[1])["profiles"], [])

    def test_key_replaces_one_key_of_an_existing_profile_and_needs_the_profile(self):
        stored, vault = self.fake_vault()
        with mock.patch("npdev_providers.Vault", vault), mock.patch("getpass.getpass", return_value=SECRET):
            self.assertEqual(self.run_cli("users", "key", "ghost", "gemini")[0], 2)
            self.run_cli("users", "add", "irmao")
            self.assertEqual(self.run_cli("users", "key", "irmao", "gemini")[0], 0)
        self.assertEqual(stored, {"gemini:irmao": SECRET})

    def test_an_error_exits_2_with_json(self):
        code, text = self.run_cli("users", "use", "ghost")
        self.assertEqual(code, 2)
        self.assertFalse(json.loads(text)["ok"])


class ClientTests(Isolated):
    """The AI client asks the profiles which account to use, and never falls back to somebody else's key."""

    def config(self):
        shipped = json.loads(aicfg.SHIPPED_PATH.read_text(encoding="utf-8"))
        return aicfg.merge(shipped, {"schemaVersion": aicfg.SCHEMA_VERSION})

    def complete(self, feature, keys, answers, **kwargs):
        seen, opener_calls = [], []

        def resolver(provider_id, account=None):
            seen.append((provider_id, account))
            return keys.get((provider_id, account))

        def opener(request, timeout=None):
            opener_calls.append(request)
            answer = answers.pop(0)
            if isinstance(answer, Exception):
                raise answer

            class _R:
                status = 200
                def __enter__(s): return s
                def __exit__(s, *a): return False
                def read(s): return json.dumps(answer).encode()

            return _R()

        usage = client.UsageLog(self.dir / "usage.jsonl")
        result = client.complete(self.config(), "hi", profile="fast", feature=feature, key_resolver=resolver,
                                 usage=usage, opener=opener, sleep=lambda s: None, backoff=(), **kwargs)
        return result, seen, usage

    def write(self, document):
        users.write(document)

    def test_a_role_pays_with_its_owners_key_and_the_log_names_the_account(self):
        self.write(doc(irmao=["bench"]))
        keys = {("gemini", "irmao"): SECRET + "irmao", ("gemini", None): SECRET + "main"}
        result, seen, usage = self.complete("bench", keys, [OPENAI_OK])
        self.assertTrue(result["ok"], result)
        self.assertEqual(seen, [("gemini", "irmao")])
        self.assertEqual((result["account"], usage.records()[0]["account"]), ("irmao", "irmao"))
        self.assertNotIn(SECRET, json.dumps(result) + json.dumps(usage.records()))

    def test_no_key_for_the_owner_never_uses_the_main_key(self):
        self.write(doc(irmao=["bench"]))
        keys = {("gemini", None): SECRET, ("groq", "irmao"): SECRET}   # the main gemini key exists, the owner's does not
        result, seen, _ = self.complete("bench", keys, [OPENAI_OK])
        self.assertEqual(seen, [("gemini", "irmao"), ("groq", "irmao")])
        self.assertIn("--account irmao", result["attempts"][0]["error"])
        self.assertEqual((result["provider"], result["account"]), ("groq", "irmao"))

    def test_a_quota_error_stays_inside_the_owners_account(self):
        self.write(doc(irmao=["bench"]))
        keys = {("gemini", "irmao"): SECRET, ("groq", "irmao"): SECRET}
        result, seen, _ = self.complete("bench", keys, [urllib.error.HTTPError("https://x", 429, "q", {}, io.BytesIO(b"")), OPENAI_OK])
        self.assertEqual([a for _, a in seen], ["irmao", "irmao"])   # the fallback is still his; marcelo's quota is untouched

    def test_without_profiles_nothing_changes(self):
        result, seen, _ = self.complete("bench", {("gemini", None): SECRET}, [OPENAI_OK])
        self.assertEqual(seen, [("gemini", None)])
        self.assertNotIn("account", result)

    def test_an_explicit_account_beats_the_profiles(self):
        document = {**doc(irmao=[]), "active": "irmao"}
        self.write(document)
        result, seen, _ = self.complete("bench", {("gemini", "other"): SECRET}, [OPENAI_OK], account="other")
        self.assertEqual(seen, [("gemini", "other")])

    def test_a_broken_profiles_file_stops_the_call(self):
        (self.dir / "user-profiles.json").write_text("{not json", encoding="utf-8")
        with self.assertRaises(client.AiCallError):
            self.complete("bench", {}, [])


if __name__ == "__main__":
    unittest.main()
