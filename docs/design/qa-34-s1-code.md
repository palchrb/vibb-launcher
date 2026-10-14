# QA 34 steps 0 and 1 (code) - the emulator docs, six calm icon tones, the three icon states

Reviewer: code QA, 2026-10-14. Branch `design-34-s1`, `500b0a08..8a2c4fed`: `038d6fc9` (step 0, docs) and `8a2c4fed`
(step 1, server and launcher in one commit). I read it against `docs/design/34-launcher-polish.md` (P1 with the
three-state table, §5 steps 0-1, §7 H1/M3/L9 and the new "Step 0 and step 1 implementation status"), and against
`qa-34-design.md` (H1, M3, L9), design 14, the root `CLAUDE.md`, `server/CLAUDE.md` ("App names and icons") and
`launcher/CLAUDE.md`.

In the code I read:
- the whole diff;
- `app_display.rs` in full: `validate`, `sanitized`, `catalog_defaults`, `device_rows`, `policy_app_display` and the
  form;
- `save_app_display` (`handlers/devices.rs`), `save_display` and the catalog page (`handlers/tracked_apps.rs`), and
  `render_apps` (`handlers/device_pages.rs`), including the PWA's own row tiles;
- `KidAvatars` (`renderAppIcon`, `drawOwnLogo`, `toneOf`, the cache), `AppDisplay.kt` and `IconTones.kt`;
- androidx Palette 1.0.0's `DEFAULT_FILTER`, from its bytecode.

Line numbers are `8a2c4fed`'s. No product code was changed. The mutations were temporary and were reverted, and
`git status` was clean before this file was written. I did not use the emulator.

