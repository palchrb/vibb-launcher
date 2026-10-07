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
# our screening service: Telecom's FILTERING_COMPLETED or our log, after the step started); a
# missed call from the contact (rings, the caller hangs up) posts our notification (id 1006) and no
# Telecom TelecomMissedCalls notification (design 12);
# outgoing calls to an unknown number (cancelled - Telecom's "Canceled from Call Redirection
# Service" or our log line) vs the contact typed in national form (redirected - our log line - and
# dialled); one call at a time (our "second call" log line, during the answered incoming call); the
# call notification and call screen gone after hang-up; never the system's "App is not available"
# screen (BlockedAppActivity) after the unlock, on Recents or on a gesture swipe-up (design 16); with
# REBOOT=1, after a reboot no UI dump shows Home's content (contacts, grid, call card) before the PIN
# unlock (design 16c), and for 45 s after the lock is up neither the shade nor Quick Settings opens
# over it and SystemUI's TaskbarDelegate has the status bar's disable flags (design 16d). Call state
# comes from `dumpsys telecom`. Screenshots of every step go into a folder; a PASS/FAIL/SKIP summary at the
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
#   CONSOLE_HOST    emulator console host - loopback or a Tailscale 100.64.0.0/10 IP (default
#                   127.0.0.1; the token is sent in cleartext, so nothing else - use the ssh tunnel)
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
#   REBOOT=1        first reboot the emulator (`adb reboot`) and dump the UI until the PIN lock is in
#                   front: no dump may show Home's content (design 16c); then pull the shade over the
#                   lock for 45 s (design 16d). Needs KID_PIN. Unset: SKIP.
#   STRICT=1        count SKIP as a failure
#   ELEMENT_SESSION, ELEMENT_ROOM  optional (design 15): the kid's own Element X MXID and a DM's room
#                   ID (as the launcher learns them from Element X's DM notification); with both set,
#                   Element X's elementx://open link is started and must open Element X - whether it
#                   shows that DM (not the room list) is checked on the screenshot. Unset: not run.
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
REBOOT="${REBOOT:-0}"
ELEMENT_SESSION="${ELEMENT_SESSION:-}"
ELEMENT_ROOM="${ELEMENT_ROOM:-}"
ELEMENT_PKG=io.element.android.x
CALL_NOTIFICATION_ID=1005
MISSED_NOTIFICATION_ID=1006
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

# Loopback, or a Tailscale address (100.64.0.0/10: WireGuard encrypts the tailnet hop).
tailnet_ip() {
    [[ "$1" =~ ^100\.([0-9]{1,3})\.[0-9]{1,3}\.[0-9]{1,3}$ ]] &&
        [ "${BASH_REMATCH[1]}" -ge 64 ] && [ "${BASH_REMATCH[1]}" -le 127 ]
}
case "$CONSOLE_HOST" in
    127.0.0.1 | localhost | ::1) ;;
    *)
        if ! tailnet_ip "$CONSOLE_HOST"; then
            echo "CONSOLE_HOST=$CONSOLE_HOST: the console must be reached on loopback or a tailnet IP (the" >&2
            echo "auth token is sent in cleartext) - tunnel it: ssh -N -L 5554:127.0.0.1:5554 <vm>" >&2
            exit 2
        fi
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
    if grep -q -i -e '^KO' -e '^error' <<<"$reply"; then
        printf '%s\n' "$reply" | grep -i -e '^KO' -e '^error' >&2
        return 1
    fi
    last="$(printf '%s\n' "$reply" | grep -v '^[[:space:]]*$' | tail -1)"
    [ "$last" = "OK" ] || return 1
    printf '%s\n' "$reply" | grep -v -e '^OK' -e '^Android Console' -e '^Documentation' -e "^'" -e '^[[:space:]]*$' || true
}

