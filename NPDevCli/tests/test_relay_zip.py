"""npdev_relay_zip.read_zip against hostile archives built inside the tests (no binary is ever committed).

Run with: python -m unittest NPDevCli.tests.test_relay_zip -v
"""
from __future__ import annotations

import io
import os
import stat
import sys
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path
from unittest import mock

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

import npdev_relay_ingest as ri  # noqa: E402
import npdev_relay_zip as rz  # noqa: E402

POLICY = ri.load_policy()
MODEL = '{"concepts": [], "name": "demo"}'


def build(members, compression=zipfile.ZIP_DEFLATED) -> bytes:
    """members: (name or ZipInfo, content) pairs, content str or bytes."""
    buffer = io.BytesIO()
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")            # zipfile warns about duplicate names; that is the point
        with zipfile.ZipFile(buffer, "w", compression) as archive:
            for name, content in members:
                archive.writestr(name, content)
    return buffer.getvalue()


def patch_all(data: bytes, signature: bytes, offset: int, apply) -> bytes:
    out = bytearray(data)
    position = out.find(signature)
    while position != -1:
        apply(out, position + offset)
        position = out.find(signature, position + 1)
    return bytes(out)


def rename(data: bytes, old: bytes, new: bytes) -> bytes:
    """Same-length rename in the local header and the central directory (offsets and CRC stay valid)."""
    assert len(old) == len(new) and data.count(old) >= 2
    return data.replace(old, new)


def with_limits(**limits) -> dict:
    policy = dict(POLICY)
    policy["limits"] = dict(POLICY["limits"], **limits)
    return policy


def by_path(result) -> dict:
    return {e["path"]: e for e in result["entries"]}


def codes(result) -> list:
    return [f["code"] for f in result["findings"]]


class GoodArchiveTests(unittest.TestCase):
    def test_a_good_archive_is_read_in_memory(self):
        data = build([("model.json", MODEL), ("npdev-submission.json", '{"intent": "create"}'),
                      ("fragments/", b"")])
        result = rz.read_zip(data, POLICY, "mvp")
        self.assertEqual(result["findings"], [])
        entries = result["entries"]
        self.assertEqual([e["path"] for e in entries], ["model.json", "npdev-submission.json"])
        self.assertEqual([(e["status"], e["reason"]) for e in entries], [("allowed", None)] * 2)
        self.assertEqual(entries[0]["text"], MODEL)
        self.assertEqual(entries[0]["bytes"], len(MODEL))

    def test_entry_shape_is_stable(self):
        data = build([("model.json", MODEL), ("config.json", "{}")])
        for entry in rz.read_zip(data, POLICY, "mvp")["entries"]:
            self.assertEqual({"path", "rawPath", "status", "reason", "text", "bytes"} - set(entry), set())

    def test_prefixes_and_utf8_are_normalised_like_pasted_files(self):
        data = build([("definition/model.json", '{"nome": "José"}')])
        entry = rz.read_zip(data, POLICY, "mvp")["entries"][0]
        self.assertEqual((entry["path"], entry["status"]), ("model.json", "allowed"))
        self.assertIn("José", entry["text"])

    def test_phase_decides_between_allowed_quarantine_and_later_phase(self):
        data = build([("model.json", MODEL), ("web/app.js", "alert(1)"), ("fragments/a.json", "{}")])
        mvp = by_path(rz.read_zip(data, POLICY, "mvp"))
        self.assertEqual((mvp["web/app.js"]["status"], mvp["web/app.js"]["reason"]), ("denied", "later-phase"))
        self.assertEqual(mvp["fragments/a.json"]["status"], "denied")
        f4 = by_path(rz.read_zip(data, POLICY, "f4"))
        self.assertEqual((f4["web/app.js"]["status"], f4["web/app.js"]["reason"]), ("quarantine", "code"))
        self.assertEqual(f4["web/app.js"]["text"], "alert(1)")
        self.assertEqual(f4["fragments/a.json"]["status"], "allowed")

    def test_policy_refusals_are_not_hostile(self):
        data = build([("model.json", MODEL), ("config.json", "{}"), ("run.sh", "rm -rf x"), ("secrets/k.json", "{}"),
                      ("notes.txt", "hi")])
        result = rz.read_zip(data, POLICY, "f4")
        entries = by_path(result)
        self.assertEqual(entries["config.json"]["reason"], "config-file-denied")
        self.assertEqual(entries["run.sh"]["reason"], "executable")
        self.assertEqual(entries["secrets/k.json"]["reason"], "protected-directory")
        self.assertEqual(entries["notes.txt"]["reason"], "not-in-allowlist")
        self.assertTrue(all(e["text"] is None for p, e in entries.items() if p != "model.json"))
        self.assertEqual(result["findings"], [])
        self.assertEqual(entries["model.json"]["status"], "allowed")

    def test_path_and_file_object_sources(self):
        data = build([("model.json", MODEL)])
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "answer.zip"
            target.write_bytes(data)
            for source in (str(target), target):
                with self.subTest(source=type(source).__name__):
                    self.assertEqual(rz.read_zip(source, POLICY, "mvp")["entries"][0]["text"], MODEL)
            with open(target, "rb") as handle:
                self.assertEqual(rz.read_zip(handle, POLICY, "mvp")["entries"][0]["text"], MODEL)


