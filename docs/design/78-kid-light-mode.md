# 78 - A light look for the kid's own screens: Mørk, Lys, Automatisk

Status: design note by the architect, 2026-11-09, on `main` at b9eff6b6, branch `design-78-light`. **Nothing is built.**
Launcher only: no server, PWA or contract change. Mockups: `docs/design/78-mockups.html` (Meldinger and Kalender in
both looks side by side, the kid Settings row, the token table).

The user's words (2026-11-09):
- On the own-bubble colour in the new message screens: "burde vi ha en mørk og en lys modus".
- On who decides: "kan ikke barnet selv få bestemme mørk/lys/følge telefonen i barnets settings meny? men da må det gi
  mening da, er vel ikke så mye mer enn meldinger og kalender den egentlig styrer, da bakgrunn settes andre steder?"
- On the proposal (one "Utseende" row in kid Settings with Mørk / Lys / Automatisk, Mørk the default, Automatisk from
  the sun at the phone's place, "Følg telefonen" probably meaningless in the kiosk): "Ja du kan sette igang" - this
  note.

## 0. The shape

- The kid picks **Mørk**, **Lys** or **Automatisk** in kid Settings, in a new "Utseende" card under the background
  picker. **Mørk is the default**: no phone looks different after the update.
- The choice changes only the screens that paint their own solid ground: **Meldinger, Kalender (Dagen), the contact
  card, and the Wi-Fi, Bluetooth and ringtone lists** opened from kid Settings (§2). Home, the phone book and kid
  Settings show the wallpaper and stay as they are; the lock, the glance, the time-rule screen, the call screen and the
  emergency menu stay night on purpose.
- **Automatisk** = light while the sun is above -6° at the phone's place (civil twilight - the same rule as the lock
  weather's "Mørkt kl. 17:02", design 75 D10), computed on the phone from the last position it already holds; without
  a position, light 07:00-19:00.
- **Mechanism**: the light tokens are Android resources under `values-notnight/`, and the launcher sets its own
  per-app night mode (`UiModeManager.setApplicationNightMode`, API 31+) to the kid's resolved mode. That per-app mode is
  what Android uses for the starting window and the bar icons before our first frame, so a page opens in its look from
  the first frame. Every other screen declares `uiMode` and pins AppCompat to the phone's own mode, so nothing else
  moves.
- A page **never changes while the kid looks at it**: a change waits until no page is in front, and then closes the
  page tasks behind (Kalender, Meldinger), so nothing can show a stale frame.
- **Stored on the phone only** (CE prefs, like the wallpaper pick). The parent neither sees nor sets it: it is a
  preference, not a restriction.
- **"Følg telefonen" is dropped**: the kid can't reach Android's dark-theme switch in the kiosk, and Battery Saver
  turns it on by itself (§3 D1).

## 1. What exists today (verified against the code)

- **One night palette**, `res/values/colors_kid.xml` (no `-night` or `-notnight` resource directory exists anywhere in
  the launcher): ground `kid_ground` #0C0C14, lift `kid_ground_lift` #1C1A2A, tile `kid_music_tile` #1B1B28, ink
  `kid_ink` #FFFFFF, `kid_core` #FBE4DC, `kid_core_dim` #C7FBE4DC, accent `kid_accent` #F0A884. `KidPaletteTest` parses
  that file and checks text pairs at >= 4.5:1 (WCAG, a translucent top composited first).
- **The bubbles** (design 77 step 2's fix round, branch `sms-screens` c36366a1, not on `main` yet): `kid_message_out` =
  the accent peach, `kid_message_out_ink` #1B1B1F (8.7:1), `kid_message_out_dim` #5E4034 (4.7:1);
  `kid_message_in` #34324A (D2), white on it 12.3:1, `kid_message_in_dim` #A6A0B4 (4.9:1).
- **Themes** (`res/values/styles.xml`): `launcherBaseTheme` has parent `Theme.AppCompat.DayNight.NoActionBar`, so the
  bits AppCompat draws itself (the `?selectableItemBackground` ripple, `colorControlNormal`, the list divider, an
  unthemed switch) already follow the phone's own dark-theme setting; our colours don't, since they live in `values/`
  only. `UIObject.modifyTheme` lays `colorThemeDefault` (the kid ground and ink) and `Background.SOLID` over every
  `UIObjectActivity`'s theme. The base theme's splash is `kid_ground` without an icon; `windowDisablePreview` is set,
  but the comment there records that Android ignores it for a start from a system surface or the launcher - and our
  app **is** the launcher, so Dagen and Meldinger (new tasks started from Home) open behind a `kid_ground` starting
  window today. It is invisible only because the page has the same ground.
- **No activity but `PinLockActivity` declares `uiMode`** in `configChanges`: a change of the phone's dark theme (or
  Battery Saver) relaunches Home, the time-rule screen and the call screen today.
- **Tasks**: Dagen's three screens (`taskAffinity .day`) and Meldinger's three (`.messages`) have tasks of their own, in
  Recents. The Wi-Fi, Bluetooth and sound-picker lists run in Home's task above kid Settings. Home is `singleTask` with
  `clearTaskOnLaunch`, so Home resumed means nothing is above it in its task. Dagen opens on today after a minute away
  (`DAY_BACK_TO_TODAY_MS`). A thread's unsent text lives only in the field (saved instance state, no stored draft).
- **Hardcoded night** in the page drawables: ripples `#1FFFFFFF`/`#33FFFFFF` (`bg_day_event`, `bg_day_event_now`,
  `bg_kid_back_button`, `bg_messages_button`, `bg_messages_delete`), the back arrow `ic_kid_back` white, the "+" glyph
  `ic_day_add` and the send glyph in `kid_ground` (it means "the ink on the peach", not "the ground"), the badge rings
  in `kid_ground`. `CalendarColor` has one ARGB table ("Dagen draws on the night ground only").
- **What the phone knows about the sun**: the lock weather's answer (`WeatherStore`, design 75) carries only the
  evening's `dark` instant, and only while the parent has weather on and the server has a position. The phone itself
  keeps its newest fix (`LocateCommands.newestKnown`) while the location policy isn't Off. The server's solar function
  (`weather::dusk`, NOAA) is checked by `server/testdata/weather.json`'s `dark` rows, which the launcher already has
  byte-identical in its test resources.

## 2. Which screens

| Screen | Today's ground | In or out | Why |
|---|---|---|---|
| Meldinger: list, "Ny melding", thread (`ui/messages`, `sms-screens`) | `kid_ground`, tiles, D/D2 bubbles | **In** | Its own solid ground; the bubbles prompted the request |
| Meldinger: a received picture (`PictureActivity`) | `kid_ground` | Out | A photo is seen best on dark, as in every gallery; it is a moment, not a page to read |
| Kalender: day view, event, "Ny avtale" (`activity_day`, `activity_event`, `activity_event_edit`) with its date/time pickers (`VibbPickerDialog`) | `kid_ground`, `kid_music_tile` rows | **In** | Its own solid ground; the user named it |
| Wi-Fi networks, Bluetooth devices, ringtone/message-sound picker (opened from kid Settings) | `kid_ground` | **In** | Solid-ground lists; the Wi-Fi password dialog (`KidAlertDialog`) follows its page |
| Contact card (`sheet_contact`, over Home and the phone book) | `kid_ground_lift` | **In** | Its own surface, not the wallpaper; the kid reads names and buttons on it |
| Home (`activity_home`) | the wallpaper (`WallpaperGround`) | Out | The kid's background is the look there (ui-polish.md "Drawing"); the ink follows the wallpaper already |
| Phone book (`activity_phone_book`) | the wallpaper | Out | As Home |
| Kid Settings (`activity_kid_settings`) | the wallpaper | Out | As Home; the new row shows a small preview instead (§8) |
| App drawer (`ui/list`, MIT) | `kid_ground` via `colorThemeDefault` | Out | Home's swipe-up: it belongs with Home; MIT code takes no new GPL code |
| PIN lock and its glance (`PinLockActivity`, `view_lock_glance`) | wallpaper under a black scrim, a black stand-in (design 32) | Out | Night on purpose; design 32's black-or-lock rule must not move |
| Time-rule screen (`LockActivity`) | `kid_ground` | Out | A calm night block screen, the glance's look (round L6) |
| Call screen (`InCallActivity`, `InCallTheme`) | the night gradient | Out | Night on purpose; it must never relaunch in a call |
| Emergency menu (`sheet_emergency`), the 112 confirms | `kid_ground_lift` | Out | Emergency looks the same everywhere, the lock included |
| Boot cover, VoIP wake, the "link blocked" notice, Legal, parent Settings | framework night / `kid_ground` | Out | Not the kid's pages, or seconds long |
| Vibb music (another app) | its own night | Out | Its own app and look; a later question if ever |

## 3. Decisions

**D1 - Three choices: Mørk, Lys, Automatisk. No "Følg telefonen".** Verified: in lock task, Quick Settings has no tiles
at all on real hardware, whatever the feature bits (`docs/notes/launcher/gotchas-and-history.md`, "LOCK_TASK_FEATURE_NOTIFICATIONS
does not bring back Quick Settings tiles"), and Android's Settings app is not on a managed phone's allowlist (the
server never adds it by itself, `device_api::call_and_text_roles` even refuses it). So the phone's dark theme is
whatever the parent left it at during setup - and Android's Battery Saver forces it on by itself (AOSP's
`UiModeManagerService` computes night while power-save is on). "Følg telefonen" would mean "your pages turn dark when
the battery is low", with nothing the kid can see or change. *Why:* the user's "men da må det gi mening".

