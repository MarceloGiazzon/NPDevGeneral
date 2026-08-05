#!/usr/bin/env bash
#
# Level-2 harness entrypoint (I5): assert `npdev doctor` fails, and names the wrong Java version,
# on a machine that has JDK ${JDK_VERSION} installed instead of the required 17. See
# Dockerfile.wrongjava's own header for why this exists and how to build/run it.
#
# Exit codes:  0 = doctor correctly failed and named the wrong version
#              1 = doctor did NOT catch it (a real bug, not a harness problem)
#              2 = harness itself could not run (clone failed, ...)

set -uo pipefail

SRC=/work/src

if [ "${LOCAL_SRC:-0}" = "1" ]; then
  [ -d "$SRC" ] || { echo "HARNESS ERROR: LOCAL_SRC=1 but $SRC is not mounted"; exit 2; }
else
  echo "cloning $REPO_URL (ref: $REPO_REF)"
  git clone --depth 1 --branch "$REPO_REF" "$REPO_URL" "$SRC" >/dev/null 2>&1 \
    || { echo "HARNESS ERROR: clone failed: $REPO_URL @ $REPO_REF"; exit 2; }
fi

cd "$SRC" || { echo "HARNESS ERROR: cannot cd $SRC"; exit 2; }

echo "------------------------------------------------------------------------------"
echo "Level 2: npdev doctor against a machine with JDK ${JDK_VERSION:-?} (not 17)"
echo "------------------------------------------------------------------------------"
OUTPUT=$(python3 NPDevCli/npdev_cli.py doctor 2>&1)
RC=$?

echo "$OUTPUT"
echo
echo "exit code: $RC"
echo

if [ "$RC" -ne 0 ] && echo "$OUTPUT" | grep -q "${JDK_VERSION:-__unset__}"; then
  echo "PASS -- doctor correctly failed and named Java ${JDK_VERSION}"
  exit 0
fi

echo "FAIL -- doctor did not catch the wrong Java version (or did not name it)"
exit 1