class HostilePathTests(unittest.TestCase):
    def test_traversal_is_denied_and_the_good_member_survives(self):
        for name in ("../evil.json", "a/../../evil.json", "fragments/../../evil.json", "./../evil.json"):
            with self.subTest(name=name):
                result = rz.read_zip(build([(name, "{}"), ("model.json", MODEL)]), POLICY, "f4")
                evil, good = result["entries"]
                self.assertEqual((evil["status"], evil["reason"], evil["text"]), ("denied", "path-traversal", None))
                self.assertEqual(good["status"], "allowed")
                self.assertIn("zip-path-traversal", codes(result))

    def test_backslash_tricks_are_denied(self):
        data = rename(build([("XX/evil.json", "{}"), ("model.json", MODEL)]), b"XX/", b"..\\")
        result = rz.read_zip(data, POLICY, "f4")
        self.assertEqual(result["entries"][0]["reason"], "path-traversal")
        self.assertEqual(result["entries"][0]["text"], None)
        data = rename(build([("fragments/XX/a.json", "{}")]), b"XX/", b"..\\")
        self.assertEqual(rz.read_zip(data, POLICY, "f4")["entries"][0]["status"], "denied")

    def test_absolute_and_drive_like_names_are_denied(self):
        drive = "C" + ":" + "/Windows/model.json"
        drive_relative = "D" + ":" + "model.json"
        for name in ("/etc/model.json", drive, drive_relative, "\\\\server\\share\\model.json"):
            with self.subTest(name=name):
                result = rz.read_zip(build([(name, "{}")]), POLICY, "mvp")
                entry = result["entries"][0]
                self.assertEqual((entry["status"], entry["text"]), ("denied", None))
                self.assertIn(entry["reason"], ("absolute-path", "path-traversal"))
                self.assertTrue(any(f["severity"] == "error" for f in result["findings"]))

    def test_colons_inside_a_name_are_denied(self):
        stream = rz.read_zip(build([("model.json:stream", "{}")]), POLICY, "mvp")["entries"][0]
        self.assertEqual(stream["status"], "denied")
        inside = rz.read_zip(build([("web/c" + ":" + "x.html", "<p>")]), POLICY, "f4")
        self.assertEqual(inside["entries"][0]["reason"], "drive-like-name")
        self.assertIn("zip-drive-like-name", codes(inside))

    def test_control_characters_and_trailing_dots_are_denied(self):
        data = rename(build([("model.jsonXexe", "{}")]), b"model.jsonXexe", b"model.json\x00exe")
        entry = rz.read_zip(data, POLICY, "mvp")["entries"][0]
        self.assertEqual((entry["status"], entry["reason"]), ("denied", "bad-name"))
        self.assertNotIn("\x00", entry["path"])
        dotted = rz.read_zip(build([("web/sub./a.html", "<p>")]), POLICY, "f4")["entries"][0]
        self.assertEqual((dotted["status"], dotted["reason"]), ("denied", "bad-name"))

    def test_symlinks_are_denied_whatever_their_name(self):
        link = zipfile.ZipInfo("model.json")
        link.external_attr = (stat.S_IFLNK | 0o777) << 16
        data = build([(link, "../../etc/passwd"), ("npdev-submission.json", "{}")])
        result = rz.read_zip(data, POLICY, "mvp")
        first, second = result["entries"]
        self.assertEqual((first["status"], first["reason"], first["text"]), ("denied", "symlink", None))
        self.assertEqual(second["status"], "allowed")
        self.assertIn("zip-symlink", codes(result))

    def test_windows_reparse_points_and_special_files_are_denied(self):
        reparse = zipfile.ZipInfo("model.json")
        reparse.external_attr = 0x400
        device = zipfile.ZipInfo("npdev-submission.json")
        device.external_attr = (stat.S_IFCHR | 0o600) << 16
        result = rz.read_zip(build([(reparse, "x"), (device, "x")]), POLICY, "mvp")
        self.assertEqual([e["reason"] for e in result["entries"]], ["symlink", "special-file"])

    def test_plain_permission_bits_without_a_type_are_a_regular_file(self):
        plain = zipfile.ZipInfo("model.json")
        plain.external_attr = 0o644 << 16
        regular = zipfile.ZipInfo("npdev-submission.json")
        regular.external_attr = (stat.S_IFREG | 0o644) << 16
        entries = rz.read_zip(build([(plain, MODEL), (regular, "{}")]), POLICY, "mvp")["entries"]
        self.assertEqual([e["status"] for e in entries], ["allowed", "allowed"])

    def test_encrypted_members_are_denied_without_being_opened(self):
        data = build([("model.json", MODEL), ("npdev-submission.json", "{}")])
        data = patch_all(data, b"PK\x03\x04", 6, lambda out, i: out.__setitem__(i, out[i] | 1))
        data = patch_all(data, b"PK\x01\x02", 8, lambda out, i: out.__setitem__(i, out[i] | 1))
        result = rz.read_zip(data, POLICY, "mvp")
        self.assertEqual([(e["status"], e["reason"], e["text"]) for e in result["entries"]],
                         [("denied", "encrypted", None)] * 2)
        self.assertEqual(codes(result).count("zip-encrypted"), 2)


