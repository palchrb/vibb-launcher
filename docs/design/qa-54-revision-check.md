# Design 54 - revision check

Checked on `main` at d349613c (2026-10-21): `docs/design/54-spotify-on-sonos.md` as revised (698 lines, "Revision after QA"
at the end) against `docs/design/qa-54-design.md` (14 findings), `CLAUDE.md`, `music/CLAUDE.md`, and the code the note
relies on (`M/` = `music/app/src/main/java/me/vibb/music/`): `sonos/SonosController.kt`, `SonosSuspension.kt`,
`SonosLifecycle.kt`, `SonosTransport.kt`, `SonosEvents.kt`, `SonosHome.kt`, `PlayBody.kt`, `playback/PlaybackService.kt`.
I also read the user's 54-0 files (`ws-spotify-2026-10-21.json`, `spotify-playtest-2026-10-21.txt`, in the session
scratchpad) to check §0.3. I read the body, not just the revision section. Nothing was built; the note was not edited.
Line numbers are the revised note's.

## Per finding

| # | Verdict | Evidence |
|---|---|---|
| 54-1 (High) ours vs mapped | **Resolved**, with N2 and N3 | §2.5:264-272 splits `ours` (prefix + queue still ours) from `mapped`. §2.9:373 makes `roomVerdict` OURS_LIVE for an unlisted queue track. §2.6, §2.7, §3 and §6 agree. The tests are in §3:511 and :521. The code claims are right (`parkGate` :1636, `handOff` :1618, `abandon` :1658, `roomVerdict` `SonosLifecycle.kt:48`). The way `ours` is decided has gaps (N2, N3). |
| 54-2 (High) the speaker's own advance | **Partly** | NORMAL, the belt (pollTick step 1, §2.5:292) and the `sonos-park-retry` chain (§2.9:392) are all in, with tests (§3:511, :522) and D16+. But the retry may not run for a suspended package, and then the stated fallback "the belt plus NORMAL bound the cost" (:397) is wrong. See N1. |
| 54-3 (High) the map | **Resolved**, with N4-N6 | §2.3 is the paged `Browse` read, `slots`, duplicates matched in order, and trust by version. The direction and formula are gone (§2.3:226, §6:607). §3 has the `SonosQueueTest` cases the QA listed, and D3 stays owed. |
| 54-4 (Medium) `itemId` lag | **Resolved** | §2.5:278-287: an immediate confirm, then a re-poll every `IDLE_RECHECK_MS` until a poll names another uri or `IDLE_RECHECK_UNTIL_MS`. `wsBlocked` clears only on that poll, and `playing` is paired with the `itemId`. The constants exist (`SonosController.kt:2097-2100`). The test is at §3:512. Wording: see N9. |
| 54-5 (Medium) natural vs pressed, modes | **Resolved**, with N8 | `boundary` is on our own anchor, or `endsNear` without a socket (:297-301), and `previousPositionMillis` is a cross-check only. The mode is read before any correction and a foreign mode is followed (§2.6:315-319). The test is at §3:514. |
| 54-6 (Medium) steps are not transfers | **Resolved** | §2.4:248-257: only the map trusted, this entry and this uid; the tile alone needs ours, fresh and nothing settling. The second-step test is at §3:509. `stepTo` setting `pending` first is confirmed in the code (:930). |
| 54-7 (Medium) household check | **Resolved** (better than asked) | The 54-0 run found `/status/accounts` empty on 86.10 (`ACCOUNTS service types: [] - Spotify linked: False` while Spotify plays). §2.1, §2.13, §6:613 and §7 all say "no account document, the add's answer decides". A 0-track add is a failed start before any Seek (:195). `lost()` takes a reason (:454; the code emits `CANT_PLAY_ON_SONOS` unconditionally today, `PlaybackService.kt:2289`). Wording slip: N10. |
| 54-8 (Medium) the end of our list | **Resolved**, but see N1b | §2.7:341-346: the at-end pause is armed only when a slot follows our last item; repeat all past a capped end plays our first item; `advance()` uses `queuePlay` when trusted. The "no timer at the queue's last position" branch depends on `endsNear`, which §2.11 starves (N1b). |
| 54-9 (Medium) repeat | **Resolved** | NORMAL always, repeat by our gated advance (§2.7:329-334), Q5 asked (§8), and the cost stated. One exception, N7 (context-mode shuffle). |
| 54-10 (Low) Q1 options | **Resolved** | Q1 (§8:631-637) now offers the one-pass reorder (the new default, round 54-b), the next-track move, single-track adds, correction only, and the speaker's own shuffle. The interim cost is stated. Per-move version accounting for 54-b is open (N5). |
| 54-11 (Low) logging | **Resolved** | §2.14:468-472: kinds, counts and positions in a release; ids only under `BuildConfig.DEBUG`; Browse, `metadataStatus` and `listed` never logged; `PlayBody.toString` without `listed`; `SourceScanTest` pins it. `SonosHome.log` being `Log.i` is stated in §0.4. |
| 54-12 (Low) SoCo details, goldens | **Resolved** | `SortCriteria` (§0.2:99-101), `insert_before + 1` (:102-103; I could not re-fetch SoCo offline, so I rely on the QA and the note's "checked in `_reorder_queue`"), 3079 only after a UPnP fault (:92-94), the goldens written by the real `ShareLinkPlugin` with the copy as a marked fallback (§2.2:207-212), and a throwing `builtDidl` branch (:182). |
| 54-13 (Low) cadence, battery | **Partly** | `cadenceHintS` from the playing item (§2.8:356-357) and the lock hours (§2.11:421) are done. The tail-cadence cut (§2.11:418-420) breaks the queue-end detection (N1b). |
| 54-14 (Low) accuracy | **Resolved** | §6:612 no longer calls the park vibb's; the 25 s throttle is in §0.1:59; the two code-QA passes are in §4:555. |

## The user's rules

- **vibb unless the user decided**: kept. §6 lists each deviation with a reason. §6 lacks rows for 54-c's AVTransport
  Next/Previous and speaker shuffle (N7).
- **Nothing starts by itself on Sonos**: kept. The transfer is gated twice (§2.2 steps 3 and 8). The belt only Pauses.
  The retry only Stops. The correction, the wrap and the repeat are gated `queuePlay`s. The residual is 54-2/N1.
- **The speaker's own advance at bedtime**: only partly met (N1).
- **The DIDL rule**: kept. `builtDidl` stays for `url` and `nrk_program`. The ShareLink metadata is SoCo's template, field
  for field, with SoCo-written goldens, and the rule's sentence is updated with 54-a2 (§4:561).
- **Podcasts, NRK and Storytel untouched**: kept (§0, §2.1, §7).
- **Measured facts**: I checked §0.3 against the user's files.
  - **`metadataStatus` on the Spotify queue**: right. Messages 30, 34, 36 and the rest carry `container` `spotify:playlist:<our id>`,
    `currentItem` and `nextItem` with `serviceId` 9. It is empty on IDLE and after the park (messages 5, 51). Used correctly as a cross-check, the
    confirm's target, and `ours`'s container test. N2 is about how strictly it is used.
  - **`queueVersion`**: right, 37 -> 39 after clear + add -> 40 after the park's clear, unchanged through jumps, the natural advance and
    the play mode change (messages 49-50).
  - **`/status/accounts` empty**: used correctly; nothing in the body still reads it for Spotify.
  - **"The run measured the lag (0.5 s)"** (Revision, 54-4; §2.5:283): not quite what the data says. In the run's 1 s poll the
    socket's PLAYING at +38.42 s was followed by the first UPnP poll showing the new track at +38.93 s, and the poll at +37.91 s still showed the
    old one. So the lag is between 0.5 s and 1.0 s, bounded by the poll cadence. D6+ (§5:576) still owes the real number.
  - **The run's natural advance** came after a seek to 10 s before the end (the script's "STEP seek to 10 s ... the speaker's own advance"). That
    is the same boundary, but `previousPositionMillis` 230998 is the seek's neighbour, not a full-track measurement. It is fine as a cross-check only,
    which is how the note uses it.

