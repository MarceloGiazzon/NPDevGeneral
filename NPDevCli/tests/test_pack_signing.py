"""Tests for R8.7's pack signing + trust policy (npdev_cli.py: the `_ed25519_*` primitives and
`ed25519_generate_seed`/`ed25519_public_key`/`ed25519_sign`/`ed25519_verify`, `_load_pack_trust_config`,
`_parse_git_coordinate`, `_fetch_pack_signature`, `run_pack_sign_keygen`, `_verify_pack_signatures`,
and `_push_pack_to_catalog`'s new `--sign-with` handling).

PREMISE CHECKED BEFORE WRITING ANY OF THIS (see npdev_cli.py's own R8.7 module comment for the
full detail):
  1. A pack fetched from a public repo was verified only by the R8.6 content digest -- no
     authenticity check existed anywhere. TRUE (grepped RemotePackFetcher/PackCache/PackLockFile).
  2. No `trust` config existed anywhere in the pack pipeline. TRUE (grepped this module and the
     Java pack package for "trust"/"signature"/"signing" before writing any of this).
  3. npdev.lock can carry a new field without breaking existing locks. TRUE:
     `PackLockFile.read` only ever reads its five named fields via Jackson `JsonNode.get(...)`; an
     extra sibling key is silently ignored, never rejected. `PackLockFile.write` unconditionally
     REWRITES the file from those five fields on every add/update, which is why
     `_verify_pack_signatures` re-adds `signature` as a Python-owned overlay AFTER every Java
     fetch -- the same pattern R8.6's `_guard_against_remote_pack_tamper` already established.

No cryptography/pynacl (or any other signing library) is installed in this repo's toolchain; the
Ed25519 primitives here are a pure-stdlib port (hashlib.sha512 + Python's arbitrary-precision
int/pow), so `Ed25519PrimitiveSelfTest` below is this port's OWN correctness proof -- not a
convenience, load-bearing: many random keys/messages round-tripping sign->verify, tamper/wrong-key/
wrong-signature rejection, and the base point's own order (`l*BASE == identity`).

Three tiers:
  - Ed25519PrimitiveSelfTest / GitCoordinateParseUnitTest / PackTrustConfigUnitTest /
    PackSignKeygenUnitTest / VerifyPackSignaturesUnitTest: fast, no git/Gradle -- exercise the
    primitives and `_verify_pack_signatures` directly against synthetic lock/trust state (the
    remote signature fetch is monkeypatched, matching how `GuardAgainstRemotePackTamperUnitTest`
    tests R8.6's guard against synthetic before/after text with no real fetch).
  - PushSigningUnitTest: fast, `_run_git` mocked (same technique `PushImmutabilityUnitTest`
    already uses) -- proves `--sign-with` writes a real, independently-verifiable detached
    signature file at `signatures/sha256/<digest>.sig` and includes it in `git add`.
  - PackSigningRoundTripTest: real end-to-end (~20-40s per test: real git, real Gradle-backed PK-5
    fetch), a throwaway BARE repo standing in for the catalog remote -- never
    github.com/MarceloGiazzon/NPR. Proves the done-when for real: a signed pack from a trusted key
    verifies and is consumable; an unsigned pack is refused without --allow-unsigned; a pack signed
    by an untrusted key is refused; a forged/stale signature (a real tamper scenario -- an attacker
    who can move a git tag but does not hold the private key reuses an old signature file under the
    new digest's path) is refused as BAD_SIGNATURE.

Stdlib-only (unittest). Run with:
    python -m unittest NPDevCli.tests.test_pack_signing -v
"""

from __future__ import annotations

import argparse
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import npdev_cli  # noqa: E402


def _write(path: Path, content) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if isinstance(content, (dict, list)):
        content = json.dumps(content, indent=2)
    path.write_text(content, encoding="utf-8")


def _lock_text(packs: dict) -> str:
    return json.dumps({"schemaVersion": "npdev-lock.v1", "packs": packs}, indent=2) + "\n"


# -------------------------------------------------------------------------------------------------
# Ed25519 primitives: this port's own correctness proof.
# -------------------------------------------------------------------------------------------------

