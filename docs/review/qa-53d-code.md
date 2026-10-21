# Code QA - design 53, round 53-d (the polling round: confirm polls, our DIDL's id as a hint, the slow check)

- Commit on branch `d53-d` over `main`'s `8e0fcd77`: `5bcb84d7` (`SonosController`'s `Confirm`/`confirmFor`/`confirmed`/
  `awaited`/`causedByUs`, the `lagging` gate in `accept`, `Early`/`metadata`/`earlyHint`, the disagreement's second look,
  the wake at a lost health, `MAX_EXTRAPOLATE_MS`; `SonosEvents`' `playbackMetadata:1` subscribe, `SocketEvent.Metadata`,
  `Playback.itemId`, `OUR_OBJECT_ID`; `SonosSession`'s `relaxed`/`POLL_SLOW_MS`, the tail cut, the one-line log; tests in
  `SonosControllerTest`/`SonosSessionTest`/`SonosEventsTest` `d53d` and the rig's `item`/`meta`/`wire`; docs: the note's
  "53-d status", §2.1 and §2.5's pointers, music/CLAUDE.md, PLAN).
- Read: root and `music/CLAUDE.md`; `docs/design/53-sonos-grouping-precision.md` (§0.1's measured lag, §2.2-§2.5,
  §2.8, every status section, "53-d status"); PLAN's "Sonos polling round (53-d)"; the capture
  `music/app/src/test/resources/sonos/ws/capture_2026-10-21.json` (the plays' `itemId`s, the metadata pushes); the full
  diff and the code around it (`SonosController` - `pollTick`, `agreement`, `onSocket`, `wake`, `playback`, `accept`,
  `played`, `playIndex`, `stepTo`, `stop`, `bookmarkNow`, `handOff`, `fadeOutAndPause`, `adjustVolume`; `SonosSession`
  whole - `tick`, `classify`'s aux, `cadenceMs`, the stall, the probe; `SonosSocket`'s health and `healthyNow`;
  `SonosPoller`'s conflated wake; `SonosLocks`; `PlaybackService`'s `saveNow` call sites and the 15 s save tick;
  `SonosBookmarks`' age term).
- Ran: `testDebugUnitTest -PwarningsAsErrors=true` (full music suite): **BUILD SUCCESSFUL, 1123 tests, 0 failures, 0
  skipped** (`SonosControllerTest` 133 in 0.17 s, `SonosSessionTest` 38 in 0.03 s, `SonosEventsTest` 12 - no test
  sleeps: the fake clock, `FakeWork`). Then 20 one-line mutations against the three Sonos test classes, each
  reverted; the tree is clean but for this file.

## Findings

**1 (Medium) - a step, a new pick or the player's stop bookmarks the old item from a snapshot up to a minute old.**
Under the slow check the last measurement of the playing item can be ~60 s old, and `bookmarkNow`
(`SonosController.kt:1693-1711`) adds at most `BOOKMARK_AGE_MAX_MS` (5 s, line 1700 - rightly: never a guess in Room).
The pause path heals itself (the verb window's poll reads PAUSED, a changed transport writes at once), the hand-off and
the suspension read live - but three paths write the stale value and nothing corrects it afterwards, because the next
polls are of another item or of no session: the service's `goTo` (next/previous/a swipe, `PlaybackService.kt:1176-1177`
`renderer.saveNow()` then `stepTo`, `SonosController.kt:953`), a new pick (`playEntry`/`playItem`/`start`,
`PlaybackService.kt:750`, `762`, `853`, then `load`/`playIndex`) and Media3's stop (`SonosController.stop`, 859-873:
`bookmarkNow(force = true)`, then Stop). A kid who skips to the next episode 50 s after a slow poll comes back to the
old one ~45 s earlier than where they left it; before 53-d the gap was at most 10 s (a 15 s cruise). The note's "bookmark
clock" bullet ("a crash that also loses the speaker loses at most ~1 minute ... a pause, a switch, a suspension and the
hand-off read live") doesn't cover these. Fix: before an item change replaces ours (the playing branch of `stepTo`,
`load` while a session of ours plays, `stop`) and when `now - snap.measuredAt > POLL_CRUISE_MS`, queue one measurement in
the actor ahead of the play/Stop - `transport.liveRefresh(LIVE_BUDGET_MS)` (one GetPositionInfo + GetTransportInfo, the
hand-off's read) - whose result is bookmarked for the old `Playing` with `force` **without** `live()`'s
`pending = null` (a small `bookmarkLive(fields, playing)`: the same ours/uri/seeded checks, then `SonosBookmarks.decide`);
the actor's order puts it before SetAVTransportURI or Stop. Test (`SonosControllerTest`): healthy socket, a poll at
7:11, 50 s on, `stepTo(1)` - the old item's bookmark is the live read's 8:01, not 7:16.

**2 (Medium) - the volume the app steps and ramps from can be 12 minutes old.** The aux read (GetVolume with the group's
coordinator) stays every 12th poll (`SonosSession.kt:442-459`, `AUX_EVERY` 649), ~12 min under the slow check (3 min
before). The note accepts it because "the volume shown is our own last set (vibb's rule)" - but our own last set
(`volOpt`) is null until the kid sets one or a start capped/restored one (`PlayResult.volume`: only `capVolume`'s or the
ramp's leftover), and then the base is `snap.volume`: the volume keys step ±3 from it (`adjustVolume`, 2029-2035), and the
sleep timer's fade ramps from it and **puts it back** after the Pause (`fadeOutAndPause`, 2145-2172:
`transport.volume((base * f) ...)`, then `transport.volume(base, target)`). A parent turning the speaker down with its
own buttons or the Sonos app at bedtime (40 -> 12) sees the timer's fade start at 27 and the speaker left at 40 for the
next play - quietly undoing the parent's change. The window existed (3 min); the slow check makes it four times wider,
and both the button and the timer are evening routines. Fix (no periodic cost): when `volOpt` is null and the last aux
read is older than `AUX_MIN_MS`, read GetVolume first inside the job - `fadeOutAndPause`'s job before its first step
(ramp from what it read), `adjustVolume` through a one-SOAP read in the actor and then the step. Alternative: under the
slow check bound the aux by time as well (`measuredAt - auxAt >= 180 s` - the old worst case; ~+26 calls an hour). Either
way the note's sentence wants correcting. Test: a session with no own set, the speaker's volume moved after the last aux
read, the fade's first SetVolume is from the new value.

**3 (Low) - an IDLE's confirm superseded by a PLAYING leaves `idleGen` set for the rest of the generation.** 53-a's
recheck cleared `idleGen` 3 s after an IDLE while UPnP still read PLAYING; now the confirm is dropped by the newer state
(`SonosController.kt:1395`) and only its window's end clears `idleGen` (1351), which a superseded confirm never reaches.
After a stop and play from the Sonos app (IDLE then PLAYING, same item) `idleGen == gen` stays, and any later BUFFERING
we didn't cause in this generation - a rebuffer - sets `skipAdvance` (1532); it is cleared only by an ours live
non-transitioning poll (1330), so a rebuffer in the last seconds of the episode skips the advance and the queue stops.
Narrow, but it is a "the next episode doesn't come" case. Fix: clear `idleGen` where `skipAdvance` is cleared, while no
STOPPED confirm is open (`if (s.transport?.live == true && !s.transitioning) { skipAdvance = null; if
(confirm?.state != TransportState.STOPPED) idleGen = null }`) - the takeover race keeps it while the IDLE's confirm still
waits for UPnP. Test: IDLE, PLAYING (not ours) 1 s later, an ours PLAYING poll, BUFFERING 20 min later near the end,
IDLE, STOPPED: the advance comes. The window end's own clear (1351) has no test either (M16 survives); the same test
with UPnP reading PLAYING through the window covers it.

**4 (Low) - the tests count the delayed polls but never their delays.** `FakeWork.after` keeps the delay and `drain()`
runs every block at once (`SonosControllerRig.kt:24-40`), so the 1 s spacing of the rechecks (`work.after(CONFIRM_EVERY_MS)`,
1347) and the second look's 3 s (1369) are pinned by nothing: the wake counts come out the same whatever the delay
(M17, M18 survive). The window's arithmetic is pinned (M3 caught) because it reads the clock. Fix: `FakeWork.after`
records `clock.now + delayMs` and `drain()` runs only the due blocks (a `drain(upTo = clock.now)`; the existing tests
`advance` the clock before they poll), or assert `r.work.afters.map { it.first }` where a test asks for the recheck and
the second look.

**5 (Low) - "since our play went out" and "our group only" for the metadata are untested.** `metadata()`'s
`e.at < h.sentAt` (`SonosController.kt:1478`) and the parser's `gid != subscribed` (`SonosEvents.kt:254-258`)
have no test (M14, M15 survive). The `vibb-1` our previous episode left before the next play went out is the case the
first one guards (every play of ours carries the same id). Fix: in `d53d an absent or other object id ...`, a `meta(OUR_OBJECT_ID,
at = before the play)` then the new item's PLAYING - still BUFFERING; in `SonosEventsTest`, a `metadataStatus` with
another `groupId` gives no event.

**6 (Nit) - `vibb-1` is every vibb's id.** The hint can't tell our play from the vibb Pi's or a sibling's phone's
started on the same room while ours settles (the same DIDL, the same `<item id="vibb-1">`): the bar would show their
position for up to `SETTLE_MS` before the landing poll reads the foreign TrackURI. Display only and bounded - add one
line to the note's (2) so the device round doesn't read it as a bug.

**7 (Nit) - docs.** `music/CLAUDE.md:469` still says "the 60 s extrapolation cap" (now 70 s, line 457's new text says so).
Design 54's own text (`54-spotify-on-sonos.md:308-310`, `790`) still names `IDLE_RECHECK_MS`/`IDLE_RECHECK_UNTIL_MS`;
the note's "For design 54" line covers it, but 54's next revision should take the new names (the qa-54 files are history
and stay). The renames are right: the constants now serve every confirm, and `CONFIRM_UNTIL_MS` 4 s with "the next
recheck within 4 s" is 53-a's "a recheck while < 3 s, one second later".

**8 (Nit) - the slow check's log line.** `tick` computes `relaxed(snap)` for the line (`SonosSession.kt:386-390`) apart
from `cadenceMs`'s own call (523), each reading the volatile health once, and the "healthy" wording is also logged when
the slow check comes back after a frozen position or a failed poll (the socket was healthy throughout). Harmless; one
`relaxed` value passed into `cadenceMs`, and "the regular UPnP check once a minute (socket healthy)" / "... at 5/15 s"
would read true in both cases.

## The checks, with the evidence

- **(1)'s "a lagging poll can't take back the transport"** (`awaited`, `SonosController.kt:467-474`; `lagging`, 1596):
  it only holds while a confirm of a PLAYING or PAUSED we didn't cause is open, of this generation and not a new item,
  and that confirm ends at the first poll that shows the state, at a poll with another TrackURI, at a failed poll, at a
  newer socket state (ours or not, 1395), at our next play or load (`forgetEvents`), at a lost health (1423), or at the
  window's last poll (≤ 4 s after the event; `confirmed`'s schedule ends with `confirm = null`, so it never outlives the
  window on a poll). Walked: a real stop after a socket PLAYING (the socket's IDLE supersedes it; a socket that missed it
  is bounded by the window, then the poll's word and the disagreement rule with its second look); a takeover (BUFFERING of
  a new item opens a new-item confirm, never `awaited`; the PLAYING after it finds `wsBlocked` and doesn't move `wsTr`,
  and the poll with the foreign TrackURI ends it); the socket going silent mid-confirm (the window; a closed socket's
  `Health(false)` clears it); our own verb meanwhile (`optTr` wins in `effectiveTransport` before `wsNewer`). What
  `awaited` reaches is display and the screens' gates that already read the effective transport (`showsPlaying`,
  `pauseCause`, `stepTo`'s "plays now", `handOff`'s `wasPlaying`) - each truer with it (a skip on a speaker the Sonos app
  paused only moves the start). It never reaches `pollTick`'s ours, the bookmark (which reads the poll's own fields), the
  follow or the advance. A stale `wsTr` of the same state can stand in for an event `wsBlocked` kept out (an IDLE, then a
  Sonos-app PLAYING): it shows the socket's latest state anyway, for ≤ 4 s.
