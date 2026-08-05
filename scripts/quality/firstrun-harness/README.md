# The first-run harness — an executable `README.md`

> Helper artifact. **Written outside the repo**; copy into
> `scripts/quality/firstrun-harness/` when you wire it up.

---

## What this is

**It does not test NPDev's code. It tests NPDev's instructions.**

A container that starts with **nothing installed** — no Java, no Python, no PowerShell, no Gradle —
clones NPDev from GitHub, and follows `README.md` **literally**. If a documented step fails, or a
step the user actually needs is not documented, the run goes red.

## Why it has to exist

The bugs it catches are **invisible on a developer machine**: `runtimehost-libs` already built, warm
Gradle cache, `D:\WorkSpace\NPDev\Build` present, correct Java/Python/pwsh.

**CI does not close the gap either.** It runs ubuntu + windows and clones fresh — but installs Java
via `setup-java`, and GitHub runners ship `pwsh` preinstalled.

> **CI proves the build works on a correct machine. It cannot prove a human can create one.**
> All three walls live in that gap.

---

## Run it

```bash
cd firstrun-helpers/harness
docker build -t npdev-firstrun .

# against the published default branch -- what a newcomer actually gets
docker run --rm npdev-firstrun

# against a branch, before merging
docker run --rm -e REPO_REF=beta1-vision-spine npdev-firstrun

# against your uncommitted working tree (pre-merge mode)
docker run --rm -e LOCAL_SRC=1 -v /d/WorkSpace/NPDev/NPDev_General:/work/src:ro npdev-firstrun
```

**A full run is 15–20 minutes** (clone + cold build). That is why it is not a per-PR gate.

---

## The four failures you should see on the first run

**If it passes before the documentation is fixed, the extraction is broken — investigate that.**

| Check | Wall | What it means |
|---|---|---|
| `prereq-present: Python 3` / `PowerShell 7` | — | README declares only *"Java 17 and Docker"*; NPDev needs Python and `pwsh` too |
| `documents-building-platform-jars` | **W1** | NPDev's kernel jars are not on Maven Central. README never says to build them, so the generated app cannot compile |
| `documents-bootJar-before-run` | **W2** | README goes straight to `docker compose up`, but the generated `Dockerfile` **COPYs an already-built jar** — its own first comment says to run `./gradlew bootJar` first |
| `app-jar-exists` · `documents-login-key` · `documents-app-url` | **W3** | no jar was ever built; and the URL and `SUPER_USER_KEY.txt` are documented nowhere |

> **Build it, watch it fail four times, then fix until green.** A harness written *after* the fixes
> has never seen a red and proves nothing.

---

## How it works

1. **Prerequisites** — greps README for its `Requires …` sentence, installs **only** what that
   sentence names, then checks whether Java 17 / Python 3 / `pwsh` are actually present. A tool
   NPDev needs but README never named fails **here**, with a message naming the *documentation*
   defect rather than a confusing downstream error.
2. **Quickstart** — extracts fenced code blocks under `## Quickstart`, joins backslash
   continuations, substitutes the placeholder output path, and **runs each command in order.**
3. **Structural checks** — asserts that the documented sequence contains the jar-build step (W1) and
   `bootJar` (W2), independent of whether the commands happened to succeed.
4. **Serve** — starts the built jar, polls `http://localhost:$APP_PORT/` for up to 120 s, accepts
   `200/301/302/401/403` (an auth redirect is a live app), and checks that the URL and the login-key
   location are documented (W3).

---

## Wiring it into the repo

**Trigger on** — *not* every PR:

- every **tag / release** — the version a newcomer actually gets
- **nightly**
- any change to `README.md`, `docs/GETTING_STARTED.md`, `NPDevCli/npdev_cli.py`, or the generated
  `build.gradle` / `Dockerfile` templates

**Required registrations** (both, or `run-script-inventory-check.ps1` fails):

- a classification in `scripts/policy/script-inventory-policy.json`
- a declared `invocation` in `scripts/policy/script-invocation-declarations.json`

**Suggested `.github/workflows/firstrun-harness.yml`:**

