#!/bin/bash
# Design 127 step 0: the Nett probe's matrix on one emulator. Usage: matrix.sh PORT VERSION TRANSPORT [shapes...]
# For each shape: V1 (the preference alone), V2 (the preference and the app outside the VPN), then cleared.
PORT=$1; V=$2; T=$3; shift 3
here=$(cd "$(dirname "$0")" && pwd)
A="adb -s emulator-$PORT"
for shape in "${@:-filter pass sink none}"; do
  for s in $shape; do
    echo "--- shape $s: $("$here/step.sh" $PORT shape --es shape $s)"; sleep 5
    echo "vpn: $($A shell dumpsys vpn_management | grep -oE 'sessionId=[^,]*|Uids: <[^>]*>' | tr '\n' ' ') lockdown: $($A shell dumpsys device_policy | grep -m1 mAlwaysOnVpnLockdown | tr -d ' ')"
    "$here/step.sh" $PORT vpnout --es pkgs , >/dev/null; "$here/step.sh" $PORT cut --es pkgs probe.nett.cut >/dev/null; sleep 4
    "$here/row.sh" $PORT "$V $s $T V1"
    "$here/step.sh" $PORT vpnout --es pkgs probe.nett.cut >/dev/null; sleep 4
    "$here/row.sh" $PORT "$V $s $T V2"
    "$here/step.sh" $PORT vpnout --es pkgs , >/dev/null; "$here/step.sh" $PORT clear >/dev/null; sleep 4
    NOBIND=1 NODM=1 "$here/row.sh" $PORT "$V $s $T cleared"
  done
done
echo "phone kills so far: $($A shell logcat -d -b all | grep -c 'Killing.*com.android.phone.*Too many Binders')"
