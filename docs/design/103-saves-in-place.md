# Design 103: saves in place, and the Advanced page's polish

Status: design, revision 2, 2026-11-15 (branch `design-103`, from main c03db8997). Server only: no launcher change, no
API or contract file, no migration. The user accepted D1-D13 as recommended ("Ja til alle"). The design QA is
`docs/review/qa-103-design.md` (2 High, 5 Medium, 10 Low), and every finding is folded in below.

## Revision 2 (2026-11-15, after the design QA)

Every finding is a decided fix. The sections it changes are rewritten in place, so where revision 1's text and this list
disagree, the section as it now reads is the design.

- **103-1 (High) Keyed matching with lookahead** (§2.3). Children are matched by key, never by bare position:
  - The key is the `id`. For an element without one, it is a soft key: the tag plus the first of `action`
    (forms), `data-flash`, `data-warning`, `data-mobile-line`, `data-choice`, `data-row`, `data-setup-step`, `for` or
    `name`. An element with none has the tag alone as its key.
  - Lookahead, idiomorph-style:
    - Current and new child agree: they are morphed.
    - The new child's key matches a later current child: the current children in between are removed.
    - The current child's key matches a later new child: the new ones before it are inserted.
    - Neither: the new one is inserted, and the current one is kept for a later match or removed at the end.
  - So a warning that comes in before a form never replaces that form's node.
- **103-2 (High) The anchor survives a closing sheet** (§2.4). This reuses scroll-restore's own rule. The tops of the
  saved form and of each of its id'd ancestors are recorded (`screenTops`). After the morph, the nearest one that is
  still connected **and drawn** (`getClientRects().length > 0`) is the anchor. A closed `<details>` counts through its
  `<summary>`. If the form has moved out of all of them, nothing scrolls.
- **103-3 (Medium) The gap between a save's landing and its GET's morph** (§2.2, new step list):
  - A flip in the gap is queued, and is neither lost nor shown as the server's state.
  - A save in flight never loses its busy marks to the morph.
  - Nothing reloads under a POST on its way.
  - A superseded refresh hands its saved forms on.
  - `handy:saved` says how many saves are still out, and the flags clear only at none.
- **103-4 (Medium) auto-reload.js in the head** (§2.7):
  - It arms when `[data-waits]` appears, also by a swap, and disarms when it goes.
  - `stop()` by a save is undone, and the timer re-armed, when the last save has landed. A page back from the
    back-forward cache re-arms.
  - The `notice=`/`app=` strip runs only on a page that holds the marker at load: today's pages, today's rule.
- **103-5 (Medium) reconnect.js's wait and pull to refresh end when a swap lands** (§2.7):
  - A wait of the new kind `"swap"` restarts by `handySwap.refresh`, never by `location.reload()`.
  - `handy:swapped` ends every wait and the pull spinner.
  - The save's 400 ms bar is a counter of its own (`handyReconnect.busy`), not a wait, so a second tap's `end()`
    can't remove it.
- **103-6 (Medium)** A refresh that lands while the page is still parsing waits for `DOMContentLoaded` before the
  morph (§2.2).
- **103-7 (Medium) A real-engine check** (§6):
  - `server/jstest/real/swap-fixture.html` holds fixture pairs and loads `../../static/swap.js`.
    `server/jstest/real/swap-real.js` runs it in Chromium and WebKit with the local Playwright and asserts every pair.
  - It is run by hand in the build round and by the code QA: `node server/jstest/real/swap-real.js`.
  - It is not run in CI: there it would download two browsers on every run. Without Playwright on the machine it
    prints "skipped" and exits 0.
  - Its file name keeps it out of `node --test jstest/`.
- **103-8 (Low) Hidden inputs written by scripts** are re-written on `handy:swapped` by their script:
  - register-sw.js's sign-out `endpoint` fill is split from its submit listener, so it can run again without a second
    listener. That is in step 2.
  - contact-form's `more`, quick-sheets' and notifications' hidden inputs follow in step 3.
- **103-9 (Low) The focused field** (§2.3):
  - In the saved form, its value takes the server's when it still equals what was sent, with the caret at the end.
  - Elsewhere, the morph skips the focused element's `value` attribute, so neither the caret nor the dirty test moves.
- **103-10 (Low)** An imported subtree's `<noscript>` elements are emptied before insertion (§2.3).
- **103-11 (Low, D10)** The open-state line shows whenever the row has no value: Play's install mode **and** an open
  with no known end (§3).
- **103-12 (Low) The flash** (§2.3, §3):
  - A `[data-flash]` element in the new page is always imported fresh, never morphed. A second save in the same group
    with the same words therefore shows a new flash.
  - Its text is copied into one persistent, visually hidden live region outside `main` (`#swap-status`, in
    `partials/js_words.html` next to `#js-words`, `role="status"`).
  - This replaces revision 1's "out of scope".
- **103-13 (Low) The hairline rule** (§4):
  - Every row line's base declarations sit in one rule, and the per-list rules keep only their `left`.
  - The 2dppx block comes after all of them, so no list gets the border and the scaled line both.
  - D12's wording is honest now: at 3x the line is 1.5 device px against today's 1 (when today's showed).
- **103-14 (Low) Focus contrast** (§5, D13):
  - The numbers are on `--surface`.
  - On `--bg` #F2F2F7 the line is 2.8:1, and in light mode the focused edge is fainter than the resting grey's 3.6:1.
  - In dark mode only the line's colour changes on a tap: the fill is the night in both states.
  - (a) stands, as the user chose.
- **103-15 (Low)** The anchor of a refresh without a save is the first id'd element in `main` whose top is at or below
  main's visible top (below the fixed top strip), found from the elements' rects. `elementFromPoint` is not used
  (§2.4).
- **103-16 (Low)** `main.container` gets `overflow-anchor: none`. Chromium then behaves like WebKit, and swap.js is the
  only thing that moves the scroll (§2.4).
- **103-17 (Low) The full list of tests that will break** is in §6, including the negative `!contains("auto-reload.js")`
  asserts that must flip to `data-waits`. §2.5 now words `calendar-preview.js`'s criterion as "nodes a save keeps".

On the decisions:
- D4's "holding the button" is every `<details>` ancestor of the submitter.
- D1's flip of a held form while offline is queued and sent once the server answers (§2.6).

What the user asked for (2026-11-15):

1. *"Btw i advanced settings per tlf side, om jeg slår brytere på og av der så kommer det forskjellige grader av synlig
   refresh hvor det også ser ut som noe Css blir tegnet på nytt eller noe? Noe som kan fikses? Gjennomgående i hele pwa
   ønsker jeg jo egentlig ikke den type oppførsel."*
2. On Avansert's "Åpne for oppsett", opened (two screenshots): *"Fortsatt for mye og unødvendig tekst? Unødvendig med to
   linjer? Mørk fersken linje er litt rar. Unødvendig med to duplicates der og?"*
3. *"Se også at det blir streker mellom på/av brytere nedover?"*: the lines between the switch rows show in one state of
   the page and not in another.
4. A text field's focus border softer: lighter and thinner peach, everywhere (Add Phone's name field was the example).

**The short version.**

