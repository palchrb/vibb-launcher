# Light code QA: `lock-media-switch` (628a7864 on 2e73a78e)

Scope: the per-app "Show as a media player on the lock screen" switch (migration 0127 `device_lock_media_apps`, route
`POST /devices/{id}/apps/lock-media`, policy `lock_media_apps`, the launcher's `lockMediaApp(.., chosen)`), the word
joiners in `server_url_refused`, and the lock tile's hidden previous/next (`lockSkipShown`). Read only; nothing built or
run (implementer: server 1708/0, launcher 2075/0).

Counts: High 0, Medium 0, Low 4, Info 6.

Verdict: **ship.** No correctness or security defect found. The four Lows are polish or things to confirm on the
Jelly Star; none blocks a merge.

## Low

**L1. A switched-on messenger puts the sender's name and avatar on the lock, and nothing says so**
(`server/src/app_permissions.rs:149` `lock_media_offered`; `server/src/i18n/en.json:1410`, `nb.json:1410`).
The switch is offered for every allowed third-party app, and its help names only podcast apps. Category apps are
music/video/browsers, whose title and cover are the content. A messenger that publishes a media session for voice
messages (Telegram does) shows the sender as title and the contact photo as art on the glance page, which is visible
before the PIN. The launcher side adds nothing beyond what category apps already expose: `foreignSession` cuts the text
and `art()` scales the cover, the buttons only reach that app's own session (`LockMediaGuardTest`), and the allowlist,
suspension and hidden checks still apply (`lockAppUsable` is unchanged, `chosen` only replaces the `mediaApp` input).
It is the parent's explicit choice, so this is exposure by consent, but the consent text is thin.
Scenario: a parent switches Signal on to get a voice message's pause button; the next message's sender name and avatar
show on the kid's lock for 10 minutes after play. Suggest one more clause in the help ("what the app plays - title and
picture - shows on the lock") or keep it as is and say so in `pwa.md`.

**L2. Hiding skips on "not advertised" may hide them on AntennaPod, the app this switch exists for**
(`launcher/.../music/LockSessionPick.kt:136`, `MusicLockPanel.kt:169-171`).
`lockSkipShown` shows next/previous only when the session's `PlaybackState.actions` has `ACTION_SKIP_TO_*`. Players
that map their notification's previous/next to rewind/fast-forward, or that advertise only
`ACTION_REWIND`/`ACTION_FAST_FORWARD`, lose both buttons and keep play/pause alone. The diff's tests cover only
synthetic masks; E9b's check was Fennec. Nothing in the commit says the tile was looked at with AntennaPod on the
emulator. Related: with the buttons hidden, `tileMediaKey`'s "no actions = use the transport control" fallback (its
KDoc, QA L-a) is no longer reachable from the tile. Not wrong, but now dead path and doc text. Check AntennaPod once
(`docs/testing/emulator.md` §6) before the Jelly Star run.

**L3. The route does not enforce what the page offers** (`server/src/handlers/devices.rs:1245` `save_lock_media`,
`perms_target`). The page shows the switch only where `lock_media_offered` (not preinstalled, allowed, third-party), but
the POST accepts any package in the grammar except Play core, Vibb and Vibb music: a preinstalled or never-installed
package gets a row. Only a signed-in parent with a CSRF token can do that, so it is not an escalation; the effect is
that rows can exist with no UI to clear them (an app uninstalled or set to not allowed later keeps its row, because the
switch only renders on an allowed row) and they count toward the 200 cap. A phone with 200 stale rows then answers
`lock_media_full` with no visible row to turn off. Unrealistic for one family, hence Low. Same shape as the camera
rows (design 68), which is probably acceptable; a note in `pwa.md` that stale rows are inert would do.

**L4. A cosmetic list can 500 the whole policy** (`server/src/handlers/device_api.rs:354`
`lock_media_apps(...).await?`). The camera list fails closed because losing it hands the camera back
(design 68). Losing `lock_media_apps` only hides a tile, yet its read error now 500s the phone's entire policy (time
rules, allowlist, lock) and `db_error_is_500_not_default` pins that. In practice a DB failing on this table fails
elsewhere too (and a missing table means the migration did not run), so the harm is small; but "fail closed" is the
wrong reason for it. Either degrade to `[]` with a log line (the launcher treats missing as none) or keep the 500 and
say in the comment that it is for simplicity. The test comment "the phone keeps its last list rather than an empty
one" is also loose: the phone keeps its last whole policy.

## Info

**I1. Help says the media apps "always show"** (`en.json:1410`, `nb.json:1410`). They show only while playing or within
10 minutes, only when allowed and not suspended. "Vises alltid" / "always show" overclaims slightly; "show on the lock
when they play" is exact.

**I2. nb word order** (`nb.json:1411`): "slå noen av først" reads better as "slå av noen først". The rest of the nb
(`Vis på låseskjermen som mediespiller`, `podkastapper`, `Musikk-, video- og nettleserapper`) is natural bokmål, no
nynorsk. en and nb both have all three keys. The strings never say "Spotify"; the kid-facing tile text comes from the
app's own session.

