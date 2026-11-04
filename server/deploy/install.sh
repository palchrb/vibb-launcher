#!/usr/bin/env bash
# Kid Phone Server — installer for a Raspberry Pi / DietPi (or any Linux/
# systemd box).
#
# Usage (as root, e.g. via sudo) - downloaded to a file and read first, never piped into a root
# shell (security review L10):
#   curl -fsSLo install.sh https://raw.githubusercontent.com/palchrb/vibb-launcher/main/server/deploy/install.sh
#   sudo bash install.sh
#
# To install from a different fork, set KPS_REPO=owner/repo (e.g.
# `sudo KPS_REPO=someone/vibb-launcher bash install.sh`, the fork's own copy; the fork must keep
# this monorepo's layout and server-v* release tags). This script never
# reads the app's .env: that file is writable by the service user, and this
# runs as root.
#
# Safe to re-run: it won't overwrite an existing .env or database, it just
# re-installs the binary and the service (useful for re-running after a failure).
#
# The server never makes this machine do anything (the user, 2026-11-04): it has no root helper,
# no update button and no schedule for the system. You update it with update.sh (DEPLOY.md,
# "Updating"), and the system and Tailscale yourself.

set -euo pipefail

REPO="${KPS_REPO:-palchrb/vibb-launcher}"
# The pre-monorepo server repo: its installs pass it in (an older install's root-side updater
# remembered the repo it was installed from). Its releases now live in the monorepo.
if [ "$REPO" = "palchrb/kid-phone-server" ]; then
    REPO="palchrb/vibb-launcher"
fi
# It goes into the download URLs, so it must be a plain owner/repo and nothing else.
if ! [[ "$REPO" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]]; then
    echo "KPS_REPO must look like owner/repo, got: $REPO" >&2
    exit 1
fi
INSTALL_DIR="/opt/kid-phone-server"
SERVICE_USER="kidphone"
# The installed server version, for the downgrade check: root's, outside data/. Installs from
# before 0.67.0 kept it in OLD_UPDATER_DIR, the root helpers' directory (remove_host_helpers). The
# same lines in install.sh and update.sh.
VERSION_FILE="/var/lib/kid-phone-server/installed_version"
OLD_UPDATER_DIR="/opt/kid-phone-server-updater"

# Security review M5: every release file root installs or runs is signed with the project's
# minisign key, pinned here (the same line in install.sh and update.sh - a server test keeps them
# equal; server/DEPLOY.md, "Release signing"). The trusted comment names the file and
# the tag, so a signed file can't be passed off under another name or as another release.
SERVER_SIGNING_PUBKEY="RWQp6olF7G4WFeRgDWSWwq2/qOkClyqqXEzDBrPPf2EUrdZlAUQATU5g"

if [ "$(id -u)" -ne 0 ]; then
    echo "Please run this as root (e.g. 'sudo bash install.sh')." >&2
    exit 1
fi

# Nothing to install without the key (QA of the security wave, Low 7): say so before touching
# anything, instead of failing on a download later.
if [ "$SERVER_SIGNING_PUBKEY" = "not-set" ]; then
    echo "No release signing key is pinned in this script, so it installs nothing: root takes only" >&2
    echo "server releases signed with the project's key, and this copy predates the key. Use the" >&2
    echo "script from main once the first signed release (server 0.24.0) is published" >&2
    echo "(server/DEPLOY.md, \"Release signing\")." >&2
    exit 1
fi

# The release's tarball for this machine: both are static musl binaries, each built natively on a
# runner of its own architecture. The same text in install.sh and update.sh.
case "$(uname -m)" in
    aarch64 | arm64) TARGET="aarch64-unknown-linux-musl" ;;
    x86_64 | amd64) TARGET="x86_64-unknown-linux-musl" ;;
    *)
        echo "Unsupported architecture: $(uname -m)" >&2
        echo "The server is released for 64-bit Linux on aarch64 or x86_64 only." >&2
        exit 1
        ;;
esac
echo "Detected architecture: $(uname -m) -> $TARGET"