1. A save no longer loads a new page. The form is posted by script, as today. When the server's answer says "back to this
   page", the script fetches that page, reads it off-screen and changes only the nodes that differ in the page on
   screen: the flash, rows that come or go, badges, the Attention list, counts. Nothing else is redrawn. Without JS the
   forms post as before. One general mechanism, no per-route code. The server keeps answering every save as it does now.
2. The same refresh in place serves the 3 s refresh of a page that waits for the phone (Avansert waits after "Åpne for
   oppsett"). On the user's choice (D3) it also serves pull to refresh and the reload after being away.
3. Avansert's "Åpne for oppsett": one row that opens in place (its chevron turns down, and the peach outline goes), one
   short line ("Apper, innstillinger og Play åpnes i 2 timer."), one button. No second title, no Lukk, no long note.
4. The hairlines between rows are drawn so that they can't vanish at a fractional position. The cause is confirmed in
   WebKit at 3x. The fix is one rule for every list.
5. A focused field's border: 1 px of a lighter peach instead of 1.5 px of the dark one.

---

## 1. Findings

### 1.1 What a save does today

- `static/autosave.js:47-51`: a `data-autosave` control calls `el.form.submit()` on change. That is hooked by
  `static/scroll-restore.js:519` (`proto.submit`).
- `scroll-restore.js:489` `stay()`: a POST whose action has a `#fragment` (a form that comes back to its page) goes out
  with `fetch` and `X-Handy-Stay: 1`. `admin_auth.rs:963` `stay_answer` turns the 303 into a `204` with
  `X-Handy-Location` (design `pwa-back`, 2026-11-14). That part is sound and stays.
- Then `land()` (`scroll-restore.js:371-389`) calls `history.replaceState(…, landing)` and **`location.reload()`**. Every
  switch therefore throws the document away and loads a new one. What the user sees as "CSS drawn again":
  - The whole page is styled and laid out from scratch. The night band, the top strip and the tab bar's
    `backdrop-filter` blur (`style.css:804`) are composited again.
  - Nunito has `font-display: swap` (`style.css:210`), so a title can paint in the fallback face for a frame.
  - Avatars and logos are decoded again.
  - The scroll place is put back at DOMContentLoaded, at `load` and on the two frames after `load`
    (`scroll-restore.js:23-24`). A slow paint can show a frame at another place first.
  - The amount of all this depends on the page's size and the network, hence "forskjellige grader".
- Other reloads of the same kind: `auto-reload.js:137` (every 3 s while a page waits for the phone, Avansert among them:
  `device_advanced.html:388`), `install-now.js:113`, `pull-refresh.js:135` and `reconnect.js:656` (the reload in place
  after being away).
- Flags that assume a save always leaves the page: `reconnect.js:785` and `pull-refresh.js:111/115` set `saving = true`
  on any `submit` or `data-autosave` change, and only a page load clears them. `auto-reload.js` `stop()` is permanent
  too. With saves in place, these must be cleared when the save has landed (§2.6).

### 1.2 Avansert's "Åpne for oppsett" (design 93 §6.6)

`device_advanced.html:18-53`, `quick_actions.rs:598-686`, `nb.json:4057-4061`:

- The `<summary>` row has the tile, "Åpne for oppsett", the value and a chevron. The opened body repeats the tile and the
  title in a `.qa-head` row with a Lukk button (`:29-33`). That is the duplicate the user saw.
- The text is the note ("Apper, Innstillinger og Play Butikk åpnes på telefonen til {name} i 2 timer, så du kan sette
  opp noe der. Den låses igjen av seg selv, eller med Lås igjen.", three lines at 390 px), the optional `on_lock` line
  and a red `.qa-danger` button "Åpne for oppsett i 2 t". While open, the row says "til 17:10" and the body says
  "Åpnet for oppsett til 17:10" again.
- **The dark-peach line is not a focus ring.** It is `style.css:3129`:
  `.row-sheet[open] > summary { box-shadow: inset 0 0 0 2px var(--tint); border-radius: 12px; }`, the open state's
  mark. It shows on every open, by tap or by key.
- A tap focus ring on a `<summary>` can still show on Safari before 15.4. There, `summary:focus` (`style.css:337`) draws
  the ring, and the `:focus:not(:focus-visible)` reset is dropped as a whole. `focus-mode.js`'s `pointer-focus`
  (`style.css:396-400`) covers only input, select and textarea.

### 1.3 The hairlines (confirmed)

`style.css:943-990`: the rows' `::before` lines are 1 px `--sep-light`, and on 2x+ screens 0.5 px `--sep`. A 0.5 px
border is snapped to device pixels, and at 3x some fractional positions round it to nothing. Measured in Playwright's
WebKit (the iPhone's engine) at `deviceScaleFactor: 3`: a group of 12 switch rows, its top moved in steps of 1/24 px,
24 positions.

| Line | Lines shown of 11, per position | Device px |
|---|---|---|
| Today: `border-top: 0.5px` | 9 or 10 of 11 at 2 positions, else 11 | 1 |
| `height: 0.5px` box | always 11 | 1 or 2, alternating (uneven) |
| `height: 1px` + `scaleY(0.5)` | always 11 | 1 full + 1 half, every line the same |
| 1 px `--sep-light` | always 11 | 3, lighter |

A save, a reload, an opened `<details>` or a sub-line's 16.9 px height above a row moves every row below it by a
fraction of a pixel. So the same line shows in one state of the page and is gone in the next. That is what the user
saw. Chromium draws the 0.5 px line on every position. Other 0.5 px lines: `.row-sheet-body` (`:3130`, it goes in §3)
and `.all-show` (`:3732`, inside a Leaflet popup, left as it is).

Other row hairlines draw 1 css px and never vanish: `.group-music` cards (`:2547`), `.wallpaper-item` (`:2770`),
`.contact-body` (`:2805`), `.held-body` (`:3557`) and `.group-lines` (`:3638`). At 3x they are heavier than the group
lines.

### 1.4 The field focus

`style.css:366-393`: a focused field's border is `--tint` (#A64B25, 5.8:1 on white; dark #F0A884) plus
`inset 0 0 0 0.5px`, so 1.5 px. The borderless `.row-time`/`.row-num` get a 1.5 px inset, and `.row-input`/`select.pick`
a 1.5 px underline. The resting edge is `--field-border` #86868B (3:1, WCAG 1.4.11). `shell.rs:1660-1668` pins the
values.

---

## 2. Saves in place

### 2.1 How the server tells the script what changed: the page itself

Three ways were weighed:

| | What it costs | What it misses |
|---|---|---|
| **A. A small JSON answer per route** | 178 forms over about 45 handlers each say what changed. The page's rendering is written twice (Askama and JS). | Whatever a save changes elsewhere: the Attention list, the bell count, another group's "venter på telefonen", `wait_for_phone`, a row that moves. Each handler has to know. It drifts. |
| **B. Named fragments** (`data-region`, replaced whole) | Markup on every page. A replaced region loses focus, open `<details>` and the switch the parent just tapped. | Anything outside the named regions. |
| **C. The page, compared node by node** (chosen) | One script, about 300 lines of ES5. One extra GET per save. | Nothing the page shows. It is the same page a reload gives. |

**C** it is. The server's rendering stays the only truth. The extra round trip is a few tens of ms on the LAN or
Tailscale, and it replaces the full reload that costs one already. Following the redirect on the server (one round
trip) was also weighed. It was dropped: `stay_answer` would have to re-enter the router with the session, and the GET
answer is what the client needs anyway.

No server change is needed for the mechanism. `stay_answer`, the CSRF check (`Sec-Fetch-Site: same-origin` from `fetch`,
`admin_auth.rs:779`) and the SameSite=Strict session cookie (sent by a same-origin `fetch` with
`credentials: "same-origin"`) work as they are. The HTML is `private, no-cache` (`admin_auth.rs:1031`), so the GET goes
to the server.

### 2.2 The flow (new `static/swap.js`, un-deferred in `head.html` after `scroll-restore.js`)

`window.handySwap.refresh(url, opts)` returns `true` when it has taken the job and `false` when the caller must do what
it did before (the reload).

1. **The save** goes out as today (`stay()`), busy as today: `aria-busy` on the form, the submitter disabled, a second
   flip queued (`again`). New: if no answer has come after **400 ms**, the thin top bar shows
   (`handyReconnect.busy(+1)`/`busy(-1)`). It is a counter of its own on reconnect.js's `.net-progress`, not a wait,
   so a later save's `end()` can't hide it (D5, 103-5).
2. **The landing** (`settle()`, `scroll-restore.js:411`): a `land` to the same path now calls
   `handySwap.refresh(landing, { forms: [the saved forms], byButton: [the submitters] })` instead of `land()`. Another
   path stays `location.assign`, as a link would.
   - **The landed forms stay busy until the morph is done** (103-3). A flip of one of them in the gap sets its `again`,
     as during the POST. Such a form counts as *not saved* in the morph: its controls keep the parent's state, as a
     dirty control does. Right after the morph it is sent again (a new stay save). The tap is never lost and never
     shown as the server's state.
   - A save of **another** form started in the gap is in `busy` and `inflight`. The morph never touches `aria-busy` or
     `disabled` on a form in `busy` or on its submitter. After the morph, only the landed forms' entries are released,
     never `releaseAll`.
   - **Nothing reloads while `inflight > 0`.** A GET that answers while another save is out is dropped unmorphed. The
     next landing refreshes, and it inherits the dropped refresh's forms, submitters and "saved" (the same holds for a
     fallback real reload: it waits for `inflight` to reach 0). A refresh started later (an auto-reload tick, a pull)
     also inherits a pending one's forms instead of dropping them: one pending landing record, and the newest GET wins.
3. **The GET**: `fetch(url, { credentials: "same-origin", redirect: "manual", cache: "no-store" })`. Each GET has a
   number, and only the newest one's answer is used (its landing record carries every inherited form).
   - **Before the morph**, `document.readyState === "loading"` waits for `DOMContentLoaded` (103-6). A switch saved
     while a long page is still parsing (autosave.js is un-deferred for that) must not have the rest of `main` appended
     twice.
4. **Is it swappable?** It must be a 200 with `text/html`. An `opaqueredirect` (the session ended, so the sign-in) and
   anything else fall back. A 423 goes to the lock (`handyLock.lock(here)`), as `auto-reload.js` does. The answer is
   parsed with `new DOMParser().parseFromString(html, "text/html")`. It falls back (a real reload, today's
   `replaceState` + `location.reload()`) when:
   - the new page has no `main`;
   - `html[lang]` differs (a language change: the tab bar and `#js-words` are outside `main`);
   - `html[data-lock-ms]` differs (the app lock turned on or off);
   - the stylesheet hrefs or the `script[src]` list differ (a server update's `?v=` also lands as a real load, so the
     new CSS is fetched);
   - a page script on the current page lacks `data-swap-ready` (§2.5).
5. **The morph** (§2.3) of `main` and of an editor page's `body > header.band` (`band::editor`, a child of `<body>`).
   Also `document.title`.
6. **The place** (§2.4), then `history.replaceState(history.state, "", landing)` without the one-shot words
   (`notice`, `app`, `saved`, scroll-restore's `ONE_SHOT`). No new history entry, as today, and a later reload doesn't
   show the flash again.
7. **After**: scroll-restore's form record is cleared (sessionStorage `handy-scroll` and the `window.name` prefix), so a
   reload within 20 s can't use a stale place. `document` gets `handy:swapped` (a `CustomEvent`, `detail.root = main`).
   If the landing record holds saved forms, it then gets `handy:saved`, once per landing, with `detail.forms` (every
   form it carries, inherited ones included) and `detail.pending` (the stay saves still out). reconnect.js,
   pull-refresh.js and auto-reload.js clear their `saving`/`leaving` only when `pending` is 0. The landed forms'
   busy marks and their share of the bar go, and then the `again` re-sends start.

### 2.3 The morph: what changes and what stays

`morph(current, next, ctx)`, ES5. A node keeps its identity when it can, so listeners, focus, a running transition and a
decoded image stay.

- **Same node**: the same node type and the same key (below). Then its attributes are synced (set where they differ,
  removed where gone) and its children are morphed. Otherwise the new node is imported
  (`document.importNode(next, true)`).
- **The key** (103-1): the `id`. For an element without one, a soft key: the tag plus the first of `action` (a form),
  `data-flash`, `data-warning`, `data-mobile-line`, `data-choice`, `data-row`, `data-setup-step`, `for` or `name`.
  Failing all of those, the tag alone. Text and comment nodes key by their type.
- **Children: keyed matching with a lookahead** (idiomorph's way, 103-1). Walk the new children with a cursor on the
  current ones:
  - the keys agree: morph the two;
  - the new child's key matches a later current child (an id'd row that moved, or the form after a removed line):
    remove the current children in between, then morph;
  - the current child's key matches a later new child (a warning inserted before a form): insert the new children
    before it;
  - neither: insert the new child, keep the current one for a later match, and remove leftovers at the end.
  - An id'd element found further away is **moved**, never rebuilt.
  - The result: an Attention line that comes in before a form leaves that form's node, its focus and its live
    controls where they were. The anchor (§2.4) then holds the place.
- **An imported subtree** has every `<noscript>` inside it emptied before insertion (103-10). DOMParser's real
  `<button type="submit">` would otherwise join `form.elements` and change implicit submission.
- **`[data-flash]`** (the group's result line): always imported fresh, never morphed, so a second save with the same
  words still shows a new line. Its text is also put into the one live region `#swap-status` outside `main` (103-12),
  so a screen reader says it.
- **Text and comments**: `nodeValue` set when it differs. An `<img>` whose `src` is unchanged is never touched.
- **`<noscript>` is opaque**: the current one is kept, never descended into, never replaced. DOMParser parses with
  scripting off, so the new page's `<noscript>` holds real elements (the Save buttons), which would show if inserted.
- **`<script>`**: never inserted. The script-list check already guarantees the same files.
- **`data-swap-keep`**: an element that a script inserted or owns is left exactly as it is, and kept where it is when
  the new page has no counterpart. Examples: `register-sw.js`'s `#push-banner` in `main`, and a map container if a map
  page ever opts in.
- **`<details open>`**: the parent's state wins: an open one stays open, a closed one stays closed. One exception: every
  `<details>` **ancestor of the button** that sent the save takes the server's state (D4, as a reload does today). A
  confirm sheet ("Lås igjen", "Koble fra") then closes, while a contact row a switch was flipped in stays open. The
  server re-opens on success only what the parent had open (`category.open`, `contact.open`, `source_open`), so the
  parent's state loses nothing.
- **Busy forms**: `aria-busy` and `disabled` on a form still in scroll-restore's `busy`, and on its submitter, are never
  synced (103-3).
- **Form controls** (`checked`, `selected`, `value`). The attributes, and so the defaults, take the server's, with one
  exception below. The live property:
  - inside a saved form (one that landed and has no `again` queued), the server's. The switch shows what was stored:
    a refused or normalised value shows.
  - a control in a form with `again` queued, or elsewhere changed and not saved (`value !== defaultValue`,
    `checked !== defaultChecked`, a changed `<option>`, auto-reload.js's `typing` rule), keeps the parent's.
  - **the focused element** (103-9):
    - in a saved form, its value takes the server's when it still equals what was sent (the caret then goes to the
      end), else it keeps the parent's text;
    - elsewhere, its `value` attribute is not synced at all (the exception above). So a focused field that isn't
      dirty doesn't jump its caret by following the attribute, and a dirty one doesn't stay "edited" for good
      against a changed default.
  - everything else follows the server's. It is set explicitly: a control with its dirty flag set no longer follows
    its attribute.
  - a file input is never touched.
  - `type=hidden` inputs have no dirty flag: their value *is* the attribute, so they take the server's. A script that
    writes one (register-sw.js's sign-out `endpoint`, contact-form's `more`, quick-sheets', notifications') writes it
    again on `handy:swapped` (103-8).
- **Scripts' load-time passes**: after the morph, `handy:swapped` is dispatched in the same task, before any paint.
  Scripts that change the page at load (confirm.js shows each `[data-confirm-cancel]`, which is `hidden` without JS)
  run that pass again (§2.5). A swap is a reload that keeps the nodes, and every script sees it as one.

### 2.4 The place: a save never jumps

Before the morph, swap.js records **a chain of tops** with scroll-restore's own `screenTops` (103-2): the saved form
(the last one that landed) and each of its ancestors with an id, by id. For a refresh without a save, the chain is one
element (103-15): the first id'd element in `main`, in document order, whose top is at or below main's visible top.
That is main's top edge, or the fixed `.top-strip`'s bottom where it covers it. It is found from the elements'
`getBoundingClientRect`, never from `elementFromPoint`, which would hit the strip or the band.

After the morph, the anchor is the first element of the chain that is still connected **and drawn**
(`getClientRects().length > 0`). A form inside a `<details>` that has just closed is not drawn, so the closed
`<details>` counts through its `<summary>`, then the next id'd ancestor. Main is scrolled by the anchor's change in top.
The row the parent touched stays under the finger even when a warning comes in above it, and "Åpne nå" in the closing
setup sheet keeps the setup row where it was. If the form has moved out of every recorded ancestor (a row moved to
another group), nothing scrolls: the rule of 2026-10-19 that a moved row is never followed (`restoreTarget`,
`scroll-restore.js:277-279`).

`main.container` gets `overflow-anchor: none` (103-16). Chromium's own scroll anchoring would otherwise move main at a
time swap.js can't predict. With it off, Chromium behaves like WebKit, which has no anchoring in a scroller, and swap.js
is the only mover. No focus is moved by the swap. Safari 14 ignores `focus({ preventScroll })`, so any focus call would
scroll there.

### 2.5 Pages and scripts: who is ready

The head's scripts are the swap's business:
- `app-lock`, `focus-mode`, `nav-timing` and `top-strip` touch nothing in `main`.
- `confirm.js` re-runs its load pass on `handy:swapped`.
- `register-sw.js` re-reads the app badge and re-fills the sign-out `endpoint` on `handy:swapped`. The fill is split
  from `signOut()`'s submit listener, so a second listener never posts twice (103-8).
- `reconnect.js` and `pull-refresh.js` clear `saving` on `handy:saved` with `pending` 0, and end their wait and spinner
  on `handy:swapped` (§2.7).
- `auto-reload.js` moves to the head (§2.7).

A **page script** is marked `<script … data-swap-ready>` once three things hold:
- its load pass is idempotent and runs again on `handy:swapped`;
- its listeners are on the document, or on nodes a save keeps (an id'd form, a region it owns);
- every hidden input it writes is written again on `handy:swapped`.

A page that carries an unmarked script refreshes with a real reload, as today. That way the mechanism can ship before
every page script has been converted, and a new page script fails safe.

| Script | Pages | Today | Step |
|---|---|---|---|
| `rule-kind.js` | schedule rule | document listeners only | 2: mark |
| `calendar-preview.js` | calendar title rule | captures `#rule-form` and `[data-preview-result]` at install, both nodes a save keeps (id'd); the server's preview after a save is right | 2: mark |
| `pull-refresh.js` | Devices, summary | document listeners; `saving` cleared on `handy:saved` | 2 |
| `quick-sheets.js` | summary | document listeners; its derived warnings and button words need a re-sync | 3 |
| `app-display.js` | Device › Apps, an app's page | document listeners; the preview re-synced | 3 |
| `contact-form.js` | Calls | per-form init (`more`, Add Contact's pairs) | 3: delegate, re-init |
| `notifications.js` | Devices, Notification Settings | per-element (`card.hidden`, buttons) | 3 |
| `passkey.js`, `reveal.js` | Account, Lock Screen & PINs | per-element, but on forms a save keeps | 3: check, mark |
| `music-cards.js` | Music, an entry | polls and swaps its status blocks itself | 3 |
| `install-now.js` | an app's page | polls; its reload becomes `handySwap.refresh` | 3 |
| `photo-crop.js` | Calls | a file pick, posted natively anyway | 3: mark when its dialog is `data-swap-keep` |
| `locate.js`, `locate-all.js`, `map-full.js`, `map-marker.js`, `zones.js` + Leaflet | Locate, Locations, a zone | the map is the script's | **stays a real reload** (D6) |

### 2.6 Errors: the switch never claims a state it doesn't have

| The save's answer | Today | Now |
|---|---|---|
| 204, same path | replace + reload | refresh in place (§2.2) |
| 204, another path | `location.assign` | the same |
| A refusal (200 refused form, 400, 403, 404, 409, 422, 423) | sent again the browser's way, so the refused page shows | the same (D2): the server refused before acting, the full page says why, and the switch is on a new page |
| 5xx, no answer, an `opaqueredirect`, a foreign address | a GET reload of the page | **a refresh in place**: the page shows what the server holds, so a switch that wasn't saved flips back. In the form's group, a red flash: "Fikk ikke svar fra serveren. Her er det som er lagret nå." / "No answer from the server. This is what is saved now." (`js.save_unknown`, read from `#js-words`) |
| …and the refresh's GET fails too (offline) | the browser's own offline page | D1 (a): the form stays busy and dimmed (`aria-busy`, today's 0.55 and `cursor: progress`), so the switch shows neither state as saved. reconnect.js's wait of kind `"swap"` takes over (§2.7): the bar, "Connecting…" after 3 s, a ping every 5 s, "Try again" after 20 s. Its `go()` is `handySwap.refresh`, never a reload. When the server answers: the refresh in place, the flash above, and the wait ends on `handy:swapped`. A flip of the held switch meanwhile is queued (`again`), kept as the parent's in that morph and sent right after it, so the latest flip is saved once the connection is back. A `pagehide` stops the wait as for a navigation. |
| The GET says 423 | n/a | the lock (`handyLock.lock(here)`) |
| The GET is a redirect (signed out) | n/a | a real reload: the sign-in flow and `handy-wanted` as today |
| Not swappable (§2.2 step 4) | n/a | a real reload, today's path |

A refusal written into the page in place (D2 b) is left out. A 4xx body is often a bare error, not a page, and refusals
are rare.

### 2.7 The other reloads (D3)

- **`auto-reload.js`** moves to `head.html` (deferred) and refreshes only while `main` holds a `[data-waits]` marker.
  The marker is a `<span hidden data-waits></span>` in the five places that load the script today
  (`device_advanced.html:388`, `device_apps.html:89`, `device_calls.html:718`, `device_detail.html:586`,
  `partials/launcher_check.html:26`), on the same server conditions. So the script list stays the same and the waiting
  ends by itself when the server stops drawing the marker. When the tick starts and stops (103-4):
  - **Armed** at install when the marker is there, and on every `handy:swapped` that finds it with no timer running.
    Avansert loads without it, and "Åpne nå" brings it in by a swap: the tick then starts and turns the row into
    "til 17:10".
  - **Disarmed** on a `handy:swapped` that finds it gone.
  - **The save hold**: a save's start sets `leaving` as today. `handy:saved` with `pending` 0 clears it and re-arms
    the timer (today's `tick()` returns without rescheduling while `leaving`).
  - **Away**: `pagehide` stops it, and `pageshow` from the back-forward cache re-arms it when the marker is there
    (today a page back from the bfcache never ticks again).
  - **The tick** calls `handySwap.refresh(location.href)` instead of `location.reload()`, and falls back to the reload
    when the refresh says no.
  - **Its holds stay as they are**: a save on its way, an open `<details>`, a shown Quick Actions sheet, typing. A
    sheet's open state is the script's, and the server would close it.
  - **The `notice=`/`app=` strip** at install runs only when the marker is there at load: the pages it ran on before,
    with today's rule. A swap's own landing drops the one-shot words anyway (§2.2 step 6).
- **`install-now.js:113`**: `handySwap.refresh` first.
- **Pull to refresh** (`handyReconnect.reload()`, used by pull-refresh.js) and **the reload in place after 1-10 minutes
  away** (`reconnect.js:656`): reconnect.js gets a wait kind `"swap"` (103-5).
  - Its timers and chip are those of `"load"`: the bar after 400 ms, "Connecting…" after 3 s, the pings, the 12 s
    restart, "Try again".
  - Every place where `go()` would `location.reload()` (the restart once the network is back, the 12 s restart, "Try
    again") calls `handySwap.refresh(here)` instead. A refresh that says no falls back to today's reload.
  - `handy:swapped` ends the wait (timers, bar, chip) and pull-refresh's spinner (`view.refreshing()` put away).
  - A refresh that hands over to the wait (its GET failed) dispatches `handy:swapwait`, and the spinner is put away
    then too: the chip is the sign from there on.
  - `saveStarted()`'s `end()` ends a `"swap"` wait as it ends a `"load"` one. The refresh it would have done comes with
    the save's landing.
  - A tap on a link stays a navigation (`"load"`).

### 2.8 Safari 14 / Chrome 80

ES5 with `fetch`, `Promise`, `DOMParser`, `CustomEvent`, `Element.closest`, `importNode`, `getClientRects` and
`history.replaceState`, all in Safari 14 and Chrome 80. Not used: `replaceChildren` (Chrome 86), `?.`, `structuredClone`,
`focus({ preventScroll })` as the only guard. `stay()` already needs `requestSubmit`/`submitter` for a **button**
(Safari 16, 15.4). On iOS 14/15 a button save stays a real POST and a full page (today's split, unchanged). An
autosave control (`form.submit()`, hooked) gets the in-place save on Safari 14 too.

### 2.9 Inventory

From the templates (each `<form method="post">`; `server/templates`, partials included): **234 POST forms**. Of these,
**178 save in place**, with 102 of the 104 `data-autosave` controls. The other 56 keep a full navigation:

| Keeps a full navigation | Forms | Why |
|---|---|---|
| Leaves its page (no `#fragment` in the action) | 24 | The answer is another page: Add Phone (`/devices/new`), Delete or Remove of a phone, a time rule, a calendar rule, a bedtime, a zone, an app, an entry, a calendar link or a CalDAV account; Sign out, Sign out everywhere; a notification's Open; the 2FA steps; Spotify's login; Add app; New setup code; Dismiss warning. |
| A password field | 13 | Sent again on a refusal it would count twice (`stayPost`'s rule): Account's password, 2FA reset, Remove passkey, Remove parent, Add passkey; CalDAV add/test; Parent PIN; Storytel; password sign-in; Provisioning; the sign-in and Change password pages. |
| A passkey assertion | 4 | A challenge is spent once: the app lock setting, `/lock`, the sign-in's two passkey forms. |
| A file field | 7 | Contact photo, the phone's picture (both `data-autosave`), the backup upload, an entry's cover and files, an APK upload, a wallpaper. |
| `data-native-post` (answered with a page) | 5 | Show Child PIN, Add Parent and a parent's Reset (one-time password), the setup QR, the music import's upload. |
| Taken over by a script | 3 | Notification Settings' Turn On/Off and the push offer (`notifications.js`, `register-sw.js`: `preventDefault`). |

Of the 178:

- **In place from step 2** (no page script, or one that step 2 marks): Avansert (11 forms, 15 switches), Home Screen
  (3/12), Mobilnett (4/7), Device › Music (5/10), Calendar (4/7), Web Filter (7/3), DNS filter (8/2), Time (2/1) and its
  rule switch partial, Location settings (2/3), Find (4), Zones list (1), Backups (4), Music settings with Spotify's
  partial (10/3), Categories (5), Spotify/Storytel add (2), schedule budget and rule, bedtime, calendar link, CalDAV
  and add, the calendar title rule, Security, Wallpapers (2/1), provisioning's launcher check, the setup QR's
  start-pause, the inbox's Read all, Recent's cancel.
- **In place from step 3** (their page script made ready): the summary (13, quick-sheets), Calls (25/16, contact-form),
  Lock Screen & PINs (11/8, passkey and reveal), Account (3/1), Device › Apps (1/1 and each app row, app-display), an
  app's page (15/4, app-display and install-now), Notification Settings (3/1, notifications.js), Music and an entry
  (6/1 with the music cards).
- **A real reload as today** (D6): Locate's two (Update location, the Alle view's refresh) and a zone's Save (an editor
  with a map).
- The one `method="get"` autosave (`notifications.html:40`, the inbox's phone filter) is a new view of the inbox, not a
  save, and stays a navigation.
- Forms whose landing is another path (a new item's `/new` → `/5`) navigate there by the same rule, with no list to
  keep.

`settings_pages::check_forms` keeps the rule that every form that stays has a fragment. The new pin (§6, `every_page_script_is_swap_ready_or_listed`) keeps the rule
that every page script is either marked or listed as opted out.

---

## 3. Avansert's "Åpne for oppsett"

The markup in `device_advanced.html:18-53`:

```
<details class="row-sheet" id="setup-sheet">
  <summary class="row">[tile] Åpne for oppsett [value: "til 17:10" while open] [chevron]</summary>
  <div class="row-sheet-body">
    closed:  <p class="row row-note">Apper, innstillinger og Play åpnes i 2 timer.</p>
             {on_lock: <p class="row row-note qa-warn">Skole på til 18:30 står på pause så lenge.</p>}   (D9)
             <form …/start-pause#setup><button class="row row-button">Åpne nå</button></form>          (D8)
    open:    {whenever the row has no value: <p class="row row-note">{open_text}</p>}
             <form …/end-windows#setup><button class="row row-button">Lås igjen</button></form>
  </div>
</details>
```

- Out: the `.qa-head` row (the second tile and title), the Lukk button (the row toggles itself; confirm.js's Escape is
  not needed for a row) and the class `act-sheet`. The long `note` and `SetupCard.note` go, along with
  `device.advanced.setup.note` (its only user is `quick_actions.rs:659`; `i18n::every_key_is_used` checks it).
  "Åpnet for oppsett til 17:10" goes when the row already says "til 17:10" (D10). **The line shows whenever the row has
  no value** (103-11): Play's install mode ("Apper og Play Butikk er åpne"), and an open with no known end
  (`setup_until` found no answered `start_pause`: `open_text` is `device.summary.qa.setup_open`,
  `quick_actions.rs:648-655`). An open row therefore always says that it is open.
- New words: `device.advanced.setup.line` nb "Apper, innstillinger og Play åpnes i 2 timer." / en "Apps, Settings and
  Play open for 2 hours." (D7); `device.advanced.setup.open_now` nb "Åpne nå" / en "Open now" (D8).
  `device.advanced.setup.button` stays: the summary's Oppsett card says it.
- The look (`style.css` "Design 93 §6.6"): `.row-sheet[open] > summary`'s 2 px peach box-shadow goes (D11). The chevron
  turns: `.row-sheet > summary .chev { transition: transform .2s }` and
  `.row-sheet[open] > summary .chev { -webkit-transform: rotate(90deg); transform: rotate(90deg) }`, with no transition
  under reduced motion. `summary.row:active` keeps the tap's grey. `.row-sheet-body`'s 0.5 px border becomes the shared
  hairline (§4).
- No tap ring anywhere on a summary: `summary` joins `.pointer-focus input:focus, …` (`style.css:396`), so Safari before
  15.4 drops it after a tap too. The keyboard keeps the ring (focus-mode.js clears the mark on Tab).
- The flash (`data-flash="setup"`) stays under the row, in the group. With the save in place, "Sendt…" appears there
  without a reload (imported fresh and spoken through `#swap-status`, §2.3). The sheet closes (D4), and the place
  holds through the setup row (§2.4). The 3 s refresh (§2.7, armed by the marker the swap brings in) turns the row into
  "til 17:10" when the phone has answered.
- The open state, the forms, `back=advanced` and `window_relock::queue`'s landing are unchanged.

## 4. The hairlines: one rule for every list

Every row line in the PWA is an absolutely positioned `::before` with `border-top`. One rule lists them all: the
group's five selectors, `.group-music`'s cards, `.wallpaper-item`, `.contact-body`, `.held-body`, `.group-lines` and
`.row-sheet-body`, which gets `position: relative` and a `::before`.

- **The one base rule** (where the group's rule is today, `style.css:943`) holds every base declaration for every
  selector: `content: ""; position: absolute; top: 0; right: 0; pointer-events: none; border-top: 1px solid
  var(--sep-light)`.
- **The per-list rules** (`:2547`, `:2770`, `:2804`, `:3557`, `:3633`, the `.group-icons`/`.group-avatars`/`.row-tile`
  insets) keep only their `left`. No `border-top` shorthand is left in them (103-13).
- **The 2dppx block** `@media (-webkit-min-device-pixel-ratio: 2), (min-resolution: 2dppx)` comes **after all of
  them** in the file: `border-top: 0; height: 1px; background: var(--sep); -webkit-transform: scaleY(0.5); transform:
  scaleY(0.5); -webkit-transform-origin: 0 0; transform-origin: 0 0`. A later shorthand would otherwise give a list
  the border and the scaled line both: a doubled line.
- **The weight**: 1 device px at 2x, as today. At 3x it is 1.5 device px (one full row and one half), against today's
  1 when today's line shows. That is a touch heavier and the same on every position (§1.3).
- **Not row lines, so they stay**: the `td` borders and `.app-display`'s top border (section edges), and `.all-show`
  (a Leaflet popup).
- No row uses `::before` for anything else, and the rounded group corners and dark mode (`--sep` #3A3A50) are
  unaffected.

D12 holds the alternative.

## 5. The field focus: lighter and thinner

New token `--field-focus-line` (the user's D13 (a)):
- **Light #D9784C**: 3.1:1 against the white focused fill and `--surface`, where every field sits in a `.group`, Add
  Phone's included. On `--bg` #F2F2F7 it would be 2.8:1. The focused edge is fainter than the resting grey's 3.6:1
  (#86868B); the white fill and the peach hue say "focused".
- **Dark #C08266**: 6.1:1 on the night fill, a dimmer peach than today's #F0A884. In dark mode `--field-bg` and
  `--field-focus` are both #0C0C14, so a tap changes only the 1 px line's colour, from the resting #7A7A90 to the peach.

The mockup offered for D13 should show both modes if the user wants to see it before step 1 (103-14).

- The text fields' and selects' focus: `border-color: var(--field-focus-line)` and **no** inset shadow, so 1 px. The
  fill stays `--field-focus`.
- `.row-time`/`.row-num`: `inset 0 0 0 1px var(--field-focus-line)`. `.row-input`/`select.pick`'s underline:
  `inset 0 -1px 0 var(--field-focus-line)`.
- The keyboard's 2 px `--tint` ring is unchanged. The ring is the keyboard's sign, and the border is the tap's.
- Everywhere at once: these are the only field focus rules. Add Phone's name field is one of them.

D13 holds the alternative.

## 6. Tests

Lean: the existing files, one new jstest and one real-engine check run by hand.

- **`jstest/swap.test.js`** (new; a small fake DOM in the test, as the other jstests do). It covers the logic:
  - the morph: attributes and text synced, and the keys and the lookahead (103-1):
    - an Attention line inserted before a form keeps that form's node, and the anchor's delta is the line's height;
    - a line removed before a form;
    - an id'd row moved;
    - two id-less forms told apart by `action`;
  - `<noscript>` kept, and emptied in an imported subtree;
  - `[data-flash]` imported fresh, with its text in `#swap-status`;
  - `data-swap-keep` kept;
  - `<details>`: the parent's state vs the button's ancestors closed;
  - busy forms' `aria-busy`/`disabled` untouched;
  - controls: the saved form's from the server, an `again` form's and a dirty one kept, the focused field's two cases,
    the defaults updated;
  - the anchor chain: a form in a closing sheet anchors on the summary or the next id'd ancestor, a form that left
    every ancestor scrolls nothing, and the no-save anchor is below the strip (103-2, 103-15);
  - the swappable checks: lang, lock mark, CSS, scripts, unmarked page script, no `main`, non-200, opaque redirect, 423;
  - the newest GET wins, and a superseded landing's forms are inherited;
  - a GET answered while `inflight > 0` is dropped, and no fallback reload while a POST is out (103-3);
  - `readyState === "loading"` waits for `DOMContentLoaded` (103-6);
  - the address without one-shot words;
  - `handy:swapped` and `handy:saved` with `forms` and `pending`; the form record cleared.
- **`jstest/real/swap-real.js` + `swap-fixture.html`** (new, 103-7): the browser semantics a fake can only assume.
  - `swap-fixture.html` holds fixture pairs (a current `main` and a new page's HTML as `<template>` text) and loads
    `../../static/swap.js`.
  - `swap-real.js` opens it from `file://` in Chromium and WebKit (`is_mobile`), through the local Playwright
    (`~/.local/opt/pw`, the browsers' paths overridable by `HANDY_PW_CHROMIUM`/`HANDY_PW_WEBKIT`), and asserts each pair:
    - the dirty value and checkedness flags after a parent's change;
    - `<option>` selectedness, and a radio group's checked one after a morph;
    - DOMParser's `<noscript>` (scripting off) kept out;
    - `importNode` of a form keeping `form.elements` right;
    - `getClientRects` of a form in a closed `<details>` and the anchor's delta;
    - the hairline rule's lines at 24 fractional offsets (the §1.3 measure, so 103-13's order is checked too).
  - Run by hand: `node server/jstest/real/swap-real.js`, in the build round of step 2 and again by the code QA.
  - **Not in CI**: there it would download two browsers on every run, which isn't cheap. Without Playwright on the
    machine it prints "skipped" and exits 0. Its name (no `.test.js`) keeps it out of `node --test jstest/`.
- **`scroll-restore.test.js`** (its `land` reload cases change):
  - a same-path landing calls `handySwap.refresh` with the saved forms and submitters, and a `false` falls back to
    today's reload;
  - the landed forms stay busy until the morph, and a flip in the gap is re-sent after it;
  - only the landed entries are released;
  - a lost answer refreshes in place and asks for the flash;
  - offline holds busy and hands over to reconnect;
  - the 400 ms bar's counter.
- **`auto-reload.test.js`** (its 3 s reload case changes, 103-4):
  - no marker, no tick;
  - a swap adds the marker and the tick starts; a swap removes it and the tick stops;
  - `handy:saved` with `pending` 0 re-arms after a save, and `pending` 1 doesn't;
  - `pageshow` from the bfcache re-arms;
  - the tick refreshes in place;
  - the strip only with the marker at load.
- **`reconnect.test.js`, `pull-refresh.test.js`** (their permanent-`saving` cases change, 103-5):
  - `saving` cleared on `handy:saved` with `pending` 0;
  - the `"swap"` wait's restarts call the refresh, never `location.reload()`;
  - `handy:swapped` ends the wait and the spinner, and `handy:swapwait` puts the spinner away;
  - `busy(+1/-1)` survives a second save's `end()`.
- **`confirm.test.js`**: the Cancel buttons shown again on `handy:swapped`.
- **`register-sw.test.js`**:
  - the banner is `data-swap-keep`;
  - the badge is re-read and the `endpoint` re-filled on `handy:swapped`, with one submit listener only (103-8).
- Step 3 scripts: each gets its re-sync case (and its hidden inputs re-written).
- **Server** (`src/tests/`). What changes, and every pin that will break (103-17):
  - `shell::every_page_carries_the_connecting_script_and_its_ping`:
    - the head carries `swap.js` (after `scroll-restore.js`) and `auto-reload.js`;
    - `#swap-status` is in `js_words.html`;
    - the `?v=` pins move: `shell.rs:1855` (`pull-refresh.js?v=4`), `:1890`, `:1904` and `:1907`
      (`reconnect.js?v=13`), `:2208` (`confirm.js?v=3`).
  - **The exact auto-reload tag asserts** (`window_relock.rs:1070`, `device_pages.rs:2702`) assert the `data-waits`
    marker.
  - **The negative asserts `!contains("auto-reload.js")`** fail on every page once the script is in the head. They
    flip to `!contains("data-waits")`: `step9.rs:120,262,286`, `calls.rs:452`, `device_pages.rs:1989,2717`,
    `provisioning.rs:993`. The positive ones in `step9`, `calls`, `tracked_apps`, `window_relock`, `device_pages` and
    `provisioning` assert `data-waits` too.
  - A new `shell::every_page_script_is_swap_ready_or_listed`: every `<script src>` in a template outside `head.html`
    carries `data-swap-ready` or is on the map list (D6).
  - `shell::the_accent_rules_hold`:
    - the new token in both themes;
    - the 1 px border with no inset shadow, and the 1 px underline;
    - `summary` in the pointer rule.
  - `shell::the_app_shell_scrolls_main_not_the_document`:
    - `overflow-anchor: none` on `.container`;
    - no `0.5px` row border left;
    - no `border-top` in a per-list hairline rule;
    - the scaled 2dppx block after every hairline rule.
  - `lock_now::the_advanced_page_opens_for_setup_and_lands_back_there`:
    - no `qa-head` and no Lukk in `#setup-sheet`;
    - the line's and the button's words, and none of the old note's;
    - the open-without-an-end case shows its line (103-11).
  - `i18n::every_key_is_used` (`i18n.rs:901`): `device.advanced.setup.note` goes from both catalogs.
    `device.summary.qa.close` stays: the summary uses it (`device_detail.html:252`).
  - The i18n key tests cover `js.save_unknown`, `device.advanced.setup.line` and `.open_now` in en and nb.
- **Browser pass** (Playwright, Chromium and WebKit with `is_mobile`, 390 px, `deviceScaleFactor: 3`, light and dark, on
  a throwaway server; the local Playwright as in earlier passes):
  - Avansert:
    - flip five switches fast and confirm `history.length`, main's scroll and that no `load` event fires;
    - the backup warning appearing above the restrictions form keeps the switch under the finger.
  - Open for setup: open it, press "Åpne nå", see the flash in place and the sheet close with the row where it was, and
    let the 3 s refresh (armed by the swap) turn the row.
  - A refused save, and a 5xx by a stub.
  - Offline: the server stopped, a flip while held, then the server started again.
  - The summary's sheets.
  - The hairlines measured as in §1.3 on the real page.
  - The focus border on Add Phone, light and dark.
  - **Owed on the iPhone**: the switches on Avansert, the hairlines at 3x, and VoiceOver on `#swap-status`.

## 7. Steps

1. **Polish** (small, on its own; style.css and the Avansert template):
   - §3, §4 (the one hairline rule, the 2dppx block last), §5 and the `summary` pointer rule;
   - i18n en + nb (`device.advanced.setup.note` removed), `style.css?v=` bumped.
   - Tests: `the_accent_rules_hold`, the shell's hairline pin and `lock_now`'s Advanced test.
2. **The mechanism**:
   - `swap.js` with the keyed morph, the anchor chain and the landing record;
   - scroll-restore's landing, its busy bookkeeping (§2.2) and the 400 ms bar's counter (`handyReconnect.busy`);
   - reconnect.js's `"swap"` wait;
   - the `handy:swapped`/`handy:saved`/`handy:swapwait` hooks in confirm.js, register-sw.js (with its endpoint
     re-fill), reconnect.js and pull-refresh.js;
   - auto-reload.js to the head with `data-waits` and its arming rules;
   - `overflow-anchor: none` on `.container`;
   - `#swap-status` and `js.save_unknown` in `js_words.html`;
   - `data-swap-ready` on rule-kind, calendar-preview, pull-refresh and auto-reload;
   - every changed script's `?v=` bumped;
   - `jstest/real/swap-real.js` run in both engines before the code QA.

   With this, Avansert and the other pages in §2.9's first list save in place.
3. **The page scripts**: quick-sheets, app-display, contact-form, notifications, passkey, reveal, music-cards,
   install-now (its reload to `handySwap.refresh`) and photo-crop, each made ready and marked, with its jstest case.
4. **D3's other reloads**: pull to refresh and the return after 1-10 minutes in place.
5. The browser pass. `docs/notes/server/pwa.md`: a new "Saves in place (design 103)" section; "Connecting", "Pull down to
   refresh", "Field focus" and design 93's "Quick Actions" note updated.

Steps 1 and 2 can be built in parallel (different files, apart from `head.html`'s `?v=`). Step 3 needs step 2.

## 8. Risks

- **A script state the morph undoes.** A script that changed the page without being marked ready would be reset by a
  swap. The ready marker and its pin are the guard. An unmarked script means a real reload, never a wrong page.
- **The dirty flag.** A control's `checked`/`value` property stops following its attribute once touched. The morph sets
  the properties explicitly (§2.3). The jstest pins it.
- **Two id-less siblings with the same soft key** (two forms with the same `action`, rows with no data key) still
  fall back to order between themselves. A line inserted between them makes the later one morph into the earlier
  one's place. The final page is right, but a parent's unsaved edit could land on a neighbour. Mitigation: the soft
  keys of §2.3 tell every form apart by `action`, and the repeated rows (contacts, held, rules, entries) have ids.
  The real-engine check and the browser pass cover Calls and Mobilnett.
- **The bookkeeping of §2.2** (busy, `again`, inflight, inherited landings) is the new code most likely to be wrong.
  scroll-restore.test.js and swap.test.js pin each case of 103-3.
- **Big pages** (Calls, an app's page): DOMParser and the walk cost a few ms on a phone. The GET was paid by the reload
  already.
- **Two round trips per save** instead of one plus a document load: on a slow link, the 400 ms bar says so.
- **Old Safari**: a button save stays a full POST on iOS 14/15 (§2.8). That was already so.
- **Screen readers**: the flash is spoken through the one persistent live region `#swap-status` (103-12). A reader
  may also announce the inserted `role="status"` line, so it could be said twice. That is a smaller fault than
  silence; VoiceOver is owed on the iPhone.

## 9. Decisions for the user

All accepted as recommended by the user on 2026-11-15 ("Ja til alle"). The notes of revision 2 are added in place.

- **D1. A save that gets no answer while offline.** (a) The page stays. The switch is dimmed and busy (it says neither
  state is saved), "Connecting…" shows after 3 s, and when the server answers, the page shows what is stored with the
  line "Fikk ikke svar fra serveren. Her er det som er lagret nå." (b) As today: the page is reloaded, which shows the
  browser's own offline page in the installed app. *Recommended: (a).* **Accepted (a).** A flip of the held switch
  meanwhile is queued and sent once the server answers (§2.6).
- **D2. A refused save** (rare: a stale form, the app lock). (a) As today: the refused page shows as a full page.
  (b) The refusal written into the page in place. *Recommended: (a)*. The server refused before acting, and (b) needs a
  page shape for every refusal. **Accepted.**
- **D3. Which other reloads become in place.** The 3 s refresh while a page waits for the phone (Avansert after "Åpne
  for oppsett", Calls, Apps, the summary), Install Now's, pull to refresh, and the reload after 1-10 minutes away.
  *Recommended: all four* (the first two in step 2/3, the last two in step 4). A tap on a link stays a page load. **Accepted.**
- **D4. An open row stays open.** After a switch saves inside an open `<details>` (a contact on Calls, an ⓘ elsewhere
  on the page), it stays open. A sheet whose **button** saved ("Lås igjen", a confirm) closes as today.
  *Recommended.* The alternative is everything closing as after a reload today. **Accepted.** "Holding the button"
  means every `<details>` ancestor of the submitter.
- **D5. A slow save shows the thin top bar** after 400 ms, the same bar as a slow page. *Recommended.* The alternative
  is only the busy form (dimmed button) with no bar. **Accepted.**
- **D6. The three map pages** (Locate's Update location and Alle refresh, a zone's Save) **keep a real reload** for now:
  their maps belong to scripts. *Recommended.* Converting them later is possible. **Accepted.**
- **D7. Open for setup's line**: "Apper, innstillinger og Play åpnes i 2 timer." / "Apps, Settings and Play open for
  2 hours." The phone's name and "it locks again by itself" go. *Recommended: as the user wrote it.* **Accepted.**
- **D8. Its button**: "Åpne nå" / "Open now" in the plain peach row button, as the summary's Oppsett card's. *Recommended*
  over keeping the red "Åpne for oppsett i 2 t". The row already says what and the line says how long, and red is for
  removing things. **Accepted.**
- **D9. While a rule is turned on**, the orange line "Skole på til 18:30 står på pause så lenge." stays under the line.
  It shows only then. *Recommended: keep*: it is a consequence the parent can't see elsewhere. **Accepted.**
- **D10. While open**: the row says "til 17:10" and the sheet holds only Lås igjen. "Åpnet for oppsett til 17:10" goes.
  Play's install mode, which has no time, keeps its one line. *Recommended.* **Accepted, with 103-11**: the line shows
  whenever the row has no value. That covers an open with no known end too, so an open row always says that it is
  open.
- **D11. The open row's mark**: the chevron turns down, and the peach outline goes. *Recommended.* **Accepted.**
- **D12. The hairlines**: (a) a 1 px line scaled to half on 2x+ screens: 1 device px at 2x (as now) and 1.5 at 3x (a
  touch heavier than today's 1, when today's showed), never gone, the same on every position. (b) A 1 css px light grey on every screen, as 1x screens have now: never gone, but 3 device px
  at 3x, visibly heavier. *Recommended: (a)*, for every row list at once (§4). **Accepted (a).**
- **D13. The field focus colour**, 1 px: (a) light #D9784C / dark #C08266. That is softer than today's #A64B25 / #F0A884
  at 1.5 px, and the focused field's edge still meets 3:1. (b) Light #E8895E (the buttons' peach) / dark #C08266. That
  is softer still, at 2.6:1. *Recommended: (a).* **Accepted (a).** The numbers are on `--surface`.
  - In light mode the focused peach edge (3.1:1) is fainter than the resting grey (3.6:1). The white fill and the hue
    carry the "focused".
  - In dark mode a tap changes only the line's colour.
  - A mockup of both modes on Add Phone can still come before step 1 if the user wants to see it.
