#!/usr/bin/env python3
"""The first-run harness's command extractor, tested against a corpus of documented shapes.

WHY THIS EXISTS
---------------
`scripts/quality/firstrun-harness/run-readme.sh` tests NPDev's INSTRUCTIONS by pulling the commands
out of content/readme.json / content/your-first-app.json and running them on a bare machine. Getting
a documented command from its written form to something safe to execute has now been wrong in three
separate ways, and every one of them was found by a ~30-minute container run that then blamed the
product:

  1. a bare `npdev` treated as an available command
  2. an example OUTPUT block executed as shell
  3. a trailing ` # comment` taken as part of an argument -- FOUR-AND-EXTERNAL.md's F3:
        $ cd /work/src     # back to the clone -- `npdev` is not on your PATH, it lives here
        FAIL  why: directory does not exist: /work/src     # back to the clone -- ...

Three wrongs in three different ways means the next patch will not be the last. The harness tests
the docs; until now nothing tested the harness. This does, in milliseconds, with no clone, no JDK
and no Docker -- so a fourth shape is caught before it costs half an hour and a wrong diagnosis.

md-zero-2026-08-11 PLAN.md Phase 5: extract_commands.py no longer parses markdown at all (see its
own docstring) -- it reads content/*.json, the JSON mirror of content/*.yml that
scripts/docs/generate_group_d_docs.py also renders back into README.md / docs/YOUR_FIRST_APP.md,
byte-identical. The corpus below moved from raw markdown strings to constructed content-doc dicts
(the same `{"sections": [{"level", "title", "blocks": [{"type", "lang", "text"}]}]}` shape the real
JSON files carry) -- kept, not deleted, because it still exercises real logic: the sub-section
attachment rule ("### stays inside its ## parent") is genuinely new code in section_blocks(), not a
leftover from the old regex parser, and it had a real bug (level-3 blocks silently dropped) caught
only by re-deriving this exact test case during the Phase 5 rewrite and diffing against the old
extractor's output on the real docs.

TWO KINDS OF CASE, and both matter:
  * CORPUS  -- fixed inputs with expected extractions, one per documented shape.
  * LIVE    -- the anchors the extractor keys off must still exist in THIS repo's content. README's
               `## Quickstart` was renamed to `## See it run` once already; the extractor matched
               nothing, and the harness reported thirteen cascading product failures that were
               really one missing heading. That rename is detectable statically, right here.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

# REG-144: exact `parents[2]` arithmetic from this file's own location -- never a walk that looks
# for a directory NAME, which gave three different build roots in a clone named `NPDevGeneral`.
# The contents assertion is the same predicate every root resolution in this repo uses.
REPO_ROOT = Path(__file__).resolve().parents[2]
if not all((REPO_ROOT / module).is_dir()
           for module in ("NPDevContract", "NPDevGenerator", "NPDevKernel")):
    print(f"error: {REPO_ROOT} is not an NPDev checkout", file=sys.stderr)
    raise SystemExit(2)
HARNESS_DIR = REPO_ROOT / "scripts" / "quality" / "firstrun-harness"
sys.path.insert(0, str(HARNESS_DIR))

import extract_commands as ex  # noqa: E402  (path set above -- the module lives with the harness)

# The heading regex run-readme.sh passes. Kept here as a literal ON PURPOSE: if the two ever
# disagree, the LIVE case below fails loudly instead of the container run failing silently.
QUICKSTART_HEADING = r"^##\s+(Quickstart|See it run)"

failures: list[str] = []


def check(name: str, actual, expected) -> None:
    if actual != expected:
        failures.append(f"{name}\n      expected: {expected!r}\n      actual:   {actual!r}")


def sh(text: str) -> dict:
    return {"type": "fence", "lang": "sh", "text": text}


def section(level: int, title: str, blocks: list[dict]) -> dict:
    return {"level": level, "title": title, "blocks": blocks}


def doc(*sections: dict) -> dict:
    return {"sections": list(sections)}


# --------------------------------------------------------------------------- corpus: comments

# F3 itself, verbatim from docs/YOUR_FIRST_APP.md. This is the RED case: before the fix the
# harness tried to `cd` into a directory whose name contained the whole comment.
check("trailing comment is not part of the command",
      ex.normalize_block("cd ../NPDevGeneral     # back to the clone -- `npdev` is not on your PATH"),
      ["cd ../NPDevGeneral"])

check("README's own annotated quickstart lines lose only the comment",
      ex.normalize_block(
          "./npdev doctor                       # is this machine ready? (Java 17+, Python, disk)\n"
          "./npdev setup                        # one-time: build NPDev's own jars locally"),
      ["./npdev doctor", "./npdev setup"])

check("a whole-line comment disappears",
      ex.normalize_block("# just explaining\n./npdev setup"),
      ["./npdev setup"])

# The corruption the naive fix would cause: a `#` that is DATA, not a comment.
check("a single-quoted # survives",
      ex.normalize_block("grep '#foo' notes.txt"),
      ["grep '#foo' notes.txt"])

check("a double-quoted # survives",
      ex.normalize_block('echo "count # 3" > out.txt'),
      ['echo "count # 3" > out.txt'])

check("a # inside a word (URL fragment) survives",
      ex.normalize_block("curl http://localhost:8080/docs#login"),
      ["curl http://localhost:8080/docs#login"])

check("an escaped # survives",
      ex.normalize_block(r"echo \#not-a-comment"),
      [r"echo \#not-a-comment"])

check("a quoted # followed by a real comment keeps the first and drops the second",
      ex.normalize_block("grep '#tag' file   # find the tagged lines"),
      ["grep '#tag' file"])

# --------------------------------------------------------------------------- corpus: shapes

check("a backslash continuation is one command",
      ex.normalize_block("./npdev generate app \\\n  --model m.json \\\n  --output ../out"),
      ["./npdev generate app --model m.json --output ../out"])

check("a $ prompt is display, not input",
      ex.normalize_block("$ ./npdev doctor"),
      ["./npdev doctor"])

check("&& splits, so the harness can track cd itself",
      ex.normalize_block('cd ../my-library && git commit -am "add publishedYear to Book"'),
      ["cd ../my-library", 'git commit -am "add publishedYear to Book"'])

check("&& inside quotes does not split",
      ex.normalize_block('echo "a && b"'),
      ['echo "a && b"'])

check("CRLF from a Windows working tree leaves no stray \\r",
      ex.normalize_block("cd /work/src\r\n./npdev setup\r\n"),
      ["cd /work/src", "./npdev setup"])

check("blank lines vanish",
      ex.normalize_block("\n./npdev setup\n\n\n./npdev doctor\n"),
      ["./npdev setup", "./npdev doctor"])

# --------------------------------------------------------------------------- corpus: sections

OUTPUT_BLOCK_DOC = doc(
    section(2, "See it run", [
        sh("./npdev doctor"),
        {"type": "prose", "text": "Leave it running and watch:"},
        {"type": "fence", "lang": "", "text": "14:09:02  changed: model.json\n14:09:47  ready in 45.2s   http://localhost:8080"},
    ]),
    section(2, "Next", [sh("./npdev never-runs")]),
)

check("an unlabelled output block is NOT executed, and the section ends at the next ##",
      ex.extract_section_commands(OUTPUT_BLOCK_DOC, QUICKSTART_HEADING),
      ["./npdev doctor"])

check("a ```json block is content, not commands",
      ex.extract_section_commands(
          doc(section(2, "See it run", [
              {"type": "fence", "lang": "json", "text": '{"a": 1}'},
              sh("ls"),
          ])),
          QUICKSTART_HEADING),
      ["ls"])

check("### sub-sections stay inside their ## section",
      ex.extract_section_commands(
          doc(
              section(2, "See it run", [sh("a")]),
              section(3, "Let it do that for you", [sh("b")]),
              section(2, "Next", [sh("c")]),
          ),
          QUICKSTART_HEADING),
      ["a", "b"])

check("a missing anchor is None, not an empty list",
      ex.extract_section_commands(doc(section(2, "Something Else", [sh("ls")])), QUICKSTART_HEADING),
      None)

check("a section that exists with no sh fence is an empty list, not None",
      ex.extract_section_commands(
          doc(section(2, "See it run", [{"type": "prose", "text": "Just prose."}])),
          QUICKSTART_HEADING),
      [])

# --------------------------------------------------------------------------- live: the anchors

readme_content = json.loads((REPO_ROOT / "content" / "readme.json").read_text(encoding="utf-8"))
live = ex.extract_section_commands(readme_content, QUICKSTART_HEADING)
if live is None:
    failures.append(
        "LIVE: content/readme.json has no heading matching " + QUICKSTART_HEADING + " -- the "
        "first-run harness would extract nothing and report every downstream check as a product "
        "failure. Restore the heading in content/readme.yml, or add the new one to run-readme.sh "
        "AND to QUICKSTART_HEADING here.")
elif not live:
    failures.append(
        "LIVE: content/readme.json's quickstart section has no sh fence -- the harness would run "
        "zero commands and every check after it would fail for that reason alone.")
else:
    for command in live:
        if command.startswith("#"):
            failures.append(f"LIVE: extracted a comment as a command: {command!r}")
        if "\r" in command:
            failures.append(f"LIVE: extracted command carries a stray CR: {command!r}")

yfa_content_path = REPO_ROOT / "content" / "your-first-app.json"
if yfa_content_path.is_file():
    # Section 6 of the harness selects step 5's closing commit block BY CONTENT, because it used to
    # select it by index -- and a `### Let it do that for you` sub-section inserted a fence ahead of
    # it, so the harness ran the dev-loop block, labelled it "step5-commit", and then failed a
    # separate check for the commit that had therefore never happened (FOUR-AND-EXTERNAL.md F4).
    yfa_content = yfa_content_path.read_text(encoding="utf-8")
    if "git commit -am" not in yfa_content:
        failures.append(
            "LIVE: content/your-first-app.json no longer contains a `git commit -am` block -- the "
            "harness's step5-commit check selects that block by content and would find nothing.")

if failures:
    print(f"check-firstrun-extractor: {len(failures)} failure(s)\n")
    for failure in failures:
        print(f"  FAIL  {failure}")
    print("\nThe first-run harness's extractor is the thing under test here, not the docs it reads.")
    sys.exit(1)

print("check-firstrun-extractor: OK -- every documented command shape extracts as expected.")