**D2 - The default is Mørk, and Mørk is exactly today.** The night page tokens are references to today's tokens
(`kid_page_ground` = `@color/kid_ground`, ...), so a phone that never touches the row looks the same pixel for pixel;
`KidPaletteTest` pins each reference (§9 step 1).

**D3 - The scope is §2's "In" rows.** The row's caption says what it covers ("Gjelder Meldinger, Kalender,
kontaktkortet og listene her i Innstillinger. Hjem beholder bakgrunnen din."), and the row shows a small preview, so a
tap in kid Settings - which itself doesn't change - visibly does something. *Why:* the user's "er vel ikke så mye mer
enn meldinger og kalender den egentlig styrer".

**D4 - The light palette is "Vibb dag"** (§4): a warm paper ground with white tiles, the night ink, the PWA's dark
peach for accent text and its lighter peach fill for buttons, the kid's own bubbles peach in both looks. Calm and
adult (the "Launcher look" rule): no new hue, no saturated surface.

**D5 - The mechanism is the per-app night mode plus `values-notnight` tokens** (§5). The rejected alternatives are in
§5.6.

**D6 - Automatisk follows civil twilight at the phone's place** (§6), from a position the phone already holds; the
fixed fallback is 07:00-19:00.

**D7 - A page never changes under the kid's eyes or behind their back.** A change of the applied mode (a "flip")
happens only while no page screen is in front and the phone is unlocked; at that moment the page tasks behind are
closed (§5.4). *Why:* a relaunch in front of the kid is the flash the user doesn't want, a relaunch behind the lock
makes the kid unlock to a screen that moved, and a task left behind would come back through its old-look snapshot.

**D8 - The choice is the kid's and stays on the phone** (§7). No PWA control, no status-report key.

## 4. The palette

### 4.1 Page tokens

New file `res/values/colors_kid_page.xml` (the night, every value a reference to today's token) and
`res/values-notnight/colors_kid_page.xml` (the day). Only page screens use `kid_page_*` (a scan test, §9). Two
colours are the same in both looks and stay unqualified in `colors_kid.xml`: `kid_on_accent` #0C0C14 (the glyph and
text on a peach fill; today's `kid_ground` in that role) and the own-bubble trio.

| Token | Role | Night (= today) | Day | Day contrast (on) |
|---|---|---|---|---|
| `kid_page_ground` | the page | `kid_ground` #0C0C14 | **#F6F2EF** | - |
| `kid_page_tile` | Dagen rows, list rows, the compose field, the event card | `kid_music_tile` #1B1B28 | **#FFFFFF** | 1.11:1 to the ground (iOS's grouped white-on-grey is the same) |
| `kid_page_lift` | contact card, in-screen confirm, dialogs | `kid_ground_lift` #1C1A2A | **#FFFFFF** | the dim behind sets it off |
| `kid_page_control` | a picture's place in a bubble | `kid_music_control` #2A2A3A | **#EAE5E1** | - |
| `kid_page_ink` | titles, text | `kid_ink` #FFFFFF | **#1B1B1F** | 15.4 ground, 17.2 tile |
| `kid_page_core` | the card's name, an event's description | `kid_core` #FBE4DC | **#1B1B1F** | as ink |
| `kid_page_dim` | second lines, times, captions | `kid_core_dim` #C7FBE4DC | **#5E5752** | 6.4 ground, 7.1 tile |
| `kid_page_accent` | start times, links ("Ny melding"), "Ukjent avsender", the unread dot, rings | `kid_accent` #F0A884 | **#A64B25** (the PWA's `--tint`) | 5.2 ground, 5.8 tile, 4.6 received bubble; dot and ring 5.8 (>= 3:1) |
| `kid_page_accent_fill` | the "+", send, a chosen segment, switch on | `kid_accent` #F0A884 | **#E8895E** (the PWA's `--tint-fill`) | `kid_on_accent` on it 7.6 |
| `kid_page_button` | the tonal pill ("Avbryt", "Del", "Melding" on the card) | `kid_sheet_button` #14FFFFFF | **#12000000** | ink 14.7, accent glyph 4.9 |
| `kid_page_back` | the round back disc | `kid_back_button` #1FFFFFFF | **#0F000000** | the arrow is `kid_page_ink` |
| `kid_page_handle` | the card's handle | `kid_sheet_handle` #38FFFFFF | **#33000000** | - |
| `kid_page_divider` | list dividers | `kid_divider` #1AFFFFFF | **#1A000000** | - |
| `kid_page_ripple` | every page ripple | #1FFFFFFF | **#14000000** | - |
| `kid_page_message_in` | a received bubble | `kid_message_in` #34324A | **#E8E4EE** | ink 13.9 |
| `kid_page_message_in_ink` | its text | `kid_ink` #FFFFFF | **#1B1B1F** | 13.9 |
| `kid_page_message_in_dim` | its time | `kid_message_in_dim` #A6A0B4 | **#5E5870** | 5.4 |
| `kid_page_day_mine` | the kid's own events' start | `kid_day_mine` #9FD3A8 | **#2E7D46** | 5.1 tile |
| `kid_page_alert` | "Ikke sendt - trykk ...", "Slett" in Dagen | `kid_missed_text` #FF8A8A | **#B3261E** | 5.9 ground, 6.5 tile |
| `kid_page_danger` / `_text` | the "Slett" pill | `kid_emergency_tonal` #3A1E24 / #FF8A8A | **#FBE3E1 / #A1241C** | 6.2 |
| (unqualified) `kid_message_out`, `_ink`, `_dim` | the kid's own bubble | #F0A884, #1B1B1F, #5E4034 | **the same** | 8.7, 4.7 |
| (unqualified) `kid_call_button`, `kid_badge` | Ring, the badge | #29833B, #B4532C with white | **the same** | 4.8, 5.0 |

The own bubble stays peach in both looks: "my messages are peach" is the one thing the kid learns, and the peach reads
on paper as well as on night (1.8:1 to the day ground as a shape; its text 8.7:1). The received bubble's day value
keeps D2's lilac hint so the two looks are one family.

### 4.2 Kalender's colours

- Start times `kid_page_accent`; the kid's own `kid_page_day_mine`; all-day `kid_page_dim`; "Pågår nå"'s 1.5 dp ring
  `kid_page_accent` (5.8:1 to the white tile; it repeats the words, so colour isn't the only signal).
- **The calendar dots** get a day table beside the night one: `CalendarColor` gains `dayArgb`, each the same hue made
  deep enough for **>= 3:1 on the white tile and the ground** (WCAG 1.4.11 for a graphic): sky #3A76B0 (4.8),
  forest #3C8650 (4.5), sun #A87414 (4.1), berry #C24E48 (4.7), sea #2B8580 (4.4), lavender #9455A2 (5.2), sand
  #8C7650 (4.4), slate #6C6C7A (5.2), own #C2622C (4.1). The parent's PWA swatches stay the night values (the PWA
  shows the name too). The legend's `DotSpan` and the glance row are unaffected (the glance stays night).
- The "+" disc: `kid_page_accent_fill` with the glyph in `kid_on_accent`.

### 4.3 The contact card

Its ground `kid_page_lift`, the name `kid_page_core`, hint and Close `kid_page_dim`, the handle `kid_page_handle`,
"Melding" a `kid_page_button` pill with a `kid_page_accent` glyph, "Ring" the call green with white in both, the
badge's ring `kid_page_lift` (it separates the badge from the avatar; it was `kid_ground`). The soft avatars and their
#1B1B28 initials (>= 8.9:1) need nothing.

### 4.4 Dialogs and pickers on a page

`KidPageTheme` (§5.2) sets `alertDialogTheme` to `KidPageAlertDialog` - `KidAlertDialog` on page tokens - so the
Wi-Fi password dialog and Dagen's delete confirm follow the page. `VibbPickerDialog` gets a day twin in
`values-notnight/styles.xml` with parent `Theme.Material.Light.Dialog` and the same items named: panel
`kid_page_lift`, text `kid_page_ink`, weekday row `kid_page_dim`, header and selection `kid_page_accent_fill` with
`kid_on_accent` (`PickerThemeTest` gains the day file). Every other screen keeps `KidAlertDialog` (night).

## 5. The mechanism

### 5.1 Resources

- `values/colors_kid_page.xml` (night) and `values-notnight/colors_kid_page.xml` (day) - the only qualified colours.
  `notnight` matches when the configuration's night bit is "no"; the launcher always sets that bit explicitly (5.3), so
  the files resolve by the kid's choice, never by the phone's own setting.
- `values/bools_kid_page.xml`: `kid_page_light_bars` false; `values-notnight`: true.
- Every page layout and drawable names `kid_page_*` (or `kid_on_accent` and the unqualified bubble, call and badge
  colours); every literal or night token in them is replaced. Code that sets a colour on a page reads it through the
  activity's context (`getColor(R.color.kid_page_...)`), which resolves in the page's mode.

### 5.2 The page theme

`KidPageTheme` (parent `launcherBaseTheme`), set in the manifest on the eight in-scope activities (`DayActivity`,
`EventActivity`, `EventEditActivity`, `MessagesActivity` - the list and "Ny melding" -, `ThreadActivity`,
`WifiNetworksActivity`, `BluetoothDevicesActivity`, `KidSoundPickerActivity`):
`windowBackground` and `windowSplashScreenBackground` = `@color/kid_page_ground`, `android:colorBackground` the same,
`windowLightStatusBar`/`windowLightNavigationBar` = `@bool/kid_page_light_bars`, `enforceNavigationBarContrast` false,
`alertDialogTheme` = `KidPageAlertDialog`. `UIObject.modifyTheme` lays `colorThemeDefault` (the night ground) over
every `UIObjectActivity`; a page applies a `PageColors` overlay after it, so its `colorBackground` stays the page's.

### 5.3 Who sets the mode

- **The app's night mode** - `UiModeManager.setApplicationNightMode(MODE_NIGHT_YES | MODE_NIGHT_NO)`, never AUTO. The
  system keeps it per package (across reboots and updates) and merges it into every activity's configuration, the
  starting window's included. That is the point: the starting window of a page (the splash Android shows because Home
  launched it) and the bar icons are already in the page's look before our first frame.
- **A page's own mode** - each page sets AppCompat's local night mode to the **applied** mode before
  `super.onCreate` (a belt: if the system call were late or refused, the page's content is still right; with the two
  equal it costs nothing). It does **not** declare `uiMode`, so the system relaunches it when the mode changes - which,
  by D7, happens only when it isn't on screen.
- **Every other activity** declares `uiMode` in `configChanges` (never relaunched by a flip - Home, the time-rule
  screen and the call screen no longer relaunch for Battery Saver either) and pins AppCompat's local night mode to the
  phone's own mode (`Resources.getSystem().configuration`) before `super.onCreate`: AppCompat's own bits on Home, the
  phone book, kid Settings, the drawer and parent Settings stay exactly as today. Their colours are unqualified and
  never move. (`PinLockActivity` already declares `uiMode`; `BootCoverActivity` and the translucent ones use framework
  themes that don't read the night bit.)
- **The contact card** is a dialog over Home or the phone book: it is built on `PageLook.wrap(activity)` - a
  `ContextThemeWrapper` over a configuration context with the applied night bit - so it is right whatever its
  activity's configuration says, and a flip while it is open changes nothing until it is closed.

### 5.4 When a flip happens

The applied mode lives in memory and in CE prefs (`kid_look.applied`). A flip = the resolved mode (§6) differs from
the applied one; it is carried out by `PageLook.applyIfDue`:

1. **May it flip now?** Only if no page screen is resumed (a counter in the page base class), the screen is on and our
   lock isn't showing (`PinLockState`). Pure `mayFlip(pageResumed, interactive, lockShowing)`.
2. If yes: close the page tasks behind - `ActivityManager.appTasks` whose root is `DayActivity` or `MessagesActivity`
   (`finishAndRemoveTask`) - then `setApplicationNightMode`, then store `applied`. If no: mark it pending.
3. **Tried at**: the kid's tap in kid Settings (kid Settings is in front: at once); Home's `onResume`; 1 s after a
   page's `onPause` with the screen on and unlocked (the kid left the page for another app - not for another page,
   which resumes in between, nor for the lock); Automatisk's boundary (a main-thread callback at the instant §6
   computes, re-armed at each process start, day change, time or zone change); and process start only on the first
   run (no `applied` stored yet - the first start after the update sets YES, Mørk).
