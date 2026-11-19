#!/bin/bash
# emulator-roaming.sh - an emulator with a Norwegian SIM that can be put on another country's network: real roaming
# for Vibb's abroad rules (design 97). docs/testing/2026-11-19-roaming-emulator.md says how and why it works.
#
#   emulator-roaming.sh setup                 make the private system-image dir and emulator copy (once)
#   emulator-roaming.sh start PLMN [SIM]      (re)start the AVD on network PLMN with the SIM SIM (default 24201)
#   emulator-roaming.sh stop                  stop the AVD
#
# PLMN = SIM is the SIM's own network (home). Any other 5- or 6-digit PLMN is another network: 24001 Sweden,
# 23801 Denmark, 90112 a ship, 311740 the US, 24201 with SIM 24214 national roaming. A start is a cold boot: the
# phone is "turned on" on that network. `adb emu gsm data roaming` + `gsm voice roaming` then sets Android's roaming
# flag; without them the foreign network is registered as home (unflagged).
#
# Env: AVD (default jelly36), PORT (default 5556; 5554 is refused), DIR (default ~/roaming-avd), IMAGE (the SDK's
# system-image dir, default android-36 google_apis_playstore x86_64). Needs `source ~/handy-env.sh` (emulator, adb).
# Run under the emulator lock (`flock -o ~/emulator.lock`), one emulator at a time. The AVD's own data is used as is:
# keep a copy and put it back afterwards. Nothing in the SDK is changed: the image's data/ and the emulator's qemu
# binary are private copies under DIR; the qemu copy has its first built-in operator ("311740") replaced.
set -euo pipefail

AVD=${AVD:-jelly36}
PORT=${PORT:-5556}
DIR=${DIR:-$HOME/roaming-avd}
SDK=${ANDROID_HOME:?source ~/handy-env.sh first}
IMAGE=${IMAGE:-$SDK/system-images/android-36/google_apis_playstore/x86_64}
AVD_DIR=${ANDROID_AVD_HOME:-$HOME/.android/avd}/$AVD.avd
SYSDIR=$DIR/sysdir
EMU=$DIR/emulator
QEMU=$EMU/qemu/linux-x86_64/qemu-system-x86_64-headless
MODEM=misc/modem_simulator
export ANDROID_SERIAL=emulator-$PORT

[ "$PORT" = 5554 ] && { echo "port 5554 is the user's own emulator: refused" >&2; exit 1; }

wait_gone() {
    adb emu kill >/dev/null 2>&1 || true
    for _ in $(seq 1 60); do
        adb devices | grep -q "^$ANDROID_SERIAL" || return 0
        timeout 1 tail -f /dev/null || true
    done
}

