# QA 59d step M - code (`d59d-step-m`, `4889adf7..58d3e0fd`)

Code QA of design 59d step M (an unarmed reboot must never hang: the boot cover stays enabled in android mode,
`lock/HomePin` re-seats our persistent preferred activities so HomeActivity stays the unlocked HOME, the take-over is a
device-protected stamp), against `docs/design/59d-android-mode-inventory.md` §8.12, §8.4 item 7, §8.6 and §5, design
59's "F3" section, `docs/testing/2026-10-23-step-m-run.md`, the six-inch run's F1/F6, the F3 and step F code QAs and
`launcher/CLAUDE.md`'s 16b/F3, Play-links and call-log bullets. Reviewed 2026-10-23 on `qa-59d-m` (= `58d3e0fd`).
Launcher only, no emulator. AOSP behaviour is cited from memory of android14/15 (`ComputerEngine
.findPersistentPreferredActivity`, `DevicePolicyEngine`, PMS's pending `PACKAGE_CHANGED` batching, `ActivityThread`'s
relaunch); [device] = only a phone or an AVD can tell.

**Run** (from `launcher/`, `flock ~/build.lock ./gradlew --no-daemon --max-workers=2 assembleDebug testDebugUnitTest`):
exit 0, BUILD SUCCESSFUL, **1616 tests, 0 failures, 0 errors, 0 skipped**.

**Verdict: merge after fixes.** Step M does what §8.12 says, and the trade it makes - giving up handy step 7's "our
PPAs are never cleared" for one bounded, self-healing re-seat - holds up: the kid can't trigger the re-seat, hold its
window open or repeat it except by rebooting through the PIN, and nothing in the window is worse than what the
existing residuals already accept (M-S trace below). No Blocker. The fixes before the merge are small: tighten the
re-seat's pin (M-2), and correct the docs that still say the PPAs are never cleared (M-3). M-1 is a design question
the architect/user should answer, but this branch doesn't have to wait for it: a tripped crash guard puts F1's hang
back on such a phone. M-1 is not a regression, and the docs should say so now. Findings: 0 Blocker, 4 Should, 8 Nit.
Mutations: 17 run, 13 killed, 4 survived (M-2).

## Mutations

Each applied alone, run against `BootCoverPlanTest`, `BootCoverManifestTest` and `PlayInvariantsTest`
(`flock ~/build.lock ./gradlew --no-daemon --max-workers=2 testDebugUnitTest --tests ...`), then reverted. The tree is
clean afterwards.

| # | Mutation | Result |
|---|---|---|
| M1 | the take-over disables the cover again in android mode (`setEnabled(app, false, "take-over (android mode)")`) | killed: `BootCoverManifestTest` "android mode keeps the cover enabled ... (59d step M)" |
| M1b | the same through the rule: `bootCoverEnabled` false at HANDED_OVER in android mode | killed: the same + `BootCoverPlanTest` "android mode - enabled at every event while the guard holds" |
| M2 | the re-seat clears without adding back (the three `enforce*` lines removed) | killed: `PlayInvariantsTest` "cleared only by the re-seat, which adds them all back" |
| M3 | the cover's PPA added first (`addCoverPin` before `reseatPreferredActivities` in `HomePin.check`) | killed: `PlayInvariantsTest` (the same) |
| M3b | HomeActivity's HOME added after the Play blocker inside the re-seat | killed: `PlayInvariantsTest` (the same) |
| M4 | the stamp read with `AtomicFile(stamp).readFully()` | killed: `BootCoverManifestTest` (step M) |
| M4b | the record read with `AtomicFile.openRead()` (the call that actually deletes `.new`) | **survived** (M-2) |
| M5 | own mode gets the enabled cover at every apply (`POLICY -> !guardTripped`) | killed: `BootCoverManifestTest` (step M) + `BootCoverPlanTest` "own mode - enabled only at shutdown ..." |
| M5b | `applyPolicy` passes `androidMode = wanted` (own mode with the switch on = always enabled) | killed: `BootCoverManifestTest` (step M) |
| M6 | the re-seat runs while unlocked whatever HOME resolves to (a non-HOME PPA change) | killed: `BootCoverPlanTest` "the HOME pin is re-seated only unlocked, only when HOME isn't HomeActivity" |
| M7 | an android-mode cover disables itself on destroy | killed: `BootCoverPlanTest` "its own Home starts disable it first ... only own mode disables on destroy" |
| M8 | any stamp counts (`stampBoot != null`) | killed: `BootCoverPlanTest` "the take-over stamp counts only in its own boot" |
| M9 | the Play blocker's re-add made conditional (`if (...) enforcePlayLinkBlocker`) | **survived** (M-2) |
| M10 | `HomePin.locked` without `synchronized` | **survived** (M-2) |
| M11 | `HomePin.check(app, "policy")` dropped from `applyPolicy` | killed: `BootCoverManifestTest` (step M) |
| M12 | `lateStart` ignored in `coverWaitStep` | killed: `BootCoverPlanTest` "a cover started after the take-over starts Home at once" |
| M13 | an early `return` between the clear and the adds | **survived** (M-2) |

