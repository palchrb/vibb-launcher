#!/usr/bin/env bash
# Stub for the OLD repo palchrb/kid-phone-server: put this file there as deploy/update.sh
# (replacing it) before archiving that repo. Not used from this repo.
#
# Pre-monorepo installs have a root-side actions.sh that runs
#   curl -sSL https://raw.githubusercontent.com/palchrb/kid-phone-server/master/deploy/update.sh | bash
# Without this stub that would install the old repo's last release (v0.18.6) over a database a
# newer server already migrated, and the server would refuse to start. This stub forwards to the
# monorepo's update.sh at its newest stable server-vX.Y.Z tag (same lookup as that script).
# The server itself (from the monorepo) also refuses to request an update while these old
# helper scripts are installed, and asks for install.sh to be re-run instead.
set -euo pipefail

REPO="palchrb/vibb-launcher"

TAG="$(curl -fsSL -H "Accept: application/vnd.github+json" \
        "https://api.github.com/repos/$REPO/releases?per_page=100" \
    | grep -oE '"(tag_name|draft|prerelease)": *("[^"]*"|true|false)' \
    | awk -F': *' '
        /"tag_name"/ { tag = $2; gsub(/"/, "", tag); draft = ""; next }
        /"draft"/ { draft = $2; next }
        /"prerelease"/ {
            if (draft == "false" && $2 == "false" && tag ~ /^server-v[0-9]+\.[0-9]+\.[0-9]+$/) { print tag; exit }
        }' || true)"
if [ -z "$TAG" ]; then
    echo "No server-vX.Y.Z release found in $REPO - not updating." >&2
    exit 1
fi

script="$(mktemp)"
trap 'rm -f "$script"' EXIT
curl -fsSL "https://raw.githubusercontent.com/$REPO/$TAG/server/deploy/update.sh" -o "$script"
KPS_REPO="$REPO" bash "$script"
