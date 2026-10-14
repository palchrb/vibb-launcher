# PWA browser pass after design 31 (2026-10-14)

Headless Chromium (Playwright 1.48.2), 390x844, on local `main` at `bd57cafb` (`style.css?v=25`). Nothing in the product was changed.

## Setup

- Server built from `server/` (debug), run from a scratch dir with `static` symlinked, `ADMIN_PASSWORD`, `BIND_ADDR=127.0.0.1:3201`, `INSECURE_COOKIES=true`; no `LAUNCHER_SIGNATURE_CHECKSUM` (so Connect shows "Server setup needed" and no QR form - the QR answer page was not covered).
- Signed in through the real pages (forced password change, TOTP set up with a code computed from the key on the page). Phones "Anna" and "Ola" were added in the PWA and enrolled with a typed code (`/api/devices/enroll`), then given a status report (apps, location, time_state 42/90 min, call_state, capabilities); "Test Phone" was left not set up. Also a time rule, a contact, a blocklist and a blocked domain, an "own files" music entry and a GitHub-type tracked app.
- Every page reachable from the five tabs (39 URLs, found by following links) was loaded in light, dark and JS-off, with a full-page screenshot each (scratch dir `shots/`, a side-by-side light | dark | no-JS strip per page `shots/trio-<page>.png`). Checks run in the page: horizontal scroll, `h1` count, unlabeled controls, contrast (WCAG 4.5/3), default-looking controls, `aria-current`, console errors/warnings, `pageerror`, failed requests, HTTP 4xx/5xx.
- Saves: per page up to four forms (lowest switch/select, lowest button form, one `<details>` confirm, topmost switch) were scrolled to mid-screen, saved, and measured (`saves.js`; destructive actions skipped), with JS and without.
- The sign-in pages were walked on a fresh database for each of light, dark and no-JS (`shots-signin/`).
- Because of Finding 1, the whole crawl was repeated with only that one brace added (served from a patched copy of `style.css`, `shots-fixed/`) to separate what that one error explains from what is left.

Scratch dir: `/tmp/claude-1000/-workspace-handy/430a895f-506c-4fe9-bfff-b4b9a36a016b/scratchpad/browser/`

## Result in one paragraph

No page overflows horizontally, every page has exactly one `<h1>`, every tab page marks its tab with `aria-current="page"`, no page logs a console error or failed request (the only 4xx is the intended 400 for an empty phone name), the tab bar never covers the last row, and every autosave control has a Save button without JS. Two things are wrong: one missing `}` in `style.css` breaks all the CSS after it (the worst visible effect is an invisible Save button in the rule editor), and without JS several autosaving switches land at the top of the page, out of view.

## Per page

Legend: ok = nothing found; numbers refer to the findings below. "Saves" is the JS result / the no-JS result. Console is clean unless stated. Contrast (WCAG) was checked in both schemes.

| Page | Light | Dark | No JS | Saves (JS / no JS) | Console |
|---|---|---|---|---|---|
| `/devices` | 1 ("+" Add Phone missing), 6a | 1 | same as light | - | ok |
| `/devices/{id}` summary (Anna, Ola) | 1 (header not centered, Music icon missing, "Locate" underlined), 6a | 1 | same | +15 min and Pause: 3 | ok |
| `/devices/{id}` summary, not set up (Test Phone) | 1 | 1 | same | - | ok |
| About This Phone | ok | ok | ok | - | ok |
| Advanced | ok (6b: native checkbox in the Disconnect confirm) | ok | ok | ok / 2 (kiosk-escapes switch) | ok |
| Lock Screen & PINs | ok | ok | ok | Parent PIN: 3 / lock-screen apps switch: 2; screen-timeout select: 2 (still in view) | ok |
| Home Screen | 6b (wallpapers are native green checkboxes) | 6b | 6b | ok / 2 (policy switches, still in view) | ok |
| Web Filter (phone) | ok | ok | ok | ok / 2 (log switch) | ok |
| Apps (phone) | 6e | 6e | 6e | ok / ok | ok |
| Music (phone) | 1 (Music row icon, see summary) | ok | ok | music settings and entry switch: 3 | ok |
| Calls & Contacts | ok | ok | ok (Upload is a plain button, correct) | ok / ok | ok |
| Time (phone) | 1 (day names wrap mid-word: "Mon/day", "Wedne/sday") | 1 | 1 | rules switch: 3 | ok |
| Locate tab (list) | ok | ok | ok | - | ok |
| Apps tab | ok | ok | ok | - | ok |
| App page | 6e, 6f | 6e | 6e | display form: 6f / is-launcher: 6f | ok |
| Add App | ok | ok | ok | - | ok |
| Music tab | ok | ok | ok | - | ok |
| Music Settings | ok | ok | ok | ok / ok | ok |
| Categories | 6b (native radios for icon and colour) | 6b | 6b | ok / ok | ok |
| Import | 6c (native file button) | 6c | 6c | - | ok |
| Storytel | ok | ok | ok | - | ok |
| Music entry | ok | ok | ok | Save: 3 | ok |
| Settings hub | ok | ok | ok | - | ok |
| Time Rules | ok | ok | ok | - | ok |
| Rule editor (new / edit) | 1 (Add/Save invisible; day names wrap one letter per line) | 1 (Save is a blue box, text overflows it) | 1 | Save: ok | ok |
| Daily Limit | ok | ok | ok | Save: ok | ok |
| Web Filter (global) | ok | ok | ok | upstream select ok; list switch: 3 | ok |
| Blocked Activity | ok | ok | ok | - | ok |
| Wallpapers | 6c | 6c | 6c | - | ok |
| Phone Setup | ok | ok | ok | country code Save: ok | ok |
| Backups | ok | ok | ok | schedule Save: 3 | ok |
| Software Updates | ok | ok | ok | - | ok |
| Account & Password | ok | ok | ok | - | ok |
| Security Log | ok | ok | ok | - | ok |
| Add Phone (Name) | ok; empty name: reason shown under the field | ok | ok | ok | 400 for the empty name (intended) |
| Connect | 1 (the two section titles run into their subtitles: "QR codeNew or reset phone") | 1 | 1 | - | ok |
| Setup code | 6d | 6d | 6d | - | ok |
| Sign in, wrong password | ok | ok | ok | - | ok |
| Change password (and refusal) | ok | ok | ok | - | ok |
| 2FA setup (key behind "Can't scan it?", wrong code) | 6g | 6g | ok | - | ok |
| 2FA login (and wrong code) | ok | ok | ok | - | ok |

