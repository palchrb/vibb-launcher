# QA of design 59b (draft) - Android's lock as the gate: face, ring, look

Reviewed: `docs/design/59b-android-lock-face.md` at `aa3eee01`, against design 59 (§3.3, §3.9, steps 3/4 as built),
`qa-59-step3-code.md`, the lock code on `fix-f3-boot-cover`, AOSP `android16-release` (`ThemeOverlayController.java`,
`FullScreenIntentDecisionProvider.kt`, fetched), and the coordinator's rc.1 log of the "app minimised after the PIN"
case (19:58:03-17.1, 19:55:28). Line numbers are the launcher's `lock/` unless named.

Verified, no finding: the theme seed rule (`ThemeOverlayController.java:254-262`: new picker = home always, else the
higher wallpaper id); the FSI order (`FullScreenIntentDecisionProvider.kt`: not interactive -> keyguard state ==
KEYGUARD -> couldHeadsUp -> occluded; DND after); the RINGING -> IN_CALL flip (`voipNoticeKind`, `VoipCallPlan.kt:140-143`:
an incoming CallStyle reads NONE, a CALL FGS notice IN_CALL; either way `continueCall` :365-380 holds IN_CALL on the
grace); `requestDismissKeyguard` only in the ask; the double-press camera stays refused (`cameraGuarded`, P59-4).

## Findings

