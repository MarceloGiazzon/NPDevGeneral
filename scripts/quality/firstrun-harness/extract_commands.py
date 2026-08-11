#!/usr/bin/env python3
"""Turn a documented content section into the list of commands a reader would actually type.

WHY THIS IS A MODULE AND NOT THREE LINES OF sed
-----------------------------------------------
The first-run harness (`run-readme.sh`) does not test NPDev's code -- it tests NPDev's
instructions, by pulling the commands out of README.md's Quickstart section (via
content/readme.json -- see below) and running them. Getting a documented command from its written
form to something safe to execute has been wrong in three DIFFERENT ways, each found only by a
~30-minute container run, and each reported as a product failure in someone else's name:

  1. a bare `npdev` treated as an available command (it is not on a fresh clone's PATH)
  2. an example OUTPUT block executed as shell   (`14:09:47  ready in 45.2s ...`)
  3. a trailing ` # comment` taken as part of an argument
     (`cd /work/src     # back to the clone` -> "directory does not exist: /work/src     # back...")

Three wrongs in three different ways means the next patch will not be the last. So the
normalization lives here, in one place, as ordinary functions with a corpus of unit tests
(`scripts/quality/check-firstrun-extractor.py`, wired into run-ai-knowledge-gate.ps1) that run in
milliseconds without a clone, a JDK or Docker. The harness tests the docs; this tests the harness.

md-zero-2026-08-11 PLAN.md Phase 5: this module no longer parses markdown at all -- it reads
content/readme.json, the JSON mirror of content/readme.yml (which scripts/docs/generate_group_d_docs.py
also renders back into README.md, byte-identical). The JSON mirror, not the YAML, because this
script runs INSIDE the first-run harness's Docker image, which is deliberately bare (no pip, so no
PyYAML -- scripts/quality/firstrun-harness/Dockerfile's own words: "Deliberately absent: java,
python, pip, pwsh, gradle, node, docker"). Python's stdlib `json` module needs nothing installed;
that is the whole reason the mirror exists. Bug class #2 above (fence language) can no longer
recur -- the JSON already tags each block's language explicitly, there is no regex fence-boundary
detection left to get wrong. Bug classes #1 and #3 are about the COMMAND TEXT itself once found,
which is still free-form shell text a human wrote, so `strip_trailing_comment`/`split_on_and`/the
prompt-strip below are UNCHANGED and still load-bearing.

WHAT "CORRECT" MEANS HERE
-------------------------
Not "a shell parser". The corpus is documented command shapes, and the rules are the ones those
shapes need, each with a case in the test corpus:

  - `#` starts a comment only OUTSIDE quotes and only at the start of a word, so `grep '#foo'`
    and `curl http://host/page#frag` survive but `cd /work/src   # note` does not
  - a trailing backslash joins the next line (one logical command)
  - a leading `$ ` prompt is display, not input
  - `&&` at top level splits into separate commands, because the harness tracks `cd` itself
    (each command runs in its own `bash -c`, so a `cd` would otherwise be a no-op for the rest)
  - only blocks whose JSON `lang` is `sh` / `bash` / `shell` are commands; every other block is
    prose or output

KNOWN LIMIT, stated rather than papered over: a `#` inside an unquoted `$( ... )` or backticks is
treated as a comment. No documented command in this repo has that shape, and handling it properly
means writing a real shell parser. If a doc ever needs it, quote it.
"""

from __future__ import annotations

import argparse
import json
import re
import sys

COMMAND_FENCE_LANGUAGES = ("sh", "bash", "shell")

# The exit code that means "the anchor this extraction keys off is gone" -- distinct from 1
# ("ran, found nothing"), because the harness's own exit contract treats it as "the harness
# itself could not run" (2) rather than as a product failure. README's `## Quickstart` heading
# was renamed to `## See it run` once already; the extraction silently matched nothing and the
# harness reported thirteen cascading failures against the product.
SECTION_NOT_FOUND = 3


def strip_trailing_comment(text: str) -> str:
    """Drop a trailing ` # ...` comment, respecting quotes and escapes.

    A `#` opens a comment only when it is unquoted, unescaped, and starts a word (POSIX). That
    single rule is what keeps `grep '#foo'`, `echo "a # b"` and `curl http://h/p#frag` intact
    while removing the comment that made `cd /work/src     # back to the clone` look like a
    directory name.
    """
    in_single = False
    in_double = False
    escaped = False
    for index, char in enumerate(text):
        if escaped:
            escaped = False
            continue
        if char == "\\" and not in_single:
            escaped = True
            continue
        if char == "'" and not in_double:
            in_single = not in_single
            continue
        if char == '"' and not in_single:
            in_double = not in_double
            continue
        if char == "#" and not in_single and not in_double:
            if index == 0 or text[index - 1].isspace():
                return text[:index].rstrip()
    return text


