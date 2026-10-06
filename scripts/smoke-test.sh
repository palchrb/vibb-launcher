#!/usr/bin/env bash
# shellcheck disable=SC2317  # helpers run indirectly through wait_for
# Smoke test for the launcher on an emulator (or a phone with adb allowed), driven by adb and the
# emulator console - run it after every change and before allowing Android updates
# (docs/testing/emulator.md, "Smoke-test script").
#
# Checks: device owner and kiosk state; the PIN lock at screen-off/on; PIN unlock; incoming calls
# from an allowed contact (rings, answerable over the lock) vs an unknown number (screened out,
# Telecom's FILTERING_COMPLETED); outgoing calls to a blocked number (cancelled) vs an allowed one
# typed in national form (redirected to the stored E.164 number); one call at a time; the call
# notification gone after hang-up. Screenshots of every step go into a folder; a PASS/FAIL/SKIP
# summary at the end (exit 1 on any FAIL, also on SKIP with STRICT=1).
#
# It never places an emergency call - that stays a manual step (printed at the end).
#
# Setup (the PWA): the phone enrolled and managed, calls managed and on, ALLOWED_NUMBER a contact
# allowed in and out, UNKNOWN_NUMBER no contact, a kid PIN set (KID_PIN) for the lock checks.
#
# Environment:
#   ADB             adb command, e.g. "adb -H build-host -P 5037" for a remote adb server (default adb)
#   PKG             launcher package (default me.vibb.launcher.debug)
#   CONSOLE_HOST    emulator console host (default 127.0.0.1 - the host running the emulator)
#   CONSOLE_PORT    emulator console port (default 5554)
#   CONSOLE_TOKEN   console auth token (default: ~/.emulator_console_auth_token if present; without
#                   a token and without -H in ADB, `adb emu` is used instead)
#   ALLOWED_NUMBER  allowed contact, E.164 with + (default +4791234567)
#   ALLOWED_DIAL    the same number as dialled for the redirection check (default: ALLOWED_NUMBER
#                   without +47, i.e. national form)
#   UNKNOWN_NUMBER  a number that is no contact (default +4799999999)
#   KID_PIN         the kid's PIN (lock checks are skipped without it)
#   OUT_DIR         screenshot folder (default ./smoke-<date>)
#   EXPECT_KIOSK=0  a phone whose kiosk is off on purpose (lock task isn't required then)
#   STRICT=1        count SKIP as a failure
#
# Example (remote adb server and emulator console on host "vm"):
#   ADB="adb -H vm -P 5037" CONSOLE_HOST=vm CONSOLE_TOKEN=$(ssh vm cat .emulator_console_auth_token) \
#     KID_PIN=1234 ./scripts/smoke-test.sh
set -uo pipefail

read -r -a ADB_CMD <<<"${ADB:-adb}"
PKG="${PKG:-me.vibb.launcher.debug}"
CONSOLE_HOST="${CONSOLE_HOST:-127.0.0.1}"
CONSOLE_PORT="${CONSOLE_PORT:-5554}"
if [ -z "${CONSOLE_TOKEN:-}" ] && [ -r "$HOME/.emulator_console_auth_token" ]; then
    CONSOLE_TOKEN="$(cat "$HOME/.emulator_console_auth_token")"
fi
CONSOLE_TOKEN="${CONSOLE_TOKEN:-}"
ALLOWED_NUMBER="${ALLOWED_NUMBER:-+4791234567}"
ALLOWED_DIAL="${ALLOWED_DIAL:-${ALLOWED_NUMBER#+47}}"
UNKNOWN_NUMBER="${UNKNOWN_NUMBER:-+4799999999}"
KID_PIN="${KID_PIN:-}"
OUT_DIR="${OUT_DIR:-./smoke-$(date +%Y%m%d-%H%M%S)}"
STRICT="${STRICT:-0}"
EXPECT_KIOSK="${EXPECT_KIOSK:-1}"
CALL_NOTIFICATION_ID=1005

PASSES=0
FAILS=0
SKIPS=0
RESULTS=()
SHOT=0

pass() { PASSES=$((PASSES + 1)); RESULTS+=("PASS  $1"); echo "PASS  $1"; }
fail() { FAILS=$((FAILS + 1)); RESULTS+=("FAIL  $1${2:+ - $2}"); echo "FAIL  $1${2:+ - $2}"; }
skip() { SKIPS=$((SKIPS + 1)); RESULTS+=("SKIP  $1${2:+ - $2}"); echo "SKIP  $1${2:+ - $2}"; }
step() { echo; echo "== $*"; }

