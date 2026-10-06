#!/usr/bin/env bash
# Emulator test loop on the dev VM: pull, build the debug launcher, free the build daemons'
# memory, install over the running app (keeps device owner and data), then start the server in
# the foreground (Ctrl+C to stop).
# Usage: ./scripts/dev-rebuild.sh            (pull + build + install + run server)
#        ./scripts/dev-rebuild.sh --no-pull
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"

if [ "${1:-}" != "--no-pull" ]; then
    git -C "$root" pull --ff-only
fi

cd "$root/launcher"
./gradlew assembleDebug
./gradlew --stop >/dev/null || true
pkill -f KotlinCompileDaemon || true
if adb get-state >/dev/null 2>&1; then
    adb install -r app/build/outputs/apk/debug/app-debug.apk
else
    echo "WARNING: no emulator/device connected - skipped install. Start the emulator and run:" >&2
    echo "  adb install -r $root/launcher/app/build/outputs/apk/debug/app-debug.apk" >&2
fi

cd "$root/server"
cargo build
exec cargo run
