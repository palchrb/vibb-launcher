# Code QA: design 59d steps J and L, and §8.9's edge swipe

Reviewed: branch `d59d-jl` (206157bc J, 87b92992 L, faf74a5d §8.9) on `main` 3a64c55d. Checked against
`docs/design/59d-android-mode-inventory.md` §8.7-§8.11, `docs/testing/2026-10-23-edge-swipe-capture.md`,
`docs/testing/screens/59d-jl/clock-twin.png`, `qa-59d-round1-code.md`, `r9-launcher-glance-small-items.md` and the
kiosk-gestures rule. Paths are under `launcher/app/src/main/java/com/kidslauncher/mdm/` unless named. I did no device
run; each finding that needs one says so.

## Build and tests

`launcher/`, JDK 17, no `app/libs/tsnet.aar` (the debug stub):
`flock ~/build.lock ./gradlew --no-daemon --max-workers=2 assembleDebug testDebugUnitTest` on faf74a5d gave **exit 0**,
BUILD SUCCESSFUL, **1620 tests, 0 failures, 0 errors, 0 skipped**. This is the first green full run of the final tree:
the implementer's last full run came before L's final refinements. I skipped `assembleRelease` because this worktree
has no `tsnet.aar`.

## The bar

- **The swipe only asks.** `GlancePanel.swipeNow` (`lock/GlancePanel.kt:412`) has exactly one action,
  `glance.root.post { step(GlanceStageEvent.Unlock) }`. That is the event the button sent, so as the face the path is
  `onStage` -> `tapAsFace` -> `AndroidLock.step(UNLOCK_TAP)`, and the only exit is still `faceStep`'s LEAVE or our
  keypad. `LockMediaGuardTest` pins it as the swipe's only `step(...)`, and `LockGlanceGuardTest` pins
  `GlanceSwipe.UNLOCK` as the only verdict the panel reads.
- **Own mode keeps our keypad.** `glanceStageStep(.., face = false)` maps Unlock to KEYPAD (mutation 8 below). A DOWN
  outside the GLANCE stage passes untouched, so typing on our keypad is never read as a swipe.
- **We never consume the edge swipe.** A DOWN always passes. A touch that starts in the bottom gesture inset is blocked
  for the whole gesture (`glanceSwipeBlocked`, `lock/LockGlance.kt:149`). A system pilfer arrives as ACTION_CANCEL and
  resets the swipe. Lock task's refusal (`TYPE_RESET_GESTURE`) is what keeps the edge gesture out. JL-5 asks whether we
  should take that refused touch.
- **§8.9's heal opens nothing and can't loop.** `HealTrigger.SYSTEM_HOME` reuses 16d's flip: `setLockTaskFeatures`
  with SYSTEM_INFO xor'd, which leaves HOME/OVERVIEW/NOTIFICATIONS/KEYGUARD unchanged, then `endStatusBarHeal`'s
  normal pass from `chromeLocked` (`lock/LockTaskChrome.kt:319`). It never writes packages, the status bar or
  `DISALLOW_CREATE_WINDOWS`, and it never starts anything. If the system started Home every second, we would heal at
  most once per 5 s (`SYSTEM_HOME_HEAL_MIN_GAP_MS`) on the single-threaded `lock-chrome` executor. The heal itself
  starts no Home, so it can't feed itself. The extra stamp on `lockResumedHealAt` only suppresses a duplicate of the
  same flip.
- **TalkBack can unlock.** Touch exploration turns one-finger touches into hover events, so they never reach
  `dispatchTouchEvent`. The hint line is screen-reader focusable and its ACTION_CLICK ("Lås opp") runs
  `step(Unlock)` (`GlancePanel.hintAction`, `:494`). A two-finger drag reaches the app as a one-finger drag, so the
  swipe works there too. The music tile's buttons are clicked through their nodes (ACTION_CLICK). A simulated tap
  moves 0 dp and stays NONE, so the controls still work under TalkBack.

## Findings

### JL-1 (Should): the swipe is dead in the ASK stage; the button was not

`lock/GlancePanel.kt:417`: `state.stage != LockStage.GLANCE` passes every touch in ASK. As the face, ASK keeps the page
drawn and touchable while the pad comes up (`glancePageShown(ASK)` is true). Before J, "Lås opp" could be tapped again
there, and `tapAsFace` re-asked after `ANDROID_ASK_RETRY_MS` (1 s). That was the run's bug 2, "never dead taps", and
`launcher/CLAUDE.md:266` still promises it. Now, when an ask dies (a loaded phone; §8.11's own device note hit the 2 s
recovery), a second swipe does nothing until `ANDROID_ASK_TIMEOUT_MS` recovers. A row tap still re-asks.
**Fix:** gate on the page, not the stage: `!glancePageShown(state.stage)` in place of `state.stage != LockStage.GLANCE`.
Unlock in ASK gives `ASK, null`, the same as the button's second tap (a row's target dropped). The leave/cover guards
stay. Add a pin (see JL-4).

