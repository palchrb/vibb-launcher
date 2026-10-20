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
   About exactly as they are, with their `#this-device`, `#devices`, `#events` fragments. The route exists for the
   Notify Me About POST already (`save_settings`); the GET joins it. Every POST under `/notifications/...` that comes
   back to one of those three groups redirects to `/notifications/settings{query}#group` (the no-jump rule:
   `scroll-restore.js` keeps the place; `back_to`, `back_query` get the page). `open` and `read-all` come back to the
   inbox.
3. **Opening the inbox reads it.** The `page` handler loads the page's rows, then marks exactly those rows read
   (`UPDATE parent_events SET read_at = datetime('now') WHERE id IN (…) AND read_at IS NULL`), and renders them with
   the unread state they had *before* the marking: a quiet dot (`row-new`, the Vibb accent, 6 px, before the title;
   `aria-label` "new") on the rows that were unread. So the bell and the app badge drop to what is not shown yet
   (older pages, another phone under a filter); the page's own bell (`Bell::load` after the update) shows that number.
   An older page (`?before=`) reads its rows the same way. Mark All as Read stays for the rest and says what it
   does ("Mark all N as read" when N > 0 remain, else hidden). A tap on a row still goes through `open` (it is what
   sets `clicked` on the delivery and lands on the phone's page).
4. **The app badge** follows the bell: `register-sw.js` sets it from the bell's `data-unread` on every page, so
   opening the inbox clears it on the device that opened it; the other devices' badges follow their next push
   (`app_badge` in the payload) or their next page open. The worker clears nothing by itself.
5. **The push's `navigate`** keeps pointing at the phone's page (the user: a tap on a notification should land on the
   phone), with design r5's `?push=<delivery>` marker (`docs/design/r5-server-small-items.md` §1), which marks the
   delivery opened and the event read.
6. **The links**: the Devices list's warning row (`notifications::warning_row`) and the inbox's "may not be
   showing"/"not reaching" attention rows go to `/notifications/settings?from=devices#devices`; the clock warning
   shows on the settings page (it is about delivery), the inbox keeps a one-line version linking there when it
   applies. The Settings hub's "Notifications" row (if present) goes to the settings page; the bell to the inbox.
7. **`static/notifications.js`** (This Device's state and the subscribe tap) loads on the settings page only
   (`?v=` bumped); the inbox page loads nothing but the shared scripts. The Send-a-Test poll (r5 §5) lives in the
   same file.
8. **Words**: the inbox's empty state "Nothing yet - what the phones report shows up here"; the settings page's
   groups keep their words.

## 2. Security

Unchanged: both pages behind the admin session and 2FA; the `open`/`read-all` POSTs keep the CSRF rules; marking read
on a GET is idempotent and reveals nothing (it only writes the admin's own events' `read_at`); a `?device=` filter
marks that phone's shown rows only. The receipt route stays outside the CSRF layer as before.

## 3. Tests (required)

`src/tests/notifications.rs` / `web_push.rs`: the inbox page has no This Device form and a gear link; the settings page
has the three groups and every form (`check_forms` on both); opening the inbox with 3 unread marks exactly the shown
rows (an older unread one under `?before=` stays until that page is opened), renders the dot on them and the bell's
count after; a `?device=` filter leaves the other phone's unread; Mark All as Read clears the rest; every POST's
redirect lands on the settings page's group; the Devices warning row links to the settings page; `register-sw.js`'s
badge source unchanged (jstest). The design 35 tests that asserted `/notifications` content move to the page that
holds it.

## 4. Device checks

iPhone: bell with 3 → open the inbox → dots on three rows, bell 0, the Home Screen badge 0 after the page; Settings
via the gear, Turn On/Send a Test/Remove come back to their groups without a jump; the Pi's Chromium the same.
