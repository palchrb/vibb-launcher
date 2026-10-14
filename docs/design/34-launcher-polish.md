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
  version of Vibb"), `tracked_apps.rs` ("Vibb didn't install that app"), the clock warning's ⓘ in `device_pages.rs` (`clock_format_note`: the Settings path's "› Kids Launcher ›" is the label then; its adb line names the package `me.vibb.launcher` and stays; step 6, qa-34-s6-code N3) → "Vibb One"; tests follow. The step greps
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

## Step 2 implementation status (2026-10-14)

P2 is built (launcher only, no server change).

**What was built**
- **Depth** (`ui/home/PressFeedback.depth`): Home's `tile_icon` and the contact avatar (`contact_avatar`, on Home and in the phone book) get an oval `ViewOutlineProvider`, `elevation` 2 dp, and black spot and ambient shadow colours. The bitmap cache, its sizes and the glance/time-rule renders are unchanged. There is no top light. `tile_frame` no longer clips the shadow (`clipChildren=false`). Elevation changes the drawing order among siblings, so the unread/missed badge (the `KidBadge` style) and the green call badge are lifted to the same 2 dp with no outline (no shadow of their own), and they still draw above the icon.
- **Press** (pure `PressMotion`; the view side is `PressFeedback.attach`): scale 0.94 and a 10 % black oval foreground in 90 ms, back in 140 ms, `DecelerateInterpolator`, no overshoot. It runs from the cell's `setOnTouchListener`, which always returns false and restores on UP and CANCEL. There is no animation when `ValueAnimator.areAnimatorsEnabled()` is false.
- **Ripple** (`PressFeedback.ovalRipple`): the cell's background is a `RippleDrawable` (`colorControlHighlight`) whose mask is an oval of the icon's size where the icon sits: 4 dp below the grid cell's top, 10 dp below the contact item's. The click stays on the whole cell.
- **Badge**: `kid_badge` `#B4532C` (white 5.0:1), never animated.
- **Tests** (`PressFeedbackTest`, plain JUnit): `onAction` returns false for 13 actions in every pair order, with animators on and off and with and without the delay; a source check that the one touch listener returns `onAction`'s answer; the 90/140 ms animations; no `animate` call with animators off; the delayed press (a scroll's CANCEL never presses, a quick tap still presses and releases); the constants; the badge colour and its >= 4.5:1 contrast with white.

