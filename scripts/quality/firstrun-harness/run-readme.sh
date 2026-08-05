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
run_cmd_list() {
  local label_prefix="$1"
  local cmd target candidate
  while IFS= read -r cmd; do
    [ -z "$cmd" ] && continue
    case "$cmd" in
      *java\ -jar*|*docker\ compose\ up*)
        echo
        echo "  \$ $cmd"
        echo "        (long-running/foreground command -- not executed by this loop)"
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
    if bash -c "cd '$CURRENT_DIR' && $cmd" >>"$LOG" 2>&1 </dev/null; then
      pass "$label_prefix: $(echo "$cmd" | cut -c1-58)"
    else
      fail "$label_prefix: $(echo "$cmd" | cut -c1-58)" \
           "exited non-zero; tail of output below" \
           "see $LOG"
      tail -12 "$LOG" | sed 's/^/          | /'
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
  | sed ':a;/\\$/{N;s/\\\n[[:space:]]*/ /;ba}' \
  | sed 's/ && /\n/g')

OUT=/work/my-first-app
CMDS=$(printf '%s\n' "$CMDS" | sed "s|/path/outside/this/repo/canonical-demo-app|$OUT|g")

echo "  commands found:"
printf '%s\n' "$CMDS" | sed 's/^/    $ /'

DID_BOOTJAR=0
DID_SYNC=0
printf '%s\n' "$CMDS" | grep -q 'sync-runtimehost-libs' && DID_SYNC=1
printf '%s\n' "$CMDS" | grep -q 'bootJar'               && DID_BOOTJAR=1

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

# ---------------------------------------------------------------- 5. change-a-field

section "5. change-a-field -- prove the product, not just the install (I2)"

# The point of this whole section: "regeneration succeeded" is not the claim that matters --
# "the field the user added is actually there" is. So this asserts on the running API's own
# response, not on a log line saying a command exited zero.
CAF_DIR=/work/change-a-field
CAF_OUT="$OUT"          # the SAME --output section 2/3 already generated -- proves in-place
                          # schema evolution, not a fresh directory that would prove nothing
CAF_CONCEPT="Provider"
CAF_FIELD_JSON='{"name": "phone", "type": "string", "ui": {"label": "Phone"}}'
CAF_PORT=8083

cd "$SRC" || die "cannot cd to $SRC for section 5"

if [ ! -d "$CAF_OUT" ]; then
  fail "change-a-field: prior app exists at $CAF_OUT" \
       "section 2/3 above did not produce $CAF_OUT -- this check regenerates against the SAME --output on purpose (the plan's own step 1: 'generate + run the demo app (already covered)')" \
       "check sections 2/3 above"
else
  pass "change-a-field: prior app exists at $CAF_OUT"

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
# (model.json, db.definition.json) or a field snippet to inject, not commands -- so extraction
# has to be step-aware (numbered "## N. Title" headings) and fence-language-aware, unlike
# README's Quickstart which is one heading and 100% ```sh. Step 8 (renaming) is illustrative
# only -- deliberately excluded, since running it would try to rename a field this app doesn't
# have a reason to rename.
YFA_DOC="$SRC/docs/YOUR_FIRST_APP.md"
YFA_WORK=/work/my-library
YFA_APP=/work/my-library-app
YFA_PORT=8084

if [ ! -f "$YFA_DOC" ]; then
  fail "your-first-app: doc exists" "$YFA_DOC not found"
else
  pass "your-first-app: doc exists"

  rm -rf "$YFA_WORK" "$YFA_APP"
  mkdir -p "$YFA_WORK"

  python3 - "$YFA_DOC" > /work/yfa-steps.json <<'PYEOF'
import json, re, sys
doc = open(sys.argv[1], encoding="utf-8").read()
steps = []
for sec in re.split(r"(?m)^## ", doc)[1:]:
    title, body = sec.split("\n", 1)
    m = re.match(r"(\d+)\.", title)
    if not m or int(m.group(1)) > 7:
        continue
    blocks = [{"lang": lang, "body": b} for lang, b in re.findall(r"```(sh|json)\n(.*?)\n```", body, re.S)]
    steps.append({"n": int(m.group(1)), "title": title.strip(), "blocks": blocks})
