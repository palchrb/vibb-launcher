#!/usr/bin/env bash
# shellcheck disable=SC2317  # helpers run indirectly through wait_for and the EXIT trap
# Smoke test for the launcher on the Android EMULATOR, driven by adb and the emulator console -
# run it after every change and before allowing Android updates (docs/testing/emulator.md §5b).
#
# Emulator only: it places calls. Before anything else it proves the target is an emulator
# (ro.kernel.qemu / ro.boot.qemu = 1) with a working console (`avd name` answers OK, and names the
# same AVD as the device when the device says which), and aborts otherwise - it never falls through
# to a real phone. Every call it starts is hung up again on exit, also after a failure or Ctrl+C.
# It never places an emergency call - that stays a manual step (printed at the end).
#
# Checks: device owner and kiosk state; the PIN lock at screen-off/on; PIN unlock; incoming calls
# from an allowed contact (rings, answerable over the lock) vs an unknown number (screened out by
# our screening service: Telecom's FILTERING_COMPLETED or our log, after the step started);
# outgoing calls to an unknown number (cancelled - Telecom's "Canceled from Call Redirection
# Service" or our log line) vs the contact typed in national form (redirected to the stored E.164
# number); one call at a time (our "second call" log line); the call notification and call screen
# gone after hang-up. Screenshots of every step go into a folder; a PASS/FAIL/SKIP summary at the
# end (exit 1 on any FAIL, also on SKIP with STRICT=1).
#
# Setup (the PWA): the phone enrolled and managed, calls managed and on, ALLOWED_NUMBER a contact
# allowed in and out, UNKNOWN_NUMBER no contact, a kid PIN set (KID_PIN) for the lock checks.
#
# Environment:
#   ADB             adb command (default adb). Remote: tunnel the VM's adb server and console over ssh
#                   and point at loopback, e.g. ssh -N -L 5038:127.0.0.1:5037 -L 5554:127.0.0.1:5554 vm
#                   then ADB="adb -H 127.0.0.1 -P 5038" (never expose an adb server with `adb -a`)
#   PKG             launcher package (default me.vibb.launcher.debug)
#   CONSOLE_HOST    emulator console host - loopback only (default 127.0.0.1; the token would cross
#                   the network in cleartext otherwise - use the ssh tunnel)
#   CONSOLE_PORT    emulator console port (default 5554)
#   CONSOLE_TOKEN   console auth token (default: ~/.emulator_console_auth_token). Required when ADB
#                   has -H/-P (a remote adb server: `adb emu` would talk to this machine's loopback);
#                   without a token and with a local adb, `adb emu` is used.
#   ALLOWED_NUMBER  allowed contact, E.164 with + (default +4791234567)
#   ALLOWED_DIAL    the same number as dialled for the redirection check (default: ALLOWED_NUMBER
#                   without +47, i.e. national form)
#   UNKNOWN_NUMBER  a number that is no contact (default +4799999999)
#   KID_PIN         the kid's PIN (lock checks are skipped without it)
#   OUT_DIR         screenshot folder (default ./smoke-<date>, gitignored)
#   EXPECT_KIOSK=0  a phone whose kiosk is off on purpose (lock task isn't required then)
#   STRICT=1        count SKIP as a failure
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
LOCK_ACTIVITY="$PKG/com.kidslauncher.mdm.lock.PinLockActivity"
CALL_ACTIVITY="$PKG/com.kidslauncher.mdm.calls.InCallActivity"

PASSES=0
FAILS=0
SKIPS=0
RESULTS=()
SHOT=0
# Every number this script called or dialled: hung up again on exit.
PLACED=()

pass() { PASSES=$((PASSES + 1)); RESULTS+=("PASS  $1"); echo "PASS  $1"; }
fail() { FAILS=$((FAILS + 1)); RESULTS+=("FAIL  $1${2:+ - $2}"); echo "FAIL  $1${2:+ - $2}"; }
skip() { SKIPS=$((SKIPS + 1)); RESULTS+=("SKIP  $1${2:+ - $2}"); echo "SKIP  $1${2:+ - $2}"; }
step() { echo; echo "== $*"; }

