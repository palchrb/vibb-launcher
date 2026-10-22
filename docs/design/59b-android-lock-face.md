# 59b - Android's lock as the gate: keeping our face in front, the WhatsApp ring, the look of Android's pad

Status: investigation + design, 2026-10-22. Read-only; nothing built. Base: design 59 (§3.3, "Step 3 as built"),
assessment 59 §4.2, the RC run (`docs/testing/2026-10-22-d59-rc-run.md`), the user's reports on 0.36.0-rc.1 (android
mode, emulator) and the two WhatsApp logs the coordinator relayed. AOSP references are `android16-release`.

## 0. Summary

- **Why SystemUI's face shows, and stays up.** A non-showWhenLocked activity on top un-occludes the keyguard. WM then
  puts the display to sleep, our glance is stopped, and SystemUI draws its face. Today **nothing of ours brings the
  glance back while the screen is on**: `refrontAction` stops on `androidGate` before every other rule. So whatever
  ended the 3.5 s came from somewhere else: a VoIP phase change, a screen-off, or the user.
- **Fix (2):** in android mode, an unexpected loss of the front is re-fronted at once, through the same
  pause/stop -> 200 ms check as own mode. "Unexpected" means none of the existing exemptions (calls, VoIP, emergency,
  alarm, SOS), no open PIN-pad ask, and no start of our own. At most two tries, then we wait for the screen-off.
  Re-fronting a showWhenLocked activity only occludes the keyguard; it dismisses nothing.
- **The WhatsApp black screen:** our lock-up at the ring, followed by our 17b "ring screen sent", re-launches
  `VoipActivityV2`, which SystemUI had already launched from the ring's full-screen intent. It is intermittent, so a
  race. Fix: in android mode the platform's full-screen intent is the ring screen. No lock-up or wake at a ring that
  has one, no send unless nothing came within a grace period, and no reopen of a "call" that is really the ring.
- **Look (3):** the lock wallpaper changes SystemUI's face and the pad's backdrop, but **not the pad's colours**. Those
  come from the one system palette, seeded from the *home* wallpaper. Proposal: the glance's own ground as the lock
  wallpaper in android mode, plus `setOrganizationName` for the "belongs to" line.

## 1. Why the glance took ~3.5 s to come back (the chat tap during a WhatsApp video call)

**What Android does** (`KeyguardController.java`):
- `updateVisibility` (:711-746) lets only the top task's top activity occlude, and only if it `canShowWhenLocked`.
  WhatsApp's `HomeActivity` can't (PLAN, 2026-10-21: only `VoiceMessagingActivity` has the manifest flag), so
  `mOccluded = false`.
- `updateKeyguardSleepToken` (:566-572) then takes the keyguard sleep token, since `isKeyguardUnoccludedOrAodShowing`
  (:130-136) is true. The display "sleeps": the new activity is not resumed, and ours is paused, then stopped
  (`checkKeyguardVisibility` :409-420: nothing but showWhenLocked is visible).
