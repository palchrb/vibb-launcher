# Design 101: the phone's summary page, mockup A, and the app's logo in its band

Status: design, 2026-11-15 (branch `design-101`, from main f775ec108). Server only: no launcher change, no new route
to the phone, no contract file, no migration.

Approved by the user on 2026-11-15 (PLAN, "The phone's summary page, mockup A"): *"Vi går for a!"* on the mockups
(`https://claude.ai/artifact/HhJSZo1KzLwDe6AYCsynaB`, board "A · Samlet", with "Trykk på bildet" for the picture's
sheet), and *"Når jeg klikker inn på en app på App tab, kunne vi lagt inn ikonet helt oppe til høyre, akkurat som
barnets profilbilde"*. The mockup is a picture with made-up content ("Emma"); this design takes its layout, not its
markup or its words where the catalogs already have them.

**The short version.**

1. The phone's picture sits in a night gap under the band, its upper part over the night and the rest on the sheet,
   88 px, with a small pencil badge where the status dot was. A tap opens a sheet: "Bytt bilde" (or "Legg til bilde"),
   "Fjern bildet" (red, only with a picture), "Avbryt". The "Add picture"/"Remove picture" link under the picture goes.
2. One status line under the picture: the status dot, then today's lead (`phone_status`: "På nett", a lock, a low
   battery, "Ingen kontakt …", "Frakoblet …", "Slått av …"), the zone with a pin glyph, the battery. The screen time
   leaves the header (the Skjermtid tile says it) and with it today's bar.
3. The three Quick Actions tiles already have state lines (design 93 §7). Two new ones: Pause regler says "Ingen regel
   nå" when a fresh report says no rule locks, and Slå på regel says "Neste: Leggetid 20:30" (the next scheduled
   start in the next 24 hours, from the policy and the phone's clock) when no rule is turned on now.
4. Activity's grey count of every row in the week (47 on the user's phone) becomes "2 nye": this parent's unread
   events of the phone, orange, shown only when there are some. Opening the Activity page marks the events it
   shows read, as the inbox does. The sub-line is never cut in the middle of its clock.
5. The shared band is not changed. The summary gets its gap with a margin on its own sheet. The picture never covers
   the band's title row.
6. An app's page under the Apps tab (`/apps/tracked/{id}`, the only per-app page there is) shows the app's tile at
   the band's top right: the real logo, else the catalog glyph, else the letter (the list's own order). The launcher
   shows the Vibb mark.

The zone line the user saw ("Skolen since 6 Nov 11:20") is not part of this design. The user says that phone is
their emulator, parked in that zone on purpose.

---

## 1. What exists today (findings)

### 1.1 The header

- `server/templates/device_detail.html:22-44`: `<header class="summary-head" id="header">` holds `.avatar-edit`
  `id="avatar"`: the upload form (`/devices/{id}/avatar#avatar`, multipart) with the hidden, focusable
  `input#avatar-file[data-autosave]` and a `<label class="avatar-pick">` round the 76 px `.avatar-large` with its
  `.avatar-dot`. Under it is either the Remove form (`button.avatar-remove[data-avatar-remove]`) or a second label
  "Add picture" (`.avatar-add`, `aria-hidden`), then `#avatar-error` (`role="alert"`). Next come the status lines,
  `{% for line in status %}<p class="summary-state" … data-line>`, "Connect again ›" when access is removed, and
  `.summary-meter` while a line says the screen time.
- `server/static/style.css:3013-3032`: `.summary-head` is a centred column (`margin: 12px 0 20px`, 4 px as the
  sheet's first box). `.avatar-large` is 76 px. `.avatar-edit` is `position: relative` (the file input is absolute
  in it). `.avatar-add`/`.avatar-remove` are tint text buttons.
- `server/src/phone_status.rs:377-404` (`header`) returns one or two `Line`s. Line 1 is the lead: for `Lead::Plain`,
  the screen time named ("4 min skjermtid i dag") or "Online"/"På nett" (`online_word`, :510). Line 2 is
  `zone_now · battery`, or `zone_now · screen` for a low battery (:385-395). The no-contact case is "Ingen kontakt ·
  sist sett …" and then `rest(…, summary=true)` (:246-262), which also carries the screen time. A phone turned off,
  offline or not reachable has line 1 alone. `with_roaming` (:295) and `with_airplane` (:327) add a part to line 2,
  or replace line 1 for an offline phone.
- `server/src/handlers/devices.rs:2267-2321` builds `header_facts`, `status` and `meter` (`meter_of`, shown only
  while a line says the screen time). The dot (`phone_status::dot`, the `DeviceDetailTemplate.dot` field) is drawn on
  the avatar (`.avatar-dot dot-{{ dot }}`) and also given as the first line's `data-status`.
