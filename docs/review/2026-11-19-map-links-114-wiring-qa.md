# Code QA: design 114 x 110, the map steps as setup steps (6037176b0)

Branch `map-links`, commit 6037176b0 ("Design 114 x 110: the map steps are setup steps") against its parent c71b59361
(the plain merge of main). Read only: I read `git show 6037176b0` in full, and the code around it: `MapLinkStep`,
`MapLinksRuntime`, `SetupFlowActivity`, `SetupPages`, `SetupReadings`, `AppEnforcer`'s suspend loop, the
`map_links` integration (`mod.rs`, `page.rs`, `tests.rs`), `setup_status.rs` (`readings_of`, `inputs_of`,
`warnings`, `Choices`), and every `setup_status` caller. Design 114 §2.2, §2.3, §2.6 and §5.4, and design 110 §6.2,
§7.5 and finding 1. No `cargo test`, Gradle or emulator (as briefed).

**Counts: 0 H, 2 M, 6 L.**

## What holds

- **Every server caller gets the part.** `state_of`, `inputs_of`, `readings_of`, `page_warnings`,
  `page_warnings_app_push`, `Choices::of` and `PageInput` all take a `&SetupPart` now, so the compiler forces it. I
  checked every caller: `attention::facts` (the Devices list), `devices::render_device` (the badge and the summary's
  `setup_value`, from one `setup_part` it builds), `setup_page::view`, `view_about`, the Home, Lock, Advanced, Music,
  Apps, Calls, Mobilnett, Locate and Sign-in pages. Each reads `state.integrations.setup(state, id)`. None passes
  `SetupPart::default()`; that appears only in the trait's default and in `Integrations::setup`. A featureless build
  adds no input, and its policy carries no `map_links`, so the phone's `mapLinksOn` is false too.
- **The need rule** is `map_links_on && map_app_installed` on both sides. The server's `map_links_on` is the
  integration's `is_on`, the same row that the policy's `map_links.on` comes from. The three package lists match:
  `MAP_APP_PACKAGES`, `MapLinks.TARGETS` and the server's `MAP_APPS`. One exception on the phone is M2.
- **The readings agree, by the formula**, for one and the same report: `play_services_links` negated (`null` stays
  unreadable, and "null never counts" on both sides), and `declared > 0 && selected >= selectable`. The phone's
  `vibbHosts` returns `(0, 0, 0)` when unreadable, and so does the server's `unwrap_or(0)`. The timing does not match
  (M1).
- **Contracts.** The commit changes no shared file. `setup_steps.json` and `map_links/contract.json` are
  byte-identical in `server/testdata/` and `launcher/app/src/test/resources/` (`cmp`). The `server.json` rows changed
  are the server's alone.
- **No dangling words.** No code or template uses the removed strings and keys any more: `map_links_step_holder*`,
  `map_links_step_vibb*`, `map_links_step_done`, `map_links.line.step*`, `MapLinkRow(s)` and the two preference
  keys. `map_links.line.off_play_fix` still points to Settings › Map links. That is right, because §6.3's way back
  stays there.
- **begin/end on the ordinary paths.**
  - Back from the page: `pageReturned` calls `end` before the sync.
  - No page: `launchPages` clears `openedStep`, and `end` runs.
  - Recreation while the page is open: `openedStep` is restored, so `pageReturned` still ends it.
  - Home in the hold: the flow's task comes back with the page still on top, and the hold stays. That is right.
  - Home in a plain pause, the hold ending, or the process dying with the page open: `MapLinkStep.held()` goes
    empty, either when the window closes or after `MAX_MS` (or `holding` is lost with the process). The enforcer's
    pass then releases the apps (`suspendTarget` over every installed package). A later `end()` on a stale
    `holding` subtracts `lastPlanSuspend` and `CameraLock.held`, so it never undoes a rule's suspension.
  - I found no path that leaves the holders suspended beyond the window or 15 minutes. The paths that release them
    early or late are L1 to L3.
- **Android 14-17.** `setupHowRes` gives the 14 words on 34 and 35 and the 16 words on 36 and 37. The test pins
  that. Nothing new needs an API above 34. `BAKLAVA` in the Settings fragment was already there.
