# 59b - Android's lock as the gate: keeping our face in front, the WhatsApp ring, the look of Android's pad

Status: design, revised 2026-10-22 from its QA (`docs/review/qa-59b-design.md`, Q59b-1..12; one line per finding at
the end). Nothing built yet. Base: design 59 (§3.3, "Step 3 as built"), assessment 59 §4.2, the RC run
(`docs/testing/2026-10-22-d59-rc-run.md`), and the user's reports and logs on 0.36.0-rc.1 (android mode, emulator).
AOSP references are `android16-release`; launcher paths are under `lock/` unless named.

## 0. Summary

- **Step 0, the user's live bug** (Q59b-1): an unlock on Android's own face or pad lands on our Home over the app the
  kid unlocked into. The fix: a leave without Home when the glance isn't in front.
- **SystemUI's face over the glance.** A non-showWhenLocked top activity un-occludes the keyguard; WM sleeps the
  display. If the glance was in front, it is stopped and SystemUI draws its face. Today nothing of ours brings the
  glance back while the screen is on: `refrontAction` stops on `androidGate` first.
  - **Fix:** a fast re-front in face mode, on the existing pause/stop check (200 ms). Only for an unexpected loss
    (no exemption, no open PIN-pad ask, no start of our own), with at most two tries per screen-on.
  - **Not during a VoIP call:** there the glance is already stopped under the call screen. The plain yield stays
    (Q59b-2).
- **The WhatsApp black screen** is a race. SystemUI launches the ring's full-screen intent itself; our lock-up and our
  17b send then move the same activity again while it starts.
  - **Fix:** in face mode the platform's full-screen intent is the ring screen. After a 3 s grace we either send once
    (the heads-up case) or record that the platform had its turn, so the card follows.
