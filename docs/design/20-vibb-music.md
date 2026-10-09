# 20 - Vibb music on the phone

Architect draft (2026-10-09). Inputs: PLAN.md "Vibb integration", `vibb-spotify-options.md` (decision: option A),
palchrb/vibb `main` (README, SPEC*, docs, pipod, `pi/vibb/{library,content,storytel,bookmarks}.py`). There is no
`PLAN-android.md` on any of vibb's six branches (GitHub API), so this doc takes its place. User decision 2026-10-09: the
library is managed by this repo's server and PWA; the phone never serves anything.

## 1. Where the player lives

| | **A: own app `music/`** (`me.vibb.music`) | B: screen in the launcher | C: launcher APK, `:music` process |
|---|---|---|---|
| Crash/ANR | own process; ExoPlayer or App Remote can't take down Home, PIN lock, in-call or tsnet | in the call path, next to tsnet's native code | isolated, but the launcher's night update kills playback |
| Time rules | suspend it or send a mode (existing machinery) | can't suspend our own package (= Home) | as B |
| Release | `music-vX.Y.Z` tags, catalog row, any day | every fix rides the device-owner release (no downgrade, night window) | as B |
| Server access | none of its own: the launcher is the courier (§2.7) | direct | shares the launcher's files |

**Recommend A, in this monorepo as `music/`** (Kotlin, its own Gradle project, GPL-3.0), not in palchrb/vibb:
- The contract (library JSON, managed-config keys, provider) spans server, launcher and player, so it must change in
  one commit (root CLAUDE.md) with shared vectors like `phone_vectors.json`. Same release key as the launcher (the
  provider's signature check), same CI pattern. A later public build can run unmanaged.
- vibb is Python (MIT; its README still says "TBD"): nothing runs on Android as is. We port its field-tested rules:
  NRK psapi catalog rules (`content.py`), Storytel quirks (`storytel.py`: `model.id`, not the format id; positions in
  ms; action `player_playback_paused`; one stable deviceId; never a bearer to the CDN), the entry fields (`order`,
  `cache` -1/0/N, `resume`) and resume rules (`bookmarks.py`, 20 s minimum). vibb and storytel-player (MIT) go in
  NOTICE.md. Media3 and spotify/android-sdk are Apache-2.0 (GPL-3.0-compatible; check the App Remote aar's own terms).
  This replaces PLAN.md's "built in the vibb repo, runs vibb itself" (question 1).

## 2. Server

1. **Data model**: a global catalog plus per-phone ticks, like apps, contacts and wallpapers.
   - `music_sections` (name, sort, kind music/audiobook/podcast, logo) and `music_entries` (section, name, source
     spotify/nrk/rss/storytel/own, target, `play_order` auto/newest_first/oldest_first, `cache` -1/0/1-100, `resume`,
     cover, cover override, `fetched_at`, `last_error`).
   - `music_items` (entry, stable key, title, sort, duration, art URL, and one of url / nrk_program_id / file_id /
     storytel_id / spotify_uri; at most 200 per entry) and `music_files` (own uploads: path, size, SHA-256, tags).
   - `device_music_entries` (device, entry, sort), `device_policy.music_*` (volume cap, mobile streaming, downloads
     Wi-Fi only) and `time_rules.music_sections_json` (the sections usable during that rule).
2. **PWA**: a "Musikk" tab. Add an entry by pasting a link: the source is detected and the feed fetched at once, so a
   bad link fails there, with the error by the field. Order, offline depth and a cover upload are set per entry. A Music
   card on the device page holds the ticks and settings. Every form keeps the parent's place (anchor + scroll restore).
3. **Covers**: one 512 px square JPEG per entry, made on the server (`image`, as for wallpapers), from the parent's
   upload, else an own file's embedded art (`lofty`, MIT/Apache), else the source's art (psapi image, RSS
   `itunes:image`, the Storytel or Spotify cover). Served by hash at `GET /api/devices/music/covers/{hash}`, scoped like
   contact photos. Per-episode art stays a public URL that the player fetches lazily.
4. **Feeds are fetched by the server**: an hourly loop like the catalog sync (one guard per entry, ETag, psapi
   incremental). A change bumps the library version and nudges the phone (SSE).
   - Why the server: one parser to fix when NRK moves, the parent sees episodes and errors, no polling on the phone.
     Audio still goes straight from the source to the phone, never through the Pi Zero (bandwidth, SD card).
   - Items carry a URL only when it is stable (RSS enclosures, NRK podcast mp3). NRK series (HLS) carry the program id,
     and the phone resolves psapi's playback manifest when it plays or downloads.
   - Strip tracking-redirect prefixes (podtrac, chtbl, ...) from enclosures; DNS blocklists often block them.
5. **Storytel**: one family account, its credentials on the server only - recoverable (login.action gives no refresh
   token), encrypted in the DB with a key from `.env` so a DB backup holds no usable password, never sent to a phone.
   - The server reads the shelf with vibb's series grouping (the picker shows the kids/locked/geo flags) and mints a
     book's signed CDN URL on request (`POST /api/devices/music/storytel/{id}/url`, the 302 Location).
   - The phone reports positions; the server mirrors them to Storytel's bookmark API (last value per book, one deviceId
     per phone). ToS: an unofficial API, as on the Pi; the realistic sanction is an account ban. Personal use only.
6. **Own files**: uploaded in the PWA and stored like catalog uploads (`.part` + rename, at most 1 GB, SHA-256). Title
   and order come from the tags. `GET /api/devices/music/files/{id}` is design 13's `ServeFile` (Range, If-Match, ETag).
7. **How the library reaches the phone**:
   - **Policy**: a small `music` object, always sent (update `policy_json_keys_snapshot` / `PolicyResponseCompatTest`):
     `library_version`, `volume_cap_pct`, `mobile_streaming`, `downloads_wifi_only`, engine apps (§4).
   - **Library**: its own endpoint, `GET /api/devices/music/library` (ETag = version, gzip). The launcher fetches it
     only when the version changes (the `dns_filter_version` pattern). It can reach hundreds of KB: too big for every
     sync, far too big for managed config.
   - **Managed config** (`setApplicationRestrictions`) keeps to a few KB of safety keys: system_server persists the
     bundle, and every read crosses Binder. Keys: `managed`, `provider_authority` (debug vs release launcher),
     `volume_cap_pct`, `mode` (all / sections / none), `mobile_streaming`, `downloads_wifi_only`, `spotify_client_id`.
     The player listens for `ACTION_APPLICATION_RESTRICTIONS_CHANGED`.
   - **Bulk**: a launcher ContentProvider that serves only the configured music package with our signature: `library`,
     `covers/{hash}`, `files/{id}`, `call("storytel_url")`, `call("report")` (positions, cache state for the device
     page). The launcher fetches over tsnet. For own files, design 13's runner is generalised from APKs to "server
     files" (same gate, records, resume and hash). The player copies a file into its own storage, closes it, then calls
     `call("release")`. **Never play from the provider**: an open fd holds a stable provider reference, so the
     launcher's death (its self-update) would kill the player.
   - **Rejected**: the player as a second server client (its own tsnet node, or the launcher's SOCKS proxy plus a second
     token). That means two connections, a second credential and duplicate download code.

## 3. Phone

1. **Engines**: one Media3 `MediaSessionService` (FGS `mediaPlayback`). ExoPlayer plays RSS/NRK (progressive and HLS),
   own files and Storytel files; a `SpotifySource` (App Remote) sits behind the same UI. For Spotify, the system media
   card is Spotify's own session [spike: avoid two media cards].
2. **Offline**: Media3 `DownloadManager` writes into one `SimpleCache`, which playback also reads, so streams are
   cached too. `customCacheKey` is the item key, so a re-minted Storytel URL resumes the same bytes. With Wi-Fi only:
   `Requirements.NETWORK_UNMETERED` + storage not low [device test: through our VPN, which since `setMetered(false)`
   inherits the underlying network's metering]. Depth per entry: the newest N in play order; everything for Storytel
   and own files; the bookmarked episode always.
3. **Resume**: positions in Room per item, saved on pause and stop and every 15 s; an item is done near its end. With
   `resume=false` (music) an entry starts from the top. A tile continues its bookmarked episode. No auto-play at boot.
4. **Kid UI** (320x569 dp, Vibb night palette, Nunito): sections in a top row, each a snapping cover carousel (240 dp
   covers, neighbours peeking, a "Ny" badge; a tap plays or continues). Now playing: full art, marquee title, progress,
   play/pause, previous/next, ±30 s for audiobooks; the episode list one tap away. No settings, links, share or search
   (an escape test like `KidScreensEscapeTest`), and no gesture-exclusion rects.
5. **Notifications and the lock**: the media notification is the FGS one. While our PIN lock is LOCKED the shade is
   off and there is no keyguard, so headset/BT buttons are the controls until phase 4's now-playing strip on the lock
   (play/pause/next through the listener's `getActiveSessions`).
6. **Audio**: audio focus (a call pauses playback), `BECOMING_NOISY` pauses, AVRCP via the session. The volume cap
   lives in the launcher: it clamps `STREAM_MUSIC` on every volume change, which also covers Spotify, other apps and BT
   absolute volume. Never `DISALLOW_ADJUST_VOLUME` [device test: clamp latency, BT absolute volume].
7. **Time rules**: the player is a rule's exempt app, as today, and rules gain music sections (e.g. bedtime: only
   "Lydbøker"). At every rule edge the launcher writes `mode` (`TimeRuleAlarm` already wakes there), and the player
   hides disallowed sections and fades out a disallowed item. Not exempt = suspended, as today [device test: does
   suspension stop its audio? If not, the launcher pauses the session]. Screen-off listening is not screen time.
8. **Data and updates**: streaming on mobile data is a per-phone switch, off by default; downloads are Wi-Fi only by
   default. The status report shows cached MB and waiting downloads. The launcher installs a player update only while
   its session isn't PLAYING (a pure gate next to design 13's). The launcher's own update leaves the player alone.

## 4. Spotify as an engine app in handy

- **Install and state**: Spotify comes from Play (the phone's Play account), never from the catalog. A new per-phone
  app state "engine" (PWA: Allowed / Engine / Not allowed): never hidden or suspended, not on Home or in the drawer
  (`AppFilter`, like Play), never pinned except in setup mode, and its hosts never DNS-blocked. It requires
  `block_activity_start` (the PWA warns without it), so its login, consent and notification taps all end in
  BlockedAppActivity.
- **Setup mode**: Settings -> "Set up Spotify" (Settings gate + PIN), 15 min (`timedWindowActive`, like install mode),
  pins Spotify and the player. The parent logs in the kid's Family member account, approves App Remote's consent from
  the player, follows the container playlists, sets Download, Wi-Fi-only downloads and the explicit filter.
- **Offline**: container playlists per kid ("Vibb – Musikk", "Vibb – Lydbøker"), owned by the parent and curated from
  the PWA with the parent's Web API token (re-authorised every 6 months). The server adds a tile's tracks to its
  container so they download; the tile plays its own album or playlist URI [spike: downloaded tracks play offline from
  another context].
- **Time rules**: no suspension. The launcher pauses the engine's MediaSession whenever the mode forbids music [spike
  item 6]. The design 11 rule cancels Spotify's promo notifications; its ongoing one can't be cancelled.

## 5. Phases

0. **Plumbing**: the `music/` skeleton; CI and `music-v*` releases (`vibb-music.apk`, never "latest"); the provider,
   managed config and the policy's `music` object. **The launcher's catalog row needs the asset filter
   `^kids-launcher-mdm\.apk$`**, or a newer `music-v*` release's APK is offered as the launcher update. Checks: a
   catalog install, kiosk pin, the restrictions-changed broadcast, the provider refusing another signer.
1. **NRK/RSS + own files, end to end**: server tables, fetcher, PWA, covers, uploads; the launcher courier; the
   player's UI, session, downloads, resume, volume cap and mode. Device checks (Jelly Star): 1 h with the screen off,
   the PIN lock LOCKED, the kiosk on and Doze; a call pauses and resumes; unplugging pauses; BT headset buttons;
   airplane mode plays the cache; Wi-Fi-only downloads wait on mobile data; a bedtime edge mid-playback; a launcher
   self-update during playback; a player update deferred while playing; suspension vs audio; battery and MB per hour.
2. **Storytel**: account, shelf, URL minting, downloads, position mirroring. Checks: a 300 MB book over interrupted
   Wi-Fi resumes after its URL expires; the position shows in Storytel's app; no credentials or JWT on the phone.
3. **Spotify**, after the spike (`vibb-spotify-options.md` items 1-7 + the two spikes above): engine state, setup mode,
   server OAuth and container curation, `SpotifySource`, the media pause.
4. **Polish**: the now-playing strip on the PIN lock, a sleep timer, and whatever phase 1 turns up.

## Open questions for the user

1. OK to build the player as its own app in this repo (`music/`, `me.vibb.music`) instead of in palchrb/vibb? Does
   the Pi box keep its own library, or should it later sync from this server?
2. Storytel: OK for the family login to live on the home server (recoverable, unofficial API, risk of an account
   ban), with listening positions mirrored back to Storytel?
3. Which music at bedtime and after the screen-time budget (e.g. only audiobooks, a sleep timer)? Should screen-on time
   in the player count toward the budget?
4. Allow streaming on mobile data (proposed off), and what volume cap: one value for speaker and headphones (e.g. 60 %)?
5. Should the server see listening positions or "now playing" for the PWA, or only what Storytel mirroring needs?
6. Library scope: one family catalog with per-phone ticks (proposed), or a separate library per child?

## User decisions (2026-10-09)

1. **A separate APK in this repo** (`music/`, `me.vibb.music`, its own `music-v*` releases through the catalog), never
   part of the launcher APK. The Pi box keeps its own library for now.
2. **Storytel:** the family login lives on the home server. **No position mirroring back to Storytel at first**; it may
   come later.
3. **Time in the player never counts as screen time.** Bedtime rules: undecided; the app can already be a rule's exempt
   app, and audiobooks-only at bedtime comes later if wanted.
4. **Mobile data:** a server on/off switch per phone decides whether downloads *and* streaming may use mobile data
   (default off). The volume cap is undecided (proposed 60 %, configurable).
5. Undecided; default to the proposal: the server sees no listening positions or "now playing".
6. **One family catalog with per-phone ticks.**
7. **Kid GUI:** to be discussed with the user next, with a clickable mockup, before phase 1 is built.

Also, before any `music-v*` release: the launcher's catalog row must only consider `launcher-v*` releases.

## Kid GUI decisions so far (mockup https://claude.ai/artifact/2np6veF8YP3bJeQCAct9fY, 2026-10-09)

- **Tapping a cover** whose library entry has resume on continues exactly where it stopped. Other entries start from
  the beginning. A **"Liste"** button on the player opens the list of songs, chapters or episodes, like vibb on the
  Pi. **"Sov"** is a sleep timer (15/30/45 min / off). The progress bar has a **draggable thumb for seeking**
  within the track, with the times updating while dragging.
- **Launcher Home** shows a "Spilles nå" card while something plays, like the ongoing-call card.
- **PIN lock while media is actively playing and the screen is on:** a media view comes first. It shows the clock, a
  tile with the album art and the title, a progress bar and prev / play-pause / next, plus Emergency call. A
  **"Lås opp"** button leads to the normal keypad, which has a back arrow and a compact play/pause row. The media view
  is part of the lock: it opens nothing but those controls. Not playing means the keypad as today.
- **Library screen** (user, 2026-10-09):
  - The neighbouring covers peek in at the sides, smaller and dimmed, and tapping one turns to it. They replace the
    arrows.
  - A resumable entry shows where the kid is ("Lydbok · del 3 av 5") with a thin overall progress line.
  - **Category tiles** along the bottom: Alle, Musikk, Eventyr, Lydbøker, Podkast. Round icons with labels, **single
    choice**: a tile shows only that category; tapping it again, or "Alle", shows everything.
  - Each library entry has a category in the PWA. The default comes from the source (Spotify -> Musikk, Storytel ->
    Lydbøker, NRK/RSS -> Podkast, own files -> Musikk). The parent can change it or add categories, each with an icon
    from the fixed set and a colour.
- **Storytel and the source fetching** (user, 2026-10-09): the phone fetches directly from the source, exactly as the
  vibb Pi does: Storytel books from Storytel's CDN, NRK and RSS from their origins. The handy server neither proxies
  nor stores any of it.
  - The server's only role is the library definition. **The Storytel login lives on the phone (like the Pi), but is
    provisioned from the server's library GUI** (user, 2026-10-09):
    - The parent enters it in the PWA, and it reaches the phone through the launcher.
    - The music app logs in and downloads by itself.
    - The server keeps it encrypted (key in `.env`) so a new phone can be provisioned again.
    - It is never shown back in the PWA and never logged.
  - Own uploaded files are the one source that lives on the server, so the phone downloads those from it.
- **Outputs** (user, 2026-10-09): a "Spill av på" button on the player picks the phone's speaker, a Bluetooth device or
  a **Sonos** room. The Sonos logic is ported from vibb `pi/sonosd.py`:
  - UPnP control from the phone as a controller, never a server.
  - Content kinds: `url` (the speaker fetches from the origin: NRK/RSS enclosures and Storytel's signed CDN URL),
    `nrk_program` (the NRK Radio Sonos service, x-sonos-http, sid=277) and `spotify_sharelink` (SoCo ShareLink; Sonos
    owns the queue).
  - The uid -> ip cache, discovery only on a cache miss, a rescan or a failed play.
  - Own uploaded files on Sonos: a short-lived signed URL on the handy server, which the speaker fetches over the LAN.
- **New content**:
  - A "NY" badge on a cover with a new episode or book (e.g. a new book in a synced Storytel series), as on vibb.
  - A bell with a count in the library header opens "Nytt": the new items with a mini cover, the entry name and the
    episode or book title. Tapping one plays it and removes it, and "Fjern alle" clears the list.
- **Offline marks**: each track or episode in the player's list shows a download mark when it is on the phone. The
  list header says "Alle lastet ned" or "N av M lastet ned".
- **The seek bar**: an ordinary thin 4 px line with a 26 px round thumb on top, and a 44 px touch height.
- **The layout**: the carousel sits midway between the header and the category tiles.
- **Categories are parent-defined** (name + an icon from the fixed set + a colour) with defaults per source, chosen per
  library entry.
- **Language**: follows the launcher's language (`launcher_ui.language`, set by the parent), else the phone's.
- **After an app or phone restart** (user, 2026-10-09; as on the Pi):
  - The last played item and its exact position are remembered, and the "Spilles nå" bar under the carousel and on
    launcher Home is back at once, paused. One tap continues.
  - No automatic playback on a phone restart: a phone in a pocket must not start making noise. The Pi auto-resumes
    because powering it on means "play".
- **Carousel dots**: all the same size, and the current one only changes colour.
- **Header** (user, 2026-10-09): no "Musikk" title. On the right are two round buttons in the same style: **search** and
  the **bell**.
  - Search opens a field with live hits over entry titles and track, chapter and episode titles. Tapping a hit plays it,
    like tapping a cover (an entry resumes when resume is on; a track hit starts that track).
- **Unread count on launcher Home**: while "Nytt" holds items, the music app keeps **one silent notification** ("N nye i
  Musikk", `setNumber(N)`, low importance, no sound). Tapping it opens "Nytt".
  - The launcher's existing badge listener shows N on the app's tile; no new launcher code beyond the music app being
    allowed.
  - The notification is cancelled when the list is emptied, by play or by "Fjern alle".
- **System bars**: the mockups omit Android's status bar and the gesture bar.
  - The app draws edge-to-edge with insets like the launcher (`KidInsets`), so about 520 dp of the 569 dp height is
    usable on the Jelly Star.
  - The library screen's flexible spacers absorb that. On the player, the cover may shrink to fit.