# Calls as Telecom sees them (`dumpsys telecom`, mCalls) - the console's `gsm list` stays empty on
# the Android 16 emulator's modem simulator, so it is never used. Telecom redacts the numbers; the
# ringing caller's number comes from telephony.registry.
LIVE_STATES='NEW|CONNECTING|SELECT_PHONE_ACCOUNT|DIALING|RINGING|ACTIVE|ON_HOLD|ANSWERED|AUDIO_PROCESSING|SIMULATED_RINGING|PULLING'
#
# With pipefail, a reader that stops early (`grep -q`, `grep -m1`, awk's exit) can fail the whole
# pipeline through the writer's SIGPIPE although it matched - so every check below greps a captured
# string (here-string), never a live `adb shell` stream.
call_states() {
    local dump
    dump="$(sh_ dumpsys telecom)"
    awk '/^  mCalls:/ { f = 1; next } f && /^    \[Call id=/ { print; next } { f = 0 }' <<<"$dump" |
        grep -o 'state=[A-Z_]*' | cut -d= -f2
    return 0
}
calls_summary() { call_states | tr '\n' ' '; }
live_calls() { grep -c -E "^($LIVE_STATES)$" <<<"$(call_states)" || true; }
any_call() { [ "$(live_calls)" -gt 0 ]; }
no_call() { ! any_call; }
call_state_is() { grep -q -x -- "$1" <<<"$(call_states)"; }
# ringing_from <E.164>: a call rings and Telephony names that number as the caller.
ringing_from() {
    call_state_is RINGING &&
        grep -q -E -- "mCallIncomingNumber=\+?${1#+}([^0-9]|$)" <<<"$(sh_ dumpsys telephony.registry)"
}

# gsm_call / dial: start a call and remember it for the hang-up on exit.
gsm_call() { PLACED+=("$1"); console gsm call "$1" >/dev/null; }
dial() { PLACED+=("$1"); sh_ am start -a android.intent.action.CALL -d "tel:$1" >/dev/null; }
hang_up() { console gsm cancel "$1" >/dev/null 2>&1 || true; }