- `server/templates/partials/status_line.html`: the `parts` macro. It puts " · " between the parts, gives each its
  tone class, `data-<hook>` and `title`, and draws the battery's CSS icon (`Part.icon`). The zone part's hook is
  `zone-now` (`phone_status.rs:492-507`).

### 1.2 The band

- `server/templates/partials/band.html`: five macros (`tab`, `sub`, `phone`, `editor`, `bare`). The summary uses
  `band::sub(device.name, "/devices", …)` (`device_detail.html:13`), so it has no picture on its band. `phone` draws
  `<span class="band-who" aria-hidden="true">` round a 28 px `.avatar` between the `<h1>` and the actions.
- `style.css:475-484`: `.band` has a 44 px row at least, `--band-top` above it and 32 px under it, of which the last
  22 lie under the sheet (`margin-bottom: -22px`). `style.css:654-663`: `.container > .band` is
  `position: relative; z-index: 8; pointer-events: none`. Its 22 px foot is night only outside the sheet's two
  corners (two `radial-gradient`s) and clear in between. `style.css:682-695`: the cap
  (`.container:not(.container-fixed)::before`) is sticky at z-index 7. `style.css:457-461`: main's own ground is
  `linear-gradient(var(--night) 50%, var(--bg) 50%)`. `.sheet` (`style.css:623-636`) is not positioned.
- `server/src/tests/shell.rs:161-205` (`every_shell_page_opens_on_one_band`) counts `{% call band::` once per
  template. It does not limit which macros exist.

### 1.3 Quick Actions

- `device_detail.html:186-206`: `.qa-grid` with three `<a class="qa">`. Each has `.qa-label` and `.qa-sub`, which
  holds the line or `&nbsp;` so an empty line keeps its height. The rule tiles are left out while `qa.rules` is empty.
  Above the grid, `relock.report_age` ("Ifølge telefonens siste rapport, 2 t siden.", `data-report-age`) shows once
  the newest report is 35 minutes old (`handlers/window_relock.rs:47-59`, `REPORT_AGE_NOTE_MS`).
