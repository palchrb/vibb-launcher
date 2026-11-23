#!/usr/bin/env bash
# Moves the Vibb Phone server's container to the release X.Y.Z, and only once the release key's signature on that exact
# image and GitHub's build attestation both check out - server/DEPLOY.md, "Docker". Run it on the host, in the folder
# with compose.yaml and .env:
#
#   ./update-image.sh 0.92.0     # that release, by hand (forward or back)
#   ./update-image.sh --auto     # the highest newer stable release whose signed statement verifies
#   ./update-image.sh --selftest # the release pick, the signature rules and the .env write on canned data
#                                # (needs jq and minisign; makes a throwaway key)
#   ./update-image.sh --verify-statement FILE FILE.minisig X.Y.Z  # the release workflow's check: prints the digest
#
# Each release carries handy-server-image.txt, one line "ghcr.io/palchrb/handy-server@sha256:<64 hex> server-vX.Y.Z",
# signed with the project's minisign key (the same key install.sh and update.sh pin) under the trusted comment
# "handy-server-image.txt server-vX.Y.Z". The script downloads it and its .minisig, verifies them with the pinned key,
# and from then on uses only the signed digest: the registry's X.Y.Z tag must name the same digest, `gh attestation
# verify` must pass on it as a second check, the pull is by that digest, and only then are HANDY_VERSION and
# HANDY_DIGEST (`@sha256:...`) written into .env (one atomic replace) and `docker compose up -d` run for the handy
# service - compose runs the image by that digest, so what runs is what was verified. A pass means: the release key
# signed this exact image for this tag, and GitHub's attestation agrees. Going back to an older release is the same
# command with its version; a release made before the image was signed has no statement and is refused.
# The verified way, beside Watchtower's automatic, unchecked one (the user's choice, 2026-11-18: both are supported).
#
# --auto (2026-11-19, for handy-update.timer): the stable server-vX.Y.Z releases (never a draft or a prerelease) newer
# than .env's HANDY_VERSION, highest first: the first whose signed statement verifies is taken, at most 5 are tried,
# each one skipped gets a line "skipped server-vX.Y.Z: <reason>". None verifies: nothing changed, a non-zero exit. The
# newest the same as HANDY_VERSION: nothing, unless the container doesn't run .env's HANDY_DIGEST (a pull or start that
# failed before) - then that version is checked again, .env's digest must be its signed one, and it is started; never
# with an empty HANDY_DIGEST. Older: a warning, nothing changed (it never goes back). HANDY_VERSION=latest is
# Watchtower's mode: it refuses to move and says to pick one. A refused check changes nothing and exits non-zero, so
# the unit shows failed. Another run holding the lock: a line, exit 0.
#
# No backup step of its own: the new server copies the database to data/backups/pre-migrate-<from>-<to>.db (./backups
# on the host) before it migrates anything (DEPLOY.md, "Updating" and "Rolling back").
#
# Needs minisign, docker (with buildx), jq, flock and gh 2.68 or later (`gh attestation verify --source-ref`), signed
# in (`gh auth login`, or GH_TOKEN: any account, it only reads). The server never runs this: it never acts on its host.
set -euo pipefail

# The same image compose.yaml runs; no override.
IMAGE="ghcr.io/palchrb/handy-server"
REPO="palchrb/vibb-launcher"
WORKFLOW="$REPO/.github/workflows/server-release.yml"
SERVICE="handy"
LOCK_FILE=".update-image.lock"
# The signed statement's asset name, and how many releases --auto tries at most.
STATEMENT="handy-server-image.txt"
MAX_TRIES=5

# The project's minisign key (server/DEPLOY.md, "Release signing"): the same line in install.sh, update.sh and this
# script - a server test keeps them equal. "not-set" refuses every release.
SERVER_SIGNING_PUBKEY="RWQp6olF7G4WFeRgDWSWwq2/qOkClyqqXEzDBrPPf2EUrdZlAUQATU5g"

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

