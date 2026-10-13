# QA 31 step 1 (code) - the shell: Vibb Phone, the 5-tab bar, the Settings hub, the new CSS, autosave.js

Reviewer: code QA, 2026-10-13. Branch `design-31-s1`, `69aee48b..96879ddf` (one commit, 50 files, server only).
I read it against `docs/design/31-pwa-redesign.md`: the Design QA section, §3.0, §3.1, §3.6, the §5 constraints, the §5.7
browser floor, §8 step 1 and the step 1 implementation status. I also read `qa-31-design.md` (L1, L3, L4, L7, M3,
M8, M9), the Settings and TabBar mockups, the root and server `CLAUDE.md`, and the code the step touches:
- `static/{autosave,scroll-restore,register-sw,music-cards}.js`, `static/style.css` (all 1411 lines), the manifest;
- `templates/partials/{head,app_header,music_card}.html`, `settings_hub.html`, every template's `{% let tab %}`;
- the template diff, line by line;
- `handlers/settings_hub.rs`, `tests/{shell,mod}.rs`, `tests/device_api.rs` (`template_files`);
- `admin_auth.rs` (`ADMIN_CSP`, `admin_headers`, `csrf_guard`), `main.rs` (the routes).

Line numbers are `96879ddf`'s. No product code was changed: the mutations below were temporary and are reverted, and
`git status` was clean before this file was written.

**Verdict: merge after a short fix round.** Nothing blocks, and the step does what §8 step 1 asks:
- the five tabs are set per template, with no script;
- the 54 `onchange` submits became `data-autosave`, one for one;
- no redirect changed, so scroll restore is untouched;
- the hub reads only SQLite, the backup directory and `APP_VERSION`;
- the rename leaves the manifest identity and the 2FA issuer alone.

Fix one thing before merging:
- **M1:** the new grouped list clips its own focus ring. A keyboard user sees no focus on Sign Out, or on the only
  phone in the Devices list. This is the component every later step copies, so fix it now.

The Lows are cheap: a deferred `autosave.js`, a wrong "Off", two red warning cards that lost their red, and three test
gaps.

Counts: 0 High, 1 Medium, 8 Low, plus nits.

## What I ran

All cargo runs used my own target dir, under the shared build lock.
- **`cargo test`, unchanged:** 674 passed, 0 failed (the implementer's count).
- **`cargo fmt --check`:** clean.
- **`cargo clippy --all-targets`:** 13 warnings, all in files the step doesn't touch (`models.rs`, `dns_engine.rs`,
  `storytel.rs`, `devices.rs:1321`). None is in `settings_hub.rs`, `tests/shell.rs` or `tests/mod.rs`.
- **`node --test jstest/*.test.js`** (node 20): 14 passed (7 autosave, 7 scroll-restore). CI's `server-ci.yml:83`
  runs the same glob, so the new file is covered there.
- **Contrast** of every token pair `style.css` uses, in both themes. The script parsed `:root` and the dark block.
  Every text pair passes 4.5:1 and both switch states pass 3:1. Two exceptions are L7 and an old one under Nits.