adb_() { "${ADB_CMD[@]}" "$@"; }
sh_() { adb_ shell "$@" 2>/dev/null | tr -d '\r'; }

shot() {
    SHOT=$((SHOT + 1))
    local file
    file="$OUT_DIR/$(printf '%02d' "$SHOT")-$1.png"
    adb_ exec-out screencap -p >"$file" 2>/dev/null || rm -f "$file"
}

# Polls "$@" every half second for up to $1 seconds.
wait_for() {
    local seconds=$1
    shift
    local tries=$((seconds * 2))
    while [ "$tries" -gt 0 ]; do
        if "$@"; then return 0; fi
        sleep 0.5
        tries=$((tries - 1))
    done
    return 1
}

# ---- emulator console -------------------------------------------------------------------------

use_tcp_console() {
    [ -n "$CONSOLE_TOKEN" ] || [ "$CONSOLE_HOST" != "127.0.0.1" ]
}

# console <command...>: one console command; prints the reply without the OK lines. Fails on KO.
console() {
    local reply
    if use_tcp_console; then
        if ! { exec 3<>"/dev/tcp/$CONSOLE_HOST/$CONSOLE_PORT"; } 2>/dev/null; then
            return 1
        fi
        printf 'auth %s\r\n%s\r\nquit\r\n' "$CONSOLE_TOKEN" "$*" >&3
        reply="$(timeout 5 cat <&3 | tr -d '\r')"
        exec 3<&- 3>&-
    else
        reply="$(adb_ emu "$@" 2>&1 | tr -d '\r')"
    fi
    if printf '%s\n' "$reply" | grep -q '^KO'; then
        printf '%s\n' "$reply" | grep '^KO' >&2
        return 1
    fi
    printf '%s\n' "$reply" | grep -v -e '^OK' -e '^Android Console' -e "^Documentation" -e '^$' || true
}

gsm_list() { console gsm list; }
has_call() { gsm_list | grep -q -- "${1#+}"; }
no_call() { ! has_call "$1"; }
call_state_is() { gsm_list | grep -- "${1#+}" | grep -q "$2"; }

# ---- device state -----------------------------------------------------------------------------

top_activity() {
    sh_ dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity' || true
}
top_is() { top_activity | grep -q "$1"; }
top_is_not() { ! top_is "$1"; }
lock_task_state() { sh_ dumpsys activity activities | grep -m1 -o 'mLockTaskModeState=[A-Z]*' | cut -d= -f2; }
call_notification_shown() {
    sh_ dumpsys notification --noredact | grep -q -E "key=[0-9]+\|$PKG\|$CALL_NOTIFICATION_ID\|"
}
call_notification_gone() { ! call_notification_shown; }
logcat_clear() { adb_ logcat -c >/dev/null 2>&1 || true; }
logcat_dump() { adb_ logcat -d -v brief 2>/dev/null | tr -d '\r'; }

# The last FILTERING_COMPLETED of Telecom (logcat, else its event history in dumpsys).
filtering_result() {
    local line
    line="$(logcat_dump | grep 'FILTERING_COMPLETED' | tail -1)"
    if [ -z "$line" ]; then
        line="$(sh_ dumpsys telecom | grep 'FILTERING_COMPLETED' | tail -1)"
    fi
    printf '%s' "$line"
}

screen_off() { sh_ input keyevent KEYCODE_SLEEP >/dev/null; }
screen_on() { sh_ input keyevent KEYCODE_WAKEUP >/dev/null; }

