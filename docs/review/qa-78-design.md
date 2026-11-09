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
