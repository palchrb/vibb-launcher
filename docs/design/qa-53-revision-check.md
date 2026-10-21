# Design 53 - revision check

Checked on `main` at b0b744f7 ("Design 53 revision after QA", 2026-10-21). I read every finding's fix in
`docs/design/qa-53-design.md`, then the revised `docs/design/53-sonos-grouping-precision.md` body (not only its
"Revision after QA" section), and spot-checked the code it relies on under `music/app/src/main/java/me/vibb/music/`
(`M/`). A check, not a second QA; nothing was built.

## Per finding

| # | Verdict | Evidence |
|---|---|---|
| 53-1 (High) | Resolved | §2.3 "State mapping": only PLAYING/PAUSED/IDLE are states, BUFFERING is TRANSITIONING (no `playWhenReady`, no `Paused`/`Resumed`, no USER pause that cancels the sleep timer). "Our own verb wins": `opt(now)` keeps precedence, an equal socket state confirms, a different one waits for `SETTLE_MS` or a poll. "The seek hold": `optPos` cleared only by PLAYING/PAUSED within 1 s. The three `d53` tests are in §3. Matches `classify` (`SonosSession.kt:339-345`). |
| 53-2 (High) | Resolved | §2.4 hysteresis (`sonosAnchor`: re-base within 1 s, jump at 1 s or more or on a motion change, seek, step, new item or play; every measurement re-bases, so the 60 s cap is never reached). The poll is stamped after GetPositionInfo and anchored at `RelTime + 0.5 s`, display only; `measuredAt` moves to that stamp (the note says it). `SonosAnchorTest` has the sawtooth, the 1.5 s jump, the pause and the more-than-60 s case. |
| 53-3 (Medium) | Resolved | §2.7 "Grouping is offered" needs: a room renders, our uid is a row's uid, that row's members hold our uid, a live session, not stale. Grouped away (the "Barnerommet + Stua" row, uid Barnerommet, as `SpeakerCache.rooms` builds it, `:128-133`) shows today's rows with the player's line and `OUTPUT` on a tap. The service refuses `ROOM_JOIN` for a row holding our uid. `OutputRowsTest` covers grouped away and a stopped session. |
| 53-4 (Medium) | Resolved | §2.9 "Who is a joiner": `knownMembers` (start = capped members; adopt/follow = first topology, nothing sent; later change adds). The empty-`members` start leaves uncapped rooms out so they are capped when they show. The three tests are in §3 (`d53b`). |
| 53-5 (Medium) | Resolved | §2.1: one pure `builtDidl(body, uri)` used by `SonosTransport.play` and the test; NRK through the static `NrkService.programUri(series, programId, serial)` (it exists, `NrkService.kt:58`) with a fixed serial; Storytel through `body.copy(uri = ...)` with a fixed fake URL; `sonos-play-test.py --golden DIR` writes the three files from vibb's per-kind arguments; `SonosTransportTest:144` and `PlayBodyTest:26,151-154` named. `didl()` stays untouched. |
| 53-6 (Medium) | Resolved (one gap, N3) | §2.2: `getPlaybackStatus` after the subscribe and its reply is required for health; the liveness rule (a verb of ours answered by a `playbackStatus` within 3 s, else dead); the backoff resets only after a healthy, live socket. Test in `SonosSocketTest`. The set of verbs that count is not exact, see N3. |
| 53-7 (Medium) | Resolved (one gap, N2) | `groupPending`/`groupFailed` are published state and the command keeps `RESULT_SUCCESS` (§2.7); joins and "−" on a non-coordinator run in `GroupVerbs` on IO, 2 s per call, only "−" on our coordinator in the actor; the `switching` lock is not held (§2.6). `capVolume` is `internal`, takes a `SpeakerControl` and takes no lock (`SonosTransport.kt:175`), so the join job can cap without the mutex. What orders the job against a later hand-off is open, see N2. |
| 53-8 (Low) | Resolved | §2.3 "Confirm polls, rate limited": at most once per 1 000 ms, IDLE and `Moved` at once, none while the last poll failed. Test in §3. |
| 53-9 (Low) | Resolved | §0.1 lists (a)-(g) as unmeasured, each rule says what changes if false (Revision list: (a)-(g)); §5 check 1 records (a)-(f), check 5 covers (g), check 7 reports the old coordinator's transport and TrackURI. (d): the candidate from a `groups` event is in the §2.8 table. (e): the advance guard in §2.8 and §6. |
| 53-10 (Low) | Resolved | §2.2 "Lifetime": any change of Wi-Fi network closes and reopens (cites `SonosHome.kt:35-43`; the callback is as described); the re-subscribe needs `coordinatorId` = our player; pure `socketWanted` with its test in §3. |
| 53-11 (Low) | Resolved | §2.2 "Threading": the hint goes through `work.submit { transport.hint(uid) }` (`SonosWork.submit` exists, `SonosController.kt:40-46`); §2.4: `playerShown` and health reach the session as injected `() -> Boolean`. |
| 53-12 (Low) | Resolved | §2.4: an uncounted poll leaves `frozen` and `lastPos` alone; the bookmark throttle excepts a transport change; `cadenceHintS` feeds the tail cadence only (§2.1, never `didl()`, never `Fields`/bookmark); §5 check 3 reports the cadence. |
| 53-13 (Low) | Resolved | §2.3: `idleEnd` dropped, `endsNear` decides; `skipAdvance = gen` closes the takeover race; tests for `mayPlay()` false (no SOAP), one play for IDLE + STOPPED poll + second wake, and the takeover BUFFERING are in §3. `endsNear` does hold at the end: live polls count it within `ENDS_NEAR_S` (`SonosController.kt:1039-1044`) and the tail/cruise cadences leave a poll in the last 20 s. |
| 53-14 (Low) | Resolved | §7 Q4 added with the default "as today"; §2.8 states the consequence and cites `leaveIfNotAllowed`/`leaveDisallowedRoom`. |
| 53-15 (Low) | Resolved (one wrong line, N1) | The DIDL fix is round 53-0 (§4). §4/§2.8/§3 name `GuardsTest`'s watch line; `PlayerHub.sheetOpen` is in §2.7; `Room.names` becomes pairs; `room_no_contact` English matches `headset_no_contact_row` (`values/strings.xml:82`, nb at `:80`); the trust exception is a §4 doc item. The emulator.md reference is partly wrong, see N1. |