# The EXIT trap: hang up everything this script started, then end whatever is left on the phone.
hang_up_all() {
    [ "${#PLACED[@]}" -gt 0 ] || return 0
    local number
    for number in "${PLACED[@]}"; do hang_up "$number"; done
    sleep 1
    if any_call; then
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
top_is() { grep -q -F -- " $1 " <<<"$(top_activity)"; }
# top_package_is <package>: an activity of that package is the resumed activity.
top_package_is() { grep -q -F -- " $1/" <<<"$(top_activity)"; }
top_is_not() { ! top_is "$1"; }
lock_task_state() { sh_ dumpsys activity activities | grep -m1 -o 'mLockTaskModeState=[A-Z]*' | cut -d= -f2; }
call_notification_shown() {
    grep -q -E "key=[0-9]+\|$PKG\|$CALL_NOTIFICATION_ID\|" <<<"$(sh_ dumpsys notification --noredact)"
}
call_notification_gone() { ! call_notification_shown; }
# notification_when <package> <id|-> <channel|-> <dump>: Notification.when of the first active
# NotificationRecord of that package (and id, and channel) in a `dumpsys notification` dump, empty
# if there is none. Plain index() matches, no regex built from the package name.
notification_when() {
    awk -v pkg="$1" -v id="$2" -v channel="$3" '
        /NotificationRecord\(/ {
            inrec = index($0, "pkg=" pkg " ") > 0 &&
                (id == "-" || index($0, " id=" id " ") > 0) &&
                (channel == "-" || index($0, "channel=" channel) > 0)
            next
        }
        inrec && /^[[:space:]]*when=[0-9]+/ {
            sub(/^[[:space:]]*when=/, "")
            sub(/[^0-9].*$/, "")
            print
            exit
        }
    ' <<<"$4"
}
# missed_notice_since <ms>: our missed-call notification is up, for a call at or after <ms>.
missed_notice_since() {
    local when
    when="$(notification_when "$PKG" "$MISSED_NOTIFICATION_ID" - "$(sh_ dumpsys notification --noredact)")"
    [ -n "$when" ] && [ "$when" -ge "$1" ]
}
# Logcat is cleared at the start of each call step, so everything below happened after it.
logcat_clear() { adb_ logcat -c >/dev/null 2>&1 || true; }
logcat_dump() { adb_ logcat -d -v brief 2>/dev/null | tr -d '\r'; }
logcat_has() { grep -q -E -- "$1" <<<"$(logcat_dump)"; }

# The last FILTERING_COMPLETED Telecom logged since the step's logcat_clear (no older history).
filtering_result() { logcat_dump | grep 'FILTERING_COMPLETED' | tail -1; }

# The system's "App is not available" screen (BlockedAppActivity, package android) in front: the kid
# must never see it (design 16). Greps a captured dump, never a live stream.
blocked_app_in_front() {
    local dump front
    dump="$(sh_ dumpsys activity activities)"
    front="$(grep -E 'topResumedActivity|mResumedActivity|mFocusedApp' <<<"$dump" || true)"
    grep -q 'BlockedAppActivity' <<<"$front"
}
# stays_false <seconds> <command...>: the command never succeeds in that time (polled every 0.5 s).
stays_false() {
    local seconds=$1
    shift
    local tries=$((seconds * 2))
    while [ "$tries" -gt 0 ]; do
        if "$@"; then return 1; fi
        sleep 0.5
        tries=$((tries - 1))
    done
    return 0
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

# Home's content (design 16c): its containers exist on the window only while the lock allows it;
# Home's night ground (home_night) is what a locked Home shows instead. Any package prefix: the
# resource package may be the namespace or the application id, and only Home has these ids.
HOME_CONTENT_IDS='home_contacts_scroll|home_contacts|home_grid|home_call_card'
# home_content_in <ui dump>: a node of Home's content is in the captured dump.
home_content_in() { grep -q -E "resource-id=\"[^\"]*:id/($HOME_CONTENT_IDS)\"" <<<"$1"; }
home_night_in() { grep -q -E "resource-id=\"[^\"]*:id/home_night\"" <<<"$1"; }
boot_completed() { [ "$(sh_ getprop sys.boot_completed)" = "1" ]; }

# The notification shade or Quick Settings is expanded (design 16d): SystemUI's shade window has the
# focus, or a UI dump (the active window) shows its panels.
shade_open() {
    local windows focus
    windows="$(sh_ dumpsys window)"
    focus="$(grep -E 'mCurrentFocus|mFocusedWindow' <<<"$windows" || true)"
    if grep -q 'NotificationShade' <<<"$focus"; then return 0; fi
    ui_dump
    grep -q -E 'resource-id="com\.android\.systemui:id/(quick_settings_panel|quick_qs_panel|qs_frame|notification_stack_scroller)"' <<<"$UI_XML"
}
# StatusBarManagerService's disable1 and SystemUI's TaskbarDelegate copy of it (gesture navigation).
# 16d experiment 2: after some boots the service had the LOCKED flags (0x7260000), TaskbarDelegate 0.
statusbar_disable1() { grep -m1 -o 'mDisabled1=0x[0-9a-fA-F]*' <<<"$(sh_ dumpsys statusbar)" | cut -d= -f2; }
# taskbar_field <name> <SystemUI dump>: the first <name>= value after the TaskbarDelegate header.
taskbar_field() {
    awk -v name="$1=" '/TaskbarDelegate/ { seen = 1 }
        seen && index($0, name) { sub(".*" name, ""); sub(/[^-0-9a-fA-Fx].*$/, ""); print; exit }' <<<"$2"
}
# Why SystemUI's TaskbarDelegate isn't the one in use (qa-16d-code #3) - it registers its dump in its
# constructor and prints mDisabledFlags=0 even when never initialised - or nothing when it is.
taskbar_unused() {
    if grep -q 'NavigationBar (displayId=' <<<"$1"; then echo "SystemUI uses NavigationBar"; return; fi
    if [ -z "$(taskbar_field mDisabledFlags "$1")" ]; then echo "no TaskbarDelegate in SystemUI's dump"; return; fi
    if [ "$(taskbar_field mNavigationMode "$1")" = "-1" ]; then echo "TaskbarDelegate never initialised (mNavigationMode=-1)"; fi
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
    if wait_for 4 call_state_is ACTIVE; then return 0; fi
    ui_dump
    tap_node "resource-id=\"$PKG:id/in_call_answer\"" || return 1
    wait_for 4 call_state_is ACTIVE
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

if grep -qx "package:$PKG" <<<"$(sh_ pm list packages "$PKG")"; then
    pass "$PKG is installed"
else
    fail "$PKG is installed"
fi
owner="$(sh_ dpm list-owners | grep -i 'DeviceOwner' | head -1)"
if [ -z "$owner" ]; then
    owner="$(sh_ dumpsys device_policy | grep -A3 -i 'Device Owner' | grep -o 'ComponentInfo{[^}]*}' | head -1)"
fi
if grep -q -e "=$PKG/" -e "{$PKG/" -e " $PKG/" <<<"$owner"; then
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

step "Boot: Home shows no content while locked (design 16c)"
if [ "$REBOOT" != "1" ]; then
    skip "no Home content before the PIN unlock after a reboot" "REBOOT=1 not set"
elif [ -z "$KID_PIN" ]; then
    skip "no Home content before the PIN unlock after a reboot" "KID_PIN not set"
else
    # Sampling starts only once the device runs a new boot (its boot_id changed), so a dump or a
    # lock seen before the reboot took effect can't pass the check (qa-16c-code #4).
    boot_id() { sh_ cat /proc/sys/kernel/random/boot_id; }
    old_boot="$(boot_id)"
    rebooted=0
    # An unreadable boot_id proves nothing: no reboot, FAIL below.
    if [ -n "$old_boot" ]; then adb_ reboot >/dev/null 2>&1 || true; fi
    deadline=$((SECONDS + 180))
    while [ -n "$old_boot" ] && [ "$SECONDS" -lt "$deadline" ]; do
        new_boot="$(boot_id)"
        if [ -n "$new_boot" ] && [ "$new_boot" != "$old_boot" ]; then
            rebooted=1
            break
        fi
        sleep 1
    done
    # From the new boot on: a UI dump every ~0.3 s plus the dump's own time, until the PIN lock is
    # in front (3 min at most). Each dump is captured, then grepped; failed dumps (boot) are skipped.
    dumps=0
    leaks=0
    night_seen=0
    lock_seen=0
    deadline=$((SECONDS + 180))
    while [ "$rebooted" -eq 1 ] && [ "$SECONDS" -lt "$deadline" ]; do
        if boot_completed; then sh_ input keyevent KEYCODE_WAKEUP >/dev/null; fi
        ui_dump
        if grep -q '<node ' <<<"$UI_XML"; then
            dumps=$((dumps + 1))
            if home_content_in "$UI_XML"; then
                leaks=$((leaks + 1))
                printf '%s\n' "$UI_XML" >"$OUT_DIR/boot-home-content-$leaks.xml"
                shot "boot-home-content-$leaks"
            fi
            if home_night_in "$UI_XML"; then night_seen=1; fi
        fi
        if top_is "$LOCK_ACTIVITY"; then
            lock_seen=1
            break
        fi
        sleep 0.3
    done
    if [ "$rebooted" -ne 1 ]; then
        fail "no Home content before the PIN unlock after a reboot" "no new boot_id within 3 min (was ${old_boot:-unreadable})"
    elif [ "$dumps" -eq 0 ]; then
        fail "no Home content before the PIN unlock after a reboot" "no UI dump succeeded before the lock - no evidence"
    elif [ "$lock_seen" -ne 1 ]; then
        fail "no Home content before the PIN unlock after a reboot" "the PIN lock never came to the front ($dumps UI dumps); top: $(top_activity)"
    elif [ "$leaks" -gt 0 ]; then
        fail "no Home content before the PIN unlock after a reboot" "$leaks of $dumps UI dumps showed Home's content (boot-home-content-*.xml)"
    else
        pass "no Home content before the PIN unlock after a reboot ($dumps UI dumps$([ "$night_seen" -eq 1 ] && echo ", the night ground seen"))"
    fi
    shot boot-lock
fi

step "Boot: no shade over the lock (design 16d)"
# SystemUI could lose lock task's status-bar flags at boot: the shade with Quick Settings opened over
# the lock (16d experiment 2). Probed for 45 s from the lock's first appearance - past the launcher's
# heals 1-40 s after its start - then SystemUI's copy of the flags is compared with the service's.
if [ "$REBOOT" != "1" ] || [ -z "$KID_PIN" ]; then
    skip "the shade can't be opened over the lock after a reboot" "REBOOT=1 and KID_PIN needed"
    skip "SystemUI has the status bar's disable flags after a reboot" "REBOOT=1 and KID_PIN needed"
elif [ "${lock_seen:-0}" -ne 1 ]; then
    skip "the shade can't be opened over the lock after a reboot" "the PIN lock wasn't in front after the reboot"
    skip "SystemUI has the status bar's disable flags after a reboot" "the PIN lock wasn't in front after the reboot"
else
    dims="$(grep -o '[0-9][0-9]*x[0-9][0-9]*' <<<"$(sh_ wm size)" | tail -1 || true)"
    width="${dims%x*}"
    height="${dims#*x}"
    opened=""
    probes=0
    started=$SECONDS
    while [ $((SECONDS - started)) -lt 45 ]; do
        # A screen-off would heal the flags itself (and hide a desync): keep the screen on.
        sh_ input keyevent KEYCODE_WAKEUP >/dev/null
        for probe in expand-notifications expand-settings swipe; do
            if [ "$probe" = "swipe" ]; then
                [[ "$width" =~ ^[0-9]+$ && "$height" =~ ^[0-9]+$ ]] || continue
                sh_ input swipe $((width / 2)) 2 $((width / 2)) $((height * 2 / 3)) 300 >/dev/null
            else
                sh_ cmd statusbar "$probe" >/dev/null
            fi
            sleep 0.7
            probes=$((probes + 1))
            if shade_open; then
                opened="${opened:+$opened, }$probe at +$((SECONDS - started)) s"
                shot "boot-shade-open-$probes"
            fi
            sh_ cmd statusbar collapse >/dev/null
        done
        sleep 1
    done
    if ! top_is "$LOCK_ACTIVITY"; then
        fail "the shade can't be opened over the lock after a reboot" "the lock left the front during the probes; top: $(top_activity)${opened:+; opened: $opened}"
    elif [ -n "$opened" ]; then
        fail "the shade can't be opened over the lock after a reboot" "opened: $opened (screenshots boot-shade-open-*)"
    else
        pass "the shade can't be opened over the lock after a reboot ($probes probes in 45 s)"
    fi
    sb="$(statusbar_disable1)"
    sysui="$(sh_ dumpsys activity service com.android.systemui)"
    unused="$(taskbar_unused "$sysui")"
    tb="$(taskbar_field mDisabledFlags "$sysui")"
    if [ -n "$unused" ]; then
        skip "SystemUI has the status bar's disable flags after a reboot" "$unused"
    elif [ -z "$sb" ]; then
        skip "SystemUI has the status bar's disable flags after a reboot" "no mDisabled1 in dumpsys statusbar"
    elif [ "$((sb))" -eq "$((tb))" ]; then
        pass "SystemUI has the status bar's disable flags after a reboot (mDisabled1=$sb, TaskbarDelegate $tb)"
    else
        fail "SystemUI has the status bar's disable flags after a reboot" "dumpsys statusbar mDisabled1=$sb, SystemUI TaskbarDelegate mDisabledFlags=$tb"
    fi
    shot boot-shade-probed
fi

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
if wait_for 8 ringing_from "$ALLOWED_NUMBER" && wait_for 5 top_is "$CALL_ACTIVITY"; then
    result="$(filtering_result)"
    if grep -q -E 'Reject|shouldReject *= *true' <<<"$result"; then
        fail "allowed contact rings on our call screen" "screened out: $result"
    else
        pass "allowed contact rings on our call screen${result:+ ($(printf '%s' "$result" | grep -o 'FILTERING_COMPLETED.*' | cut -c1-80))}"
    fi
else
    fail "allowed contact rings on our call screen" "calls: $(calls_summary) top: $(top_activity)"
fi
shot incoming-allowed
if answer; then
    pass "the call is answered"
    shot in-call
    # One call at a time: while this call is on, a second (otherwise allowed) outgoing call is
    # cancelled - our log line is the evidence, and Telecom must still have one live call. (Tested
    # on an incoming call: the emulator's modem simulator hangs up outgoing calls at once.)
    logcat_clear
    dial "$ALLOWED_NUMBER"
    if wait_for 8 logcat_has 'second (outgoing )?call'; then
        if [ "$(live_calls)" -le 1 ]; then
            pass "one call at a time ($(logcat_dump | grep -m1 -o -E '(Cancelling|Disconnecting) a second.*'))"
        else
            fail "one call at a time" "logged, but Telecom has: $(calls_summary)"
        fi
    else
        fail "one call at a time" "no 'second call' line logged; calls: $(calls_summary)"
    fi
    shot one-call
else
    fail "the call is answered" "calls: $(calls_summary)"
    skip "one call at a time" "the call wasn't answered"
fi
hang_up "$ALLOWED_NUMBER"
if wait_for 6 no_call && wait_for 6 call_notification_gone; then
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
if grep -q -E 'Reject|shouldReject *= *true' <<<"$result" || [ -n "$screening" ]; then
    pass "unknown number screened out (${result:-$screening})"
elif logcat_has 'KidInCallService.*Rejecting an incoming call'; then
    fail "unknown number screened out" "our screening didn't reject it (failed open?) - only the in-call service did"
else
    fail "unknown number screened out" "no reject logged; FILTERING_COMPLETED: ${result:-none}; calls: $(calls_summary)"
fi
if top_is "$CALL_ACTIVITY"; then
    fail "no call screen for the unknown number" "top: $(top_activity)"
else
    pass "no call screen for the unknown number"
fi
shot incoming-unknown
hang_up "$UNKNOWN_NUMBER"

step "Missed call"
# Design 12: as the default dialer with a receiver for SHOW_MISSED_CALLS_NOTIFICATION we get
# Telecom's broadcast instead of its own notification: ours (id 1006) must come up, and a
# TelecomMissedCalls record only fails the check when it is for a call after the step started (one
# Telecom posted before the first unlock may be left over - QA 12 #5). Notification.when is the
# call's time on both, compared with the device clock at the start.
wait_for 6 no_call || true
start_s="$(sh_ date +%s)"
if ! [[ "$start_s" =~ ^[0-9]+$ ]]; then
    skip "a missed call posts our notification" "the device clock couldn't be read"
    skip "no TelecomMissedCalls notification for it" "the device clock couldn't be read"
else
    start_ms=$((start_s * 1000))
    gsm_call "$ALLOWED_NUMBER"
    if wait_for 8 ringing_from "$ALLOWED_NUMBER"; then
        hang_up "$ALLOWED_NUMBER"
        wait_for 6 no_call || true
        if wait_for 10 missed_notice_since "$start_ms"; then
            pass "a missed call from the contact posts our notification (id $MISSED_NOTIFICATION_ID)"
        else
            dump="$(sh_ dumpsys notification --noredact)"
            fail "a missed call from the contact posts our notification (id $MISSED_NOTIFICATION_ID)" \
                "ours: when=$(notification_when "$PKG" "$MISSED_NOTIFICATION_ID" - "$dump") (step start $start_ms)"
        fi
        telecom_when="$(notification_when com.android.server.telecom - TelecomMissedCalls "$(sh_ dumpsys notification --noredact)")"
        if [ -n "$telecom_when" ] && [ "$telecom_when" -ge "$start_ms" ]; then
            fail "no TelecomMissedCalls notification for it" "Telecom posted its own (when=$telecom_when) - our receiver wasn't used"
        else
            pass "no TelecomMissedCalls notification for it${telecom_when:+ (an older one is left: when=$telecom_when)}"
        fi
    else
        fail "a missed call from the contact posts our notification (id $MISSED_NOTIFICATION_ID)" "it didn't ring; calls: $(calls_summary)"
        skip "no TelecomMissedCalls notification for it" "no missed call"
        hang_up "$ALLOWED_NUMBER"
    fi
    shot missed-call
    # The call ended with the screen on: the lock comes back before the unlock step.
    if [ "$locked" -eq 1 ]; then wait_for 6 top_is "$LOCK_ACTIVITY" || true; fi
fi

step "Unlock"
if [ -n "$KID_PIN" ] && [ "$locked" -eq 1 ]; then
    if enter_pin "$KID_PIN" && wait_for 5 top_is_not "$LOCK_ACTIVITY"; then
        pass "PIN unlock"
        # Design 16: the lock leaves through Home, so a latent Recents task never surfaces.
        if stays_false 3 blocked_app_in_front; then
            pass "no BlockedAppActivity after unlock"
        else
            fail "no BlockedAppActivity after unlock" "top: $(top_activity)"
        fi
    else
        fail "PIN unlock" "top: $(top_activity)"
        skip "no BlockedAppActivity after unlock" "not unlocked"
    fi
    shot unlocked
fi

step "Recents and swipe-up (design 16)"
# Only meaningful in the kiosk's lock task: without it no BlockedAppActivity is possible (qa-16-17 #10).
lock_task_now="$(lock_task_state)"
if top_is "$LOCK_ACTIVITY"; then
    skip "Recents shows no BlockedAppActivity" "the PIN lock is in front"
    skip "swipe-up shows no BlockedAppActivity" "the PIN lock is in front"
elif [ "$lock_task_now" != "LOCKED" ]; then
    skip "Recents shows no BlockedAppActivity" "no kiosk lock task (mLockTaskModeState=${lock_task_now:-unknown})"
    skip "swipe-up shows no BlockedAppActivity" "no kiosk lock task (mLockTaskModeState=${lock_task_now:-unknown})"
else
    sh_ input keyevent KEYCODE_APP_SWITCH >/dev/null
    if stays_false 3 blocked_app_in_front; then
        pass "Recents shows no BlockedAppActivity"
    else
        fail "Recents shows no BlockedAppActivity" "top: $(top_activity)"
    fi
    shot recents
    sh_ input keyevent KEYCODE_HOME >/dev/null
    sleep 1
    nav="$(sh_ settings get secure navigation_mode)"
    dims="$(grep -o '[0-9][0-9]*x[0-9][0-9]*' <<<"$(sh_ wm size)" | tail -1 || true)"
    width="${dims%x*}"
    height="${dims#*x}"
    if [ "$nav" != "2" ]; then
        skip "swipe-up shows no BlockedAppActivity" "not gesture navigation (navigation_mode=${nav:-unknown})"
    elif ! [[ "$width" =~ ^[0-9]+$ && "$height" =~ ^[0-9]+$ ]]; then
        skip "swipe-up shows no BlockedAppActivity" "screen size unknown ('$dims')"
    else
        swipe_ok=1
        # A slow swipe (towards Recents) and a fast one (Home): quickstep starts its fallback
        # Recents for either while lock task allows the gesture.
        for duration in 1000 200; do
            sh_ input swipe $((width / 2)) $((height - 2)) $((width / 2)) $((height * 3 / 5)) "$duration" >/dev/null
            if ! stays_false 3 blocked_app_in_front; then swipe_ok=0; fi
            shot "swipe-up-$duration"
            sh_ input keyevent KEYCODE_HOME >/dev/null
            sleep 1
        done
        if [ "$swipe_ok" -eq 1 ]; then
            pass "swipe-up shows no BlockedAppActivity"
        else
            fail "swipe-up shows no BlockedAppActivity" "seen after a swipe (screenshots swipe-up-*)"
        fi
    fi
fi

step "Outgoing calls"
# Unknown number: cancelled before it is placed - needs positive evidence, silence fails.
BLOCKED_OUT='Canceled from Call Redirection Service|KidCallRedirection.*Cancelling a not-allowed outgoing call|KidInCallService.*Disconnecting a not-allowed outgoing call'
logcat_clear
dial "$UNKNOWN_NUMBER"
if wait_for 8 logcat_has "$BLOCKED_OUT"; then
    if wait_for 3 no_call; then
        pass "outgoing call to an unknown number is stopped ($(logcat_dump | grep -m1 -o -E "$BLOCKED_OUT"))"
    else
        fail "outgoing call to an unknown number is stopped" "logged as stopped but Telecom has: $(calls_summary)"
    fi
else
    fail "outgoing call to an unknown number is stopped" "no cancel logged (call redirection didn't run?); calls: $(calls_summary)"
fi
hang_up "$UNKNOWN_NUMBER"
shot outgoing-blocked
sh_ input keyevent KEYCODE_HOME >/dev/null

# Allowed contact typed in national form: redirected to the stored E.164 number (our log line - the
# number itself is never logged; outgoingDialTarget is unit-tested) and dialled by Telecom. The
# emulator's modem simulator hangs up outgoing calls at once (DisconnectCause REMOTE), so Telecom's
# SET_DIALING in the log is the evidence that it was placed.
if [ "$ALLOWED_DIAL" = "$ALLOWED_NUMBER" ]; then
    PLACED_OUT='KidCallRedirection.*(Redirecting|Placing) an allowed outgoing call'
else
    PLACED_OUT='KidCallRedirection.*Redirecting an allowed outgoing call'
fi
logcat_clear
dial "$ALLOWED_DIAL"
PLACED+=("$ALLOWED_NUMBER")
if wait_for 8 logcat_has "$PLACED_OUT" && wait_for 6 logcat_has 'SET_DIALING|SET_ACTIVE'; then
    if logcat_has "$BLOCKED_OUT"; then
        fail "outgoing call to the contact ($ALLOWED_DIAL) is redirected and dialled" "also cancelled: $(logcat_dump | grep -m1 -o -E "$BLOCKED_OUT")"
    else
        pass "outgoing call to the contact ($ALLOWED_DIAL) is redirected and dialled"
    fi
else
    fail "outgoing call to the contact ($ALLOWED_DIAL) is redirected and dialled" \
        "calls: $(calls_summary) log: $(logcat_dump | grep -m1 -E 'KidCallRedirection|KidInCallService')"
fi
shot outgoing-allowed
hang_up "$ALLOWED_NUMBER"
if any_call; then sh_ input keyevent KEYCODE_ENDCALL >/dev/null; fi
if wait_for 6 no_call && wait_for 6 call_notification_gone; then
    pass "call notification cleared after an outgoing call"
else
    fail "call notification cleared after an outgoing call" "calls: $(calls_summary)"
fi
if wait_for 6 top_is_not "$CALL_ACTIVITY"; then
    pass "no call screen left after the call"
else
    fail "no call screen left after the call" "top: $(top_activity)"
fi
shot after-outgoing

# Design 15, optional: Element X's own open link, built like the launcher's elementRoomUri (every
# byte but RFC 3986 unreserved characters percent-encoded). Needs Element X allowed and signed in
# as ELEMENT_SESSION, and the phone unlocked.
uri_encode() {
    local LC_ALL=C s="$1" out="" c i
    for ((i = 0; i < ${#s}; i++)); do
        c="${s:i:1}"
        case "$c" in
            [A-Za-z0-9._~-]) out+="$c" ;;
            *) out+="$(printf '%%%02X' "'$c")" ;;
        esac
    done
    printf '%s' "$out"
}
if [ -n "$ELEMENT_SESSION" ] && [ -n "$ELEMENT_ROOM" ]; then
    step "Element X room link (optional)"
    element_link="elementx://open/$(uri_encode "$ELEMENT_SESSION")/$(uri_encode "$ELEMENT_ROOM")"
    element_out="$(sh_ am start -W -a android.intent.action.VIEW -d "$element_link" "$ELEMENT_PKG")"
    if grep -q -E 'Error|unable to resolve' <<<"$element_out"; then
        fail "the elementx open link starts Element X" "$(grep -m1 -E 'Error|unable to resolve' <<<"$element_out")"
    elif wait_for 5 top_package_is "$ELEMENT_PKG"; then
        pass "the elementx open link starts Element X (screenshot: the DM must be open, not the room list)"
    else
        fail "the elementx open link starts Element X" "top: $(top_activity)"
    fi
    shot element-room
    sh_ input keyevent KEYCODE_HOME >/dev/null
fi

echo
echo "== Manual step: Element X Message button (design 15)"
echo "   With Element X allowed and a phone-book contact on Element: the contact sends the kid a DM;"
echo "   after that notification the contact sheet's Message opens that DM (before it: the profile)."
echo "   ELEMENT_SESSION/ELEMENT_ROOM check the link itself."

echo
echo "== Manual step: a VoIP call over the PIN lock (design 17)"
echo "   Element X allowed and a contact's messaging app, the phone LOCKED with the screen off: call the kid"
echo "   from another Element account. The lock wakes and rings with 'Ringer deg i Element X' (Avvis/Svar);"
echo "   Svar opens Element's ring screen, answer there - the lock stays away during the call (logcat"
echo "   VoipCalls 'VoIP RINGING -> IN_CALL', PinLock 'exempt screen: voip') and comes back after hang-up."

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