json.dump(steps, sys.stdout, indent=2)
PYEOF

  if [ ! -s /work/yfa-steps.json ]; then
    fail "your-first-app: doc parses into numbered steps" "extraction produced nothing -- see the doc's '## N. Title' headings"
  else
    pass "your-first-app: doc parses into numbered steps"

    STEP_COUNT=$(python3 -c "import json; print(len(json.load(open('/work/yfa-steps.json'))))")
    echo "  steps 1-7 found: $STEP_COUNT"

    # subst <text> -- rewrite the doc's own relative paths onto this container's /work layout.
    # Longest/most-specific pattern first: "../my-library-app" is a superstring of "../my-library".
    subst() {
      sed -e "s|\\.\\./my-library-app|$YFA_APP|g" \
          -e "s|\\.\\./my-library|$YFA_WORK|g" \
          -e "s|\\.\\./NPDevGeneral|$SRC|g"
    }

    # yfa_block <step-n> <block-index> -- the doc's per-step blocks are processed one at a
    # time, explicitly, in document order (which step holds a file, which holds a snippet,
    # which holds commands is fixed and small -- 7 steps), rather than a generic block-walking
    # loop: explicit is more honest here than a clever parser this small, rarely-changing doc
    # does not need.
    yfa_block() { python3 -c "
import json
steps = json.load(open('/work/yfa-steps.json'))
step = next((s for s in steps if s['n'] == $1), None)
print(step['blocks'][$2]['body'] if step and len(step['blocks']) > $2 else '', end='')
"; }

    # Step 1 (sh): mkdir + cp -- runs from the repo root, same as every other step below.
    # (run_cmd_list is invoked via `<<<`, not a pipe -- a pipe's last stage runs in a subshell
    # in bash by default, and pass/fail's CHECKS_RUN/FAILURES mutations would silently vanish
    # the moment that subshell exited, undercounting every check inside it. `<<<` redirects
    # stdin without forking, so counters and CURRENT_DIR both persist correctly.)
    CURRENT_DIR="$SRC"
    S1=$(yfa_block 1 0 | subst | sed 's/ && /\n/g')
    if [ -n "$S1" ]; then run_cmd_list "your-first-app step1" <<< "$S1"
    else fail "your-first-app step1: block present" "no sh block found under step 1"; fi

    # Step 2 (json): full model.json content
    S2=$(yfa_block 2 0)
    if [ -n "$S2" ]; then
      printf '%s\n' "$S2" > "$YFA_WORK/model.json"
      if python3 -c "import json; json.load(open('$YFA_WORK/model.json'))" >>"$LOG" 2>&1; then
        pass "your-first-app step2: model.json written and is valid JSON"
      else
        fail "your-first-app step2: model.json written and is valid JSON" "see $LOG"
      fi
    else
      fail "your-first-app step2: block present" "no json block found under step 2"
    fi

    # Step 3 (json): full db.definition.json content
    S3=$(yfa_block 3 0)
    if [ -n "$S3" ]; then
      printf '%s\n' "$S3" > "$YFA_WORK/db.definition.json"
      if python3 -c "import json; json.load(open('$YFA_WORK/db.definition.json'))" >>"$LOG" 2>&1; then
        pass "your-first-app step3: db.definition.json written and is valid JSON"
      else
        fail "your-first-app step3: db.definition.json written and is valid JSON" "see $LOG"
      fi
    else
      fail "your-first-app step3: block present" "no json block found under step 3"
    fi

    # Step 4 (sh): git init -- AFTER model.json/db.definition.json exist, so both get tracked
    # (this ordering is itself the fix for a real bug the doc's own first draft had: git init
    # BEFORE those files existed meant they were never `git add`ed, and the closing `git commit
    # -am` at step 7 silently committed nothing).
    S4=$(yfa_block 4 0 | subst | sed 's/ && /\n/g')
    CURRENT_DIR="$SRC"
    if [ -n "$S4" ]; then run_cmd_list "your-first-app step4" <<< "$S4"
    else fail "your-first-app step4: block present" "no sh block found under step 4"; fi

    if git -C "$YFA_WORK" log --oneline >>"$LOG" 2>&1; then
      pass "your-first-app step4: git history exists after git init"
    else
      fail "your-first-app step4: git history exists after git init" "see $LOG"
    fi

    # Step 5 (sh): cd back to repo root + validate
    S5=$(yfa_block 5 0 | subst | sed 's/ && /\n/g')
    CURRENT_DIR="$SRC"
    if [ -n "$S5" ]; then run_cmd_list "your-first-app step5" <<< "$S5"
    else fail "your-first-app step5: block present" "no sh block found under step 5"; fi

    # Step 6 (sh): generate, cd, gradlew bootJar, java -jar (deferred by run_cmd_list)
    S6=$(yfa_block 6 0 | subst | sed 's/ && /\n/g')
    CURRENT_DIR="$SRC"
    if [ -n "$S6" ]; then run_cmd_list "your-first-app step6" <<< "$S6"
    else fail "your-first-app step6: block present" "no sh block found under step 6"; fi

    # Step 6's real "does it run" proof -- boot the built jar and hit the documented dev-key path.
    YFA_JAR=$(find "$YFA_APP" -name '*.jar' -path '*build/libs*' 2>/dev/null | head -1)
    if [ -z "$YFA_JAR" ]; then
      fail "your-first-app step6: jar exists after build" "no build/libs/*.jar under $YFA_APP"
    else
      pass "your-first-app step6: jar exists after build"
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
        pass "your-first-app step6: app responds on :$YFA_PORT"
        YFA_BOOK=$(curl -sS -X POST "http://localhost:$YFA_PORT/api/books" \
          -H 'X-Api-Key: dev-key' -H 'Content-Type: application/json' \
          -d '{"title":"The Hobbit","isbn":"9780345339683","copies":1}' 2>>"$LOG")
        if printf '%s' "$YFA_BOOK" | grep -q '"title"[[:space:]]*:[[:space:]]*"The Hobbit"'; then
          pass "your-first-app step6: dev-key creates a Book over the documented REST API"
        else
          fail "your-first-app step6: dev-key creates a Book over the documented REST API" \
               "see $LOG"; printf '%s' "$YFA_BOOK" | tail -c 500 | sed 's/^/          | /'
        fi
      else
        fail "your-first-app step6: app responds on :$YFA_PORT" "no HTTP response within 120s" "see /work/yfa-app.log"
        tail -15 /work/yfa-app.log | sed 's/^/          | /'
      fi
      kill "$YFA_PID" 2>/dev/null; wait "$YFA_PID" 2>/dev/null
    fi

    # Step 7 (json #0): the publishedYear field snippet -- inject it (scripted, same mechanism
    # as change-a-field), then run the step's sh block (json #... none further; sh block #0).
    S7_FIELD=$(yfa_block 7 0)
    if [ -n "$S7_FIELD" ] && python3 /usr/local/bin/inject_field.py "$YFA_WORK/model.json" "Book" "$S7_FIELD" >>"$LOG" 2>&1; then
      pass "your-first-app step7: publishedYear field injected into Book"
    else
      fail "your-first-app step7: publishedYear field injected into Book" "see $LOG"
    fi

    S7=$(yfa_block 7 1 | subst | sed 's/ && /\n/g')
    CURRENT_DIR="$SRC"
    if [ -n "$S7" ]; then run_cmd_list "your-first-app step7" <<< "$S7"
    else fail "your-first-app step7: sh block present" "no second (sh) block found under step 7"; fi

    # Step 7's real proof: the field the user "added" is actually there, and the book created
    # in step 6 SURVIVED the regenerate+rebuild+restart (H2Local, per step 3's db.definition.json)
    # rather than only proving a fresh, empty app boots.
    YFA_JAR2=$(find "$YFA_APP" -name '*.jar' -path '*build/libs*' 2>/dev/null | head -1)
    if [ -z "$YFA_JAR2" ]; then
      fail "your-first-app step7: jar exists after rebuild" "no build/libs/*.jar under $YFA_APP"
    else
      pass "your-first-app step7: jar exists after rebuild"
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
        pass "your-first-app step7: app responds after regenerate+rebuild+restart"
        YFA_LIST=$(curl -sS "http://localhost:$YFA_PORT/api/books" -H 'X-Api-Key: dev-key' 2>>"$LOG")
        if printf '%s' "$YFA_LIST" | grep -q '"publishedYear"'; then
          pass "your-first-app step7: publishedYear field reachable via REST"
        else
          fail "your-first-app step7: publishedYear field reachable via REST" "see $LOG"
          printf '%s' "$YFA_LIST" | tail -c 500 | sed 's/^/          | /'
        fi
        if printf '%s' "$YFA_LIST" | grep -q '"title"[[:space:]]*:[[:space:]]*"The Hobbit"'; then
          pass "your-first-app step7: the book created in step 6 survived the schema change"
        else
          fail "your-first-app step7: the book created in step 6 survived the schema change" \
               "H2Local should have kept it -- see $LOG"
          printf '%s' "$YFA_LIST" | tail -c 500 | sed 's/^/          | /'
        fi
      else
        fail "your-first-app step7: app responds after regenerate+rebuild+restart" \
             "no HTTP response within 120s" "see /work/yfa-app2.log"
        tail -15 /work/yfa-app2.log | sed 's/^/          | /'
      fi
      kill "$YFA_PID2" 2>/dev/null; wait "$YFA_PID2" 2>/dev/null
    fi

    # Step 7's closing git commit -am -- proves the doc's own restructured step ordering
    # actually fixed the "nothing to commit" bug found while writing it.
    S7_COMMIT=$(yfa_block 7 2 | subst | sed 's/ && /\n/g')
    CURRENT_DIR="$SRC"
    if [ -n "$S7_COMMIT" ]; then run_cmd_list "your-first-app step7-commit" <<< "$S7_COMMIT"
    else fail "your-first-app step7: closing commit block present" "no third block found under step 7"; fi

    if [ "$(git -C "$YFA_WORK" log --oneline 2>>"$LOG" | wc -l)" -ge 2 ]; then
      pass "your-first-app step7: git commit -am actually committed the tracked change"
    else
      fail "your-first-app step7: git commit -am actually committed the tracked change" \
           "expected 2 commits (start + publishedYear); see $LOG"
    fi
  fi
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