## M-S: the re-seat window and the kiosk's pins (security trace)

**What is pinned.** Five PPAs of our package, all set by `AppEnforcer.apply` at its top (`AppEnforcer.kt:293-297`)
and the cover's in `BootCover.applyPolicy`: HomeActivity's HOME (HOME+DEFAULT), the Play link blocker's two
(`market:`, `http(s)://play.google.com/store*`), the call-log pin (`VIEW vnd.android.cursor.dir/calls` ->
PhoneBookActivity), and the cover's HOME (HOME+DEFAULT+`BOOT_COVER`). There is no other `addPersistentPreferredActivity`
in `src/main` (grepped), so `reseatPreferredActivities` + `addCoverPin` re-adds the whole set; nothing of ours is lost
for longer than the re-seat.

**The window.** `reseatPreferredActivities` (`AppEnforcer.kt:1311-1319`) clears our package's PPAs, then adds HOME,
Play x2, the call log, and `HomePin.check` adds the cover's last (`HomePin.kt:73-76`), all under `HomePin.lock`.
The run measured ~475 ms for the clear and five adds; the policy engine flushes package restrictions at each write, so
per PPA: HOME is missing for about the clear + one add, the Play blocker for about three writes, the call log for
four, the cover's for the whole window (irrelevant unlocked). The window can't be held open: each step is one
synchronous binder call into DPMS that waits on nothing the kid controls.

**What triggers it, and how often.** Only `homePinReseatDue(unlocked, resolvesToHome, reseats)`
(`BootCoverPlan.kt:352`): unlocked, the system's HOME (resolved as the system does, no `setPackage`) isn't HomeActivity,
fewer than 3 in this process. It is checked:
- at the take-over (`BootCover.kt:154`, android mode only): the main thread, every CE start of our process, before
  `BootHome`'s start. At a boot that is right after the PIN, with the cover in front and no lock task yet;
- after every apply with the parent's choice (`BootCover.kt:94`), on the apply's thread, any time, the screen on or off.

The kid can't make HOME resolve elsewhere while our PPA is there: a PPA beats the role holder, any preferred activity
and filter priorities, and HomeActivity can't be disabled or hidden by the kid. So in practice the re-seat runs once
per boot, at the take-over, on the boots whose persisted order put the cover's PPA first (about every other boot,
the run saw 1 in 4), and at an apply only after something removed HomeActivity's PPA (the policy engine's purge after a
component change before the unlock - which step M removes in android mode except at a crash-guard trip - or a failed
re-add). There is no time-based rate limit, only the per-process cap. The kid can repeat it only by rebooting and
typing the PIN, and gets at most one window per boot, at a moment they don't see coming.

