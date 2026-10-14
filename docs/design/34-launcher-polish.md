# 34 - A quiet polish of the launcher and the music app

Status: proposal (2026-10-14), revised with the user's answers (§6) and the design QA (§7), not built. Visual only: no behaviour, rule, gesture or policy change except where a
proposal says so (flags in §3). Mockups: `docs/design/34-mockups/` (`NN-<screen>-before.png` is the emulator,
`NN-<screen>-after.png` the proposal, both 480×854 = 320×569 dp at 1.5×, the Jelly Star).

Direction (the user's): calm and adult, iOS-like restraint, made for a child of 9 to 13. Never garish, never built to
grab or hold attention. Polish means quiet quality - consistent icons, gentle depth, good type, calm motion, helpful
empty states. Every stock Android gesture keeps working; the music app's behaviour stays palchrb/vibb's.

## 1. What exists

Captured on the emulator (AVD `handy36`), enrolled against a throwaway server: three Home contacts, eleven allowed
apps, a child and a parent PIN, a daily budget for the time-rule screen, six own-file music entries.

**The emulator's density is wrong for the Jelly Star.** The AVD runs 480×854 at 320 dpi = 240×427 dp; the Jelly Star
is 480×854 at 240 dpi = 320×569 dp (what `PinLockLayoutTest`, `FitTest` and `KidHeaderLayoutTest` assume). At 320 dpi
the call screen's Hang up is off the bottom of the screen, the incoming call's buttons are cut in half, the keypad is
cramped and Home's labels ellipsize ("Telefonb…", "Innstillin…"). At `adb shell wm density 240` all of that fits. All
"before" PNGs are at 240. Step 0 (§5) puts `wm density 240` into `docs/testing/emulator.md`. The 240×427 dp results
still matter as a stand-in for a large font scale - P4, P7 and P10 are checked up to font scale 2.0.

| Screen | Good | Off |
|---|---|---|
| Home (`01-home-before`) | Night ground, Nunito, big contacts, clear labels, badge and call-badge logic | **Mustard tiles**: Chrome, Disk, Foto, Kalender, Maps are dark yellow - `tileColor` keeps the vibrant swatch's hue (Google's yellow) and darkens it until white reaches 3:1. **Two icon styles side by side**: white glyph on colour (apps with a monochrome layer) next to full-colour own icons (Kamera, Klokke, Meldinger, YouTube). **Flat**: no depth or press state beyond a rectangular grey ripple over the whole cell. **Loud avatars**: saturated blue/yellow/green 76 dp discs are the brightest things on the phone. The top under the status bar is empty (kept so - the user's answer). The grid is cut hard at its edges (a half row of icons under the status bar when scrolled); the third contact's call badge is clipped at the right edge |
| Drawer (`02-drawer-before`) | A plain list, PIN-gated gear | Upstream µLauncher grey (`#252827`) and accent `#5555FF`, not Vibb night; lists **"Kids Launcher [debug]"** (ourselves) |
| Contact sheet (`03-contact-sheet-before`) | Big avatar, Ring/Melding, clear hint | A **white** sheet over a night UI - a glare at bedtime; Melding is a saturated blue |
| Time-rule screen (`04-time-lock-before`) | Says why, what still works | Three full-width **saturated** buttons (blue, green, red) stacked like an alarm; "6 av 1 minutter brukt i dag"; the clock is `H:mm` here (`6:15`) and `HH:mm` on the PIN lock (`06:15`); the red Nødsamtale and the code button scroll below the fold |
| PIN lock, glance (not mocked) | Calm: clock, date, peach "Lås opp", red text "Nødsamtale" | The 800-weight clock is heavy; otherwise keep |
| PIN lock, keypad (`05-lock-keypad-before`) | Fits 320×569 dp, Foreldrekode quiet | A large **saturated red** emergency pill under the keys, the loudest element on the lock - unlike the glance page's red text link |
| Call screens | Calm gradient, avatar ring, labelled round buttons | Fine at 240 dpi; gets the avatar palette (P3) |
| Kid Settings (`06-kid-settings-before`) | One header, cards, peach switches | Two built-in wallpapers are loud (green `#2B8A3E`; sunset `#F76707→#862E9C`, and sky `#1C7ED6`); the Lyd/Lydløs choice is a filled pill next to an outlined one of another height |
| Music, empty (`07-music-empty-before`) | - | Plain centred text "No library" / "Ingen bibliotek" (and "Ingenting nytt akkurat nå", "Fant ingenting") |
| Music, library (`08-music-library-before`) | Mockup v12: covers, carousel, now-playing bar | At 320×569 dp the **category tiles are cut off by the now-playing bar** (labels "Alle", "Musikk" half hidden); an entry without art is a brown square with ♪ |
| Music, player | Calm, v12 | Keep |
| App name | - | Android shows **"Kids Launcher"** (release) / "Kids Launcher [debug]" in the notification-permission dialog, the ongoing-call notification's header, Settings › Apps and the drawer; the anchor service's ongoing notification says "Kids Device MDM" |
| Clocks | - | Every clock follows Android's 12/24 h setting, which the parent can't choose; the time-rule screen's `H:mm` and the lock's `HH:mm` differ |

## 2. Principles for this product

1. **Low stimulus.** One accent (peach `#F0A884`) and the night ground. Colour fields are mid-dark and muted; the only
   saturated colours left are functional: call green and emergency red, used small. No streaks, badges that pull,
   confetti, bouncing or looping animation. Unread badges stay - a plain number in a small muted terracotta pill (`#B4532C`, white 5.0:1; today's `#D9480F` is
   4.3:1), never animated, never pulsing.
2. **People and apps read differently.** Contacts are soft, light discs with a dark initial (or their photo); apps
   are mid-dark tone discs with a white glyph, unless the parent chose the app's own logo (P1).
3. **Readable on a 3" screen, in sun and at night.** Body text >= 4.5:1, glyphs on tiles >= 4.5:1 (the six tones
   reach 5.3:1 with white), a tile against the ground >= 3:1. No white surfaces at night (no glare). Text scale is
   honoured - labels may wrap, never shrink below the user's size.
