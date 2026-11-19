#!/usr/bin/env bash
# Moves the Vibb Phone server's container to the release X.Y.Z, and only once GitHub's build attestation for that
# exact image checks out (privacy review 2026-11-17 H1) - server/DEPLOY.md, "Docker". Run it on the host, in the
# folder with compose.yaml and .env:
#
#   ./update-image.sh 0.85.0     # that release, by hand (forward or back)
#   ./update-image.sh --auto     # the newest stable release, only if it is newer than .env's HANDY_VERSION
#   ./update-image.sh --selftest # the release pick, the version rules and the .env write on canned data (needs jq)
#
# It finds the release's multi-arch image digest, runs `gh attestation verify` on that digest, pulls the image by that
# digest, and only then writes HANDY_VERSION and HANDY_DIGEST (`@sha256:...`) into .env (one atomic replace) and runs
# `docker compose up -d` for the handy service - compose runs the image by that digest, so what runs is what was
# verified. Going back to an older release is the same command with its version.
# The verified way, beside Watchtower's automatic one (the user's choice, 2026-11-18: both are supported).
# A pass means the project's release workflow (server-release.yml in palchrb/vibb-launcher, on GitHub's own runners)
# built that image for the tag server-vX.Y.Z - not just any workflow of the account.
#
# --auto (2026-11-19, for handy-update.timer): the highest stable server-vX.Y.Z in the releases list (never a draft or
# a prerelease). Software Updates and update.sh take the first stable one listed, which is the same release unless a
# lower version was published later. Newer than HANDY_VERSION: the path above, exactly. The same: nothing, unless the
# container doesn't run .env's HANDY_DIGEST (a pull or start that failed before) - then that digest is checked again
# and started; never with an empty HANDY_DIGEST. Older: a warning, nothing changed (it never goes back).
# HANDY_VERSION=latest is Watchtower's mode: it refuses to move and says to pick one. A refused check changes nothing
# and exits non-zero, so the unit shows failed. Another run holding the lock: a line, exit 0.
#
# No backup step of its own: the new server copies the database to data/backups/pre-migrate-<from>-<to>.db (./backups
# on the host) before it migrates anything (DEPLOY.md, "Updating" and "Rolling back").
#
# Needs docker (with buildx), jq, flock and gh 2.68 or later (`gh attestation verify --source-ref`), signed in (`gh auth
# login`, or GH_TOKEN: any account, it only reads). The server never runs this: it never acts on its host.
set -euo pipefail

# The same image compose.yaml runs; no override.
IMAGE="ghcr.io/palchrb/handy-server"
REPO="palchrb/vibb-launcher"
WORKFLOW="$REPO/.github/workflows/server-release.yml"
SERVICE="handy"
LOCK_FILE=".update-image.lock"

