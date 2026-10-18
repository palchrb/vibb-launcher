# Code QA: fix round 1, track B (apps) - `fix-r1-apps`, `9e085254..e7c08ba1`

Reviewer: code QA, 2026-10-18. Five commits: the self-update "already running" step (7), Safety Center's nags (8),
the music app's sync after its update (6), Home's swipes from their own DOWN (9), and Spotify track pictures ahead of
play (10).

**Verdict: merge after the gesture point (M1) is put to the user, plus three small test additions.** All five fixes do
what the report says. The gesture rewrite is sound. The detector is fed once, the old `onTouchEvent` feed and both
view listeners are gone, so nothing is fed twice. The synthetic CANCEL reaches the original touch target, and
`PressFeedback` handles it. Long-press, taps, both scrolls and the cards behave as before. One gesture changed that
the report and `launcher/CLAUDE.md` don't mention: a swipe **up** that starts on a contact now opens the drawer (M1).

Counts: 0 High, 1 Medium, 4 Low, 4 Nits.

## Full runs (under `~/build.lock`)

| Suite | Result |
|---|---|
| launcher `testDebugUnitTest assembleDebug -PwarningsAsErrors=true` | BUILD SUCCESSFUL, 135 classes, 1293 tests, 0 failed |
| music `testDebugUnitTest assembleDebug -PwarningsAsErrors=true` | BUILD SUCCESSFUL, 59 classes, 891 tests, 0 failed |
| server `cargo test` | 965 passed, 0 failed, 0 ignored |
| server `cargo fmt --check` | clean |
| server `cargo clippy --all-targets --locked` | the 13 known warnings, none new (none in `music_sweep.rs`) |

## Medium

### M1. A swipe up that starts on a contact now opens the drawer (a gesture change the report doesn't list)

Where: `launcher/.../ui/home/HomeSwipe.kt:56` (the up rule ignores `start`). `HomeSwipeTest.kt:41` pins the new
behaviour. Not mentioned in `launcher/CLAUDE.md:111` or in the implementer's report.

Before this change, a contact (a clickable item) took the DOWN. `HorizontalScrollView` steals only horizontal drags,
so a vertical drag stayed with the contact. None of the three detector feeds saw any of it, so no drawer opened. A
swipe up from the row's empty space did open the drawer (the HSV took that DOWN, and its listener fed the detector).
Now every swipe up from the contacts row opens the drawer once the grid can't scroll. That follows from "decide every
swipe from its real DOWN" (approved 2026-10-14, PLAN.md). Still, the brief allowed only two additions: the left swipe
on a contact in a row that fits, and no tap after a swipe. The user's rule is that no gesture changes without asking.

