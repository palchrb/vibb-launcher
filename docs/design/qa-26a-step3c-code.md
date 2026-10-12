# QA 26a step 3c code review - room scoping (2026-10-12)

Scope: branch `music-1b-sonos`, d12a4bb..e4f827f (`600c9f0` the contract commit: server, launcher, music app and the
shared files; `e4f827f` the status). Read against design 26a §5.5, §8 step 3c and the 3c status. QA built all three:
server 508/0 (fmt clean, clippy unchanged), launcher 837/0, music 362/0; contract copies identical; scope clean.
Line numbers are e4f827f's. (Recorded by the implementer from QA's findings as handed over by the main session.)

Verdict: fix round needed. Counts: 1 High, 1 Medium, 8 Low. Decisions from the main session: (a) a group row needs
EVERY member allowed; (b) leaving a dropped room gets its own line; M1: fix it, not a documented limit. QA accepted
all 10 status choices (2 with L2, 6 with L1, 7 with H1). Checked and fine: migration 0065's trigger on every path, the
hash per phone, corrupt JSON fails closed, adopt on a forbidden room leaves, grouped-away restarts to our own uid,
the suspension and the leave share `switching`, `EMPTY_LIBRARY` harmless, the `GuardsTest` edits deliberate.

## High

1. **H1: a group row only needs its coordinator allowed, so a forbidden room can sound.** `RoomScope.kt:13-14`,
   `SpeakerCache.kt:56,123`, `PlaybackService.kt:1282` (`toRoom`), `SonosRenderer.kt:110` (the pre-play check). The
   parent allows Stua only; the household keeps "Stua + Kjøkken" grouped with Stua as coordinator; the kid taps the
   group row, `toRoom(stua)` passes, the music plays in Kjøkken. Fix (decision a): `Room.members`; `allowedRooms` =
   every member allowed; `toRoom`, `mustLeaveRoom` and the pre-play check use the same member set; invert the
   coordinator-only test, add Kjøkken leading with Stua forbidden; correct §5.5 and the owed checks; document that an
   allowed room absorbed into a group with a forbidden one leaves the sheet.

## Medium

1. **M1: the leave never re-runs when the room's group changes or the session moves.** `PlaybackService.kt:233` (the
   rooms collector only publishes), `SonosRenderer.kt:95` (`followed` only writes renderer.json). (1) A session on
   Stua (allowed); an adult groups Kjøkken (forbidden) into Stua: the music sounds in Kjøkken until the next library
   change. (2) Stua leaves the group, the stream migrates to Kjøkken and the controller follows to the forbidden uid:
   the pre-play check blocks new plays but the running stream plays on. Fix: the leave (after `rendererReady`, under
   `switching`) from the rooms collector and after a follow, with H1's member check; a `GuardsTest` pin.

## Low

1. **L1** (decision b): `PlaybackService.kt:1216` reuses `CANT_PLAY_ON_SONOS` for the leave. Own message
   `ROOM_NOT_ALLOWED` ("Rommet er ikke lenger tillatt" / "This room is no longer allowed"), mapped in `MusicRoot.kt:138`,
   emitted by the leave (sync and adopt) and the refused pick; pinned.
2. **L2**: `device_detail.html:381` - say that "All rooms" off freezes the set: "Speakers the phone finds later
   aren't allowed until you tick them."
3. **L3**: `handlers/music.rs:2660-2668`, `device_detail.html:392` - a stored room no longer reported shows its raw
   uid; label it "A speaker the phone no longer knows" with the uid in small text.
4. **L4**: `handlers/music.rs:3064,3071` - the save's 400s are bare text (server/CLAUDE.md: a 400 re-renders with the
   error); reachable when reported + stored-unreported rooms pass 32. Redirect to `#music-sonos` with a `music_notice`.
5. **L5**: `handlers/music.rs:3108-3120` - the event detail joins up to 32 phone-supplied names (~1.4 KB); clip it.
6. **L6**: `Report.kt:40` - the name cleaning differs slightly from the server's (control characters around spaces).
7. **L7**: `PlaybackService.kt:204` - `roomScope` is null until the first `libraries()` emission (the sheet shows
   every room for a moment); seed it before adopt.
8. **L8**: the tap-vs-sync race (`toRoom` passes on the old library just before `reloadLibrary`; a moment of sound,
   then a bookmarked Stop): no code; document in §5.5.