## New issues

**N1 (Medium) - 54-2's residual is understated, and the fallback sentence is wrong.** §2.9:392-397.
- After `suspendStop` the service calls `comeHome` (`PlaybackService.kt:2112-2131`), so no controller exists afterwards. The belt (a controller
  rule) cannot protect a missed park. If Android does not run a suspended package's WorkManager work (D16+ is exactly this question), a missed park
  leaves the rest of the queue playing, up to 1000 tracks. NORMAL only stops it at the queue's end.
- Fix: (a) change :397 to say so: "if not, a missed park lets the rest of the queue play; NORMAL ends it at the queue's end". (b) Decide with D16+
  and the user. The independent bound is a speaker-side `ConfigureSleepTimer` set at the transfer for the time rule's next edge (and cleared
  by the park). It needs no phone and no Wi-Fi at the edge. Add it as an option to D16+/Q5, or record that the residual is accepted.

**N1b (Medium) - the tail-cadence cut starves the queue-end rule.** §2.11:418-420 against §2.5:293 and §2.7:344-346. `endsNear` is set only by an ours
PLAYING poll within `ENDS_NEAR_S` (20 s) of the end (`SonosController.kt:1265`) and the STOPPED-near-the-end rule reads it (:1267). With a healthy
socket and the screen off the cadence is 15 s cruise, so a poll may not land in the last 20 s. The queue's end (our last item at the queue's last
position, "no timer") then reads as a stop we didn't make: no `ended`, no done mark.
- Fix: for this kind, arm the tail cadence also while our last item plays at the queue's last position (or while `repeat all` is on), or compute `endsNear` from the
  anchor's extrapolation as well as from polls.

