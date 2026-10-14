# QA 34 step 5 (code): the night drawer

Reviewed `design-34-s5` at 58172f39 (on 7f5825a9), launcher only: `res/values/colors.xml` (the default colour
theme points at the kid palette), `styles.xml` (`accentButton` as the base theme's `android:buttonStyle`),
`apps/AppFilter.kt` (`packageName !in OWN_PACKAGES`), `AppFilterTest` +2, `KidPaletteTest` +1, `launcher/CLAUDE.md`, the
design doc's status. Checked against design 34 P5, §5 step 5, §7 L2 and the root/launcher `CLAUDE.md`s.

**Verdict: good to merge after M1 (a small theme fix, a new style and a test).** The change is small and does what it
says. The drawer (`activity_list.xml`, `list_apps.xml`, the text row) has no colour of its own: its heading, the
settings/close icons (`fillColor="?android:textColor"`) and the rows are white on `#0C0C14` (19:1). The MIT boundary
holds: one condition and one constant in `apps/AppFilter.kt`, nothing in `ui/list/`. `OWN_PACKAGES` drops both ids. The
music app, a near-miss id and the old `com.kidslauncher.mdm` stay listed. Home and the drawer share `kidListable` (the
drawer has no search), and Home's own `packageName` filter is now redundant but harmless. The global theme change is
mostly an improvement: preference category titles, switch thumbs and the status bar went from `#5555ff` on `#252827`
(2.9:1) to peach on night (9.9:1). One knock-on remains. The theme's default AlertDialogs (four of them kid-facing)
now have peach buttons on a panel that follows the system's dark mode (M1).

## Every consumer of the changed colours

| Consumer | Reads | Before | Now | Note |
| --- | --- | --- | --- | --- |
| Drawer rows, heading, settings/close icons | `android:textColor` on `colorBackground` | white on `#252827` 15:1 | white on `#0C0C14` 19:1 | ok |
| Every `UIObjectActivity` window ground (Settings, Wi-Fi, Bluetooth, Lock, Phone book) | `android:colorBackground` | `#252827` | `#0C0C14` | only light ink sits on it; `item_wifi_network`/`item_bluetooth_device`'s `textColorSecondary` is white |
| Parent Settings `PreferenceCategory` titles | `colorAccent` | 2.9:1 | 9.9:1 | better |
| Parent Settings' 2 `SwitchPreference`s (framework `Switch`, `android:colorAccent` = `?colorAccent`) | thumb accent, track accent 30 % | blue | peach 9.9:1 thumb | ok; the off-track is pre-existing |
| Status/nav bar | `colorPrimaryDark` | `#252827` | `#0C0C14` | ok |
| `PlayLinkBlockedActivity`'s framework `Button` | `android:buttonStyle` -> `accentButton` | white on blue 5.1:1 | `#0C0C14` on peach 9.9:1 | ok, pinned |
| Parent dialogs: `ParentPinDialog`, Settings' `showEditTextDialog`, `ContextMenuActions` (drawer long-press rename), `AppAction`, `EmergencyCall`, `PinLockActivity:707` | `AlertDialogCustom` (light, `colorAccent` `#000`) | - | unchanged | not night, see N1 |
| `dialog_choose_color`/`dialog_edit_text`/`dialog_rename_app` EditTexts | inside `AlertDialogCustom` | - | unchanged | ok |
| Default AlertDialogs: `PhoneBookActivity.kt:184` (112 confirm), `:201` (not allowed), `:210` (call confirm), `WifiNetworksActivity.kt:111` (password + EditText) | buttons and the EditText underline/cursor `colorAccent` on `colorBackgroundFloating` | 2.0:1 dark / 5.1:1 light | 5.1:1 dark / **1.97:1 light** | **M1** |
| XML `<Button>` (AppCompat `buttonStyle` = `Widget.AppCompat.Button`, fill `colorButtonNormal` = accent) | white theme text on peach | none today | 1.97:1 if one is added | L1 |
| Drawer long-press `PopupMenu` | framework popup, no accent | - | unchanged | ok |
| Kid Settings switches/radios, lock glance progress, contact sheet, call screens | own drawables and colours | - | unchanged | ok |

No strings changed, so nb and en are the same here. Both the `#0C0C14` ground and the dialog-panel numbers above hold
for both languages.

## Findings

### M1 - Default AlertDialogs: peach buttons on a light panel in light system mode (1.97:1)

`PhoneBookActivity.kt:184/201/210` and `WifiNetworksActivity.kt:111` build `AlertDialog.Builder(this)` with no theme,
so they use the activity's `alertDialogTheme`. That is AppCompat's `ThemeOverlay.AppCompat.Dialog.Alert`, whose
`android:colorBackground` is `?attr/colorBackgroundFloating` (appcompat 1.7.1 `Base.V7.ThemeOverlay.AppCompat.Dialog`).
`launcherBaseTheme` is `Theme.AppCompat.DayNight` and nothing sets a night mode, so the panel follows the phone:

- dark mode, `#424242`: the peach "Ring"/"Avbryt"/"Koble til" buttons are 5.1:1 (were 2.0:1 in blue);
- light mode, `#FFFFFF`: they are 1.97:1 (were 5.1:1). The Wi-Fi password field's peach underline and cursor are 1.97:1
  too, below the 3:1 a UI component needs.

These are kid-facing. One of them is the 112 confirm when no call rules can be read. In light mode their title and
message are also the theme's white on white (`android:textColor`, which the overlay doesn't override). That part is
older than this step, but this step's knock-on check ("the parent Settings and its dialogs") missed these dialogs.
`launcher/CLAUDE.md` now says the dialogs are night.

