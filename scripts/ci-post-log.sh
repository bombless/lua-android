#!/usr/bin/env bash
# Development aid: posts the tail of build/test logs as a commit comment when CI fails, so the
# failure can be read through the GitHub API without downloading raw job logs.
#   usage: ci-post-log.sh <title> <logfile>...
set -uo pipefail
title=${1:?title}; shift
out=$(mktemp)
{
  echo "### CI failure: ${title} (run ${GITHUB_RUN_ID:-?}, attempt ${GITHUB_RUN_ATTEMPT:-?})"
  for f in "$@"; do
    [ -f "$f" ] || continue
    echo; echo "<details open><summary>${f} (tail)</summary>"; echo; echo '```'
    tail -n 220 "$f" | tail -c 24000
    echo '```'; echo "</details>"
  done
} > "$out"
body=$(head -c 62000 "$out")
gh api "repos/${GITHUB_REPOSITORY}/commits/${GITHUB_SHA}/comments" -f body="$body" > /dev/null || true