class NestingAndContentTests(unittest.TestCase):
    def test_nested_archives_are_denied_by_name_and_by_content(self):
        inner = build([("model.json", MODEL)])
        result = rz.read_zip(build([("inner.zip", inner), ("model.json", inner), ("npdev-submission.json", "{}")]),
                             POLICY, "mvp")
        by_name = by_path(result)
        self.assertEqual(by_name["inner.zip"]["reason"], "nested-archive")
        self.assertEqual(by_name["model.json"]["reason"], "nested-archive")
        self.assertEqual(by_name["npdev-submission.json"]["status"], "allowed")
        self.assertEqual(codes(result).count("zip-nested-archive"), 2)
        self.assertTrue(all(e["text"] is None for e in result["entries"][:2]))

    def test_binary_and_non_utf8_members_are_denied_with_a_warning(self):
        result = rz.read_zip(build([("model.json", b"\x89PNG\r\n\x1a\n\x00\x00\x00"),
                                    ("npdev-submission.json", b"\xff\xfe{\x00}\x00")]), POLICY, "mvp")
        self.assertEqual([(e["status"], e["reason"], e["text"]) for e in result["entries"]],
                         [("denied", "not-text", None)] * 2)
        self.assertEqual([(f["code"], f["severity"]) for f in result["findings"]], [("zip-not-text", "warning")] * 2)

    def test_invalid_utf8_without_nul_is_denied(self):
        entry = rz.read_zip(build([("model.json", b"{\"a\": \"\xe9\"}")]), POLICY, "mvp")["entries"][0]
        self.assertEqual((entry["status"], entry["reason"]), ("denied", "not-text"))

    def test_corrupt_member_is_denied_not_raised(self):
        data = bytearray(build([("model.json", MODEL * 5), ("npdev-submission.json", "{}")], zipfile.ZIP_STORED))
        position = data.find(MODEL.encode())
        data[position] ^= 0x55                                   # flips a stored byte: the CRC no longer matches
        result = rz.read_zip(bytes(data), POLICY, "mvp")
        first, second = result["entries"]
        self.assertEqual((first["status"], first["reason"]), ("denied", "unreadable"))
        self.assertEqual(second["status"], "allowed")
        self.assertIn("zip-entry-unreadable", codes(result))


