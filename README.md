# Vibb Phone

A parent-curated phone for a child, on stock Android. Parents manage it from a web app at home, the child gets a
calm, simple phone. Three parts live in this repository:

- **`launcher/` - Vibb One**, the phone's home screen and, as *device owner*, the parental-control agent: a kiosk
  with an app allowlist, call allowlists in both directions, its own PIN lock with a glance page, school, bedtime and
  custom time rules, screen time, contacts with photos, missed calls, Element X chat links, an on-device DNS filter,
  Find My Device, and silent app installs and updates.
- **`music/` - Vibb Music**, the child's music, podcast and audiobook player: NRK and podcasts, the family's own
  files, Storytel books, Spotify playlists, and Sonos speakers at home. Everything is chosen by the parents; it is
  installed and managed through Vibb One.
- **`server/` - the Vibb Phone server** (Rust, Axum, SQLite) with the parents' web app (a PWA). It holds the rules
  and the library, lists the music sources, and builds each phone's DNS filter. The phone never serves anything:
  changes reach it at once over a server-sent-events stream it holds to its own server (no Firebase or Google push),
  and it checks in by itself every 30 minutes.

## Status

Built and tested on the Android emulator; device tests on the Unihertz Jelly Star are under way. The plan, the
status and the fix backlog are in [`PLAN.md`](PLAN.md); the design notes, one per step with their QA reviews, are in
[`docs/design/`](docs/design/) and [`docs/review/`](docs/review/).

## Getting started

- **Server**: [`server/DEPLOY.md`](server/DEPLOY.md) - a Raspberry Pi (signed aarch64 release) or Docker
  (`ghcr.io/palchrb/handy-server`, amd64 and arm64; `server/deploy/compose.yaml`, `env.example`,
  `Caddyfile.example`). The admin pages answer the tailnet only by default; the phones' API can have its own
  listener behind your own HTTPS proxy.
- **A phone**: in the web app, Devices › Add Phone - a setup QR code for a new or reset phone, or a setup code for one
  set up with `adb`.
- **Google account and Play on the child's phone** (optional): [`docs/setup/google-account.md`](docs/setup/google-account.md).
- **Development**: the emulator test loop in [`docs/testing/emulator.md`](docs/testing/emulator.md) and
  `scripts/dev-rebuild.sh`; build and test commands in [`CLAUDE.md`](CLAUDE.md).

## Releases

| Tag | Result |
|---|---|
| `launcher-vX.Y.Z` | Vibb One as a signed APK (`kids-launcher-mdm.apk`); the stable ones become GitHub's "latest", which the setup QR uses. |
| `launcher-vX.Y.Z-rc.N` | A Vibb One prerelease, never "latest". |
| `music-vX.Y.Z` (or `-rc.N`) | Vibb Music as a signed APK (`vibb-music.apk`), never "latest". |
| `server-vX.Y.Z` | The server: the aarch64 tarball and `update.sh`, signed with minisign, and the multi-arch Docker image. |

Phones update themselves from the server's app catalog: Vibb One at night with the screen off, Vibb Music when the
parent has turned it on for the phone. See [`CLAUDE.md`](CLAUDE.md) for the CI details.

## Licence

GPL-3.0-or-later; see [`LICENSE`](LICENSE) and [`NOTICE.md`](NOTICE.md). The launcher's inherited app-list code stays
MIT ([`launcher/LICENSE-MIT-UPSTREAM`](launcher/LICENSE-MIT-UPSTREAM)). Nunito is OFL 1.1, and the Material Symbols
icons are Apache-2.0. Vibb Music's behaviour is ported from the Vibb player (MIT).
