# Design QA: design 78 - a light look for the kid's own screens (`docs/design/78-kid-light-mode.md`)

Checked against `design-78-light` (2a6b282f, on `main` b9eff6b6) and `sms-screens` (376854a6, which includes the cited
c36366a1): the manifest (every activity's theme, `configChanges`, task, process, direct boot), `res/values/styles.xml`,
`res/values-v29/styles.xml`, `colors_kid.xml` on both branches, `ui/UIObject.kt`, `ui/KidHeader.kt`,
`include_kid_header.xml`, `calls/KidSheet.kt`, `calls/ContactSheet.kt`, `KidSheetDialog`, `ui/day/DayListAdapter.kt`,
`day/CalendarColor.kt`, `ui/wallpaper/WallpaperGround.kt`, `ui/home/NightGround.kt`, `play/PlayLinkBlockedActivity.kt`,
`lock/PinLockActivity.kt:736-760`, `Application.kt:290-330` (`ensureUnlockedInit`), `server/LocateCommands.kt:349`,
`zones/ZonePlan.kt` (`QueuedFix.mock`), the message screens and their drawables on `sms-screens`, the Wi-Fi, Bluetooth
and sound-picker layouts, `KidPaletteTest`, `docs/notes/launcher/ui-polish.md`, `server/testdata/weather.json`'s `dark`
rows (byte-identical in the launcher), AppCompat 1.7.1's `AppCompatDelegateImpl` (disassembled: `setLocalNightMode`,
`updateAppConfiguration`, `onCreate`, `attachBaseContext2`) and `UiModeManager` in `android-36`'s `android.jar`. Every
contrast figure below was recomputed (WCAG 2.x, translucent tops composited first). No build, no emulator.

**Verdict: the right product shape and the right primary mechanism, but the "everything else stays exactly as it is"
half of §5.3 doesn't hold as written.** The per-app night mode is per package and the music app is out of its reach.
minSdk is 34, so there is no below-31 path. The palette passes its stated contrast pairs, and so do the dot table and
the bubbles. Automatisk's rule matches design 75's shared vectors. No adb step, no new permission and no insecure
fallback. i18n is complete, and leaving the PWA out is justified.

The problems:
- Pinning the other screens to "the phone's own mode" can't be read reliably. It also makes every shared resource resolve
  day on a phone whose own dark theme is off (H1).
- The flip test counts a resumed *page*, so the Meldinger task can be closed while the kid looks at a picture in it (H2).
- The first-run step reads CE storage at process start, which can come before the first unlock (H3).
- Several smaller mechanism gaps: AppCompat's recreate, the first run, pages in Home's task, theme-attribute colours, the
  Dagen legend and the contact card's navigation bar.

Counts: **0 Critical, 3 High, 7 Medium, 11 Low, 6 Nit.**

## What holds (verified)

