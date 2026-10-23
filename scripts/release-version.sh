#!/usr/bin/env bash
# The versionName and versionCode a release tag builds - the one rule for both apps' release workflows
# (.github/workflows/launcher.yml and music.yml). Usage:
#
#   scripts/release-version.sh launcher-v0.36.0-rc.4   # prints name=0.36.0-rc.4, code=360004, prerelease=true
#   scripts/release-version.sh --selftest              # the examples, the ranges, malformed tags, the contract file
#
# A tag is launcher-vX.Y.Z or music-vX.Y.Z (a release) or either with -rc.N (a release candidate, a prerelease).
# versionCode = X*10_000_000 + Y*10_000 + Z*10 + N, where N is the RC number (1..8) and 9 for the release, so every
# build has its own code and each one installs over the one before by Android's own rule:
#   0.36.0-rc.3 -> 360003, 0.36.0-rc.4 -> 360004, 0.36.0 -> 360009, 0.36.1-rc.1 -> 360011.
# Ranges: X <= 199 (Android's cap is 2_100_000_000), Y and Z <= 999, N 1..8; numbers without leading zeros.
#
# Before 2026-10-23 the rule was X*1_000_000 + Y*1_000 + Z, an RC one less (every RC of a version shared a code).
# Every tag up to launcher-v0.36.0-rc.3 and music-v0.5.0-rc.3 was built that way, and every code of today's rule is
# above every code of that one, so Android installs a new build over an old one. But an old launcher build decides
# with its own parser, which reads every tag by the old rule (launcher-v0.36.0-rc.4 to rc.8 as 35999, its own code):
# 0.36.0-rc.3 takes them only by the legacy name rule (`launcherBuildAhead`, capability same_code_update_v1), and
# 0.36.0-rc.1/rc.2 (without it) only from 0.36.0 on (36000) or by hand - so the name rule and the capability stay
# needed while any phone runs 0.36.0-rc.1 to rc.3. A tag below the first one under today's rule
# (launcher-v0.36.0-rc.4, music-v0.5.0-rc.4) is refused: its code would read differently on the server and the
# launcher, which know the old rule for exactly those tags (server/testdata/launcher_tag_codes.json, the launcher's
# `launcherTagVersionCode`, the server's `install_now::launcher_tag_version_code` and, for the music app's code,
# `version_of_code` in server/src/handlers/device_pages.rs, whose MUSIC_NEW_RULE_FROM is MUSIC_FIRST's code).
#
# On success prints the three lines name=, code=, prerelease= on stdout (for $GITHUB_OUTPUT); on failure the reason
# on stderr and exit 1.
set -euo pipefail

# The first tag of each app under today's rule, as X Y Z N. The launcher's is also NEW_RULE_FROM in SelfUpdatePlan.kt
# and install_now.rs, the music app's MUSIC_NEW_RULE_FROM (50004) in device_pages.rs; the contract's rows tie them.
LAUNCHER_FIRST=(0 36 0 4)
MUSIC_FIRST=(0 5 0 4)

release_version() {
    local tag=$1 app version first
    case "$tag" in
        launcher-v*) app=launcher; version=${tag#launcher-v}; first=("${LAUNCHER_FIRST[@]}") ;;
        music-v*) app=music; version=${tag#music-v}; first=("${MUSIC_FIRST[@]}") ;;
        *) echo "Tag '$tag' is neither launcher-v... nor music-v..." >&2; return 1 ;;
    esac
    local num='(0|[1-9][0-9]{0,8})'
    if [[ ! "$version" =~ ^$num\.$num\.$num(-rc\.$num)?$ ]]; then
        echo "Tag '$tag' is neither $app-vMAJOR.MINOR.PATCH nor $app-vMAJOR.MINOR.PATCH-rc.N" >&2
        return 1
    fi
    local major=${BASH_REMATCH[1]} minor=${BASH_REMATCH[2]} patch=${BASH_REMATCH[3]} rc=${BASH_REMATCH[5]:-}
    local n=9 prerelease=false
    if [ -n "$rc" ]; then n=$rc; prerelease=true; fi
    # Below the first tag under today's rule (an RC sorts before its release).
    local order=1000000000 i
    if [ -n "$rc" ]; then order=$rc; fi
    local mine=("$major" "$minor" "$patch" "$order")
    for i in 0 1 2 3; do
        if (( mine[i] != first[i] )); then
            if (( mine[i] < first[i] )); then
                echo "Tag '$tag' is older than $app-v${first[0]}.${first[1]}.${first[2]}-rc.${first[3]}, the first tag under this versionCode rule" >&2
                return 1
            fi
            break
        fi
    done
    if (( major > 199 || minor > 999 || patch > 999 )); then
        echo "Tag '$tag' is out of range (MAJOR <= 199, MINOR and PATCH <= 999)" >&2
        return 1
    fi
    if [ "$prerelease" = true ] && (( n < 1 || n > 8 )); then
        echo "Tag '$tag' is out of range (rc.1 to rc.8; the release itself is the 9th build)" >&2
        return 1
    fi
    echo "name=$version"
    echo "code=$(( major * 10000000 + minor * 10000 + patch * 10 + n ))"
    echo "prerelease=$prerelease"
}

