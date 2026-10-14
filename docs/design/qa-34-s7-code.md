# QA 34 step 7 (code) - the time-rule screen as one calm card (P6)

Reviewer: code QA, 2026-10-14. Branch `design-34-s7`, `9200c9a8..758568dd` (one commit, launcher and docs only). I read
it against `docs/design/34-launcher-polish.md` (P6, §5 step 7, §7 L4/L7/M4, "Step 7 implementation status"),
`qa-34-design.md`, mockup `04-time-lock-after`/`-before`, the root `CLAUDE.md` and `launcher/CLAUDE.md`.

In the code I read:
- the whole diff;
- the old `LockActivity` (`9200c9a8`) side by side with the new one, state by state;
- `BudgetUse`/`TimeLock` (`timerules/TimeRules.kt`), `KidAvatars.cachedAppIcon`/`renderAppIcon`, and `GlancePanel`'s
  icon job (the pattern this step copies);
- a grep for every removed name.

Line numbers are `758568dd`'s. No product code was changed. The mutations below were temporary and were reverted, and
`git status` was clean before this file was written. I did not use the emulator.

**Verdict: merge; the two Lows can go into a short fix round.** The screen is behaviourally the old one:
- **Rows.** The same rows appear in every state. The phone book shows iff `callsManaged && lock.callsAllowed != false`
  (a null lock counts as allowed, as before). The apps go through the same filter: a launch intent, not suspended,
  `CameraLock.suspendedForLists` for a held camera, the parent's label, sorted by the lower-cased label.
- **Targets.** Each target is the same: `PhoneBookActivity.intent`, the app's launch intent with `NEW_TASK` (now
  looked up at tap rather than at render, which is equivalent), `EmergencyCall.confirm`, and `showUnlockCodeDialog`.
- **Back and Emergency call.** Back is still a no-op. "Nødsamtale" is still always visible.
- **The sentence.** It is the old branch structure: no rule plus an allowance gives the allowance, otherwise
  "also used up" when exhausted. Only the number changed: the allowance instead of "used of allowance".
- **The rule's end.** It is still shown through `formatUntil` (pinned by `ClockFormatTest`).
- **What a child loses.** Only the used-minutes count. That count is the same as the allowance except in an overrun,
  where it was the confusing "6 av 1". Nothing a child needs is gone.

There are no Medium findings. The two Lows are:
- **L1:** "always shown" for the emergency button is pinned only in the layout, so a runtime hide survives the tests.
- **L2:** the first icon pass rebuilds every row, which moves TalkBack focus off a row.

Counts: 0 High, 0 Medium, 2 Low, 5 Nits.

## What I ran

- From `launcher/`: `assembleDebug testDebugUnitTest --rerun -PwarningsAsErrors=true` passed. It ran 1222 tests in 129
  classes, matching the implementation status.
- `sh scripts/material-symbols.sh` from the root, run again against the pinned commit: it printed "Done: 16 app icons,
  12 music icons" and `git status` was clean afterwards. The run is deterministic, and the `hourglass_bottom` hash
  verifies.
- My own WCAG contrast calculation, with the alpha inks composited:

  | Ink | Ground | Ratio |
  | --- | --- | --- |
  | `kid_emergency_text` | `kid_ground` | 8.58:1 (the ≥ 7 claim holds) |
  | `kid_core_dim` | `kid_ground` | 9.9:1 |
  | `kid_core` | `kid_ground_lift` | 14.0:1 |
  | the chevron (`kid_lock_chevron`, 50 %) | the lift | 4.45:1 (graphic, ≥ 3) |
  | `kid_accent` | its 14 % disc | 7.8:1 |
  | white | `kid_phone_book` | 5.46:1 |

## Low