- **Mutations.** Each was a temporary edit, run against the tests named, then reverted:

  | Mutation | Tests | Result |
  |---|---|---|
  | `utc_start_of_local_day` without `.with_timezone(&Utc)` (local midnight's digits taken as UTC) | `shell`, `settings_hub`, `pages_restore_scroll`, run with TZ = UTC, Europe/Oslo and America/New_York | **survives** (L8) |
  | `newest_backup` without the `is_safe_backup_filename` filter | same | **survives** (L8) |
  | `newest_backup` keeps the **oldest** file (`modified < n`) | same | **survives** (L8) |
  | `template_files` doesn't walk into subfolders | same | **survives** (L8) |
  | autosave: no save on `focusout`; `keyed` never set; no pointer reset; `pending` not cleared on save; no `preventDefault` on Enter; `keyed` not reset on `focusout` | `jstest/autosave.test.js` | all killed |
  | autosave: the `Tab` key early return removed | same | survives. This is an equivalent mutant: `focusout` clears `keyed` right after |

- **Static checks:**
  - **Templates.** Every template with the bar sets one of the five tabs, and the set matches §3.1's table. No template
    keeps `this.form.submit()`.
  - **Mechanical change.** The only non-mechanical template lines are the renames and the removed Apps|Music
    switch.
  - **Class names.** None of the new CSS class names collides with a class an old template uses.
  - **Old tokens.** Every old token the pages' inline styles use is aliased. Only `--shadow` was dropped, and nothing
    uses it.
  - **Old classes.** The dropped classes (`bottom-nav*`, `icon-list*`, `active`) are unused.

## Checked and fine

- **The tab bar (§3.1, L1).**
  - `app_header.html` writes `aria-current="page"` from `tab` alone. The links are plain `<a href>`, so they work
    without JS.
  - The icons are `aria-hidden`, and the labels are 12 px.
  - Each item is about 76 × 48 px at 390 px.
  - Askama won't compile a page that includes the header without `tab`. `every_template_with_the_bar_sets_its_tab`
    pins the value; `every_page_marks_its_own_tab` pins the rendering, with one current tab and the order.
- **The fixed bar and the Leaflet map.** The locate map's card is `z-index: 0` (`device_locate.html:82`), so its panes
  (z 400/1000) stay under the bar (z 20).
- **No jump on save (§5.1, the user's rule).**
  - No redirect target or fragment changed. The Rust diff touches only the hub, two titles, a log line and an error
    message.
  - `autosave.js` saves through `el.form.submit()`, which is the prototype method `scroll-restore.js` wraps. That
    script is loaded un-deferred first, so every auto-save records the position as before.
  - The delegated, capturing listeners also cover controls that `music-cards.js` swaps in. Its swapped status blocks
    hold no form controls anyway, and it never swaps a card that holds the focus, so a select waiting for Enter or
    blur is safe.
- **autosave.js.**
  - All 54 controls are form-associated: 38 checkboxes, 14 selects, 1 file input and 1 text input.
  - A checkbox, the file input and the text input save on their one `change`, as before.
  - A select changed with the pointer or a phone's picker saves at once (`mousedown`/`touchstart` reset `keyed`).
  - From the keyboard, a select saves on Enter or blur.
  - No control in an auto-saving form is named `submit`, so `form.submit` isn't shadowed.
- **CSP.**
  - `script-src 'self'` allows `/static/autosave.js`.
  - No inline auto-submit is left.
  - 13 other inline handlers remain (the `confirm()` deletes, the three device pickers, `schedules.html`'s kind →
    calls, `tracked_app_add.html`'s radios). §5.2/§5.6 move these in later steps, so `'unsafe-inline'` stays until
    then, as the spec says.
- **The rename (§5.3, L4).**
  - The manifest has `"id": "/"`, and `name` and `short_name` are "Vibb Phone". Its URL is unchanged with no `?v=`, and
    `start_url`, `scope` and `display` are kept.
  - The head: `<title>`, `apple-mobile-web-app-title`, the two `theme-color` metas and `color-scheme`.
  - `security.rs:155` still says "Kids Device MDM", and nothing else does.
- **Reaching installed apps.**
  - `templates/sw.js` has no cache and no fetch handler, so the service worker can't serve an old page or stylesheet.
  - HTML is `private, no-cache`, so every page asks again and gets `style.css?v=8` and `autosave.js?v=1` as new URLs.
  - `register-sw.js` changed without a `?v=`. A stale cached copy is the arrow-function version, which every browser
    with service workers parses, so this is harmless.
  - Android picks up the new manifest name at its next WebAPK check. iOS keeps the old icon name, and the release note
    in `DEPLOY.md` says so.
- **The Settings hub (§3.6, M3).**
  - **Values.** Four `COUNT(*)` queries, one `read_dir` of `BACKUP_DIR` and `APP_VERSION`. There is no client, no
    `AppState` field but `db`, and the `app.fetch` mock records nothing.
  - **Queries.** `device_dns_events.received_at` is `datetime('now')` text, so the string comparison with the
    `%Y-%m-%d %H:%M:%S` bound is correct. That column has no index, but the table holds at most 7 days of opt-in logs;
    a scan is fine at this size.
  - **Sign Out** is a `POST /logout` form. `csrf_guard` passes it on `Sec-Fetch-Site: same-origin` (there is no token
    field to forget), and `form-action 'self'` allows it.
  - **Links.** Every old hub link is still there: Backups, Updates, Security, Account, Wallpapers and Provisioning,
    which is now Phone Setup. `/settings`, `/dns`, `/dns/log`, `/schedules` and `/settings/provisioning` keep their
    URLs, so bookmarks work.
- **The helpers (§5.5, M8).**
  - `tags`/`parse_attrs` handle quoted and bare attributes and don't match `<inputs`.
  - `form_action_of` refuses a control after `</form>`, and `nav_current` reads only the bar.
  - `the_markup_helpers_read_attributes` kills each helper's obvious mutant.
  - The converted assertions in `hardening.rs`, `lock_glance.rs`, `music.rs`, `enrollment.rs` and `parent_pin.rs` still
    pin the same facts: checked, auto-saving, the form's action, and the redirect's path and fragment.
- **The browser floor (§5.7), in the new rules.**
  - The new rules use no `:has()`, nesting, `color-mix()`, `@layer`, `inset`, `aspect-ratio`, `:is()`/`:where()`, `dvh`
    or flex `gap`.
  - `appearance` has its `-webkit-` twin.
  - Focus is `:focus` with an outline, and the `:not(:focus-visible)` reset is a separate rule. Safari < 15.4 drops
    that whole rule and keeps the outline.
  - Hairlines are 1 px, and 0.5 px only under 2dppx.
  - Forced colours show a real checkbox.
  - Reduced motion removes transitions.
  - The translucent bar is behind `@supports`, with an opaque fallback.
  - `register-sw.js` is ES5.
- **The old pages in the new shell.**
  - The page-local `<style>` blocks (`music*.html`, `wallpapers.html`) and the inline styles use only the aliased
    `--color-*` tokens.
  - No template hard-codes a white or black background, so nothing turns into light text on white in dark mode.
  - `.card`, `.error`, `.success`, tables and buttons restyle sensibly.
  - The one casualty is L4.

## Medium

### M1. The grouped list clips its own focus ring: no visible focus on Sign Out or on a single phone

- **Where.**
  - `style.css:165-181` draws focus as `outline: 2px solid var(--tint); outline-offset: 2px`, outside the element.
  - `.group` (`:321-325`) and `.device-list` (`:1125-1129`) are `overflow: hidden` so that the rows' backgrounds follow
    the 12 px corners.
  - A row fills its group's width, so the group clips the left and right sides of the ring. The top of the first row
    and the bottom of the last row are clipped too.
- **Failure.**
  - On `/settings`, Sign Out is the only row in its `form.group` (`settings_hub.html:77-79`). Tabbing to it shows **no
    focus at all**.
  - The same happens to the only phone in the Devices list and the only app in the Apps list.
  - Every other hub row shows only two thin lines, over or under its neighbours.
  - This breaks WCAG 2.4.7 and §5.7's promise that focus stays visible.
  - Steps 2-15 build every page from `.group`/`.row`, so the bug would spread to every page.
- **Fix.** Draw the ring inside the row, for example:
  ```css
  .group .row:focus, .group summary:focus, .device-list .device-card:focus { outline-offset: -2px; }
  ```
  An outline still shows in forced colours, unlike an inset `box-shadow`. Then do the user check below, with Tab on a
  desktop browser.

## Low

### L1. `autosave.js` is deferred, so a switch tapped while a long page is still loading changes but never saves

- **Where.** `partials/head.html:22` loads it with `defer`, so its listeners exist only after the whole document is
  parsed.
- **Failure.**
  - The browser paints a long page progressively; the device page has about 3,300 words and 30 auto-saving controls.
  - On a slow phone connection a parent can toggle a switch at the top before parsing ends.
  - The checkbox flips, `change` fires with no listener, and nothing is posted. With JS on there is no `<noscript>`
    Save button, so nothing hints at the loss. The next visit shows the old value.
  - The inline `onchange` it replaced worked from the moment the element existed.
- **Fix.**
  - Load it like `scroll-restore.js`, without `defer`. It only calls `document.addEventListener`, which works from
    `<head>`.
  - Update the head comment and §5.2's "one deferred `static/autosave.js`".
  - A test can pin it next to the existing `<script src="/static/autosave.js` check in
    `the_app_is_called_vibb_phone_with_a_stable_identity`.

### L2. A keyboard choice made in an open dropdown waits for blur; Enter doesn't save it

- **Where.** `autosave.js:31-43,51-59`.
- **How it happens.** The key that opens a dropdown (Space, Alt+↓, or ↓ on macOS) sets `keyed`. Choosing an option
  with Enter then fires `keydown` Enter **before** `change`; Firefox does, and Chrome too when the open popup forwards
  keys. At that moment `pending` is still null, so nothing saves. `change` then parks the value as pending.
- **Failure.**
  - The parent pressed Enter as §5.2 says, but nothing happens until the select loses focus.
  - If they then click a tab-bar link, `focusout` starts the POST and the link's navigation starts at once. A
    connection the browser drops can cancel the handler mid-write.
  - This is confirmed by reading the code. It needs the desktop check below.
- **Fix.** Remember an Enter that arrived while nothing was pending (`enterOn = el`). In `change`, save at once when
  `enterOn === el`, and add a jstest for "keydown Enter, then change". The spec's choice (no save on every arrow key)
  stays.

### L3. The hub says Web Filter "Off" when no shared list is on, but phones can still be filtering

- **Where.** `settings_hub.rs:84`, `plural(n, "Off", "list", "lists")` over `dns_blocklists WHERE enabled = 1`.
- **Failure.** With every shared list off, the phones still block:
  - the custom blocked domains (`dns_custom_domains`, `device_api.rs:609`, `dns_engine.rs:98`);
  - a per-phone blocklist override.

  The row still says **Off**, a fact the count doesn't establish. The spec's value is a count ("3 lists").
- **Fix.** Use "No lists" (like "No rules"), and update the doc comment at `:36` and the test at `:194`.

### L4. The provisioning page's two blocking warnings lost their red outline

- **Where.** `provision_qr.html:16,22`: `<div class="card" style="border-color:#dc2626;">`. The new `.card` is
  `border: 0` (`style.css:839-845`), so `border-color` has nothing to colour.
- **Failure.** "Launcher signing checksum not configured … no QR code is shown" and "No server URL configured yet" now
  look like any other white card. This is the page where the parent is about to scan.
- **Fix.** Make them `class="error"` (red background, red text, in both themes), or `card error`. Step 15 rewrites the
  page anyway, so the one-line fix is enough for now.

### L5. The status's browser-floor claim is too broad: flex `gap`, `aspect-ratio` and `overflow-wrap: anywhere` remain

The status says "no … `gap` where Safari 14.0 needs it". All of these predate the step, but they are below the floor
the spec now promises:
- **Flex `gap` in the stylesheet:** `style.css:1343-1349` (`.glyph-grid`, `.color-grid`) and `:1360-1366`
  (`.glyph-choice`), in the "Name and icon" form. On Safari 14.0 the glyph and colour choices touch.
- **Flex `gap` inline:**
  - `device_detail.html:265,539,541`;
  - `dns_filter.html:29,111`;
  - `device_locate.html:86`;
  - the `music*.html` style blocks.
- **`aspect-ratio: 9 / 16`** on `.wallpaper-swatch` (`wallpapers.html:7`). For the built-in wallpapers it is a `<div>`
  with no height (`:49`). On Safari 14.x and Chrome < 88 the colour swatches collapse to 0 px, and Settings ›
  Wallpapers shows only the labels and buttons.
- **`overflow-wrap: anywhere`** is the only wrap rule throughout. Safari supports it only from 15.4, so on Safari 14 a
  long feed name or URL widens the page at 360 px.

**Fix.**
- Now: replace the three `gap`s in `style.css` with margins, as the rest of the file does. Add
  `word-break: break-word;` before each `overflow-wrap: anywhere` (Safari and Chrome treat it as the same thing), or
  once on `.row-label`, `.card` and `.error`. Correct the status sentence.
- In the pages' own steps: give the swatch a `padding-top: 177.78%` fallback, or `height` in `@supports not
  (aspect-ratio: 1)`, and remove the inline `gap`s.

### L6. `.knob` has no display, so outside a flex row it is an inline span: no width, no height, no knob

- **Where.** `style.css:527-537`. The `width`/`height` only work because `.row` (flex) blockifies its children.
- **Failure.** A later step that puts `.sw + .knob` in a `<p>`, a `.card` or a `<label>` that isn't `.row` gets a bare
  pill whose state is colour only. That is exactly what M9 warned about.
- **Fix.** Add `display: inline-block;` to `.knob`. Nothing uses `.sw` yet, so this is the cheapest moment.

### L7. The dark filled button's hover colour fails 4.5:1

- **Where.** `style.css:97`: dark `--strong-hover: #1A75E5`, with white text at **4.45:1**. Light's `#0956B5` gives 6.98.
- **Fix.** Use `#0956B5` in dark too, or drop the dark override.

### L8. Four of the step's decisions can be undone without a red test

These are the surviving mutants above.
- **The hub's "today" bound.** Taking local midnight's digits as UTC survives in UTC, Oslo and New York. The unit test
  `today_starts_at_local_midnight_in_stored_utc` only checks a 0-25 h window, which a shift of a few hours stays
  inside, and CI runs in UTC.
  - Fix: make the function generic over the zone, `utc_start_of_day<Tz: TimeZone>(now: DateTime<Tz>)`.
  - Assert exact strings, e.g. 2026-10-13 10:00 +02:00 → `2026-10-12 22:00:00` and 10:00 −05:00 →
    `2026-10-13 05:00:00`, plus the DST-gap fallback.
- **`newest_backup`.** It survives without the name filter and when it keeps the oldest file. The test writes one zip
  and one `.txt` at the same instant, so "other files don't count" and "newest" are never really tested.
  - Fix: write two zips with set mtimes (`File::set_modified`, std since 1.75), and a newer `notes.txt`.
  - Assert the label of the newer zip.
- **`template_files`' subfolder walk.** It survives without recursion, because `templates/` has no subfolder but
  `partials/`.
  - Fix: take the root as an argument, and test it on a temp dir holding `a.html`, `device/b.html` and
    `partials/c.html`.
  - Expect `a` and `b`.

## Nits

- `scroll-restore.js:3-4` still describes the auto-saving controls as `onchange="this.form.submit()"`.
- `DEPLOY.md:183`: "Remove it from Provisioning afterwards". The other lines say Settings > Phone Setup.
- `manifest.webmanifest`'s `background_color` is still the old dark `#14161c`. Android's launch splash is dark before a
  light page; `#F2F2F7` matches the light theme.
- Input borders (`--sep-light`) are 1.17-1.51:1 against their fill or the card in both themes, below 1.4.11's 3:1. This
  predates the step (the old `#e2e4e9` was the same), and iOS does it too, but §5.4 asks 3:1 for UI. It is worth a look
  when the forms are rebuilt.
- `.row > * + * { margin-left: 12px }` relies on DOM order. A future `.row` with a visually hidden first child gets a
  stray 12 px.

## The implementer's interpretations

1. **Keeping the 44 px brand bar on every page: accepted for now.**
   - The old pages still have their own `<h1>` and nothing else at the top, so removing the bar now would leave them
     with no app name.
   - But the hub now shows the "Vibb Phone" bar *and* a 34 px "Settings" title. The mockups' tab landing pages have
     no bar at all; §3.0 only says it goes on inner pages.
   - The spec should name the step that removes it from the tab landing pages too. Otherwise it outlives the redesign
     (step 15 is the natural place, "clean-up").
2. **The mockup's four unlabelled groups instead of the table's Rules/Content/Server headers: accepted.** §6.0 gives
   the mockups the grouping and §3 the content, and every row of §3.6 is there.
3. **Server local time for "today" and the backup time: accepted.**
   - It matches the Backups page beside it.
   - On the Docker image (TZ = UTC) "today" starts at UTC midnight until step 2's `display_time`. The spec should
     know this.
   - The conversion itself needs L8's test.
4. **Renaming `/dns` and `/settings/provisioning` in place: accepted.** The titles, the links' text and the docs follow;
   the content waits for steps 14-15.
5. **Not fetching `/updates` in the tab test: accepted.** The template check pins its tab, and fetching it would call
   GitHub.
6. **The browser pass is owed: agreed.** It is the user checks below, and it should happen before the merge.

## User checks (no browser here)

Run the server from `design-31-s1` (plus fixes). Use a phone, or desktop Chrome's device mode at **390 px**.
1. **Light, then dark (system setting).**
   - Open Devices, a phone's page, Calls, Apps, an app, Locate (with a phone that has a fix), Music, a music entry,
     Settings and each of its ten rows, Phone Setup and a provision QR.
   - Expect: no light text on a light box and no white box in dark. The `.error` and `.success` boxes are readable.
   - The tab bar is opaque or blurred, never see-through to unreadable text.
   - The current tab is blue on every page, including the inner ones (Calls → Devices, Web Filter → Settings, a music
     entry → Music).
   - Expect the Locate map to stay under the tab bar when you scroll.
2. **The status bar colour.** Installed on Android, it should be `#F9F9FA` in light and `#161618` in dark. Say whether
   the step between it and the page's `#F2F2F7`/black brand bar looks wrong.
3. **JS off** (DevTools › Settings › Debugger › Disable JavaScript).
   - On the device page, change a hardening switch and press its Save, and do the same with the screen-timeout select.
     Each saves and comes back at the same card or anchor.
   - The tab bar still marks the current tab.
   - Sign Out on Settings works.
4. **JS on: auto-save and no jump.**
   - Scroll halfway down the device page and toggle a switch. The page comes back at the same place.
   - Do the same with the screen-timeout select, picked with the mouse or finger, and with a music entry's "Offline"
     select on `/music`.
5. **Keyboard (desktop Chrome and Firefox, and Safari if you have a Mac).**
   - Tab to the screen-timeout select and press ↓ twice. Expect no reload.
   - Press Enter. Expect a save, in place.
   - Again: ↓ then Tab away. Expect a save.
   - Again: open the list with Space or Alt+↓, pick with ↓ and Enter. Today this doesn't save until you Tab away (L2).
   - Tab through Settings to Sign Out, and through a one-phone Devices list. Today no focus ring is visible (M1).
6. **Forced colours.** Windows High Contrast, or DevTools › Rendering › Emulate forced-colors. The tab bar's current tab
   and the hub rows are readable.
7. **An installed app.**
   - Android: after the update the app still opens signed in, and within days the name becomes "Vibb Phone" (Chrome may
     ask).
   - iPhone: the icon keeps its old name until it is re-added.
   - Neither should reinstall or lose the session.