```yaml
name: first-run harness
on:
  push:
    tags: ['*']
  schedule: [{ cron: '0 4 * * *' }]
  pull_request:
    paths:
      - 'README.md'
      - 'docs/GETTING_STARTED.md'
      - 'NPDevCli/npdev_cli.py'
      - 'NPDevRuntimeHost/build.gradle*'
jobs:
  firstrun:
    runs-on: ubuntu-latest
    timeout-minutes: 40
    steps:
      - uses: actions/checkout@v4
      - run: docker build -t npdev-firstrun scripts/quality/firstrun-harness
      - run: docker run --rm -e REPO_REF=${{ github.ref_name }} npdev-firstrun
```

---

## Why this is worth more than the three fixes it verifies

> **It tests the documentation, not the code.**

Edit README and break the sequence → red. Change a default port without updating the docs → red.
Add a prerequisite without declaring it → red.

The project already gates stale ledger citations (`check-blocker-citation-freshness.py`) and
divergent twin pairs (`check-twin-pair-consistency.py`). **There is no gate for "the instructions do
not work"** — and that family produced F3, F6, F8, the stale `beta1.1` claim, and all three walls.

**This is that gate — the first mechanical control aimed at the only audience never represented in
the test suite.**

---

## Levels

| Level | Scope | Catches | Cost |
|---|---|---|---|
| **1** *(run-readme.sh)* | bare Linux, follow README, assert the app answers | **W1 · W2 · W3 · incomplete prereqs · the change-a-field/your-first-app/init loops** | done |
| **2** *(Dockerfile.wrongjava)* | a machine with a deliberately **wrong Java (21)** pre-installed | proves `npdev doctor` actually catches it | done |
| **3** | Windows container for the `.bat` path | Windows-specific drift | expensive, lower value initially |

**Level 2 matters more than it looks:** without it, `npdev doctor` ships untested by construction —
a checker that has never seen a red, on a machine that passes every check it makes.

```bash
docker build --build-arg JDK=21 -f Dockerfile.wrongjava -t npdev-firstrun-wrongjava .
docker run --rm npdev-firstrun-wrongjava
# exit 0: doctor correctly failed and named Java 21. exit 1: it did not -- a real bug.

# against your uncommitted working tree, same pre-merge convention as Level 1:
docker run --rm -e LOCAL_SRC=1 -v /d/WorkSpace/NPDev/NPDev_General:/work/src:ro npdev-firstrun-wrongjava
```

---

## Known limits — state them, do not paper over them

- **Tests the non-Docker run path** (`java -jar`), not `docker compose up`. That avoids
  Docker-in-Docker while exercising the same walls. Add the compose path later only if it earns it.
- **Ubuntu only** at Level 1. macOS is not covered by anything today, including CI.
- **Needs network** — clone plus Gradle dependency resolution.
- **Keys off the literal heading `## Quickstart`.** If README's structure changes, the
  `quickstart-section` check fails loudly rather than silently skipping — that is deliberate.
- **`LOCAL_SRC=1` on a Windows host can show a false RED unrelated to the docs under test.** The
  bind mount is read-only, so it exposes the host checkout exactly as `core.autocrlf` left it on
  disk -- if that produced CRLF line endings, `gradlew`'s `#!/bin/sh` shebang becomes `#!/bin/sh\r`,
  which Linux resolves to a nonexistent interpreter (`cannot execute: required file not found`,
  masked further downstream as a `-Dorg.gradle.projectcachedir` URL error). Confirmed this is a
  `LOCAL_SRC`-only artifact, not a real defect: a genuine `git clone` of this same repo state inside
  a clean Linux container produces `gradlew` with zero `\r` bytes (git stores it LF-only; nothing in
  this repo's checkout config converts it on a real Linux clone). Not fixed here, since the mount is
  read-only and copying+normalizing the whole tree just to test doc edits is more machinery than the
  case has earned -- if this bites again, `git -C <checkout> add --renormalize .` before mounting is
  the workaround, or run against `REPO_REF=<branch>` (real clone mode) instead.
