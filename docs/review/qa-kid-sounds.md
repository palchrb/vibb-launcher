# Code QA: branch `kid-sounds` (24b832fe) - ringtone and message sound pickers

Light code QA, read only (no Gradle, no cargo). 2026-11-04.

## High

- **H1 `KidSounds.kt` Preview.play (the `setOnCompletionListener` / `setOnErrorListener` lambdas): `stop()` calls
  `MediaPlayer.stop()`, not `Preview.stop()`.** The lambdas sit inside `MediaPlayer().apply { ... }`, so the closest
  implicit receiver is the MediaPlayer and an unqualified `stop()` binds to it. Effects:
  - Completion: a sound that plays to its end is only stopped, never `reset/release`d, `player` stays set and
    `abandonAudioFocusRequest` is never called. Music/other apps stay ducked or paused until the kid taps again or
    leaves the page (the footer's promise "stops" is kept, the focus release is not). Every preview that is not cut
    off by a tap or leaving hits this.
  - Error: `MediaPlayer.stop()` in the Error state throws IllegalStateException on the main looper (uncaught in a
    listener) - a launcher crash on any decode/server-died error.
  Fix: `this@Preview.stop()` in both lambdas (or move the stop out of the `apply`).

## Medium

- **M1 Preview.play, the catch path leaks the MediaPlayer.** `player = MediaPlayer().apply { setDataSource(...) }`:
  when `setDataSource` throws (missing/unreadable URI, SecurityException) the instance was never assigned, so the
  catch's `stop()` has nothing to release. Create into a local, assign first, release in the catch.
- **M2 No audio-focus listener, and the focus result is ignored.** The request has no `OnAudioFocusChangeListener`, so
  the preview does not stop when a call rings as a heads-up (the picker stays resumed, so no `onStop`), nor in a call
  (`requestAudioFocus` returns FAILED and it plays anyway). Short unlooped clips, so bounded, but cheap to fix:
  listener that stops on LOSS/LOSS_TRANSIENT, and do not play when the request is not GRANTED.

## Low

- L1 No `onDestroy` stop/release as a belt (today `onStop` always precedes it, so no leak found).
- L2 `sounds` is cached even when `KidSounds.sounds` failed and returned an empty list (the "No sounds" message stays
  until the activity is recreated). `lead` is also lost on a recreate (font scale, dark mode - `configChanges` only has
  orientation|screenSize), so the parent's file sound drops off the list if the kid had already switched.
- L3 Fast double tap on a Kid Settings row can open two pickers (no singleTop); harmless.
- L4 Stale `saved` can be set by a load that was in flight when a write started (the next tap may issue one extra
  idempotent write); the final `load()` fixes the view.

## Checked, nothing found

- No path out of the kiosk: no intent started except our own Kid Settings -> picker; `ACTION_RINGTONE_PICKER` banned
  by `KidScreensEscapeTest` and both new files added to its list. Activity `exported=false`, same attributes as
  KidSettingsActivity, our own package = lock-task package.
- Shared-storage sounds: the cursor's external rows are dropped by `isBuiltInSound` (`content://media/internal/`; the
  `0@` user id is stripped, a look-alike authority does not match). The only non-filtered URI is the lead = the
  phone's current default (set by the parent); playing or re-writing it adds no capability. A file the kid placed
  can only matter as that lead, and then it only plays audio.
- Preview stops on Home, screen off, a full-screen call, the lock (`onStop`; `redirectIfLocked` also stops), parent bit
  or app-op gone (`finishIfNotAllowed`); rotation is handled by `configChanges` and the sound correctly continues.
  Back/leave: footer promise kept by `onStop`.
- Writes: `KidSounds.set` on `Dispatchers.IO` under a Mutex, catches SecurityException/IllegalArgumentException, result
  is the read-back (`sameSound`), `load()` after the last write flips a refused row back. `Settings.System.canWrite`
  on main is the same cheap check the touch row does.
- Rows hidden with the sound bit off, read-only (50 %, not clickable, touch row's line) without the app-op; also
  shown on a phone without a vibrator.
- "Parent's own file stays first": `leadSound` is taken once at first load, kept while open; built-in current -> none.
- TalkBack: RadioButtons direct children of a RadioGroup; rows have contentDescription "label, value"; chevrons
  `importantForAccessibility=no`. Strings: all 7 new keys in `values` and `values-nb`.

## The vibration claim (builder's note)

Right for the part I could fetch: android14-release Telecom `SystemSettingsUtil.isRingVibrationEnabled` has the comment
"VIBRATE_WHEN_RINGING setting was deprecated, only RING_VIBRATION_INTENSITY controls the ringtone vibrations on/off
state now" and reads only `Settings.System.RING_VIBRATION_INTENSITY != VIBRATION_INTENSITY_OFF`. That `RING_VIBRATION_INTENSITY`
is `@hide` and not in `PUBLIC_SETTINGS` (so refused to a third-party app) I did not re-fetch (Settings.java was
truncated by the fetch tool); it matches what I know of AOSP, but not verified here. Not building the row is the honest choice.
