"""Reads a .zip the user brought back from a browser chat AI, without ever extracting it.

A chat AI that cannot paste many files offers one archive. An archive is untrusted input: the classic
attacks are a path that climbs out of the folder (`..`), an absolute or drive-like name, a symlink that
points somewhere else, an encrypted member, a nested archive, a zip bomb (a few KB that unpack to GBs) and
the same name twice with different content. `read_zip` is a PURE function: it reads members into memory
(never to disk, never past the limits), runs every member name through `npdev_relay_ingest.check_path`
(the same allowlist the pasted-text path uses) and returns what each member is. The caller decides what to
do with the result; nothing here writes a file.

Limits come from the ingest policy (`relay-ingest-policy.json`, "limits"): `maxFiles` (members),
`maxFileChars` (bytes of one member), `maxAnswerChars` (bytes of all members we would read, and, plus a small
allowance, of the archive itself). The size guard is applied twice: on what the headers DECLARE, before
anything is decompressed, and on the bytes ACTUALLY read.

Standard library only (the Manager ships a private Python without third-party packages).
"""
from __future__ import annotations

import io
import os
import stat
import zipfile
import zlib

import npdev_relay_ingest as relay_ingest

_ARCHIVE_ALLOWANCE = 65536          # headers + central directory on top of maxAnswerChars of content
_NESTED_MAGICS = (b"PK\x03\x04", b"PK\x05\x06", b"PK\x07\x08")
_WINDOWS_REPARSE_POINT = 0x400      # MS-DOS style attribute some tools set on a symbolic link
_MAX_NAME_REPORT = 200

# A denied reason in this set is a sign of a hostile or broken archive, not an ordinary policy refusal
# (a config file, a script, a path outside the allowlist): it also produces an error finding.
HOSTILE_REASONS = {
    "bad-name": "zip-bad-name", "path-traversal": "zip-path-traversal", "absolute-path": "zip-absolute-path",
    "drive-like-name": "zip-drive-like-name", "symlink": "zip-symlink", "special-file": "zip-special-file",
    "encrypted": "zip-encrypted", "duplicate-entry": "zip-duplicate-entry", "nested-archive": "zip-nested-archive",
    "entry-too-large": "zip-entry-too-large", "unreadable": "zip-entry-unreadable",
}   # (zip-total-too-large is reported once, as zip-bomb)


def _display_name(raw: str) -> str:
    cleaned = "".join(ch if ch.isprintable() else "?" for ch in raw)
    return cleaned[:_MAX_NAME_REPORT]


def _open_archive(source, max_bytes: int):
    """(ZipFile, close_callback). Raises zipfile.BadZipFile / OSError / ValueError for anything unreadable."""
    if isinstance(source, (bytes, bytearray, memoryview)):
        data = bytes(source)
        if len(data) > max_bytes:
            raise ValueError("zip-too-large")
        return zipfile.ZipFile(io.BytesIO(data)), lambda: None
    if hasattr(source, "read"):
        return zipfile.ZipFile(source), lambda: None
    path = os.fspath(source)
    if os.path.getsize(path) > max_bytes:
        raise ValueError("zip-too-large")
    handle = open(path, "rb")
    try:
        return zipfile.ZipFile(handle), handle.close
    except BaseException:
        handle.close()
        raise


def _classify(info: zipfile.ZipInfo, policy: dict, phase: str):
    """(normalised path, status, reason) from the member's header alone; nothing is decompressed."""
    raw = info.orig_filename
    if any(ord(ch) < 32 or ord(ch) == 127 for ch in raw):
        return _display_name(raw), "denied", "bad-name"
    mode = info.external_attr >> 16
    if stat.S_ISLNK(mode) or (info.external_attr & _WINDOWS_REPARSE_POINT):
        return _display_name(raw), "denied", "symlink"
    kind = stat.S_IFMT(mode)          # some tools store only permission bits: no type means a plain file
    if kind and kind != stat.S_IFREG:
        return _display_name(raw), "denied", "special-file"
    if info.flag_bits & 0x1:
        return _display_name(raw), "denied", "encrypted"
    if raw.lower().endswith(".zip"):
        return _display_name(raw), "denied", "nested-archive"
    path, status, reason = relay_ingest.check_path(raw, policy, phase)
    path = _display_name(path)
    if status == "denied":
        return path, status, reason
    segments = path.split("/")
    if ":" in path:                                   # a drive letter or an NTFS alternate data stream
        return path, "denied", "drive-like-name"
    if any(seg != seg.rstrip(" .") for seg in segments):   # Windows drops trailing dots and spaces
        return path, "denied", "bad-name"
    return path, status, reason


