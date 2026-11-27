#!/usr/bin/env bash
# The oldest Vibb server a launcher or music build needs (design 140 §2) - the value Gradle writes into the APK as
# assets/vibb/min_server (contracts/music/min_server.json), which a server reads before it serves the release. Usage:
#
#   scripts/min-server.sh launcher            # prints X.Y.Z on stdout, "case N: why" on stderr
#   scripts/min-server.sh music
#   scripts/min-server.sh --check-server-tag server-vX.Y.Z [<commit>]   # §2.1's guard, server-release.yml's first step
#   scripts/min-server.sh --selftest          # in a throwaway git repo; no network
#
# V = server/Cargo.toml's version, found from the repository's top wherever this is called from.
#   0. A shallow clone, or a repo with no server-v* tag at all, fails: the tags weren't fetched, and case 2 would then
#      answer wrongly.
#   1. V must be X.Y.Z.
#   2. No tag server-vV: V ("not yet tagged", an unreleased bump; §2.1's guard makes the later server-vV carry it).
#   3. server-vV on HEAD or after it: V.
#   4. server-vV before HEAD and nothing the app talks to changed since: V.
#   5. Else X.Y.(Z+1): any server released after V.
#
# The app's paths (what changes the server's behaviour towards that app) live only here, in counted_paths; the guard
# uses the same. The server's own code counts whole for both apps; its tests, docs, the PWA's templates and static
# files, test data and deploy scripts don't. The launcher counts contracts/core, integrations and shipped; the music app
# contracts/music except bridge_contract.json (launcher to music, the server never reads it).
#
# --check-server-tag (design 140 §2.1): a server-vV tag goes on the same commit as, or a commit after, every
# launcher-v*/music-v* tag built while server/Cargo.toml said V - unless nothing that app talks to differs between the
# two. The commit is HEAD unless given.
set -euo pipefail

# The app's paths as git pathspecs (relative to the repository's top).
counted_paths() {
    local app=$1
    local server=(server/src server/migrations server/Cargo.toml server/Cargo.lock)
    local not_counted=(':(exclude,glob)server/src/**/tests/**' ':(exclude,glob)server/src/**/tests.rs'
        ':(exclude,glob)**/*.md')
    case "$app" in
        launcher) printf '%s\n' "${server[@]}" contracts/core contracts/integrations contracts/shipped "${not_counted[@]}" ;;
        music) printf '%s\n' "${server[@]}" contracts/music "${not_counted[@]}" \
            ':(exclude)contracts/music/bridge_contract.json' ;;
        *) echo "Unknown app '$app' (launcher or music)" >&2; return 2 ;;
    esac
}

# server/Cargo.toml's [package] version at a revision ("" = the working tree).
cargo_version() {
    local top=$1 rev=${2:-} text
    if [ -n "$rev" ]; then
        text=$(git -C "$top" show "$rev:server/Cargo.toml" 2>/dev/null) || return 1
    else
        text=$(cat "$top/server/Cargo.toml") || return 1
    fi
    awk '/^\[/ { in_package = ($0 == "[package]") } in_package && /^version *= *"/ {
        sub(/^version *= *"/, ""); sub(/".*$/, ""); print; exit }' <<<"$text"
}

is_x_y_z() {
    [[ "$1" =~ ^(0|[1-9][0-9]{0,3})\.(0|[1-9][0-9]{0,3})\.(0|[1-9][0-9]{0,3})$ ]]
}

# Fails on a shallow clone or a repo without server-v* tags; prints the repository's top.
repo_top() {
    local top
    top=$(git rev-parse --show-toplevel 2>/dev/null) || { echo "Not inside a git repository" >&2; return 1; }
    if [ "$(git -C "$top" rev-parse --is-shallow-repository)" = true ]; then
        echo "A shallow clone: fetch the whole history and the tags (fetch-depth: 0)" >&2
        return 1
    fi
    if [ -z "$(git -C "$top" tag -l 'server-v*' | head -n 1)" ]; then
        echo "No server-v* tag in this repository: fetch the tags (fetch-depth: 0)" >&2
        return 1
    fi
    printf '%s\n' "$top"
}