case "${1:-}" in
setup)
    mkdir -p "$SYSDIR" "$EMU/qemu/linux-x86_64"
    for f in "$IMAGE"/*; do
        [ "$(basename "$f")" = data ] || ln -sfn "$f" "$SYSDIR/$(basename "$f")"
    done
    rm -rf "$SYSDIR/data"
    cp -a "$IMAGE/data" "$SYSDIR/data"
    for f in "$SDK/emulator"/*; do
        case "$(basename "$f")" in emulator | qemu) ;; *) ln -sfn "$f" "$EMU/$(basename "$f")" ;; esac
    done
    # A copy, not a link: the launcher finds qemu next to its own path.
    cp "$SDK/emulator/emulator" "$EMU/emulator"
    for f in "$SDK/emulator/qemu/linux-x86_64"/*; do
        [ "$(basename "$f")" = qemu-system-x86_64-headless ] || ln -sfn "$f" "$EMU/qemu/linux-x86_64/$(basename "$f")"
    done
    cp "$SDK/emulator/qemu/linux-x86_64/qemu-system-x86_64-headless" "$QEMU.orig"
    echo "set up in $DIR"
    ;;
start)
    PLMN=${2:?start PLMN [SIM]}
    SIM=${3:-24201}
    [ -f "$QEMU.orig" ] || { echo "run setup first" >&2; exit 1; }
    wait_gone
    # The SIM: IMSI = SIM padded with zeros to 15 digits; EF_AD's last byte = the MNC length.
    IMSI=$(printf '%s%0*d' "$SIM" $((15 - ${#SIM})) 0)
    sed -i -E "s#<CIMI>[0-9]+</CIMI>#<CIMI>$IMSI</CIMI>#; s#(p3=\"4\" data=\"\">144,0,0000000)[23]#\1$((${#SIM} - 3))#" \
        "$SYSDIR/data/$MODEM/iccprofile_for_sim0.xml"
    # The modem registers on the SIM's own PLMN when numeric_operator.xml names it, else on its first built-in
    # operator - which the qemu copy says is PLMN.
    case "$SIM" in 24201) NAME="Telenor=TELENOR" ;; 24214) NAME="Ice=ICE" ;; *) NAME="Home=HOME" ;; esac
    if [ "$PLMN" = "$SIM" ]; then NAMED="        <item numeric=\"$SIM\">$NAME</item>"; FIRST=311740
    else NAMED=""; FIRST=$PLMN; fi
    python3 -I - "$QEMU.orig" "$QEMU" "$FIRST" <<'PY'
import sys
src, dst, plmn = sys.argv[1], sys.argv[2], sys.argv[3].encode()
assert len(plmn) in (5, 6) and plmn.isdigit(), "a 5- or 6-digit PLMN"
b = open(src, "rb").read()
# The first built-in operator is built in place as a short libc++ std::string: its size byte (6 << 1), then
# movl "3117" and movw "40" (emulator 37.2).
old = b"\xc6\x44\x24\x08\x0c" + b"\xc7\x44\x24\x09" + b"3117" + b"\x66\xc7\x44\x24\x0d" + b"40"
assert b.count(old) == 1, "this emulator build has another layout"
tail = plmn[4:] if len(plmn) == 6 else plmn[4:] + b"\x00"
new = b"\xc6\x44\x24\x08" + bytes([len(plmn) << 1]) + b"\xc7\x44\x24\x09" + plmn[:4] + b"\x66\xc7\x44\x24\x0d" + tail
open(dst, "wb").write(b.replace(old, new))
PY
    chmod +x "$QEMU"
    for f in "$SYSDIR/data/$MODEM/etc/modem_simulator/files/numeric_operator.xml" \
        "$AVD_DIR/modem_simulator/etc/modem_simulator/files/numeric_operator.xml"; do
        [ -d "$(dirname "$f")" ] || continue
        {
            echo '<?xml version="1.0" encoding="utf-8"?>'
            echo '<resources xmlns:xliff="urn:oasis:names:tc:xliff:document:1.2">'
            echo '    <add-resource name="numeric_to_operator" type="array"/>'
            echo '    <string-array name="numeric_to_operator">'
            [ -n "$NAMED" ] && echo "$NAMED"
            echo '        <item numeric="310260">T-Mobile=TMOBILE</item>'
            echo '    </string-array>'
            echo '</resources>'
        } >"$f"
    done
    # Automatic selection (Android sets it at every boot anyway).
    if [ -d "$AVD_DIR/modem_simulator" ]; then
        printf '{"instances":{"0":{"emergency_mode":false,"modem_technoloy":64,"network_selection_mode":0,"preferred_network_mode":99}}}\n' \
            >"$AVD_DIR/modem_simulator/modem_nvram.json"
    fi
    (setsid "$EMU/emulator" -avd "$AVD" -port "$PORT" -sysdir "$SYSDIR" -no-window -no-audio \
        -gpu swiftshader_indirect -no-snapshot-save -no-snapshot-load -no-boot-anim \
        >"$DIR/emulator-$PLMN.log" 2>&1 &)
    for _ in $(seq 1 150); do
        [ "$(timeout 3 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break
        timeout 2 tail -f /dev/null || true
    done
    want=$([ "$PLMN" = "$SIM" ] && echo "$SIM" || echo "$PLMN")
    for _ in $(seq 1 60); do
        [ "$(adb shell getprop gsm.operator.numeric | tr -d '\r')" = "$want" ] && break
        timeout 1 tail -f /dev/null || true
    done
    # The SIM needs an APN for mobile data (the image's list has none for these SIMs).
    if ! adb shell content query --uri content://telephony/carriers --where "numeric='$SIM'" | grep -q Row; then
        adb shell content insert --uri content://telephony/carriers --bind name:s:Test --bind numeric:s:"$SIM" \
            --bind mcc:s:"${SIM:0:3}" --bind mnc:s:"${SIM:3}" --bind apn:s:internet --bind type:s:default,supl,mms \
            --bind protocol:s:IPV4V6 --bind roaming_protocol:s:IPV4V6 --bind carrier_enabled:b:true --bind edited:i:0
    fi
    echo "SIM $(adb shell getprop gsm.sim.operator.numeric | tr -d '\r') on network" \
        "$(adb shell getprop gsm.operator.numeric | tr -d '\r') ($(adb shell getprop gsm.operator.iso-country | tr -d '\r'))," \
        "roaming=$(adb shell getprop gsm.operator.isroaming | tr -d '\r')"
    ;;
stop)
    wait_gone
    ;;
*)
    sed -n '2,20p' "$0"
    exit 1
    ;;
esac
