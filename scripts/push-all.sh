#!/usr/bin/env bash
# Push the current branch of the monorepo (launcher/ and server/ together) to origin.
# Release tags (launcher-v*, server-v*) are pushed separately, on purpose: `git push origin <tag>`.
# Upstream remotes (if added for cherry-picks, see PLAN.md) are never pushed to.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
branch="$(git -C "$root" branch --show-current)"
echo "== $branch -> $(git -C "$root" remote get-url origin)"
git -C "$root" push -u origin "$branch"
