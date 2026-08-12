#!/usr/bin/env bash
#
# NPDev first-run harness -- an EXECUTABLE README.md.
#
# This script does not test NPDev's code. It tests NPDev's INSTRUCTIONS.
# It follows README.md literally on a machine that starts with nothing, and fails
# the moment a documented step does not work or a needed step is not documented.
#
# EXPECTED RESULT ON FIRST RUN: FOUR FAILURES (prereqs, build-jars, bootJar, serve/login).
# If it passes before the docs are fixed, the extraction is broken -- investigate that,
# do not celebrate.
#
# Exit codes:  0 = every documented step worked   1 = at least one check failed
#              2 = harness itself could not run (clone failed, README missing, ...)

set -uo pipefail

FAILURES=0
CHECKS_RUN=0
SRC=/work/src
LOG=/work/harness.log
APP_LOG=/work/app.log
# Created up front, not on first append. `log_start=$(wc -l < "$LOG" 2>/dev/null || echo 0)` cannot
# suppress its own failure: bash applies `< "$LOG"` BEFORE the `2>/dev/null` that was meant to hide
# it, so a missing log made the harness print
#     run-readme.sh: line 172: /work/harness.log: No such file or directory
# once, at the very first command it ran -- a harness-shaped error in the middle of the product's
# output, which is precisely the confusion this file exists to avoid causing.
: > "$LOG"
: "${APP_PORT:=8080}"

# ---------------------------------------------------------------- output helpers

c_red()  { printf '\033[31m%s\033[0m\n' "$*"; }
c_grn()  { printf '\033[32m%s\033[0m\n' "$*"; }
c_yel()  { printf '\033[33m%s\033[0m\n' "$*"; }
hr()     { printf '%.0s-' {1..78}; echo; }

section() { echo; hr; echo "== $*"; hr; }

# md-zero-2026-08-11 PLAN.md Phase 7: reconstructs a Group D doc's rendered text from its
# content/*.json mirror -- the exact inverse scripts/docs/generate_group_d_docs.py's own render()
# uses to write the .md, so this stays byte-identical to what a reader sees without this script
# ever opening README.md / docs/GETTING_STARTED.md itself.
render_content_doc() {
  python3 -c "
import json, sys

def render_blocks(blocks):
    parts = []
    for block in blocks:
        text = block.get('text') or ''
        lines = text.split('\n')
        if block['type'] == 'prose':
            parts.extend(lines)
        else:
            parts.append('\`\`\`' + (block.get('lang') or ''))
            parts.extend(lines)
            parts.append('\`\`\`')
    return parts

doc = json.load(open(sys.argv[1], encoding='utf-8'))
parts = render_blocks(doc['preamble'])
for section in doc['sections']:
    parts.append('#' * section['level'] + ' ' + section['title'])
    parts.extend(render_blocks(section['blocks']))
print('\n'.join(parts))
" "$1"
}

pass() { CHECKS_RUN=$((CHECKS_RUN+1)); c_grn "  PASS  $1"; }

# Failures listed in accepted-failures.json are still COUNTED and still PRINTED -- they are just
# recorded separately so the exit code can distinguish "a documented step is broken" from "a case
# we examined, wrote down, and accepted". An allowlist beats deleting the check: a deleted check
# takes its reasoning with it, and next year nobody can tell whether the case was considered or
# never thought of. Anything NOT on the list still turns the run red.
ACCEPTED_FILE="$(dirname "$0")/accepted-failures.json"
ACCEPTED_FAILURES=0
ACCEPTED_LIST=""

is_accepted() {
  [ -f "$ACCEPTED_FILE" ] || return 1
  jq -e --arg id "$1" '.accepted[]? | select(.id == $id)' "$ACCEPTED_FILE" >/dev/null 2>&1
}

