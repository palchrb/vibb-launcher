# QA (code): design 26b step 4b - Storytel on a Sonos room (2026-10-12)

Reviewed: `git diff 48a9c90..e3c4bda` on `music-2-storytel` (`d88bc5e` the music app and its tests, `a802f28`
`fake-storytel.py --no-download`, `e3c4bda` the status section), in full. Read against root and `music/CLAUDE.md`,
26b §0, §4.1-§4.2, §5 (all), §6, §7, §8 step 4b, §9, §13 (M1, M6, M7, H2), both merge sections' "for step 4b" lists,
the step 3 and 4a statuses and "Step 4b implementation status"; 26a §2.5, §3, §4 (4.2-4.8), §5.3, §6, the correction
round and both merge sections; `qa-26a-*.md` for the level; vibb `pi/daemon.py` (`_sonos_body` 1423-1476,
`_sonos_known_duration` 2084-2105, the adopt 4497-4570, the poller's lookup 4640-4672), `pi/sonosd.py`
(`_stall_bookkeeping`) and `tests/storytel_sonos.py` (vibb `pipewire` `1fcadac`). No product code changed here; one
mutation was run and reverted (below).

## Runs (this worktree, the sandbox's JDK 21 as for every earlier step)

- From `music/`: `./gradlew cleanTestDebugUnitTest testDebugUnitTest lintDebug -PwarningsAsErrors=true --rerun-tasks`:
  **568 JVM tests, 0 failures** (542 + 26, the status's count), lint 0 errors and the same 40 warnings; the one
  warning in a touched file (`UseKtx`, `PlaybackService.kt:782`) predates the step.
- `python3 -I scripts/fake-sonos.py --selftest` and `python3 -I scripts/fake-storytel.py --selftest`: OK (5c, the new
  `--no-download`, included).
- `git diff --stat 48a9c90..e3c4bda -- server launcher`: empty. No AIDL, `bridge_contract.json`, shared snapshot,
  `music_state.json` or Room schema change.
- Mutation (reverted, `git status` clean): the token check after the mint removed (`SonosController.kt:805`) turns
  **2** `SonosControllerTest` rows red, as the status says. The other three mutations were confirmed by reading:
  without `durationByConsumableId` only the speaker-started-book row fails (an adopt's length also comes from 1b's
  listing length of the playing item); `normUri` for `sameStream` in `roomVerdict` fails the three
  `SonosLifecycleTest` Storytel rows (and also `SonosControllerTest`'s "13 and 7", whose keep goes through
  `ourLiveSession`); without the filter's Storytel branch `build` returns null for every book, so every Storytel row
  fails.

## Findings

0 High, 1 Medium, 6 Low. The step does what §5 and the merge lists ask: the late mint sits inside the actor job between
two token checks, a failed mint sends nothing and touches no volume, `renderer.json` has exactly three uri writers and
all go through `storedUri`, every comparison with a stored marker goes through `sameStream`, the call sites read the
view §5.6 names, the constants move together and the arithmetic holds. The Medium is a spec gap that 4b makes routine,
not an implementation slip.

### Medium 1 - On the phone, a Storytel bookmark on a book that isn't downloaded still starts another book (cover tap: book 1 from 0; RESUME and a headset's play: the next downloaded book), and the entry's bookmark then moves to it

- **Where:** `M/playback/PlaybackService.kt:578` (`playEntry` -> `coverTapPlan` -> `logic/Bookmarks.kt:43-46`
  `rotateToBookmark`: the bookmark's key not in the phone's order -> the order unrotated, at 0), `:625-630`
  (`restore` -> `logic/Queue.kt:79-93` `restoredQueue`/`storedPlan`: the stored current key not playable -> "the first
  stored key after it that is still there"), used by `resumeLast` (`:594`), `restoreThen` (`:609`) and
  `onPlaybackResumption` (`:471-489`, a Bluetooth play button). `comeHome` (`:1566-1572`) is the only path 4b gave the
  rule. `SwitchViewTest:166-168` pins today's cover tap ("the phone's queue starts elsewhere").
- **Failure path:** the kid listens to book 2 (not on the phone - the "first N" limit, the 1.5 GB floor, or simply not
  downloaded yet) on Stua, then goes to bed with the phone. (a) Room -> phone: "Ikke lastet ned", nothing starts - as
  §5.6 says. (b) Then a tap on the series' cover: book 1 starts from 0 with no message (`start` sees index 0 playable).
  (c) Or the headset's play / the player's resume: the room's stored row is `[2,3,4,5,1]` at index 0, `restoredQueue`
  skips to book 3 at its position, again without a message. Either way the first position save writes the entry's
  bookmark onto book 1 (or 3) (`persistPlace` -> `place`), so the next cover tap on Stua resumes that book, not book 2;
  book 2's place survives only as an item position, reachable from the list. Before 4b a bookmark sat on a missing
  book only after its copy was pruned (step 3's note); since 4b it happens every evening a room plays a book the phone
  hasn't fetched. §9's reason for the room -> phone row ("a phone in a pocket must not start another book"; vibb's
  book 1 from 0 "is an accident") applies to (c) as much as to (a).
- **Fix (a decision for the main session; the code is small either way):** one rule for "the bookmark's (or the
  stored row's current) Storytel book has no copy on the phone": the phone starts nothing and says `NOT_DOWNLOADED`
  on a cover tap, RESUME, `restoreThen` and `onPlaybackResumption` (the list still plays a downloaded book by a pick);
  the keep set already fetches the bookmarked book first (§3.3), so the wait ends at the next Wi-Fi download. The
  alternative - keep vibb's cover-tap behaviour but never let it move the entry's bookmark - is harder to explain to a
  kid. Pure rows in `QueueTest`/`StorytelRulesTest` (a Storytel `coverTapPlan`/`storedPlan` with the bookmark's book
  absent), `SwitchViewTest:166-168` follows, a `GuardsTest` pin per call site; §5.6/§9 gain the rule.

### Low 1 - The second token check runs before `transport.play`, not "before `SetAVTransportURI`": the speaker check, the hush, the cap (members included) and `RemoveAllTracksFromQueue` sit in between

- **Where:** `M/sonos/SonosController.kt:805-807`; `M/sonos/SonosTransport.kt:86-112` (`speakers.speaker(verify =
  true)`, `hush` - GetMute/SetMute -, `capVolume` per member at `MEMBER_CAP_BUDGET_MS` 1.5 s each,
  `removeAllTracksFromQueue`, then `setAvTransportUri`). §5.1/§13 M6: "again after the mint, before
  `SetAVTransportURI`".
- **Failure path:** usually milliseconds; with a slow speaker verify or a grouped room's caps, seconds. A step in that
  window: the superseded play still sends `SetAVTransportURI` + `Play` and the coalescer's next job a second one (the
  latest wins - 1b's outcome for a url play). A suspension in that window: the book sounds until the service's park
  (queued behind it) stops it. M6's "a play queued before a suspension never sounds after it" is not strictly true.
- **Fix:** pass the token into the play: `transport.play(b, startCap, room, gate = { stillWanted(g) })`, checked right
  before `setAvTransportUri`; a failed gate ends the play with nothing sent (`unhush` in its `finally` puts the mute
  back; a cap already set stays, which is harmless) and returns the "dropped" result (null). A `SonosControllerTest`
  row with `during` moved into the fake speaker's `GetVolume` under a cap. Or say in §5.1 that the window is the
  play's own pre-steps and accept it.

### Low 2 - Deviation 1's premise doesn't hold behind a mint: an NRK/RSS play queued behind a Storytel mint runs ungated up to 10 s after its `mayPlay()`

- **Where:** `M/sonos/SonosController.kt:799-807` (the token only `if (cid != null)`); status choice 1 ("1b's url and NRK
  plays go out within milliseconds of their `mayPlay()`").
- **Failure path:** a Storytel book starts on Stua (mint running), the kid taps an RSS entry (its play job queues
  behind the mint, `mayPlay()` true on main), the launcher suspends the app: the mint job's token drops it, the RSS job
  sends `SetAVTransportURI` + `Play` after bedtime, and the park's Stop behind it ends it a moment later. Before 4b the
  same exposure existed behind a hushed play's settled seek (8 s), so this widens 1b's window rather than opening a new
  one; after `close()` the drained RSS job likewise plays until the hand-off's Stop behind it.
- **Fix:** gate every play job with the part of the token that isn't bookkeeping: `if (closed || !mayPlay()) return@submit
  null` before `transport.play` for every body (keep the generation check Storytel-only if 1b's superseded-play
  bookkeeping needs it; a dropped url play then goes through `dropped()` like a book). One `SonosControllerTest` row (an
  RSS play queued behind a slow mint, `allowed = false` during the mint: nothing sent).

### Low 3 - `storytelUnavailable` records `no_audio` against the service's *current* entry, which after a supersede or a release can be another entry

- **Where:** `M/sonos/SonosController.kt:820` (called before the `closed` / `g != gen` checks, with only the item key);
  `M/sonos/SonosRenderer.kt:113` (`queue()?.entry?.id`, the service's `active` at result time).
- **Failure path:** a Storytel book's mint is slow (up to 10 s) and ends `NoAudio`; meanwhile the kid tapped an RSS
  entry (a load: the play is superseded) or switched home and started another entry (`closed`). The result posts
  `ItemErrors.record(<the RSS entry's id>, <the book's cid>, no_audio)`: a row for a key that entry doesn't have, on
  the parent's card under the wrong entry (and the book's own error is missing).
- **Fix:** capture the entry at submit (`playIndex` already has `val entryId = e?.id`) and pass it:
  `host.storytelUnavailable(entryId, item.key, code)`; `SonosRenderer` records with that id. A `SonosControllerTest`
  row (a step to another entry during a `NoAudio` mint: the recorded pair is the book's).

### Low 4 - `PositionInfo` holds the signed URL twice (`trackUri`, `trackMetaData`'s `res`) as a data class with the generated `toString`

- **Where:** `M/sonos/SonosSession.kt:48-54`; `SourceScanTest`'s holders list (4b added `PlayBody`, `PlayResult`,
  `Moved`, `Fields`).
- **Evidence:** §0 (L9): "every type that holds one prints it redacted". `PositionInfo` is GetPositionInfo's raw
  answer and is never logged today, so this is the one holder the rule misses rather than a leak; the next
  `log("... $pos")` would print the token. (`Playing`, `Pending`, `OptPos` are plain private classes: `Object`'s
  `toString`, fine.)
- **Fix:** an explicit redacting `toString` (`redactUri(trackUri)`, the metadata as its length) and the class in
  `SourceScanTest`'s list.

### Low 5 - The owed emulator list misses M6's own check and the failed-mint path on a room; `--no-download` adds download mints the "one `asset 222`" check must tell apart

- **Where:** the status's "Owed - emulator" paragraph (`docs/design/26b-storytel.md`, step 4b status); §13 M6 ("the
  fake's `--slow-mint` for the emulator"); `scripts/fake-storytel.py` (`--no-download` answers the CDN 503).
- **Evidence:** the list has the mash during a slow mint but not a suspension during one, which is what M6 is about;
  nothing exercises a failed mint on a room (no SOAP, "Kan ikke spilles på Sonos", the phone paused; `no_audio` on the
  card for a locked book). With `--no-download 222,444,555` the download worker mints those books on every retry (2 min
  doubling) and logs `cdn 222: 503`, so the fake's log holds more than one `asset 222` line; the Sonos mint is the one
  without a following `cdn` line (the fake speaker never fetches).
- **Fix:** add to the owed list (and to `emulator.md` §6i in step 5): `--slow-mint 5`, start book 2 on Stua and
  `adb shell pm suspend me.vibb.music.debug` during the mint - no `SetAVTransportURI` in `fake-sonos`'s log (then `pm
  unsuspend`); `--refuse` (a fresh login) and `--locked 222` with book 2 on Stua - no SOAP, the phone paused with "Kan
  ikke spilles på Sonos", `item_errors` `no_audio` for 222; and the note on reading the `asset` lines.

### Low 6 - Spec text: §5.1's check point, §5.6's table, §9's row and §8 4b's files lag the code

- §5.1/§13 M6 say the check is "before `SetAVTransportURI`" (Low 1: before `transport.play`, or move the check), and
  name only `HANDOFF_MS` and `SWITCH_WAIT_MAX_MS` (`SonosTail.MAX_MS` 26 s moves with them; status spec problem 1).
- §5.6's `leaveDisallowedRoom`/`lost` row: both come home through `comeHome` (status spec problem 3); the table lacks
  `restore`'s and `playEntry`'s Storytel rule (Medium 1).
- §5.3's "The migration follow's hint ... the new room's `renderer.json` gets the marker" - true now because the follow
  writes a uri at all (status spec problem 2, deviation 7).
- §8 4b's file list: `SonosTransport.kt` needed only `PlayResult`'s `toString`, `StorytelAccount.kt` nothing; `logic/
  Switch.kt`, `SonosTail.kt`, `RendererChoice.kt` and `logic/Storytel.kt` (`sonosMintCode`) were touched (status spec
  problems 5, deviation 10).
- **Fix:** fold these into §5.1, §5.3, §5.6, §8 in the fix round (or step 5).

## The questions asked

1. **The late mint.** Order as specified: `stillWanted(g)` -> `mintFor` (`minter.mint(cid, 6 s, elapsed + 10 s)`) ->
   `stillWanted(g)` -> `mintedDropReason` (inside `mintFor`) -> `transport.play` (cap and hush inside). The token reads
   `@Volatile closed`, the `AtomicLong` generation (single writer: main) and `mayPlay` (`isPackageSuspended` and the
   room scope's belt, both safe off main; `uid` is `@Volatile`). A superseded play can still send
   `SetAVTransportURI` only inside `transport.play`'s pre-steps (Low 1). A play queued before a suspension: the token
   drops it after the mint (`dropped()`: the book waits at its place; `SonosControllerTest` "a release or a suspension
   during a mint"); an NRK/RSS play queued behind the mint is not gated (Low 2). A failed mint: nothing sent, no
   volume touched (the test asserts an empty speaker log under a cap for all six `Mint` classes and a throwing
   minter), `StorytelUnavailable(code)` -> `failed()`: a first start is Lost - `lost()` says "Kan ikke spilles på
   Sonos" and comes home paused (`quiet`, so no second message) -, a start on a room already ours is refused with the
   same words, a step or an advance is dropped (the index back). What each class shows: the kid sees the same words
   for every class on a start and nothing on a step (a kid can't act on a login anyway); the parent sees `no_audio` as
   the book's item error (deviation 3), `refused` and `none` through the report's `storytel` (the cooldown is the
   account's, written by `StorytelApi` whoever minted; it reaches the card at the next report), and nothing for
   `LoginFailed`/`Failed`/`Unreachable` - transient, and the same as downloads since M4's decision. "Logged only" is
   enough for those. The player on a room does **not** show a login state; it shows 1b's failed play, which is what
   §5.1 specifies. The serial actor: every job behind a mint waits up to 10 s (deviation 4). A bedtime Stop with the
   service up: `suspendStop`'s park queues behind the mint (up to 10 s), the receiver's 9 s `goAsync` budget ends
   first and the service's coroutine goes on in its own scope (the receiver only joins it,
   `MusicApp.kt:83-86`); the queued play is a no-op by its token, so the room plays the *previous* item at most
   ~10 s + the classify longer than before (1b: up to 8 s behind a settled seek). The standalone path is separate: its
   own `SonosTransport`, no actor, no play job; a destroyed service's drained mint (`DRAIN_MS` 30 s outlasts
   `TAIL_WAIT_MS` 5 s) ends `closed`, so its play is dropped.
2. **`renderer.json`.** Writers: `RendererChoice.played`/`followed`/`adopted` (each through `storedUri`), plus
   `RendererChoice.local` (drops the uri), `ramp`'s `copy(volume, volumeUid)` and `toRoom`'s constructor (no uri).
   `lookAtRoom` writes `choice.adopted(...)` on both branches; the suspension writes `local`; nothing else
   (`grep home.write|writeChoice|RendererChoice(`). In memory the signed URL lives in `PlayBody`, `PlayResult`,
   `Fields`, `Moved` (all redacted now), `Playing`/`Pending`/`OptPos` (plain classes), the session's `uri` and
   `PositionInfo` (Low 4). No log line prints a uri but the redacted "not-ours?"; the failed-play line prints the
   exception's message (`StorytelUnavailable`'s is its code; `UpnpFault`'s the speaker's description); Room, the
   report and the MediaSession see `storytel:book:<cid>` or keys only. `SourceScanTest`: still exactly four readers -
   `privateMintAllowed()` is called once, as the controller's default parameter (`SonosController.kt:168`).
3. **Adopt by `consumableId`.** `roomVerdict`, `SonosSession.classify` and the migration probe use `sameStream`;
   `adoptIndex` parses `consumableId` (exact, never a substring; vibb 7's ambiguous token tested). The keep returns
   the live TrackURI for every source (deviation 6): for NRK/RSS `roomVerdict` already required `normUri` equality, the
   NRK serial and `adoptIndex`'s parse read the same values, `NORM_PREFIXES` has no `x-sonos-http:` (so an NRK live and
   stored differ at most in percent-encoding) - no regression. A fresh re-mint of ours is ours: `transport.play`
   sets the session uri to the URL it sent (`session.played`), the polls compare by `normUri`, and the settle
   (`pending` with no uri) covers the mint itself, so `hidesPosition` can't fire during it (it needs `pending ==
   null`); with the fixtures, `SonosSessionTest` shows the marker ours by cid, another book not, and a session holding
   token 1 not matching token 2 (another phone's play of the same book is someone else's stream - right). The bedtime
   looks (`adoptLive(..., aux = false)` with the marker as the session uri) match by `sameStream` (tested with
   `suspensionStop`). Durations: `durationByConsumableId` first when the speaker's is falsy (vibb 4640-4672, flagged,
   never deciding done), then 1b's listing length and same-uri carry; adopt goes through `accept`.
4. **§5.6.** Every call site as the table says (GuardsTest pins each and counts them). Deviation 8 is Storytel-only
   (the branch tests `a0.entry.source == Source.STORYTEL`; other entries keep `a0.items`); a Storytel entry can't be
   shuffled (`canShuffle`: own files only) and has no explicit order beyond `snap.order`, so nothing is lost - the only
   difference from `a0.items` is that `homePlan` wraps the whole order. Coming home on an undownloaded book: nothing
   starts, `NOT_DOWNLOADED` unless `quiet` (deviation 9: right after `lost()`/the scope's leave, which already said
   why; the suspension isn't quiet - see "Not worth fixing"); what the *next* play on the phone does is Medium 1.
   `SonosSuspension.entryMatcher`/`bookmark` read the room's view: with a stored marker `bookmark` uses only
   `choice.itemKey`, the entry and its positions (identical in both views), and with no stored uri the room's view is
   the one whose `adoptIndex` finds an undownloaded book - so the bookmark is saved for it either way. `MusicViewModel`
   follows `PlayerHub.State.output.kind`; after a restart the screens show the phone's view until the adopt makes the
   output a room (the stored row's undownloaded book is hidden for that second or two - `nowView` returns null - then
   every book shows). `SwitchViewTest`'s whole-series case uses the real filter.
5. **Timeouts.** `HANDOFF_MS` 25 s, `SonosTail.MAX_MS` 26 s, `SWITCH_WAIT_MAX_MS` 35 s >= 26 + 4 + 4 = 34 and > 25;
   `SwitchTest` reads the constants. The receiver's 9 s is unaffected (see 1). `SonosLocks.TIMEOUT_MS` (10 min,
   re-asserted by the polls) is untouched - a poll waits at most 10 s. A phone -> room switch has no hand-off (the
   phone's `saveNow`); the new room shows the new item as BUFFERING (`spec`: `pending != null`) for the mint's length,
   then plays or comes home with "Kan ikke spilles på Sonos". Room -> room/phone: the `waiting` hold shows the new
   item while the hand-off waits behind the mint - fine.
6. **Re-minting.** Only `playIndex` builds a body: a start, a restart from STOPPED or not-ours (vibb 16), a step, an
   advance, phone -> room and room -> room mint; pause/resume (`Play`), seek, the stall retry (vibb `sonosd.py`
   `_stall_bookkeeping`: Stop, Play, settled seek - no new uri), the hush and settled seek of a play, the ramp, adopt
   and the follow don't. All tested in "storytel_sonos 5". `--no-download` and `--slow-mint` make the emulator checks
   possible (Low 5 for what they still miss).
7. **Tests.** They assert behaviour (empty speaker logs, mint calls with their budgets, the recorded codes, the stored
   marker through a real file, the index and place after a drop). GuardsTest pins M1 (three writers, no other uri
   write - the pin matches `uri =` on one line, enough for today's code) and §5.6 (each call site, the count, the
   screens, the re-plan, the comeHome branch). `SonosActorTest`'s new row passes without 4b - it pins 1b's actor (a
   failed or null job keeps the consumer, a call waits behind a slow job); "never cancelled" is a property of the
   callers (no `cancel` on a job), not something the row can see. §7's rows for 4b: all present (vibb 2-7, 9, 10, 13,
   14; the new `SonosLifecycleTest`, `SonosSessionTest`, `GuardsTest`, M6 and H2 rows). vibb's checks not ported here:
   **1** (on the box only downloaded books, as local paths) is step 3's `forRenderer(false)` in `StorytelRulesTest`;
   **8** (the expand cache cleared on a renderer switch) has no counterpart - there is no cache, the view is computed per
   renderer (`StorytelRulesTest`); **11** (pause freezes the bar) and **12** (a refused resume-seek doesn't eat the
   bookmark) are renderer rules 1b ported for every url play, unchanged by Storytel. Omitting them here is right.
8. **Hygiene.** See Runs. The owed checks: the status's emulator list covers §8's 4b list but not M6's suspension nor a
   failed mint on a room (Low 5); the device items match "Device checks"' Sonos line; step 5 still owes
   `music/CLAUDE.md` (renderer.json's "**built** uri" line at :98 is now "or a Storytel marker", the mint and its token,
   25/26/35 s, the views) and `emulator.md` §6i's 4b lines with `--no-download` (its flag table doesn't list it yet).

## The 11 deviations

1. **Token for Storytel only** - accepted, but its stated premise holds only when the actor is idle (Low 2: gate every
   play with `!closed && mayPlay()`).
2. **A current play dropped after its mint: no message, the book waits** - accepted; a suspension is the only way there
   and the launcher says why.
3. **`no_audio` from a room's mint as the book's item error** - accepted (the parent needs it; `onIsPlayingChanged`
   clears it when the book plays); record it under the right entry (Low 3).
4. **The serial actor behind a mint** - accepted. The costs are bounded (10 s; `HANDOFF_MS`, `DRAIN_MS`, the switch's
   hold cover them; the trap of per-job cancellation avoided). A mash costs up to two mints before the one
   `SetAVTransportURI` (the first job's mint finishes and is dropped) - see "Not worth fixing".
5. **`SonosTail.MAX_MS` and `SWITCH_WAIT_MAX_MS` with `HANDOFF_MS`** - accepted; arithmetic checked (above).
6. **The adopted session takes the live TrackURI for every source** - accepted; no NRK/RSS regression (question 3).
7. **The migration follow writes the hint's uri** - accepted; for NRK/RSS it stores a `normUri`-equal form, for
   Storytel the marker.
8. **Room -> room re-plans a Storytel series** - accepted; Storytel-only, nothing lost (question 4).
9. **Coming home to a book not on the phone, Storytel-only, `quiet` after `lost`/the leave** - accepted; the rule stops
   at `comeHome` (Medium 1).
10. **No change in `StorytelAccount.kt`** - accepted; the account already had the one signature and
    `runInterruptible`.
11. **`RendererChoice`'s three writers** - accepted; a better pin than a service test could be.

## The spec problems the status lists

All six confirmed: `SonosTail.MAX_MS` unnamed in §5.1/M6; 1b's follow wrote no uri; `leaveDisallowedRoom`/`lost` come
home through `comeHome` (no separate row needed); §7 13/14's "the poll prefers it" shows only for a stream the speaker
started itself (an adopt and our plays get the same value from 1b's listing length); §8 4b's `SonosTransport.kt` needed
only a `toString`; the merge list's `SonosSuspension` view was right and is fixed, and `hostDropReason` is its shared
helper. Added here: Medium 1 (the phone's cover tap/RESUME on a room-only bookmark), Low 1 (where the second check
sits), Low 6 (the text).

## Not worth fixing

- **A step mashed during a slow mint costs two mints** (the queued job's, then the coalescer's latest), so the final
  `SetAVTransportURI` can come after two mint budgets; still one `SetAVTransportURI`, the kid's last want wins, and a
  real mint with a kept JWT is well under a second.
- **During a mint the player shows the new book's title over the old book's moving position and length** (`spec`'s
  BUFFERING with `position()` reading the old, ours snapshot) - 1b's in-flight display, now for the mint's length;
  cosmetic, sub-second in practice.
- **A hand-off pressed during a mint lets the play go out, then bookmarks and stops it** (`SonosControllerTest` pins
  it): the bookmark lands on the book the kid chose; bumping the generation in `handOff` would bookmark the old book
  under the new index instead.
- **`suspendStop`'s `comeHome` isn't `quiet`**: an undownloaded book at bedtime emits "Ikke lastet ned" to a suspended
  app's UI that isn't showing.
- **`lost()` refreshes the topology after a Storytel mint failure** (one cheap household read, 1b's rule).
- **`RendererChoice`'s M1 pin matches a named `uri =` on one line**: a positional uri in a multi-line constructor would
  slip past; no such call exists and `toRoom`'s constructor stops at `itemKey`.
- **`host.played` returns without writing when the service has no queue** (1b wrote `entryId = null`): only after the
  queue went, when nothing reads the row before the next play.
- **`toRoom` for a Storytel book that left the listing while the phone still plays its copy** starts the room's order
  at book 1 from 0 (`homePlan` with the key absent) - an edge of an edge; the copy is pruned at the next sync anyway.
- **`roomScope` is read off main by `mayPlay` without `@Volatile`**: a belt behind the service's own leave; a stale
  read only delays it to the next check.

## Verdict

Ready to merge after a short fix round: decide Medium 1 (the main session's call; then its few lines and rows), and
take Lows 1-6 (each a few lines, a test row or text). No High; nothing in the late mint, `renderer.json`, the adopt,
the call sites or the timeouts is wrong against §5. The emulator checks (with Low 5's additions) and the Sonos device
checks stay owed.
