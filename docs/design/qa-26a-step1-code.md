# QA 26a step 1 code review - the Sonos core (2026-10-11)

Scope: branch `music-1b-sonos`, d0dc35d..a01bd05 on the spec commit d289371 (`music/app/src/main/java/me/vibb/music/sonos/`,
its tests and fixtures, the manifest, the debug feed's `sonos.json`, `scripts/fake-sonos.py`, `docs/testing/emulator.md`
§6g, NOTICE.md and the step 1 status). Read against design 26a (§0-§9, the QA decisions, the user's answers, the step 1
status and its twelve choices), design 26's working rules, the CLAUDE.md files, and palchrb/vibb `pipewire` (`1fcadac`:
`pi/sonosd.py`, `pi/vibb/renderer.py`, `pi/daemon.py`'s `_sonos_body` and `sonos_start_target`, and `tests/sonos_*.py`).
Paths below are under `music/app/src/main/java/me/vibb/music/sonos/` unless they say otherwise; line numbers are a01bd05's.

Build: CI run 38167939758 on the branch built debug and release, R8, lint and `checkDebugHasDebugFeed`; the unit-test
compile failed under `-PwarningsAsErrors=true` (finding 0 below). The tests themselves ran in the implementer's pure-JVM
harness (98 green). No emulator or speaker here.

Counts: 2 High, 2 Medium, 9 Low, plus the CI compile error.

## 0. CI: the unit-test compile

`music/app/src/test/java/me/vibb/music/sonos/SonosFakes.kt:15:9 Only safe (?.) or non-null asserted (!!.) calls are
allowed on a nullable receiver of type 'File?'` in `compileDebugUnitTestKotlin` and `compileReleaseUnitTestKotlin`:
android.jar marks `File.getParentFile()` nullable, the JDK leaves it a platform type, so the harness couldn't see it.

## High

1. **High: a new play can be disarmed by its own first poll.**
   - Where: `SonosSession.kt:236-272`. `downSince` and `fails` survive `played()` (181-194) and `adopt()` (197-210); they
     are reset only while unarmed.
   - Scenario: an episode ends (STOPPED, uri retained) and the session stays armed, so the down streak starts. 9 min
     55 s later the kid plays another episode. Its first poll reads TRANSITIONING -> STOPPED (Medium 1), a real STOPPED,
     or fails: `now - downSince >= 600 s` -> `Disarmed`, on a speaker that is playing. A stale `fails` also makes the
     new room's first failure back off 300 s and read UNREACHABLE at once instead of three BUFFERING polls.
   - vibb has it latent (`down_since` is local to `poll_loop`, which never sees a new `/play`); no reason to port it.
   - Fix: reset `downSince = null`, `fails = 0`, `lastTr = null` in `played()` and `adopt()`. Test: a play after 595 s
     STOPPED stays armed; its first failed poll backs off 10 s.

2. **High: a failed first poll after a play publishes the previous session's measurement as ours.**
   - Where: `publishUnreachable` (`SonosSession.kt:356-362`) copies the old snapshot with `measuredAt = lastOkAt`;
     `played()`/`adopt()` reset neither `lastOkAt` nor the measured fields.
   - Reproduced: ep1 polled at 0:23:50, ep2 played, its first poll failed -> the snapshot says `uri = ep1`, `ours = true`,
     `relS = 1430`, `fresh = true` while `session.uri = ep2`. Step 3 would bookmark ep1's near-end position onto ep2; a
     Wi-Fi blip after an auto-advance or a room switch is enough.
   - vibb's failed publish drops every measured field; "keep the last values" holds within one session only.
   - Fix: in `played()`/`adopt()`, `lastOkAt = null` and `snapshot = Fields(armed, uid, kind, seq, measuredAt = null)`.
     Test: a failed first poll after a play has `ours == false`, `relS == null`, `measuredAt == null`.

## Medium

1. **Medium: TRANSITIONING inherits a stale `lastTr`, then the poller sleeps 15-60 s.**
   - Where: `classify` (280-286) never resets `lastTr` per session; `cadenceMs` (369-386) gives PAUSED 15 s and STOPPED
     60 s whatever `fastAt` says.
   - Scenario 1: a new episode at 0 buffers; the state reads STOPPED from the last episode's end -> the next poll in 60 s.
   - Scenario 2: a resume from PAUSED reads TRANSITIONING -> reported PAUSED -> 15 s. §4.3's `optTr` expires after 8 s,
     so the player shows "paused" for 8-15 s after every resume.
   - Fix: reset `lastTr` in `played()`/`adopt()`; poll at `POLL_MS` whenever the raw state was TRANSITIONING, or inside
     the verb/arm window whatever the state; record the cadence change as a §9 deviation. Test: two sessions in a row,
     and a resume.

