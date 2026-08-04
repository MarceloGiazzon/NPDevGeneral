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
: "${APP_PORT:=8080}"

# ---------------------------------------------------------------- output helpers

c_red()  { printf '\033[31m%s\033[0m\n' "$*"; }
c_grn()  { printf '\033[32m%s\033[0m\n' "$*"; }
c_yel()  { printf '\033[33m%s\033[0m\n' "$*"; }
hr()     { printf '%.0s-' {1..78}; echo; }

section() { echo; hr; echo "== $*"; hr; }

pass() { CHECKS_RUN=$((CHECKS_RUN+1)); c_grn "  PASS  $1"; }

fail() {
  CHECKS_RUN=$((CHECKS_RUN+1)); FAILURES=$((FAILURES+1))
  c_red "  FAIL  $1"
  [ $# -gt 1 ] && echo "        why: $2"
  [ $# -gt 2 ] && echo "        fix: $3"
  return 0
}

die() { c_red "HARNESS ERROR: $*"; exit 2; }

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

[ -f "$SRC/README.md" ] || die "no README.md at the repo root"
cd "$SRC" || die "cannot cd $SRC"
echo "  HEAD: $(git -C "$SRC" log -1 --format='%h %s' 2>/dev/null | cut -c1-60)"

# ---------------------------------------------------------------- 1. prerequisites

section "1. Install ONLY what README's prerequisites sentence names"

# Pull the sentence that states requirements. We look for the line containing
# "Requires" inside (or just after) the Quickstart heading.
PREREQ_LINE=$(grep -m1 -i '^Requires\|^\*\*Requires\|Requires Java' README.md || true)

if [ -z "$PREREQ_LINE" ]; then
  fail "prereqs-declared" \
       "README has no recognisable 'Requires ...' sentence" \
       "state prerequisites explicitly near the Quickstart heading"
else
  echo "  README says: $PREREQ_LINE"
  pass "prereqs-declared"
fi

want() { echo "$PREREQ_LINE" | grep -qi "$1"; }

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
# This is the real prerequisite test: NPDev needs java + python + pwsh. If README
# failed to name any of them, the corresponding check fails HERE, with a message
# that names the documentation defect rather than a confusing downstream error.

for tool_spec in "java:Java 17" "python3:Python 3" "pwsh:PowerShell 7"; do
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

# ---------------------------------------------------------------- 2. quickstart

section "2. Run README's Quickstart commands, verbatim and in order"

# Extract fenced code blocks that appear under the Quickstart heading, up to the
# next '## ' heading. We run only lines that look like commands (skip comments
# and blank lines), substituting the placeholder output path.
QUICKSTART=$(awk '
  /^##[[:space:]]+Quickstart/ { inq=1; next }
  inq && /^##[[:space:]]/     { inq=0 }
  inq                          { print }
' README.md)

if [ -z "$QUICKSTART" ]; then
  fail "quickstart-section" "no '## Quickstart' section found in README.md" \
       "the harness keys off that heading"
else
  pass "quickstart-section"
fi

# Collect commands from fenced blocks, joining backslash continuations. LOCAL_SRC mode (see
# harness/README.md's own documented "pre-merge" use case) mounts whatever line endings the
# HOST checkout has -- on Windows with core.autocrlf, that is CRLF, even though the repository's
# own git blob is LF (confirmed: `git show HEAD:README.md` has none). A trailing \r left on an
# extracted line survives as a literal, invisible character on the last token of that line (a
# file path, most often), producing a baffling "not found" for a path that is visibly correct in
# the log. Strip it here rather than depending on every host's checkout matching git's own
# normalization, since LOCAL_SRC exists specifically to test an UNCOMMITTED tree.
CMDS=$(printf '%s\n' "$QUICKSTART" | tr -d '\r' | awk '
  /^```/ { infence = !infence; next }
  infence { print }
' | sed 's/[[:space:]]*#.*$//' | grep -v '^[[:space:]]*$' \
  | sed ':a;/\\$/{N;s/\\\n[[:space:]]*/ /;ba}')

OUT=/work/my-first-app
CMDS=$(printf '%s\n' "$CMDS" | sed "s|/path/outside/this/repo/canonical-demo-app|$OUT|g")

echo "  commands found:"
printf '%s\n' "$CMDS" | sed 's/^/    $ /'

DID_BOOTJAR=0
DID_SYNC=0
# A real terminal keeps ONE persistent working directory across a whole session -- `cd` in one
# line changes where the NEXT line runs. Each extracted command here runs in its own throwaway
# `bash -c`, so without tracking this ourselves, a `cd` command would be a no-op for every command
# after it (README's own documented sequence -- validate/sync/generate from the repo root, then cd
# into the generated app, then build/run it there -- depends entirely on this actually working).
CURRENT_DIR="$SRC"
while IFS= read -r cmd; do
  [ -z "$cmd" ] && continue
  case "$cmd" in
    *sync-runtimehost-libs*) DID_SYNC=1 ;;
    *bootJar*)               DID_BOOTJAR=1 ;;
  esac
  # README documents TWO ways to run the built app -- a foreground `java -jar` and
  # `docker compose up` -- both of which are meant to occupy the terminal and never
  # return on their own. Running either one here, in a loop that waits for each command
  # to EXIT before trying the next, would hang the harness forever on the first one it
  # hits. Section 3 below already starts the jar itself (backgrounded, with a real
  # readiness poll and a kill at the end) -- that is the harness's actual "does it run"
  # proof; these two lines only need to be RECOGNIZED as documented, not executed here.
  case "$cmd" in
    java\ -jar\ *|docker\ compose\ up*)
      echo
      echo "  \$ $cmd"
      echo "        (long-running/foreground command -- not executed by this loop; see section 3)"
      pass "cmd (recognized, deferred to section 3): $(echo "$cmd" | cut -c1-40)"
      continue
      ;;
  esac
  # A bare `cd <path>` only updates OUR tracked directory -- there is nothing else useful to
  # execute-and-check about it, and running it via bash -c would (correctly, but uselessly)
  # report success while telling us nothing about whether the target even exists.
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
        pass "cmd: $(echo "$cmd" | cut -c1-58)"
      else
        fail "cmd: $(echo "$cmd" | cut -c1-58)" "directory does not exist: $candidate"
      fi
      continue
      ;;
  esac
  echo
  echo "  \$ $cmd"
  # </dev/null is load-bearing: this loop's input comes from a here-string ($CMDS), inherited by
  # every subshell we spawn since none of them redirect their own stdin. A command that reads or
  # even just probes stdin (pwsh does, at startup) silently consumes the REST of that here-string,
  # so the outer `read -r cmd` hits EOF on the NEXT iteration and the loop ends after ONE command
  # with no error at all -- discovered when a real run processed only the first Quickstart command
  # (validate model) and silently skipped all seven after it, no failure printed for any of them.
  if bash -c "cd '$CURRENT_DIR' && $cmd" >>"$LOG" 2>&1 </dev/null; then
    pass "cmd: $(echo "$cmd" | cut -c1-58)"
  else
    fail "cmd: $(echo "$cmd" | cut -c1-58)" \
         "exited non-zero; tail of output below" \
         "see $LOG"
    tail -12 "$LOG" | sed 's/^/          | /'
  fi
done <<< "$CMDS"

# --- The two structural gaps, checked explicitly -------------------------------

if [ "$DID_SYNC" = "1" ]; then
  pass "documents-building-platform-jars"
else
  fail "documents-building-platform-jars" \
       "README never tells the user to build runtimehost-libs; NPDev's kernel jars are not on Maven Central, so the generated app cannot compile without them (W1)" \
       "add 'npdev setup' (or sync-runtimehost-libs.ps1 -BuildLocalJars) as the FIRST quickstart step"
fi

if [ "$DID_BOOTJAR" = "1" ]; then
  pass "documents-bootJar-before-run"
else
  fail "documents-bootJar-before-run" \
       "README goes straight to 'docker compose up', but the generated Dockerfile COPYs an ALREADY-BUILT jar -- its own first comment says to run ./gradlew bootJar first (W2)" \
       "insert './gradlew bootJar' before any run instruction"
fi

# ---------------------------------------------------------------- 3. serve

section "3. Does the app actually run, on the documented port?"

JAR=$(find "$OUT" -name '*.jar' -path '*build/libs*' 2>/dev/null | head -1)

if [ -z "$JAR" ]; then
  fail "app-jar-exists" \
       "no build/libs/*.jar under $OUT -- nothing to run" \
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

  # W3: is the login path documented, and does it materialise?
  if grep -rq 'SUPER_USER_KEY' README.md docs/GETTING_STARTED.md 2>/dev/null; then
    pass "documents-login-key"
  else
    fail "documents-login-key" \
         "neither README nor GETTING_STARTED mentions SUPER_USER_KEY.txt -- the user has a running app and no way in (W3)" \
         "print the URL and key location at the end of 'generate app', and document both"
  fi

  if grep -rqE 'localhost:[0-9]{4}' README.md docs/GETTING_STARTED.md 2>/dev/null; then
    pass "documents-app-url"
  else
    fail "documents-app-url" "no localhost URL documented" "state http://localhost:$APP_PORT"
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
RUN_APP_OUT=/work/my-run-app
RUN_APP_PORT=8081
cd "$SRC" || die "cannot cd to $SRC for section 4"
if RUN_APP_JSON=$(./npdev run app --model NPDevContract/dsl/resources/Models/canonical-demo/model.json \
    --config NPDevContract/dsl/resources/Models/canonical-demo/config.json \
    --output "$RUN_APP_OUT" --port "$RUN_APP_PORT" --timeout 300 2>>"$LOG"); then
  if printf '%s' "$RUN_APP_JSON" | grep -q '"ok": true'; then
    pass "npdev run app (one-shot generate+build+boot) succeeds"
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

# ---------------------------------------------------------------- summary

section "SUMMARY"

echo "  checks run : $CHECKS_RUN"
echo "  failures   : $FAILURES"
echo

if [ "$FAILURES" -eq 0 ]; then
  c_grn "  GREEN -- a bare machine can follow README.md end to end."
  exit 0
fi

c_red "  RED -- $FAILURES documented step(s) do not work on a clean machine."
echo
c_yel "  Before the documentation fixes, FOUR failures are expected:"
echo "     prereq-present (python3 / pwsh) · documents-building-platform-jars"
echo "     documents-bootJar-before-run    · app-jar-exists / documents-login-key"
echo
c_yel "  A harness that is green on its first run is not testing anything."
exit 1