echo "Installing prerequisites..."
apt-get update -qq
apt-get install -y -qq curl tar iptables minisign >/dev/null

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
# VERSION_FILE, older installs in OLD_UPDATER_DIR - both root's. Nothing in data/ is read (security
# QA L2: the service user's file could hang root or block every later update); an install with
# neither file skips the downgrade check.
installed_version() {
    local f v
    for f in "$VERSION_FILE" "$OLD_UPDATER_DIR/installed_version"; do
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

# KPS_TAG (a release picked by hand; an older install's root-side updater sets it to the tag it
# fetched update.sh from) or the newest stable server release.
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

# verify_signed FILE NAME TAG: FILE.minisig must be a good signature of FILE by the pinned key,
# with the trusted comment "NAME TAG". Anything else - no key pinned, no minisign, no or a bad
# signature, another comment - refuses.
verify_signed() {
    local file="$1" name="$2" tag="$3" comment
    if [ "$SERVER_SIGNING_PUBKEY" = "not-set" ]; then
        echo "No release signing key is pinned in this script - refusing $name (server/DEPLOY.md, \"Release signing\")." >&2
        return 1
    fi
    if ! command -v minisign >/dev/null 2>&1; then
        { apt-get update -qq && apt-get install -y -qq minisign; } >/dev/null 2>&1 || true
    fi
    if ! command -v minisign >/dev/null 2>&1; then
        echo "minisign is missing and couldn't be installed - refusing $name unchecked." >&2
        return 1
    fi
    if ! comment="$(minisign -V -Q -P "$SERVER_SIGNING_PUBKEY" -m "$file" -x "$file.minisig" 2>/dev/null)" ||
        [ "$comment" != "$name $tag" ]; then
        echo "$name has no valid signature for $tag - not using it." >&2
        return 1
    fi
}

# Downloads the release tarball, its .sha256 and its .minisig into $1 and checks the signature and
# the hash; exits on any failure.
download_release() {
    local dir="$1" asset="kid-phone-server-$TARGET.tar.gz" expected actual
    local url="https://github.com/$REPO/releases/download/$TAG/$asset"
    echo "Downloading $TAG from $url ..."
    curl -fsSL "$url" -o "$dir/$asset"
    curl -fsSL "$url.sha256" -o "$dir/$asset.sha256"
    if ! curl -fsSL "$url.minisig" -o "$dir/$asset.minisig"; then
        echo "$TAG has no signature ($asset.minisig): server releases before 0.24.0 aren't signed, and" >&2
        echo "this script installs only signed ones - not installing (server/DEPLOY.md, \"Release signing\")." >&2
        exit 1
    fi
    verify_signed "$dir/$asset" "$asset" "$TAG" || exit 1
    expected="$(head -c 64 "$dir/$asset.sha256")"
    actual="$(sha256sum "$dir/$asset" | cut -c1-64)"
    if ! [[ "$expected" =~ ^[0-9a-f]{64}$ ]] || [ "$expected" != "$actual" ]; then
        echo "SHA-256 of $asset doesn't match $asset.sha256 - not installing." >&2
        exit 1
    fi
    tar -xzf "$dir/$asset" -C "$dir"
    rm -f "$dir/$asset" "$dir/$asset.sha256" "$dir/$asset.minisig"
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

# The version just installed (vX.Y.Z, the form of the app's own version), for the downgrade check.
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
# The phones' server address in the setup QR while Settings > Phone Setup has none saved (an address
# saved there wins). https://; http:// to a Tailscale address (100.x, fd7a:115c:a1e0::, *.ts.net);
# http:// to another private address only through an encrypted tunnel (taken with a warning, the
# server can't tell); http:// to a public host is ignored.
#PHONE_SERVER_URL=https://vibb.your-tailnet.ts.net
EOF
    PRINT_CREDENTIALS=1
else
    echo "Existing .env found — leaving it untouched."
    PRINT_CREDENTIALS=0
fi

# data/ is the service user's: root never creates, writes or chmods anything in it that they could
# swap for a symlink (security review M4; QA of the security wave, Low 2).
if [ -L "$INSTALL_DIR/data" ]; then
    echo "$INSTALL_DIR/data is a symlink - refusing to continue." >&2
    exit 1
fi
chown "$SERVICE_USER:$SERVICE_USER" "$INSTALL_DIR/data"

chown -R "$SERVICE_USER:$SERVICE_USER" "$INSTALL_DIR"
chmod 600 "$INSTALL_DIR/.env"
# data/ (the database, backups, keys) is the service user's alone (security
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

# The host helpers' removal. Since 0.67.0 the server never makes its host do anything (the user,
# 2026-11-04): no root watcher for its requests (update, restart, apt, Tailscale, reboot, format a
# drive, restore), no root scheduler for the system's updates, no root copy of the backups to a
# drive. An install from before has them; this removes them, the last time root touches them. A
# helper that is running right now is never stopped (an older server's "Update now" runs update.sh
# inside the watcher's own service): with its unit gone it ends by itself. A backup drive mounted
# at /mnt/kid-phone-server-backup and its /etc/fstab line are the owner's and stay. The same text
# in install.sh and update.sh.

# record_version vX.Y.Z: the installed server version, into VERSION_FILE (root's).
record_version() {
    install -d -m 755 "${VERSION_FILE%/*}"
    printf '%s' "$1" >"$VERSION_FILE.new"
    mv -f "$VERSION_FILE.new" "$VERSION_FILE"
}

remove_host_helpers() {
    local unit current
    for unit in kid-phone-server-updater.path kid-phone-server-scheduler.timer kid-phone-server-backup-sync.timer; do
        systemctl disable --now "$unit" >/dev/null 2>&1 || true
    done
    for unit in kid-phone-server-updater kid-phone-server-scheduler kid-phone-server-backup-sync; do
        rm -f "/etc/systemd/system/$unit.service"
        rm -rf "/etc/systemd/system/$unit.service.d"
    done
    rm -f /etc/systemd/system/kid-phone-server-updater.path \
        /etc/systemd/system/kid-phone-server-scheduler.timer \
        /etc/systemd/system/kid-phone-server-backup-sync.timer
    # The version the downgrade check reads moves out of the directory that goes.
    current="$(installed_version)"
    if [ -n "$current" ] && [ ! -f "$VERSION_FILE" ]; then
        record_version "$current"
    fi
    # Root's alone (0700): actions.sh, watcher.sh, scheduler.sh, backup_sync.sh, their run dates.
    rm -rf "$OLD_UPDATER_DIR"
    systemctl daemon-reload
}

record_version "$INSTALLED_VERSION"
remove_host_helpers

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
echo "The server never changes anything on this machine by itself. To update it,"
echo "run update.sh over SSH (DEPLOY.md, \"Updating\"); keep the system and"
echo "Tailscale updated yourself. Backups are made by the server in data/backups/."
echo ""
echo "Check status any time with: systemctl status kid-phone-server"
