# Code QA - the PWA's vibb identity, direction D (server only)

- Commit: `ead6c5e0` on branch `pwa-identity-d`, on top of `0c8dcadf` (`git diff 0c8dcadf..ead6c5e0`, 18 files).
  Reviewed on branch `pwa-d-qa`. Read: the whole diff, `docs/notes/server/pwa.md` "The vibb identity",
  `docs/testing/2026-10-22-pwa-identity-pass.md`, and around it `tab_bar.html`, the avatar readers, `sw.js`, `ADMIN_CSP`.
- Ran (`CARGO_TARGET_DIR=~/.cache/handy-target/pwadqa`, under `~/build.lock`): `cargo fmt --check` OK; `cargo test`
  **1134 passed, 0 failed**. The same 9 compiler warnings on base and branch. 9 one-change mutations, each reverted;
  `git status` clean.
- Browser (Playwright 1.48.2, headless Chromium, 390x844 @2x): the pass doc's seed (`setup.py`) and its `saves.js`, on
  a debug server of **each** commit (base binary checked to hold `style.css?v=39` and no `vibb-brand`), fresh database.

## Verified correct

- **CSS**: braces balance (428/428), and the shell's shape guard passes. No reference is left to the removed
  `--bar-line`/`--bar-translucent` (the 0.5 px hairline rule went with them). No template or JS sets an inline colour on
  the new grounds. `.avatar-grey` resets the text to white, so the grey stays 4.5:1 or more.
- **Contrast** (computed): light tint `#A64B25` is 5.15 on `--bg`, 5.75 on `--surface`, 4.58 on `--row-active` (the
  `.act`/secondary hover) and 4.72 on the pill. White on `--strong` is 5.75 (hover 8.72) in both schemes. Dark tint
  `#F0A884` is 8.62 to 9.87 on the night grounds (7.14 on the pill). Dark `--label-2` is 6.37 on the cards and 5.27 on
  `--row-active`. Destructive buttons are white on `#D70015` (5.38; dark hover 4.68). The focus ring (tint, 2 px,
  offset 2) is 5.15 or more against every ground. Dark field edges are 4.06 or more (1.4.11). The pass doc's ratios
  match.
- **Tab bar**: keyboard focus reaches every tab, with a visible ring in both schemes (screenshots). The current tab has
  `aria-current="page"` together with the pill. `.tab.is-pending` gets the pill: the tapped tab and the current one
  both show a pill until the page comes, as both used to turn blue. At 1280 px the bar is x 360-920 (560 px, centred)
  and `main` ends at its top (716.6). The safe-area insets are inside `max()` with a plain fallback first, which fits
  the browser floor. No JS reads the bar's geometry.
- **Font**: a WOFF2 variable font, `wght` 700-900, served as `font/woff2` from `/static/fonts/` with no request
  elsewhere. Its cmap covers U+0020-007E, U+00A0-017E (æøåÆØÅ, `·`), U+2013/2014/2018-201E/2026/2039-203A and €.
  Every static heading is ASCII plus `&`. User-named headings (phone, app or entry names) fall back glyph by glyph
  through the stack. `font-display: swap`. The preload has `as="font" type="font/woff2" crossorigin` and resolves to the
  same URL as the `@font-face` (no "preloaded but not used" warning on the console). `OFL.txt` is the launcher's text
  word for word, there is no Reserved Font Name, and NOTICE has its line. CSP `default-src 'self'` allows the font.
- **Icons**: 180/192/512 are opaque RGB at their declared sizes. The maskable icon's farthest ring pixel is 197 px from
  the centre, inside the 204.8 px safe zone. The manifest and the head point at the right files, the apple-touch icon
  is the opaque 180, and `theme-color` follows `--bg` in both schemes.
- **Avatars**: every reader goes through `avatar_class`/`avatar_class_of` (Devices, summary, Locate, Music, Apps, Time
  Rules, app page, inbox). `AVATAR_COLOURS` now feeds only `.app-row-tile`, which keeps its own white text. The grey is
  pinned (`devices_list.rs:127`).
- **Lock page**: `device_lock.html` and the lock CSS are untouched. Only the shared tokens reach it.

## Saves (the four cases that moved), base vs branch

