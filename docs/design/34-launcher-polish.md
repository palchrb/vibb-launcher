# 34 - A quiet polish of the launcher and the music app

Status: proposal (2026-10-14), not built. Visual only: no behaviour, rule, gesture or policy change except where a
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
still matter as a stand-in for a large font scale at 320 dp - see P4 and P7.

| Screen | Good | Off |
|---|---|---|
| Home (`01-home-before`) | Night ground, Nunito, big contacts, clear labels, badge and call-badge logic | **Mustard tiles**: Chrome, Disk, Foto, Kalender, Maps are dark yellow - `tileColor` keeps the vibrant swatch's hue (Google's yellow) and darkens it until white reaches 3:1. **Two icon styles side by side**: white glyph on colour (apps with a monochrome layer) next to full-colour own icons (Kamera, Klokke, Meldinger, YouTube). **Flat**: no depth or press state beyond a rectangular grey ripple over the whole cell. **Loud avatars**: saturated blue/yellow/green 76 dp discs are the brightest things on the phone. **Top**: a band of nothing under the status bar. The grid is cut hard at its edges (a half row of icons under the status bar when scrolled); the third contact's call badge is clipped at the right edge |
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
| App name | - | Android shows **"Kids Launcher"** (release) / "Kids Launcher [debug]" in the notification-permission dialog, the ongoing-call notification's header, Settings › Apps and the drawer |

## 2. Principles for this product

1. **Low stimulus.** One accent (peach `#F0A884`) and the night ground. Colour fields are mid-dark and muted; the only
   saturated colours left are functional: call green and emergency red, used small. No streaks, badges that pull,
   confetti, bouncing or looping animation. A count badge stays a plain number.
2. **People and apps read differently.** Contacts are soft, light discs with a dark initial (or their photo); apps
   are mid-dark tone discs with a white glyph. One icon style everywhere the launcher draws an app.
3. **Readable on a 3" screen, in sun and at night.** Body text >= 4.5:1, glyphs on tiles >= 4.5:1 (the tones below
   reach 5.3:1 with white), a tile against the ground >= 3:1. No white surfaces at night (no glare). Text scale is
   honoured - labels may wrap, never shrink below the user's size.
4. **Touch targets >= 48 dp**, fixed sizes for round controls (no shrinking buttons).
5. **Motion is feedback, not decoration.** <= 150 ms, decelerate, no overshoot; nothing moves on its own; with
   "Remove animations" on, nothing animates (`ValueAnimator.areAnimatorsEnabled()`). The system's own activity and
   back transitions stay as they are (they carry predictive back).
6. **Nothing new to read.** No new information on screens (no greetings, tips, counters); copy only gets clearer.

## 3. Proposals (each small, separately shippable)

Flags: **[G]** touches a gesture, **[L]** touches the lock screens, **[C]** touches the policy contract or a shared
contract file, **[S]** needs the server. None touches a gesture.

**P1 - Calm icon tones (fixes the mustard).** *Why:* the yellow-brown tiles and the random hue spread. *What:* seven
tones, mid-dark and muted: slate `#606B80`, blue `#496C9C`, teal `#347470`, plum `#805B9A`, rose `#A45166`,
amber `#866237`, indigo `#6065A9` (white >= 5.3:1, ground >= 3.5:1; no green or red - those mean call and
hang-up/emergency, as in design 14). A new pure `iconTone(swatches, packageName)` (`ui/home/IconTones.kt`, tested)
replaces `tileColor(seedColour(..))` in `KidAvatars.renderAppIcon`: the palette's swatches are scored; a single
dominant hue (Gmail red, Maps…) maps to the nearest tone by hue; a multi-hue icon (Google's four colours: Chrome,
Drive, Photos, Calendar) or a grey one gets a tone chosen by a stable hash of the package, so it never changes and
never lands on mustard. The parent's own icon colours (design 14, `auto`) use the same function. The cache key gains
a version (`|t1`). *Stays:* the monochrome glyph in white, design 14's glyph icons, the 60 dp size, badges. *Risk:*
low; the palette pass already runs off the main thread in `refreshApps`.
- **P1b (optional, [C])**: map design 14's six parent colours onto these tones - `server/testdata/app_icons.json` and
  the launcher copy change their `tile` hexes in one commit (server contrast test, launcher `tileColor` test, the
  PWA preview).

