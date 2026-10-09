# 21 - Vibb music, phase 1 (buildable spec)

Architect, 2026-10-09. Sources: design 20's decision sections (they win over its §1-§5), the locked mockup
`docs/design/mockups/vibb-music/` (v12), palchrb/vibb `main` (`pi/vibb/{library,content,bookmarks,storytel}.py`,
`pi/sonosd.py`; MIT: behaviour reimplemented in Kotlin, credited in NOTICE.md and each ported file's header), the
CLAUDE.md files. **In**: server library + PWA; the music app with NRK, RSS and own files; Storytel login plumbing;
speaker + Bluetooth; launcher integration. **Out**: Storytel playback (2), Spotify (3), NRK live, position mirroring.
**Sonos is 1b**, after phase 1's device checks: `sonosd.py` (uid -> ip cache, discovery only on a miss, `url`/
`nrk_program` sid 277, stall and group-migration handling) needs a Kotlin UPnP/SSDP client, SSDP through
`KidVpnService` and a LAN URL for own files (question 3).

## 1. Server
It holds the library *definition* and the parent's own uploads (audio, covers). The phone fetches NRK and RSS (later
Storytel) from the source, like the Pi; the server never proxies or stores them.
### 1.1 Data, migration `0049_music.sql`
- `music_categories`: name (1-20), `icon`/`color` (keys of `testdata/music_icons.json`: ~12 Material Symbols, 8
  colours with white >= 3:1, made by `scripts/material-symbols.sh` like design 14, byte-identical in the music app),
  `sort`, `default_kind`. Seeded Musikk (music), Eventyr, Lydbøker (audiobook), Podkast (podcast); new entries default
  by source (own/spotify music, storytel audiobook, nrk/rss podcast). A category in use can't be deleted.
- `music_entries`: name, `category_id`, `source` (nrk/rss/own; storytel/spotify refused until their phase), `target`
  (normalised URL, unique; NULL for own), `key` (vibb `state_key`: NRK podkast slug, else `sha1(target)[:12]`;
  `own-<id>`), `play_order` (auto/newest_first/oldest_first), `cache` (-1 all, 0 none, 1-100 newest N; default 5; own =
  all), `resume` (default on; off for own), `cover_hash` (parent upload), `sort`. <= 200 entries.
- `music_files`: `entry_id` (CASCADE), path, size (<= 1 GB), sha256, title/artist/album/track_no/duration_ms (tags via
  `lofty`, MIT/Apache, `spawn_blocking`), `art_hash` (embedded picture -> 512 px JPEG), sort, `missing`; <= 300 per
  entry, in track order when every file has a number (vibb), else upload order.
- `device_music_entries` (per-phone ticks). `device_policy`: `music_mobile_data` (0), `music_volume_cap_pct` (NULL =
  off, 40-80, default 60 - question 1), `music_storytel` (0). `music_storytel` singleton (ciphertext, nonce,
  `generation` +1 on every save/clear). `device_status.music_state_json`. `tracked_apps.release_tag_prefix` (1.4).
- Audio in `data/music_files/<entry>/` (`.part`, hashed while streaming, renamed; refused below 1 GB free), never in
  backups (a missing file is flagged and left out). Covers in a `photos::Store` `MUSIC_COVERS` (own prune, backed up).
### 1.2 PWA `/music` (an "Apps | Musikk" switch atop `/apps`; linked from each device's Music card)
- **Add**: paste a link. NRK (`radio.nrk.no/podkast/<slug>[/<ep>]`, `/serie/<slug>[/<programId>]`): one psapi GET of
  `/radio/catalog/{podcast|series}/<slug>`; other http(s): one GET (10 s, <= 5 MB) that must parse as RSS with >= 1
  enclosure; the title becomes the name. This add check is the server's only fetch. "Egne filer" takes multipart
  uploads; what lofty can't read as mp3/m4a/m4b/ogg/opus/flac/wav is a 400 and deleted.
