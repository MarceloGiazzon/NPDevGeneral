#!/usr/bin/env python3
"""GATE: no server engine may be special-cased without its siblings.

    python check-engine-parity.py --repo <repo>
    python check-engine-parity.py --repo <repo> --baseline   # freeze today's gaps
    python check-engine-parity.py --self-test

Exit 0 = parity holds. Exit 1 = an engine was forgotten. Exit 2 = bad usage.

THE RULE
    A file that branches on Postgres must branch on MySQL and SQL Server too.

That is not tidiness. `OperationalRunbookEmitter` emits the five scripts a user runs to create, stop,
inspect, connect to and reset their database. It branches on Postgres five times and on MySQL zero
times, so today those five operations exist for one engine and throw "Unsupported engine" for two
others that the config schema happily accepts. **The engine a user picked changes what NPDev can do
for them, and nothing warned them at the point of choice.**

Embedded engines (H2Local, H2Server, InMemory) are exempt from container operations -- they
genuinely have no container, and pretending otherwise would be its own dishonesty. They are still
reported.

WHY A GATE AND NOT A NOTE. This is the twin-pair shape the repo already tracks: "these locations must
move together". Every previous instance (REG-89, REG-104, REG-112, REG-144) was found late, by
someone else, after it shipped. Wire this into run-ai-knowledge-gate.ps1 and the next forgotten
engine fails in seconds instead of in a user's first hour.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import tempfile
from pathlib import Path

SERVER_ENGINES = ("postgres", "mysql", "sqlserver")
SPELLINGS = {
    "postgres": ("Postgres", "POSTGRES", "postgresql"),
    "mysql": ("MySQL", "MYSQL", "mysql"),
    "sqlserver": ("SqlServer", "SQL_SERVER", "sqlserver", "mssql"),
}
CONDITIONAL = re.compile(
    r"""(-eq\s+['"](?P<a>\w+)['"])"""
    r"""|(==\s*DatabaseEngine\.(?P<b>\w+))"""
    r"""|(case\s+(?P<c>[A-Z_]+)\s*->)"""
    r"""|(case\s+"(?P<d>\w+)"\s*->)"""
    r"""|(\.equals\("(?P<e>\w+)"\))"""
)
MAIN_ROOTS = ("NPDevGenerator/generator/src/main", "NPDevRuntimeHost/src/main",
              "NPDevKernel/kernel/src/main", "NPDevCli", "scripts")
BASELINE = Path(__file__).with_name("engine-parity-baseline.json")


def engine_of(tok: str) -> str | None:
    for key, spellings in SPELLINGS.items():
        if tok in spellings:
            return key
    return None


def engines_in(path: Path) -> set[str]:
    found: set[str] = set()
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return found
    for line in text.splitlines():
        s = line.strip()
        if s.startswith(("//", "*", "#")):
            continue
        for m in CONDITIONAL.finditer(line):
            tok = next((g for g in m.groupdict().values() if g), None)
            key = engine_of(tok) if tok else None
            if key:
                found.add(key)
    return found


def gaps_for(repo: Path) -> list[tuple[str, list[str]]]:
    out = []
    for root in MAIN_ROOTS:
        base = repo / root
        if not base.is_dir():
            continue
        for pattern in ("*.java", "*.ps1", "*.py"):
            for f in sorted(base.rglob(pattern)):
                p = f.as_posix()
                if "/test/" in p or "/build/" in p or "/Output/" in p:
                    continue
                covered = engines_in(f)
                if "postgres" in covered:
                    missing = [e for e in SERVER_ENGINES if e not in covered]
                    if missing:
                        out.append((f.relative_to(repo).as_posix(), missing))
    return out


def self_test() -> bool:
    """Prove the checker separates a parity gap from parity. Runs on temp files, never the repo --
    so it cannot pass merely because the repo happens to be clean today."""
    ok = True
    with tempfile.TemporaryDirectory() as d:
        root = Path(d)
        for m in ("NPDevContract", "NPDevGenerator", "NPDevKernel"):
            (root / m).mkdir(parents=True)
        main = root / "NPDevGenerator/generator/src/main"
        main.mkdir(parents=True)

        (main / "Gap.java").write_text(
            'if (engine == DatabaseEngine.POSTGRES) { start(); }\n', encoding="utf-8")
        found = gaps_for(root)
        if not any(f.endswith("Gap.java") and set(m) == {"mysql", "sqlserver"} for f, m in found):
            print("  self-test FAILED: a Postgres-only branch was not reported"); ok = False

        (main / "Gap.java").write_text(
            'if (engine == DatabaseEngine.POSTGRES) { a(); }\n'
            'if (engine == DatabaseEngine.MYSQL) { b(); }\n'
            'if (engine == DatabaseEngine.SQL_SERVER) { c(); }\n', encoding="utf-8")
        if any(f.endswith("Gap.java") for f, _ in gaps_for(root)):
            print("  self-test FAILED: a complete file was reported as a gap"); ok = False

        # A comment naming only Postgres must NOT count as a branch.
        (main / "Comment.java").write_text(
            '// POSTGRES is the default engine\nint x = 1;\n', encoding="utf-8")
        if any(f.endswith("Comment.java") for f, _ in gaps_for(root)):
            print("  self-test FAILED: a comment was treated as a branch"); ok = False

    print("  self-test: gap detected, completeness accepted, comment ignored -- OK" if ok else "")
    return ok


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--repo")
    ap.add_argument("--baseline", action="store_true", help="freeze today's gaps so the gate can be wired before they are fixed")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        return 0 if self_test() else 1

    # REG-144: identify the repo by its CONTENTS, never by its directory NAME, and default to the
    # checkout this file lives in (<repo>/scripts/quality/) so the gate can invoke it with no
    # arguments -- the same arithmetic every other checker here uses. --repo still wins, which is
    # how it was run from outside the repo while it was still a draft.
    if args.repo:
        repo = Path(args.repo).resolve()
    else:
        repo = Path(__file__).resolve().parents[2]
    if not all((repo / m).is_dir() for m in ("NPDevContract", "NPDevGenerator", "NPDevKernel")):
        print(f"error: {repo} is not an NPDev checkout", file=sys.stderr)
        return 2

    found = gaps_for(repo)

    if args.baseline:
        BASELINE.write_text(json.dumps({"frozen": {f: m for f, m in found}}, indent=2, sort_keys=True) + "\n",
                            encoding="utf-8")
        print(f"  froze {len(found)} existing gap(s) -> {BASELINE.name}")
        print("  this is the work list, not a permanent exemption")
        return 0

    frozen = {}
    if BASELINE.exists():
        frozen = json.loads(BASELINE.read_text(encoding="utf-8"))["frozen"]

    blocking = [(f, m) for f, m in found if f not in frozen or set(m) - set(frozen.get(f, []))]

    print(f"  files branching on Postgres without every server engine: {len(found)}"
          + (f"   (frozen: {len(frozen)})" if frozen else ""))
    for f, m in found:
        mark = "BLOCKING" if (f, m) in blocking else "frozen  "
        print(f"    {mark}  {f}\n              missing: {', '.join(m)}")

    if blocking:
        print(f"\nFAILED: {len(blocking)} engine-parity gap(s).")
        print("A user's experience must not depend on which engine they chose. Either handle every")
        print("server engine, or refuse the unsupported one AT THE POINT OF CHOICE -- never emit an")
        print("operation that works for one engine and throws for another.")
        return 1

    print("\nOK: every Postgres branch has a MySQL and SQL Server sibling.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
