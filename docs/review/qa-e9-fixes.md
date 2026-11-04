# QA (light) of branch `e9-fixes` - the E9 findings M1, L1-L8

Reviewed by code reading only (`git diff a458a44c..HEAD`, commits d107e9b3 launcher and ae0f2a8a server); no build, no emulator.
Source of the findings: `docs/testing/2026-11-04-e9-run.md`. Counts: **High 0, Medium 1, Low 3, Info 6.**
**Verdict: ship after fixing Medium 1** (a small generation guard); the Lows can ride along or be queued.

## Medium

**M1 - A pending fallback from an earlier tap undoes the kid's later tap**
`launcher/.../music/MusicSessions.kt:362-378` (`send`, the `main.postDelayed` at :370), `LockSessionPick.kt:63` (`tileFallback`).
Each play or pause tap on a non-Vibb session posts its own 1 s check, and the check only looks at the state *now*
("is the player where this tap asked it to go?"), not at whether a newer tap has since changed what is wanted. Scenario
(Fossify or any player, which now obeys the key at once): tap Pause at t=0 (player pauses at ~0.1 s, the tile shows
paused), the kid taps Play again at t=0.5 (key PLAY, player plays). At t=1.0 the first tap's PAUSE check runs:
`playingNow == true`, `tileFallback(PAUSE, true)` is true, so `control(c, PAUSE)` calls `transportControls.pause()` and
the music the kid just resumed stops. The mirror case is Play, Pause (within 1 s), then the PLAY check finds it paused
and calls `play()`, which restarts what the kid just stopped (guarded by `usable`, but a usable app is the normal case).
A kid mashing the button, or a double tap, is the normal use of this tile; the result looks like "the button ignored me".
Several taps queue several such checks.
*Fix:* keep a per-controller (or global) generation counter in `MusicSessions`: bump it in `send`, capture it in the
runnable, and do nothing when it has moved (or `main.removeCallbacks` a token before posting). `tileFallback` stays pure.
Add a test of the guard (source-pinned like `MusicSuspensionGuardTest`, or move the decision into a pure function that
takes "newest tap id").

## Low

**L-a - Partial dispatch can double-fire a skip; legacy sessions that do not advertise a skip action lose it**
`MusicSessions.kt:389-393` (`mediaButton`) and :362-368. `mediaButton` returns `down && up`. If the DOWN was accepted
and the UP call returns false (the session died between the two calls) or throws, `send` takes it as "no button"
and also calls `control(c, NEXT)`: the action has already run on the DOWN, so a next skips twice. Very narrow, but the
header comment promises "a skip is never repeated". Treat a DOWN that was accepted as sent (`return` after DOWN
succeeded; the UP result only matters for logging). Separately: the framework's default `MediaSession.Callback.onMediaButtonEvent`
(legacy `MediaSessionCompat` apps that do not override it) acts on NEXT/PREVIOUS/PLAY/PAUSE only when the matching action
is in `PlaybackState.getActions()`, while `transportControls.skipToNext()` reaches `onSkipToNext()` unconditionally. A
legacy player that implements skip but does not advertise it worked on the tile before this change and now ignores the
button silently (skips have no fallback; play/pause do, via the 1 s check). Low, because a player that does not advertise
skip is usually wrong or has no skip; mention it in the notes, or fall back to the transport control for NEXT/PREVIOUS when
`playbackState.actions` lacks the bit.

**L-b - Back label "Tilbake til hjemskjermen" is still wrong for pages opened from the lock or a notification**
`ui/day/DayActivity.kt:221` and `calls/PhoneBookActivity.kt:67` use the default `kid_back_home`. Both are opened from the PIN
lock (`LockActivity.kt:295-297`, `GlanceOpener.kt:141,147`: "Back from it comes back here") and PhoneBook also from the
missed-call notification (`MissedCallNotifier.kt:321`). TalkBack says "back to the home screen" while Back returns to the
lock or the previous app. The fix touched the three kid Settings pages and the two Event pages only. A neutral `kid_back`
("Tilbake") for these two, or choosing by the intent's origin, completes L6. The three Settings sub-pages are right: Wi-Fi,
Bluetooth and the sound picker are only started from `KidSettingsActivity` (:166, :170, :184).

**L-c - Wallpaper labels now have uneven item widths**
`server/static/style.css:2251-2265` (`width: min-content; min-width: 76px; max-width: 100%`). A label whose longest word
is wider than 76 px (for example "Kveldshimmel" at 0.8125rem, about 85 px) makes that one item wider than its neighbours,
so a wrapping row of items no longer lines up in a regular grid. Cosmetic and the stated goal (no mid-word break) is met;
only noting it. The save path is untouched (CSS and cache-buster only), so the "a save must never jump to top" rule is not
affected. `style.css?v=80` is bumped in the only head partial.