- **Entry**: name, category, order, offline depth (Ingen/1/3/5/10/20/Alle; not own), resume, cover, files, delete.
  **Categories**: name, icon/colour pickers, up/down. **Storytel**: email + password, never prefilled or shown back
  ("Lagret 9. okt" + Fjern), disabled without the key. **Device card `#music`**: the music app switch (the catalog
  row's `toggle_tracked_app`, so allowlisted too), entry ticks (auto-saving mini forms), mobile data, volume cap,
  "Storytel på denne telefonen", the phone's `music_state` (version, library applied, downloads, cache MB, errors).
- **No scroll jump**: every form redirects to its anchor (`#entry-<id>`, `#categories`, `#storytel`, `#music`), new
  templates include `partials/head.html`, a 400 keeps the values with the error by the autofocused field. Changes nudge
  the phones (SSE) and log `security_events` (`music_*`, `storytel_login_saved/cleared/sent`).
### 1.3 Device API
- **Policy** (always; `policy_json_keys_snapshot` / `PolicyResponseCompatTest`): `music: {library_version, mobile_data,
  volume_cap_pct, storytel_generation}`. `library_version` = 16 hex of SHA-256 over this phone's library JSON (NULL
  without ticks); the generation is 0 unless the phone's Storytel switch is on and a login is stored.
- **`GET /api/devices/music/library`**: `ETag` = version, `If-None-Match` -> 304, gzip; this phone's ticked entries,
  their categories, files and covers - not in the policy (size; a bad field must never fail it) nor managed config.
  Pinned by `music_library_snapshot` into `server/testdata/music_library.json` (byte-identical in the music app):
  `{v:1, version, categories:[{id,name,icon,color,sort}], entries:[{id,key,name,category,source,target,order,cache,
  resume,cover,sort}], files:[{id,entry,title,artist,track,duration_ms,size,sha256,art,sort}], covers:[hash]}`.
- `GET .../music/covers/{hash}`, `.../music/files/{id}`: scoped to this phone's ticks (else 404); files via design 13's
  `ServeFile` (Range, strong ETag, If-Match -> 412, 416) + `X-Content-SHA256`. `GET .../music/storytel` ->
  `{generation, email, password}`, `no-store`; 404 when the switch is off or none is stored, 503 without a usable key.
- Status `music_state` (`music_v1`, known fields only like `lock_state`): `{package, version_code, bridge,
  library_version, cache_bytes, downloads_waiting, entry_errors <= 50, storytel}`. Never positions or now playing.
### 1.4 Storytel at rest, catalog rows, tests
- `MUSIC_SECRET_KEY` (base64, 32 bytes) in `.env`; AES-256-GCM (`aes-gcm`), random nonce, AAD `storytel-v1`. A DB
  backup holds no usable password; a lost key = re-enter. Never in a page, log, status or event.
- Before any `music-v*` release `newest_matching_release` honours `release_tag_prefix`; the migration sets the
  launcher row to `launcher-v` (blank asset filter -> `^kids-launcher-mdm\.apk$`) and adds the music row (same repo,
  `music-v`, `^vibb-music\.apk$`, `me.vibb.music`, prereleases off, design 14 default "Musikk"/music_note/peach).
- Tests: snapshots, ETag/304, scoping, Range/If-Match, uploads, the add check on canned bodies, Storytel (no
  plaintext in DB/page/log, generation, 404/503), `music-v*` never the launcher's release, auth, scroll-restore.

## 2. Bridge (launcher <-> music app)
The server is reachable only over the launcher's tsnet, so the launcher is the courier.
- **Managed config** (only the device owner writes it; the system persists it): `v`=1, `managed`, `launcher_package`,
  `language` (`launcher_ui.language`), `mobile_data`, `library_version`, `storytel_generation`. Written in `apply()`
  when it differs, cleared when unmanaged. Too small and too readable for the library, files or a password.
- **A bound service, not a ContentProvider**: a client holding a provider reference (`call()`, a provider fd) dies with
  the provider's process, so the launcher's night self-update would stop a bedtime story; a bound service's death only
  disconnects. `MusicBridgeService`: exported, `permission="${applicationId}.permission.MUSIC_BRIDGE"` (signature, the
  launcher defines it, the music app requests both build names: a debug/release mix fails to bind, not to install);
  caller uid `me.vibb.music[.debug]`. The music app binds its restrictions' package after `hasSigningCertificate`.
