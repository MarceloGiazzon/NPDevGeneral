#!/bin/sh
# REG-234 -- Dockerfile.independent-tester's ENTRYPOINT.
#
# Deliberately does NOT pre-clone the repo. A real stranger's first action is deciding to clone the
# repo and running that command themselves -- pre-cloning on their behalf would quietly skip over
# exactly the kind of first-step friction (and the REG-17 "does a fresh clone succeed" question)
# this feature exists to surface. The agent is told the public remote URL in the brief and clones
# it itself, into /work/repo, on its own initiative, using the tools it's given.
#
# Env vars (all set by NPDevCli's run_tester(), never read from anywhere else):
#   NPDEV_TESTER_ANTHROPIC_API_KEY  required -- the tester agent's own Anthropic API key.
#   NPDEV_TESTER_REF                default "main" -- branch/tag the brief tells the agent to clone.
#   NPDEV_TESTER_REPO_URL           default the public NPDevGeneral remote.
#   NPDEV_TESTER_TASK               default "all" -- one of A, B, C, all.

set -eu

mkdir -p /work/output
exec python3 /agent/independent_tester/tester_agent.py "$@"
