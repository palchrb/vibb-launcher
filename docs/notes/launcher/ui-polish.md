# Launcher notes: UI polish (design 34), wallpapers, icons, avatars

Moved from launcher/CLAUDE.md on 2026-11-01.

## UI polish: Home, Nunito, kid Settings, wallpapers (handy step 8, design `docs/design/08-ui-polish.md`, at the monorepo root)

- **Home**: no clock/date (the status bar has the time; lock task keeps `SYSTEM_INFO`). Pure `ui/home/HomeModel.kt` (`HomeModelTest`): `showPhoneBookTile` (managed or fail-closed calls always show the tile, even with calls off - only unmanaged hides it), `homeGrid` ends with `GridTile.Settings` (no long-press: only `GridTile.App` has the menu), `gridMetrics(contentWidthDp, cols)` scales the mockup (288 dp content, 60 dp icons, 13 sp labels, gaps 8/14 dp via `GridGapDecoration`), `contactRow` (76 dp/28 dp centred, 64/16 when that doesn't fit, else a scroll with half an avatar peeking; since design 34 P4 a row that fits is never wider than the content, `contactItemDp`, below); the icon tile colour was `tileColor` until design 34 P1 replaced it with the six tones (`IconTones.kt`, below). The swipes are pure `ui/home/HomeSwipe.kt` (`homeSwipe`, `HomeSwipeTest`): the detector is fed once, from `HomeActivity.dispatchTouchEvent` in window coordinates, so every swipe is decided from its own DOWN (qa-34-s4-code M1: it was fed by the grid, the row and the activity, and a DOWN on a contact was never seen); `swipeStart` classifies the DOWN (contacts row, grid, a clickable card - the call card, the music card, "Lock again" keep the whole touch, no swipe - or blank). `swipeStart` and both "can scroll" values are recorded at the DOWN (qa-r1-apps-code L1), so a left swipe that scrolls the row to its end stays a scroll. Up opens the drawer only when the grid can't scroll further - from anywhere, a contact included: new with this fix (before, a contact kept the vertical drag and nothing opened, while the row's empty space already opened the drawer), approved by the user on 2026-10-18; left opens kid Settings, except that a left swipe starting in a contacts row that can still scroll stays its scroll (on a contact in a row that fits, it now opens Settings). A swipe that opened something reaches the views as a cancel, so it is never also the tap of the contact or tile it ended on.
- **Icons**: `KidAvatars.renderAppIcon` - monochrome layer (mutated, tinted white) on the app's tone (design 34 P1, below; was `tileColor` of the palette seed), else the full adaptive icon, else a legacy icon on a light disc. **Rendered only in `refreshApps` on `Dispatchers.Default`** (with the palette pass); `HomeGridAdapter.onBind` only reads the key→bitmap map (slate placeholder). Cache key = app + size + density.
- **Font**: Nunito 600/700/800 bundled in `res/font` (and 300, `nunito_light`, only for the glance's profile clock - design 59d JL-2 - and never in `nunito.xml`'s family, where a normal 400 request would pick it over 600) (static instances of the variable v3.602 from google/fonts, pinned commit + SHA-256 in `scripts/nunito-static.sh`; OFL in `assets/licenses/OFL.txt` and in Open Source Licenses; no Reserved Font Name; `FontLicenseTest`). The base theme sets `@font/nunito` (normal = 600, bold = 700); names/titles use `@font/nunito_extrabold`. No `sans-serif*` families in layouts (tested). An APK resource, so the in-call screen gets it before the first unlock too.
- **Kid Settings** (`ui/kidsettings/KidSettingsActivity`, replaces `QuickControlsActivity`): opened by the Settings tile and the left swipe; the same device-owner switches (`QuickControls`), the wallpaper picker, nothing PIN-gated because nothing changes a rule. Pure `kidSettingsModel`/`controlsSection` (`KidSettingsModelTest`): `NotOwner` / `NoPolicyYet` (no cache, or only the last enforced plan) / `Unreadable` (corrupt cache, fail closed) / `NoneEnabled` (mask 0: no card) / `Rows`, each with its own text and logged (`describe()`, tag `KidSettings`); a rejected or undecodable fresh policy never reaches the cache, so the switches stay those of the last accepted one. Re-renders when a sync stores a new policy. `KidScreensEscapeTest` fails if `ui/kidsettings`, `ui/quickcontrols`, `ui/wallpaper` or `QuickControls.kt` reference `Settings.ACTION_*`, `Settings.Panel`, the system wallpaper pickers, app details or `Intent.createChooser`. Back buttons are 48 dp touch targets (44 dp circle).
- **Wallpapers** (`ui/wallpaper/`, pure `Wallpapers.kt`/`WallpaperInk.kt`/`WallpaperApplyPlan.kt` with tests): `launcher_ui.wallpapers` (DTO `PolicyWallpaper`; missing = navy) is the parent's allowed list; the kid's pick is CE prefs `wallpaper_state.kid_wallpaper_id`; `effectiveWallpaper` = pick if allowed and cached, else the first usable, else navy. `WallpaperStore` (one background thread, everything caught) holds the shown wallpaper, its ink and **one** decoded photo (RGB_565, sampled, ≤ 1080×2400 checked first); `WallpaperStore.sync` runs after every accepted sync (after `ContactPhotos.sync`): downloads allowed images (`GET api/devices/wallpapers/{hash}`, ≤ 3 MB, hash checked, temp + rename, `filesDir/wallpapers`), deletes every image no longer allowed, then applies at once. Nothing wallpaper-related in DE storage.
- **System wallpaper** (`WallpaperApplier`, `SET_WALLPAPER` permission): pure `wallpaperApplyPlan` - only while managed, as device owner, when `isWallpaperSupported`/`isSetWallpaperAllowed`, only when the wanted key (home fill, lock fill, size) isn't in place, at most one attempt per key per day (`setBitmap` returning 0 is a failure, logged, no fight), never on the main thread, never on resume - only after a sync, a pick or the face's edges (`WallpaperStore.faceChanged`, design 59d step K). **In own mode photos go on the home screen only**: the lock screen gets navy unless the parent ticked "also on the lock screen" for that photo (`lockScreenFill`) - the opt-in governs own mode's Android wallpaper only. **As Android's face** (design 59d step K, the user's decision R1-1) Android's lock wallpaper is the glance's own ground - `lockWallpaper`'s choice, a photo included whether or not it is ticked (the glance already shows it to anyone holding the phone; the only new audience is SystemUI's face before the first unlock after a reboot) - with the glance's scrim baked in (`lockScreenFill(.., glance)`, `GlanceGround`, `lockScreenScrim`; the key gains `lockscrim=`); the night mark only while no ground is known or its photo can't be decoded (R1-6, Home's half is still set). Android keeps its own copy of a set wallpaper in system storage. Unmanaged: `resetIfOurs` puts navy back before `AppEnforcer` lifts the restriction.
- **`HardeningRestriction.SET_WALLPAPER`** (`DISALLOW_SET_WALLPAPER`): launcher-only, no server switch (`value()` null, default on) - set while managed, cleared when unmanaged, not lifted by override/pause; the device owner is exempt (AOSP `isSetWallpaperAllowed`).
- **Drawing**: Home, the phone book and kid Settings show the system wallpaper behind their window (`UIObject.showsSystemWallpaper`); when it is ours and current (`systemShowsOurs`) the root is transparent, otherwise `WallpaperGround` draws the fill (colour, 160° gradient, the shared photo) - never two of our full-screen bitmaps. **Home always draws its own ground** (since 2026-10-25, PLAN "Home's wallpaper copy: a one-frame zoom step at the hand-over", branch `launcher-small-a`; design 08's last section): `WallpaperGround`'s FULL ground under the grid whenever the content is on the window (pure `ui/home/HomeGround.kt` `homeGroundDue(contentShown)`, `HomeGroundTest`) - no hand-over to Android's wallpaper, so neither the black frames of the app-close transition (2026-10-24's fix, measured on `jelly36`: 2-3 black frames at 30 fps before, 0 after, `docs/testing/2026-10-24-home-round-run.md`) nor the one-frame zoom step when the copy went (the user's 24 fps video: Android renders the photo at a slightly different scale); design 59d step B's face-leave mark is gone with the 2026-10-24 arrival window (it is covered). Never at a lock-up: it is the content root's background, which isn't on the window while LOCKED or at a dark lock-up, and Home's snapshot stays the black task description; Back needs nothing. The phone book and kid Settings draw the same copy (`ownGround = true`, qa-launcher-small-a-code Q-1: no step on the switches between them and Home); they and Home are the only screens with `windowShowWallpaper`. `WallpaperGround.apply` keeps an unchanged ground's drawable (view tag `wallpaper_ground_key`). `WallpaperInk`: white or #1B1B1F (the worst stop per ink; photos: mean ± sd of a 32×57 render in linear light, once per hash), a black or white scrim up to 0.45 (photos ≥ 0.15) to reach 4.5:1 (white ink judged on Kid Settings' card + track since qa-34-s10-code M1), a label shadow when the cap can't; the ink also sets the status/navigation-bar icons.
- **Fix round (qa-08-code.md)**: `AppEnforcer.clearHardening` lifts the off restrictions in `hardeningClearSteps` order - `WallpaperApplier.resetIfOurs` (navy, else `WallpaperManager.clear`) right before `DISALLOW_SET_WALLPAPER`, which stays set until the reset worked; each step caught. `setBoth` sets the lock wallpaper before the home one; a half-applied key is kept as `partial_key`, a failed reset keeps the record (retried every pass). A photo of ours that is no longer allowed or cached (`revokedImageShows`) is replaced on every sync, not once a day, with a reset as fallback. Picker thumbnails are tile-sized squares, decoded once (pending/failed sets, 8 MB LRU), and only redraw the picker; `State.systemShowsOurs` is worked out on the store thread (no binder call per render). Home icons are rendered from `constantState.newDrawable().mutate()` (the drawer's cached drawable is never touched off-thread). `KidScreensEscapeTest` also covers `HomeActivity`, `ui/home`, `PhoneBookActivity`/`Adapter` and plain-string actions.
- **Not device-tested**: the screenshot checklist and device checks in `08-ui-polish.md` ("Implementation status") - in particular `setBitmap` under `DISALLOW_SET_WALLPAPER` on the Jelly Star, the lock screen showing navy for a photo, recents matching Home, the Quick Controls "no switches" root cause on the emulator, frame times and heap.

## Six calm icon tones and three icon states (design 34 P1, `docs/design/34-launcher-polish.md`, at the monorepo root)

- **Tones** (pure `ui/home/IconTones.kt`, `IconTonesTest`): six mid-dark, muted tones - slate `#606B80`, blue
  `#496C9C`, teal `#347470`, plum `#805B9A`, rose `#A45166`, indigo `#6065A9` (`ICON_TONES`; white >= 5.3:1, >= 3.5:1
  on the night; no amber, green or red). `iconTone(swatches, packageName)` replaced `tileColor(seedColour(..))` (both
  gone, with `TILE_GREY`): a fixed tone for the known multi-colour apps (`FIXED_TONES`: Chrome, Drive, Photos, Gmail,
  Calendar, Maps, ... - mockup 01), else one dominant hue -> the nearest chromatic tone (`nearestTone`; slate is never
  a hue's tone), else (a second hue with >= 25 % of the coloured part, or < 15 % in colour, or nothing) a tone by
  `String.hashCode` of the package (`hashedTone`, stable; slate without a package). Always one of the six.
  `KidAvatars.toneOf` feeds it `swatchesOf` - the opaque pixels (alpha >= 128) of a 48 px render of the icon's colour layers, counted per 4-bit colour; never androidx Palette, whose default filter drops orange and brown (qa-34-s1-code L2).
- **Three states** (QA H1; `appdisplay/AppDisplay.kt`): `AppDisplayEntry.icon` `null` = **Vibb style** (default),
  `OWN_ICON` = `"own"` = **the app's own logo** (`ownLogo`), else a glyph. `appDisplayMap` accepts `KNOWN_ICONS` (the
  glyphs + `own`; `own` always with `auto`) - an older launcher's set (the glyphs alone) drops `own` (with no label the
  entry is gone, today's look). `appIconKey` gives `|g=own|c=auto`.
- **`renderAppIcon`** follows the pure `iconRender(entry, hasMonochrome)` (`IconRender` OwnLogo / Glyph / VibbMono, `IconTonesTest`, qa-34-s1-code L4), which checks `own` first: `drawOwnLogo` - an adaptive icon's background + foreground in their original
  colours at 1.5x, never the monochrome layer, no tint; a legacy icon inset 1/8 on `kid_icon_fallback` `#F1F3F5`; both
  clipped to the circle. Then a parent glyph (`renderSymbolIcon` on the colour's tile, or the app's tone for `auto`).
  Then Vibb style: a monochrome layer white on the app's tone, any other icon `drawOwnLogo` (nothing to tone - a legacy
  icon is no longer drawn on its colour).
- **Parent colours** (QA M3): `app_icons.json`'s keys stay, their tiles are tones (peach rose, plum plum, sky blue,
  sunset indigo, night teal, grey slate); the generated `AppGlyphs.COLORS` follows. **Fixed tiles**: the Phone book
  `kid_phone_book` `#3A7552` (white 5.5:1; also the time-rule screen's phone book row), Settings `kid_settings_tile`
  = slate, the grid's placeholder slate (`KidPaletteTest`). The 112 avatar's tonal pair is step 3's.
- Not emulator-checked yet: Home and the glance with an app in each state.

## Soft avatars and the night contact sheet (design 34 P3, `docs/design/34-launcher-polish.md`, at the monorepo root)

- **Avatars** (pure `ui/home/HomeModel.kt`, `HomeModelTest`): a contact without a photo is a soft, light disc with a
  dark initial - `AVATAR_PALETTE` peach `#E8B49A`, sky `#9FC3E3`, sand `#E3CF9A`, sage `#A9CDB0`, lilac `#C9B3DE`, rose
  `#E6AFB4`, all with `AVATAR_INK` `#1B1B28` (>= 8.9:1), picked by contact id (`floorMod`; append, never reorder - a
  contact keeps its colour). Photos are unchanged. Every surface that binds a contact (`KidAvatars.bindContact`: Home,
  the phone book, the sheet, the glance rows, the call screen and Home's call card) gets them. The 112 avatar
  (`EMERGENCY_AVATAR` = `kid_emergency_tonal` + `kid_emergency_text`, kept equal by `KidPaletteTest`, the pair P7's pill
  uses) is `#FF8A8A` on `#3A1E24` (6.6:1), whatever the id, with a 3 dp `#FF8A8A` ring drawn by `bindContact`
  (`EMERGENCY_RING_DP`) - the disc alone is ~1.1-1.3:1 on the night; every other branch restores `bg_kid_circle` (the
  phone book recycles views). It shows only on Home's contacts row, in the phone book (its no-rules tile included) and
  on the sheet: the call screens and Home's call card show the silhouette for emergency calls (`callAvatar`), and the
  glance rows never pass `isEmergency`. The sheet's ring (`avatarRingColour`) is the contact's colour, but the red ink
  for an emergency number (in the disc's colour it would cover the disc's red ring).
- **The sheet at night** (`sheet_contact.xml`, `bg_kid_sheet.xml` 26 dp top radius): `kid_sheet` is `kid_ground_lift`
  (also the missed badge's ring, `bg_kid_badge_sheet`), the name `kid_core`, the hint and Close `kid_sheet_muted` =
  `kid_core_dim`, missed calls `kid_missed_text` `#FF8A8A` (7.5:1), the handle white 22 %, Message the tonal
  `kid_sheet_button` (white 8 %) with `kid_core` text and a peach glyph; Call stays `kid_call_button` - the one call
  green `#29833B` (white 4.77:1), also the answer button (`bg_call_green`) and Home's call card (`bg_home_call_card`).
  `kid_sheet_ink` is only Home's window banner now; `kid_message_button` went with the time-rule screen's app buttons (P6).
  `KidPaletteTest` parses `sheet_contact.xml` and checks every text colour and glyph tint against its composited
  ground (`contrast` composites a translucent ink and refuses a translucent ground). Buttons, order, behaviour and
  `ContactSheet` unchanged.

## Quiet depth, press feedback, the badge colour (design 34 step 2, P2, `docs/design/34-launcher-polish.md`, at the monorepo root)

- **Depth on the view, never in the bitmap** (QA M2): `ui/home/PressFeedback.depth` gives Home's `tile_icon` and the contact avatar (`contact_avatar`, Home's row and the phone book) an oval `ViewOutlineProvider`, `elevation` 2 dp and black shadow colours. The icon cache, its sizes and the glance/time-rule renders are untouched. The parents don't clip the shadow (`tile_frame` `clipChildren=false`; the contact item already had it). Elevation changes the drawing order among siblings, so the badges are lifted to the same 2 dp with no outline of their own (`KidBadge` style: `elevation` + `outlineProvider none`; the call badge in `PressFeedback.contact`). No top light.
- **Press** (pure `PressMotion`, `PressFeedbackTest`): the icon/avatar goes to scale 0.94 and a 10 % black oval foreground in 90 ms, then back in 140 ms, decelerating with no overshoot. It is driven by the cell's `setOnTouchListener`, which **always returns false**: `onAction` answers false for every action and the listener returns what `onAction` returns (a source check pins this). The click, long-press, Home's swipe-up/left and the contacts row's scroll see the same events as before. Restored on UP/CANCEL. In the scrolling parents (grid, contacts row, phone book) the press waits `ViewConfiguration.getTapTimeout()`, as Android's pressed state does, so the start of a scroll doesn't make the icon twitch. A tap shorter than that still gets the press and the release on UP. With "Remove animations" on (`ValueAnimator.areAnimatorsEnabled()` false) nothing animates: the state jumps, and a quick tap shows no scale at all.
- **The press follows the finger** (qa-34-s2-code M1): the listener passes `PressMotion.inside` (the cell's bounds widened by the scaled touch slop, as `View.onTouchEvent` tests). A MOVE outside releases as a CANCEL does, and everything up to the next DOWN is ignored, so a swipe that starts on an icon never carries a pressed icon along and a lift outside never replays the press. `PressMotion.reset` (from `onViewRecycled` in `HomeGridAdapter`/`PhoneBookAdapter` and on the cell's detach) puts a cell back at rest; the motion lives in the view tag `R.id.press_motion` (`res/values/ids_press.xml`). The listener carries `@SuppressLint("ClickableViewAccessibility")`: it never consumes, so the click still goes through `performClick`, and accessibility clicks don't use touch.
- **Ripple and focus** (`PressFeedback.cellBackground`, built once per icon size, view tag `R.id.press_cell_size`): the cell keeps the click (>= 48 dp target); its background is a `RippleDrawable` in the theme's `colorControlHighlight`, masked to an oval of the icon's size where the icon sits (grid 4 dp below the cell's top, contacts 10 dp). It is drawn under the elevated, opaque icon, so it shows only as a thin ring while the icon is scaled down - and focus would not show at all. Keyboard/D-pad focus therefore has its own static ring: the cell's foreground is a `StateListDrawable` whose `state_focused` item is a 2 dp `kid_accent` oval ring 4 dp outside the icon (above the icon; nothing in any other state).
- **Badge**: `kid_badge` is `#B4532C` (white 5.0:1; it was `#D9480F`, 4.3:1). It also colours the contact sheet's badge (`bg_kid_badge_sheet.xml`, on the sheet) - one badge colour everywhere. Badges are never animated (`bindBadge` only sets text and visibility).
- **Translucent icon backgrounds**: an adaptive icon without a monochrome layer is drawn from its own background layer; if that layer is translucent, the oval shadow shows through as a faint grey disc. Rare and accepted; look for it in the emulator pass.
- GPL code in `ui/home/` (not the MIT `ui/list/`, `apps/`). Not emulator-checked yet: the screenshots and the gesture checks (tap, long-press menu, swipe-up to the drawer, swipe-left to kid Settings, the contacts row's scroll, animations off).

## The drawer in Vibb night, text only, without Vibb itself (design 34 step 5, P5, `docs/design/34-launcher-polish.md`, at the monorepo root)

- **Colours through `res/` only** (QA L2: the drawer is the MIT `ui/list/`, so no new code there): `res/values/colors.xml`'s
  default colour theme now points at the kid palette - `finnmglasTheme_background_color` = `@color/kid_ground`,
  `finnmglasTheme_accent_color` = `@color/kid_accent`, `finnmglasTheme_text_color` = `@color/kid_ink` (was µLauncher's
  `#252827`/`#5555ff`/`#fff`). Both `launcherBaseTheme` and `colorThemeDefault` (applied over it in `UIObject`) read
  them, so the drawer and the PIN-gated parent Settings (its switches and category titles, the status bar via
  `colorPrimaryDark`) are night with the peach accent: white 19:1, peach 9.9:1. The parent's dialogs (`ParentPinDialog`,
  Settings' edit-text dialog, the drawer's rename/uninstall, the PIN lock's Parent code) keep their explicit light
  `AlertDialogCustom` (qa-34-s5-code N1). `EmergencyCall.confirm` ("Ringe 112?" on the PIN lock, its ring card and the
  time-rule screen) takes `KidAlertDialog` explicitly since the emulator round of 2026-10-14 (N10), like the phone book's.
- **`KidAlertDialog`** (qa-34-s5-code M1): the base theme's `alertDialogTheme`, so every `AlertDialog.Builder(this)`
  without a theme - the phone book's call, not-allowed and 112 confirms, the Wi-Fi password dialog - is night in both
  system modes (before, AppCompat's panel followed the phone's dark mode: in light mode the peach buttons were 1.97:1 and
  the white text invisible). The panel is `kid_ground_lift` (its own `bg_kid_dialog` window background - AppCompat's is
  a white shape), title, message and text `kid_ink` (17:1), the buttons `KidDialogButton` in `kid_accent` (8.6:1, set on
  the style so the theme's white can't win), an EditText's cursor and underline the accent. The drawer stays a text list (the user's answer; the row's
  icon view is `gone`, no per-row icons), its header and its buttons, the swipe-up that opens it, the long-press menu and launching are unchanged
  (the drawer has no search).
- **`accentButton`** (`styles.xml`): the base theme's `android:buttonStyle` and, since qa-34-s5-code L1, AppCompat's
  `buttonStyle` (an XML `<Button>` inflates as `AppCompatButton`, which reads that one) - `Widget.AppCompat.Button.Colored`
  with `kid_ground` text. A button sits on `colorButtonNormal` = the accent, and the theme's white text was 1.9:1 on
  peach (fine on the old blue). Today's only user is `PlayLinkBlockedActivity`'s framework OK.
- **Vibb One is never on its own list**: one condition in `AppFilter.kidListable` (`apps/AppFilter.kt`, MIT like the
  file - the existing palchrb-modified precedent): `packageName !in OWN_PACKAGES` (`me.vibb.launcher`,
  `me.vibb.launcher.debug` - both, so a debug build next to a release one lists neither). Home's own
  `packageName` filter in `refreshApps` stays (now redundant). The music app is listed as before.
- Tests: `AppFilterTest` (both own ids refused in and out of install mode; the music app, a near-miss id and the old
  `com.kidslauncher.mdm` still listed; `OWN_PACKAGES` equals `app/build.gradle.kts`'s `applicationId` and
  `applicationId + applicationIdSuffix`), `KidPaletteTest` (the three theme colours are the kid palette's, text and
  accent >= 4.5:1 on the ground (by the equalities - the palette's own test pins the pairs), both styles read them,
  both button styles are `accentButton`, its text on the accent >= 4.5:1, `KidAlertDialog` as `alertDialogTheme` with its
  panel, window background and every ink >= 4.5:1 on the panel, the drawer's row sets no text colour of its own).
- Not emulator-checked yet: the drawer and parent Settings (with a switch) at `wm density 240`, nb and en; a phone-book
  call confirm and the Wi-Fi password dialog in the system's light and dark mode.

## Home's edges and labels (design 34 step 4, P4, `docs/design/34-launcher-polish.md`, at the monorepo root)

- **The top stays empty** (the user's answer): no date line, greeting or anything else above the contacts; `HomeEdgesTest` pins the order of `activity_home.xml`'s children.
- **Fading edges** (pure `ui/home/HomeEdges.kt`, `HomeEdgesTest`): the grid is `ui/home/FadingRecyclerView` (`activity_home.xml`: `requiresFadingEdge="vertical"`, `fadingEdgeLength` = `FADE_LENGTH_DP` 32 dp, the only length Android has). It overrides `getTop/BottomFadingEdgeStrength` with `fadeStrength(remainingPx, edgePx, lengthPx)`, so the top fades up to `FADE_TOP_DP` 12 dp and the bottom up to `FADE_BOTTOM_DP` 32 dp, ramping with the distance left to scroll (the scroll offset at the top, range - extent - offset at the bottom, via `remainingScrollPx`: at least 1 px while `canScrollVertically` says there is more) like ScrollView's, so it never snaps on or off (qa-34-s4-code L1); an edge the grid can't scroll past doesn't fade at all. Its body is exactly seven overrides (`HomeEdgesTest` pins the set: no `init`, property or listener). With `clipToPadding=false` it also overrides the padding offsets (as AbsListView does), so the fade covers the 16 dp bottom padding and a faded row doesn't reappear below it. It is View.draw's alpha fade, so it works over the system wallpaper. It consumes no touch: the swipe-up/left detector, the item touch listener and the scroll are RecyclerView's as before. **No fold rule** (2026-11-01, the user on rc.8: "Kutt lavere, sånn ca heller der det var før"): the 2026-10-27 rule that clipped the grid at the icon's middle when a label fell under the fade (`gridFoldPaddingPx`, `foldGrid`) is gone; the grid runs to its own edge with the 16 dp padding and the bottom fade, as before rc.8. The contacts row (`HorizontalScrollView`) uses Android's own horizontal fade, `CONTACTS_FADE_DP` 16 dp (short, so the half-avatar peek still reads); it fades only while it can scroll.
- **Labels**: `tile_label` is `maxLines="2"` (`GRID_LABEL_MAX_LINES`) with `ellipsize="end"` and `hyphenationFrequency="normal"` (a single word too wide for the cell, "Innstillinger" at 1.3/2.0, is hyphenated rather than cut between letters) at every font scale. **A word is never cut between letters** (emulator round 2026-10-14 N7: "Mamm/a", "Chro/me", "Ph/one" at 2.0 - hyphenation only helps where the language's patterns know the word): `tile_label` and `contact_name` (Home and the phone book) are `ui/WholeWordTextView` - Android's layout as it is while no shown line ends inside a word without a drawn hyphen; else the label shrinks in 5 steps to at most 75 % of its size, and at that floor each word too wide for a line ends in "…" (a `TransformationMethod`: `text` stays whole; the label's own accessibility text is the shown one, TalkBack reads the full name from the clickable cell's content description). A line end at an ICU line-break opportunity (`BreakIterator.getLineInstance`: between ideographs, Thai words) is never a cut, and a new text always asks for a layout and a new fit (`onTextChanged`; a recycled tile kept the old label's fit - qa-34-emu-launcher-code M1). The decisions are pure in `WholeWords` (`cutLines`, `shownLines`, `breakOpportunities`, `fitKey`, `FitState`; `WholeWordsTest`: nb and en labels at 1.0/1.3/2.0 in 3 and 4 columns, the contacts row and the phone book, against a breaker model without hyphenation, plus the pure parts). No code sets a line count (QA L6): a row is as tall as its tallest tile (GridLayoutManager), and the icons stay top-aligned. `tileHeightDp` models a row at font scale 1.0/1.3/2.0 (Nunito's line 1.364 em, linear as an upper bound); at 2.0 on the Jelly Star (3-button navigation, three contacts) a whole row plus the bottom fade still fits, in both 3 and 4 columns; with the music card a whole row still fits (the fade may reach into its labels at 2.0). Contact names were already two lines; since six-inch F3 (2026-10-26) with 4 dp sides inside their cell, so neighbours' names never touch.
- **The call badge** (the cause was layout math, not clipping): items were `itemDp` = avatar + gap wide, so three 104 dp items made a 312 dp row in the 288 dp content. The row scrolled by 24 dp and the third contact's avatar and badge were cut at the screen edge. Now a row that fits gives each item `contactItemDp` = min(avatar + gap, content / count), in whole pixels rounded down by `contactItemPx` from the row's real width, `contactsContentPx` (Home's column measured once laid out, else `screenWidthDp` - 32 dp less a pixel; qa-34-s4-code L2) (`HomeActivity.renderContacts`: the item width and the name's `maxWidth`). On the Jelly Star that is 96 dp: the third badge's right edge sits at 282 of 288 dp, and the row neither scrolls nor fades. Wider screens keep 104 dp. Every item keeps `contactRoomDp` = avatar + 2 × 4 dp (the badge's 4 dp overhang, `CALL_BADGE_OVERHANG_DP`, from the layout; P2's focus ring, `PressFeedback.FOCUS_RING_GAP_DP`). `contactRow`'s 76 dp and 64 dp centred cases also need that room, else the next case or the scroll (this only bites below about 84 dp per contact, never at real widths). A scrolling row is unchanged.
- Not emulator-checked yet (the list is in the design doc's "Step 4 implementation status"): Home at font scale 1.0/1.3/2.0 (`docs/testing/emulator.md` §3b), 3 and 4 columns, the fade ramps over a photo wallpaper, the contacts row with 3 and 5 contacts, the swipes and long-press with the new grid class, the contacts-row swipes (M1: on a contact and between contacts, as the first gesture and after a grid tap) and a `dumpsys gfxinfo` frame-time check.

## One clock format on the whole phone (design 34 step 6, P12, `docs/design/34-launcher-polish.md`, at the monorepo root)

- **Vibb never has a format of its own** (`ui/ClockFormat.kt`, `ClockFormatTest`): every clock time follows the phone's
  system format, as the status bar does - `Settings.System.TIME_12_24` when it is "24"/"12", else the default of the
  **system** locale, read from `LocaleManager.systemLocales` (qa-34-s6-code M1: on Android 13+ `Resources.getSystem()`
  and `Locale.getDefault()` carry Vibb's per-app language in our process; they are only the fallbacks,
  `chooseSystemLocale`). The pattern is the system locale's own (`getBestDateTimePattern` with the hour field forced,
  skeleton "Hm"/"hm"), so fi's `H.mm` matches its status bar (N6). The AM/PM marker's word is Vibb's language
  (the context's own locale - Vibb's per-app language, or the system's when it follows the phone; `chooseTextLocale`, emulator round 2026-10-14 N9, qa-34-emu-launcher-code N2): Vibb in nb
  on an en_US system says "Til 11:30 p.m." (CLDR's bokmål marker), not "Til 11:30 PM" - the status bar shows no marker,
  so there is no word of its to match; the hour cycle, separator and order stay the system's. **Clock faces** (`formatTime`, `bind`) drop the
  AM/PM marker like the status bar ("6:15"); **end times** (`formatUntil`: "until 9:30 PM") keep it in 12 h (L1) - the
  lock's "until", Home's window banner, the relock notification (`WindowRelock`), install mode's notification
  (`PlayRuntime`) and its Settings summary (`SettingsFragmentLauncher`). Faces: the glance clock and row times
  (`GlancePanel`), the missed-call text. The two time TextClocks (`lock_clock`, `lock_glance_clock`; the XML keeps
  `h:mm`/`HH:mm` for the first frame) get both formats set to the system's face pattern by `ClockFormat.bind` on the
  start/resume path (`LockActivity.onStart`, `GlancePanel.resumeNow` - pinned by the test, not only in the watch
  lambda), and the two lock screens `watch` `TIME_12_24` and `ACTION_LOCALE_CHANGED` while started (bind + re-render;
  the PIN lock handles `locale` itself, so it isn't recreated). Durations (the call screen, Home's call card, the music
  position, the PIN back-off) are not clocks. **Only `ui/ClockFormat.kt` may call `is24HourFormat`/`getTimeFormat`/
  `getTimeInstance`/`ofLocalizedTime`/`FORMAT_SHOW_TIME`, ask `getBestDateTimePattern` for an hour field, spell a time
  pattern or build one from `${...hour}`** (`ClockFormatTest`'s source scan, comments stripped); every TextClock with a
  time format must be bound, and each surface's string takes its time straight from `formatTime`/`formatUntil`
  (structural regexes, N5).
- **The PWA sets the phone's format** (`server/ClockFormatSetting.kt`, pure `clockFormatChoiceOf`/`clockFormatWrite`/
  `clockBeforeAfterPut`/`clockFormatApplied`, `ClockFormatSettingTest`): `launcher_ui.clock_format`
  (`LauncherUi.clockFormat: String?`; missing, `null` or unknown = "system", `PolicyResponseCompatTest`). Every
  `AppEnforcer.apply()` writes `TIME_12_24` = "24"/"12" when it differs; "system" writes nothing, except putting back
  what the phone had before our first write (CE prefs `clock_format_state`: `clock_written` our value, `clock_before`
  the phone's own - unset or a hand-set value; N4) once, only while the setting still holds our value; a hand change
  since is left alone. **No `launcher_ui`** (no policy, an older server, the last-enforced fallback) is no choice
  (`clockFormatChoiceOf(null)` = null) and never writes (L2: else a fallback would put back the parent's 24 h). Not a
  restriction: the override and the pause change nothing, and the write never lifts `DISALLOW_CONFIG_DATE_TIME`
  (pinned: `applyDateTimeLock` is called once, before the write, and nothing after it in `apply()` touches the
  restriction). **Android's limit**: a device owner can't write it (`setSystemSetting` takes only brightness and the
  screen-off timeout); the manifest declares `WRITE_SETTINGS` and the "Modify system settings" app-op is granted by
  the parent during a pause from Settings' "Modify system settings" row (`WriteSettingsAccess`, "Background
  connection" below), or with adb as the shortcut (`adb shell appops set me.vibb.launcher WRITE_SETTINGS allow`,
  `docs/testing/emulator.md` §4). Without it (`Settings.System.canWrite` false) nothing is written.
- **Status** `clock_format_applied`: `true`/`false` = whether `TIME_12_24` reads back as the choice (so a missing
  app-op and a refused write are both `false`; the PWA warns), left out (`null`) for "Follow the phone" or without
  `launcher_ui`. Device check (design 34 step 6): whether SettingsProvider refuses the write under our
  `DISALLOW_CONFIG_DATE_TIME` - the restriction is never lifted for it.

## The time-rule screen as one calm card (design 34 step 7, P6, `docs/design/34-launcher-polish.md`, at the monorepo root)

- **Visual only**: what the lock allows (usable apps, calls, the phone book, exempt apps), Back off, the gestures and
  every target are unchanged; `decideTimeLock` decides, the screen only shows it. Mockup `04-time-lock-after`.
- **Layout** (`activity_lock.xml`): the clock (52 sp, Nunito 700, `kid_core`, through `ClockFormat` as before), the
  kind's icon in a 52 dp peach disc (`bg_lock_kind_disc` = accent 14 %, glyph `kid_accent`, decorative), the title
  (22 sp, a heading), the message (15 sp `kid_core_dim`: the rule's "until" on its own line, then one paragraph), **one
  card** of rows (`lock_rows`, `bg_lock_card` = `kid_ground_lift`, the contact sheet's lift - QA M4; hairlines
  `kid_divider`), then at the bottom "Emergency call" as a **red text button** (`kid_emergency_text`, the glance page's
  red, >= 48 dp, always shown, `EmergencyCall.confirm` as before with the `EmergencyDialer` fallback); "Enter unlock
  code" went on 2026-11-01 (PWA only). A weighted `Space` keeps it at the bottom when there is room; otherwise the
  screen scrolls. No saturated full-width buttons: `KidLockButton`, `item_lock_app.xml` and `kid_message_button` are gone.
- **Rows** (`item_lock_row.xml`, pure `ui/TimeLockModel.kt` `timeLockRows`, `TimeLockModelTest`): the phone book first
  while calls are managed and the lock allows them (its tile: `kid_phone_book` with `ic_kid_phone_book`), then each
  usable app that can be opened (launchable, not suspended - `CameraLock.suspendedForLists` as before) sorted by the
  parent's name (two apps named alike are two rows), the music app among them under a budget lock. App icons are Home's
  tone icons through `ui/home/AppIconLoader` - one loader shared with the glance page (`renderAppIcon` on
  `Dispatchers.Default`, the pure `iconPass` returns its failures so every set is touched on the main thread only, the
  job catches everything), a slate disc until drawn; drawn icons are set **in place** (`iconsDrawn` over `rowIcons`), the
  rows are never rebuilt for them, so TalkBack's focus and a press survive (qa-34-s7-code L2). Each row is one TalkBack
  node: content description = the name, role button (step 8's `lock/ButtonRole`, also on both text buttons), so TalkBack
  reads "Telefonbok, knapp"; icon, label and chevron are `importantForAccessibility="no"`. Labels wrap to two lines; the
  card clips the rows' ripple to its corners (`clipToOutline`).
- **Emergency call is visible in every state**: `render()` sets it `VISIBLE` each time, and `TimeLockModelTest` allows
  exactly three uses of the view (its click, its role, that line) - never hide or move it (qa-34-s7-code L1).
- **The kind's icon** (`lockGlyph`): screen time (no rule) the hourglass, bedtime the moon, school the book, any other
  kind a dot. `lock_glyph_hourglass`/`_bedtime`/`_school` are converted Material Symbols (`hourglass_bottom`,
  `bedtime`, `menu_book`; Apache-2.0, "Converted from" lines) generated by `scripts/material-symbols.sh` (`LOCK_GLYPHS`,
  not in `app_icons.json` - not a parent choice); `lock_glyph_dot` is drawn by hand.
- **The budget sentence** (`budgetLine`, `durationText`): under the budget lock "Du har brukt dagens 1 t 30 min." /
  "You've used today's 1 h 30 min." with the day's allowance = budget + today's extra minutes
  (`BudgetUse.effectiveMinutes`), never the minutes used - the same sentence whatever the overrun (the old "6 av 1
  minutter brukt i dag"); a budget of 0 with no extra minutes says "Ingen skjermtid i dag." / "There's no screen time
  today."; under a rule "used up too" as before. `durationText` takes the units from `duration_unit_hours`/`_minutes`
  (nb "t", en "h"; "min").
- Not emulator-checked yet: each rule kind and the budget in nb and en at `wm density 240` (font scale 1.0/1.3/2.0),
  TalkBack on the rows and buttons, 112 with Telecom refusing (`docs/testing/emulator.md` §6 item 9).
- **The lock glance's look (launcher round L6, branch `rule-screen`; the user, 2026-10-26, Jelly Star screenshots of
  school mode: the screen looked dated next to the glance)**: the clock, the kind's 52 dp disc, the 22 sp title and the
  message paragraph are gone; the screen is the glance's top in the glance's parts - **the clock face**
  (`lock/LockClockFace`, moved out of `GlancePanel` and used by both: the 56 sp clock in `kid_ink` and the long date,
  or with the phone's clock profile design 59d step L's Nunito Light clock, short date and left alignment, placed in the
  window's coordinates under the keyguard status bar - so the clock stands where the glance's does; the alarm line,
  `bindAlarm`, with its `ACTION_NEXT_ALARM_CLOCK_CHANGED` receiver between onStart and onStop; `follow()` places it at
  every root height/padding change and skips the frame that moved it), **the rule card** (`view_lock_rule_card.xml`,
  `<include>`d by both layouts, bound by `lock/RuleCardView` for both: the glyph disc, the rule's name, "Til 14:34" only
  with a certain end, and here line 3 - the budget and calls sentences, `lock_rule_card_note`, gone on the glance;
  TalkBack one node "Skole, til 14:34. Du kan bare ringe nødnumre nå." - since E7's L7 about calls only, so it
  doesn't read as "nothing else works" above a Kalender or app row -, `lock_rule_card_spoken_note`), the rows' card 14 dp
  under it (the glance's `ROWS_TOP_DP`), and "Nødsamtale" as the glance's bottom-row button (`pin_lock_emergency`,
  8 dp under the content, no flashlight). **The ground** is the lock's (`lockGround`, the parent's lock picture or
  Home's wallpaper under its scrim, once `WallpaperStore.state.lockReady`; the night until then). Behaviour unchanged:
  `decideTimeLock`, the rows, Back off, Emergency's flow and its three code lines, `start`'s CLEAR_TOP | SINGLE_TOP
  and `onNewIntent`'s `render()`. `TimeLockModelTest` compares each shared part's attributes with the glance's
  (clock, date, alarm, Emergency) and pins the one include; `LockGlanceLayoutTest` inlines includes in its model (the
  card is still 82 dp there). Not emulator- or device-checked yet: the profile clock's place on the Jelly Star against
  the glance's (no jump at the unlock), a long rule name (one line, cut, as on the glance), the lock picture behind the
  rows' card, font scale 2.0.

## The keypad's tonal emergency button and the spacing rule (design 34 step 8, P7, `docs/design/34-launcher-polish.md`, at the monorepo root)

- **Nødsamtale** on the keypad stage (`activity_pin_lock.xml` `pin_emergency`) and on the VoIP ring card (`view_voip_ring.xml` `voip_ring_emergency`, qa-34-s8-code L2) is the tonal pill `bg_pin_emergency_tonal` (`kid_emergency_tonal` `#3A1E24`, 24 dp radius; a calm press ripple of the label's red at 20 %) with its label and glyph in `kid_emergency_text` `#FF8A8A` (6.6:1; the pill itself is ~1.3:1 on the night, QA N4 - the text and glyph identify it) - the same pair as the 112 avatar. The saturated `bg_pin_emergency` is gone; on the ring card the one saturated red is Avvis. Both pills announce as a button (`lock/ButtonRole`, an accessibility delegate that only sets the class name). The views, their clicks and the emergency flow are unchanged (the glance page's link still `performClick`s the keypad's; that link is 48 dp now, its margin giving the 4 dp so the glance fit didn't move).
- **The spacing rule**: one column with nothing overlaid - prompt row, dots, keypad, >= 16 dp (the button's top margin; the keypad has no bottom margin and its keys never overflow it), Emergency call (min 48 dp, 6 dp vertical padding; the label wraps and the pill grows with the font scale), "Foreldrekode" (min 44 dp), the 4 dp inset. Only the keys give way (`PinKeypadLayout`: min(72, (h - 3 x 6) / 4) dp of the measured keypad, floor 32 dp). A digit never outgrows its key: the pure `keyStyle` caps the 30 sp digits at 0.7 x the key (`digitTextPx`; the line box stays inside the key, and a 2.0 digit is no smaller than at 1.0), and `sizeKeys` applies size and digit apart (`keyStale`), so keys that keep their first 48 dp get the cap too.
- **Keys** keep the translucent `bg_pin_key` (the lock's wallpaper and scrim show through) with a 1 dp top highlight drawn over it (`lock/PinKeyHighlight`: a white 6 % arc over the top third, as the mockups draw it; layered in `buildKeypad`).
- **`PinLockLayoutTest`** models text growth (`max(minHeight, lines x sp x scale x 1.4 + padding)`, lines from the longest nb/en string at 0.6 em a character, the scale linear - an upper bound, QA M6) on the Jelly Star (320x569 dp, 48 dp status bar, 24/48 dp navigation). Modelled, not measured: 1.0 -> 71/65 dp keys (>= 56 asserted), 1.3 -> 69/63 (>= 48), 2.0 -> 49/43 (above the 32 dp floor, never overflowing the keypad; the model wraps the English label, which in real Nunito fits one line - the screen will show larger keys). `KidPaletteTest` reads both pills' colours from their layouts and the drawable (>= 4.5:1).
- **The glance clock** is Nunito 700 (`@font/nunito_bold`; it was 800); size and fit unchanged (`LockGlanceLayoutTest`).
- **A font-scale change under the lock** (emulator round 2026-10-14 N8): the lock handles `fontScale` itself (no recreation - crash guard, re-front), so `PinLockActivity.onConfigurationChanged` re-sizes every text in place (`lock/FontScaleRefresh`: each size back to its sp at the old metrics with `TypedValue.deriveDimension`, non-linear scale included, then set again in sp; the keys skipped), then `sizeKeys` and `GlancePanel.refit()`. The glance date is `gravity="center_horizontal"`, so at 2.0 it wraps centred. `LockFontScaleTest`.
- Not emulator-checked yet: the keypad at font scale 1.0/1.3/2.0 via the emulator console screenshot (`docs/testing/emulator.md` §6c), nb and en; the glance clock.

## Softer built-in wallpapers (design 34 step 9, P8, `docs/design/34-launcher-polish.md`, at the monorepo root)

- The server's migration 0076 softens three built-ins in place (same ids and `builtin_key`s, so a kid's pick stays):
  `green` is now the gradient `#2A4A4F -> #101820`, `sky` `#34406A -> #14141F`, `sunset` `#5A3A4A -> #1A1420`. The
  launcher keeps no built-in colours of its own (it draws what `launcher_ui.wallpapers` sends; only navy is its
  fallback), so an older launcher shows them too. Only the names are ours: `wallpaper_green`/`_sky`/`_sunset` = nb
  "Fjord", "Kveldshimmel", "Skumring", en "Fjord", "Evening sky", "Dusk" (`KidSettingsActivity.wallpaperLabel`, by
  `builtin_key`).
- `WallpaperInkTest` lists today's seven built-ins: each reaches 4.5:1 with no label shadow, the three softened ones take
  white ink with no scrim (>= 9.5:1 on the lightest stop), and the lock's inks reach 4.5:1 on every built-in's stops
  (the red emergency link needs a light scrim on all three softened lighter stops - 4.22-4.43:1 without it - which `lockScrimForColours` gives). The old
  bright sky/sunset stay in the test as generic bright grounds.

## Kid Settings' sound choice as one segmented control (design 34 step 10, P9, `docs/design/34-launcher-polish.md`, at the monorepo root)

- **Visual only**: design 18's two choices, what a tap sets (`soundTapMode`), the read-only DND state and its notes are
  unchanged; so are the gestures and Kid Settings' other rows. Mockup `06-kid-settings-after`.
- **The control** (`activity_kid_settings.xml`, pure `ui/kidsettings/SoundSegments.kt`, `SoundSegmentsTest`): the
  `RadioGroup` is the track (`bg_kid_segment_track`, white 8 % `#14FFFFFF`, radius 14 dp, 3 dp padding); its two
  `RadioButton`s are equal halves (`0dp` + weight 1, no gap, `match_parent` height so both take the taller one), each
  >= 48 dp, Nunito 800 14 sp, radius 11 dp (`SEGMENT_RADIUS_DP`), `hyphenationFrequency="normal"`. No line limit or
  ellipsis: a label wraps and the control grows with the font scale. `KidSettingsActivity.styleSegment` draws the pure
  `segmentColours(ink, accent, night)`: the chosen half `kid_accent` with `kid_ground` text (9.9:1) - outlined 1 dp in
  the ink on a light ground (dark ink), where the peach is only ~2:1 to the track (qa-34-s10-code L1) -, the other no
  fill with the wallpaper's ink, a ripple in the ink at 12 % clipped to the half, and a 2 dp focus ring as the
  foreground (night on the chosen half, peach - the ink on a light ground - on the other; N2). The `state_checked`
  items come first. `renderSound` reads `state.selected`/`state.enabled` directly (no wrapper); read-only halves keep
  their 50 % alpha.
- **TalkBack** (QA L7): still two `RadioButton`s in a horizontal `RadioGroup` - the role, the checked state ("valgt")
  and the group of two come from the framework; nothing anywhere in the activity sets a delegate, role or class name
  (`SoundSegmentsTest` scans it whole, qa-34-s10-code L2). The group carries the name ("Lyd på telefonen"); the
  visible label is `importantForAccessibility="no"`, so it is read once.
- **`SoundSegmentsTest`** (12): the layout against the test's own mirror of its values and width/lines model (0.6 em a
  character, linear scale, 1.4 x line - an upper bound; L3) at 320/360/411 dp and font scale 1.0/1.3/2.0 in nb and en,
  the track, design 18's taps, the TalkBack structure, `segmentColours` and the state order (mutations of the role and
  of `state_checked` are caught), the light-ground outline (>= 3:1), the focus ring, the manage links in the ink, and
  contrast: the chosen half 9.9:1; the other's ink on track + card + scrim over every built-in (>= 6.2:1) and over
  mid-tone grounds.
- **White ink is judged on the card and the track** (qa-34-s10-code M1, `WallpaperInk`): `scrimFor` judges white ink
  on the ground under the scrim (at the 8-bit alpha drawn, `drawnScrimAlpha`, as `lockScrim`) plus the two white 8 %
  layers Kid Settings puts under it (`underKidSurfaces`: `KID_SURFACE_ALPHA`, `KID_SURFACE_LAYERS` = card + track, each
  rounded to 8 bits); dark ink only gains from white layers and is judged on the bare ground (also at the drawn alpha).
  The one shared scrim gets a little stronger on mid-tone grounds only (`#1C7ED6` 0.05 -> ~0.24, the edge grey 0 ->
  ~0.21; built-ins and light grounds keep 0, the 0.45 cap is never reached before dark ink takes over) - on Home and
  the phone book too. The card's "Manage networks/devices" links are drawn in the ink (`renderInk`), no longer peach
  (~2:1 on the card over a light ground). `WallpaperInkTest` checks mid-tone colours, the edge greys and a photo on
  bare, card and card + track, per channel at 8 bits.
- Not emulator-checked yet: Kid Settings at 320/360/411 dp and font scale 1.0/1.3/2.0 in nb and en, TalkBack on the
  halves (read-only under DND too), the press.

## The name "Vibb One" (design 34 step 12, P11, `docs/design/34-launcher-polish.md`, at the monorepo root)

- **What users see**: `app_name` "Vibb One" (`res/values/donottranslate.xml`; debug "Vibb One (debug)" in
  `src/debug`) - Settings › Apps, permission dialogs, the Home-app picker, notification headers, Recents;
  `notification_listener_title` "Vibb One" (en and nb, the anchor's ongoing notification); the VPN session
  `vpn_session` = "Vibb One filter" / nb "Vibb One-filter" (`KidVpnService.setSession(getString(..))`, Android's VPN
  settings and its key dialog; qa-34-s12-code N1).
- **What stays**: the packages `me.vibb.launcher`(`.debug`), the Kotlin namespace `com.kidslauncher.mdm`, the admin
  component, the `kids-launcher-mdm.apk` asset (the QR's URL and checksum), CI names - nothing in provisioning,
  updates or the music app reads the label (the music app names the launcher by package). The PWA and the catalog row
  (server migration 0077) follow on the server's side.
- Test: `ui/VibbOneNameTest` (the label, the debug label, the notification title and the VPN session in en and nb,
  and no "Kids Launcher", "Kids Device MDM" or "Kid Phone Filter" left in `src/main`'s res, java and manifest or
  anywhere in `src/debug`).
