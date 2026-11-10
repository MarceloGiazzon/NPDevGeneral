"""What leaves the computer in a `fix` conversation: the text of a failed build or boot, made safe to paste into a chat AI.

A build log names the person's folders, their user name, sometimes a key the app was started with. The prompt
this text goes into is pasted into an AI service outside the computer, so the CLI scrubs it itself and never trusts the
caller to have done it (the Manager, a script, a person typing `--errors-file`):

  1. the app's own folder (the generated app and the model folder next to it) becomes `<app>`;
  2. the home folder becomes `<home>`;
  3. any other absolute path becomes `<path>/<last name>`, so a stack trace still says WHICH file without saying where
     on the machine it lives;
  4. credentials and privacy data, by the ONE definition the rest of NPDev uses (npdev_redact, redaction-rules.json).
     Folders go first: the redaction also rewrites the user name inside a path, which would stop the app's own folder
     from matching.

Pure functions, stdlib plus npdev_redact. Never raises on odd input: an empty text comes back empty.
"""
from __future__ import annotations

import re
from pathlib import Path

import npdev_redact

# A drive path, spelled out from parts: this module must not itself contain the text of one (the portable-tooling scan
# reads every NPDevCli file for "letter, colon, slash").
_DRIVE = "[A-Za-z]" + ":" + r"[\\/]"
_SEGMENT = r"[^\s\"'<>|*?\\/]"
# Folders in the middle of a path may hold single spaces ("Program Files"); the last name stops at the first space. A drive
# letter only counts when nothing letter-like comes before it, so the "s:/" inside "https://" is not one.
_BODY = r"(?:" + _SEGMENT + r"+(?: " + _SEGMENT + r"+)*[\\/])*" + _SEGMENT + r"*"
_WINDOWS = re.compile(r"(?<![A-Za-z0-9])" + _DRIVE + _BODY)
_POSIX = re.compile(r"(?<![\w.:/-])/(?:home|Users|tmp|var|opt|mnt|srv|root|private)/" + _BODY)


def _last_name(match) -> str:
    parts = re.split(r"[\\/]", match.group(0).rstrip("\\/"))
    return "<path>/" + (parts[-1] if parts and parts[-1] else "")


def _folder_variants(folder) -> list:
    """The ways one folder shows up in a log: as written, with forward slashes, with backslashes."""
    if not folder:
        return []
    text = str(folder).rstrip("\\/")
    if not text:
        return []
    return sorted({text, text.replace("\\", "/"), text.replace("/", "\\")}, key=len, reverse=True)


def strip_paths(text: str, app_dir=None, home=None) -> tuple[str, int]:
    """`text` with local folders made anonymous, and how many substitutions that took."""
    if not text:
        return text or "", 0
    total = 0
    app_folders = []
    if app_dir:
        base = Path(str(app_dir))
        app_folders = [base, base.parent / base.name[: -len("-app")]] if base.name.endswith("-app") else [base]
    for folder, label in [(f, "<app>") for f in app_folders] + [(home if home is not None else Path.home(), "<home>")]:
        for variant in _folder_variants(folder):
            pattern = re.compile(re.escape(variant) + r"(?=$|[\\/\s\"'<>|:,;)\]])", re.IGNORECASE)
            text, n = pattern.subn(label, text)
            total += n
    text, n = _WINDOWS.subn(_last_name, text)
    total += n
    text, n = _POSIX.subn(_last_name, text)
    total += n
    return text, total


def scrub_errors(text: str, app_dir=None, home=None) -> tuple[str, dict]:
    """Local folders made anonymous, then credentials and privacy data redacted. Returns (text, counts) where counts says
    how many of each kind were replaced (never what they were)."""
    if not text:
        return text or "", {}
    # Folders first: redaction also rewrites the user name inside a path, after which the app's own folder no longer matches.
    anonymous, paths = strip_paths(text, app_dir=app_dir, home=home)
    redacted, counts = npdev_redact.redact_text(anonymous)
    counts = dict(counts)
    if paths:
        counts["local-path"] = paths
    return redacted, counts