**N2 (Medium) - `ours` leans on the socket's container, which is measured only for a playlist.** §2.5:265-269, §2.12:429-432.
- Only `spotify:playlist:<id>` was seen. For an album, a show, a track or an episode the container may be another form or absent, and it is absent right after
  a transfer and on IDLE. A strict "container is our target" would turn `ours` false there, which is 54-1 again (the park skips the speaker).
- Also a socket field decides a Stop gate, where design 53's rule is that UPnP decides.
- Fix: container absent or of another form is "no evidence" (not "someone else's"); only a container naming another Spotify container, or a moved
  version, makes it not ours. Say that the park's final verdict is checked by UPnP (a `BrowseMetadata` `UpdateID` against the read's) where it can be.

**N3 (Medium) - "a guest's queue is detected, not assumed" does not hold after a restart or in the standalone suspension.** §2.9:373-378, §2.12:431, Q3, §6:609.
- `renderer.json` stores only `queueUri(uid)`. The standalone `roomVerdict` has no socket, no baseline `UpdateID` and no target, and the note makes it
  OURS_LIVE for any Spotify queue track under the marker. Adopt's `GetMediaInfo CurrentURI == queueUri(uid)` does not tell a guest either, because a queue
  built in the Sonos app has the same `x-rincon-queue:<uid>#0`. The adopt's own `learnQueue` read then becomes the baseline, so a guest's queue is "ours".
- So a parent's Spotify queue in the room would be stopped at bedtime, which contradicts "the bedtime park never stops a parent's music" (§2.12:431-432).
  It is arguably the right direction for a time rule, but the note says the opposite.
