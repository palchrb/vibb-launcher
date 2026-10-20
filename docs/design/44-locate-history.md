# 44 - Locate: the history behind a row, a span pick, its own Recent (proposal)

Asked 2026-10-20. Server only (`handlers::locate`, `device_locate.html`, a new page, `static/locate.js`, the phone's summary page `device_detail.html`); no API
or launcher change. Builds on design 31's Locate tab (`docs/notes/server/pwa.md`, "The Locate tab") and the Find My
Device commands (`docs/notes/server/architecture.md`).

## 0. Goal

The user, 2026-10-20: "hvor kan jeg vise location history ... Kan velge å lagre history i x days, men ser jo ikke den
på noe vis i kartet"; Recent "viser egentlig feil ting" (Install Now and Unlock rows on the Locate page); "1 og 2 er
bra" plus a span for the trail; "mulig det å se full posisjon liste bør være en detaljer man må trykke seg inn på ...
så de ikke blir masse støy på locate siden".

Today: the history is the blue trail alone (every stored point, `locations.json`, invisible when the points
coincide); Recent lists the phone's last 10 commands of every kind (`RECENT_COMMANDS`), because all commands share
`device_commands` and Locate is the only page with such a list (the Time page has its own for its kinds).

## 1. Decisions

1. **Recent on Locate shows only the page's own actions**: `locate`, `ring`, `stop_ring`, `lock`, `wipe`. The query
   takes a kind list; the limit stays 10.
2. **The override commands get a Recent of their own on the phone's summary page** (`device_detail.html`, a
   `#recent` group under `#quick`, where their buttons are): `end_windows` ("Lock again"), `start_pause` ("Unlock
   for 2 h"), `allow_blocked` ("Allow these apps"). The same partial, the same rows, the same Cancel. The Lock page
   gets nothing. `install_update` ("Install Now") needs nothing: `install_now::install_row` already shows the
   command's row on the launcher's app page.
3. **One partial, one function.** `templates/partials/recent.html` renders a `Vec<CommandRow>` with a cancel form and
   the flash; `locate::recent(state, device, kinds, zone, now) -> Vec<CommandRow>`; the cancel route stays
   `POST /devices/{id}/commands/{command_id}/cancel` and takes a `Form<HashMap<String, String>>` with a hidden `back`
   input in the shared partial, whitelisted to `locate` (default; `/devices/locate?device={id}&cancel=…#recent`) and
   `summary` (`/devices/{id}#recent`), and redirects there with the notice - never the top (`scroll-restore.js` keeps
   the place).
4. **The history list is a page of its own**, `GET /devices/{id}/locations` (`device_locations.html`, tab Locate,
   back link to `/devices/locate?device={id}`), reached from one row on the Locate page under the map, "Locations",
   whose value is the count in the chosen span ("14 in the last 12 hours ›", "3 in the last 7 days", "None yet"). The
   page: the same map at the top with the trail and the newest marker, the span pick (§1.5), then every stored fix
   as a row: the time in the page's zone words (`display_time`, the exact time in `title`), the accuracy as the
   sub-line ("± 25 m"; "accuracy unknown" when null), newest first, 200 per page with an "Older" row
   (`?before=<captured_at>,<id>`: rows sort by `captured_at DESC, id DESC` and fixes can arrive out of order, so the
   page keys on both), the same shape as the inbox's paging. A tap on a row scrolls the map box into view inside the
   page scroller (`scrollIntoView({block: "start"})`, no sticky map), then pans to the fix and opens its popup (JS;
   without JS the row is plain text). The page is `no_store_path` (`["devices", _, "locations"]`): it holds every kept
   fix. Nothing posts on this page.
5. **The span pick**, on both pages above the map: "Show: 12 hours · 24 hours · 7 days · All kept" as a segmented
   row of four links (`role="radiogroup"` around `<a role="radio" aria-checked>`, JS calls `preventDefault`), default 12 hours. A view preference:
   remembered per browser in `localStorage` (`handy-locate-span`, one of `12`, `24`, `168`, `0`), no POST, nothing
   to save, nothing that can jump. The pick re-fetches `locations.json?since=<hours>` (`0` = everything kept) and
   redraws the trail, the dots, the marker, the "Locations" row's count and the list page's rows (the list page's
   first 200 of the span; "Older" keeps the span in its query). Without JS the pages show the server-rendered 12-hour
   view and the pick does nothing (the links carry `?span=` as the progressive fallback, the server honours
   `span` for the rendered count and list). The `newest: true` point is never counted in the row's span count.
6. **The newest fix is always shown**, whatever the span - the marker never disappears, the row's count may say "0
   in the last 12 hours · newest 3 days ago".
7. **The trail gets a small dot per point** (`L.circleMarker`, 3 px, the trail's colour; the map is
   `L.map(..., { preferCanvas: true })`, as "All kept" can be about 4,300 dots) so a short history with
   points close together shows at all; the newest keeps the marker.
8. **`locations.json`** takes `since=<hours>` (positive integer, else everything) and answers the points of the span
   oldest first plus the newest point when it is older than the span (flagged `"newest": true` on that last point so
   the trail doesn't join it to the span). The route stays `no-store` (`admin_auth::no_store_path`).
9. **The map script is a file**: `static/locate.js` (ES5, `?v=`), the device id, `shown` and the popup text in
   `data-` attributes (`textContent` rules unchanged; `js_string` goes). Both pages load it; the waiting poll moves
   with it.
10. The retention setting ("Keep history for") stays where it is; the "Locations" row's help line says what the span
   shows and what retention keeps.

## 2. Data

No migration. `device_locations` has the index `(device_id, captured_at)`; the span filter is `captured_at >= ?`
with the span's start computed on the server from `now`. The count for the row is one `COUNT(*)` with the same
filter.

## 3. Security

Nothing new leaves the server: the pages are admin pages behind the session, `locations.json` stays
`no-store`, every value the phone sent reaches the page through `textContent` or Askama's escaping (security review
H2) - the list's time and accuracy are server-formatted strings, the popup is built as today. `span`/`since` parse
to an integer or are ignored.

## 4. Tests (required)

Server (the existing Locate test file in `src/tests/`): the Locate page's Recent holds a `locate` row and not an
`end_windows` one, the summary's `#recent` the reverse; Cancel with `back=summary` comes back to `/devices/{id}#recent`,
an unknown `back` falls to Locate, and Cancel from Locate still lands on `/devices/locate?device={id}&cancel=…#recent`;
the list page is no-store (`admin_auth::tests::no_store_paths`);
`locations.json?since=12` leaves a 2-day-old point out and keeps the newest when it is the only one (`newest: true`);
the list page pages at 200 with `before=<captured_at>,<id>` without skipping or repeating out-of-order rows; the "Locations" row's count matches the span; `?span=168` renders the week's
count. JS (`jstest/locate.test.js`, new): the pick stores and reads the span, fetches with `since`, redraws (a fake
Leaflet with recorded calls), the span links are `role=radio` with `aria-checked`, the list page's rows scroll the map
into view and pan it. `no_store_paths` keeps the JSON route.

## 5. Device checks

The emulator with two `geo fix` positions ten minutes apart (docs/testing/emulator.md §5): the trail with its dots,
the row's count, the list page with both rows, a tap pans; the span flipped to 24 hours keeps both, "All kept" too;
the iPhone: the pick remembered across a relaunch, no jump on any tap.

## Revision (2026-10-20, after qa-r5-design.md)

- 44-1: the list page added to `no_store_path` (`["devices", _, "locations"]`) and tested.
- 44-2: the override commands' Recent goes on the summary page (`#recent` under `#quick`), `back=summary`; the Lock page gets nothing; the Install Now fallback sentence dropped.
- 44-3: `static/locate.js` with data- attributes, both pages load it; `js_string` goes (§1.9).
- 44-4: a row tap scrolls the map box into view in the page scroller, then pans; no sticky map.
- 44-5: the cancel takes a `Form` with a whitelisted `back` (`locate`, `summary`).
- 44-6: paging on `(captured_at, id)`.
- 44-7: `preferCanvas: true`.
- 44-8: `<a role="radio" aria-checked>` with `preventDefault`; the `newest: true` point is never counted.
- 44-9: test names corrected (`no_store_paths`, `src/tests/` Locate file); list-page-no-store and Cancel-from-Locate tests added.

## Implementation status (2026-10-20, branch `r5-a`)

Built as decided: Locate's Recent filtered to its kinds, the summary's `#recent` under `#quick` for the override kinds,
one macro (`partials/recent.html`) and `locate::recent`, Cancel's whitelisted `back`; the Locations row with the span's
count; `/devices/{id}/locations` (no-store, 200 a page on `(captured_at, id)`, a row's tap scrolls the map into view
and pans); the span pick as `role=radio` links remembered in `localStorage`; `locations.json?since=` with the flagged
newest; `static/locate.js` with `data-` attributes and `preferCanvas` dots (`js_string` gone). Notes:
`docs/notes/server/pwa.md`, "Locate: history, span, each page its Recent". Checked with the real Leaflet in headless
Chromium against a fake `locations.json`: the trail, dots and marker with its popup, the pick redrawing and
remembered across a reload, a list row bringing the map into view.

Deviations:

- **The Locations page's pick opens the page with the span** (its link, not `preventDefault`), and a remembered span
  other than the rendered one reopens it once: the rows are worded in the server's zone (`Zone::at`) and paged by the
  server, which the script can't redo from `locations.json`. Locate's pick redraws in place as designed.
- **The summary's Recent shows once it has a row (or a Cancel line)**: "Nothing asked yet." on every phone's
  summary would be noise. Today keeps today's answers as before, and Recent leaves out the row Today's answer
  already shows (that command's newest) unless it can still be cancelled - only Recent has Cancel (code QA N2;
  filtering Recent was less code than reworking Today's answer card and its tests).
- **The Locations page's pick replaces the history entry** (`location.replace`, code QA N1): Back goes to Locate,
  not through the spans.
- **A Locations row's tap scrolls `main` by an offset** that puts the map's top at `main`'s top padding (under the
  status bar); `scroll-margin-top` needs Safari 14.1 and the floor is 14.0 (code QA N5). A page whose window scrolls
  uses `scrollIntoView`.
- **Small additions**: "Newest" on an older Locations page (the inbox's shape); All kept reads "3 kept"; Locate's
  no-JS span links carry `#location` (they land at the map, not the top); the Update Location wait polls
  `locations.json?since=1` (the newest is last either way) instead of every kept fix.

Device checks owed (§5): the emulator with two `geo fix` positions ten minutes apart - the trail with its dots, the
row's count, the Locations page with both rows, a tap pans; 24 hours and All kept keep both; on the iPhone the pick
remembered across a relaunch, no jump on any tap, and a Locations row's tap leaves the map under the status bar, not
behind it.
