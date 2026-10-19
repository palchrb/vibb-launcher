# QA (code): all pictures ASAP on Wi-Fi (branch `art-asap`, 2026-10-19)

Scope: `git diff e8e0b456 eda0a743` (bacd5d0c server, eda0a743 music + launcher), design 39's "Addendum (2026-10-19)",
`docs/notes/server/music.md`'s episode-art section, the updated paragraphs of `music/CLAUDE.md`, `launcher/CLAUDE.md`
and `server/CLAUDE.md`. Product code not changed.

## Summary

- **Counts**: High 0, Medium 3, Low 7.
- **Runs** (x86 VM, under `~/build.lock`, one at a time): server `cargo test -q -j 2` **1065 passed**, `cargo fmt
  --check` clean, `cargo clippy --all-targets` 13 warnings (the known 13, none new); music `testDebugUnitTest
  -PwarningsAsErrors=true` **920 passed**; launcher the same **1398 passed** (only kapt's known javac `RELEASE_11` note).
- **Mutations**: 8 of 8 caught (table below).
- The server side is sound: no picture cap is left, the 3-failures stop, the backlog rule, the retries and the
  same/own judgement are unchanged, `Why::Art` sorts after every other reason, no runaway against a dead host. The
  phone side works and its pagination terminates, but `later` still follows the downloads switch on mobile data (the
  user said Wi-Fi only for the bulk), the bulk run holds the courier, and "Spotify's look-ahead" in `now` is every
  Spotify entry's, not the playing one's.

## Verified

**Server**
- `ART_PER_CHECK`, `ART_PER_CHECK_SPOTIFY`, `ART_PER_DAY`, `ART_DAY_RESERVE`, `art_gets`, `art_backfill_allowed`,
  `art_requests_today`, `count_art_request` are gone; no reader of `music_art_requests` is left in `server/` (grep:
  only 0070's CREATE and 0081's DROP). `reference_prints` lost only its day check. `Sweep::art_per_check` defaults to
  `u32::MAX`; `gets` is decremented only per request, so production never holds an item.
- `ART_NETWORK_FAILURES` (3 in a row) still ends the pass; `unreachable` resets on an answer. A network-failed item
  gets `art_fetched_at` and is not `art_due` for a day, so `rest` never counts it again.
- Budget cut: `job.spent()` or `Got::Skipped` sets `cut_at`; `rest` = the due candidates from there;
  `plan.art.more` stored by `finish` (`old.art_more` kept on a recheck-only check, so a flagged NRK entry still comes
  back for its pictures). `schedule_one` gives `Why::Art` only with `failures == 0`, after `CheckNow`, `New`,
  `Rework` and the flags, and only while `regular > now`; `Why` derives `Ord` with `Art` last, so every other due
  entry goes first (no starvation; mutation S4). The 5-checks warning is skipped for `Art`; `CHECKS_PER_PASS` still
  bounds an entry to 5 checks a pass (a 1245+-item feed continues in the next pass 1 s later).
- Dead host: the `came` flag - a cut by the 3 failures is "due again" only when a picture answered in this pass;
  a host that flaps still progresses by >= 1 item a check. Backlog switched off in the same pass zeroes `more`.
- `migrations_73_to_81_in_order` updated; 0081 is a new file (0080 untouched); `server/CLAUDE.md` names 0082 as next.

**Phone**
- Want set on unmetered (`artWant(unmetered = true)` -> `wantedArt(all, allLater)`): `now` + `list` + keep sets +
  Nytt + every item of every ticked entry in play order (Spotify context entries excluded), less what is held, the
  first 2000 (`EpisodeArt.kt:100`). Metered: `allLater = false`, the wire is as before apart from the Spotify move.
- Pagination proof: the window is "the first 2000 *missing*", recomputed on every send; the launcher fetches in
  rounds of 100 (`fetchArtPaced`, re-reading the want set, files and network each round), nudges after each round;
  the music app's sync `pullCovers` copies every launcher-held hash of the same recomputed set, so `held` grows and
  the next `BridgeSync.wantArt` (unconditional at every sync's end) names the next ones. Termination: each launcher
  round must store >= 1 or the loop ends; a stored hash leaves `next` (it is in `has`); a refused hash is backed off
  after 2 failures. Nothing is re-sent forever except a hash the launcher can never fetch (see L6); nothing is left
  out (mutation P3).
- `now` = `nowArt`: window ±5, then the queue's next 50 (no wrap), then the Spotify look-ahead; capped 500.
  `PlaybackService.wantArtSoon` sends at once (`wantArtDelay` 0 for the first) and a run of skips once more at the
  end of the 2 s gap (`wantArtAt` moves only when a send runs).
- Gates (`artGates`, `MusicPlan.kt:385-401`): `now` = online && not roaming && (unmetered || stream switch) - as the
  user asked. `later` = online && (unmetered || downloads switch && not roaming) - see M1.
- The launcher fetches `now` at once on `wantArt` (`MusicCourier.fetchNowArt` from `wantArtChanged`, beside a courier
  run), then `request`s the courier when anything is fetchable; a listing update reaches the music app's sync, which
  applies listings before `ArtWants.compute` and sends (`Sync.kt`, pinned by `asap - a listing applied while playing
  re-sends ...`).
- Prune: the music app's `keep` has no cap (`EpisodeArt.kt:104`, `allOwn` on any network); the launcher's
  `artToDelete` keeps library covers + the stored want set.
- `bridge_contract.json` `want_art {max: 2000, now_max: 500}` byte-identical in both apps (`cmp`), checked by
  `MusicPlanTest` and the music app's contract test; the AIDL files are byte-identical, the comment is stale (L1).
- Binder size: a `String[]` element in a Parcel is 4 bytes of length + UTF-16 (128 bytes) + a 2-byte terminator
  padded to 4 = 136 bytes, so 2000 hashes are ~272 KB per call, not ~130 KB (the code's own comment says ~270 KB).
  Within the 1 MB buffer, but see L2.
- Storage: ~50 MB for a ticked 1000-item feed, in the music app's covers dir, **not counted** against
  `storage_limit_gb` (`CacheThrough.evict` sees only `DownloadRow`s). My view: it should be counted in the bytes the
  app reports (`audio_bytes`/`extra_bytes` beside a new picture total, so the parent's number is honest), but not
  evicted to make room for audio - pictures are a few % of a 1-2 GB limit and evicting them would re-download them
  on the next Wi-Fi. L4.

## Findings

### Medium

**M1. `later` (the bulk list) still follows the downloads switch on mobile data** -
`launcher/app/src/main/java/com/kidslauncher/mdm/music/MusicPlan.kt:394`. The user: the bulk list is Wi-Fi only. The
music app leaves the bulk out of a metered send, but the launcher keeps the last stored want set: after a Wi-Fi to
mobile switch with `download_mobile_data` on, the courier's next round (`fetchArtPaced` re-reads the network, and the
gate lets `later` through) fetches up to 100 bulk pictures (~5 MB) on mobile data before the music app's next send
replaces the set - more when the music app's sync doesn't run promptly (each round's nudge starts it). The test
`art gates - ...` pins the current row ("metered, downloads on: later"), and `launcher/CLAUDE.md:431` says "a metered
network ends it", which is only true with the switch off. **Fix**: `later = online && unmetered` (kept episodes' and
Nytt's pictures then wait for Wi-Fi too - the playing ones come with `now`), the test row and both CLAUDE.md
paragraphs with it; or, if kept pictures on mobile data must stay, a third list (`bulk`) in the want set and the AIDL
(appended method) gated on unmetered only.

**M2. The paced bulk run holds the courier** - `MusicSync.kt:243` (`syncArt` inside `fetchAll`) and `:326`. The loop
runs while `next` is non-empty, and each round's nudge makes the music app name the next 2000, so on Wi-Fi a library
of several thousand pictures keeps one courier run going for many minutes (every picture a sequential GET). A sync
in the meantime only sets `again`: a new library (a parent's tick), a changed listing and the own-file downloads
(`MusicFileDownloads.request` after the run) wait until all pictures are in. **Fix**: bound a run (e.g. 5 rounds =
500 pictures), then set `again` (or `request` itself) so the next run fetches the library and listings first and
goes on with the pictures; or move the bulk loop out of `fetchAll` into its own job as `fetchNowArt` is.

**M3. "Spotify's look-ahead" in `now` is every Spotify list entry's, not the playing one's** -
`music/app/src/main/java/me/vibb/music/sync/ArtPlan.kt:68,87`. `ahead` collects the next 50 tracks of each Spotify
list-mode entry from where a cover tap would start it, and `nowArt` puts all of them in `now`. With the streaming
switch on, a phone with eight Spotify playlists fetches ~400 pictures (~20 MB) over mobile data at once (the 500 cap
cuts the rest), none of them playing. Before this change they were `later` (the download gate). The brief's rule is
"the picture of what is playing" on mobile data. **Fix**: in `now` only the playing entry's look-ahead (when the queue
is a Spotify entry, `nowArt`'s next 50 already covers it), the other entries' look-ahead in `later` (Wi-Fi only, M1);
adjust `streaming's now - ...` and `a Spotify list entry wants its next tracks' art ...`.

### Low

**L1. The AIDL comment is stale** - `launcher/` and `music/app/src/main/aidl/me/vibb/bridge/IMusicBridge.aidl:45-47`:
"the now-playing window, fetched on any network" and "later (the kept episodes' and Nytt's, with the audio's network
gate)". `now` is streaming's (±5, next 50, look-ahead; never roaming, mobile only with the stream switch); `later`
also carries every ticked entry's pictures on Wi-Fi; a call is a page of at most 2000 missing hashes. **Fix**: the
comment in both files in one commit (byte-identical, `MusicPlanTest`/`BridgeTest`).

**L2. A call is ~272 KB, two queued ones are near the oneway limit** - `EpisodeArt.kt:20`. Oneway transactions share
the target's async space (half the 1 MB buffer). The launcher's `wantArt` returns at once (it only stores and
launches), so two in flight are unlikely, but the sync's send and the player's can land together; a refused oneway
call is caught (`Sync.kt` "didn't reach the launcher" / `LiveBridge` drops the bridge) and only the next send heals
it. **Fix** (optional): `WANT_ART_MAX` 1000 (~136 KB; pagination makes the size free) and the brief's/notes' figure
corrected to ~270 KB.

**L3. The server's "due again at once" has no progress guard for a cut before any picture request** -
`server/src/music_sweep.rs:3603` and `:3696`. A pass cut at its first candidate by `job.spent()` (the listing used the
250 requests or the 10 minutes) still stores `art_more`, and the entry comes back at once, 5 checks a pass, a pass a
second later, each a full listing check, for as long as the listing stays that expensive. Unreachable with today's
sources (NRK <= 100 items, Spotify <= 1000 tracks in pages of 50, RSS one GET; time only on a pathological host), so
Low. **Fix**: store `more` only when the pass sent at least one image request (the dead-host rule's `came`, widened
to "requested"), else 0.

**L4. Pictures are not counted in the music storage** (see Verified, Storage). **Fix**: report the covers dir's
bytes with the music state (and show them in the PWA's storage line); no eviction.

**L5. Duplicate GETs** - `MusicBridgeService.kt:203`. Each `wantArt` call launches its own `fetchNowArt` beside the
courier and beside an earlier `fetchNowArt` (a run of skips sends twice), so a hash can be fetched twice at once.
`writeAtomic` makes it safe; only bytes are wasted. **Fix** (optional): a single-flight set of hashes in flight.

**L6. A hash the launcher can never fetch stays in the music app's window** - `EpisodeArt.kt:100`. The launcher backs
it off (2 failures, a day), the music app keeps naming it; it uses one of the 2000 slots for good. Only >= 2000 such
hashes would stall the pagination - theoretical (the server lists only hashes it stored). Also on a Wi-Fi to mobile
switch the music app's next set drops the bulk, and the launcher's `artToDelete` deletes bulk pictures it fetched but
the music app hadn't copied yet - re-fetched on Wi-Fi. No fix needed; noted for the docs.

**L7. Docs**: `launcher/CLAUDE.md:431` "so a metered network ends it" (true only with the downloads switch off; goes
with M1); the brief's/`music/CLAUDE.md`'s "~270 KB" vs the brief's "130 KB" (L2).

## Mutations (each reverted; the tree is clean)

| # | Mutation | Caught by |
|---|---|---|
| S1 | The server pass stops after 20 pictures again (`art_per_check().min(20)` in `run_check`) | `music_art::a_check_fetches_every_picture_in_play_order`, `due_again_at_once_until_the_pictures_are_done`, `every_ok_item_in_play_order_and_gone_items_get_nothing_new` |
| S2 | `Why::Art` never scheduled (`if false && row.art_more > 0 ...`) | `music_art::due_again_at_once_until_the_pictures_are_done` |
| S3 | The dead-host rule removed (`if came` -> `if true`) | `music_art::a_dead_host_is_not_due_again_at_once`, `music_sweep::the_budget_commits_progress_and_the_next_check_continues` |
| S4 | The schedule's reasons reversed (`Art` first, starving the rest) | `music_sweep::the_most_urgent_entry_goes_first` (generic; no Art-specific ordering test) |
| P1 | The metered want set returns the bulk list (`allLater = true`) | `EpisodeArtTest`: `asap - on Wi-Fi every picture ...`, `over the library - ...`, `a Spotify list entry wants ...`, `an extra's picture is kept` |
| P2 | `now` gated on the downloads switch instead of streaming | `MusicPlanTest`: `art gates - ...`, `art to fetch follows the policy's two switches`, `asap - the now pictures at once ...` |
| P3 | The pagination cursor not advancing (`wire.take(2000).filter { !in held }`) | `EpisodeArtTest`: `at most 2000 on the wire, ... - keep has no cap` |
| P4 | The launcher's paced fetch stops after one round | `MusicPlanTest`: `asap - the courier fetches in rounds of 100 ...` |

## The implementer's deviations - verdicts

The six as the addendum and the two commit messages give them:

1. **`later` keeps the downloads gate on mobile data** - rejected: M1 (the user said Wi-Fi only for the bulk).
2. **Spotify's look-ahead moved from `later` to `now`** - accepted for the playing entry only: M3.
3. **2000 hashes a call instead of "no cap", the rest paged** - accepted: the pagination terminates and leaves
   nothing out (P3); the size is ~272 KB, not ~130 KB (L2).
4. **The prune keeps every ticked entry's pictures on any network** - accepted: a Wi-Fi picture is not deleted on
   mobile data; costs storage (L4).
5. **A dead host that answered nothing waits for the regular check** - accepted: the only guard against a runaway
   (S3); widen it to "no request at all" (L3).
6. **A metered sync that leaves pictures for Wi-Fi enqueues one UNMETERED sync (`music-wifi-art`, KEEP)** - accepted:
   the whole set goes out as soon as Wi-Fi comes, with no polling.
