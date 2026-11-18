#!/usr/bin/env bash
# Moves the Vibb Phone server's container to the release X.Y.Z, and only once GitHub's build attestation for that
# exact image checks out (privacy review 2026-11-17 H1) - server/DEPLOY.md, "Docker". Run it by hand on the host, in
# the folder with compose.yaml and .env:
#
#   ./update-image.sh 0.85.0
#
# It finds the release's multi-arch image digest, runs `gh attestation verify` on that digest, and only then writes
# HANDY_VERSION and HANDY_DIGEST into .env and runs `docker compose up -d` - compose pulls the image by that digest,
# so what runs is what was verified. Going back to an older release is the same command with its version.
# Needs docker (with buildx) and gh, signed in (`gh auth login`; any account, it only reads). The server never runs
# this: it never acts on its host.
set -euo pipefail

IMAGE="${HANDY_IMAGE:-ghcr.io/palchrb/handy-server}"
OWNER="${HANDY_OWNER:-palchrb}"

version="${1:-}"
if ! [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "usage: $0 X.Y.Z   (a server-vX.Y.Z release; Settings > Software Updates shows the newest)" >&2
    exit 2
fi
if [ ! -f compose.yaml ] || [ ! -f .env ]; then
    echo "run it in the folder with compose.yaml and .env" >&2
    exit 2
fi
for tool in docker gh; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "$tool is not installed" >&2
        exit 2
    fi
done

# The index's digest: the one the release attested (server-release.yml), and the one compose pulls.
manifest="$(docker buildx imagetools inspect "$IMAGE:$version" --format '{{json .Manifest}}')"
digest="$(printf '%s' "$manifest" | grep -o '"digest":"sha256:[0-9a-f]\{64\}"' | head -n 1 | cut -d'"' -f4 || true)"
if ! [[ "$digest" =~ ^sha256:[0-9a-f]{64}$ ]]; then
    echo "no image digest for $IMAGE:$version" >&2
    exit 1
fi

echo "Checking the build attestation of $IMAGE@$digest ..."
if ! gh attestation verify "oci://$IMAGE@$digest" --owner "$OWNER"; then
    echo "The attestation did not verify: nothing changed, the server keeps running what it runs." >&2
    exit 1
fi

# .env's line for `key`, replaced or added.
set_env() {
    local key="$1" value="$2"
    if grep -q "^$key=" .env; then
        sed -i "s|^$key=.*|$key=$value|" .env
    else
        printf '%s=%s\n' "$key" "$value" >>.env
    fi
}
set_env HANDY_VERSION "$version"
set_env HANDY_DIGEST "$digest"

docker compose pull
docker compose up -d
echo "Running $IMAGE:$version@$digest"