**Fix:** in `styles.xml`, add
`<style name="KidAlertDialog" parent="ThemeOverlay.AppCompat.Dialog.Alert">` with
`<item name="colorBackgroundFloating">@color/kid_ground_lift</item>` and
`<item name="android:colorBackground">@color/kid_ground_lift</item>`. Set `<item name="alertDialogTheme">@style/KidAlertDialog</item>`
on `launcherBaseTheme`. The panel is then night in both modes: peach 8.6:1, white 17:1 on `#1C1A2A`. Pin it in
`KidPaletteTest` (the base theme's `alertDialogTheme`, plus accent and ink >= 4.5:1 on its background), and check one call confirm
and the Wi-Fi password dialog in light and dark mode during the emulator pass. `AlertDialogCustom` takes an explicit theme,
so it doesn't change.

### L1 - `accentButton` covers only framework Buttons; an XML `<Button>` would be white on peach

`launcherBaseTheme` sets `android:buttonStyle` only. `AppCompatViewInflater` turns an XML `<Button>` into
`AppCompatButton`, whose default style is AppCompat's `buttonStyle` (`Widget.AppCompat.Button`). That style is filled with
`colorButtonNormal` (`?colorAccent`, peach), and the theme's white text is 1.97:1 on it. No layout has a `<Button>` today, so
this is latent; the implementation notes it but leaves the trap. **Fix:** in `launcherBaseTheme`, add
`<item name="buttonStyle">@style/accentButton</item>`, or a `Widget.AppCompat.Button` child with `kid_ground` text.
Add one assertion next to the `android:buttonStyle` one in `KidPaletteTest`.

### N1 - `launcher/CLAUDE.md` says the parent Settings' dialogs are night

The new section reads: "the drawer and the PIN-gated parent Settings (and its dialogs, switches, ...) are night with the
peach accent". The parent's dialogs (`ParentPinDialog`, `showEditTextDialog`, the drawer's rename/uninstall dialogs) all
use `AlertDialogCustom`, a light dialog with a black accent, which this step doesn't touch. **Fix:** say "its switches
and category titles". The dialogs stay `AlertDialogCustom`'s light ones. The default AlertDialogs follow M1.

### N2 - `KidPaletteTest`'s theme contrast loop can't fail on its own

The test asserts that the three theme colours equal `@color/kid_ground`/`kid_accent`/`kid_ink`, and only then checks
their contrast. A colour below 4.5:1 always trips the equality first (mutation 2), so the `>= 4.5` loop only restates
`kid_*` pairs that other tests already pin. That's harmless. Either drop the loop (less code), or drop the equalities and
keep the contrast if the palette itself is meant to be free to move.

## Build and tests

From `launcher/`, under `~/build.lock`, `./gradlew --no-daemon --max-workers=2 assembleDebug testDebugUnitTest --rerun
-PwarningsAsErrors=true`: BUILD SUCCESSFUL, 1263 tests, 0 failures, 0 skipped (the step's own count).

## Mutations

Each one was applied to the source, run with `testDebugUnitTest --tests '*AppFilterTest' --tests '*KidPaletteTest'`, then
reverted.

| # | Mutation | Result |
| --- | --- | --- |
| 1 | `kidListable` without `packageName !in OWN_PACKAGES` (own package listed) | caught - `AppFilterTest` "Vibb One itself is never listed" |
| 2 | `finnmglasTheme_accent_color` -> `#5555ff` (2.9:1 on the old ground; a theme colour off the palette) | caught - `KidPaletteTest` "the drawer and the parent Settings are Vibb night" (by the equality, see N2) |
| 3 | `accentButton`'s text -> `@color/kid_ink` (white on peach, 1.97:1) | caught - `KidPaletteTest` (the contrast on the accent) |
| 4 | the condition -> `!packageName.startsWith("me.vibb.")` (drops the music app too) | caught - `AppFilterTest` (`me.vibb.music` must stay listed) |

No survivors among the four. Nothing tests the default dialog theme (M1) or AppCompat's `buttonStyle` (L1), which is
why both are findings.

## Counts

0 High, 1 Medium (M1), 1 Low (L1), 2 Nits (N1, N2).
