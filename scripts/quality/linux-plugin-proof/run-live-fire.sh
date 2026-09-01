#!/bin/sh
# GOES AT scripts/quality/linux-plugin-proof/run-live-fire.sh (LF, chmod +x).
#
# The post-delegation phase of the SEC-3 fork (a) live-fire proof, invoked as the "$@" that
# cgroup-delegate-init.sh execs once the container's own cgroup subtree is delegating memory/cpu to
# its children. Kept as its own script file (rather than a second nested `bash -lc '...'` string)
# so the caller (run-linux-plugin-resource-proof.ps1) never has to build a doubly-quoted shell
# command line -- one docker CLI argument, one bash -lc string, done.
set -eu

scripts/quality/linux-plugin-proof/probe-cgroup-environment.sh > /npdev-build/cgroup-after.json
cat /npdev-build/cgroup-after.json

# Same command .github/workflows/publish-runtimehost-libs.yml uses to build and stage every jar
# runtimehost-core compiles against -- no second, divergent recipe to keep in step.
chmod +x ./npdev
./npdev setup --build-local

cd NPDevRuntimeHost/runtimehost-core
./gradlew --no-daemon --console=plain test \
    --tests "com.finalexec.npdev.service.pluginipc.PluginIpcChildProcessLinuxResourceLimitTest"
