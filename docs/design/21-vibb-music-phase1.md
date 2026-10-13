# 21 - Vibb music, phase 1 (buildable spec)

Architect, 2026-10-09. Sources: design 20's decision sections (they win over its §1-§5), the locked mockup
`docs/design/mockups/vibb-music/` (v12), palchrb/vibb `main` (`pi/vibb/{library,content,bookmarks,storytel}.py`,
`pi/sonosd.py`; MIT: behaviour reimplemented in Kotlin, credited in NOTICE.md and each ported file's header), the
CLAUDE.md files. **In**: server library + PWA; the music app with NRK, RSS and own files; Storytel login plumbing;
speaker + Bluetooth; launcher integration. **Out**: Storytel playback (2: **now design 26b**, `26b-storytel.md`, the
buildable spec and each step's status; built on branch `music-2-storytel`, to be released as `music-v0.3.0`), Spotify
(3), NRK live, position mirroring.
**Sonos is 1b**, after phase 1's device checks: `sonosd.py` (uid -> ip cache, discovery only on a miss, `url`/
`nrk_program` sid 277, stall and group-migration handling) needs a Kotlin UPnP/SSDP client, SSDP through
`KidVpnService` and a LAN URL for own files (question 3). **Now design 26a** (`26a-sonos.md`, the buildable spec
and each step's status; built on branch `music-1b-sonos`, to be released as `music-v0.2.0`): its own UPnP client,
SSDP beside the VPN (which routes only its own address), and own files on Sonos left out ("Decisions after QA
review" below).

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
  `releaseFile(id, sha256)`, `getStorytelLogin(haveGeneration, IStorytelCallback)`,
  `reportState(Bundle)`, `setNowPlaying(Bundle?)`, `setForeground(IBinder token, inFront)`. Off the main thread.
- **Nudge**: the explicit broadcast `me.vibb.music.action.BRIDGE_CHANGED` carries nothing; its receiver only enqueues
  the sync work. A running app registers for `ACTION_APPLICATION_RESTRICTIONS_CHANGED` and re-reads at every sync.
- **Own files**: the launcher downloads every listed file not handed over; the music app copies it from `openFile`,
  checks size + SHA-256, closes the fd, calls `releaseFile`; the launcher deletes its copy and remembers `id:sha`.
  The music app's `reportState` names the files it holds, which replace those marks (wiped data, a reinstall: the
  missing ones are fetched again). `wantFiles` was dropped in step 3's fix round (qa-21-step3-code S1).
- **Storytel**: on `getStorytelLogin` with an older generation the launcher fetches it over tsnet and answers through
  the callback, in memory only. The music app keeps it AES-GCM-encrypted under an Android Keystore key in
  `noBackupFilesDir`, never logged; generation 0 deletes it (phase 2 also the books, vibb `forget_downloads`).
- **Language**: restrictions `language` -> the music app's `LocaleManager.setApplicationLocales` (system = empty);
  `values` + `values-nb` with a TranslationsTest. **"Nytt" count**: the music app's own notification (§4.5).

## 3. Launcher
- **Catalog**: the music row is an ordinary catalog app (install + allowlist via the device card, kiosk pin, badge).
- **Sync** (`MusicSync`, after `WallpaperStore.sync`, only with a music package installed): inside the sync only the
  managed config (no network) and its nudge. The fetch runs after the sync in its own one-at-a-time runner
  (`MusicCourier`, changed 2026-10-11 after qa-21-step3-code M5: a big first fill must never hold a parent's lock or
  ring behind the sync lock): a new `library_version` -> GET (If-None-Match, <= 4 MB, `v == 1`; the launcher reads
  only `v`, `covers`, `files[].{id,size,sha256}`, `entries[].{id,items}`) stored atomically as
  `filesDir/music/library.json`; the changed listings; covers; then the nudge and the own files. Failures retry after
  the next sync and never touch policy acceptance; `PolicyResponse.music` is nullable (older server).
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
  depth and removed entries are deleted (`prune_cache`). Streams aren't cached. *(Changed by design 30
  (`30-music-streaming-data.md`): two mobile-data switches, a stream written to the cache as it plays above depth 0,
  played extras kept within the parent's storage limit and a free floor.)*
- **New** (`_mark_new_seen`): an entry's first listing acknowledges all (and design 29: a new entry's one item);
  later, unseen item keys join the Nytt tray
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

## QA review (2026-10-09)
1. **High, FGS**: the music app may not start `DownloadService` (dataSync FGS) from a nudge or job (Android 12+; 6 h a
   day on 15+): listings + downloads as WorkManager jobs, Range per item; `mediaPlayback` only on a tap or a command.
2. **High, HLS**: Media3 has no `customCacheKey` for HLS: a `CacheKeyFactory` (item key + segment) survives re-resolves.
3. **High, only copy**: missing audio (never backed up) "left out" makes phones prune it after a Pi restore: list it as
   `missing`; prune only what a parsed library dropped; a query error sends `music: null`, never a null version.
4. **Medium, library**: hash it without its own `version`; refuse ticks over ~3 MB per phone (60k files ~15 MB).
5. **Medium, screen time** is safe only if `setForeground` follows `onTopResumedActivityChanged`, false in multi-window
   (as `freeScreenSharesScreen`), and the app never keeps the screen on, so the auto-lock bounds a left-on player.
6. **Medium, badge**: `badgeCounts` skips `EXTRA_MEDIA_SESSION` (paused = counted); DO-grant `POST_NOTIFICATIONS`.
7. **Medium, bridge**: `<queries>` for both launcher names; rebind on `onBindingDied` (self-update; the fence skips the
   app: controllable, not HOME); generations compare `!=` (DB restore); CI debug APKs differ in key: build both locally.
8. **Medium, launcher**: `sessionActivity` needs `ALLOW_IF_VISIBLE` (as `VoipCalls`); listener-free fallbacks: pause on
   `ACTION_MY_PACKAGE_SUSPENDED`, defer by the last `setNowPlaying`; own files one ahead of handover, APKs first.
9. **Medium, sources**: `sort=asc` + <= 100 keeps a long podcast's oldest 100 (as vibb): podkast walks `sort=desc`;
   listings obey the mobile-data/roaming gate; untagged own files sort by filename (vibb), not upload order.
10. **Medium, server**: old `.env`s never get `MUSIC_SECRET_KEY`: the server writes a 0600 key file outside `data/`,
    fingerprint in the AAD (changed key = 503, re-enter); 0049 sets `launcher-v` only where the cached tag has it.
11. **Steps/CI**: deploy the step-1 server before a stable `music-v*` (old servers offer it as the launcher update);
    step 3 needs a signed music RC; launcher `buildFeatures.aidl`; licence assets; `PinLockLayoutTest` stays >= 52 dp.
12. **Open questions**: 1 - §1.1/§3: NULL default, Off/90/80/70/60; 2 - as specced; 3 - yes in 1b: a LAN-only listener
    for HMAC-signed, <= 1 h, Range own-file URLs (the main server stays on the tailnet).

## Decisions after QA review (2026-10-09)

- All 12 QA findings are accepted as written and override the matching parts of §1-§6. In particular:
  - downloads and listings run as WorkManager jobs, with the `mediaPlayback` FGS only on a tap or command (#1);
  - an HLS `CacheKeyFactory` (#2);
  - missing own files listed as `missing`, never pruned by a failed query (#3);
  - the library is hashed without its own version, with ~3 MB per phone (#4);
  - the screen-time lease follows top-resumed and multi-window, and the screen is never kept on (#5);
  - the bridge gets `<queries>`, rebinds and `!=` generation checks (#7);
  - the server writes its own 0600 music key file when `.env` has none (#10);
  - the deploy order: the step-1 server before any stable `music-v*` (#11).
- Volume cap (user): NULL/off by default; the PWA offers Off / 90 / 80 / 70 / 60 % per phone, for all output.
- Budget (user): music may play after the screen-time budget is used up and never counts. Bedtime and school follow
  the time rules.
- Sonos own files (user, 2026-10-09): **out of 1b.** Sonos plays NRK/RSS/Storytel (and Spotify in phase 3) from the
  origin. Own files on Sonos come later only if missed, via a LAN-only listener on the server for signed own-file URLs
  (QA #12), never the phone.

## Step 1 implementation status (2026-10-09)

Built and tested in the sandbox (server 0.21.0 and the launcher DTO); not yet checked on the Pi or a phone.
- **Server**: migration 0049 (new tables and defaulted columns only, plus the two guarded catalog updates - tested on a
  database migrated to 0048 with data in it), `/music` + `/music/entries/{id}` + the device card `#music`, the policy's
  `music` object, `GET /api/devices/music/{library,covers/{hash},files/{id},storytel}`, the status `music_state`
  (known fields only, shown on the card), SSE nudges, `music_*`/`storytel_login_*` events. `server/CLAUDE.md` ("Vibb
  music library") and `server/DEPLOY.md` (order, key file, own files outside backups) describe it.
- **Launcher**: `PolicyResponse.music` is a nullable raw `JsonElement` (nothing in it can fail the policy), unused;
  `PolicyResponseCompatTest` covers today's shape, `null`, missing and odd shapes and the cache round trip.
- **Checks done**: `cargo test` (279), fmt, clippy (no new warnings); the launcher's unit tests and debug build with
  `-PwarningsAsErrors=true`; the pages rendered at 360 px wide in light and dark (no horizontal scroll; a 400 scrolls
  to its focused field). **Still to do (§6 step 1)**: the PWA on a phone, real NRK/RSS adds, `curl` of the library
  (ETag/304) and of a file with Range on the Pi, the launcher row after a `music-v0.0.1-rc.1` in a fork.

Choices made while building (none changes a decision above):
1. The PWA stays English like every other page ("Apps | Music", "Own files", "Saved 9 Oct 2026" + Remove, "Storytel on
   this phone"); only the seeded category names are Norwegian, since the kid sees them.
2. The library lists `files[].missing` (QA #3) and is a 404 while nothing is ticked (the policy then says
   `library_version: null`). `testdata/music_library.json` is the served document pretty-printed in wire order.
3. Release prefixes: a catalog row with `release_tag_prefix` takes only its own tags; one **without** never takes
   `music-v*` (protects a fresh install's hand-made launcher row, which 0049 can't recognise). The prefix is editable on
   the app's page and the add form; `/music` has "Add the music app to the catalog" for a server where 0049 found no
   `launcher-v*` row. The music row starts enabled, so the hourly sync reports "no release" until the first `music-v*`.
4. The Storytel key: `MUSIC_SECRET_KEY`, else `data/keys/music-secret.key` (`MUSIC_SECRET_KEY_FILE` moves it),
   created 0600 at startup in a 0700 directory no backup copies; `music_storytel.key_fingerprint` lets the page say
   "enter it again" after a key change. The login is one sealed JSON (`email`, `password`).
5. Entries get up/down buttons on `/music` (the carousel order; not in §1.2). Categories' `default_kind` isn't
   editable; with the matching category deleted a new entry goes to the first one.
6. Own files are stored as `data/music_files/<entry>/<random>.<ext>` with the extension of what lofty found; their
   embedded art and the parent's covers are 512 px square JPEGs (`photos::process`, as contact photos).
7. The 3 MB guard covers ticks (refused, `?music_notice=too_big#music`) and uploads (the new files are taken back);
   renaming a category or an entry isn't checked (a few bytes).

## Step 2 implementation status (2026-10-09)

Built and tested in the sandbox on branch `music-step2` (`music/`, `.github/workflows/music.yml`); not run on an
emulator or a phone yet (no KVM here). `music/CLAUDE.md` describes the code.
- **In**: the library and listing model (lenient, from the shared JSON), Room (listings, positions, bookmarks, Nytt,
  seen keys, downloads, item errors, shuffle, the last queue), the sync and downloads as WorkManager jobs, keep/prune
  on the server's lists (21b §6), NY/Nytt and the "N nye i Musikk" notification, the report for step 3
  (`filesDir/music/report.json`: `version_code`, `library_version`, `cache_bytes`, `downloads_waiting`,
  `entry_errors`, `item_errors`, `downloads`), the player service (Media3 session, focus, noisy, buttons, sleep,
  outputs, stream errors, the queue rules), the screens of `Main.dc.html` v12 in nb/en, the debug feed
  (`src/debug`, docs/testing/emulator.md §6f) and its release check, `music.yml`.
- **Tests**: 106 JVM tests - the three shared files byte-identical and parsed; vibb's episode_resume, resume_overlap
  (case 4), new_badge, cache_prune, offline_browse, offline_keep_all, now_playing (1-5, 8-12), prev_long_form,
  mpv_next_wrap, mpv_prev, session_window (the phone's part), ui_carousel_slide (the index rules) and cat_carousel
  ported; keep/prune with withdrawn copies and the bookmark; item errors from downloads and streams; the gate, HLS
  keys, the address guard; translations, the escape scan, the debug feed outside `src/debug`, the glyphs.
- **Checks done**: `./gradlew assembleDebug assembleRelease testDebugUnitTest -PwarningsAsErrors=true` and
  `./gradlew build` (lint: 0 errors); the release APK is 4.6 MB, R8-minified, without the debug feed or Google
  services. `scripts/material-symbols.sh` now also writes the music app's glyphs (no change elsewhere).

Readings and deviations:
1. **Compose** (foundation only, no Material), as §4.1 says - the launcher uses Views, but the specified carousel is a
   `HorizontalPager` with `graphicsLayer`.
2. **Downloads** run Media3's `ProgressiveDownloader`/`HlsDownloader` item by item inside the WorkManager job (QA #1),
   not `DownloadManager`; Room's `downloads` is the index. An HLS item fetches one rendition (the best up to
   192 kbit/s, else the lowest) and plays its copy with the same stream key; its cache key is the item plus the
   resource's path relative to the playlist, so a re-resolved URL hits (QA #2).
3. **Shuffle** keeps the permutation as the stored queue (Room's now-playing row) instead of ExoPlayer's shuffle mode
   with `DefaultShuffleOrder`: the same behaviour, and it survives a restart. A cover tap on a shuffled entry starts
   a new permutation; a list pick continues in the shown order.
4. **Art**: the session carries `artworkUri` to a ≤ 300 px JPEG in the cache dir, not inline `artworkData` (the
   session sends the whole queue's metadata to controllers; Media3 loads the bitmap for the platform session the
   launcher reads). Episode art URLs aren't fetched: the entry's cover (or an own file's embedded art) everywhere,
   else the category colour with its glyph.
5. **Keep set**: the bookmarked item and the next one are kept at every depth above 0. **Depth 0 ("Ingen") keeps
   nothing** - everything streams, the item in progress too (the user's decision after QA, as vibb's cache 0; this
   reverses the first reading, "plus" even at 0).
6. **Queue ends**: next at the last item wraps to the first (vibb), but a queue played to its end stops, drops the
   bookmark and the partial positions (vibb's `clear_state`) and hides the now-playing bar. A tap on the cover of the
   entry already playing or paused in this session continues it, resume off included (the user's decision, as vibb's
   tile press); resume off applies when the entry starts fresh (after another entry, or after a restart).
7. **Errors**: every failed download or stream of a listed url is recorded (`http_<code>`, `bad_media` for an HTML
   answer or unparsable media, else `network`): the latest per item, at most 50, cleared when the item plays or
   downloads, its listing version changes or its entry leaves. Retries are per item: a new url at once, a 4xx (not
   408/429) after a day and then daily, anything else after 2 min doubling to 6 h. "Ikke lastet ned - trenger
   Wi-Fi" when there is no network for a stream, else "Kan ikke spilles nå"; the queue moves on.
8. **Address guard** (QA Low 14 of step 1c, 21b §2.7): audio goes through OkHttp; for a public-only entry its DNS
   drops private answers and a network interceptor refuses a private connected address, on every redirect hop;
   a refusal is the existing code `network`. Every entry is public-only unless the library marks it `lan` (an RSS
   feed on the LAN or tailnet; added to the contract in the fix round, the user's decision).
9. Until step 3, `mobile_data` comes from the debug feed's `config.json` and the language from the system (or
   `cmd locale set-app-locales`). "Ingen bibliotek" also shows when no entry has anything to play.
10. More than 20 entries show a window of 20 equal dots around the current one.

Emulator (21 §6 step 2, emulator.md §6f): every screen at 320x569 dp in nb and en; the carousel (drag, snap, a
neighbour tap, a tap during a slide, "Remove animations"); resume, shuffle, sleep (fade, a manual pause cancels it);
airplane mode; restart -> the bar back paused, one tap continues; that `adb push` into the app's external dir
works; Nytt and its notification; the output sheet. **Jelly Star**: 1 h screen off in Doze; a call pauses and
resumes (with the 3 s overlap); unplugging pauses; BT buttons, also play after a restart (playback resumption);
speaker vs BT; downloads wait on mobile data; an NRK serie (HLS) downloaded and played offline; battery and MB/h.

The `release` environment's deployment rule must allow `music-v*` tags before the first music release.

### Fix round after QA's code review (`qa-21-step2-code.md`, 2026-10-09)

All findings are fixed except as noted; 117 JVM tests (11 new), the server's 361 with `lan`.
- **User decisions**: the library entries carry `lan` (server and app in one commit, both snapshots); depth 0 keeps
  nothing; a cover tap on the loaded entry continues it (readings 5, 6 and 8 above).
- **H1**: an entry or file counts as removed only when the library no longer names its id; one the app can't read is
  hidden with its state kept, and fields degrade (an unknown order is `auto`, a bad cover or version none). Positions
  and bookmarks are never pruned (vibb keeps its state files).
- **M1/M4**: per-item retries (above); the download job re-plans until nothing new is wanted, ends without a backoff
  when the network goes (its successor waits on the constraints), and a sync replaces a waiting job or follows a
  running one. A 416 drops the partial copy and fetches once more from the start.
- **M2**: `KidMediaButtonReceiver` starts the service for a media button only when a stored queue exists (a pref
  written with the now-playing row). **M3**: headphones arriving while the phone's speaker is chosen take the sound.
  **M5**: a listing without an `items` array, or with fewer than half its items readable, is refused (`bad_listing`,
  the old one stays). **M6**: next, previous, seek and shuffle before play load the stored queue paused first.
- **Low**: done marks from the player's duration, and the bookmark moves on when the next item can't play (1);
  `clear_state` at a queue's end (2); the resume overlap only on the interrupted item, the music beat for resume-off
  entries (3); the Nytt notification posted again when the system no longer shows it, a swipe recorded by its delete
  intent (5); one cache-key factory per item (6, S3); a partial copy from another url starts over (7); a broken copy
  is dropped so the next sync fetches it (8); item errors cleared (9); NRK/RSS audio reads http(s) only (10); the
  snapshot built off the main thread, art made on the writer thread, `WhileSubscribed`, the position tick only while
  the screen is up, no library parse in a constructor (11); the seek thumb holds after a release, slider semantics
  (12); the title fades only on another entry (13); no play circle in Nytt (14); "Kan ikke spilles nå" (15); back from
  the player lands on the playing entry and its category (16); unique temp files and a lock for the report (17);
  `WAKE_MODE_LOCAL` for copies, `CONTENT_TYPE_SPEECH` for resume entries (18); vibb's MIT text in
  `assets/licenses/MIT-vibb.txt`, NOTICE's icon line, the debug feed's test name (19).
- **Simplifications** accepted: S1 (no position settle - ExoPlayer reports the seek target at once), S2 (no sync
  counter; the latest error per item), S3 (above), S4 (`playedFromTray` is what the player uses).
- **Tests added for QA's gaps**: what a save, a natural end and a queue end store (incl. no listed duration), the
  carousel tap rule, the guard on a local socket (a rebinding name, an IP literal, a LAN client), unknown fields,
  degraded fields, refused listings and libraries, a hidden entry keeping its copies.
- **Not done, with reason**: now_playing case 12's 12 s bound on "playing" (Media3 reports buffering itself; the
  button shows the intent); the service's transition wiring stays untested on the JVM (its decisions are pure and
  tested; the wiring is for the emulator). `isReturnDefaultValues` stays: ReportTest builds a `PlaybackException`.
- The Room schema stays version 1, changed in place: no build of the app was installed anywhere yet.
- Device checks added: BT/car-kit PLAY after a queue ended does nothing (no crash); choose "Telefonen", then plug in
  headphones; seek/next before play after a restart; the Nytt notification after a reboot.
- Device checks added (2026-10-11, from the vibb `pipewire` drift check):
  - **downloads while playing over Bluetooth on the Jelly Star: listen for stutter.** vibb holds off downloads while
    anything plays (radio contention on the Pi Zero); if the phone stutters, that is the fallback.
  - **a Bluetooth headset that drops out mid-play and comes back**: playback stays paused, unless the headset itself
    sends PLAY on reconnect (then it resumes like a button press). No automatic resume of our own (design 20).

## Step 3 implementation status (2026-10-10)

Built and tested in the sandbox on `music-step2` (launcher, music app; no server change); not run on an emulator or a
phone. The launcher's part is a new package `com.kidslauncher.mdm.music` with small hooks elsewhere (the sync, the
status DTO and capability `music_v1`, the manifest, one check in `AppDownloads`).
- **Bridge (§2, 21b §5)**: `me/vibb/bridge/IMusicBridge.aidl` and `IStorytelCallback.aidl`, byte-identical in both
  apps (both suites compare), all of §2's methods plus `listingVersion`/`openListing` at the end. The launcher's
  `MusicBridgeService` is exported behind its own signature permission `<applicationId>.permission.MUSIC_BRIDGE`
  and checks the caller's package on every call; the music app requests both build names' permission, has
  `<queries>` for both launchers, and binds only the restrictions' launcher when it is a launcher name signed with
  its own certificate (`hasSigningCertificate`).
- **Launcher sync (§3, 21b §5)**: `MusicSync` after the wallpapers, only with a music app installed: the library on
  a new `library_version` (If-None-Match, ≤ 4 MB, `v == 1`), the listings whose version changed (≤ 2 MB, any version
  stored, the unnamed ones deleted), covers (hash-checked), own files queued, then the managed config (`v`,
  `managed`, `launcher_package`, `language`, `mobile_data`, `library_version`, `storytel_generation`; written when it
  differs, cleared without a policy) and one nudge (`BRIDGE_CHANGED`) when anything changed. A failure keeps what
  is stored; `PolicyResponse.music` stays raw and nullable.
- **Own files**: one ahead of the handover, after the catalog's APKs, design 13's rules (Range + the first ETag as
  If-Match, 412/416/404, size + SHA-256, NOT_METERED unless `music.mobile_data`, never roaming, room); the music app
  copies from `openFile`, checks size and SHA-256, closes, `releaseFile`s; its report names the files it holds.
- **Storytel**: the launcher fetches the login when the music app's generation isn't the policy's (`!=`) and
  answers through the callback in memory only (404 = generation 0); the music app keeps it AES-GCM-sealed under an
  Android Keystore key in noBackupFilesDir, deletes it at generation 0, and reports `storytel` "ok"/"none".
- **Updates and status**: a music app update waits while the last `setNowPlaying` says "playing" (a `DEFER` outcome
  in `AppDownloads`' install; the pause asks for a run); `music_state` = the music app's last `reportState` (known
  keys) + the package, version code and `bridge` ("bound" while reports arrive, else "unbound").
- **Music app**: the bridge part runs first in the sync and the report goes back at its end; the managed config sets
  the language (`LocaleManager`) and mobile data; `BRIDGE_CHANGED` and the restrictions-changed broadcast start a
  sync; `LiveBridge` (held by the player and the screens, rebound after the launcher's own update) sends
  `setNowPlaying` (play/pause/item change, art ≤ 64 KB, null at a queue's end) and `setForeground` (the top resumed
  activity, never in multi-window, its token dying with the process); `MY_PACKAGE_SUSPENDED` pauses (QA #8's
  fallback). The debug feed still works and doesn't need the launcher.
- **Tests**: launcher `MusicPlanTest` (AIDL identity, the signature permission, the caller check, policy and library
  reading on the shared snapshots, listings, one-ahead own files, the managed config's keys, the deferral,
  `music_state`); music `BridgeTest` (AIDL identity, the config, another signer refused, nothing ticked, listings,
  the sealed login) and the report's `storytel`.

Readings and deviations:
1. **Own files have their own small runner** (`MusicFileDownloads`) built on design 13's pure rules, not new
   `DownloadRecord` kinds inside `AppDownloads`: design 22 L1 rewrites that runner's transport, and the music files
   need neither installs nor release tags. It has no network callback: a run starts at each sync, handover or
   `wantFiles`, and stops when the network stops qualifying. Its room check is design 13's (twice the file). (The
   fix round adds the gate during the copy, the hash-failure limit and the wake-lock renewal; `wantFiles` is gone.)
2. **All music server calls sit behind `MusicServer`** (Retrofit over the tsnet proxy, the bearer), for L1.
3. **`reportState` also names the own files the music app holds** (`held_files`, `held_sha256`; never sent to the
   server). They replace the launcher's handed-over marks, so a launcher reinstall or files fed another way never
   cause a second download. (Corrected in the fix round: the first version didn't do what it claimed for a stale
   "ready" file - QA M1; now a ready file the app holds is deleted at the report and never blocks the next one.)
4. **The nudge goes only when something changed** (library, listing, cover, the managed config, a file ready), not at
   every sync.
5. **Nothing ticked**: the launcher deletes its library and the managed config says `library_version` ""; the music app
   then applies an empty library (the parent's explicit choice; an unmanaged phone never does).
6. **The managed config is written by `MusicSync`** (after the library, from the enforced policy), not inside
   `AppEnforcer.apply()`: one place, and `apply()` stays untouched.
7. The launcher stores `setForeground` and `setNowPlaying` now; their readers (screen time, the Home card, the lock
   view) and QA #6 (the badge, the POST_NOTIFICATIONS grant) are step 4.
8. Local build note: the sandbox's SDK `aidl` is x86_64 only; it now uses the aarch64 `aidl` from the same third-party
   tools as the aapt2 override (not in the repo; CI's x86_64 runners are unaffected).

Device checks (21 §6 step 3): a catalog install of the music app and its kiosk pin; a language change in the PWA reaching
the music app; a music app signed with another key isn't bound; own files over an interrupted Wi-Fi (resumed, one
ahead of the handover); a Storytel login set -> stored ("ok" on the card) / cleared -> deleted; a launcher self-update
while playing (the music keeps playing, the bridge comes back); a music update deferred while playing and installed
after the pause; `BRIDGE_CHANGED` and a restrictions change start a sync; the device card shows the music app with no
bridge warning.

### Fix round after QA's code review (`qa-21-step3-code.md`, 2026-10-11)

All findings are fixed; launcher 18 + 8 music tests (`MusicPlanTest`, `MusicFileDownloadsTest`), music 8 in
`BridgeTest`, server +2 (`music_state` end to end, "Music app not installed").
- **H1**: the managed config holds only Boolean, Int and String - `storytel_generation` is an Int (clamped), and
  writing anything else throws. A test checks every value `restrictionsFor` produces; `BridgeTest` feeds Ints.
- **H2**: the own-file copy asks the gate again every 1 MB (`OwnFileFetch`): a download started on Wi-Fi stops on
  mobile data or while roaming, keeping its partial and ETag; the wake lock is renewed in the same loop.
- **M1**: a ready file the music app reports as held is deleted and never blocks the next (`nextOwnFile`,
  `reportState`). **M2**: a deferred music update is skipped for the run (`nextDownload`), the others go on.
  **M3**: listing versions come from an index the courier writes with each listing, never from parsing them.
  **M4**: only a JSON `null` `library_version` means "nothing ticked"; any malformed `music` field keeps what is there.
  **M5**: the fetch left the sync lock (§3 above).
- **Low**: two hash failures skip a file for a day (1); "playing" is measured from the last `setNowPlaying` and
  cleared when the player's process dies (2); the player's end of life sends its snapshot paused, not null (3);
  `LiveBridge` retries a bind (2 s, 10 s, 60 s) and on a config change (4); a deferred install clears its
  "Installing" notice (5); a nudge never cancels a running sync and cancellations are rethrown (6); the suspended
  receiver isn't exported and the nudge is honoured only from a launcher (`setShareIdentityEnabled`,
  `sentFromPackage`) (7); `@Streaming` for the library and listings (8); one failed request no longer stops the fetch
  (9); a new music app gets a sync at once and the nudge reaches a never-opened app (10); `mobile_data` is read live
  (11); the launcher deletes its music files when no music app is installed (12); the device card says "Music app
  not installed." (13, server).
- **Simplifications** accepted: `wantFiles` dropped from both apps and the AIDL (1), `mobile_data` read live and the
  language applied in one place (2), a pause asks for a download run only when an install waits (3).
- **Test gaps closed**: the config's value types; `OwnFileFetch` with a fake `MusicServer` (200/206/412/416/404/5xx,
  the hash, resume, the gate mid-copy); a handed-over ready file; DEFER letting the next record run; malformed versus
  null; a scan that every bridge method calls `check()` first; the cross-app names in one shared file
  (`bridge_contract.json`, byte-identical in both apps); `music_state` end to end on `server/testdata/music_state.json`
  (the server keeps every field, the launcher keeps the app's, the app sends exactly those).
- Device checks added: after a sync, `dpm.getApplicationRestrictions` for the music app equals what was written; an
  own-file download moving from Wi-Fi to mobile data stops; BT-less sleep music for hours keeps an update waiting;
  a never-opened music app gets its first own file; install the music app first, then the step-3 launcher.


## Step 4 implementation status (2026-10-11)

Built and tested in the sandbox on branch `music-step4` (launcher, a small music app change; no server change - the
PWA already offers Off/90/80/70/60); not run on an emulator or a phone. The launcher's part is in its music package
plus the pure `ui/home/MusicCard.kt` and `lock/LockMedia.kt`, with small hooks in `HomeActivity`, `PinLockActivity`
(and its layout), `AppEnforcer.apply()`, `ScreenTime(Tracker)`, `TimeRules(Runtime)`, `BadgeCounts` and `Application`.
- **Sessions**: `MusicSessions` reads the music app's platform session through the badge listener
  (`getActiveSessions` + its change listener), only while Home or the lock shows a surface. No listener access, a
  suspended or an uninstalled music app: no media surfaces at all (the snapshot card included).
- **Home "Spilles nå"** (HomeCard v12): between the contacts row (or the call card) and the grid. Live while the
  session plays or is paused (the button is its play/pause, a tap sends its `sessionActivity` with
  `ALLOW_IF_VISIBLE`), else the last `setNowPlaying` snapshot shown paused (both open `MainActivity` with
  `EXTRA_RESUME`). 52 dp art (the session's bitmap, the snapshot's art file, else a music note on the mockup's
  gradient), the entry, "Kapittel 3 · 7:46 av 18:30", the 44 dp peach button. Active only between Home's onStart and
  onStop with its content shown (never LOCKED); 1 s ticks only while playing.
  Since design 28 (`28-music-card-session.md`) the card shows only while playing or for 10 minutes after the end of
  play or a fresh headset; outside that window a paused session or the snapshot shows nothing.
- **Lock media view** (Lock v12): a layer over the keypad, under the VoIP ring screen. When the lock comes up with
  the screen on - its first show, and after every screen-off - it shows the media view while the session plays, else
  the keypad. Clock, date, the tile (72 dp art, item, entry, 5 dp progress, previous / play-pause / next), "Lås opp"
  and the lock's emergency call. "Lås opp" -> the keypad with a back arrow by the prompt and the compact row (32 dp
  art, entry, play/pause) at the bottom; the clock and date give way, keys stay >= 52 dp at 320x569 dp with both bars
  at 48 dp. The view goes when the session does; a pause keeps it.
  Replaced by design 25's glance page (its tile, 25b-G), and since design 28 a paused session's tile only inside the
  same 10-minute listening window as Home's card.
- **Time**: `screenTimeCounts(musicListening)`: the player in front (`setForeground`, cleared at every screen-off
  and at the token's death) while it plays (the last `setNowPlaying`) - each change re-running the tracker (since the
  fix round; first "the player in front"). The budget lock adds both music package names to its usable apps (the
  allowlist still applies): music plays on after the budget and the lock screen lists the app. A rule keeps only its
  own exempt apps; before `apply()` suspends a music package its playing session is paused.
- **Volume cap**: `VolumeCap` holds `STREAM_MUSIC` at `volumeCapIndex(max, pct)` on every volume change and output
  change, while managed (`hardeningManaged`) and without the override or the pause; refreshed by every `apply()`.
- **QA #6**: media notifications never count as unread (`EXTRA_MEDIA_SESSION`, only the key's presence, in a helper
  outside the listener, which still reads no extras), so the music tile's badge is the
  "N nye i Musikk" notification's `number`; `MusicSync` grants the music app `POST_NOTIFICATIONS` while a policy
  exists (the default state without one).
- **Music app**: `MainActivity` honours `EXTRA_RESUME` (the RESUME command: the stored queue where it stopped, or play
  on); the player's names are in `bridge_contract.json` (`player`), checked by both suites.
- **Tests**: launcher 826 JVM tests (+25: `MusicMediaPlanTest`, `MusicCardTest`,
  `LockMediaTest`/`LockMediaGuardTest`/`LockMediaLayoutTest`, the music keypad stage in `PinLockLayoutTest`, the
  player in front in `ScreenTimeTest`, the budget's music apps in `TimeRulesTest`, media notifications in
  `BadgeCountsTest`, the contract's `player`), music 126 (+1 "never keeps the screen on", and the `player` contract
  in `BridgeTest`); music lint 0 errors.

Readings and deviations:
1. **The lock's stage** is decided when the lock comes up with the screen on (`lockStageDue`): coming back to the lock
   from the emergency dialer or an alarm with the screen on keeps the stage the kid was on.
2. **The keypad stage** keeps the lock's prompt ("Skriv inn koden din" with its wait/wrong texts), Emergency call and
   Parent code; the back arrow sits in the prompt's row (the mockup's header), the compact row at the very bottom.
   The media view's emergency button is the lock's own (same confirm, same text "Nødsamtale", not the mockup's
   "Nødanrop"), styled as the mockup's plain red text; the media view has no Parent code (as the mockup).
3. **The card's tap on a live session without a session activity** opens the player without resuming; only the
   snapshot resumes.
4. **Volume cap index**: max x pct / 100 rounded down, never below 1 or the stream's minimum (a cap never mutes); 100 %
   or more, or 0 or below (a server fault), is no cap. An output change is looked at again after 1.5 s (a BT device's
   absolute volume can settle after it appears). The cap is read from the cached policy at process start.
5. **Picture-in-picture** (replaced in the fix round, user decision): "listening never counts" - time in the player
   counts unless it plays, so a video in PiP over it (its sound pauses the story) or an idle hour of browsing counts;
   with the kiosk on lock task allows no PiP anyway.
6. The install deferral stays on the last `setNowPlaying` (step 3, listener-free), not on the session.

Device checks (21 §6 step 4), emulator (`docs/testing/emulator.md`, 320x569 dp, nb and en):
- Home card: live (pause/play from the card, the clock moving, a tap opens the player), a queue's end (gone), the
  snapshot after a force-stop and after a reboot (paused; the button and a tap open the player playing - and whether a
  resume without leaving Home is possible), no card without listener access, none while the app is suspended.
- Lock, in both kiosk modes, gesture and 3-button navigation: the media view at screen-on while playing (and the
  keypad when paused), its controls, "Lås opp" -> keypad with arrow and row (key sizes), back to the media view,
  screen off/on, the session ending under the media view, Nødsamtale from it, a VoIP ring over it, TalkBack not
  reaching the covered keypad, nothing else reachable (long presses, swipes, the shade).
- Time: `time_state.used_minutes` not moving while the player is in front and plays, moving while it is browsed with
  nothing playing, in split screen and in other apps; the budget used up while playing (music plays on, the lock screen lists the app and opens it); a bedtime edge
  mid-playback with the app not exempt (paused, position kept, suspended, the lock shows the keypad) and exempt
  (plays on).
- Volume cap: 60 % in the PWA - volume keys stop at the cap on the speaker and wired headphones, Off releases it, the
  override and the pause lift it; the Nytt badge on the music tile equals "N nye", a paused player adds nothing, and
  `POST_NOTIFICATIONS` is granted at install without a dialog.

Jelly Star: the card live and after a reboot; both lock stages in both kiosk modes; a bedtime edge mid-playback;
suspension vs audio (is the session paused before the suspension, does a suspended app's audio stop); clamp latency
incl. BT absolute volume (a headset's own buttons); the Nytt badge.

**For `music-v0.1.0`** (after these checks): the server with the music catalog row (0.21.0+, deployed before any
stable `music-v*`, QA #11) and a launcher release with steps 3-4 on the phones; the `release` environment's
deployment rule must allow `music-v*` tags; then tag `music-v0.1.0` on a `main` commit - `music.yml` signs
`vibb-music.apk` with the launcher's key (`RELEASE_CERT_SHA256` check) and publishes it with its `.sha256`, never
"latest". The media surfaces also need notification-listener access on each phone (adb at provisioning, as the
badges).

### Fix round after QA's code review (`qa-21-step4-code.md`, 2026-10-11)

All findings are fixed (0 High, 1 Medium, 7 Low), plus the user's player-controls request; on main with mockup v15.
- **M1, a suspended music app can't play**: the music app refuses to start playing while it is suspended
  (`playAllowed`: `KidPlayer.play`/`setPlayWhenReady` - every controller, the launcher's surfaces, headset and BT
  buttons -, its own play commands and playback resumption; a pause always works). The launcher looks the app up again
  at every play, next and previous (a pause always goes), and its card and lock view re-render on
  `onPackagesSuspended`/`Unsuspended`; `apply()`'s pause-then-suspend order is pinned by a source-order test.
- **L1**: the volume cap is also re-applied whenever a player starts or moves to another output
  (`registerAudioPlaybackCallback`: the music app's own picker routes between outputs already connected).
- **L2 (user decision: "listening never counts")**: the player in front counts as screen time unless it plays
  (`musicListening(inFront, playing)`); browsing with nothing playing counts, and so does a PiP video over it.
- **L3**: `EXTRA_RESUME` is honoured once (`resumeRequested`): never from Recents
  (`FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`, also after a reboot) or in a restored instance, and removed when used.
- **L4**: the music app also reports the position with every 15 s save while playing; the launcher rewrites the art
  file only when it changed. Home's snapshot is right after a crash or a dead battery.
- **L5**: on the music keypad stage the prompt keeps the arrow's width clear on both sides and wraps between them
  (`PinLockLayoutTest` counts two lines; keys stay >= 52 dp).
- **L6**: the device page's listener-access warning names the notification filter, the "Spilles nå" card and the lock's
  player (server template; no code change).
- **L7**: the media view's touch blocker has no TalkBack node (`importantForAccessibility="no"`, not focusable).
- **Player controls fit 320 dp** (user, design 20, mockup v15): the control row is v12's 44/56/76/56/44 with 14 dp
  gaps where 332 dp fit, else v15's 44/52/72/52/44 with 6 dp gaps (exactly 288 dp); `RoundButton` is
  `requiredSize`, so a button never becomes an oval, and the row is symmetric, so play is centred (`Fit.kt`,
  `FitTest` at 320, 360 and 411 dp). The other screens at those widths: the bottom bar cut "Sov · 30 min" and,
  at 320 dp, "Telefonen" - it now shares the width as the mockup's flex row (Liste and Sov never cut, the output's
  name gives way; 2 dp inner padding instead of 6); the carousel (180 dp cover, 46 dp peek at 320 dp), the category
  tiles, list, Nytt and search rows, and in the launcher the lock's media view and Home's card fit
  (`FitTest`, `LockMediaLayoutTest`).
- **Tests**: launcher 830 JVM tests (+4: the controls look the app up, the suspension events, apply()'s order, the
  widths; the listening rule replaces the in-front one), music 135 (+9: `GuardsTest` - the play gate, the resume once,
  the 15 s report -, `FitTest`); music lint 0 errors; server 487 (the template only), fmt clean, clippy no new
  warnings.
- Device checks added: at a bedtime edge, the headset's and the lock's play buttons do nothing; switch the music app's
  output picker with both outputs connected (the cap holds); open the music app from Recents after a snapshot tap and
  after a reboot (nothing plays); kill the player mid-item, Home shows the place to the last 15 s; a long prompt on the
  music keypad stage at 320 dp and font scale 1.3; TalkBack over the media view; the player at 320 and 411 dp.