- **AIDL** `me/vibb/bridge/IMusicBridge.aidl`, byte-identical in both apps (both suites compare): `version()`;
  `openLibrary(haveVersion)`, `openCover(hash)`, `openFile(id)` -> read-only `ParcelFileDescriptor` or null; `oneway`
  `releaseFile(id, sha256)`, `wantFiles(ids)`, `getStorytelLogin(haveGeneration, IStorytelCallback)`,
  `reportState(Bundle)`, `setNowPlaying(Bundle?)`, `setForeground(IBinder token, inFront)`. Off the main thread.
- **Nudge**: the explicit broadcast `me.vibb.music.action.BRIDGE_CHANGED` carries nothing; its receiver only enqueues
  the sync work. A running app registers for `ACTION_APPLICATION_RESTRICTIONS_CHANGED` and re-reads at every sync.
- **Own files**: the launcher downloads every listed file not handed over; the music app copies it from `openFile`,
  checks size + SHA-256, closes the fd, calls `releaseFile`; the launcher deletes its copy and remembers `id:sha`;
  `wantFiles` (wiped data, reinstall) clears the marks.
- **Storytel**: on `getStorytelLogin` with an older generation the launcher fetches it over tsnet and answers through
  the callback, in memory only. The music app keeps it AES-GCM-encrypted under an Android Keystore key in
  `noBackupFilesDir`, never logged; generation 0 deletes it (phase 2 also the books, vibb `forget_downloads`).
- **Language**: restrictions `language` -> the music app's `LocaleManager.setApplicationLocales` (system = empty);
  `values` + `values-nb` with a TranslationsTest. **"Nytt" count**: the music app's own notification (§4.5).

## 3. Launcher
- **Catalog**: the music row is an ordinary catalog app (install + allowlist via the device card, kiosk pin, badge).
- **Sync** (`MusicSync`, after `WallpaperStore.sync`, only with a music package installed): a new `library_version` ->
  GET (If-None-Match, <= 4 MB, `v == 1`; the launcher reads only `v`, `covers`, `files[].{id,size,sha256}`) stored
  atomically as `filesDir/music/library.json`; covers prefetched like wallpapers; then restrictions and the nudge.
  Failures retry next sync and never touch policy acceptance; `PolicyResponse.music` is nullable (older server).
- **Own-file downloads** reuse design 13's runner: `DownloadRecord.kind` APK | MUSIC_FILE, the same Range/If-Match/
  ETag/412/416/404/backoff/hash rules, `noBackupFilesDir/music_files/`, its gate with NOT_METERED unless
  `music.mobile_data`; done = READY. **Sessions** (`MusicSessions`): `MediaSessionManager.getActiveSessions(
  BadgeListenerService)` + its listener, filtered to the music packages. No notification access = no media surfaces.
- **Home "Spilles nå"** (HomeCard; pure `ui/home/MusicCard.kt`): between the contacts row (or call card) and the grid
  while the live session plays or is paused, else from the last `setNowPlaying` snapshot (CE prefs, never sent to the
  server) shown paused - back at once after a restart. 52 dp art, entry, "Kapittel 3 · 7:46 av 18:30", 44 dp peach
  play/pause. Live: transport controls, a tap opens `sessionActivity`. Snapshot: both open the player with
  `EXTRA_RESUME` [device check: resume without leaving Home]. Ticks 1/s only while shown and playing; never LOCKED.
- **Lock media view** (Lock mockup; pure `lock/LockMedia.kt`): the lock coming up with the screen on while the session
  is PLAYING shows clock, date, a tile (72 dp art, item, entry, 5 dp progress, prev / play-pause / next), "Lås opp" and
  "Nødanrop". "Lås opp" -> the keypad with a back arrow (while a live session exists) and a compact row (32 dp art,
  entry, play/pause). Not playing = keypad. Nothing else opens. `PinLockLayoutTest`: compact-row case, keys >= 48 dp.
- **Time rules**: player time never counts: `setForeground` (token `linkToDeath`, cleared at screen-off and binder
  death) -> `screenTimeCounts(.., musicInFront)`. Rule locks keep today's exempt apps; before `apply()` suspends a music
  package its session is paused. The budget lock adds the music packages to its usable apps (question 2).