def split_on_and(text: str) -> list[str]:
    """Split `a && b` into separate commands, ignoring `&&` inside quotes.

    The harness runs each command in its own `bash -c` and tracks `cd` across them itself, so a
    documented `cd ../my-library && git commit -am "..."` has to arrive as two commands or the
    `cd` is lost. Quote-aware because the second half of that very line contains a quoted string.
    """
    parts: list[str] = []
    current: list[str] = []
    in_single = False
    in_double = False
    escaped = False
    index = 0
    while index < len(text):
        char = text[index]
        if escaped:
            current.append(char)
            escaped = False
            index += 1
            continue
        if char == "\\" and not in_single:
            current.append(char)
            escaped = True
            index += 1
            continue
        if char == "'" and not in_double:
            in_single = not in_single
            current.append(char)
            index += 1
            continue
        if char == '"' and not in_single:
            in_double = not in_double
            current.append(char)
            index += 1
            continue
        if char == "&" and not in_single and not in_double and text[index:index + 2] == "&&":
            parts.append("".join(current))
            current = []
            index += 2
            continue
        current.append(char)
        index += 1
    parts.append("".join(current))
    return [part.strip() for part in parts if part.strip()]


def _join_continuations(lines: list[str]) -> list[str]:
    joined: list[str] = []
    buffer: str | None = None
    for line in lines:
        piece = line.rstrip()
        if buffer is not None:
            piece = piece.lstrip()
            buffer = buffer + " " + piece
        else:
            buffer = piece
        if buffer.endswith("\\"):
            buffer = buffer[:-1].rstrip()
            continue
        joined.append(buffer)
        buffer = None
    if buffer is not None:
        joined.append(buffer)
    return joined


_PROMPT = re.compile(r"^\s*\$\s+")


def normalize_block(text: str) -> list[str]:
    """A raw fenced block body -> the commands to run, in order.

    Order matters: continuations are joined on the RAW lines (before comment stripping), because
    a comment cannot legally interrupt a continuation, and joining first keeps a documented
    multi-line command as one command.
    """
    lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    commands: list[str] = []
    for line in _join_continuations(lines):
        line = _PROMPT.sub("", line)
        line = strip_trailing_comment(line).strip()
        if not line:
            continue
        commands.extend(split_on_and(line))
    return commands


def section_blocks(content_doc: dict, heading_pattern: str) -> list[dict] | None:
    """The fence/prose blocks of the first section whose title matches `heading_pattern`, or None
    if no section matches -- the "anchor is gone" signal the caller must not confuse with "the
    section exists and has no commands". Sub-sections (level 3) that immediately follow a matched
    level-2 section are included, the same "attach to the parent" rule section_body() used to
    apply by stopping only at the next `## `, never at `### `."""
    pattern = re.compile(heading_pattern)
    sections = content_doc.get("sections", [])
    for index, section in enumerate(sections):
        heading_line = "#" * section["level"] + " " + section["title"]
        if not pattern.search(heading_line):
            continue
        blocks = list(section["blocks"])
        for sibling in sections[index + 1:]:
            if sibling["level"] <= section["level"]:
                break
            blocks.extend(sibling["blocks"])
        return blocks
    return None


def extract_section_commands(content_doc: dict, heading_pattern: str,
                             languages=COMMAND_FENCE_LANGUAGES) -> list[str] | None:
    blocks = section_blocks(content_doc, heading_pattern)
    if blocks is None:
        return None
    wanted = tuple(languages)
    commands: list[str] = []
    for block in blocks:
        if block.get("type") == "fence" and (block.get("lang") or "").lower() in wanted:
            commands.extend(normalize_block(block.get("text") or ""))
    return commands


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("file", nargs="?", help="content/*.json file (omit with --normalize-block)")
    parser.add_argument("--section", help="regex matching the heading whose commands to extract")
    parser.add_argument("--normalize-block", action="store_true",
                        help="read one already-extracted block body from stdin")
    parser.add_argument("--languages", default=",".join(COMMAND_FENCE_LANGUAGES),
                        help="comma-separated fence languages treated as commands")
    args = parser.parse_args(argv)

    languages = tuple(part.strip().lower() for part in args.languages.split(",") if part.strip())

    if args.normalize_block:
        for command in normalize_block(sys.stdin.read()):
            print(command)
        return 0

    if not args.file or not args.section:
        parser.error("need a content/*.json file and --section, or --normalize-block")

    with open(args.file, encoding="utf-8") as handle:
        content_doc = json.load(handle)

    commands = extract_section_commands(content_doc, args.section, languages)
    if commands is None:
        print(f"no section matching {args.section!r} in {args.file}", file=sys.stderr)
        return SECTION_NOT_FOUND
    for command in commands:
        print(command)
    return 0


if __name__ == "__main__":
    sys.exit(main())