**Verdict: merge. A short fix round is optional.** The contract holds both ways:
- **A new server and an old launcher.** The old launcher drops `own` as an unknown icon (design 14 QA #1).
  - With no label, the entry is gone and the phone draws its pre-34 default.
  - With a label, the label stays on the default look.
  - Nothing fails, and `AppDisplayTest` pins the old shape.
- **An old server and a new launcher.** The policy shape is unchanged: `policy_json_keys_snapshot` and
  `PolicyResponseCompatTest` are untouched, and `own` simply never arrives.
- **Stored data keeps its meaning.** There is no flip:
  - A NULL icon was "the launcher's default look" and is now Vibb style, which is the same render path: monochrome
    white on a tile.
  - `DisplayValues::own()`/`is_own()` became `empty()`/`is_empty()` at every call site. These are `keep_row`,
    `policy_app_display`'s filter, `render_apps` and the catalog/device handlers.
  - The new `is_own()` is used only where the logo is meant.
  - `app_icons.json` is byte-identical in both directories, and both generated tables match it. The server's table
    test and `AppGlyphsTest` both passed.

**Contrast.** Every tone carries white at 5.32-5.41:1 and stands off the night at 3.60-3.66:1. The Phone book tile
`#3A7552` reaches 5.46:1 and 3.57:1, and `OWN_PREVIEW` 6.05:1.

**The user's hard decisions are kept:**
- six tones, no amber;
- Vibb style is the default;
- `own` draws the real icon untinted;
- a glyph is the third state;
- every save posts to its row's anchor.

The findings are about two things: an older open page whose button now means something different, and a tone
extraction that loses orange icons before the rule sees them.

Counts: 0 High, 0 Medium, 4 Low, 6 Nits.

## What I ran

- Server, from `server/` under `~/build.lock`: `cargo fmt --check && cargo clippy --all-targets --locked && cargo
  test` passed, 943 tests and 0 failed. The clippy warnings are the old dead-field ones in `models.rs`, none new.
  `node --test server/jstest/*.test.js` passed 30/30.
- Launcher, from `launcher/`: `./gradlew --no-daemon --max-workers=2 assembleDebug testDebugUnitTest --rerun
  -PwarningsAsErrors=true` passed: 125 classes and 1179 tests, `IconTonesTest` 8/8.
- 16 mutations, 8 on each side. 11 were caught and 5 survived. The table is at the end.

## Low

### L1. An older open Device › Apps page's "Use the app's own" now does the opposite under a catalog default

`handlers/devices.rs:943` (`"reset" | "own" | "catalog"`).

Before step 1, the device form's `action=own` stored an empty phone row. The doc comment this commit removes said
"on this phone, even with a catalog default". That empty row overrides the default. Now `own` is Reset: the row is
deleted and the phone shows the catalog default.

A parent with a Device › Apps tab open from before the deploy (the PWA keeps tabs open, and `app-display.js?v=2` is
cached) can press "Use the app's own" on an app that has a catalog default. They are redirected back to the row and
see the catalog's glyph, the thing they were trying to get rid of. Nothing tells them. Without a catalog default the
two behaviours match.

The catalog form's old `own` was always "clear the default", so Reset is right there. The device form's old
`catalog` was "delete the row", so it is right too. Only the device form's `own` changed meaning. The test
`a_catalog_default_reaches_every_phone_and_a_phones_choice_replaces_it` pins the new mapping (`for old in ["own",
"catalog"]`), so the change is deliberate. It just isn't the old meaning.

*Fix:* map the device form's legacy `own` to its old meaning: `DisplayValues::empty()`, then the normal `keep_row`.
That keeps an empty row when a default exists, which is Vibb style on that phone today, the pre-34 look. Only
`reset` and `catalog` delete. In the test, assert `json!([])` for `own` with the default present, and the default
for `catalog`.

### L2. Palette's default filter removes orange and brown before `iconTone` sees them

`KidAvatars.kt:294` (`Palette.from(small).generate()`) feeding `IconTones.kt:80-94`.

androidx Palette 1.0.0 applies `DEFAULT_FILTER` to every quantized colour. I confirmed this from the bytecode. The
filter drops three kinds of colour:
- black, lightness <= 0.05;
- white, lightness >= 0.95;
- "near the red I line": hue 10-37° with saturation <= 0.82.

For an orange, terracotta or brown icon (e.g. `#E8772E`, s 0.80, h 24°), the coloured swatches never reach
`iconTone`. It then sees only greys, or nothing, and returns the package's hash tone: teal, blue, slate and so on.
P1's rule says the nearest tone, which is rose (345° is 27-55° away; teal is 139° or more). Only fully saturated
oranges (`#F76707`, s 0.93) survive the filter.

Palette's black filter is also what keeps the bitmap's transparent area out of the `all` population today:
transparent pixels quantize to black. `iconTone`'s `CHROMATIC_SHARE_MIN` therefore silently depends on that filter.

*Fix:* in `toneOf`, `Palette.from(small).clearFilters().addFilter { rgb, _ -> Color.alpha(rgb) != 0 }`. Better still,
build the swatches from the opaque pixels directly (48×48 is 2304 pixels; skip alpha < 128). `iconTone`'s own
lightness and chroma rules then decide on black and white. Add an `IconTonesTest` case where an orange swatch
`#E8772E` maps to rose. The pure test can't see the filter, so name the dependency in `toneOf`'s KDoc either way.

### L3. "Vibb style" promises a white symbol that some apps won't get

`templates/partials/app_display_form.html:43`.

The hint says "Vibb style: a white symbol on a calm colour picked from the app's icon". For an app without a
monochrome layer (mockup 01's Kamera, Klokke, YouTube, and every legacy icon), Vibb style draws the app's own logo.
That is the implementer's decision, consistent with P1's table ("its own icon (nothing to tone)"). On the phone, the
two radios then look identical for that app. The parent can't tell from the PWA, which has no icon to inspect, and
may think the save didn't work.

*Fix:* one clause in the hint: "…picked from the app's icon (an app without a plain symbol shows its own logo)".

### L4. The three-state dispatch on the phone is untested

`KidAvatars.kt:175-206`.

The order is: `own` first, then a glyph, then monochrome or else `drawOwnLogo`. This order is the whole of H1's
launcher fix: "`own` must be its own branch before both, or it falls through to the monochrome path, which is
exactly today's bug". It lives only in Android drawing code. Mutation L7, which disabled the `own` branch, survived
the full unit suite.

*Fix:* pull the decision into a pure function in `IconTones.kt` or `AppDisplay.kt`, e.g.
`iconRender(entry: AppDisplayEntry?, adaptive: Boolean, hasMonochrome: Boolean): IconRender` with
`OwnLogo | Glyph(res, tileOrNull) | VibbMono`, have `renderAppIcon` switch on it, and test the six combinations.
Include `own` with a monochrome layer → `OwnLogo`, and an unknown glyph → Vibb.

## Nits

- **N1.** `AppDisplayForm.own_row` is dead. It is set at `device_pages.rs:1349` but no longer read now that "Follow
  the catalog" is gone (`app_display.rs:308`, `:382`). Drop it, or use it to show Reset only when there is
  something to reset.
- **N2.** `style.css:1192`: `.app-tile`'s default background is still the old grey `#868E96` (white 3.3:1). The 16
  glyph radios in the form (`app-tile app-tile-small`, no inline colour) sit on it. Use the slate `#606B80`
  (`AUTO_PREVIEW`), so the form previews the calm palette.
- **N3.** The red checks don't wrap: `app_display.rs:530` (`hue < 20.0`) and `IconTonesTest.kt:31` (`h < 15f`). A
  tone at 350° would pass both. The server keeps the old RGB rule, which catches only saturated reds. Use a hue
  distance to 0° (`hueDistance(h, 0f) < 15f`).
- **N4.** `policy_app_display`'s `&& !v.is_own()` (`app_display.rs:260`) can't be reached with a colour. `sanitized`
  already forces `auto` for `own` on every read, and mutation S4 survived. Either drop it, or keep it as a guard
  with a unit test that builds a `DisplayValues` directly.
- **N5.** `iconTone`'s filters are only partly pinned. Removing the lightness window (L5) or the
  `population > 0` check (L6) survives. Add a dark, saturated swatch (e.g. `#2A0F3A`, l 0.14, or `#1A0A2A` below
  0.12) beside a small blue one, and a zero-population red beside a blue one, each asserting `TONE_BLUE`.
- **N6.** `docs/testing/emulator.md:97` justifies the `WRITE_SETTINGS` app-op by P12's `TIME_12_24`, which isn't
  built yet. The user today is kid Settings' brightness (`QuickControls.kt:181`). Name both, so the line doesn't
  read as speculative.

## Checked and fine

- **`validate`:**
  - `own` is accepted beside `icon_known`, not in `ICONS`, so the glyph and licence tests stand.
  - Any colour sent with it, valid or not, becomes `auto` without an error. A no-JS post with a colour still checked
    is therefore fine.
  - An unknown icon is still refused (S1).
- **`sanitized`:** keeps `own` and forces `auto` (S3, S7). The catalog page reads `display_*` raw, but only
  `validate` writes there.
- **`keep_row`:**
  - `own` without a default is kept (S5).
  - `own` equal to an `own` default keeps no row, because both sides are `auto`.
  - Vibb style over a default keeps an empty row, which `policy_app_display` filters out. The phone then gets no
    entry, which is Vibb style.
- **Older posts:**
  - Catalog `own` → clear the default.
  - Device `catalog` → delete the row (S6).
  - Device `own` → see L1.
- **The form:**
  - The three radios, `own_checked`, and `glyph()` returning `None` for `own` or an unknown key. An entered `rocket`
    no longer builds an `<img src>`.
  - Askama escaping.
  - `data-tile` comes from a constant.
  - `?v=3`.
  - The anchors are unchanged (`#app-<package>`, `?open=display#display`), so a save never jumps to the top.
  - The script's `updateAll` on load disables the colours for a server-rendered `own`. A bfcache restore keeps the
    DOM state.
  - Without JS the colours stay enabled, and the server ignores them. That is right: a server-rendered `disabled`
    would trap a parent switching from the logo to a glyph.
- **The PWA's row tiles** (`device_pages.rs:1328-1334`, `tracked_apps.rs:1250-1253`) filter on `icon_known`, so
  `own` shows the letter, never `/static/app-icons/own.svg`.
- **Launcher:**
  - `appDisplayMap` keeps `own` with `auto` (L1, L3).
  - `appIconKey` gives `|g=own|c=auto` (L2).
  - The cache is an in-memory `LruCache`, so the new tones need no invalidation: an update restarts the process.
  - The palette pass runs only on a cache miss, in `refreshApps` and the glance's job, never in `onBind`. `own` skips
    it.
- **`drawOwnLogo`:**
  - Layers at 1.5× (`-size/4 … size*5/4`) is right. The layers are 108 dp, and the visible mask is the centre 72 dp,
    so the full circle shows exactly that. The 66 dp safe zone is 92 % of it.
  - Legacy icons are inset 1/8 on `#F1F3F5`.
  - Everything is clipped to the circle, and the monochrome layer is never touched.
- **`iconTone`:**
  - Always one of the six.
  - Slate is never a hue's tone.
  - The hash is stable: `String.hashCode` is specified, and `floorMod` handles negative codes.
  - `FIXED_TONES` are tones.
  - Yellow → rose, green → teal, so no amber or green tile.
- **Step 0:**
  - `wm density 240` (480×854 → 320×569 dp), `settings put system font_scale`, and the resets are correct.
  - `appops set $P WRITE_SETTINGS allow` is the right form, and `$P` is set in §4.
  - `relock_blocks`, `outside_play` and "Allow these apps…" exist as named.

## Mutations

| # | Mutation | Tests | Result |
|---|---|---|---|
| S1 | `validate` accepts any icon | `cargo test app_display` | caught (`validation_trims…`, `refused_input_keeps…`) |
| S2 | `validate` keeps the colour sent with `own` | same | caught (`the_apps_own_logo_is_a_third_state`, `…_stored_sent_and_shown`) |
| S3 | `sanitized` keeps a stored colour with `own` | same | caught |
| S4 | the wire sends `v.color` for `own` | same | **survived** (redundant behind `sanitized`, N4) |
| S5 | `keep_row` without a default on `!is_own()` | same | caught |
| S6 | the device form drops an older page's `catalog` | same | caught |
| S7 | `sanitized` drops `own` | same | caught |
| S8 | `app-display.js` never disables the colours | same + `node --test` | **survived** (no JS test of the form; harmless, the server forces `auto`) |
| L1 | `appDisplayMap` defaults to the glyphs only (drops `own`) | `AppDisplayTest`, `IconTonesTest`, `AppGlyphsTest`, `KidPaletteTest` | caught |
| L2 | `appIconKey` without `g=own` | same | caught |
| L3 | `own` keeps the sent colour | same | caught |
| L4 | rose `#C46176` (below the floor) | same | caught |
| L5 | `iconTone` without the lightness window | same | **survived** (N5) |
| L6 | `iconTone` without `population > 0` | same | **survived** (N5) |
| L7 | `renderAppIcon` skips the `own` branch | full unit suite | **survived** (L4) |
| L8 | `iconTone` without the second-hue check | four tests | caught |
