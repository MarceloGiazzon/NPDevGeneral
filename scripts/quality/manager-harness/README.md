# NPDev Manager headless container proof

CLOSEOUT_PLAN.md §5 (I4): **"the same instrument, one layer up"** from
`scripts/quality/firstrun-harness`. That harness proves the CLI's own README instructions work on
a genuinely bare machine. This one proves the **Manager's** install path works on a bare machine —
mechanically, on every change, not once by a human clicking through five screens.

## What it proves

A container that starts with **no Java, no Python** (the M3 thesis: NPDev's private JDK gets
`doctor` fully green with no system Java at all) runs `npdev-manager --selftest`
(`NPDevManager/src/selftest.rs`), which:

1. resolves + downloads + checksum-verifies + extracts a private JDK 17 (Adoptium)
2. resolves Python (system, since none is installed here it falls through to the pinned portable
   build) — see `runtime.rs::detect_system_python` / `resolve_portable_python`
3. resolves or installs an NPDev CLI version (the current `beta1.6` tag) via the same tag-zip
   download the Install screen's version picker uses
4. runs `npdev doctor --json` through those private runtimes and asserts every check passes

Any failure anywhere in that chain exits non-zero with which step failed — see `--selftest`'s own
output format.

## Why `git` is installed here, unlike firstrun-harness

firstrun-harness's runtime stage is "present only: git + curl" because the CLI's own instructions
assume `git clone`. The Manager needs **no git at all** — NPDev versions install as an HTTPS zip
download (`versions.rs::install_version`), which is the entire point of I4's "no git" framing in
the original plan sketch.

**But `npdev doctor` has its own, unrelated `git-present` check** (`npdev_cli.py`'s `checks`
list) that is a hard failure if git is absent — missing git makes `doctor`'s overall `ok` false
regardless of anything the Manager does. Leaving git out of this image would make the harness
report a failure that has nothing to do with what I4 exists to test. Decision recorded 2026-08-05:
install git, so a real, literal "10/10 checks pass" is achievable — matching CLOSEOUT_PLAN.md's own
stated assertion — rather than silently redefining "10/10" downward to "9/10, git excused."

## Why the runtime stage also needs libwebkit2gtk/libgtk/etc (not -dev, just the shared libraries)

Tauri 2 dynamically links the compiled binary against the webview/tray stack even though
`--selftest` never creates a window. The ELF dynamic linker resolves every `DT_NEEDED` entry at
process **start**, unconditionally — so `npdev-manager` cannot even launch on a machine missing
those runtime libraries, regardless of what code path it takes once running. This is a real,
load-bearing fact about packaging a Tauri app for Linux, found empirically while building this
harness (2026-08-05) — not a "clean machine" violation, since these ship as ordinary runtime
dependencies of any Tauri Linux build (the same libraries a `.deb`/AppImage package declares).

## Run it

```bash
pwsh -NoProfile -File scripts/quality/run-manager-harness.ps1
```

Or directly:

```bash
docker build -f scripts/quality/manager-harness/Dockerfile -t npdev-manager-harness .
docker run --rm npdev-manager-harness
# exit 0: SELFTEST PASS. exit 1: which step failed, printed to stderr.
```

**A full run needs network** (JDK download, NPDev tag-zip download) and, on the very first build,
compiles the entire Rust/Tauri dependency tree from scratch (this project's own dev machine is
Windows, so this Dockerfile's builder stage is also how a Linux `npdev-manager` binary gets
produced at all today).

## A real bug this harness's first run found: `jarsSource` was always `"build"`

The first time this harness ran green (2026-08-05), `npdev setup`'s `jarsSource` was `"build"` --
the slow ~9-10 min local compile -- despite `beta1.6` having a published Release asset that I3
proved downloads correctly against a real `git clone`. Root cause: `npdev setup`'s download-path
detection is `git describe --tags --exact-match HEAD` (`_current_git_tag()` in `npdev_cli.py`), but
`versions::install_version` installs a version by downloading a GitHub tag **zip archive**, which
has no `.git` directory at all -- so that detection always returned nothing for *any*
Manager-installed version, silently defeating the entire point of the fast-download path for the
one audience it exists for. Fixed in `versions.rs::stamp_git_tag_for_setup`: after unzipping,
best-effort `git init && git commit && git tag <tag>` inside the installed copy, so the CLI's
existing detection finds it. Confirmed live: before the fix, `jarsSource=build`; after, `download`.
Silently no-ops (falls back to the pre-existing, correct, only-slower behaviour) if git is missing
or fails for any reason -- confirmed live too, via a Windows long-path collision (`git add -A`
hitting `Filename too long` on a deeply nested path) that reproduced the exact "git present but
this operation fails" case this fallback exists for.

## Known limits

- **Requires a compatible tagged NPDev release** (one with `doctor`/`setup` already shipped, i.e.
  `beta1.6` or later) to reach a green doctor — an untagged/pre-`--json` tag correctly fails
  `--selftest` at the doctor step, same as CLOSEOUT_PLAN.md's own §0.1 reasoning for tagging
  before the VM test.
- **No compiled-Rust cache between runs** — every `docker build` recompiles from scratch unless
  Docker's own layer cache is warm. Not wired into per-PR CI for the same reason
  firstrun-harness isn't (cost), see `run-manager-harness.ps1`'s `manual-runbook` invocation
  declaration.
- **Linux only.** Proves the container/CLI path, not the Windows/macOS installer double-click —
  that is I5, and needs a human on a real VM.
