#!/usr/bin/env bash
# Smoke test of a server image (design 22 §6), run by CI and the release before an image is
# tagged: the user is 65532, TLS works through the app's own stack (selftest-tls), and the server
# starts healthy with the documented hardening (read-only root, tmpfs /tmp, no capabilities,
# no-new-privileges, named volumes for data/ and data/keys/), writes its generated first password
# to a file (never the log), and reset-2fa runs without a shell.
#
# Usage: image-smoke.sh IMAGE [PLATFORM]   (PLATFORM e.g. linux/arm64, under QEMU)
set -euo pipefail

image="$1"
platform="${2:-}"
platform_args=()
if [ -n "$platform" ]; then
    platform_args=(--platform "$platform")
fi

user="$(docker image inspect --format '{{.Config.User}}' "$image")"
if [ "$user" != "65532" ]; then
    echo "Config.User is '$user', not 65532" >&2
    exit 1
fi

docker run --rm "${platform_args[@]}" "$image" selftest-tls

suffix="$$-$RANDOM"
name="handy-smoke-$suffix"
data="handy-smoke-data-$suffix"
keys="handy-smoke-keys-$suffix"
cleanup() {
    docker rm -f "$name" >/dev/null 2>&1 || true
    docker volume rm "$data" "$keys" >/dev/null 2>&1 || true
}
trap cleanup EXIT
docker volume create "$data" >/dev/null
docker volume create "$keys" >/dev/null

docker run -d --name "$name" "${platform_args[@]}" \
    --read-only --tmpfs /tmp --cap-drop ALL --security-opt no-new-privileges:true \
    -v "$data:/app/data" -v "$keys:/app/data/keys" \
    -e TZ=Europe/Oslo \
    --health-interval 3s --health-start-period 120s \
    "$image" >/dev/null

status=starting
for _ in $(seq 1 80); do
    status="$(docker inspect --format '{{.State.Health.Status}}' "$name")"
    if [ "$status" = healthy ]; then
        break
    fi
    if [ "$(docker inspect --format '{{.State.Running}}' "$name")" != true ]; then
        echo "the container stopped" >&2
        docker logs "$name" >&2 || true
        exit 1
    fi
    sleep 3
done
if [ "$status" != healthy ]; then
    echo "not healthy: $status" >&2
    docker logs "$name" >&2 || true
    exit 1
fi

logs="$(docker logs "$name" 2>&1)"
if ! grep -q "initial-admin-password" <<<"$logs"; then
    echo "the log doesn't name the generated password's file" >&2
    echo "$logs" >&2
    exit 1
fi
password="$(docker cp "$name:/app/data/initial-admin-password" - | tar -xO)"
if [ -z "$password" ] || grep -qF "$password" <<<"$logs"; then
    echo "the generated password is missing, or it is in the log" >&2
    exit 1
fi
docker exec "$name" /app/kid_phone_server reset-2fa admin
echo "image smoke test passed: $image ${platform:-native}"