class Ed25519PrimitiveSelfTest(unittest.TestCase):
    def test_base_point_order(self):
        identity = npdev_cli._ed25519_scalarmult(npdev_cli._ED25519_BASE, npdev_cli._ED25519_L)
        self.assertEqual((0, 1), identity)

    def test_encode_decode_point_roundtrip(self):
        encoded = npdev_cli._ed25519_encodepoint(npdev_cli._ED25519_BASE)
        decoded = npdev_cli._ed25519_decodepoint(encoded)
        self.assertEqual(npdev_cli._ED25519_BASE, decoded)

    def test_sign_then_verify_round_trips_for_many_random_keys_and_messages(self):
        for trial in range(8):
            seed = os.urandom(32)
            public_key = npdev_cli.ed25519_public_key(seed)
            message = os.urandom(trial * 7)  # includes the empty message at trial 0
            signature = npdev_cli.ed25519_sign(message, seed, public_key)
            npdev_cli.ed25519_verify(signature, message, public_key)  # must not raise

    def test_tampered_message_is_rejected(self):
        seed = os.urandom(32)
        public_key = npdev_cli.ed25519_public_key(seed)
        signature = npdev_cli.ed25519_sign(b"sha256:original", seed, public_key)
        with self.assertRaises(ValueError):
            npdev_cli.ed25519_verify(signature, b"sha256:tampered", public_key)

    def test_wrong_public_key_is_rejected(self):
        seed = os.urandom(32)
        public_key = npdev_cli.ed25519_public_key(seed)
        other_public_key = npdev_cli.ed25519_public_key(os.urandom(32))
        signature = npdev_cli.ed25519_sign(b"sha256:x", seed, public_key)
        with self.assertRaises(ValueError):
            npdev_cli.ed25519_verify(signature, b"sha256:x", other_public_key)

    def test_tampered_signature_bytes_are_rejected(self):
        seed = os.urandom(32)
        public_key = npdev_cli.ed25519_public_key(seed)
        signature = bytearray(npdev_cli.ed25519_sign(b"sha256:x", seed, public_key))
        signature[-1] ^= 0x01
        with self.assertRaises(ValueError):
            npdev_cli.ed25519_verify(bytes(signature), b"sha256:x", public_key)

    def test_malformed_lengths_are_rejected_not_crashed(self):
        seed = os.urandom(32)
        public_key = npdev_cli.ed25519_public_key(seed)
        signature = npdev_cli.ed25519_sign(b"m", seed, public_key)
        with self.assertRaises(ValueError):
            npdev_cli.ed25519_verify(signature[:-1], b"m", public_key)
        with self.assertRaises(ValueError):
            npdev_cli.ed25519_verify(signature, b"m", public_key[:-1])


# -------------------------------------------------------------------------------------------------
# git+ coordinate parsing (Python port of GitCoordinate.parse).
# -------------------------------------------------------------------------------------------------

class GitCoordinateParseUnitTest(unittest.TestCase):
    def test_with_subpath(self):
        transport, repo_url, subpath, tag = npdev_cli._parse_git_coordinate(
            "git+https://example.com/catalog.git//packs/widgets@v1.0.0")
        self.assertEqual("https", transport)
        self.assertEqual("example.com/catalog.git", repo_url)
        self.assertEqual("packs/widgets", subpath)
        self.assertEqual("v1.0.0", tag)

    def test_without_subpath(self):
        transport, repo_url, subpath, tag = npdev_cli._parse_git_coordinate(
            "git+file:///D:/x/y@v2.0.0")
        self.assertEqual("file", transport)
        self.assertEqual("", subpath)
        self.assertEqual("v2.0.0", tag)

    def test_last_at_is_the_tag_delimiter_not_a_user_at_host(self):
        transport, repo_url, subpath, tag = npdev_cli._parse_git_coordinate(
            "git+ssh://git@host/repo@v1")
        self.assertEqual("ssh", transport)
        self.assertEqual("git@host/repo", repo_url)
        self.assertEqual("v1", tag)

    def test_missing_scheme_is_refused(self):
        with self.assertRaises(npdev_cli.CliError):
            npdev_cli._parse_git_coordinate("git+nowhere@v1")

    def test_bad_transport_is_refused(self):
        with self.assertRaises(npdev_cli.CliError):
            npdev_cli._parse_git_coordinate("git+ftp://example.com/x@v1")

    def test_missing_tag_is_refused(self):
        with self.assertRaises(npdev_cli.CliError):
            npdev_cli._parse_git_coordinate("git+https://example.com/x")

    def test_not_git_plus_is_refused(self):
        with self.assertRaises(npdev_cli.CliError):
            npdev_cli._parse_git_coordinate("oci://example.com/x:v1")


# -------------------------------------------------------------------------------------------------
# npdev-trust.json.
# -------------------------------------------------------------------------------------------------

