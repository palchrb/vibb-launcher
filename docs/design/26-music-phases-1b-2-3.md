# Design 26: Vibb music phases 1b (Sonos), 2 (Storytel), 3 (Spotify) - brief

Status: a brief for the session that builds these phases (2026-10-11). Nothing here is designed yet: the first step of
each phase is an architect's buildable spec, in the style of design 21. This file carries what that session needs and
can't see elsewhere, in particular the working rules the main session keeps in its own memory.

## Where things stand

- **Phase 1 is done on `main`** (design 21, steps 1-4 plus their fix rounds): server library, import (21a) and sweep
  (21b), PWA, the music app with NRK, RSS and own files, speaker + Bluetooth, the launcher bridge, Home card, the lock's
  media view, time rules, the volume cap. **Not done yet:** `music-v0.1.0` isn't tagged, and phase 1's device checks
  on the Jelly Star (design 21's step status lists) haven't run.
- **Read first:** root `CLAUDE.md`, `music/CLAUDE.md`, `docs/design/20-vibb-music.md` (its decision sections win over
  its §1-§5), `21-vibb-music-phase1.md`, `21b-music-server-sweep.md`, `vibb-spotify-options.md`, the mockup
  `docs/design/mockups/vibb-music/` (v15, published at https://claude.ai/artifact/2np6veF8YP3bJeQCAct9fY).
- **The reference implementation** is the Pi player, github `palchrb/vibb` (`pi/sonosd.py`, `pi/daemon.py`,
  `pi/vibb/{library,content,storytel,bookmarks}.py`, `tests/`). MIT: behaviour is reimplemented in Kotlin and credited
  in NOTICE.md and each ported file's header.

## The phases, in order

### 1b - Sonos (start here)
- Design 20 "Outputs": the "Spill av på" picker gains Sonos rooms. The phone is a UPnP **controller**, never a server.
- Port `sonosd.py`: the uid -> ip cache, discovery only on a cache miss, a rescan or a failed play; content kinds `url`
  (the speaker fetches from the origin: NRK/RSS enclosures, later Storytel's signed CDN URL) and `nrk_program` (the NRK
  Radio Sonos service, `x-sonos-http`, sid 277); stall and group-migration handling. `spotify_sharelink` waits for 3.
- Open points for the architect: a Kotlin UPnP/SSDP client (licence must be GPL-compatible), SSDP and multicast
  through the launcher's `KidVpnService`, and own files on Sonos: a short-lived signed URL on the handy server that the
  speaker fetches over the LAN (design 21 question 3; note 21b's LAN/public rules and the `lan` flag).
- Testable here with canned UPnP/SSDP responses and vibb's tests ported; real speakers are a device check for the user.

### 2 - Storytel
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
