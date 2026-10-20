# 45 - Notifications: the inbox first, the settings behind a gear, read on open (proposal)

Asked 2026-10-20. Server only (`handlers::notifications`, `notifications.html` split in two, `static/notifications.js`,
the links to it); no API or launcher change. Builds on design 35 §5.1 (the bell and the inbox), §5.2 (This Device),
§6 (Getting Notifications) and the step 1-3 implementation status in `docs/design/35-notifications-to-the-parent.md`.

## 0. Goal

The user, 2026-10-20: "nå kommer man inn på notification settings, som også viser en notification inbox ... burde man
ikke heller bare komme til en slags notification inbox? Og så kan man der trykke på en settings-knapp eller tannhjul
... notification counter bør nullstilles når man har åpnet den innboxen? nå må man trykke på hvert varsel (som tar deg
til den telefonens skjermkort) for å nulle ut varselene, det er tungvint og unødvendig."

Today `/notifications` is one page: This Device, Inbox, Getting Notifications, Notify Me About. The bell leads
there; an event is read only when its row is tapped (`POST /notifications/{id}/open`, then the phone's page) or by
Mark All as Read.

## 1. Decisions

1. **`GET /notifications` is the inbox alone**: the back link to the tab it came from (`?from=`, as today), the title
   "Notifications", a **gear** top right in the bell's place (`title-action`, the same 44 px hit area, `aria-label`
   "Notification settings") to `/notifications/settings?from=<tab>`, then the phone filter, the rows, Newer/Older,
   Mark All as Read - the inbox group as it is, without its `group-header` (the page is the group).
2. **`GET /notifications/settings` is the settings page** (`notifications_settings.html`): back link to the inbox
   (`/notifications?from=<tab>`), title "Notification Settings", then This Device, Getting Notifications, Notify Me
   About exactly as they are, with their `#this-device`, `#devices`, `#events` fragments. One route:
   `.route("/notifications/settings", get(settings_page).post(save_settings))`. The page includes the tab bar like the
   inbox (`tab = ""`). The `device` hidden inputs (the inbox filter) stay for the query round-trip. Every POST under `/notifications/...` that comes
   back to one of those three groups redirects to `/notifications/settings{query}#group` (the no-jump rule:
   `scroll-restore.js` keeps the place; `back_to`, `back_query` get the page). `open` and `read-all` come back to the
   inbox.