# X.Y.Z > X.Y.Z. Both are checked against the pattern first: bash arithmetic on an unchecked string (one comes from
# .env) would evaluate it.
version_gt() {
    local re='^([0-9]+)\.([0-9]+)\.([0-9]+)$' a b i
    [[ "$1" =~ $re ]] || return 1
    a=("${BASH_REMATCH[@]:1}")
    [[ "$2" =~ $re ]] || return 1
    b=("${BASH_REMATCH[@]:1}")
    for i in 0 1 2; do
        if ((10#${a[i]} > 10#${b[i]})); then return 0; fi
        if ((10#${a[i]} < 10#${b[i]})); then return 1; fi
    done
    return 1
}

# The releases list's JSON on stdin -> the highest stable server version (X.Y.Z), or nothing. Neither a draft nor a
# prerelease, and only a plain server-vX.Y.Z tag (an -rc.N, a launcher-v* or music-v* release never counts). Anything
# but a list (an error answer, not JSON) gives nothing.
newest_stable_version() {
    local best="" v
    while IFS= read -r v; do
        if [ -z "$best" ] || version_gt "$v" "$best"; then
            best="$v"
        fi
    done < <(jq -r '(if type == "array" then .[] else empty end) | objects
        | select(.draft == false and .prerelease == false) | .tag_name | strings
        | select(test("^server-v[0-9]+\\.[0-9]+\\.[0-9]+$")) | ltrimstr("server-v")' 2>/dev/null || true)
    printf '%s' "$best"
}

# .env's value for `key` (the last line that sets it), without quotes or surrounding blanks; nothing if unset.
env_value() {
    local key="$1" file="${2:-.env}" line
    line="$(grep -E "^[[:space:]]*$key=" "$file" | tail -n 1 || true)"
    line="${line#*=}"
    line="${line%$'\r'}"
    line="$(printf '%s' "$line" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'\$/\1/")"
    printf '%s' "$line"
}

# Writes HANDY_VERSION and HANDY_DIGEST into `file` in one atomic replace: every other line kept, each key's first line
# replaced and its repeats dropped, a missing key appended. Through a symlink to its target; the owner and mode kept.
ENV_TMP=""
write_env() {
    local file="$1" version="$2" digest="$3" target
    target="$(readlink -f -- "$file")"
    ENV_TMP="$(mktemp "$(dirname -- "$target")/.env.tmp.XXXXXX")"
    awk -v v="HANDY_VERSION=$version" -v d="HANDY_DIGEST=$digest" '
        /^[[:space:]]*HANDY_VERSION=/ { if (!sv) print v; sv = 1; next }
        /^[[:space:]]*HANDY_DIGEST=/ { if (!sd) print d; sd = 1; next }
        { print }
        END { if (!sv) print v; if (!sd) print d }' "$target" >"$ENV_TMP"
    chmod --reference="$target" -- "$ENV_TMP"
    chown --reference="$target" -- "$ENV_TMP" 2>/dev/null || true
    mv -f -- "$ENV_TMP" "$target"
    ENV_TMP=""
}
trap '[ -z "$ENV_TMP" ] || rm -f -- "$ENV_TMP"' EXIT

# What --auto does with .env's HANDY_VERSION and the newest stable release: update, same, older, latest (Watchtower's
# mode), unpinned (no X.Y.Z in .env) or none (no release found).
auto_decision() {
    local current="$1" newest="$2"
    if [ -z "$newest" ]; then echo none; return; fi
    if [ "$current" = "latest" ]; then echo latest; return; fi
    if ! [[ "$current" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then echo unpinned; return; fi
    if version_gt "$newest" "$current"; then echo update
    elif [ "$newest" = "$current" ]; then echo same
    else echo older
    fi
}

selftest() {
    local failed=0 got
    if ! command -v jq >/dev/null 2>&1; then
        echo "the selftest needs jq" >&2
        exit 2
    fi
    check() {
        if [ "$2" != "$3" ]; then
            echo "FAIL $1: got '$2', want '$3'" >&2
            failed=1
        fi
    }
    # shellcheck disable=SC2016 # the last row is a literal $(echo), never expanded
    for row in "1.0.0 0.9.9 0" "0.10.0 0.9.0 0" "0.9.0 0.10.0 1" "1.2.3 1.2.3 1" "0.86.1 0.86.0 0" "01.0.0 1.0.0 1" \
        "1.0 0.9.0 1" "latest 0.1.0 1" '$(echo) 0.1.0 1'; do
        read -r a b want <<<"$row"
        got=0
        version_gt "$a" "$b" || got=1
        check "version_gt $a $b" "$got" "$want"
    done

    # Canned releases, newest first as GitHub lists them: launcher and music releases, a draft, a prerelease, an RC
    # tag marked stable, a malformed tag, a release with its keys in another order, and an older backport listed above
    # the newest stable server release.
    local releases='[
  {"tag_name": "launcher-v9.0.0", "draft": false, "prerelease": false, "assets": [{"name": "kids-launcher-mdm.apk"}]},
  {"tag_name": "server-v0.99.0", "draft": true, "prerelease": false, "assets": []},
  {"draft": false, "prerelease": true, "tag_name": "server-v0.98.5", "assets": []},
  {"tag_name": "server-v0.98.0", "draft": false, "prerelease": true, "assets": []},
  {"tag_name": "server-v0.97.0-rc.1", "draft": false, "prerelease": false, "assets": []},
  {"tag_name": "server-v0.96", "draft": false, "prerelease": false, "assets": []},
  {"tag_name": null, "draft": false, "prerelease": false},
  {"tag_name": "music-v5.0.0", "draft": false, "prerelease": false, "assets": []},
  {"tag_name": "server-v0.80.9", "draft": false, "prerelease": false, "body": "a backport: \"tag_name\": \"server-v9.9.9\"", "assets": []},
  {"tag_name": "server-v0.86.1", "draft": false, "prerelease": false, "assets": [{"name": "kid_phone_server.tar.gz"}]},
  {"tag_name": "server-v0.86.0", "draft": false, "prerelease": false, "assets": []}
]'
    check "newest of the canned list" "$(newest_stable_version <<<"$releases")" "0.86.1"
    check "newest of the compact list" "$(jq -c . <<<"$releases" | newest_stable_version)" "0.86.1"
    check "newest of no stable server release" \
        "$(newest_stable_version <<<'[{"tag_name": "server-v1.0.0", "draft": false, "prerelease": true}]')" ""
    check "newest of an empty list" "$(newest_stable_version <<<'[]')" ""
    check "newest of an error answer" "$(newest_stable_version <<<'{"message": "Bad credentials"}')" ""
    check "newest of no JSON" "$(newest_stable_version <<<'<html>rate limited</html>')" ""

    check "auto newer" "$(auto_decision 0.86.0 0.86.1)" update
    check "auto same" "$(auto_decision 0.86.1 0.86.1)" same
    check "auto older" "$(auto_decision 0.87.0 0.86.1)" older
    check "auto latest" "$(auto_decision latest 0.86.1)" latest
    check "auto empty" "$(auto_decision '' 0.86.1)" unpinned
    check "auto junk" "$(auto_decision '0.86.1; rm -rf /' 0.86.1)" unpinned
    check "auto no release" "$(auto_decision 0.86.1 '')" none

    local dir zeros ones
    dir="$(mktemp -d)"
    zeros="$(printf '0%.0s' {1..64})"
    ones="$(printf '1%.0s' {1..64})"
    printf '%s\n' 'HANDY_VERSION=latest' 'TZ=Europe/Oslo' '# HANDY_VERSION=0.1.0' 'HANDY_VERSION="0.85.2" ' \
        "HANDY_DIGEST=@sha256:$zeros" >"$dir/env"
    check "env pinned (the last line wins)" "$(env_value HANDY_VERSION "$dir/env")" "0.85.2"
    check "env unset" "$(env_value NOT_THERE "$dir/env")" ""
    printf 'HANDY_VERSION=0.86.0\r\n' >"$dir/env"
    check "env with CRLF" "$(env_value HANDY_VERSION "$dir/env")" "0.86.0"

    # The atomic write: the other lines kept in place, the keys replaced once, a last line without a newline, the
    # mode kept, a symlink kept and its target written, no temp file left.
    printf '%s\n' '# the family server' 'HANDY_VERSION=0.85.2' 'TZ=Europe/Oslo' 'HANDY_VERSION=0.1.0' >"$dir/real.env"
    printf 'ADMIN_PUBLIC=off' >>"$dir/real.env"
    chmod 640 "$dir/real.env"
    ln -s real.env "$dir/.env"
    write_env "$dir/.env" 0.86.1 "@sha256:$ones"
    check "write keeps the link" "$(readlink "$dir/.env")" "real.env"
    check "write's lines" "$(cat "$dir/real.env")" "$(printf '%s\n' '# the family server' 'HANDY_VERSION=0.86.1' \
        'TZ=Europe/Oslo' 'ADMIN_PUBLIC=off' "HANDY_DIGEST=@sha256:$ones")"
    check "write keeps the mode" "$(stat -c %a "$dir/real.env")" "640"
    check "write leaves no temp file" "$(find "$dir" -name '.env.tmp.*' | wc -l)" "0"
    check "write reads back" "$(env_value HANDY_DIGEST "$dir/.env")" "@sha256:$ones"
    rm -rf "$dir"

    if [ "$failed" -ne 0 ]; then
        echo "update-image.sh selftest failed" >&2
        exit 1
    fi
    echo "update-image.sh selftest passed"
}

usage() {
    echo "usage: $0 X.Y.Z | --auto | --selftest   (a server-vX.Y.Z release; Settings > Software Updates shows the newest)" >&2
    exit 2
}

# compose with .env's HANDY_VERSION/HANDY_DIGEST only: an exported one in the caller's environment would win
# compose's interpolation and run another image than the one checked.
compose() {
    env -u HANDY_VERSION -u HANDY_DIGEST docker compose "$@"
}

# Whether the handy container runs the image `digest` names (sha256:...).
runs_digest() {
    local cid running want
    cid="$(compose ps -q "$SERVICE" 2>/dev/null || true)"
    [ -n "$cid" ] || return 1
    running="$(docker inspect -f '{{.Image}}' "$cid" 2>/dev/null)" || return 1
    want="$(docker image inspect -f '{{.Id}}' "$IMAGE@$1" 2>/dev/null)" || return 1
    [ -n "$want" ] && [ "$running" = "$want" ]
}

arg="${1:-}"
case "$arg" in
    --selftest)
        selftest
        exit 0
        ;;
    --auto) auto=1 ;;
    *)
        auto=0
        [[ "$arg" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || usage
        ;;
esac
[ "$#" -le 1 ] || usage

if [ ! -f compose.yaml ] || [ ! -f .env ]; then
    echo "run it in the folder with compose.yaml and .env" >&2
    exit 2
fi
for tool in docker gh jq flock; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "$tool is not installed" >&2
        exit 2
    fi
done
# Captured, not piped into grep -q: under pipefail an early grep exit can fail the pipe.
gh_help="$(gh attestation verify --help 2>&1 || true)"
if [[ "$gh_help" != *--source-ref* ]]; then
    echo "gh is too old for this check: it needs gh 2.68 or later (gh attestation verify --source-ref)." >&2
    echo "Install gh from GitHub's own apt repository (https://cli.github.com/packages), not the distribution's." >&2
    exit 2
fi

# One run at a time (the timer and a run by hand): the lock is held until the script exits.
if ! exec 9>>"$LOCK_FILE"; then
    echo "can't open $LOCK_FILE: run it as the user the timer runs as (root, once handy-update.timer is installed)" >&2
    exit 2
fi
if ! flock -n 9; then
    echo "another update-image.sh is running in this folder ($LOCK_FILE): leaving it to that run"
    exit 0
fi

digest=""
if [ "$auto" = 1 ]; then
    current="$(env_value HANDY_VERSION)"
    releases="$(gh api -H "Accept: application/vnd.github+json" "repos/$REPO/releases?per_page=100")" || {
        echo "could not read $REPO's releases from GitHub: nothing changed" >&2
        exit 1
    }
    newest="$(newest_stable_version <<<"$releases")"
    case "$(auto_decision "$current" "$newest")" in
        update)
            echo "server-v$newest is out (running $current): checking it"
            version="$newest"
            ;;
        same)
            pinned="$(env_value HANDY_DIGEST)"
            # Never with an empty digest: that would be a pull by tag.
            if ! [[ "$pinned" =~ ^@sha256:[0-9a-f]{64}$ ]]; then
                exit 0
            fi
            if runs_digest "${pinned#@}"; then
                exit 0
            fi
            echo "the container doesn't run .env's $current${pinned}: checking it again and starting it"
            version="$current"
            digest="${pinned#@}"
            ;;
        older)
            echo "warning: the newest stable release ($newest) is older than .env's HANDY_VERSION=$current" >&2
            echo "(was the release this server runs withdrawn?): never going back, nothing changed" >&2
            exit 0
            ;;
        latest)
            echo "HANDY_VERSION=latest in .env is Watchtower's mode: pick one. Either pin a version with" >&2
            echo "./update-image.sh X.Y.Z (and turn Watchtower off for this container), or turn handy-update.timer off." >&2
            exit 1
            ;;
        unpinned)
            echo "HANDY_VERSION in .env is not a version (X.Y.Z): start with ./update-image.sh X.Y.Z" >&2
            exit 1
            ;;
        *)
            echo "no stable server-vX.Y.Z release found in $REPO's releases: nothing changed" >&2
            exit 1
            ;;
    esac