## Fix round (the implementer, 2026-10-12)

Every finding fixed, none skipped. Commits: `789506e` (server: L2-L5, L6's server test), `378926b` (music: H1, M1,
L1, L6, L7) and the docs commit (L8, §5.5, the status). Builds: server `cargo test` **508/0**, `cargo fmt --check`
clean, clippy the same 13 warnings (none new); launcher `assembleDebug testDebugUnitTest` **837/0** (no launcher
change this round); music `assembleDebug assembleRelease testDebugUnitTest lintDebug -PwarningsAsErrors=true`
**362/0** (the scope's tests rewritten in place: `RoomScopeTest` 4, `GuardsTest`'s scope pin, `ReportTest`,
`TopologyTest`), lint 0 errors and 40 warnings; `fake-sonos.py --selftest` OK; contract copies identical.

- **H1**: `Room` gains `members` (uids, coordinator first; a solo room its own uid), built in `SpeakerCache.rooms`.
  `RoomScope.kt`: `roomMembers(rooms, uid)` (the row's members, else the uid alone), `groupAllowed` (every member),
  `allowedRooms` (a row only with every member allowed), `mustLeaveRoom(room, rooms, allowed)`. `toRoom` refuses
  unless `groupAllowed(allowedRoomsNow(), roomsNow(), uid)` - `roomsNow()` reads the speaker cache (the same source
  as the sheet's rows, and fresh after a probe's topology refresh, which doesn't publish the sheet's state); the leave
  uses the same; the controller's belt uses `home.rooms.state.value` (main thread, no file read). `RoomScopeTest`:
  the coordinator-only case inverted (Stua leading, only Stua allowed: no row, refused), Kjøkken leading with Stua
  forbidden, both allowed. `TopologyTest`'s two group rows name their members. §5.5 corrected; the absorbed-room
  consequence documented there and in `RoomScope.kt`.
- **M1**: `leaveIfNotAllowed()` (after `rendererReady`, under `switching`) runs from the `home.rooms.state` collector
  (whenever the renderer is a room), from the library collector, and from `SonosRenderer`'s new `onFollowed` (after
  `host.followed` writes renderer.json). For (1) the groups must be read to be noticed: the poller never reads the
  topology, so while a room plays under a limited scope (`roomScope != null`) the service reads it every 60 s
  (`SCOPE_WATCH_MS`) with `RoomDirectory.refreshQuietly()` - one `GetZoneGroupState`, the rows published, never a
  discovery round (pinned), nothing read when every room is allowed. `RoomScopeTest` covers both scenarios' decisions;
  `GuardsTest` pins the three callers, the gate and the watch.
- **L1**: `PlayerHub.Message.ROOM_NOT_ALLOWED`, `room_not_allowed` in `values` and `values-nb`, mapped in
  `MusicRoot`; the leave and the refused pick emit it (pinned; the leave never `CANT_PLAY_ON_SONOS`).
- **L2**: the switch's line adds "Speakers the phone finds later aren't allowed until you tick them, and a group of
  speakers plays only when every room in it is ticked" (the latter for decision a).
- **L3**: "A speaker the phone no longer knows" with the uid in 12 px subtitle text (tested).
- **L4**: `?music_notice=sonos_unknown|sonos_too_many#music-sonos`; `device_card` routes `sonos_*` codes to the
  Sonos section (`SonosRoomsCard.notice`), not the card's top; nothing stored (tested). The auto-saving ticks are the
  values as entered, so the page as it was is the re-render.
- **L5**: the detail through `audit::clip(.., 300)` (tested with 32 long names: 300 characters).
- **L6**: `reportedRooms` now mirrors `cut_title` exactly: split on Unicode White_Space (`(?U)\s+`, not `trim()`,
  whose `isWhitespace` also takes U+001C-U+001F), joined with one space, then Cc out, then 40 code points. Filtering
  control characters before the split (QA's suggestion) would differ from the server for "a \u0007 b"; the same input
  (`"\u001C Stua \u0007 x "` -> `" Stua  x"`) is asserted on both sides.
- **L7**: the startup coroutine reads the scope (`roomScope = allowedRoomsNow()`) and publishes before the startup
  look and adopt, inside the `try` (the gate settles whatever happens); pinned.
- **L8**: documented in §5.5 (the race and why it is bounded).