- **Volume cap** (pure `volumeCapIndex(max, pct)`): while managed, without override/pause, `STREAM_MUSIC` above the cap
  is set back (`setStreamVolume(.., 0)`) on `VOLUME_CHANGED_ACTION` and route changes; all apps and outputs. Never
  `DISALLOW_ADJUST_VOLUME`. **Updates**: a music app install waits while its session plays (pure
  `installDeferredForPlayback`, retried on the pause); the launcher's own update needs no gate (bound service).
- **Status**: `music_state` = the last `reportState` + the bridge/download view. **Tests**: compat, AIDL identity,
  callers, `MusicCard`, `LockMedia`, `screenTimeCounts`, budget apps, `volumeCapIndex`, download gate, deferral.

## 4. Music app (`music/`)
### 4.1 Project
Own Gradle project beside `launcher/`; `me.vibb.music` (debug `.debug`), minSdk 34, SDK 36, the launcher's AGP/Kotlin,
`-PwarningsAsErrors=true`, GPL-3.0; Compose foundation, Media3 (exoplayer, hls, session), Room, WorkManager, OkHttp;
no Firebase/GMS (`checkReleaseHasNoGoogleServices`); Nunito, night palette. Edge-to-edge, insets once (`KidInsets`):
~520 of 569 dp usable on the Jelly Star, spacers absorb it, the player cover shrinks. Unmanaged: "Ingen bibliotek".
### 4.2 Screens, exactly `Main.dc.html` v12
- **Library**: header (44 dp back "Tilbake til Hjem", search, bell + count); the carousel midway; title, hint, a thin
  overall-progress line for a started resume entry; 8 dp dots (current peach); category tiles (Alle + categories with
  entries here; single choice, re-tap or Alle = all; > 4 scroll); the now-playing bar. Kind: nrk/rss "Podkast", own
  "Egne filer". Hint: resume on and started "<kind> · episode|del|spor N av M", not started "· fortsetter der du
  slapp", off "· starter fra begynnelsen". "NY" while the entry has Nytt items.
- **Carousel motion**: a `HorizontalPager` (wrapping virtual pages when > 1 entry) follows the finger and snaps to the
  nearest cover with a short spring (~250-300 ms). Around the 180 dp cover (radius 24) the neighbours peek 46 dp wide;
  their scale (1 -> 0.73) and alpha (1 -> 0.45) interpolate continuously with the page offset (`graphicsLayer`). A tap
  on a neighbour animates the same way; dots and title/hint cross-fade. With "remove animations" (animator scale 0) it
  jumps without animation. A category change shows index 0 at once. No gesture-exclusion rects.
- **Player** (a cover tap plays, §4.4): back + entry title, cover, marquee item, "Episode|Spor N av M", the seek line
  (4 dp, 26 dp thumb, 44 dp touch, times update while dragging, seek on release), [shuffle] prev / play-pause / next
  [slot], then Liste / Sov / "Spill av på".
- **Shuffle**: a 44 dp toggle left of "previous", peach when on; only for music entries (own files - later Spotify -
  with resume off), else an empty slot keeps play centred. Remembered per entry. On: a permutation with the current
  track first is stored (`DefaultShuffleOrder` from stored indices, survives a restart); "next" = the next unplayed
  track in it; the list shows that order; the queue ends after the last. Off: play order from the current track.
- **Liste**: "Alle lastet ned" / "N av M lastet ned"; rows (number or ✓ when heard, title, "Spilles nå · 14:10" /
  "Hørt · 14:10" / duration, offline mark); a tap plays. **Nytt**: "Fjern alle"; rows (mini cover, "Ny episode" /
  "Nytt spor", item, entry); a tap plays and removes; empty "Ingenting nytt akkurat nå". **Search**: live hits (<= 12)
  over entry and item titles; an entry hit plays like its cover, an item hit starts that item; "Fant ingenting".
  **Sheets**: "Sov om" 15/30/45 min / Av ("Sov · 30 min"); "Spill av på" (§4.6).
- Nothing else: no settings, links, share or web views (an escape scan like `KidScreensEscapeTest`). One line beyond
  the mockup: an item that can't play now shows "Ikke lastet ned - trenger Wi-Fi" and the queue moves on.