`/devices/locate?device=N` (the map) was not opened.

## Findings

### High

**1. One missing `}` in `server/static/style.css` turns the last ~160 lines into nested rules that never apply (steps 9, 10, 14 and 15 CSS).**
- Where: `style.css:1745-1746`, `.group-apps > .cell > .flash { padding-left: 64px;` is never closed, and the "Design 31 step 14" block follows directly. In the browser the stylesheet has 304 top-level rules and that rule holds 57 nested ones (checked in `document.styleSheets`). The same text is in `85fc01ce~1`, so it is not from the CSS clean-up alone; the step 14 section was inserted right after it.
- Seen on every page, 390 px, light and dark and no JS. Examples:
  - Rule editor (`/schedules/rules/new`, `/schedules/rules/1`): the Add/Save button in the top bar is a solid blue square with blue text in light mode (the label is invisible), and in dark mode a blue box with the white text spilling out of it (`button.title-action` loses its `background: none`, the global `button` rule applies). The Mon..Sun labels are squeezed to one letter per line.
  - Device › Time, Own daily limit: "Mon/day", "Tues/day", "Wedne/sday" break mid-word.
  - Devices and Apps tabs: the "+" in the top bar (`.title-add`) is missing, the bar is just empty space.
  - Device summary: the header is not the centered avatar/name/status block (avatar top-left, name far below, status line left-aligned with a gap), the Music row has no coloured icon (invisible in light mode), "Locate" in Quick actions is underlined.
  - Connect: section title and subtitle are glued together.
- With that single brace added (patched copy served to the same pages) all of the above render as designed (`shots-fixed/`); only items 6a-6g remain.
- Screenshots: `shots/schedules_rules_1-light.png`, `shots/schedules_rules_1-dark.png`, `shots/trio-devices_1.png` (as shipped) against `shots-fixed/trio-devices_1.png` (one brace added), `shots/trio-devices.png` against `shots-fixed/trio-devices.png`, `shots/trio-devices_1_time.png` against `shots-fixed/trio-devices_1_time.png`, `shots/trio-devices_1_provision.png`.

**2. Without JS, six autosaving forms land at the top of the page; for three the form is then out of view.**
- Steps: no JS, open the page, scroll the form to mid-screen, change it, press the Save that replaces the autosave. The redirect has no `#fragment` and nothing restores the scroll position, so the page comes back at scrollY 0.
- Out of view afterwards (form top before -> after, viewport 844): Advanced, the `kiosk-escapes` switch group (383 -> 1323); Web Filter (phone), "Keep a log" (`/dns/log/1`, 540 -> 1061); Lock Screen & PINs, a lock-screen app switch (`/devices/1/lock-screen/apps`, 606 -> 1298).
- At the top but still on screen (the jump is 330-210 px): Home Screen `/devices/1/policy` (427 -> 755); Lock Screen & PINs screen timeout (397 -> 605).
- Apps page, a tracked app's "Source & Signing" switch (`/apps/tracked/{id}/is-launcher`): the page lands at the top and the (closed) form is below the fold.
- With JS the same forms keep their position (scroll-restore): Advanced 383 -> 383, Web Filter 648 -> 648, Home 471 -> 471, Lock Screen 397 -> 397.
- Other no-JS forms are fine: the ones with a `#fragment` land on a group that contains the form (device Apps downloads, DNS server, DNS lists, Phone Setup country code, Daily Limit, backups schedule).
- Screenshots: `shots-saves/devices_1_advanced-2-nojs-before.png`, `shots-saves/devices_1_advanced-2-nojs-after.png`, `shots-saves/devices_1_filter-8-nojs-before.png / -after.png`, `shots-saves/devices_1_lock-5-nojs-before.png / -after.png`.