fail() {
  CHECKS_RUN=$((CHECKS_RUN+1))
  if is_accepted "$1"; then
    ACCEPTED_FAILURES=$((ACCEPTED_FAILURES+1))
    ACCEPTED_LIST="$ACCEPTED_LIST
     $1"
    c_yel "  FAIL(accepted)  $1"
    [ $# -gt 1 ] && echo "        why: $2"
    echo "        accepted: see scripts/quality/firstrun-harness/accepted-failures.json"
    return 0
  fi
  FAILURES=$((FAILURES+1))
  c_red "  FAIL  $1"
  [ $# -gt 1 ] && echo "        why: $2"
  [ $# -gt 2 ] && echo "        fix: $3"
  return 0
}

# A check that CANNOT be answered on this run, with the reason printed. Distinct from both a pass
# (it did not verify anything) and a failure (nothing is broken).
#
# The case it exists for: README's documented way to run the app is `npdev dev`, a WATCH LOOP that
# never exits, which this loop correctly defers -- and then `app-jar-exists` asserted the jar that
# command would have produced. Three options were on the table and only one is right. Changing
# README to document a non-watch path is the tail wagging the dog. Having the harness quietly run
# some OTHER command first means asserting a path the README never told the reader to take. So:
# skip, and say why. It matches what this harness already does for
# `documents-bootJar-before-run (n/a -- the documented flow never runs a prebuilt jar)`.
#
# IT MUST PRINT THE REASON, AND THE SUMMARY MUST NAME IT AGAIN. A silent skip is how a harness
# stops testing without anyone noticing -- exactly what happened to this one when README's anchor
# heading moved and thirteen checks quietly stopped meaning anything.
SKIPPED=0
SKIP_LIST=""
skip() {
  CHECKS_RUN=$((CHECKS_RUN+1))
  SKIPPED=$((SKIPPED+1))
  SKIP_LIST="$SKIP_LIST
     $1"
  c_yel "  SKIP  $1"
  [ $# -gt 1 ] && echo "        why: $2"
  return 0
}

die() { c_red "HARNESS ERROR: $*"; exit 2; }

# The extractor: markdown -> the commands a reader would actually type. It has its own unit tests
# (scripts/quality/check-firstrun-extractor.py, in the AI knowledge gate) because its extraction
# has now been wrong three different ways -- a bare `npdev`, an example OUTPUT block run as shell,
# and a trailing ` # comment` taken as part of an argument -- and each was found only by a
# ~30-minute container run that then blamed the product.
EXTRACTOR=/usr/local/bin/extract_commands.py
QUICKSTART_HEADING='^##\s+(Quickstart|See it run)'

# run_cmd_list <label-prefix>
# Executes commands read one-per-line from stdin, tracking a `cd` across lines via the
# global CURRENT_DIR (see the note at the original call site on why: each command runs in
# its own throwaway `bash -c`, so a `cd` would otherwise be a no-op for everything after it).
# Recognizes but does not execute foreground/long-running commands (`java -jar`, `docker
# compose up`) -- a loop that waits for one of those to exit would hang forever on the first
# one it hit. Shared by every "run a doc's own commands and check they work" section in this
# harness -- README's Quickstart, YOUR_FIRST_APP.md, and change-a-field's scripted regenerate
# -- so a parsing/execution fix in one applies to all of them, per the plan's own instruction
# not to write a second extraction.
#
# Every deferred command is also RECORDED (DEFERRED_COMMANDS), because a deferral is not free: it
# means whatever that command would have produced does not exist. A check downstream of it must be
# able to say "skipped, because its producer was deferred" instead of failing as though the product
# were broken -- see skip() above.
DEFERRED_COMMANDS=""
run_cmd_list() {
  local label_prefix="$1"
  local cmd target candidate
  while IFS= read -r cmd; do
    [ -z "$cmd" ] && continue
    case "$cmd" in
      # `npdev dev` belongs here for the same reason as the other two: it is a WATCH LOOP that
      # never exits on its own. It was absent from this list only because README's own quickstart
      # used a bare `npdev dev`, which died instantly with "command not found" -- so the hang could
      # not happen. Fixing that command to `./npdev dev` turned a fast failure into a container that
      # sat for 55 minutes with the app healthy and READY behind it, looking exactly like a stall.
      # A harness that can hang is worse than one that fails: nothing reports, and the reader has to
      # guess whether it is working. Section 8 exercises `npdev dev` properly, with its own timeout.
      *java\ -jar*|*docker\ compose\ up*|*npdev\ dev*)
        echo
        echo "  \$ $cmd"
        echo "        (long-running/foreground command -- not executed by this loop)"
        DEFERRED_COMMANDS="$DEFERRED_COMMANDS
$cmd"
        pass "$label_prefix (recognized, deferred): $(echo "$cmd" | cut -c1-40)"
        continue
        ;;
    esac
    case "$cmd" in
      cd\ *)
        target=$(printf '%s\n' "$cmd" | sed 's/^cd[[:space:]]*//')
        case "$target" in
          /*) candidate="$target" ;;
          *)  candidate="$CURRENT_DIR/$target" ;;
        esac
        echo
        echo "  \$ $cmd"
        if [ -d "$candidate" ]; then
          CURRENT_DIR=$(cd "$candidate" && pwd)
          pass "$label_prefix: $(echo "$cmd" | cut -c1-58)"
        else
          fail "$label_prefix: $(echo "$cmd" | cut -c1-58)" "directory does not exist: $candidate"
        fi
        continue
        ;;
    esac
    echo
    echo "  \$ $cmd"
    # </dev/null is load-bearing -- see the note at the original call site: a command that
    # reads or even just probes stdin (pwsh does, at startup) silently consumes the rest of
    # the caller's here-string, ending the whole loop early with no error at all.
    # Remember where THIS command's output starts. `tail -12 "$LOG"` alone tails the shared,
    # cumulative log, so a command that fails with little or no output of its own displays the
    # PREVIOUS command's tail instead -- which is worse than printing nothing, because it looks
    # like a diagnosis. Measured: `./npdev init my-app` failed and the harness printed `npdev setup:
    # [3/3] done`, sending the reader to a step that had succeeded.
    local log_start
    log_start=$(wc -l < "$LOG" 2>/dev/null || echo 0)
    if bash -c "cd '$CURRENT_DIR' && $cmd" >>"$LOG" 2>&1 </dev/null; then
      pass "$label_prefix: $(echo "$cmd" | cut -c1-58)"
    else
      fail "$label_prefix: $(echo "$cmd" | cut -c1-58)" \
           "exited non-zero; output of THIS command below" \
           "see $LOG"
      if [ "$(wc -l < "$LOG")" -gt "$log_start" ]; then
        tail -n +$((log_start + 1)) "$LOG" | tail -12 | sed 's/^/          | /'
      else
        echo "          | (this command produced no output at all before exiting non-zero)"
      fi
    fi
  done
}

# ---------------------------------------------------------------- 0. obtain source

section "0. Obtain the project the way a newcomer would"

if [ "${LOCAL_SRC:-0}" = "1" ]; then
  [ -d "$SRC" ] || die "LOCAL_SRC=1 but $SRC is not mounted"
  c_yel "  using mounted local source (pre-merge mode): $SRC"
else
  echo "  git clone $REPO_URL (ref: $REPO_REF)"
  git clone --depth 50 --branch "$REPO_REF" "$REPO_URL" "$SRC" >/dev/null 2>&1 \
    || die "clone failed: $REPO_URL @ $REPO_REF"
  c_grn "  cloned OK"
fi

# your-first-app (section 6) and `npdev init` both run real `git commit`s -- that needs SOME
# identity configured, which this bare image deliberately has none of. This is not a gap in
# NPDev's own docs to fix: anyone who owns a git identity already configured it long before they
# ever cloned NPDev, the same unstated assumption as "knows how to open a terminal." Configuring
# it here is harness setup, standing in for that pre-existing human state -- not a documented
# NPDev prerequisite.
git config --global user.email "harness@example.invalid"
git config --global user.name "NPDev Harness"

[ -f "$SRC/README.md" ] || die "no README.md at the repo root"
cd "$SRC" || die "cannot cd $SRC"
echo "  HEAD: $(git -C "$SRC" log -1 --format='%h %s' 2>/dev/null | cut -c1-60)"

# md-zero-2026-08-11 PLAN.md Phase 7: every prose-content question this harness asks (the
# prerequisites sentence, "does it document the login key/URL") is answered from the SAME
# content/*.json mirrors section 2/6 already read -- never from README.md / GETTING_STARTED.md
# text directly, so this file has no markdown reads left either.
README_TEXT=$(render_content_doc content/readme.json)
GETTING_STARTED_TEXT=$(render_content_doc content/getting-started.json)

# ---------------------------------------------------------------- 1. prerequisites

section "1. Install ONLY what README's prerequisites sentence names"

# Pull the sentence that states requirements. We look for the line containing
# "Requires" inside (or just after) the Quickstart heading.
PREREQ_LINE=$(printf '%s\n' "$README_TEXT" | grep -m1 -i '^Requires\|^\*\*Requires\|Requires Java' || true)

if [ -z "$PREREQ_LINE" ]; then
  fail "prereqs-declared" \
       "README has no recognisable 'Requires ...' sentence" \
       "state prerequisites explicitly near the Quickstart heading"
else
  echo "  README says: $PREREQ_LINE"
  pass "prereqs-declared"
fi

want() { echo "$PREREQ_LINE" | grep -qi "$1"; }

# I4: `npdev setup` replaced pwsh as the way to build runtimehost jars on the user path -- README
# no longer names PowerShell as a requirement, and should not start again by accident (a stale
# "Requires ... pwsh" would send a newcomer installing something they no longer need).
if want 'powershell' || want 'pwsh'; then
  fail "prereqs-drop-pwsh" \
       "README's prerequisites sentence still names PowerShell/pwsh" \
       "the user path uses 'npdev setup' now (I4) -- pwsh is maintainer-only, see docs/GETTING_STARTED.md"
else
  pass "prereqs-drop-pwsh"
fi

APT_PKGS=""
want 'java'                        && APT_PKGS="$APT_PKGS openjdk-17-jdk"
want 'python'                      && APT_PKGS="$APT_PKGS python3"
want 'git'                         && APT_PKGS="$APT_PKGS git"
{ want 'powershell' || want 'pwsh'; } && NEED_PWSH=1 || NEED_PWSH=0

echo "  installing (from README's list only):${APT_PKGS:- <nothing>}"
if [ -n "$APT_PKGS" ]; then
  apt-get update >/dev/null 2>&1
  # shellcheck disable=SC2086
  apt-get install -y --no-install-recommends $APT_PKGS >/dev/null 2>&1 \
    || fail "prereq-install" "apt failed for:$APT_PKGS" "check package names"
fi

if [ "$NEED_PWSH" = "1" ]; then
  echo "  installing PowerShell 7 (README named it)"
  curl -sSL https://packages.microsoft.com/config/ubuntu/24.04/packages-microsoft-prod.deb \
       -o /tmp/ms.deb >/dev/null 2>&1 && dpkg -i /tmp/ms.deb >/dev/null 2>&1
  apt-get update >/dev/null 2>&1 && apt-get install -y powershell >/dev/null 2>&1
fi

# --- Now check that what README named is actually SUFFICIENT. -------------------
# This is the real prerequisite test: NPDev needs java + python. If README failed to name
# either, the corresponding check fails HERE, with a message that names the documentation
# defect rather than a confusing downstream error. pwsh is deliberately NOT in this list
# anymore (I4) -- `npdev setup` replaced it on the user path, so it is no longer a real
# NPDev requirement to assert against, only a maintainer-script one (see prereqs-drop-pwsh
# just above, which checks the opposite: that README does NOT claim it back).

for tool_spec in "java:Java 17" "python3:Python 3"; do
  tool=${tool_spec%%:*}; label=${tool_spec#*:}
  if command -v "$tool" >/dev/null 2>&1; then
    pass "prereq-present: $label"
  else
    fail "prereq-present: $label" \
         "$tool is required by NPDev but README's prerequisites sentence does not name it" \
         "add $label to README's 'Requires ...' line"
  fi
done

# Java must specifically be 17 -- the single most common newcomer failure.
if command -v java >/dev/null 2>&1; then
  JV=$(java -version 2>&1 | head -1)
  if echo "$JV" | grep -q '"17'; then
    pass "java-is-17  ($JV)"
  else
    fail "java-is-17" "found: $JV" "README must state Java 17 specifically"
  fi
fi

# I4's own decisive test: `npdev setup` (run for real below, in the Quickstart) must work with
# NO pwsh installed. Asserting its absence explicitly, rather than just relying on the fact that
# section 1 above no longer installs it, protects against some OTHER package silently pulling
# pwsh in as a dependency and quietly making this test meaningless.
if command -v pwsh >/dev/null 2>&1; then
  fail "pwsh-genuinely-absent" "pwsh is present on this image -- I4's own test needs it to NOT be"
else
  pass "pwsh-genuinely-absent"
fi

# ---------------------------------------------------------------- 2. quickstart

section "2. Run README's Quickstart commands, verbatim and in order"

# The commands under README's Quickstart heading, in order. Everything about HOW they are pulled
# out of the markdown -- which fences count, joining backslash continuations, dropping a trailing
# ` # comment` without corrupting a quoted one, splitting `a && b`, surviving a CRLF working tree
# -- lives in extract_commands.py, which has its own unit-test corpus. That is deliberate: this
# extraction has been wrong three separate times, and each time a ~30-minute container run was the
# only thing that noticed, and it blamed the product.
#
# The heading it keys off is a CONTRACT with README.md, and it has already been broken once
# silently: README was rewritten (188add8, "lead with what NPDev lets you build") and `## Quickstart`
# became `## See it run`. The extraction then matched nothing, so the command list was EMPTY, so
# `npdev setup` never ran, so runtimehost-libs were never staged -- and every downstream gradlew
# bootJar / run app / dev check failed. The harness reported 14 failures as if the product were
# broken. It was reporting its own missing anchor, thirteen times, in someone else's name.
#
# Three responses, because the rename was only half the problem:
#   1. Accept the headings README has actually used. A rename is a normal editorial act; silently
#      disarming the harness must not be its consequence.
#   2. Treat "no section found" (exit 3) and "section found, no commands in it" as EXIT 2 -- "the
#      harness itself could not run", which its own exit contract already defines -- and stop.
#      Continuing produced thirteen cascading failures that pointed at the product instead of at
#      this line, which is the most expensive kind of red: one that sends the reader to the wrong
#      place.
#   3. check-firstrun-extractor.py asserts the same anchor STATICALLY, in the AI knowledge gate,
#      so the next rename is caught in milliseconds instead of after a container run.
command -v python3 >/dev/null 2>&1 \
  || die "python3 is not installed, so the harness cannot extract README's commands. Section 1's \
prereq-present check above says whether README is at fault; either way this run cannot continue."

CMDS=$(python3 "$EXTRACTOR" --section "$QUICKSTART_HEADING" content/readme.json)
EXTRACT_RC=$?
if [ "$EXTRACT_RC" -eq 3 ]; then
  c_red "  HARNESS CANNOT RUN: no runnable section found in content/readme.json."
  echo  "    Looked for a '## Quickstart' or '## See it run' heading and found neither."
  echo  "    This is NOT a product failure -- it means the harness has no commands to follow, and"
  echo  "    every check after this one would fail for that reason alone. Fix QUICKSTART_HEADING in"
  echo  "    this script (and in scripts/quality/check-firstrun-extractor.py, which pins the same"
  echo  "    regex), or restore the section in content/readme.yml (README.md is GENERATED from it --"
  echo  "    see scripts/docs/generate_group_d_docs.py)."
  exit 2
elif [ "$EXTRACT_RC" -ne 0 ]; then
  die "the command extractor failed (exit $EXTRACT_RC) -- see the error above"
elif [ -z "$CMDS" ]; then
  c_red "  HARNESS CANNOT RUN: README's quickstart section contains no runnable commands."
  echo  "    The heading was found, but it holds no \`\`\`sh/bash/shell fence. Same consequence as a"
  echo  "    missing heading: nothing to follow, and every check after this one red for that reason."
  exit 2
fi
pass "quickstart-section"

# Where README's OWN flow puts the app. Derived from the commands just extracted, never hardcoded.
# The previous literal (/work/my-first-app, substituted for a `/path/outside/this/repo/...`
# placeholder README no longer contains) quietly stopped pointing at anything the documented flow
# produces -- and section 3's jar check then failed for a reason that had nothing to do with the
# product. Same failure mode as the heading rename, one layer down.
#
# Two sources, in the order a reader would take them: an explicit `--output`, else `npdev init
# <dir>`, whose app output defaults to `../<dir>-app` (npdev_cli._infer_run_app_paths, and
# dev_loop's `--output` help text says the same). `realpath -m` normalises `..` without requiring
# the directory to exist yet -- at this point in the run it does not.
OUT=$(printf '%s\n' "$CMDS" | sed -n 's|.*--output[[:space:]]\{1,\}\([^[:space:]]\{1,\}\).*|\1|p' | head -1)
if [ -z "$OUT" ]; then
  INIT_TARGET=$(printf '%s\n' "$CMDS" | sed -n 's|^\./npdev init[[:space:]]\{1,\}\([^[:space:]]\{1,\}\).*|\1|p' | head -1)
  if [ -n "$INIT_TARGET" ]; then
    case "$INIT_TARGET" in /*) OUT="$INIT_TARGET-app" ;;
                            *) OUT="$(realpath -m "$SRC/$INIT_TARGET")-app" ;; esac
  fi
else
  case "$OUT" in /*) ;; *) OUT=$(realpath -m "$SRC/$OUT") ;; esac
fi
echo "  README's app output resolves to: ${OUT:-<README names none>}"

echo "  commands found:"
printf '%s\n' "$CMDS" | sed 's/^/    $ /'

DID_BOOTJAR=0
DID_SYNC=0
printf '%s\n' "$CMDS" | grep -qE 'sync-runtimehost-libs|npdev setup' && DID_SYNC=1
printf '%s\n' "$CMDS" | grep -q 'bootJar'               && DID_BOOTJAR=1
# Does the documented flow actually RUN a prebuilt artifact? The generated Dockerfile still
# `COPY build/libs/<jar> app.jar` (DockerDeploymentEmitter, verified) -- so W2 is a real hazard for
# anyone told to `docker compose up` or `java -jar` without being told to build the jar first. It is
# NOT a hazard for a flow that never goes there: `npdev dev` and `npdev run app` build the jar
# themselves. Asking unconditionally for `bootJar` made this check fail on a README that had
# correctly moved past needing it -- testing the remedy instead of the hazard.
NEEDS_PREBUILT=0
printf '%s\n' "$CMDS" | grep -qE 'docker compose up|docker run|java -jar' && NEEDS_PREBUILT=1

# A real terminal keeps ONE persistent working directory across a whole session -- `cd` in one
# line changes where the NEXT line runs. Each extracted command here runs in its own throwaway
# `bash -c`, so without tracking this ourselves, a `cd` command would be a no-op for every command
# after it (README's own documented sequence -- validate/sync/generate from the repo root, then cd
# into the generated app, then build/run it there -- depends entirely on this actually working).
CURRENT_DIR="$SRC"
run_cmd_list "cmd" <<< "$CMDS"

# --- The two structural gaps, checked explicitly -------------------------------

if [ "$DID_SYNC" = "1" ]; then
  pass "documents-building-platform-jars"
else
  fail "documents-building-platform-jars" \
       "README never tells the user to build runtimehost-libs; NPDev's kernel jars are not on Maven Central, so the generated app cannot compile without them (W1)" \
       "add 'npdev setup' (or sync-runtimehost-libs.ps1 -BuildLocalJars) as the FIRST quickstart step"
fi

if [ "$NEEDS_PREBUILT" = "0" ]; then
  # Stated rather than silently skipped: the reader should be able to tell "this hazard cannot
  # arise here" from "this check did not run", which are very different claims.
  pass "documents-bootJar-before-run (n/a -- the documented flow never runs a prebuilt jar)"
elif [ "$DID_BOOTJAR" = "1" ]; then
  pass "documents-bootJar-before-run"
else
  fail "documents-bootJar-before-run" \
       "README documents running the container or the jar, but the generated Dockerfile COPYs an ALREADY-BUILT jar -- its own first comment says to run ./gradlew bootJar first (W2)" \
       "insert './gradlew bootJar' before any run instruction"
fi

# probe_editor_surface <base-url>
# The three product checks that need a LIVE app: the editor screen every generated app ships, every
# chunk the generator claims to have copied, and the JSON shape the editor's default tab renders
# from. Extracted into a function because their host moved: they used to hang off the app README's
# quickstart built, and README's documented run path is now a watch loop this harness defers. They
# are product checks, not documentation checks -- so they run against the app section 4 boots, which
# does not depend on which flow README happens to document this month.
probe_editor_surface() {
  local base="$1"

  # editor/ANALYSIS.md E4: the editor ships inside every generated app at /npdev-ui-react/ --
  # a broken screen there is a first-impression defect (the Manager's own hand-off is "open
  # your running app"), so prove it is actually reachable rather than trusting that it compiled.
  local editor_html=/work/editor-index.html editor_status
  # -L: /npdev-ui-react/ 302-redirects to /npdev-ui-react/index.html (UiRedirectController).
  editor_status=$(curl -sSL -o "$editor_html" -w '%{http_code}' "$base/npdev-ui-react/" 2>/dev/null)
  if [ "$editor_status" = "200" ] && grep -q 'assets/app\.js' "$editor_html"; then
    pass "editor responds at /npdev-ui-react/ and references its own bundle"
  else
    fail "editor responds at /npdev-ui-react/ and references its own bundle" \
         "HTTP status was '$editor_status', or the page did not reference assets/app.js" \
         "see $editor_html"
  fi

  # A 200 on index.html proves nothing about the OTHER shipped files -- Vite code-splits into a
  # variable number of chunks (e.g. AuthoringApp.js, ReactWorkbenchApp.js) that index.html never
  # references directly (they're lazy-loaded from app.js only once a user opens that surface), so
  # a stale/incomplete generator copy step can drop one and still pass the check above. Read the
  # manifest the SOURCE checkout says it shipped (written by build-templates.ps1, consumed by
  # RuntimeApiEmitter.emitOptionalReactUiAssets()) and probe every one of those files for real,
  # rather than trusting index.html's own text.
  local editor_manifest="$SRC/NPDevGenerator/generator/src/main/resources/npdev-templates/static-react-manifest.json"
  local asset asset_status asset_failures
  if [ -f "$editor_manifest" ]; then
    asset_failures=""
    for asset in $(python3 -c "import json,sys; print('\n'.join(json.load(open(sys.argv[1]))))" "$editor_manifest"); do
      asset_status=$(curl -sS -o /dev/null -w '%{http_code}' "$base/npdev-ui-react/$asset" 2>/dev/null)
      if [ "$asset_status" != "200" ]; then
        asset_failures="$asset_failures $asset=$asset_status"
      fi
    done
    if [ -z "$asset_failures" ]; then
      pass "editor: every manifested asset resolves (not just index.html)"
    else
      fail "editor: every manifested asset resolves (not just index.html)" \
           "non-200 for:$asset_failures" \
           "check RuntimeApiEmitter.emitOptionalReactUiAssets() actually copied everything the manifest lists"
    fi
  else
    fail "editor: every manifested asset resolves (not just index.html)" \
         "no manifest at $editor_manifest -- cannot know what the build actually shipped"
  fi

  # REG-139: a 200 on index.html AND every manifested chunk resolving is not proof the page
  # rendered anything -- the model editor's default tab crashed on a genuinely fresh boot because
  # the draft endpoint served the wrong SHAPE (the compiled model verbatim, no `entities` key at
  # all), which neither of the two checks above can see since they never look at the app's own
  # JSON responses. Hit the same endpoint the default tab's own first render depends on and check
  # the shape directly -- this is the harness-level check REG-139 says was missing.
  local editor_draft
  editor_draft=$(curl -sS "$base/api/admin/model/editor/draft" -H 'X-Api-Key: dev-key' 2>/dev/null)
  if printf '%s' "$editor_draft" | python3 -c "
import json, sys
try:
    body = json.load(sys.stdin)
except Exception:
    sys.exit(1)
sys.exit(0 if isinstance(body, dict) and isinstance(body.get('entities'), list) else 1)
" 2>/dev/null; then
    pass "editor: model editor draft endpoint returns a real ModelEditorDraft shape (entities[]), not the compiled model verbatim"
  else
    fail "editor: model editor draft endpoint returns a real ModelEditorDraft shape (entities[]), not the compiled model verbatim" \
         "response was not a JSON object with an 'entities' array -- the default tab will crash on a fresh boot (REG-139)" \
         "body: $(printf '%s' "$editor_draft" | tail -c 300)"
  fi
}

# ---------------------------------------------------------------- 3. serve

section "3. Does the app actually run, on the documented port?"

# W3: is the login path documented? Pure documentation greps -- they used to sit INSIDE the
# jar-exists branch below, so a flow that produced no jar took them down with it and the harness
# stopped asking the question entirely. Nothing about them needs a running app.
if printf '%s\n%s\n' "$README_TEXT" "$GETTING_STARTED_TEXT" | grep -q 'SUPER_USER_KEY'; then
  pass "documents-login-key"
else
  fail "documents-login-key" \
       "neither README nor GETTING_STARTED mentions SUPER_USER_KEY.txt -- the user has a running app and no way in (W3)" \
       "print the URL and key location at the end of 'generate app', and document both"
fi

if printf '%s\n%s\n' "$README_TEXT" "$GETTING_STARTED_TEXT" | grep -qE 'localhost:[0-9]{4}'; then
  pass "documents-app-url"
else
  fail "documents-app-url" "no localhost URL documented" "state http://localhost:$APP_PORT"
fi

JAR=""
[ -n "$OUT" ] && JAR=$(find "$OUT" -name '*.jar' -path '*build/libs*' 2>/dev/null | head -1)

if [ -z "$JAR" ] && [ -n "$DEFERRED_COMMANDS" ]; then
  # F1 (FOUR_AND_EXTERNAL.md A.2). README's documented way to run the app is `npdev dev`, a watch
  # loop -- and this harness correctly refuses to execute a command that never exits. Asserting the
  # jar that deferred command would have built is asserting an artifact the harness itself prevented
  # from being made; it is not a product defect, and the very next section proves the product builds
  # and boots. Skipped WITH THE REASON PRINTED, not silently, and named again in the summary.
  skip "app-jar-exists" \
       "the documented flow's app-producing command was deferred (it never exits, so this loop cannot run it):$(printf '%s' "$DEFERRED_COMMANDS" | tr '\n' ' ') -- section 4 proves generate+build+boot for real, and section 9 exercises the watch loop itself with its own timeout"
  skip "app-responds on :$APP_PORT" \
       "no jar to start (see app-jar-exists just above)"
elif [ -z "$JAR" ]; then
  fail "app-jar-exists" \
       "no build/libs/*.jar under ${OUT:-<README names no app output directory>} -- nothing to run, and no documented command was deferred that would explain it" \
       "consequence of W1/W2 above"
else
  pass "app-jar-exists ($(basename "$JAR"))"
  echo "  starting: java -jar $JAR --spring.profiles.active=dev --server.port=$APP_PORT"
  ( cd "$(dirname "$(dirname "$(dirname "$JAR")")")" \
    && java -jar "$JAR" --spring.profiles.active=dev --server.port="$APP_PORT" ) >"$APP_LOG" 2>&1 &
  APP_PID=$!

  UP=0
  for _ in $(seq 1 60); do
    sleep 2
    if curl -sS -o /dev/null -w '%{http_code}' "http://localhost:$APP_PORT/" 2>/dev/null | grep -qE '^(200|301|302|401|403)$'; then
      UP=1; break
    fi
    kill -0 "$APP_PID" 2>/dev/null || break
  done

  if [ "$UP" = "1" ]; then
    pass "app-responds on :$APP_PORT"
  else
    fail "app-responds on :$APP_PORT" \
         "no HTTP response within 120s" \
         "check $APP_LOG; also confirm the documented port matches the app's actual port"
    tail -15 "$APP_LOG" | sed 's/^/          | /'
  fi

  kill "$APP_PID" 2>/dev/null; wait "$APP_PID" 2>/dev/null
fi

# ---------------------------------------------------------------- 4. npdev run app

section "4. Does 'npdev run app' work as a one-shot command? (REG-131)"

# Not part of README's own documented Quickstart (that path is generate -> bootJar -> java -jar),
# but a real, shipped, one-command alternative -- and precisely the class of command a hardcoded
# dev-machine path can break invisibly for everyone but its author (REG-131: NPDEV_RUNTIMEHOST_LIBS_DIR
# used to default to a literal D:/WorkSpace/... string). A foreign machine is the only thing that
# can prove this, which is why it belongs in this harness rather than only in a unit test.
#
# --timeout 600, not 300. This is the FIRST full Gradle build of a generated app in this container:
# empty dependency cache, no daemon, everything resolved from the network. Every later section
# (5, 6, 7, 9) builds against the cache this one fills, so 300 s was the tightest budget in the whole
# harness applied to its single heaviest step. Measured failing at 300 with
#     "logExcerpt": "BUILD exceeded the overall --timeout budget."
# on a machine that was also running Gradle elsewhere -- and a timeout reported as
# `npdev run app ... FAILED` reads as a product defect, which is exactly the misdirection the
# canary's own -CanaryBootTimeoutSeconds was raised to 300 to stop (CLAUDE.md records that episode:
# two false REDs while health, smoke and acceptance all passed at a longer timeout).
RUN_APP_OUT=/work/my-run-app
RUN_APP_PORT=8081
cd "$SRC" || die "cannot cd to $SRC for section 4"
if RUN_APP_JSON=$(./npdev run app --model NPDevContract/dsl/resources/Models/canonical-demo/model.json \
    --config NPDevContract/dsl/resources/Models/canonical-demo/config.json \
    --output "$RUN_APP_OUT" --port "$RUN_APP_PORT" --timeout 600 2>>"$LOG"); then
  if printf '%s' "$RUN_APP_JSON" | grep -q '"ok": true'; then
    pass "npdev run app (one-shot generate+build+boot) succeeds"
    RUN_APP_OK=1
  else
    fail "npdev run app (one-shot generate+build+boot) succeeds" \
         "exited 0 but reported ok:false; tail of its own JSON below" \
         "see /work/harness.log"
    printf '%s' "$RUN_APP_JSON" | tail -c 800 | sed 's/^/          | /'
  fi
else
  fail "npdev run app (one-shot generate+build+boot) succeeds" \
       "exited non-zero; tail of output below" \
       "see /work/harness.log"
  printf '%s' "$RUN_APP_JSON" | tail -c 800 | sed 's/^/          | /'
  tail -12 "$LOG" | sed 's/^/          | /'
fi

# `npdev run app` leaves the app RUNNING on purpose (it returns at READY -- see the
# `_RUN_APP_CHILD_PROCESS = None` comment in npdev_cli._run_app), so this is the harness's one
# reliable live app, whatever README currently documents. The editor/REG-139 probes ride on it.
if [ "${RUN_APP_OK:-0}" = "1" ]; then
  probe_editor_surface "http://localhost:$RUN_APP_PORT"
else
  skip "editor responds at /npdev-ui-react/ and references its own bundle" \
       "npdev run app did not reach READY, so there is no live app to probe (see the failure above)"
  skip "editor: every manifested asset resolves (not just index.html)" \
       "npdev run app did not reach READY, so there is no live app to probe"
  skip "editor: model editor draft endpoint returns a real ModelEditorDraft shape (entities[])" \
       "npdev run app did not reach READY, so there is no live app to probe"
fi

# ---------------------------------------------------------------- 5. change-a-field

section "5. change-a-field -- prove the product, not just the install (I2)"

# The point of this whole section: "regeneration succeeded" is not the claim that matters --
# "the field the user added is actually there" is. So this asserts on the running API's own
# response, not on a log line saying a command exited zero.
CAF_DIR=/work/change-a-field
# The SAME --output an earlier section already generated -- the point is IN-PLACE schema evolution
# over an existing app and its existing database, not a fresh directory that would prove nothing.
#
# That prior app is section 4's, not section 2/3's. This used to point at README's quickstart
# output, which was right while README documented generating the canonical-demo app into an
# explicit `--output`; README now documents `npdev init` + `npdev dev`, a watch loop this harness
# defers, so nothing was ever produced there and the whole section died on its own precondition.
# Section 4 generates the same canonical-demo model+config into $RUN_APP_OUT and boots it, so it is
# the same claim against an app that actually exists -- and it does not depend on which flow README
# documents this month, which is exactly the coupling that broke it.
CAF_OUT="$RUN_APP_OUT"
CAF_CONCEPT="Provider"
CAF_FIELD_JSON='{"name": "phone", "type": "string", "ui": {"label": "Phone"}}'
CAF_PORT=8083

cd "$SRC" || die "cannot cd to $SRC for section 5"

if [ ! -d "$CAF_OUT" ]; then
  fail "change-a-field: prior app exists at $CAF_OUT" \
       "section 4 above did not produce $CAF_OUT -- this check regenerates against the SAME --output on purpose (the plan's own step 1: 'generate + run the demo app (already covered)')" \
       "check section 4 above"
else
  pass "change-a-field: prior app exists at $CAF_OUT"

  # Section 4 deliberately left that app running. Regenerating and rebuilding underneath a live JVM
  # would race it for the jar and for the H2 file lock, so stop it first -- and give it a moment to
  # release both. Same technique section 7 uses for its own leftover app.
  echo "  stopping the app section 4 left running on :$RUN_APP_PORT"
  pkill -f "$RUN_APP_OUT" 2>/dev/null
  sleep 5

  rm -rf "$CAF_DIR"; mkdir -p "$CAF_DIR"
  cp NPDevContract/dsl/resources/Models/canonical-demo/model.json  "$CAF_DIR/model.json"
  cp NPDevContract/dsl/resources/Models/canonical-demo/config.json "$CAF_DIR/config.json"

  echo "  injecting a scripted field edit: $CAF_CONCEPT.phone"
  if python3 /usr/local/bin/inject_field.py "$CAF_DIR/model.json" "$CAF_CONCEPT" "$CAF_FIELD_JSON" >>"$LOG" 2>&1; then
    pass "change-a-field: field injected into model (scripted, not hand-edited)"
  else
    fail "change-a-field: field injected into model (scripted, not hand-edited)" "see $LOG"
  fi

  echo "  re-validating the edited model"
  if ./npdev validate model "$CAF_DIR/model.json" >>"$LOG" 2>&1; then
    pass "change-a-field: re-validate passes"
  else
    fail "change-a-field: re-validate passes" "see $LOG"
  fi

  echo "  re-generating against the SAME --output"
  if ./npdev generate app --model "$CAF_DIR/model.json" --config "$CAF_DIR/config.json" --output "$CAF_OUT" >>"$LOG" 2>&1; then
    pass "change-a-field: re-generate against same --output"
  else
    fail "change-a-field: re-generate against same --output" "see $LOG"
  fi

  echo "  rebuilding"
  if (cd "$CAF_OUT" && ./gradlew bootJar) >>"$LOG" 2>&1; then
    pass "change-a-field: rebuild after regenerate"
  else
    fail "change-a-field: rebuild after regenerate" "see $LOG"
  fi

  CAF_JAR=$(find "$CAF_OUT" -name '*.jar' -path '*build/libs*' 2>/dev/null | head -1)
  if [ -z "$CAF_JAR" ]; then
    fail "change-a-field: jar exists after rebuild" "no build/libs/*.jar under $CAF_OUT"
  else
    pass "change-a-field: jar exists after rebuild"
    ( cd "$(dirname "$(dirname "$(dirname "$CAF_JAR")")")" \
      && java -jar "$CAF_JAR" --spring.profiles.active=dev --server.port="$CAF_PORT" ) >/work/caf-app.log 2>&1 &
    CAF_PID=$!

    CAF_UP=0
    for _ in $(seq 1 60); do
      sleep 2
      if curl -sS -o /dev/null -w '%{http_code}' "http://localhost:$CAF_PORT/" 2>/dev/null | grep -qE '^(200|301|302|401|403)$'; then
        CAF_UP=1; break
      fi
      kill -0 "$CAF_PID" 2>/dev/null || break
    done

    if [ "$CAF_UP" = "1" ]; then
      pass "change-a-field: app responds after regenerate+rebuild+restart"

      CAF_CREATE=$(curl -sS -X POST "http://localhost:$CAF_PORT/api/providers" \
        -H 'X-Api-Key: dev-key' -H 'Content-Type: application/json' \
        -d '{"npi":"1234567890","fullName":"Harness Test Provider","specialty":"Testing","phone":"555-0100"}' 2>>"$LOG")

      if printf '%s' "$CAF_CREATE" | grep -q '"phone"[[:space:]]*:[[:space:]]*"555-0100"'; then
        pass "change-a-field: new field reachable via REST (POST echoes it)"
      else
        fail "change-a-field: new field reachable via REST (POST echoes it)" \
             "response did not contain the new field/value; body below" "see $LOG"
        printf '%s' "$CAF_CREATE" | tail -c 500 | sed 's/^/          | /'
      fi

      CAF_LIST=$(curl -sS "http://localhost:$CAF_PORT/api/providers" -H 'X-Api-Key: dev-key' 2>>"$LOG")
      if printf '%s' "$CAF_LIST" | grep -q '"phone"[[:space:]]*:[[:space:]]*"555-0100"'; then
        pass "change-a-field: new field survives a GET (not just an echo)"
      else
        fail "change-a-field: new field survives a GET (not just an echo)" \
             "GET /api/providers did not contain the new field/value" "see $LOG"
        printf '%s' "$CAF_LIST" | tail -c 500 | sed 's/^/          | /'
      fi
    else
      fail "change-a-field: app responds after regenerate+rebuild+restart" \
           "no HTTP response within 120s" "see /work/caf-app.log"
      tail -15 /work/caf-app.log | sed 's/^/          | /'
    fi

    kill "$CAF_PID" 2>/dev/null; wait "$CAF_PID" 2>/dev/null
  fi
fi

# ---------------------------------------------------------------- 6. your-first-app

section "6. Execute docs/YOUR_FIRST_APP.md the way a newcomer would (I1/I2)"

# This reuses run_cmd_list -- the same command-parsing/execution machinery section 2 uses for
# README's Quickstart -- rather than a second implementation, per the plan's own instruction.
# YOUR_FIRST_APP.md interleaves ```sh command blocks with ```json blocks that are FILE CONTENT
# (a concepts array to splice in, a field snippet to inject), not commands -- so extraction has
# to be step-aware (numbered "## N. Title" headings) and fence-language-aware, unlike README's
# Quickstart which is one heading and 100% ```sh. Step 6 (renaming) is illustrative only --
# deliberately excluded, since running it would try to rename a field this app doesn't have a
# reason to rename.
#
# md-zero-2026-08-11 PLAN.md Phase 5: reads content/your-first-app.json (the JSON mirror of
# content/your-first-app.yml, which also renders docs/YOUR_FIRST_APP.md byte-identically) instead
# of parsing the .md file's raw text -- this image has no PyYAML (see extract_commands.py's own
# docstring for why the mirror exists), so this stays a stdlib-only `json` read. The numbered-step
# extraction (level-2 "N. Title" headings, N <= 5, blocks tagged sh/json) is unchanged in shape;
# only the source of the section list moved from regex-over-markdown to structured JSON already
# split into sections by scripts/docs/generate_group_d_docs.py's own conversion.
YFA_DOC="$SRC/content/your-first-app.json"
YFA_WORK=/work/my-library
YFA_APP=/work/my-library-app
YFA_PORT=8084

if [ ! -f "$YFA_DOC" ]; then
  fail "your-first-app: doc exists" "$YFA_DOC not found"
else
  pass "your-first-app: doc exists"

  rm -rf "$YFA_WORK" "$YFA_APP"

  python3 - "$YFA_DOC" > /work/yfa-steps.json <<'PYEOF'
import json, re, sys
content = json.load(open(sys.argv[1], encoding="utf-8"))
sections = content.get("sections", [])
steps = []
for index, sec in enumerate(sections):
    if sec.get("level") != 2:
        continue
    title = sec["title"]
    m = re.match(r"(\d+)\.", title)
    if not m or int(m.group(1)) > 5:
        continue
    # A `### ` sub-section (e.g. step 5's own "Let it do that for you") stays part of its parent
    # step's blocks, the same "next ## ends it, ### does not" rule the old regex split applied by
    # only ever splitting on `^## `.
    raw_blocks = list(sec.get("blocks", []))
    for sibling in sections[index + 1:]:
        if sibling.get("level", 2) <= 2:
            break
        raw_blocks.extend(sibling.get("blocks", []))
    blocks = [
        {"lang": b["lang"], "body": b["text"]}
        for b in raw_blocks
        if b.get("type") == "fence" and b.get("lang") in ("sh", "json")
    ]
    steps.append({"n": int(m.group(1)), "title": title.strip(), "blocks": blocks})
json.dump(steps, sys.stdout, indent=2)
PYEOF

  if [ ! -s /work/yfa-steps.json ]; then
    fail "your-first-app: doc parses into numbered steps" "extraction produced nothing -- see the doc's '## N. Title' headings"
  else
    pass "your-first-app: doc parses into numbered steps"

    STEP_COUNT=$(python3 -c "import json; print(len(json.load(open('/work/yfa-steps.json'))))")
    echo "  steps 1-5 found: $STEP_COUNT"

    # subst <text> -- rewrite the doc's own relative paths onto this container's /work layout.
    # Longest/most-specific pattern first: "../my-library-app" is a superstring of "../my-library".
    subst() {
      sed -e "s|\\.\\./my-library-app|$YFA_APP|g" \
          -e "s|\\.\\./my-library|$YFA_WORK|g" \
          -e "s|\\.\\./NPDevGeneral|$SRC|g"
    }

    # yfa_block <step-n> <block-index> -- the doc's per-step blocks are processed one at a
    # time, explicitly, in document order (which step holds a file, which holds a snippet,
    # which holds commands is fixed and small -- 5 steps), rather than a generic block-walking
    # loop: explicit is more honest here than a clever parser this small, rarely-changing doc
    # does not need.
    yfa_block() { python3 -c "
import json
steps = json.load(open('/work/yfa-steps.json'))
step = next((s for s in steps if s['n'] == $1), None)
print(step['blocks'][$2]['body'] if step and len(step['blocks']) > $2 else '', end='')
"; }

    # yfa_block_matching <step-n> <needle> -- the first sh block in that step whose body contains
    # <needle>. Positional indexing is fine for a block whose position IS the point ("step 2's json
    # fragment"); it is NOT fine for a block identified by what it does, and step 5 proved why. A
    # `### Let it do that for you` sub-section was added to step 5 with its own ```sh fence, which
    # pushed the closing commit block from index 2 to index 3. The harness went on reading index 2,
    # ran the dev-loop block under the label "step5-commit", and then failed a SEPARATE check for
    # the commit that had, of course, never happened -- two reds, one wrong offset, and neither
    # message pointing at it. Selecting by content is stable across an editorial insertion, which is
    # a normal thing for a tutorial to receive.
    yfa_block_matching() { python3 -c "
import json, sys
steps = json.load(open('/work/yfa-steps.json'))
step = next((s for s in steps if s['n'] == $1), None)
needle = sys.argv[1]
blocks = [b for b in (step or {}).get('blocks', []) if b['lang'] == 'sh' and needle in b['body']]
print(blocks[0]['body'] if blocks else '', end='')
" "$2"; }

    # Every YFA block goes through the SAME extractor README's quickstart uses -- one place that
    # knows about `&&`, backslash continuations, `$` prompts and trailing comments. The trailing
    # comment is not hypothetical here: step 5's `cd ../NPDevGeneral     # back to the clone ...`
    # was run verbatim, comment and all, and reported as "directory does not exist".
    normalize() { python3 "$EXTRACTOR" --normalize-block; }

    # Step 1 (sh): `npdev init ../my-library` -- one command now does what used to be four
    # separate steps (mkdir+cp, write db.definition.json, git init+add+commit): I3 built `npdev
    # init` precisely so this doc could stop teaching those as separate manual steps. Runs from
    # the repo root, same as every other step below. (run_cmd_list is invoked via `<<<`, not a
    # pipe -- a pipe's last stage runs in a subshell in bash by default, and pass/fail's
    # CHECKS_RUN/FAILURES mutations would silently vanish the moment that subshell exited,
    # undercounting every check inside it. `<<<` redirects stdin without forking, so counters
    # and CURRENT_DIR both persist correctly.)
    CURRENT_DIR="$SRC"
    S1=$(yfa_block 1 0 | subst | normalize)
    if [ -n "$S1" ]; then run_cmd_list "your-first-app step1" <<< "$S1"
    else fail "your-first-app step1: block present" "no sh block found under step 1"; fi

    for f in model.json config.json db.definition.json README.md .gitignore; do
      if [ -f "$YFA_WORK/$f" ]; then
        pass "your-first-app step1: npdev init scaffolded $f"
      else
        fail "your-first-app step1: npdev init scaffolded $f" "missing: $YFA_WORK/$f"
      fi
    done
    if git -C "$YFA_WORK" log --oneline >>"$LOG" 2>&1; then
      pass "your-first-app step1: npdev init already gave the model a git history"
    else
      fail "your-first-app step1: npdev init already gave the model a git history" "see $LOG"
    fi

    # Step 2 (json): a `"concepts": [...]` fragment meant to be pasted over the scaffold's own
    # concepts array, not a standalone document -- wrap it in braces to parse, same as a human
    # reading "replace its concepts array with" would mentally do. Routed through a temp FILE,
    # not interpolated into the python -c string directly: the fragment is full of double quotes
    # (it's JSON), and embedding it inside an already-double-quoted shell string would terminate
    # that string early on the first `"` it contains -- a real shell-quoting bug caught before
    # this ever ran, the same class of mistake as the change-a-field/inject_field.py design this
    # section otherwise reuses (always pass untrusted-shaped content as a file or an argv element,
    # never splice it into a script string).
    S2=$(yfa_block 2 0)
    printf '%s' "$S2" > /work/yfa-concepts-fragment.json
    if [ -n "$S2" ] && python3 -c "
import json
with open('$YFA_WORK/model.json', encoding='utf-8') as f:
    model = json.load(f)
with open('/work/yfa-concepts-fragment.json', encoding='utf-8') as f:
    fragment = f.read()
model.update(json.loads('{' + fragment + '}'))
with open('$YFA_WORK/model.json', 'w', encoding='utf-8') as f:
    json.dump(model, f, indent=2)
" >>"$LOG" 2>&1; then
      pass "your-first-app step2: Book/Member concepts replace the scaffold's own"
    else
      fail "your-first-app step2: Book/Member concepts replace the scaffold's own" "see $LOG"
    fi

    # Step 3 (sh): cd back to repo root + validate
    S3=$(yfa_block 3 0 | subst | normalize)
    CURRENT_DIR="$SRC"
    if [ -n "$S3" ]; then run_cmd_list "your-first-app step3" <<< "$S3"
    else fail "your-first-app step3: block present" "no sh block found under step 3"; fi

    # Step 4 (sh): generate, cd, gradlew bootJar, java -jar (deferred by run_cmd_list)
    S4=$(yfa_block 4 0 | subst | normalize)
    CURRENT_DIR="$SRC"
    if [ -n "$S4" ]; then run_cmd_list "your-first-app step4" <<< "$S4"
    else fail "your-first-app step4: block present" "no sh block found under step 4"; fi

    # Step 4's real "does it run" proof -- boot the built jar and hit the documented dev-key path.
    YFA_JAR=$(find "$YFA_APP" -name '*.jar' -path '*build/libs*' 2>/dev/null | head -1)
    if [ -z "$YFA_JAR" ]; then
      fail "your-first-app step4: jar exists after build" "no build/libs/*.jar under $YFA_APP"
    else
      pass "your-first-app step4: jar exists after build"
      ( cd "$(dirname "$(dirname "$(dirname "$YFA_JAR")")")" \
        && java -jar "$YFA_JAR" --spring.profiles.active=dev --server.port="$YFA_PORT" ) >/work/yfa-app.log 2>&1 &
      YFA_PID=$!
      YFA_UP=0
      for _ in $(seq 1 60); do
        sleep 2
        if curl -sS -o /dev/null -w '%{http_code}' "http://localhost:$YFA_PORT/" 2>/dev/null | grep -qE '^(200|301|302|401|403)$'; then
          YFA_UP=1; break
        fi
        kill -0 "$YFA_PID" 2>/dev/null || break
      done
      if [ "$YFA_UP" = "1" ]; then
        pass "your-first-app step4: app responds on :$YFA_PORT"
        YFA_BOOK=$(curl -sS -X POST "http://localhost:$YFA_PORT/api/books" \
          -H 'X-Api-Key: dev-key' -H 'Content-Type: application/json' \
          -d '{"title":"The Hobbit","isbn":"9780345339683","copies":1}' 2>>"$LOG")
        if printf '%s' "$YFA_BOOK" | grep -q '"title"[[:space:]]*:[[:space:]]*"The Hobbit"'; then
          pass "your-first-app step4: dev-key creates a Book over the documented REST API"
        else
          fail "your-first-app step4: dev-key creates a Book over the documented REST API" \
               "see $LOG"; printf '%s' "$YFA_BOOK" | tail -c 500 | sed 's/^/          | /'
        fi
      else
        fail "your-first-app step4: app responds on :$YFA_PORT" "no HTTP response within 120s" "see /work/yfa-app.log"
        tail -15 /work/yfa-app.log | sed 's/^/          | /'
      fi
      kill "$YFA_PID" 2>/dev/null; wait "$YFA_PID" 2>/dev/null
    fi

    # Step 5 (json #0): the publishedYear field snippet -- inject it (scripted, same mechanism
    # as change-a-field), then run the step's sh block (sh block #1).
    S5_FIELD=$(yfa_block 5 0)
    if [ -n "$S5_FIELD" ] && python3 /usr/local/bin/inject_field.py "$YFA_WORK/model.json" "Book" "$S5_FIELD" >>"$LOG" 2>&1; then
      pass "your-first-app step5: publishedYear field injected into Book"
    else
      fail "your-first-app step5: publishedYear field injected into Book" "see $LOG"
    fi

    S5=$(yfa_block 5 1 | subst | normalize)
    CURRENT_DIR="$SRC"
    if [ -n "$S5" ]; then run_cmd_list "your-first-app step5" <<< "$S5"
    else fail "your-first-app step5: sh block present" "no second (sh) block found under step 5"; fi

    # Step 5's real proof: the field the user "added" is actually there, and the book created
    # in step 4 SURVIVED the regenerate+rebuild+restart (H2Local, from npdev init's own
    # db.definition.json) rather than only proving a fresh, empty app boots.
    YFA_JAR2=$(find "$YFA_APP" -name '*.jar' -path '*build/libs*' 2>/dev/null | head -1)
    if [ -z "$YFA_JAR2" ]; then
      fail "your-first-app step5: jar exists after rebuild" "no build/libs/*.jar under $YFA_APP"
    else
      pass "your-first-app step5: jar exists after rebuild"
      ( cd "$(dirname "$(dirname "$(dirname "$YFA_JAR2")")")" \
        && java -jar "$YFA_JAR2" --spring.profiles.active=dev --server.port="$YFA_PORT" ) >/work/yfa-app2.log 2>&1 &
      YFA_PID2=$!
      YFA_UP2=0
      for _ in $(seq 1 60); do
        sleep 2
        if curl -sS -o /dev/null -w '%{http_code}' "http://localhost:$YFA_PORT/" 2>/dev/null | grep -qE '^(200|301|302|401|403)$'; then
          YFA_UP2=1; break
        fi
        kill -0 "$YFA_PID2" 2>/dev/null || break
      done
      if [ "$YFA_UP2" = "1" ]; then
        pass "your-first-app step5: app responds after regenerate+rebuild+restart"
        YFA_LIST=$(curl -sS "http://localhost:$YFA_PORT/api/books" -H 'X-Api-Key: dev-key' 2>>"$LOG")
        if printf '%s' "$YFA_LIST" | grep -q '"publishedYear"'; then
          pass "your-first-app step5: publishedYear field reachable via REST"
        else
          fail "your-first-app step5: publishedYear field reachable via REST" "see $LOG"
          printf '%s' "$YFA_LIST" | tail -c 500 | sed 's/^/          | /'
        fi
        if printf '%s' "$YFA_LIST" | grep -q '"title"[[:space:]]*:[[:space:]]*"The Hobbit"'; then
          pass "your-first-app step5: the book created in step 4 survived the schema change"
        else
          fail "your-first-app step5: the book created in step 4 survived the schema change" \
               "H2Local should have kept it -- see $LOG"
          printf '%s' "$YFA_LIST" | tail -c 500 | sed 's/^/          | /'
        fi
      else
        fail "your-first-app step5: app responds after regenerate+rebuild+restart" \
             "no HTTP response within 120s" "see /work/yfa-app2.log"
        tail -15 /work/yfa-app2.log | sed 's/^/          | /'
      fi
      kill "$YFA_PID2" 2>/dev/null; wait "$YFA_PID2" 2>/dev/null
    fi

    # Step 5's closing git commit -am -- proves the doc's own step ordering (npdev init's own
    # commit happens BEFORE any edit, so both the concepts-replacement and publishedYear are
    # tracked modifications by the time this runs) actually produces a second commit, not a
    # silently-empty `git commit -am`.
    S5_COMMIT=$(yfa_block_matching 5 "git commit" | subst | normalize)
    CURRENT_DIR="$SRC"
    if [ -n "$S5_COMMIT" ]; then run_cmd_list "your-first-app step5-commit" <<< "$S5_COMMIT"
    else fail "your-first-app step5: closing commit block present" \
              "no sh block under step 5 contains 'git commit' -- the doc no longer closes the loop by committing, or the block moved out of step 5"; fi

    if [ "$(git -C "$YFA_WORK" log --oneline 2>>"$LOG" | wc -l)" -ge 2 ]; then
      pass "your-first-app step5: git commit -am actually committed the tracked changes"
    else
      fail "your-first-app step5: git commit -am actually committed the tracked changes" \
           "expected >= 2 commits (npdev init + publishedYear); see $LOG"
    fi
  fi
fi

# ---------------------------------------------------------------- 7. npdev init -> run app

section "7. npdev init -> run app with NO flags (I3, CWD inference)"

# your-first-app above already proves `npdev init`'s scaffolding + git history. This section
# proves I3's OTHER half: `npdev run app`, given no --model/--config/--output at all, must infer
# them from the current directory -- the exact promise `npdev init my-app && cd my-app && npdev
# run app` makes. Uses the DEFAULT seed (no --from), unlike your-first-app which overwrites it --
# so this is also the one place the harness ever boots the seed's own Patient/Appointment shape.
INIT_DIR=/work/init-check-app
INIT_OUT=/work/init-check-app-app

cd "$SRC" || die "cannot cd to $SRC for section 7"
rm -rf "$INIT_DIR" "$INIT_OUT"

if ./npdev init "$INIT_DIR" >>"$LOG" 2>&1; then
  pass "npdev init: scaffolds a fresh directory"
else
  fail "npdev init: scaffolds a fresh directory" "see $LOG"
fi

for f in model.json config.json db.definition.json README.md .gitignore; do
  if [ -f "$INIT_DIR/$f" ]; then
    pass "npdev init: scaffolded $f"
  else
    fail "npdev init: scaffolded $f" "missing: $INIT_DIR/$f"
  fi
done

if git -C "$INIT_DIR" log --oneline >>"$LOG" 2>&1; then
  pass "npdev init: git history exists (first commit made automatically)"
else
  fail "npdev init: git history exists (first commit made automatically)" "see $LOG"
fi

# Re-running init into the SAME (now non-empty) directory must refuse, not silently overwrite.
if ./npdev init "$INIT_DIR" >>"$LOG" 2>&1; then
  fail "npdev init: refuses a non-empty target directory" \
       "a second init into the same directory exited 0 -- it should have refused"
else
  pass "npdev init: refuses a non-empty target directory"
fi

# The actual point of this section: no --model/--config/--output at all.
INIT_JSON=$(cd "$INIT_DIR" && "$SRC/npdev" run app --timeout 420 2>>"$LOG")
INIT_RC=$?
if [ "$INIT_RC" -eq 0 ] && printf '%s' "$INIT_JSON" | grep -q '"ok": true'; then
  pass "npdev run app (no flags): infers model/config/output from CWD and boots"
  INIT_URL=$(printf '%s' "$INIT_JSON" | python3 -c "import json,sys; print(json.load(sys.stdin).get('baseUrl') or '')" 2>>"$LOG")
  if [ -n "$INIT_URL" ] && curl -sS -o /dev/null -w '%{http_code}' "$INIT_URL/" 2>/dev/null | grep -qE '^(200|301|302|401|403)$'; then
    pass "npdev run app (no flags): the app it booted actually responds"
  else
    fail "npdev run app (no flags): the app it booted actually responds" "baseUrl='$INIT_URL'; see $LOG"
  fi
  pkill -f "init-check-app-app" 2>/dev/null; sleep 1
else
  fail "npdev run app (no flags): infers model/config/output from CWD and boots" \
       "exit=$INIT_RC; tail of its own JSON below" "see $LOG"
  printf '%s' "$INIT_JSON" | tail -c 800 | sed 's/^/          | /'
fi

# ---------------------------------------------------------------- 8. npdev mcp install

section "8. npdev mcp install -- config + live stdio handshake (I6)"

# The agent-verifiable half of I6: the emitted config is valid and its paths resolve, and --
# the real test -- the server actually answers an MCP initialize/tools/list handshake over
# stdio. What no harness can verify is a GUI client discovering the file after a restart; that
# is a separate, human, one-time step (see docs/AUTHORING_WITH_AI.md's own note on the split).
cd "$SRC" || die "cannot cd to $SRC for section 8"
rm -rf /work/mcp-install-test && mkdir -p /work/mcp-install-test
if (cd /work/mcp-install-test && "$SRC/npdev" mcp install --client claude-code) >>"$LOG" 2>&1; then
  pass "npdev mcp install: writes .mcp.json"
else
  fail "npdev mcp install: writes .mcp.json" "see $LOG"
fi

MCP_CMD=$(python3 -c "import json; print(json.load(open('/work/mcp-install-test/.mcp.json'))['mcpServers']['npdev']['command'])" 2>>"$LOG")
MCP_SERVER=$(python3 -c "import json; print(json.load(open('/work/mcp-install-test/.mcp.json'))['mcpServers']['npdev']['args'][0])" 2>>"$LOG")
if [ -n "$MCP_CMD" ] && [ -x "$MCP_CMD" ] && [ -f "$MCP_SERVER" ]; then
  pass "npdev mcp install: emitted command + server path both resolve"
else
  fail "npdev mcp install: emitted command + server path both resolve" \
       "command='$MCP_CMD' server='$MCP_SERVER'"
fi

HANDSHAKE_OK=$(python3 - "$MCP_CMD" "$MCP_SERVER" <<'PYEOF' 2>>"$LOG"
import json, subprocess, sys
cmd, server = sys.argv[1], sys.argv[2]
p = subprocess.Popen([cmd, server], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                     text=True, bufsize=1)
try:
    req = {"jsonrpc": "2.0", "id": 1, "method": "initialize",
           "params": {"protocolVersion": "2024-11-05", "capabilities": {},
                      "clientInfo": {"name": "npdev-harness-selftest", "version": "1"}}}
    p.stdin.write(json.dumps(req) + "\n"); p.stdin.flush()
    init_resp = json.loads(p.stdout.readline())
    assert "result" in init_resp, f"initialize had no result: {init_resp}"

    req2 = {"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}}
    p.stdin.write(json.dumps(req2) + "\n"); p.stdin.flush()
    tools = json.loads(p.stdout.readline())["result"]["tools"]
    assert len(tools) >= 15, f"expected >= 15 tools, got {len(tools)}"
    print(f"true {len(tools)}")
except Exception as exc:
    print(f"false {exc}")
finally:
    p.terminate()
PYEOF
)
if printf '%s' "$HANDSHAKE_OK" | grep -q '^true'; then
  pass "npdev mcp install: server answers initialize + tools/list over stdio ($(printf '%s' "$HANDSHAKE_OK" | awk '{print $2}') tools)"
else
  fail "npdev mcp install: server answers initialize + tools/list over stdio" "$HANDSHAKE_OK"
fi

# ---------------------------------------------------------------- 9. npdev dev

section "9. npdev dev -- the watch loop (the product's inner loop)"

# Section 5 proves a newcomer CAN change a field: edit, validate, generate, build, restart,
# assert over REST. This section proves `npdev dev` does all of that FOR them on save --
# and, just as importantly, that a typo does NOT take their running app down.
#
# The two checks that carry the design:
#   dev-invalid-model-keeps-app-up   validate runs before anything is touched (the trust invariant)
#   dev-teardown / dev-no-orphan     the loop stops what it started, or reclaims it next start

DEV_DIR=/work/dev-loop
DEV_LOG=/work/dev.log
DEV_PORT=8087
DEV_CONCEPT="Provider"

cd "$SRC" || die "cannot cd to $SRC for section 9"

rm -rf "$DEV_DIR"; mkdir -p "$DEV_DIR"
if ./npdev init "$DEV_DIR/dev-app" >>"$LOG" 2>&1; then
  pass "npdev dev: scaffold via npdev init"
else
  fail "npdev dev: scaffold via npdev init" "npdev init failed" "see $LOG"
fi

DEV_APP="$DEV_DIR/dev-app"
if [ -f "$DEV_APP/model.json" ]; then
  # Persistence is an `npdev init` promise, and `npdev dev` depends on it: with an
  # InMemory engine every reload would silently discard the user's test rows.
  if grep -q '"engine"[[:space:]]*:[[:space:]]*"H2Local"' "$DEV_APP/db.definition.json" 2>/dev/null; then
    pass "npdev dev: scaffolded db is persistent (H2Local, not InMemory)"
  else
    fail "npdev dev: scaffolded db is persistent (H2Local, not InMemory)" \
         "npdev init scaffolded an ephemeral database, so dev reloads would discard data" \
         "db.definition.json should declare engine H2Local + KeepExistingIfCompatible"
  fi

  ( cd "$DEV_APP" && "$SRC/npdev" dev --port "$DEV_PORT" ) >"$DEV_LOG" 2>&1 &
  DEV_PID=$!

  DEV_UP=0
  for _ in $(seq 1 220); do
    sleep 3
    grep -q "ready in" "$DEV_LOG" && { DEV_UP=1; break; }
    kill -0 "$DEV_PID" 2>/dev/null || break
  done

  if [ "$DEV_UP" = "1" ]; then
    pass "npdev dev: first cycle reaches READY with no flags"

    DEV_SEED_CONCEPT=$(python3 -c "import json;print(json.load(open('$DEV_APP/model.json'))['concepts'][0]['name'])" 2>/dev/null)
    echo "  seeded concept: $DEV_SEED_CONCEPT"

    # --- a saved edit must rebuild and become reachable ---------------------------
    python3 /usr/local/bin/inject_field.py "$DEV_APP/model.json" "$DEV_SEED_CONCEPT" \
      '{"name": "devLoopProof", "type": "string"}' >>"$LOG" 2>&1

    DEV_RELOADED=0
    for _ in $(seq 1 220); do
      sleep 3
      [ "$(grep -c 'ready in' "$DEV_LOG")" -ge 2 ] && { DEV_RELOADED=1; break; }
      kill -0 "$DEV_PID" 2>/dev/null || break
    done

    if [ "$DEV_RELOADED" = "1" ]; then
      pass "npdev dev: a saved edit rebuilds and reaches READY again"
    else
      fail "npdev dev: a saved edit rebuilds and reaches READY again" \
           "no second 'ready in' after editing model.json" "see $DEV_LOG"
      tail -20 "$DEV_LOG" | sed 's/^/          | /'
    fi

    if grep -q 'changed:' "$DEV_LOG"; then
      pass "npdev dev: the file change was detected and reported"
    else
      fail "npdev dev: the file change was detected and reported" "no 'changed:' line" "see $DEV_LOG"
    fi

    # --- THE TRUST INVARIANT: an invalid model must not take the app down ----------
    python3 - "$DEV_APP/model.json" <<'PYEOF' >>"$LOG" 2>&1
import json, sys
p = sys.argv[1]
m = json.load(open(p, encoding="utf-8"))
m["concepts"][0]["fields"][1]["type"] = "strng"     # deliberate, known-bad
json.dump(m, open(p, "w", encoding="utf-8"), indent=2)
PYEOF
    sleep 30

    if curl -sS -o /dev/null -w '%{http_code}' "http://localhost:$DEV_PORT/actuator/health" 2>/dev/null | grep -q '^200$'; then
      pass "npdev dev: an INVALID model leaves the running app up (validate-first)"
    else
      fail "npdev dev: an INVALID model leaves the running app up (validate-first)" \
           "the app stopped serving after a typo was saved -- validate must run before anything is touched" \
           "see $DEV_LOG"
      tail -20 "$DEV_LOG" | sed 's/^/          | /'
    fi

    if grep -qi 'FAILED' "$DEV_LOG"; then
      pass "npdev dev: the validation failure is reported, not swallowed"
    else
      fail "npdev dev: the validation failure is reported, not swallowed" \
           "no failure reported for a known-bad model" "see $DEV_LOG"
    fi
  else
    fail "npdev dev: first cycle reaches READY with no flags" \
         "npdev dev never reached READY" "see $DEV_LOG"
    tail -20 "$DEV_LOG" | sed 's/^/          | /'
  fi

  # --- teardown: stop the loop, then prove nothing was left behind -----------------
  kill -INT "$DEV_PID" 2>/dev/null
  sleep 8
  kill -INT "$DEV_PID" 2>/dev/null      # second interrupt escalates, by design
  sleep 12
  if kill -0 "$DEV_PID" 2>/dev/null; then
    kill -9 "$DEV_PID" 2>/dev/null
    fail "npdev dev: exits on interrupt" \
         "the loop was still running 20s after two interrupts" "see $DEV_LOG"
  else
    pass "npdev dev: exits on interrupt"
  fi

  # Teardown cannot be guaranteed on every exit path (a force-kill runs no cleanup), so
  # the contract is: either the app is gone, OR the next `npdev dev` reclaims it. Assert
  # the reclaim path directly rather than assuming the happy one.
  sleep 5
  if curl -sS -o /dev/null -w '%{http_code}' "http://localhost:$DEV_PORT/actuator/health" 2>/dev/null | grep -q '^200$'; then
    if [ -f "$DEV_APP/.npdev-dev/app.pid" ]; then
      pass "npdev dev: a surviving app is recorded for reclaim (app.pid present)"
    else
      fail "npdev dev: a surviving app is recorded for reclaim (app.pid present)" \
           "an app is still serving and nothing recorded its pid, so the next run cannot reclaim it"
    fi
  else
    pass "npdev dev: no app left serving after the loop exits"
  fi
else
  fail "npdev dev: scaffold via npdev init" "no model.json under $DEV_APP" "see $LOG"
fi

# ---------------------------------------------------------------- summary

section "SUMMARY"

echo "  checks run : $CHECKS_RUN"
echo "  failures   : $FAILURES"
if [ "$ACCEPTED_FAILURES" -gt 0 ]; then
  echo "  accepted   : $ACCEPTED_FAILURES (declared in accepted-failures.json)"
fi
if [ "$SKIPPED" -gt 0 ]; then
  echo "  skipped    : $SKIPPED (could not be answered on this run -- each named below)"
fi
echo

if [ "$SKIPPED" -gt 0 ]; then
  # Named at the bottom, where a reader skimming for a verdict will see them. A skip that only
  # appears mid-scroll is halfway to a silent skip, and a silent skip is how this harness stopped
  # testing anything at all the last time its README anchor moved.
  c_yel "  SKIPPED ($SKIPPED) -- verified nothing; the reason is printed beside each one above:$SKIP_LIST"
  echo
fi

if [ "$FAILURES" -eq 0 ]; then
  if [ "$ACCEPTED_FAILURES" -gt 0 ]; then
    # Never a silent green. The accepted ones are named again here, at the bottom, where someone
    # skimming for a verdict will actually see them.
    c_yel "  GREEN with $ACCEPTED_FAILURES accepted failure(s):$ACCEPTED_LIST"
    c_yel "  Each is justified in accepted-failures.json. Re-read that file when this list grows."
    echo
  fi
  c_grn "  GREEN -- a bare machine can follow README.md end to end."
  exit 0
fi

c_red "  RED -- $FAILURES documented step(s) do not work on a clean machine."
echo
c_yel "  Before the documentation fixes this harness was written against, FOUR failures were expected:"
echo "     prereq-present (python3 / pwsh) · documents-building-platform-jars"
echo "     documents-bootJar-before-run    · documents-login-key"
echo "  (app-jar-exists was the fifth. It is now a SKIP, not a failure, whenever README's own"
echo "   app-producing command is a watch loop this harness defers -- see the skip's printed reason.)"
echo
c_yel "  A harness that is green on its first run is not testing anything."
exit 1
