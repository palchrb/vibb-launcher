#!/bin/bash
# Design 127 step 0: a wiped emulator to the probe's start. Usage: provision.sh PORT APKDIR
# The debug launcher as Device Owner (not enrolled: no server, no policy), the two target apps, the filter shape.
PORT=$1; O=$2
A="adb -s emulator-$PORT"; P=me.vibb.launcher.debug
$A wait-for-device
for i in $(seq 1 100); do [ "$($A shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break; sleep 3; done
echo "sdk $($A shell getprop ro.build.version.sdk) $($A shell getprop ro.build.fingerprint)"
$A install -r "$(dirname "$0")/../../launcher/app/build/outputs/apk/debug/app-debug.apk" | tail -1
# GMS's account check may refuse for a minute after boot (emulator.md §6v)
for i in $(seq 1 12); do $A shell dpm set-device-owner $P/com.kidslauncher.mdm.server.MdmDeviceAdminReceiver 2>&1 | grep -q "^Success" && break; sleep 10; done
$A shell dpm list-owners | tail -1
$A install "$O/probe.nett.cut.apk" | tail -1; $A install "$O/probe.nett.free.apk" | tail -1
$A shell am start -a android.intent.action.MAIN -c android.intent.category.HOME -p $P >/dev/null; sleep 8
"$(dirname "$0")/step.sh" $PORT shape --es shape filter