summary() {
    echo
    echo "== Summary ($OUT_DIR)"
    if [ "${#RESULTS[@]}" -gt 0 ]; then printf '%s\n' "${RESULTS[@]}"; fi
    echo "PASS $PASSES  FAIL $FAILS  SKIP $SKIPS"
}

# abort <reason>: stop before (more) calls are placed; the EXIT trap still hangs up.
abort() {
    fail "$1" "${2:-}"
    echo "ABORTED: ${2:-$1}" >&2
    summary
    exit 1
}

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

# ---- configuration checks ---------------------------------------------------------------------

case "$CONSOLE_HOST" in
    127.0.0.1 | localhost | ::1) ;;
    *)
        echo "CONSOLE_HOST=$CONSOLE_HOST: the console must be reached on loopback (the auth token is sent" >&2
        echo "in cleartext) - tunnel it: ssh -N -L 5554:127.0.0.1:5554 <vm>" >&2
        exit 2
        ;;
esac

remote_adb() {
    local arg
    for arg in "${ADB_CMD[@]}"; do
        case "$arg" in -H | -P | -L) return 0 ;; esac
    done
    return 1
}

for var in ALLOWED_NUMBER UNKNOWN_NUMBER; do
    if ! [[ "${!var}" =~ ^\+[1-9][0-9]{7,14}$ ]]; then
        echo "$var=${!var}: give a full E.164 number (+ and 8-15 digits) - never a short or emergency number" >&2
        exit 2
    fi
done
if ! [[ "$ALLOWED_DIAL" =~ ^\+?[0-9]{8,15}$ ]]; then
    echo "ALLOWED_DIAL=$ALLOWED_DIAL: 8-15 digits - never a short or emergency number" >&2
    exit 2
fi
if [ "$ALLOWED_NUMBER" = "$UNKNOWN_NUMBER" ]; then
    echo "ALLOWED_NUMBER and UNKNOWN_NUMBER must differ" >&2
    exit 2
fi

# ---- emulator console -------------------------------------------------------------------------

use_tcp_console() { [ -n "$CONSOLE_TOKEN" ] || remote_adb; }

# console <command...>: one console command; prints the reply without the protocol lines. Fails
# unless the console answered the command with OK: no reply, a KO, an `adb emu` error or exit
# status all count as a failure.
console() {
    local reply status last
    if use_tcp_console; then
        [ -n "$CONSOLE_TOKEN" ] || return 1
        if ! { exec 3<>"/dev/tcp/$CONSOLE_HOST/$CONSOLE_PORT"; } 2>/dev/null; then
            return 1
        fi
        printf 'auth %s\r\n%s\r\nquit\r\n' "$CONSOLE_TOKEN" "$*" >&3
        reply="$(timeout 5 cat <&3 | tr -d '\r')"
        status=$?
        exec 3<&- 3>&-
    else
        reply="$(adb_ emu "$@" 2>&1 | tr -d '\r')"
        status=$?
    fi
    [ "$status" -eq 0 ] || return 1
    if printf '%s\n' "$reply" | grep -q -i -e '^KO' -e '^error'; then
        printf '%s\n' "$reply" | grep -i -e '^KO' -e '^error' >&2
        return 1
    fi
    last="$(printf '%s\n' "$reply" | grep -v '^[[:space:]]*$' | tail -1)"
    [ "$last" = "OK" ] || return 1
    printf '%s\n' "$reply" | grep -v -e '^OK' -e '^Android Console' -e '^Documentation' -e "^'" -e '^[[:space:]]*$' || true
}

gsm_list() { console gsm list; }
has_call() { gsm_list | grep -q -- "${1#+}"; }
no_call() { ! has_call "$1"; }
call_state_is() { gsm_list | grep -- "${1#+}" | grep -q "$2"; }