- **Look:** the night ground with the vibb mark becomes the lock wallpaper (the user's decision), so SystemUI's face
  and the pad's backdrop are night. The pad's colours are not ours to set. No organisation name.

## 1. What happened in the user's cases

**How Android un-occludes.**
- `KeyguardController.updateVisibility` (:711-746) lets only the top task's top activity occlude, and only if it
  `canShowWhenLocked`. WhatsApp's `HomeActivity` can't (PLAN 2026-10-21: only `VoiceMessagingActivity` has the flag).
- `updateKeyguardSleepToken` (:566-572) then takes the sleep token, because `isKeyguardUnoccludedOrAodShowing`
  (:130-136) is true. The new activity isn't resumed; only showWhenLocked activities are visible (:409-420).
- SystemUI animates its face in: the lock wallpaper (the parent's photo opt-in, design 08), the clock and the
  device-owner disclosure. A chat tap shows no chat content: the keyguard holds.

**The chat arrow during a WhatsApp video call.**
- The call screen was on top. Our glance was already stopped beneath it, so the arrow gives us no event at all
  (Q59b-2).
- **The ~3.5 s, then black, then the glance** came from somewhere other than a re-front. The likely chain:
  1. The arrow slept WhatsApp's activities; WhatsApp then left `IN_COMMUNICATION` or re-posted its notice.
  2. The next 2 s pass (`VoipCalls.kt:66`, `VoipCallPlan.kt:378`) read `IN_CALL -> NONE`.
  3. `VoipEnded` -> `showLock` (`PinLockState.kt:216-224`).
  4. Our black stand-in (`PinLockActivity.kt:451`), then the glance.
- **Or** a screen-off: the user's press or SystemUI's idle timeout, then the lock-up at screen-off. The log tells:
  `VoIP IN_CALL -> NONE`, or `screen-off, lock-up` with `screen_toggled`.

**The PIN typed on SystemUI's pad (rc.1, 19:58).** The chats were on top (15.863), then:
1. 16.896: `USER_PRESENT ... the glance leaves`, which runs `presentLeave` -> `unlock` -> `leave` (`PinLockActivity.kt:399`,
   `:1006`, `:1025`).
2. `go(lockLeave(..))`. `lockLeave` (`LockTaskRoot.kt:74-79`) is own mode's rule: `homeFirst` is always true with the
   kiosk on, and `!callLive` with it off.
3. 16.928: `HomeFront.bring(this, "the lock left")` (`:1048`). Our Home covers the chats.

In own mode, Home sits above any latent task the lock exposes. In face mode, the glance was stopped *under* the app, so
its finish exposes nothing, and Home only hides the app.

## 2. Step 0: the leave without Home (Q59b-1, Q59b-6)

- **`lockLeave` gains `glanceInFront`**, read from the activity's `resumed` at the leave (default `true`, so every own
  path is unchanged).
- **Not in front** (an unlock on SystemUI's face or pad: `USER_PRESENT` while the glance is stopped):
  - `homeFirst = false`, no `GlanceOpener` (no row target can exist without our ask), and the glance finishes under
    the app.
  - The lock task is stopped first only when it is ours, as today (kiosk off: started or running).
  - **Kept exception:** kiosk on and `startedLockTask` -> `(stopLockTaskFirst, homeFirst) = (true, true)`. The glance
    is the lock-task root; stopping its lock task drops the kiosk, and Home re-roots it. `rootLeave` stays the retry.
- **In front** ("Lås opp", a row via the pad, a fingerprint from dark that resumes the glance) -> today's rule.
- **The UNLOCKED edge's chrome pass is unchanged.** With the kiosk on, Home is the root and the permitted app stays in
  lock task.
- **A runtime check that finds Android open** (Q59b-6): `androidGate` is false, so today it would take own mode's
  `Refront`. Then the glance comes over the app, `RESUME` -> LEAVE, and the in-front branch starts Home. So the
  re-front step takes `presentSinceLocked`:
  - Face on, Android open, mark set -> the leave without Home (the activity's `androidUnlockHook`, or with no
    activity `LockEvent.Unlocked`), never a re-front.
  - Mark not set -> today's re-front. It stays as the backstop: the glance's resume then WAITs and RELOCKs an
    unexpectedly open Android.
  - `presentLeave` with a non-LEAVE step reads again after `ANDROID_ANSWER_WAIT_MS` instead of dropping it.

## 3. A fast re-front in face mode

**The rule.** A pure `androidLossStep` in `RefrontPolicy.kt` replaces the blanket `inputs.androidGate -> Stop`
(`RefrontPolicy.kt:80`). It runs on the same pause/stop -> `PAUSE_REFRONT_MS` check (`PinLockRuntime.kt:1465`, `:1482`).
While Android's secure keyguard is locked under the face:
1. **Not interactive -> Stop.** This comes before the exemptions only in face mode; own mode's dark-cover row
   `DarkLockUp` is unchanged (Q59b-12). The screen-off lock-up and the sleep check own the dark.
2. **Exemptions -> Yield**, the existing rows in today's order: `ourCall`, `telecomInCall`, `emergencyFlow`,
   `alarmRinging`, `voipCall` (ringing **and** IN_CALL - no re-front during a call, Q59b-2), `sosFlow`.
3. **Ours -> Stop**:
   - `askOpen`: `askedAt` set in `PinLockActivity`, or an answer less than 1 s old (`ANDROID_ASK_SETTLE_MS`). This
     covers the pad and its Emergency dialer. It is published as an elapsed stamp, so a lost callback expires.
   - `ownStartAt` less than 2 s old (Q59b-8). There is **one** stamping point, `PinLockRuntime.noteOwnStart(why)`,
     called in `VoipCalls.send` (`VoipCalls.kt:401`) and in one shared start for our showWhenLocked screens
     (`InCallActivity`, `VoipWakeActivity`, the time-rule screen). Our Nødsamtale is `emergencyFlow` already. A
     source test fails on any `startActivity` in `lock/` that bypasses it (the glance's own `show()` excepted).
   - `ownOthersResumed > 0`, as today.
4. **Android open** -> §2's step (`presentSinceLocked`).
5. **Anything else -> Refront.** `show()` adds `FLAG_ACTIVITY_NO_USER_ACTION`, so the covered activity gets no user
   leave hint (no PiP, no Element hang-up: design 57).
6. **The cap** (Q59b-3): a dedicated `androidRefronts`, an input of the step, zeroed only at a screen-off and at
   `USER_PRESENT`, at most 2 per screen-on. `refrontAttempt` is useless as the cap: every resume, pause and stop zero it
   (`:1441`, `:1474`, `:1492`). A third loss -> Stop: SystemUI's face, with our ground (§5), until the screen-off.
   - Own mode never gives up (a cap fails open there, QA 10 #2). Here a cap leaves only SystemUI's face.
   - It is also what keeps us off SystemUI's own pad.
7. **Logging:** `AndroidLock: re-front #n (why)` only. No contract field (Q59b-7).

**What it can't tell.** No public API separates "un-occluded" from "another showWhenLocked activity over us":
`isKeyguardLocked` is true in both (`KeyguardController.java:148-151`), and the locked-state listener needs a permission
we can't hold. An unknown showWhenLocked screen is covered at most twice, then left: a look, never a path. Design 57's
top-activity read makes this exact.

**Why it can never pass the PIN.**
- `PinLockActivity` is `showWhenLocked` with no `dismissKeyguard` or `turnScreenOn` (manifest :159-165, pinned by a
  test). On top it becomes `mTopOccludesActivity` (:735-740). `mDismissingKeyguardActivity` needs a dismiss window
  (:727-729). So our start occludes and dismisses nothing.
- `requestDismissKeyguard` stays in `askAndroid` alone. The leave rule (`faceStep`, design 59 §4) is untouched, and §2
  only removes a Home start.
- **BAL:** the device owner may start from the background (`BackgroundActivityStartController.java:1090-1092`), as F3
  does.

## 4. The WhatsApp ring that went black

**What we know.**
- During the black, `topResumedActivity` was `VoipActivityV2`, occluding and resumed.
- The screenshot showed the status bar and the handle only: no controls, no name. A missing camera would leave
  WhatsApp's controls; answered video calls on this emulator showed both the video and the controls.
- It is intermittent (video fine, video black, audio fine, video fine), so a race.

**The double start** (19:44; 19:49 matches):
1. 28.332: SystemUI launches the full-screen intent itself. The decision is `FSI_DEVICE_NOT_INTERACTIVE`
   (`FullScreenIntentDecisionProvider.kt:174-193`; `_KEYGUARD_SHOWING`/`_OCCLUDED` with the screen on). That is the
   platform's job under a locked keyguard. 17b's own send exists for own mode, where the keyguard is off and the ring
   heads up.
2. 28.359: our `VoipRinging` -> `showLock` + wake (`PinLockState.kt:208-212`) covers WhatsApp's splash.
3. 29.601: 17b's `sendVoipRingScreen` (`PinLockRuntime.kt:1041`) moves the same singleTask activity to the front
   (`START_TASK_TO_FRONT`).
4. `notePause` can't see a ring screen that came *before* our lock, so it sent.

**The flip, 0.3 s after display.** WhatsApp re-posts its ring notice without the full-screen intent. `voipNoticeKind`
reads it as NONE (an incoming CallStyle) or as IN_CALL (a CALL FGS) (`VoipCallPlan.kt:140-143`). Either way
`continueCall` holds IN_CALL for `VOIP_GRACE_MS` (15 s, :365-380).

**The covered ring** (Q59b-5). After the flip, nothing holds IN_CALL past 15 s except `inCall && audioInCommunication`
(:378), and a ring has no `IN_COMMUNICATION`. So a ring that outlasts the grace goes NONE -> `VoipEnded` -> `showLock`:
the glance covers a still-ringing screen, with no card (`ringId` is null), so the kid can't answer. The 19:44 end fits
this (flip ≈ 29.9 + 15 s + the 2 s poll ≈ 45.8; NONE at 45.834) as well as "the ring ended".

The 45.570 start was our `reopenCall` from the glance's resume (`LockResumed` with IN_CALL); WhatsApp finished it at
once. The SOS line does nothing in face mode (the check stops first; `sosMarkKept` is false).

**The fix, face mode with Android locked** (own mode unchanged; `VoipCallPlanTest` pins both):
1. **`VoipRinging` with a full-screen intent -> no `showLock`, no wake.** SystemUI launches it and wakes the screen.
2. **The grace** (Q59b-4) is a pure `voipRingStep`, 3 s from `ringStartedElapsedMs`, and it always writes `voipFsiTry`:
   - The glance has been resumed since the ring's first sight (no `notePause` mark), the screen is on and Android is
     locked: the heads-up case -> the one send, `VoipFsiTry(sent = true)`.
   - Otherwise -> `VoipFsiTry(sent = false)`: "the platform had its turn". `voipRingUi` gives `CARD`, `voipFsiDue` is
     false, and a later resume never sends again.
   - Still dark -> the wake + the card, with no send (DND rings this way, as own mode does today).
   - `FSI_DENIED` -> the card at once, unchanged.
3. **The reopen** (Q59b-9): in face mode only with `audioInCommunication` at the send. That tells the ring from the
   call, and a Back within 15 s of answering still gets the call screen back. Every send goes through
   `noteOwnStart`.
4. **The re-posted ring** (Q59b-5), a candidate rule once §7's dump confirms it: a re-post by the ringing package (an
   incoming CallStyle, or a CALL notice that lost its full-screen intent) continues the ring, up to
   `VOIP_RING_LIMIT_MS`. Until then, check 5b measures the covered-ring case.

## 5. The look of Android's face and pad (Q59b-10, Q59b-11)

**What can be set:**
- The lock wallpaper (`FLAG_LOCK`) is the backdrop of SystemUI's face and, under the bouncer's scrim, of the pad.
- The pad's colours come from the one system palette (`THEME_CUSTOMIZATION_OVERLAY_PACKAGES`). With the new picker
  it is seeded from the home wallpaper; otherwise from the higher wallpaper id (`ThemeOverlayController.java:254-262`).
  The device owner can't write the setting (`DevicePolicyManagerService.java:728-735`).
- Layout, the clock style and PIN auto-confirm (Android's rule: 6+ digits) are SystemUI's or the user's.

**The step:**
- `lockScreenFill(home, androidFace)` (`Wallpapers.kt:92`) gives:
  - in face mode, the **night ground with the vibb mark** (the user's decision), drawn by `WallpaperRender` at the
    screen size, with no asset;
  - the parent's lock-photo opt-in (design 08) still wins when ticked, under the glance's scrim. That adds no family
    data: the photo is the parent's existing opt-in.
- `setBoth` (`WallpaperApplier.kt:170-190`) already sets the lock first, then home, in one pass. `wallpaperKey`
  carries the lock fill, so the face's edges re-apply by themselves, and a rollback restores design 08's choice. There
  is no new hook.
- On the old picker path, the lock `setBitmap` briefly writes `color_source: lock` and the home set writes it back.
  That is design 08's transient today, not new.
- **No organisation name** (Q59b-11). "tilhører Vibb" is false (Vibb doesn't own the phone), and a family name tells a
  finder whose phone it is. The name would also show in Settings and in Quick Settings' "managed by" footer. So the
  disclosure stays generic until the PWA has a field. Any later setter must clear it (`null`) on the rollback and on
  the unmanaged path.

## 6. The flash on repeated power presses

| Candidate | Log line | Emulator-only? |
|---|---|---|
| RELOCK under the thumb (a quick off/on reads Android open -> WAIT -> `lockNow`) | `didn't lock under the glance - locked it` | No. None seen in the RC's 107 lock-ups. |
| The press beats the lock-up's start (A §4.3, ≈100 ms) | `screen-off, lock-up` after the next `screen_toggled 1` | No |
| No AOD/doze: an instant panel with no fade shows one frame of the occlusion change | `wm_set_keyguard_shown` toggling `occluded` | Yes, the likeliest |
| The glance's task snapshot before its first frame | Nothing; only a recording | No |

```
adb logcat -c; adb logcat -v epoch -b main,events -s AndroidLock PinLock PinLockActivity KeyguardViewMediator \
  screen_toggled wm_set_keyguard_shown wm_set_resumed_activity wm_on_paused_called wm_on_stop_called > power.log
adb emu screenrecord start --time-limit 30 /tmp/power.webm   # the console recording keeps FLAG_SECURE windows
```

## 7. When WhatsApp goes black again

```
adb shell dumpsys activity activities | grep -E 'topResumedActivity|VoipActivityV2'
adb shell dumpsys window | grep -E 'mCurrentFocus|VoipActivityV2|isOnScreen|mHasSurface'
adb exec-out screencap -p > black.png              # controls on black = video only; all black = nothing drawn
adb shell dumpsys notification --noredact | grep -A40 'pkg=com.whatsapp' > notif-<t>.txt
                                    # at the ring, after its screen shows, and at flip + 15 s (still ringing?)
adb logcat -v epoch -s AndroidLock PinLock VoipCalls ActivityTaskManager | grep -iE 'whatsapp|Voip|PinLock|START|Displayed'
```

To confirm the double start, make 10 dark video rings, then audio as the control, in two setups:
- in a parent pause (no lock-up and no send) and on today's build;
- or on a debug build with the send off in face mode.

## 8. Steps

| Step | Size | What |
|---|---|---|
| 0 | S | §2: `lockLeave(.., glanceInFront)`, the `presentSinceLocked` input, `presentLeave`'s re-read. |
| 1 | S | §4: `voipRingStep` (no lock-up or wake with a full-screen intent, the grace writing `voipFsiTry`), the reopen gate on `audioInCommunication`. |
| 2 | M | §3: `androidLossStep`, `askOpen`, `noteOwnStart` (one point and its source test), `NO_USER_ACTION`, `androidRefronts`, the log line. |
| 3 | S | §5: `lockScreenFill(home, androidFace)` and the mark in `WallpaperRender`. |
| 4 | S | §6: an `AndroidLock` line per screen on/off with the keyguard read. |

## 9. Tests (JVM)

- `LockTaskRootTest`: `glanceInFront = false`:
  - kiosk off -> no Home, stop if ours;
  - kiosk on, not root -> `(false, false)`;
  - kiosk on, root -> `(true, true)`;
  - `true` -> today's rows.
- `RefrontPolicyTest`, face mode:
  - dark -> Stop; each exemption, IN_CALL included, -> Yield; `askOpen`/`ownStartAt` -> Stop;
  - Android open with the mark -> the leave, without -> Refront;
  - three losses with resumes between -> Refront, Refront, Stop; reset at a screen-off and at `USER_PRESENT`;
  - own mode's rows unchanged (never give up, `DarkLockUp`).
- `VoipCallPlanTest`:
  - face + full-screen intent -> no lock-up or wake;
  - the heads-up send only with an unbroken front, the screen on and the grace over;
  - otherwise `sent = false` -> CARD, never a later send; dark -> wake + card;
  - `FSI_DENIED` -> card at once;
  - the reopen only with `IN_COMMUNICATION`;
  - own mode unchanged.
- `WallpapersTest`: face -> night + mark, the photo opt-in kept; not face -> design 08's rule.
- Manifest test: `PinLockActivity` has `showWhenLocked`, and neither `turnScreenOn` nor `dismissKeyguard`.
- Source test: no `startActivity` in `lock/` bypasses `noteOwnStart`.

## 10. Emulator checks

1. During a call, the chat arrow, then SystemUI's face, then the PIN on its pad: the chats stay on top for 10 s, with
   no `the lock left` line. The same with the kiosk off.
2. DeskClock over the glance, relaunched three times: exactly two `re-front` lines, then SystemUI's face; a swipe up
   and the PIN stay in DeskClock.
3. "Lås opp": the pad stays up for 30 s; its Emergency dialer stays; Back returns to the glance.
4. SOS (five presses), an alarm and `gsm call` stay in front.
5. Ten rings in a row, alternating video and audio, screen dark, plus five with the glance on screen:
   - no black screen;
   - one `VoipActivityV2` start per dark ring (none with our uid);
   - Decline and a caller's hang-up return to the glance.
   - **5b:** a ring left unanswered for 40 s stays answerable (its screen or our card, never the glance alone), with
     the notice dumps of §7.
6. With WhatsApp's full-screen permission revoked: our card. **6b:** answer, then Back within 15 s: the call screen
   comes back.
7. SystemUI's face and the pad over the night ground with the mark (the photo when opted in); the generic disclosure;
   Home's wallpaper unchanged; `theme_customization_overlay_packages` before = after, read once the apply is done.
8. §6's capture over 20 quick power presses; any RELOCK line is a finding.

## QA fixes (`docs/review/qa-59b-design.md`)

- Q59b-1: step 0, `lockLeave(.., glanceInFront)`: an unlock on Android's own face or pad finishes the glance under the
  app with no Home; the kiosk-root exception is kept; own mode is unchanged (§1, §2, check 1).
- Q59b-2: §2.1 dropped: IN_CALL keeps `Yield("voip")`, and the old open question 3 is moot (§3).
- Q59b-3: a dedicated `androidRefronts`, reset only at a screen-off and at `USER_PRESENT`, cap 2 per screen-on (§3,
  check 2).
- Q59b-4: the grace always writes `voipFsiTry` (the one send, or `sent = false` -> CARD); no send after a dark grace
  (§4).
- Q59b-5: the covered ring is named, the re-posted-ring rule is written as a candidate, and the dump at flip + 15 s
  plus check 5b test it (§4, §7).
- Q59b-6: Android open with the mark -> the leave without Home; without the mark, the re-front is kept as the backstop;
  `presentLeave` re-reads (§2).
- Q59b-7: `face_refronts` dropped; a log line only, no contract change (§3).
- Q59b-8: one `noteOwnStart` point and a source test (§3).
- Q59b-9: the 15 s no-reopen window dropped; `audioInCommunication` alone gates the reopen (§4, check 6b).
- Q59b-10: the smaller step via `lockScreenFill`; the night ground with the vibb mark; the photo opt-in kept; the
  palette transient explained (§5).
- Q59b-11: no organisation name, and why; a later setter clears it on the rollback and the unmanaged path (§5).
- Q59b-12: the dark row is face mode only; the manifest pin covers `turnScreenOn`/`dismissKeyguard`; steps renumbered
  from 0 (§3, §8, §9).