- **The advance**: still `pollTick`'s STOPPED-near-the-end rule on a fresh ours poll; the confirm only adds polls. Exactly
  once: the advance's `playIndex` sets `pending` synchronously (later polls return at the settle) and `forgetEvents`
  drops the confirm; with `mayPlay` false the generation moves and `endsNear` is 0. The natural end's IDLE carries no
  `itemId` (the capture), so it never opens a new-item confirm; a takeover after it still sets `skipAdvance`. 53-a's
  IDLE-recheck test runs as the confirm (its one extra wake is the second look - correct: UPnP still PLAYING 3 s after
  the IDLE).
- **Nothing starts by itself / the gates**: every 53-d path only wakes a poll (`wake`, rate-limited, never while the
  last poll failed) or re-bases the anchor; no SOAP verb, no `playIndex`, no Room write is reachable from an event, a
  confirm, the hint or the health wake. `mayPlay`, the scope belt and `playAllowed` are untouched.
- **The hint** (`earlyHint`, 1490-1502): needs `pending` with an answered uri, our id since the play went out, a parked
  PLAYING/PAUSED of an `itemId` other than the speaker's before the play, and a position; it calls `measure` (the anchor,
  display) and sets `applied` (READY instead of BUFFERING in `spec`). `pending`, `seeded` (the bookmark refuses a seeded
  snapshot), the snapshot's ours, `rendererState` (null while `pending`), the advance and the follow are untouched -
  `showsPlaying` already read the seed as ours and PLAYING before 53-d; `early` dies with `pending` (the landing poll,
  `SETTLE_MS`, `forgetEvents`). Media3 sees READY + playWhenReady ~1 s sooner: the session's isPlaying and the 15 s save
  tick start earlier - the tick's `bookmarkNow` refuses the seeded snapshot. Events before the play's answer are still
  dropped, so the parked event is always after the SOAP job (its settled seek included); the metadata may come before
  the answer and is kept (`meta`) until the parked event, or after it - the capture has both orders (next: PLAYING then
  the metadata), and `earlyHint` runs from both sides.