**Decisions**
- **Plain JUnit, not Robolectric**: the launcher has no Robolectric and adding it means new verified dependencies (trust on first use). Instead the press logic is pure (`PressMotion` with a `Host`) and tested directly: it reads `animatorsEnabled` from the host, and the view side reads `ValueAnimator.areAnimatorsEnabled()`. A `MotionEvent` can't be built in a JVM test, so the listener's "always false" is tested on `onAction` plus a source check that the listener returns exactly its answer.
- **The press waits the tap timeout in scrolling parents** (the grid, the contacts row, the phone book: all of them): this is Android's own pressed-state delay. Without it, every scroll would shrink the icon for a moment before the CANCEL. A tap shorter than the timeout still shows the full press and release on UP. Tap, long-press and swipes are not affected because nothing is consumed.
- **With animations off** the state jumps instead of animating. A quick tap then shows no scale at all, because it ends at rest; the system ripple follows the animator scale itself.
- **The ripple sits under the elevated, opaque icon**, as the design says (the cell's background with an oval mask). What shows is a thin ring while the icon is scaled down; the 10 % darkening is the main visible press. Focus does not show through it at all; the fix round gives focus its own ring (below).
- **The contact sheet's avatar** (`sheet_contact.xml`) gets neither depth nor press: P2 names Home's tiles and the contact avatar, and the sheet is P3's (built in parallel).

**Deferred / not checked**
- The emulator pass (`wm density 240`, nb and en): screenshots of Home and the phone book, and the gesture check: tap, the long-press menu, swipe-up to the drawer, swipe-left to kid Settings, the contacts row's scroll, the grid's scroll, "Remove animations" on. Frame times with the elevated tiles have not been measured.
- P10 (c) (the music app's covers and bar get P2's elevation) is the music app's step 11.

### Step 2 code QA fix round (`qa-34-s2-code.md`, 2026-10-14)

| Finding | What was done |
|---|---|
| M1 the press doesn't follow the finger | The listener passes `PressMotion.inside` (bounds widened by the scaled touch slop, as `View.onTouchEvent`). A MOVE outside releases like a CANCEL and sets `left`, and every MOVE and UP after it is ignored until the next DOWN. So a swipe never carries a pressed icon along, and a lift outside never replays the press. Tests: leaving before the timeout never presses (not even at the lift, not even after coming back in), and leaving a pressed cell releases once. |
| M2 focus invisible under the icon | `cellBackground` adds the cell's foreground: a `StateListDrawable` whose `state_focused` item is a static 2 dp `kid_accent` oval ring 4 dp outside the icon. It sits above the icon, applies to the grid, Home's contacts and the phone book, and is shown only with keyboard/D-pad focus. The press is unchanged. Both doc claims are corrected. |
| L1 ripple rebuilt per bind | `cellBackground` (was `ovalRipple`) builds the ripple and the ring once per (icon size, offset), keyed in the view tag `R.id.press_cell_size`. A rebind with the same metrics returns at once. |
| L2 no reset on recycle | The motion lives in the view tag `R.id.press_motion`. `PressFeedback.reset(cell)` → `PressMotion.reset()` cancels the pending press and jumps to rest. It is called from `onViewRecycled` in `HomeGridAdapter` and `PhoneBookAdapter`, and from the cell's `onViewDetachedFromWindow` (this covers Home's contact row, which is rebuilt with `removeAllViews`). Tested. |
| L3 wiring and z-order unpinned | The pure `PressMotion.tapDelay(scrolling, timeout)` is tested. Source checks pin the view host's `areAnimatorsEnabled()` and `tapDelay(...)` lines. A resource test pins `KidBadge`'s `elevation` = `ELEVATION_DP` with `outlineProvider none`, and the call badge's code. Another pins `item_kid_tile.xml` (no `selectableItemBackground`, `tile_frame` `clipChildren=false`). A wiring test covers the grid (`depth`, `attach(..., scrolling = true)`, `cellBackground`, `reset`), Home's contacts and the phone book (`contact`, `reset`). Re-run: all 4 surviving mutations are now caught (host animators on, host delay 0, `KidBadge` without elevation, the grid without `attach`), as are 3 new ones (MOVE ignored, the phone book's recycle reset gone, focus ring on the wrong state). One mutation survives: the UP's `left` guard removed. It is equivalent, because the MOVE that set `left` already cancelled the pending press and released, so UP has nothing to do; the guard is kept as defence. |
| N1 no-op animators | `PressMotion` tracks its `target` and skips a release to rest from rest (a scroll's CANCEL, a stray UP). The host also returns early when it is already at `to`. Tested. |
| N2 a new DOWN during a quick tap's press | DOWN first releases to rest when `target != 0`, then starts its own (delayed) press. Tested with a fake whose animations don't finish at once. |
| N3 `ClickableViewAccessibility` | `attach` carries `@SuppressLint("ClickableViewAccessibility")` with the reason in its KDoc: it never consumes, so the click goes through `onTouchEvent` → `performClick`, and accessibility clicks don't use touch. `lintDebug` ran: no issue in the changed files. The two `ClickableViewAccessibility` warnings left are the older `homeContactsScroll` listener in `HomeActivity`. Lint is not a gate (`abortOnError = false`). |
| N4 the source check's regex | There is a comment on the listener ("Pinned by PressFeedbackTest: keep it one expression"). The test now also rejects `;`, a newline, `\|\|` and `&&` in the body. |
| N5 the sheet badge | Documented in `launcher/CLAUDE.md`: `kid_badge` also colours `bg_kid_badge_sheet.xml`, so there is one badge colour everywhere. |
| N6 translucent icon backgrounds | Documented (KDoc of `PressFeedback`, `launcher/CLAUDE.md`). An adaptive icon without a monochrome layer and with a translucent background layer shows the shadow as a faint grey disc. This is rare and accepted, and is on the emulator pass's list. |

Tests: `PressFeedbackTest` 17. The full run (`assembleDebug testDebugUnitTest lintDebug -PwarningsAsErrors=true`) passed with 1146 tests in 121 classes.

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

## Step 0 and step 1 implementation status (2026-10-14)

**Step 0** (docs only, its own commit): `docs/testing/emulator.md` §3b - `wm density 240` (320x569 dp, the Jelly
Star) with `font_scale` 1.3/2.0 and the resets (`wm density reset`, `font_scale 1.0`), the `outside_play` block of an
`adb install`ed app and "Allow these apps…" on the phone's summary, the time-zone recipe (`getprop
persist.sys.timezone` into Phone Setup); §4 gains `appops set $P WRITE_SETTINGS allow` (P12) next to the role grants.

**Step 1** (P1, server and launcher in one commit; no migration, the policy shape unchanged - `policy_json_keys_snapshot`
and `PolicyResponseCompatTest` untouched, `app_display` is still a list of `{package_name, label, icon, color}`):

- **Server** (`app_display.rs`): `OWN = "own"`; `validate` and `sanitized` accept it beside `icon_known` (not in
  `ICONS`/`app_icons.json`), always with `auto` (a colour sent or stored with it is dropped); `DisplayValues::own()` is now
  `empty()`, the old `is_own()` is `is_empty()` (what `keep_row` and `policy_app_display` decide on) and `is_own()`
  now means the logo; `policy_app_display` sends `color: "auto"` for `own`; `describe()`: "Chat, with the app's own
  logo", "its own name, with the Vibb-style icon", "the chat icon on rose" / "on the app's tone"; `color_label` names
  design 14's keys by their tones (Rose, Plum, Blue, Indigo, Teal, Slate). The form: Icon radios "Vibb style" (`""`),
  "The app's own logo" (`own`) and the 16 glyphs; the colour fieldset `data-colors`, which `app-display.js` (`?v=3`)
  disables for `own` and which a disabled fieldset then posts nothing for (= `auto`); the preview says "Vibb style" /
  "Its own logo" on `OWN_PREVIEW`, `auto` previews slate (`AUTO_PREVIEW` `#606B80`, was `#868E96`); a hint line says
  what each state is and what Reset does here. "Use the app's own" and "Follow the catalog" are one **Reset**
  (`action=reset`; an older page's `own`/`catalog` do the same): the device form deletes the phone's row (it then follows
  the catalog default, else Vibb style), the catalog form clears the default. "Vibb style" with no name over a catalog
  default keeps an empty phone row (Vibb style on that phone) - the way to override a default back to the tone. Every
  form still posts to its row (`#app-<package>`, `?open=display#display`), so a save lands where it was.
- **Contract files**: `app_icons.json` (both copies) keeps the six keys and their seeds; the tiles are the tones,
  the comment says so; `scripts/material-symbols.sh` regenerated `app_icons.rs` and `AppGlyphs.kt` (its comment lines
  updated; the music tables and glyphs came out unchanged). The server's tile test is at 4.5:1 and checks green/red
  by hue (the old RGB rule called teal `#347470` green).
- **Launcher**: `ui/home/IconTones.kt` (pure) - `ICON_TONES`, `iconTone(swatches, packageName)`, `nearestTone`,
  `hashedTone`, `FIXED_TONES`. `KidAvatars.renderAppIcon` checks `own` first (`drawOwnLogo`: the adaptive layers in
  their own colours 1.5x, or a legacy icon inset 1/8 on `kid_icon_fallback`, clipped to the circle), then a glyph
  (the colour's tile or the app's tone), then Vibb style (the monochrome glyph white on the tone; without one
  `drawOwnLogo`). `tileColor`, the HSL helpers, `seedColour` and `TILE_GREY` are gone. `appDisplayMap` takes
  `KNOWN_ICONS` (glyphs + `own`), `own` always `auto`; `appIconKey` gives `|g=own|c=auto`. `kid_phone_book` `#3A7552`
  (also the time-rule screen's phone book button), `kid_settings_tile` slate, the grid's placeholder slate.
- **Tests**: server `the_apps_own_logo_is_a_third_state`, `every_colour_has_a_tone_name`,
  `the_form_knows_the_three_states`, the tile floor 4.5, `tests::app_display::the_apps_own_logo_is_stored_sent_and_shown`
  (kept without a default and with a name, the wire's `auto`, the form, a catalog default of `own`) and the Reset path
  (incl. an older page's `own`/`catalog`); launcher `IconTonesTest` (six tones, contrast, no amber/green/red, every
  output one of the six, Google's four colours never 30-60°, the nearest hue, stable per package, the fixed apps, the
  parent colours are tones), `AppDisplayTest` (`own` with and without a label, an older launcher's shape drops it,
  `g=own`), `AppGlyphsTest` (tile is a tone), `KidPaletteTest` (Phone book and Settings tiles: white >= 4.5:1, ground
  >= 3.5:1).

**Decisions**
- **Fixed tones for the known multi-colour apps** (`FIXED_TONES`, mockup 01's Drive teal, Photos plum, Gmail rose,
  Calendar blue, Maps indigo; Chrome, the Google app blue; YouTube/YouTube Music rose; Keep indigo). They win over the
  palette for those packages, so Home doesn't depend on which swatch a palette pass finds first; every other
  multi-colour or grey icon gets the package's hash tone, as P1 says.
- **Slate is never a hue's tone** - it is the hash's and the Settings tile's; a blue icon goes to blue or indigo.
- **Vibb style without a monochrome layer draws the app's own logo**, a legacy bitmap included (on the light neutral
  disc, no longer on a darkened copy of its colour) - "its own icon (nothing to tone)" in P1's table, and the two
  states then look the same for such an app.
- **The colour fieldset is disabled by the script only**: a server-rendered `disabled` would, without JS, keep a parent
  who switches from the logo to a glyph from picking a colour.
- **The PWA's own tiles** (Device › Apps, the Apps tab) show a letter for an `own` app, as for Vibb style: the server
  has no logo to show.

**Not checked on the emulator yet**: Home and the glance page with an app in each state (Vibb style with and without
a monochrome layer, the app's own logo, a chosen glyph on a colour and on `auto`); the Phone book and Settings tiles.

### Step 1 code QA fix round (`qa-34-s1-code.md`, 2026-10-14)

| Finding | What was done |
|---|---|
| L1 an older page's "Use the app's own" now reset | The device form's legacy `action=own` keeps its old meaning: `DisplayValues::empty()` through the normal `keep_row` - an empty row over a catalog default (Vibb style on that phone), no row without one. Only `reset` and the old `catalog` delete. The catalog form's old `own` stays "clear the default". `a_catalog_default_reaches_every_phone_and_a_phones_choice_replaces_it` asserts `[]` for `own` with the default present, the default for `catalog`, and the default again after Reset |
| L2 Palette's filter drops orange and brown | `KidAvatars.toneOf` no longer uses androidx Palette: the pure `swatchesOf(pixels)` counts the opaque pixels (alpha >= 128) of the 48 px render per 4-bit colour, and `iconTone`'s own lightness and chroma rules judge black, white and grey. Tests: `#E8772E` and a brown map to rose, an orange disc on transparency is one swatch, half-transparent edges don't count, neighbouring shades share a bucket. The palette dependency stays in the build (no other user; removing it is a build change for another day) |
| L3 the hint promised a white symbol | "Vibb style: a white symbol on a calm colour picked from the app's icon (an app without a plain symbol shows its own logo)" |
| L4 the state order untested | The pure `iconRender(entry, hasMonochrome)` in `IconTones.kt` returns `IconRender.OwnLogo` / `Glyph(res, tile or null)` / `VibbMono`; `renderAppIcon` only switches on it. `IconTonesTest` covers nothing and a name only (with and without a monochrome layer), `own` with a monochrome layer and with a name, a glyph on a colour and on `auto`, an unknown glyph both ways |
| N1 `own_row` dead | Used: `AppDisplayForm::can_reset()` (the phone's own row on the device page, a stored default - the new `has_choice` - on the catalog page) shows Reset and its hint line only when there is something to reset. `reset_is_offered_only_with_something_to_reset` |
| N2 `.app-tile`'s grey | `.app-tile`'s default background is the slate `#606B80` (`AUTO_PREVIEW`); `style.css?v=28` |
| N3 red checks don't wrap | Server: red is within 15° of 0° either side (`hue.min(360 - hue) < 15`; rose at 344.8° is 15.2° off); launcher: `hueDistance(h, 0f) < 15f` |
| N4 the wire's `!is_own()` unreachable | Kept as a guard in a pure `wire_entry`, with `the_wire_sends_auto_for_the_logo_and_vibb_style` building `DisplayValues` directly (`own` and no icon with `sky` go out `auto`, a glyph keeps `sky`) |
| N5 filters partly pinned | `a near-black colour and a non-positive count are no colour`: a dark violet (lightness 0.10, 80 %) beside a small blue is blue; a near-white wash beside a red is rose; a red with a zero or negative count beside a blue is blue (the negative case is the one the `population > 0` check decides) |
| N6 emulator.md's WRITE_SETTINGS line | It says the grant is for P12 (step 6, not built yet; harmless now) and why: `TIME_12_24` isn't on `setSystemSetting`'s list. Kid Settings' brightness does **not** use the app-op - it goes through `setSystemSetting` (`QuickControls.setBrightness`), so the report's "the user today is brightness" isn't so; the line says that instead |

## Step 11 implementation status (2026-10-14)

P10 is built (music app only; no launcher, server or contract change), on branch `design-34-s11`. Visual only: no
behaviour, gesture or text outside the empty states changes.

**What was built**
- **(a) Empty states** (`ui/Widgets.kt` `EmptyState`): a 104 dp night disc (`Palette.card`, P2's 2 dp soft shadow) with
  the glyph in peach (40 dp), the headline (19 sp ExtraBold, `fg`) and an optional body (15 sp Bold, `dim`), centred,
  wrapping, never cut. The library shows the headphones, "Her kommer musikken din" / "Your music will appear here" and
  "Mamma eller pappa legger til musikk, podkaster og bøker. De dukker opp her av seg selv." / "Mum or dad adds music,
  podcasts and books. They show up here by themselves." (new `no_library_body`; `no_library` reworded). Nytt shows the
  bell with today's "Ingenting nytt akkurat nå", search the search glyph with "Fant ingenting". TalkBack (QA L7): the
  disc has no description (the glyph is `Glyph`, `contentDescription = null`), the two texts are one merged node.
  Nothing animates, nothing is tappable.
- **(b) Fit** (QA L1): the weighted spacers around the carousel are gone; the carousel (or the empty state) is the
  `weight(1f)` slot between the header and the category tiles + bar, which keep their own height - so the tiles can't
  slide under the bar at any font scale. In the slot a `BoxWithConstraints` reads the height; the title (21 sp) and hint
  (13 sp) lines are measured with a `TextMeasurer` at the current font scale; `libraryCover(slot, lines)` =
  min(180, slot - lines - 41), floor 120 (`Fit.kt`; the 41 dp is the pager box's 4 dp shadow margin, two 8 dp gaps,
  the 8 dp dots and the title block's 3 + 10 dp). Below the floor the slot scrolls vertically (`libraryScrolls`; the
  scroll is disabled otherwise). The cover feeds the pager's page size, `carouselSide`, `carouselPeek`, the new
  `carouselPull` (24 dp at 180) and `carouselGlyph` (84 dp at 180). The modelled Jelly Star (320×569 dp, status 24 dp,
  nav 24/48 dp): 180 dp at font scale 1.0, ~174 dp at 1.3, the 120 dp floor at 2.0 with three-button navigation and the
  bar (a scroll of under 20 dp), no scroll at 2.0 with gesture navigation.
- **(c) Soft shadows**: the centred cover keeps v12's 12 dp, a neighbour has P2's 2 dp, the elevation follows the page
  offset in the cover's `graphicsLayer` (was 12 dp or nothing, switched at the page change); the now-playing bar and the
  empty-state disc get 2 dp. Shadows are black (Compose's default spot/ambient).

**Decisions**
- **The title block keeps the progress line's room** (3 + 3 + 4 dp, a `Spacer` when there is no progress): otherwise
  the cover would resize by 10 dp whenever the carousel turns between an entry with and one without progress.
- **The lines are measured on "Ag"**, not the shown title: they are one line each (`maxLines = 1`), so the height is the
  style's, and the cover doesn't change with the entry.
- **`coverDecodePx` is unchanged**: it already decodes at the largest size a cover is shown (the player's 224 dp), and
  the library and the player share one decoded bitmap per hash; decoding the library's smaller cover separately would
  double the memory. The design's "pass it to `coverDecodePx`" is therefore not needed.
- **The empty library keeps search and Nytt in the header** (mockup 07 shows only Back): hiding them is a behaviour
  change. Mockup 08's other differences (the "Alle" glyph, a cover without art without the ♪) are not in P10 and were not built.
- **Search's empty state starts from the top** (24 dp under the field), not centred: the search screen opens with the
  keyboard up (`adjustResize`), so a centred disc would jump when the keyboard goes. Nytt's is centred in its room.
- **The English body** says "Mum or dad", matching the Norwegian "Mamma eller pappa".
- **Tests are plain JUnit** (the music app has no Compose UI test harness): the pure `libraryCover`/`libraryScrolls`/
  `librarySlotContent`, a model of the library column at 320×569 dp for font scales 1.0/1.3/2.0 with and without the bar
  and for both navigation modes (Nunito's line = 1.364 × sp × scale from its hhea metrics, linear: an upper bound for
  Android 14's curve), the carousel at the floor (the neighbours still peek >= 46 dp), and source checks of the wiring
  (`FitTest` +5); `EmptyStateTest` (3: the words in nb and en, no source named, each screen's glyph, the decorative
  disc, the merged texts, nothing moving). The existing `SourceScanTest` keeps "Spotify" out of every string.

**Deferred / not checked**
- The emulator pass (`wm density 240`, nb and en): the library empty and full, with and without the bar, at font scale
  1.0, 1.3 and 2.0 (the tiles fully above the bar, the scroll at 2.0); Nytt and search empty; TalkBack on the empty
  states; the shadows' look on the night ground.

**Layout vs mockup 08 (qa-34-s11-code N3).** The carousel is centred in its slot, and the tiles sit 8 dp above the bar
(v12's layout). Mockup 08 puts the tiles just under the dots and leaves the spare room above the bar. Both are calm;
v12 stays the reference. Without the bar, the room it would take stays free (N2), so the carousel centres in the
taller slot.

### Step 11 code QA fix round (`qa-34-s11-code.md`, 2026-10-14)

| Finding | What was done |
|---|---|
| L1 2 px overflow at density 1.5 | The cover is computed in whole pixels: `libraryFit(slotPx, linesPx, reservePx, density)` takes the slot from `constraints.maxHeight`, the two lines as measured px, and `libraryFixedPx(density)` rounds each fixed part as `Dp.roundToPx` does (63 px at 1.5, not 61.5). The pager box is `coverPx + roundToPx(4 dp)`, and the reserved progress spacer is `progressRoomPx` (the line's 3 + 4 dp, each rounded). The slot scrolls only when the content in pixels is taller than the slot. `FitTest` sweeps every slot from 150 to 420 dp in pixels, at densities 1.5, 2, 2.625 and 3 and font scales 1.0, 1.3 and 2.0. It sums the drawn parts independently: without a scroll the content is never a pixel taller than the slot, and a scroll happens only at the floor. |
| L2 source checks pinned to formatting | Both tests now read the sources with whitespace collapsed. The checks that remain carry meaning: one weighted slot (only the header's weighted spacer), the tiles and bar unweighted, `libraryFit`, one `verticalScroll` and only under `fit.scrolls`, the progress room kept, no `180.dp`/`184.dp`, the shadow's direction, the bar's shadow, the merge, no description, nothing moving. The exact `measure(...)` literals, modifier chains and the like are gone. |
| N1 the cover's shadow cut at the slot's top | `verticalScroll` is applied only when `fit.scrolls`, so above the floor nothing clips the slot. |
| N2 180↔174 jump when the bar comes or goes | Chose the smaller, stable size: without the bar, its room (`nowBarRoomPx`: 6 + max(44 dp, its two measured lines) + 6, and the 8 dp gap) is kept out of the cover's room. The cover is the same with and without the bar at every density, scale and nav mode (`FitTest`), and the spare room centres. |
| N3 the centring vs mockup 08 | Recorded above. |
| N4 `heightIn(min = maxHeight)` with an unbounded height | In both `EmptyState` and the carousel, `heightIn` (and `EmptyState`'s `verticalScroll`, which would throw too) is applied only when `constraints.hasBoundedHeight`. An unbounded carousel slot counts as `Int.MAX_VALUE`, so the cover is 180 and there is no scroll. |
| N5 tautological asserts | Gone. The Jelly Star test asserts the numbers: 270 px (180 dp) at 1.0, 250-269 px with no scroll at 1.3, no scroll at 2.0 with gestures, and at 2.0 with three buttons and the bar the 180 px floor with a scroll of 1-30 px (19). |
| N6 m9 survived; m8 | `EmptyStateTest` parses each `EmptyState(...)` call: the library and Nytt are centred (the default), search is `centred = false`, and `centred` maps to `Arrangement.Center`/`Top`. The font-scale key of `measuredLinesPx` stays. It is harmless, because the activity has no `configChanges` and is recreated on a font-scale change; its KDoc says so. |

## Step 4 implementation status (2026-10-14)

P4 is built (launcher only; no server, policy or contract change), on branch `design-34-s4`. The top stays empty:
no date line, nothing new above the contacts.

**What was built**
- **(a) Fading edges** (pure `ui/home/HomeEdges.kt`; view `ui/home/FadingRecyclerView.kt`): Home's grid is a
  `FadingRecyclerView` with `requiresFadingEdge="vertical"` and the one `fadingEdgeLength` of 32 dp
  (`FADE_LENGTH_DP`). `getTopFadingEdgeStrength`/`getBottomFadingEdgeStrength` return `fadeStrength(canScroll, edge)`:
  12/32 at the top (so 12 dp), 1 at the bottom (32 dp), 0 at an edge the grid can't scroll past (QA L6). The grid
  keeps `clipToPadding=false` with its 16 dp bottom padding, so the view also overrides the padding offsets as
  AbsListView does. Otherwise View.draw would fade above the padding and the row would show again, unfaded, inside it.
  It is an alpha fade, so it works over the system wallpaper. The view adds no touch handling. The contacts row (a
  `HorizontalScrollView`, not a RecyclerView) uses Android's own horizontal fade at 16 dp (`CONTACTS_FADE_DP`), which is
  0 at an edge it can't scroll past, so a row that fits never fades.
- **(b) Labels**: `tile_label` `maxLines="2"`, `ellipsize="end"`, at every scale (`GRID_LABEL_MAX_LINES`); no code sets
  lines. GridLayoutManager makes a row as tall as its tallest tile; the icons stay top-aligned.
- **(c) The call badge**: the clip was the row's width, not `clipChildren`. Each Home contact was `itemDp` = avatar +
  gap = 104 dp wide, so three made 312 dp in the Jelly Star's 288 dp content. The `fillViewport` row then scrolled by
  24 dp, and the third avatar (278-302 dp) and its badge were cut at the edge (mockup 01-before). Now a row that fits
  gives each item `contactItemDp = min(itemDp, content / count)`, in whole pixels rounded down (`contactItemPx`, used for
  the item and the name's `maxWidth`): 96 dp on the Jelly Star, which matches mockup 01-after's thirds. The third
  badge's right edge is at 282 of 288 dp. Wider screens keep 104 dp. `contactRoomDp(avatar)` = avatar + 2 × 4 dp is the
  badge's 4 dp overhang (frame +12, `marginEnd` 2) and P2's focus ring (`PressFeedback.FOCUS_RING_GAP_DP`, now
  `internal`). Every item keeps that room, and `contactRow`'s 64 dp centred case also requires it (in practice this
  only matters for one contact below 72 dp of content). Scrolling rows are unchanged.

**Decisions**
- **No "4 dp end padding"**: once the row fits, the badge sits inside its item with 6 dp to spare. Padding would only
  have moved the overflow. `clipChildren=false` was already on the item and its frame (step 2).
- **The swipe rule's input changes; its outcome is for the emulator pass** (corrected in the fix round, qa-34-s4-code
  M1). `onFling`'s guard - "a left swipe that starts in a contacts row that can still scroll stays a scroll" - reads
  `canScrollHorizontally(1)`, which is now false for three contacts on the Jelly Star (the row no longer overflows). But
  the guard doesn't run on the swipe's own start: a DOWN on a contact goes to the clickable item, so the gesture
  detector never sees it and `onFling` decides with the last DOWN it was fed - usually an earlier grid touch, or none
  on Home's first gesture - and compares coordinates of two different views. That is a design 08 bug, older than this
  step and not changed by it. So whether a left swipe from a contact opens kid Settings depends on that stale DOWN,
  with three contacts as with five; a swipe that starts in the row's empty space (one or two contacts) gives the
  detector its DOWN and works as the rule says. The fix (one observe-only feed in `dispatchTouchEvent`, in screen
  coordinates) changes which swipes open Settings, so it waits for the user's answer.
- **The contacts row fades 16 dp, not 32**: its peek is half a 64 dp avatar (32 dp), and a 32 dp fade would make
  the peek invisible.
- **Tests are plain JUnit** (`HomeEdgesTest`, 9; 10 after the fix round). They cover the fade strengths and lengths in
  px at densities 1.5-3, and the layout's fading attributes. A source check confirms the view scales each edge, fades
  over its padding and touches no touch event. The label rule is checked in the XML and by a scan for line-count code in the adapter. A row
  height model (`tileHeightDp`) runs at font scale 1.0/1.3/2.0 in 3 and 4 columns at 240/288/360 dp: rows grow with
  the scale, two lines at most, and the bottom fade is shorter than a tile. On the Jelly Star (both navigation modes,
  three contacts with two-line names), a whole row plus the bottom fade fits even at 2.0 (row ~140 dp in ~297 dp).
  The badge is checked as layout math: before (312 > 288) and after (96 dp, 144 px, badge at 282 dp). A sweep runs 1-8
  contacts over 200-480 dp at four densities: a fitting row never exceeds the content in px, and every item keeps its
  room. The badge overhang and the focus ring are tied to the layout and to P2's constant. Another source check covers
  HomeActivity's wiring.

**Not checked on the emulator yet** (shared emulator; the pass comes later):
- Home at `wm density 240` and font scale 1.0, 1.3 and 2.0, with 3 and 4 columns; "Innstillinger" at 1.3 and 2.0
  (hyphenated, not cut between letters), in nb and en.
- The top fade growing in over the first 12 dp of a scroll, the bottom fade melting away over the last 32 dp (no snap
  as a fling settles), over a photo wallpaper.
- Three contacts (no scroll, badge whole, no fade) and five (the scroll and its 16 dp fade).
- Tap, long-press, the swipe-up at the grid's end, the press and the focus ring on the new grid class.
- **The contacts-row swipes (M1)**, with three and with five contacts: a left swipe starting on a contact's avatar and
  one starting between two contacts (below a one-line name, or in the gap), each (a) as Home's first gesture after
  the unlock and (b) right after tapping a grid tile's label area and coming back. Record for each whether kid
  Settings opens or the row scrolls. This is the input for the user's decision on the swipe fix.
- **Frame time (N4)**: `adb shell dumpsys gfxinfo $P reset`, fling the grid up and down for 10 s, then `dumpsys
  gfxinfo $P` - janky frames and the 90th/99th percentiles, with the fades on, next to step 2's unmeasured elevated
  tiles (one measurement covers both).

### Step 4 code QA fix round (`qa-34-s4-code.md`, 2026-10-14)

| Finding | What was done |
|---|---|
| M1 a swipe from a contact is decided with an old DOWN | Docs only, as asked: the step's claim ("now opens kid Settings") is replaced by the true current behaviour (above, "The swipe rule's input changes") in this doc and `launcher/CLAUDE.md`, and the contacts-row swipes (3 and 5 contacts, on a contact and between contacts, as the first gesture and after a grid tap) are in the emulator checklist. The swipe handling is unchanged until the user answers. |
| L1 the fade snaps at the first and last pixel | `fadeStrength(remainingPx, edgePx, lengthPx)` = `min(1, remaining / edge) × min(1, edge / length)`, 0 with nothing left. `FadingRecyclerView` feeds it `computeVerticalScrollOffset()` at the top and range − extent − offset at the bottom, through `remainingScrollPx(canScroll, estimate)` (at least 1 px while `canScrollVertically` says there is more, so the layout manager's estimate can never hide a real edge; 0 when it says there isn't). The edges are `FADE_TOP_DP`/`FADE_BOTTOM_DP` × density over the view's real `verticalFadingEdgeLength`. Tested: full from the edge's length on, half at half, at most a pixel one pixel in, 0 at the end, monotonic, at four densities. Mutation m11 (no ramp) is caught. |
| L2 the row's width from `screenWidthDp` | `contactItemPx(layout, count, contentPx, density)` takes pixels; `HomeActivity.renderContacts` passes `contactsContentPx(measured, screenWidthDp, density)`: Home's column measured (`root.width` − its padding, which includes the side insets) once laid out, else (screenWidthDp − 32) dp in px less a pixel. The sweep now tries each width at −1/0/+1 px around the dp estimate. The layout choice (`contactRow`) stays on dp. |
| L3 two source checks pinned strings | `FadingRecyclerView`'s class body (comments stripped) must declare exactly the seven overrides - each once, each `override` - and nothing else: no `init`, no property, no listener, `setOn`/`addOn`, `Touch` or `object`. The wiring check matches whole lines anchored at the end (`maxWidth = itemPx`, `item.layoutParams = LinearLayout.LayoutParams(itemPx, ...WRAP_CONTENT)`), each assignment once in `renderContacts`, plus the measured width. Re-run: m6 (`init { setOnTouchListener { _, _ -> true } }`) and m7 (`maxWidth = itemPx + 40`) are now caught. |
| N1 no room check on the 76 dp row | `contactRow`'s 76 dp case also needs `count × contactRoomDp(76)`; the KDoc says both checks only bite below about 84 dp per contact. The sweep starts at 1 dp; one contact in 80 dp is 64 dp centred, in 70 dp it scrolls, in 84 dp it is 76 dp. Re-run: m5 (the 64 dp check dropped) and m5b (the 76 dp check dropped) are caught. |
| N2 "Innstillinger" breaks mid-word | `tile_label` has `android:hyphenationFrequency="normal"`: a single word too wide for the cell is hyphenated with the app language's patterns (nb and en ship with Android) instead of cut between letters. Chosen over `breakStrategy` (already `high_quality` by default for a TextView; it doesn't add hyphens). Pinned in the XML test; the look is on the emulator list. |
| N3 `contactRoomDp`'s `max` | KDoc: the badge overhangs on the end side only and the ring on both, so the larger on each side covers both and over-reserves at the start - harmless. |
| N4 the frame time is unmeasured | A `dumpsys gfxinfo` line in the emulator checklist above, measuring the fades and step 2's elevated tiles together. |
| N5 the music card isn't in the row model | The Jelly Star test also models the music card (12 dp margin, max(72 dp, 2 × 10 dp padding + two 12 sp lines + a 15 sp title at 1.6 em)): a whole row still shows at every scale, columns and navigation mode; the row plus the bottom fade fits up to 1.3. At 2.0 with the card, the fade may reach into the one whole row's labels while more rows are below (stated in the test). |

Tests: `HomeEdgesTest` 10. Mutations re-run against `HomeEdgesTest`/`HomeModelTest`: m5, m5b, m6, m7 and m11 caught.

## Step 6 implementation status (2026-10-14)

P12 is built (server and launcher in one commit, migration `0075_clock_format.sql`), on branch `design-34-s6`.

**What was built**
- **Server**: `device_policy.clock_format` `'system'|'24'|'12'` (default `'system'`, CHECK) and `device_status.clock_format_applied` (NULL/0/1). Home Screen's launcher form has a third inline select, "Clock" - Follow the phone / 24 h / 12 h - autosaving with the form's `<noscript>` Save, back to `#layout` (`assert_lands`, `check_forms`' redirect table). `update_launcher_ui` writes it with `COALESCE(?, clock_format)`: a post without the field (an older cached page) keeps the stored format; only a present unknown value is a 400 that writes nothing (L10). `launcher_ui.clock_format` is always sent (`models::clock_format` maps a stored value outside `CLOCK_FORMATS` to "system"); `policy_json_keys_snapshot`'s `ui_keys` has it. The status field is stored like the other optional fields. While 24 h or 12 h is chosen and the last report says `false`, Home Screen shows "Clock format not applied" - "The phone can't change its clock format yet." - above the Language group, with the adb grant and the Settings path behind its ⓘ (`device_pages::clock_format_note`). The group footer says the format is the whole phone's.
- **Launcher, the clocks** (`ui/ClockFormat.kt`): `systemIs24` = `TIME_12_24` when "24"/"12", else the system locale's "jm" pattern (`Resources.getSystem()`); `timePattern`, `formatTime` ("06:15" / "6:15", 12 h without AM/PM as the status bar). Every clock goes through it: the glance clock and row times, the time-rule clock and its "until" (`TimeRules.clockText` is gone), the missed-call text, Home's window banner, the relock notification, install mode's notification (`PlayRuntime`) and its Settings summary (`SettingsFragmentLauncher:285`). The time-rule clock's `H:mm` is `HH:mm` now, so 6:15 vs 06:15 is gone. The two time TextClocks get both formats set to the system's pattern (`ClockFormat.bind`) at every start/resume, and both lock screens watch `TIME_12_24` while started (bind and re-render).
- **Launcher, the write** (`server/ClockFormatSetting.kt`): every `AppEnforcer.apply()` writes `TIME_12_24` = "24"/"12" when it differs and `Settings.System.canWrite`; "system" writes nothing except clearing our own value once (CE prefs `clock_format_state` `clock_written`) while the setting still holds it - a value set by hand since is left alone. `WRITE_SETTINGS` is declared in the manifest. The status reports `clock_format_applied` from the setting read back. DTOs: `LauncherUi.clockFormat: String?`, `StatusReportRequest.clockFormatApplied: Boolean?`.
- **Runbooks**: `docs/testing/emulator.md` §4 (step 0's line, now "built") and `04-hardening.md`'s new-phone step name the app-op grant.

**Decisions**
- **TextClocks are bound in code, not left to pick.** A TextClock chooses between `format12Hour` and `format24Hour` by `is24HourFormat(its context)`, and with `TIME_12_24` unset that falls back to the context's locale - Vibb's per-app language. So `bind` sets both formats to the system's pattern; the XML keeps `h:mm`/`HH:mm` as the first frame's. TextClock's own observer still ticks; a format change goes through our `watch`.
- **`clock_format_applied` is the read-back**, not the write's result: `Settings.System.putString` can report success when SettingsProvider drops the write (its `call` answers nothing either way), so `true` means the setting is the choice now, `false` covers both a missing app-op and a refusal. `null` (left out) for "Follow the phone" and for a policy without `launcher_ui`.
- **A policy without `launcher_ui` leaves the setting alone** (no policy, an older server, the last-enforced fallback, which doesn't carry it), so a fallback never clears the parent's 24 h. An older server's `launcher_ui` without the key is "system".
- **Not a restriction**: the override and the pause don't lift it, and the write never lifts `DISALLOW_CONFIG_DATE_TIME` (the device check below decides; `ClockFormatSettingTest` checks the writer touches no restriction).
- **The warning sits above the Language group**, like "Notification access off" (design 31 §4.2's place for a sub-page warning), not between the rows: the partial's layout. No Attention row (the design names none).
- **`music_spotify::the_migrations_end_with_design_30_then_spotify` is relaxed** as its own note asked once 0074 was on `main`: it now pins 73 (design 30), 74 (Spotify) and 75 (the clock format) in order, not that 74 is the last.
- **The Settings path in the ⓘ names "Kids Launcher"**, today's label; P11 (step 12) renames it and greps the PWA texts.
- **The source scan** (`ClockFormatTest`, comments stripped): outside `ui/ClockFormat.kt` no `is24HourFormat`, `getTimeFormat`, `getTimeInstance`, `getDateTimeInstance`, `ofLocalized(Date)Time`, `FORMAT_SHOW_TIME`, `getBestDateTimePattern` with an hour field, a string literal holding a time pattern (`H:mm`, `HH:mm`, `h:mm`, `k:mm`, `K:mm`, also with a dot) or `%02d:%02d` (not an `h:mm:ss` duration's). Every layout TextClock with a time format must spell ClockFormat's two patterns and be `ClockFormat.bind`-ed; every surface of the inventory calls `formatTime`.

**Tests**: server `tests::launcher_ui::clock_format_form_keeps_a_missing_field` (each choice reaches the policy, the page selects it, a post without the field keeps 12 h, four unknown values are 400s), `clock_format_not_applied_is_a_warning` (false/true/null/absent stored and shown, the command only in the ⓘ, "Follow the phone" hides it), `launcher_ui_defaults_and_form`, `device_pages` (the form's action, `<noscript>`, the redirect table with `clock_format`), `policy_json_keys_snapshot`. Launcher `ClockFormatTest` (7), `ClockFormatSettingTest` (5), `PolicyResponseCompatTest` (+1: present, missing, `null`, unknown; the cache round trip; the status key, left out when null).


Full runs: server `cargo fmt --check`, `cargo clippy --all-targets --locked` (no new warning), `cargo test` 947 passed, `node --test jstest/*.test.js` 30 passed; launcher `assembleDebug testDebugUnitTest --rerun -PwarningsAsErrors=true` 1195 tests in 127 classes passed.
**Deferred / not checked** (no emulator in this step: another agent had it)
- Each option with and without the app-op; the status bar and every Vibb clock change together; Vibb in nb on an en_US system follows the status bar.
- **The device check**: whether SettingsProvider refuses the `TIME_12_24` write while `DISALLOW_CONFIG_DATE_TIME` is on. If it does, the status says `false` and the PWA warns; lifting the restriction around the write is a separate decision.

### Step 6 code QA fix round (`qa-34-s6-code.md`, 2026-10-14)

| Finding | What was done |
|---|---|
| M1 the system locale was probably the per-app one | `ClockFormat.systemLocale` reads `LocaleManager.systemLocales` first; `Resources.getSystem()` and `Locale.getDefault()` are only the fallbacks (the pure `chooseSystemLocale`, tested with the manager's en_US beating a per-app nb). `ClockFormatTest` pins the `systemLocales` call and that nothing in `ClockFormat.kt` reads `context.resources`; the QA's mutation 5 (the per-app locale) now fails. The emulator check (Vibb in nb on an en_US system) stays on the deferred list. |
| L1 a 12 h "until" had no AM/PM (coordinator's decision) | Clock faces keep the status bar's look (`formatTime`, `bind`: 12 h without AM/PM); every end time goes through the new `formatUntil`, which keeps the locale's AM/PM in 12 h ("until 9:30 PM"): the lock's "Until", Home's window banner, the pause/override relock notification, install mode's notification and its Settings summary. Tests: `ClockFormat.format` gives "9:30 PM"/"9:30 AM" with the marker and "6:15" without; the surface check requires `formatUntil` in those five places (mutation: the banner back on `formatTime` fails). |
| L2 the wiring wasn't pinned (three survivors) | (1) "No `launcher_ui`" is part of the pure decision: `clockFormatChoiceOf(null)` = no choice, `clockFormatWrite(null, ...)` = Keep, `clockFormatApplied(null, ...)` = null; `apply` no longer returns early, it passes `clockFormatChoiceOf(ui)` (pinned; `ui ?: LauncherUi` fails `wiring`). (2) `the write never lifts the date-time restriction`: `applyDateTimeLock(` is called exactly once in `AppEnforcer.kt`, with `plan.lockDateTime`, before `ClockFormatSetting.apply`, nothing after the write in `apply()` names `DISALLOW_CONFIG_DATE_TIME`, `applyDateTimeLock(` or `setAutoTimeEnabled`, and the restriction is spelled only inside `applyDateTimeLock`. (3) The TextClock test slices `GlancePanel.resumeNow`'s body and `LockActivity.onStart` before its `watch(` for the bind. Re-run: mutations 9, 11 and 12 are now caught (by the new date-time test, `wiring`, and the TextClock test). |
| N1 the CHECK constraint was unpinned | `tests::launcher_ui::the_clock_format_column_takes_only_its_three_values`: a new row is "system", "25", "", "follow" and "SYSTEM" are refused by the database itself, the three values are stored. |
| N2 the order test's stale name | Renamed `music_spotify::migrations_73_74_75_in_order`, its message names 0075, its comment the old name. |
| N3 "Kids Launcher" in the ⓘ | Added to P11's list above (the clock warning's Settings path in `clock_format_note`; its adb line names the package and stays). |
| N4 "Follow the phone" reset to unset | Restored instead, cheap and safe: `clock_format_state` keeps `clock_before`, the phone's own value from before our first write (or a value set by hand since our last write, `clockBeforeAfterPut`); "Follow the phone" puts it back (`ClockFormatWrite.Restore(before)`, `null` = unset), still only while the setting holds our value; a hand change since is forgotten and left alone (`Forget` drops both keys). Tested with the QA's walk: 12 h by hand, PWA 24 h, 12 h, 24 h, then Follow puts back 12 h. |
| N5 the surface check was a substring | Structural: each surface's string must take its time straight from the call (`getString(R.string.home_window_open, ClockFormat.formatUntil(this, until))`, `val time = ...formatUntil(app, untilMs)` with `getString(title, time)`, the three `missed_when_*` strings with `time`, ...). The scan also forbids a string template building a clock from `${...hour}`. |
| N6 a locale change while the lock shows; fi's `H.mm` | `ClockFormat.watch` also registers `ACTION_LOCALE_CHANGED` (bind + re-render on both lock screens). The pattern comes from the system locale's own `getBestDateTimePattern` with the hour field forced (skeleton "Hm"/"hm", from `TIME_12_24` or the locale's "jm"), so fi shows `21.30` like its status bar; a face drops the AM/PM marker (`facePattern`, quoted text kept); a pattern java.time can't read falls back to `HH:mm` / `h:mm (a)`. |

Full runs: server `cargo fmt --check`, `cargo clippy --all-targets --locked` (13 warnings, all old), `cargo test` 948 passed, `node --test jstest/*.test.js` 30 passed; launcher `assembleDebug testDebugUnitTest --rerun -PwarningsAsErrors=true` 1200 tests in 127 classes passed (`ClockFormatTest` 9, `ClockFormatSettingTest` 8).

## Step 7 implementation status (2026-10-14)

P6 is built (launcher only; no server, policy or contract change), on branch `design-34-s7`. Visual only: what the lock
allows (its usable apps, calls, the phone book, exempt apps), Back off, the gestures, every target and the emergency
flow are unchanged.

**What was built**
- **One calm card** (`activity_lock.xml`, mockup 04-after): the clock through P12 (`ClockFormat.bind`, as step 6 left it)
  at 52 sp / Nunito 700 in `kid_core`; the kind's icon in a 52 dp peach disc (`kid_lock_kind_disc`, the accent at 14 %,
  glyph `kid_accent`, decorative); the title (22 sp, a heading); the message (15 sp `kid_core_dim`: the rule's "until"
  on its own line, then the sentences as one paragraph); one card of rows on `kid_ground_lift` (the contact sheet's
  lift, QA M4) with hairlines; at the bottom "Nødsamtale" as a red text button (`kid_emergency_text`, step 3's resource
  and the glance page's red, `minHeight` 48 dp, always shown, `EmergencyCall.confirm` unchanged) and "Skriv inn
  opplåsingskode" in `kid_core_dim` (48 dp). A weighted `Space` (min 16 dp) puts the two at the bottom when the screen
  has room; with many rows or a large font the screen scrolls as before.
- **Rows** (`item_lock_row.xml`; pure `ui/TimeLockModel.kt` `timeLockRows`): the phone book first while calls are
  managed and the lock allows them (the phone book tile's `kid_phone_book` + `ic_kid_phone_book`), then every usable app
  that can be opened (the same filter: launchable, not suspended, a camera the PIN lock holds counts as usable) sorted by
  the parent's name - the music app among them under a budget lock. App icons are Home's tone icons (`renderAppIcon`,
  one background job as on the glance page, a slate disc until drawn). A row: 34 dp icon, the name (16 sp, two lines at
  most), a chevron (`ic_kid_chevron`, the light at 50 %), `minHeight` 52 dp.
- **TalkBack** (QA L4, L7): each row is one node whose content description is the name and whose role is a button
  (`LockActivity.asButton` sets the node's class to `Button`; since the fix round step 8's `ButtonRole`), so TalkBack reads "Telefonbok, knapp" / "Phone book,
  button" in the phone's own words; icon, label and chevron are `importantForAccessibility="no"`. The two text buttons get
  the same role ("Nødsamtale, knapp"); the kind's icon is decorative, the title is a heading.
- **Glyphs** (QA L4): `lock_glyph_hourglass` (`hourglass_bottom`, SHA-256 pinned in the script) and `lock_glyph_bedtime`
  (`bedtime`) are converted Material Symbols, generated by `scripts/material-symbols.sh` with their "Converted from"
  lines (Apache-2.0, already shipped); the book is `lock_glyph_school` (`menu_book`, the same symbol as the parent's
  "book" glyph) from the same new `LOCK_GLYPHS` table - not in `app_icons.json`, as it is no parent choice; the custom
  rule's dot (`lock_glyph_dot`) is drawn by hand. Pure `lockGlyph(kind)`: no rule (screen time) the hourglass, bedtime
  the moon, school the book, custom or unknown the dot. Re-running the script changed nothing else.
- **The sentence** (QA L4): `budgetLine` + `durationText`. Under the budget lock: "Du har brukt dagens {allowance}." /
  "You've used today's {allowance}." with allowance = `BudgetUse.effectiveMinutes` (today's budget + today's extra
  minutes, the number `time_state.budget_minutes` reports), formatted "1 t 30 min" / "1 h 30 min", "45 min", "2 t" /
  "2 h" (units `duration_unit_hours`/`_minutes`; abbreviations, so 1 and many read alike). The minutes used are no
  longer shown at all, so an overrun (6 minutes counted against a budget lowered to 1) reads the same sentence - the
  "6 av 1 minutter brukt i dag" string `lock_budget_used` is gone. Under a rule, "Skjermtiden for i dag er også brukt
  opp." as before.

**Decisions**
- **A budget of 0 says "Ingen skjermtid i dag." / "There's no screen time today."** rather than "Du har brukt dagens
  0 min." (a day the parent set to no screen time; with extra minutes it is the normal sentence).
- **The card is `kid_ground_lift`** (`#1C1A2A`, the sheet's) rather than the mockup's `#1B1B28`: one surface colour for
  the night's raised things (QA M4), and the two are a hair apart.
- **The role comes from the node's class, not the text**: a content description "Telefonbok, knapp" would be read twice
  ("..., knapp, knapp") by TalkBack, which adds the role itself, and wouldn't follow the phone's language.
- **The phone book's place**: first in the card (the mockup), not after the apps as the old button was.
- **Removed**: `KidLockButton` (style), `item_lock_app.xml`, `kid_message_button` (P3's table: its last user was these
  buttons). `bg_kid_button` stays (the contact sheet's buttons).

**Tests**: `TimeLockModelTest` (11: the glyph per kind; `durationText` 0-1440 and negative in nb and en; the sentence in
nb and en incl. extra minutes; the overrun and the old string gone in both languages; budget 0, a rule with the budget
used up, nothing to say; the rows - phone book first, calls unmanaged or blocked, sorting, duplicates, empty; the three
converted glyphs' lines, the script's hash and table, every `LockGlyph` wired to its drawable; the screen's order, 52 sp
/ 700, no tinted or styled button left; the emergency button's text, colour = the glance page's, >= 48 dp, always shown;
each row one button with decorative children; the targets, the emergency confirm, the open filter and Back off pinned),
`KidPaletteTest` +1 (every ink on the screen >= 4.5:1 against the night, the emergency red >= 7:1, the accent on its
disc >= 4.5:1, the card is the lift, the row name >= 4.5:1 and the chevron >= 3:1 on it, `kid_message_button` gone).
`ClockFormatTest` still pins the clock's bind and the "until" through `formatUntil`. Full run:
launcher `assembleDebug testDebugUnitTest --rerun -PwarningsAsErrors=true` - 1222 tests in 129 classes passed.

**Deferred / not checked** (no emulator in this step)
- Each rule kind (school, bedtime, custom) and the budget lock in nb and en at `wm density 240`, font scale 1.0, 1.3 and
  2.0 (the two buttons at the bottom, the scroll with many rows); the icons' look; TalkBack on the rows and buttons.
- 112 with Telecom refusing (`docs/testing/emulator.md` §6 item 9) - the flow's code is unchanged.

### Step 7 code QA fix round (`qa-34-s7-code.md`, 2026-10-14)

Main (design 34 step 8) was merged into `design-34-s7` first; the doc, `launcher/CLAUDE.md` and `KidPaletteTest`
conflicts kept both sides.

| Finding | Fix |
|---|---|
| L1 "always shown" pinned only in the XML | `render()` sets `lockEmergencyButton.visibility = View.VISIBLE` on every render (before the rows). `TimeLockModelTest` `emergency call is visible in every state` allows exactly three uses of the view in the comment-stripped source - its click (`EmergencyCall.confirm`), its role, that line - so any hide, `isGone`, alpha or hand-off to a helper fails; nothing hides the binding root. Mutation 6 (hide it while calls are managed) re-run: now caught. |
| L2 the first icon pass rebuilt every row | `renderRows` keeps `rowIcons` (package -> the row's `ImageView`, cleared with the rows); the loader's callback `iconsDrawn` sets each drawn bitmap in place (`showAppIcon`) and nothing else - no `render()`, no `snapshot`, no inflate. `render()` runs again only on the preference, clock and locale events, as before P6. Tested on the source (`icons arrive in place`). |
| N1 the code button's role unpinned | Both text buttons and the rows take step 8's shared `lock/ButtonRole` (`LockActivity.asButton` is gone); the test pins all three assignments. Mutation 7 (the code button's role removed) re-run: now caught. |
| N2 dead `distinctBy` | Removed (the input comes from the lock's package set). The test now has two packages named alike and expects both rows, ordered by package. |
| N3 square ripple in the round card | `lock_rows` `clipToOutline="true"` (pinned). |
| N4 a copied icon loader, a `failedIcons` race | `ui/home/AppIconLoader` (cache lookup that notes the missing, one background `load`, `cancel`), used by the glance page and this screen (~40 lines less). The background pass is the pure `iconPass`, which returns drawn and failed; the failed set is merged on the main thread in the continuation, so every set is main-thread only. Tested: the pass (a throw is a failure), no shared set written inside `withContext`, neither screen keeps its own `renderAppIcon`/`failedIcons`/`withContext`. The glance page's `renderIcons` keeps its `catch (Throwable)` (`LockGlanceGuardTest`). |
| N5 a tautological dot test | The dot is checked as drawn by hand: no "Converted from" line, a 24-unit vector with one white filled circle path. |

Tests: `TimeLockModelTest` 14 (was 11). Full run: launcher `assembleDebug testDebugUnitTest --rerun
-PwarningsAsErrors=true` - 1237 tests in 129 classes passed.

## Step 8 implementation status (2026-10-14)

P7 built (launcher only, no server or contract change), on branch `design-34-s8`:
- **The tonal pill** (`activity_pin_lock.xml` `pin_emergency`): new `bg_pin_emergency_tonal` = step 3's
  `kid_emergency_tonal` `#3A1E24`, 24 dp radius; label and glyph `kid_emergency_text` `#FF8A8A` (6.6:1, `ImageView`
  `tint` and `textColor`). The view, its click listener and the glance page's `performClick` are untouched, so the
  emergency flow is exactly as before. The VoIP ring card's pill follows P7 too since the fix round (L2, below).
- **The spacing rule**: the button's top margin is 16 dp (was 8), its `minHeight` 48 dp (was 44) with 6 dp vertical
  padding, the label wraps (no line limit) so the pill grows with the font scale; "Foreldrekode" `minHeight` 44 dp (was
  40). One column, nothing overlaid; the keypad has no bottom margin and its keys never overflow it, so the 16 dp margin
  is the least gap. `PinKeypadLayout`'s rule is unchanged (min(72, (h - 3 × 6) / 4), floor 32).
- **Digits** (decision): at 2.0 the keys give way but a 30 sp digit would not - 60 sp linear (38 dp with Android 14's
  non-linear scale) on a 43 dp key is cut. `PinKeypadLayout.digitTextPx` caps the digit at 0.7 × the key (since the
  fix round, N2: the line box stays inside the key and a 2.0 digit is no smaller than at 1.0; the mockups draw 0.42 at
  every scale, so they are not the reason); at 1.0 and 1.3 on the Jelly Star it doesn't bite.
- **The key highlight**: the keypad root carries the lock wallpaper under its scrim, so the keys stay the translucent
  `bg_pin_key` (an opaque key with a highlight under it would change their look over a photo). The highlight is
  `lock/PinKeyHighlight`, a white arc over the top third, layered over the key in `buildKeypad` - since the fix round 1
  dp at 6 % as the mockups draw it (N3).
- **The glance clock**: `@font/nunito_bold` (700), was `nunito_extrabold` (800); size, fit and format untouched (step 6
  owns the format).
- **Modelled (an upper bound, not measured on a screen)** (`PinLockLayoutTest`'s model, Jelly Star 320×569 dp, 48 dp
  status bar, gesture 24 dp / 3-button 48 dp): font scale 1.0 -> keys 71 / 65 dp (the 56 dp floor holds - QA N1: it
  didn't need to drop to 52); 1.3 -> 69 / 63 dp (>= 48); 2.0 -> 49 / 43 dp (above the 32 dp floor). The model wraps
  the English "Emergency call" to two lines (a 96 dp pill); in real Nunito ExtraBold it is 7.21 em and fits one line
  even at a linear 2.0, so the emulator will show a pill of about 50 dp and larger keys (N1).
- **Tests**: `PinLockLayoutTest` 13 (was 8; the model now `max(minHeight, lines × sp × scale × 1.4 + padding)` with
  lines from the longest nb/en string at 0.6 em, linear scale as an upper bound; new: 1.3, 2.0, the spacing rule, the
  pill, the highlight and digit cap; every scale checks no overflow and no floor), `KidPaletteTest` +1 (the pill's
  label and glyph from the layout and drawable >= 4.5:1, 6.6:1; the pill < 1.5:1 on the night), `LockGlanceLayoutTest`
  +1 (the clock is 700, nothing sets its typeface in code).
- **Check on the emulator** (not done here - shared emulator): the keypad at font scale 1.0, 1.3 and 2.0 in nb and en
  via the emulator console screenshot (`docs/testing/emulator.md` §6c, the window is `FLAG_SECURE`), against mockups
  05/05b; the key highlight over a photo wallpaper; Nødsamtale from the keypad and from the glance page (the flow as
  before); the glance clock.

### Step 8 code QA fix round (`qa-34-s8-code.md`, 2026-10-14)

Main (design 34 step 6) was merged into `design-34-s8` first; the doc and `launcher/CLAUDE.md` conflicts kept both sides.

| Finding | Fix |
|---|---|
| L1 the digit cap skipped at an unchanged 48 dp | Pure `PinKeypadLayout.keyStyle` (size + capped digit) and `keyStale` (size and digit checked apart); `sizeKeys` applies the digit to every stale key beside the size branch, not inside it. Tested on the 48 dp case (a key that keeps its size but shows the 90 px digit is stale) and pinned in the activity (the call, its indentation beside the size branch). Mutation m4b (the `setTextSize` call deleted) re-run: now caught. |
| L2 the VoIP ring card's pill | Follows P7 (coordinator's decision, QA's recommendation): `bg_pin_emergency_tonal`, `kid_emergency_text` label and glyph, `minHeight` 48 dp, 6/20 dp padding, a wrapping label with no line limit; the click path is unchanged. `bg_pin_emergency.xml` had no other user and is deleted. `KidPaletteTest` reads both pills from their layouts; `PinLockLayoutTest` checks the ring pill's resources and that it grows exactly as the keypad's at 1.0/1.3/2.0. |
| L3 no button role | `lock/ButtonRole`, an accessibility delegate that only sets the class name to `Button`, on both pills (`PinLockActivity.onCreate`); pinned by a source check. |
| N1 "measured" was the model | Both docs say "modelled (an upper bound)"; the English label fits one line in real Nunito, so the screen will show a smaller pill and larger keys. The test's two-line assertion stays as a model check. |
| N2 the cap's rationale | `DIGIT_SHARE` 0.7: the line box (1.364 em) stays inside the key (0.95) and a 2.0 digit on the Jelly Star's modelled 43 dp keys is >= 30 dp, as at 1.0 (tested). The mockup claim is gone. |
| N3 highlight 1 px at 20 % | 1 dp (`HIGHLIGHT_STROKE_DP` × density, passed in) at 6 % (`0x0FFFFFFF`), the mockups' `inset 0 1px 0 rgba(255,255,255,.06)`. |
| N4 no pressed state | `bg_pin_emergency_tonal` is a `<ripple>` of the label's red at 20 % over the tonal shape (tested: colour and alpha). |
| N5 the glance link at 44 dp | 48 dp; its top margin went from 6 to 2 dp, so "Lås opp" + "Nødsamtale" stay 106 dp and every expected glance fit is unchanged (tested). |

Tests: `PinLockLayoutTest` 17 (was 13), `KidPaletteTest` 8 (the pill test covers both pills, the ripple and the deleted drawable), `LockGlanceLayoutTest` 7 (was 6).

## Step 9 implementation status (2026-10-14)

P8 is built: server migration 0076 and the launcher's names, in one commit.

**What was built**
- **Server** (`server/migrations/0076_softer_wallpapers.sql`): three UPDATEs matched by `builtin_key`, never by id.
  - `green` gets `kind = 'gradient'`, `["#2A4A4F","#101820"]` and the label "Fjord". It was a `color` row (QA M1): with two colours and its old kind it would fail `checked_colours()` and drop out of the policy.
  - `sky` gets `["#34406A","#14141F"]` and "Evening sky".
  - `sunset` gets `["#5A3A4A","#1A1420"]` and "Dusk".
  - Ids, keys and sort are kept. So every phone's ticks (`device_wallpapers`), its lock choice (`device_lock_wallpaper`) and the kid's pick (an id on the phone) stay, now softened. That is the user's answer: a phone that has one picked changes with it.
  - The PWA's two grids (Home Screen › Wallpapers and Settings › Wallpapers) show the new swatches and names straight from the rows (`css_background`, `label`). No template changed.
- **Launcher**: only the names, since it keeps no built-in colours of its own. `wallpaper_green`/`_sky`/`_sunset` are nb "Fjord", "Kveldshimmel", "Skumring" and en "Fjord", "Evening sky", "Dusk" (N2).
- **Contract**: the shape of `launcher_ui.wallpapers` is unchanged (keys, kinds and the policy snapshot are untouched). An older launcher draws the new colours from the policy as before; it shows its own old names until it updates.

**Tests**
- Server:
  - `wallpapers::every_builtin_row_passes_to_policy_after_all_migrations` (new; M1): every built-in row, all 7, passes `to_policy()` after every migration.
  - `the_softened_builtins_keep_their_ids_and_keys` (new): ids 4/5/6, the keys, `gradient`, the colours and labels, and both PWA grids showing the new swatches and names with no old colour left.
  - `every_phone_gets_the_builtins_with_every_key`: now asserts the three rows' whole policy JSON.
  - `music_spotify::migrations_73_to_76_in_order`: renamed from `migrations_73_74_75_in_order`, now with 76.
- Launcher:
  - `WallpaperInkTest`'s built-in list is today's seven.
  - `the softened built-ins take white ink with no scrim` (new): white ink, no scrim or shadow, and >= 9.5:1 on the lightest stop (the lowest is Fjord at 9.59:1, as the design's 9.6 said; qa-34-s9-code N2).
  - The lock-ink test also runs on every built-in's stops. All three softened lighter stops need a light lock scrim for the red emergency link (#FF8A8A is 4.22:1 on Fjord, 4.43 on Evening sky, 4.33 on Dusk), and `lockScrimForColours` gives it (qa-34-s9-code N1).
  - The old bright sky/sunset stay as generic bright grounds.
  - `TranslationsTest` covers the strings.

**Check on the emulator** (not done here): kid Settings' wallpaper picker in nb and en with the three new names, Home, and the lock over Fjord, Evening sky and Dusk.

## Step 10 implementation status (2026-10-14)

P9 built (launcher only, no server or contract change), on branch `design-34-s10`:
- **The control** (`activity_kid_settings.xml`): the sound row's `RadioGroup` is the track - `bg_kid_segment_track`,
  white 8 % (`#14FFFFFF`, the card's alpha; the mockup's `.07` rounded to it), radius 14 dp, 3 dp inside. The two
  `RadioButton`s are equal halves (`0dp` + weight 1, no 8 dp gap any more, `match_parent` height so a wrapped label
  makes both taller), >= 48 dp, 6/8 dp padding, Nunito 800 14 sp as the mockup, radius 11 dp (concentric with the
  track). No `maxLines`/`ellipsize`: the labels wrap and the control grows.
- **States** (`KidSettingsActivity.styleSegment`): chosen = `kid_accent` with `kid_ground` text, as P9 and mockup 06
  say (decision: the brief's "quiet, not saturated" is read as this - the one muted accent, not a new colour);
  the other = no fill, the wallpaper's ink (full, not the mockup's dim ink, so it reaches 4.5:1 on every built-in). The
  old outlined pill next to a filled one is gone. A ripple in the ink at 12 %, clipped to the half, is the press.
  Read-only (DND, unknown) keeps the 50 % alpha on both halves.
- **Behaviour unchanged**: `renderSound` reads the checked half and the enabled state from the pure `soundSegments(state)`
  (`ui/kidsettings/SoundSegments.kt`), which only restates `soundRowState`; the clicks still call
  `QuickControls.setSound`, and `soundTapMode` decides as in design 18.
- **TalkBack** (QA L7): still two `RadioButton`s in a horizontal `RadioGroup`, so the framework gives the radio role,
  the selected state and the group (single selection, two items) - TalkBack reads "Lyd, valgt" as the design asks.
  The group's content description names it; the visible label "Lyd på telefonen" is now
  `importantForAccessibility="no"` so it isn't read twice.
- **Tests** (`SoundSegmentsTest`, 9): the layout (equal halves, the `SOUND_SEGMENTS` order, the minimums and paddings,
  no line limit), the track drawable, the width/lines model at 320/360/411 dp and font scale 1.0/1.3/2.0 in nb and en
  (`segmentLayout`, an upper bound like `PinLockLayoutTest`'s: at 2.0 on the Jelly Star "Lydløs (vibrerer)" wraps and
  the control grows past 48 dp; at 411 dp and 1.0 both fit one line at 48 dp), the selection mapping for Normal,
  Vibrate, Silent and DND with design 18's taps, the TalkBack structure (no delegate or role override on the halves),
  and contrast: the chosen half 9.87:1; the other half's ink on track + card + scrim >= 4.5:1 over every built-in
  wallpaper and two light grounds (lowest 6.2:1, Fjord).
- **Found, not changed in step 10** (fixed in the QA fix round, M1 below): `WallpaperInk` brings a ground to 4.5:1 for the bare
  wallpaper, so with white ink on a mid-tone ground at the edge of that (a photo, or a bright colour such as `#1C7ED6`)
  every label on Kid Settings' white 8 % card is already under 4.5:1 (~3.9:1), and the segment's track takes it to
  ~3.5:1. Fixing it means judging the card's composite in the ink rule (all kid screens), not this step.
- **Check on the emulator** (not done here - no emulator): Kid Settings at 320/360/411 dp (`wm size`/`wm density`) and
  font scale 1.0/1.3/2.0, nb and en, against mockup 06; TalkBack on both halves ("Lyd, valgt", "Lydløs (vibrerer), ikke
  valgt", the group) and under DND (read-only); a tap switching Normal/Vibrate with the volume keys still working.

### Step 10 code QA fix round (`qa-34-s10-code.md`, 2026-10-14)

| Finding | Fix |
|---|---|
| M1 white ink under 4.5:1 on the card | Its own commit. `WallpaperInk.scrimFor` judges white ink on the ground under the scrim at the 8-bit alpha drawn (`drawnScrimAlpha`, as `lockScrim`) plus the card and the track (`underKidSurfaces`: two white 8 % layers, each rounded to 8 bits - without the rounding the edge grey came out 4.48:1); dark ink on the bare ground, also at the drawn alpha. Built-ins and light grounds keep 0; `#1C7ED6` goes 0.05 -> ~0.24, the edge grey 0 -> ~0.21 (on Home and the phone book too - one shared scrim). The card's manage links take the ink (`renderInk`), not peach. `WallpaperInkTest` +5: mid-tone colours, edge greys and a photo on bare/card/track per channel at 8 bits, the built-ins, the surfaces' alpha against both drawables, no truncation gap; `SoundSegmentsTest`'s contrast case takes mid-tone grounds and pins the links. |
| L1 light ground: the chosen half only ~2:1 | `segmentColours`: on dark ink the chosen half gets a 1 dp outline in the ink (`SEGMENT_STROKE_DP`; 17:1 on white, >= 3:1 to the track and the peach). Tested on white, `#FFF3B0`, `#F4F1EA`; none on the night. |
| L2 chosen look and role unpinned | The pure `segmentColours(ink, accent, night)` holds every state's colour; the test pins it, the `state_checked` spec, and that the checked items come before the default in both the text colours and the fills (no `state_selected`/`state_activated`). The TalkBack scan covers the whole activity (`accessibilityDelegate`, `AccessibilityDelegate`, `ButtonRole`, `accessibilityClassName`, `AccessibilityNodeInfo`). Mutations 3 (`button.accessibilityDelegate = ButtonRole` in `styleSegment`) and 4 (`state_checked` -> `state_selected`) re-run: both caught now. |
| L3 test-only model in production | `SegmentLayout`, `segmentLayout`, `linesFor` and the six mirror constants moved into `SoundSegmentsTest` (checked against the XML there). `SoundSegments.kt` keeps `SEGMENT_RADIUS_DP`, the stroke/ring widths, `SOUND_SEGMENTS` and `segmentColours`. |
| N1 "(vibrerer)" cut between letters at 2.0 | `hyphenationFrequency="normal"` on both halves; the layout test checks it. |
| N2 focus nearly invisible | A `state_focused` 2 dp ring as the half's foreground: night on the peach half, peach on the other (the dark ink on a light ground); >= 3:1 on both, tested with the state order. |
| N3 `soundSegments` restated `soundRowState` | Dropped with `SoundSegment`; `renderSound` reads `state.selected`/`state.enabled` as before step 10. The test keeps the halves' order and design 18's taps. |

Tests: `SoundSegmentsTest` 12 (was 9), `WallpaperInkTest` +5.

## Step 13 implementation status (2026-10-14)

P13: the launcher reports its time zone, so the lock badge can say until when. One commit, server and launcher; no
policy change, no migration, no capability.

- **Launcher**: `TimeState.zone: String? = null` (`server/dto/StatusReportRequest.kt`). `TimeRulesRuntime.report`
  reads `zone()` (`ZoneId.systemDefault()`) once and sends `reportedZone(zone)` - the zone the report's `day` and the
  rules' windows are counted in. The pure `reportedZone` (`timerules/TimeRules.kt`) is the id as it is, left out
  (`null`, so not in the JSON) when blank, over 64 characters (`MAX_REPORTED_ZONE_LENGTH`, the server's limit) or not
  printable ASCII - never on a real `ZoneId`, a guard only. An offset id ("Z", "+01:00") is sent as it is; the server
  never shows an end for it.
- **`TimeStateZoneTest`** (4, QA L8): the **encoded** `StatusReportRequest` equals an exact string with
  `"time_state":{...,"zone":"Europe/Oslo"}` (and parses back with the zone); `zone = null` leaves the key out and an
  older report without it decodes; `reportedZone` of real ids, of the machine's own zone (non-null and `ZoneId.of`
  reads it back as the same zone) and of offsets; a source check that `report` passes `reportedZone(zone)` of the
  runtime's own `zone()`. `PolicyResponseCompatTest`'s key-set assertion is unchanged (it builds a `TimeState` without
  a zone - a default is left out).
- **Server**: nothing to build. `TimeReport::parse` already keeps `zone` as a non-empty string of at most 64 bytes
  (anything else - a number, `null`, `""`, a longer string - is `None`), and `provable_end` compares it for exact
  equality with Phone Setup's IANA name (`Zone::name`, `None` in the UTC fallback), so an unknown or garbage zone, an
  offset id or another case is ignored safely: no end, never an error. The status intake stores `time_state` as an
  opaque capped object (`time_state_json`, <= 4096). Comments and docs that said no launcher sends a zone are
  updated (`time_state.rs`, the two older tests).
- **End-to-end test** `tests::devices_list::a_launcher_report_with_its_zone_shows_the_rules_end`: the launcher test's
  exact body (`LAUNCHER_TIME_REPORT`, with today's day, the rule's id and the zone under test put in) posted to
  `/api/devices/status`; a bedtime rule "Leggetid" 01:00-07:00 on today's weekday only; Phone Setup's zone chosen per
  run as the `Etc/GMT±N` where it is 03:xx now (so the end is "until 07:00" today whatever the time of the test - a
  real IANA name like "Europe/Oslo" would need the test to run at night). The zone equal: "Leggetid until 07:00" on
  the Devices list's row, the summary's header and Device › Time. "Europe/Oslo", the same offset spelled `UTC+N`, the
  name lower-cased, no `zone`, `null`, `""`, `42`, "Not/A_Zone" and the zone padded past 64 bytes: "Leggetid" alone on all three.
  Back to the equal zone: the end again.
- **Contract files**: `server/testdata/` pins no status shape (checked: `phone_vectors.json`, `app_icons.json`, the
  music files); the two tests' literal is the contract - kept equal by hand, said in both tests' docs.
- **Docs**: `server/CLAUDE.md` (the lock badge bullet, Device › Time, the summary's header), `launcher/CLAUDE.md`
  ("Time rules" › Status). The emulator recipe (`adb shell getprop persist.sys.timezone` into Phone Setup) was step 0's
  (`docs/testing/emulator.md`).
- **Counts**: server `cargo test` 951 passed (was 950), `cargo fmt --check` clean, `cargo clippy --all-targets
  --locked` 13 warnings, none in the touched files; node jstests 30/30. Launcher `assembleDebug testDebugUnitTest
  --rerun -PwarningsAsErrors=true` 1259 tests, 0 failures (`TimeStateZoneTest` +4).
- **Check on the emulator** (not done here - no emulator): Phone Setup's zone = the emulator's
  `persist.sys.timezone`, a bedtime rule running now; the phone's summary, the Devices list and Device › Time say
  "Leggetid until …"; Phone Setup set to another zone - the rule's name alone.

### Step 13 code QA fix round (`qa-34-s13-code.md`, 2026-10-14)

| Finding | Fix |
|---|---|
| L1 the zone read again for the day and the rule | `TimeRulesRuntime.snapshot(.., zone: ZoneId = zone())` reads the system zone once and passes it to `ledger(.., zone)`, `loadLedger(.., zone)` (the unreadable ledger's day) and the rules' local time; `Snapshot` keeps it, and the pure `timeStateOf(snap, lockReason)` (what `report` calls) sends `snap.zone.id`. `TimeStateZoneTest.the_report_carries_the_zone_the_snapshot_was_counted_in` builds a snapshot in a zone that isn't the machine's and checks the report's zone; a source check pins that `snapshot`/`ledger`/`loadLedger`/`timeStateOf` read no zone of their own. Mutations re-run: `timeStateOf` reading `zone()` (the QA's L1b, moved) - killed (2 tests); `snapshot`'s local time from `zone()` - killed (the source check). |
| L2 an end in a DST gap | `Zone::single_instant(t)` (`display_time.rs`: `from_local_datetime(..).single()`), and `provable_end` returns `None` unless the end is exactly one instant. `time_state::tests::an_end_that_isnt_one_instant_shows_no_end`: a rule 00:00-02:30 in Oslo shows "until 02:30" on an ordinary night, the name alone on 2026-03-29 (02:30 never shows) and on 2026-10-25 (02:30 comes twice - the fall-back case, now refused too, one night's badge), plus the zone's own answers. |
| L3 the literal kept equal by hand | The contract file `server/testdata/time_status_report.json`, byte-identical in `launcher/app/src/test/resources/`: the launcher test compares its encoded report with it and the server's copy (`the_contract_is_identical_to_the_servers_copy`), the server test `include_str!`s it and compares the launcher's copy (`devices_list::the_time_report_contract_matches_the_launchers_copy`). Root `CLAUDE.md`'s contract list and the CI path filters (`launcher.yml`: the server's copy; `server-ci.yml`: the launcher's) name it. |
| N1 `reportedZone` unreachable | Dropped with `MAX_REPORTED_ZONE_LENGTH`; the report sends `zone.id` (the server's `TimeReport::parse` is the guard). |
| N2 the padded case didn't test the cap | Dropped from the end-to-end test; `time_state::tests::the_zone_is_kept_only_as_a_short_non_empty_string` pins `parse`: 64 bytes kept, 65 dropped, `""`, `null`, a number dropped. |
| N3 the clippy count | Checked on the base (`e17949e3`) with this sandbox's toolchain (clippy 0.1.99, 2026-09-28): 13 warnings, the same 13 on this branch, none in the touched files. The QA's 15 is its toolchain's. |

Counts: server `cargo test` 954 passed (951 + `an_end_that_isnt_one_instant_shows_no_end`,
`the_zone_is_kept_only_as_a_short_non_empty_string`, `the_time_report_contract_matches_the_launchers_copy`), `cargo fmt
--check` clean, clippy 13 (as on the base), node jstests 30/30; launcher 1260 tests, 0 failures (`TimeStateZoneTest` 5,
was 4).

## Step 5 implementation status (2026-10-14)

P5: the drawer in Vibb night with the peach accent, text only, without Vibb itself. Launcher only, one commit; no
behaviour, gesture or policy change.

- **Colours by resource, not code** (QA L2): the drawer is the MIT `ui/list/` and gets no new code. `res/values/colors.xml`
  (not an MIT directory) points the default colour theme at the kid palette: `finnmglasTheme_background_color` ->
  `@color/kid_ground`, `finnmglasTheme_accent_color` -> `@color/kid_accent`, `finnmglasTheme_text_color` ->
  `@color/kid_ink` (was `#252827`/`#5555ff`/`#fff`). `launcherBaseTheme` and `colorThemeDefault` both read them, so the
  drawer and the parent Settings share the night (as §3 says): white 19:1, peach 9.9:1 on `#0C0C14`.
- **One knock-on fixed**: the base theme's plain `Button` (`Widget.AppCompat.Button.Colored`, filled with the accent)
  had the theme's white text - readable on the old blue, 1.9:1 on peach. The new `accentButton` style (the base
  theme's `android:buttonStyle`) gives it `kid_ground` text (9.9:1). Its one user today is `PlayLinkBlockedActivity`'s
  OK; XML `<Button>`s inflate as `AppCompatButton` and don't take it.
- **Text only**: the row layout and the adapter are unchanged (the icon view stays `gone`); the rows take the theme's
  text colour. The mockup's card behind the rows and the round header buttons are not built - P5's text names only the
  colours and the filter, and both would need layout or code in the MIT drawer.
- **Without Vibb itself**: one condition in `AppFilter.kidListable` (`apps/AppFilter.kt`, MIT like the file),
  `packageName !in OWN_PACKAGES` = `me.vibb.launcher` and `me.vibb.launcher.debug` (both, so a debug build beside a
  release one lists neither). Home already dropped its own package; that filter stays.
- **Tests**: `AppFilterTest` +2 (both own ids refused in and out of install mode, the music app, a near-miss id and the
  old `com.kidslauncher.mdm` still listed; `OWN_PACKAGES` equals the Gradle `applicationId` and its debug suffix),
  `KidPaletteTest` +1 (the theme colours are the palette's, text and accent >= 4.5:1 on the ground, both styles read
  them, `accentButton` on the accent >= 4.5:1, the drawer's row sets no colour of its own).
- **Not emulator-checked yet**: the drawer (the swipe-up, a long-press menu, a launch) and the parent Settings with a
  dialog and a switch, at `wm density 240`, nb and en.

Counts: launcher `assembleDebug testDebugUnitTest --rerun -PwarningsAsErrors=true` 1263 tests, 0 failures (was 1260).

### Step 5 fix round (qa-34-s5-code, 2026-10-14)

| Finding | Fix |
|---|---|
| M1 default AlertDialogs follow the system's dark mode (peach buttons 1.97:1 in light mode) | `KidAlertDialog` (`ThemeOverlay.AppCompat.Dialog.Alert`) is `launcherBaseTheme`'s `alertDialogTheme`: panel `kid_ground_lift` (`android:colorBackground`, `colorBackgroundFloating` and its own window background `bg_kid_dialog` - AppCompat's `abc_dialog_material_background` is a white shape), title, message and text `kid_ink` (17:1), the buttons `KidDialogButton` with `kid_accent` text on the style (8.6:1; the theme's white would otherwise override the text appearance), `colorAccent`/`colorControlActivated` peach for an EditText's cursor and underline. Covers `PhoneBookActivity`'s three dialogs (the 112 confirm among them) and `WifiNetworksActivity`'s password dialog in both modes. The parent's `AlertDialogCustom` dialogs take an explicit theme and are unchanged. `KidPaletteTest` pins the theme attribute, the panel (both attributes and the drawable's solid), the button style and every ink >= 4.5:1 on the panel. |
| L1 AppCompat's `buttonStyle` unset | `launcherBaseTheme` sets `buttonStyle` to `accentButton` too, so an XML `<Button>` (an `AppCompatButton`) gets night text on the accent; asserted next to `android:buttonStyle`. |
| N1 `launcher/CLAUDE.md` called the parent's dialogs night | It now says the parent Settings' switches and category titles; the parent's dialogs stay `AlertDialogCustom`'s light ones, the default ones follow M1. |
| N2 the theme contrast loop couldn't fail on its own | Dropped: the equalities to `kid_ground`/`kid_accent`/`kid_ink` cover it, and `ink, accent and light text are readable on the ground and its lift` pins those pairs' contrast. |

Counts after the fix round (main 57ac2a36 merged in): launcher 1263 tests, 0 failures (no new test methods; the
assertions went into `KidPaletteTest`'s P5 test). Still to check on the emulator: a phone-book call confirm and the Wi-Fi
password dialog in light and dark system mode.

## Fixes after the emulator round (2026-10-14)

Launcher findings of `docs/testing/2026-10-14-emulator-round.md` (the music app's are fixed separately). Gestures, the emergency flow and the one clock format are unchanged.

- **N7 words cut between letters at 2.0**: `tile_label` and `contact_name` (Home, phone book) are `ui/WholeWordTextView` - Android's own layout while no shown line ends inside a word without a drawn hyphen ("Innstillin-ger" stays); else the label shrinks in 5 steps to at most 75 % of its size, and at that floor a word too wide for a line ends in "…" (a `TransformationMethod`, the text itself stays whole) - a hyphen, a smaller size or an ellipsis, never a bare letter split. `WholeWordsTest` (5): nb and en labels at 1.0/1.3/2.0 in 3 and 4 columns at 288/320/360 dp, the contacts row (1-5 contacts) and the phone book, against a line-breaker model without hyphenation; nothing changes at 1.0; "Mamma"/"Mormor" at 2.0 shrink and stay whole.
- **N8 the lock ignored a font-scale change; the date wrapped start-aligned**: the lock keeps handling `fontScale` itself (no recreation: crash guard, re-front); `onConfigurationChanged` re-sizes every text in place (`lock/FontScaleRefresh`: back to its sp at the old metrics with `TypedValue.deriveDimension`, non-linear scale included, then set in sp again; the keys skipped), then `sizeKeys` and `GlancePanel.refit()`. `lock_glance_date` is `gravity="center_horizontal"`. `LockFontScaleTest` (4). Why the old sizes also survived `am crash` is not explained - re-check on the emulator.
- **N9 "Til 11:30 PM" in Norwegian** (decision): the status bar is the reference for the format, and on an en_US system it shows "11:30" with no marker at all - so it has no marker word to match. The hour cycle, separator and order stay the system's (12 h here); the marker is a word in Vibb's sentence and follows Vibb's language (the context's own locale since the fix round below): "Til 11:30 p.m." - CLDR's bokmål marker, the one java.time/ICU give for nb (checked: nb "11:30 p.m." / "9:30 a.m."). `ClockFormatTest` +1.
- **N10 the lock's 112 confirm was a light dialog**: `EmergencyCall.confirm` (PIN lock keypad, ring card, time-rule screen) builds with `R.style.KidAlertDialog`, the phone book's night dialog, in both system modes; the flow (confirm, `onCalling`, call, `EmergencyDialer` fallback) is unchanged. Pinned in `LockFontScaleTest`.

Re-check on the emulator: Home at 2.0 in nb and en, 3 and 4 columns, with "Mamma"/"Mormor" contacts and the phone book (no letter splits; a word with "…" only where even 75 % doesn't fit); the lock's glance and keypad after `settings put system font_scale 2.0` and back to 1.0 with the lock showing (and after a screen off/on), the date centred; the rule screen's "Til" with Vibb in nb on an en_US 12 h system; "Ringe 112?" on the lock in light and dark system mode (careful: RING calls at once).

### Fix round after the emulator round's code QA (`qa-34-emu-launcher-code.md`, 2026-10-14)

| Finding | What was done |
|---|---|
| M1 a recycled Home tile kept the old label's fit ("Chro/me" back) | `WholeWordTextView.onTextChanged` asks for a layout when the text changes (not for the rule's own ellipsis `setText`); the fit key holds the text, so the next measure fits the new label. The bookkeeping is the pure `WholeWords.FitState` (`due`, `textChanged`, `sizeSet`); `WholeWordsTest` rebinds a tile "Phone book" -> "Chrome" -> "Phone book" at the same width and size. |
| L1 CJK and Thai labels counted every break as a cut | A line end is a cut only when it is not a line-break opportunity of the label's locale (`WholeWords.breakOpportunities`: `java.text.BreakIterator.getLineInstance`, ICU on Android); nb and en behave as before. Tested: between ideographs (zh, ja) a break, inside "Mamma" none, after a space and a hyphen a break, a two-line Chinese label not a cut. |
| L2 six mutations survived the source-string pins | The view's decisions are pure and tested by behaviour: `cutLines` (a cut, a drawn hyphen, a difference below the threshold, a trailing space, past the shown lines, a break opportunity), `shownLines`, `fitKey`, `FitState` (only a caller's size moves the base), `FontScaleRefresh.walk` (a fake tree, depth first), `ClockFormat.chooseTextLocale` (the context's locale wins, none falls back). Re-run: M2, M2b, M3, M3b, M4b and M6 are killed, and so are the new M1 (no layout on a text change), M1b (a layout for the rule's own change), L1 (no break opportunities) and M6b (the context's locale not passed). |
| N1 "the accessibility text stays whole" | The comment says what is true: the label's own node reads the shown text ("Melding…"); TalkBack reads the full name from the clickable tile or contact's content description. |
| N2 `textLocale` asked `LocaleManager` | `ClockFormat.textLocale` is `context.resources.configuration.locales[0]` (the per-app language, or the system's when Vibb follows the phone - the locale "Til" itself was resolved in); `ClockFormatTest` allows exactly that one read of the context's configuration, never for the format. |

Tests: `WholeWordsTest` 9 (was 5), `LockFontScaleTest` 5 (was 4), `ClockFormatTest` 10.
