# QA 34 (design) - a quiet polish, Vibb One, 12/24 h clock, `time_state.zone`

Reviewer: design QA, 2026-10-14. Branch `design-34` at `70b45622` (on main `bd57cafb`). I read it against:
- `docs/design/34-launcher-polish.md` (proposal, with the user's answers in §6) and all 17 PNGs in `34-mockups/`;
- the root, launcher, music and server `CLAUDE.md`s (contract files, MIT directories, design 14, wallpapers, migrations).

I also read the code each proposal touches:
- launcher: `KidAvatars.kt`, `HomeModel.kt` (`tileColor`, `AVATAR_PALETTE`, `gridMetrics`), `HomeGridAdapter.kt`,
  `HomeActivity.refreshApps`, `appdisplay/AppDisplay.kt`, `colors_kid.xml`, `sheet_contact.xml`, `activity_lock.xml`,
  `activity_pin_lock.xml`, `PinKeypadLayout.kt`/`PinLockLayoutTest`, `GlancePanel.kt`, `apps/AppFilter.kt`,
  `ui/list/apps/*`, `styles.xml`/`colors.xml`, `KidSettingsActivity` (wallpaper labels), `StatusReportRequest.TimeState`,
  `TimeRulesRuntime`, every time formatter in `src/main`, `KidVpnService`, the `app_name`/`notification_listener_title` strings;
- music: `LibraryScreen.kt`, `Fit.kt`, the empty-state strings;
- server: `app_display.rs`, `app_icons.rs`, `handlers/devices.rs` (the display save), `partials/app_display_form.html`,
  `migrations/0028`, `0033`, `0044`, `wallpapers.rs`, `handlers/wallpapers.rs`, `time_state.rs`, `device_home.html`,
  `tests/device_api.rs` (`ui_keys`), and every "Vibb" in `src/` and `templates/`.

Contrast numbers below are WCAG 2.x, computed for this review (white = `#FFFFFF`, ground = `kid_ground` `#0C0C14`).
No code was built or changed.

**Verdict: good direction, approve after a revision of the doc.** The proposals are calm, small and mostly
independent; the tones (P1) really do reach 5.3-5.5:1 with white and 3.5-3.7:1 on the ground, the avatar inks 8.9-11:1,
the quiet badge 5.0:1 and the tonal emergency pill 6.6:1, as claimed. Nothing weakens the PIN lock, the glance page's
reading rules (design 25), the emergency flow or a stock gesture, and no debug hook or secret is involved. What needs
fixing before step 1 is mostly in the contracts: the `own` icon's semantics (H1), the wallpaper migration's `kind`
(M1), the migration number (M5), and two places where the doc's own contrast rules aren't met by what it leaves alone
(M3, M4).

Counts: 1 High, 7 Medium, 10 Low, 5 Nits.

## High

**H1 - P1 "`own`": the doc collapses three states into two, and the server code it names doesn't do what it says.**
Today there are three things a parent can mean, and the design 14 code stores only two:
- *nothing chosen* - after P1 the calm tone with the white monochrome glyph;
- *the original logo* - the user's hard requirement, the new `own`;
- *a parent glyph* - design 14's icon + colour.

The form has **two** "own" controls that today mean "no override": the Icon radio "Its own" (`value=""`, combinable
with a label) and the button "Use the app's own" (`action=own`, clears label and icon - `DisplayValues::own()`;
`handlers/devices.rs` ~944, then `keep_row` deletes the row when there's no catalog default). P1 says only "the form's
'Use the app's own' button stores it". Left like that, "label 'Chat' + its own logo" can't be expressed, the radio
"Its own" silently changes meaning from "original" to "tone", and there is no way back to the tone once `own` is
stored except picking a glyph. More places that must change and aren't named:
- `icon_known` is "a key of `ICONS`"; `sanitized()` drops unknown icons and `validate()` refuses them, so `own` must be
  accepted explicitly there (not added to `ICONS`/`app_icons.json`, or the glyph/licence tests break);
- `DisplayValues::is_own()` (label and icon `None`) and `keep_row` decide on that shape - a row with `icon = "own"` is
  not "own" in their sense any more; rename one of them;
- `describe()` would print "with own"; the preview (`app-display.js`, `OWN_PREVIEW`, "its own icon") needs the third
  state; the colour fieldset must be disabled for `own`;
- `policy_app_display` passes `v.color` whenever `icon.is_some()` - for `own` it must send `"auto"` (the doc says the
  colour "is ignored", the wire should say it too);
- the catalog page (`tracked_apps.rs` ~1253, ~2023) uses the same form and `DisplayValues::own()`: say whether a
  catalog default may be `own`;
- launcher: `appDisplayMap` filters icons by `AppGlyphs.ICONS.keys` (would drop `own`), and `renderAppIcon` looks the
  glyph up in `AppGlyphs.ICONS` - `own` must be its own branch before both, or it falls through to the monochrome path,
  which is exactly today's bug.

*Fix:* write the three states into P1 as a table (stored value, wire value, phone render, form control): e.g. Icon radio
"Vibb style" (`""`/no icon → tone), "The app's own logo" (`own`), the 16 glyphs; the button "Use the app's own" becomes
"Reset" (no row) or goes. Say that existing rows keep their meaning (a stored `NULL` icon stays "tone", nothing is
migrated). Tests: server `validate`/`sanitized` accept `own`, `entries` keeps `{label: null, icon: "own"}` without a
catalog default, the colour is `auto` on the wire; launcher `appDisplayMap` keeps `own` with and without a label,
`appIconKey` contains `g=own`, and an older-launcher test that `own` alone drops the entry (today's look).

## Medium

**M1 - P8: "green" is a `color` row; giving it two colours without changing `kind` drops it.**
`wallpapers` row 4 is `kind = 'color'`, `colors = '["#2B8A3E"]'` (`0028`). Fjord is a gradient. `checked_colours()`
wants exactly one colour for `color` and two for `gradient`, and `to_policy()` leaves a row out (with a warning) when
it doesn't validate - so an UPDATE of `colors` alone makes the phone lose the wallpaper and a kid who picked it falls
back to the first allowed one. *Fix:* the migration sets `kind = 'gradient'` for `builtin_key = 'green'`, matches rows
by `builtin_key` (never by id), and a server test asserts every built-in row passes `to_policy()` after all migrations.
Update `WallpaperInkTest`'s colour lists in the same commit and add the three new ones (all take white: 9.6-10.1:1).

**M2 - P2: a shadow baked into the cached bitmap is cut off and leaks into every other surface.**
`HomeGridAdapter` (line ~135) shows the icon through `KidAvatars.circular()` = `RoundedBitmapDrawable.isCircular`,
which crops to the inscribed circle - a shadow drawn in the 4 dp frame (2 dp offset + 6 dp blur reaches ~8 dp) is
mostly cropped away. The same cache also feeds the glance rows (`GlancePanel`, `cachedAppIcon`/`renderAppIcon`), the
time-rule screen and (option) the drawer, so a baked shadow and a grown frame change the visual size and the badge
anchor everywhere, and `sizePx` stops meaning "the circle". Contacts are a tinted `ImageView` background, not a
bitmap, so "contacts get the same shadow" needs a different mechanism anyway. *Fix:* draw depth on the view, not in
the bitmap: `outlineProvider = OVAL` + a small `elevation` (2-3 dp; `outlineSpotShadowColor` black) on `tile_icon` and
on the contact avatar, cache unchanged. If it must be baked, give it its own cache key suffix, don't crop it, and keep
the glance/time-rule renders shadow-free.

**M3 - P1b can't be optional, and P1 leaves out the tiles it doesn't render.**
The doc's principle 3 (glyph >= 4.5:1 on its tile, tile >= 3:1 on the ground) is not met by what P1 leaves alone:
- design 14's parent colours (`app_icons.json` `tile`): peach `#E4763F` 3.0:1, sunset `#DE6C20` 3.35:1, grey `#868E96`
  3.3:1, sky `#1E7ED4` 4.2:1 with white; plum `#4A2545` 1.5:1 and night `#1C1A2A` 1.1:1 against the ground. Peach and
  sunset are also the most saturated fields left on Home - exactly the "mustard" kind of loud;
- the Phone book tile `kid_phone_book` `#37B24D`: white 2.75:1 (fails even 3:1); the Settings tile
  `kid_settings_tile` `#868E96` 3.3:1; `EMERGENCY_AVATAR` `#E03131` is a saturated 76 dp red disc.

*Fix:* make P1b part of step 1 (or its own step right after), with these notes:
- map the six keys onto the tones (keeping the keys, so stored rows stay valid);
- change `server/testdata/app_icons.json` and the launcher's test copy in one commit, and regenerate `app_icons.rs` and
  `AppGlyphs.kt` with `scripts/material-symbols.sh`;
- `AppGlyphsTest`'s `tileColor(seed) == tile` check must follow whatever replaces `tileColor`;
- the server's `tiles_carry_white_and_are_neither_green_nor_red` test raises its floor to 4.5;
- `static/app-display.js` and `OWN_PREVIEW` follow.

Give the Phone book a muted call green (e.g. `#3A7552`: white 5.5:1, ground 3.6:1) and the Settings tile the slate
tone. Make the 112 avatar the muted emergency red of P7 (the tonal `#3A1E24`/`#FF8A8A` pair, or a mid red with white
>= 4.5:1).

**M4 - P3: the sheet goes dark, but its inks are light-sheet inks.**
`sheet_contact.xml` uses `kid_sheet_ink` `#1B1B1F` (name), `kid_missed_text` `#C92A2A` (missed calls), `kid_sheet_muted`
`#495057` (hint, Close) and `kid_message_button`. On `kid_ground_lift` `#1C1A2A` the missed text is 3.1:1 and the dark
inks are invisible. `bg_kid_badge_sheet.xml` rings the badge in `kid_sheet` (white). `kid_sheet_ink` is also used by
Home's window banner (`activity_home.xml:45`, `bg_home_window_lock_again.xml`), and `kid_message_button` by the
time-rule screen's app buttons (`item_lock_app.xml`). *Fix:* list each colour P3 changes and where it is used. Add
`kid_sheet_*` night values (name `kid_core`, hint `kid_core_dim`, missed `#FF8A8A` - 7.5:1 on the lift). Keep
`kid_sheet_ink` for the banner or re-point the banner explicitly. Add every pair to `KidPaletteTest`.

**M5 - P12: the migration is 0075, not 0074.**
Design 26c (Spotify, `0074_music_spotify.sql`) is being merged into main now, so the clock format's migration is
`migrations/0075_clock_format.sql` and deploys after 26c's 0074. *Fix:* rename it in P12 and step 12. Add a note that
sqlx applies the migrations in order and that a server carrying 0075 must also carry 0074 (main's order).

**M6 - Font scale: test at Android's real maximum, not 1.3.**
`minSdk` is 34, and Android 14+ offers non-linear font scaling up to 2.0 (the Jelly Star runs 14+ if it runs this
launcher). P4, P7 and P10 test 1.3 only. There are three more problems:
- `PinLockLayoutTest` reads a budget from XML and can't see wrap-content text growth without modelling it;
- mockup 05b (1.3) shows keys the same size as 05, so "the keys give way" isn't demonstrated;
- below 48 dp the keys stop being 48 dp touch targets (the floor is 32 dp).

*Fix:* add 2.0 to the checks of steps 4, 7 and 10 (emulator: `settings put system font_scale 2.0`). In
`PinLockLayoutTest`, model the emergency button and "Foreldrekode" as `max(minHeight, lines x sp x scale + padding)`.
Assert the keys still stay >= 48 dp at 1.3 and say what is accepted at 2.0 (the floor, never an overlap).

**M7 - P12's status-bar part is over-engineered and under-specified.** The launcher clocks part is small and good. The
status-bar part adds all of this, for one small clock:
- a provisioning step (`appops set ... WRITE_SETTINGS allow`, before adb is blocked);
- a remember/restore state (the `ScreenTimeout` pattern);
- an unknown under `DISALLOW_CONFIG_DATE_TIME`;
- a new status field `clock_status_bar`, which the [S] list doesn't include: the server's status parse, storage, the
  Home Screen page's sentence and a test.

The kid can't change the system value anyway, so "Follow the phone" is effectively "the locale's default". *Fix*
(subject to the user's answer, question 2): ship launcher clocks only. The parent's choice applies to every Vibb One
clock; the status bar keeps the phone's setting (set once at provisioning with `adb shell settings put system
time_12_24 24` if wanted - no app-op, no code). If the status bar stays in, name the server work and the status field
in the contract list. Also complete the clock inventory: `PlayRuntime.kt:244` (install-mode "until") and
`SettingsFragmentLauncher.kt:285` (the pause's "until") use `java.text.DateFormat.SHORT` and are missing. Add a source
scan test that only `ui/ClockFormat.kt` may call `getTimeFormat`/`is24HourFormat`/`getTimeInstance`, like the repo's
other guard tests.

## Low

**L1 - P10b: size the cover from the measured slot, not from a height formula.**
`LibraryScreen` is a `Column` with two weighted spacers. The title/hint, the category tiles' labels and the bar all grow
with the font scale, so `libraryCover(heightDp, hasTiles, hasBar)` from constants will be wrong at 1.3/2.0. *Fix:* a
`BoxWithConstraints` in the weighted area around the carousel. Take the cover as `min(180, maxHeight - title/dots
measured)`, floor 120, and pass it to `carouselSide`/`carouselPeek`/`coverDecodePx`, which use the `CAROUSEL_COVER_DP`
constant today. Below the floor, scroll rather than overlap. `FitTest` keeps the pure clamp.

**L2 - P5: there is no GPL caller for the drawer's list.**
The drawer is `ui/list/` (`AppListActivity`, `ListFragmentApps`, `AppsRecyclerAdapter` - all MIT), and it builds its own
`AppFilter`. Home's own-package filter is in `HomeActivity.kt:697`. *Fix:* say concretely that the filter is one
condition in `AppFilter.kidListable` (already a palchrb-modified MIT file, the existing precedent), with the test on
`kidListable`. On open question 1 I recommend **text only**:
- per-row icons need the MIT adapter to bind GPL-rendered bitmaps;
- they need a second render per app at 30 dp (a second cache entry);
- they must honour `own` (mockup 02 shows Chrome as a tone while mockup 01 shows its own logo).

**L3 - P11's inventory misses some old names.** Missing:
- `KidVpnService.setSession("Kid Phone Filter")`, shown in Android's VPN settings and dialog;
- `provision_qr.html` ("installs Vibb", "make Vibb the device owner", "Vibb's Settings");
- `device_detail.html` ("this version of Vibb");
- `device_advanced.html` and `device_pages.rs:574` ("Vibb's Settings").

Main moved on (PWA-only commits to `7936e6d7`), so rebase step 11 on main and grep `src/` and `templates/` again there.
Confirmed harmless: the label changes nothing for provisioning or updates - the admin component, the QR's
`kids-launcher-mdm.apk` URL and checksum, the catalog row (matched by package/`is_launcher`) and `pendingApkCheck`
don't read the label, and the music app names the launcher only by package.

**L4 - P6 details.** The things P6 leaves open:
- hourglass and moon glyphs don't exist in the launcher (only `app_glyph_menu_book`/`school`). Add them as converted
  Material Symbols with their "Converted from" lines (Apache-2.0 is already shipped).
- Define the new sentence:
  - which number "dagens 1 t 30 min" is (budget + today's extra lift minutes?);
  - the overrun case behind today's "6 av 1";
  - a pure formatter (`1 t 30 min` / `1 h 30 min`, nb/en, `TranslationsTest`).
- Give each row a content description ("Telefonbok, knapp").

**L5 - P2's press feedback sits in the gesture path.** A touch listener on `tile_icon` must return `false`, restore on
`ACTION_CANCEL`/`UP`, and never consume a DOWN. Otherwise tap, long-press and Home's swipe-up/left can change, which
the doc's "None touches a gesture" claims won't happen. With the click still on the cell, a borderless ripple on the
icon only shows if the icon gets `duplicateParentState` (or the ripple is the cell's background with an oval mask).
The cell stays the >= 48 dp target. The top light (white 10 %) lowers white-glyph contrast to 4.3-4.4:1 on every tone,
below the doc's own 4.5:1 - drop it or halve it.

**L6 - P4 mechanics.** `fadingEdgeLength` is one value for both edges. 12 dp top / 32 dp bottom needs a `RecyclerView`
subclass overriding `getTopFadingEdgeStrength`/`getBottomFadingEdgeStrength`, or a gradient overlay. `gridMetrics`
has no row height to "add" (rows wrap). Simpler: `maxLines = 2` at every scale, and `HomeModelTest` keeps only the
metrics it has.

**L7 - Accessibility of the new controls.** P9's segmented control needs radio semantics: two `RadioButton`s or
`stateDescription` + "selected", each half >= 48 dp tall, TalkBack reading "Lyd, valgt". P10's empty-state disc is
decorative (`contentDescription = null`; the headline is the text). P6's emergency text button keeps >= 48 dp and its
label.

**L8 - P13's emulator check needs a zone recipe.** The launcher forces automatic time zone whenever a time policy has a
rule or budget, so the emulator's zone comes from the (emulated) network, often UTC. Phone Setup's zone must equal it.
Give the steps in emulator.md: set the AVD/host zone, or set Phone Setup to the emulator's zone. Also: `TimeState.zone`
must be encoded when set (check `ServerJson`'s `encodeDefaults`/`explicitNulls`) - the launcher test should assert
the JSON, not the data class.

**L9 - The amber tone reads as brown.** `#866237` (hue ~33°) is what Maps gets in mockup 01, and it sits right next to
the "mustard" the user disliked. The step-1 test ("never a hue between 40° and 60°") doesn't exclude it. *Fix:* ask the
user (question 3) or drop amber (six tones). In either case, have the test assert that the output is always one of the
listed tones.

**L10 - P12 form: a missing field keeps the value.** The Home Screen page's launcher form "writes both values" (§5.6).
With a third field, a POST without `clock_format` (an old cached page) must keep the stored value, not reset it to
`system`. Only a present, unknown value is a 400. Add the test.

## Nits

- **N1** `PinLockLayoutTest` asserts keys >= **56** dp at 320×569 dp today. P7 says ">= 52 dp". Adding 8 dp of gap and
  4 dp of button costs about 3 dp per key - say which floor the test keeps.
- **N2** P8 gives only nb names. Add the en `wallpaper_*` strings (e.g. "Fjord", "Evening glow", "Dusk") and the DB
  labels the PWA shows.
- **N3** Say it once: the launcher's kid screens and the music app are always dark (night). There is no light mode to
  design for, and the parent Settings follow the dark AppCompat theme with the new accent (peach on `#252827`
  7.5:1).
- **N4** Step 0: also note `adb shell wm density reset` and `font_scale 1.0` after the checks. The tonal emergency pill
  is 1.3:1 against the ground; that's acceptable because its text and glyph (6.6:1) identify it - keep it that way.
- **N5** The open-question numbering in §6 restarts at 1 after the answered list. Refer to them as "open 1/2" in
  P5/P12.

## Questions only the user can answer

1. **Drawer icons** (open 1): small tone icons per row, or text only as today? (QA recommends text only, L2.)
2. **Status-bar clock** (open 2): accept that only Vibb One's own clocks follow the parent's choice? (QA recommends
   yes, M7.) Or add the adb app-op to provisioning and the status-bar write?
3. **Tones**: keep amber (Maps looks brown in mockup 01), or six tones without it? (L9)
4. **"Own" in the PWA** (H1): the three states as proposed - Vibb style (calm tone, the default), the app's own logo,
   or a chosen glyph - with "Vibb style" the default for apps the parent hasn't touched?
5. Optional: rename the launcher's catalog row (your data, "Vibb") to "Vibb One", so the PWA's app list matches?