4. **Touch targets >= 48 dp**, fixed sizes for round controls (no shrinking buttons).
5. **Motion is feedback, not decoration.** <= 150 ms, decelerate, no overshoot; nothing moves on its own; with
   "Remove animations" on, nothing animates (`ValueAnimator.areAnimatorsEnabled()`). The system's own activity and
   back transitions stay as they are (they carry predictive back).
6. **Nothing new to read.** No new information on screens (no greetings, tips, counters); copy only gets clearer.

## 3. Proposals (each small, separately shippable)

Flags: **[L]** touches the lock screens, **[C]** a policy contract or a shared contract file, **[S]** needs the server
(server and launcher in one commit wherever both are named). No proposal touches a gesture (P2's press feedback is
built so it can't - see there).

**P1 - Calm icon tones and three icon states [C][S].** *Why:* the mustard tiles, the random hue spread, and the
parent's wish to keep an app's real logo. *Tones:* six, mid-dark and muted - slate `#606B80`, blue `#496C9C`, teal
`#347470`, plum `#805B9A`, rose `#A45166`, indigo `#6065A9` (white >= 5.3:1, ground >= 3.5:1; no amber - it read as
brown next to the old mustard; no green or red - those mean call and hang-up/emergency). A new pure
`ui/home/IconTones.kt` `iconTone(swatches, packageName)` replaces `tileColor(seedColour(..))` in
`KidAvatars.renderAppIcon`: a single dominant hue maps to the nearest tone by hue; a multi-hue icon (Google's four
colours) or a grey one gets a tone by a stable hash of the package. The output is always one of the six.

*The three states* (the user confirmed this model):

| State | Stored (`device_app_display` / catalog `display_*`) | Policy `launcher_ui.app_display` entry | Phone draws | PWA control |
|---|---|---|---|---|
| **Vibb style** (default) | no row, or `icon` NULL (a label alone is fine) | absent, or `icon: null` (+ label) | the monochrome glyph in white on its tone; an app without a monochrome layer: its own icon (nothing to tone) | Icon radio "Vibb style" (`value=""`, today's "Its own") |
| **The app's own logo** | `icon = 'own'`, colour ignored (stored `auto`) | `icon: "own"`, `color: "auto"` (+ label if any) | the adaptive icon's background + foreground layers in their original colours, 1.5×, clipped to the circle - never the monochrome layer, no tone, no tint; a legacy bitmap inset 1/8 on `kid_icon_fallback` `#F1F3F5` | Icon radio "The app's own logo" (`value="own"`); the colour fieldset disabled |
| **A chosen glyph** | `icon` = one of the 16 keys, `color` a key or `auto` | `icon: "<key>"`, `color` | the white glyph at 55 % on the colour's tile (`auto` = the app's tone) | the 16 glyph radios + colours, as design 14 |

Existing rows keep their meaning: a stored NULL icon is "Vibb style", nothing is migrated (no CHECK on the column, so
no migration either). The button "Use the app's own" (`action=own`, `DisplayValues::own()`) becomes **"Reset"**: it
deletes the phone's row (device form) or clears the catalog default (catalog form). A catalog default may be `own`
(then every phone with the app shows the logo unless its own row says otherwise).

Server (`app_display.rs`, `handlers/devices.rs`, `handlers/tracked_apps.rs`, `partials/app_display_form.html`,
`static/app-display.js`): a constant `OWN = "own"`; `validate()` and `sanitized()` accept it beside `icon_known` (it is
**not** added to `ICONS`/`app_icons.json`, so the glyph and licence tests stand); `DisplayValues::is_own()` is renamed
`is_empty()` (label and icon `None` - "nothing to send") and `keep_row` decides on that; `entries` keeps
`{label: null, icon: "own"}` with or without a catalog default; `policy_app_display` sends `color: "auto"` for `own`;
`describe()` says "with the app's own logo"; the preview shows "Its own logo" on `OWN_PREVIEW`. Launcher
(`appdisplay/AppDisplay.kt`, `KidAvatars.kt`): `appDisplayMap` keeps `own` before the `AppGlyphs.ICONS` filter;
`renderAppIcon` checks `own` first, before the glyph lookup and the monochrome branch; `appIconKey` gets `g=own`.
An older launcher drops `own` as an unknown icon (design 14 QA #1) - with no label the entry is gone and it draws today's
look. Tests: server `validate`/`sanitized` accept `own`, `entries` keeps it without a default, the wire colour is
`auto`, `describe()`; launcher `appDisplayMap` keeps `own` with and without a label, `appIconKey` contains `g=own`,
an older-launcher shape (`ICONS` without `own`) drops it, `IconTonesTest` (every output one of the six, Google's four
colours never between 30° and 60°, stable per package).

*Parent colours and the fixed tiles (QA M3; part of this step):* design 14's six colour keys stay (stored rows stay
valid), their tiles become tones - peach → rose `#A45166`, plum → plum `#805B9A`, sky → blue `#496C9C`, sunset → indigo
`#6065A9`, night → teal `#347470`, grey → slate `#606B80`, with labels to match in the PWA. `server/testdata/
app_icons.json` and the launcher's test copy change in one commit, `scripts/material-symbols.sh` regenerates
`app_icons.rs` and `AppGlyphs.kt`; `AppGlyphsTest`'s `tileColor(seed) == tile` becomes "tile is one of the tones";
the server's `tiles_carry_white_and_are_neither_green_nor_red` floor goes to 4.5. The Phone book tile
(`kid_phone_book` `#37B24D`, white 2.75:1) becomes a muted call green `#3A7552` (white 5.5:1, ground 3.6:1), the Settings
tile slate, and the 112 avatar (`EMERGENCY_AVATAR` `#E03131`) the tonal pair `#3A1E24`/`#FF8A8A` (6.6:1).

**P2 - Gentle depth and press feedback.** *Depth on the view, not in the bitmap* (QA M2): `tile_icon` and the contact
avatar get `outlineProvider` OVAL, `elevation` 2 dp and a black `outlineSpotShadowColor`; the bitmap cache, its
sizes and the glance/time-rule renders are unchanged. No top light (it cost the glyph contrast). *Press:* the click
stays on the cell (>= 48 dp target); the ripple is the cell's background with an oval mask the icon's size; the icon
scales to 0.94 and darkens 10 % in 90 ms, back in 140 ms - from the cell's `setOnTouchListener` that **always returns
false**, never consumes a DOWN, and restores on `UP`/`CANCEL`, so tap, long-press, the swipe-up and the swipe-left
see the same events as today; no animation when `ValueAnimator.areAnimatorsEnabled()` is false (Robolectric test for
both). *Badges:* kept, `kid_badge` `#B4532C` (white 5.0:1), never animated. *Risk:* low.

**P3 - Soft contact avatars, a night contact sheet.** `AVATAR_PALETTE` (`HomeModel.kt`): `#E8B49A`, `#9FC3E3`,
`#E3CF9A`, `#A9CDB0`, `#C9B3DE`, `#E6AFB4` with ink `#1B1B28` (>= 8.9:1); photos unchanged. The sheet
(`sheet_contact.xml`, `bg_kid_sheet.xml`) on `kid_ground_lift` with a 26 dp top radius. Colours it uses, with where
else they are used (QA M4):

| Resource | Today | Night value | Also used by |
|---|---|---|---|
| `kid_sheet` | `#FFFFFF` (sheet, badge ring `bg_kid_badge_sheet`) | `kid_ground_lift` `#1C1A2A` | - |
| `kid_sheet_ink` | `#1B1B1F` (name) | the sheet's name re-pointed to `kid_core` | Home's window banner (`activity_home.xml`, `bg_home_window_lock_again.xml`) - keeps `kid_sheet_ink` |
| `kid_sheet_muted` | `#495057` (hint, Lukk) | `kid_core_dim` | - |
| `kid_sheet_handle` | `#CED4DA` | white 22 % | - |
| `kid_missed_text` | `#C92A2A` | `#FF8A8A` (7.5:1 on the lift) | - |
| `kid_message_button` | `#1864AB` (Melding) | the sheet's Melding re-pointed to a tonal white 8 % with a peach glyph | the time-rule screen's app buttons (`item_lock_app.xml`) - replaced by P6 anyway |

Ring stays call green. Every pair goes into `KidPaletteTest`. *Stays:* the sheet's buttons, order and `ContactSheet`.

**P4 - Home's edges and labels.** The top stays empty (the user's answer). (a) The grid and the contacts row fade at
their scrolling edges: a small GPL `ui/home/FadingRecyclerView` overrides `getTopFadingEdgeStrength`/
`getBottomFadingEdgeStrength` so one `fadingEdgeLength` (32 dp) gives 12 dp at the top and 32 dp at the bottom (QA L6;
alpha fades, so it works over the system wallpaper). (b) Labels: `maxLines = 2` at every scale (rows wrap, no row
height to compute). (c) The last call badge isn't clipped (`clipChildren=false`, 4 dp end padding). *Stays:* the
swipe-up to the drawer, the swipe-left to Kid Settings, `onResume`'s order.