# gsm_call / dial: start a call and remember it for the hang-up on exit.
gsm_call() { PLACED+=("$1"); console gsm call "$1" >/dev/null; }
dial() { PLACED+=("$1"); sh_ am start -a android.intent.action.CALL -d "tel:$1" >/dev/null; }
hang_up() { console gsm cancel "$1" >/dev/null 2>&1 || true; }

# The EXIT trap: hang up everything this script started, then end whatever is left on the phone.
hang_up_all() {
    [ "${#PLACED[@]}" -gt 0 ] || return 0
    local number left
    for number in "${PLACED[@]}"; do hang_up "$number"; done
    left="$(gsm_list 2>/dev/null)" || left="unknown"
    if [ -n "$left" ]; then
        sh_ input keyevent KEYCODE_ENDCALL >/dev/null 2>&1 || true
        for number in "${PLACED[@]}"; do hang_up "$number"; done
    fi
}
trap hang_up_all EXIT
trap 'exit 130' INT TERM

# ---- device state -----------------------------------------------------------------------------

top_activity() {
    sh_ dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity' || true
}
# top_is <package/class>: exactly that component is the resumed activity.
top_is() { top_activity | grep -q -F -- " $1 "; }
top_is_not() { ! top_is "$1"; }
lock_task_state() { sh_ dumpsys activity activities | grep -m1 -o 'mLockTaskModeState=[A-Z]*' | cut -d= -f2; }
call_notification_shown() {
    sh_ dumpsys notification --noredact | grep -q -E "key=[0-9]+\|$PKG\|$CALL_NOTIFICATION_ID\|"
}
call_notification_gone() { ! call_notification_shown; }
# Logcat is cleared at the start of each call step, so everything below happened after it.
logcat_clear() { adb_ logcat -c >/dev/null 2>&1 || true; }
logcat_dump() { adb_ logcat -d -v brief 2>/dev/null | tr -d '\r'; }
logcat_has() { logcat_dump | grep -q -E -- "$1"; }

# The last FILTERING_COMPLETED Telecom logged since the step's logcat_clear (no older history).
filtering_result() { logcat_dump | grep 'FILTERING_COMPLETED' | tail -1; }

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
    tap_node "resource-id=\"$PKG:id/in_call_answer\"" || return 1
    wait_for 4 call_state_is "$1" active
}

# ---- run --------------------------------------------------------------------------------------

mkdir -p "$OUT_DIR"
echo "Smoke test: ${ADB_CMD[*]}, package $PKG, screenshots in $OUT_DIR"

step "Device"
if ! adb_ get-state >/dev/null 2>&1; then
    abort "adb reaches the device" "$(adb_ get-state 2>&1 | head -1)"
fi
pass "adb reaches the device"

# Emulator only, proven before any call: the qemu property AND a console that answers.
qemu="$(sh_ getprop ro.kernel.qemu)"
boot_qemu="$(sh_ getprop ro.boot.qemu)"
if [ "$qemu" != "1" ] && [ "$boot_qemu" != "1" ]; then
    abort "target is an emulator" "ro.kernel.qemu='$qemu' ro.boot.qemu='$boot_qemu' - this script places calls and runs on the emulator only"
fi
if remote_adb && [ -z "$CONSOLE_TOKEN" ]; then
    abort "emulator console reachable" "ADB uses a remote adb server: set CONSOLE_TOKEN and tunnel the console to $CONSOLE_HOST:$CONSOLE_PORT (emulator.md 5b)"
fi
if ! avd="$(console avd name)" || [ -z "$avd" ]; then
    abort "emulator console reachable" "no OK from the console ($(use_tcp_console && echo "$CONSOLE_HOST:$CONSOLE_PORT" || echo "adb emu")) - fix CONSOLE_PORT/CONSOLE_TOKEN; no call was placed"
fi
device_avd="$(sh_ getprop ro.boot.qemu.avd_name)"
if [ -n "$device_avd" ] && [ "$device_avd" != "$avd" ]; then
    abort "console and adb reach the same emulator" "console: $avd, device: $device_avd"