- **Settings' "Kartlenker"** shows only §6.3's row. It shows while the switch is off and Play services' links read
  off, and it hides when the switch is on (`mapLinkBackShown`, tested).

## M

### M1. The server's row 16 can't turn done during the pause the step is done in: the phone and the PWA disagree on N

`server/src/integrations/map_links/mod.rs:279` (`store_report`), read by `setup_part` (`mod.rs:166`). Pinned by
`tests.rs:619`.

- `store_report` keeps the last `vibb_hosts` from before the pause, as well as `reach`, whenever a report comes in
  during a pause. The setup hold counts as a pause here: `restrictionsPaused = RestrictionsPause.isActive()`, and
  the hold is a `RestrictionsPause`.
- Step 2 can only be done in a pause or the hold. So the report that follows `pageReturned`'s sync, which carries
  `selected == selectable`, is stored with the old `vibb_hosts`.
- On a fresh phone in the hold, the first report's `vibb_hosts` (`selected: 0`) is frozen for the whole hold, since
  each paused report keeps the one before it.

**Scenario.** The parent finishes step 2 in the setup flow.
- The phone's live reading says done, and its flow moves on to "Klar".
- Device › Oppsett, the `#map-links` warning line "Vibb One doesn't open every map link", the Attention count and the
  summary all still count `map_vibb_links`. That lasts until the first report after the pause or hold ends, which can
  be up to 2 h in a plain pause.
- The parent watching the PWA sees the one step that never ticks off, and may redo it.
- Row 15 (`play_services_links`) is not kept, so it does tick off. That makes row 16 look broken.

Design 110 §7.5 says only that the PWA ignores `reach` from a pause-time report. `vibb_hosts` does not depend on what
is unhidden: `selectable` counts only the hosts held by unsuspendable apps, and `selected` is Vibb One's own state.

**Fix.** Keep only `reach` from before a pause (`for key in ["reach"]`). Change `a_pause_report_keeps_the_last_reach`
to check that `vibb_hosts` is the paused report's. Add a row: a paused report with all hosts selected makes
`setup_part`'s `map_vibb_links` `Some(true)`.

### M2. Phone and server disagree on N while Organic Maps is installed but hidden

`launcher/app/src/main/java/com/kidslauncher/mdm/setup/SetupReadings.kt:153-158` (`installed`), used at `:149`.

- `installed()` calls `getPackageInfo(pkg, 0)`. That throws `NameNotFoundException` for a package hidden with
  `setApplicationHidden`, which is the normal state of an app the parent hasn't allowed, outside a pause.
- The report's `installed_apps`, which the server reads for the same input (`setup_status::inputs_of`), is built
  with `MATCH_UNINSTALLED_PACKAGES` (`MdmSyncWorker.collectInstalledApps`), so it lists hidden apps.
- Design 114 §2.3 says the phone uses "the same package list it reports as `installed_apps`, so both see the same
  set".
- This code is 114's, but it did nothing until this commit, because `mapLinksOn` was hard `false` before.

**Scenario.** The parent turns map links on but hasn't allowed Organic Maps (the group shows "Organic Maps isn't
allowed").
- Outside a pause, the server counts 2 map steps: Oppsett, Attention, the summary and the `#map-links` lines.
- The phone counts 0: its Settings "Oppsett" row, its setup notice and the flow's End screen.
- In a pause the app is unhidden and the phone counts 2 again, so N changes with the pause.

**Fix.** Read as the report does: `getPackageInfo(pkg, PackageInfoFlags.of(MATCH_UNINSTALLED_PACKAGES))`, and require
`applicationInfo.flags and FLAG_INSTALLED != 0`, so that a package uninstalled with kept data doesn't count.
Better still, use the reporter's own `controllablePackages` check, so the two cannot drift apart.

## L

### L1. A double tap on "Åpne" opens two pages, and the first return gives the apps back while the second is open

`SetupFlowActivity.kt:396-400`, `:456-464`.

- `begin` runs on IO first: `getInstalledPackages` plus a domain-state read per package, a noticeable delay. The
  button stays enabled meanwhile.
- A second tap starts a second coroutine, and both reach `launchPages`, so the "Open by default" page is stacked
  twice.
