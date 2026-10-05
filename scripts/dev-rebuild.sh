#!/usr/bin/env bash
# Emulator test loop on the dev VM: pull all three repos, build the debug launcher,
# free the build daemons' memory, install over the running app (keeps device owner and
# data), then start the server in the foreground (Ctrl+C to stop).
# Usage: ./scripts/dev-rebuild.sh            (pull + build + install + run server)
#        ./scripts/dev-rebuild.sh --no-pull
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"

if [ "${1:-}" != "--no-pull" ]; then
    for repo in "$root" "$root/kid-phone-server" "$root/kids-launcher-mdm"; do
        git -C "$repo" pull --ff-only
    done
fi

cd "$root/kids-launcher-mdm"
./gradlew assembleDebug
./gradlew --stop >/dev/null || true
pkill -f KotlinCompileDaemon || true
adb install -r app/build/outputs/apk/debug/app-debug.apk

cd "$root/kid-phone-server"
cargo build
exec cargo run
