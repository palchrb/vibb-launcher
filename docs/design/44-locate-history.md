# 44 - Locate: the history behind a row, a span pick, its own Recent (proposal)

Asked 2026-10-20. Server only (`handlers::locate`, `device_locate.html`, a new page, the Lock page's template); no API
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
2. **The override commands get a Recent of their own on the Lock page** (`device_lock.html`, a new group `#recent`
   under the page's last group): `end_windows` ("Lock again"), `start_pause` ("Unlock for 2 h"), `allow_blocked`
   ("Allow these apps"). The same partial, the same rows, the same Cancel. `install_update` ("Install Now") needs no
   new list: design 37's Install Now lives on the launcher's app page, whose phone rows show the install's state; the
   implementer confirms that row names the last result and time ("Installed 0.30.0 · yesterday 18:42") - if it
   doesn't, that page gets a Recent of `install_update` alone, the same partial.
3. **One partial, one function.** `templates/partials/recent.html` renders a `Vec<CommandRow>` with a cancel form and
   the flash; `locate::recent(state, device, kinds, zone, now) -> Vec<CommandRow>`; the cancel route stays
   `POST /devices/{id}/commands/{command_id}/cancel` and takes `back=locate|lock` (default `locate`) to redirect to
   the right page's `#recent` with the notice - never the top (`scroll-restore.js` keeps the place).
4. **The history list is a page of its own**, `GET /devices/{id}/locations` (`device_locations.html`, tab Locate,
   back link to `/devices/locate?device={id}`), reached from one row on the Locate page under the map, "Locations",
   whose value is the count in the chosen span ("14 in the last 12 hours ›", "3 in the last 7 days", "None yet"). The
   page: the same map at the top with the trail and the newest marker, the span pick (§1.5), then every stored fix
   as a row: the time in the page's zone words (`display_time`, the exact time in `title`), the accuracy as the
   sub-line ("± 25 m"; "accuracy unknown" when null), newest first, 200 per page with an "Older" row
   (`?before=<id>`), the same shape as the inbox's paging. A tap on a row pans that page's map to the fix and opens
   its popup (JS; without JS the row is plain text). Nothing posts on this page.
5. **The span pick**, on both pages above the map: "Show: 12 hours · 24 hours · 7 days · All kept" as a segmented
   row of four buttons (`role="radiogroup"`, the chosen one `aria-checked`), default 12 hours. A view preference:
   remembered per browser in `localStorage` (`handy-locate-span`, one of `12`, `24`, `168`, `0`), no POST, nothing
   to save, nothing that can jump. The pick re-fetches `locations.json?since=<hours>` (`0` = everything kept) and
   redraws the trail, the dots, the marker, the "Locations" row's count and the list page's rows (the list page's
   first 200 of the span; "Older" keeps the span in its query). Without JS the pages show the server-rendered 12-hour
   view and the pick does nothing (its buttons are links with `?span=` as a progressive fallback, the server honours
   `span` for the rendered count and list).
6. **The newest fix is always shown**, whatever the span - the marker never disappears, the row's count may say "0
   in the last 12 hours · newest 3 days ago".
7. **The trail gets a small dot per point** (`L.circleMarker`, 3 px, the trail's colour) so a short history with
   points close together shows at all; the newest keeps the marker.
8. **`locations.json`** takes `since=<hours>` (positive integer, else everything) and answers the points of the span
   oldest first plus the newest point when it is older than the span (flagged `"newest": true` on that last point so
   the trail doesn't join it to the span). The route stays `no-store` (admin_auth's list of pages never stored).
9. The retention setting ("Keep history for") stays where it is; the "Locations" row's help line says what the span
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

Server (`src/tests/locate.rs` or the existing Locate tests): the Locate page's Recent holds a `locate` row and not an
`end_windows` one, the Lock page's the reverse; Cancel from the Lock page comes back to `/devices/{id}/lock#recent`;
`locations.json?since=12` leaves a 2-day-old point out and keeps the newest when it is the only one (`newest: true`);
the list page pages at 200 with `before`; the "Locations" row's count matches the span; `?span=168` renders the week's
count. JS (`jstest/locate.test.js`, new): the pick stores and reads the span, fetches with `since`, redraws (a fake
Leaflet with recorded calls), the list page's rows pan the map. `admin_auth::never_stored` keeps the JSON route.

## 5. Device checks

The emulator with two `geo fix` positions ten minutes apart (docs/testing/emulator.md §5): the trail with its dots,
the row's count, the list page with both rows, a tap pans; the span flipped to 24 hours keeps both, "All kept" too;
the iPhone: the pick remembered across a relaunch, no jump on any tap.
