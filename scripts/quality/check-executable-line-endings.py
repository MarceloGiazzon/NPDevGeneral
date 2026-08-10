#!/usr/bin/env python3
"""A file that declares a shebang must have LF line endings.

    python scripts/quality/check-executable-line-endings.py

Exit 0 = every shebang'd file is LF. Exit 1 = at least one would not run. Exit 2 = bad usage.

WHY THIS EXISTS

`scripts/quality/firstrun-harness` is the tool that proves NPDev's instructions work on a machine
that starts with nothing. On 2026-08-09 it could not start at all from a Windows checkout:

    /usr/bin/env: 'bash\r': No such file or directory

`core.autocrlf=true` (Git for Windows' default) checks `run-readme.sh` out with CRLF, there was no
`.gitattributes` to say otherwise, and `COPY` bakes the CRLF shebang straight into the image. The
kernel reads `#!/usr/bin/env bash\r` and looks for an interpreter named `bash\r`.

**The tool that exists to catch "works on my machine" was itself not portable**, and it failed that
way only on Windows -- so CI, which checks out LF on a Linux runner, stayed green throughout.

`.gitattributes` now pins `*.sh` and `Dockerfile*` to LF, which fixes it at checkout. This checker
exists because that is a declaration and declarations drift: an editor, a generator, a copy-paste or
a new file type can reintroduce CRLF in the working tree even with the attribute set. The rule here
is broader than the glob on purpose --

    ANY tracked file whose first two bytes are `#!` must be LF

-- because a shebang is a declaration of intent to be executed directly, whatever the extension, and
the failure is identical for a `.py`, a `.rb` or a file with no extension at all.

WHAT IT DELIBERATELY DOES NOT CHECK
  * `.ps1` / `.bat` / `.cmd` -- Windows scripts, CRLF is correct for them
  * files without a shebang -- a CRLF `.md` or `.json` hurts nobody
  * anything untracked, or under build/target/node_modules output directories
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

SKIP_DIR_PARTS = ("/build/", "/target/", "/node_modules/", "/Output/", "/.git/")
# Windows-native scripts: CRLF is right for these, and some Windows tooling requires it.
WINDOWS_SUFFIXES = (".ps1", ".bat", ".cmd", ".psm1", ".psd1")


def repo_root() -> Path:
    # REG-144: identify the repo by CONTENTS, never by directory name. This file lives in
    # <repo>/scripts/quality/, so two levels up -- the same arithmetic every checker here uses.
    root = Path(__file__).resolve().parents[2]
    if not all((root / m).is_dir() for m in ("NPDevContract", "NPDevGenerator", "NPDevKernel")):
        print(f"error: {root} is not an NPDev checkout", file=sys.stderr)
        raise SystemExit(2)
    return root


def tracked_files(root: Path) -> list[str]:
    out = subprocess.run(["git", "ls-files"], cwd=root, capture_output=True, text=True, timeout=60)
    if out.returncode != 0:
        print("error: git ls-files failed", file=sys.stderr)
        raise SystemExit(2)
    return [line for line in out.stdout.splitlines() if line.strip()]


def main() -> int:
    root = repo_root()
    offenders: list[tuple[str, int]] = []
    checked = 0

    for rel in tracked_files(root):
        posix = "/" + rel
        if any(part in posix for part in SKIP_DIR_PARTS):
            continue
        path = root / rel
        if not path.is_file() or path.suffix.lower() in WINDOWS_SUFFIXES:
            continue
        try:
            with path.open("rb") as handle:
                if handle.read(2) != b"#!":
                    continue
                handle.seek(0)
                data = handle.read()
        except OSError:
            continue
        checked += 1
        crlf = data.count(b"\r\n")
        if crlf:
            offenders.append((rel, crlf))

    print(f"  {checked} tracked file(s) declare a shebang.")
    if not offenders:
        print("\nOK: every shebang'd file has LF line endings and will run where it is executed.")
        return 0

    print(f"\nFAILED: {len(offenders)} file(s) declare a shebang and have CRLF line endings.\n")
    for rel, count in sorted(offenders, key=lambda x: -x[1]):
        print(f"    {rel}   ({count} CRLF line(s))")
    print("\nA CRLF shebang is unrunnable on Linux: the kernel reads `#!/usr/bin/env bash\\r` and")
    print("looks for an interpreter literally named `bash\\r`. Inside a container this surfaces as")
    print("    /usr/bin/env: 'bash\\r': No such file or directory")
    print("and it fails ONLY on a Windows checkout, so CI will not catch it for you.")
    print("\nFix: `.gitattributes` should pin this file type to `eol=lf`, then re-checkout it:")
    print("    git add --renormalize <file> && git checkout -- <file>")
    return 1


if __name__ == "__main__":
    sys.exit(main())
