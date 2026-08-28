#!/usr/bin/env bash
#
# Level-2 harness entrypoint (I5/A1): assert `npdev doctor` is HONEST about a machine whose only
# JDK is ${JDK_VERSION} (not 17). See Dockerfile.wrongjava's own header for why this exists and
# how to build/run it.
#
# D1 (Cold Clone Audit fix, 2026-08-28): this test used to assert doctor FAILS on a >17-only
# machine. The foojay toolchain resolver (now registered in the platform's own builds, A1) makes a
# >17-only machine genuinely fine -- Gradle auto-provisions the 17 toolchain it needs -- so the
# contract this test pins is the NEW one: doctor must NOT block the machine, and must NAME the
# version it found so the reader understands why the message is a warning and not a pass.
#
# Exit codes:  0 = doctor exited 0 AND its warning names the found version
#              1 = doctor blocked the machine, or warned without naming the version (a real bug)
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

# NEW contract (D1): a >17-only machine is workable (foojay auto-provisions the 17 toolchain), so
# doctor must exit 0 (warnings never block) while naming the found version so the warning is
# explainable -- NOT silently passing over it. Exit non-zero OR a warning that fails to name the
# version are both bugs, and "silent pass" is the worst of the three because it lies about being
# checked.
if [ "$RC" -eq 0 ] && echo "$OUTPUT" | grep -q "${JDK_VERSION:-__unset__}"; then
  echo "PASS -- doctor did not block the JDK ${JDK_VERSION} machine and named the found version"
  exit 0
fi

echo "FAIL -- doctor blocked the machine, or warned without naming Java ${JDK_VERSION}"
exit 1