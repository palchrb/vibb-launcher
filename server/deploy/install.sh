#!/usr/bin/env bash
# Kid Phone Server — installer for a Raspberry Pi / DietPi (or any Linux/
# systemd box).
#
# Usage (as root, e.g. via sudo):
#   curl -fsSL https://raw.githubusercontent.com/palchrb/vibb-launcher/main/server/deploy/install.sh | sudo bash
#
# To install from a different fork, set KPS_REPO=owner/repo (e.g.
# `... | sudo KPS_REPO=someone/vibb-launcher bash`; the fork must keep
# this monorepo's layout and server-v* release tags). This script never
# reads the app's .env: that file is writable by the service user, and this
# runs as root.
#
# Safe to re-run: it won't overwrite an existing .env or database, it just
# re-installs the binary/service/watcher scripts (useful for re-running
# after a failure, or to pick up a newer privileged action).

set -euo pipefail

REPO="${KPS_REPO:-palchrb/vibb-launcher}"
# The pre-monorepo server repo: its installs pass it in (the root-side updater remembers the
# repo it was installed from). Its releases now live in the monorepo.
if [ "$REPO" = "palchrb/kid-phone-server" ]; then
    REPO="palchrb/vibb-launcher"
fi
# Substituted into the root-run actions.sh below, so it must be a plain
# owner/repo and nothing else.
if ! [[ "$REPO" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]]; then
    echo "KPS_REPO must look like owner/repo, got: $REPO" >&2
    exit 1
fi
INSTALL_DIR="/opt/kid-phone-server"
SERVICE_USER="kidphone"

# Bumped only when the privileged scripts below (actions.sh/watcher.sh/
# scheduler.sh/backup_sync.sh) actually gain or change a privileged action -
# independent of the app's own release version, which changes on every
# release including ones that touch nothing here. The app compares this
# against its own required minimum (security::REQUIRED_WATCHER_SCHEMA) to
# decide whether re-running this installer is actually necessary, rather
# than just checking whether the release version strings happen to match.
WATCHER_SCHEMA_VERSION="6"

if [ "$(id -u)" -ne 0 ]; then
    echo "Please run this as root (e.g. 'sudo bash install.sh')." >&2
    exit 1
fi

case "$(uname -m)" in
    aarch64) TARGET="aarch64-unknown-linux-musl" ;;
    *)
        echo "Unsupported architecture: $(uname -m)" >&2
        echo "This installer supports 64-bit (aarch64) Pi OS / DietPi only." >&2
        exit 1
        ;;
esac
echo "Detected architecture: $(uname -m) -> $TARGET"

echo "Installing prerequisites..."
apt-get update -qq
apt-get install -y -qq curl tar unzip iptables jq >/dev/null

# Cleanup for any Pi that ran an installer from before the on-device-filtering
# migration (see this repo's CLAUDE.md): this server no longer runs a live DNS
# listener at all (filtering happens entirely on-device now, via the
# launcher's own KidVpnService), so the old port-53/853 DNAT redirect rules
# and the route_localnet sysctl they depended on are both dead weight. Removal
# is best-effort/idempotent (`|| true`) since a fresh install has none of this
# to clean up in the first place.
for proto in udp tcp; do
    iptables -t nat -D PREROUTING -i tailscale0 -p "$proto" --dport 53 \
        -j DNAT --to-destination 127.0.0.1:5300 2>/dev/null || true
done
iptables -t nat -D PREROUTING -i tailscale0 -p tcp --dport 853 \
    -j DNAT --to-destination 127.0.0.1:8853 2>/dev/null || true
rm -f /etc/sysctl.d/99-kid-phone-server.conf
systemctl disable --now kid-phone-server-tls-cert.timer >/dev/null 2>&1 || true
rm -f /etc/systemd/system/kid-phone-server-tls-cert.timer /etc/systemd/system/kid-phone-server-tls-cert.service
rm -f /opt/kid-phone-server-updater/tls_cert_renew.sh

if ! id "$SERVICE_USER" >/dev/null 2>&1; then
    echo "Creating service user '$SERVICE_USER'..."
    useradd --system --no-create-home --shell /usr/sbin/nologin "$SERVICE_USER"
fi