- Back from the top page runs `pageReturned`, then `end()`, which empties `holding`. The page under it is then open
  with the holders back, so "Add link" greys 16-17 of the 20 hosts again. That is finding 1's symptom.

**Fix.** Ignore "Åpne" while a page is opening or open: for example, return early when `openedStep != null` or an
`opening` flag is set, or disable the button until `refresh()`.

### L2. A recreation or finish during `begin` leaves the holders suspended with no page

`SetupFlowActivity.kt:396-400`.

- If the activity is destroyed while `begin` runs on IO (a configuration change, or `finish()` from the hold
  ending), the `lifecycleScope` coroutine is cancelled when `withContext` returns.
- Then neither `launchPages` nor the `openedStep == null` `end` runs.
- The apps stay suspended until `MAX_MS` (15 min) or the pause's end. Meanwhile Google Maps and the Google app are
  suspended in a pause, and nothing on screen says why.

**Fix.** Wrap it in `try { ... } finally { if (openedStep == null) withContext(NonCancellable + Dispatchers.IO) { MapLinkStep.end(app) } }`.

### L3. `launchPages`'s generic catch leaves `openedStep` set, so `end` is skipped

`SetupFlowActivity.kt:424-427` (the code is older; this commit's check at `:399` relies on it).

- On a non-`ActivityNotFoundException` (for example a `SecurityException` from the explicit Settings component),
  `launchPages` returns with `openedStep = step` and no page.
- `if (openedStep == null)` is then false, so the holders stay until `MAX_MS` or the pause's end, and the stale
  `openedStep` is carried along.

**Fix.** Set `openedStep = null` in that catch, as `launch()` does.

### L4. The PWA's ⓘ for rows 15-16 still names Android 14's switch

`server/src/i18n/en.json:4587-4588`, `nb.json:4587-4588` (`setup.how.map_play_links` / `map_vibb_links`).

- The phone's screen now says Android 16's choices ("In your browser" / "In the app"). Device › Oppsett's ⓘ still
  says "turn off / turn on Open supported links", a switch that isn't on the Jelly Star's (Android 16) page.
- The server doesn't know the phone's Android version.

**Fix.** Use words that hold on both versions, for example:
- "Åpne som standard: «I nettleseren» (eller slå av Åpne støttede linker)."
- "Åpne som standard: «I appen» og alle linkene (eller slå på Åpne støttede linker og kryss av alle)."

Or a shorter form that the user approves. Copy is the user's call, under "Words in the UI".

### L5. With Play services' links unreadable and step 2 done, the group says "Ready." though short links don't reach Vibb One

`server/src/integrations/map_links/page.rs:80-88`.

- The old `step_play` line fired on `!reach.short && play_services_links != false`, which included `null`.
- Now a `null` reading never counts (114's rule), so `steps_left` is false and `line_code` falls through. With
  `reach.google` true it says "Ready.", while a short link goes to Play services or a browser.
- This needs `getDomainVerificationUserState(gms)` to fail, so it is rare.

**Fix.** Before "held"/"ready", return no line (or `waiting`) when `!reach("short")` and `play_services_links` is
not `false`. Add a `server.json` row for it.

### L6. No shared vector pins that the phone and the server read rows 15-16 alike

`launcher/.../map/MapLinkRows.kt` (`playLinksDone`, `vibbLinksDone`) against `server/.../map_links/mod.rs:166`
(`setup_part`).

- The two formulas are the same today, but each side tests only its own: `MapLinksTest` and the server's `pwa_lines`
  test.
- This is the kind of rule the repo usually pins in a shared file.

**Fix.** Add a `setup_readings` list to `map_links/contract.json` (`{report, map_play_links, map_vibb_links}`: the
`null` holder, `declared: 0`, `selected < selectable`, all selected), run by `MapLinksTest` and `map_links::tests`
on both sides, which already compare the copies.

## Not findings

- The server's `the_pwa_lines_follow_the_rows` derives `steps_left` from the readings alone. It ignores
  `map_app_installed` and the warnings path. That is fine for those rows, all of which have an allowed or suspended
  target. The end-to-end test `the_policy_and_the_group_follow_the_switch` covers the real path, through
  `installed_apps`.
- With the target hidden and steps left, the group shows both 114's lines and its own orange "isn't allowed" line.
  Both are true, and they say different things.
