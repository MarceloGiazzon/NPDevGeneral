#!/usr/bin/env bash
# NPDev Gradle wrapper (bash / Git Bash / WSL).
#
# Runs the nearest gradlew with Gradle's PROJECT CACHE (.gradle/) relocated OUT of the
# NPDev_General source tree. Gradle only supports this via the --project-cache-dir flag
# (settings.gradle / init scripts run too late), so this wrapper injects it. Mirrors the
# external build-output policy already applied to build/.
#
# Usage (from any NPDev build root, or the repo root):
#     <repo>/npdev-gradlew.sh :generator:test --console=plain
#
# Cache location: $NPDEV_BUILD_ROOT (if set) else <parent-of-NPDev_General>/Build,
# under gradle-cache/<build-root-name>.
set -euo pipefail

find_up() { # $1=start dir, $2=marker filename
    local dir
    dir="$(cd "$1" && pwd)"
    while [ -n "$dir" ]; do
        if [ -e "$dir/$2" ]; then printf '%s\n' "$dir"; return 0; fi
        [ "$dir" = "/" ] && break
        dir="$(dirname "$dir")"
    done
    return 1
}

cwd="$(pwd)"
buildRoot="$(find_up "$cwd" gradlew)" || {
    echo "No gradlew found from '$cwd' upward; run this from inside an NPDev Gradle build root." >&2
    exit 1
}

# npdev-build-root-resolution: identify the repo root by its CONTENTS, not its name -- see
# scripts/npdev-common.ps1's Get-NPDevBuildRoot comment for the CI failure the name match caused.
srcRoot="$buildRoot"
while [ "$srcRoot" != "/" ] && ! { [ -d "$srcRoot/NPDevContract" ] && [ -d "$srcRoot/NPDevGenerator" ] && [ -d "$srcRoot/NPDevKernel" ]; }; do
    srcRoot="$(dirname "$srcRoot")"
done

if [ -n "${NPDEV_BUILD_ROOT:-}" ]; then
    externalRoot="$NPDEV_BUILD_ROOT"
elif [ -d "$srcRoot/NPDevContract" ] && [ -d "$srcRoot/NPDevGenerator" ] && [ -d "$srcRoot/NPDevKernel" ]; then
    externalRoot="$(dirname "$srcRoot")/Build"
else
    externalRoot="$(dirname "$buildRoot")/Build"
fi

cacheDir="$externalRoot/gradle-cache/$(basename "$buildRoot")"

exec "$buildRoot/gradlew" --project-cache-dir "$cacheDir" "$@"