**L1 - "Emergency call is always shown" is pinned only in the layout, not at runtime.**
`TimeLockModelTest.kt:189` checks only that the XML has no `visibility` attribute. A line in `render()`
(`LockActivity.kt:162` area) that hides `lockEmergencyButton` in some state therefore passes the whole suite (mutation 6).
The button must be visible in every state: it is the only way to the dialer during a lock (QA step 4 #1).
*Fix:* in `the targets and the emergency flow are what they were`, assert that `LockActivity.kt` never assigns
`lockEmergencyButton.visibility`, `.isVisible` or `.isGone`. Alternatively, set it to `View.VISIBLE` explicitly in
`render()` and pin that line.

**L2 - The first icon pass rebuilds every row, so TalkBack focus on a row is lost.**
When the background job has drawn any icon, it calls the whole `render()` (`LockActivity.kt:295`). That runs
`TimeRulesRuntime.snapshot` again, and `renderRows` (`:204-234`) removes and re-inflates every row. This happens on
most opens after a process start, a fraction of a second after the screen appears. Two things break:
- A TalkBack user who has already moved onto a row loses accessibility focus: the node is gone.
- A press in progress loses its ripple.

The hard rule "TalkBack reads each row" holds for a steady screen, but not across this refresh. The glance page does the
same, but it isn't a screen the child navigates by rows.
*Fix:* keep a `packageName -> ImageView` map from `renderRows`. When the job ends, set each drawn bitmap in place
(`KidAvatars.circular`, transparent background tint) and leave the rows alone. Then `render()` stays tied to the
preference, clock and locale events, as before P6.

## Nits

**N1 - The unlock-code button's Button role is not pinned.** Removing `asButton(binding.lockUnlockCodeButton)`
(`LockActivity.kt:85`) passes the full suite (mutation 7). `TimeLockModelTest.kt:214` pins only the emergency button's
role. *Fix:* add the same assertion for `lockUnlockCodeButton`.

**N2 - `distinctBy { it.packageName }` is dead code, and its test can't tell the key.** `timeLockRows` gets a list
built from `lock.usableApps`, which is a `Set<String>`, so a package never repeats (`TimeLockModel.kt:90`). The test's
duplicate (`TimeLockModelTest.kt:118,121`) has the same package and the same label, so `distinctBy { it.label }` also
passes (mutation 2). Deduplicating by label would wrongly drop one of two apps the parent named alike.
*Fix:* drop `distinctBy` and the duplicate in the test (less code). Or, if it stays, test two packages with one label
and expect both rows.

**N3 - The rows' pressed ripple is square inside the card's 18 dp corners.** Each row's background is a bounded
`selectableItemBackground`. The card (`activity_lock.xml:74`, `bg_lock_card`) doesn't clip it, so pressing the first or
last row lights the card's rounded corners square. *Fix:* add `android:clipToOutline="true"` on `lock_rows`
(minSdk 34).

**N4 - The icon loader is a copy of the glance page's, races included.**
`iconKey`, `appIcon` and `renderMissingIcons` (`LockActivity.kt:248-301`) repeat `GlancePanel.kt:433-475` almost line
for line. In both copies, `failedIcons` is a plain `mutableSetOf` that is written on `Dispatchers.Default` and read on
the main thread by a render that runs during the job. That is harmless in practice, but it is a data race.
*Fix:* one small shared helper (in `ui/home/KidAvatars.kt` or next to it) that returns the failed packages from the
`withContext` block and updates the set on the main thread. Both screens can use it, and it removes about 40 lines.

**N5 - A tautological assertion.** `TimeLockModelTest.kt:142` asserts that `"lock_glyph_dot.xml"` is in the name of
the file it just opened by that name. *Fix:* assert that the dot is hand-drawn instead, i.e. that its text has no
"Converted from" line. The glyph's wiring is already pinned below that line.

## Mutations

| # | Mutation | Tests run | Result |
|---|---|---|---|
| 1 | `timeLockRows`: phone book when `callsManaged` only (shown with calls blocked by the rule) | `TimeLockModelTest`, `KidPaletteTest`, `ClockFormatTest` | caught (`rows - the phone book first...`) |
| 2 | `distinctBy { it.label }` (an app sharing a label is dropped) | same | **survived** (N2) |
| 3 | Overrun: `Used(max(allowance, usedMinutes))` | same | caught (`an overrun reads the same sentence`) |
| 4a | `durationText`: `total <= 60` for minutes only (60 → "60 min") | `TimeLockModelTest` | caught (nb 60) |
| 4b | `durationText`: `rest <= 1` (61 → "1 t") | `TimeLockModelTest` | caught (nb 61) |
| 5 | `openableApps` without `CameraLock.suspendedForLists` (a held camera dropped) | full unit suite | caught (`the targets...`) |
| 6 | `render()` hides `lockEmergencyButton` while calls are allowed | full unit suite | **survived** (L1) |
| 7 | `asButton(binding.lockUnlockCodeButton)` removed | full unit suite | **survived** (N1) |
| 8 | A row's `asButton(view.root)` removed (no Button class) | full unit suite | caught (`each row is one button...`) |

## Checked and fine

- **Removed resources.** `KidLockButton`, `item_lock_app.xml`, `kid_message_button`, `lock_budget_used`,
  `lock_phone_book_button` and `lock_apps` are unused anywhere in `launcher/app/src` (the matches left are comments,
  docs and the tests asserting they are gone). `bg_kid_button` is kept and still used (`styles.xml:231`).
  `kid_back_button` is still used by `bg_kid_back_button`.
- **The sentence.** It has three branches:
  - Budget plus extra is `BudgetUse.effectiveMinutes`.
  - Budget 0 with no extra gives "Ingen skjermtid i dag."; 0 plus 15 extra gives the normal sentence.
  - A rule whose budget is also used up gives "også brukt opp". A rule whose budget is not used up gives nothing.

  The calls sentence keeps its old condition. "Til hh:mm" is on its own line. nb and en both have the new strings
  (`TranslationsTest`).
- **The glyph pipeline.**
  - The `LOCK_GLYPHS` table is in `material-symbols.sh`.
  - `hourglass_bottom` is pinned by SHA-256. `bedtime` and `menu_book` were already pinned.
  - The three files carry the "Converted from ... Apache License 2.0" line with the commit, and Apache-2.0 is already
    in `assets/licenses`.
  - The dot is drawn by hand.
- **TalkBack.**
  - Each row is one focusable, clickable node whose content description is the label. Icon, label and chevron are
    `importantForAccessibility="no"`.
  - The role comes from the node's class, so TalkBack doesn't read "knapp" twice.
  - The kind's disc is decorative and the title is a heading (minSdk 34, so the attribute applies).
  - The focus order is clock, title, message, rows, Nødsamtale, then the code.
- **Performance.**
  - Icons are drawn off the main thread. The main thread only does an `LruCache` lookup and a scan of the app list
    per row.
  - There is no per-frame work and no timer, and the job is cancelled in `onStop`.
- **Layout.** The ground is solid `kid_ground`, so the contrast checks against the night are the real ones. The
  weighted `Space` inside `fillViewport` puts the two buttons at the bottom and still scrolls when the content is long.
  Labels wrap to two lines.
