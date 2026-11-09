# 78 - A light look for the kid's own screens: Mørk, Lys, Automatisk

Status: design note by the architect, 2026-11-09, on `main` at b9eff6b6, branch `design-78-light`. **Nothing is built.**
Launcher only: no server, PWA or contract change. Mockups: `docs/design/78-mockups.html` (Meldinger and Kalender in
both looks side by side, the contact card - night in both -, the kid Settings row, the token table).
**Revision 2, 2026-11-09**, after the design QA (`docs/review/qa-78-design.md`: 0 Critical, 3 High, 7 Medium, 11 Low,
6 Nit) and the user's answers to the first six questions. Every finding is fixed or explained in §12, which maps each
one to where it landed. New in this revision:
- every non-page screen is pinned to night as a constant (QA H1), and D2 says honestly what that changes;
- a change of look waits on the page tasks being out of sight, counted by started activities, and closes every live
  page (H2, M3);
- nothing touches the choice before the first unlock (H3); the stored mode is re-applied at every unlocked start (M2);
- a throwaway **step 0** on the emulator and the Jelly Star decides go or no-go before anything is built (M7);
- **the contact card stays night** (the user, answer 4) - it is out of scope;
- Automatisk's fallback without a fresh fix is the sun at the phone's time zone's main city (L1, L3), then 07-19.

The user's words (2026-11-09):
- On the own-bubble colour in the new message screens: "burde vi ha en mørk og en lys modus".
- On who decides: "kan ikke barnet selv få bestemme mørk/lys/følge telefonen i barnets settings meny? men da må det gi
  mening da, er vel ikke så mye mer enn meldinger og kalender den egentlig styrer, da bakgrunn settes andre steder?"
- On the proposal (one "Utseende" row in kid Settings with Mørk / Lys / Automatisk, Mørk the default, Automatisk from
  the sun at the phone's place, "Følg telefonen" probably meaningless in the kiosk): "Ja du kan sette igang".
- The answers to revision 1's questions (relayed 2026-11-09): (1) the warm paper ground #F6F2EF; (2) the kid's own
  bubble the same peach in both looks; (3) the 07-19 fallback OK (now the last link of a better chain, §6); (4) the
  contact card **always night** - it sits over Home, which doesn't switch, and a white card would flash white on every
  tap; (5) nothing in the PWA; (6) Kalender and Meldinger leaving Recents at a change of look is OK.

## 0. The shape

- The kid picks **Mørk**, **Lys** or **Automatisk** in kid Settings, in a new "Utseende" card under the background
  picker. **Mørk is the default**, and on Mørk the kid's pages look as today (D2 names the one exception: AppCompat's
  own ripples and controls on a phone whose system dark theme is off).
- The choice changes only the full-screen pages that paint their own solid ground: **Meldinger, Kalender (Dagen), and
  the Wi-Fi, Bluetooth and ringtone lists** opened from kid Settings (§2). Home, the phone book and kid Settings show
  the wallpaper; the contact card, the lock, the glance, the time-rule screen, the call screen and the emergency menu
  stay night.
- **Automatisk** = light while the sun is above -6° (civil twilight - the lock weather's "Mørkt kl. 17:02", design 75
  D10), at the phone's place if it holds a fix under 14 days old, else at its time zone's main city (Europe/Oslo ->
  Oslo), else 07:00-19:00.
- **Mechanism**: the light tokens are Android resources under `values-notnight/`; the launcher sets its own per-app
  night mode (`UiModeManager.setApplicationNightMode`; minSdk is 34) to the kid's resolved mode; every non-page
  activity is pinned to night. **Hypothesis, checked by step 0 before anything is built**: Android uses the per-app
  night mode for a page's starting window and its bar icons, so a page opens in its look from the first frame.
- A page **never changes while it can be seen**: a change waits until no activity of a page task is started, and then
  finishes every live page, so nothing can show a stale frame.
- **Stored on the phone only** (CE prefs, like the wallpaper pick), never read before the first unlock. The parent
  neither sees nor sets it: it is a preference, not a restriction.
- **"Følg telefonen" is dropped**: the kid can't reach Android's dark-theme switch in the kiosk, and Battery Saver
  turns it on by itself (D1).

## 1. What exists today (verified against the code)

- **One night palette**, `res/values/colors_kid.xml` (no `-night` or `-notnight` resource directory exists anywhere in
  the launcher): ground `kid_ground` #0C0C14, lift `kid_ground_lift` #1C1A2A, tile `kid_music_tile` #1B1B28, ink
  `kid_ink` #FFFFFF, `kid_core` #FBE4DC, `kid_core_dim` #C7FBE4DC, accent `kid_accent` #F0A884. `KidPaletteTest` parses
  that file and checks text pairs at >= 4.5:1 (WCAG, a translucent top composited first).