**I3. The word joiners go to the clipboard too** (`values/strings.xml:257`, `values-nb/strings.xml:205`). U+2060 stops
the break at the dots as intended (aapt2 resolves `⁠`; it is default-ignorable, so no tofu on a font without the
glyph). A parent who selects and copies the example address from the dialog gets invisible characters in the text, which
`server_url_vectors` would then refuse. It is an example, not a value to copy, so only worth knowing.

**I4. A value other than `on` means off** (`devices.rs:1252`): `lock_media=junk` switches the app off, where the
sibling camera form answers 400 for an unknown value. Harmless (the only sender is the checkbox); noted for consistency.

**I5. The 200 cap is a count then an insert** (`app_permissions.rs:112-143`), two statements without a transaction.
Two parallel saves could end at 201. Sibling `set_camera` is the same; irrelevant at this size.

**I6. Staleness on the lock** (`MusicSessions.kt` `lockUsableApps`, `val chosen`). The policy is read at the lock's
start, a sessions-list change, a suspension and at a tap (`cachedPolicy()` is memoised per raw string, so the second
`currentPolicyDecision()` beside `allowlist` costs nothing). A switch turned on while the kid's lock is already up and
the app already playing shows the tile at the next such event, not at the sync. Same as the allowlist today.
`LockSessionPickTest` also checks two source lines by reading `MusicSessions.kt` as text, which breaks on a rename; the
repo does this elsewhere (`LockMediaGuardTest`).

## Checked and fine

- **Auth, CSRF, ownership:** the route is registered next to `/apps/permissions` in `build_admin_router`
  (`main.rs:1368`), so the session layer, `csrf_guard`, `admin_gate` and `settings_log::log_saves` apply identically (a
  generic POST logger, nothing per route to add). `perms_target` checks the device row (404), the package grammar
  (400), Play core, `tracked_apps.is_launcher` and the `me.vibb.launcher` prefix (refused `perms`), and for this switch
  Vibb music (`MUSIC_PACKAGES`). The refactor out of `save_app_permissions` keeps the old order (camera value 400, then
  grammar, then device, then refusal); `from` still goes through `AppsFrom::parse`; package and `from` reach the
  redirect only after validation (`apps_url`).
- **Save returns to its details and never jumps:** form action carries `#perms-<package>`, the redirect is
  `?perms=<package>#perms-<package>` (`PermsTarget::saved`), the checkbox is `data-autosave` (so `form.submit()` through
  scroll-restore, as the camera switch); `the_lock_media_switch_returns_to_its_open_details` asserts the redirect,
  the open `<details>`, the nudge and a landing page, for `from` too. A refusal goes to the row's anchor with
  `?notice=lock_media_full` (`toggle_refusal` has the code) and is not logged as a save (`settings_log::refused`).
- **Input validation:** the grammar is `valid_package_name` (<= 255, dotted segments, ASCII); the 200 cap counts other
  packages so re-saving an existing row at the cap works; `ON CONFLICT` upserts; off deletes. `PermsView::of`
  with `access: None` and a switch gives the details with the switch alone, no row line; the old `None` for no report
  and no switch is unchanged (tested).
- **Policy in both dirs, one commit:** `models.rs` `lock_media_apps: Vec<String>` (sorted, always present),
  `build_policy` sets it, `policy_json_keys_snapshot` lists it twice and asserts `[]`, `PolicyResponse.kt` takes a
  `JsonElement?` (snake_case via `ServerJson`), `PolicyResponseCompatTest` covers a list, junk entries, `{}` and
  `LastEnforcedPlan` (null). No contract JSON file is needed (not a body both sides encode). `lockMediaAppsOf` is
  tolerant: non-array, null, non-string and invalid names give none, never a failed policy. An older launcher ignores
  the key; a newer launcher with an older server reads missing as none. A `Fallback` policy carries none, so after a
  corrupt cache a switched-on app drops off the tile (fails toward showing less).
- **Pause before suspension still covers it:** `AppEnforcer` pauses every app's playing session (`playingSessions`,
  `sessionsToPause`) when it is about to be suspended, independent of the lock's media rule, so a switched-on app is
  paused as any other. `lockAppUsable` still refuses a suspended or hidden app and one off the allowlist.
- **`lockSkipShown` and the layout:** Vibb music always shows (`vibb ||`, and `picked?.vibb ?: true` when nothing is
  picked, so actions 0 never hides Vibb's); the hidden button is `INVISIBLE` in the same row, so play/pause stays centred
  and the tile's height does not change; an invisible button is not clickable and `GlancePanel.hit` uses `isShown`, so a
  swipe from that spot is not swallowed. `actions` survives `foreignSession`'s `copy` and the window pick, and every
  playback-state callback re-renders, so a skip appearing later (Media3 after prepare) shows.
- **Migration:** 0127 is the next free number, `ON DELETE CASCADE`, PK (device_id, package_name); the
  `migrations_from_73_in_order` row `(127, "lock media apps")` and its doc line are added; `server/CLAUDE.md` moves
  "next free" to 0128 and lists the table; `db_error_is_500_not_default` has the table.
- **Notes:** `pwa.md`, `launcher-ui.md`, `music-bridge.md` and `server/CLAUDE.md` are updated and match the code. The
  PLAN entry is in the queue as built, in QA.