The pass seed and two `saves.js` runs on each commit (the second run changes each control back):

| case | base run 1 / run 2 | branch run 1 / run 2 |
|---|---|---|
| Advanced, last switch | +87 / -54 | +88 / -79 |
| Web Filter (phone), "Keep a log" | 0 / +64 | 0 / +64 |
| Lock Screen & PINs, lowest select | 0 / gone (`#notifications`) | 0 / gone (`#notifications`) |
| `/dns`, DNS server select | 0 / -396 (to `/dns/upstream#server`) | 0 / -396 (same) |

The other 16 cases are 0 px in all four runs. Each case moves the same way on both commits, so the CSS did not cause
any of them. It depends on the state: on a fresh database three of them stay at 0. The Advanced case differs by 25 px
in run 2 because `main` is about 25 px shorter above the floating bar. That is the end-of-scroll clamp after the note
goes, not a jump.

## Findings

No High, no Medium. 3 Low, 3 Info.

**PD-1 (Low) - a checked plain checkbox in dark: the white tick on peach is 1.97:1.**
`style.css:2064-2067` fills it with `var(--tint)`, which is `#F0A884` in dark, and the tick is a white data-URI. The
blue it replaces was 2.83:1, so this was already below 3:1 and is now worse. Scenario: Storytel shelf, Add from Spotify
and Advanced's confirm in dark. The tick is barely visible. The state still reads from the fill (8.6:1 on the card),
so this is Low. Fix: in the dark block, fill with `--strong` (`#A64B25`; white is 5.75), or give dark a `#0C0C14`
tick.

**PD-2 (Low, test gap) - the preload's URL isn't tied to the `@font-face` URL.** Mutation: preload
`href="/static/fonts/nunito-latin.woff2"`. All the tests pass. Scenario: a later rename of the font changes one of
the two. Every page then downloads a 404 or a second font, and Chrome warns "preloaded but not used". Fix: in
`the_app_is_called_vibb_phone_with_a_stable_identity`, assert that the head's preload `href` is `/static/` plus the
`@font-face` URL, and that the file exists.

**PD-3 (Low) - `server/deploy/generate-icons.ps1` still draws the old icons.** Its header says "Re-run manually if the
icons ever need to change". It writes a brand-blue (`#2563EB`) square with a phone glyph over `static/icons/`, and it
knows nothing of `icon-180.png`. Scenario: someone follows it and the vibb icons are lost. Fix: delete it (the icons
now come from palchrb/vibb `pi/web/`; pwa.md says so), or change its header to say where the icons come from.

**PD-4 (Info) - one old-blue colour is left: `static/locate.js:22` `TRAIL = "#0B66D6"`.** This is the Locate map's
trail line on OSM tiles. Blue reads well on the map, so keeping it is fine. If it stays, a one-line comment saying it
is on purpose would stop the next sweep from asking.

**PD-5 (Info) - contact initials on Calls & Contacts are now peach too** (`device_calls.html:76`, plain `.avatar`). A
contact now looks like a phone, where it used to be iOS grey. If that is not wanted, add `avatar-grey` there.

**PD-6 (Info) - the woff2's name table still says "Nunito ExtraLight"** (`varLib.instancer` without
`--update-name-table`). It does no harm, because `@font-face` names the family, but DevTools' "Rendered fonts" shows
that name. Re-cut with `--update-name-table`, or leave it.

## Mutations (9, each reverted)

| # | mutation | result |
|---|---|---|
| 1 | `icon-180.png` replaced by the 192 | killed `shell.rs:189` |
| 2 | manifest maskable `sizes` 192x192 | killed `:189` |
| 3 | a second `src:` from `fonts.gstatic.com` in `style.css` | killed `:207` |
| 4 | `static/fonts/OFL.txt` removed | killed `:202` |
| 5 | the woff2 zeroed (no `wOF2` magic) | killed `:197` |
| 6 | `font-display: block` | survived (not pinned; fine) |
| 7 | the preload `href` renamed | **survived** (PD-2) |
| 8 | `vibb-brand` added to `/apps` | killed `shell.rs:522` |
| 9 | `avatar_class_of` never grey | killed `devices_list.rs:127` |
