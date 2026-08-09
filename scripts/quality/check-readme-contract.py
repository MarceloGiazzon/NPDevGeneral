#!/usr/bin/env python3
"""The README contract slice: the STATIC half of what the first-run harness proves.

WHY THIS EXISTS
---------------
Wiring the first-run harness into the release-candidate gate found five real defects on its first
real run. Clearing them cost three ~30-minute container runs. At least three of the five were
answerable statically, in about two seconds:

    * the first four commands used a bare `npdev`, which a fresh clone does not have
    * the quickstart told the user to scaffold INSIDE the clone, which `npdev init` refuses
    * `--config` / `--output` documented a default the code did not implement

None of those needed a clone, a JDK, or Gradle. This checks them without any of it, so the common
case is seconds and the full harness is left for what only it can prove: that the documented steps
actually WORK end to end on a bare machine.

THE BAR THIS HAD TO MEET (PRE_ROUND_FIXES.md section 4): it must RED-prove against the commit before
the bare-`npdev` fix. A checker that cannot detect the defect that already happened will not detect
the next one.

WHAT IT DELIBERATELY DOES NOT DO
--------------------------------
It does not run anything. It cannot tell you that `npdev setup` works -- only that the docs name a
command that exists and a path the CLI will accept. The harness remains the only thing that proves
the steps run.
"""
from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]

# Docs whose fenced sh blocks are meant to be COPIED AND RUN, and where the reader is standing when
# they do. Adding a doc here is how you put it under contract.
DOCS = [
    ("README.md", "## See it run"),
    ("docs/YOUR_FIRST_APP.md", None),  # None -> every sh block in the file
]

CLI = REPO / "NPDevCli" / "npdev_cli.py"


def fenced_sh_commands(text: str, section_heading: str | None) -> list[str]:
    """Commands from ```sh/```bash fences, optionally only under one `## ` heading.

    Only sh-tagged fences. An untagged block is illustrative OUTPUT -- the harness once executed
    README's sample dev-loop log as shell and reported the product broken because of it.
    """
    if section_heading is not None:
        lines, keep, out = text.splitlines(), False, []
        for line in lines:
            if line.startswith(section_heading):
                keep = True
                continue
            if keep and line.startswith("## "):
                break
            if keep:
                out.append(line)
        text = "\n".join(out)

    commands, in_fence = [], False
    for line in text.splitlines():
        if line.startswith("```"):
            lang = line[3:].strip().lower()
            in_fence = lang in ("sh", "bash", "shell") if not in_fence else False
            continue
        if in_fence:
            stripped = re.sub(r"\s*#.*$", "", line).strip()
            if stripped:
                commands.extend(part.strip() for part in stripped.split("&&") if part.strip())
    return commands


def check_commands_resolve(failures: list[str]) -> int:
    """Every documented command must name something a fresh clone actually has.

    `npdev` is NOT on a new user's PATH -- the repo ships `./npdev`. That one missing `./` made the
    first four commands of the front door fail with `command not found`, and everything after them
    failed behind it.
    """
    checked = 0
    for doc, heading in DOCS:
        path = REPO / doc
        if not path.is_file():
            failures.append(f"{doc}: listed in DOCS but not present")
            continue
        for cmd in fenced_sh_commands(path.read_text(encoding="utf-8", errors="replace"), heading):
            checked += 1
            head = cmd.split()[0]
            if head == "npdev":
                failures.append(
                    f"{doc}: `{cmd}`\n"
                    f"      -> bare `npdev` is not on a fresh clone's PATH; the repo ships `./npdev`.\n"
                    f"         A reader copying this gets: bash: npdev: command not found")
            elif head.endswith("npdev") and head.startswith("."):
                launcher = (REPO / head.lstrip("./")).name
                if not (REPO / launcher).is_file():
                    failures.append(f"{doc}: `{cmd}` -> no launcher named {launcher} in the repo root")
    return checked


