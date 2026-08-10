#!/usr/bin/env python3
"""Turn documented markdown into the list of commands a reader would actually type.

WHY THIS IS A MODULE AND NOT THREE LINES OF sed
-----------------------------------------------
The first-run harness (`run-readme.sh`) does not test NPDev's code -- it tests NPDev's
instructions, by extracting the commands out of README.md / docs/YOUR_FIRST_APP.md and running
them. That extraction has now been wrong in three DIFFERENT ways, each found only by a ~30-minute
container run, and each reported as a product failure in someone else's name:

  1. a bare `npdev` treated as an available command (it is not on a fresh clone's PATH)
  2. an example OUTPUT block executed as shell   (`14:09:47  ready in 45.2s ...`)
  3. a trailing ` # comment` taken as part of an argument
     (`cd /work/src     # back to the clone` -> "directory does not exist: /work/src     # back...")

Three wrongs in three different ways means the next patch will not be the last. So the extraction
lives here, in one place, as ordinary functions with a corpus of unit tests
(`scripts/quality/check-firstrun-extractor.py`, wired into run-ai-knowledge-gate.ps1) that run in
milliseconds without a clone, a JDK or Docker. The harness tests the docs; this tests the harness.

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
  - only ```sh / ```bash / ```shell fences are commands; every other fence is prose or output

KNOWN LIMIT, stated rather than papered over: a `#` inside an unquoted `$( ... )` or backticks is
treated as a comment. No documented command in this repo has that shape, and handling it properly
means writing a real shell parser. If a doc ever needs it, quote it.
"""

from __future__ import annotations

import argparse
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


def fenced_blocks(markdown: str, languages=COMMAND_FENCE_LANGUAGES) -> list[str]:
    """Bodies of fenced blocks whose info string names one of `languages`.

    Language-aware rather than "toggle on every fence": README's own quickstart section now shows
    an illustrative dev-loop LOG in an unlabelled fence, and a toggling extractor ran it as shell
    -- producing `FAIL cmd: 14:09:47  ready in 45.2s   http://localhost:8080`, the harness
    executing example output and calling the product broken when it could not.
    """
    wanted = tuple(languages)
    blocks: list[str] = []
    current: list[str] | None = None
    for line in markdown.replace("\r\n", "\n").replace("\r", "\n").split("\n"):
        if line.startswith("```"):
            if current is not None:
                blocks.append("\n".join(current))
                current = None
            else:
                language = line[3:].strip().lower()
                if language in wanted:
                    current = []
            continue
        if current is not None:
            current.append(line)
    if current is not None:                     # unterminated fence: take what we have
        blocks.append("\n".join(current))
    return blocks


def section_body(markdown: str, heading_pattern: str) -> str | None:
    """The body under the first heading matching `heading_pattern`, or None.

    Ends at the next `## ` heading -- deliberately NOT at `###`, so a step's own sub-sections stay
    part of it. None (rather than "") is the "anchor is gone" signal the caller must not confuse
    with "the section exists and has no commands".
    """
    pattern = re.compile(heading_pattern)
    body: list[str] | None = None
    for line in markdown.replace("\r\n", "\n").replace("\r", "\n").split("\n"):
        if body is None:
            if pattern.search(line):
                body = []
            continue
        if re.match(r"^##\s", line):
            break
        body.append(line)
    return None if body is None else "\n".join(body)


def extract_section_commands(markdown: str, heading_pattern: str,
                             languages=COMMAND_FENCE_LANGUAGES) -> list[str] | None:
    body = section_body(markdown, heading_pattern)
    if body is None:
        return None
    commands: list[str] = []
    for block in fenced_blocks(body, languages):
        commands.extend(normalize_block(block))
    return commands


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("file", nargs="?", help="markdown file (omit with --normalize-block)")
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
        parser.error("need a file and --section, or --normalize-block")

    with open(args.file, encoding="utf-8") as handle:
        markdown = handle.read()

    commands = extract_section_commands(markdown, args.section, languages)
    if commands is None:
        print(f"no section matching {args.section!r} in {args.file}", file=sys.stderr)
        return SECTION_NOT_FOUND
    for command in commands:
        print(command)
    return 0


if __name__ == "__main__":
    sys.exit(main())