**P5 - The drawer in Vibb night, text only, without ourselves.** `finnmglasTheme_background_color` → `kid_ground`,
the accent → `kid_accent` (`res/values/colors.xml` - not an MIT directory; the parent Settings share it: peach on
`#252827` is 7.5:1); the list stays text only (the user's default; no per-row icons, so no GPL rendering in the MIT
adapter and no second cache). Our own package leaves the list by one condition in `AppFilter.kidListable`
(`apps/AppFilter.kt`, MIT, already palchrb-modified - the precedent), MIT-licensed like the file, with a test on
`kidListable` (QA L2).

**P6 - The time-rule screen as a calm card [L].** `activity_lock.xml` / `LockActivity`: the clock through P12, 52 sp/700;
a small icon in a peach-tinted disc - hourglass (screen time), moon (bedtime), book (school), a dot (custom); the
hourglass and moon are new converted Material Symbols (`hourglass_bottom`, `bedtime`; `scripts/material-symbols.sh`,
"Converted from" lines, Apache-2.0 already shipped) as `lock_glyph_*`. The sentence for screen time: "Du har brukt
dagens {allowance}." / "You've used today's {allowance}." where allowance = today's budget + today's extra minutes
(`budgetMinutes` in `TimeState` already includes them), formatted by a pure `durationText(minutes)` ("1 t 30 min",
"45 min", "2 t" / "1 h 30 min"...; nb/en, `TranslationsTest`) - the same sentence whatever the overrun (today's "6 av 1").
Then one card of rows - the usable apps (the music app among them under a budget lock) and the phone book - each with a
tone icon, name, chevron and a content description ("Telefonbok, knapp"); at the bottom "Nødsamtale" as a >= 48 dp
red text button with its label, and "Skriv inn opplåsingskode" in core-dim. A pure `timeLockRows(...)` builds the rows
(`LockActivityModelTest`). *Stays:* every target, the emergency flow (`EmergencyDialer` fallback), the code dialog,
Back off, the rules.

**P7 - The keypad's emergency button and the lock's weights [L].** The emergency pill becomes tonal (`#3A1E24` with
`#FF8A8A`, 6.6:1; the pill is only 1.3:1 against the ground - acceptable, its text and glyph identify it); keys get a
1 px top highlight; the glance clock 800 → 700. **Spacing rule:** one column, nothing overlaid - header, dots,
keypad, **>= 16 dp gap**, emergency button (min 48 dp, wraps, grows with the font scale), "Foreldrekode" (min 44 dp),
the inset. Only the keys give way: `PinKeypadLayout` sizes them from the keypad's measured height, `min(72, (h - 3 ×
gap) / 4)` dp, floor 32 (today's rule). `PinLockLayoutTest` (QA M6, N1): it models the button and the link as
`max(minHeight, lines × sp × scale + padding)`; at scale 1.0 and 320×569 dp the keys stay >= 52 dp (the test's 56 dp
floor drops to 52 only if the 16 dp gap needs it - the step records the measured value); at 1.3 >= 48 dp; at 2.0
(Android 14's maximum) the keys may go to the 32 dp floor and below 48 dp touch size - accepted - but never overlap.
Mockups 05 (1.0) and 05b (2.0: the keys give way).

**P8 - Softer built-in wallpapers [S].** Migration `0076_softer_wallpapers.sql`, rows matched by `builtin_key` (never
id): `green` → `kind = 'gradient'`, `["#2A4A4F","#101820"]`, label "Fjord" (QA M1: a `color` row with two colours
fails `checked_colours()` and drops out of the policy); `sky` → `["#34406A","#14141F"]`, "Evening sky"; `sunset` →
`["#5A3A4A","#1A1420"]`, "Dusk". The launcher's `wallpaper_green/_sky/_sunset`: nb "Fjord", "Kveldshimmel",
"Skumring", en "Fjord", "Evening sky", "Dusk". Server test: every built-in row passes `to_policy()` after all
migrations; `WallpaperInkTest` gets the new colours (all white ink, 9.6-10.1:1). A phone that picked one keeps it,
softened. Not [C]: no key or field changes.

**P9 - Kid Settings' sound choice.** One segmented control (white-8 % track, the chosen half peach with night text,
both halves >= 48 dp) as two `RadioButton`s in a `RadioGroup`, so TalkBack reads "Lyd, valgt" (QA L7). *Stays:*
`SoundMode`.

**P10 - The music app's empty states and its fit.** (a) Empty library: a 104 dp night disc with the headphones glyph in
peach (`contentDescription = null`, decorative), "Her kommer musikken din" / "Your music will appear here", "Mamma eller
pappa legger til musikk, podkaster og bøker. De dukker opp her av seg selv."; Nytt and search empty get the disc with
the bell/search glyph and today's text. (b) Fit (QA L1): a `BoxWithConstraints` around the carousel in the weighted
area; the cover is `min(180, maxHeight - the measured title, hint and dots)`, floor 120, passed to `carouselSide`,
`carouselPeek` and `coverDecodePx` (which read `CAROUSEL_COVER_DP` today); below the floor the screen scrolls rather
than overlap. `FitTest` keeps the pure clamp. (c) Covers and the bar get P2's elevation. *Stays:* mockup v12 as the
reference, every behaviour (vibb).

**P11 - The name "Vibb One" [S].** Label "Vibb One", debug "Vibb One (debug)"; packages, the namespace, the admin
component and the `kids-launcher-mdm.apk` asset (the QR's URL and checksum) stay - nothing in provisioning or updates
reads the label, and the music app names the launcher by package (QA L3). Every place:
- `app_name` (`res/values/donottranslate.xml`, `src/debug/...`): Settings › Apps, permission dialogs, the Home-app
  picker, notification headers, Recents.
- `notification_listener_title` "Kids Device MDM" (en, nb): the anchor's ongoing notification → "Vibb One".
- `KidVpnService.setSession("Kid Phone Filter")`: Android's VPN settings and dialog → "Vibb One filter".
- The PWA: About This Phone's row "Vibb" (`device_pages.rs`), "Vibb's Settings" (`device_pages.rs:574`,
  `enroll_code.html`, `device_detail.html`, `device_advanced.html`, `device_lock.html`, `parent_pin.rs`'s event texts),
  `provision_qr.html` ("installs Vibb", "make Vibb the device owner", "Vibb's Settings"), `device_detail.html` ("this
  version of Vibb"), `tracked_apps.rs` ("Vibb didn't install that app") → "Vibb One"; tests follow. The step greps
  `src/` and `templates/` again on the main it is built on (main has moved, `7936e6d7`).
- The catalog row: migration `0077_launcher_row_name.sql` - `UPDATE tracked_apps SET name = 'Vibb One' WHERE
  is_launcher = 1 AND name IN ('Vibb', 'Kids Launcher')`. There is no seed (the parent creates the row), so these two
  are the "default" names (the mockups' and the old runbook's); any other name is the parent's and stays.
- Not changed: "Vibb" in `music_import.rs` (the vibb music project). Docs: `launcher/README.md`, `launcher/CLAUDE.md`,
  the root `CLAUDE.md`; old design docs stay.

**P12 - One clock format on the whole phone, settable from the PWA [C][S].** The user's rule: the status bar and
Vibb One always show the same format. So:
- **Vibb One never has a format of its own.** Every clock it draws follows the phone's system format, exactly as the
  status bar does. A pure `ui/ClockFormat.kt`: `systemIs24(context)` = `Settings.System.TIME_12_24` when it is "24"/"12",
  else the default of the **system** locale (`Resources.getSystem().configuration.locales[0]` - not Vibb One's per-app
  language, which can differ from the status bar's); `timePattern()` ("HH:mm"/"h:mm"), `formatTime(ms)`; TextClocks keep
  `format12Hour="h:mm"`/`format24Hour="HH:mm"` and pick by the system (`ClockFormatTest`). This alone fixes `6:15` vs
  `06:15`. Every formatter goes through it: the glance clock and row times (`GlancePanel`), the time-rule clock and rule
  ends (`activity_lock.xml`, `TimeRules.kt`), the missed-call text (`MissedCallText.kt`), Home's window banner
  (`HomeActivity:656`), the relock notification (`WindowRelock:458`), install mode's "until" (`PlayRuntime:244`), the
  pause's "until" (`SettingsFragmentLauncher:285`). A source-scan test: only `ui/ClockFormat.kt` may call
  `getTimeFormat`/`is24HourFormat`/`getTimeInstance` or spell a time pattern. Durations (call screen, Home's call card)
  and the music app (no clock times) are not clocks.
- **The PWA sets the phone's format.** Home Screen page, launcher form: "Clock: Follow the phone / 24 h / 12 h"
  (default Follow; next to "Phone language" and "Apps per row", the launcher's display settings). On every `apply()`
  (background, `AppEnforcer`'s lock) the launcher writes `Settings.System.TIME_12_24` = "24"/"12" when it differs; with
  "Follow" it writes nothing, except clearing its own earlier value once (pref `clock_written`) so the phone goes back to
  its locale's default. The status bar and Vibb One's clocks then change together (TextClocks and the status bar
  listen to the setting; Vibb One re-renders on `ACTION_TIME_CHANGED`/the next resume).
- **Android's limit:** the device owner can't write it (`setSystemSetting` allows only brightness and the screen-off
  timeout); an app may with the **"Modify system settings"** app-op (`WRITE_SETTINGS`; `TIME_12_24` is a public
  `Settings.System` key), which the device owner can't grant. A one-time grant: `adb shell appops set me.vibb.launcher
  WRITE_SETTINGS allow` at provisioning, next to the role grants (the provisioning runbook in `04-hardening.md` and
  `docs/testing/emulator.md` §4), or the parent during a pause: Android Settings › Apps › Special app access › Modify
  system settings › Vibb One. **Device check:** whether SettingsProvider refuses the write while
  `DISALLOW_CONFIG_DATE_TIME` (ours, set while managed) is on - the launcher sets no other restriction that touches it.
  If it is refused, the write fails, the status says `false` and the PWA shows the warning; lifting the restriction
  around the write is then a separate decision, not part of this step.
- **Status:** `clock_format_applied`: `true` (the system value matches the choice), `false` (it should be applied but
  can't: no app-op - `Settings.System.canWrite` - or the write was refused), `null` ("Follow the phone", or an older
  launcher that doesn't send it). The PWA shows under the control, only when `false`: "The phone can't change its clock
  format yet." with an ⓘ holding the adb command and the Settings path.
- **Contract (both directories, one commit):** policy `launcher_ui.clock_format` = `"system"|"24"|"12"`, always sent;
  status `clock_format_applied` (true/false/null). Server: `migrations/0075_clock_format.sql` (after design 26c's
  `0074_music_spotify.sql`; sqlx applies them in order, so a server with 0075 carries main's 0074) -
  `device_policy.clock_format TEXT NOT NULL DEFAULT 'system' CHECK (clock_format IN ('system','24','12'))`; the form (a
  POST without `clock_format` - an old cached page - keeps the stored value; only a present unknown value is a 400;
  test); the status field read from the stored report like the other optional fields, with the warning test;
  `policy_json_keys_snapshot`'s `ui_keys` gains `clock_format`. Launcher: `LauncherUi.clockFormat: String? = null`, read
  leniently (missing/null/unknown = system; `PolicyResponseCompatTest`); `StatusReportRequest.clockFormatApplied:
  Boolean? = null` with a JSON test.

**P13 - The phone's time zone in the status report [S].** Launcher: `TimeState.zone: String? = null`
(`server/dto/StatusReportRequest.kt`), set by `TimeRulesRuntime.report` to `ZoneId.systemDefault().id`; the test
asserts the **encoded JSON** has `"time_state":{..."zone":"Europe/Oslo"...}` (QA L8; `ServerJson` doesn't encode
defaults, a set value is encoded). No capability, no policy change. Server: nothing to build (`time_state.rs`'s
`TimeReport::parse` reads `zone`, `provable_end` shows the end only when it equals Phone Setup's); one end-to-end test
with a launcher-shaped report (snake_case, a rule active: the zone equal → "Locked until 07:00", another → no end).
`server/testdata/` pins no status shape. One commit.

**Look and motion, everywhere.** The kid screens and the music app are always dark (night); there is no light mode to
design (QA N3). No new screen transitions (the system's stay, with predictive back); new motion is only P2's press;
the sheet slide stays <= 220 ms and off with "Remove animations"; the lock keeps none.

## 4. Mockups (`docs/design/34-mockups/`)

| # | Screen | Shows |
|---|---|---|
| 01 | Home | P1 six tones (Chrome as "the app's own logo", Kamera/Klokke/YouTube without a monochrome layer), the muted Phone book green, P2 depth and the quiet badge, P3 avatars, P4 bottom fade; the top empty |
| 02 | Drawer | P5, text only |
| 03 | Contact sheet | P3 |
| 04 | Time-rule screen | P6 |
| 05 / 05b | PIN keypad at font scale 1.0 / 2.0 | P7's spacing rule; at 2.0 the keys give way |
| 06 | Kid Settings | P8, P9 |
| 07 / 08 | Music, empty / library | P10 |

Sources in `34-mockups/src/` (HTML/CSS, Nunito from `launcher/app/src/main/res/font`, glyphs from the launcher's
drawables by `glyphs.py`; Material Symbols, Apache-2.0). Render: `NODE_PATH=<playwright>/node_modules node
docs/design/34-mockups/src/render.js [chromium]`. The Chrome/Drive glyphs and the own logos are approximations.

## 5. Build steps (in order; one commit each; emulator at `wm density 240`, nb and en)

Server migrations take the next free numbers on the main a step is built on, in this order (0075 clock, 0076
wallpapers, 0077 the catalog row's name - renumber together if main has moved).

0. Docs: `docs/testing/emulator.md` - `adb shell wm density 240` (and `wm density reset`, `settings put system
   font_scale 1.0` after); an `adb install`ed app is held by the relock block `outside_play` until "Allow these apps" on
   the phone's summary in the PWA (expected); P13's zone recipe: read `adb shell getprop persist.sys.timezone` and set
   Phone Setup's zone to it (the launcher forces automatic time zone, so the emulator's network zone wins); §4 gains
   `adb shell appops set $P WRITE_SETTINGS allow` (P12) next to the role grants.
1. **P1 [S+L]**: tones, the three icon states, the parent colours' tiles (+ `app_icons.json` both sides, the generated
   tables), the Phone book/Settings/112 tiles; the tests in P1. Check: Home, glance; an app on each of the three states.
2. **P2 [L]**: elevation, press feedback (tap/long-press/swipes unchanged; animators off), badge colour.
3. **P3 [L]**: avatars, the sheet and its inks (`KidPaletteTest`). Check: Home, phone book, sheet, call screens.
4. **P4 [L]**: `FadingRecyclerView`, two-line labels, the badge clip. Check at font scale 1.0, 1.3, 2.0.
5. **P5 [L]**: drawer colours, `kidListable` (test). Check: drawer, parent Settings.
6. **P12 [S+L]**: migration 0075, the form (missing field kept), the policy key + snapshot, the status field and the
   PWA warning, `ClockFormat` and every formatter, the `TIME_12_24` write, the source scan, the runbook lines. Check:
   each option with and without the app-op; the status bar and every Vibb One clock change together; the device check
   under `DISALLOW_CONFIG_DATE_TIME` (record the result); Vibb One in nb on an en_US system follows the status bar.
7. **P6 [L]**: the time-rule screen (`timeLockRows`, `durationText`, glyphs, `TranslationsTest`). Check: each rule
   kind; 112 with Telecom refusing (emulator.md §6 item 9).
8. **P7 [L]**: keypad + glance (`PinLockLayoutTest` at 1.0/1.3/2.0, `LockGlanceLayoutTest`). Check via the emulator
   console screenshot at 1.0, 1.3 and 2.0.
9. **P8 [S+L]**: migration 0076 + strings (server `to_policy()` test, `WallpaperInkTest`, `TranslationsTest`).
10. **P9 [L]**: Kid Settings (TalkBack reads the choice). Check at 320/360/411 dp.
11. **P10 (music)**: empty states, `BoxWithConstraints` fit (`FitTest`). Check: library empty/full, with and without the
    bar, at 569 dp and font scale 1.0/1.3/2.0; Nytt and search empty.
12. **P11 [S+L]**: label, `notification_listener_title`, the VPN session name, every PWA text (grep again), migration
    0077, docs. Check: the permission dialog, a notification header, the VPN dialog, the PWA's About row and app list.
13. **P13 [S+L]**: `time_state.zone` + JSON test, the server's end-to-end test. Check: "Locked until …" on the phone's
    summary during a bedtime rule.

Every step: launcher/music `./gradlew assembleDebug assembleRelease testDebugUnitTest` (music with
`-PwarningsAsErrors=true`); with [S] also `cargo test`, `cargo fmt --check`, `cargo clippy --all-targets`.

## 6. The user's answers (2026-10-14)

1. Name: "Vibb One" (debug "Vibb One (debug)") - P11; the catalog row renamed when it still has a default name.
2. Icons: the calm tones, six without amber; three states - Vibb style (default), the app's own logo, a chosen glyph -
   P1.
3. Home's top stays empty - P4.
4. Emergency in a muted red is fine - P6, P7 with the spacing rule.
5. Soften green, sky and sunset - P8.
6. Unread badges kept, quiet, never animated - P2.
7. The status bar and Vibb One always show the same clock format, settable from the PWA (Follow / 24 h / 12 h) - P12. The zone in the status
   report - P13.
8. The drawer stays text only - P5.

Nothing is open.

## 7. Design QA (`qa-34-design.md`) - how each finding was handled

- **H1** three icon states as a table (stored, wire, render, control), existing rows keep their meaning, every server
  function and launcher path named, tests listed - P1.
- **M1** `green` becomes `kind = 'gradient'`, rows by `builtin_key`, a `to_policy()` test, `WallpaperInkTest` - P8.
- **M2** depth by `elevation` on the views, the bitmap cache untouched, no top light - P2.
- **M3** the parent colours' tiles mapped onto the tones inside step 1 (contract files, generated tables, tests), Phone
  book `#3A7552`, Settings slate, 112 tonal - P1.