def check_init_target_is_outside_the_clone(failures: list[str]) -> int:
    """`npdev init` REFUSES to scaffold inside the repo -- so the docs must not tell you to.

    The guardrail is right (an app buried in NPDev's own history is one nobody could clone). The
    documentation walked straight into it, and every step after `init` failed behind the refusal.
    """
    checked = 0
    for doc, heading in DOCS:
        path = REPO / doc
        if not path.is_file():
            continue
        for cmd in fenced_sh_commands(path.read_text(encoding="utf-8", errors="replace"), heading):
            match = re.match(r"^\.?/?npdev\s+init\s+(\S+)", cmd)
            if not match:
                continue
            checked += 1
            target = match.group(1)
            if not (target.startswith("..") or target.startswith("/") or re.match(r"^[A-Za-z]:", target)):
                failures.append(
                    f"{doc}: `{cmd}`\n"
                    f"      -> scaffolds INSIDE the clone, which `npdev init` refuses:\n"
                    f'         "refusing to scaffold inside this repo ... Pick a directory outside the repo."')
    return checked


def check_documented_defaults_match_the_code(failures: list[str]) -> int:
    """A `--help` string that promises one default while the code computes another.

    `npdev dev --help` said `--config` defaults to "beside the model" and `--output` to
    "../<dir>-app"; both were computed from the CURRENT DIRECTORY. Identical whenever the model is in
    the CWD -- which is every no-flag run -- so it stayed invisible until the documented quickstart
    ran from the clone.

    Reading the SOURCE for this was tried and abandoned: `candidate` is reused for the model, the
    config and the output, so tracing it by regex resolved to the wrong assignment and reported a
    false failure against correct code. A checker that cries wolf on the fixed version is worse than
    none, because the next reader learns to ignore it. So this ASKS THE FUNCTION, from a directory
    that is deliberately not the model's -- which is the exact condition under which the promise and
    the behaviour diverged, and the only one in which the question is even meaningful.
    """
    import argparse
    import os
    import tempfile

    sys.path.insert(0, str(REPO / "NPDevCli"))
    try:
        import npdev_cli  # noqa: E402
    except Exception as exc:  # pragma: no cover - import failure is itself the finding
        failures.append(f"npdev_cli.py could not be imported, so its defaults cannot be checked: {exc}")
        return 0

    checked = 0
    with tempfile.TemporaryDirectory(prefix="npdev-contract-") as tmp:
        root = Path(tmp)
        app = root / "my-app"
        app.mkdir()
        (app / "model.json").write_text("{}", encoding="utf-8")
        (app / "config.json").write_text("{}", encoding="utf-8")
        elsewhere = root / "somewhere-else"
        elsewhere.mkdir()

        previous = Path.cwd()
        try:
            os.chdir(elsewhere)
            args = argparse.Namespace(model=str(app / "model.json"), config=None, output=None)
            diagnostic = npdev_cli._infer_run_app_paths(args)
        finally:
            os.chdir(previous)

        checked += 1
        if diagnostic is not None:
            failures.append(
                "npdev_cli.py: `--config` --help promises \"beside the model\", but running with an\n"
                f"      explicit --model from another directory failed: {diagnostic}\n"
                "      -> this is the documented quickstart's own shape (`./npdev dev --model ../my-app/model.json`).")
        elif Path(args.config).parent.resolve() != app.resolve():
            failures.append(
                f"npdev_cli.py: `--config` --help promises \"beside the model\" but resolved to "
                f"{args.config}\n      -> not beside {app}.")

        checked += 1
        if diagnostic is None and Path(args.output).resolve() != (root / "my-app-app").resolve():
            failures.append(
                f"npdev_cli.py: `--output` --help promises \"../<dir>-app\" but resolved to "
                f"{args.output}\n      -> expected {root / 'my-app-app'}; deriving it from the CWD "
                f"generates into a directory named after wherever the user happened to be standing.")
    return checked


def main() -> int:
    failures: list[str] = []
    total = 0
    total += check_commands_resolve(failures)
    total += check_init_target_is_outside_the_clone(failures)
    total += check_documented_defaults_match_the_code(failures)

    print(f"README contract slice: {total} documented command(s)/default(s) checked.")
    if failures:
        print(f"\nFAILED -- {len(failures)} documented thing(s) a reader could not follow:\n")
        for failure in failures:
            print(f"  - {failure}")
        print("\nThese are STATIC findings: no clone, no JDK, no Gradle. The first-run harness")
        print("proves the steps RUN; this proves they could be followed at all.")
        return 1
    print("OK: every documented command resolves, every `npdev init` target is outside the clone,")
    print("    and every documented default matches the code's actual default.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
