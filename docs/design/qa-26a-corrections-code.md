# QA 26a correction round code review - after the main session's review (2026-10-12)

Scope: branch `music-1b-sonos`, `9ecdad8..6fb433e` (`c489ad4` no bound socket, `8c43b0c` the start cap, `b4c04c6` the
bedtime looks, `0187c5d` + `c9f8881` tests, grouped-away position and the fake, `6f5aca6` unknown members and tailnet
audio, `122a53c` the PWA line, `6fb433e` the 26a section). Read against 26a §2.1, §2.2, §3.2-3.3, §4, §5.3, §5.5,
§6.1, §6.4-6.5, §7 and the "Correction round" section; the launcher's `KidVpnService.kt` / `AppEnforcer.kt`; vibb
(`pi/daemon.py` `status()` 2770-2810, `tests/sonos_same_tile.py`, `sonos_migrate.py`, `sonos_grouped.py`,
`sonos_hold_scan.py`). Line numbers are `6fb433e`'s.

QA ran, from the worktree: music `./gradlew testDebugUnitTest lintDebug -PwarningsAsErrors=true` **378 tests, 0
failures, 0 errors**, lint clean (BUILD SUCCESSFUL); `python3 -I -B scripts/fake-sonos.py --selftest` OK (all five
sections). Server `cargo test` not re-run: the only server change is two template sentences, and the one test that
reads that card (`src/tests/music.rs:2370`, "The phone hasn't seen any speakers yet.") still matches the unchanged
prefix. Mutation runs (deleting the rules to see the tests go red) were **not** possible: this session's permission
classifier refused even a temporary edit of product sources, so the "red without the rule" answers below are from
reading each test against the code, not from running mutants.

Verdict: **the five points are done correctly; merge after one hygiene fix (M3) and a decision on M1 and M2 (fix, or
write them down as limits).** Counts: 0 High, 3 Medium, 5 Low.

## Medium