2. **Medium: `Fields.fresh` stays true while unreachable, and flickers.**
   - Where: `SonosSession.kt:110`: freshness = `measuredAt + nextPollMs + 10 s`, but after a failure `nextPollMs` is the
     backoff (10..160 s) while `measuredAt` stays at the last good poll -> fresh at every failed poll for 165 s, stale
     in between.
   - Fix: anchor freshness on the cadence chosen after the last SUCCESSFUL poll (store it), or require `reachable`; fix
     §2.4's wording. Test: failures 1-6 never extend freshness.

## Low (fix now; step 3 would otherwise undo them)

1. **Low: non-SonosExceptions escape; `Location` accepts hostnames.** `Soap.url` (Soap.kt:88): `HttpUrl.Builder.host()`
   throws IllegalArgumentException on a bad host. `Topology.location` (Topology.kt:66-71) accepts any `[^/:\[\]]+`, so an
   odd Location gets cached; then `RoomDirectory.refresh` (catches IOException only, SpeakerCache.kt:179-186) throws on
   every sheet open, and plays and verbs throw outside the failed-play path. A hostname would also resolve outside the
   Wi-Fi binding. `requireNotNull` at SonosTransport.kt:87 is the same class. Fix: only IP literals in `location()`, IAE
   -> `SpeakerUnreachable` in Soap, a `SonosException` instead of `requireNotNull`.
2. **Low: API shape for step 3.** `Soap(client)` (Soap.kt:51) freezes one Wi-Fi `Network`'s socket factory: after a
   reconnect every call fails (ENONET) until Soap, CachedSpeakers, HouseholdProbe and the NrkService/RoomDirectory
   lambdas are rebuilt. `SoapSpeaker`/`SpeakerResolver` have no per-call timeout, and §4.6/§4.7/§6.1 need a 1.5 s
   classify. Fix: `Soap(() -> OkHttpClient)` or a delegating SocketFactory, and `SoapSpeaker(..., timeoutMs)`.
3. **Low: `RoomDirectory` never says a round finished** (§2.1: "the rows refresh when it ends"): add an `onChanged`
   callback or a `StateFlow<RoomsView>`.
4. **Low: `rescanInBackground` (SpeakerCache.kt:189-202) sets `scanning` before `scope.launch`**; a cancelled scope never
   runs the body or its `finally` -> `scanning` wedged true for ever (reproduced). Clear it in `invokeOnCompletion` or use
   `CoroutineStart.ATOMIC`.
5. **Low: names step 3 would rename.** `sonos.Player` (Topology.kt:15) collides with Media3's `Player` in the package of
   the planned `SonosRenderer`; `RendererState` (the renderer.json record) collides with §3.3's
   `PlayerHub.State.rendererState` enum. Rename now (e.g. `Zone`, `RendererChoice`) and update the spec's §3.3/§4.1.
6. **Low: PlayBody edges.** `java.net.URI(url)` (PlayBody.kt:138) rejects enclosure URLs OkHttp and ExoPlayer accept (an
   unencoded space, `|`) -> use `toHttpUrlOrNull()`. An NRK series item with an empty key falls through to `url` with
   its HLS manifest (123-126) -> drop it. Numeric shorthand hosts (`10.1`, `167772161`) pass `privateLiteral` -
   acceptable; say so in the comment.
7. **Low: `SonosSession.snapshot` is a plain `var` read across threads**: `@Volatile`, or document that main reads only
   the copies the actor posts.
8. **Low: the fake never runs in CI**: add `python3 -I scripts/fake-sonos.py --selftest` to `music-build` in
   `.github/workflows/music.yml` and the script to its change filter; update root CLAUDE.md's CI paragraph and its
   `scripts/` list.
9. **Low: test margins.** `SpeakerCacheTest` 1 and 2 (lines 76, 87) give Dispatchers.IO 1 s to start the round; wait on
   the rig's latch or poll up to 5 s like `waitForRound`.

## Confirmed