mkdir -p "$INSTALL_DIR"
# The repo is a monorepo that also publishes the launcher, whose stable releases are GitHub's
# "latest" - so the server's release is looked up in the releases list (newest first): the first
# server-vX.Y.Z that is neither a draft nor a prerelease. Never releases/latest. Plain grep/awk on
# the API's JSON (each release lists tag_name, then draft, then prerelease; asset objects have
# none of these keys). Same text in install.sh, update.sh and install.sh's actions.sh.
resolve_server_tag() {
    curl -fsSL -H "Accept: application/vnd.github+json" \
        "https://api.github.com/repos/$REPO/releases?per_page=100" \
        | grep -oE '"(tag_name|draft|prerelease)": *("[^"]*"|true|false)' \
        | awk -F': *' '
            /"tag_name"/ { tag = $2; gsub(/"/, "", tag); draft = ""; next }
            /"draft"/ { draft = $2; next }
            /"prerelease"/ {
                if (draft == "false" && $2 == "false" && tag ~ /^server-v[0-9]+\.[0-9]+\.[0-9]+$/) { print tag; exit }
            }' || true
}

# vX.Y.Z > vX.Y.Z. Both are checked against the pattern first: bash arithmetic on an unchecked
# string (one comes from a file) would evaluate it.
version_gt() {
    local re='^v([0-9]+)\.([0-9]+)\.([0-9]+)$' a b i
    [[ "$1" =~ $re ]] || return 1
    a=("${BASH_REMATCH[@]:1}")
    [[ "$2" =~ $re ]] || return 1
    b=("${BASH_REMATCH[@]:1}")
    for i in 0 1 2; do
        if ((10#${a[i]} > 10#${b[i]})); then return 0; fi
        if ((10#${a[i]} < 10#${b[i]})); then return 1; fi
    done
    return 1
}

# The installed server version (vX.Y.Z), or nothing if unknown: install.sh/update.sh record it in
# the root-owned updater directory; installs from before that only have data/watcher_version
# (written by install.sh, at install time).
installed_version() {
    local f v
    for f in /opt/kid-phone-server-updater/installed_version "$INSTALL_DIR/data/watcher_version"; do
        [ -f "$f" ] || continue
        v="$(head -c 32 "$f" | tr -d '\n')"
        if [[ "$v" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
            echo "$v"
            return
        fi
    done
}

# Refuses to install an older release than the installed one (it would refuse to start on a
# database the newer version migrated) unless KPS_ALLOW_DOWNGRADE=1.
refuse_downgrade() {
    local new="$1" current
    current="$(installed_version)"
    if [ -n "$current" ] && version_gt "$current" "$new"; then
        if [ "${KPS_ALLOW_DOWNGRADE:-}" = "1" ]; then
            echo "Installing $new over the newer $current (KPS_ALLOW_DOWNGRADE=1)."
        else
            echo "The newest release ($new) is older than the installed $current - not installing." >&2
            echo "To go back on purpose, see DEPLOY.md (Rolling back) and set KPS_ALLOW_DOWNGRADE=1." >&2
            exit 1
        fi
    fi
}

# KPS_TAG (set by the root-side updater, which fetched this script at that tag) or the newest
# stable server release.
pick_tag() {
    if [ -n "${KPS_TAG:-}" ]; then
        if ! [[ "$KPS_TAG" =~ ^server-v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
            echo "KPS_TAG must look like server-vX.Y.Z, got: $KPS_TAG" >&2
            exit 1
        fi
        echo "$KPS_TAG"
    else
        resolve_server_tag
    fi
}

# Downloads the release tarball and its .sha256 into $1 and checks the hash; exits on any failure.
download_release() {
    local dir="$1" asset="kid-phone-server-$TARGET.tar.gz" expected actual
    local url="https://github.com/$REPO/releases/download/$TAG/$asset"
    echo "Downloading $TAG from $url ..."
    curl -fsSL "$url" -o "$dir/$asset"
    curl -fsSL "$url.sha256" -o "$dir/$asset.sha256"
    expected="$(head -c 64 "$dir/$asset.sha256")"
    actual="$(sha256sum "$dir/$asset" | cut -c1-64)"
    if ! [[ "$expected" =~ ^[0-9a-f]{64}$ ]] || [ "$expected" != "$actual" ]; then
        echo "SHA-256 of $asset doesn't match $asset.sha256 - not installing." >&2
        exit 1
    fi
    tar -xzf "$dir/$asset" -C "$dir"
    rm -f "$dir/$asset" "$dir/$asset.sha256"
    if [ ! -s "$dir/kid_phone_server" ] || [ ! -d "$dir/static" ]; then
        echo "The download doesn't contain kid_phone_server and static/ - not installing." >&2
        exit 1
    fi
}

TAG="$(pick_tag)"
if [ -z "$TAG" ]; then
    echo "No server-vX.Y.Z release found in $REPO." >&2
    exit 1
fi
refuse_downgrade "${TAG#server-}"
TMP_EXTRACT="$(mktemp -d)"
trap 'rm -rf "$TMP_EXTRACT"' EXIT
download_release "$TMP_EXTRACT"
# install(1) replaces the file instead of writing into it, so this works while a re-run's
# service is still running the old binary (restarted below).
install -m 755 "$TMP_EXTRACT/kid_phone_server" "$INSTALL_DIR/kid_phone_server"
rm -rf "$INSTALL_DIR/static"
cp -r "$TMP_EXTRACT/static" "$INSTALL_DIR/static"

# The version just installed (vX.Y.Z, the form of the app's own version). Recorded
# so the app can tell you when the root-side watcher/scheduler scripts (only
# ever refreshed by re-running this installer, never by the in-app update
# button) have fallen behind the app version, instead of silently no-op'ing
# on features the installed watcher doesn't know about yet.
INSTALLED_VERSION="${TAG#server-}"

mkdir -p "$INSTALL_DIR/data"

if [ ! -f "$INSTALL_DIR/.env" ]; then
    echo "No existing .env found — generating one with a fresh admin password."
    # `head -c 24` exiting early sends tr a SIGPIPE, which pipefail treats as
    # a pipeline failure and would abort the whole script under set -e — the
    # password itself is still captured correctly, so just swallow that.
    ADMIN_PASSWORD="$(tr -dc 'A-Za-z0-9' </dev/urandom | head -c 24 || true)"
    cat >"$INSTALL_DIR/.env" <<EOF
DATABASE_URL=sqlite://data/kidphone.db
BIND_ADDR=127.0.0.1:3100
ADMIN_USERNAME=admin
ADMIN_PASSWORD=$ADMIN_PASSWORD

# Which GitHub repo the in-app update check looks at (owner/repo).
SERVER_RELEASE_REPO=$REPO
# Provisioning QR (Devices > Provision). The defaults point at the launcher in
# the palchrb/vibb-launcher monorepo; only change them if you build your own.
LAUNCHER_ADMIN_COMPONENT=me.vibb.launcher/com.kidslauncher.mdm.server.MdmDeviceAdminReceiver
LAUNCHER_APK_URL=https://github.com/palchrb/vibb-launcher/releases/latest/download/kids-launcher-mdm.apk
# SHA-256 of the launcher's signing certificate, base64url without padding
# (43 characters). No QR code is shown until this is set. Compute it from a
# release APK with:
#   apksigner verify --print-certs kids-launcher-mdm.apk | sed -n 's/.*SHA-256 digest: //p' | xxd -r -p | openssl base64 -A | tr '+/' '-_' | tr -d '='
# (DEPLOY.md has the same command, plus one that reads the keystore.)
#LAUNCHER_SIGNATURE_CHECKSUM=
EOF
    PRINT_CREDENTIALS=1
else
    echo "Existing .env found — leaving it untouched."
    PRINT_CREDENTIALS=0
fi

# Writes $2 into data/$1 by rename: data/ belongs to the service user, so a symlink there is
# replaced, never written through (security review M4).
write_data_file() {
    local tmp
    tmp="$(mktemp "$INSTALL_DIR/data/.install.XXXXXX")"
    printf '%s' "$2" >"$tmp"
    chmod 644 "$tmp"
    mv -fT "$tmp" "$INSTALL_DIR/data/$1"
}
if [ -L "$INSTALL_DIR/data" ]; then
    echo "$INSTALL_DIR/data is a symlink - refusing to continue." >&2
    exit 1
fi
if [ -n "$INSTALLED_VERSION" ]; then
    write_data_file watcher_version "$INSTALLED_VERSION"
fi
write_data_file watcher_schema_version "$WATCHER_SCHEMA_VERSION"

chown -R "$SERVICE_USER:$SERVICE_USER" "$INSTALL_DIR"
chmod 600 "$INSTALL_DIR/.env"
# data/ (the database, backups, the live mirror, keys) is the service user's alone (security
# review M2); an existing install's files are tightened too. Run as that user, so a symlink in
# data/ can't make chmod touch anything else. The same in update.sh.
chmod 700 "$INSTALL_DIR/data"
runuser -u "$SERVICE_USER" -- find "$INSTALL_DIR/data" -xdev \
    \( -type d -exec chmod 700 {} + \) -o \( -type f -exec chmod 600 {} + \) 2>/dev/null || true

echo "Installing systemd service..."
cat >/etc/systemd/system/kid-phone-server.service <<EOF
[Unit]
Description=Kid Phone Server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=$SERVICE_USER
Group=$SERVICE_USER
WorkingDirectory=$INSTALL_DIR
EnvironmentFile=$INSTALL_DIR/.env
ExecStart=$INSTALL_DIR/kid_phone_server
Restart=on-failure
RestartSec=5
# Everything the server writes is for the service user alone (security review M2).
UMask=0077

NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=$INSTALL_DIR/data

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable kid-phone-server
# `enable --now` only *starts* the unit, which is a no-op if it's already
# running - meaning re-running this installer to pick up a new release
# would silently keep the old binary running forever. Restart explicitly so
# this works the same whether this is a fresh install or a re-run.
systemctl restart kid-phone-server

echo "Installing the update watcher and scheduler..."
# A separate, root-owned component that does the actual privileged work -
# updating the app itself, apt packages, Tailscale, and rebooting.
# kid_phone_server itself runs unprivileged and can only ever drop a flag
# file in its own data/ folder asking for one of a small fixed set of
# actions - it can never reach or modify anything in this directory, even if
# fully compromised, since it lives outside $INSTALL_DIR entirely and
# nothing here is kidphone-writable.
UPDATER_DIR="/opt/kid-phone-server-updater"
mkdir -p "$UPDATER_DIR"
# The server version just installed, for update.sh's downgrade check (root-owned, unlike data/).
echo -n "$INSTALLED_VERSION" >"$UPDATER_DIR/installed_version"

# Placeholder mount point for an optional external backup drive. Doesn't do
# anything by itself - mount a drive here (e.g. via /etc/fstab) and enable
# "copy backups to external drive" on the Backups page to use it.
mkdir -p /mnt/kid-phone-server-backup

# Shared privileged actions, used both by the manual (flag-file-triggered)
# watcher and the automatic (time-triggered) scheduler, so the actual
# commands only need to be reviewed/maintained in one place.
cat >"$UPDATER_DIR/actions.sh" <<'ACTIONS_EOF'
#!/usr/bin/env bash
set -euo pipefail

# Filled in by install.sh (sed below) with the repo it was installed from.
REPO="@REPO@"
DATA_DIR="/opt/kid-phone-server/data"
BACKUP_DIR="$DATA_DIR/backups"

# The repo is a monorepo that also publishes the launcher, whose stable releases are GitHub's
# "latest" - so the server's release is looked up in the releases list (newest first): the first
# server-vX.Y.Z that is neither a draft nor a prerelease. Never releases/latest. Plain grep/awk on
# the API's JSON (each release lists tag_name, then draft, then prerelease; asset objects have
# none of these keys). Same text in install.sh and update.sh.
resolve_server_tag() {
    curl -fsSL -H "Accept: application/vnd.github+json" \
        "https://api.github.com/repos/$REPO/releases?per_page=100" \
        | grep -oE '"(tag_name|draft|prerelease)": *("[^"]*"|true|false)' \
        | awk -F': *' '
            /"tag_name"/ { tag = $2; gsub(/"/, "", tag); draft = ""; next }
            /"draft"/ { draft = $2; next }
            /"prerelease"/ {
                if (draft == "false" && $2 == "false" && tag ~ /^server-v[0-9]+\.[0-9]+\.[0-9]+$/) { print tag; exit }
            }' || true
}

# Runs update.sh as it is in the release it installs (not master HEAD), from a file: a failed or
# truncated download is never executed.
action_app_update() {
    local tag script rc=0
    tag="$(resolve_server_tag)"
    if [ -z "$tag" ]; then
        echo "app_update: no server-vX.Y.Z release found in $REPO" >&2
        return 1
    fi
    script="$(mktemp)"
    if ! curl -fsSL "https://raw.githubusercontent.com/$REPO/$tag/server/deploy/update.sh" -o "$script"; then
        rm -f "$script"
        echo "app_update: couldn't download update.sh for $tag" >&2
        return 1
    fi
    KPS_REPO="$REPO" KPS_TAG="$tag" bash "$script" || rc=$?
    rm -f "$script"
    return "$rc"
}

action_app_restart() {
    systemctl restart kid-phone-server
}

action_os_check() {
    apt-get update -qq
}

action_os_upgrade() {
    apt-get update -qq
    DEBIAN_FRONTEND=noninteractive apt-get upgrade -y -qq
}

action_tailscale_update() {
    tailscale update --yes
}

action_reboot() {
    systemctl reboot
}

# Formats a removable drive as ext4 and mounts it at the external backup
# path. Defense in depth: independently re-validates the device from
# scratch - matches the expected /dev/sd[a-z] pattern, is actually marked
# removable by the kernel, and is definitely not whatever backs / or /boot
# - regardless of what the (unprivileged, internet-facing) app already
# checked before requesting this, since that process is exactly the one
# component that must never be trusted to have gotten this right on its own.
action_format_drive() {
    local device="$1"

    if [ -z "$device" ]; then
        echo "format_drive: no device specified" >&2
        return 1
    fi
    if ! echo "$device" | grep -qE '^/dev/sd[a-z]$'; then
        echo "format_drive: refusing to format '$device' — doesn't match the expected /dev/sd[a-z] pattern" >&2
        return 1
    fi

    local devname
    devname="$(basename "$device")"
    if [ ! -f "/sys/block/$devname/removable" ] || [ "$(cat "/sys/block/$devname/removable")" != "1" ]; then
        echo "format_drive: refusing to format '$device' — not marked removable by the kernel" >&2
        return 1
    fi

    local root_device boot_device
    root_device="$(findmnt -no SOURCE / | sed -E 's/p?[0-9]+$//')"
    if [ "$device" = "$root_device" ]; then
        echo "format_drive: refusing to format '$device' — this is the root filesystem's device" >&2
        return 1
    fi
    boot_device="$(findmnt -no SOURCE /boot 2>/dev/null | sed -E 's/p?[0-9]+$//' || true)"
    if [ -n "$boot_device" ] && [ "$device" = "$boot_device" ]; then
        echo "format_drive: refusing to format '$device' — this is the boot device" >&2
        return 1
    fi

    echo "Formatting $device as ext4..."
    umount "${device}"* 2>/dev/null || true
    mkfs.ext4 -F -q "$device"

    mkdir -p /mnt/kid-phone-server-backup
    local uuid
    uuid="$(blkid -s UUID -o value "$device")"

    # Replace any existing fstab entry for this mount point first, so
    # re-formatting a previously-configured drive doesn't leave stale
    # duplicate entries behind.
    sed -i '\#/mnt/kid-phone-server-backup#d' /etc/fstab
    echo "UUID=$uuid /mnt/kid-phone-server-backup ext4 defaults,nofail 0 2" >>/etc/fstab

    mount /mnt/kid-phone-server-backup
    echo "Drive formatted and mounted at /mnt/kid-phone-server-backup."
}

# Restores a named backup zip: stops the app, swaps in the backup's database, and restarts.
# data/ belongs to the unprivileged service user, so this root code never follows or writes
# through anything it finds there (security review M4): only the zip's kidphone.db is read, as a
# stream into a root-only temp directory, at most RESTORE_MAX_BYTES and only if it is an SQLite
# file; the live database is copied aside into a root-owned directory; and the restored file goes
# into place by rename, which replaces a symlink at the target instead of writing through it.
# Everything that can be checked happens *before* the app is stopped, so a bad request never takes
# the app down for nothing. Once stopped, an EXIT trap restarts it however this ends.
RESTORE_MAX_BYTES=$((1024 * 1024 * 1024))
PRERESTORE_ROOT="/var/backups/kid-phone-server-prerestore"

action_restore_backup() {
    local filename="$1"

    if [ -z "$filename" ]; then
        echo "restore_backup: no filename specified" >&2
        return 1
    fi
    case "$filename" in
        backup-*.zip) ;;
        *)
            echo "restore_backup: refusing to restore '$filename' — doesn't match the expected backup-*.zip pattern" >&2
            return 1
            ;;
    esac
    if echo "$filename" | grep -qE '/|\.\.'; then
        echo "restore_backup: refusing to restore '$filename' — invalid characters" >&2
        return 1
    fi
    if [ -L "$DATA_DIR" ] || [ -L "$BACKUP_DIR" ]; then
        echo "restore_backup: $DATA_DIR or $BACKUP_DIR is a symlink - refusing" >&2
        return 1
    fi

    local backup_path="$BACKUP_DIR/$filename"
    if [ -L "$backup_path" ] || [ ! -f "$backup_path" ]; then
        echo "restore_backup: '$backup_path' not found (or not a plain file)" >&2
        return 1
    fi

    local restore_tmp staged size magic
    restore_tmp="$(mktemp -d)"
    staged="$restore_tmp/kidphone.db"
    # Only kidphone.db, streamed: no other entry (a symlink, a path) is ever written anywhere, and
    # head stops a zip bomb at the limit (unzip then fails on the closed pipe).
    if ! unzip -p "$backup_path" kidphone.db 2>/dev/null | head -c "$((RESTORE_MAX_BYTES + 1))" >"$staged"; then
        echo "restore_backup: couldn't read kidphone.db from '$backup_path' (missing, damaged or larger than $RESTORE_MAX_BYTES bytes)" >&2
        rm -rf "$restore_tmp"
        return 1
    fi
    size="$(stat -c %s "$staged")"
    magic="$(head -c 16 "$staged" | od -An -tx1 | tr -d ' \n')"
    if [ "$size" -eq 0 ] || [ "$size" -gt "$RESTORE_MAX_BYTES" ] || [ "$magic" != "53514c69746520666f726d6174203300" ]; then
        echo "restore_backup: the kidphone.db in '$backup_path' is not an SQLite database (or too large) - aborting" >&2
        rm -rf "$restore_tmp"
        return 1
    fi

    echo "Stopping the app for restore..."
    systemctl stop kid-phone-server
    trap 'systemctl start kid-phone-server' EXIT

    # Safety copy of whatever's live right now, in a root-owned directory (never inside data/): the
    # newest 3 are kept. Only plain files are copied; a symlink in data/ is never followed.
    local prerestore_dir f incoming
    install -d -m 700 "$PRERESTORE_ROOT"
    prerestore_dir="$PRERESTORE_ROOT/$(date +%Y%m%d-%H%M%S)"
    mkdir -m 700 "$prerestore_dir"
    for f in kidphone.db kidphone.db-wal kidphone.db-shm; do
        if [ -f "$DATA_DIR/$f" ] && [ ! -L "$DATA_DIR/$f" ]; then
            cp -p "$DATA_DIR/$f" "$prerestore_dir/$f"
        fi
    done
    find "$PRERESTORE_ROOT" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort | head -n -3 \
        | while read -r old; do rm -rf "${PRERESTORE_ROOT:?}/$old"; done

    # Into place by rename: mktemp creates a new file (never an existing one), and mv -T replaces
    # whatever sits at the target - a symlink included - instead of writing through it.
    incoming="$(mktemp "$DATA_DIR/.restore.XXXXXX")"
    cat "$staged" >"$incoming"
    chown kidphone:kidphone "$incoming"
    chmod 600 "$incoming"
    rm -f "$DATA_DIR/kidphone.db-wal" "$DATA_DIR/kidphone.db-shm"
    mv -fT "$incoming" "$DATA_DIR/kidphone.db"
    rm -rf "$restore_tmp"
    # The next start forgets every admin session the backup holds (security review M2), and logs
    # the restore. The same new-file-and-rename as above.
    incoming="$(mktemp "$DATA_DIR/.restore.XXXXXX")"
    printf '%s' "$filename" >"$incoming"
    chown kidphone:kidphone "$incoming"
    mv -fT "$incoming" "$DATA_DIR/restore_applied"

    echo "Restore complete (the previous database is in $prerestore_dir)."
}
ACTIONS_EOF
sed -i "s|@REPO@|$REPO|" "$UPDATER_DIR/actions.sh"

cat >"$UPDATER_DIR/watcher.sh" <<'WATCHER_EOF'
#!/usr/bin/env bash
# Runs as root, triggered only when kid-phone-server (unprivileged) drops
# a flag file asking for one of a fixed set of actions.
set -euo pipefail
source /opt/kid-phone-server-updater/actions.sh

FLAG_FILE="/opt/kid-phone-server/data/update_requested"
# data/ belongs to the service user: a symlinked flag is never read (security review M4), and at
# most one short line is.
if [ -L "$FLAG_FILE" ]; then
    rm -f "$FLAG_FILE"
    echo "The update request is a symlink - ignored" >&2
    exit 1
fi
FLAG_CONTENT="$(head -c 256 "$FLAG_FILE" 2>/dev/null | head -n 1 || true)"
rm -f "$FLAG_FILE"

# Most actions are a single word; format_drive/restore_backup additionally
# carry a second, space-separated argument.
ACTION="${FLAG_CONTENT%% *}"
if [ "$ACTION" != "$FLAG_CONTENT" ]; then
    ARG="${FLAG_CONTENT#* }"
else
    ARG=""
fi

case "$ACTION" in
    update) action_app_update ;;
    restart) action_app_restart ;;
    os_check) action_os_check ;;
    os_upgrade) action_os_upgrade ;;
    tailscale_update) action_tailscale_update ;;
    reboot) action_reboot ;;
    format_drive) action_format_drive "$ARG" ;;
    restore_backup) action_restore_backup "$ARG" ;;
    *)
        echo "Unknown or empty update-request action: '$ACTION'" >&2
        exit 1
        ;;
esac
WATCHER_EOF

# Runs on a timer (not flag-triggered) to apply the admin-configured
# schedule from the Software updates page. This script is already root, so
# unlike the manual path above it just acts directly rather than going
# through the flag-file indirection - that indirection exists only to let
# the *unprivileged, internet-facing* app request privileged actions, which
# doesn't apply to this trusted, non-network-facing component.
cat >"$UPDATER_DIR/scheduler.sh" <<'SCHEDULER_EOF'
#!/usr/bin/env bash
# Root-run, every 10 minutes: the OS/Tailscale schedule from the Software updates page.
# schedule.conf lives in data/, which the unprivileged service writes, so it is never sourced or
# executed (security review H1): each key is read with grep and must match its pattern, else its
# default is used and the problem logged. A symlinked file is ignored. update.sh rewrites this
# script on every update (the same text is in install.sh and update.sh; a server test keeps them
# equal).
set -euo pipefail
# shellcheck source=/dev/null
source /opt/kid-phone-server-updater/actions.sh

CONFIG_FILE="/opt/kid-phone-server/data/schedule.conf"
LAST_RUN_FILE="/opt/kid-phone-server-updater/last_run_date"

[ -f "$CONFIG_FILE" ] || exit 0
if [ -L "$CONFIG_FILE" ]; then
    echo "scheduler: $CONFIG_FILE is a symlink - ignored" >&2
    exit 0
fi

# setting KEY DEFAULT PATTERN: the key's value if it matches PATTERN (an anchored regex), else
# DEFAULT.
setting() {
    local value
    value="$(grep -m1 "^$1=" "$CONFIG_FILE" | cut -d= -f2- || true)"
    if [[ "$value" =~ $3 ]]; then
        printf '%s\n' "$value"
    else
        if [ -n "$value" ]; then
            echo "scheduler: ignoring an invalid $1 in $CONFIG_FILE" >&2
        fi
        printf '%s\n' "$2"
    fi
}

FREQUENCY="$(setting FREQUENCY daily '^(daily|weekly|monthly)$')"
DAY_OF_WEEK="$(setting DAY_OF_WEEK 0 '^[0-6]$')"
DAY_OF_MONTH="$(setting DAY_OF_MONTH 1 '^([1-9]|1[0-9]|2[0-8])$')"
CHECK_TIME="$(setting CHECK_TIME 03:00 '^([01][0-9]|2[0-3]):[0-5][0-9]$')"
AUTO_APPLY_OS="$(setting AUTO_APPLY_OS false '^(true|false)$')"
AUTO_APPLY_TAILSCALE="$(setting AUTO_APPLY_TAILSCALE false '^(true|false)$')"
AUTO_REBOOT="$(setting AUTO_REBOOT false '^(true|false)$')"

TODAY="$(date +%F)"
LAST_RUN="$(cat "$LAST_RUN_FILE" 2>/dev/null || true)"
[ "$LAST_RUN" != "$TODAY" ] || exit 0

case "$FREQUENCY" in
    weekly)
        [ "$(date +%w)" = "$DAY_OF_WEEK" ] || exit 0
        ;;
    monthly)
        [ "$(date +%-d)" = "$DAY_OF_MONTH" ] || exit 0
        ;;