min_server() {
    local app=$1 top version tag paths changed
    counted_paths "$app" >/dev/null || return 2
    top=$(repo_top) || return 1
    version=$(cargo_version "$top") || true
    if ! is_x_y_z "${version:-}"; then
        echo "server/Cargo.toml's version '${version:-}' is not X.Y.Z" >&2
        return 1
    fi
    tag="server-v$version"
    if ! git -C "$top" rev-parse -q --verify "refs/tags/$tag^{commit}" >/dev/null; then
        echo "case 2: $tag not yet tagged" >&2
        echo "$version"
        return 0
    fi
    if git -C "$top" merge-base --is-ancestor HEAD "$tag"; then
        echo "case 3: $tag is on this commit or after it" >&2
        echo "$version"
        return 0
    fi
    mapfile -t paths < <(counted_paths "$app")
    changed=$(git -C "$top" diff --name-only "$tag" HEAD -- "${paths[@]}")
    if [ -z "$changed" ]; then
        echo "case 4: nothing the $( [ "$app" = music ] && echo "music app" || echo launcher ) talks to changed since $tag" >&2
        echo "$version"
        return 0
    fi
    local major minor patch
    IFS=. read -r major minor patch <<<"$version"
    echo "case 5: $(head -n 3 <<<"$changed" | paste -sd ' ' -)$([ "$(wc -l <<<"$changed")" -gt 3 ] && echo ' ...') changed since $tag" >&2
    echo "$major.$minor.$((patch + 1))"
}