## Info

1. **L1 dialogs are sound.** `editServerUrl(row, typed, refused = true)` reopens only from the OK button of a refused value
   (never on its own, never on cancel), keeps the typed text and shows `server_url_refused` as the dialog message above the
   input (`setMessage` + `setView` coexist in `AlertDialog`). The QR path's new pre-check at `SettingsFragmentLauncher.kt:660`
   is exactly the check `applyProvisioningExtras` makes first (`Provisioning.kt:29`), so the outcome is the same: nothing is
   saved, nothing enrolled; the blank-field case cannot differ (`fromQrJson` already rejects a blank address). "Sync now"
   (:710) uses the same OK-only dialog. `Provisioning.kt:31` still carries the same string in `EnrollFailure` for the
   other callers; fine.
2. **L3 is sound.** `fitEmergency` runs from `layoutNow` (first layout, then on every height or inset change), starts from the
   full 15 sp each time (no accumulating shrink, no loop: `setTextSize` is called only when the value differs, and a changed
   text size does not change the height that keys `layoutNow`). The shrink never goes below what the longest word needs for
   the room, which at 320 dp and font 2.0 is about 0.88 of 30 dp, so not too small; English wraps between "Emergency" and
   "call". A width-only change with the same height (rare) would keep the old size until the next layout. **The time-lock
   screen's `lock_emergency_button` does not need the same treatment:** `activity_lock.xml:93` is `wrap_content` in a
   full-width column with only its 18 dp padding, and has no 52 dp margins on both sides (the glance's margins, made for the
   48 dp torch button, are what left only ~188 dp for a ~168 dp word at 2.0). At font 2.0 it has the whole page width, so it
   cannot break a word until the font is far beyond Android's 2.0 maximum. No change recommended.
3. **L2, L4, L5, L7 read correct.** L2: `lastOwnAlarmPx` mirrors the date-line copy, set only when the text is rebuilt; no
   extra rebuild per tick. L4: `WeatherWords.spoken` strips U+00AD at all three speech builders (a test pins the count of
   call sites). L5: nb "Du har nyeste versjon" is bokmål (nynorsk would be "nyaste"), en "You have the latest version";
   the music card's own "Oppdatert/Up to date" (`device.music.library_current`) is a different key and was left, which is
   right (it reads fine with its row label). L7: both chevrons are hidden together with the 50 % alpha and restored on
   `writable`; nothing else keys off their visibility.
4. **i18n and the kid's strings.** New launcher strings `kid_back_settings`/`kid_back` exist in `values/` and `values-nb/`
   (bokmål "Tilbake til Innstillinger", "Tilbake"; "Innstillinger" matches `kid_settings_title`); server strings exist in
   both `en.json` and `nb.json` (only those two files exist). The diff adds no string with "Spotify", and the tile's text
   path is unchanged.
5. **M1 otherwise holds up.** No double fire from key plus fallback: the fallback is posted only after the key was
   accepted, only for PLAY/PAUSE (never a toggle key, so repeating a pause or play is idempotent), and a skip is never
   repeated. A suspended or blocked app: `playPause`, `next` and `previous` check `usable` at the tap, the fallback re-checks
   it for PLAY at fire time, and PAUSE always goes (as before). Vibb music and Home's card are unchanged (`vibb = true`
   gives `tileMediaKey == null` and the old transport-control call; `LockMediaGuardTest` still pins the four calls and now the
   four key codes). No leak: the runnable holds the controller and the application context for one second, and is not
   cancelled (see M1 for why it should be superseded). `playbackState` on a framework `MediaController` is read over binder
   at the call, so the "took effect" test sees the current state; BUFFERING and the SKIPPING states count as playing
   (`sessionPlay`), so a stream that is still starting a pause is not re-paused and a play that is buffering is not
   re-played. A slow player (fade-out longer than 1 s, a stream in CONNECTING or STOPPED for over a second) gets one extra
   pause()/play(), which is idempotent. `dispatchMediaButtonEvent` returns whether the call reached the session, not
   whether it was handled, so the fallback check is what covers a handler that ignores the key.
6. **Not verified on a device.** The tests pin the key choice and the guard strings; nothing exercises
   `dispatchMediaButtonEvent` on Fossify itself (the E9 run used `cmd media_session dispatch`, which goes through the
   service's route, not the controller's). Re-run the E9 item 1 transport on the emulator (pause and next on Fossify, a
   quick pause-play-pause to check M1 above) before the tag. PLAN.md still says "IN BUILD" for this item; mark it built,
   awaiting QA, and (when merged) done in the merge commit, as the working rules say.
