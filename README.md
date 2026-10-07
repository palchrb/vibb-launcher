# Vibb launcher

A parent-curated phone for a child, on stock Android. Parents manage it from home, the child gets a simple phone.

- **`launcher/`** - the Android app. It is the phone's home screen and, as *device owner*, the parental-control agent:
  a kiosk with an app allowlist, call allowlists in both directions, its own PIN lock, school and bedtime rules,
  screen time, contacts with photos, missed calls, Element X chat links, and silent app installs and updates.
- **`server/`** - the self-hosted admin server (Rust, Axum, SQLite) with the parents' web app (PWA). It is reached
  over Tailscale/Headscale; the phone never serves anything. Changes reach the phone through Firebase Cloud Messaging
  nudges, with a server-sent-events fallback.

## Status

Built and tested on the Android emulator; device tests on a Unihertz Jelly Star are next. The plan and status are in
[`PLAN.md`](PLAN.md), and the design notes, one per step with their QA reviews, are in [`docs/design/`](docs/design/).

## Getting started

- Server: [`server/DEPLOY.md`](server/DEPLOY.md).
- Emulator test loop: [`docs/testing/emulator.md`](docs/testing/emulator.md) and `scripts/dev-rebuild.sh`.
- Provisioning a phone: the server's Devices > Provision page (QR), or `adb` as in the emulator guide.
- Google account and Play on the child's phone (optional): [`docs/setup/google-account.md`](docs/setup/google-account.md).

## Releases

| Tag | Result |
|---|---|
| `launcher-vX.Y.Z` | A signed APK release (`kids-launcher-mdm.apk`). |
| `launcher-vX.Y.Z-rc.N` | A prerelease, never "latest". |
| `server-vX.Y.Z` | The server tarball. |

Only stable launcher releases become "latest", which the provisioning QR uses. See [`CLAUDE.md`](CLAUDE.md) for the
CI details.

## Licence

GPL-3.0-or-later; see [`LICENSE`](LICENSE) and [`NOTICE.md`](NOTICE.md). The launcher's inherited app-list code stays
MIT ([`launcher/LICENSE-MIT-UPSTREAM`](launcher/LICENSE-MIT-UPSTREAM)). Nunito is OFL 1.1, and the Material Symbols
icons are Apache-2.0.