check_server_tag() {
    local tag=${1:-} commit=${2:-HEAD} top version app_tag app paths failures=0
    version=${tag#server-}
    version=${version#v}
    if ! is_x_y_z "$version"; then
        echo "'$tag' is not server-vX.Y.Z" >&2
        return 2
    fi
    top=$(repo_top) || return 1
    git -C "$top" rev-parse -q --verify "$commit^{commit}" >/dev/null || { echo "No commit '$commit'" >&2; return 1; }
    while IFS= read -r app_tag; do
        [ "$(cargo_version "$top" "$app_tag" || true)" = "$version" ] || continue
        if git -C "$top" merge-base --is-ancestor "$app_tag" "$commit"; then
            continue
        fi
        app=${app_tag%%-v*}
        mapfile -t paths < <(counted_paths "$app")
        if git -C "$top" diff --quiet "$app_tag" "$commit" -- "${paths[@]}"; then
            continue
        fi
        echo "$app_tag was built for server $version on a commit this tag lacks: tag the server on it or later, or bump to the next version" >&2
        failures=$((failures + 1))
    done < <(git -C "$top" tag -l 'launcher-v*' 'music-v*')
    if (( failures > 0 )); then
        return 1
    fi
    echo "server-v$version: every launcher and music tag built for $version is on this commit's history or needs nothing it lacks"
}

selftest() {
    local failures=0 dir repo
    dir=$(mktemp -d)
    # shellcheck disable=SC2064
    trap "rm -rf '$dir'" RETURN
    repo="$dir/repo"
    g() { git -C "$repo" -c user.name=selftest -c user.email=selftest@example.invalid -c commit.gpgSign=false \
        -c tag.gpgSign=false "$@"; }
    put() { mkdir -p "$repo/$(dirname "$1")"; printf '%s\n' "$2" >"$repo/$1"; g add -A; g commit -q -m "$1"; }
    cargo() { printf '[package]\nname = "kid_phone_server"\nversion = "%s"\n\n[dependencies]\nfoo = { version = "9.9.9" }\n' "$1"; }
    expect() {  # name, app, the value it must print or "-" for a failure, [dir to run from]
        local name=$1 app=$2 want=$3 from=${4:-$repo} got
        if got=$(cd "$from" && min_server "$app" 2>/dev/null); then :; else got=-; fi
        printf '%-58s %-9s %-8s %s\n' "$name" "$app" "$got" "$([ "$got" = "$want" ] && echo ok || echo "FAIL (want $want)")"
        [ "$got" = "$want" ] || failures=$((failures + 1))
    }
    both() { expect "$1" launcher "$2"; expect "$1" music "$3"; }
    # The guard: name, the tag, the commit, want "pass" or "refuse".
    guard() {
        local name=$1 tag=$2 commit=$3 want=$4 got
        if (cd "$repo" && check_server_tag "$tag" "$commit" >/dev/null 2>&1); then got=pass; else got=refuse; fi
        printf '%-58s %-18s %s\n' "$name" "$got" "$([ "$got" = "$want" ] && echo ok || echo "FAIL (want $want)")"
        [ "$got" = "$want" ] || failures=$((failures + 1))
    }

    mkdir -p "$repo"
    git -C "$repo" init -q -b main
    put server/Cargo.toml "$(cargo 0.9.0)"
    put server/src/main.rs "fn main() {}"
    put contracts/core/a.json "{}"
    put contracts/music/music_library.json "{}"
    put contracts/music/bridge_contract.json "{}"
    put launcher/app/a.kt "a"
    echo "case                                                       app       value    result"
    expect "no server-v* tag at all" launcher -
    g tag server-v0.9.0
    put server/Cargo.toml "$(cargo 0.10.0)"
    both "no tag for Cargo's version (case 2)" 0.10.0 0.10.0
    g tag server-v0.10.0
    both "the tag on HEAD (case 3)" 0.10.0 0.10.0
    local tagged
    tagged=$(g rev-parse HEAD)
    put launcher/app/a.kt "b"
    put server/src/tests/x.rs "t"
    put server/src/weather/tests.rs "t"
    put server/src/weather/tests/y.rs "t"
    put server/templates/a.html "x"
    put server/static/a.css "x"
    put server/testdata/a.json "x"
    put server/deploy/a.sh "x"
    put server/src/NOTES.md "x"
    put contracts/core/README.md "x"
    both "the tag before, only uncounted paths changed (case 4)" 0.10.0 0.10.0
    expect "called from a subdirectory" launcher 0.10.0 "$repo/launcher/app"
    g checkout -q "$tagged"
    both "the tag after HEAD (case 3)" 0.10.0 0.10.0
    g checkout -q main
    put contracts/music/bridge_contract.json '{"b": 1}'
    both "bridge_contract.json changed (case 4 for both)" 0.10.0 0.10.0
    put contracts/music/music_library.json '{"m": 1}'
    both "contracts/music changed (music case 5)" 0.10.0 0.10.1
    put server/Cargo.toml "$(cargo 0.10.1)"
    g tag server-v0.10.1
    put contracts/core/a.json '{"c": 1}'
    both "contracts/core changed (launcher case 5)" 0.10.2 0.10.1
    put server/Cargo.toml "$(cargo 0.11.0)"
    g tag server-v0.11.0
    put server/src/main.rs "fn main() { 1; }"
    both "server/src changed (case 5 for both)" 0.11.1 0.11.1
    put server/migrations/0001.sql "x"
    both "server/migrations changed (case 5)" 0.11.1 0.11.1
    put server/Cargo.toml "$(cargo 0.11)"
    both "Cargo's version not X.Y.Z" - -
    put server/Cargo.toml "$(cargo 0.012.0)"
    expect "Cargo's version with a leading zero" launcher -
    put server/Cargo.toml "$(cargo 0.12.0)"
    expect "unknown app" vibb -
    git clone -q --depth 1 "file://$repo" "$dir/shallow" 2>/dev/null
    git -C "$dir/shallow" fetch -q --depth 1 origin 'refs/tags/*:refs/tags/*' 2>/dev/null || true
    expect "a shallow clone" launcher - "$dir/shallow"

    # §2.1's guard. Cargo says 0.12.0 on HEAD, no server-v0.12.0 yet.
    echo "guard                                                      answer"
    local before after
    put server/src/main.rs "fn main() { 2; }"
    before=$(g rev-parse HEAD)
    g tag launcher-v0.50.0-rc.1
    guard "an app tag on the server tag's commit" server-v0.12.0 "$before" pass
    put launcher/app/a.kt "c"
    after=$(g rev-parse HEAD)
    guard "an app tag before the server tag" server-v0.12.0 "$after" pass
    put server/templates/b.html "y"
    g tag music-v0.9.0-rc.1
    guard "an app tag after it, only an uncounted change" server-v0.12.0 "$after" pass
    put server/src/main.rs "fn main() { 3; }"
    g tag launcher-v0.50.0-rc.2
    guard "an app tag after it with a counted change" server-v0.12.0 "$after" refuse
    guard "the same server tag on that app tag's commit" server-v0.12.0 HEAD pass
    guard "another version's server tag ignores them" server-v0.11.1 "$after" pass
    guard "a malformed server tag" server-v0.12 HEAD refuse
    local rc2
    rc2=$(g rev-parse HEAD)
    put contracts/core/b.json "{}"
    g tag music-v0.9.0-rc.2
    guard "a music tag after it, only the launcher's contracts" v0.12.0 "$rc2" pass
    put contracts/music/music_library.json '{"m": 2}'
    g tag music-v0.9.0-rc.3
    guard "a music tag after it, its own contracts" v0.12.0 "$rc2" refuse

    if (( failures > 0 )); then
        echo "$failures failed"
        return 1
    fi
    echo "all ok"
}

case "${1:-}" in
    --selftest) selftest ;;
    --check-server-tag) check_server_tag "${2:-}" "${3:-HEAD}" ;;
    launcher | music) min_server "$1" ;;
    *) echo "usage: $0 <launcher|music> | --check-server-tag server-vX.Y.Z [<commit>] | --selftest" >&2; exit 2 ;;
esac
