# NPDev first-run harness — a deliberately BARE machine.
#
# WHY THIS IMAGE HAS ALMOST NOTHING IN IT
# ---------------------------------------
# The bugs this harness exists to catch (W1/W2/W3 + an incomplete prerequisite list) are
# INVISIBLE on a developer machine, because that machine already has java/python/pwsh
# installed, runtimehost-libs already built, and a warm Gradle cache.
#
# CI does not close that gap either: GitHub runners install Java via setup-java and ship
# pwsh preinstalled. CI proves the build works on a CORRECT machine. It cannot prove a
# human can CREATE one. That is exactly where all three walls live.
#
# So: start with nothing. Whatever the harness needs, it must install by following
# README.md's own prerequisite list. If README does not name it, the run FAILS.
# That failure IS the test.
#
# Deliberately absent: java, python, pip, pwsh, gradle, node, docker.
# Present only: git + curl (to obtain the repo and probe the running app at all).

FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=C.UTF-8 \
    NPDEV_HARNESS=1

# The absolute minimum needed to fetch the project and talk to it over HTTP.
# NOTE: nothing here is an NPDev prerequisite -- these are the harness's own tools.
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      ca-certificates curl git jq \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /work
COPY run-readme.sh /usr/local/bin/run-readme.sh
RUN chmod +x /usr/local/bin/run-readme.sh

# REPO_URL   which repository to clone (override to test a fork/branch)
# REPO_REF   branch or tag to check out
# LOCAL_SRC  if set to 1, use /work/src mounted from the host instead of cloning
#            (lets you test BEFORE merging to main -- see harness/README.md)
ENV REPO_URL=https://github.com/MarceloGiazzon/NPDevGeneral.git \
    REPO_REF=main \
    LOCAL_SRC=0 \
    APP_PORT=8080

ENTRYPOINT ["/usr/local/bin/run-readme.sh"]
