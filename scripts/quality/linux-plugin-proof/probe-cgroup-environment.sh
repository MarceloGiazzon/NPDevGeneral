#!/bin/sh
# STEP A2 of the plan: characterise the container's cgroup v2 environment BEFORE touching any Java.
# Prints one JSON object on stdout. Run it INSIDE the container, both before and after
# cgroup-delegate-init.sh, and keep both outputs as evidence.
#
#   docker run ... <image> sh -c 'scripts/quality/linux-plugin-proof/probe-cgroup-environment.sh'
#   docker run ... <image> scripts/quality/linux-plugin-proof/cgroup-delegate-init.sh \
#       scripts/quality/linux-plugin-proof/probe-cgroup-environment.sh
#
# The second run is the one that must show:
#   "rootSubtreeControl": "cpu memory"   (or at least containing "memory")
#   "canCreateSiblingWithMemoryMax": true
# If it does not, the Docker flags are wrong -- fix that before blaming the Java.
set -u

ROOT=/sys/fs/cgroup
q() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g; s/\t/ /g' | tr -d '\n'; }
readfile() { [ -r "$1" ] && cat "$1" 2>/dev/null | tr '\n' ' ' | sed 's/ *$//' || printf '<unreadable>'; }

selfCgroup=$(readfile /proc/self/cgroup)
relative=$(printf '%s' "$selfCgroup" | sed 's/^0:://')
case "$relative" in /*) relative=${relative#/} ;; esac
if [ -z "$relative" ]; then ownDir="$ROOT"; else ownDir="$ROOT/$relative"; fi
parentDir=$(dirname "$ownDir")

# Can we create a SIBLING of our own cgroup and give it a memory ceiling? This is the exact
# operation the fixed PluginLinuxCgroupResourceLimiter performs.
probe="$parentDir/npdev-probe-$$"
canSibling=false
siblingError=""
if mkdir "$probe" 2>/dev/null; then
    if [ -e "$probe/memory.max" ] && echo 134217728 > "$probe/memory.max" 2>/dev/null; then
        canSibling=true
    else
        siblingError="memory.max absent or unwritable in $probe (parent subtree_control lacks +memory)"
    fi
    rmdir "$probe" 2>/dev/null || true
else
    siblingError="mkdir $probe failed (parent not writable)"
fi

# And the CHILD-of-own-cgroup operation the CURRENT code performs -- expected to fail.
childProbe="$ownDir/npdev-probe-child-$$"
canChild=false
childError=""
if mkdir "$childProbe" 2>/dev/null; then
    if [ -e "$childProbe/memory.max" ] && echo 134217728 > "$childProbe/memory.max" 2>/dev/null; then
        canChild=true
    else
        childError="memory.max absent or unwritable (no-internal-process rule: our own cgroup holds this shell)"
    fi
    rmdir "$childProbe" 2>/dev/null || true
else
    childError="mkdir $childProbe failed"
fi

systemdRun=false
command -v systemd-run >/dev/null 2>&1 && systemd-run --user --scope --quiet --collect -- /bin/true >/dev/null 2>&1 && systemdRun=true

printf '{\n'
printf '  "kernel": "%s",\n'                       "$(q "$(uname -sr)")"
printf '  "unifiedHierarchy": %s,\n'               "$([ -e "$ROOT/cgroup.controllers" ] && echo true || echo false)"
printf '  "cgroupMountWritable": %s,\n'            "$([ -w "$ROOT/cgroup.procs" ] && echo true || echo false)"
printf '  "procSelfCgroup": "%s",\n'               "$(q "$selfCgroup")"
printf '  "ownCgroupDir": "%s",\n'                 "$(q "$ownDir")"
printf '  "parentCgroupDir": "%s",\n'              "$(q "$parentDir")"
printf '  "rootControllers": "%s",\n'              "$(q "$(readfile "$ROOT/cgroup.controllers")")"
printf '  "rootSubtreeControl": "%s",\n'           "$(q "$(readfile "$ROOT/cgroup.subtree_control")")"
printf '  "parentSubtreeControl": "%s",\n'         "$(q "$(readfile "$parentDir/cgroup.subtree_control")")"
printf '  "ownSubtreeControl": "%s",\n'            "$(q "$(readfile "$ownDir/cgroup.subtree_control")")"
printf '  "canCreateSiblingWithMemoryMax": %s,\n'  "$canSibling"
printf '  "siblingError": "%s",\n'                 "$(q "$siblingError")"
printf '  "canCreateChildWithMemoryMax": %s,\n'    "$canChild"
printf '  "childError": "%s",\n'                   "$(q "$childError")"
printf '  "systemdRunUserScopeWorks": %s\n'        "$systemdRun"
printf '}\n'