The three user rules hold in the revised body:
- **DIDL**: `didl()` is untouched; `PlayBodies.build` sends a duration only for Storytel; `cadenceHintS` never reaches the
  DIDL (§2.1) and has a test; the goldens are generated from vibb's own construction. The note keeps "less than vibb,
  never more".
- **Nothing starts by itself / every play gated**: §2.10 says an event never starts anything; the IDLE advance is today's
  gated `advance()` (`mayPlay()` first, "Til slutten", test for no SOAP); `ROOM_JOIN` is in `Commands.PLAYS` and behind
  `playAllowed` and the scope; the cap is a volume set, not a play.
- **Follows vibb pipewire**: §6 lists every deviation (events, grouping verbs, the confirm-poll advance, the cap, the
  `+0.5 s` display anchor). No section contradicts another that I found.

## New issues

**N1 (Low) - §2.1, §3 and §4 name emulator.md `:858` as text to change, but it describes a Storytel book.**
`docs/testing/emulator.md:858` ("Book 2 on Stua ... the DIDL's `res duration` and no `albumArtURI`") is the Storytel
case, which keeps its duration after the fix. Only `:741` ("ends by itself at its DIDL duration") and the `--duration`
default at `:713` change for a podcast. Fix: drop `:858` from the list, or say "stays true (Storytel)".

**N2 (Low) - §2.6/§2.7: the join job is outside `switching` and the actor, and it checks its gates only when it starts.**
A hand-off, a call (suspension) or a follow that lands between two members of a busy-row join leaves the rest joining
to a coordinator that is no longer ours. Fix: capture the coordinator uid and the controller's generation at the job's
start and re-check `playAllowed`, the suspension and that generation before each member's verb (abort on a change). Add
the matching belt for `ROOM_LEAVE` (refuse a uid that is not a member of our group). Also say that the advance guard's
window (§2.8) reaches the session through the actor like `hint`, because the probe runs inside `transport.poll`, before
the controller sees the STOPPED.

**N3 (Low) - §2.2 liveness rule: "every play, pause, resume, seek or step" can include verbs that cause no state change.**
The speaker pushes on change only (§0.1). A pause on an already paused room, a resume on a playing one, or a seek while
paused (unmeasured, §0.1 (b)) may send nothing, and the rule would mark a healthy socket dead and reconnect. Fix: count
only a verb that was issued against a confirmed opposite state (play/resume from PAUSED or IDLE, pause from PLAYING, a
new play, a step), and exclude seeks unless §5 check 1 shows they always push.

**N4 (Low, decide) - §2.7 "live" includes PAUSED.** A "+" on a paused session stops a busy room's stream and joins it to
a coordinator that is silent, the very case the note gives for excluding stopped sessions. Either require PLAYING, or
say it is accepted (the group sounds on resume). The QA's own fix had the same wording, so this is not a regression.

## Verdict

**Ready for implementation.** All 15 findings are resolved as the QA asked, in the body and not only in the revision
section; the user rules hold; and there is no contradiction between sections. N1-N4 are Low, none blocks a round.
Fold N1 into the 53-0 brief (the emulator.md edit), N2-N4 into the 53-a/53-b briefs.