- `server/src/handlers/quick_actions.rs:287-322` writes the lines:
  - `time_line` comes from `input.screen` (today's report only): "{left} igjen i dag", "Brukt opp i dag", or "Ingen
    grense i dag" without a budget.
  - `pause_line` is the latest-ending running pause, else `rule_in_force` ("{rule} gjelder nå"). In
    `devices.rs:2385-2395` that is the lock badge, kept only while it isn't stale (`at_last_report` none) and isn't
    the screen-time lock.
  - `on_line` is only a rule turned on now ("Skole på til 18:30", with "· venter" until a report shows it).
- The badge's staleness test is in `time_state.rs:319-334` (`lock_badge`): the report is from another day, or the
  phone is not online and has been away more than `AWAY_STALE_MINUTES` (15).
- Nothing in the server computes a rule's **next start**. `time_state::covering_ends` (`time_state.rs:406`) answers
  "which windows cover this minute", which is what a next-start search needs to skip a start inside a running window.

### 1.4 Activity's row

- `device_detail.html:455-472`: one `a.row.row-tile` to `/devices/{id}/activity` with the sub-line
  `.row-sub.row-sub-line` (one line, cut with "…", `style.css:991-995`) and a grey `.badge` with `activity.badge`.
- `server/src/handlers/activity.rs:100-139` (`Summary::of`): the badge is `activity.in_window`, every row of the
  week (commands, lifts, turned-on rules, events), "50+" past the cap. `rows` (:170-…) reads the events with
  `notifications::row_from`, bound to `admin_id`, so every event row already carries this parent's `unread`
  (`InboxRow.unread`, `handlers/notifications.rs:67-80`).
- The Activity page (`activity::view`, :300-332) marks nothing read. The inbox reads its page and marks exactly the
  rows it shows read (`notifications::page`, `handlers/notifications.rs:286-306`, `parent_events::mark_read`), per
  parent (`parent_event_reads`, `parent_events::UNREAD`, `parent_events.rs:1442`).

### 1.5 The app pages

- The Apps tab has one per-app page, `/apps/tracked/{id}` (`tracked_app_detail.html:12`,
  `band::sub(app.name, "/apps", …)`). Device › Apps has no per-app page: each app is a `.cell` row on the phone's Apps
  page (`partials/device_app_row.html`), with Name and icon and Permissions inline. Kalender's row links to Device ›
  Calendar, which already shows the phone's picture (`band::phone`).
- The catalog list's tile is `tracked_apps::app_tile` (`handlers/tracked_apps.rs:2778-2799`): `logo` (the launcher
  gets `app_logos::VIBB_MARK`, any other app the hash most phones name, `app_logos::catalog_logos`), `icon` (the
  catalog's glyph and colour), and `letter` + `colour`. `apps_list.html:35-41` draws logo, then icon, then letter.
  `style.css:2121-2154` styles `.app-row-tile` (36 px, round) and `.app-row-logo` (the image over the letter, a
  `--sep` hairline).
- The detail handler already reads `catalog_logos` for the Name and icon preview (`tracked_apps.rs:3596-3601`).

---

## 2. Part 1: the picture over the band and its sheet

### 2.1 Geometry (no change to the shared band)

**Revised 2026-11-16 (the user, option 3a):** the summary's own 38 px gap is gone - the sheet's edge is where every other page has it, so stepping into the phone's own pages moves nothing. The picture lies in a layer over the band (`.summary-sheet .pic-pick { z-index: 9 }`, under the tab bar's 10; centred, it never meets the cap's rounded corners) with its top 13 px above the sheet's edge (`margin-top: -29px`), its ring just under the title's line. The text below is the original design.

The picture must lie half over the night without covering the band's title row (a long name runs to the right edge).
The summary's own sheet gets `class="sheet summary-sheet"` and `.summary-sheet { margin-top: 38px; }`. That opens a
38 px gap between the band's foot and the sheet's rounded edge. Night shows in it, because main's own ground is night
in its top half (`style.css:457-461`, the same ground the bounce shows). The band's foot gradients now lie over that
night and match it. Neither the band's box nor its macro changes (D6).

```
 ┌ band (night) ── ‹  Emma ─────────────────┐  title row, 44 px
 │                                           │  10 px band
 │                 ( picture )               │  38 px gap (main's night), picture top 2 px into it
 ╰──── sheet edge ─(  88 px  )──────────────╯  36 px of the picture above the edge, 52 below
                  ● På nett · ⌖ Hjemme siden 15:40 · 82 %
```

- `.summary-sheet > .summary-head:first-child { margin-top: -36px; }`: the head sits 36 px above the sheet's content
  edge, so the picture's top is 2 px below the band's solid part (title row + 10 px). The 15 px top padding and 1 px
  border of `.sheet` stay as they are; the -36 px is measured against them (the number is pinned in the test, §8).
- The picture stays a plain child of the sheet: no `z-index`. It lies under the band's 22 px foot, which is clear
  between the two corners and takes no taps (`pointer-events: none`), so the picture shows and its top part is
  tappable. It scrolls under the cap like any row. A `z-index` above the band would put it over the cap's curve
  while scrolling.
- `.avatar-large` on the summary goes to 88 px (font 2.5rem) with a 4 px ring in `--bg`
  (`box-shadow: 0 0 0 4px var(--bg)`, the mockup's border). The ring separates the picture from the night in light
  and dark alike.
- From the title row's bottom to the status line: today 10 + 16 + 4 + 76 + 33 (the link) + 3 = 142 px; now
  10 + 38 + 52 + 12 = 112 px. The mockup's own figure is 91 px, because it also lets the picture cover the title
  row (D6).

### 2.2 The pencil badge and the dot

- The `.avatar-dot` leaves the picture. In its corner sits `.pic-badge`: a 28 px circle on `--surface` with a soft
  shadow (`0 1px 4px rgba(0, 0, 0, 0.25)`) and a 16 px pencil (inline SVG, `stroke="currentColor"`, `--tint`),
  `aria-hidden`. The dot moves to the start of the status line (§3, D1).
- The badge is drawn for every phone, with or without a picture: a tap always opens the sheet.

### 2.3 The sheet: a `<details>`, no `<dialog>`

`<dialog>` needs iOS Safari 15.4, and the floor is 14. The sheet is a `<details class="pic-menu">`, which works with
and without JS. `auto-reload.js` already holds while a `<details>` is open, and so does `pull-refresh.js`
(`pull-refresh.js:77-90`).

```html
<div class="avatar-edit" id="avatar">
  <details class="pic-menu">
    <summary class="pic-pick" aria-label="{change_picture_for | add_picture_for}">
      <span class="avatar avatar-large {{ avatar }}" aria-hidden="true">E<img class="avatar-img" …></span>
      <span class="pic-badge" aria-hidden="true"><svg>pencil</svg></span>
    </summary>
    <span class="pic-backdrop" data-confirm-cancel hidden aria-hidden="true"></span>
    <div class="pic-panel" role="group" aria-label="{picture_of: Bildet for Emma}">
      <div class="pic-card">
        <p class="pic-note">{picture_note: Bildet vises bare for foreldrene.}</p>
        <form method="post" action="/devices/{id}/avatar#avatar" enctype="multipart/form-data" class="pic-form">
          <label class="pic-action">
            <input class="pic-file" type="file" id="avatar-file" name="picture" accept="image/*" data-autosave …>
            {change_picture | add_picture}
          </label>
          <noscript><button type="submit" class="pic-action">{save_picture}</button></noscript>
        </form>
        {% if photo %}<form method="post" action="/devices/{id}/avatar/remove#avatar" class="pic-form">
          <button type="submit" class="pic-action pic-danger" data-avatar-remove>{remove_picture}</button>
        </form>{% endif %}
      </div>
      {% include "partials/confirm_cancel.html" %}   {# "Avbryt", hidden without JS #}
    </div>
  </details>
  {% if error %}<p class="field-error avatar-error" id="avatar-error" role="alert">…</p>{% endif %}
</div>
```

- **The file field is the row.** `.pic-file` lies over its row (`position: absolute; inset: 0; opacity: 0`) inside
  the `position: relative` label. A tap on the row is a tap on the input itself, which is the surest way to open the
  picker on iOS. The keyboard reaches it with Tab, and Space or Enter opens the picker. Its focus ring is drawn on
  the row (`.pic-file:focus-visible` + a sibling span, or `:focus-within` on the label, both allowed under the
  floor). No `capture` attribute, as today. A chosen file autosaves through the hooked `form.submit()`, and
  `scroll-restore.js` keeps the place. The page comes back at `#avatar` with the sheet closed.
- **"Fjern bildet"** posts at once, with no second confirm. The mockup has none, and the picture comes back the moment
  it is chosen again. It lands on `#avatar` as today.
- **Avbryt and the backdrop**: `confirm.js` already unhides every `[data-confirm-cancel]` and, on a click, closes the
  `<details>` it sits in and focuses its summary (`preventScroll`). It does not check that the details is a
  `.confirm`. The backdrop is a `[data-confirm-cancel]` span, so with JS a tap beside the sheet closes it too.
  Without JS both stay hidden: there is no dim, and the picture (the summary) still closes the sheet. The class is
  not `confirm` (it is not a confirm), so `shell::every_confirm_has_a_cancel` doesn't apply. The include is there
  anyway.
- **Layout**: `.pic-panel` is `position: absolute; left: 10px; right: 10px; bottom: calc(14px +
  env(safe-area-inset-bottom, 0px)); z-index: 12`, at most 560 px wide, centred (`margin: 0 auto` with left and
  right). It sits where the tab bar floats (design 43), over it. `.pic-backdrop` is `position: absolute; inset: 0`
  (top/right/bottom/left 0, since `inset` needs iOS 14.5), `z-index: 11`, `background: rgba(12, 12, 20, 0.42)`.
  **No ancestor of the two may be positioned**: `.avatar-edit` loses its `position: relative`, which was only there
  for the old input. Their containing block is then the body, which is exactly the screen (design 43). They don't
  scroll with main and aren't clipped by it, and the tab bar (z-index 10) lies under the dim. The card is `--surface`
  with radius 14 px. Its rows are 56 px, separated by a `--sep` hairline, in `--tint`. "Fjern bildet" is
  `--red-text`. Avbryt is a separate 56 px card under it, weight 600 (`.pic-panel > .button-cancel`, restyled).
- **Opened**: the first action takes the focus (`preventScroll`) via a small `toggle` listener in `confirm.js` for
  `details.pic-menu`. Without JS the focus stays on the summary.
- **Spoken**: the summary's `aria-label` is today's `device.summary.change_picture_for` / `add_picture_for` ("Bytt
  bilde for Emma"). Change the former to cover both actions: "Bytt eller fjern bildet for {name}" when there is a
  picture. The panel is labelled "Bildet for {name}".
- **With no picture**: the letter on the phone's colour as today, the same badge, and a sheet with "Legg til bilde"
  and Avbryt only (the user's words). Opening the picker straight from the picture would save a tap. It is offered as
  D7 but not recommended.
- **A refused file** (`?avatar=<code>#avatar`): the error under the picture as today. The input keeps
  `aria-invalid`/`aria-describedby="avatar-error"`.

### 2.4 Words

| Key | en | nb |
|---|---|---|
| `device.summary.change_picture_for` (changed) | Change or remove the picture for {name} | Bytt eller fjern bildet for {name} |
| `device.summary.change_picture` (new) | Change picture | Bytt bilde |
| `device.summary.picture_of` (new) | Picture for {name} | Bildet for {name} |
| `device.summary.picture_note` (new) | Only parents see this picture. | Bildet vises bare for foreldrene. |

The note is true: the picture is never sent to a phone (`pwa.md`, "A phone's own picture") (D8). `add_picture`,
`add_picture_for`, `remove_picture` ("Fjern bildet") and `save_picture` stay. `common.cancel` is the Avbryt.

---

## 3. Part 2: one status line

### 3.1 The rule

`phone_status::header` keeps its leads and their order (`phone_status.rs:1-27`). It drops the screen time from the
header and returns **one** `Line`; the Devices row (`phone_status::row`) is unchanged.

| Lead | The line (after the dot) |
|---|---|
| Not set up / access removed / not seen yet | the word alone (grey dot), as today |
| Turned off | "Slått av 21:14 · batteriet var 3 %", as today |
| Offline | "Frakoblet · sist sett 13:05", as today; airplane mode replaces it as today |
| No contact (away ≥ 10 min) | "Ingen kontakt · sist sett for 20 min siden" · the lock, if any · the battery (no zone, as today) |
| A rule locks | the lock's words (orange, "Leggetid til 07:00 · anrop av") · ⌖ zone · battery |
| Low battery | "Lavt batteri, 9 %" (orange) · ⌖ zone |
| Plain | "På nett" (green, `Tone::Online`) · ⌖ zone · battery |

- The screen time leaves every header case (Plain's line 1, Low's line 2, `rest(…, summary=true)`). With it,
  `meter_of`'s bar leaves the summary: `DeviceDetailTemplate.meter` goes. The Devices list keeps both.
  `rest(…, summary)` keeps its `summary` argument for the row's own call. In the header the screen part is not
  added.
- Roaming and airplane mode keep their places: `with_roaming` and `with_airplane` work on the `Vec<Line>` as today,
  and a last step `phone_status::one_line(lines)` joins the lines' parts in order. They don't need to change, and the
  Devices row's code path is untouched.
- **The zone's pin**: `Part` gets `pin: bool` (the zone part only, set in `zone_now`). `status_line.html` draws a
  12 px inline SVG pin (`aria-hidden`, `currentColor`) before the zone's text. The words are unchanged
  (`devices.status.zone_since`, "Hjemme siden 15:40"), so nothing new is spoken.

### 3.2 Markup and look

```html
<p class="summary-state" data-status="{{ dot }}" data-line><span class="state-dot dot-{{ dot }}" aria-hidden="true"></span>{% call status_line::parts(line) %}{% endcall %}</p>
```

- `.state-dot`: 8 px, round, `display: inline-block`, `margin-right: 6px`, `vertical-align: 0.1em`, coloured by
  today's `.dot-online/.dot-away/.dot-offline` (`style.css:1861-1863`). It is `aria-hidden`: the first part already
  says the state ("På nett", "Ingen kontakt", "Frakoblet").
- `.summary-state` stays centred, 0.9375rem, `--label-2`. `margin-top: 12px` under the picture. Each part is
  `white-space: nowrap` (`.summary-state > span { white-space: nowrap }`), so the line wraps only between parts:
  "Leggetid til 07:00 · anrop av" never breaks inside. At 390 px the plain line ("● På nett · ⌖ Hjemme siden 15:40 ·
  82 %", about 270 px) fits on one line. A lock line wraps to two, centred.
- The `.bat` icon stays before the battery (`Part.icon`).
- "Connect again ›" (`data-connect-again`) stays under the line for a phone whose access was removed.

### 3.3 What the Attention list keeps

Nothing changes in Attention (`attention::items`), the mobile-network lines, the airplane lines, the Oppsett card or
the foreign-app warning. They come after the header as today.

---

## 4. Part 3: the tiles say their state

Design 93 §7 already gives the lines and their priorities. This part adds two lines and changes one (D2). Every line
is the reader's language, in the phone's wall-clock time, with "(telefonens tid)" when the phone's zone isn't Phone
Setup's (`device.summary.qa.phone_time`).

| Tile | Line, first that holds | Source |
|---|---|---|
| Skjermtid | "{left} igjen i dag"; "Brukt opp i dag"; **without a budget: "{used} brukt i dag" (D2)**; nothing without today's report | the newest report of the phone's today (`screen_time`, as now) |
| Pause regler | the latest-ending running pause ("Leggetid på pause til 21:00", `.qa-on`); "{rule} gjelder nå"; **"Ingen regel nå"**; nothing | the server's lifts; the lock badge and a fresh report |
| Slå på regel | the rule turned on now ("Skole på til 18:30 · venter", `.qa-on`); **"Neste: Leggetid 20:30" / "Neste: Skole i morgen 08:00"**; nothing | `time_lock_now`; the policy's rules and the phone's clock |

### 4.1 "Ingen regel nå"

Shown when no pause runs, the lock badge is `None`, and **the report is fresh by the badge's own test**: the
`TimeReport` is today's, and the phone is online, or away no more than `AWAY_STALE_MINUTES`. Factor the first half of
`lock_badge` (`time_state.rs:327-334`) into `time_state::report_stale(report, ctx, zone, now) -> bool`, used by both.
Pass `Inputs.no_rule_now: bool` from `render_device` (true only when a time report was read, it isn't stale, and
`lock` is `None`). A screen-time lock counts as "no rule": the Skjermtid tile says "Brukt opp i dag" beside it. A
stale or missing report leaves the line empty (an `&nbsp;`) rather than guessing. The rule lines need a fresh report;
the report-age note above the grid ("Ifølge telefonens siste rapport, 2 t siden.") stays and says how old the report
is.

### 4.2 "Neste: Leggetid 20:30"

A pure function in `lock_now.rs` (beside `minute_of`/`end_words`; it is about the phone's clock):

```rust
/// The next scheduled start of one of `rules` after `now_local` and within 24 hours, on the phone's wall clock:
/// (the rule, its start). Skips a copy (negative id), a rule whose days don't read, a start inside a window of the
/// same rule already running (`covering_ends` at the minute before), and a start before `paused_until` of a running
/// pause of that rule or of all rules. Ties: school, then bedtime, then the rest (`kind_rank`), then `sort_order`.
pub fn next_start(rules: &[TimeRuleRow], pauses: &[(Option<i64>, NaiveDateTime)], now_local: NaiveDateTime)
    -> Option<(&TimeRuleRow, NaiveDateTime)>
```

- Its inputs are already read by `quick_actions::build`: `input.rules` (the effective rows, `devices.rs` passes
  `time_rules`), the pause rows (`pause_rows`, :258-266, their `expires_at` in the phone's zone) and `now_local`
  (`lock_now::phone_zone(latest)`, :237-238). Without the phone's zone (no report with `time_state.zone`) there is no
  line, as Slå på nå is disabled then.
- Windows from `served_days(parse_days(days_json))`, today's and tomorrow's weekday. A window with `start == end`
  lasts 24 h and starts at `start`.
- The words: `device.summary.qa.next` "Neste: {rule} {at}" / "Next: {rule} {at}", where `at` is "20:30" today or
  `time.tomorrow` ("i morgen 08:00") tomorrow, wrapped in `phone_time` when the zones differ. The rule's name is
  `time_rules::shown_name`.
- Shown only while no rule is turned on now. A rule in force by its schedule doesn't hide it: "Skole gjelder nå" on
  Pause regler and "Neste: Leggetid 20:30" on Slå på regel can stand together.
- This line comes from the policy, not the report, so an old report doesn't change it. The phone's zone comes from the
  newest report that has one.
- Beyond 24 hours (a Friday evening with nothing until Monday): no line (D9).

### 4.3 Without a budget (D2)

The user's brief says "or nothing without a budget". Part 2 moves the header's "4 min skjermtid i dag" out, so with
nothing on the tile the summary would no longer say the time used anywhere. **Recommended**: "{used} brukt i dag"
(`device.summary.qa.used`, "{used} used today"), grey. The alternative is nothing, as the brief says, and "Ingen
grense i dag" goes either way. With a budget, nothing changes ("1 t 20 min igjen i dag", 2 lines in a 112 px tile at
390 px, as now).

### 4.4 Words

| Key | en | nb |
|---|---|---|
| `device.summary.qa.no_rule_now` | No rule now | Ingen regel nå |
| `device.summary.qa.next` | Next: {rule} {at} | Neste: {rule} {at} |
| `device.summary.qa.used` (D2) | {used} used today | {used} brukt i dag |

`device.summary.qa.no_limit` goes (it is used nowhere else; the i18n test of unused keys will say so).

---

## 5. Part 4: Activity's count and its sub-line

### 5.1 The count (D4)

- `Summary` gets `new: usize`: the `ActivityRow::Event` rows in `activity.rows` with `unread`, which are exactly the
  events the Activity page lists (the same read and cap). Commands, lifts and turned-on rules never count: they are a
  parent's own acts and have no read state.
- The badge shows only when `new > 0`: `<span class="badge badge-warn" data-activity-new="2">`, "2 nye" / "2 new"
  (`device.activity.new`, a count key: one/other). Spoken in full: "2 nye hendelser" / "2 new events"
  (`device.activity.new_spoken`). `badge-warn` is the mockup's orange (`--orange-bg`/`--orange-text`, light and dark
  tokens exist).
- **The Activity page marks what it shows read**: `activity::view` collects the event ids among the rows it renders
  and calls `parent_events::mark_read(db, admin.id, ids)` after the read, as `notifications::page` does. The page's
  rows keep the state they had (no visual change on the page). The bell on the tab pages and the app badge count the
  same table, so they drop too, which is what reading them means. Another parent's state is untouched.
- `in_window`, `badge`, `spoken` and `count_more` leave the summary's row. The page keeps its footer and its 50 + "50+"
  rules. `Summary.count` still decides whether the row shows (a week with a row).

### 5.2 The sub-line (D5)

The row's sub is today's `n.what · n.when`, cut with "…" (`.row-sub-line`). It becomes two lines at most and never
cuts the clock:

```html
<span class="row-sub row-sub-wrap" data-newest title="…">{{ n.what }}{% if !n.when.is_empty() %} · <span class="nowrap">{{ n.when }}</span>{% endif %}</span>
```

`.row-sub-wrap { display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2; overflow: hidden; }`
(Chrome 80 and iOS 14 both have the prefixed clamp; no `:has()`). `.nowrap { white-space: nowrap }`. `.row-sub-line`
stays in style.css only if another page uses it (grep at build; today only this row).

---

## 6. Part 5: the band's title row

The band's row is 44 px because that is the back chevron's tap target, and 10 px of night follow it. Shrinking either
changes 49 templates and the editors' sticky flash numbers (`shell::the_app_shell_scrolls_main_not_the_document` pins
them). The user agreed the gain is small. **Recommended: no change to the shared band** (D6). The summary's gain
comes from part 1: the link under the picture goes, and the picture lies in the night, about 30 px in all.

---

## 7. Part 6: the app's tile on its page's band

- **Which page**: `/apps/tracked/{id}` (`tracked_app_detail.html`), the only per-app page. Device › Apps has rows,
  not pages (§1.5). `tracked_app_add.html` has no app yet.
- **The macro**: `band::app(title, href, parent, tile)`, a copy of `phone` whose `.band-who` holds the app's tile at
  28 px:

  ```html
  <span class="band-who" aria-hidden="true">
    {% if let Some(src) = tile.logo %}<span class="app-row-tile app-row-logo {{ tile.colour }}">{{ tile.letter }}<img src="{{ src }}" alt="" decoding="async"></span>
    {% else if let Some((icon, colour)) = tile.icon %}<span class="app-row-tile" style="background: {{ colour }}"><img src="/static/app-icons/{{ icon }}.svg" alt=""></span>
    {% else %}<span class="app-row-tile {{ tile.colour }}">{{ tile.letter }}</span>{% endif %}
  </span>
  ```

  `.band-who > .app-row-tile { width: 28px; height: 28px; font-size: 0.8125rem; }`,
  `.band-who > .app-row-tile img:not(:only-child)` keeps the logo's fill, and the glyph is 17 px
  (`.band-who > .app-row-tile > img:only-child`). On the night band the logo's hairline is `--band-edge`'s twin:
  `.band .app-row-logo { box-shadow: 0 0 0 1px rgba(255, 255, 255, 0.14); }`, so a dark logo doesn't vanish into
  the night.
- **The tile's data**: the handler (`render_detail_full`, `tracked_apps.rs:3525`, its template at :3693) builds `AppTile` with the existing
  `app_tile(app, &label, &logos)`. `label` is the display label or the name (as `catalog_row`, :2814-2818). `logos`
  is the `catalog_logos` read the handler already makes for the preview; read it once and use it for both. A failed
  read is logged, and the tile falls back to the glyph or letter, as on the list. `AppTile` becomes `pub(crate)` with
  its fields, so the template can read it.
- **Order**: the logo wins, then the catalog glyph, then the letter on the app's colour. This is exactly the list's
  tile (design 87 §8.1 revision 3), so an app looks the same on the list and on its page. The launcher shows the Vibb
  mark (`VIBB_MARK`).
- Not spoken, not a link (the title says the name), as the phone's picture.

---

## 8. Tests (lean, the existing files)

- `src/tests/summary.rs`
  - `the_header_says_whether_the_phone_is_connected`, `the_header_shows_the_lock_and_todays_screen_time`: one
    `p.summary-state[data-line]`, its `.state-dot dot-…`, the lock in the line, no screen time and no
    `.summary-meter` in the header, and the zone's pin before `data-zone-now`. Rename the second test to
    `the_header_is_one_line_with_the_lock_zone_and_battery`.
  - New `the_picture_opens_its_sheet`: without a picture, `details.pic-menu` with the badge, "Legg til bilde" as the
    file row and no remove form. With one: "Bytt bilde", the remove form `/avatar/remove#avatar` with
    `data-avatar-remove`, and the Cancel include. The upload form is still the summary's first form and its one
    `data-autosave`. No `.avatar-dot` in the header. `every_form_on_the_summary_returns_to_its_group` keeps passing
    over the moved forms.
  - `the_quick_actions_follow_the_phone`: add "Ingen regel nå" with a fresh report and no lock, no line with a report
    from yesterday or an offline phone, "Neste: Leggetid 20:30" from a rule and the phone's zone, "i morgen 08:00",
    the turned-on rule winning, and D2's "brukt i dag".
  - `the_summary_row_skips_what_waits_and_counts_the_week` becomes `the_summary_row_counts_what_is_new_to_this_parent`:
    two unread events and a command give "2 nye". After `GET /devices/{id}/activity` the summary has no badge, the
    other parent still sees "2 nye", and with nothing unread there is no badge. Check the sub-line's clock in its
    `nowrap` span.
- `lock_now::tests::the_next_start` (pure, vectors): later today, tomorrow, beyond 24 h none, a window over midnight,
  a running window's own start skipped, a 24 h window, two rules at one minute (school first), a pause of that rule
  and of all rules over the start, a copy skipped, bad days skipped.
- `phone_status::tests`: the header's one line per lead (the table in §3.1), with roaming and airplane parts in
  place.
- `src/tests/avatars.rs`: the selectors that read `.avatar-remove`/`.avatar-add` read the sheet's rows.
- `src/tests/app_logos.rs::the_pages_show_the_logos`: the app's page band holds the logo `<img>` in `.band-who`; a
  glyph app shows its glyph; an app with neither shows its letter; the launcher shows the Vibb mark.
- `src/tests/shell.rs::the_app_shell_scrolls_main_not_the_document`: pins `.summary-sheet`'s 38 px,
  `.summary-head`'s -36 px, and that no rule gives `.avatar-edit`, `.sheet` or `.summary-head` a `position`.
- `jstest/confirm.test.js`: a non-button `[data-confirm-cancel]` (the backdrop) closes its details; opening
  `details.pic-menu` focuses its first action.
- Cache: `style.css?v=114`, `confirm.js?v=2`.
- Measured before the merge, in Chromium at 320 and 390 px, light and dark, scripts on and off: the gap and the
  picture under a 40-letter name, the sheet over the tab bar, Avbryt and the backdrop, an autosave keeping the place,
  pull-to-refresh refused while the sheet is open, and the app band with a logo, a glyph and a letter. The iPhone
  walk: the file row opens the photo picker in the installed app, and the picture scrolls under the cap with no seam.

## 9. Steps

One server branch, size M, three commits (one build agent, then code QA):

1. **The header**: §2 and §3 (the template, `phone_status::header` + `one_line`, `Part.pin`, the CSS, `confirm.js`'s
   two additions, the words, the tests).
2. **Tiles and Activity**: §4 and §5 (`report_stale`, `lock_now::next_start`, `quick_actions` lines, `Summary.new`,
   `activity::view` marking read, the sub-line, the words, the tests).
3. **The app's band**: §7 (`band::app`, `AppTile` reuse, the CSS, the test).

Docs: `docs/notes/server/pwa.md` gets "The summary as mockup A (design 101)" with pointers from "A phone's own
picture", "Tidier status", "Quick Actions as three tiles" and the Activity bullet; "The night band" gets the sixth
macro.

## 10. Risks

- **The sheet's containing block.** The panel is placed against the body only while no ancestor is positioned (or
  transformed). A later rule that gives `.sheet`, `.summary-head` or `.avatar-edit` a `position` would pin the panel
  to the header. The shell test pins it.
- **The label over the file input in an absolute panel on iOS 14**: the input is a real tap target (not
  `display: none`), the pattern most robust on old WebKit. To verify on the iPhone walk.
- **Marking read on the Activity page** changes what the bell counts. That is the point, but a parent who used the
  bell as a to-do list will see it drop after opening a phone's Activity. The inbox already behaves this way.
- **"Neste" and DST**: the start is the phone's wall clock as written in the rule. On the night the clocks change, a
  02:30 start may not exist. The line says the rule's own time, as the Time page does.
- **"Ingen regel nå" while the phone sleeps**: away within 15 minutes counts as fresh, which is the same trust the
  badge has today.

## 11. Decisions for the user

- **D1. The status dot moves from the picture into the line**, and the pencil badge takes the picture's corner (the
  mockup). *Recommended: yes.* The alternative is to keep the dot on the picture and put the pencil elsewhere, which
  crowds the picture.
- **D2. Skjermtid without a daily limit**: "25 min brukt i dag" (*recommended*: the header no longer says the time
  used, so without it the summary says nothing of screen time) or nothing (the brief's words).
- **D3. A rule that locks stays first in the status line** ("Leggetid til 07:00 · anrop av · ⌖ Hjemme siden 15:40 ·
  82 %"), although Pause regler also says "Leggetid gjelder nå". *Recommended: keep it* (the lead states stay, the line
  gives the end and "anrop av"). The alternative is "På nett" in the line and the rule only on the tile.
- **D4. Activity's count**: "2 nye" for this parent's unread events of the phone, shown only when there are some, with
  the Activity page marking what it shows read (as the inbox; the bell drops with it). *Recommended.* The alternative
  is no count at all.
- **D5. Activity's sub-line**: up to two lines, the clock never split. *Recommended.* The alternative is one line with
  the words cut and the clock kept whole at the end.
- **D6. The band stays as it is.** The picture lies in a 38 px night gap under the title row, never over the name.
  *Recommended.* The mockup's exact overlap (the picture over the title row's lower part) saves another ~20 px but
  needs a rule that cuts a long name before the picture, which shortens names at 320 px to about 8 letters.
- **D7. Without a picture, a tap opens the sheet with "Legg til bilde"** (the user's words). *Recommended: as asked.*
  The alternative is opening the photo picker at once, one tap fewer, but then the picture behaves differently with
  and without a photo.
- **D8. The sheet's note "Bildet vises bare for foreldrene."** (in the mockup). *Recommended: keep it*; it is true and
  answers the obvious question. The alternative is no note.
- **D9. "Neste" looks 24 hours ahead** ("Neste: Skole i morgen 08:00" on a Sunday evening, nothing on a Friday
  evening). *Recommended.* The alternative is the next start within a week, with the weekday ("Neste: Skole man.
  08:00").