3. **Opening the inbox reads it.** The `page` handler loads the page's rows, then marks exactly those rows read
   (`UPDATE parent_events SET read_at = datetime('now') WHERE id IN (…) AND read_at IS NULL`), and renders them with
   the unread state they had *before* the marking: the existing `unread-dot` and "Unread:" text (no new `row-new`
   class) on the rows that were unread. The order is: read the page, mark the shown ids, then count `unread_after`
   (Mark All as Read shows on `unread_after > 0`). The bell and the app badge drop to what is not shown yet (older
   pages, another phone under a filter); the inbox renders `data-unread="{{ unread_after }}"` on the gear link.
   An older page (`?before=`) reads its rows the same way. Events are shared by all parents (`parent_events` has no
   `admin_id`), so the read state is shared too, as Mark All as Read is today. Back to the inbox is a reload (no-store)
   and shows no dots: the rows are read now - by design. Mark All as Read stays for the rest and says what it
   does ("Mark all N as read" when N > 0 remain, else hidden). A tap on a row still goes through `open` (it is what
   sets `clicked` on the delivery and lands on the phone's page).
4. **The app badge** follows `data-unread`: `register-sw.js` sets it from the element carrying it (the bell on the tab
   pages, the gear link on the inbox), so opening the inbox clears it on the device that opened it; the other devices' badges follow their next push
   (`app_badge` in the payload) or their next page open. The worker clears nothing by itself.
5. **The push's `navigate`** keeps pointing at the phone's page (the user: a tap on a notification should land on the
   phone), with design r5's `?push=<delivery>` marker (`docs/design/r5-server-small-items.md` §1), which marks the
   delivery opened and the event read.
6. **The links**: the Devices list's warning row (a template href, `devices_list.html:36`) and the inbox's "may not be
   showing"/"not reaching" attention rows (today `#devices` on the same page, `notifications.html:23`) go to
   `/notifications/settings{query}#devices`; the clock warning
   shows on the settings page (it is about delivery), the inbox keeps a one-line version linking there when it
   applies. The Settings hub's "Notifications" row (`settings_hub.html:16`) goes to the settings page; the bell to the inbox.
7. **`static/notifications.js`** (This Device's state and the subscribe tap) loads on the settings page only
   (`?v=` bumped); the inbox page loads nothing but the shared scripts. The Send-a-Test poll (r5 §5) lives in the
   same file.
8. **Words**: the inbox's empty state "Nothing yet - what the phones report shows up here"; the settings page's
   groups keep their words.

## 2. Security

Unchanged: both pages behind the admin session and 2FA; the `open`/`read-all` POSTs keep the CSRF rules; marking read
on a GET is idempotent and reveals nothing (it writes `read_at` of the shown, shared events); the admin cookie is
SameSite=Strict, so a cross-site GET can't mark rows read, which is why a GET may write here, and the module doc "No
GET changes state" (`notifications.rs:8`) says so; a `?device=` filter
marks that phone's shown rows only. The receipt route stays outside the CSRF layer as before.

## 3. Tests (required)

`src/tests/notifications.rs` / `web_push.rs`: the inbox page has no This Device form and a gear link; the settings page
has the three groups and every form (`check_forms` on both); opening the inbox with 3 unread marks exactly the shown
rows (an older unread one under `?before=` stays until that page is opened), renders the dot on them and `data-unread` carries the post-mark count; a `?device=` filter leaves the other phone's unread; Mark All as Read clears the rest; every POST's
redirect lands on the settings page's group; the Devices warning row links to the settings page; `register-sw.js`'s
badge source unchanged (jstest). The design 35 tests that asserted `/notifications` content move to the page that
holds it.

## 4. Device checks

iPhone: bell with 3 → open the inbox → dots on three rows, bell 0, the Home Screen badge 0 after the page; Settings
via the gear, Turn On/Send a Test/Remove come back to their groups without a jump; the Pi's Chromium the same.

## Revision (2026-10-20, after qa-r5-design.md)

- 45-1: the inbox renders `data-unread="{{ unread_after }}"` on the gear link; §1.4's "the page's own bell" corrected; the test asserts the post-mark count.
- 45-2: read the page, mark the shown ids, then count; the existing `unread-dot` + "Unread:" reused, no `row-new`.
- 45-3: read state shared across parents; module doc changed; SameSite=Strict named as the reason a GET may write.
- 45-4: template hrefs named; one `.route("/notifications/settings", get(...).post(...))`; `device` hidden inputs kept.
- 45-5: Back to the inbox shows no dots, by design, written down.

## Implementation status (2026-10-20, branch `r5-b`)

Built as decided: `/notifications` is the inbox alone (`notifications.html`, the gear in the bell's place carrying
`data-unread`), `/notifications/settings` the settings page (`notifications_settings.html`, one route with Notify Me
About's POST); opening the inbox marks exactly the rows it shows, which keep their dot this once; every subscription
and Notify Me About redirect lands on the settings page's group; the Devices warning and the Settings hub's row lead
there, the bell to the inbox; `notifications.js` (`?v=2`) loads on the settings page only. Tests in
`src/tests/notifications.rs` and `web_push.rs` (`pages()` reads both pages where a word may be on either).

Deviations:
- The gear's `data-unread` carries **every** unread event left, not the filter's `unread_after`: §1.3 says the badge
  drops "to what is not shown yet (… another phone under a filter)", which is the bell's global count. "Mark all N as
  read" counts the filter's. The gear reuses `title-action title-bell` (the bell's 44 px icon button; no new CSS) in a
  `title-bar title-bar-split` with the back link, as `schedule_rule.html` does.
- The settings page keeps the full clock warning (`#clock-warning`); the "may not be showing" rows are on the inbox
  only (the settings page's device rows carry the badges). "Off: still listed here" became "Off: still in the inbox".
- Send a Test's push now navigates to `/notifications/settings#devices` (it was `/notifications#devices`).
- `tests::shell::every_template_with_the_bar_sets_its_tab` allows `notifications_settings.html` with no tab: a one-line
  change in its own commit, since `shell.rs` belongs to the design 43 branch.
- The Settings hub's row still shows "N unread" while it now leads to the settings page (unchanged; worth a look).

Device checks owed (§4): the iPhone - bell with 3 → inbox → dots on three rows, bell 0, the Home Screen badge 0; the
gear → Turn On / Send a Test / Remove come back to their groups without a jump; the same on the Pi's Chromium.