**Q59b-1 (High) - An unlock through Android's pad lands on our Home over the app the kid unlocked into.**
Evidence (user, rc.1): 03.336 the chat arrow starts WhatsApp's `HomeActivity` -> SystemUI's face 12 s -> PIN -> 15.863
WhatsApp top -> 16.896 `USER_PRESENT ... the glance leaves`, `LOCKED -> UNLOCKED` -> 16.928 `Home brought to the front:
the lock left` -> our Home top. Path: `androidUnlockHook` (`PinLockActivity.kt:393`) -> `presentLeave` (:399) ->
`actAsFace(LEAVE)` -> `unlock()` (:1006) -> `leave()` (:1025) -> `go(lockLeave(..))` -> `HomeFront.bring(this, "the lock
left")` (:1048). `lockLeave` (`LockTaskRoot.kt:74-79`) gives `homeFirst = true` always with the kiosk on, and `!callLive`
with it off - own mode's rule (design 16 QA #5(a), design 32 decision 2: the keypad unlock lands on Home, Home above any
latent task). In android mode the glance was *stopped under* the app when Android unlocked, so nothing latent is
exposed by its finish; Home only hides the app. No other Home start runs on that edge (`afterUnlock` starts only the
time-rule screen, correctly; `HOME_THEN_LOCK` is a screen-off lock-up and is LOCK alone under a locked keyguard,
`PinLockRuntime.kt:687-690`; `BootHome` is the boot's first unlock).
Fix to the note (new §2.3, and step 0 - first, smallest, the user's live bug): `lockLeave` gains `glanceInFront`
(the activity resumed at the leave). Not in front (an unlock on SystemUI's face/pad: `USER_PRESENT` while stopped) ->
`homeFirst = false`, `GlanceOpener` not used, the glance finishes under the app, lock task stopped first only when ours
(kiosk off) as today. Exception: kiosk on and `startedLockTask` (the glance is the lock-task root, the fallback) keeps
Home, because stopping its lock task drops the kiosk and Home re-roots it. In front (Lås opp, a row via the pad, a
fingerprint from dark that resumes the glance) -> today's rule, so own-mode semantics hold. The UNLOCKED edge's chrome
pass stays as it is: with the kiosk on, Home is the root and the app (permitted) stays in lock task. Pure rows in
`LockTaskRootTest`; emulator check: chat arrow during a call -> PIN on SystemUI's pad -> the chats stay on top for 10 s.

**Q59b-2 (High) - §2.1 (re-front during IN_CALL) has no trigger in the user's case and covers the live call in others.**
During a VoIP call the call screen is on top and the glance is already stopped beneath it. The chat arrow
(`HomeActivity` un-occluding the keyguard) gives the glance no pause/stop (nothing of ours changes), so
`onLockPaused`/`onLockStopped` (`PinLockRuntime.kt:1464`, `:1482`) never schedule the check - §2.1's "≤ 200 ms + one
frame" can't happen. The only check that runs is the Yield loop (`EXEMPT_RECHECK_MS` 2 s, :1582), and it cannot tell
"chats over the call" from "the call screen in front". With §2.1 it would re-front over WhatsApp's or Element's live call
screen 2 s after every answer (no `ownStartAt`: §3 removes our sends), and §3.3's reopen gate (no reopen inside
`VOIP_GRACE_MS` of the flip) then leaves the glance over the call for up to 15 s. Fix: drop §2.1. IN_CALL keeps
`Yield("voip")`. After Q59b-1 the user's case is right as it is: SystemUI's face (our ground after §4), the PIN, the
chats, no Home. Open question 3 goes. The exact rule waits for design 57's top activity, as §2 already says.

**Q59b-3 (Medium) - The two-try cap never caps.** `refrontAttempt` is zeroed at every resume (`:1441`) and at every
pause and stop (`:1474`, `:1492`). A re-front that works resumes the glance and resets it, so an app that relaunches
itself over the glance (a showWhenLocked one, or anything un-occluding) ping-pongs forever. Check 2's "two tries, then
SystemUI's face stays" can't pass. Fix: a separate `androidRefronts` count, an input of `androidLossStep`, zeroed only at
a screen-off and at `USER_PRESENT`, with cap 2 per screen-on. Test: three losses with resumes between -> Refront,
Refront, Stop.

**Q59b-4 (Medium) - §3's grace leaves the ring UI in WAIT, and the dark fallback re-opens the double start.** With a
full-screen intent and no try, `voipRingUi` is `WAIT` (`VoipCallPlan.kt:289`), and `voipFsiDue` stays true until a try
for this ring (`:270`). §3.2's "dark after the grace -> today's wake + card" resumes the glance, and that runs
`sendVoipRingScreen` (`PinLockRuntime.kt:1041`) - our send again, racing an app screen that was only late. If the grace
passes with no send, the card never shows. Fix: `voipRingStep` writes the outcome into `voipFsiTry`. At the grace:
either the one heads-up send (glance resumed since the ring's first sight, screen on, Android locked) or a
`VoipFsiTry(sent = false)` that means "the platform had its turn". The second gives `CARD` and `voipFsiDue = false`.
The grace counts from `ringStartedElapsedMs`. "Lost the front since the ring" is `notePause`'s existing mark.
`VoipCallPlanTest`: no send after a dark grace, CARD after it, own mode unchanged.

**Q59b-5 (Medium) - A ring that outlasts the grace after the flip is covered.** After the flip, nothing makes
IN_CALL hold past 15 s except `pkg in inCall && audioInCommunication` (:378). A ring that is still going has no
`IN_COMMUNICATION`. So 15 s after WhatsApp re-posts its notice, the phase is NONE -> `VoipEnded` -> `showLock`, and
the glance covers a still-ringing screen. `ringId` is null by then, so there is no card either: the kid can't answer.
The 19:44 timing fits this as well as "the ring ended": flip ≈ 29.9 + 15 s + the 2 s poll ≈ 45.8, and NONE was seen at
45.834. The note rules out the 15 s coincidence for SOS but not for the grace. Fix: name the path. Have §6's dump also
record whether WhatsApp still rang at flip + 15 s. Add check 5b: a ring left unanswered for 40 s. Write in the
candidate rule for once the dump confirms: a re-post by the ringing package (an incoming CallStyle, or a CALL notice
that has lost its full-screen intent) continues the ring up to `VOIP_RING_LIMIT_MS`.

**Q59b-6 (Medium) - The re-front after `USER_PRESENT` is a second door to Q59b-1, and 59b widens it.** With Android
open, `androidGate` is false (`:1562`), so any check that runs while our chrome is still LOCKED uses own-mode rules ->
`Refront`. The glance comes over the app, `RESUME` gives LEAVE, and Q59b-1's "in front" branch then starts Home. The
1 s `presentLeave` delay while an ask is open (:396), a `presentLeave` that read Android still locked (no LEAVE, chrome
left LOCKED over the app), and a pending Yield recheck all lead here. Fix: `androidLossStep` takes `presentSinceLocked`.
Face on, Android open, mark set -> the leave without Home (Q59b-1's path), never a re-front. Mark not set -> today's
re-front: it is the backstop that RELOCKs an unexpectedly open Android, so keep it. `presentLeave` with a non-LEAVE
step reads again after `ANDROID_ANSWER_WAIT_MS` instead of dropping it.

**Q59b-7 (Low) - `face_refronts` in `lock_state`: a log line only.** A contract field touches both directories,
the snapshot and the compat tests for a look counter nobody acts on. `exempt_yields` and `keyguard_relocks` already
report the security-relevant events. Log `AndroidLock: re-front #n (why)` and drop step 2's contract part.

**Q59b-8 (Low) - `ownStartAt`'s source list will drift.** It names six starts, and any new one forgets the stamp. Our
own activities are already covered by `ownOthersResumed`, but late: their resume can land after the 200 ms check.
Fix: one `PinLockRuntime.noteOwnStart` called in `VoipCalls.send` (:401) and in the shared start of our showWhenLocked
screens (`InCallActivity`, `VoipWakeActivity`, the time-rule screen). EmergencyCall is already `emergencyFlow`. A test
fails if a `startActivity` in `lock/` while LOCKED bypasses it.

**Q59b-9 (Low) - §3.3's "never inside `VOIP_GRACE_MS` of a flip" blocks a real reopen.** The kid answers, then
presses Back within 15 s. The glance resumes and the reopen is refused, so the glance sits over a live call with no way
back. `audioInCommunication` at the send already tells the ring (not IN_COMMUNICATION) from the call. Keep that half and
drop the window.

**Q59b-10 (Low) - §4 is less code than written, and its palette claim holds only at the end.** `setBoth` already
sets lock first, then home, in one pass (`WallpaperApplier.kt:170-190`). `wallpaperKey` already carries the lock fill,
so the face's edges re-apply by themselves and a rollback restores. The step is `lockScreenFill(home, androidFace)`
(`Wallpapers.kt:92`) plus the mark in `WallpaperRender`. There is no new edge hook and no explicit "home re-set". On the
old picker path the lock `setBitmap` briefly writes `color_source: lock`, and the home set writes it back
(`handleWallpaperColors`). That transient is design 08's today, not new. Say so, and read check 7's before = after once
the apply is done. **User decision (open question 1):** night ground with the vibb mark on the lock wallpaper, so the
pad's backdrop is night. Write it in. Q: does the parent's lock-photo opt-in (design 08) still apply in android mode?
Default: yes, under the scrim. No family data is added: the photo is the parent's existing opt-in.

**Q59b-11 (Low) - `setOrganizationName`: recommend none.** "tilhører Vibb" is false: Vibb doesn't own the phone. A
family name tells a finder whose phone it is. The name also shows in Settings and in Quick Settings' "managed by" footer.
Default: unset, which is the generic "organisasjonen din", until the PWA has a field. Whenever one is set, the rollback
and the unmanaged path set it back to `null`. The note says neither.

**Q59b-12 (Nit)** - §2 step 1 "Not interactive -> Stop" should say it comes before the exemptions only in face mode
(own mode's dark cover row `DarkLockUp` unchanged). Pin the manifest test on both `showWhenLocked` and the absence of
`turnScreenOn`/`dismissKeyguard`. §7: renumber with step 0 = Q59b-1.

## Kid-path answers asked for

- Misread as "ours" (`askOpen` ≤ 1 s, `ownStartAt` ≤ 2 s): the worst case is SystemUI's face until the next screen-off.
  That is a look, never a path, because the keyguard holds. Misread as "unexpected": the bouncer from "Lås opp" keeps
  the glance resumed (focus only, design 59 step 3's device check). The pad's Emergency dialer stops it while
  `askedAt` is still set. A bouncer on SystemUI's own face gives us no event. With Q59b-3's real cap, a fight with the
  bouncer is impossible. After two tries the kid sees SystemUI's face with our ground, swipes up, types the PIN, and
  stays in the app (Q59b-1).
- Call UI, PiP, Element: with §2.1 dropped we never cover a call screen during IN_CALL. `NO_USER_ACTION` still matters
  for the general re-front (no user-leave hint -> no PiP -> no Element hang-up, design 57 :128-131).
- The ring: no FSI permission -> `FSI_DENIED` -> card at once (unchanged). DND: no FSI, the screen stays dark, and the
  grace gives the card plus a wake (as own mode does today, so no regression; `ringPlan` stays silent). Heads-up while
  the glance is in front: our ringer and WAIT for 3 s, then the one send. Own mode: every new rule is gated on face on
  and Android locked. `VoipCallPlanTest` pins this.

## Emulator checks to add or change

1 (rewrite): chat arrow during a call -> SystemUI's face -> PIN -> the chats stay on top (no `the lock left` line).
2: three DeskClock relaunches -> exactly two `re-front` lines. 5b: a 40 s unanswered WhatsApp ring stays answerable.
6b: Back within 15 s of answering -> the call screen comes back. 7: the night ground with the mark and no
organisation line.

## Open questions - recommendations

1. Answered by the user: night ground with the vibb mark. 2. None (Q59b-11). 3. Moot (Q59b-2).
