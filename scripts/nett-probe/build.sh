#!/bin/sh
# Design 127 step 0 (throwaway branch nett-probe): the Nett probe's two target apps, probe.nett.cut and
# probe.nett.free, built without Gradle. Usage: scripts/nett-probe/build.sh OUTDIR (ANDROID_HOME set).
set -eu
here=$(cd "$(dirname "$0")" && pwd)
out=$1
BT=$ANDROID_HOME/build-tools/36.0.0
AJ=$ANDROID_HOME/platforms/android-36/android.jar
rm -rf "$out" && mkdir -p "$out/classes" "$out/dex"
javac -source 17 -target 17 -cp "$AJ" -d "$out/classes" "$here/Probe.java"
"$BT/d8" --min-api 34 --lib "$AJ" --output "$out/dex" "$out"/classes/probe/nett/*.class
for pkg in probe.nett.cut probe.nett.free; do
  "$BT/aapt2" link -I "$AJ" --manifest "$here/AndroidManifest.xml" --rename-manifest-package $pkg \
    --min-sdk-version 34 --target-sdk-version 36 --version-code 1 --version-name 1 -o "$out/$pkg.tmp.apk"
  (cd "$out/dex" && zip -q "../$pkg.tmp.apk" classes.dex)
  "$BT/zipalign" -f 4 "$out/$pkg.tmp.apk" "$out/$pkg.aligned.apk"
  "$BT/apksigner" sign --ks ~/.android/debug.keystore --ks-pass pass:android --key-pass pass:android \
    --out "$out/$pkg.apk" "$out/$pkg.aligned.apk"
done
