#!/bin/bash
# Design 127 step 0: one launcher probe step. Usage: step.sh PORT STEP [extra am args...]
PORT=$1; STEP=$2; shift 2
adb -s emulator-$PORT shell am broadcast -n me.vibb.launcher.debug/com.kidslauncher.mdm.server.NettProbeReceiver --es step $STEP "$@" | sed -n 's/.*data="\(.*\)"/\1/p'