The rest holds line by line against vibb: `SonosUris`/`Didl` are byte-identical to vibb's Python output; `Soap`,
`Topology`, `Ssdp`, `SpeakerCache`, `SonosTransport`, `NrkService`, `PlayBody`, the two Android wrappers, the repo rules,
§7's coverage, the fake and §6g. The step 1 choices 1 and 3-11 are justified; 2 is fine as a shape but enables Medium 2;
12 (the live classify deferred) is fine given Low 2's timeout parameter.

## Fix round (the implementer, 2026-10-11)

Every finding is fixed; none skipped. Tests 98 -> 109.
- **0**: `parentFile!!.list()!!` in `SonosFakes.fixtureNames()` (compiles on both classpaths).
- **H1, H2, M1 (the reset)**: one `newSession()` called by `played()` and `adopt()` resets `downSince`, `fails`, `lastTr`,
  `lastOkAt` and the cadence anchor, and replaces the snapshot with an unmeasured one (`measuredAt = null`).
  `SonosSessionTest`: "H1 - a play after 595 s STOPPED stays armed, and its first failed poll backs off 10 s", "H2 - a
  failed first poll after a play never shows the last episode's position as ours" (an adopt too).
- **M1 (the cadence)**: `Fields.transitioning` records a raw TRANSITIONING; `cadenceMs` returns 5 s whatever the state
  while it is set or inside the 30 s verb/arm window (UNREACHABLE keeps its backoff). Tests "M1 - two sessions in a row"
  and "M1 - a resume that reads TRANSITIONING re-polls in 5 s, not 15"; the cadence and migration tests follow the rule.
  Design 26a §2.4 and a §9 row record the deviation.
- **M2**: `Fields.measuredCadenceMs`, the cadence chosen after the last successful poll, kept by failed polls;
  `fresh()` uses it. Test "M2 - failed polls never extend freshness" (failures 1-6; stale from the third). §2.4 and §9
  reworded.
- **L1**: `Topology.location` matches IP literals only (`isIpLiteral`: a dotted quad or an IPv6 literal); `Soap` refuses
  a non-literal host before any request and maps HttpUrl's IllegalArgumentException to `SpeakerUnreachable`; an
  `nrk_program` body without its series or program id throws `SonosException`. Tests in `SonosFixturesTest`,
  `TopologyTest` and `SonosTransportTest`.
- **L2**: `Soap(() -> OkHttpClient)`, with the old constructor kept; `SoapSpeaker(..., budgetMs)` gives every call the
  rest of one shared budget (spent: `SpeakerUnreachable`, nothing sent); `SpeakerResolver.speaker(uid, verify,
  budgetMs)` and `CachedSpeakers` pass it on (the uid check too). Tests: the provider is asked per call; a 1.5 s answer
  is cut by a 0.3 s budget, and a spent budget sends nothing.
- **L3**: `RoomDirectory.state: StateFlow<RoomsView>`, published by `view()`/`refresh()`, when a round starts and when
  it ends (the new rows; stale only if no household answered). `SpeakerCacheTest` 3 checks it.
- **L4**: the flag is cleared in the job's `invokeOnCompletion` (the flow first, then the flag). Test "L4 - a scope
  cancelled before the round ran never wedges scanning".
- **L5**: `Zone` and `RendererChoice` (`RendererChoice.kt`), in code, tests, the debug receiver and the spec (§1, §3.3,
  §4.1, §8; §3.3's UI enum keeps `RendererState`).
- **L6**: `speakerDropReason` reads the URL with `toHttpUrlOrNull()`; "no program id" for a key-less series item;
  `privateLiteral` takes `HttpUrl.host` and its comment accepts numeric shorthand with the reason. Test "L6 - the URL as
  OkHttp reads it".
- **L7**: `@Volatile` on `SonosSession.snapshot`, documented.
- **L8**: `music.yml` runs the selftest after the build (from `music/`) and watches `scripts/fake-sonos.py`; root
  CLAUDE.md updated.
- **L9**: the rig counts down a `started` latch when the round begins; tests 1 and 2 wait up to 5 s on it.

Verified: the JVM harness (Kotlin 2.1.20, warnings as errors) ran the 109 tests green plus the interop test against
`scripts/fake-sonos.py`; `SsdpScanner.kt`, `SonosFiles.kt` and `DebugFeedReceiver.kt` type-check against the android.jar
stubs; `python3 -I scripts/fake-sonos.py --selftest` OK. The Android build and `testDebugUnitTest` are CI's.