class DuplicateTests(unittest.TestCase):
    def test_the_same_name_twice_is_refused_for_every_copy(self):
        result = rz.read_zip(build([("model.json", MODEL), ("model.json", '{"other": 1}'),
                                    ("npdev-submission.json", "{}")]), POLICY, "mvp")
        statuses = [(e["path"], e["status"], e["reason"]) for e in result["entries"]]
        self.assertEqual(statuses, [("model.json", "denied", "duplicate-entry"),
                                    ("model.json", "denied", "duplicate-entry"),
                                    ("npdev-submission.json", "allowed", None)])
        self.assertEqual(codes(result).count("zip-duplicate-entry"), 2)

    def test_names_that_collide_after_normalisation(self):
        for names in (("model.json", "definition/model.json"), ("model.json", "./model.json"),
                      ("model.json", "Input/model.json")):
            with self.subTest(names=names):
                result = rz.read_zip(build([(names[0], MODEL), (names[1], "{}")]), POLICY, "mvp")
                self.assertEqual([e["reason"] for e in result["entries"]], ["duplicate-entry"] * 2)

    def test_a_denied_twin_does_not_poison_the_good_member(self):
        link = zipfile.ZipInfo("model.json")
        link.external_attr = (stat.S_IFLNK | 0o777) << 16
        entries = rz.read_zip(build([(link, "target"), ("model.json", MODEL)]), POLICY, "mvp")["entries"]
        self.assertEqual([(e["status"], e["reason"]) for e in entries], [("denied", "symlink"), ("allowed", None)])


