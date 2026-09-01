#!/bin/sh
# SEC-3 fork (a): make the container's own cgroup v2 subtree usable as a resource-limit parent,
# then exec the real command.
#
# WHY THIS IS NEEDED AT ALL
# -------------------------
# cgroup v2 has a "no internal process" rule: a non-root cgroup may hold member PROCESSES, or it
# may enable controllers for its CHILDREN via cgroup.subtree_control -- never both. A container's
# /sys/fs/cgroup (with --cgroupns=private) LOOKS like a root, but it is a non-root cgroup in the
# host's real hierarchy, and it holds every process in the container. So out of the box:
#
#   cgroup.subtree_control is EMPTY  ->  a freshly created child cgroup has NO memory.max file
#                                    ->  writing memory.max fails with ENOENT
#                                    ->  PluginLinuxCgroupResourceLimiter's raw-cgroup fallback
#                                        silently degrades and the plugin runs UNLIMITED.
#
# The fix is the same dance systemd does at boot: move every process into a leaf cgroup first,
# leaving the (namespaced) root empty, and only then enable the controllers for its children.
#
# After this script runs:
#   /sys/fs/cgroup/                 <- empty of processes, subtree_control = "cpu memory"
#   /sys/fs/cgroup/init/            <- holds the JVM (and everything else)
#   /sys/fs/cgroup/npdev-plugin-N/  <- created at run time by the limiter, as a SIBLING of init
#
# That sibling placement is exactly why PluginLinuxCgroupResourceLimiter must be changed (plan step
# A3): as written it creates the plugin cgroup as a CHILD of its own cgroup (/init), and /init can
# never enable controllers for children while the JVM lives in it.
#
# REQUIRED docker run flags: --privileged --cgroupns=private
#   --privileged      : without it /sys/fs/cgroup is mounted read-only and every write below fails.
#   --cgroupns=private: makes /sys/fs/cgroup the CONTAINER's own cgroup rather than the host's.
#
# Exit codes: 64 = the cgroup filesystem is not writable (wrong docker flags -- a setup error, not
# a product defect, and deliberately distinct from the test's own pass/fail).
set -eu

ROOT=/sys/fs/cgroup

if [ ! -e "$ROOT/cgroup.controllers" ]; then
    echo "cgroup-delegate-init: $ROOT/cgroup.controllers is missing -- this host is not running cgroup v2's unified hierarchy." >&2
    exit 64
fi
if [ ! -w "$ROOT/cgroup.procs" ]; then
    echo "cgroup-delegate-init: $ROOT is not writable -- re-run the container with --privileged --cgroupns=private." >&2
    exit 64
fi

mkdir -p "$ROOT/init"

# Move every process out of the namespaced root into the leaf. cgroup.procs accepts ONE pid per
# write, and the file shrinks as we drain it, so re-read it until it is empty. A pid that has
# already exited makes the write fail (ESRCH, or EIO on this driver) -- ignore that, it is not an
# error here.
#
# cgroup.procs is a kernfs pseudo-file: stat() always reports st_size=0 regardless of how many pids
# it actually lists, so `[ -s cgroup.procs ]` is unconditionally false and can never be used to
# detect "still has members" -- it has to be judged by content, not size.
attempts=0
remaining=$(cat "$ROOT/cgroup.procs" 2>/dev/null)
while [ -n "$remaining" ] && [ "$attempts" -lt 50 ]; do
    printf '%s\n' "$remaining" | while read -r pid; do
        [ -n "$pid" ] || continue
        echo "$pid" > "$ROOT/init/cgroup.procs" 2>/dev/null || true
    done
    attempts=$((attempts + 1))
    remaining=$(cat "$ROOT/cgroup.procs" 2>/dev/null)
done
if [ -n "$remaining" ]; then
    echo "cgroup-delegate-init: could not drain $ROOT/cgroup.procs after $attempts passes; still holding:" >&2
    printf '%s\n' "$remaining" >&2
    exit 64
fi

available=$(cat "$ROOT/cgroup.controllers")
for controller in memory cpu; do
    case " $available " in
        *" $controller "*)
            echo "+$controller" > "$ROOT/cgroup.subtree_control"
            ;;
        *)
            echo "cgroup-delegate-init: controller '$controller' is not available at $ROOT (have: $available)" >&2
            ;;
    esac
done

# This is a transparent wrapper around "$@" -- its own status line goes to stderr so it never
# pollutes the wrapped command's stdout (e.g. probe-cgroup-environment.sh's JSON).
echo "cgroup-delegate-init: ready. subtree_control='$(cat "$ROOT/cgroup.subtree_control")' self='$(cat /proc/self/cgroup)'" >&2

exec "$@"