# tap_node <attribute regex>: taps the centre of the first UI node whose attributes match.
UI_XML=""
ui_dump() { UI_XML="$(adb_ exec-out uiautomator dump /dev/tty 2>/dev/null | tr -d '\r')"; }
tap_node() {
    local node coords
    node="$(printf '%s' "$UI_XML" | grep -o '<node [^>]*>' | grep -E -m1 "$1")" || return 1
    coords="$(printf '%s' "$node" | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | grep -o '[0-9]*' | tr '\n' ' ')"
    # shellcheck disable=SC2086
    set -- $coords
    [ $# -eq 4 ] || return 1
    sh_ input tap $((($1 + $3) / 2)) $((($2 + $4) / 2)) >/dev/null
}

enter_pin() {
    local digit i
    ui_dump
    for ((i = 0; i < ${#1}; i++)); do
        digit="${1:i:1}"
        tap_node "(text|content-desc)=\"$digit\"" || return 1
        sleep 0.2
    done
}

answer() {
    sh_ input keyevent KEYCODE_CALL >/dev/null
    if wait_for 4 call_state_is "$1" active; then return 0; fi
    ui_dump
    tap_node 'resource-id="[^"]*:id/in_call_answer"' || return 1
    wait_for 4 call_state_is "$1" active
}

# ---- run --------------------------------------------------------------------------------------

mkdir -p "$OUT_DIR"
echo "Smoke test: ${ADB_CMD[*]}, package $PKG, screenshots in $OUT_DIR"

step "Device"
if ! adb_ get-state >/dev/null 2>&1; then
    fail "adb reaches the device" "$(adb_ get-state 2>&1 | head -1)"
    echo
    printf '%s\n' "${RESULTS[@]}"
    exit 1
fi
pass "adb reaches the device"
if sh_ pm list packages "$PKG" | grep -qx "package:$PKG"; then
    pass "$PKG is installed"
else
    fail "$PKG is installed"
fi
owner="$(sh_ dpm list-owners | grep -i 'DeviceOwner' | head -1)"
if [ -z "$owner" ]; then
    owner="$(sh_ dumpsys device_policy | grep -A3 -i 'Device Owner' | grep -o 'ComponentInfo{[^}]*}' | head -1)"
fi
if printf '%s' "$owner" | grep -q -e "$PKG/" -e "{$PKG/"; then
    pass "device owner is $PKG"
else
    fail "device owner is $PKG" "found '${owner:-none}'"
fi
console_ok=1
if ! gsm_list >/dev/null 2>&1; then
    console_ok=0
fi
sh_ input keyevent KEYCODE_WAKEUP >/dev/null
sleep 1
kiosk="$(lock_task_state)"
if [ "$EXPECT_KIOSK" = "1" ]; then
    case "$kiosk" in
        LOCKED) pass "kiosk (lock task) engaged: $kiosk" ;;
        *) fail "kiosk (lock task) engaged" "mLockTaskModeState=${kiosk:-unknown}" ;;
    esac
else
    pass "kiosk not required (EXPECT_KIOSK=0): mLockTaskModeState=${kiosk:-unknown}"
fi
shot device

step "PIN lock"
if [ -z "$KID_PIN" ]; then
    skip "lock at screen-off/on" "KID_PIN not set"
    skip "PIN unlock" "KID_PIN not set"
    locked=0
else
    screen_off
    sleep 2
    screen_on
    if wait_for 5 top_is PinLockActivity; then
        pass "screen-off then on shows the PIN lock"
        locked=1
    else
        fail "screen-off then on shows the PIN lock" "top: $(top_activity)"
        locked=0
    fi
    shot lock
fi

step "Incoming calls"
if [ "$console_ok" -ne 1 ]; then
    skip "incoming calls" "emulator console unreachable at $CONSOLE_HOST:$CONSOLE_PORT (CONSOLE_TOKEN?)"
else
    # Allowed contact: Telecom's filter lets it ring, our call screen comes up (over the lock).
    logcat_clear
    console gsm call "$ALLOWED_NUMBER" >/dev/null
    if wait_for 8 call_state_is "$ALLOWED_NUMBER" incoming && wait_for 5 top_is InCallActivity; then
        result="$(filtering_result)"
        if printf '%s' "$result" | grep -q -E 'Reject|shouldReject *= *true'; then
            fail "allowed contact rings" "screened out: $result"
        else
            pass "allowed contact rings (${result:+$(printf '%s' "$result" | grep -o 'FILTERING_COMPLETED.*' | cut -c1-80)})"
        fi
    else
        fail "allowed contact rings" "calls: $(gsm_list | tr '\n' ' ') top: $(top_activity)"
    fi
    shot incoming-allowed
    if answer "$ALLOWED_NUMBER"; then
        pass "the call is answered"
    else
        fail "the call is answered" "calls: $(gsm_list | tr '\n' ' ')"
    fi
    shot in-call
    console gsm cancel "$ALLOWED_NUMBER" >/dev/null
    if wait_for 6 no_call "$ALLOWED_NUMBER" && wait_for 6 call_notification_gone; then
        pass "call notification cleared after hang-up"
    else
        fail "call notification cleared after hang-up"
    fi
    if [ "$locked" -eq 1 ]; then
        if wait_for 6 top_is PinLockActivity; then
            pass "back on the PIN lock after the call"
        else
            fail "back on the PIN lock after the call" "top: $(top_activity)"
        fi
    fi
    shot after-incoming

    # Unknown number: rejected by our screening, never rings.
    logcat_clear
    console gsm call "$UNKNOWN_NUMBER" >/dev/null
    sleep 4
    result="$(filtering_result)"
    ours="$(logcat_dump | grep -E 'KidCallScreening|KidInCallService' | grep -m1 'Rejecting')"
    if { printf '%s' "$result" | grep -q -E 'Reject|shouldReject *= *true'; } || [ -n "$ours" ]; then
        pass "unknown number screened out (${result:-$ours})"
    else
        fail "unknown number screened out" "FILTERING_COMPLETED: ${result:-none}; calls: $(gsm_list | tr '\n' ' ')"
    fi
    if top_is InCallActivity; then
        fail "no call screen for the unknown number" "top: $(top_activity)"
    else
        pass "no call screen for the unknown number"
    fi
    shot incoming-unknown
    console gsm cancel "$UNKNOWN_NUMBER" >/dev/null 2>&1 || true
fi

step "Unlock"
if [ -n "$KID_PIN" ] && [ "$locked" -eq 1 ]; then
    if enter_pin "$KID_PIN" && wait_for 5 top_is_not PinLockActivity; then
        pass "PIN unlock"
    else
        fail "PIN unlock" "top: $(top_activity)"
    fi
    shot unlocked
fi

step "Outgoing calls"
if [ "$console_ok" -ne 1 ]; then
    skip "outgoing calls" "emulator console unreachable"
else
    # Blocked number: cancelled before it is placed.
    logcat_clear
    sh_ am start -a android.intent.action.CALL -d "tel:$UNKNOWN_NUMBER" >/dev/null
    sleep 4
    if has_call "$UNKNOWN_NUMBER"; then
        fail "outgoing call to an unknown number is stopped" "calls: $(gsm_list | tr '\n' ' ')"
        console gsm cancel "$UNKNOWN_NUMBER" >/dev/null 2>&1 || true
    else
        pass "outgoing call to an unknown number is stopped"
    fi
    shot outgoing-blocked
    sh_ input keyevent KEYCODE_HOME >/dev/null

    # Allowed contact typed in national form: redirected to the stored E.164 number.
    logcat_clear
    sh_ am start -a android.intent.action.CALL -d "tel:$ALLOWED_DIAL" >/dev/null
    if wait_for 8 has_call "$ALLOWED_NUMBER"; then
        pass "outgoing call to the contact ($ALLOWED_DIAL) is placed as $ALLOWED_NUMBER"
        console gsm accept "$ALLOWED_NUMBER" >/dev/null 2>&1 || true
        wait_for 4 call_state_is "$ALLOWED_NUMBER" active || true
        shot outgoing-allowed

        # One call at a time: a second call is cancelled while this one is on.
        before="$(gsm_list | grep -c -E 'inbound|outbound')"
        sh_ am start -a android.intent.action.CALL -d "tel:$ALLOWED_NUMBER" >/dev/null
        sleep 4
        after="$(gsm_list | grep -c -E 'inbound|outbound')"
        second="$(logcat_dump | grep -m1 -E 'second (outgoing )?call')"
        if [ "$after" -le "$before" ]; then
            pass "one call at a time (${second:-second call not placed})"
        else
            fail "one call at a time" "calls: $(gsm_list | tr '\n' ' ')"
        fi
        shot one-call
        console gsm cancel "$ALLOWED_NUMBER" >/dev/null
        if wait_for 6 no_call "$ALLOWED_NUMBER" && wait_for 6 call_notification_gone; then
            pass "call notification cleared after an outgoing call"
        else
            fail "call notification cleared after an outgoing call"
        fi
        if wait_for 6 top_is_not InCallActivity; then
            pass "no call screen left after the call"
        else
            fail "no call screen left after the call" "top: $(top_activity)"
        fi
        shot after-outgoing
    else
        fail "outgoing call to the contact ($ALLOWED_DIAL) is placed as $ALLOWED_NUMBER" \
            "calls: $(gsm_list | tr '\n' ' ') log: $(logcat_dump | grep -m1 -E 'KidCallRedirection|KidInCallService')"
        shot outgoing-allowed
    fi
fi

echo
echo "== Manual step (never automated): emergency call"
echo "   On the PIN lock tap 'Emergency call' -> 'Call 112?' and confirm only on the emulator"
echo "   (it fakes 112) or on a phone in its emergency test mode. The emergency dialer/in-call UI must"
echo "   stay in front (logcat PinLock: 'Lock yields to an exempt screen: emergency')."

echo
echo "== Summary ($OUT_DIR)"
printf '%s\n' "${RESULTS[@]}"
echo "PASS $PASSES  FAIL $FAILS  SKIP $SKIPS"
if [ "$FAILS" -gt 0 ] || { [ "$STRICT" = "1" ] && [ "$SKIPS" -gt 0 ]; }; then
    exit 1
fi
exit 0