class LimitTests(unittest.TestCase):
    def test_a_member_over_the_entry_limit_is_denied_before_decompression(self):
        policy = with_limits(maxFileChars=100, maxAnswerChars=1000)
        result = rz.read_zip(build([("model.json", "x" * 101), ("npdev-submission.json", "{}")]), policy, "mvp")
        big, small = result["entries"]
        self.assertEqual((big["status"], big["reason"], big["text"]), ("denied", "entry-too-large", None))
        self.assertEqual(small["status"], "allowed")
        self.assertIn("zip-entry-too-large", codes(result))
        exact = rz.read_zip(build([("model.json", "x" * 100)]), policy, "mvp")["entries"][0]
        self.assertEqual(exact["status"], "allowed")

    def test_a_real_bomb_is_refused_by_its_declared_sizes_alone(self):
        payload = b"0" * 900_000
        data = build([("fragments/a.json", payload), ("fragments/b.json", payload), ("fragments/c.json", payload)])
        self.assertLess(len(data), 20_000)                     # a few KB that would unpack to 2.7 MB
        with mock.patch.object(zipfile.ZipFile, "open", side_effect=AssertionError("must not decompress")):
            result = rz.read_zip(data, POLICY, "f4")
        self.assertEqual([(e["status"], e["reason"], e["text"]) for e in result["entries"]],
                         [("denied", "zip-total-too-large", None)] * 3)
        bomb = [f for f in result["findings"] if f["code"] == "zip-bomb"]
        self.assertEqual(len(bomb), 1)
        self.assertEqual(bomb[0]["detail"]["stage"], "declared")

    def test_total_declared_size_counts_only_what_would_be_read(self):
        policy = with_limits(maxFileChars=100, maxAnswerChars=150)
        data = build([("model.json", "x" * 90), ("npdev-submission.json", "y" * 90), ("config.json", "z" * 90)])
        result = rz.read_zip(data, policy, "mvp")
        self.assertIn("zip-bomb", codes(result))                  # 180 declared > 150
        data = build([("model.json", "x" * 90), ("config.json", "z" * 90), ("run.sh", "q" * 90)])
        ok = rz.read_zip(data, policy, "mvp")                      # denied members are never decompressed
        self.assertEqual(ok["entries"][0]["status"], "allowed")
        self.assertNotIn("zip-bomb", codes(ok))

    def test_actual_bytes_are_guarded_even_when_headers_understate(self):
        policy = with_limits(maxFileChars=100, maxAnswerChars=150)
        data = build([("model.json", "a" * 60), ("npdev-submission.json", "b" * 60)])     # declares 120 <= 150
        with mock.patch.object(zipfile.ZipFile, "open", lambda self, info, *a, **k: io.BytesIO(b"x" * 90)):
            result = rz.read_zip(data, policy, "mvp")
        first, second = result["entries"]
        self.assertEqual(first["status"], "allowed")
        self.assertEqual((second["status"], second["reason"], second["text"]), ("denied", "zip-total-too-large", None))
        bomb = [f for f in result["findings"] if f["code"] == "zip-bomb"]
        self.assertEqual([f["detail"]["stage"] for f in bomb], ["read"])
        with mock.patch.object(zipfile.ZipFile, "open", lambda self, info, *a, **k: io.BytesIO(b"x" * 10_000)):
            result = rz.read_zip(data, policy, "mvp")
        self.assertEqual([e["reason"] for e in result["entries"]], ["entry-too-large"] * 2)

    def test_too_many_members(self):
        policy = with_limits(maxFiles=3)
        data = build([(f"fragments/{n}.json", "{}") for n in range(4)])
        result = rz.read_zip(data, policy, "f4")
        self.assertEqual(result["entries"], [])
        self.assertEqual(codes(result), ["zip-too-many-files"])
        exact = build([(f"fragments/{n}.json", "{}") for n in range(3)])
        self.assertEqual(len(rz.read_zip(exact, policy, "f4")["entries"]), 3)

    def test_the_shipped_member_limit(self):
        limit = POLICY["limits"]["maxFiles"]
        data = build([(f"fragments/{n}.json", "{}") for n in range(limit + 1)])
        result = rz.read_zip(data, POLICY, "f4")
        self.assertEqual((result["entries"], codes(result)), ([], ["zip-too-many-files"]))

    def test_directory_members_do_not_count(self):
        policy = with_limits(maxFiles=1)
        data = build([("fragments/", b""), ("fragments/sub/", b""), ("model.json", MODEL)])
        self.assertEqual(len(rz.read_zip(data, policy, "mvp")["entries"]), 1)

    def test_an_archive_over_the_container_limit_is_refused_unparsed(self):
        policy = with_limits(maxAnswerChars=100)
        result = rz.read_zip(b"PK\x03\x04" + b"0" * 70_000, policy, "mvp")
        self.assertEqual((result["entries"], codes(result)), ([], ["zip-too-large"]))


class BrokenArchiveTests(unittest.TestCase):
    def test_not_a_zip_is_reported_not_raised(self):
        for data in (b"", b"not a zip at all", build([("model.json", MODEL)])[:-10], b"PK\x05\x06" + b"\x00" * 3):
            with self.subTest(size=len(data)):
                result = rz.read_zip(data, POLICY, "mvp")
                self.assertEqual((result["entries"], codes(result)), ([], ["zip-unreadable"]))

    def test_missing_file_is_reported(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = rz.read_zip(Path(tmp) / "nope.zip", POLICY, "mvp")
        self.assertEqual((result["entries"], codes(result)), ([], ["zip-unreadable"]))


class NothingIsWrittenTests(unittest.TestCase):
    def test_reading_a_hostile_archive_touches_no_file(self):
        link = zipfile.ZipInfo("model.json")
        link.external_attr = (stat.S_IFLNK | 0o777) << 16
        data = build([("../escaped.json", "{}"), (link, "x"), ("a/../../b.json", "{}"), ("model.json", MODEL)])
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp) / "work"
            work.mkdir()
            before = os.getcwd()
            os.chdir(work)
            try:
                with mock.patch("builtins.open", side_effect=AssertionError("read_zip must not open files")):
                    result = rz.read_zip(data, POLICY, "f4")
            finally:
                os.chdir(before)
            self.assertEqual(sorted(p.name for p in Path(tmp).rglob("*")), ["work"])
        self.assertEqual([e["status"] for e in result["entries"]], ["denied", "denied", "denied", "allowed"])


if __name__ == "__main__":
    unittest.main()