**P2 - Gentle depth and press feedback on app tiles.** *Why:* "flat circles". *What:* the rendered bitmap gains a soft
shadow (y 2 dp, blur 6 dp, black 45 %) and a faint top light (white 10 % → 0 over the upper half, a 1 px inner
highlight at 14 %) - drawn once into the cached bitmap in `KidAvatars` (the tile frame grows by the shadow's 4 dp),
so no elevation or extra views. Pressing: the icon scales to 0.94 and darkens 10 % in 90 ms, back in 140 ms
(`HomeGridAdapter`, a touch listener on `tile_icon`, skipped when animators are off); the rectangular cell ripple
(`selectableItemBackground` in `item_kid_tile.xml`) becomes a circular ripple on the icon only. Contacts get the same
shadow. *Stays:* tap/long-press behaviour, the long-press menu. *Risk:* bitmap size +15 % in the 8 MB LRU.

**P3 - Soft contact avatars, a night contact sheet.** *Why:* the brightest colours on Home are people's initials; the
white sheet glares at night. *What:* `AVATAR_PALETTE` (`HomeModel.kt`) becomes six soft tones with a dark ink
(`#E8B49A` peach, `#9FC3E3` sky, `#E3CF9A` sand, `#A9CDB0` sage, `#C9B3DE` lilac, `#E6AFB4` rose; ink `#1B1B28`,
>= 8.9:1), photos unchanged. `sheet_contact.xml`/`bg_kid_sheet.xml`: the sheet on `kid_ground_lift` with a 26 dp top
radius and a 22 % handle; Ring stays call green; Melding becomes a tonal button (white 8 %, peach glyph); the hint at
`kid_core_dim`. `KidPaletteTest` gains the avatar pairs. *Stays:* the sheet's buttons, order, missed-call text,
`ContactSheet` logic. The call screens and the phone book follow automatically.

**P4 - Home's top, edges and labels.** *Why:* the empty band; the hard cut; ellipsized labels at large text. *What:*
(a) a quiet date line under the status bar - "onsdag 14. oktober", 14 sp/700 at ink 75 % (wallpaper ink), centred,
30 dp; the status bar keeps the time (design 08's decision stands; no clock). Updated on `ACTION_DATE_CHANGED`/
`TIME_SET`/`TIMEZONE_CHANGED` while Home is started; hidden during the ongoing-call card. (b) The grid and the contacts
row fade at their scrolling edges (`requiresFadingEdge`, 12 dp top / 32 dp bottom) instead of cutting icons in half.
(c) Tile labels may take two lines when the font scale makes one ellipsize (`maxLines = 2` only above
`fontScale` 1.0, `gridMetrics` adds the row height) - never auto-shrunk below the user's size. (d) The contact row's
last call badge is no longer clipped (`clipChildren=false` on the row, 4 dp end padding). *Files:* `activity_home.xml`,
`HomeActivity`, `HomeModel.gridMetrics`/`contactRow` (`HomeModelTest`), `item_kid_tile.xml`. *Stays:* swipe-up to the
drawer at the grid's top/bottom, swipe-left to Kid Settings, `onResume`'s order.

**P5 - The drawer in Vibb night, without ourselves.** *Why:* the one screen still in µLauncher grey with a bright
blue accent, and it lists the launcher itself. *What:* `finnmglasTheme_background_color` → `kid_ground`, the accent →
`kid_accent` (also calms the parent Settings' switches and dialog buttons, which share the theme); the list as one
`kid_music_tile`-coloured card with 50 dp rows, 16 sp/700, hairline separators; optionally (Q2) a 30 dp tone icon per
row from the same render cache as Home. Our own package is filtered out of the drawer's list. *Licence:* `ui/list/` and
`apps/` are the MIT directories - the colour change is in `res/`; the own-package filter goes into the GPL caller that
builds the drawer's list, or as a one-line MIT change in `AppFilter` (no new GPL code in those directories). *Stays:*
how the drawer opens and closes, its PIN-gated gear, the list's content and sort.

**P6 - The time-rule screen as a calm card [L].** *Why:* three alarm-coloured buttons for a rule that is meant to
calm down the evening. *What:* `activity_lock.xml` / `LockActivity`: the clock in the glance page's format (`format24Hour="HH:mm"`;
52 sp/700), a small icon in a peach-tinted disc (hourglass for screen time, moon for
bedtime, book for school, a dot for custom), the title, one sentence ("Du har brukt dagens 1 t 30 min." replaces
"6 av 1 minutter brukt i dag"; the rule texts are unchanged), then the usable apps (under a budget lock
the music app is one of them) and the phone book as rows in one card (tone icon, name, chevron), and at the bottom "Nødsamtale" in the
lock's emergency red as a 48 dp text button and "Skriv inn opplåsingskode" in core-dim - the glance page's pattern.
*Stays:* every target and its order of preference, the emergency flow (`EmergencyDialer` fallback), the unlock-code
dialog, Back off, the time-rule logic. *Risk:* the emergency button gets quieter - Q4.

**P7 - The keypad's emergency button and the lock's weights [L].** *What:* `activity_pin_lock.xml`: the emergency
pill becomes tonal (`#3A1E24` with `#FF8A8A` text and phone glyph, 6.6:1, 48 dp tall, same place and action); keys get
a 1 px top highlight; the prompt stays one line where it fits; the glance clock goes 800 → 700. *Stays:*
`PinKeypadLayout`'s sizing and `PinLockLayoutTest` budget, FLAG_SECURE, no transitions (25b), "Foreldrekode", the
emergency flow. *Risk:* as P6 - Q4.

**P8 - Softer built-in wallpapers [S].** *What:* a server migration updates three built-in rows' colours and labels,
keeping their ids and `builtin_key`s: green → "Fjord" `#2A4A4F→#101820`, sky → "Kveldshimmel" `#34406A→#14141F`,
sunset → "Skumring" `#5A3A4A→#1A1420` (gradients at 160° as today); the launcher's `wallpaper_green/_sky/_sunset`
strings (nb/en) follow in the same commit. A phone that has one picked gets the softened version at its next sync
(`WallpaperInk` recomputes; all three take white ink). *Stays:* the policy shape (`launcher_ui.wallpapers`), photos,
Natt as the default. Not [C]: no key or field changes.

**P9 - Kid Settings' sound choice.** *What:* Lyd/Lydløs as one segmented control (a white-8 % track, the chosen half
peach with night text, both halves the same height; `activity_kid_settings.xml`, `QuickControls` rendering only).
*Stays:* `SoundMode` rules, the rows, the picker.

**P10 - The music app's empty states and its fit.** *What:* (a) `LibraryScreen` without entries: a 104 dp night disc
with the headphones glyph in peach, "Her kommer musikken din" / "Your music will appear here" and one line "Mamma eller
pappa legger til musikk, podkaster og bøker. De dukker opp her av seg selv." (nb/en; the parent wording follows the
launcher's "mamma og pappa" captions). Nytt ("Ingenting nytt akkurat nå") and search ("Fant ingenting") get the same
disc with the bell / search glyph, no extra sentence. (b) **Fit**: the library's carousel cover is sized from the room
left after the category tiles and the now-playing bar (`Fit.kt`: a `libraryCover(heightDp, hasTiles, hasBar)` between
the 120 dp floor and today's 180 dp; `FitTest` at 569/640/732 dp tall), so the tiles are never covered. (c) Covers,
category tiles and the now-playing bar get P2's soft shadow. *Stays:* mockup v12 as the reference (this is a delta,
recorded in design 20's mockup note), every behaviour (vibb), the player.

**P11 - The app name.** *What:* `app_name` "Kids Launcher" → **"Vibb"** (debug "Vibb (debug)") in
`res/values/donottranslate.xml` and `src/debug`. Shown in the notification-permission dialog, notification headers,
Settings › Apps, the Home-role picker. The package and component names are unchanged (no reinstall, no
re-provisioning). The PWA already says "Vibb's Settings". Q1.

**Motion, across all of the above.** No new screen transitions: the system's stay (predictive back). New motion is only
the press feedback (P2) and the existing sheet slide (`kid_sheet_in/out`, kept at <= 220 ms decelerate, none when
animators are off). The lock keeps no transitions; the music carousel keeps its spring (already off with "Remove
animations").

## 4. Mockups (`docs/design/34-mockups/`)

| # | Screen | Proposals shown |
|---|---|---|
| 01 | Home | P1 tones, P2 depth, P3 avatars, P4 date line and edge fade (Kamera/Klokke/YouTube: apps without a monochrome layer keep their own icon, softened - see Q2) |
| 02 | Drawer | P5 (with the optional row icons) |
| 03 | Contact sheet | P3 |
| 04 | Time-rule screen (screen time) | P6 |
| 05 | PIN keypad | P7 |
| 06 | Kid Settings | P8 wallpapers, P9 |
| 07 | Music, empty | P10a |
| 08 | Music, library | P10b-c (tiles above the bar) |

Sources in `34-mockups/src/` (HTML/CSS, Nunito from `launcher/app/src/main/res/font`, the glyphs generated from the
launcher's vector drawables by `glyphs.py`; Material Symbols, Apache-2.0). Render:
`NODE_PATH=<playwright>/node_modules node docs/design/34-mockups/src/render.js [chromium]`. Glyphs for Chrome and
Drive are stand-ins (the real monochrome layers differ); the tones are exact.

## 5. Build steps (in order; each its own commit and emulator check at `wm density 240`, nb and en)

0. `docs/testing/emulator.md`: `adb shell wm density 240` for Jelly Star screenshots (the AVD default is 240×427 dp).
1. **P1**: `IconTones.kt` + `IconTonesTest` (each tone white >= 4.5:1 and ground >= 3:1; Google's four-colour seed set
   never yields a hue between 40° and 60° at low lightness; a single-hue red → rose; stable per package; parent `auto`
   uses it); `KidAvatars` wired, cache key versioned. Check: Home and the glance page's app icons.
2. **P2**: shadow/highlight in the render (a pure `tileFrame(sizePx)` for the padded bitmap size, tested), press
   feedback (Robolectric: no animation with the duration scale 0), circular ripple. Check: Home, frame times
   (`dumpsys gfxinfo`, no frame > 32 ms from icon work - design 08's check).
3. **P3**: palette + sheet (`KidPaletteTest`: avatar ink >= 4.5:1, sheet texts >= 4.5:1 on `kid_ground_lift`). Check:
   Home, phone book, sheet, call screens.
4. **P4**: date line, fading edges, two-line labels (`HomeModelTest`: row height at font scale 1.0/1.3), badge clip.
   Check: Home at font scale 1.0 and 1.3, with the call card, with 5 contacts.
5. **P5**: theme colours, list rows, own package filtered (a JVM test on the filter). Check: drawer, parent Settings.
6. **P6** [L]: `activity_lock.xml`, `LockActivity` (a pure `timeLockRows(...)` for the rows, `LockActivityModelTest`; the
   new strings in `TranslationsTest`). Check: screen time, bedtime, school, a custom rule with exempt apps; 112 via the
   emergency row with Telecom refusing (emulator.md §6 item 9).
7. **P7** [L]: keypad and glance (`PinLockLayoutTest` and `LockGlanceLayoutTest` unchanged and green). Check via the
   emulator console screenshot (FLAG_SECURE).
8. **P8** [S]: migration + launcher strings, one commit (server `cargo test` incl. the wallpaper tests; launcher
   `TranslationsTest`). Check: Kid Settings picker, each on Home and the lock.
9. **P9**: Kid Settings. Check at 320/360/411 dp.
10. **P10** (music): strings nb/en, `FitTest` cases, empty-state composables; `./gradlew ... -PwarningsAsErrors=true`.
    Check: library empty / full with and without the now-playing bar at 569 dp, Nytt and search empty.
11. **P11**: label. Check: the notification-permission dialog, a notification header, Settings › Apps.

Every step: `./gradlew assembleDebug assembleRelease testDebugUnitTest` (launcher/music as touched), screenshots
before/after into the step's QA note.

## 6. Questions for the user

1. **The name**: "Vibb" as the launcher's name on the phone (Android's dialogs, notifications, Settings)? Or something
   else ("Hjem", "Vibb hjem")?
2. **App icons**: (A, proposed) calm tone + white glyph where the app has a monochrome icon, the app's own icon
   (slightly softened) where it hasn't - as mockup 01; or (B) every app's own full-colour icon, softened, like a stock
   phone. And should the drawer show the same small icons (mockup 02) or stay text only?
3. **The date line on Home** (P4a): yes, or keep the top empty?
4. **Emergency prominence**: on the keypad and the time-rule screen, a tonal red button / red text link (as the glance
   page already does) instead of a solid red button - fine for a 9-year-old, or keep solid red there?
5. **Wallpapers**: soften green/sky/sunset as in mockup 06 (a phone that picked one changes with it)?