4. What closing costs: Kalender loses nothing (it opens on today after a minute anyway); Meldinger reopens on its list.
   **A thread's unsent text is kept** per conversation (CE prefs, cleared when sent or emptied) so leaving a half-typed
   message across a flip loses nothing; restored when the thread opens. The Wi-Fi, Bluetooth and sound lists are never
   behind at a flip (they sit above kid Settings in Home's task, and Home resumed means they are gone). Recents loses
   the Kalender and Meldinger cards at a flip.

So: a page open at sunset stays light until the kid leaves it; one left open under the lock all night is still light
when unlocked, and changes when the kid goes Home. At most two flips a day, each where the kid sees Home.

### 5.5 Status and navigation bars

A page draws edge to edge (targetSdk 36) under transparent bars; the icon ink comes from `kid_page_light_bars` in the
theme, so it is right from the starting window on. `KidInsets` is unchanged. Home and the wallpaper screens keep
`WallpaperInk`'s choice.

### 5.6 Rejected

| Alternative | Why not |
|---|---|
| AppCompat's local night mode alone, per page (`values-night` tokens) | The starting window is drawn by the system from the app's configuration before our code runs: in Lys, a `kid_ground` frame before every page - the flash |
| Our own theme attributes (`?attr/kidPageGround`) with a day and a night theme | The same starting-window flash (the manifest theme is fixed), and every drawable and layout rewritten to attributes |
| `SplashScreen.setSplashScreenTheme` | Persisted for the whole package, so it would replace the lock's black stand-in (design 32) and Home's breathing mark |
| The phone's own dark theme (`UiModeManager.setNightMode`) | Needs a privileged permission, and would turn Android's own screens (dialer in a call, permission dialogs) too |
| Recolouring views at runtime | Every adapter and drawable would need a second path; easy to miss one |
| Keeping page tasks behind and relaunching them when shown | Android shows the task's snapshot (the old look) until the relaunch draws - one frame of the other look |

Android's own "force dark" doesn't touch a light page: it applies only while the configuration is night.

## 6. Automatisk

- **The rule**: light while the sun's centre is above **-6°** at the phone's place (civil twilight), dark otherwise.
  The evening boundary is then exactly the lock weather's "Mørkt kl. ..." (design 75 D10), so what the kid reads on
  the lock and what the pages do agree. The morning boundary is the same crossing upward. White nights and the
  midnight sun: light all day; the depth of the polar night: dark all day; Tromsø in December: light for the short
  twilight around noon.
- **Inputs**: the time and the phone's zone (`ZoneId.systemDefault()`), and a position: the newest fix the phone
  already holds (`LocateCommands.newestKnown`), only while the location policy isn't "Off" and never a mock fix. No
  location request is ever made for this, nothing is sent, and no position is stored by this feature - only the next
  boundary's instant is kept in memory.
- **The fallback** (location Off, or no fix yet): light from 07:00 to 19:00 by the phone's clock.
- **Pure** `ui/look/Sun.kt`: `sunAltitude(lat, lon, instant)` (NOAA's solar position, the server's `weather::dusk`
  ported) and `nextCrossing(lat, lon, from, -6.0)` (one-minute steps over at most 36 h, then bisection to the
  second). `ui/look/PageLook.kt`: `resolve(choice, now, zone, position) -> Mode` and `nextBoundary(...)` - the next
  instant `resolve` changes, `null` for Mørk and Lys.
- **When it is evaluated**: at every flip point of §5.4 (cheap: one altitude), and the boundary callback re-armed after
  each evaluation. A boundary while a page is in front is held as pending (D7).
- **What the row says** (§8): "Lys nå · mørk fra kl. 17:02" or "Mørk nå · lys fra kl. 07:41", "Lys hele døgnet i dag",
  "Mørk hele dagen i dag" - from the same function, so the kid can see why.

## 7. Where the choice lives, and the parent

- CE shared prefs `kid_look`: `choice` (`dark` | `light` | `auto`; absent or unknown = `dark`) and `applied` (`dark` |
  `light`). `commit()` on the kid's tap, like the wallpaper pick (`WallpaperStore`, `kid_wallpaper_id`). Before the first
  unlock nothing reads it (no page runs then).
- **The parent sees and sets nothing.** *Why:* the standing rule is that every restriction is liftable; this is not a
  restriction but the kid's preference, like the ringtone. Nothing to lift, nothing to watch, no policy key, no
  status-report key, no contract file, no server or PWA change. A parent who wants to know looks at the phone. (If the
  user wants it visible, the cheap version is one `look` value in the status report shown on About This Phone - a
  contract change in both directories; not proposed.)

## 8. The kid Settings row

- A new section after the background section, before "Tilkobling, skjerm og lyd": heading **Utseende** /
  **Appearance** (`KidSettingsHeading`), a card (`bg_kid_card`) holding a three-way segmented control and a preview,
  and a caption under the card.
- **The control** is design 34 P9's sound control with three segments (`SoundSegments`' track, radius and colours
  through `segmentColours`; each segment >= 48 dp, labels wrap at a large font): **Mørk** / **Dark**, **Lys** /
  **Light**, **Automatisk** / **Automatic**. A `RadioGroup`; TalkBack reads the heading, the label and its state.
