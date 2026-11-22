#!/bin/bash
# Design 127 step 0: one row of the Nett probe. Usage: row.sh PORT LABEL
# Prints the cut app's basic/bind/dm, the free app's basic and the launcher's own check.
PORT=$1; LABEL=$2
A="adb -s emulator-$PORT"
L=me.vibb.launcher.debug/com.kidslauncher.mdm.server.NettProbeReceiver
app() { $A shell am broadcast -f 32 -n $1/probe.nett.Probe --es what $2 2>/dev/null | sed -n 's/.*data="\(.*\)"/\1/p'; }
echo "=== $LABEL"
# the download job needs the app out of the never-launched standby bucket (its quota)
for p in probe.nett.cut probe.nett.free; do $A shell am set-standby-bucket $p active; done
echo "cut.basic: $(app probe.nett.cut basic)"
echo "cut.nc:    $($A shell "run-as probe.nett.cut toybox nc -w 4 1.1.1.1 443 </dev/null >/dev/null 2>&1; echo exit=\$?")"
[ "${NOBIND:-}" ] || echo "cut.bind:  $(app probe.nett.cut bind)"
[ "${NODM:-}" ] || echo "cut.dm:    $(app probe.nett.cut dm)"
echo "free.basic: $(app probe.nett.free basic)"
echo "launcher:  $($A shell am broadcast -n $L --es step self 2>/dev/null | sed -n 's/.*data="\(.*\)"/\1/p')"