- **M4** the sheet's inks listed with their other uses, night values, the banner keeps `kid_sheet_ink` - P3.
- **M5** clock migration `0075` after 26c's `0074`, the order note - P12, §5.
- **M6** font scale 2.0 in steps 4, 8, 11; `PinLockLayoutTest` models text growth; 48 dp at 1.3, the floor accepted at
  2.0; mockup 05b at 2.0 shows the keys giving way - P7.
- **M7** replaced by the user's rule (one format on the whole phone): Vibb One's clocks always follow the system, the
  PWA setting writes `TIME_12_24` through the app-op, a minimal `clock_format_applied` status with a PWA warning, the
  `DISALLOW_CONFIG_DATE_TIME` device check named; `PlayRuntime` and `SettingsFragmentLauncher` added; a source-scan
  test - P12.
- **L1** the cover from a measured slot (`BoxWithConstraints`), scrolling below the floor - P10.
- **L2** the filter in `AppFilter.kidListable` (MIT, the precedent); the drawer text only - P5.
- **L3** the VPN session name, `provision_qr.html`, "this version of Vibb", `device_pages.rs:574`, "Vibb didn't
  install"; grep again on the current main - P11.
- **L4** hourglass/moon as converted Material Symbols, the sentence's number and formatter, content descriptions - P6.
- **L5** the touch listener never consumes, the oval-masked cell ripple, the top light dropped - P2.
- **L6** a `FadingRecyclerView` for unequal edges; `maxLines = 2` always - P4.
- **L7** radio semantics for the sound choice, the decorative disc, the emergency button's label - P9, P10, P6.
- **L8** the emulator zone recipe; the test asserts the JSON - step 0, P13.
- **L9** amber dropped (six tones); the test asserts the output is one of them - P1.
- **L10** a missing `clock_format` keeps the stored value; test - P12.
- **N1** the keypad floor stated (52 dp at 1.0, 48 at 1.3, 32 at 2.0) - P7.
- **N2** en wallpaper names and the DB labels - P8.
- **N3** always dark, said once - "Look and motion".
- **N4** `wm density reset` / `font_scale 1.0` in step 0; the pill's 1.3:1 noted - P7.
- **N5** no open questions left; the numbering is gone with them - §6.