- **The slow check** (`relaxed`, `SonosSession.kt:558`): reachable, no frozen position, the socket's volatile
  `healthyNow` (`SonosSocket.kt:548-551`). The tail cut keeps the advance's 5 s tail polls and the last bookmark ≤ 5 s
  before the end (9 tail polls in the hour test). Freshness follows the cadence (70 s), the bar's cap is 70 s, the
  stall's retry ≤ 90 s (pinned). Every way health ends reaches the controller as `Health(false)` on main (`close`,
  `ended` - a dead ping, the liveness miss, the disagreement - and `planHealth` on a re-subscribe), which now wakes a
  poll whose cadence is today's (`SonosPoller.wake` cuts the 60 s wait: a conflated channel). The probe: a coordinator
  leaving under a healthy socket comes with `groups`/`MOVED` (a wake, `followSoon`); without one the first STOPPED-empty
  poll is ≤ 60 s after the last ours sighting, inside `MIGRATE_WINDOW_MS` (90 s), and the window's 5 s cadence gives the
  three tries. Grouped away is read from the TrackURI on every poll and our uid coordinating nothing ends health. A group
  change from a speaker's button reaches 53-b's `groups` path, which doesn't wait for a poll. Finding 2 is the aux's cost.
- **Locks and Doze**: the partial wake lock and the Wi-Fi lock still follow `armedPlaying`; refreshes come at least at
  every poll (≤ 60 s) against `SonosLocks.RENEW_MS` 5 min. While playing the lock keeps the CPU, so the 60 s waits are
  real; while paused there is no lock (as before - only 15 s became 60 s). A Sonos-app resume while paused now shows
  PLAYING through the lagging confirm polls, so the lock is taken at once instead of flapping.