### JL-2 (Should): the clock is not thin; bundle Nunito Light for the profile clock only

The spec (§8.8) asks for "a thin weight" like Android's. The twin shows the right place but the wrong weight. I
measured stems in `clock-twin.png` (each half at half scale): Android's digits have stems of 3-4 px, ours 7-8 px. That
is about 0.06 em against SemiBold's 0.11 em, so ours is twice as heavy and visibly wider (319 against 270 px of ink).
The position is right: the red guides match the ink's top and baseline within 2 px.
**Fix:**
- Add `300:light` to `launcher/scripts/nunito-static.sh`'s loop. It uses the same pinned variable font (v3.602,
  200-1000, OFL 1.1, already in NOTICE.md).
- Commit `res/font/nunito_light.ttf` and use `R.font.nunito_light` in `profileLook` (`lock/GlancePanel.kt:216`). The
  centred clock keeps `nunito_bold` and the date keeps its own font.
- **Do not add it to `nunito.xml`'s family.** A TextView asking for the normal weight (400) gets the nearest weight:
  300 (distance 100) would beat 600 (distance 200), and every body text in the app would turn Light. The family's
  comment ("body text 600 (asked for as normal)") depends on 600 being the nearest. Reference the file directly, as
  `nunito_extrabold` is referenced.
- Update `LockGlanceLayoutTest`'s font-set pin (`LockGlanceTest.kt:864`) and `FontLicenseTest`'s weight list.
- Re-take the twin. If the stems still read heavier than Android's, try 200 (ExtraLight). Light should also bring the
  width closer to Android's.

Note: the server's `nunito-latin-wght.woff2` is cut to 700-900 (NOTICE.md), so it is no reference for a light weight.
The script's google/fonts TTF is.

### JL-3 (Should, the user's call): with the profile, the music tile gives way before the clock's air

With the emulator Pixel profile, the clock block runs from the page's top to the baseline at 182 dp plus its descent:
156.4 dp, against 78.4 for the centred big clock. Of that, about 64 dp is empty space above the ink (the ink starts at
120 dp, the content at 56 dp). Since both clock candidates are equal (`clockBig == clockSmall`, `lock/GlancePanel.kt:924`),
the tile and the rows give way first. Music + 1 row, r9's own "user's case", now needs 493 dp against 457/481 available
at 473/497 dp, so it takes the compact tile (candidate 4): play only, no previous/next, no progress. Before L it got the
full tile with the big clock (444.4). Music alone keeps the full tile (423 dp).

At font scale 1.3 the clock block doesn't grow (dp), but the tile, rows and hint do. Any row next to music then means
the compact tile. By my estimate (~446 of 457 dp), music alone still just fits the full tile at 473 dp.

Previous/next on the lock are a function; the air above Android's clock is decoration that Android itself never shares
with a full player. **Suggested fix:** for the profile, make candidate 2 the same clock lifted, for example its
baseline raised by `min(air above the ink - 8 dp, 40 dp)`. The size, left edge and weight stay; only its height in the
page changes. Music + 1 row is then 453 dp and keeps the full tile at both heights. Pin it in the profile's
`LockGlanceLayoutTest` fits. If the user prefers Android's exact place at all times, the compact tile stays, and
`launcher/CLAUDE.md` already says so.

### JL-4 (Should): three of the swipe's glue guards are untested (the mutations survived)

The mutation table below lists them:
- dropping the music tile's buttons (or any control) from `swipeControls()` (`:475`);
- dropping the `leaving` / stage guard from the DOWN (`:417`);
- returning PASS instead of CANCEL_VIEWS on recognition, so a row would click after a swipe (`:461`).

The full suite stayed green with all three. **Fix:** in `LockMediaGuardTest`:
- (a) the ids in `swipeControls()` equal the XML's touchables minus `lock_glance_root` and `lock_timer_tile`. That is a
  structural pin: a new control in the layout must be added there too.
- (b) the DOWN guard contains `leaving` and the page gate (after JL-1, `!glancePageShown(state.stage)`).
- (c) the recognised branch returns `SwipeTouch.CANCEL_VIEWS`, and the activity sends the views an `ACTION_CANCEL`.

### JL-5 (Should, the user's call): the bottom-edge swipe, Android's own unlock gesture, does nothing on a synced lock