def read_zip(source, policy: dict, phase: str) -> dict:
    """Read an archive into memory and classify every member.

    `source` is a path (str or PathLike), the archive's bytes, or a seekable binary file object.
    Returns {"entries": [...], "findings": [...]}:

      entries   one per file member, in archive order (directory members are skipped):
                {"path": normalised name, "rawPath": name as stored (printable form), "status":
                 "allowed" | "quarantine" | "denied", "reason": None | str, "text": str | None,
                 "bytes": int}.
                `text` is the member decoded as UTF-8 and is set only when status is allowed or
                quarantine; a denied member's bytes are never handed on.
      findings  [{"code", "severity", "detail"}]. A hostile or broken archive gives severity "error"
                findings named zip-<reason> (zip-symlink, zip-path-traversal, zip-encrypted, zip-bomb ...).

    Whole-archive refusals return no usable entries: zip-unreadable, zip-too-large, zip-too-many-files.
    """
    limits = policy["limits"]
    max_files = int(limits["maxFiles"])
    max_entry = int(limits["maxFileChars"])
    max_total = int(limits["maxAnswerChars"])
    max_archive = int(limits.get("maxZipBytes", max_total + _ARCHIVE_ALLOWANCE))
    findings: list = []

    def flag(code: str, severity: str, **detail) -> None:
        findings.append({"code": code, "severity": severity, "detail": detail})

    try:
        archive, close = _open_archive(source, max_archive)
    except ValueError as error:
        if str(error) == "zip-too-large":
            flag("zip-too-large", "error", maxBytes=max_archive)
        else:
            flag("zip-unreadable", "error")
        return {"entries": [], "findings": findings}
    except (zipfile.BadZipFile, OSError, EOFError, NotImplementedError, RuntimeError, zlib.error):
        flag("zip-unreadable", "error")
        return {"entries": [], "findings": findings}

    try:
        members = [info for info in archive.infolist() if not info.is_dir()]
        if len(members) > max_files:
            flag("zip-too-many-files", "error", count=len(members), maxFiles=max_files)
            return {"entries": [], "findings": findings}
        entries = []
        for info in members:
            path, status, reason = _classify(info, policy, phase)
            entries.append({"path": path, "rawPath": _display_name(info.orig_filename), "status": status,
                            "reason": reason, "text": None, "bytes": 0})
        candidates = [i for i, e in enumerate(entries) if e["status"] != "denied"]

        # The same member twice (also after normalisation and case folding) is ambiguous: refuse every copy.
        seen: dict = {}
        for i in candidates:
            seen.setdefault(entries[i]["path"].casefold(), []).append(i)
        for indexes in seen.values():
            if len(indexes) > 1:
                for i in indexes:
                    entries[i].update(status="denied", reason="duplicate-entry")
        candidates = [i for i in candidates if entries[i]["status"] != "denied"]

        # Declared sizes, before a single byte is decompressed.
        for i in candidates:
            if members[i].file_size > max_entry:
                entries[i].update(status="denied", reason="entry-too-large")
        candidates = [i for i in candidates if entries[i]["status"] != "denied"]
        declared = sum(members[i].file_size for i in candidates)
        if declared > max_total:
            for i in candidates:
                entries[i].update(status="denied", reason="zip-total-too-large")
            flag("zip-bomb", "error", declaredBytes=declared, maxBytes=max_total, stage="declared")
            candidates = []

        # Actual bytes, member by member, never reading more than the limit allows.
        actual_total = 0
        for position, i in enumerate(candidates):
            entry = entries[i]
            try:
                with archive.open(members[i]) as stream:
                    data = stream.read(max_entry + 1)
            except (zipfile.BadZipFile, RuntimeError, NotImplementedError, zlib.error, EOFError, OSError, ValueError):
                entry.update(status="denied", reason="unreadable")
                continue
            if len(data) > max_entry:
                entry.update(status="denied", reason="entry-too-large")   # the header understated the size
                continue
            actual_total += len(data)
            if actual_total > max_total:
                for j in candidates[position:]:
                    entries[j].update(status="denied", reason="zip-total-too-large")
                flag("zip-bomb", "error", readBytes=actual_total, maxBytes=max_total, stage="read")
                break
            entry["bytes"] = len(data)
            if data.startswith(_NESTED_MAGICS):
                entry.update(status="denied", reason="nested-archive")
                continue
            if b"\x00" in data:
                entry.update(status="denied", reason="not-text")
                continue
            try:
                entry["text"] = data.decode("utf-8")
            except UnicodeDecodeError:
                entry.update(status="denied", reason="not-text")
                continue
    finally:
        archive.close()
        close()

    for entry in entries:
        if entry["status"] == "denied":
            entry["text"] = None
            if entry["reason"] in HOSTILE_REASONS:
                flag(HOSTILE_REASONS[entry["reason"]], "error", path=entry["path"])
            elif entry["reason"] == "not-text":
                flag("zip-not-text", "warning", path=entry["path"])
    return {"entries": entries, "findings": findings}
