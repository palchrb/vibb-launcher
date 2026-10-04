#!/usr/bin/env bash
# Push the current branch of handy and both forks to their own origin.
# Upstream is never touched: only `origin` is pushed to.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
for repo in "$root" "$root/kid-phone-server" "$root/kids-launcher-mdm"; do
    name="$(basename "$repo")"
    if ! git -C "$repo" remote get-url origin >/dev/null 2>&1; then
        echo "== $name: no origin remote, skipped"
        continue
    fi
    branch="$(git -C "$repo" branch --show-current)"
    echo "== $name ($branch -> $(git -C "$repo" remote get-url origin))"
    git -C "$repo" push -u origin "$branch"
done