### 4.3 Sources (from `content.py`)
- **NRK podkast**: psapi `/radio/catalog/podcast/<slug>/episodes?page=1&pageSize=50&sort=asc` via `_links.next`, <= 100;
  id = last segment of `_links.self.href`; art = smallest `squareImage` >= 300 px; stream = `/playback/manifest/podcast/
  <id>` `playable.assets[0].url`, 8 in parallel; incremental refresh (`sort=desc`, stop at the first known id); psapi
  empty -> `podkast.nrk.no/program/<slug>.rss`. **NRK serie**: the same with `series` and `manifest/program` (HLS);
  `<serie>/<programId>` walks `/playback/metadata/program/<id>` `_links.next`. A failing stored URL is re-resolved once.
- **RSS**: `.rss`/`.xml`, or a body whose first 2 KB start `<?xml` or hold `<rss`; items without an enclosure skipped;
  channel art `itunes:image@href`, else `image/url`; item art `itunes:image`. Item key `sha1(guid)[:12]`, else vibb's
  `sha1(enclosure)[:12]` (deviation: a guid survives a re-hosted file). Tracking prefixes (podtrac, chtbl, pdst.fm,
  op3.dev) are stripped from the fetch URL only (the DNS filter may block them). ETag/If-Modified-Since.
- **Own files** play from the app's copy with their own art. Listings live in Room; **playback never waits for the
  network** (vibb `STALE_OK`); entry errors (`not_found`, `not_feed`, `http_<code>`, `no_items`) go to `reportState`.
### 4.4 Order, queue, resume (from `library.py`, `bookmarks.py`)
- Natural order: NRK podkast and RSS newest first, NRK serie and own files oldest first; an explicit other reverses.
- Positions per (entry key, item key) in Room: on pause, stop, item change, every 15 s and in `onTaskRemoved`. Within
  20 s of the end an item is done (stored as its duration, read as 0); a position <= 20 s seeks to 0.
- Cover tap, resume on: the play order rotated to the bookmarked item (`rotate_to_bookmark`) at its position;
  finishing item N moves the bookmark to N+1 at 0; a queue played to its end drops the bookmark, keeping done marks.
  Resume off: the first item at 0. A list, search or Nytt pick starts that item at its own position when resume is on.
