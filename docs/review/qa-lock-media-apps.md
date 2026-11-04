# Code QA: lock-media-apps (272621cb) - the PIN lock's media tile for any allowed app

Security-minded review of `git diff main...lock-media-apps`. Ran `LockSessionPickTest`, `LockMediaGuardTest`,
`MusicSuspensionGuardTest`, `MusicWindowTest`: all pass.

**High: none.** The lock still offers only transport controls on the picked session, and no foreign app gets a way to
open anything over it.

## Medium

**M1. Main-thread work on every playback update, for every app, and also while only Home is listening**
(`music/MusicSessions.kt:120,125-127,160-165,179-190`; `music/MusicLockPanel.kt:89`; `MusicSessions.kt:217-229`).
- `update()` ignores the callback's `state`/`metadata` arguments. Through `sessionOf` it calls `getPlaybackState()`
  and `getMetadata()` over binder, and `getMetadata` unparcels the session's art bitmap every time.
- Each change then reaches the lock's `lookUp()` (coalesced per frame). For **every** live session, `lookUp()` calls
  `isPackageSuspended` and `isApplicationHidden`, plus `currentPolicyDecision()`. That call decodes the
  last-enforced plan's JSON on every call, because the argument is evaluated eagerly. `WindowRelock.blockedPackages`
  reads its store each time too.
- Scenario: with the lock up, a podcast or YouTube-type player republishes its state about once a second (position or
  buffered updates) and has a 1-2 MB cover. Each second, the main thread does about 2 + 3N binder calls (N = live
  sessions), unparcels a bitmap and decodes N JSON blobs.
- While Home alone is listening (most of the time), every foreign session stays registered. Each update still does
  the two binder reads, and the result reaches no listener (`changed(vibb = false)` reaches no one).
- Fix:
  - Use the callback's arguments, and read a foreign session's art only in `onMetadataChanged`.
  - Compute `usableApps` only at `start()`, on a change to the sessions list and on `packagesChanged`, not on every
    playback update.
  - Register callbacks on foreign controllers only while an `everyApp` listener exists.

## Low

**L1. The check at the tap covers suspension only** (`MusicSessions.kt:236-255`). `playPause`, `next` and `previous`
re-check `usable()` (suspended or not installed), not `lockUsable` (hidden, allowlist, our own package).
- Today this is not exploitable: a de-allowlisted app is suspended and hidden by `AppEnforcer`, hiding kills its
  process, and its session dies.
- Still, the brief asks for the check at the tap, and the full check is cheap at a tap. Call `lockUsable` for a
  non-Vibb `picked` before play or skip; a pause always goes.

**L2. `foreignText` strips ISO control characters only** (`music/LockSessionPick.kt:69-75`). These pass through:
- bidi overrides and isolates (U+202A-202E, U+2066-2069);
- U+2028/U+2029;
- zero-width and other `Cf` characters;
- runs of combining marks.

Scenario: a title of `"‮..."` renders reversed. 119 combining marks on one letter draw over the line above and
below inside the tile. The impact is cosmetic only (the tile isn't clickable and has `maxLines=1`). Fix: map
`Character.FORMAT`, `LINE_SEPARATOR` and `PARAGRAPH_SEPARATOR` to spaces (or `BidiFormatter.unicodeWrap`), and cap
combining marks per base character.

**L3. Foreign times are not fully bounded** (`LockSessionPick.kt:58-83`, `music/MusicMediaPlan.kt:59-67`).
`foreignSession` clamps `positionMs` and `durationMs` but not `positionAtElapsedMs`. `positionNow` carries a playing
session forward at up to 4x speed, with no upper bound when `durationMs == 0`. Scenario: a "live" session with
`updateTime = 1` and speed 4, after 5 days' uptime, shows "480:00:00". `fixWidth` keeps it from clipping, but the doc
claim ("can't widen the tile's times") is false. Fix: treat a stamp outside `(0, now]` as unknown in
`foreignSession`, or clamp the carried position for a foreign session.

**L4. The foreign app sets its own window** (`LockSessionPick.kt:36-40`). The window rests on the app's own
`lastPositionUpdateTime`. An app that re-stamps a paused state, or is stuck in BUFFERING (which counts as PLAYING),
keeps its tile up indefinitely. This is inherent (any app can claim PLAYING) and only its own transport controls are
offered. A note only.

**L5. Decision note (the lock's finder/thief job, S-M4).** Any allowed app's live session now shows its title on the
lock and can be played or skipped without the code. Scenario: a messenger's voice note ("Voice message · <contact>")
or a video title. A fresh headset opens the window for every live session whatever its pause age, so a finder with
earbuds can read and play it. The request covers this; it's worth one line to the user.

**L6. Nit:** without device owner, `isApplicationHidden` throws, so no foreign app ever shows
(`MusicSessions.kt:223`). This fails closed, but the "null allowlist = unmanaged phone" branch is unreachable in
practice.

## Checked, nothing found

- **Open paths.** `MusicLockPanel` calls only `playPause`, `next` and `previous` on `picked?.controller`. It uses no
  `sessionActivity`, custom action, media button or PendingIntent, and the tile has no click listener.
  `LockMediaGuardTest` guards this. A transport control is not a PendingIntent and gives the app no background-start
  privilege, so it adds no way to launch over the lock beyond what the app already has.
- **Fake Vibb music.** `vibb` is decided by `MusicTrust` (name and signer). A squatter takes the foreign path (cut
  text, allowlist needed). The tile shows no app label, so label spoofing is moot.
- **Who may show.** Our own package, hidden, not-allowlisted and relock-blocked apps are excluded. The allowlist is
  null only on a never-managed phone: `choosePolicy`'s fallback is fail-closed.
- **Suspension.** `packagesChanged` re-checks a suspended foreign app with a live session at once.
- **Budget.** The budget's exemption is untouched (Vibb music's alone). A budget-suspended foreign app is unusable,
  so its tile hides.
- **Home.** Home is unchanged: the first Vibb music session by name and signer, re-rendered only on Vibb music's
  updates and on a change to the sessions list, as before.
- **Races and leaks.**
  - Late callbacks are guarded (`watched.none { it === w }`).
  - `picked` and `session` come from the same `shown()` read.
  - Callbacks are unregistered on removal, on `onSessionDestroyed` and on unwatch.
- **Big bitmaps.** Art is scaled to 512 px (but see M1 for the per-update unparcel).
- **`pauseBeforeSuspend`.** The branch doesn't make it worse. A suspended foreign app's tile hides, so the lock offers
  no pause for it, the same as before the branch, when it had no tile at all.
- **Tests.** Lean and meaningful: the pick, the usable rule and the text cut are tested as pure functions, and the
  source guards are updated.