fi
pass "target is an emulator with a working console ($avd)"

if sh_ pm list packages "$PKG" | grep -qx "package:$PKG"; then
    pass "$PKG is installed"
else
    fail "$PKG is installed"
fi
owner="$(sh_ dpm list-owners | grep -i 'DeviceOwner' | head -1)"
if [ -z "$owner" ]; then
    owner="$(sh_ dumpsys device_policy | grep -A3 -i 'Device Owner' | grep -o 'ComponentInfo{[^}]*}' | head -1)"
fi
if printf '%s' "$owner" | grep -q -e "=$PKG/" -e "{$PKG/" -e " $PKG/"; then
    pass "device owner is $PKG"
else
    fail "device owner is $PKG" "found '${owner:-none}'"
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
locked=0
if [ -z "$KID_PIN" ]; then
    skip "lock at screen-off/on" "KID_PIN not set"
    skip "PIN unlock" "KID_PIN not set"
else
    screen_off
    sleep 2
    screen_on
    if wait_for 5 top_is "$LOCK_ACTIVITY"; then
        pass "screen-off then on shows the PIN lock"
        locked=1
    else
        fail "screen-off then on shows the PIN lock" "top: $(top_activity)"
    fi
    shot lock
fi

step "Incoming calls"
# Allowed contact: Telecom's filter lets it ring, our call screen comes up (over the lock).
logcat_clear
gsm_call "$ALLOWED_NUMBER"
if wait_for 8 call_state_is "$ALLOWED_NUMBER" incoming && wait_for 5 top_is "$CALL_ACTIVITY"; then
    result="$(filtering_result)"
    if printf '%s' "$result" | grep -q -E 'Reject|shouldReject *= *true'; then
        fail "allowed contact rings on our call screen" "screened out: $result"
    else
        pass "allowed contact rings on our call screen${result:+ ($(printf '%s' "$result" | grep -o 'FILTERING_COMPLETED.*' | cut -c1-80))}"
    fi
else
    fail "allowed contact rings on our call screen" "calls: $(gsm_list | tr '\n' ' ') top: $(top_activity)"
fi
shot incoming-allowed
if answer "$ALLOWED_NUMBER"; then
    pass "the call is answered"
else
    fail "the call is answered" "calls: $(gsm_list | tr '\n' ' ')"
fi
shot in-call
hang_up "$ALLOWED_NUMBER"
if wait_for 6 no_call "$ALLOWED_NUMBER" && wait_for 6 call_notification_gone; then
    pass "call notification cleared after hang-up"
else
    fail "call notification cleared after hang-up"
fi
if [ "$locked" -eq 1 ]; then
    if wait_for 6 top_is "$LOCK_ACTIVITY"; then
        pass "back on the PIN lock after the call"
    else
        fail "back on the PIN lock after the call" "top: $(top_activity)"
    fi
fi
shot after-incoming

# Unknown number: rejected by our screening service (only evidence logged after this step's
# logcat_clear counts; a reject by the in-call service means screening failed open).
logcat_clear
gsm_call "$UNKNOWN_NUMBER"
wait_for 6 logcat_has 'FILTERING_COMPLETED|KidCallScreening.*Rejecting|KidInCallService.*Rejecting' || true
sleep 1
result="$(filtering_result)"
screening="$(logcat_dump | grep -m1 -E 'KidCallScreening.*Rejecting an incoming call')"
if { printf '%s' "$result" | grep -q -E 'Reject|shouldReject *= *true'; } || [ -n "$screening" ]; then
    pass "unknown number screened out (${result:-$screening})"
elif logcat_has 'KidInCallService.*Rejecting an incoming call'; then
    fail "unknown number screened out" "our screening didn't reject it (failed open?) - only the in-call service did"
else
    fail "unknown number screened out" "no reject logged; FILTERING_COMPLETED: ${result:-none}; calls: $(gsm_list | tr '\n' ' ')"
fi
if top_is "$CALL_ACTIVITY"; then
    fail "no call screen for the unknown number" "top: $(top_activity)"