# The releases list's JSON on stdin -> the stable server versions (X.Y.Z), highest first, one a line, no repeats.
# Neither a draft nor a prerelease, and only a plain server-vX.Y.Z tag (an -rc.N, a launcher-v* or music-v* release
# never counts). Anything but a list (an error answer, not JSON) gives nothing.
stable_versions_desc() {
    local v list=() i j t
    while IFS= read -r v; do
        [[ "$v" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || continue
        list+=("$v")
    done < <(jq -r '(if type == "array" then .[] else empty end) | objects
        | select(.draft == false and .prerelease == false) | .tag_name | strings
        | select(test("^server-v[0-9]+\\.[0-9]+\\.[0-9]+$")) | ltrimstr("server-v")' 2>/dev/null || true)
    # A plain insertion sort by version_gt (a releases list is short).
    for ((i = 1; i < ${#list[@]}; i++)); do
        t="${list[i]}"
        j=$((i - 1))
        while ((j >= 0)) && version_gt "$t" "${list[j]}"; do
            list[j + 1]="${list[j]}"
            j=$((j - 1))
        done
        list[j + 1]="$t"
    done
    t=""
    for v in "${list[@]}"; do
        [ "$v" = "$t" ] || printf '%s\n' "$v"
        t="$v"
    done
}

# statement_digest STATEMENT MINISIG PUBKEY IMAGE VERSION: prints the signed digest (sha256:<64 hex>) and returns 0
# only when MINISIG is a good signature of STATEMENT by PUBKEY with the trusted comment exactly
# "handy-server-image.txt server-vVERSION", and STATEMENT is exactly one line "IMAGE@sha256:<64 hex> server-vVERSION"
# ending in one newline. Anything else prints the reason and returns 1.
statement_digest() {
    local statement="$1" minisig="$2" pubkey="$3" image="$4" version="$5" comment content line rest digest
    if [ -z "$pubkey" ] || [ "$pubkey" = "not-set" ]; then
        echo "no release signing key is pinned in this script"
        return 1
    fi
    if ! [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
        echo "'$version' is not a version (X.Y.Z)"
        return 1
    fi
    if [ ! -s "$statement" ]; then
        echo "no $STATEMENT"
        return 1
    fi
    if [ ! -s "$minisig" ]; then
        echo "no $STATEMENT.minisig"
        return 1
    fi
    if ! comment="$(minisign -V -Q -P "$pubkey" -m "$statement" -x "$minisig" 2>/dev/null)"; then
        echo "the signature doesn't verify with the pinned key"
        return 1
    fi
    if [ "$comment" != "$STATEMENT server-v$version" ]; then
        echo "the signature is for '$comment', not '$STATEMENT server-v$version'"
        return 1
    fi
    # The bytes as they are: a trailing x keeps $(...) from eating the newlines.
    content="$(cat -- "$statement" && printf x)"
    content="${content%x}"
    line="${content%$'\n'}"
    if [ "$line" = "$content" ] || [[ "$line" == *$'\n'* ]] || [[ "$line" == *$'\r'* ]]; then
        echo "the statement is not exactly one line"
        return 1
    fi
    rest="${line#"$image@"}"
    digest="${rest%" server-v$version"}"
    if [ "$rest" = "$line" ] || [ "$digest" = "$rest" ] || ! [[ "$digest" =~ ^sha256:[0-9a-f]{64}$ ]]; then
        echo "the statement doesn't name $image@sha256:<64 hex> server-v$version"
        return 1
    fi
    printf '%s\n' "$digest"
}

# digest_decision SIGNED OTHER: ok when OTHER (the registry's tag, or .env's HANDY_DIGEST) is the signed digest, else
# the mismatch.
digest_decision() {
    if [ -n "$1" ] && [ "$1" = "$2" ]; then
        echo ok
    else
        echo "mismatch: signed $1, found ${2:-nothing}"
    fi
}

# fetch_statement VERSION DIR: the release's statement and its .minisig into DIR (the selftest replaces it).
fetch_statement() {
    gh release download "server-v$1" --repo "$REPO" --dir "$2" --clobber \
        --pattern "$STATEMENT" --pattern "$STATEMENT.minisig" >/dev/null 2>&1
}

# signed_digest VERSION: downloads and checks the release's statement; prints the signed digest, or the reason and
# returns 1.
signed_digest() {
    local version="$1" dir status=0
    dir="$(mktemp -d)"
    if ! fetch_statement "$version" "$dir"; then
        echo "the release or its $STATEMENT could not be downloaded"
        rm -rf -- "${dir:?}"
        return 1
    fi
    statement_digest "$dir/$STATEMENT" "$dir/$STATEMENT.minisig" "$SERVER_SIGNING_PUBKEY" "$IMAGE" "$version" ||
        status=1
    rm -rf -- "${dir:?}"
    return "$status"
}

# pick_signed CURRENT < versions (highest first): the first version newer than CURRENT whose statement verifies, at
# most MAX_TRIES tried; prints "VERSION DIGEST", and a "skipped server-vX.Y.Z: <reason>" line on stderr for each one
# passed over. None: nothing printed, returns 1.
pick_signed() {
    local current="$1" v tries=0 got
    while IFS= read -r v; do
        version_gt "$v" "$current" || continue
        if ((tries >= MAX_TRIES)); then
            break
        fi
        tries=$((tries + 1))
        if got="$(signed_digest "$v")"; then
            printf '%s %s\n' "$v" "$got"
            return 0
        fi
        echo "skipped server-v$v: $got" >&2
    done
    return 1
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
    local failed=0 got tool
    for tool in jq minisign; do
        if ! command -v "$tool" >/dev/null 2>&1; then
            echo "the selftest needs $tool" >&2
            exit 2
        fi
    done
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
    # tag marked stable, a malformed tag, a release with its keys in another order, an older backport listed above
    # the newest stable server release, and a repeat.
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
  {"tag_name": "server-v0.86.0", "draft": false, "prerelease": false, "assets": []},
  {"tag_name": "server-v0.86.1", "draft": false, "prerelease": false, "assets": []}
]'
    local want_desc
    want_desc="$(printf '%s\n' 0.86.1 0.86.0 0.80.9)"
    check "versions of the canned list" "$(stable_versions_desc <<<"$releases")" "$want_desc"
    check "versions of the compact list" "$(jq -c . <<<"$releases" | stable_versions_desc)" "$want_desc"
    check "versions of no stable server release" \
        "$(stable_versions_desc <<<'[{"tag_name": "server-v1.0.0", "draft": false, "prerelease": true}]')" ""
    check "versions of an empty list" "$(stable_versions_desc <<<'[]')" ""
    check "versions of an error answer" "$(stable_versions_desc <<<'{"message": "Bad credentials"}')" ""
    check "versions of no JSON" "$(stable_versions_desc <<<'<html>rate limited</html>')" ""

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

    # The signed statement, with two throwaway key pairs made here and deleted with the temp dir (nothing is
    # committed): the test key and another one. A fake image name, so no row could pass for the real image.
    local k="$dir/keys" s="$dir/st" pub other fake="ghcr.io/example-test/fake-image" abc
    mkdir -p "$k" "$s"
    minisign -G -W -p "$k/test.pub" -s "$k/test.key" >/dev/null 2>&1
    minisign -G -W -p "$k/other.pub" -s "$k/other.key" >/dev/null 2>&1
    pub="$(sed -n 2p "$k/test.pub")"
    other="$(sed -n 2p "$k/other.pub")"
    abc="$(printf 'ab%.0s' {1..32})"
    # signed NAME CONTENT [COMMENT [KEY]]: writes $s/NAME and its .minisig.
    signed() {
        printf '%s' "$2" >"$s/$1"
        minisign -S -s "${4:-$k/test.key}" -m "$s/$1" -x "$s/$1.minisig" -t "${3:-$STATEMENT server-v0.92.0}" \
            >/dev/null 2>&1
    }
    # verdict NAME [VERSION [PUBKEY [MINISIG]]]: statement_digest's output, after ok or no.
    verdict() {
        local out
        if out="$(statement_digest "$s/$1" "${4:-$s/$1.minisig}" "${3:-$pub}" "$fake" "${2:-0.92.0}")"; then
            echo "ok $out"
        else
            echo "no $out"
        fi
    }
    signed good "$fake@sha256:$abc server-v0.92.0"$'\n'
    check "statement: a valid one gives the digest" "$(verdict good)" "ok sha256:$abc"
    check "statement: no .minisig" "$(verdict good 0.92.0 "$pub" "$s/missing.minisig")" "no no $STATEMENT.minisig"
    : >"$s/empty.minisig"
    check "statement: an empty .minisig" "$(verdict good 0.92.0 "$pub" "$s/empty.minisig")" "no no $STATEMENT.minisig"
    signed other_key "$fake@sha256:$abc server-v0.92.0"$'\n' "" "$k/other.key"
    check "statement: another key's signature" "$(verdict other_key)" \
        "no the signature doesn't verify with the pinned key"
    check "statement: checked with another key" "$(verdict good 0.92.0 "$other")" \
        "no the signature doesn't verify with the pinned key"
    check "statement: the key not-set" "$(verdict good 0.92.0 not-set)" \
        "no no release signing key is pinned in this script"
    signed other_tag "$fake@sha256:$abc server-v0.92.0"$'\n' "$STATEMENT server-v0.93.0"
    check "statement: a comment with another tag" "$(verdict other_tag)" \
        "no the signature is for '$STATEMENT server-v0.93.0', not '$STATEMENT server-v0.92.0'"
    signed other_name "$fake@sha256:$abc server-v0.92.0"$'\n' "update.sh server-v0.92.0"
    check "statement: a comment with another file name" "$(verdict other_name)" \
        "no the signature is for 'update.sh server-v0.92.0', not '$STATEMENT server-v0.92.0'"
    signed older "$fake@sha256:$abc server-v0.91.0"$'\n' "$STATEMENT server-v0.91.0"
    check "statement: an older release's under its own version" "$(verdict older 0.91.0)" "ok sha256:$abc"
    check "statement: an older release's under a newer version" "$(verdict older 0.92.0)" \
        "no the signature is for '$STATEMENT server-v0.91.0', not '$STATEMENT server-v0.92.0'"
    signed wrong_line "$fake@sha256:$abc server-v0.91.0"$'\n'
    check "statement: a line with another version under this tag's comment" "$(verdict wrong_line)" \
        "no the statement doesn't name $fake@sha256:<64 hex> server-v0.92.0"
    local bad="no the statement doesn't name $fake@sha256:<64 hex> server-v0.92.0"
    signed other_image "ghcr.io/example-test/other-image@sha256:$abc server-v0.92.0"$'\n'
    check "statement: another image" "$(verdict other_image)" "$bad"
    signed short "$fake@sha256:${abc:1} server-v0.92.0"$'\n'
    check "statement: a short digest" "$(verdict short)" "$bad"
    signed upper "$fake@sha256:${abc^^} server-v0.92.0"$'\n'
    check "statement: an upper-case digest" "$(verdict upper)" "$bad"
    signed space "$fake@sha256:$abc server-v0.92.0 "$'\n'
    check "statement: a trailing space" "$(verdict space)" "$bad"
    local one="no the statement is not exactly one line"
    signed two_lines "$fake@sha256:$abc server-v0.92.0"$'\n'"$fake@sha256:$abc server-v0.92.0"$'\n'
    check "statement: a second line" "$(verdict two_lines)" "$one"
    signed crlf "$fake@sha256:$abc server-v0.92.0"$'\r\n'
    check "statement: CRLF" "$(verdict crlf)" "$one"
    signed no_newline "$fake@sha256:$abc server-v0.92.0"
    check "statement: no newline" "$(verdict no_newline)" "$one"
    signed blank_after "$fake@sha256:$abc server-v0.92.0"$'\n\n'
    check "statement: a blank line after" "$(verdict blank_after)" "$one"
    : >"$s/empty"
    check "statement: an empty file" "$(verdict empty)" "no no $STATEMENT"

    check "digest: equal" "$(digest_decision "sha256:$abc" "sha256:$abc")" ok
    check "digest: a moved registry tag" "$(digest_decision "sha256:$abc" "sha256:$ones")" \
        "mismatch: signed sha256:$abc, found sha256:$ones"
    check "digest: another HANDY_DIGEST" "$(digest_decision "sha256:$abc" "sha256:$zeros")" \
        "mismatch: signed sha256:$abc, found sha256:$zeros"
    check "digest: none found" "$(digest_decision "sha256:$abc" "")" "mismatch: signed sha256:$abc, found nothing"

    # --auto's pick on canned releases: fetch_statement answers from $rel/<version>/ and notes each call. The test key
    # stands in for the pinned one, inside the pick's subshell only.
    local rel="$dir/rel" v
    for v in 0.93.0 0.94.0 0.95.0 0.96.0 0.97.0 0.98.0 0.99.0; do
        mkdir -p "$rel/$v"
        printf '%s@sha256:%s server-v%s\n' "$IMAGE" "$abc" "$v" >"$rel/$v/$STATEMENT"
        minisign -S -s "$k/test.key" -m "$rel/$v/$STATEMENT" -x "$rel/$v/$STATEMENT.minisig" \
            -t "$STATEMENT server-v$v" >/dev/null 2>&1
    done
    # 0.99.0: signed by another key; 0.98.0: no statement at all.
    minisign -S -s "$k/other.key" -m "$rel/0.99.0/$STATEMENT" -x "$rel/0.99.0/$STATEMENT.minisig" \
        -t "$STATEMENT server-v0.99.0" >/dev/null 2>&1
    mv "$rel/0.98.0" "$dir/gone"
    # Its own name: signed_digest has a local $dir of its own, which the fake would otherwise write into.
    local fetch_log="$dir/fetched"
    pick() {
        (
            SERVER_SIGNING_PUBKEY="$pub"
            fetch_statement() {
                echo "$1" >>"$fetch_log"
                [ -f "$rel/$1/$STATEMENT" ] || return 1
                cp "$rel/$1/$STATEMENT" "$rel/$1/$STATEMENT.minisig" "$2/"
            }
            : >"$fetch_log"
            printf '%s\n' "${@:2}" | pick_signed "$1" 2>"$dir/skipped" || echo none
        )
    }
    check "pick: newest bad, next good" "$(pick 0.96.0 0.99.0 0.97.0 0.96.0)" "0.97.0 sha256:$abc"
    check "pick: one skipped line" "$(cat "$dir/skipped")" \
        "skipped server-v0.99.0: the signature doesn't verify with the pinned key"
    check "pick: none verifies" "$(pick 0.97.0 0.99.0 0.98.0 0.97.0 0.96.0)" none
    check "pick: none verifies, two skipped lines" "$(cat "$dir/skipped")" "$(printf '%s\n' \
        "skipped server-v0.99.0: the signature doesn't verify with the pinned key" \
        "skipped server-v0.98.0: the release or its $STATEMENT could not be downloaded")"
    check "pick: past two bad ones" "$(pick 0.90.0 0.99.0 0.98.0 0.97.0 0.96.0 0.95.0 0.94.0 0.93.0)" \
        "0.97.0 sha256:$abc"
    # Only bad ones among the five highest: the sixth (0.94.0) and seventh are never tried.
    for v in 0.97.0 0.96.0 0.95.0; do
        : >"$rel/$v/$STATEMENT.minisig"
    done
    check "pick: more than five newer" "$(pick 0.90.0 0.99.0 0.98.0 0.97.0 0.96.0 0.95.0 0.94.0 0.93.0)" none
    check "pick: only five tried" "$(paste -sd ' ' "$fetch_log")" "0.99.0 0.98.0 0.97.0 0.96.0 0.95.0"
    check "pick: none newer" "$(pick 0.99.0 0.99.0 0.94.0)" none
    check "pick: nothing fetched when none is newer" "$(wc -l <"$fetch_log")" "0"

    # --verify-statement uses the pinned key: the test key's statement is refused there; a wrong count is usage.
    local script
    script="$(readlink -f -- "$0")"
    check "verify-statement: the pinned key refuses the test key" \
        "$("$BASH" "$script" --verify-statement "$rel/0.94.0/$STATEMENT" "$rel/0.94.0/$STATEMENT.minisig" 0.94.0 \
            2>&1 || echo "exit $?")" \
        "the signature doesn't verify with the pinned key"$'\n'"exit 1"
    check "verify-statement: usage" "$("$BASH" "$script" --verify-statement x 2>/dev/null || echo "exit $?")" "exit 2"

    # Without minisign on the PATH the script stops first, with exit 2 and its line.
    mkdir -p "$dir/host" "$dir/nobin"
    : >"$dir/host/compose.yaml"
    : >"$dir/host/.env"
    local missing="minisign is not installed (apt install minisign): the image signature can't be checked"
    check "no minisign" "$(cd "$dir/host" && PATH="$dir/nobin" "$BASH" "$script" 0.92.0 2>&1 || echo "exit $?")" \
        "$missing"$'\n'"exit 2"
    check "no minisign, --auto" "$(cd "$dir/host" && PATH="$dir/nobin" "$BASH" "$script" --auto 2>&1 ||
        echo "exit $?")" "$missing"$'\n'"exit 2"
    check "no minisign, --verify-statement" \
        "$(PATH="$dir/nobin" "$BASH" "$script" --verify-statement a b 0.92.0 2>&1 || echo "exit $?")" \
        "$missing"$'\n'"exit 2"

    rm -rf -- "${dir:?}"

    if [ "$failed" -ne 0 ]; then
        echo "update-image.sh selftest failed" >&2
        exit 1
    fi
    echo "update-image.sh selftest passed"
}

usage() {
    echo "usage: $0 X.Y.Z | --auto | --selftest | --verify-statement FILE FILE.minisig X.Y.Z" >&2
    echo "(a server-vX.Y.Z release; Settings > Software Updates shows the newest)" >&2
    exit 2
}

# No fallback: without minisign nothing is checked, so nothing runs.
need_minisign() {
    if ! command -v minisign >/dev/null 2>&1; then
        echo "minisign is not installed (apt install minisign): the image signature can't be checked" >&2
        exit 2
    fi
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
    --verify-statement)
        # The release workflow's check (server-release.yml's merge job): the pinned key and the real image name.
        need_minisign
        [ "$#" -eq 4 ] || usage
        if ! out="$(statement_digest "$2" "$3" "$SERVER_SIGNING_PUBKEY" "$IMAGE" "$4")"; then
            echo "$out" >&2
            exit 1
        fi
        printf '%s\n' "$out"
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
need_minisign
for tool in docker gh jq flock; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "$tool is not installed" >&2
        exit 2
    fi
done
if [ "$SERVER_SIGNING_PUBKEY" = "not-set" ]; then
    echo "No release signing key is pinned in this script: it accepts no image (server/DEPLOY.md, \"Release signing\")." >&2
    exit 1
fi
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
    versions="$(stable_versions_desc <<<"$releases")"
    newest="$(head -n 1 <<<"$versions")"
    case "$(auto_decision "$current" "$newest")" in
        update)
            echo "server-v$newest is out (running $current): checking the signed image statements"
            # The skipped lines go to the journal (stderr).
            if ! picked="$(pick_signed "$current" <<<"$versions")"; then
                echo "no newer release has a valid signed image statement: nothing changed" >&2
                exit 1
            fi
            read -r version digest <<<"$picked"
            echo "server-v$version: the signed image statement verifies ($digest)"
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
            if ! digest="$(signed_digest "$version")"; then
                echo "server-v$version has no valid signed image statement: nothing changed ($digest)" >&2
                exit 1
            fi
            decision="$(digest_decision "$digest" "${pinned#@}")"
            if [ "$decision" != ok ]; then
                echo ".env's HANDY_DIGEST is not server-v$version's signed image: nothing changed ($decision)" >&2
                exit 1
            fi
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
    echo "Checking the signed image statement of server-v$version ..."
    if ! digest="$(signed_digest "$version")"; then
        echo "server-v$version has no valid signed image statement: nothing changed ($digest)" >&2
        exit 1
    fi
fi

# The signed digest is the digest from here on. The registry's X.Y.Z tag must name it too: a moved tag fails the run
# visibly.
manifest="$(docker buildx imagetools inspect "$IMAGE:$version" --format '{{json .Manifest}}' || true)"
tagged="$(printf '%s' "$manifest" | jq -r '.digest // empty' 2>/dev/null || true)"
decision="$(digest_decision "$digest" "$tagged")"
if [ "$decision" != ok ]; then
    echo "$IMAGE:$version is not the signed image: nothing changed ($decision)" >&2
    exit 1
fi

# A second check: GitHub's build attestation of that same digest.
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