else
    version="$arg"
fi

if [ -z "$digest" ]; then
    # The index's digest: the one the release attested (server-release.yml), and the one compose pulls.
    manifest="$(docker buildx imagetools inspect "$IMAGE:$version" --format '{{json .Manifest}}')"
    digest="$(printf '%s' "$manifest" | jq -r '.digest // empty')"
    if ! [[ "$digest" =~ ^sha256:[0-9a-f]{64}$ ]]; then
        echo "no image digest for $IMAGE:$version" >&2
        exit 1
    fi
fi

echo "Checking the build attestation of $IMAGE@$digest ..."
if ! gh attestation verify "oci://$IMAGE@$digest" --repo "$REPO" --signer-workflow "$WORKFLOW" \
    --source-ref "refs/tags/server-v$version" --deny-self-hosted-runners; then
    echo "The attestation of $IMAGE:$version did not verify: nothing changed, the server keeps running what it runs." >&2
    exit 1
fi

# By the digest just checked, before .env names it: a failed pull leaves .env as it was, and the next run tries again.
if ! docker pull "$IMAGE@$digest"; then
    echo "Pulling $IMAGE@$digest failed: nothing changed, the server keeps running what it runs." >&2
    exit 1
fi

write_env .env "$version" "@$digest"

# --wait (compose 2.1.1 and later): a release that doesn't come up healthy fails the run instead of "Running ...".
# Without it the run ends once the container is started; `docker compose ps` shows its health.
compose_help="$(compose up --help 2>&1 || true)"
if [[ "$compose_help" == *--wait* ]]; then
    compose up -d --wait "$SERVICE"
else
    echo "this docker compose has no --wait: not waiting for the health check"
    compose up -d "$SERVICE"
fi
echo "Running $IMAGE:$version@$digest"