- SystemUI sees the occlusion change and animates its own face in: the lock wallpaper (the family photo the parent
  put on the lock screen, design 08's opt-in), the clock, and the device-owner disclosure.

**What we do**:
- `onLockPaused`/`onLockStopped` (`PinLockRuntime.kt:1465`, `:1482`) schedule `runRefrontCheck` after 200 ms (700 ms
  within 3 s of a screen-on).
- The check sets `androidGate = chromeLocked && !lockResumed && AndroidLock.refrontHeld(context)` (`:1562`).
  `refrontAction` returns `Stop` on it before any other rule (`RefrontPolicy.kt:80`) and logs "the glance isn't in
  front - no re-front, Android's keyguard guards" (`:1565`).
- The face waits (1 s/3 s, `AndroidLockPlan.kt:105`, `:113`) only run from the glance's own resume, tap or
  `USER_PRESENT`. The sleep check (`PinLockRuntime.kt:856`) runs only while dark.
- So with the screen on, only these bring the glance back:
  - a VoIP or phone call ending: `VoipEnded`/`CallsEnded` -> `showLock` (`PinLockState.kt:192-224`);
  - a screen-off: `lockUpAtScreenOff` (`:678`) starts it, and it is there at the next wake;
  - an unlock on SystemUI's pad: `USER_PRESENT`, but then the glance leaves rather than returns.

**The likely chain for the 3.5 s.** The call was IN_CALL. Its exemption holds only while WhatsApp's call notice is
there *and* the audio mode is `IN_COMMUNICATION` after the grace (`VoipCallPlan.kt:378`), re-read every 2 s
(`VoipCalls.kt:66`). WhatsApp's activities were slept by the un-occluded keyguard. If WhatsApp then left
`IN_COMMUNICATION` or re-posted its notice, the next pass gave `IN_CALL -> NONE`. That is `VoipEnded` -> `show()` ->
our showWhenLocked glance, the black frame, then the glance.

**The black frame** is our own stand-in. `PinLockActivity` sets a pure-black task description and theme for exactly
this (design 32 M3, `PinLockActivity.kt:451`), and here it is drawn while SystemUI animates its face out (occlude).

**Alternatives the log will tell apart:**

| What happened | Log line |
|---|---|
| Phase change (the chain above) | `VoIP IN_CALL -> NONE` then `... on VoipEnded` |
| The user's power press, or SystemUI's own idle timeout on its face (no wake lock there), then a lock-up at the screen-off | `screen-off, lock-up ...` and `screen_toggled` in `-b events` |

No chat content can show in either case: the keyguard held. This is a look issue, as assessment §4.2 said. The user's
verdict ("buggy og lite sexy") is the requirement for §2.

## 2. A fast re-front in android mode

**Rule.** Replace the blanket `inputs.androidGate -> Stop` with a pure step, `androidLossStep`, in `RefrontPolicy.kt`.
It runs on the same pause/stop -> `PAUSE_REFRONT_MS` (200 ms) check. While Android's secure keyguard is locked
(`androidGate` as today):

1. Not interactive -> Stop. The screen-off lock-up and the sleep check own the dark.
2. **Expected losses -> Yield, not Stop** (the existing rows, in today's order):
   - `ourCall`, `telecomInCall`, `emergencyFlow` (our Nødsamtale, 2 min), `alarmRinging`;
   - `voipCall`, with the refinement in §2.1;
   - `sosFlow` (a stop within 3 s of a screen-on).
3. **Our own asks -> Stop.** These are new inputs, each with a timeout:
   - `askOpen`: `askedAt != null` in `PinLockActivity`, or an answer less than `ANDROID_ASK_SETTLE_MS` (1 s) ago. This
     covers the pad itself and the pad's own Emergency button (its dialer is showWhenLocked; the ask's
     cancel/answer comes around the stop). It is published to the runtime as an elapsed stamp, so a lost callback
     expires.
   - `ownStartAt`: our own start less than 2 s ago. That is `VoipCalls.send` (ring screen, answer, reopen),
     `EmergencyCall`, our `InCallActivity`, the notice wake's `VoipWakeActivity` and the time-rule screen. One stamp,
     `PinLockRuntime.noteOwnStart(why)`.
   - `ownOthersResumed > 0` (already counted).
4. Anything else -> `Refront`, with `show()` adding `FLAG_ACTIVITY_NO_USER_ACTION`. That way the activity we cover
   gets no `onUserLeaveHint`, so no PiP and no Element-style hang-up (PLAN, design 57 notes).
5. **Loop guard:** at most `ANDROID_REFRONT_TRIES = 2` (at 200 ms, then 500 ms), then Stop until the next resume or
   screen-off. It is logged and counted as `face_refronts` in `lock_state` beside `keyguard_relocks`.
   - Own mode never gives up, because there a cap fails open (QA 10 #2). Here a cap only leaves SystemUI's face up.
   - This guard is what keeps us from fighting SystemUI's own pad. A kid who swipes up on SystemUI's face does so
     after our two tries are spent (≤ 0.7 s).

**What it can't see.** No public API tells "un-occluded" from "a showWhenLocked activity came over us":
- `isKeyguardLocked` is true in both cases (`:148-151`).
- `addKeyguardLockedStateListener` needs a permission we can't hold.
- So the exemption list carries it. The one gap is an **unknown** showWhenLocked activity coming over the glance, which
  we would cover once or twice and then leave. That costs only a look, never a path.
- Once design 57's usage access lands, add the top resumed activity as an input: yield to the call's own activity,
  re-front anything else. That makes §2.1 exact.

### 2.1 During a VoIP call (the user's case)

`voipCall -> Yield` would keep SystemUI's face up for the whole call, which is exactly the report. So in android mode,
while IN_CALL, a loss that is not within `ownStartAt` re-fronts too. This is safe because the glance's resume during
IN_CALL already sends the call screen back (`LockResumed.showVoipCall` -> `VoipCalls.reopenCall`,
`PinLockState.kt:179-183`, `PinLockRuntime.kt:657`), and that send sets `ownStartAt`. So the call screen's own
re-entry is never fought.

The chat arrow then ends as: SystemUI's face for ≤ 200 ms + one frame -> the glance -> the call screen. Two losses
within 5 s during one call stop the re-front (no ping-pong) until the next resume. While RINGING, keep the plain
Yield (§3).

### 2.2 Why it can never open a path past the PIN

- `PinLockActivity` is `showWhenLocked` with no `dismissKeyguard`/`FLAG_DISMISS_KEYGUARD` (manifest :159-165). On top,
  it becomes `mTopOccludesActivity` (`:735-740`). `mDismissingKeyguardActivity` is set only for a dismiss window
  (`:727-729`), so the start occludes and dismisses nothing.
- `requestDismissKeyguard` stays in `tapAsFace` alone (`PinLockActivity.kt:289`). The leave rule (`faceStep`, design
  59 §4) is untouched. A re-front only changes *which* locked face shows.
- **BAL:** our start runs from the background, often from a paused activity. The device owner is allowed
  (`BackgroundActivityStartController.java:1090-1092`: "Device Owner", `BAL_ALLOW_ALLOWLISTED_COMPONENT`), as F3
  already relies on. `FLAG_ACTIVITY_NO_USER_ACTION` changes nothing there.
- **Lock task:** our package is always permitted while LOCKED.

## 3. The WhatsApp ring that went black for ~15 s

**What was on top.** `topResumedActivity=com.whatsapp/.calling.ui.VoipActivityV2`, and the screenshot showed only the
status bar and the gesture handle. WhatsApp's window was on top, occluding and resumed, but it had drawn nothing.
Neither SystemUI nor we were showing. The emulator camera works: answered video calls showed the virtual scene and
WhatsApp's controls. A camera-only black would leave WhatsApp's buttons and the caller's name on the screenshot.

**The double start, step by step** (19:44 log; the 19:49 one matches):
- 28.330: the ring is seen.
- 28.332: SystemUI launches WhatsApp's full-screen intent itself. Android 16 decides `FSI_DEVICE_NOT_INTERACTIVE` (or
  `FSI_KEYGUARD_SHOWING`/`_OCCLUDED`, `FullScreenIntentDecisionProvider.kt:174-193`). That is the platform's job under
  a locked keyguard. In own mode the keyguard is off and the screen-on case heads up, which is why 17b sends it
  itself.
- 28.359: our `VoipRinging` -> `showLock` + wake (`PinLockState.kt:208-212`) starts `PinLockActivity` and
  `VoipWakeActivity` over WhatsApp's splash, 27 ms later.
- 29.601: once our glance is "settled", 17b's `sendVoipRingScreen` (`PinLockRuntime.kt:1041`) sends the full-screen
  intent again. Result 2 is `START_TASK_TO_FRONT`: the same singleTask activity is moved to the top while its first
  launch is still settling.
- Our first-send detection (`notePause`, "came up without our send") can't see a ring screen that came *before* our
  lock, so it sent.
- The user's runs: video fine, video black, audio fine, video fine. That fits a race between WhatsApp's splash and
  init and our cover-then-refront. The prime suspect is WhatsApp's own UI state after being stopped mid-start and
  re-delivered. This is unproven; §6 has the check.

**Why the phase said IN_CALL 0.3 s after display, with no answer.** `RINGING -> IN_CALL (-)` means the ring notice
stopped reading as a ring. Most likely WhatsApp re-posts its ring notice without the full-screen intent once its
screen is up. `voipNoticeKind` then reads either NONE (incoming CallStyle, `VoipCallPlan.kt:140-141`), so the ring
"ended" and `VOIP_GRACE_MS` (15 s, `:25`) holds IN_CALL, or IN_CALL (FGS + `CATEGORY_CALL`, `:143`). A
`dumpsys notification` during a ring (§6) settles which.

**What ended the 15 s.** The ring ended (the caller or WhatsApp's timeout), so `VoipActivityV2` finished and our
glance below resumed. Its `onLockResumed` runs before the logged "the glance resumed" (`PinLockActivity.kt:666-668`).
During IN_CALL, `LockResumed` -> `reopenCall` sent the "call" notice's content intent, which is the ring's
`SHOW_INCOMING_CALL_SCREEN` (45.570, new task t145). WhatsApp finished it at once ("Duplicate finish request"). Then
the notice went (`IN_CALL -> NONE`, 45.834) -> `VoipEnded` -> the glance.

**The "probably Emergency SOS" line** (`PinLockRuntime.kt:1523`) does nothing in android mode today. The check that
follows returns `Stop` on `androidGate`, so `sosMarkKept` is false and the mark is cleared at once. That the 15 s
`SOS_FLOW_MS` and the 15 s `VOIP_GRACE_MS` match the black is a coincidence.

**Fix, android mode with the face and Android locked** (own mode unchanged):
1. **The ring's full-screen intent is the platform's.**
   - `VoipRinging` with a full-screen intent -> no `showLock`, no `wake`. SystemUI launches it and wakes the screen
     (the call app's turn-screen-on).
   - The glance stays wherever it is: on top only if it was on top.
2. **One send at most, after a grace.** `VOIP_PLATFORM_GRACE_MS` = 3 s from the ring's first sight; then send once
   only if all of these hold:
   - our glance has been resumed throughout and has not lost the front since the ring began;
   - the screen is on;
   - Android is locked.

   That is the heads-up case: a ring while the kid is looking at the glance. Still dark after the grace, so the app's
   screen never came -> today's wake + card. A ring with no full-screen intent (`FSI_DENIED`) -> the card at once, as
   today.
3. **No reopen of a ring.**
   - In android mode, `reopenCall` runs only once the call is confirmed (`audioInCommunication` at the send) and
     never inside `VOIP_GRACE_MS` of a ring flip.
   - Any send sets `ownStartAt` (§2).
   - Longer term: classify IN_CALL from an *ongoing* CallStyle (`EXTRA_CALL_TYPE` = 2) or Element's channel, not
     from any CALL FGS notice. That needs the notice dump first.
4. **The ring keeps its Yield in §2.** We never cover the app's own ring screen.

## 4. Making Android's face and PIN pad look like Vibb

**What sets what:**

| Part | Source | Can we set it |
|---|---|---|
| SystemUI's face backdrop, and the pad's (under the bouncer's scrim/blur) | the **lock** wallpaper | Yes: `setBitmap(.., FLAG_LOCK)`. We already hold `SET_WALLPAPER`, and the device owner is exempt from `DISALLOW_SET_WALLPAPER` (`WallpaperApplier.kt:30-31`). |
| Pad colours (keys, dots, text) | the **one system Material You palette** (`THEME_CUSTOMIZATION_OVERLAY_PACKAGES`) | No. With SystemUI's `newCustomizationPickerUi` flag the seed is **always the home wallpaper** (`ThemeOverlayController.java:254-258`). Without it, the seed is the most recently set wallpaper, lock or home (`:260-262`, `:333-367`). The device owner can't write that setting (`DevicePolicyManagerService.java:728-735`: only IME, first-use hints, unknown sources, location). |
| Layout, key shape, font, the Emergency button | SystemUI | No. |
| Clock style (the big clock) | the user's "Wallpaper & style" | No. |
| "PIN auto-confirm" | the user's Settings | No, and Android allows it only for PINs of 6+ digits; ours are 4-6. |
| "Denne enheten tilhører organisasjonen din" | the disclosure (`do_disclosure_generic`) | Only the name: `setOrganizationName(name)` turns it into `do_disclosure_with_name`, "...tilhører <name>" (SystemUI `strings.xml:1257-1260`). |
| Owner info line | `setDeviceOwnerLockScreenInfo` | Yes. Design 59 uses it only for the restart line. |

So **a night+peach lock wallpaper does not tint the pad**. On the old picker path it would re-seed the *whole phone's*
palette, which is worse. The pad's colours already follow the home wallpaper we set (design 08). A parent can choose a
preset colour once in "Wallpaper & style" (a device check: `DISALLOW_SET_WALLPAPER` may grey that page out).

**Proposal:**
- **The lock wallpaper in android mode = the glance's own ground**: `lockGround` (fill, the lock photo, and the
  scrim baked in), rendered at the screen's size by `WallpaperRender` with no new asset. SystemUI's face and the pad's
  backdrop then match the glance. A flash, when one still happens, shows only a clock change.
- If the parent's lock choice is the default night, draw the vibb mark small in the lower third. SystemUI's clock is
  at the top, and its lower area is empty because shortcuts are off. Whether to do this is open question 1.
- **When it is set:**
  - On the face's on edge (`AndroidLock.face` changed -> true), through `WallpaperApplier` on its thread, `FLAG_LOCK`
    only, **then re-set the home bitmap in the same pass**. On the old picker path, home is then still the most
    recent wallpaper, so the palette doesn't move.
  - On the off edge (a rollback), design 08's normal lock choice comes back (navy, or the photo if ticked).
  - The home wallpaper is never changed by this.
  - Record the key so it isn't rewritten every apply (design 08's once-a-day rule).
- **Organisation name:** set at apply. The name is open question 2: a family name from the server, or "Vibb". Keep the
  owner info line empty after the first unlock, as design 59 decided.
- **Kid-visible check:**
  - DeskClock over the glance -> SystemUI's face shows the glance's ground, and "...tilhører <name>".
  - "Lås opp" -> the pad over the same ground.
  - Home's wallpaper and Settings' colours unchanged (`settings get secure theme_customization_overlay_packages`
    before = after).

## 5. The flash on repeated power presses

**Candidates, with the line that tells each:**

| Candidate | Log line | Emulator-only? |
|---|---|---|
| **RELOCK under the thumb.** A wake read Android not locked (the keyguard's lock was still pending from a quick off/on). `RESUME` -> WAIT 1 s -> `lockNow` -> the screen goes off: glance -> black. | `Android's keyguard didn't lock under the glance - locked it (the glance's wait)` | No (could happen on a phone). The RC run saw none in 107 lock-ups. |
| **The press beats the lock-up's start** (assessment §4.3, ≈100 ms): SystemUI's face for a frame, then our black stand-in. | `screen-off, lock-up LOCK` later than the `screen_toggled 1` that follows it | No. |
| **SystemUI's going-to-sleep/waking path without AOD.** The emulator has no doze: the panel switches instantly with no fade, so one composed frame of the keyguard's occlusion change can be seen. A phone's fade hides it. | Only `wm_set_keyguard_shown` toggling `occluded` around the presses | Yes, the likeliest emulator-only one. |
| **The glance's task snapshot** (its last frame, e.g. the pad stage) shown before the first frame | Nothing logged; only a recording shows it | No. |

**Capture:**
```
adb logcat -c; adb logcat -v epoch -b main,events -s AndroidLock PinLock PinLockActivity KeyguardViewMediator \
  screen_toggled wm_set_keyguard_shown wm_set_resumed_activity wm_on_paused_called wm_on_stop_called > power.log
adb emu screenrecord start --time-limit 30 /tmp/power.webm   # the console recording keeps FLAG_SECURE windows
```

## 6. What the user runs the next time WhatsApp goes black

```
adb shell dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity|VoipActivityV2'
adb shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp|VoipActivityV2|isOnScreen|mHasSurface'
adb shell dumpsys SurfaceFlinger --list | grep -i whatsapp
adb exec-out screencap -p > black.png        # WhatsApp's controls on black = video only; all black = nothing drawn
adb shell dumpsys notification --noredact | grep -A40 'pkg=com.whatsapp' > notif.txt   # during the ring: category,
                                     # flags (0x40 = FGS), fullScreenIntent, android.callType - before and after its screen shows
adb logcat -v epoch -s AndroidLock PinLock VoipCalls ActivityTaskManager WindowManager ActivityManager | grep -iE \
  'whatsapp|Voip|PinLockActivity|keyguard|START|Displayed'
```

**Confirming the double start:**
- (a) A debug build with a one-line switch that skips `sendVoipRingScreen` in android mode: 10 video calls with the
  screen dark, counting the black ones. Today's build gets the same 10.
- (b) Without a build: the same calls in a parent pause (the lock is off, so neither our lock-up nor our send runs;
  SystemUI's full-screen intent alone). Zero blacks there and some here points at us.
- Audio calls in both, as the control.

## 7. Steps

| Step | Size | What |
|---|---|---|
| 1 | S | §3 VoIP in android mode: `voipRingStep` (pure: full-screen intent present, android-locked, interactive, grace, the glance's front since the ring) replacing `showLock`/`wake`/17b's send there; the reopen gate; the `ownStartAt` stamp at every send. |
| 2 | M | §2 the fast re-front: `androidLossStep` in `RefrontPolicy.kt`, the `askOpen`/`ownStartAt` inputs, `NO_USER_ACTION` on the android re-front, the try cap, `face_refronts` (status contract: a new field in `lock_state`, both directories, one commit). |
| 3 | S | §4 the lock wallpaper (`WallpaperApplyPlan` gains the android lock choice; home re-set after) and `setOrganizationName`. |
| 4 | S | §5 logging only: one `AndroidLock` line per screen-on/off with `interactive` and the keyguard read, so the next logcat names the flash. |

## 8. Tests (JVM, behaviour)

- `RefrontPolicyTest`, android gate:
  - dark -> Stop; each exemption -> Yield;
  - `askOpen`/`ownStartAt` -> Stop;
  - else Refront, then Stop after 2 tries;
  - IN_CALL without `ownStartAt` -> Refront, with it -> Stop;
  - RINGING -> Yield;
  - own mode's rows unchanged (the never-give-up row pinned).
- `VoipCallPlanTest`:
  - android + full-screen intent + locked -> no lock-up and no wake;
  - the send only after the grace with an unbroken front and the screen on;
  - dark after the grace -> card;
  - `FSI_DENIED` -> card at once;
  - the reopen refused inside the flip grace and without `IN_COMMUNICATION`;
  - own mode unchanged.
- `WallpaperApplyPlanTest`: the android lock choice is the glance's ground; home set after lock; the off edge restores
  design 08's choice.
- A manifest pin: `PinLockActivity` has no `dismissKeyguard` (§2.2).

## 9. Emulator checks

1. During a WhatsApp video call over the lock, the chat arrow, five times. Each time: SystemUI's face ≤ 0.3 s, then the
   glance, then the call screen. No chats, and `face_refronts` counted.
2. DeskClock over the glance (no call): the glance back ≤ 0.3 s; then repeatedly within 1 s: two tries, then SystemUI's
   face stays; a swipe up and the PIN leave (`USER_PRESENT`).
3. "Lås opp": the pad stays up for 30 s with no re-front. The pad's Emergency button opens the dialer and it stays.
   Back on the pad returns to the glance.
4. Five power presses (SOS): the countdown stays. The alarm over the lock stays. `gsm call`: our call screen stays.
5. Rings, alternating video and audio, 10 in a row with the screen dark and 5 with the glance on screen:
   - no black screen;
   - one `VoipActivityV2` start per ring (no `realCallingUid=<ours>` start while dark);
   - Decline, and a caller's hang-up, return to the glance with no t145-style duplicate.
6. A ring with WhatsApp's full-screen-intent permission revoked: our card.
7. §4: the face, the pad backdrop and the disclosure name; Home's wallpaper and the palette setting unchanged; the
   rollback restores the lock wallpaper.
8. §5's capture on 20 quick power presses; any RELOCK line is a finding.

## 10. Open questions

1. The vibb mark on the night lock ground: yes (default) or the plain ground?
2. The organisation name for "...tilhører ...": a family name the parent types, or "Vibb"? Default: none until the
   PWA has the field.
3. During a call, two losses within 5 s stop the re-front. Is a SystemUI face that stays after a third chat-arrow tap
   acceptable until the call ends? Usage access (design 57) removes this.
