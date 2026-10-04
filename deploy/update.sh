#!/usr/bin/env bash
# Kid Phone Server — updater. Downloads the latest release and replaces the
# running app, without touching your .env. Migrations run automatically on
# the next startup - so before swapping anything, this copies the database
# and the old binary to /var/backups/kid-phone-server/<timestamp>/ (the last
# 3 are kept).
# An older binary refuses to start against a database that newer migrations
# have touched, so rolling back means restoring both - see DEPLOY.md.
#
# Usage (as root, e.g. via sudo):
#   curl -sSL https://raw.githubusercontent.com/palchrb/kid-phone-server/master/deploy/update.sh | sudo bash
#
# KPS_REPO=owner/repo picks a different fork (same as install.sh). The
# root-side updater passes the repo it was installed from.

set -euo pipefail

REPO="${KPS_REPO:-palchrb/kid-phone-server}"
if ! [[ "$REPO" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]]; then
    echo "KPS_REPO must look like owner/repo, got: $REPO" >&2
    exit 1
fi
INSTALL_DIR="/opt/kid-phone-server"
SERVICE_USER="kidphone"

if [ "$(id -u)" -ne 0 ]; then
    echo "Please run this as root (e.g. 'sudo bash update.sh')." >&2
    exit 1
fi

if [ ! -f "$INSTALL_DIR/.env" ]; then
    echo "$INSTALL_DIR doesn't look like an existing install (no .env found)." >&2
    echo "Run install.sh first." >&2
    exit 1
fi

case "$(uname -m)" in
    aarch64) TARGET="aarch64-unknown-linux-musl" ;;
    *)
        echo "Unsupported architecture: $(uname -m)" >&2
        exit 1
        ;;
esac

TARBALL_URL="https://github.com/$REPO/releases/latest/download/kid-phone-server-$TARGET.tar.gz"
echo "Downloading latest release from $TARBALL_URL ..."
curl -sSL "$TARBALL_URL" -o /tmp/kid-phone-server.tar.gz

echo "Stopping service..."
systemctl stop kid-phone-server

# Taken with the service stopped, so the database files are consistent. The
# fixed path matches install.sh's DATABASE_URL; .env itself is never read
# here, since the service user can write it and this runs as root. The copies
# go to a root-owned directory outside data/: the service user owns data/ and
# could otherwise swap in a symlink that the cleanup below would follow.
BACKUP_ROOT="/var/backups/kid-phone-server"
BACKUP_DIR="$BACKUP_ROOT/$(date +%Y%m%d-%H%M%S)"
echo "Backing up the database and current binary to $BACKUP_DIR ..."
mkdir -p -m 700 "$BACKUP_ROOT"
mkdir -p "$BACKUP_DIR"
for f in kidphone.db kidphone.db-wal kidphone.db-shm; do
    if [ -f "$INSTALL_DIR/data/$f" ]; then
        cp -p "$INSTALL_DIR/data/$f" "$BACKUP_DIR/$f"
    fi
done
cp -p "$INSTALL_DIR/kid_phone_server" "$BACKUP_DIR/kid_phone_server"
chmod 700 "$BACKUP_DIR"
# Keep the newest 3 (directory names sort by time).
ls -1d "$BACKUP_ROOT"/*/ 2>/dev/null | sort | head -n -3 | xargs -r rm -rf

echo "Installing update..."
TMP_EXTRACT="$(mktemp -d)"
tar -xzf /tmp/kid-phone-server.tar.gz -C "$TMP_EXTRACT"
rm /tmp/kid-phone-server.tar.gz

cp "$TMP_EXTRACT/kid_phone_server" "$INSTALL_DIR/kid_phone_server"
chmod +x "$INSTALL_DIR/kid_phone_server"

rm -rf "$INSTALL_DIR/static"
cp -r "$TMP_EXTRACT/static" "$INSTALL_DIR/static"
rm -rf "$TMP_EXTRACT"

chown -R "$SERVICE_USER:$SERVICE_USER" "$INSTALL_DIR/kid_phone_server" "$INSTALL_DIR/static"

echo "Starting service..."
systemctl start kid-phone-server

sleep 2
if systemctl is-active --quiet kid-phone-server; then
    echo ""
    echo "Update complete and running."
else
    echo ""
    echo "The service didn't start cleanly — check 'systemctl status kid-phone-server'" >&2
    echo "and 'journalctl -u kid-phone-server -n 50' for details." >&2
    exit 1
fi