- **The preview** (decorative, `importantForAccessibility="no"`): a 56 dp strip with 12 dp corners in the chosen
  look's page ground, with a received bubble ("Hei! Kommer du?" / "Hi! Are you coming?") and an own one ("Ja, nå!" /
  "Yes, now!"), drawn through `PageLook.wrap(context, mode)` so it uses the real tokens. For Automatisk it shows the
  look in force now.
- **The caption** (12 sp, the wallpaper caption's style): "Gjelder Meldinger, Kalender, kontaktkortet og listene her i
  Innstillinger. Hjem beholder bakgrunnen din." / "For Messages, Calendar, the contact card and the lists here in
  Settings. Home keeps your background." Under Automatisk a second line from §6.
- A tap stores the choice and applies at once (kid Settings is in front).
- Strings (en / nb), all new:

| Key | en | nb |
|---|---|---|
| `kid_settings_look` | Appearance | Utseende |
| `kid_settings_look_dark` | Dark | Mørk |
| `kid_settings_look_light` | Light | Lys |
| `kid_settings_look_auto` | Automatic | Automatisk |
| `kid_settings_look_caption` | For Messages, Calendar, the contact card and the lists here in Settings. Home keeps your background. | Gjelder Meldinger, Kalender, kontaktkortet og listene her i Innstillinger. Hjem beholder bakgrunnen din. |
| `kid_settings_look_light_until` | Light now · dark from %1$s | Lys nå · mørk fra kl. %1$s |
| `kid_settings_look_dark_until` | Dark now · light from %1$s | Mørk nå · lys fra kl. %1$s |
| `kid_settings_look_light_all_day` | Light all day today | Lys hele døgnet i dag |
| `kid_settings_look_dark_all_day` | Dark all day today | Mørk hele dagen i dag |
| `kid_settings_look_preview_in` | Hi! Are you coming? | Hei! Kommer du? |
| `kid_settings_look_preview_out` | Yes, now! | Ja, nå! |

  Times by `ClockFormat.formatClock` (the phone's 12/24 h).

## 9. Steps

Each step is one branch, code QA'd and merged before the next. Lean tests: pure functions, the palette, source scans.

**Step 1 - The mechanism, the tokens and Kalender** (needs nothing else). `ui/look/` (`PageLook` choice/applied store,
`applyIfDue`, `mayFlip`, the page base class with the resumed counter, `wrap`), `colors_kid_page.xml` both files, the
bools, `KidPageTheme` and `KidPageAlertDialog`, the day `VibbPickerDialog`; the manifest (pages on `KidPageTheme`,
every other activity `uiMode`); AppCompat's pin on the other activities; Kalender's three screens and their drawables on
page tokens, `CalendarColor.dayArgb`; the kid Settings row with **Mørk and Lys only** (Automatisk hidden until step 3).
Tests: `KidPaletteTest` reads `values-notnight` too - every §4 pair, the dot table >= 3:1 on tile and ground, and each
night `kid_page_*` a reference to today's token; `PageLookTest` (prefs parse, absent/unknown = dark, `mayFlip`'s
table, the tasks a flip closes); `PageManifestTest` (exactly the page activities on `KidPageTheme`, without `uiMode` -
Kalender's three now, the eight of §2 after step 2 -; every other activity with it); `PageTokensScanTest` (the page layouts, drawables and classes name no night
token, no literal colour, no `#..FFFFFF` ripple - an allowlist for `kid_on_accent` and the unqualified bubble, call
and badge colours); `PickerThemeTest` both files. Emulator (`docs/testing/emulator.md`): a 60 fps screen recording of
Home -> Kalender in each look (no frame of the other look, the bar icons right from the first frame), a flip from kid
Settings with Kalender behind (its task gone, the next open clean), screenshots of Home, the phone book, kid Settings,
the drawer, parent Settings, the time-rule screen and the lock before and after the update in the system's light and
dark mode (identical), TalkBack on the row, font scale 1.3.

**Step 2 - Meldinger, the card and the lists** (after design 77 step 2 is merged). The three message screens on page
tokens (bubbles, compose field, send, the delete confirm and its pills, the delivery line, the day separator), a
thread's unsent text kept per conversation, the closing of the Meldinger task in `applyIfDue`; the contact card on
`wrap`; the Wi-Fi, Bluetooth and sound lists; `KidHeader` tints the back arrow with `kid_page_ink` on a page. Tests:
the bubble pairs in `KidPaletteTest`; `PageTokensScanTest` over `ui/messages`, `ui/quickcontrols`,
`KidSoundPicker*`, `ContactSheet`; `MessagesModelTest` for the kept text (stored, restored, cleared on send).
Emulator: both looks of every screen in §2's "In" rows, a half-typed text across a flip.

**Step 3 - Automatisk.** `Sun.kt`, `PageLook.resolve`/`nextBoundary`, the boundary callback and the pending flip, the
row's third segment and its times. Tests: `SunTest` runs every `dark` row of `weather.json` (the shared copy) -
`nextCrossing` from that day's solar noon equals the server's `at` within 60 s, `never` and `all_day` match - plus
dawn cases (Oslo 2026-11-03 and 2026-06-21, Tromsø 2026-12-21, Trondheim 2026-06-21: dawn before solar noon, light
all day, dark all day); `PageLookTest` for `resolve` and `nextBoundary` (the fallback hours, a DST day in each
direction, a zone change, Location Off). Emulator: the clock moved over a boundary with Kalender open (no change), then
Home (changed, the task gone); location Off (the fallback and its caption).

Docs at each step: a new section in `docs/notes/launcher/ui-polish.md` ("The kid's look: Mørk, Lys, Automatisk",
design 78), its line in `launcher/CLAUDE.md`'s index, `docs/testing/emulator.md`.

Sizes, roughly: step 1 ~600 lines of Kotlin and XML (most of it the token swap in Kalender's resources); step 2 ~500;
step 3 ~300 with ~100 of solar maths.

## 10. Risks and what the emulator must show

- **The starting window**: the whole flash-free claim rests on WM merging the per-app night mode into the starting
  window's configuration (it does for per-app locales, the same mechanism). Step 1's recording is the check. If it
  shows a frame of the other look, step 1 stops there and the recording goes back to the user before anything else is
  built: what would be left is a short night frame before a light page, or no light mode.
- **AppCompat's pin on the other screens** must reproduce today exactly: step 1's before/after screenshots in both
  system modes.
- **A flip while the contact card is open** keeps the card's look until it closes (it was built on `wrap`).
- **A crash or update restart** inside a page: the process restarts, the page relaunches in the applied mode (stored
  and in the system) - no flip at a restart except the very first.
- **OEM builds** that ignore `setApplicationNightMode`: the pages are still right (the AppCompat belt), only the
  starting window may be the phone's own look for a moment. The Jelly Star is checked at the first device run.

## 11. Open questions for the user

1. The light ground: warm paper #F6F2EF with white cards (the mockup), or the PWA's cooler grey #F2F2F7?
2. The kid's own bubble stays the same peach in light mode (the mockup). OK?
3. Automatisk without a position (location Off): light 07:00-19:00. Other hours?
4. The contact card over Home follows the choice (white card over a dark Home in Lys). OK, or keep the card night?
5. The parent sees nothing of the choice in the PWA. OK?
6. At a change of look, Kalender and Meldinger leave Recents (they open fresh next time; a half-written message is
   kept). OK?