Fix: put it to the user in one line. If they keep it, add one clause to `launcher/CLAUDE.md:111` ("a swipe up from a
contact opens the drawer too"). If they don't, return `NONE` for `start == CONTACTS_ROW` in the up branch only when
the DOWN was on an item, which is more code (a fifth `SwipeStart`). I recommend keeping it: it is consistent, and the
row's empty space already worked this way.

## Low

### L1. The start and the "can scroll" inputs are read at UP, not at DOWN (older than this change)

Where: `HomeActivity.kt:325-331` (`swipeStart(e1)`, `canScrollVertically(1)`, `canScrollHorizontally(1)` all run
inside `onFling`). A left swipe that scrolls the row to its end during the swipe sees `rowCanScrollRight = false` at
UP, so it opens kid Settings on top of the scroll. The grid's swipe up works the same way. The old code did this too,
so it isn't a regression. But the fix's own claim is "decided from its own DOWN", and only the DOWN's coordinates are.
The doc comment on `swipeStart` ("Where a swipe's DOWN landed") is also read at UP, against the layout at UP. That is
harmless, because none of the classified views move.

Fix (optional, for the emulator pass): in `dispatchTouchEvent`, on `ACTION_DOWN`, record `swipeStart(ev)` and both
`canScroll*` values, and have `onFling` use the recorded ones. That is three fields, and the pure function doesn't
change.

### L2. The CANCEL and the single feed are untested (m3 survived)

Where: `HomeActivity.kt:353-363`. Mutation m3 (always pass the UP through) survived every launcher test.
`HomeGateTest` now looks only at `onFling`'s `contentShown = contentShown`. Nothing stops a later change from adding a
second feed back (a view listener, or `onTouchEvent`), which would bring the stale-DOWN bug back. Nothing pins the
cancel either.

Fix: add a source check next to `HomeGateTest`'s. `gestureDetector.onTouchEvent(` appears exactly once in
`HomeActivity.kt`, inside `override fun dispatchTouchEvent`. That body contains `ACTION_CANCEL` and `recycle()`. There
is no `override fun onTouchEvent`, no `addOnItemTouchListener` and no `homeContactsScroll.setOnTouchListener`.

### L3. Server: the wiring of the per-link budget is untested (m14 survived), and the day budget is now shared faster

Where: `server/src/music_sweep.rs:219-224` (`art_per_check(&self, link)`). `a_spotify_check_fetches_more_pictures`
tests only the pure `art_per_check_for`. Mutating the sentinel arm to `ART_PER_CHECK` passed every test. Separately,
one Spotify check can now spend 100 of the 400 requests a day. A family that adds four large playlists in one day uses
the day's budget on them, so NRK/RSS pictures wait until rows age out (up to a day). The cap holds, and steady state
is unaffected because art is fetched once per item. This is acceptable, but `server/CLAUDE.md` could say so.

Fix: a unit test on `Sweep::default().art_per_check(&spotify) == 100` and `(&rss) == 20`, plus `set_art_per_check(7)`
giving 7 for both (the knob overrides both). Optionally, one sentence in `server/CLAUDE.md`.

### L4. Spotify's `ahead` ignores the kid's shuffle

Where: `music/.../sync/ArtPlan.kt:43` (`shuffle = false`). With the entry in `snap.shuffle` and resume off, a cover
tap plays a fresh permutation (`PlaybackService.kt:693-694`). So for a playlist over 50 tracks, the 50 pictures
fetched are the first 50 in list order, not the ones that will play. The cost is at most about 2.5 MB under `later`'s
gate, and nothing breaks. Fix: a sentence in the `artWant` KDoc and in `SPOTIFY_ART_AHEAD`'s, saying that a shuffled
entry gets its first 50 in list order. A random pick can't be predicted, so no code change.

## Nits

- **N1** `HomeSwipeTest.kt:56-62` ("the previous touch doesn't matter") tests a stateless function, so it can't fail.
  The real risk lives in the feed (L2). Drop it, or replace it with L2's source check.
- **N2** `EpisodeArtTest` "a context entry ... want nothing": the fixture's context entry has no visible item in
  `Snapshot.order`, so the assertion is vacuous (m11 survived). In production a context entry's one item has no own
  art either, so the `isSpotifyContext` skip is belt and braces. Give the fixture a context entry with a listed item,
  or drop the branch and the claim.
- **N3** `music/app/src/main/AndroidManifest.xml:93`: nothing pins `exported="false"` on `PackageReplacedReceiver`
  (m10 survived). It is harmless: `MY_PACKAGE_REPLACED` is a protected broadcast, and a missing attribute fails the
  manifest merge. One line in `SourceScanTest` would pin it, like the DebugStartup check at `:619-622`.
- **N4** `wantedArt`: the `.distinct()` on `ordered` is redundant, because `nowAll` is a `Set`, so `nowAll + list` is
  already a set. My first dedupe mutation was equivalent for that reason. It is harmless and reads as intent, so keep
  it.

## Confirmed (no finding)

- **Item 7.** `launcherTagVersionCode`: `launcher-v0.26.0@641676265` gives 26000. `-rc.2@...` gives 25999. `v0.26.0`,
  free-text upload labels, `x@launcher-v...`, `-beta`, and `v0.0.0-rc.1` (code 0) all give null, so the download goes
  ahead as before. A wrong "installed" needs a tag that parses to at most the running code, which a newer release
  never does. The rc arithmetic matches `.github/workflows/launcher.yml:176-196`. The order is right:
  installed/withdrawn/refused come first (a pending APK is dropped), then ALREADY_RUNNING, then in-flight and backoff.
  `SelfUpdate.dropPending` deletes the file and clears the state. `recordInstalled(key, update.releaseTag)` writes the
  advertised tag. A rollback (the server lists an older tag) is recorded once. The next sync hits
  `advertisedTag == lastInstalledTag`, so it returns NOTHING and there is no loop. rc.N to rc.N+1 (only with
  `include_prereleases` on): same versionCode, now ALREADY_RUNNING. The old path also ended as "installed" at the
  commit precheck (equal = installed), after a wasted download, so this is not a regression. A debug build (code 116)
  is unaffected.
- **Item 8.** The match is exact (`in` a set). `safety_center_critical_warning`, upper case, `USB` and `null` stay
  essential (tested). The ongoing, non-clearable, FSI, insistent and category guards come first. Only `android` is
  affected: the same channels on SystemUI stay. `groupSummaryCancellable` and the repost budget go through
  `nagVerdict` unchanged. The investigation holds. The public `DevicePolicyManager` API grants runtime permissions
  only (`setPermissionGrantState`), and has no app-op setter, so `GET_USAGE_STATS` can't be granted to ourselves.
  `UsageStatsManager` would also mean polling. The observer APIs (`registerAppUsageObserver`, the task-stack listener)
  are system or hidden. `getRunningTasks`/`getAppTasks` show our own tasks only.
- **Item 6.** For a replacing `PACKAGE_ADDED`, `PackageMonitor` calls `onPackageUpdateFinished` and then
  `onPackageModified`, which LauncherApps delivers as `onPackageChanged`. That comes after the new APK is in place, the
  same signal Launcher3 relies on. `musicNudgeOnChange` is exact against `MUSIC_PACKAGES` (tested, m9).
  `MusicSync.nudge` still adds `FLAG_INCLUDE_STOPPED_PACKAGES` and shares its identity. The music app doesn't toggle
  its own components, so no PACKAGE_CHANGED loop is possible. A component change elsewhere costs one extra deduped
  sync. The receiver is `exported="false"`, and `Sync.nudge` runs on its executor, not the main thread. Two nudges
  (the receiver and the launcher) collapse under the `KEEP` policy.
- **Item 10.** `ahead` comes last in `later`, is deduplicated against `now`/`kept`/`news`, and is capped by
  `WANT_ART_MAX` (tested, m12/m13). A bookmark past the end or missing gives `rotateToBookmark` the unchanged order,
  so it starts from the first track (tested for unstarted and resume off). Storytel and NRK/RSS paths are unchanged.
  The `later` gate is unchanged, because `ahead` only feeds `wantedArt.later`. Spotify entries no longer pass through
  `keepSet` (bookmark + next at cache 0). Those two are now the first two of `ahead`.
- **Item 9, geometry.** `ev.x/y` in `Activity.dispatchTouchEvent` are window coordinates, and `getGlobalVisibleRect`
  is relative to the decor view, so the two are consistent. The old code compared `rawX` with the global rect, which
  breaks with a window offset. Before layout, or when GONE, `isShown`/`getGlobalVisibleRect` give false, so the start
  is BLANK. The fading-edge `RecyclerView` is classified by its own bounds. `clipChildren=false` affects drawing only,
  and a touch outside the HSV never reaches its items. On the night ground the binding views are detached (BLANK) and
  `homeSwipe` returns NONE (tested, m4). `gestureDetector` exists only after `bindContent`, which the
  `isInitialized` guard covers.

## Gesture audit

| Gesture | Before | After | Unchanged? |
|---|---|---|---|
| Tap a tile | launches (press feedback) | same | yes |
| Long-press a tile | app menu (popup window) | same (the detector's own long-press only suppresses a fling, as before via the grid feed) | yes |
| Tap / long-press a contact | contact sheet | same | yes |
| Contacts row horizontal scroll + fling | scrolls | same (no CANCEL unless a swipe fired) | yes |
| Left swipe in a row that can still scroll | scroll; Settings sometimes opened too (stale DOWN, M1 of qa-34-s4) | scroll only | fixed bug |
| Left swipe on a contact, row fits | erratic (stale DOWN) | opens kid Settings | documented addition (design 08 intent) |
| Left swipe on empty row space / grid / blank | kid Settings | same | yes |
| **Swipe up starting on a contact** | **nothing** | **drawer (grid can't scroll)** | **no - M1** |
| Swipe up from grid / empty row space / blank, grid at end | drawer | same | yes |
| Swipe up while the grid can scroll | grid scrolls | same | yes |
| Short left swipe ending inside the tile/contact it started on | Settings **and** the tap | Settings only (CANCEL) | documented addition |
| Call card tap / swipe on it | opens the call screen / no swipe (the card took the touch) | same (`CARD` gives NONE) | yes |
| Music card body and play button | open / play-pause / no swipe | same | yes |
| "Lock again" | ends the window / no swipe | same | yes |
| Night ground (locked Home) | no swipe | no swipe | yes |

## Mutations (each reverted; targeted `--tests` runs, server `cargo test music_spotify`)

| # | Mutation | Result |
|---|---|---|
| m1 | `homeSwipe` ignores the row's scrollability | caught (HomeSwipeTest) |
| m2 | a CARD start no longer blocks | caught |
| m3 | drop the synthetic CANCEL | **survived** (L2) |
| m4 | `contentShown = true` in `onFling` | caught (HomeGateTest) |
| m5 | rc −1 flipped to +1 | caught (SelfUpdatePlanTest) |
| m6 | `tagCode <= running` becomes `<` | caught |
| m7 | parse accepts a tag without `launcher-` | caught |
| m8 | channel match by `safety_center` prefix | caught (NotificationRuleTest) |
| m9 | `musicNudgeOnChange` nudges any package | caught (MusicPlanTest) |
| m10 | receiver `exported="true"` | **survived** (N3, harmless) |
| m11 | `ahead` includes context-mode entries | **survived** (N2, vacuous fixture) |
| m12 | drop the dedupe (`nowAll.toList() + ... + ahead`) | caught (EpisodeArtTest; the first variant was equivalent, N4) |
| m13 | `ahead` not capped at 50 | caught |
| m14 | server per-check ignores the link | **survived** (L3) |