- **The bookmark clock**: the 15 s save tick re-writes the last poll + ≤ 5 s; the pause, the hand-off and the
  suspension measure live or soon after; finding 1 is what doesn't.
- **The metadata subscription**: one subscribe per (re)subscribe of playback, its answers ignored for health (a refused
  one says nothing); a `metadataStatus` of our group is reduced to the container's object id on OkHttp's reader thread
  under the existing 64 KiB message cap (`SonosEvents.kt:97`), every step a safe cast (`as? JsonObject`, `isString`,
  `contentOrNull`) - an unknown shape is `objectId = null`, no evidence. Traffic: one push per item change (the capture:
  six in its whole session), plus whatever a radio stream's song changes push; each is a `main.post` and a refresh.
- **Thread safety**: `Confirm`, `Early`, `wsItem` are main-confined (set in `onSocket`/`pollTick`/`playIndex`, read in
  `spec`, all on main); the `work.after` blocks run on main and re-check `closed` and `confirm === c`; the session's
  `slowLogged` is the actor's; `healthyNow` is `@Volatile`.
- **Logging**: two new lines - "our DIDL's id on the socket ..." (once per play) and the slow check's - neither carries a
  title, an id, a uri or an ip. `SocketEvent.Metadata`'s `toString` would print a foreign object id, but no event is ever
  logged (only `host.log("sonos socket event failed: $x")`, the exception).