**What lands in the window** (a Home press, a link, a notification tap within that ~0.5 s):
- **HOME**: no PPA matches, so the system falls back to the HOME role holder's preferred activity (ours if we hold the
  role - HomeActivity or the cover; else the stock launcher) or, with none, `ResolverActivity` ("Select a Home app").
  At the take-over, `BootHome`'s typed start brings our Home over whatever came 1-3 s later and roots lock task
  (kiosk on). From the stock launcher in those seconds the kid reaches only what enforcement leaves launchable (hidden
  and suspended apps stay so; Settings is hidden, so "App info" goes nowhere). That is the class 16d already accepts
  ("an allowed app could start that package's stock home"). An "Always" on the chooser writes an ordinary preferred
  activity, which our PPA overrides again from the re-seat's first add on.
- **Play link**: with Play suspended the link resolves to Play and gets the suspended-app dialog; with Play open
  (install mode) it reaches Play directly. That is where the blocker forwards it anyway. No gain.
- **Call log** (`VIEW calls`, e.g. Telecom's missed-call notification tapped at that instant): the system dialer's
  call log with its keypad. This is the worst of the three. The kid has to hit a ~0.4 s window right after the
  PIN. Outgoing calls still go through the call policy (`calls/`), which this doesn't touch. At the take-over, BootHome
  covers it within seconds.
- An apply-time re-seat (rare, see above) has the same exposure but no Home start after it. The next Home press goes
  to ours again.

**A failure in the middle.** Every add is in its own `try` (`AppEnforcer.kt:1259-1302,1321-1333`, `BootCover.kt:106-112`),
so a throw drops only that PPA. A process death between the clear and the adds (the take-over runs on the main thread,
~0.5 s) leaves no PPA of ours until the next of: the next apply (every apply re-adds all four at its top, and the
cover's), or the next CE start of the process, whose take-over check finds HOME not HomeActivity and re-seats. The
anchor service restarts the process, so that is seconds, not "the next sync". Acceptable.

**The relaxed `PlayInvariantsTest`.** It pins: the clear only in `AppEnforcer.kt`, exactly one call, inside
`reseatPreferredActivities`, before HOME < Play < call log; callers only `HomePin.kt`; `check` under `synchronized(lock)`
with the cover's add after the re-seat. It does not pin that the three adds run unconditionally after the clear: an
`if` around one add (mutation M9) or a `return` between the clear and the adds (M13) passes, and so does `HomePin.locked`
losing its `synchronized` (M10), which lets an apply's cover add land between the clear and HomeActivity's add -
the cover first again. M-2.

**The chooser (F3's B2) in any state now.**
- BFU: the candidates are the direct-boot-aware HOMEs, the cover (+ a DBA stock launcher on 16, + FallbackHome if
  Settings weren't hidden). The cover's PPA picks it. Without that PPA (dropped by a delayed `PACKAGE_CHANGED`, M-5) a
  DBA stock launcher or the chooser may answer the boot's own HOME start, and `atLockedBoot`'s typed start still brings
  the cover over it (F3R-2) - as before step M.
- Unlocked: only with no PPA of ours matching and no role default - the re-seat window above, or after the policy
  engine's purge at a component change before the unlock. In android mode that change now happens only at a
  crash-guard trip in BFU. The take-over's re-seat then repairs it, but a Home press between the PIN and the take-over
  (~2 s on the run's build) can still get the chooser. Own mode is unchanged from F3 (the take-over's disable purges
  only the cover's PPA; HomeActivity's stays).
- So the chooser stays reachable only through a sub-second, kid-untimeable window or the crash guard. Today's B2
  (every boot after a disable) is gone in android mode, if §8.12's diagnosis is right (§8.4 item 7 (e)).

**The cover as the resolved HOME while unlocked.** Yes, and the design says so: between the PIN and the take-over on a
cover-first boot (the cover is in front then anyway, and a Home press is a new intent to its singleTask). After the
take-over only when the re-seat failed or hit its cap. Then a Home press creates a cover with `lateStart`
(`BootCoverActivity.kt:141`), which starts Home at once (`coverWaitStep`'s START_HOME). `startOwnHome` sees our typed
HOME isn't HomeActivity, disables itself first (`:451-456`), and the next apply re-enables it with its PPA appended
after HomeActivity's. What the kid sees: the breathing mark for a frame or two. What is reachable from it: nothing.
The content is the mark and the hint line, and the root is a tap target only while gated (`faceViews`). Unlocked,
Back is not held (`holdBack` only with the flag), so Back finishes the cover. `onPause` marks the finish as the
system's (`finishedInFront`), and `onDestroy` starts Home on the way out (`coverDestroyStartsHome` -> `startOwnHome`).
It ends at our Home, never at the stock launcher. New with step M: the component is `exported` (a HOME filter) and now
enabled while unlocked, so any allowed app can start it explicitly. Taken over, it bounces to our Home at once. It reads
no extras, and its Home start's `EXTRA_BROUGHT` is something any app can already send to the exported HomeActivity. No
new capability.

**The take-over stamp.** `boot_cover_taken` holds the `BOOT_COUNT` it was written in (`BootCoverGuard.kt:115-133`).
Only the main process writes it, on the main thread (the take-over and `BootHome.callMayHaveEnded`, both main): a fixed
`.tmp`, `fsync`, `rename`. Both processes read the stamp file itself.
- No torn read: `rename(2)` replaces the name atomically. A reader with the old inode open reads the old content
  whole, and a reader after the rename reads the new one. Even a torn read would be harmless: `toIntOrNull` of a
  prefix or of nothing is never this boot's count, so it costs one 250 ms poll. The temp + rename is belt and
  braces for the stamp. The AtomicFile hazard is gone: nothing reads either file through `AtomicFile` any more (one
  `AtomicFile` left, the cover's own record writer, `startWrite`/`finishWrite`; pinned, M4/M4b).
- Per boot: `coverTakenOver` needs `stampBoot == bootNow` and `bootNow >= 0` (`BootCoverPlan.kt:324`), so a stamp of an
  earlier boot never counts, and an unreadable count never does. Both reads (`lateStart` at create, the poll) happen
  only once `isUserUnlocked`, after the boot completed.
- **Can a stale stamp recreate today's hang?** No. F1 is about the component's enabled state at boot, and the stamp
  doesn't touch that: the cover is enabled by every apply, the take-over and the shutdown, and the stamp is read only
  unlocked. The worst a wrong stamp can do: false -> the cover waits for its 30 s hand-over (never stranded); true too
  early -> START_HOME after 3 s and FINISH 5 s after the "take-over", even with our Home not yet up. For that the
  stamp would have to equal a new boot's count, i.e. `BOOT_COUNT` not yet bumped when the PIN is typed. The run's log
  order (no "Boot cover starts Home" line before the main process's take-over in a2/b1/c1) says it is bumped earlier
  on 15. §8.4 item 7 should log the two numbers on 16 and the Jelly Star (M-10).

## Findings

### M-1 - Should - a tripped crash guard reproduces F1 at every later boot (android mode, no other DBA HOME)

`BootCoverPlan.kt:69-79` (`androidMode -> !guardTripped`), `BootCoverGuard.kt:167`, `BootCover.kt:151-153`. In android
mode a tripped guard (two cover crashes in one boot) disables the cover at that moment, at every apply, at the
take-over and at the shutdown. Until a launcher update or the switch's off -> on edge clears it (`coverGuardClearDue`),
a phone like the six-inch Android 15 image (stock launcher not direct-boot aware, Settings hidden) has no HOME at its
next boot, armed or not. That is F1: the boot never completes, input is dead, and nothing of ours runs to clear the
guard. Only something that starts an activity rescues it, like an incoming call, which in the run didn't always work.
In own mode the guard's "fail safe = off" is safe (no BFU, A+B). In android mode on such a phone it is the hang.
**Not a regression**: before step M a trip disarmed the shutdown arm, so the same phone hung at every boot. But §8.12
and design 59's step M say "never hang" without the exception. Fix: (now) name it in §8.12's risks and in the
`launcher/CLAUDE.md` bullet. (Design, for the architect/user) either (a) in android mode a trip keeps the component
enabled and turns the cover into an inert HOME until the unlock: a bare night window, no AVD, no asks, no receivers,
the guard check still first in `onCreate`. It doesn't finish (a HOME that finishes at once makes the system restart it
in a loop). Unlocked, it starts Home typed. Or (b) keep Settings unhidden while the guard is tripped in android mode,
so FallbackHome exists (option 3's hole, only in the degraded state). Either way the device run should include one
forced trip. Backlog item in PLAN.

### M-2 - Should - the re-seat's pin doesn't hold the "adds everything back" half

`PlayInvariantsTest.kt:20-47`. The test locates the clear and the three adds by `indexOf`, so a condition or an early
exit between them survives: M9 (`if (...) enforcePlayLinkBlocker(...)`) and M13 (`return` after the clear) pass, and
both would leave the kiosk without a pin until the next apply. Also `HomePin.locked`'s `synchronized` (M10) is
unpinned, though `BootCover`'s two cover adds rely on it to stay out of the clear/re-add (`BootCover.kt:90,152`).
Separately, the AtomicFile ban pins `readFully(` only, while the hazard is `openRead` (which `readFully` calls): the
record read through `file(context).openRead()` (M4b) passes, and would bring back the run's bug for the crash
counter. Fix: pin the body exactly. After the clear's `try { } catch { log }`, the rest of
`reseatPreferredActivities` is exactly the three calls, no `return`/`if`/`?:` (compare the whitespace-normalised
tail). Assert `fun <T> locked(block: () -> T): T = synchronized(lock) { block() }`. Extend
`BootCoverManifestTest.kt:328`'s ban to `openRead(` in `BootCoverGuard.kt`.

### M-3 - Should - the docs and KDoc still say our PPAs are "never cleared"

`launcher/CLAUDE.md:123` (Links: "set on every `apply()` and **never cleared** - clearing ours would drop the HOME
pin (QA #2, `PlayInvariantsTest`)"), `launcher/CLAUDE.md:407` (call log: "never cleared (`PlayInvariantsTest`)"),
`AppEnforcer.kt:1252-1255` and `:1286-1288` (the same in the two KDocs). These are the invariants the next
implementer reads first. Fix: "cleared only by `HomePin`'s re-seat, which adds every PPA of ours back in the same
pass (design 59d step M, `PlayInvariantsTest`)" in all four.

### M-4 - Should (step F's, not M's) - boot-time relaunches use up the three automatic asks

`BootCoverActivity.kt:97,199-203,379`, `BootCoverPlan.kt:464-476`. When the theme overlays load,
`ApplicationInfo` changes and ActivityThread relaunches the cover (destroy + create, `isChangingConfigurations` true).
The old instance's `onStop` runs `padStep(STOPPED)`, which closes the open ask and re-arms. The new instance starts at
`armed = true` with the carried count, and its resume asks again. Two relaunches in ~10 s make three counted asks
while one pad stays up. After the kid's first Back, only taps ask (the line shows, so it is not a lock-out). Fix
(pure, small): a relaunch is no stop and no new ask. In `onStop`/`onDestroy` with `isChangingConfigurations`, skip
`padStep(STOPPED)` and keep the whole `BootPad` in the companion (as `autoAsksThisProcess` is). The new instance
starts from it with a `PadEvent.RELAUNCHED`. With an ask open, that asks again **without counting**: the old ask's
callback is dropped by `isDestroyed`, so the new instance needs its own, and a second `requestDismissKeyguard` while
the pad is up only re-registers. Otherwise it acts as RESUMED. Test it in `BootCoverPlanTest`: two relaunches with the
pad up leave `autoAsks` at 1. Not this round's blocker (step M doesn't change how often the cover is relaunched). It
belongs before rc.3, since every android-mode boot shows it. PLAN Fix backlog if not done in the fix round.

### M-5 - Nit - the cover's PPA is added before its enable

`BootCover.kt:90` then `:93`, `:152` then `:153`. When the cover is disabled (its own 30 s hand-over or a disabling
Home start earlier in this boot), PMS's pending `PACKAGE_CHANGED` for that disable is delayed (batched, ~1 s, longer
during startup). If the policy engine handles it between the add and the enable, it sees the cover disabled and drops
the just-added PPA. The enable's own broadcast then finds nothing to drop and nothing re-adds it, so the cover's PPA
stays missing until the next apply. That matters on a DBA-stock-launcher phone (16) at the next boot: the stock
launcher or the chooser, covered by `atLockedBoot`'s start. Fix: `setEnabled` first, then `addCoverPin`, in both
places. The order is pinned the other way round in `BootCoverManifestTest.kt:268,274`, so flip the asserts too.

### M-6 - Nit - a cover disable after the take-over waits for the next apply

`BootCoverActivity.kt:451-456`, `:295-305`. The cover's own disables (`startOwnHome` when our typed HOME isn't
HomeActivity, the crash guard) are undone only by the next apply or take-over, which `BootCover.kt:139-146` and §8.12
say. An unarmed reboot in that gap is F1 again on such a phone. The gap is short in practice: `startOwnHome` disables
only when the re-seat failed or hit its cap, and an apply comes with the next sync. Name it in §8.12's risks next to
"a crash inside the first apply". Optional fix: `HomeActivity.onResume` in android mode re-enables a disabled,
untripped cover by the same rule (one PM read per resume).

### M-7 - Nit - `HomePin.check` counts and logs a re-seat that didn't happen

`HomePin.kt:72-80`. `reseats++` and "Re-seated our persistent preferred activities" come whether or not `asOwner`
ran the block (not device owner, or a throw before `reseatPreferredActivities`). Fix: have `asOwner` return whether
it ran, and log "skipped (not device owner)" otherwise.

### M-8 - Nit - `PlayInvariantsTest` reads the first file of a name, not the walked file

`PlayInvariantsTest.kt:22,41` call `code(file.name)` / `code(it.name)`, which reads
`sources.walkTopDown().first { it.name == name }`. With two `.kt` files of the same basename (none today; checked),
the second is never scanned for a clear or a re-seat call. Fix: strip the comments from `file.readText()` itself (a
`code(file: File)` overload).

### M-9 - Nit - the duplicate PPAs have no hard bound

§8.12 and the run note that Android 15's policy engine re-adds every PPA at every boot and PMS appends (+5 a boot), and
that a re-seat clears them. A re-seat comes only on a cover-first boot. With k copies of each HOME entry in a random
persisted order that is about 1/2 a boot, so n boots without one has probability ~2^-n and the expected pile stays
around ten entries. In android mode it is bounded in practice. Phones without the cover, or in own mode, never clear
(+4 a boot, pre-existing on 14+, not step M's). Resolution doesn't care (a duplicate names the same component). Only
`package-restrictions.xml` grows (a few hundred bytes a boot). Fix: none now. Add the count to §8.4 item 7 (f)
(`abx2xml` of `package-restrictions.xml`, `grep -c` our package's `<item` under `persistent-preferred-activities`,
before and after 5 boots, root/AVD). If own mode's growth ever matters, a re-seat at every Nth take-over in every mode
bounds it, at one window per N boots.

### M-10 - Nit - §8.4 item 7 should also check

(g) A Back and a Home press on the cover between the PIN and the take-over, on a cover-first boot (force one by
re-running until the take-over logs `HOME resolves to ...BootCoverActivity`): it ends at our Home, never the chooser or
the stock launcher. (h) `am start -n <pkg>/com.kidslauncher.mdm.lock.BootCoverActivity` while unlocked after the
take-over: our Home at once (`late start true`). (i) A boot where the re-seat runs with the fixed build (the run's
re-seat boot a1 had the old stamp code; its "Not done"). (j) The stamp vs `settings get global boot_count` after the
PIN (and the cover's first-frame boot count from its record), confirming `BOOT_COUNT` is bumped before the unlock.
(k) The duplicate count of M-9. On the Jelly Star (Android 16 after its OTA, `56-android-13-and-17.md`) the item's BFU
`query-activities` decides whether M-1 can hit it at all.

### M-11 - Nit - "after every apply" is "after every apply with the parent's choice"

§8.12 ("at the take-over and after every apply"), design 59's step M bullet, `HomePin.kt` KDoc and the CLAUDE.md
bullet. `applyPolicy` returns before `HomePin.check` when `parentChoice` is false (`BootCover.kt:77`). A fallback apply
still re-adds HomeActivity's PPA (AppEnforcer's top) but doesn't check the order. Fix: say "after every apply with the
parent's choice".

### M-12 - Nit - android -> own leaves the cover enabled while unlocked until the next process start

`BootCoverPlan.kt:69-79`. When the parent moves the phone from Android's lock to our own, the apply's rule in own mode
is `POLICY -> null` (leave it), so the cover that android mode kept enabled stays enabled, with its PPA, until the
next CE start's own-mode take-over disables it. `HomePin.check` still runs at that apply (in every mode), so HOME stays
HomeActivity, and the next boot's own-mode cover hands over after 3 s. Harmless, but "own mode keeps 16b's rule" isn't
quite true for that day. Fix: none needed, or a sentence in §8.12. A `POLICY -> false` in own mode would break the
shutdown -> unlock cover, so don't.

## What holds

- `bootCoverEnabled` (android mode: true at every event while the guard holds; CRASH_GUARD always false; own mode
  unchanged) and its callers: `applyPolicy` passes the marker's rule (`bootCoverAndroidMarker(wanted, androidGate)`),
  the shutdown the DE marker, the take-over `true`. `atLockedBoot`'s enable is now a no-op in android mode, so no
  component change before the unlock and no BFU purge (except the crash guard, M-1).
- `HomePin.check`: unlocked first, the system's resolution (no `setPackage`, pinned), cap 3, one lock for the clear
  and every cover add. No deadlock: the apply holds `AppEnforcer`'s monitor and then takes `HomePin.lock`. The main
  thread takes only `HomePin.lock`, and nothing under it calls back into `AppEnforcer`'s synchronized methods
  (`reseatPreferredActivities` and the `enforce*` are plain). Interleavings of an apply's unlocked HOME/Play/call-log
  adds with a re-seat are harmless (re-added after the clear). Only the cover's add could reorder HOME, and it is
  locked.
- The cover's step: `coverWaitStep(.., takenOver, .., lateStart)` keeps every earlier rule (never before the unlock,
  never Home while stopped, the 30 s hand-over waits for a call), and `lateStart` only shortens the nudge. Its own
  hand-over still disables before its Home start (pinned). `coverHomeStartDisablesFirst(null)` disables (fail safe
  towards HomeActivity). `HOME_ACTIVITY_CLASS` is pinned to the class, and the cover never loads it.
- The stamp and the record read their base files only (pinned against `readFully`, M4/M4b).
- §8.12's "as built", design 59's step M bullet and checklist item 6, and the CLAUDE.md 16b/F3 bullet match the diff
  (apart from M-3, M-11). PLAN's entry is current.