esac

NOW_MINUTES=$(( 10#$(date +%H) * 60 + 10#$(date +%M) ))
TARGET_MINUTES=$(( 10#${CHECK_TIME%%:*} * 60 + 10#${CHECK_TIME##*:} ))
DIFF=$(( NOW_MINUTES - TARGET_MINUTES ))
# Only fire in the 10-minute window right after the scheduled time (this
# runs on a 10-minute timer, so this is the precision that's actually
# achievable - good enough for background maintenance).
[ "$DIFF" -ge 0 ] && [ "$DIFF" -lt 10 ] || exit 0

echo "$TODAY" >"$LAST_RUN_FILE"

action_os_check
if [ "$AUTO_APPLY_OS" = "true" ]; then
    action_os_upgrade
fi
if [ "$AUTO_APPLY_TAILSCALE" = "true" ]; then
    action_tailscale_update
fi
if [ "$AUTO_REBOOT" = "true" ] && [ -f /var/run/reboot-required ]; then
    action_reboot
fi
SCHEDULER_EOF

# Mirrors backups (both the named point-in-time snapshots and the
# continuously-refreshed live-mirror file) to the external drive, if
# enabled and the drive is currently mounted. Runs on its own much faster
# timer than the OS-update scheduler above, since a stale offsite copy
# defeats the point of having one. Reads just the settings it needs via
# grep rather than sourcing backup_schedule.conf, since that file and
# schedule.conf (above) share variable names (FREQUENCY, DAY_OF_WEEK, ...)
# and sourcing both in the same script would let one silently clobber the
# other.
cat >"$UPDATER_DIR/backup_sync.sh" <<'BACKUP_SYNC_EOF'
#!/usr/bin/env bash
set -euo pipefail

BACKUP_CONFIG_FILE="/opt/kid-phone-server/data/backup_schedule.conf"
EXTERNAL_MOUNT="/mnt/kid-phone-server-backup"
DATA_DIR="/opt/kid-phone-server/data"

[ -f "$BACKUP_CONFIG_FILE" ] || exit 0
mountpoint -q "$EXTERNAL_MOUNT" || exit 0
# data/ belongs to the service user: never follow a symlink it put there (security review M4).
if [ -L "$DATA_DIR" ] || [ -L "$DATA_DIR/backups" ] || [ -L "$BACKUP_CONFIG_FILE" ]; then
    echo "backup_sync: a symlink in $DATA_DIR - not copying" >&2
    exit 0
fi

EXTERNAL_COPY_ENABLED="$(grep -m1 '^EXTERNAL_COPY_ENABLED=' "$BACKUP_CONFIG_FILE" | cut -d= -f2 || true)"
CONTINUOUS_MIRROR_ENABLED="$(grep -m1 '^CONTINUOUS_MIRROR_ENABLED=' "$BACKUP_CONFIG_FILE" | cut -d= -f2 || true)"

# The copies hold the whole database: root's alone on the Pi (security review M2).
chmod 700 "$EXTERNAL_MOUNT"

if [ "$EXTERNAL_COPY_ENABLED" = "true" ] && [ -d "$DATA_DIR/backups" ]; then
    install -d -m 700 "$EXTERNAL_MOUNT/snapshots"
    cp -au "$DATA_DIR/backups/." "$EXTERNAL_MOUNT/snapshots/" 2>/dev/null || true
fi

if [ "$CONTINUOUS_MIRROR_ENABLED" = "true" ] && [ -f "$DATA_DIR/live_mirror.db" ]; then
    install -d -m 700 "$EXTERNAL_MOUNT/live"
    cp -au "$DATA_DIR/live_mirror.db" "$EXTERNAL_MOUNT/live/kidphone.db" 2>/dev/null || true
fi
BACKUP_SYNC_EOF

chown -R root:root "$UPDATER_DIR"
chmod 700 "$UPDATER_DIR" "$UPDATER_DIR/actions.sh" "$UPDATER_DIR/watcher.sh" "$UPDATER_DIR/scheduler.sh" "$UPDATER_DIR/backup_sync.sh"

cat >/etc/systemd/system/kid-phone-server-updater.path <<'PATH_EOF'
[Unit]
Description=Watch for Kid Phone Server update/restart requests

[Path]
PathExists=/opt/kid-phone-server/data/update_requested

[Install]
WantedBy=multi-user.target
PATH_EOF

cat >/etc/systemd/system/kid-phone-server-updater.service <<'SERVICE_EOF'
[Unit]
Description=Handle a pending Kid Phone Server update/restart request

[Service]
Type=oneshot
ExecStart=/opt/kid-phone-server-updater/watcher.sh
SERVICE_EOF

cat >/etc/systemd/system/kid-phone-server-scheduler.timer <<'TIMER_EOF'
[Unit]
Description=Check the Kid Phone Server update/reboot schedule every 10 minutes

[Timer]
OnBootSec=2min
OnUnitActiveSec=10min

[Install]
WantedBy=timers.target
TIMER_EOF

cat >/etc/systemd/system/kid-phone-server-scheduler.service <<'SCHED_SERVICE_EOF'
[Unit]
Description=Apply the Kid Phone Server scheduled update/reboot, if due

[Service]
Type=oneshot
ExecStart=/opt/kid-phone-server-updater/scheduler.sh
SCHED_SERVICE_EOF

cat >/etc/systemd/system/kid-phone-server-backup-sync.timer <<'BACKUP_TIMER_EOF'
[Unit]
Description=Sync Kid Phone Server backups to the external drive every 90 seconds

[Timer]
OnBootSec=1min
OnUnitActiveSec=90sec

[Install]
WantedBy=timers.target
BACKUP_TIMER_EOF

cat >/etc/systemd/system/kid-phone-server-backup-sync.service <<'BACKUP_SERVICE_EOF'
[Unit]
Description=Mirror Kid Phone Server backups to the external drive, if enabled

[Service]
Type=oneshot
ExecStart=/opt/kid-phone-server-updater/backup_sync.sh
BACKUP_SERVICE_EOF

systemctl daemon-reload
systemctl enable --now kid-phone-server-updater.path
systemctl enable --now kid-phone-server-scheduler.timer
systemctl enable --now kid-phone-server-backup-sync.timer

echo ""
echo "=========================================="
echo " Kid Phone Server is installed and running."
echo "=========================================="
echo ""
echo "Locally on the Pi: http://127.0.0.1:3100"
echo ""
if [ "$PRINT_CREDENTIALS" -eq 1 ]; then
    echo "First-time admin login:"
    echo "  Username: admin"
    echo "  Password: $ADMIN_PASSWORD"
    echo ""
    echo "Save this password now — it won't be shown again. You'll be forced"
    echo "to change it and set up two-factor login the first time you sign in."
    echo ""
fi
echo "This only listens on the Pi itself (127.0.0.1) for security. To reach it"
echo "from your phone or other devices, see \"Choose how it's reachable\" in"
echo "DEPLOY.md (tailscale serve, or your own Caddy)."
echo ""
echo "Future updates, backups, and restarts can all be done from the app's"
echo "Backups / Software updates pages once logged in - you shouldn't need to"
echo "SSH back in for routine maintenance after this."
echo ""
echo "Check status any time with: systemctl status kid-phone-server"