- The last played item and position are kept globally: after an app or phone restart the now-playing bar (and the
  launcher's snapshot) is back **paused**. Nothing ever auto-plays.
### 4.5 Offline, sync, new content
- **Sync** (unique WorkManager work): every 6 h, on the nudge (15 s settle, as vibb), at start when > 6 h old: bridge
  (library, covers, files, login, restrictions) -> listings -> downloads -> prune -> `reportState`.
- **Downloads**: Media3 `DownloadManager` + `SimpleCache` (`NoOpCacheEvictor`), `customCacheKey` = item key,
  progressive and HLS. Wanted: the newest N by publication (vibb), all for -1, none for 0, plus the bookmarked item and
  the next in play order. NOT_METERED unless `mobile_data`, storage not low, paused while roaming. Items beyond the
  depth and removed entries are deleted (`prune_cache`). Streams aren't cached.
- **New** (`_mark_new_seen`): an entry's first listing acknowledges all; later, unseen item keys join the Nytt tray
  (Room, newest first, <= 30, dropped with their entry) and leave it when they start playing by any path or on "Fjern
  alle". While it holds N > 0: one LOW-channel notification "N nye i Musikk", `setNumber(N)`, silent, clearable, not
  ongoing (what the badge listener counts); the tap opens Nytt; cancelled when empty; a swipe only hides it.
### 4.6 Player, tests
One Media3 `MediaSessionService` (FGS `mediaPlayback`); audio focus (a call pauses and resumes), becoming-noisy pauses,
headset/AVRCP buttons via the session; metadata carries `artworkData` (<= 300 px) for the launcher. An uncached stream
needs a physical transport, no roaming, NOT_METERED unless `mobile_data` (design 13's gate, ported pure). **Outputs**:
"Telefonen" plus connected A2DP/LE/wired devices (`AudioManager.getDevices`), picked with
`ExoPlayer.setPreferredAudioDevice` (default: system routing) until that device goes; no pairing here. **Sleep**:
15/30/45 min, then a ~10 s fade, pause, position saved; a manual pause cancels it. The volume cap is the launcher's.
`setNowPlaying` on play/pause/item change (entry, item, position, duration, art <= 64 KB; cleared at a queue's end),
`setForeground` from onStart/onStop; no time-rule logic in the app. **Tests**: the shared library JSON (a bad entry is
dropped, not the library), icon JSON and AIDL identity, psapi/RSS parsing on recorded fixtures, play order, resume,
cache depth, new content, shuffle, sleep, network gate, translations, the escape scan.

## 5. CI and release
`music.yml` like `launcher.yml`: `music-build` only when `music/**`, the workflow or the shared AIDL/testdata changed
(debug, unsigned release, lint, unit tests); the always-run `music-ci` goes into branch protection. `music-vX.Y.Z`
(`-rc.N` = prerelease) on master: build (versionCode `X*1_000_000+Y*1_000+Z`, RC minus 1) -> sign in the `release`
environment with **the launcher's key** (the signature permission needs one signer; add `music-v*` to its tag rule; same
`RELEASE_CERT_SHA256` check) -> publish `vibb-music.apk` + `.sha256`, `make_latest: false`. NOTICE.md: vibb (MIT),
Media3 and Material Symbols (Apache-2.0), Nunito (OFL). Root CLAUDE.md: `music/` in Layout and CI.

## 6. Steps (each a commit set with its tests)
1. **Server library** (+ the launcher's nullable `PolicyResponse.music` and compat test): §1. Checks: the PWA on a
   phone (no jump on any form), NRK/RSS/own entries, `curl` the library (ETag/304) and a file with Range, the launcher
   row still on the newest `launcher-v*` after a `music-v0.0.1-rc.1` in a fork.
2. **Music app core** (`music/`, `music.yml`): §4, fed by a debug-only loader (`src/debug`, JSON + files via adb; a
   release dex check like `DebugHookAbsentTest`). Emulator: every screen at 320x569 dp in nb/en, the carousel (drag,
   snap, neighbour tap, animations off), resume/shuffle/sleep, airplane mode, restart paused. Jelly Star: 1 h screen
   off in Doze, a call pauses/resumes, unplug, BT buttons, speaker vs BT, downloads wait on mobile data, battery, MB/h.
3. **Bridge, catalog, downloads** (launcher + music app + server status): §2, §3 sync/own files/updates. Checks:
   catalog install + kiosk pin, a language switch via restrictions, another signer refused, own files over interrupted
   Wi-Fi, login set -> stored / cleared -> deleted, a launcher self-update while playing, a music update deferred.
4. **Media surfaces and time** (launcher): Home card, lock view, volume cap, screen-time lease, budget apps,
   pause-before-suspend. Checks: the card live and after a reboot, both lock stages in both kiosk modes, a bedtime edge
   mid-playback, suspension vs audio, clamp latency incl. BT absolute volume, the Nytt badge. Then `music-v0.1.0`.

## 7. Open questions
1. Volume cap: one value for the whole phone (all apps, speaker and headphones), default 60 %? vibb caps only its
   speaker; separate speaker/headphone caps would be a second column.
2. Once the day's screen-time budget is used up, may music still play (spec: yes, it never counts), or is it suspended?
3. Sonos (1b): may the handy server answer on the home LAN (short-lived signed URLs) so speakers can play own files,
   or are own files phone-only?

## User answers (2026-10-09)

- **Volume cap: off by default.** The parent can set it per phone in the PWA, from 100 % down to 60 %, applying to the
  speaker and headphones alike.
- **Screen time:** listening never counts, and music keeps playing after the budget is used up. Bedtime and school
  stay governed by the time rules (the app as a rule's exempt app).
- **Own files are always downloaded to the phone, so the phone has everything locally** (design 13's machinery,
  Wi-Fi-only unless the per-phone mobile-data switch allows more). For Sonos (phase 1b), the speaker needs an HTTP
  source, and the phone never serves anything. So: a short-lived signed URL on the home server over the LAN
  (recommended), pending the user's confirmation.