## Step 3 implementation status (2026-10-14)

P3 built (launcher only, no server or contract change), on branch `design-34-s3`:
- **Avatars** (`HomeModel.kt`): `AVATAR_PALETTE` is the six soft discs with `AVATAR_INK` `#1B1B28` (8.9-11.1:1),
  picked by contact id as before (a contact keeps its index, so its colour moves only from the old to the new hue at
  that index); photos unchanged. Every `KidAvatars.bindContact` surface gets them - Home, the phone book, the sheet, the
  glance rows, the call screen and Home's call card.
- **The 112 avatar** (QA M3): `EMERGENCY_AVATAR` is the tonal pair `#FF8A8A` on `#3A1E24` (6.65:1), built here
  because it is an avatar; step 1 needs only the Phone book and Settings tiles. Decision: the sheet's ring
  (`avatarRingColour`) is the red ink for an emergency number - the dark disc alone is 1.13:1 on the lift (1.29:1 on the ground), so the ring
  keeps 112 recognisable on the sheet; other contacts keep the ring in their colour.
- **The sheet** (QA M4, the table in P3): `kid_sheet` = `@color/kid_ground_lift` (sheet and the missed badge's ring),
  the name `kid_core` (14:1), hint and Close `kid_sheet_muted` = `@color/kid_core_dim` (9.0:1 composited), missed calls
  `kid_missed_text` `#FF8A8A` (7.5:1), handle `#38FFFFFF`, Message the new `kid_sheet_button` `#14FFFFFF` with
  `kid_core` text (11.2:1) and a peach glyph (`app:drawableTint`, 6.9:1); 26 dp top radius. `kid_sheet_ink` stays
  `#1B1B1F` for Home's window banner, `kid_message_button` for `item_lock_app.xml`. Call stays call green; the sheet's
  buttons, order, size and `ContactSheet` are untouched. The mockup's 112 dp avatar without a ring was not taken
  (P3 doesn't ask for it; the 132 dp frame and the ring stay).
- **TalkBack** (L7): nothing new to announce - the avatar views stay `importantForAccessibility="no"`, the glyph is a
  compound drawable, the texts and their order are unchanged.
- **Tests**: `HomeModelTest` (the six colours, ink >= 8.9:1 on each and the discs >= 10:1 on the ground, a colour
  per contact id that name/number don't move, the 112 pair >= 6.6:1 for any id, the ring colour), `KidPaletteTest`
  (the sheet is the lift, the banner keeps its ink, every sheet text >= 4.5:1 with alpha composited, Message's text and
  glyph >= 4.5:1 on the tonal button).
- **Noted** (changed in the fix round, L1): white on `kid_call_button` `#2B8A3E` (Ring) was 4.37:1 - 17 sp
  extra-bold, close to but under AA's large-text size. The unread badge's colour is step 2's.
- **Check on the emulator** (not done here - shared emulator, screenshots later): Home, the phone book (incl. the 112
  tile with no rules), the sheet with and without missed calls, with a photo, with Message, the call screens.

### Step 3 code QA fix round (`qa-34-s3-code.md`, 2026-10-14)

| Finding | Fix |
|---|---|
| L1 Ring's white 4.37:1 | `kid_call_button` `#29833B` (white 4.77:1). `bg_call_green.xml` (answer) and `bg_home_call_card.xml` (Home's call card) use the resource instead of the `#2B8A3E` literal, so there is one green. The card hint's `alpha="0.9"` is gone (white 4.77:1). New `KidPaletteTest` `the call green carries white` |
| L2 the 112 disc has no edge | Coordinator's decision: `KidAvatars.bindContact` draws an emergency number's disc as an oval `#3A1E24` with a 3 dp `#FF8A8A` ring (`EMERGENCY_RING_DP`) on every surface that shows it (Home's row, the phone book's grid and no-rules tile, the sheet). Every other branch restores `bg_kid_circle`, since the phone book recycles views. The sheet's `ring()` keeps `avatarRingColour`: it is not redundant, because a 4 dp ring in the disc's colour would cover the disc's red ring, so for 112 it draws the same red |
| L3 the layout's colours are unpinned | `KidPaletteTest` parses `sheet_contact.xml` (plus `styles.xml` for `KidSheetButton`'s text, and the vectors for an untinted glyph's colour). Every text colour and glyph tint must reach 4.5:1 over its composited ground (`backgroundTint`, or the background's solid, over `kid_sheet`). That is 8 checks: name, missed, Call text and glyph, Message text and glyph, hint, Close. The badge pill is step 2's. Mutation 7 (`sheet_name` on `kid_sheet_ink`) now fails at 1.01:1 |
| N1 "~1.3:1 on the lift" | Corrected to 1.13:1 on the lift, 1.29:1 on the ground |
| N2 `contrast()` dropped alpha | `contrast(top, under)` composites `top` over `under` and asserts that `under` is opaque |
| N3 two homes for the 112 colours | New `kid_emergency_tonal` `#3A1E24` next to `kid_emergency_text`. `KidPaletteTest` keeps `EMERGENCY_AVATAR` equal to that pair, so P7's pill can take the resources |
| N4 where the 112 avatar shows | `launcher/CLAUDE.md` now says: only on Home's contacts row, in the phone book and on the sheet. The call screens show the silhouette for emergency calls, and the glance rows never pass `isEmergency` |
