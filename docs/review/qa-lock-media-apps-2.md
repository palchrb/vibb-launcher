# Light code QA: lock-media-apps-2 (71c75448)

Read only; no Gradle or cargo run. Read the diff in full plus `MusicLockPanel`, `lockUsableApps`' callers, `AppEnforcer.apply`,
`EnforcementPlan` (hide is a subset of suspend), `minSdk` (34).

Result: no High. Two Medium, three Low.

## Medium

- M1 `LockSessionPick.kt:70` / `MusicSessions.kt:296` - `ApplicationInfo.category` is the developer's own word, so the rule is
  fail-closed but can also hide real players. (a) Fake/stray audio or video category: an app can declare `appCategory=audio`
  and pass. Realistic for a messenger? Unlikely (WhatsApp, Telegram, Signal are not known to; unverified, not checked on a
  device), and the app must also be on the parent's allowlist. (b) The reverse is more likely in practice: Spotify, YouTube,
  NRK, Storytel etc. may declare no category (undefined) and then never show on the lock. The platform may fill in a fallback
  category for some Play apps (unverified). Fix/verify: log or `adb shell dumpsys package <pkg>` the family's real player
  apps' category on the device before tagging; if some are undefined, a small server/parent override (package list) is the
  cure, not a looser rule.
- M2 `MusicSessions.kt:314-318` - the browser test is "resolves VIEW https://example.invalid/ with BROWSABLE". That is the
  platform's own idea of a browser (a filter for http(s) with no host), so a messenger does not pass it by accident
  (their filters carry hosts: t.me, wa.me, signal.me). It is easy to fake by an app author (one host-less https filter, or
  host `*`), and some non-browser link handlers (a Google-search style app, a link/RSS reader, a mail client) may pass. Not an
  attack the setup has (the kid installs nothing, the allowlist is the parent's), so Medium only because the user said
  "Absolutt ikke" to a messenger. A cheap tightening with no privilege: intersect the https probe with an `http://` probe
  (browsers declare both; a faker declaring only https drops out), or also accept only apps whose main activity has
  `CATEGORY_APP_BROWSER` (stricter, but not every browser declares it, so as an addition it would drop real browsers).
  I would add the http intersection; it is two lines.

## Low

- L1 `MusicLockPanel.kt:129-130` - `usablePick` calls `lockUsableApps` on the main thread at every play tap, which re-runs
  the `queryIntentActivities` (MATCH_ALL) for a non-Vibb app. One binder call, a few ms, lazy (only for a non-Vibb session
  that passed `usable`), never at a tick or update. Fine; a cache would need an invalidation on package changes, not worth it.
- L2 `AppEnforcer.kt:474` - `if (shouldBeSuspended && !currentlySuspended)` repeats `sessionsToPause`' own condition. It is
  there so the lazy read is skipped on unsuspend passes (correct: the read happens only when something is about to be
  suspended, once per pass). The source-string guard test (`MusicMediaPlanTest.kt:255-265`) pins the text, which is brittle
  but the file's existing pattern.
- L3 `MusicSessions.kt:395` - the pause is a oneway-style request followed at once by `setPackagesSuspended`; the app may
  see the suspension first (as before for Vibb music, which also pauses itself on `MY_PACKAGE_SUSPENDED`). Another app has no
  such second chance, so a rare player keeps going silently-muted; the pause is best effort. Not worse than before.

## Checked and fine

1. Tile reach: messenger, social, game, undefined, productivity, news, maps, image, accessibility never reach it
   (`lockMediaApp` is the only way for a non-Vibb app, `lockAppUsable` requires it, `mediaApp && pkg != own && !hidden &&
   allowlist`). Vibb music is unchanged (suspension only). A failure of `getApplicationInfo` (hidden/removed app), of the
   browser query or of the allowlist read gives "no" (`catch -> false`, `browserPackages` -> empty set); the lazy
   `browsers` is inside the `try`.
2. Browser query: runs only in `lockUsableApps`, itself only at lock start, at a `lockGeneration` change (session list or
   suspension change) and at a play tap - not per playback update or tick. Lazy: not run when no live non-Vibb session
   survives `usable`. `QUERY_ALL_PACKAGES` is held (manifest line 11) and `ResolveInfoFlags` needs API 33 (minSdk 34). No
   cache between calls; acceptable.
3. Pause: only `shouldBeSuspended && !currentlySuspended` packages get their sessions (`sessionsToPause`), so the budget's
   Vibb music (kept unsuspended) and an already suspended app are never paused; hide is a subset of suspend
   (`EnforcementPlan.kt:203`), so a newly hidden app is paused too. Without listener access `getActiveSessions` throws
   `SecurityException` -> empty map, cached by the lazy for the pass, nothing paused. Exceptions: the read is contained
   (both catches), each `pause()` is in its own try, and the whole block sits in `apply`'s per-package `try`. Thread:
   `apply` is `@Synchronized` and documented never-on-main; the lazy is local to the pass (default synchronized lazy, one
   thread); `getActiveSessions` is one binder call, no waiting on an app; `pause()` is a request to the system server.
   The old signer check (`MusicTrust`) before pausing Vibb music is gone, harmless: pause only, by session package.
4. Tests: `lockMediaApp` rows cover audio, video, browser and 8 other categories; `lockAppUsable` has the `mediaApp = false`
   row for a non-Vibb app and Vibb with `mediaApp = false` still true; `sessionsToPause` covers playing, not playing, already
   suspended, unsuspend and the budget's Vibb music. Not covered (needs a device): `browserPackages` itself and the real
   category of installed apps (M1/M2). Meaningful for the pure parts.
