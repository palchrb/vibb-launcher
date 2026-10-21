# Code QA - design 53, rounds 53-0 (vibb's DIDL) and 53-a (the event socket, the bar's anchor, the IDLE advance)

- Commits on branch `d53-a` over `main`'s `dcaff335`: `a0fcb939` (53-0: `PlayBodies.build`, `cadenceHintS`, `builtDidl`,
  `sonos-play-test.py --golden`, the three `didl_*.xml`), `0ca41764` (53-a: `SonosEvents`, `SonosSocket`,
  `logic/SonosAnchor`, `SonosController.onSocket` and the poll guards in `SonosSession`, the renderer's socket and
  `PlayerHub.playerShown`, the tests), `d9024cca` (docs, `sonos-ws-probe.py --save`).
- Read: root and `music/CLAUDE.md`; `docs/design/53-sonos-grouping-precision.md` whole (as revised after
  `qa-53-design.md` and `qa-53-revision-check.md`); PLAN's design 53 entry; the full diff and the code around it
  (`SonosController` - `spec`, `position`, `pollTick`, `accept`, `playIndex`, `played`, `pause`/`resume`, `seek`,
  `stepTo`, `advance`; `SonosSession` - `tick`, `classify`, `cadenceMs`, the stall and the probe; `SonosRenderer` -
  the flush, `positionMs`, "Til slutten"'s look, `release`; `SonosHome`, `SonosPoller`, `Soap.client`,
  `logic/Addresses.kt`, `MusicRoot`'s skip pair); `scripts/sonos-play-test.py --golden`, `scripts/sonos-ws-probe.py
  --save`; vibb `pi/daemon.py` `_sonos_body` (1423-1475) and `pi/sonosd.py` `didl()`, `nrk_program_uri`, `Session.play`.
- Ran: `testDebugUnitTest -PwarningsAsErrors=true` (full music suite): **BUILD SUCCESSFUL, 1042 tests, 0 failures, 0
  errors, 0 skipped**. `python3 -I scripts/fake-sonos.py --selftest`: OK. `sonos-play-test.py --golden` into a scratch
  dir: the three files `cmp`-equal to the committed goldens; and the same three built with **vibb's own**
  `sonosd.py` `didl()`/`nrk_program_uri()`/`nrk_desc()` (imported, not the script's copy): equal byte for byte. Two
  scratch tests (not committed) that reproduce findings 1 and 2. Then 18 one-line mutations against the targeted
  test classes, each reverted; the tree is clean but for this file.

## Findings

**1 (High) - under a healthy socket the bar freezes 60 s after the last event, and "15 s tilbake", "30 s fram" and
"Til slutten"'s fade read the frozen value.** `logic/SonosAnchor.kt:39` returns the **old** anchor (its old `at`)
for a poll within the second while the socket is healthy. Events come only on a change (design 53 §0.1: "no ticks"),
so during an episode that plays on, nothing ever moves `at` forward again; `position()` hands `(a.posMs, a.at, true)`
to the spec (`SonosController.kt:490`) and `SonosSpec.positionAt` caps the extrapolation at `MAX_EXTRAPOLATE_MS`
(`SonosController.kt:137`). A scratch test (healthy link, the getPlaybackStatus-like PLAYING event, then six polls
15 s apart that agree within the second) shows **491.5 s after 90 s of play, where ~521 s is right** - the bar stops
dead 60 s after the event and stays there until the item ends or the kid pauses. This is the case on every real
speaker with a working socket. What reads it: the player's bar and the lock screen (`SonosRenderer.kt:505`), the
skip pair (`MusicRoot.kt:142`: `skipWithin(PlayerHub.positionMs() ...)` - "15 s tilbake" three minutes into an episode
seeks to *frozen - 15 s*, two minutes back), "Til slutten"'s look (`SonosRenderer.kt:308`: `left` never shrinks, so the
fade never starts and the episode ends unfaded), and the pause freeze (`SonosController.kt:703`: `frozen` = the capped
value, then the PAUSED event jumps the bar forward). The design's two sentences ("every measurement re-bases, so the
60 s cap is never reached"; "under a healthy socket a poll only re-bases when it differs by 1 s or more") were read
as "a poll within the second changes nothing", but the hysteresis already ignores a poll's *value* within the second
(it re-bases at the old extrapolation's value) - the healthy branch only stops `at` from moving. The tests miss it:
`SonosAnchorTest` "under a healthy socket a poll within the second changes nothing" pins the bug (`assertSame`), and
"the re-base survives more than 60 s" feeds events, never polls. Fix: delete line 39 (a poll within the second then
re-bases at the old extrapolation's value at its own instant, socket or not - the value is still ignored); change the
`assertSame` to "same position, `at` moved to the poll's"; add a `SonosControllerTest` `d53` case with a healthy link
and polls 15 s apart for 90 s asserting the bar at ~+90 s (the scratch test above, 15 lines).

**2 (High) - the liveness rule kills a healthy socket whenever the speaker's event beats the verb's SOAP answer to the
main thread - after nearly every play, and many pauses and resumes; the backoff never resets.** `Liveness`
(`SonosSocket.kt:177-208`) learns that an event is owed only when `owed()` is called, and the controller calls it
only from the verb's result (`SonosController.kt:684`, `:714`, `:963`), after the SOAP answer came back through the
actor. An event that reached main before that is checked by `event()` (`:368`: `liveness.seen(e.at)` with `since ==
null` -> false) and forgotten; `owe()` then waits for a second event that never comes, and 3 s later `dead()` closes
the socket and counts a failure. The speaker pushes its `playbackStatus` 0.0-0.4 s after a command (design 53 §0.1);
a pause's or a resume's SOAP answer races it, and a play's job (`SetAVTransportURI`, `Play`, the hush, the settled
seek at a start >= 5 s) answers well after the new item's BUFFERING/PLAYING arrived. Every natural advance owes (the
socket's IDLE and the poll's STOPPED agree, `socketAgrees(null, ...)`), so the socket dies ~3 s into each new episode.
`backoff.passed()` runs only for an event that pays an owed verb, so after a few of these the reconnects are 15, 60
and then 300 s apart, and the round's deliverables - the IDLE advance, the confirmed play button, a pause from the
Sonos app at once - are gone much of the time (the app falls back to polling and, with the player on screen, the 1 s
display poll). A scratch test (healthy socket; the PAUSED event at +200 ms; `owed(since = the verb's send, answeredAt
= +300 ms)`; 3 s later) shows the socket closed. `SonosSocketTest`'s liveness tests only ever call `owed` before the
event. Fix: `Liveness` keeps the last event's receipt time of the connection (`lastEventAt`, set for every
`Playback` in `event()` before the check, cleared with the connection); `owe(since, answeredAt)` returns "paid" at
once when `lastEventAt >= since` (and the socket then calls `backoff.passed()` as `event()` does); a `SonosSocketTest`
case with the event before `owed` (the scratch test, ~10 lines).

**3 (Low) - small backward steps of the bar at our own resume, and a freeze after a rebuffer.** Two places where the
anchor's "a change of motion takes the measurement at once" (`logic/SonosAnchor.kt:36`) moves the bar back:
- Our resume: while the speaker still reads PAUSED the bar runs from the tap (`SonosController.kt:492`, `maxOf(o.at,
  a.at)`), but the anchor stays "not moving"; the confirming PLAYING event is a change of motion, so the bar is put
  back to the exact position at the event's instant - back by the command's latency (0.1-0.4 s) plus the paused
  poll's half second when the anchor came from a poll (`RelTime + 0.5`). Design 53 §5 check 4 expects "pause and
  resume show no backward jump". Fix: on a PLAYING event that confirms our `optTr` PLAYING, first turn the anchor
  into the motion the bar showed (`anchor = a.copy(at = maxOf(o.at, a.at), moving = true)`), so the event re-bases
  within the second like any other measurement.
- A rebuffer we didn't cause: BUFFERING stops the anchor (`:1301`) and sets `wsBlocked` (`:1295`), so the PLAYING
  that follows is ignored (`:1308`) and the bar stands still until the confirm poll (up to ~1 s, the wake's rate
  limit), then jumps forward. Acceptable as designed (a takeover must not move the bar); noted for §5 check 4.

**4 (Low) - the IDLE's confirm poll gets one recheck, while UPnP lags a change by up to 2.6 s.** `SonosController.kt
:1189-1192`: a confirm poll that still reads PLAYING/TRANSITIONING asks once more after 1 s, then the tail's 5 s
cadence. Design 53 §0.1 measured the 1 s UPnP poll seeing a change 0.3-2.6 s after it, so the first confirm poll
(~0.1 s after the IDLE) will usually still read PLAYING and the recheck at ~1.1 s may too; then the advance waits up to
5 s more - the round's "next episode within ~1 s" holds only when UPnP is quick. As the spec says, so not a defect of
the implementation, but the spec's own measurement argues for more: recheck every second while `idleGen == gen` and
the poll still reads PLAYING/TRANSITIONING, up to `DISAGREE_AFTER_MS` (3 s) after the IDLE. Untested either way
(mutation M9 survives): add a `d53` case "IDLE, the confirm poll still PLAYING -> one `work.after` of 1 s". §5 check 3
should report the gap measured.

**5 (Low) - load-bearing lines with no test (mutations M2, M9, M10, M11 survive).**
- `logic/SonosAnchor.kt:41`'s `m.at <= old.at` guard (M2): without it a measurement older than the anchor (a poll
  stamped at GetPositionInfo's return, handed over after an event that came during its GetTransportInfo) re-bases
  the anchor back in time - a backward step. One `SonosAnchorTest` case.
- The disagreement rule's 3 s guard (`SonosController.kt:1203`, M10): it is what keeps the confirm polls - which, per
  the UPnP lag above, read the old state right after an event - from closing a healthy socket at every change. One
  `d53` case: an event, then two polls within 3 s that still read the old state -> `disagreements == 0`.
- The seek hold's 1 s tolerance on an event (`:1317`, M11): a PLAYING/PAUSED far from the target clearing the hold is
  not tested (the test's events are the pre-seek BUFFERING and a near PLAYING). One more event in the existing test.
- The IDLE recheck (M9): finding 4.
- Also untested: "no measurement after an IDLE until an ours poll" (only the BUFFERING half is), and that an IDLE is
  never the effective transport (`effectiveTransport` after an IDLE).

**6 (Low) - the host gate's "private" is the public-address guard's wider set.** `socketGate` (`SonosSocket.kt:66`)
uses `PlayBodies.privateLiteral` -> `isPrivateAddress` (`logic/Addresses.kt:38-44`), which also passes loopback
(127/8), `0.0.0.0/8`, CGNAT/tailnet `100.64/10` and `192.0.0/24`; design 53 §2.2 names RFC 1918, link-local and IPv6
ULA/link-local. The cached ip comes from the speakers' own SSDP/ZoneGroupState answers, so a planted `127.0.0.1` would
have the trust-all client dial a local port on the phone (read-only, with the public key; low impact). Fix: a
`speakerLiteral(host)` for exactly the design's ranges (or `isPrivateAddress && !loopback && !any && !100.64/10`), and
two asserts in the gate test.

**7 (Low) - a subscribe answer is told by `type` before `response`.** `SonosEvents.kt:174-181`: a message whose
`type` is `playbackStatus` goes to `playback()` even when it is the answer to our `playback:1 subscribe`
(`response: subscribe`); the ack is then never set and the socket is never healthy. The provisional `ws/` fixtures
have `type: none` on the answer (sonos-websocket's shape), and design 53 §0.1 (f) ("whether a subscribe pushes the
current status by itself") is still open. Fix: check `response == "subscribe"` first (ack it, then also hand its body
to `playback()` when its `type` is `playbackStatus`); one `SonosEventsTest` case. The capture (§5 check 1) settles it.

**8 (Low) - docs: the round's choices aren't written where the next reader looks.** Design 53 has no status section
for 53-0/53-a (26a, 26b and 26c record each step's status and the places they differ from the spec, and
`music/CLAUDE.md` points readers there); the deviations judged below live only in the commits and the code. And §5
check 10 ("a debug build logs the certificate's subject and issuer once") has no code behind it - `SpeakerTrust`
accepts and records nothing. Fix: a "53-0 / 53-a status" section in the note (the accepted deviations below, the
fixes of this QA); the check-10 log in `SpeakerTrust.checkServerTrusted` under `BuildConfig.DEBUG`, once per process,
subject and issuer only - or drop check 10 from §5.

**9 (Nit) - `sonos-ws-probe.py --save`.** It writes JSONL, while the `ws/` fixtures and `SonosEventsTest` read JSON
arrays: the README's "replaces them in one commit" needs a conversion step - say so (or let `--save` write the array).
IPv6 literals are not scrubbed (a `fe80::` address can carry the speaker's MAC) - add a pattern; and every household
id maps to the same fake one (fine for one household).

## The checks, with the evidence

1. **53-0, the DIDL - holds.** `PlayBodies.build` (`PlayBody.kt`): `url` and `nrk_program` carry `durationS = null`,
   Storytel the shelf's `dur_s`; `art` is vibb's http-only rule and the controller passes `art = null` (the phone holds
   hashes only) - as `_sonos_body` (daemon.py 1423-1475: a `duration_s` only in the Storytel branch; NRK through
   `nrk_program` with the show class, the protocol and the desc; Storytel's `art_url` is not sent - less than vibb,
   never more, as the note decided). `builtDidl(body, uri)` is the one builder (`SonosTransport.play` for both kinds,
   the minted URL for a book). The goldens equal vibb's own `didl()` output byte for byte (checked above), and
   `PlayBodyTest` compares `builtDidl(PlayBodies.build(...))` with each. `cadenceHintS` never reaches `didl()` (tested
   with a hint of 99 999 s) and is read only by `SonosSession.played` -> `cadenceMs`, ours only, null after an adopt.
   `didl()` untouched; the fake reads `res@duration` as before (selftest OK); emulator.md §6h updated.
2. **The event -> state mapping - holds.** BUFFERING never sets `wsTr` or `optTr`: it shows the phase BUFFERING with
   the held `playWhenReady` (`spec`, `wsBuffering() && fresh && ours`), so no `IsPlaying` flip, no `Paused`/`Resumed`,
   no USER `pauseCause`; IDLE is never effective (only `wsBlocked`, `idleGen`, the wake). Our own verb wins: an event
   opposite to `opt(now)` returns before the gate and the measurement (`:1307`), a matching one clears `optTr` and `frozen`. Outside a
   verb a PLAYING/PAUSED newer than the last poll is the effective transport (`wsNewer`) and a measurement, under the
   gate fresh + ours + no play pending + not blocked; an answered play's event is parked and applied by the landing
   poll (only when it landed), one before the answer dropped. `Health(false)` forgets the socket's state. Each part
   has a test that a mutation breaks (M3, M4, M8).
3. **The advance on IDLE - holds, with finding 4.** The IDLE only wakes a confirm poll; `pollTick`'s existing rule
   (`STOPPED && endsNear > 0 && playing != null`, ours and fresh) decides, then `advance()` with `mayPlay`, "Til
   slutten" first and repeat (tested, also with `mayPlay` false: no SOAP). No double advance: the advance's
   `playIndex` sets `pending`, so a second wake's poll settles and returns (tested: one `seturi`). Never on a foreign
   stream (the ours gate) or `x-rincon:` (tested). `skipAdvance` (an uncaused BUFFERING/PLAYING after the IDLE) skips
   exactly that STOPPED and is cleared by the next ours live poll (tested, M5).
4. **The anchor - findings 1 and 3.** Within a second, same key and motion: re-based at the old extrapolation (no
   stutter; the sawtooth test is monotone with no stall); a second or more, a seek (`seekAt` in the key), a step, a new
   item or a play's seed: at once. The 60 s cap: finding 1.
5. **The poll guards - hold.** Freshness keeps the normal cadence (`measuredCadenceMs = lastCadence`, never the 1 s
   one; tested), so the screen going off doesn't read stale and the locks stay; the stall counts polls >= 5 s apart and
   an uncounted poll returns before touching `frozen`/`lastPos` (M16); the probe's tries >= 1.5 s apart, re-armed by
   an ours live poll; the aux at most every 60 s (12 polls at 5 s = 60 s, so the normal cadences are unchanged); the
   poll's bookmark at most every 5 s unless the transport or the stream changed (M6); `measuredAt` at GetPositionInfo's
   return (tested with a slow GetTransportInfo). The 1 s poll only while shown + not healthy + ours PLAYING fresh
   (M17), and the flag turning on wakes the poller (`SonosRenderer` init).
6. **The socket - holds, with findings 2, 6 and 7.** TLS trust: `SpeakerTrust` lives only in `SonosSocket.kt`, used
   only by `speakerSocketClient` (the SOAP client's `newBuilder()`: unbound, `Proxy.NO_PROXY`, **`followRedirects(false)`
   inherited**, so a 3xx to the upgrade is an HTTP failure and a park, never a dial elsewhere), with a verifier for
   exactly the gated ip; a fresh `SSLContext` per connection makes its `Address` unique, so no other client reuses the
   connection (`SourceScanTest` d53). Only the cached ip of `controller.uid` is dialled, built with `HttpUrl.Builder`;
   a `MOVED`'s or a player's `websocketUrl` is read for matching only (no `wss://` literal, pinned). Lifetime: `want()`
   from every flush (`socketWanted`: `session.armed` - volatile -, Wi-Fi, not closed or released, not parked), a new
   uid or `Network` re-targets (B's `onAvailable` before A's `onLost` is a re-target, A's late `onLost` is a no-op),
   `release()` closes it and drops late posts (`released`, `removeCallbacksAndMessages`, stale connection ids). Backoff
   1-2-5-15-60-300 s, reset only by `passed()` (M13); HTTP and five refused connects park 6 h (process memory). The
   liveness rule counts only verbs against a state the socket and a fresh ours poll agree on (N3, M12) - but see
   finding 2. Threading: the listener's `Plan` lives on OkHttp's reader thread (`onOpen` and `loopReader` share it),
   `send` from there, everything else posted to main; `healthy` is `@Volatile`, read by the session through the
   injected reader; the session's own state stays under the transport's mutex.
7. **Battery - holds.** No socket unless the session is armed (the disarm after 10 min idle, a stop, the hand-off and
   the release close it); none while the phone renders (only `SonosRenderer` owns one) and none on the standalone
   suspension path. The 30 s ping only while it is open.
8. **Log hygiene - holds.** The new lines carry fixed words, uids, states, exception class names and HTTP codes - no
   ip, title, room name, url, body or key (`SonosSocket`, `onSocket`, `agreement`, the skip line). `PlayBody`'s
   `toString` adds `cadenceHintS` only.
9. **Tests - lean and meaningful, no sleeping** (fake clocks, a fake `WebSocket.Factory`, a delayed-block list run on
   demand). Gaps: findings 1, 2 and 5.

## The implementer's deviations from the note

- **`socketWanted` reads the session's own `armed` (now `@Volatile`) at every flush** instead of `Fields.armed` from
  each poll result: accepted - one volatile read, the same truth, and it also catches a disarm by `stop()` at once.
- **One parked event (the last), applied only when the landing poll saw the play land**: accepted - the last state is
  the one that matters, and an unlanded settle's event belongs to no known stream.
- **BUFFERING stops the anchor only outside a verb of ours** (`opt(now) == null`): accepted - under a resume or a
  seek the verb's intent and the seek hold are what the bar shows.
- **The poll's bookmark throttle also lets a changed stream through** (`bmStream`): accepted - another item is
  another bookmark.
- **`SourceScanTest`'s "no Network binding" scan allows `tls.socketFactory` in `SonosSocket.kt`**: accepted - it is
  the TLS factory over the client's own unbound sockets, and the d53 test pins where the trust lives.
- **`--save` replaces names too** (rooms, groups, titles), beyond "ids replaced": accepted, better (finding 9).
- **The goldens' generator uses the script's copy of vibb's `didl()`**: accepted - checked equal to vibb's own
  `sonosd.py` output above; the copy's header says it is field for field.
- **The host gate's private set** (`isPrivateAddress`): flagged, finding 6.

## Mutations

Each one line, run against the named classes only, reverted.

| # | Mutation | Tests run | Result |
|---|---|---|---|
| M1 | the anchor's jump threshold x5 | `SonosAnchorTest`, `SonosControllerTest` | caught - 2 anchor tests |
| M2 | an older measurement re-bases (no `m.at <= old.at` guard) | `SonosAnchorTest`, `SonosControllerTest` | **NOT caught** (finding 5) |
| M3 | `spec` ignores the socket's BUFFERING | `SonosControllerTest` | caught - "BUFFERING during a seek or a resume" |
| M4 | our verb no longer wins over an opposite event | `SonosControllerTest` | caught - "our own verb wins" |
| M5 | `skipAdvance` ignored | `SonosControllerTest` | caught - "the takeover race" |
| M6 | the bookmark throttle ignores a changed transport | `SonosControllerTest` | caught - "a pause from the Sonos app" |
| M7 | an event wakes a poll after a failed poll | `SonosControllerTest` | caught - "the event wake" |
| M8 | the answered play's event isn't parked | `SonosControllerTest` | caught - "parked and applied by the landing poll" |
| M9 | no recheck after an IDLE's confirm poll reads PLAYING | `SonosControllerTest` | **NOT caught** (findings 4, 5) |
| M10 | the disagreement rule without its 3 s guard | `SonosControllerTest` | **NOT caught** (finding 5) |
| M11 | any PLAYING/PAUSED event clears the seek hold | `SonosControllerTest` | **NOT caught** (finding 5) |
| M12 | a pause owes an event on any healthy socket (N3 off) | `SonosControllerTest` | caught - the N3 liveness test |
| M13 | the backoff resets on any event | `SonosSocketTest` | caught - the backoff test and a liveness test |
| M14 | the gate without the private check | `SonosSocketTest` | caught - the gate test and "refused before any connect" |
| M15 | `playback:1` re-subscribed for a group we don't coordinate | `SonosEventsTest` | caught - the re-subscribe test |
| M16 | the stall counts every poll | `SonosSessionTest`, `SonosTransportTest` | caught - "an uncounted poll leaves the stall count alone" |
| M17 | the 1 s poll under a healthy socket too | `SonosSessionTest` | caught - "the 1 s display poll only while ..." |
| M18 | a Storytel book's DIDL loses its duration | `PlayBodyTest` | caught - the golden and storytel_sonos 2/9 |

Findings 1 and 2 are not mutations: both are bugs the current tests pin or don't reach (scratch tests above).

## Verdict

**Not ready to merge as it stands: two Highs, both small fixes.** 53-0 is right: vibb's DIDL field for field, and the
goldens equal vibb's own output byte for byte. 53-a's design is followed closely, and the mapping, the advance, the
poll guards, the trust and the lifetime hold. But on a real speaker with a working socket the bar freezes after a
minute, and the skip pair and "Til slutten" act on the frozen value (finding 1: delete one line, change one test, add
one). The liveness rule also closes the socket after most plays (finding 2: remember the last event, ~10 lines plus a
test). Both must be fixed before the merge and before the speaker checks of §5: check 4 would fail on finding 1, and
checks 3 and 5 would measure finding 2's churn instead of the design. Findings 3-7 are small and fit the same fix
round (5's tests especially: M10 guards against the same kind of socket churn). 8-9 are docs and can go with it or
with 53-b.