- Fix: either persist the target (and the read's version) in `renderer.json` and have adopt and the standalone path compare it with one
  `Browse Q:0` (`UpdateID`) and the container, or state that after a restart and in the standalone path a Spotify queue under the marker is ours (the park stops it), and fix the
  sentences in §2.12, §8 Q3 and §6.

**N4 (Medium) - a start item not found in the queue is played at track 1 with the saved second.** §2.2:197-199 against §2.3:227-228.
- Step 7: "not found -> position `first` at 0" and step 10 then does the settled seek to the start item's `startS`. That seeks track 1 to another
  track's second, while `index` still names the start item.
- Fix: not found = play position `first` with no seek, no bookmark, index not moved, and let the read's slots correct the index (or fail the start as unplayable for
  a pick of an item with no slot, as §2.3 does).

**N5 (Low) - trust compares two version sources, and the reorder job's own edits.** §2.3:229-231, §2.6:323-325.
- The version is "the socket's `queueVersion`, else the Browse's `UpdateID` [D10: the same number?]". If D10 says no, switching source mid-session (the socket
  reconnects) compares unlike numbers. Keep the source with the value, and treat a change of source as "read again".
- 54-b makes N edits (`queueVersion` +N). The note says the version after is ours, but not how it tells its own moves from a guest's edit in between. Decide in 54-b's QA (the version after each move, or a re-read at the end).

**N6 (Low) - a partial map, and a pick of an item with no slot.**
- §2.2:211 hands back `QueueRead.partial`, and step 12 reads the rest after. A step that arrives before it finishes is "trusted"? Say that steps wait for the read job in
  actor order, or use the partial page; never a transfer (it would make a mash re-add).
- §2.3:227-228 refuses a pick of an item with no slot. For an item added to the playlist after the transfer (a sync while the room plays), a refusal stays until a restart. Prefer one transfer
  when the listing changed since the read, and refuse only for an item that a fresh transfer would not give (relinked, unplayable).
- §2.5:279: the change trigger is "a uri other than `playing`'s". Two equal uris at neighbouring positions (a duplicate) then never trigger. Trigger on `Track` or the uri.

**N7 (Low) - context mode contradicts "NORMAL always" and §6 lacks its rows.** §2.12:439-440 sets `SHUFFLE_NOREPEAT` and uses AVTransport `Next`/`Previous`;
§2.7:329 says NORMAL always and §0.1:68 lists both as "not in vibb". Add them to §6 as the one exception (54-c, with the next transfer back to NORMAL),
and keep D8+ (`Track` under SHUFFLE) as a gate for 54-c.

**N8 (Low) - a press without a socket counts as natural.** §2.5:299-301: "a press in the last 20 s counts as natural - it only adds a done mark", but "natural" also
triggers the correction (§2.6:315). A press on the speaker in the last 20 s of a track in a non-following list would be overridden. Run the correction only on the
anchor-based natural, not on the `endsNear` fallback.

**N9 (Low) - wording.** "0.5 s measured" (§2.5:283, Revision) is a lower bound (see above). §2.2:194-196 says a network failure "throws `ShareRefused`", while §2.13:454-455 gives a network failure
the ordinary `CANT_PLAY_ON_SONOS`: say the network failure is its own exception.

**N10 (Low) - the raw 54-0 files live only in a session scratchpad.** §0 header, PLAN. They hold unscrubbed ids until 54-a1 turns them into fixtures; copy them somewhere durable (outside the repo) now.

## Verdict

**Ready for 54-a1** (pure rules, SOAP, the socket's fields, the fake - nothing visible to the kid), with N2, N4, N5 and N1b folded
into the note first because they change `SonosQueue`'s trust shape and `ours` (small edits to §2.2, §2.3, §2.5, §2.11).
**Not yet for 54-a2** until: N1 is decided (the retry's fallback written truthfully, or a speaker-side sleep timer, or an explicit acceptance by the user),
N3 is decided (persist the baseline or fix the claim), and D16+ has an answer or its failure is accepted. Of the 14 findings, 12 are resolved, 2 partly (54-2 and 54-13, both
through N1/N1b), and none is left unresolved. The three High findings are resolved in design except 54-2's device-dependent residual.