- **The bubbles** (design 77 step 2's fix round, branch `sms-screens`, not on `main` yet): `kid_message_out` = the accent
  peach, `kid_message_out_ink` #1B1B1F (8.7:1), `kid_message_out_dim` #5E4034 (4.7:1); `kid_message_in` #34324A (D2),
  white on it 12.3:1, `kid_message_in_dim` #A6A0B4 (4.9:1). `bg_message_selected_in` is a `kid_accent` ring,
  `bg_message_selected_out` a `kid_core` ring, `bg_message_picture` `kid_music_control`, `bg_messages_unread`
  `kid_accent`.
- **Themes** (`res/values/styles.xml`): `launcherBaseTheme` has parent `Theme.AppCompat.DayNight.NoActionBar`, so the
  bits AppCompat draws itself (the `?selectableItemBackground` ripple, `colorControlNormal`, the list divider, an
  unthemed switch, parent Settings' preference widgets) follow the phone's own dark-theme setting today; our colours
  don't, since they live in `values/` only. The base theme sets `android:textColor*` and `colorAccent` to the kid ink
  and accent, and `UIObject.modifyTheme` lays `colorThemeDefault` (the kid ground, ink, accent) and
  `Background.SOLID` over every `UIObjectActivity`'s theme on every `getTheme()`. The base theme's splash is
  `kid_ground` without an icon; `windowDisablePreview` is set, but Android ignores it for a launch from HOME - and our
  app **is** HOME, so Dagen and Meldinger (new tasks started from Home) open behind a `kid_ground` starting window
  today, invisible only because the page has the same ground. (Found by the QA in passing: `values-v29/styles.xml`
  redefines `launcherHomeTheme` without the `windowSplashScreen*` items, so on minSdk 34 Home's breathing splash is
  never used. Pre-existing, outside this design; for the main session.)
- **Theme references on pages**: `item_wifi_network.xml` and `item_bluetooth_device.xml` colour text and icons with
  `?android:attr/textColorSecondary` and their rows with `?attr/selectableItemBackground(Borderless)`.
- **No activity but `PinLockActivity` declares `uiMode`** in `configChanges`: Battery Saver's dark theme relaunches
  Home, the time-rule screen and even the call screen mid-call today. `PlayLinkBlockedActivity` is a plain `Activity`
  with no theme of its own (the application's `launcherBaseTheme`).
- **Direct boot**: the main process starts before the first unlock (`InCallActivity`, `KidInCallService`, the call
  screening and redirection services and `BootCallReceiver` are `directBootAware`). CE storage is first safe in
  `Application.initUnlocked()` (through `ensureUnlockedInit`: the component factory before a non-direct-boot component,
  and `ACTION_USER_UNLOCKED`).
- **Tasks**: Dagen's three screens (`taskAffinity .day`) and Meldinger's three (`.messages`, `PictureActivity`
  included) have tasks of their own, in Recents. The Wi-Fi, Bluetooth and sound-picker lists run in Home's task above
  kid Settings. Home is `singleTask` with `clearTaskOnLaunch`. Dagen opens on today after a minute away
  (`DAY_BACK_TO_TODAY_MS`). A thread's unsent text lives only in the field (saved instance state, no stored draft).
- **Hardcoded night** in the page drawables: ripples `#1FFFFFFF` (`bg_day_event`, `bg_day_event_now`,
  `bg_messages_button`, `bg_messages_delete`) and `#33FFFFFF` (`bg_kid_back_button`), the back arrow `ic_kid_back`
  white, the "+" glyph `ic_day_add` and the send glyph in `kid_ground` (it means "the ink on the peach", not "the
  ground"). `CalendarColor` has one ARGB table, read as `.argb` by the row dot, the legend's `DotSpan` and the event page.
- **What the phone knows about the sun**: the lock weather's answer (`WeatherStore`, design 75) carries only the
  evening's `dark` instant, and only while the parent has weather on. The phone keeps its newest fix
  (`LocateCommands.newestKnown`, with `mock` and its time) while the location policy isn't Off. The server's solar
  function (`weather::dusk`, NOAA) is checked by `server/testdata/weather.json`'s six `dark` rows, which the launcher
  has byte-identical in its test resources.

## 2. Which screens

The rule: **a full-screen page that paints its own solid ground follows the choice; everything drawn over the
wallpaper or over Home, and everything that is night on purpose, stays night.**

| Screen | Today's ground | In or out | Why |
|---|---|---|---|
| Meldinger: list, "Ny melding", thread (`ui/messages`, `sms-screens`) | `kid_ground`, tiles, D/D2 bubbles | **In** | Its own solid ground; the bubbles prompted the request |
| Meldinger: a received picture (`PictureActivity`) | `kid_ground` | Out (night) | A photo is seen best on dark; it counts as part of the Meldinger task for §5.4 |
| Kalender: day view, event, "Ny avtale" (`activity_day`, `activity_event`, `activity_event_edit`) with its date/time pickers (`VibbPickerDialog`) | `kid_ground`, `kid_music_tile` rows | **In** | Its own solid ground; the user named it |
| Wi-Fi networks, Bluetooth devices, ringtone/message-sound picker (opened from kid Settings) | `kid_ground` | **In** | Solid-ground lists; the Wi-Fi password dialog (`KidAlertDialog`) follows its page |
| Contact card (`sheet_contact`, over Home and the phone book) | `kid_ground_lift` | Out (night) | The user, answer 4: it sits over Home, which doesn't switch, and a white card would flash white at every tap; design 34 P3's "the contact sheet is night, never a white surface" stays |
| Home (`activity_home`), its music and call cards | the wallpaper (`WallpaperGround`) | Out | The kid's background is the look there (ui-polish.md "Drawing") |
| Phone book (`activity_phone_book`) | the wallpaper | Out | As Home |
| Kid Settings (`activity_kid_settings`) | the wallpaper | Out | As Home; the new row shows a small preview instead (§8) |
| App drawer (`ui/list`, MIT) | `kid_ground` via `colorThemeDefault` | Out | Home's swipe-up: it belongs with Home; MIT code takes no new GPL code |
| PIN lock and its glance (`PinLockActivity`, `view_lock_glance`) | wallpaper under a black scrim, a black stand-in (design 32) | Out | Night on purpose; design 32's black-or-lock rule must not move |
| Time-rule screen (`LockActivity`) | `kid_ground` | Out | A calm night block screen, the glance's look (round L6) |
| Call screen (`InCallActivity`, `InCallTheme`) | the night gradient | Out | Night on purpose; it must never relaunch in a call |
| Emergency menu (`sheet_emergency`), the 112 confirms | `kid_ground_lift` | Out | Emergency looks the same everywhere, the lock included |
| Boot cover, VoIP wake, the "link blocked" notice, Legal, parent Settings | framework night / `kid_ground` | Out | Not the kid's pages, or seconds long |
| Vibb music (another app) | its own night | Out | Its own package; the per-app night mode never reaches it |

## 3. Decisions

**D1 - Three choices: Mørk, Lys, Automatisk. No "Følg telefonen".** Verified: in lock task, Quick Settings has no tiles
at all on real hardware, whatever the feature bits (`docs/notes/launcher/gotchas-and-history.md`, "LOCK_TASK_FEATURE_NOTIFICATIONS
does not bring back Quick Settings tiles"), and Android's Settings app is not on a managed phone's allowlist (the
server never adds it by itself, `device_api::call_and_text_roles` even refuses it). So the phone's dark theme is
whatever the parent left it at during setup - and Android's Battery Saver forces it on by itself (AOSP's
`UiModeManagerService` computes night while power-save is on). "Følg telefonen" would mean "your pages turn dark when
the battery is low", with nothing the kid can see or change. *Why:* the user's "men da må det gi mening".

**D2 - The default is Mørk, and what that changes is named.** The night page tokens are references to today's tokens
(`kid_page_ground` = `@color/kid_ground`, ...; two ripple tokens so `#1F` and `#33` stay apart), so our colours on
every page are today's; `KidPaletteTest` pins each reference. Every non-page activity is pinned to night (§5.3), so on
a phone whose system dark theme is **on**, nothing at all changes. On a phone whose system dark theme is **off** (the
likely state after setup), the bits AppCompat draws itself on every screen - the `?selectableItemBackground` ripple,
`colorControlNormal`, unthemed switches and dividers, parent Settings' preference widgets - take their night form
instead of their day form. That is what a night UI should have had all along (a dark ripple on the night ground is
invisible today), and step 1's before/after screenshots show exactly those bits and nothing else.

**D3 - The scope is §2's "In" rows.** The row's caption says what it covers ("Gjelder Meldinger, Kalender og listene
her i Innstillinger. Hjem og kontaktkortet er alltid som før."), and the row shows a small preview, so a tap in kid
Settings - which itself doesn't change - visibly does something. *Why:* the user's "er vel ikke så mye mer enn
meldinger og kalender den egentlig styrer".

**D4 - The light palette is "Vibb dag"** (§4): the warm paper ground #F6F2EF (the user, answer 1) with white tiles,
the night ink, the PWA's dark peach for accent text and its lighter peach fill for buttons, the kid's own bubbles the
same peach in both looks (answer 2). Calm and adult (the "Launcher look" rule): no new hue, no saturated surface.

**D5 - The mechanism is the per-app night mode plus `values-notnight` tokens, every other screen pinned to night**
(§5), provided step 0 confirms the starting window. The rejected alternatives are in §5.6.

**D6 - Automatisk follows civil twilight** (§6): at a fresh fix the phone already holds, else at the time zone's main
city, else 07:00-19:00.

**D7 - A page never changes while it can be seen, nor behind the kid's back.** A change of the applied mode (a "flip")
happens only while no activity of a page task is started and the phone is unlocked; at that moment every live page is
finished and the page tasks are closed (§5.4). *Why:* a relaunch in front of the kid is the flash the user doesn't
want, a relaunch behind the lock makes the kid unlock to a screen that moved, and a page left behind would come back
through its old-look snapshot.

**D8 - The choice is the kid's and stays on the phone** (§7). No PWA control, no status-report key (the user, answer 5).

## 4. The palette

### 4.1 Page tokens

New file `res/values/colors_kid_page.xml` (the night, every value a reference to today's token or today's literal)
and `res/values-notnight/colors_kid_page.xml` (the day). Only page screens use `kid_page_*` (a scan test, §9); since
every non-page screen is pinned to night, a `kid_page_*` reached anywhere else is today's night value anyway. Two
colour groups are the same in both looks and stay unqualified in `colors_kid.xml`: `kid_on_accent` #0C0C14 (the glyph
and text on a peach fill; today's `kid_ground` in that role) and the own-bubble trio.

| Token | Role | Night (= today) | Day | Day contrast (on) |
|---|---|---|---|---|
| `kid_page_ground` | the page | `kid_ground` #0C0C14 | **#F6F2EF** | - |
| `kid_page_tile` | Dagen rows, list rows, the compose field, the event card | `kid_music_tile` #1B1B28 | **#FFFFFF** | 1.11:1 to the ground (iOS's grouped white-on-grey is the same) |
| `kid_page_edge` | a 1 dp hairline on the compose field (an input must be identifiable) | #00000000 | **#24000000** | the field's edge |
| `kid_page_lift` | the in-screen confirm, dialogs | `kid_ground_lift` #1C1A2A | **#FFFFFF** | the dim behind sets it off |
| `kid_page_control` | a picture's place in a bubble | `kid_music_control` #2A2A3A | **#FFFFFF** | stands off the received bubble (1.2:1) and the peach |
| `kid_page_ink` | titles, text | `kid_ink` #FFFFFF | **#1B1B1F** | 15.4 ground, 17.2 tile |
| `kid_page_core` | an event's description and place | `kid_core` #FBE4DC | **#1B1B1F** | as ink |
| `kid_page_dim` | second lines, times, captions | `kid_core_dim` #C7FBE4DC | **#5E5752** | 6.4 ground, 7.1 tile |
| `kid_page_accent` | start times, links ("Ny melding"), "Ukjent avsender", the unread dot, rings, the selected received bubble's ring | `kid_accent` #F0A884 | **#A64B25** (the PWA's `--tint`) | 5.2 ground, 5.8 tile, 4.6 received bubble; dot and ring 5.8 (>= 3:1) |
| `kid_page_accent_fill` | the "+", send, a chosen segment, switch on | `kid_accent` #F0A884 | **#E8895E** (the PWA's `--tint-fill`) | `kid_on_accent` on it 7.6 |
| `kid_page_selected_out` | the selected own bubble's ring | `kid_core` #FBE4DC | **#1B1B1F** | 8.7 on the peach |
| `kid_page_button` | the tonal pill ("Avbryt", "Endre") | `kid_sheet_button` #14FFFFFF | **#12000000** | ink 14.7 |
| `kid_page_back` | the round back disc | `kid_back_button` #1FFFFFFF | **#0F000000** | the arrow is `kid_page_ink` |
| `kid_page_divider` | list dividers | `kid_divider` #1AFFFFFF | **#1A000000** | - |
| `kid_page_ripple` | the rows' and pills' ripple | #1FFFFFFF | **#14000000** | - |
| `kid_page_ripple_strong` | the back disc's ripple | #33FFFFFF | **#1F000000** | - |
| `kid_page_message_in` | a received bubble | `kid_message_in` #34324A | **#E8E4EE** | ink 13.7 |
| `kid_page_message_in_ink` | its text | `kid_ink` #FFFFFF | **#1B1B1F** | 13.7 |
| `kid_page_message_in_dim` | its time | `kid_message_in_dim` #A6A0B4 | **#5E5870** | 5.4 |
| `kid_page_day_mine` | the kid's own events' start | `kid_day_mine` #9FD3A8 | **#2E7D46** | 5.1 tile |
| `kid_page_alert` | "Ikke sendt - trykk ...", "Slett" in Dagen | `kid_missed_text` #FF8A8A | **#B3261E** | 5.9 ground, 6.5 tile |
| `kid_page_danger` / `_text` | the "Slett" pill | `kid_emergency_tonal` #3A1E24 / #FF8A8A | **#FBE3E1 / #A1241C** | 6.2 |
| (unqualified) `kid_message_out`, `_ink`, `_dim` | the kid's own bubble | #F0A884, #1B1B1F, #5E4034 | **the same** | 8.7, 4.7 |

The own bubble stays peach in both looks: "my messages are peach" is the one thing the kid learns, and the peach reads
on paper as well as on night (1.8:1 to the day ground as a shape; its text 8.7:1). The received bubble's day value
keeps D2's lilac hint so the two looks are one family. The tiles stay without an edge (1.11:1, like iOS's grouped
lists); only the compose field, an input, gets the hairline.

### 4.2 Kalender's colours

- Start times `kid_page_accent`; the kid's own `kid_page_day_mine`; all-day `kid_page_dim`; "Pågår nå"'s 1.5 dp ring
  `kid_page_accent` (5.8:1 to the white tile; it repeats the words, so colour isn't the only signal).
- **The calendar dots** get a day table beside the night one: `CalendarColor` gains `dayArgb`, each the same hue made
  deep enough for **>= 3:1 on the white tile and the ground** (WCAG 1.4.11 for a graphic): sky #3A76B0 (4.8),
  forest #3C8650 (4.5), sun #A87414 (4.1), berry #C24E48 (4.7), sea #2B8580 (4.4), lavender #9455A2 (5.2), sand
  #8C7650 (4.4), slate #6C6C7A (5.2), own #C2622C (4.1). **One resolver**, `CalendarColor.argb(context)` (by the
  context's night bit), is the only way `ui/day/` reads a dot colour: the row dot, the legend's `DotSpan` and the event
  page. The glance gets the night value through its pinned context. The parent's PWA swatches stay the night values.
- The "+" disc: `kid_page_accent_fill` with the glyph in `kid_on_accent`.

### 4.3 The contact card

Out of scope (the user, answer 4): it keeps `kid_ground_lift` and every colour of design 34 P3, and `KidPaletteTest`'s
"the contact sheet is night, never a white surface" stays as it is. As a dialog of a pinned (night) activity it
resolves night whatever the kid picks.

### 4.4 Dialogs and pickers on a page

`KidPageTheme` (§5.2) sets `alertDialogTheme` to `KidPageAlertDialog` - `KidAlertDialog` on page tokens - so the
Wi-Fi password dialog and Dagen's delete confirm follow the page. `VibbPickerDialog` gets a day twin in
`values-notnight/styles.xml` with parent `Theme.Material.Light.Dialog`; a qualified style replaces the whole style and
never merges with the unqualified one, so the twin names every item again: panel `kid_page_lift`, text
`kid_page_ink`, weekday row `kid_page_dim`, header and selection `kid_page_accent_fill` with `kid_on_accent`, and the
two picker styles (`PickerThemeTest` reads both files). Every other screen keeps `KidAlertDialog` (night).

## 5. The mechanism

### 5.1 Resources

- `values/colors_kid_page.xml` (night) and `values-notnight/colors_kid_page.xml` (day) - the only qualified colours.
  `notnight` matches when the configuration's night bit is "no". Pages carry the kid's resolved mode; every other
  activity is pinned to night (5.3), so only pages ever resolve the day file.
- `values/bools_kid_page.xml`: `kid_page_light_bars` false; `values-notnight`: true.
- Every page layout and drawable names `kid_page_*` (or `kid_on_accent` and the unqualified bubble colours); every
  literal or night token in them is replaced. Code that sets a colour on a page reads it through the activity's
  context (`getColor(R.color.kid_page_...)`), which resolves in the page's mode.

### 5.2 The page theme and its colour overlay

- `KidPageTheme` (parent `launcherBaseTheme`), set in the manifest on the eight page activities (`DayActivity`,
  `EventActivity`, `EventEditActivity`, `MessagesActivity` - the list and "Ny melding" -, `ThreadActivity`,
  `WifiNetworksActivity`, `BluetoothDevicesActivity`, `KidSoundPickerActivity`): `windowBackground` and
  `windowSplashScreenBackground` = `@color/kid_page_ground`, `windowLightStatusBar`/`windowLightNavigationBar` =
  `@bool/kid_page_light_bars`, `enforceNavigationBarContrast` false, `alertDialogTheme` = `KidPageAlertDialog`.
- **`PageColors`**, a theme overlay applied by the page base class in `getTheme()` after `UIObject.modifyTheme`, on
  every call (`modifyTheme` re-applies `colorThemeDefault` each time): `android:colorBackground`, `android:textColor`,
  `textColorPrimary`, `textColorSecondary`, `textColorHint`, `colorAccent`, `colorPrimary`, `colorControlNormal`,
  `colorControlActivated` and `colorControlHighlight`, all `kid_page_*`. That is what colours the Wi-Fi and Bluetooth
  rows' `?android:attr/textColorSecondary` text and icons and their `?attr/selectableItemBackground` ripples.
  `KidPaletteTest` checks the overlay's text pairs in both files.

### 5.3 Who sets the mode

- **The app's night mode** - `UiModeManager.setApplicationNightMode(MODE_NIGHT_YES | MODE_NIGHT_NO)`, never AUTO.
  The system keeps it per package (across reboots and updates), so the music app never sees it. **Step 0's
  hypothesis**: WM merges it into a page's starting window configuration (Shell draws the splash from it) and the
  splash window's bar ink, so a page is in its look from the first frame. That is the only reason the call exists.
- **A page's own mode** - the page base class sets AppCompat's local night mode to the **applied** mode in
  `attachBaseContext`, before `super.attachBaseContext`, so AppCompat's `attachBaseContext2` builds the override
  configuration from the start: no `recreate` and one `onCreate` per open (step 1's recording confirms it). It is a
  belt: if the system call were late or refused, the content is still right. A page does **not** declare `uiMode`.
- **Every other activity is pinned to night as a constant**: `UIObjectActivity`'s `attachBaseContext` sets AppCompat's
  local night mode to `MODE_NIGHT_YES` (the page base class overrides it), and so do `PinLockActivity` and
  `InCallActivity`, which extend `AppCompatActivity` directly. It reads nothing - there is no reliable public reading of
  the phone's own applied night bit once an app override exists, and a pin to the phone's mode would let shared
  resources (`include_kid_header`, `bg_kid_sheet`, the badges, `bg_kid_card`, `ic_kid_back`) resolve their day values
  on Home, kid Settings, the card or the emergency menu. Every one of them also declares `uiMode` in `configChanges`, so
  a flip never relaunches it (and Battery Saver no longer relaunches the call screen). D2 names what the pin changes.
  `PlayLinkBlockedActivity` (a plain `Activity`) gets an explicit framework night theme like `BootCoverTheme`, so no
  AppCompat light window shows before its content; `BootCoverActivity` and the translucent ones already use framework
  themes that don't read the night bit.

### 5.4 When a flip happens

The applied mode lives in memory and in CE prefs (`kid_look.applied`). A flip = the resolved mode (§6) differs from
the applied one; it is carried out by `PageLook.applyIfDue`, which only ever runs after `initUnlocked()`:

1. **May it flip now?** Only if no activity of a page task is **started** - a counter of `onStart`/`onStop` kept by
   the page base class and by every activity that lives in a page task (`PictureActivity` included, through the same
   counter, without a theme change) - and the screen is on and our lock isn't showing (`PinLockState`). A picture in
   front, a share sheet or another translucent activity over a page all keep the count above zero. Pure
   `mayFlip(pageTaskStarted, interactive, lockShowing)`.
2. If yes: `commit()` the new `applied` first; then finish every **live page** (a registry the page base class keeps:
   the Wi-Fi, Bluetooth and sound lists in Home's task included) and `finishAndRemoveTask` the two page tasks
   (`ActivityManager.appTasks` whose root is `DayActivity` or `MessagesActivity`); then `setApplicationNightMode`. If
   no: mark it pending.
3. **Tried at**: the kid's tap in kid Settings (kid Settings is in front, no page task can be started: at once); Home's
   `onResume`; 1 s after the page-task count drops to zero with the screen on and unlocked (the kid left for another
   app - not the lock, which keeps it from flipping); Automatisk's boundary (a main-thread callback at the instant §6
   computes, re-armed at each evaluation, day change, time or zone change; at the callback the rule is computed again,
   so a callback armed on an older position never flips wrongly).
4. **At every unlocked start** (`initUnlocked`), no flip: `setApplicationNightMode(applied ?: dark)` unconditionally,
   writing `applied = dark` first when it is absent (the first run after the update). Equal to the system's value it
   costs nothing; it repairs a value the system lost (it persists the package configuration asynchronously, so a reboot
   right after a flip can drop it) or a crash between the two writes; and at a first run no page of this process exists
   yet. So a phone is on YES before any page can open, whatever the update window's screen state. Nothing reads
   `kid_look` before the first unlock: the main process runs direct-boot-aware call components then, and a CE read
   there would throw.
5. What finishing costs: Kalender loses nothing (it opens on today after a minute anyway); Meldinger reopens on its
   list; a list in Home's task returns to kid Settings. **A thread's unsent text is kept** per conversation (CE prefs,
   at most 1,600 characters, at most 50 conversations - the oldest dropped -, cleared when sent or emptied, when the
   conversation is deleted, when its contact may no longer text, and when the phone goes unmanaged) and restored when the
   thread opens. Recents loses the Kalender and Meldinger cards at a flip (the user, answer 6).

So: a page open at sunset stays light until the kid leaves it; one left open under the lock all night is still light
when unlocked, and changes when the kid goes Home. At most two flips a day, each where the kid sees Home or another
app.

### 5.5 Status and navigation bars

A page draws edge to edge (targetSdk 36) under transparent bars; the icon ink comes from `kid_page_light_bars` in the
theme, so it is right from the starting window on (step 0 checks the splash's bars). `KidInsets` is unchanged. Home
and the wallpaper screens keep `WallpaperInk`'s choice. (The contact card's bar ink, QA M6, is moot: the card stays
night.)

### 5.6 Rejected

| Alternative | Why not |
|---|---|
| AppCompat's local night mode alone, per page (`values-night` tokens) | The starting window is drawn by the system from the app's configuration before our code runs: in Lys, a `kid_ground` frame before every page - the flash |
| Our own theme attributes (`?attr/kidPageGround`) with a day and a night theme | The same starting-window flash (the manifest theme is fixed), and every drawable and layout rewritten to attributes |
| `SplashScreen.setSplashScreenTheme` | Persisted for the whole package, so it would replace the PIN lock's black stand-in (design 32) |
| The phone's own dark theme (`UiModeManager.setNightMode`) | Needs a privileged permission, and would turn Android's own screens (dialer in a call, permission dialogs) too |
| Pinning the other screens to the phone's own mode | No public reading of it once an app override exists (`Resources.getSystem()` carries the override on 13+), and it would let shared resources resolve day on night screens (QA H1) |
| Recolouring views at runtime | Every adapter and drawable would need a second path; easy to miss one |
| Keeping page tasks behind and relaunching them when shown | Android shows the task's snapshot (the old look) until the relaunch draws - one frame of the other look |

Android's own "force dark" doesn't touch a light page: it applies only while the configuration is night. Android 16's
force-invert accessibility option ("make more apps dark") is documented in AOSP as ignoring an app's `uiMode` override;
if a parent turned it on, Lys pages could be inverted. Step 0 and step 1 check it; nothing is built unless it shows.

## 6. Automatisk

- **The rule**: light while the sun's centre is above **-6°** at the place, dark otherwise. The evening boundary is
  then the lock weather's "Mørkt kl. ..." (design 75 D10), so what the kid reads on the lock and what the pages do
  agree; the morning boundary is the same crossing upward. What the shared `dark` rows say: Oslo at midsummer is dark
  only from 00:29 to the early dawn; Trondheim and Tromsø at midsummer are light all night (`never`); Tromsø on 21
  December has light for the short twilight around noon (dark from 13:53); Longyearbyen on 21 December is dark all day
  (`all_day`).
- **The place, in order** (pure `place(fix, zone, now)`):
  1. the newest fix the phone already holds (`LocateCommands.newestKnown`), only while the location policy isn't
     "Off", never a mock fix, and **at most 14 days old** (under an "on request" policy a fix can be weeks old or from
     a holiday abroad);
  2. else the **main city of the phone's time zone**: each zone's coordinates from tzdata's `zone.tab` (public
     domain; one principal city per zone), Europe/Oslo -> Oslo 59.92 N 10.75 E, Arctic/Longyearbyen -> Longyearbyen.
     Not `zone1970.tab`, which merges Europe/Oslo into Europe/Berlin. A small Kotlin table generated once from the file
     (a header names the tzdata version);
  3. else, a zone not in the table: light 07:00-19:00 by the phone's clock.

  No location request is ever made for this, nothing is sent, and no position is stored by this feature - only the
  next boundary's instant is kept in memory. The zone's city is right to a few minutes in the south of Norway and off
  in the north in winter (Oslo's daylight on a Tromsø phone in December), which is why a fresh fix comes first. The
  lock weather's `dark` instant is not used: it gives only the evening, and the zone's city gives both ends.
- **Pure** `ui/look/Sun.kt`: `sunAltitude(lat, lon, instant)` (NOAA's solar position, the server's `weather::dusk`
  ported) and `nextCrossing(lat, lon, from, -6.0)` (one-minute steps over at most 36 h, then bisection to the
  second). `ui/look/PageLook.kt`: `resolve(choice, now, zone, place) -> Mode` and `nextBoundary(...)` - the next
  instant `resolve` changes, `null` for Mørk and Lys.
- **When it is evaluated**: at every flip point of §5.4 (cheap: one altitude), and the boundary callback re-armed after
  each evaluation; a newer fix is picked up at the next evaluation. A boundary while a page task is started is held as
  pending (D7).
- **What the row says** (§8): "Lys nå · mørk fra kl. 17:02" or "Mørk nå · lys fra kl. 07:41", "Lys hele døgnet i dag",
  "Mørk hele døgnet i dag" - from the same function, so the kid can see why.

## 7. Where the choice lives, and the parent

- CE shared prefs `kid_look`: `choice` (`dark` | `light` | `auto`; absent or unknown = `dark`), `applied` (`dark` |
  `light`) and the kept thread texts (§5.4). `commit()` on the kid's tap, like the wallpaper pick (`WallpaperStore`,
  `kid_wallpaper_id`). Read only from `initUnlocked()`, the pages and kid Settings (a source-scan test); never before the
  first unlock.
- **The parent sees and sets nothing** (the user, answer 5). *Why:* the standing rule is that every restriction is
  liftable; this is not a restriction but the kid's preference, like the ringtone. Nothing to lift, nothing to watch,
  no policy key, no status-report key, no contract file, no server or PWA change.

## 8. The kid Settings row

- A new section after the background section, before "Tilkobling, skjerm og lyd": heading **Utseende** /
  **Appearance** (`KidSettingsHeading`), a card (`bg_kid_card`) holding a three-way segmented control and a preview,
  and a caption under the card.
- **The control** is design 34 P9's sound control with three segments (`SoundSegments`' track, radius and colours
  through `segmentColours`; each segment >= 48 dp): **Mørk** / **Dark**, **Lys** / **Light**, **Automatisk** /
  **Automatic**. A `RadioGroup`; TalkBack reads the heading, the label and its state. At 320 dp a segment is about
  85 dp before padding and "Automatisk" in Nunito 800 14 sp about that wide: step 1 measures it at font scale 1.0 and
  1.3; if it hyphenates ("Automa-tisk"), these labels get `hyphenationFrequency="none"` and wrap whole.
- **The preview** (decorative, `importantForAccessibility="no"`): a 56 dp strip with 12 dp corners in the chosen
  look's page ground, with a received bubble ("Hei! Kommer du?" / "Hi! Are you coming?") and an own one ("Ja, nå!" /
  "Yes, now!"), drawn through `PageLook.wrap(context, mode)` (a `ContextThemeWrapper` over a configuration context
  with that night bit) so it uses the real tokens. For Automatisk it shows the look in force now.
- **The caption** (12 sp, the wallpaper caption's style): "Gjelder Meldinger, Kalender og listene her i Innstillinger.
  Hjem og kontaktkortet er alltid som før." / "For Messages, Calendar and the lists here in Settings. Home and the
  contact card stay as they are." Under Automatisk a second line from §6.
- A tap stores the choice and applies at once (kid Settings is in front). Kid Settings listens to `PageLook`'s changes
  (`PageLook.addListener`, removed in `onStop`), so a boundary or a tap redraws the preview and the line.
- **Release builds show the row from step 2 on** (step 1 changes only Kalender, so its caption would not be true yet);
  debug builds show it in step 1 for testing.
- Strings (en / nb), all new:

| Key | en | nb |
|---|---|---|
| `kid_settings_look` | Appearance | Utseende |
| `kid_settings_look_dark` | Dark | Mørk |
| `kid_settings_look_light` | Light | Lys |
| `kid_settings_look_auto` | Automatic | Automatisk |
| `kid_settings_look_caption` | For Messages, Calendar and the lists here in Settings. Home and the contact card stay as they are. | Gjelder Meldinger, Kalender og listene her i Innstillinger. Hjem og kontaktkortet er alltid som før. |
| `kid_settings_look_light_until` | Light now · dark from %1$s | Lys nå · mørk fra kl. %1$s |
| `kid_settings_look_light_until_12h` | Light now · dark from %1$s | Lys nå · mørk fra %1$s |
| `kid_settings_look_dark_until` | Dark now · light from %1$s | Mørk nå · lys fra kl. %1$s |
| `kid_settings_look_dark_until_12h` | Dark now · light from %1$s | Mørk nå · lys fra %1$s |
| `kid_settings_look_light_all_day` | Light all day today | Lys hele døgnet i dag |
| `kid_settings_look_dark_all_day` | Dark all day today | Mørk hele døgnet i dag |
| `kid_settings_look_preview_in` | Hi! Are you coming? | Hei! Kommer du? |
| `kid_settings_look_preview_out` | Yes, now! | Ja, nå! |

  Times by `ClockFormat.formatClock`; the `_12h` keys (no "kl." before "5:02 PM") when it gives the 12-hour form.

## 9. Steps

Each step is one branch, code QA'd and merged before the next. Lean tests: pure functions, the palette, source scans.

**Step 0 - The go/no-go spike (throwaway branch, never merged). Needs the user for the Jelly Star.** On a branch
`spike-78`: `KidPageTheme` with one `values-notnight` ground and bar bool on `DayActivity`, the
`setApplicationNightMode` call from a debug button, the night pin on `UIObjectActivity`. A 60 fps screen recording of
Home -> Kalender, each look, from Home's tile and from the glance's row, on the Android 16 emulator **and on the Jelly
Star** (a debug build on the test phone, never the kid's - the user runs it or hands over the phone). Pass: not one
frame of the other look, and the status-bar icons dark on the light splash from its first frame. Also noted: what
Android 16's "make more apps dark" does to the light page. If it fails, nothing is built and the recordings go to the
user with the two leftover choices (a short night frame before a light page, or no light mode).

**Step 1 - The mechanism, the tokens and Kalender** (after step 0 passes). `ui/look/` (`PageLook`: choice/applied store,
`applyIfDue`, `mayFlip`, the page base class with the started counter and the live-page registry, the
`attachBaseContext` modes, `wrap`, the listener), `colors_kid_page.xml` both files, the bools, `KidPageTheme`,
`PageColors`, `KidPageAlertDialog`, the day `VibbPickerDialog`; the manifest (pages on `KidPageTheme`, every other
activity with `uiMode`, `PlayLinkBlockedActivity`'s night theme); the night pin on `UIObjectActivity`,
`PinLockActivity`, `InCallActivity`; the unlocked-start re-apply in `initUnlocked`; Kalender's three screens and their
drawables on page tokens, `CalendarColor.dayArgb` and `argb(context)`; the kid Settings row with **Mørk and Lys only**,
debug builds only. Tests: `KidPaletteTest` reads `values-notnight` too - every §4 pair, `PageColors`' pairs, the dot
table >= 3:1 on tile and ground, and each night `kid_page_*` equal to today's token or literal; `PageLookTest` (prefs
parse, absent/unknown = dark; `mayFlip`'s table with a picture in front and a translucent activity over a page; the
pages a flip finishes, a list in Home's task included; `applied` written before the system call; the unlocked-start
re-apply, first run included); `PageManifestTest` (exactly the page activities on `KidPageTheme`, without `uiMode` -
Kalender's three now, the eight of §5.2 after step 2 -; every other activity with it); `PageTokensScanTest` (the page
layouts, drawables and classes name no night token, no literal colour, no `#..FFFFFF` ripple - an allowlist for
`kid_on_accent` and the unqualified bubble colours; `?android:attr/textColor*` allowed because `PageColors` sets it;
no `.argb` read under `ui/day/` outside `CalendarColor`); `PageLookScanTest` (`kid_look` touched only from
`initUnlocked`, the pages and kid Settings; every non-page `AppCompatActivity` pins night in `attachBaseContext`);
`PickerThemeTest` both files. Emulator (`docs/testing/emulator.md`): the step 0 recording again on the real code
(`onCreate` once per open); a flip from kid Settings with Kalender behind (its task gone, the next open clean); before
and after screenshots of Home, the phone book, kid Settings, the drawer, parent Settings, the time-rule screen and the
lock in the system's dark mode (identical) and light mode (only D2's AppCompat bits differ); TalkBack on the row; font
scale 1.3 and the "Automatisk" label.

**Step 2 - Meldinger and the lists** (after design 77 step 2 is merged). The three message screens on page tokens
(bubbles, selection rings, the picture's place, the unread dot, compose field with its hairline, send, the delete
confirm and its pills, the delivery line, the day separator), `PictureActivity` in the started counter, the kept
thread text with its lifecycle, the Meldinger task in the flip; the Wi-Fi, Bluetooth and sound lists; `KidHeader` tints
the back arrow with `kid_page_ink` on a page; the row shown in release builds with its full caption. Tests: the bubble
pairs in `KidPaletteTest`; `PageTokensScanTest` over `ui/messages`, `ui/quickcontrols`, `KidSoundPicker*`;
`MessagesModelTest` for the kept text (stored, restored, cleared on send, on delete, when the contact may no longer
text, when unmanaged; the 1,600-character and 50-conversation caps). Emulator: both looks of every screen in §2's "In"
rows; a half-typed text across a flip; a picture open while a boundary passes (no flip until it is closed and the kid
leaves).

**Step 3 - Automatisk.** `Sun.kt`, the zone table (generated from `zone.tab`, its version in the header),
`PageLook.place`/`resolve`/`nextBoundary`, the boundary callback and the pending flip, the row's third segment and its
lines. Tests: `SunTest` runs every `dark` row of `weather.json` (the shared copy) - `nextCrossing` from that day's
solar noon equals the server's `at` within 60 s, `never` and `all_day` as recorded: Oslo 2026-11-03 at 17:02, Oslo
2026-06-21 at 00:29 the next day, Trondheim and Tromsø 2026-06-21 never, Tromsø 2026-12-21 at 13:53 (twilight at noon),
Longyearbyen 2026-12-21 all day - plus the upward crossing before solar noon on the `at` days; `PageLookTest` for
`place` (a fix 13 and 15 days old, a mock fix, Location Off, Europe/Oslo, a zone not in the table), `resolve` and
`nextBoundary` (a DST day in each direction, a zone change). Emulator: the clock moved over a boundary with Kalender
open (no change), then Home (changed, the task gone); location Off (the zone's city and its caption).

Docs at each step: a new section in `docs/notes/launcher/ui-polish.md` ("The kid's look: Mørk, Lys, Automatisk",
design 78), its line in `launcher/CLAUDE.md`'s index, `docs/testing/emulator.md`.

Sizes, roughly: step 0 ~80 throwaway lines; step 1 ~700 lines of Kotlin and XML (most of it the token swap in
Kalender's resources); step 2 ~450; step 3 ~350 with ~100 of solar maths and the generated zone table.

## 10. Risks and what the devices must show

- **The starting window** (step 0): everything rests on WM merging the per-app night mode into the starting window's
  configuration and the splash's bar ink. Unproven until step 0's recordings; no step 1 before them.
- **OEM builds**: the Jelly Star is in step 0. A build that ignores `setApplicationNightMode` would leave only the
  starting window wrong (the pages are right through the `attachBaseContext` belt) - step 0 shows it.
- **D2's AppCompat bits** on a phone whose system dark theme is off: step 1's screenshots show them; anything beyond
  ripples, control tints, switches and dividers is a finding.
- **A crash or update restart** inside a page: the process restarts, `initUnlocked` re-applies the stored mode, the
  page relaunches in it - no flip at a restart.
- **Android 16's force-invert option**: checked in steps 0 and 1.

## 11. Open question for the user

1. Automatisk without a fix under 14 days old uses the sun at the phone's time zone's main city (Oslo for Norway), and
   07-19 only for a zone not in the table. OK? (Oslo's times are off in the north in winter; a fresh fix comes first.)

Step 0 needs the user: a debug build on the Jelly Star test phone (never the kid's phone) for one recording in each
look.

## 12. Revision 2: the design QA, finding by finding

| Finding | Outcome |
|---|---|
| H1 the pin to "the phone's own mode" | Fixed: every non-page activity pinned to `MODE_NIGHT_YES`, a constant, in `attachBaseContext` (`UIObjectActivity`, `PinLockActivity`, `InCallActivity`) (§5.3); D2 reworded - identical with the system dark theme on, only AppCompat's own bits take their night form with it off; step 1's screenshots in both system modes; the alternative in §5.6 |
| H2 a picture or translucent activity closes the Meldinger task | Fixed: `mayFlip` counts *started* activities of the page tasks, `PictureActivity` included (§5.4.1); `PageLookTest` rows for a picture and a translucent activity |
| H3 CE read before the first unlock | Fixed: the start hook is `initUnlocked()` only (§5.4.4, §7); `PageLookScanTest` |
| M1 AppCompat recreates the page | Fixed: the local mode in `attachBaseContext`, before `super.attachBaseContext` (§5.3); step 1 checks one `onCreate` per open |
| M2 first run waits; `applied` drifts | Fixed: unconditional re-apply of `applied ?: dark` at every unlocked start, `applied` committed before the system call (§5.4.2, §5.4.4) |
| M3 pages in Home's task behind a flip | Fixed: a live-page registry; a flip finishes every live page plus the two page tasks (§5.4.2) |
| M4 theme-attribute colours on pages | Fixed: `PageColors` overlay in the page's `getTheme()` after `modifyTheme` (§5.2); `KidPaletteTest` checks it |
| M5 the legend's dots | Fixed: one resolver `CalendarColor.argb(context)` for the row, the legend and the event page; a scan test (§4.2) |
| M6 the card's bar ink | Moot: the contact card stays night (the user, answer 4) |
| M7 go/no-go after ~600 lines | Fixed: step 0, a throwaway spike on the emulator and the Jelly Star (needs the user); §0 states the hypothesis |
| L1 stale or far fix | Fixed: a 14-day limit, then the zone's city, then 07-19 (§6). The weather's `dark` instant is not used: evening only |
| L2 polar wording | Fixed: every `dark` row named with its outcome (§6, step 3) |
| L3 07-19 in a Norwegian winter | Fixed: the zone's city before 07-19 (§6); the one question left (§11) |
| L4 "Automatisk" width | Step 1 measures it; `hyphenationFrequency="none"` if it breaks (§8) |
| L5 faint shapes | The compose field gets a 1 dp `kid_page_edge` hairline; the picture's place is white in day (§4.1). Tiles stay edgeless, like iOS's grouped lists |
| L6 unnamed `sms-screens` drawables | Named: `kid_page_accent` (received selection ring, unread dot), `kid_page_selected_out` (own selection ring), `kid_page_control` (the picture's place) (§4.1) |
| L7 the white card overturns design 34 | Moot: the card stays night; §2's rule reworded so Home's cards are out by rule |
| L8 `PlayLinkBlockedActivity` | Fixed: an explicit framework night theme (§5.3) |
| L9 step 1's caption untrue | Fixed: release builds show the row from step 2 on (§8) |
| L10 Android 16's force-invert | Added to steps 0 and 1 and §10 |
| L11 kept drafts' lifecycle | Fixed: caps and the four clears, with test rows (§5.4.5, step 2) |
| N1 "API 31+" | Fixed: "minSdk is 34" |
| N2 13.9 | Fixed: 13.7 (note and mockup) |
| N3 one ripple token for two values | Fixed: `kid_page_ripple` and `kid_page_ripple_strong` |
| N4 the strings | Fixed: "døgnet" in both; `_12h` keys without "kl." |
| N5 kid Settings refresh | Fixed: `PageLook.addListener` (§8) |
| N6 `values-v29` shadows Home's splash | Noted in §1 for the main session; §5.6's argument now rests on the PIN lock's black stand-in; §4.4 says a qualified style never merges |