1. **M1: a cap lowered during a session never reaches a louder speaker (the override or the pause ends at 20:00, the
   room plays on at 70).** The cap is read only when we send something: a start (`SonosController.kt:733`), a key or
   the slider (`setVolume`, `SonosController.kt:1117-1128`), the ramp's restore (735). The restriction-change broadcast
   (`MusicApp.kt:34-43`) re-reads the config but nothing tells the controller. Path: the parent's offline override (or
   the restrictions pause) lifts the cap; the kid turns the room to 70 with the keys - our own set, allowed then; the
   override ends, `volume_cap_pct` comes back as 40; the speaker stays at 70 until the next start, possibly a whole
   audiobook. On the phone the same edge pulls `STREAM_MUSIC` down at once (`L/music/VolumeCap.kt`: "set back at
   once, on every volume change ... and whenever ... the cap in force" changes via `refresh`). §5.3 says "every set is
   clamped to the cap in force" and "a louder volume set elsewhere is not pulled down", but is silent on the cap edge
   itself, and 70 here was **our** set, not an external one - so vibb's "never fight an external change" doesn't cover
   it. Fix (small): on `ACTION_APPLICATION_RESTRICTIONS_CHANGED`, if a room session is live and the new cap is below
   `volOpt` (our own last set), send `setVolume(cap)` once through the controller (it clamps already); a louder volume
   from the Sonos app (`volOpt` null or at/below the cap) stays untouched, so §5.3's rule holds. Test: start, keys to
   70 with no cap, cap 40 pushed -> one `volume 40`; Sonos-app 80 with `volOpt` 30 -> nothing. Or, if the main session
   prefers vibb's letter, write in §5.3 that a lowered cap applies from the next press or start.
2. **M2: the start cap caps only a group's coordinator; the other rooms in the group start louder than the cap.**
   `capVolume(spk, cap)` (`SonosTransport.kt:147-162`) reads and sets `spk`, the coordinator (`body.uid`), through
   RenderingControl (`Soap.kt:208, 220-224`), which is that one speaker's volume (SoCo's `spk.volume`, vibb the same).
   A group row ("Kjøkken + Stua", 26a §5.5) is a legitimate pick; Stua left at 80 plays our stream at 80. §5.3 and
   `music/CLAUDE.md:118-120` say "our stream never starts louder than the parent allows" - true for a solo room only.
   (The keys have the same coordinator-only reach since 3a; that is vibb's model and not new.) Fix: at a start, cap
   every member of the row (`roomMembers(rooms, uid)` minus unknown uids; each a `GetVolume`/`SetVolume` to its own
   ip from the cache, the same best-effort catch), inside the hush as now; or document the limit in §5.3 and the
   device check ("a group's other rooms keep their own volume"). The first is ~15 lines plus one controller test.
3. **M3: a compiled Python file is committed.** `c9f8881` adds `scripts/__pycache__/fake-sonos.cpython-313.pyc`
   (48 KB, binary); the root `.gitignore` has no `__pycache__/` rule. It is a local build artifact of one Python
   version, will go stale with the next edit of the fake, and would land on `main` with the merge. Fix:
   `git rm --cached scripts/__pycache__/fake-sonos.cpython-313.pyc` and add `__pycache__/` to `.gitignore`, in one
   commit on this branch.

## Low

1. **L1: `volOpt` can be set above what the speaker holds.** `SonosController.kt:756` and `783` take `res.volume` (the
   cap set before Play) as the base of the next press, but the same job then puts back a ramp leftover (`restore`,
   735-745) that can be lower: base 30, cap 50, the process died mid-ramp, someone set 80 in the Sonos app, a start ->
   cap 50, then restore 30; `volOpt` = 50, the snapshot shows 50, the speaker is at 30, and the next "down" sends 47.
   Likewise a key press while the start is in flight sets `volOpt` (1120) and is then overwritten by the play's
   result. Rare both. Fix: `volOpt = restore ?: res.volume` when the restore succeeded (and `session.ownVolume` with
   it), and take `res.volume` only if `volOpt` hasn't changed since the submit.
2. **L2: the bedtime Stop and park are unbudgeted after a late answer.** The looks stop at 6.5 s (`SonosSuspension.kt:32,
   48`), but `transport.park(uid)` (`SonosLifecycle.kt:145`, `SonosTransport.kt:256-266`) is one actor verb with two
   blocking SOAPs at the client's 2 s connect / 5 s read. A speaker slow enough to need the second look can answer at
   ~6.4 s and then take seconds over Stop; `withTimeoutOrNull(9 s)` (`MusicApp.kt:77, 89`) cannot interrupt a blocking
   call, so `pending.finish()` lands late and the park/renderer.json write are cancelled (the room stays recorded, so
   the next look parks it - no wrong state, just a late finish). The doc's "the Stop and the park keep ~2.5 s" holds
   only for a responsive speaker. Fix (optional): give `park` the time left (a `budgetMs` on the speaker, as the looks
   have), or write the caveat in §6.1.
3. **L3: stale "bound to Wi-Fi" text outside the rewritten sections.** `Ssdp.kt:12` ("SsdpScanner.kt adds the Wi-Fi
   binding"), `Ssdp.kt:79` ("bound to the Wi-Fi network by the caller"), `Topology.kt:70` ("resolved outside the Wi-Fi
   binding"), `docs/testing/emulator.md:547` ("The app binds every Sonos socket to the Wi-Fi network" - the advice to
   use the `AndroidWifi` image still holds, the reason is now the gate) and `:565` ("Wi-Fi binding" as a device check).
   26a §7 (600-605) still lists only "an external higher volume left alone" (no start cap), `PlayBodyTest` without
   the tailnet filter and `SourceScanTest` without the bind ban; not contradictions, just behind. The emulator list
   has no start-cap check (a fake at 90 before the pick: `GetVolume`, `SetVolume 40` before `SetAVTransportURI`).
   Fix: one doc/comment sweep.
4. **L4: `privateLiteral`'s KDoc is orphaned.** `PlayBody.kt:106-111` (privateLiteral's doc) now sits directly above
   `tailnetLiteral`'s KDoc (112-116), so it documents nothing and `privateLiteral` (130) has none. Fix: move
   `tailnetLiteral` with its doc above the first block, or below `privateLiteral`.
5. **L5: the new bind ban has easy holes.** `SourceScanTest.kt:96-102` scans `src/main/java` only (a debug-only
   helper in `src/debug` could bind), and matches `bindSocket(`/`socketFactory(`/`.socketFactory`: a Kotlin
   `network.getSocketFactory()` call or a `Network::bindSocket` reference passes. It does catch what the task listed
   (`bindSocket`, an OkHttp `socketFactory(...)`, `network.socketFactory`, `bindProcessToNetwork`,
   `setProcessDefaultNetwork`, `openConnection(network)` via `.openConnection(`). Fix: scan `src/`, and ban the bare
   tokens `bindSocket`, `SocketFactory()` and `etSocketFactory` as well.

## Verdict per point