else
    pass "no call screen for the unknown number"
fi
shot incoming-unknown
hang_up "$UNKNOWN_NUMBER"

step "Unlock"
if [ -n "$KID_PIN" ] && [ "$locked" -eq 1 ]; then
    if enter_pin "$KID_PIN" && wait_for 5 top_is_not "$LOCK_ACTIVITY"; then
        pass "PIN unlock"
    else
        fail "PIN unlock" "top: $(top_activity)"
    fi
    shot unlocked
fi

step "Outgoing calls"
# Unknown number: cancelled before it is placed - needs positive evidence, silence fails.
BLOCKED_OUT='Canceled from Call Redirection Service|KidCallRedirection.*Cancelling a not-allowed outgoing call|KidInCallService.*Disconnecting a not-allowed outgoing call'
logcat_clear
dial "$UNKNOWN_NUMBER"
if wait_for 8 logcat_has "$BLOCKED_OUT"; then
    if has_call "$UNKNOWN_NUMBER"; then
        fail "outgoing call to an unknown number is stopped" "logged as stopped but on the modem: $(gsm_list | tr '\n' ' ')"
    else
        pass "outgoing call to an unknown number is stopped ($(logcat_dump | grep -m1 -o -E "$BLOCKED_OUT"))"
    fi
else
    fail "outgoing call to an unknown number is stopped" "no cancel logged (call redirection didn't run?); calls: $(gsm_list | tr '\n' ' ')"
fi
hang_up "$UNKNOWN_NUMBER"
shot outgoing-blocked
sh_ input keyevent KEYCODE_HOME >/dev/null

# Allowed contact typed in national form: redirected to the stored E.164 number.
logcat_clear
dial "$ALLOWED_DIAL"
PLACED+=("$ALLOWED_NUMBER")
if wait_for 8 has_call "$ALLOWED_NUMBER"; then
    pass "outgoing call to the contact ($ALLOWED_DIAL) is placed as $ALLOWED_NUMBER"
    console gsm accept "$ALLOWED_NUMBER" >/dev/null 2>&1 || true
    wait_for 4 call_state_is "$ALLOWED_NUMBER" active || true
    shot outgoing-allowed

    # One call at a time: a second call is cancelled while this one is on - our log line is the
    # evidence, and the modem must still have one call.
    logcat_clear
    before="$(gsm_list | grep -c -E 'inbound|outbound')"
    dial "$ALLOWED_NUMBER"
    if wait_for 8 logcat_has 'second (outgoing )?call'; then
        after="$(gsm_list | grep -c -E 'inbound|outbound')"
        if [ "$after" -le "$before" ]; then
            pass "one call at a time ($(logcat_dump | grep -m1 -o -E '(Cancelling|Disconnecting) a second.*'))"
        else
            fail "one call at a time" "logged, but the modem has: $(gsm_list | tr '\n' ' ')"
        fi
    else
        fail "one call at a time" "no 'second call' line logged; calls: $(gsm_list | tr '\n' ' ')"
    fi
    shot one-call
    hang_up "$ALLOWED_NUMBER"
    if wait_for 6 no_call "$ALLOWED_NUMBER" && wait_for 6 call_notification_gone; then
        pass "call notification cleared after an outgoing call"
    else
        fail "call notification cleared after an outgoing call"
    fi
    if wait_for 6 top_is_not "$CALL_ACTIVITY"; then
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

echo
echo "== Manual step (never automated): emergency call"
echo "   On the PIN lock tap 'Emergency call' -> 'Call 112?' and confirm only on the emulator"
echo "   (it fakes 112) or on a phone in its emergency test mode. The emergency dialer/in-call UI must"
echo "   stay in front (logcat PinLock: 'Lock yields to an exempt screen: emergency')."

summary
if [ "$FAILS" -gt 0 ] || { [ "$STRICT" = "1" ] && [ "$SKIPS" -gt 0 ]; }; then
    exit 1
fi
exit 0
