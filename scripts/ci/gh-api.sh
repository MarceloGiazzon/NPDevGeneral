#!/usr/bin/env bash
#
# GitHub REST API helper, SCOPED to a single repository (MarceloGiazzon/NPDevGeneral).
#
# Purpose: let the assistant perform GitHub API calls it needs for this project's CI loop
# (list workflow runs, download run logs, merge the PR-gate PR, dispatch the workflow) WITHOUT
# granting a blanket `curl:*` permission. The permission rule allowlists THIS script only, and this
# script can only ever talk to api.github.com for this one repo -- it cannot be pointed at another
# host, another repo, or an arbitrary URL.
#
# Usage:
#   scripts/ci/gh-api.sh <METHOD> <repo-relative-path> [json-body]
#     <METHOD>            GET | POST | PUT | PATCH | DELETE
#     <repo-relative-path> appended to https://api.github.com/repos/<REPO>/
#     [json-body]         optional request body (for POST/PUT/PATCH)
#
# Examples:
#   scripts/ci/gh-api.sh GET  'actions/runs?branch=lnch19-ci-verify&per_page=1'
#   scripts/ci/gh-api.sh PUT  pulls/3/merge '{"merge_method":"merge"}'
#   scripts/ci/gh-api.sh POST 'actions/workflows/npdev-pr-gate.yml/dispatches' '{"ref":"beta1-vision-spine"}'
#
# The token is read at run time from Git Credential Manager (the same credential `git push` uses)
# and is NEVER printed or persisted. Prints the raw JSON response to stdout.
#
set -euo pipefail

readonly REPO="MarceloGiazzon/NPDevGeneral"

METHOD="${1:?usage: gh-api.sh <METHOD> <path> [json-body]}"
API_PATH="${2:?usage: gh-api.sh <METHOD> <path> [json-body]}"
BODY="${3:-}"

# Hard-fail on any attempt to escape the repo scope (absolute path, parent traversal, or a full URL).
case "$API_PATH" in
  /* | *..* | *://*) echo "gh-api.sh: refusing out-of-scope path: $API_PATH" >&2; exit 2 ;;
esac
case "$METHOD" in
  GET|POST|PUT|PATCH|DELETE) ;;
  *) echo "gh-api.sh: unsupported method: $METHOD" >&2; exit 2 ;;
esac

TOKEN="$(printf 'protocol=https\nhost=github.com\n\n' | git credential fill 2>/dev/null | sed -n 's/^password=//p')"
[ -n "$TOKEN" ] || { echo "gh-api.sh: no GitHub token available from Git Credential Manager" >&2; exit 3; }

URL="https://api.github.com/repos/${REPO}/${API_PATH}"

if [ -n "$BODY" ]; then
  curl -sS -L -X "$METHOD" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Accept: application/vnd.github+json" \
    -H "X-GitHub-Api-Version: 2022-11-28" \
    -d "$BODY" "$URL"
else
  curl -sS -L -X "$METHOD" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Accept: application/vnd.github+json" \
    -H "X-GitHub-Api-Version: 2022-11-28" \
    "$URL"
fi