class PackTrustConfigUnitTest(unittest.TestCase):
    def test_missing_file_is_the_safe_default(self):
        with tempfile.TemporaryDirectory() as tmp:
            model_path = Path(tmp) / "model.json"
            trust = npdev_cli._load_pack_trust_config(model_path)
            self.assertEqual("warn", trust["mode"])
            self.assertEqual({}, trust["trustedKeys"])

    def test_valid_file_is_read(self):
        with tempfile.TemporaryDirectory() as tmp:
            model_path = Path(tmp) / "model.json"
            _write(Path(tmp) / npdev_cli.PACK_TRUST_FILE_NAME, {
                "mode": "enforce", "trustedKeys": {"abc123": "de" * 32},
            })
            trust = npdev_cli._load_pack_trust_config(model_path)
            self.assertEqual("enforce", trust["mode"])
            self.assertEqual({"abc123": "de" * 32}, trust["trustedKeys"])

    def test_invalid_mode_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            model_path = Path(tmp) / "model.json"
            _write(Path(tmp) / npdev_cli.PACK_TRUST_FILE_NAME, {"mode": "yolo"})
            with self.assertRaises(npdev_cli.CliError):
                npdev_cli._load_pack_trust_config(model_path)

    def test_non_object_trusted_keys_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            model_path = Path(tmp) / "model.json"
            _write(Path(tmp) / npdev_cli.PACK_TRUST_FILE_NAME, {"trustedKeys": ["not", "a", "dict"]})
            with self.assertRaises(npdev_cli.CliError):
                npdev_cli._load_pack_trust_config(model_path)

    def test_malformed_json_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            model_path = Path(tmp) / "model.json"
            (Path(tmp) / npdev_cli.PACK_TRUST_FILE_NAME).write_text("{not json", encoding="utf-8")
            with self.assertRaises(npdev_cli.CliError):
                npdev_cli._load_pack_trust_config(model_path)


# -------------------------------------------------------------------------------------------------
# `npdev pack sign-keygen`.
# -------------------------------------------------------------------------------------------------

class PackSignKeygenUnitTest(unittest.TestCase):
    def test_writes_a_usable_keyfile(self):
        with tempfile.TemporaryDirectory() as tmp:
            out_path = Path(tmp) / "key.json"
            args = argparse.Namespace(out=str(out_path))
            with redirect_stdout(io.StringIO()) as out:
                code = npdev_cli.run_pack_sign_keygen(args)
            self.assertEqual(0, code)
            self.assertTrue(out_path.is_file())
            record = json.loads(out_path.read_text(encoding="utf-8"))
            for field in ("keyId", "privateKey", "publicKey"):
                self.assertIn(field, record)
            self.assertEqual(64, len(record["privateKey"]))  # 32 bytes hex
            self.assertEqual(64, len(record["publicKey"]))
            # The printed stdout must carry the PUBLIC half + keyId (what a consumer needs), and
            # must be the same public key that was written to disk. stdout also carries a trailing
            # plain-text line after the JSON block, so parse just the first JSON value.
            printed, _rest = json.JSONDecoder().raw_decode(out.getvalue())
            self.assertEqual(record["keyId"], printed["keyId"])
            self.assertEqual(record["publicKey"], printed["publicKey"])
            self.assertNotIn(record["privateKey"], out.getvalue(), "the raw private key hex must never be printed")

            # The keyfile must actually be usable: sign+verify with what was written.
            seed = bytes.fromhex(record["privateKey"])
            public_key = bytes.fromhex(record["publicKey"])
            signature = npdev_cli.ed25519_sign(b"sha256:x", seed, public_key)
            npdev_cli.ed25519_verify(signature, b"sha256:x", public_key)  # must not raise

    def test_key_id_is_derived_from_the_public_key(self):
        with tempfile.TemporaryDirectory() as tmp:
            out_path = Path(tmp) / "key.json"
            args = argparse.Namespace(out=str(out_path))
            with redirect_stdout(io.StringIO()):
                npdev_cli.run_pack_sign_keygen(args)
            record = json.loads(out_path.read_text(encoding="utf-8"))
            import hashlib
            expected_key_id = hashlib.sha256(bytes.fromhex(record["publicKey"])).hexdigest()[:16]
            self.assertEqual(expected_key_id, record["keyId"])


# -------------------------------------------------------------------------------------------------
# `_verify_pack_signatures` -- the three named refusals, and the two accepted outcomes.
# `_fetch_pack_signature` and `_load_pack_trust_config` are monkeypatched: this class tests the
# DECISION logic, not the network/git fetch (that is `PackSigningRoundTripTest`'s job below).
# -------------------------------------------------------------------------------------------------

