# Code QA - design 53, round 53-b (grouping in "Spill av på", grouping done outside the app, volume in a group)

- Commits on branch `d53-b` over `main`'s `a2d66476`: `53f66044` (the verbs and `GroupVerbs`/`RoomVolumes`,
  `ROOM_JOIN`/`ROOM_LEAVE`/`ROOM_VOLUME`, the belts in `RoomScope.kt`, `groupSheet` and the sheet's block, the busy look,
  `SonosController.topology` - joiners, `knownMembers`, the baseline, `accepted`, the label, the follow's candidate, the
  advance guard -, `logic/GroupVolume.kt` and the per-room `volOpts`, `VolumeSlider`, `fake-sonos.py`'s verbs, the
  tests), `dccd93bf` (docs: the note's "53-b status", music/CLAUDE.md, emulator.md §6h, 26a, PLAN).
- Read: root and `music/CLAUDE.md`; `docs/design/53-sonos-grouping-precision.md` whole (the user's answers Q1-Q4, the
  revision check N1-N4, "53-b status" with its deviations); `docs/design/qa-53-design.md`; `docs/review/qa-53a-code.md`;
  the approved sketch `group-page/spill-av-pa-grupper.html` (boards 1-4, the bottom paragraphs as captions); vibb
  `sonosd.py`/`daemon.py` for the verbs (vibb has none; soco's `join`/`unjoin` as the note says); the full diff and the
  code around it (`SonosController` - `topology`, `baseline`, `follow`, `pollTick`'s end rule, `onSocket`, the volume
  paths; `SonosSession.maybeProbeMigration`; `SonosTransport.verb`/`capVolume`/`capMembers`; `SonosRenderer` - the
  hooks, `regroup`, `release`; `SonosActor.close`; `PlaybackService` - the rooms.state collector, `onCustomCommand`,
  `grouping`, `roomJoin`/`roomLeave`, `publish`, `lookAtRoom`, `toRoom`, `leaveDisallowedRoom`; `SpeakerCache`/`RoomDirectory`;
  `Topology.parse`'s member order; `Soap`'s exception types; `MusicViewModel.roomVolume`; `OutputSheet`/`GroupBlock`).
- Ran: `testDebugUnitTest -PwarningsAsErrors=true` (full music suite): **BUILD SUCCESSFUL, 1085 tests, 0 failures, 0
  errors, 0 skipped**. `python3 -I scripts/fake-sonos.py --selftest`: OK (4b included). Four scratch tests (not
  committed, `ControllerRig`) that reproduce findings 1-4 - each passed, i.e. the behaviour described is what the code
  does. Then 22 one-line mutations against the targeted test classes, each reverted; the tree is clean but for this file.

## Findings

**1 (Medium) - a room grouped in while no session of ours plays is accepted, so the next start plays in a room the
scope forbids.** `SonosController.topology` (`SonosController.kt:1699`) adds every joiner to `accepted` before it asks
`session`; only the cap and `knownMembers` wait for a session (1700-1703, the note's deviation "a joiner seen while no
session of ours plays (stopped) is accepted"). After a stop (`session` false, the renderer still the room), an adult
grouping a forbidden Kjøkken into Stua in the Sonos app makes Kjøkken accepted: the rooms.state collector's
`leaveIfNotAllowed` no longer hands off (`mustLeaveRoom(..., accepted)`), and the kid's next play on our row (`toRoom`'s
same-room shortcut -> `old.play()`) passes the controller's belt (`sessionAllowed` with that `accepted`) and starts the
stream in Stua + Kjøkken. Before 53-b the session left, and the belt refused. Q4 is "aksepter at den er lagt til der og
da og fortsette å spille" - a running session going on - and §2.8 says the scope still limits "every room the app
itself starts or adds". Scratch test: stop, then `topology([ours, kjøkken])` -> `accepted == {kjøkken}`. Fix: move
`accepted = accepted + outside` (and its log) inside `if (session)`; a joiner seen while stopped stays unknown and
unaccepted, so the start's `members` caps it and the belt/leave judge it by the scope as before. A `RoomScopeTest`-style
case is not enough here - add a `SonosControllerTest` `d53b` case (stopped + joiner -> not accepted; playing + joiner ->
accepted), which also kills surviving mutation M7.

**2 (Medium) - the baseline after a follow waits for a rows change that may never come, and then swallows the next
joiner uncapped; the adopt's baseline is the cache as it was.** `follow()` sets `baselinePending = true`
(`SonosController.kt:1478`) and the renderer's `followed` hook asks for a debounced `refreshQuietly`
(`SonosRenderer.kt:116`, `regroup` 198-203). But `topology()` is only ever called by the service's `rooms.state`
collector, and `RoomDirectory.view()` publishes an equal `RoomsView` that the `StateFlow` drops. With the socket the
`groups` event's own refresh (300 ms) usually lands **before** the probe's follow, so the follow's refresh finds nothing
new: no emission, no topology, the baseline stays pending. The next real change - a kid holding Play on Bad at 80 with
the cap 40 - is then taken as that baseline (`baseline(..., accept = true)`): Bad accepted, known, **never capped**
(§2.9: "a joiner louder than the cap is capped once"; §5 check 8). Scratch test: follow to Kjøkken, no topology, then
`topology([kjøkken, bad])` -> Bad's speaker got no `SetVolume`. Related: the adopt's baseline is `topology(roomsNow())`
(`PlaybackService.kt:2048`) - the cache file as the last refresh left it, possibly from before the app died, so a room
grouped in meanwhile is a "joiner" at the first fresh read and gets a `SetVolume` after an adopt (§5 check 8: "after an
app restart that adopts a playing group, no member's volume changes"). Fix (one rule for both): the baseline is the
first **fresh** topology after the adopt or the follow - in `regroup`, after `refreshQuietly()` returns, post
`topology(home.rooms.state.value.rooms)` to main whether or not the state emitted, and let the adopt call
`groupsChanged()` instead of `topology(roomsNow())` (the scope's leave after an adopt keeps reading `roomsNow()`).
Add a `SonosControllerTest` case: a follow, then the baseline's topology equal to the current rows sends nothing, then a
louder third room is capped.

**3 (Medium) - a volume raise sends a member's stale read back to it, above the cap; an older read also replaces a
newer set of ours.** `setRooms` skips a room only when its target equals what the app knows **and** that value is our
own set (`SonosController.kt:1886`: `volumeOf(u) == v && volOpts.containsKey(u)`). `roomMove`'s raise rule returns a
room at or above the cap unchanged - so for a member known only by a read above the cap (the Sonos app turned it to 80;
"never pulled down") a key press up or a "Begge" raise sends `SetVolume(80)`. If the room was turned down elsewhere
since that read, or the read is older than our own cap (below), the app itself takes it from 20 (or 40) **to 80, above
the parents' cap** - the one thing §2.12 says never happens ("a raise never takes any room above the cap"). Scratch
test: cap 40, the start caps Kjøkken to 40, a read says 80, a key up -> `RoomVolumes.set(kjøkken, 80)`. The stale read
has a second way in: `absorb` (1902-1907) drops our own set when a read differs, but `RoomVolumes.read` posts only after
**every** member answered (`awaitAll`, 1 s each), so a read taken before our cap's or our drag's `SetVolume` landed - a
slow third member delays its post - replaces the newer set with the older value (the joiner's cap: 40 replaced by the
pre-cap 80, then the next raise sends 80). Also, sending the unchanged 80 records it in `volOpts` as our own set, so a
later lowered cap (`capChanged`) pulls a louder volume set elsewhere down - against 26a §5.3. Fix: in `setRooms`, never
send a target equal to the known volume (drop `&& volOpts.containsKey(u)`), and never send one above the cap (a
target `> c` is a room the raise rule left where it is: skip it - a lower is clamped to the cap already); give the reads a generation per room (bumped by each
own set, as `volSets` is for the coordinator - the note's "volSets per uid too") and let `absorb` ignore a read started
before the room's last own set. Test: the scratch case above (no set above the cap), and a read older than a set doesn't
replace it (kills surviving mutation M14's area).

**4 (Medium) - one member whose volume can't be read disables the group's keys, the system's slider and "Begge" - our
own coordinator included.** `withRoomVolumes` (`SonosController.kt:1830-1837`) runs the key press or the system
slider's move only when **every** member's volume is known after one read; `setGroupVolume` returns when the drag's base
lacks one (1873). A member that is switched off but still in the household's topology (Sonos keeps it for a while), or
a uid the cache has no row for (`Room.members` includes those; `speakers.speaker` throws `UnknownUid`), never answers:
every press starts another 1 s read and does nothing, and the "Begge" slider is shown (the coordinator's volume is
always known, so `groupVolume()` is non-null - the deviation "shows only once that volume is known" doesn't hold) but
every drag is logged and ignored. Today's lone-room keys always worked; §5 check 8c ("a switched-off member doesn't
delay Pause") has the same spirit for the keys. Scratch test: Kjøkken read, Bad unreadable -> two key presses and a
"Begge" drag send nothing to anyone. Fix: move the rooms that are known - `groupStep`/`groupMove` already take any map,
so pass `roomVolumes()` (the known ones) after the one read, and take the drag's base from the known rooms; show
"Begge" only while two or more volumes are known. Test: the scratch case, expecting the coordinator and Kjøkken to move.

**5 (Low) - the volume slider's thumb can stay where it was let go, e.g. above the cap.** `VolumeSlider`
(`ui/Widgets.kt:340`) holds the released value while the published one differs and less than a second passed - but
that test runs only at a recomposition, and nothing recomposes the sheet a second later (the seek bar's twin, line 279,
recomposes with the position; this one has no ticker). A "Begge" drag to 80 with the cap 60 publishes a mean below 80
right after the release (within the second): the thumb stays at 80 until some other state change. Fix:
`LaunchedEffect(released) { if (released != null) { delay(1_000); released = null } }`.

**6 (Low) - the system's slider on a group doesn't move like "Begge", and music/CLAUDE.md says it does.**
`setDeviceVolume` (`SonosController.kt:1826`) computes each event's delta from the volumes as they are now, not from a
base: with one room at the cap the other rooms take the shortfall at every event (Stua 55 at the cap 60, Kjøkken 35,
slid to 60: Kjøkken ends at 60, where "Begge" gives 45), and sliding back doesn't restore the balance. §2.12 calls the
system's slider "the same way" as "Begge", and music/CLAUDE.md's "Volume on a room" says "the system's slider and
'Begge' add the same delta to every room (from the drag's base ...)". Fix: keep a base for the system's slider too (taken
at its first event, dropped after ~1 s without one), or correct the two sentences to what it does.

**7 (Low) - inside the group-change window the advance runs after the probe's first miss.** `pollTick`
(`SonosController.kt:1252`) waits only until one poll's probe ran and missed (`Fields.probeMissed`,
`SonosSession.kt:581`). The probe's candidates come from a live `GetZoneGroupState`; right after a coordinator leaves,
the new coordinator can still be missing from it (or its transport TRANSITIONING), so the first try misses and the old
coordinator starts the next episode while the new one plays the current one - exactly what §2.8's guard is for (only
when the change falls in the last 20 s of an item, hence Low). Fix: inside the window advance when the probe missed with
no tries left (`migrateTries == 0`, report it as `probeMissed`) or the window closed; the test "advances when it
misses" becomes three spaced misses.

**8 (Low) - a follow accepts every remaining member, also rooms the app put there.** `baseline(..., accept = true)`
after a follow (`SonosController.kt:1718`) accepts the new group's members other than our coordinator - but after "−" or
a hold-Play on our coordinator those are the old group's rooms, some joined by our own "+" (in scope then). A parent's
later scope that forbids such a room no longer makes the session leave (§2.8: "a member the app put there (not
accepted)" still leaves). The note's "the app put none of them there" holds for an adopt, not a follow. Fix: at a
follow keep `knownMembers` and `accepted` as they were (the stream's group moved with them); only rooms neither known
nor accepted are joiners.

**9 (Low) - "Spiller noe annet" is stale between openings.** `RoomDirectory.view()` keeps `views.value.busy`
(`SpeakerCache.kt:198`), so the sheet opens with the last opening's busy rows until the new look ends (up to 2 s), and
`lookBusy` returns without touching it off Wi-Fi (207): the old lines stay. Fix: clear `busy` at `ROOMS_REFRESH` before
the look (or at the sheet's close), and in `lookBusy`'s early return.

**10 (Low) - tests the mutations show missing.** Surviving: M7 (joiners capped without a session - finding 1's test
covers it), M13 (the 90 s grace for an accepted room that left: no test of `ACCEPTED_GRACE_MS` - add "a follow onto an
accepted room within the grace stays, after it the room is no longer accepted"), M14 (`absorb` - finding 3), M15
(`capChanged`'s membership filter: a room that left our group with an own set above the new cap is pulled down -
add it to the cap test), M20 (the `groups` event's candidate is never needed by the only test, whose topology already
opened the window and whose probe asks every room - assert `hint` reached the session, e.g. the hinted room asked first
with a `HouseProbe` that records the order), M21 (the service's `ROOM_JOIN` belt `joinOffered` - one `GuardsTest` line
pinning `!joinOffered(view, ours, c.liveSession(now), roomScope, row)` in `roomJoin`, as the scope's other belts are).

**11 (Nit) - a refused "−" on our coordinator shows "Fikk ikke kontakt".** `leaveGroup` answers `then(false)` when
`!session` (`SonosController.kt:1743`), which `roomLeave` turns into the no-contact line (`PlaybackService.kt:1917`).
Rare (the belt asked `liveSession` just before); log it and clear the pending mark instead.

**12 (Nit) - the busy look reads `controller.uid` off the main thread** (`PlaybackService.kt:582`, inside
`launch(Dispatchers.IO)`). Read `sonos.uid` before the launch, as `regroup` does.

**13 (Nit) - a topology between `joining(B)` and B's join forgets B** (`knownMembers.retainAll`), so B, once in, counts
as a joiner from outside: one extra `GetVolume` through our actor and a misleading "joined from outside" log line. Keep
the job's rooms in a `joiningNow` set until the job ends and retain those too.

**14 (Nit) - the drag id survives a drag that never ended.** `MusicViewModel.roomVolume` resets `volumeDrag` only on a
`done`; reset it in `outputSheetClosed` too, so a gesture cut off by the sheet closing can't hand its old base to the
next "Begge" drag.

**15 (Nit) - the sketch's "HØYTTALERE" label.** Boards 1-4 put a small section label between the phone's row and the
rooms; the note (§2.7, "the sheet's look stays the app's") and the string list leave it out and the app has none. Not a
bug - worth a line to the user at the device check whether it was part of what was approved.

**16 (Nit) - `fake-sonos.py`'s `join` resolves the target's coordinator before the joiner hands its own stream over**
(`join`: `coord = by_uid.get(coord.coordinator, coord)` then `hand_over`), so a coordinator joining one of its own
members ends standalone. The app never sends that (`joinOffered` refuses a row holding our uid); a comment or a fault is
enough.

## The checks, with the evidence

1. **The verbs - hold.** Join = `SetAVTransportURI` to `x-rincon:<our coordinator>` with empty metadata on the joining
   room (`SoapSpeaker.joinGroup`, the wire pinned by `SonosFixturesTest` d53); leave =
   `BecomeCoordinatorOfStandaloneGroup` on the leaving room, its reply fixture parsed; soco's verbs as §0.2 says.
   `joinOrder` = the row's members, then its coordinator (`Topology.parse` puts the coordinator first; M1). Each room
   capped before its join (M3), the uid verified, 2 s a call; a room that doesn't answer its `GetVolume` isn't joined, a
   fault on the volume alone is logged and the join goes on. A row holding our coordinator is refused in the job and in
   the service's belt.
2. **N2 and the gates - hold.** `ROOM_JOIN` is in `PLAYS` (refused while suspended at the command), and `roomJoin` asks
   `joinOffered` (live session of ours, not stale, our uid a row's uid and in its members, the row not ours, every room
   of it in the scope) and `playAllowed`; the job's `still` re-asks on main, before each room, the renderer's identity,
   `!closed`, the captured coordinator and generation, `playAllowed` and `liveSession` (M2). `ROOM_LEAVE` only for a
   member of our group of two or more (`leaveOffered`, M5). Nothing starts by itself: the verbs come only from a tap; an
   outside change only re-reads, caps, relabels or wakes a poll. A "+" on a paused session joins a silent room (N4).
3. **Where they run - holds.** Verbs on other rooms in `GroupVerbs` (IO, one `Mutex`, `SonosHome.verbs`), never in our
   actor or under the switch lock (`Commands.GROUPING` returns before the lock); "−" on our coordinator in our actor
   (`SonosTransport.leave`, `verb(ifUid)`), then `followSoon`: the hint and the window through the actor, the burst now,
   +1.5 s, +3.5 s (M22). Members' volume sets in `RoomVolumes` (a conflated channel per room on the renderer's actor
   scope, which `SonosActor.close` cancels after its drain - no leak), reads in parallel 1 s each; the coordinator's set
   in our actor. A switched-off member delays no Pause. The joiner's cap goes through our actor as §2.9 says
   (`capMembers`, 1.5 s a room).
4. **The sheet - holds, with findings 5 and 15.** `groupSheet` is pure and drawn by `OutputSheet`: offered only on a
   live session (M4); our lone room selected with "Spiller nå"/"Sonos" and no "−" (Q2, M17); our group one block with
   the group glyph, "Spiller nå i begge"/"i alle N", the "Begge"/"Alle" slider, a line per member coordinator first with
   its "−" and its own quieter slider; every other row a "+" with "Trykk for å bytte hit"/"Spiller noe annet"/the
   no-contact line, the row itself still `OUTPUT`; the Spinner while pending, published state, 8 s/30 s with a posted
   republish; grouped away: nothing selected, the holding row says "Spilles i en annen gruppe", no buttons; stale:
   today's rows. The order is design 52's (`outputSheetItems`: phone, routed outputs, headsets, rooms, rescan;
   pinned). No hint lines (pinned against the captions). TalkBack: "Spill også i X", "Ta X ut av gruppen", the sliders
   named with `setProgress`/`progressBarRangeInfo`; the row's clickable doesn't swallow the inner buttons (each its own
   clickable node). Strings in both languages, design 52's no-contact wording (pinned). The board-4 geometry fits
   320/360/411 dp (`FitTest`).
5. **Group and per-room volume - findings 3, 4, 6.** The mean (`groupMean`), an equal delta from the drag's base clamped
   per room to 0..100 and the cap, a room at the cap staying while the others rise, drag back restoring the balance
   (M9, M10), ±3 per room on the keys, a member's own slider for that room only, the coordinator through the actor and
   the others latest-wins off it, `capChanged` over the per-room sets; a lone room keeps the old rules.
6. **Outside grouping - holds, with findings 2, 7, 8.** The rooms.state collector hands the members to the renderer
   before `publish()` and the scope's look (pinned). Case 1/2: `topology` -> joiners accepted and (while a session plays)
   capped once only when louder, a room that left forgotten, the label from the rows. Case 3: `MOVED` or a `groups` event
   whose former members another coordinator holds -> `followSoon`; the session probes the hinted room first (M18) and,
   for 8 s after a group change, a STOPPED of ours that kept its TrackURI (M19); the advance waits for the probe's miss
   or the window's end (M11, M12); the follow re-baselines with nothing sent and keeps `accepted`. Case 4: the poll's
   `x-rincon:` decides grouped away; nothing is sent. Q4's four places use `sessionAllowed`/`accepted` (M6), `toRoom`'s
   same-room shortcut first; the 90 s grace keeps an accepted room through the follow onto it. Never a double advance
   (the advance's play bumps the generation; the guard only delays) and never a foreign one (the ours gate) - but see 7.
7. **The cap on joiners - finding 2.** A start's members are known (`baseline(..., accept = false)` at `START`); an
   adopt's baseline sends nothing (tested, M8); a joiner is capped once (and again only after leaving and rejoining);
   a louder volume set later in the Sonos app is never pulled down by the join rule; our own "+" caps before its
   `x-rincon:` and marks the room known first.
8. **Threading - holds, with Nit 12.** The controller's group state is main-only (`joining` hops to main, reads post to
   main, the job's `still` runs on main); `accepted` is `@Volatile` for the belt in the actor; `hint`/`groupChanged`
   reach the session under the transport's mutex; no PackageManager or file IO added on main (`lookBusy` and the
   refreshes on IO; the cap's `Bridge.config` read on main is the existing pattern of `cap()`).
9. **Battery - holds.** Reads per sheet opening (one topology refresh, one busy look over at most 8 rows, one volume
   read of the members), per `groups` event while the sheet is open (the busy look), per member change and per group key
   press while a volume is unknown (finding 4). The group watch every 60 s while a room plays without a healthy socket or
   under a limited scope (pinned) - no other polling of other rooms while the sheet is closed.
10. **Log hygiene - holds.** The new lines carry uids, counts, volumes and exception class names - no room name, title,
    ip or url.
11. **Tests - lean, no sleeping** (fake clocks, `FakeWork`, `FakeVolumes` answering at once, `TinyHttp` for the wire).
    Gaps: finding 10. **`fake-sonos.py`** - the join (a busy room's stream stops, a coordinator's members keep it on the
    next), the leave (a member stops, a coordinator's stream moves as `move`), the reply from `become_standalone.xml`,
    selftest 4b; finding 16.

## The implementer's deviations from the note

- **Member lines indented 46 dp, not 38**: accepted - the app's disc and gap put the names under the title as 38 does in
  the sketch's geometry.
- **The "Begge" drag's base keyed by a drag id (`ARG_DRAG`)**: accepted (Nit 14).
- **`absorb`: a member's read that differs replaces our own set**: accepted as the rule (a change in the Sonos app must
  show) - but its race is part of finding 3.
- **An accepted room that left stays accepted for 90 s**: accepted - needed for the follow onto it; untested (10).
- **A room that leaves is dropped from `knownMembers`, a rejoin is capped again**: accepted - a new join.
- **A joiner seen while no session plays is accepted**: not accepted - finding 1.
- **`GroupVerbs.join`: no contact on the `GetVolume`, a row holding our coordinator refused**: accepted.
- **A partly failed "+" shows the no-contact line; a "−" on our coordinator settles when it shares no group any more**:
  accepted.
- **`liveSession` doesn't wait for a play to settle**: accepted - the "+" would blink at every next episode.
- **The label from the published rows, never `home.label`**: accepted - no file read on main.
- **A `groups` event always names the holder of our former members as the candidate**: accepted (a three-poll burst
  when a member leaves); untested (10).
- **"Begge" and a member's slider only once that volume is known**: holds for the members' sliders, not for "Begge"
  (the coordinator is always known) - finding 4.
- **The busy look only while a room renders**: accepted.
- **Found, not built - the sleep timer's fade on a group fades only the coordinator**: agreed, it is in PLAN "To decide".

## Mutations

Each one change, run against the named classes only, reverted.

| # | Mutation | Tests run | Result |
|---|---|---|---|
| M1 | `joinOrder` keeps the row's order (coordinator first) | `GroupVerbsTest` | caught - 2 tests |
| M2 | the join's `still()` never asked | `GroupVerbsTest` | caught - "a closed gate, a follow between two rooms ..." |
| M3 | the joiner's cap after its `x-rincon:` | `GroupVerbsTest` | caught - 3 tests |
| M4 | `groupingOffered` without `live` | `RoomScopeTest`, `OutputRowsTest` | caught - the belts, "no + or − while ... isn't live" |
| M5 | `leaveOffered` without "two or more" (a "−" on a lone room) | `RoomScopeTest` | caught - the belts |
| M6 | `sessionAllowed` ignores `accepted` | `RoomScopeTest` | caught - both Q4 tests |
| M7 | joiners capped and known without a session | `SonosControllerTest` | **NOT caught** (findings 1, 10) |
| M8 | the adopt's/follow's baseline caps its members | `SonosControllerTest` | caught - "an adopt's baseline sends nothing" |
| M9 | `roomMove` without the raise rule at the cap | `GroupVolumeTest`, `SonosControllerTest` | caught - "a room at the cap stays there" |
| M10 | "Begge" takes a new base at every send | `SonosControllerTest` | caught - "dragging back restores the balance" |
| M11 | the advance guard removed | `SonosControllerTest` | caught - "the end waits for the probe" |
| M12 | the guard ignores `probeMissed` (always the full window) | `SonosControllerTest` | caught - "advances when it misses" |
| M13 | `ACCEPTED_GRACE_MS` = 0 | `SonosControllerTest`, `RoomScopeTest` | **NOT caught** (finding 10) |
| M14 | `absorb` never replaces our own set | `SonosControllerTest` | **NOT caught** (findings 3, 10) |
| M15 | `capChanged` without the membership filter | `SonosControllerTest` | **NOT caught** (finding 10) |
| M16 | the busy look asks our own row too | `SpeakerCacheTest` | caught - the busy look |
| M17 | a lone room drawn as a group block (a "−" on it) | `OutputRowsTest` | caught - "our lone room selected without −" |
| M18 | the probe ignores the hint | `SonosSessionTest`, `SonosControllerTest` | caught - "the hinted candidate is asked first" |
| M19 | no kept-TrackURI probe in the window | `SonosSessionTest`, `SonosControllerTest` | caught - 3 tests |
| M20 | a `groups` event never names a candidate | `SonosControllerTest` | **NOT caught** (finding 10) |
| M21 | `roomJoin` without `joinOffered` | `GuardsTest`, `OutputRowsTest`, `SourceScanTest` | **NOT caught** (finding 10) |
| M22 | the "−" on our coordinator without `followSoon` | `SonosControllerTest` | caught - "− on our coordinator ... the poll burst" |

16 caught, 6 survived. Findings 1-4 are not mutations: the current tests don't reach them (the scratch tests above).

## Verdict

**Ready to merge after one fix round; no High.** The verbs are soco's and right on the wire, members first and the
coordinator last, each capped before it joins; N2's re-checks and every gate are in place, "+" is a play, and nothing
starts by itself. The sheet follows the approved sketch (Q2, Q3, the order, the busy rows, grouped away, pending and
failed as published state, TalkBack, both languages), the four outside cases are followed as the note says, and the
threading and battery rules hold. Four Mediums should be fixed before the speaker checks of §5 (6, 7, 8, 8b, 8c), which
would otherwise meet them: the scope's exception reaching a stopped session (1, a few lines), the baseline after a
follow or an adopt (2, the cap check 8), a raise that can set a room above the cap (3, check 8c) and the group's keys
dead behind one unreadable member (4). The Lows are small and fit the same round, 10's tests especially; the Nits can go
with it.