- **Scope (§2):** every "In" screen paints `kid_ground` (or `kid_ground_lift`/`kid_music_tile`) itself: `activity_day`,
  `activity_event`, `activity_event_edit`, `activity_messages`, `activity_thread`, `activity_wifi_networks`,
  `activity_bluetooth_devices`, `activity_kid_sound_picker`, `sheet_contact`. Home, the phone book and kid Settings draw
  the wallpaper (`WallpaperGround`; Home draws its own copy, so Android's wallpaper rendering is never involved). No
  kid screen with its own full ground was missed. Home's "Spilles nå" card and call card are surfaces on Home, and are
  out by the same rule as the lock's cards (see L7 for wording).
- **§1:** no `-night`/`-notnight` directory exists (`values`, `values-h720dp`, `values-nb`, `values-v29` only). Only
  `PinLockActivity` declares `uiMode`, so a Battery Saver toggle relaunches `InCallActivity` mid-call today. Fixing
  that is a real bonus of the design. Home is `singleTask` + `clearTaskOnLaunch`. `DAY_BACK_TO_TODAY_MS` = 60 s.
  `windowDisablePreview` is ignored for a launch from HOME. `newestKnown` exists and carries `mock`.
- **The music app:** `setApplicationNightMode` is keyed by the calling package (the system's per-package config
  persister), so `me.vibb.music` never sees it. `:bootcover` gets it, but its theme is framework Material with explicit
  bar ink and unqualified colours, and it only runs before the unlock, when no flip can happen.
- **Lock task:** the lock task's root is Home (or the PIN lock), never Dagen or Meldinger. `finishAndRemoveTask` on
  those tasks never ends lock task.
- **The palette:** every pair in §4.1 recomputes within 0.2 of the note:
  - ink 15.42/17.17, dim 6.37/7.09, accent 5.16/5.75 (4.59 on the received bubble)
  - on-accent on the fill 7.58, the pill's ink 14.66 and accent glyph 4.91
  - received ink 13.70 and its time 5.40, mine 5.07, alert 5.87/6.54, danger 6.17
  - the own bubble 8.70/4.71, the call green 4.77, the badge 4.98
  - every day dot is >= 3.65 on the ground and >= 4.06 on the tile
  - the decided night bubbles match `sms-screens` (`kid_message_in` #34324A, white 12.34, time 4.88; own peach with
    #1B1B1F 8.70 and #5E4034 4.71).
- **Automatisk vs design 75:** `weather.json`'s six `dark` rows are the right oracle. The note's -6°, NOAA port and
  `never`/`all_day` kinds match `weather::dusk`.
- **Product rules:** no adb, no permission and no new location request (an existing fix, never a mock). Strings exist
  in en and nb. The choice is not a restriction, so the liftable rule doesn't apply and no PWA control is needed (D8 is
  sound).

## Findings

**H1 High - The other screens can't be pinned to "the phone's own mode", and pinning them to it leaks the day palette.**
(§5.1, §5.3, D2)
- *How the mode is read.* §5.3 pins AppCompat on every non-page activity to `Resources.getSystem().configuration`. In
  our process that object is updated from the process configuration (`ResourcesManager.applyConfigurationToResources`).
  On 13+ the process configuration carries the app-specific override that `setApplicationNightMode` installs, which is
  why AndroidX's `LocaleManagerCompat.getSystemLocales` stops using `Resources.getSystem()` on 33+ for the same
  mechanism's locales. So it reads the kid's choice, not the phone's. `UiModeManager.getNightMode()` returns the
  setting, but not Battery Saver's forced night or AUTO/CUSTOM's current phase. There is no public getter for the
  phone's applied night bit once an app override exists.
- *Why it leaks.* Even with a correct reading, "pinned to the phone's mode" means that on a phone whose own dark theme
  is off (the likely state after setup), Home, the phone book, kid Settings, `PictureActivity`, parent Settings, the
  time-rule screen and the PIN lock resolve `values-notnight`. §5.1's "the files resolve by the kid's choice, never by
  the phone's own setting" then holds only for pages. Shared resources would take their day values on those screens:
  - `include_kid_header.xml` (title `kid_ink`, subtitle `kid_core_dim`, `bg_kid_back_button`, `ic_kid_back`): kid
    Settings, the phone book, `PictureActivity` and every page
  - `bg_kid_sheet`: the contact card and the emergency menu
  - `bg_kid_badge`/`bg_kid_call_badge`: Home's grid, the phone book and the card
  - `bg_messages_button`: the delete confirm and `PictureActivity`
  - `bg_day_event`: Dagen, `item_messages_row` and a style
  - `bg_kid_card`: kid Settings and the sound picker
  - `ic_kid_back`: also the PIN lock

  A dark back disc on a dark wallpaper, or a white emergency menu, follows from a token swap §9 calls mechanical.
- *Fix:*
  - Pin every non-page AppCompat activity to the constant `MODE_NIGHT_YES`; it reads nothing. A `kid_page_*` token
    reached anywhere outside a page then *is* today's night value, so shared resources are harmless.
  - Put the pin in `UIObjectActivity`'s `attachBaseContext` (see M1; `ui/UIObject.kt` is not in the MIT `ui/list/`),
    with the page base class overriding it, plus `PinLockActivity` and `InCallActivity`, which extend
    `AppCompatActivity` directly.
  - The cost is honest and small: on a phone whose system dark theme is off, the AppCompat-drawn bits on those screens
    (`?selectableItemBackground` ripples, `colorControlNormal`, unthemed switches, parent Settings' preference widgets)
    take their night form, which is what a night UI should have had. D2 must then say "identical in the system's dark
    mode; in its light mode only these AppCompat bits change", and step 1's before/after screenshots check exactly that.
  - The weaker alternative is a pin from `getNightMode() == MODE_NIGHT_YES` plus a scan test that no non-page layout,
    drawable or style reaches a `kid_page_*` token or a `values-notnight` style.

**H2 High - "No page resumed" closes the Meldinger task under the kid's eyes.** (§5.4 items 1-3, D7)
- `PictureActivity` lives in the `.messages` task (`sms-screens` manifest) but is out of scope, so it isn't a page.
  With a flip pending (for example, a boundary passed while the thread was open), this happens:
  1. The kid taps a picture.
  2. `ThreadActivity.onPause` arms the 1 s check.
  3. No page is resumed, the screen is on and the phone is unlocked, so `applyIfDue` runs `finishAndRemoveTask` on the
     Meldinger task.

  The picture the kid is looking at disappears, and so does the share sheet opened from its "Del".
- A translucent activity over a page (the system share sheet, a permission dialog, our `ComposeActivity`) also pauses
  the page while it stays visible.
- *Fix:*
  - Decide on visibility of the page tasks, not on resumed pages: count *started* activities (`onStart`/`onStop`) of
    the page base class and of every activity in a page task (`PictureActivity` included, through the same counter, no
    theme change).
  - Allow a flip only when that count is zero.
  - Add `PageLookTest` rows: a picture in front, a translucent activity over a page.

**H3 High - The first-run step reads CE storage at process start, which may be before the first unlock.** (§5.4
item 3, §7)
- §5.4 runs `applyIfDue` "at process start only on the first run (no `applied` stored yet)". Knowing that means reading
  `kid_look` in CE prefs.
- The main process does start before the first unlock: `InCallActivity`, `KidInCallService`, the call screening and
  redirection services and `BootCallReceiver` are `directBootAware`.
- A CE `SharedPreferences` read there throws. The process that must ring and show an emergency call before the unlock
  would crash, which is the BFU incident class `launcher/CLAUDE.md` warns about.
- §7's "Before the first unlock nothing reads it (no page runs then)" contradicts §5.4.
- *Fix:* the start hook is `Application.initUnlocked()` (reached through `ensureUnlockedInit`: the component factory
  before a non-direct-boot component, and `ACTION_USER_UNLOCKED`), never `Application.onCreate`. Add a source-scan test
  that `PageLook`'s prefs are touched only from that path, the pages and kid Settings.

**M1 Medium - Setting AppCompat's local mode "before `super.onCreate`" recreates the page in exactly the case the belt
exists for.** (§5.3)
- At that point `attachBaseContext2` has run (`mBaseContextAttached = true`). AppCompat 1.7.1's `setLocalNightMode`
  then calls `applyDayNight()`, which is `applyApplicationSpecificConfig(allowRecreation = true)`.
- In `updateAppConfiguration`, an activity that doesn't handle `uiMode` (every page) and whose night bit differs is
  `ActivityCompat.recreate`d. That is a second `onCreate` and a visible restart, the flash the belt was meant to avoid.
- An activity that does handle `uiMode` (every non-page) instead gets an in-place `Resources.updateConfiguration` rather
  than AppCompat's override configuration. The next configuration push from the framework resets it until AppCompat
  re-applies it.
- *Fix:* set `delegate.localNightMode` in `attachBaseContext` before `super.attachBaseContext`, so `attachBaseContext2`
  builds the override configuration from the start. Step 1's recording should also confirm `onCreate` runs once per
  page open.

**M2 Medium - The first run waits on `mayFlip`, and `applied` can drift from the system's value.** (§5.4 item 3, §10)
- Until the first `setApplicationNightMode` call, the app's mode is "undefined", so pages follow the phone's own
  setting. On a phone whose dark theme is off, Kalender and Meldinger then resolve `values-notnight`, which is *light*,
  though the kid chose nothing.
- §5.4 routes the first run through `mayFlip`. An update installed in the night window (screen off or locked) leaves it
  pending until Home resumes. A page opened without Home resuming first (the glance's Kalender row, a message
  notification, the time-rule screen's Kalender row) opens light, and M1's belt then recreates it.
- Separately, `applied` and the system's value can part:
  - a crash between the system call and the `commit`;
  - the system persists the package configuration asynchronously, so a reboot right after a flip can lose it;
  - a data clear (which also clears `applied`, so that case is consistent).
- *Fix:* at every unlocked start (`initUnlocked`), call `setApplicationNightMode(applied ?: YES)` unconditionally. It is
  a no-op when equal, and at a first run no page of this process exists yet. On a flip, `commit()` `applied` *before*
  the system call. That also makes §10's "no flip at a restart except the very first" true by construction.

**M3 Medium - Pages in Home's task can be behind at a flip.** (§5.4 item 4)
- "The Wi-Fi, Bluetooth and sound lists are never behind at a flip ... Home resumed means they are gone" holds only for
  the Home-resume trigger. The boundary callback and "1 s after a page's `onPause`" fire with the list stopped in Home's
  task, for example:
  - the kid leaves the Wi-Fi list through a notification into the music app;
  - a time rule begins and `LockActivity` starts in Home's task above the list.
- The list then relaunches when it is shown again: one frame of the old window, and the scroll and dialog state lost.
- *Fix:* the page base class keeps a registry of live page activities. A flip finishes every one of them (and
  `finishAndRemoveTask` for the two page tasks), not only the two tasks. `PageLookTest`'s "tasks a flip closes" gains
  the Home-task case.

**M4 Medium - Theme-attribute colours on pages are not in the plan.** (§5.2, §9 step 2)
- `item_wifi_network.xml` and `item_bluetooth_device.xml` colour their text and icons with
  `?android:attr/textColorSecondary` and their rows with `?attr/selectableItemBackground`.
- `launcherBaseTheme` sets `android:textColor`/`textColorPrimary`/`textColorSecondary`/`textColorHint` to
  `finnmglasTheme_text_color` (= `kid_ink`) and `colorAccent` to `kid_accent`. `UIObject.modifyTheme` re-applies
  `colorThemeDefault` (`android:textColor` = `kid_ink`, `colorAccent`) on *every* `getTheme()` call.
- `KidPageTheme` names only the window, `colorBackground`, the bars and the dialog theme, and `PageColors` only
  `colorBackground`. In Lys, the Wi-Fi and Bluetooth rows would be white on paper, and `PageTokensScanTest` can't see a
  theme reference.
- *Fix:*
  - `PageColors` covers `android:textColor`, `textColorPrimary`/`Secondary`/`Hint`, `colorAccent`,
    `colorControlNormal`/`Activated`/`Highlight` and `colorPrimary`, all `kid_page_*`.
  - It is applied in the page base class's `getTheme()` after `modifyTheme`, on every call.
  - `KidPaletteTest` checks the overlay's text pairs.
  - The scan test flags `?android:attr/textColor*` in page layouts only if the overlay is missing.

**M5 Medium - The Dagen legend's dots are on the page, not "unaffected".** (§4.2)
- `DayListAdapter.Legend` draws `DotSpan(color.argb, ...)` into `item_day_legend` on Dagen's own ground, and the event
  page shows its calendar's dot too.
- In Lys, the night table on the paper ground is 1.52 (sand), 1.64 (sun), 1.71 (sea) and 1.77 (own) up to at most 2.33,
  all under §4.2's own 3:1.
- *Fix:* one resolver, `CalendarColor.argb(context)` (by the context's night bit), used by the row dot, the legend and
  the event page. The glance keeps the night value through its pinned context. A scan test forbids reading `.argb`
  directly under `ui/day/`.

**M6 Medium - The contact card's window has night bar ink over a white card.** (§4.3, §5.3)
- `KidSheetDialog` is a non-floating, translucent window that runs to the bottom edge. `KidSheet.setUpKidSheet` puts the
  sheet's own surface under the navigation bar, and nothing sets the dialog window's bar appearance.
- In Lys the gesture handle (or the 3-button icons) is white on the white card.
- *Fix:*
  - Create the dialog on `PageLook.wrap(activity)` (the dialog's theme must resolve in the wrapped configuration, not
    only the inflated view).
  - Give it `windowLightNavigationBar` = `@bool/kid_page_light_bars`, or set
    `insetsController.setSystemBarsAppearance(APPEARANCE_LIGHT_NAVIGATION_BARS)` from the resolved mode.
  - Add an emulator shot of the card in Lys with gesture and 3-button navigation.

**M7 Medium - The whole design's go/no-go comes after ~600 lines.** (§9 step 1, §10)
- Everything rests on WM merging the per-app night mode into the starting window. Android 12+'s splash is drawn by Shell
  from the `StartingWindowInfo`'s task configuration, and the splash window's bar ink is Shell's decision too.
- §0 states both as fact, while §10 rightly calls them unproven. Step 1 builds the tokens, Kalender and the row before
  the recording.
- *Fix:* add a step 0 spike on a throwaway branch: `KidPageTheme` with one `values-notnight` ground on `DayActivity`, the
  system call, and a 60 fps recording of Home -> Kalender in both modes on the Android 16 emulator and the Jelly Star
  (the OEM risk §10 defers "to the first device run"). Reword §0 as the hypothesis the spike checks. Only then build
  step 1.

**L1 Low - The position can be stale or far away.** (§6)
- `newestKnown` is whatever fix was last kept. Under an "on request" location policy it can be weeks old, or from a
  holiday abroad. The instant maths stay right for *that* place but wrong for home.
- *Fix:* an age limit (for example 14 days), and then a chain: the fix, then the lock weather's `dark` instant for the
  evening when `WeatherStore` has one, then the fixed hours. Re-arm the boundary when a newer fix arrives.

**L2 Low - §6's polar wording and step 3's test list disagree with the shared vectors.** (§6, §9 step 3)
- "White nights ... light all day": `weather.json`'s Oslo midsummer row is dark at 00:29. Only Trondheim and Tromsø are
  `never`.
- Step 3's list pairs "Tromsø 2026-12-21" with "dark all day". Tromsø has twilight at noon (the `at` 13:53 row), and
  dark all day is Longyearbyen (`all_day`).
- *Fix:* name each case's expectation.

**L3 Low - The 07:00-19:00 fallback is far off in a Norwegian winter.** (§6, Q3)
- In Oslo in December it is light for over three hours of darkness, and in Tromsø for most of the day.
- Either say so in Q3, or (cheap) derive the fallback from the zone and month. L1's weather instant covers most phones
  anyway.

**L4 Low - "Automatisk" may not fit a third of the card on the Jelly Star.** (§8)
- At 320 dp the segment is about 85 dp before its own padding. Nunito 800 at 14 sp puts "Automatisk" at about that
  width.
- With the control's `hyphenationFrequency="normal"` (ui-polish.md, P9) it may break as "Automa-tisk" at font scale
  1.0.
- *Fix:* measure it in step 1. If it breaks, use `hyphenationFrequency="none"` on these labels and let them wrap whole.

**L5 Low - Shapes on paper are faint.** (§4.1)
- Tile on ground is 1.11:1 (the note says so), the received bubble 1.13:1, and the compose field, a text input, also
  1.11:1 with no boundary. WCAG 1.4.11 expects an input to be identifiable, and the placeholder only partly does it.
- `kid_page_control` #EAE5E1 is 1.00:1 on the received bubble #E8E4EE, so a picture's placeholder vanishes inside a
  received bubble.
- *Fix:* a 1 dp `kid_page_divider` hairline on the compose field (and, if wanted, tiles) in day. A received bubble a
  shade deeper, or a placeholder from `kid_page_tile`.

**L6 Low - Some `sms-screens` drawables are not in §4.1.**
- `bg_message_selected_in` (a `kid_accent` ring, 4.59 on the day bubble), `bg_message_selected_out` (a `kid_core` ring,
  which `kid_page_core` would make #1B1B1F on the peach, 8.70), `bg_message_picture` (`kid_music_control`) and
  `bg_messages_unread`.
- *Fix:* name each one's token so step 2 doesn't guess.

**L7 Low - The white contact card overturns an earlier decision, and the scope rule should say why.** (§2, §4.3, Q4)
- Design 34 P3 (QA M4) decided "the contact sheet is night, never a white surface". `KidPaletteTest` pins it (`the
  contact sheet is night, never a white surface`).
- *Fix:* Q4 should cite that, so the user overturns it knowingly, and step 2 lists the test's rewrite. §2's rule should
  read "full-screen grounds plus the modal card the kid reads at length", so Home's music and call cards are visibly
  out by rule.

**L8 Low - `PlayLinkBlockedActivity` can't take the AppCompat pin.** (§2, §5.3)
- It is a plain `Activity` without a theme, so it runs on the application's `launcherBaseTheme`
  (`Theme.AppCompat.DayNight`).
- In Lys the app mode resolves AppCompat's light window background, so a light frame shows before its night content
  view.
- *Fix:* give it an explicit framework night theme, as `BootCoverTheme` does.

**L9 Low - Step 1 ships a caption that isn't true yet.** (§8, §9)
- The row's caption names Meldinger, the card and the lists, while step 1 changes only Kalender. A launcher tag cut
  between steps 1 and 2 would show it.
- *Fix:* a per-step caption, or keep the row hidden until step 2.

**L10 Low - Android 16's "make more apps dark" may invert Lys.** (§5.6 last line, §10)
- Android's own force dark is off for a light configuration, as the note says. The newer force-invert accessibility
  option ("make more apps dark") is documented in AOSP as ignoring the app's `uiMode` override. If a parent turned it
  on, Lys pages could be inverted.
- *Fix:* add it to step 1's emulator checks and to §10. Nothing to build unless it shows.

**L11 Low - Kept drafts need a lifecycle.** (§5.4 item 4, step 2)
- A draft kept per conversation in CE prefs should be cleared when the thread is deleted, when its contact leaves the
  rules, and when the phone goes unmanaged, and capped in count.
- *Fix:* `MessagesModelTest` rows for each.

**N1 Nit - minSdk is 34.** "API 31+" invites a below-31 path that can't exist. Say minSdk 34, so no fallback is
designed. §10's OEM line stays.

**N2 Nit - One mockup figure is off.** The mockup's and §4.1's received-bubble ink is 13.9; it computes to 13.70. The
rest agree.

**N3 Nit - One ripple token for two night values.** `kid_page_ripple` #1FFFFFFF replaces both #1FFFFFFF and #33FFFFFF
(`bg_kid_back_button` is #33). Night is then not pixel-identical. Keep two tokens, or say so in D2.

**N4 Nit - The strings.**
- "Lys hele døgnet i dag" and "Mørk hele dagen i dag" use different nouns. Use "døgnet" in both.
- "kl. %1$s" reads oddly before a 12-hour time. Drop "kl." when `ClockFormat` gives 12 h.

**N5 Nit - kid Settings needs to refresh.** When a boundary flips while kid Settings is in front (it flips at once),
the row's preview and "Lys nå · mørk fra ..." line must refresh. Name the listener.

**N6 Nit - Found in passing (pre-existing, outside this design).**
- `res/values-v29/styles.xml` redefines `launcherHomeTheme` without the `windowSplashScreen*` items. On minSdk 34 it
  shadows `values/`, so Home's breathing splash (styles.xml:153-162) is never used.
- This weakens §5.6's `setSplashScreenTheme` argument (the "breathing mark" it would replace isn't shown today; the
  PIN lock's black stand-in still is).
- It is also a reminder for §4.4: a style in `values-notnight` replaces the whole style; it never merges.

---

## Revision 3 (2026-11-11)

Design QA of §12, "Revision 3: the mechanism after step 1's passes", and of the three rows it added to §5.6, at
d2812379 (branch `kid-look-mech-qa`). Paper and source reading only: nothing built, nothing run, no emulator.

Read for it: this branch's launcher code; `frameworks/base` at tag `android-16.0.0_r1` (`ActivityRecord`,
`ActivityStarter`, `Task`, `RootWindowContainer`, `RecentTasks`, `TaskPersister`, `LockTaskController`,
`PackageConfigPersister`, `UiModeManagerService`, `ActivityThread`, `ParsedActivityUtils`, and the Shell's
`SplashscreenContentDrawer`, `SplashscreenWindowCreator`, `AbsSplashWindowCreator`, `PhoneStartingWindowTypeAlgorithm`,
`SnapshotDrawerUtils`); and AppCompat **1.7.1**'s own sources (`appcompat-1.7.1-sources.jar` from Google's Maven, its
SHA-1 equal to the published one) - the version the app builds with (`launcher/gradle/libs.versions.toml:6`), which
§12 did not have at hand.

Every statement below is marked: **(code)** read in this branch, **(AOSP)** read at that tag, **(AndroidX 1.7.1)**
read in that jar, **(inferred)** concluded from those and read nowhere.

**Verdict: the mechanism holds against the sources, and the spike may start after four named changes to §12.7 (S1-S4
below). None of them touches the decision (§12.2).** Every load-bearing AOSP claim of §12.3 that was checked holds, and
R8 holds in 1.7.1 as it was read in `androidx-main`. What is short is the spike's power to see a wrong frame in Lys
(M1), and R6's last sentence, which calls a reachable case unreachable and leaves two failure rows of the new order
unnamed (M2).

Counts: **0 High, 2 Medium, 5 Low, 3 Nit.**

### What holds (verified)

- **The starting window's theme is the launched activity's own manifest theme.** `ActivityRecord.java:1980` (the
  record's theme is the manifest's), 7102-7104 (the resolved theme; a replacement only from the options' theme or
  `PackageManager.getSplashScreenTheme`, 7046-7072), `AbsSplashWindowCreator.java:47-52`. On a task brought forward
  it is the **top** activity's: `Task.java:3566-3594`. (AOSP)
- **It is resolved in the record's server-side configuration, which carries the phone's night bit.** `Task.java:3592`
  copies the activity's configuration in; `SplashscreenContentDrawer.java:155-206` re-creates the package context
  when the night bits differ. (AOSP) AppCompat's local night mode is an override inside the client and never reaches
  that record. (inferred) So R3's "unqualified" is necessary and sufficient: the Shell reads only `windowBackground`,
  `windowSplashScreenBackground` and the icon items (`SplashscreenContentDrawer.java:472-490`), and the ground is the
  splash colour when it is set (353-372). (AOSP) **The four cases of §12.3's table hold, for a new task and for a task
  brought forward.** The theme's `Theme.AppCompat.DayNight` parent does not matter to the Shell. (inferred)
- **The bar ink comes from the ground's lightness**, not from `windowLightStatusBar`:
  `SplashscreenWindowCreator.java:209-221`. A snapshot's window copies the pictured window's bar appearance:
  `SnapshotDrawerUtils.java:236-258`. (AOSP)
- **Splash or snapshot.** `ActivityRecord.java:2392-2431`: a splash for a non-home activity in a new task, with no
  process, or at a task switch to an activity not created (`activityCreated` is "between STARTED and STOPPED",
  7109-7110); a snapshot only at a task switch to a created activity whose snapshot has the same top component and
  rotation (2458-2470). The Shell's `PhoneStartingWindowTypeAlgorithm.java:45-120` repeats the rule and adds a
  solid-colour splash where a snapshot was allowed and none exists (94, 107) - coloured from the same theme. Nothing
  in the Shell's snapshot classes reads the snapshot's `uiMode` (a grep), so a phone-theme flip does not make one
  stale. (AOSP) A card reopened after a reboot is therefore a splash from the top activity's theme: R2's "all three
  need a twin" is right.
- **"No process" never happens at a tap.** The pages run in the launcher's main process, with Home and the lock
  (no `android:process` on their manifest entries, `AndroidManifest.xml:229, 236, 242`). (code) So the case that
  matters after a death is "task switch to an activity not created", above. (inferred)
- **`windowDisablePreview` and the home process.** `ActivityRecord.java:2231-2271`: ignored when the start comes
  from the system, SystemUI or the home process (2200-2229, 2263). (AOSP) Every start of a page is the launcher's own
  (`HomeGridAdapter.kt:120`, `LockActivity.kt:306`, `GlanceOpener.kt:151`, and the three inside `ui/day/`:
  `DayActivity.kt:114`, `EventActivity.kt:117`, `DayListAdapter.kt:177`). (code)
- **Home-type gets no theme-drawn window** (R9): `ActivityRecord.java:2420-2423`, and an activity added to a task
  takes the task's type, `Task.java:1498-1528`. (AOSP)
- **Two classes, one affinity.** `RootWindowContainer.java:403-427`: a task whose `realActivity` is the class, else
  one with the same root affinity; `ActivityStarter.java:2574-2602` then adds the new activity on top. A task whose
  top activity is finishing is skipped (`RootWindowContainer.java:369-376`), so a start right after R6's removal makes
  a new task. (AOSP) **Recents cannot show two `.day` cards**: adding a task removes the older entry of the same
  affinity, `RecentTasks.java:1662-1722`. (AOSP)
- **A start racing a change.** Every start of a page and every change run on the launcher's main thread (the call
  sites above are click handlers; `LookState` is "Main thread", `PageLook.kt:75`). (code) So the two cannot
  interleave: a start before the change makes a task the change then removes, a start after it picks the new class
  and finds no task. (inferred) What is left is L5's shape, M2 below. **A process death between "tasks removed" and
  "look stored"** leaves no page task and the old look, as R6 says. (inferred)
- **The languages are kept once no night mode is in the record.** `PackageConfigPersister.java:221-225`: the write
  record takes the first of night mode, languages and gender that changes it; `updateNightMode` is a no-op for null
  (236-242), and a loaded record's night mode is null when the attribute is absent (126, 141). (AOSP)
  `UiModeManagerService.java:995-1015`: `setApplicationNightMode` stores "undefined" for anything but yes and no.
  (AOSP)
- **R8 in AppCompat 1.7.1.** `AppCompatDelegateImpl.java:2793-2815`: `ActivityCompat.recreate` only when a changed
  bit is not among the activity's declared `configChanges`; 672-702: `onConfigurationChanged` applies Day/Night again
  with recreation off. The declared flags are read for **the host's own class** (2945-2970), so a twin's manifest
  entry must itself declare `uiMode` - `PageManifestTest`'s "pairs identical but for the theme" carries real weight.
  The mode set in `attachBaseContext` (`UIObject.kt:63-72`, code) becomes an override configuration on a wrapper of
  the base context (383-470). (AndroidX 1.7.1) The server relaunches only for a change the manifest does not
  declare: `ActivityRecord.java:8629-8650`. (AOSP) That the framework keeps that override when it updates the
  activity's resources was not read (`ResourcesManager`); it is the path step 1 saw on the non-page activities, and
  A4 is its proof. (inferred)
- **No door past the builders.** A grep over `launcher/app/src/main` (Kotlin, `res/`, the manifest), `src/debug` and
  `proguard-rules.pro`: no `PendingIntent`, `TaskStackBuilder`, `ComponentName(`, `setClassName`, `Class.forName`,
  shortcut, `parentActivityName` or `recreate()` names a Dagen page; the only activity `recreate()`s are
  `HomeActivity.kt:210` and `SettingsActivity.kt:44`. The lock task allow-list is by **package**
  (`LockTaskChrome.kt:360`; AOSP judges a task by its package too, `LockTaskController.java:809-843`), and a page's
  task can be removed under the kiosk - only the lock task's root task is protected
  (`LockTaskController.java:254-258`). `setComponentEnabledSetting` is the boot cover's alone (`BootCover.kt:396`,
  `BootCoverGuard.kt:181`); no persistent preferred activity names a page (`BootCover.kt:110`,
  `AppEnforcer.kt:1635-1686`). `KidAppComponentFactory.kt:21-51` with `DirectBootComponents.kt:22`: a class not in the
  direct-boot list gets the unlocked setup. (code, AOSP)
- **The release build's checks are not touched.** `launcher/app/build.gradle.kts:193-215, 311` scans the merged
  manifest and the dex for the debug hooks' names only; `isMinifyEnabled` (125) with `-dontobfuscate -dontoptimize`
  (`proguard-rules.pro:2-3`), no resource shrinking. (code) An empty subclass named in the manifest is kept by the
  manifest's own keep rules, and with optimisation off no class is merged into its parent. (inferred)
- **An update from a released build is safe.** A phone on main has a `.day` card rooted at `DayActivity` and no
  stored look; absent means dark (`PageLook.kt:31`), and `DayActivity` is the night class. (code, inferred) Choosing
  "the class as it is is the night one" is what makes that so; it deserves a line in R2.

### Findings

**M1 Medium - The spike's recordings cannot carry the Lys rows, and A2 covers only half of A1.** (§12.7 A1, A2)
- *The claim:* sixteen recordings "at 60 fps, stepped frame by frame" must show "not one frame in which the page's
  area has the other look's ground".
- *Read:* in Lys the wrong frame is a starting window on `kid_ground`, #0C0C14 (`values/colors_kid.xml:8`, code),
  and it would come straight after Home, itself a night screen: step 1 describes the right sequence as "Home's night
  frame through the system's cross-fade (66-240 ms) to the paper" (`docs/testing/emulator.md:3464-3465`). A dark flat
  frame inside a cross-fade out of a dark screen is what the eye is asked to find. (inferred) A recording holds what
  the compositor made and the encoder kept; on a software-GL emulator under load neither is sixty a second, and the
  recording does not say how many it missed. (inferred - nothing was read for it) A2's held process is the structural
  proof, but it demands a `Splash Screen` window, so it covers the opens that make the task; A1's eight
  bring-forward rows rest on the recordings alone.
- The system says in text which theme and colour drew each starting window: the server's "Checking theme of starting
  window: 0x%x", "Creating SplashScreenStartingData" and "Creating SnapshotStartingData"
  (`ActivityRecord.java:2235, 2348, 2359`, group `WM_DEBUG_STARTING_WINDOW`), and the Shell's "addSplashScreen for
  package: %s with theme: %s for task: %d, suggestType: %d", the configuration it re-created the context with, and
  "getWindowAttrs: window attributes color: %s" (`SplashscreenContentDrawer.java:169-171, 198-200, 487-489`, group
  `WM_SHELL_STARTING_WINDOW`). (AOSP)
- *What follows:* A1 as written can pass with a wrong Lys frame in it. The reads say there is none; the spike exists
  to see it, and it should not rest on stepping through dark frames by eye.
- *Smallest change:* **S1** - both log groups are switched on for the whole run (`wm logging enable-text
  WM_DEBUG_STARTING_WINDOW`, and the Shell's `protolog enable-text WM_SHELL_STARTING_WINDOW` through `dumpsys
  activity service SystemUIService WMShell` - the commands are from memory, not read: try them on the image first, and
  if it will not print them say so and let A2 carry it). A new must-show over A1, A5, A6 and leg C: for every open,
  the theme logged is the variant's (the two ids from the spike APK's resource table) and the colour logged is the
  literal, with a snapshot only where the task was alive. **S2** - A2 is run **first**, and also once per look and
  phone theme for the open that brings the task forward (an `APPLICATION_STARTING` window on the variant's record,
  the screenshot in the look). A1 stays as the eye's check and states each recording's real frame count over the
  opening second. Not needed in A1: the phone-theme axis on the bring-forward rows - a snapshot does not depend on
  it unless the flip falls while the task is behind, which is A4's row (L2).

**M2 Medium - R6's assertion is a route, and the new order has two failure rows it does not name.** (§12.2 R6, §12.7)
- *The claim:* "tasks are removed first, the new look is stored after"; a page whose class differs from the applied
  look "logs and removes its task ...: an assertion - by the order above no such activity exists - not a route".
- *Read:* `PageLook.kt:234-244` - `closePageTasks` catches a `RuntimeException` per task, logs W and goes on;
  223-228 - `store` is `edit(commit = true)` and drops the result; 127-133 - `change` runs straight through. (code)
  `TaskPersister.java:430-446`: a removed task's file is deleted by the lazy writer when its queue runs empty, not at
  the removal. (AOSP) §5.4, "Step 1": Home's resume runs a waiting change without `mayChange`
  (`PageLook.kt:118-120`, code); L5 (§8, kept by §12.8).
- *What follows* - four ways to a page of the other class, none excluded by the order:
  1. A removal that failed for another reason than "already gone": the change still stores. The next open then
     stacks the new class on the old-rooted task - §12.3's "two classes, one affinity", the very case R6 is there to
     exclude - and the assertion does not see it, because the new activity's class is right. (inferred)
  2. `commit()` returns false: memory has the new look, the disk the old one; after the next process start every
     card made since is of the other class. (inferred)
  3. Power lost, not a clean shutdown, between the commit and the lazy delete: the old card is restored at boot with
     the new look stored. (inferred from the AOSP read)
  4. L5: a waiting change run by a Home resume between a page's start and its creation. (inferred)
- *What the kid sees* in 2-4: the system's starting window in the other look - drawn before any code of ours, the one
  frame the ground rule forbids - then the task closes and the screen below is back; the next tap makes a right task.
  Nothing strands and nothing loops: after the removal there is nothing left to trigger it again. (inferred) Removal
  is the right answer, and a softer one (a restart through the chooser) would hide the cause. In 1 the kid gets a
  mixed task until the next change.
- *Smallest change:* R6 gains two refusals that keep the rule by not changing: **the look is stored only when a second
  read of `appTasks` shows no page task** - otherwise nothing is stored, `E PageLook`, the kid's tap lost - and
  **memory changes only when `commit()` returned true**; `PageLookTest` gets a row for each. R6's last sentence names
  3 and 4 as the known rare routes and says what the kid sees. **S3** - the spike's build has the assertion as its
  log line alone: §12.7 says "no assertion" while A8 gates on "no `E PageLook`", which nothing in that build writes
  once `applyNightMode` is gone (`PageLook.kt:246-254` holds the object's only `Log.e`, code). With the line, A8
  checks over the whole run that no page of the wrong class was ever created.

**L1 Low - R5's scan must hold the night classes' names too.** (§12.2 R4, R5; §12.6)
- *The claim:* the table "is the only place that names a light class (a scan test)"; the builders are "the only ones
  that name the classes".
- *Read:* true today - `DayActivity.kt:265`, `EventActivity.kt:159`, `EventEditActivity.kt:245, 248` build
  `Intent(context, <Page>::class.java)`, and no other start exists. (code)
- *What follows:* after the rework the dangerous name is the **night** one: a later `Intent(this,
  EventActivity::class.java)` compiles, works in Mørk, and in Lys opens a night page that the assertion closes at
  every tap - a door that never opens in one look. That is the one way this design can take a page from the kid.
  (inferred)
- *Smallest change:* R5's scan reads "no page class, night or light, is named as `::class` anywhere in the main
  source set outside the table" (an `is DayActivity` check stays allowed, as §12.3 says).

**L2 Low - A4 proves less than it reads.** (§12.7 A4; R8)
- *The claim:* "screenshots before and after identical; a date picker opened after the flip in the page's look".
- *Read:* a view keeps the colours it resolved when it was inflated, so an identical screenshot shows only that
  nothing was relaunched. (inferred) Only what is resolved **after** the flip tests the pin - the picker is such a
  thing, and it is the row that carries A4.
- *Smallest change:* A4 adds "rows bound after the flip (another day opened, the list scrolled) are in the page's
  look", and records - not only screenshots - the bring-forward of a task that was behind Home across the flip: the
  configuration change is delivered there, and a frame in the phone's look could only show there.

**L3 Low - A6 has the reboot, not the process that is replaced under a living card.** (§12.7 A6)
- *Read:* a card whose process is gone is a task switch to an activity not created, the same branch as the restored
  card (`ActivityRecord.java:2420-2423`, AOSP), so the window should be the twin's. (inferred) It is the nightly
  route on a real phone: the launcher's own update replaces the process with Kalender's card in Recents.
- *Smallest change:* **S4** - A6 once more per look with the launcher's process killed instead of a reboot, the
  event page on top, opened from the tile and from the card.

**L4 Low - What must be true for A2's held process to mean anything is not written down.** (§12.7 A2)
- *Read:* the starting window exists only because the caller is the home process (`ActivityRecord.java:2200-2229,
  2263`, AOSP; `launcherBaseTheme` sets `windowDisablePreview`, `styles.xml:122`, code). On an AVD where the debug
  launcher is **not** the home app there is no starting window at all, and A1 then passes without testing anything.
  (inferred) The launcher has more than one process (`AndroidManifest.xml:203, 592`: `:bootcover` and the isolated
  `:mmsparse`, code). `src/debug/res` holds a network config and one untranslated string file, no theme and no colour, so the
  debug build's windows are the release's. (code)
- *Smallest change:* A2's preconditions in one sentence: the debug launcher is the home app (`cmd package
  resolve-activity` for HOME names it); the spike's themes keep inheriting `windowDisablePreview` as the real build
  will; the process held is the one that owns Home, by pid; the stop is short enough that no ANR dialog is in the
  screenshot; and `dumpsys` shows no page record created yet. With S2's order a missing starting window stops the run
  before the sixteen recordings.

**L5 Low - "Such a device is wiped" is stronger than the source.** (§12.4, last paragraph; §12.7's first paragraph)
- *Read:* `PackageConfigPersister.java:198-207` removes the whole record when the night mode is null **or reset**,
  the languages are empty and the gender is unset. (AOSP) So a record that holds a reset night mode is gone once the
  app's languages are emptied, and the next language makes a record with the language alone. (inferred)
- *What follows:* nothing for the spike - "a restored clean AVD" stays the simple and right instruction, since the
  step 1 build has no call that sets the mode back. Only the sentence "not the same as never having set it" needs
  "while the record lives".

**N1 Nit - §12.9 point 6 is closed.** R8 was read in 1.7.1 (above); §12's "(AndroidX)" mark and §12.6's third bullet
can say so. A4 stays as the proof on the device.

**N2 Nit - R2's alias sentence can cite the source.** `ParsedActivityUtils.java:264-328` reads an alias's own
attributes - no theme among them - and makes it from its target (298). (AOSP) An alias is also the same class, so R4
could not read the look from it. (inferred)

**N3 Nit - Three smaller things.** A7's four boots prove a negative about code that is gone: with R1 a change of look
makes no system call, so two boots (one before, one after a change) say the same; its note for §12.9 point 2 is
main's question and could be taken on main. (inferred) R9 should say that a one-class page has no row in the table
and passes the assertion by the applied look. And the twins sit in `ui/day/`, or `DayTaskTest`'s "none outside
`.ui.day.` has the `.day` affinity" (line 36) changes with them; `PageManifestTest`'s `: KidPageActivity()` source
check needs a form for a twin (`: DayActivity()`). (code)

### The spike: may it start?

**After S1-S4.** In one place:

| | Change to §12.7 | From |
|---|---|---|
| S1 | The two starting-window log groups on for the whole run; a must-show on the theme id and the colour logged for every open | M1 |
| S2 | A2 first, and also for the bring-forward open; A1 states its real frame counts; A2's preconditions written out | M1, L4 |
| S3 | The assertion in the spike's build as a log line, so A8's `E PageLook` gates something | M2 |
| S4 | A6 once more with the process killed instead of a reboot | L3 |

R6's two refusals (M2) and R5's wider scan (L1) are changes to the rework, not to the spike; they can go into §12.2
while the spike runs. Leg C is step 2's and does not gate; it stays as written. Nothing found argues for §12.5's gate
or against the twins.