- **The claims' tests**: `d53d an hour of a podcast` pins 542 and 162 exactly (the session alone, no events - the note
  says confirm polls come on top); the cadence table, the tail cut, the stall's return and freshness are pinned; the
  controller's confirm by wake counts (finding 4: not their spacing); the hint through the real capture via
  `SonosEvents.Plan`; the handshake's extra subscribe and the capture's `itemId`s/object ids in `SonosEventsTest`.

## Mutations

| # | Mutation | Result |
|---|---|---|
| M1 | `wsNewer` without `awaited` (a lagging poll takes the transport back) | caught - `d53d a pause from the Sonos app` |
| M2 | `accept` re-bases the bar from a lagging poll (`lagging` ignored) | caught - the same ("nor the bar") |
| M3 | the window `<= CONFIRM_UNTIL_MS` -> `<` | caught - `d53d a confirm ends 4 s after its event` |
| M4 | a newer socket state no longer supersedes the confirm | caught - `d53d a newer state supersedes` |
| M5 | `causedByUs` ignored (our own verb's event opens a confirm) | caught - `d53d a confirm ends 4 s` ("the event's one wake only") |
| M6 | a new `itemId` never opens a confirm | caught - `d53d a new item we didn't cause` |
| M7 | a failed poll keeps the confirm open | caught - the same (its second rig) |
| M8 | no second look after the first disagreeing poll | caught - `d53d a poll that disagrees` and 53-a's IDLE-recheck test |
| M9 | no wake when the socket's health is lost | caught - `d53d the socket going unhealthy` |
| M10 | `relaxed` ignores a frozen position | caught - `SonosSessionTest` `d53d the slow check - ... a frozen position` |
| M11 | no tail cut (a flat 60 s in cruise) | caught - the cadence table and the hour's counts |
| M12 | the hint takes an unchanged `itemId` | caught - `d53d an absent or other object id` |
| M13 | the hint without our DIDL's id | caught - the same |
| M14 | `metadata()` takes a `vibb-1` from before the play went out | **NOT caught** (finding 5) |
| M15 | the parser's `Metadata` for any group | **NOT caught** (finding 5) |
| M16 | `idleGen` kept when an IDLE's confirm ends unconfirmed | **NOT caught** (finding 3's test covers it) |
| M17 | the recheck's `work.after(CONFIRM_EVERY_MS)` -> `after(0)` | **NOT caught** (finding 4) |
| M18 | the second look's `after(DISAGREE_AFTER_MS)` -> `after(60 s)` | **NOT caught** (finding 4) |
| M19 | PAUSED never relaxed | caught - the cadence table |
| M20 | the hint never shows READY | caught - `d53d the capture` |

Each run: `testDebugUnitTest --tests` `SonosControllerTest`, `SonosSessionTest`, `SonosEventsTest` (without
`-PwarningsAsErrors`, so a mutant's unused value compiles). **15 of 20 caught**; the five survivors are findings 3-5 -
the guards' absence of a test (M14-M16) and the delays nothing times (M17, M18).

## Verdict

**0 High, 2 Medium, 3 Low, 3 Nit.** The polling round does what the note says: the confirm generalises 53-a's IDLE
recheck soundly, "a lagging poll can't take back the transport" is bounded by the confirm (≤ 4 s, ended by any newer
word) and never reaches ours, the bookmark or the advance; the advance is still the poll's, once, only on our stream;
nothing starts by itself; the hint is display only; health loss and a disagreement now come back to today's cadence in
seconds; the 542 -> 162 count is pinned. Approve after findings 1 and 2 - both are what a minute between polls costs on
paths the note's reasoning didn't cover (a step's bookmark, a volume base that is no own set) - with 3-5 in the same
fix round. Owed unchanged: the note's 53-d (a)-(f) on the speakers; add to (b) a volume change on the speaker's own
buttons followed by a sleep-timer end (finding 2's fix), and to (a) a skip to the next episode 50 s after a slow poll,
then back (finding 1's).