### Medium

**3. With JS, a save that redirects to a `#fragment` scrolls the form's group to the top edge of the screen (a 360 px jump).**
- `scroll-restore.js` does not restore when there is a hash, so the browser puts the anchor at the top. The group lands at 27 px from the top.
- Measured (form top before -> after): summary "+15 min" 388 -> 27 and "Pause" 401 -> 27 (`#quick`); Device › Music switch 390 -> 27 (`#music-settings`) and entry switch 235 -> 1; Device › Time "Own rules" switch 396 -> 27 (`#rules`); Web Filter list switch 146 -> 27 (`#lists`); Backups schedule Save 458 -> 659 (`#schedule`); Music entry Save 399 -> 246 (`#settings`); Parent PIN 304 -> 131 (`#parent-pin`, flash there); Categories move 137 -> 0 (`#category-4`).
- This follows §5.1 rule 4 (the flash's group id is the anchor), but the user's rule is that the page does not move. Tapping "+15 min" twice in a row moves the page both times.
- Screenshots: `shots-saves/devices_1-0-js-before.png`, `shots-saves/devices_1-0-js-after.png` (summary +15 min), `shots-saves/devices_1_music-*-js-*.png`.

### Low

**6a. Contrast.** The orange "Away" (device list rows, summary header) is 4.3:1 on the light background (needs 4.5). Dark mode: nothing below 4.5 apart from Finding 1's Save button.

**6b. Native widgets among styled rows.** Home Screen wallpapers are native green checkboxes (the page's other controls are switches); Music › Categories icon and colour pickers are native radio buttons; Advanced's Disconnect confirm has a native checkbox. Usable, but they look unfinished next to the rest (`shots/trio-devices_1_home.png`, `shots/trio-music_categories.png`).

**6c. Native "Choose File" button** on Wallpapers (and Music › Import) is the browser default, not styled like the other controls (`shots/trio-wallpapers.png`).

**6d. Setup code page** (`/devices/{id}/regenerate-code`): the code is flush with the card's left edge (no padding) and left-aligned, while the rows below have 16 px (`shots-signin/nojs-12-setup-code.png`; same in light/dark). Not re-checked with the patched CSS.

**6e. App page and Device › Apps:** "Show on phones as" / the per-app display row is a bare `<details>` with the browser's black triangle and "Name and icon: the app's own name and icon" in plain text, not the chevron row used everywhere else (`shots/trio-apps_tracked_1.png`, `shots/devices_1_apps-light.png`). Same with the patched CSS.

**6f. Saving a tracked app's display form** (App page, "Show on phones as") returns with the `<details>` closed and the page back at the top (scrollY 0; the page is 844 px high with it closed), so the parent sees the row collapse and no flash in view. Observed with and without JS.

**6g. 2FA setup:** the key ("Can't scan it?") wraps at 390 px so the last character sits alone on a second line (`shots-signin/dark-6-setup-2fa-key.png`).

## Checks that were clean

- No horizontal scroll on any page, light, dark or no JS (`scrollWidth` = 390 everywhere).
- One `<h1>` on every page; tab bar `aria-current="page"` on every tab page; the tab bar never covers the last row (59-100 px spare at the bottom).
- Form controls: all labelled (the icon radios in the Apps pickers are labelled by `alt`/`title`, not flagged as a defect).
- Console/requests: no console error, `pageerror`, failed request or 4xx/5xx on any page (the only 400 is the intentional empty-name refusal on Add Phone).
- No JS: every page renders, confirms (`<details>`) open and submit, every autosave control has a Save in `<noscript>` (the one without is the contact photo's own "Upload" button, a normal button).
- Dark mode: no unreadable text found; sign-in pages, 2FA, Add Phone and the code page are fine in all three modes.

## Not covered

The QR answer page (needs `LAUNCHER_SIGNATURE_CHECKSUM`), the Locate map (`?device=N`), the Music import preview, Storytel with a login, the lock/wipe command pages, and a real device stream (every phone shows "Away", as no stream is open).