class VerifyPackSignaturesUnitTest(unittest.TestCase):
    def _keypair(self):
        seed = os.urandom(32)
        public_key = npdev_cli.ed25519_public_key(seed)
        return seed, public_key

    def _model_and_lock(self, tmp: Path, packs: dict) -> Path:
        model_path = tmp / "model.json"
        lock_path = tmp / npdev_cli.PACK_LOCK_FILE_NAME
        lock_path.write_text(_lock_text(packs), encoding="utf-8")
        return model_path

    def test_local_pack_with_no_from_is_never_checked(self):
        with tempfile.TemporaryDirectory() as tmp:
            model_path = self._model_and_lock(Path(tmp), {
                "local": {"resolvedVersion": "1.0.0", "digest": "sha256:aa", "sourcePath": "x"},
            })
            with mock.patch.object(npdev_cli, "_fetch_pack_signature") as fetch:
                npdev_cli._verify_pack_signatures(model_path, None, argparse.Namespace())
                fetch.assert_not_called()

    def test_unsigned_without_allow_unsigned_is_refused_and_lock_restored(self):
        with tempfile.TemporaryDirectory() as tmp:
            before_text = _lock_text({})
            packs = {"widgets": {"resolvedVersion": "1.0.0", "digest": "sha256:aa", "sourcePath": "x",
                                  "from": "git+https://example.com/w.git//p@v1.0.0"}}
            model_path = self._model_and_lock(Path(tmp), packs)
            lock_path = Path(tmp) / npdev_cli.PACK_LOCK_FILE_NAME
            with mock.patch.object(npdev_cli, "_fetch_pack_signature", return_value=None):
                with self.assertRaises(npdev_cli.CliError) as ctx:
                    npdev_cli._verify_pack_signatures(
                        model_path, before_text, argparse.Namespace(allow_unsigned=False))
            self.assertIn("UNSIGNED", str(ctx.exception))
            self.assertIn("widgets", str(ctx.exception))
            self.assertEqual(before_text, lock_path.read_text(encoding="utf-8"))

    def test_unsigned_with_allow_unsigned_is_accepted_and_recorded_in_the_lock(self):
        with tempfile.TemporaryDirectory() as tmp:
            packs = {"widgets": {"resolvedVersion": "1.0.0", "digest": "sha256:aa", "sourcePath": "x",
                                  "from": "git+https://example.com/w.git//p@v1.0.0"}}
            model_path = self._model_and_lock(Path(tmp), packs)
            lock_path = Path(tmp) / npdev_cli.PACK_LOCK_FILE_NAME
            with mock.patch.object(npdev_cli, "_fetch_pack_signature", return_value=None):
                npdev_cli._verify_pack_signatures(
                    model_path, _lock_text(packs), argparse.Namespace(allow_unsigned=True))
            written = json.loads(lock_path.read_text(encoding="utf-8"))
            sig = written["packs"]["widgets"]["signature"]
            self.assertEqual("unsigned", sig["status"])
            self.assertTrue(sig["allowedUnsigned"])

    def test_unsigned_in_enforce_mode_is_refused_even_with_allow_unsigned(self):
        with tempfile.TemporaryDirectory() as tmp:
            packs = {"widgets": {"resolvedVersion": "1.0.0", "digest": "sha256:aa", "sourcePath": "x",
                                  "from": "git+https://example.com/w.git//p@v1.0.0"}}
            model_path = self._model_and_lock(Path(tmp), packs)
            _write(Path(tmp) / npdev_cli.PACK_TRUST_FILE_NAME, {"mode": "enforce"})
            before_text = _lock_text(packs)
            with mock.patch.object(npdev_cli, "_fetch_pack_signature", return_value=None):
                with self.assertRaises(npdev_cli.CliError) as ctx:
                    npdev_cli._verify_pack_signatures(
                        model_path, before_text, argparse.Namespace(allow_unsigned=True))
            self.assertIn("UNSIGNED", str(ctx.exception))
            self.assertIn("enforce", str(ctx.exception))

    def test_unknown_signer_is_refused_and_not_bypassable_by_allow_unsigned(self):
        with tempfile.TemporaryDirectory() as tmp:
            seed, public_key = self._keypair()
            digest = "sha256:aa"
            signature = npdev_cli.ed25519_sign(digest.encode("utf-8"), seed, public_key)
            packs = {"widgets": {"resolvedVersion": "1.0.0", "digest": digest, "sourcePath": "x",
                                  "from": "git+https://example.com/w.git//p@v1.0.0"}}
            model_path = self._model_and_lock(Path(tmp), packs)
            before_text = _lock_text(packs)
            sig_record = {"keyId": "someone-else", "signature": signature.hex()}
            with mock.patch.object(npdev_cli, "_fetch_pack_signature", return_value=sig_record):
                with self.assertRaises(npdev_cli.CliError) as ctx:
                    npdev_cli._verify_pack_signatures(
                        model_path, before_text, argparse.Namespace(allow_unsigned=True))
            self.assertIn("UNKNOWN_SIGNER", str(ctx.exception))
            self.assertIn("someone-else", str(ctx.exception))

    def test_bad_signature_is_refused_even_from_a_trusted_signer(self):
        with tempfile.TemporaryDirectory() as tmp:
            seed, public_key = self._keypair()
            digest = "sha256:aa"
            # Signed over a DIFFERENT digest -- simulates a forged/stale signature (see
            # PackSigningRoundTripTest's live tamper proof for the real-git version of this).
            stale_signature = npdev_cli.ed25519_sign(b"sha256:not-this-one", seed, public_key)
            packs = {"widgets": {"resolvedVersion": "1.0.0", "digest": digest, "sourcePath": "x",
                                  "from": "git+https://example.com/w.git//p@v1.0.0"}}
            model_path = self._model_and_lock(Path(tmp), packs)
            before_text = _lock_text(packs)
            _write(Path(tmp) / npdev_cli.PACK_TRUST_FILE_NAME,
                   {"trustedKeys": {"trusted-key": public_key.hex()}})
            sig_record = {"keyId": "trusted-key", "signature": stale_signature.hex()}
            lock_path = Path(tmp) / npdev_cli.PACK_LOCK_FILE_NAME
            with mock.patch.object(npdev_cli, "_fetch_pack_signature", return_value=sig_record):
                with self.assertRaises(npdev_cli.CliError) as ctx:
                    npdev_cli._verify_pack_signatures(model_path, before_text, argparse.Namespace())
            self.assertIn("BAD_SIGNATURE", str(ctx.exception))
            self.assertEqual(before_text, lock_path.read_text(encoding="utf-8"),
                              "the lock must be restored, never left trusting a bad signature")

    def test_verified_signature_is_accepted_and_recorded_in_the_lock(self):
        with tempfile.TemporaryDirectory() as tmp:
            seed, public_key = self._keypair()
            digest = "sha256:aa"
            signature = npdev_cli.ed25519_sign(digest.encode("utf-8"), seed, public_key)
            packs = {"widgets": {"resolvedVersion": "1.0.0", "digest": digest, "sourcePath": "x",
                                  "from": "git+https://example.com/w.git//p@v1.0.0"}}
            model_path = self._model_and_lock(Path(tmp), packs)
            _write(Path(tmp) / npdev_cli.PACK_TRUST_FILE_NAME,
                   {"trustedKeys": {"trusted-key": public_key.hex()}})
            sig_record = {"keyId": "trusted-key", "signature": signature.hex()}
            lock_path = Path(tmp) / npdev_cli.PACK_LOCK_FILE_NAME
            with mock.patch.object(npdev_cli, "_fetch_pack_signature", return_value=sig_record):
                npdev_cli._verify_pack_signatures(model_path, _lock_text(packs), argparse.Namespace())
            written = json.loads(lock_path.read_text(encoding="utf-8"))
            sig = written["packs"]["widgets"]["signature"]
            self.assertEqual("verified", sig["status"])
            self.assertEqual("trusted-key", sig["keyId"])

    def test_fetch_failure_propagates_as_named_cli_error_not_swallowed_as_unsigned(self):
        with tempfile.TemporaryDirectory() as tmp:
            packs = {"widgets": {"resolvedVersion": "1.0.0", "digest": "sha256:aa", "sourcePath": "x",
                                  "from": "git+https://example.com/w.git//p@v1.0.0"}}
            model_path = self._model_and_lock(Path(tmp), packs)
            with mock.patch.object(npdev_cli, "_fetch_pack_signature",
                                    side_effect=npdev_cli.CliError("git clone failed: boom")):
                with self.assertRaises(npdev_cli.CliError) as ctx:
                    npdev_cli._verify_pack_signatures(model_path, None, argparse.Namespace())
            self.assertIn("boom", str(ctx.exception))


