# Design 26: Vibb music phases 1b (Sonos), 2 (Storytel), 3 (Spotify) - brief

Status: a brief for the session that builds these phases (2026-10-11). Nothing here is designed yet: the first step of
each phase is an architect's buildable spec, in the style of design 21. This file carries what that session needs and
can't see elsewhere, in particular the working rules the main session keeps in its own memory.

## Where things stand

- **Phase 1 is done on `main`** (design 21, steps 1-4 plus their fix rounds): server library, import (21a) and sweep
  (21b), PWA, the music app with NRK, RSS and own files, speaker + Bluetooth, the launcher bridge, Home card, the lock's
  media view, time rules, the volume cap. **Not done yet:** `music-v0.1.0` isn't tagged, and phase 1's device checks
  on the Jelly Star (design 21's step status lists) haven't run.
- **Phase 1b (Sonos) is built on `music-1b-sonos`** (status 2026-10-12; spec and every step's status in
  `26a-sonos.md`): steps 1-4 (the `sonos/` core, the renderer axis, the renderer, lifecycle and phone rules, room
  scoping, the docs), each with QA's code review and its fix round; main merged in. **Not done yet:** the emulator
  checks (`docs/testing/emulator.md` §6g) and the device checks on the Jelly Star with real speakers (26a "Device
  checks" and each step's owed list); the merge to `main` is the user's; `music-v0.2.0-rc.1` is tagged on the merge
  commit after it (26a "Release").
- **Read first:** root `CLAUDE.md`, `music/CLAUDE.md`, `docs/design/20-vibb-music.md` (its decision sections win over
  its §1-§5), `21-vibb-music-phase1.md`, `21b-music-server-sweep.md`, `vibb-spotify-options.md`, the mockup
  `docs/design/mockups/vibb-music/` (v15, published at https://claude.ai/artifact/2np6veF8YP3bJeQCAct9fY).
- **The reference implementation** is the Pi player, github `palchrb/vibb`, **branch `pipewire`** (user, 2026-10-11:
  that is vibb's head; GitHub's default `main` is 101 commits behind, with an older `sonosd.py`, `daemon.py` and
  `library.py`, and without `tests/sonos_hold_scan.py`). Files: `pi/sonosd.py`, `pi/daemon.py`,
  `pi/vibb/{library,content,storytel,bookmarks}.py`, `tests/`. MIT: behaviour is reimplemented in Kotlin and credited
  in NOTICE.md and each ported file's header.
- **Phase 1 was ported from vibb `main`.** `library.py`'s only behaviour change since (the `cache` default) applies to
  Spotify under soloist, which the import skips. `daemon.py` changed by ~400 lines (mostly the Pi's audio, idle and
  soloist handling). The main session checks pipewire's play rules (shortcuts, resume, queue, sleep) against phase 1
  and fixes phase 1 itself; the 1b session needn't.
- **Sonos and Storytel are ports, not new designs** (user, 2026-10-11: the Pi's logic there is worked through). The
  architect maps every behaviour of the vibb code below to its Kotlin home and ports vibb's tests as Kotlin tests. A
  deviation is allowed only where the phone forces it (no server on the phone, the kiosk, Doze, the launcher as
  courier), and each one is listed with its reason. Read the vibb code before designing anything; don't re-derive
  what it already handles. **Spotify is the exception**: new ground (below).

## The phases, in order

### 1b - Sonos (start here)
- **Source of truth:** vibb `pi/sonosd.py` and its tests `tests/sonos_contract.py`, `sonos_renderer.py`,
  `sonos_groups.py`, `sonos_grouped.py`, `sonos_migrate.py`, `sonos_migrate_probe.py`, `sonos_same_tile.py`,
  `sonos_hush.py`, `sonos_hold_scan.py` (`sonos_spotify_queue.py` belongs to phase 3).
- Design 20 "Outputs": the "Spill av på" picker gains Sonos rooms. The phone is a UPnP **controller**, never a server.
- Port `sonosd.py`: the uid -> ip cache, discovery only on a cache miss, a rescan or a failed play; content kinds `url`
  (the speaker fetches from the origin: NRK/RSS enclosures, later Storytel's signed CDN URL) and `nrk_program` (the NRK
  Radio Sonos service, `x-sonos-http`, sid 277); stall and group-migration handling. `spotify_sharelink` waits for 3.
- Open points for the architect: a Kotlin UPnP/SSDP client (licence must be GPL-compatible), SSDP and multicast
  through the launcher's `KidVpnService`, and own files on Sonos: a short-lived signed URL on the handy server that the
  speaker fetches over the LAN (design 21 question 3; note 21b's LAN/public rules and the `lan` flag).
- Testable here with canned UPnP/SSDP responses and vibb's tests ported; real speakers are a device check for the user.

### 2 - Storytel
- **Source of truth:** vibb `pi/vibb/storytel.py` and `pi/vibb/bookmarks.py`, with the tests `tests/storytel_api.py`,
  `storytel_expand.py`, `storytel_routes.py`, `storytel_sync.py`, `storytel_bookmark_sync.py`, `storytel_subprocess.py`,
  and `storytel_sonos.py` (Storytel on Sonos through the signed CDN URL; it lands here if 1b is done, else after it).
- Design 20 "Storytel and the source fetching": the phone logs in and downloads by itself, from Storytel's CDN, like
  the Pi. The login is entered in the PWA, stored encrypted on the server and sealed to the phone through the launcher:
  **that plumbing already exists** (design 21 §1.4 and step 3). Phase 2 is the shelf, series grouping, playback,
  downloads and positions on the phone.
- vibb's quirks (`storytel.py`): `model.id`, not the format id; positions in ms; the `player_playback_paused` action;
  one stable deviceId per phone; never a bearer token to the CDN. **No position mirroring back to Storytel** at first
  (user, 2026-10-09).
- Checks from design 20 §5: a 300 MB book over interrupted Wi-Fi resumes after its URL expires; no credentials or JWT
  in logs. The credentials are never shown back in the PWA and never logged.

### 3 - Spotify (a spike first)
- **New ground:** the Pi's Spotify path (go-librespot) doesn't carry over to Android (`vibb-spotify-options.md`: the
  audio-key denial for new accounts). Only `tests/sonos_spotify_queue.py` (Sonos owning a Spotify queue via ShareLink)
  is reusable, for Spotify on Sonos. Everything else is designed from scratch, from the spike's answers.
- Design 20 §4 and `vibb-spotify-options.md`: App Remote driving the official Spotify app as an "engine".
- **The user's hard rule: the kid never sees the Spotify app** (design 20 §4 "Never shown"). Spike items 1 and 3 are
  kill criteria: if App Remote can't connect without showing Spotify, the Spotify source is dropped.
- The spike needs a real phone with Spotify installed, so the session can only build the spike APK and its checklist;
  the user runs it on the Jelly Star. Nothing else in phase 3 starts before the spike's answers.

## How to work (the user's rules; the main session keeps them in memory)

- Talk to the user in Norwegian. Ask only real decisions. Music behaviour questions (resume, queue, offline depth,
  tile taps, NY badges, prune, sleep, ...) follow vibb: check its code and decide; don't ask.
- **Process per step:** architect -> design QA (every non-trivial step) -> one implementer -> code QA -> the same
  implementer fixes every real bug, Lows included (skip only theoretical or scale hardening, with a reason).
  Self-hosted, one family per install: no multi-tenant machinery, but never the "budget edition".
- **Models:** Opus for all real coding and QA; Sonnet only for mechanical work. A fresh implementer per step; resume one
  only for its own fix round.
- **Tests:** implementers run targeted tests while working and the full suites at the end and after a rebase; QA runs
  the full suites once. Run tests in the foreground with `timeout`, or in the background and wait for the notification.
  Never poll with `until ! pgrep -f <pattern>`: the pattern matches the loop's own command line (one hung for 7 hours).
- **Parallel implementers** each get their own git worktree and at most one cargo target dir; QA uses one shared dir
  and deletes it afterwards (disk).
- **Repo rules:** the contract files and the bridge AIDL are byte-identical in their copies and change in one commit
  (root `CLAUDE.md`; new AIDL methods are appended). Migration numbers are picked after the final rebase. Fake keys
  and tokens in tests must never look like real formats (GitHub secret scanning flagged one). Never commit keystores
  or secrets. PWA saves never jump to the top of the page. Ask the user concretely before removing any stock gesture.
  Never install a debug APK on a kid's phone.
- **Git:** work on a branch (e.g. `music-1b-sonos`) and push the branch. Don't open a PR and don't push to `main`; the
  user decides the merge.

## Coordination with the main session

The main session works on `main` at the same time. To keep merges clean:
- **Its areas:** the server security wave (`server/deploy/`, auth, backups, tracked apps), design 24b (the launcher's
  relock, `PinLockActivity`, `device_status`), next design 25 (the lock's glance page: `PinLockActivity`, the
  notification listener) and the launcher security wave. Stay out of those files where you can.
- **Shared with music:** the launcher's music bridge, Home card and the lock's media view. Change them only where a
  phase needs it, append to the AIDL, and rebase on `main` often.
- Server migrations: both sessions add them, so renumber after the final rebase.

## Review of the brief (the 1b session, 2026-10-11)

Read before the 1b spec: the brief, designs 20/21/21b, `music/` on `main` (`cdf003d`), and vibb `22bdc38` (`pi/sonosd.py`,
`pi/daemon.py`, `pi/vibb/{renderer,storytel}.py`, `tests/sonos_*.py`, `tests/storytel_*.py`). The order 1b -> 2 -> 3 and
"ports, not designs" hold. Five corrections, two of them decisions for the user.

**vibb's branch (user, 2026-10-11):** the live line is `pipewire` (`1fcadac`, 2026-09-21, 101 commits past GitHub's
default `main` `22bdc38`, which is its direct ancestor). Every phase reads `pipewire`. For 1b the difference is one
`sonosd.py` behaviour (the background SSDP round, design 26a §2.1); `storytel.py` and `bookmarks.py` are identical on
both, so phase 2's inventory can cite either. The `storytel` branch is older than `main` and is not a source.

1. **Sonos is two ports, not one.** `sonosd.py` (1115 lines) is only the sidecar: the SOAP calls, the poller and the
   uid -> ip cache. The controller lives in `daemon.py`: about twenty `_sonos_*` methods (lines 1085-2100, `_sonos_poller`
   at 4254, `_sonos_on_term` at 5340, the fields at 350-420) with the queue mapping, bookmark throttling, stall and
   migration handling, the step/seek workers and `_sonos_body` (late URL minting). `tests/sonos_renderer.py` and
   `storytel_sonos.py` import `daemon`, not `sonosd`. The architect maps both files. In the music app this means a
   **renderer axis** next to the output axis, as the Pi has it (`renderer.py`: `output` = where *we* write audio,
   `renderer` = who makes sound): a `Renderer` with Local (ExoPlayer) and Sonos (SOAP poller), the Sonos one a Media3
   `SimpleBasePlayer` mirroring the speaker, so the launcher's Home card and lock media view (they read the platform
   session) keep working unchanged. Today `Output(id: Int, kind, name)` is keyed by `AudioDeviceInfo` id and
   `PlaybackService` assumes ExoPlayer plays.
2. **The UPnP client is written here, no library.** The SoCo surface `sonosd.py` uses is small: `SoCo(ip)`, `discover`,
   AVTransport (SetAVTransportURI, Play/Pause/Stop/Seek, GetPositionInfo, GetTransportInfo), RenderingControl
   (volume, mute), ZoneGroupTopology (group, coordinator), the queue (clear, play from index) and ShareLink (phase 3):
   eight to ten SOAP actions, SSDP M-SEARCH and the device description. OkHttp + `XmlPullParser`, 400-600 lines.
   Cling is LGPL and dead; jUPnP is CDDL (not GPL-compatible). That answers the licence question.
3. **SSDP and the VPN are fine; three other things need spelling out.** `KidVpnService` routes only `192.0.2.2/32`
   through the tun, so multicast to 239.255.255.250 and LAN unicast bypass it. Needed instead: `WifiManager.MulticastLock`
   + `CHANGE_WIFI_MULTICAST_STATE` (Android drops multicast replies without it). Then:
   - the music app's address guard (21b §2.7, `publicOnly`) refuses private addresses: SOAP control to 192.168.x.x
     goes through a LAN-only client (like `MediaCache.lanClient`), and the guard never applies to control traffic;
   - **bedtime on Sonos**: the launcher pauses the session before suspending the app; the Sonos renderer must turn that
     pause into a synchronous `Stop` on the speaker, or the speaker plays on after the app is suspended (the Pi never
     had this problem). A crash while Sonos plays leaves the speaker playing: accepted, documented;
   - **polling from a sleeping phone**: vibb's cadence (5/15/60 s, disarm after 600 s) is for a Pi on mains. On the
     phone the poller runs in the `mediaPlayback` FGS while the phone itself plays nothing, and Doze defers network.
     GENA eventing is out (the phone is never a server). So: a slower cadence with the screen off (e.g. 60 s), a
     bookmark on every poll, and a Doze device check on the Jelly Star.
4. **Storytel: who fetches the shelf? (user decision, before phase 2.)** Design 20 §2.5 says the server reads the shelf
   and mints URLs; the 2026-10-09 decision says the phone logs in and fetches by itself and the server holds only the
   library definition. The parent still picks books and series in the PWA, which needs the shelf server-side.
   - **A (recommended):** the server logs in with its own stable `deviceId`, for `login.action` + `bookshelf` only,
     never bookmarks or audio (it already holds the plaintext via `MUSIC_SECRET_KEY`); the phone mints the CDN URL,
     downloads and keeps positions. Cost: two "devices" on the account.
   - **B:** the phone reports the normalised shelf over the bridge and the PWA shows it. A phone must be logged in and
     synced before anything can be picked; a new phone can't bootstrap.
   **Decided: A** (user, 2026-10-11). The server logs in with its own stable `deviceId` (never the phones'), only for
   `login.action` and `bookshelf`, never bookmarks or audio; the shelf is normalised with vibb's `normalize_shelf`
   (`storytel.py:424-490`: `model.id` not the format id, series grouping, the kids/locked/geo flags as data) for the
   PWA's picker. The phone keeps everything else: the CDN url minted per play, downloads, positions. Phase 2's
   architect writes it up in that spec.
   Also for phase 2: `_encrypt_password` shells out to `openssl`; on Android it is `javax.crypto` (AES-128-CBC, PKCS#7,
   UPPERCASE hex) with `tests/storytel_api.py`'s vector ported as is, and `storytel_subprocess.py` is a deviation. The 302
   from `assets/v2/consumables/<id>/abook` is captured with `followRedirects=false`, as vibb does, never left to OkHttp's
   header stripping.
5. **Sequencing.** Design 21 put 1b after phase 1's device checks; the brief says start 1b now. Both hold: the checks are
   the user's on the Jelly Star, 1b is built in the sandbox meanwhile. But tag `music-v0.1.0` first so the checks test a
   fixed build, and 1b becomes 0.2.0. The risk is the checks forcing `PlaybackService` changes while 1b rewrites it, so
   1b starts with the Android-free core (see the steps in the 1b spec).

Spotify (phase 3), two additions: the App Remote AAR's terms must be checked before it enters a GPL repo (design 20
flagged it; not done), and the spike APK is a throwaway module outside `music/`. The "engine" app state is launcher
work in `AppEnforcer`/`AppFilter`, which the main session also touches: the biggest coordination risk of phase 3.
