# The dependency risk register

One row per outside dependency: something we don't control that a feature needs at runtime (or, for the last rows, to
build and ship). Started by design 116 (consolidation step C2, audit R1) from the audit's format
(`docs/review/2026-11-17-maintenance-audit.md` §5). **Kept current like the queue:** changed in the commit that adds or
changes a dependency, and re-checked at every launcher RC and every major Android or iOS update. A new feature names
its row and its health signal, or it waits (the audit's gate after C4).

Columns:
- **Kind**: S = an official or stable spec, U = unofficial or undocumented, P = Android-version or platform behaviour,
  G = Google-, Pixel- or OEM-specific, T = a third party's terms.
- **Fails**: closed (nothing is allowed that wasn't), soft (a feature stops, the core works), open (something the parent
  restricted is let through).
- **Detected by**: today, then what design 116 adds (marked "C2"). Att = an Attention row, Ev = a parent event (inbox,
  maybe push), Page = a line on the feature's own PWA page.
- **Kill switch**: how the parent (or owner) turns the dependency off without a release.
- **Verified**: the date the row was checked against the code. It says the row matches the code, not that the
  upstream still works. "Unverified" names what wasn't checked.

S/ = `server/src/`, L/ = `launcher/app/src/main/java/com/kidslauncher/mdm/`, M/ = `music/app/src/main/java/`.

## Runtime: outside services the server calls

| ID | Dependency | Kind | Used by | Breaks how | Fails | Detected by | Canary / fake | Kill switch | Verified |
|---|---|---|---|---|---|---|---|---|---|
| D1 | Storytel login, token and asset API (storytel-player's AES key and IV, `login.action`, the asset call's 302) | U, T | music: Storytel (S/storytel.rs; the phone gets tokens by the courier) | a new key, endpoint or answer shape; the account refused | soft: downloaded books play, new ones stop | Att `MusicStorytelRefused` (from the phone's report); Music › Settings' Storytel lines. C2: Att Integration `storytel` (outage) | `scripts/fake-storytel.py`, `storytel_token_answers.json` | Storytel off per phone (`device_policy.music_storytel`) | 2026-11-18 |
| D2 | Spotify Web API, accounts service, Web Playback SDK (loaded unpinned from `sdk.scdn.co`), Developer Policy (dev-mode quota, User Management; forbids products aimed at children) | S, U, T | music: Spotify (S/spotify.rs, the music app's player) | the SDK changes in place; quota; the client ID revoked | soft per entry; the feature hard if the client ID goes | Att `MusicSpotify`, `MusicSpotifyLogin`, `MusicSpotifyEnding`, `MusicSpotifyNoApp`. C2: Att Integration `spotify` (outage) | `scripts/fake-spotify.py`, `server/testdata/spotify/` | the account unbound per phone | 2026-11-18 |
| D3 | Google's `/maps/preview/place` by feature id, short-link redirects | U | map links (design 110) | format, a consent page, refusal | soft (Organic Maps' own search) | not built; design 110's counter, then C2's Integration row | recorded answers (planned) | the resolver off (planned) | not built |
| D7 | NRK psapi; podcast RSS hosts | U (NRK), S (RSS) | music: NRK and RSS entries (S/music_sweep.rs, S/music_sources.rs) | the API or a feed moves; 404/410 | soft: kept items play | Page (`music.status.failed_*`); Att `MusicFailing` counts only what the phone reports. C2: Att Integration `music_feed` | `music_library`/`music_listing` snapshots, `scripts/fake-audio.py` | the entry off per phone | 2026-11-18 |
| D8 | api.met.no (locationforecast, nowcast) and its terms (User-Agent with `MET_CONTACT`, caching) | S, T | lock-screen weather (S/weather_cache.rs) | terms, a 403 for the User-Agent, format | soft: no weather line | nothing (codes in `weather_cells.last_error` only). C2: Att Integration `met` | `weather.json`, `server/testdata/weather/` | Weather off per phone (`launcher_ui.lock_weather`) | 2026-11-18; the nowcast's outcome is in memory only |
| D9 | CalDAV servers (read sync and the kid's events written back) | S (each server's quirks) | Dagen (S/caldav.rs, S/calendar_kid_events.rs) | a refused password, another host, a quirk | soft | Page (Device › Calendar). C2: Att Integration `caldav`, `caldav_write` | `tests::fake_dav` | the calendar unticked; the account removed | 2026-11-18 |
| D10 | ICS calendar links (schools' feeds) | S | Dagen (S/calendar_feeds.rs) | 404/410, not a calendar, private address | soft: the last good set stays | Page (`calendar.links.status.failing`). C2: Att Integration `calendar_link` | `calendar_feeds.json`, `server/testdata/calendar_feeds/` | the link removed per phone | 2026-11-18 |
| D11 | GitHub Releases API and assets | S | catalog, Vibb One's own updates (S/handlers/tracked_apps.rs); also the server's own update check (S/handlers/system_update.rs) and the setup QR's launcher check (S/launcher_check.rs), shown on Software Updates and Phone Setup and left out of C2's feed | rate limit (403/429), 5xx, a repo moved | closed (holds, signer pins) | Page (`apps.list.update_failed`, the app's page); the state is in memory and lost at a restart. C2: Att Integration `github` (persisted) | `server/testdata/apk/`, `tests::tracked_apps` | the app unchecked per phone | 2026-11-18 |
| D12 | F-Droid index v2 (`entry.jar`, JAR signature) for f-droid.org, IzzyOnDroid and pinned repos | S | catalog (S/fdroid.rs) | a new key, rollback, format | closed (a bad signature or key refuses) | Page (`apps.sync.repo_*`). C2: Att Integration `fdroid` | `tests::fdroid` fixtures | the app unchecked per phone | 2026-11-18 |
| D13 | DNS blocklist feeds (public lists by URL) | S | the Web filter's lists (S/dns_engine.rs) | a feed moves or serves a portal page | closed per feed: the last good domains are kept, also over a restart | Page (Settings › Web filter, the feed's state). C2: Att Integration `blocklist` (only feeds in force on the phone) | `DnsFilterEngineTest`, server tests | the feed off | 2026-11-18 |
| D14 | Web Push services: FCM, Mozilla autopush, Apple Web Push for installed PWAs (RFC 8030/8291/8292) | S, G | parents' pushes (S/push.rs, S/webpush.rs) | an endpoint gone (404/410), a VAPID refusal, iOS changes | soft | `push::Health` on Getting Notifications, the Devices list's push row, Ev `PushOff` | none | per subscription | 2026-11-18 |
| D25 | OpenStreetMap's tiles (`tile.openstreetmap.org`, in the CSP, S/admin_auth.rs) and its tile usage policy | S, T | the PWA's maps (Locate, zones) | the policy, a block of the server's referrer, an outage | soft: no map, the coordinates and zones still work | nothing | none | none | 2026-11-18 |
| D15 | Matrix homeservers posting to the UP gateway; Element X's push provider setting and call notices | S (Matrix push gateway, UnifiedPush), third-party app | app push, VoIP over the lock (S/unified_push/, L/push/up/) | an Element X release; a homeserver's push changes | soft: Element X falls back to Google | Att `AppPushWaiting`, `AppPushRefused`, `AppPushOutside` (design 108c, the server's own data); no C2 feed (inbound) | `scripts/fake-homeserver.py` | UP off (`unified_push.on`) | 2026-11-18; Element X's behaviour per release unverified |

## Runtime: the phone's platform

| ID | Dependency | Kind | Used by | Breaks how | Fails | Detected by | Canary / fake | Kill switch | Verified |
|---|---|---|---|---|---|---|---|---|---|
| D4 | SystemUI and keyguard behaviour, `BOOT_COUNT` | P, G | the glance, keyguard cover, boot cover (L/lock/) | a monthly patch, an OS update, Unihertz's build vs Pixel's | hard: locked out or open | Att `BootCoverNotShown`, `ChildLockOff`, `UnseenRestarts`; crash reports | emulator gates (`docs/testing/emulator.md`) on 14/16/17 + the Jelly Star | none (core) | 2026-11-18 |
| D5 | `AirplaneModeVoiceActivity` + the Digital assistant role, the lift of `DISALLOW_CONFIG_DEFAULT_APPS`, OEM `apm_enhancement_enabled` | P, G | Flymodus (design 98) | an OEM leaves the activity out; an Android change | soft: the row stops working, Off still works | the row's `last_error` (Device › Advanced) | `airplane_mode.json`, emulator §6 | bit 64 of `quick_controls_mask` | 2026-11-18 |
| D6 | Default SMS role, telephony, operators' MMSCs and APNs (AOSP `apns-full-conf.xml` at a pinned commit) | P | SMS/MMS (L/sms/) | Android telephony; an operator's APN | closed: held, not lost | Ev `SmsHeld`, `MmscHost`; Att `Calls(MessagesHidden)`, `NotificationsOff` | `scripts/fake-mmsc.py`, `sms_contract.json`, `scripts/mmsc-hosts.py --selftest` | `sms_mode` | 2026-11-18 |
| D16 | VpnService always-on (`setAlwaysOnVpnPackage`), `establish()`, pcap4j 1.8.2 (an old release), dnsjava 3.6.5 | P, an old library | the Web filter, the sink (L/server/KidVpnService.kt) | an Android VPN change; `establish()` fails or the VPN is revoked; a library bug | **open** for filtering: lockdown is off in the filter shape | nothing: the report only leaves `dns_upstream` out, and no page reads that as down. C2: report `vpn_filter`, Att `FilterDown`, Ev `FilterDown` (pushed), a retry on an alarm | `DnsFilterEngineTest`, `MobileNetworkTest`, `mobile_network.json` | the filter switch per phone | 2026-11-18 |
| D17 | Captive portal detection, the browser role, arbitrary portal HTML | P | Wi-Fi sign-in, Chrome only for logins (design 102) | portal HTML; the role per Android version | closed: the kid can't sign in | Ev `WifiSigninWide` | `scripts/fake-portal.py`, `login_rules.json` | `captive_portal` / `login_browser` switches | 2026-11-18 |
| D18 | Play Store, Play services, GSF (other apps' FCM, Play installs) | G | Element X's FCM, Play as an app source (S/play.rs) | a Play update; FCM hosts | soft | none | none | Play's install mode | 2026-11-18 |
| D26 | The filter's public resolvers: Cloudflare and Quad9, DoT on 853 and DoH (L/server/PublicResolver.kt, design 36/67) | S | the Web filter's upstream when no own DNS server is set | an outage, a blocked port on a network | closed for names: lookups fail | the `/dns` page's "provider down" line from the report's `dns_upstream` | `CustomDnsTest`, `DnsUpstreamStatusTest` | the parent's own DNS server (Settings › Web filter) | 2026-11-18 |
| D19 | Sonos UPnP/SOAP/SSDP and the speaker's WebSocket, an exact DIDL | U | music: rooms (M/…/sonos/) | firmware | soft: plays on the phone | nothing on the server | `scripts/fake-sonos.py` | the room scope (`music_sonos_rooms_json`; `[]` = no room) | 2026-11-18; whether the PWA offers "no room" unverified |

## Build and release

| ID | Dependency | Kind | Used by | Breaks how | Fails | Detected by | Canary / fake | Kill switch | Verified |
|---|---|---|---|---|---|---|---|---|---|
| D20 | Tailscale tsnet via gomobile (Go, NDK) | S | the launcher's transport (unused by the family) | Go/NDK/gomobile bumps | the CI build fails; local builds use a stub | CI | the stub (`-PrequireTsnet`) | a flavour (audit R6, not built) | 2026-11-18 |
| D21 | Maven Central, the Gradle plugin portal, crates.io, checksum-verified (`verification-metadata.xml`, `Cargo.lock`) | S | every build | an artifact moves or is served by another repo (the compose plugin's .pom from the plugin portal, 2026-11-18) | the build fails closed | CI | none | none | 2026-11-18 |
| D22 | GitHub Actions (pinned by SHA), native arm64 runners, GHCR, the `release` environment's secrets | S | releases, the server image | a runner image change, GHCR visibility | releases stop; nothing ships unsigned | CI | `workflow_dispatch` dry runs | none | 2026-11-18 |
| D23 | Pinned build-time sources: yr's weather symbols (a commit + SHA-256), AOSP's APN list (a commit + SHA-256), Unicode `emoji-test.txt` 16.0 (SHA-256) | S | the launcher's symbols, `mmsc_hosts.txt`, the emoji list | only when bumped | the script refuses a changed source | `--selftest`s in CI | the vendored samples | stay on the pin | 2026-11-18 |

## Not a dependency, kept here because it fails silently

| ID | What | Fails | Detected by | Verified |
|---|---|---|---|---|
| D24 | Backups in `data/backups/` (same disk as the database; the server never copies off the machine) | unknown until a restore; a scheduled failure is only logged today | C2: each backup opened and checked (design 116 §4), the Backups page, a Devices-list row. In Docker the folder becomes a host bind mount, `./backups` (design 116 §5), so the host's own backup takes it; off-machine copies stay the owner's (DEPLOY.md) | 2026-11-18 |