The capture shows Quickstep's `ResetGestureInputConsumer` doesn't pilfer when it refuses. The touch reaches our window,
and `glanceSwipeBlocked` then drops it as an edge touch. So in the normal (synced) state, the gesture a child learns on
Android's face, a swipe from the very bottom, is a no-op, while the same swipe 1 mm higher asks.

Taking it would be safe:
- When the system takes the gesture (a desync), its monitor pilfers at its touch slop. The capture's log shows
  "Gesture Monitor swipe-up is stealing input" at once. We get ACTION_CANCEL long before our page/3 drag or the fling,
  which counts only at the lift, so nothing of ours fires.
- When the system refuses, we ask, as Android's face does.

§8.7 decided "the edge stays the system's" on the premise that lock task refuses it, and it does. Raise it with the
user: dropping the inset clause is a one-line change plus a test row. A device check is needed: an edge swipe on a
synced lock asks, and on the simulated desync (`add_features 4`) the system still wins with no ask from us. If kept as
is, no change.

### JL-6 (Nit): the profile key - the Jelly Star's model string is unverified, and nothing is logged when no profile matches

`glanceClockProfile` (`lock/LockGlance.kt:296`) matches `MANUFACTURER == "Unihertz"` and `MODEL contains "Jelly Star"`.
Nothing in the repo records the phone's `ro.product.model`. A "JellyStar" or "Jelly_Star" falls back silently to the
centred clock, and only a match is logged (`GlancePanel.kt:214`). **Fix:** before rc.3, read
`adb shell getprop ro.product.model` / `ro.product.manufacturer` on the user's phone, or match ignoring spaces and
underscores. Log the no-match case once, with model, maker and density (none of these is sensitive).

`density == 1.5` is the safe key: it applies exactly where the numbers were measured, and a "Display size" change falls
back cleanly. The real condition is the dp geometry. SystemUI lays its clock out in dp, so a key on
`screenWidthDp >= ~260` (34 dp plus the widest "00:00" in ink) would also be valid at another density. Keep 1.5 until a
second density is measured.

`JELLY_STAR_CLOCK` = the emulator's numbers (`:289`). The status bar alone differs (the emulator's is 136 px with the
cutout spec, the Jelly Star's is far shorter), so on the real phone the clock won't sit on Android's. This is fine for
rc.3 if the release notes ask the user for a wake recording to measure (PLAN says "measure a frame of its face").

### JL-7 (Nit): §8.9 - what the evidence covers

Decision (a) is well supported where it can be: with the flags in sync, Quickstep refused in four runs (kiosk on x3,
kiosk off), and the simulated missing `home_disabled` reproduced the user's frames exactly. Both paths deliver
`wm_new_intent HomeActivity` through `onNewIntent`, so the trigger is observable on either.

Not covered:
- **A natural desync.** The simulation keeps HOME in DPM's features, so it can't show the flip cures SystemUI's stale
  copy; that rests on 16d's experiment.
- **The user's own phone.** If the rc.2 recording was on the Jelly Star, its Launcher3 build is unchecked (the
  Android 12/13 `canStartSystemGesture` has the same HOME and OVERVIEW rule, so (a) likely holds).
- **A destroyed Home.** If Home had been destroyed, the system's start creates it (`onCreate`, not `onNewIntent`) and
  no heal fires. This is rare (Home is the persistent launcher, and BFU never starts it because it isn't
  direct-boot-aware), and the next screen-off heal covers it.

Could it fire on a legitimate start? The boot cover (`BootCoverActivity:443`) and every `HomeFront` start carry
`EXTRA_BROUGHT`. BFU can't start Home. Own mode's lock task refuses the Home button. A stray system Home start while
LOCKED would cost one 250 ms blink of the status icons per 5 s, nothing more.

**Ask for the rc.3 recording:** two edge swipes in a row on the glance. If the second one also goes through, the heal
doesn't cure the user's case, and (b) or a dumpsys at that moment is next.

### JL-8 (Nit): docs

- §8.11 (`docs/design/59d-android-mode-inventory.md:689`): the twin is "both '10:18'", but ours reads 10:21.
- `launcher/CLAUDE.md:266`: "a tap on an ask older than `ANDROID_ASK_RETRY_MS` asks again" is true only for a row
  until JL-1.
- `launcher/CLAUDE.md:675`: "the row sits 12 dp under 'Lås opp' ... every fit ... is unchanged" is stale. The row sits
  4 dp under the hint, and the fits changed. The bullet's new `fixed` sentence just above is right.