**Point 1 (no socket bound to the Wi-Fi `Network`): correct; the premise is right.** Launcher: `KidVpnService.kt:86-92`
builds the tun with `addAddress`, `addDnsServer(192.0.2.2)`, `addRoute(192.0.2.2, 32)`, `setBlocking(true)` and no
`allowBypass()`/allowed/disallowed lists; `AppEnforcer.kt:1032` makes it always-on with lockdown false. Android's
`VpnService.Builder.allowBypass()`: "By default, all traffic from apps is forwarded through the VPN interface and it is
not possible for apps to side-step the VPN. If this method is called, apps may use methods such as
`ConnectivityManager#bindProcessToNetwork` to instead send/receive directly over the underlying network". netd's
`checkUserNetworkAccess` returns `-EPERM` for an explicitly selected non-VPN network when a secure (non-bypassable)
VPN applies to the uid and the uid can't `protect()`, so `Network.bindSocket`, `getSocketFactory()`'s sockets and
`bindProcessToNetwork` all fail for the music app while the filter is on - the step 1-4 code could not have worked on
the kid's phone. Unbound: the uid's lookup goes to the tun's table first (only 192.0.2.2/32), then the VPN fallthrough
rule sends it to the **system default network's** table; with Wi-Fi the default (the usual case with mobile data up
too), SSDP to 239.255.255.250 leaves on wlan0 (the default route's device; no `IP_MULTICAST_IF` needed) and SOAP to
192.168.x.x takes the connected route. The MulticastLock lets the replies in. The pool per Wi-Fi network is right:
`client()` (`SonosHome.kt:69-74`) builds a new client when the `Network` changes, the old one is dropped and its idle
connections close on OkHttp's 5-minute eviction - no leak worth code.

The documented limit is real: the fallthrough follows the default network, so with Wi-Fi up but unvalidated (no
internet at home, a captive portal, "avoid bad Wi-Fi") and mobile data up, LAN packets go to rmnet and the speakers
read unreachable. Mitigations, none worth it now: `MulticastSocket.setNetworkInterface(wlan0)` would get SSDP out on
Wi-Fi (an `oif` lookup reaches the output-interface rule and the wlan0 table without any bind), but SOAP over TCP
cannot pick an interface without `bindSocket` (EPERM) or `SO_BINDTODEVICE` (JNI, no Java API), and a bind to the wlan0
source address does not change the policy routing - finding speakers it then can't control is no gain. The only real
fixes are launcher-side and weaken the filter (`allowBypass()` for every app, or `addDisallowedApplication` for the
music app); not for this round. A one-line log when the Wi-Fi isn't `VALIDATED` would help the device check; optional.

**Point 2 (volume cap at start): correct as specified, with M1 and M2 open.** `capVolume` runs after the hush's mute
and before `SetAVTransportURI`/`Play` (`SonosTransport.kt:76-108`, the cap at 78; the test `a start from a bookmark caps inside the
hush` pins `mute true, volume 40, seturi, play, seek, mute false`); only `PlayWhy.START` passes a cap (733); `volOpt`,
`session.ownVolume` and the seeded snapshot take it; steps/advances pass none, polls and the adopt send no transport
command (tests: `the start cap is once per session`, the reworked `SonosLifecycleTest` case); the ramp's leftover is
capped (735, test); a `GetVolume` failure is caught and logged and the play goes on (test); no cap -> no `GetVolume`
(test asserts the full log). Red without the rule: four of the six new tests assert a `volume 40` that only
`capVolume` sends, and the "no cap" test asserts no `getvolume` - removing the rule or widening it to steps turns
them red. Mid-session cap change: see M1.

**Point 3 (bedtime stop retry): correct.** Arithmetic: `lookMs = max(1500, 6500 - elapsed)` (`SonosSuspension.kt:48`);
with no tail and Wi-Fi known at once, look 1 = 1.5 s, pause 0.25 s, look 2 = the ~4.7 s left, then `left < 500` ends
the loop at ~6.7 s worst case (no third look); refused looks (no time spent) give three looks and two 250 ms pauses
(test); after a 5 s tail wait the one look keeps its 1.5 s. `aux = false` skips both `groupCoordinator()` and
`volume()` and leaves `auxN` alone (`SonosSession.kt:340-356`); the test pins the speaker log as exactly
`getposition, gettransport, stop, seturi`. A speaker answering the second look gets its bookmark, Stop and park (test
with budgets 1500 then 4250). One that never answers: nothing is sent, the verdict is NO_ANSWER, renderer.json keeps
the room, and the next look (`lookAgainBeforePlaying`, a later suspension) handles it - written in the section and
§6.1; the speaker plays on past bedtime in that case, which is the inherent limit of an unreachable speaker. The
service-up path (`handOff(park = true)`) is unchanged; the standalone run inside the service (`PlaybackService.kt:1290`)
now holds `switching` up to ~7 s and can outlast the receiver's 9 s when it waited for the lock, but the job lives in
the service's scope, so the Stop still goes out. See L2 for the unbudgeted Stop. The `GuardsTest` pin change is
legitimate: it still pins the live look (now `budget, aux = false`), the verdict and bookmark-before-park.

**Point 7 (tests, grouped-away position): correct.** `hidesPosition` (`SonosController.kt:315-318`) hides only a
fresh, reachable, not-ours snapshot of a live session with nothing settling or seeded; a stale one keeps the last ours
values; ours again shows the position (test). It matches vibb `status()` (2783-2795: `position = ... if ours else
None` inside the fresh branch; vibb's stale branch even shows the raw `rel_s`, which the phone improves on). UI:
Media3 gets duration `TIME_UNSET` and `isSeekable = false` (`SonosRenderer.kt:342-348`), so the platform session and
the launcher's lock/Home view show no progress bar; the in-app player shows 0:00; the bridge's `NOW_PLAYING` sends
position 0 with the listed duration (`PlaybackService.kt:1127-1128`, its fallback) - harmless, `playing` is false.
Same-tile tests are real: test 5's stale press would be a no-op without `s.fresh(now)` (the rest of `steady` holds),
test 4's first case would resume instead of re-transfer without `!settling`; 3b by meaning (a skip while paused,
`playing.index != index`) is a fair port - `playing == null` (vibb's `sonos_idx = None`) has no reachable twin with a
fresh ours snapshot. Migrate 1 now uses a hint uri different from the session's and checks adopt, the bookmark and the
`volOpt` reset - as vibb's check 1. Hold-scan 4 asserts `stale` and `scanning` in both the view and the state before
the gate opens, as vibb's 4. The fake: all 26 fixtures exist in `music/app/src/test/resources/sonos/`; the byte-for-byte
checks compare the HTTP reply text with the fixture file (`fault_711`, `transport_paused`, `transport_stopped`,
`position_stopped_empty`, 701-with-401), and the x-rincon one with the fixture after putting the reply's own five
values in - real comparisons, not fixture-to-fixture.

**Point 8 (lows): correct.** Members: every row's `members` is now the topology's group (`SpeakerCache.kt:126-137`),
and the scope compares **uids**, never names, so an allowed speaker whose name hasn't resolved (a "?" row) is still in
`players`, still reported (`Report.kt:35-39` reports every cached player), and passes when ticked - no wrongful
refusal. In practice an "unknown" uid can't arise from `applyTopology` (it merges every visible member into `players`
before the groups are stored; `Invisible` members are dropped from both), only from a dropped corrupt cache row, so
the rule is a cheap defensive one. Tailnet: 100.64.0.0/10 = first octet 100, second 64..127; fd7a:115c:a1e0::/48 =
the first six bytes - both right (tests include the edges 100.63.255.255, 100.128.0.1, fd7a:115c:a1e1::1). A home LAN
on 100.64/10 would collide with the household's own Tailscale anyway, so nothing LAN-reachable is lost; Tailscale's
4via6 subnet routes (inside the /48) are tailnet-only too. The PWA sentence: plain template text (no variable, nothing
to escape), accurate, and it is where parents read about scoping - keep it; touching `server/` for a text line is fine
(the API rule in root `CLAUDE.md` is about contract changes; the server build job simply runs once more).

**Regressions:** none found. Phase 1 playback doesn't touch these paths; 1b's state table, adopt (no cap, no
transport command), suspension, room scope and `GuardsTest` pins are intact (only the suspension pin changed, see
point 3). Strings: no resource changed. `-PwarningsAsErrors=true` and lint clean. 26a's §3.2 table, §3.3 and §7 now
agree on the hidden position; §5.3, §6.1, §6.5 and `music/CLAUDE.md` match the code (M1/M2 aside).

**The implementer's "disagreements and things to check":** (a) 3b ported by meaning - agree (above). (b) The stricter
start cap over the earlier "never pulled down" - agree; it is what the review asked, and the kid's-phone reading is
right. Add M1's edge to the same paragraph either way. The new device checks (no `EPERM` with the filter on, a louder
speaker starts at the cap) are the right ones; add "a group row: every room starts at or below the cap" if M2 is fixed.

## Not worth fixing

- SSDP over `setNetworkInterface` for the "Wi-Fi not default" case (SOAP still can't follow), or binding "when the
  filter is off" as a fallback (the ban is simpler and the filter is normally on).
- Evicting the old OkHttp pool on a Wi-Fi change or in `SonosHome.release()` (idle sockets close within 5 minutes).
- An adopt after process death followed by a resume plays at whatever the speaker had (the adopt is the same session).
- Previous while grouped away now steps back an item instead of restarting (position 0 < the restart threshold); the
  press reclaims the room either way.
- The bridge's Home card shows 0 with the listed duration while the player shows no duration.
- An IPv4-mapped IPv6 or shorthand tailnet literal (`[::ffff:100.64.0.1]`, `100.64.1`) passes the tailnet filter;
  the speaker fails it by itself.
- Same tile 4's second case is guarded by the index check rather than `pending`; the first case pins `settling`.
- An `UnknownUid` is looked at up to three times in ~0.5 s at bedtime (pointless, harmless).