selftest() {
    local failures=0 tag want got
    check() {  # tag, the code it must build or "-" for a refusal
        tag=$1 want=$2
        if got=$(release_version "$tag" 2>/dev/null); then got=$(sed -n 's/^code=//p' <<<"$got"); else got=-; fi
        printf '%-34s %-11s %s\n' "$tag" "$got" "$([ "$got" = "$want" ] && echo ok || echo "FAIL (want $want)")"
        [ "$got" = "$want" ] || failures=$((failures + 1))
    }
    echo "tag                                code        result"
    # The examples, both apps, the cutover.
    check launcher-v0.36.0-rc.4 360004
    check launcher-v0.36.0 360009
    check launcher-v0.36.1-rc.1 360011
    check launcher-v0.36.0-rc.3 -
    check launcher-v0.35.9 -
    check launcher-v1.2.3-rc.8 10020038
    check music-v0.5.0-rc.4 50004
    check music-v0.5.0 50009
    check music-v0.5.0-rc.3 -
    check music-v0.4.9 -
    check music-v1.0.0 10000009
    # The ranges.
    check launcher-v199.999.999 1999999999
    check launcher-v199.999.999-rc.8 1999999998
    check launcher-v200.0.0 -
    check launcher-v1.1000.0 -
    check launcher-v1.0.1000 -
    check launcher-v1.0.0-rc.9 -
    check launcher-v1.0.0-rc.0 -
    check launcher-v1.0.0-rc.10 -
    check music-v1.0.0-rc.9 -
    check music-v200.0.0 -
    # Malformed.
    check launcher-v01.0.0 -
    check launcher-v1.0 -
    check launcher-v1.0.0-rc. -
    check launcher-v1.0.0-beta -
    check launcher-v1.0.0-rc.1-rc.2 -
    check "launcher-v1.0.0@5" -
    check v1.0.0 -
    check server-v1.0.0 -
    check music-1.0.0 -
    check "" -
    # The tags in the shared contract (server/testdata/launcher_tag_codes.json): its launcher rows (`"tag"`, the
    # parsers' cases) and music rows (`"music_tag"`, the server's version_of_code cases). A tag this script builds gets
    # the row's code; a null row is refused; every row from the app's first tag under today's rule on is built, an
    # older one refused. The parsers' other inputs (a release id after `@`, other names) are theirs.
    local contract
    contract="$(dirname "${BASH_SOURCE[0]}")/../server/testdata/launcher_tag_codes.json"
    local launcher_rows=0 music_rows=0 line code bad first_code
    local launcher_first=$(( LAUNCHER_FIRST[0] * 10000000 + LAUNCHER_FIRST[1] * 10000 + LAUNCHER_FIRST[2] * 10 + LAUNCHER_FIRST[3] ))
    local music_first=$(( MUSIC_FIRST[0] * 10000000 + MUSIC_FIRST[1] * 10000 + MUSIC_FIRST[2] * 10 + MUSIC_FIRST[3] ))
    while IFS= read -r line; do
        if [[ "$line" =~ \"tag\":\ \"(launcher-v[^\"@]*)\",\ \"code\":\ (null|[0-9]+) ]]; then
            first_code=$launcher_first launcher_rows=$((launcher_rows + 1))
        elif [[ "$line" =~ \"music_tag\":\ \"(music-v[^\"@]*)\",\ \"code\":\ (null|[0-9]+) ]]; then
            first_code=$music_first music_rows=$((music_rows + 1))
        else
            continue
        fi
        tag=${BASH_REMATCH[1]} code=${BASH_REMATCH[2]}
        if got=$(release_version "$tag" 2>/dev/null); then got=$(sed -n 's/^code=//p' <<<"$got"); else got=-; fi
        bad=false
        if [ "$code" = null ]; then
            [ "$got" = - ] || bad=true
        elif [ "$got" = - ]; then
            (( code < first_code )) || bad=true
        else
            [ "$got" = "$code" ] || bad=true
        fi
        if [ "$bad" = true ]; then
            printf '%-34s %-11s FAIL (contract %s)\n' "$tag" "$got" "$code"
            failures=$((failures + 1))
        fi
    done <"$contract"
    if (( launcher_rows < 20 || music_rows < 10 )); then
        echo "FAIL: only $launcher_rows launcher and $music_rows music rows read from $contract"
        failures=$((failures + 1))
    else
        echo "contract: $launcher_rows launcher and $music_rows music rows of server/testdata/launcher_tag_codes.json checked"
    fi
    if (( failures > 0 )); then
        echo "$failures failed"
        return 1
    fi
    echo "all ok"
}

case "${1:-}" in
    --selftest) selftest ;;
    "") echo "usage: $0 <launcher-v|music-vX.Y.Z[-rc.N]> | --selftest" >&2; exit 2 ;;
    *) release_version "$1" ;;
esac