# -------------------------------------------------------------------------------------------------
# `pack publish --push --sign-with`: `_run_git` mocked (same technique `PushImmutabilityUnitTest`
# in test_pack_publish_push.py already uses) -- proves the detached signature file itself, without
# needing a real git repo.
# -------------------------------------------------------------------------------------------------

class PushSigningUnitTest(unittest.TestCase):
    def test_sign_with_writes_a_verifiable_detached_signature_and_stages_it_for_git_add(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            catalog_repo = tmp_dir / "catalog-repo"
            (catalog_repo / ".git").mkdir(parents=True)
            new_pack_path = tmp_dir / "widgets.json"
            _write(new_pack_path, {"dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.0",
                                    "concepts": [{"name": "Widget", "fields": [
                                        {"name": "id", "type": "uuid", "id": True, "required": True}]}]})
            key_path = tmp_dir / "key.json"
            with redirect_stdout(io.StringIO()):
                npdev_cli.run_pack_sign_keygen(argparse.Namespace(out=str(key_path)))
            key_record = json.loads(key_path.read_text(encoding="utf-8"))

            args = argparse.Namespace(catalog_repo=str(catalog_repo), remote="origin",
                                       repository_url="https://example.com/catalog", push=False,
                                       sign_with=str(key_path))
            with mock.patch.object(npdev_cli, "_run_git") as run_git:
                run_git.return_value = mock.Mock(stdout="deadbeef\n")
                result = npdev_cli._push_pack_to_catalog(new_pack_path, args)

            self.assertTrue(result["signed"])
            digest = npdev_cli._pack_content_digest_of_dir(catalog_repo / "packs" / "widgets")
            digest_hex = digest.split(":", 1)[1]
            sig_path = catalog_repo / "signatures" / "sha256" / f"{digest_hex}.sig"
            self.assertTrue(sig_path.is_file())
            sig_record = json.loads(sig_path.read_text(encoding="utf-8"))
            self.assertEqual(key_record["keyId"], sig_record["keyId"])
            npdev_cli.ed25519_verify(
                bytes.fromhex(sig_record["signature"]), digest.encode("utf-8"),
                bytes.fromhex(key_record["publicKey"]))  # must not raise

            add_calls = [c for c in run_git.call_args_list if c.args[0][0] == "add"]
            self.assertEqual(1, len(add_calls))
            self.assertIn(f"signatures/sha256/{digest_hex}.sig", add_calls[0].args[0])

    def test_no_sign_with_writes_no_signature_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            catalog_repo = tmp_dir / "catalog-repo"
            (catalog_repo / ".git").mkdir(parents=True)
            new_pack_path = tmp_dir / "widgets.json"
            _write(new_pack_path, {"dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.0",
                                    "concepts": [{"name": "Widget", "fields": [
                                        {"name": "id", "type": "uuid", "id": True, "required": True}]}]})
            args = argparse.Namespace(catalog_repo=str(catalog_repo), remote="origin",
                                       repository_url="https://example.com/catalog", push=False)
            with mock.patch.object(npdev_cli, "_run_git") as run_git:
                run_git.return_value = mock.Mock(stdout="deadbeef\n")
                result = npdev_cli._push_pack_to_catalog(new_pack_path, args)
            self.assertFalse(result["signed"])
            self.assertFalse((catalog_repo / "signatures").exists())

    def test_sign_with_without_push_is_refused_by_run_pack_publish(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            old_pack = tmp_dir / "old.json"
            new_pack = tmp_dir / "new.json"
            body = {"dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.0",
                    "concepts": [{"name": "Widget", "fields": [
                        {"name": "id", "type": "uuid", "id": True, "required": True}]}]}
            _write(old_pack, body)
            _write(new_pack, body)
            args = argparse.Namespace(old_pack=str(old_pack), new_pack=str(new_pack), out=None,
                                       write=False, push=False, sign_with="some-key.json")
            with self.assertRaises(npdev_cli.CliError) as ctx:
                npdev_cli.run_pack_publish(args)
            self.assertIn("--sign-with requires --push", str(ctx.exception))


# -------------------------------------------------------------------------------------------------
# Real end-to-end: bare git repo (the catalog "remote") -> signed pack publish --push -> a fresh
# `pack add` verifies and consumes it; unsigned/untrusted/forged variants are refused for real.
# -------------------------------------------------------------------------------------------------

def _run(cwd: Path, *cmd: str) -> subprocess.CompletedProcess:
    result = subprocess.run(list(cmd), cwd=str(cwd), capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(f"{cmd} failed in {cwd}: {result.stdout}\n{result.stderr}")
    return result


@unittest.skipUnless(shutil.which("git"), "git not on PATH")
class PackSigningRoundTripTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp_dir = Path(self._tmp.name)
        self.addCleanup(self._tmp.cleanup)

        self.bare = self.tmp_dir / "catalog-remote.git"
        self.bare.mkdir()
        _run(self.bare, "git", "init", "--quiet", "--bare", "--initial-branch=main")

        self.clone = self.tmp_dir / "catalog-clone"
        _run(self.tmp_dir, "git", "clone", "--quiet", str(self.bare), str(self.clone))
        _write(self.clone / "README.md", "catalog\n")
        _run(self.clone, "git", "-c", "user.name=t", "-c", "user.email=t@example.com", "add", "-A")
        _run(self.clone, "git", "-c", "user.name=t", "-c", "user.email=t@example.com",
             "commit", "--quiet", "-m", "seed")
        _run(self.clone, "git", "push", "--quiet", "origin", "main")
        self.repository_url = self.bare.resolve().as_uri()

        self.key_path = self.tmp_dir / "signing-key.json"
        with redirect_stdout(io.StringIO()):
            npdev_cli.run_pack_sign_keygen(argparse.Namespace(out=str(self.key_path)))
        self.key_record = json.loads(self.key_path.read_text(encoding="utf-8"))

    def _coordinate_for(self, pack_id: str, tag: str) -> str:
        """Same stripping `_push_pack_to_catalog` itself applies before building its own
        `coordinate` -- `self.bare` is already named `catalog-remote.git`, so `self.repository_url`
        already ends in `.git`; naively appending a second `.git` (as an earlier version of this
        test did) produces `catalog-remote.git.git`, which git correctly refuses to clone."""
        stripped = self.repository_url.rstrip("/")
        if stripped.endswith(".git"):
            stripped = stripped[: -len(".git")]
        return f"git+{stripped}.git//packs/{pack_id}@{tag}"

    def _publish_args(self, old_pack: Path, new_pack: Path, *, sign_with: str | None) -> argparse.Namespace:
        return argparse.Namespace(
            old_pack=str(old_pack), new_pack=str(new_pack), out=None, write=False,
            push=True, catalog_repo=str(self.clone), repository_url=self.repository_url,
            tag_template="v{version}", remote="origin", branch=None,
            git_user_name="npdev-test", git_user_email="npdev-test@example.com",
            sign_with=sign_with,
        )

    def _publish(self, pack_id: str, version: str, *, sign: bool, extra_field: str | None = None) -> None:
        old_pack = self.tmp_dir / f"{pack_id}-old.json"
        new_pack = self.tmp_dir / f"{pack_id}-new.json"
        fields = [{"name": "id", "type": "uuid", "id": True, "required": True}]
        if extra_field:
            fields.append({"name": extra_field, "type": "string", "required": False})
        _write(old_pack, {"dslVersion": "1.0.0", "pack": pack_id, "version": "0.0.1",
                           "concepts": [{"name": "Thing", "fields": fields[:1]}]})
        _write(new_pack, {"dslVersion": "1.0.0", "pack": pack_id, "version": version,
                           "concepts": [{"name": "Thing", "fields": fields}]})
        with redirect_stdout(io.StringIO()) as out:
            code = npdev_cli.run_pack_publish(
                self._publish_args(old_pack, new_pack, sign_with=str(self.key_path) if sign else None))
        self.assertEqual(0, code, out.getvalue())

    def _consume(self, app_dir: Path, coordinate: str, *, allow_unsigned: bool,
                 trust_config: dict | None = None):
        model_path = app_dir / "model.json"
        _write(model_path, {"namespace": f"npdev.throwaway.r87.{app_dir.name}", "dslVersion": "1.0.0",
                             "version": "1.0", "packs": [{"from": coordinate}]})
        if trust_config is not None:
            _write(app_dir / npdev_cli.PACK_TRUST_FILE_NAME, trust_config)
        env = {"NPDEV_PACK_CACHE_ROOT": str(self.tmp_dir / f"pack-cache-{app_dir.name}")}
        add_args = argparse.Namespace(model=str(model_path), from_catalog=None,
                                       allow_unsigned=allow_unsigned)
        with mock.patch.dict(os.environ, env):
            with redirect_stdout(io.StringIO()) as out:
                code = npdev_cli.run_pack_add(add_args)
        return code, out.getvalue(), model_path

    def test_signed_pack_from_a_trusted_key_is_verified_and_consumable(self):
        self._publish("honestwidgets", "1.0.0", sign=True)
        coordinate = self._coordinate_for("honestwidgets", "v1.0.0")
        app_dir = self.tmp_dir / "consumer-happy"
        code, out, model_path = self._consume(
            app_dir, coordinate, allow_unsigned=False,
            trust_config={"trustedKeys": {self.key_record["keyId"]: self.key_record["publicKey"]}})
        self.assertEqual(0, code, out)
        lock = json.loads((app_dir / "npdev.lock").read_text(encoding="utf-8"))
        sig = lock["packs"]["honestwidgets"]["signature"]
        self.assertEqual("verified", sig["status"])
        self.assertEqual(self.key_record["keyId"], sig["keyId"])

    def test_unsigned_pack_raises_named_cli_error_without_allow_unsigned(self):
        self._publish("unsignedwidgets2", "1.0.0", sign=False)
        coordinate = self._coordinate_for("unsignedwidgets2", "v1.0.0")
        app_dir = self.tmp_dir / "consumer-unsigned2"
        model_path = app_dir / "model.json"
        _write(model_path, {"namespace": "npdev.throwaway.r87.unsigned2", "dslVersion": "1.0.0",
                             "version": "1.0", "packs": [{"from": coordinate}]})
        env = {"NPDEV_PACK_CACHE_ROOT": str(self.tmp_dir / "pack-cache-unsigned2")}
        add_args = argparse.Namespace(model=str(model_path), from_catalog=None, allow_unsigned=False)
        with mock.patch.dict(os.environ, env):
            with self.assertRaises(npdev_cli.CliError) as ctx:
                with redirect_stdout(io.StringIO()):
                    npdev_cli.run_pack_add(add_args)
        self.assertIn("UNSIGNED", str(ctx.exception))

    def test_unsigned_pack_is_accepted_with_allow_unsigned_and_recorded_in_the_lock(self):
        self._publish("unsignedwidgets3", "1.0.0", sign=False)
        coordinate = self._coordinate_for("unsignedwidgets3", "v1.0.0")
        app_dir = self.tmp_dir / "consumer-unsigned3"
        code, out, model_path = self._consume(app_dir, coordinate, allow_unsigned=True)
        self.assertEqual(0, code, out)
        lock = json.loads((app_dir / "npdev.lock").read_text(encoding="utf-8"))
        sig = lock["packs"]["unsignedwidgets3"]["signature"]
        self.assertEqual("unsigned", sig["status"])
        self.assertTrue(sig["allowedUnsigned"])

    def test_signed_pack_from_an_untrusted_key_is_refused(self):
        self._publish("untrustedwidgets", "1.0.0", sign=True)
        coordinate = self._coordinate_for("untrustedwidgets", "v1.0.0")
        app_dir = self.tmp_dir / "consumer-untrusted"
        model_path = app_dir / "model.json"
        _write(model_path, {"namespace": "npdev.throwaway.r87.untrusted", "dslVersion": "1.0.0",
                             "version": "1.0", "packs": [{"from": coordinate}]})
        # Deliberately NO npdev-trust.json -- the default trustedKeys is empty, so even a real,
        # valid signature is from an "unknown" signer.
        env = {"NPDEV_PACK_CACHE_ROOT": str(self.tmp_dir / "pack-cache-untrusted")}
        add_args = argparse.Namespace(model=str(model_path), from_catalog=None, allow_unsigned=True)
        with mock.patch.dict(os.environ, env):
            with self.assertRaises(npdev_cli.CliError) as ctx:
                with redirect_stdout(io.StringIO()):
                    npdev_cli.run_pack_add(add_args)
        self.assertIn("UNKNOWN_SIGNER", str(ctx.exception))

    def test_tampered_pack_with_forged_signature_is_refused_as_bad_signature(self):
        """The done-when's own "a tampered pack is refused with a named error": publish a signed
        pack, then simulate an attacker who can force-move the git tag but does NOT hold the
        private key -- they mutate pack.json and copy the OLD (still cryptographically valid, but
        now-mismatched) signature file to the NEW digest's path, hoping it reads as signed. A
        fresh consumer (no prior npdev.lock entry, so R8.6's own separate tamper guard never even
        engages) must refuse it as BAD_SIGNATURE, not silently accept a signature that does not
        match the content it is attached to.
        """
        self._publish("honeypotwidgets", "1.0.0", sign=True)

        # Read the real signature the honest publish just wrote, and the honest digest.
        honest_pack_dir = self.clone / "packs" / "honeypotwidgets"
        honest_digest = npdev_cli._pack_content_digest_of_dir(honest_pack_dir)
        honest_digest_hex = honest_digest.split(":", 1)[1]
        honest_sig_path = self.clone / "signatures" / "sha256" / f"{honest_digest_hex}.sig"
        self.assertTrue(honest_sig_path.is_file())
        honest_sig_bytes = honest_sig_path.read_bytes()

        # Mutate the pack content (a real content change -> a real new digest) and copy the OLD
        # signature file forward to the NEW digest's path, unchanged.
        pack_json_path = honest_pack_dir / "pack.json"
        mutated = json.loads(pack_json_path.read_text(encoding="utf-8"))
        mutated["concepts"][0]["fields"].append({"name": "backdoor", "type": "string", "required": False})
        _write(pack_json_path, mutated)
        mutated_digest = npdev_cli._pack_content_digest_of_dir(honest_pack_dir)
        mutated_digest_hex = mutated_digest.split(":", 1)[1]
        self.assertNotEqual(honest_digest_hex, mutated_digest_hex, "the mutation must change the digest")
        forged_sig_path = self.clone / "signatures" / "sha256" / f"{mutated_digest_hex}.sig"
        forged_sig_path.write_bytes(honest_sig_bytes)

        _run(self.clone, "git", "-c", "user.name=t", "-c", "user.email=t@example.com", "add", "-A")
        _run(self.clone, "git", "-c", "user.name=t", "-c", "user.email=t@example.com",
             "commit", "--quiet", "-m", "tamper: mutate content, forward-copy the old signature")
        _run(self.clone, "git", "tag", "-f", "v1.0.0")
        _run(self.clone, "git", "push", "--quiet", "--force", "origin", "main")
        _run(self.clone, "git", "push", "--quiet", "--force", "origin", "v1.0.0")

        coordinate = self._coordinate_for("honeypotwidgets", "v1.0.0")
        app_dir = self.tmp_dir / "consumer-tampered"
        model_path = app_dir / "model.json"
        _write(model_path, {"namespace": "npdev.throwaway.r87.tampered", "dslVersion": "1.0.0",
                             "version": "1.0", "packs": [{"from": coordinate}]})
        _write(app_dir / npdev_cli.PACK_TRUST_FILE_NAME,
               {"trustedKeys": {self.key_record["keyId"]: self.key_record["publicKey"]}})
        env = {"NPDEV_PACK_CACHE_ROOT": str(self.tmp_dir / "pack-cache-tampered")}
        add_args = argparse.Namespace(model=str(model_path), from_catalog=None, allow_unsigned=True)
        with mock.patch.dict(os.environ, env):
            with self.assertRaises(npdev_cli.CliError) as ctx:
                with redirect_stdout(io.StringIO()):
                    npdev_cli.run_pack_add(add_args)
        message = str(ctx.exception)
        self.assertIn("BAD_SIGNATURE", message)
        self.assertIn("honeypotwidgets", message)


if __name__ == "__main__":
    unittest.main()