Otherwise §8.11 matches the diff: the hint's sizes and margins, the swipe's thresholds, `fixed` 79.6, the profile
numbers, the heal's wiring and the tests named. `launcher/CLAUDE.md` covers the button's removal, the swipe, the hint,
the clock profile and the heal. PLAN has the round and the Jelly Star measurement. Both strings, nb ("Sveip opp for å
låse opp") and en, are there. `lock_media_unlock` is kept as the TalkBack label. Deleting `bg_lock_unlock.xml` is safe:
no reference is left in code, layouts or scripts, only historical docs. `ic_lock_unlock` stays for
`docs/design/34-mockups/src/glyphs.py`. The smoke test swipes up from the hint's centre to a third of its y, which is
above the gesture inset at 240 dpi.

### Checked and fine

- **The ink re-measure per text change** (`GlancePanel.kt:226`). One `getTextBounds` and one `getLocationOnScreen`
  per minute tick. `margins()` writes, and so lays out, only when the first digit's side bearing changes, at most at an
  hour's turn. There is no thrash, and a margin change doesn't trigger a refit (the fit is keyed on the available
  height).
- **Font-scale changes.** `FontScaleRefresh.rescale` turns the dp clock into sp for a moment, then `glance?.refit()`
  sets it back in DIP. The date's 16 sp grows into the 37 dp between the two baselines, which still holds at 2.0 (an
  ascent of about 32 dp). The floor keeps it under the clock's baseline. The clock's ink starts at 120 dp, below any
  status bar, so it never meets the status icons.

## Mutations

Each was applied to the final tree, run with the full `testDebugUnitTest`, and reverted. The tree is clean
(`git diff d59d-jl` is empty).

| # | Mutation | Result |
|---|---|---|
| 1 | J: a swipe starting in the bottom edge inset asks (`glanceSwipeBlocked` without `downY >= height - edgeInset`) | killed - `LockGlanceTest` "the swipe never starts on a control, in a scroll that can scroll or in the bottom edge's gesture inset" |
| 2 | J: a horizontal fling asks (`dy <= abs(dx)` -> `dy <= 0`) | killed - `LockGlanceTest` "only a mostly vertical swipe upwards, never one that started on a control" |
| 3 | J: the hint line's accessibility action removed (`hintAction(glance.lockGlanceHint)` dropped) | killed - `LockMediaGuardTest` "the page's touchables are the tile controls, the rows and Nødsamtale - each wired, the swipe and the hint's action ask" |
| 4 | J: a swipe in own mode asks Android's pad (Unlock -> always ASK) | killed - `LockGlanceTest` "Lås opp and a row lead to the keypad, the arrow back drops the target", "own mode never enters ASK - its rows unchanged" |
| 5 | J: the swipe fed under the VoIP ring card (`covered = false`) | killed - `LockMediaGuardTest` (touchables test) |
| 6 | J: a swipe on the music tile's controls asks (the tile's buttons dropped from `swipeControls()`) | **survived** (JL-4) |
| 7 | J: the DOWN's `leaving` and stage guards dropped | **survived** (JL-4) |
| 8 | J: a recognised swipe passes the views no cancel (CANCEL_VIEWS -> PASS) | **survived** (JL-4) |
| 9 | L: the profile applied at 320 dpi (the density check removed) | killed - `LockGlanceTest` "the clock profile - the emulator's Pixel and the Jelly Star at 240 dpi, else none" |
| 10 | L: the fallback lost when no profile matches (`else -> EMULATOR_PIXEL_CLOCK`) | killed - the same test |
| 11 | L: the fit's clock height for the profile without its margin, so a row overflows (`withMargins` -> `measured`) | killed - `LockGlanceLayoutTest` "the expected fits with the emulator Pixel's clock profile at 473 and 497 dp" (by its source pin; the model can't see the runtime's height) |
| 12 | §8.9: the heal fires for our own Home start (`!broughtByUs` dropped) | killed - `StatusBarHealTest` "the system's Home while locked heals at once, at most every 5 s, never for our own Home or unlocked" |
| 13 | §8.9: the 5 s cap removed (`SYSTEM_HOME_HEAL_MIN_GAP_MS` 0) | killed - the same test |
| 14 | §8.9: Home passes `false` for `broughtByUs` | killed - `StatusBarHealTest` "the runtime heals after the start, at boot completed, the first screen-on and every screen-off - waiting outside the monitor" |

11 of 14 killed. The 3 survivors are the swipe's glue guards (JL-4).

## Verdict

**Merge after fixes.** Nothing blocks:
- the swipe only asks;
- own mode keeps our keypad;
- the edge swipe is never consumed by us;
- the heal opens nothing and is bounded.

Before rc.3:
- JL-1: the swipe in ASK, one condition plus a pin.
- JL-4: pin the three glue guards.
- JL-2: Nunito Light for the profile clock (the spec's thin weight), with the family left as is.
- JL-6: check the Jelly Star's model string.

For the user to decide: JL-3 (the clock lifts before the tile goes compact) and JL-5 (take the refused edge swipe).
The nits can ride along.
