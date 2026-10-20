# Server round 5 - the small items (proposal)

Asked 2026-10-19/20, decided in PLAN ("Next server round", "PWA small things", the pictures entry). Server only. Goes
out with designs 43, 44 and 45 as server 0.30.0.

## 1. The tap on a declarative notification marks the delivery opened

On Safari a tap on a declarative notification goes straight to its `navigate` URL; WebKit skips the worker when the
notification has one, so no `clicked` receipt ever comes from an iPhone (0.29.6, `docs/notes/server/notifications.md`).

- `push::send`'s `navigate` carries the delivery: `?push=<delivery id>` inserted before any `#fragment`
  (`/devices/3/calls?push=48`, `/devices/3?push=48#foreign-access`, `/notifications?push=48`). The classic worker's
  `data.navigate` is the same string, so Chromium's tap lands the same way (its `click` receipt still comes too).
- An admin-router layer (`middleware::from_fn` after the session/2FA layers, GET only) reads `push=<i64>` from the
  query and, for a delivery of one of this admin's subscriptions, sets `clicked_at = COALESCE(clicked_at, now)` and
  the delivery's event `read_at = COALESCE(read_at, now)`. Unknown or foreign id: nothing, no error. The page renders
  as if the parameter weren't there (it is not part of `page_query`/`back_query`; `scroll-restore.js` keys on
  `pathname`, unaffected).
- Words: `delivery_line` gains the opened state (a 5th field, `clicked_at IS NOT NULL`): one delivery -> "Opened on
  iPhone · Safari" before "Shown on" before "Delivered to" before "Pushed to …, not confirmed"; several -> "Pushed to
  2 devices · opened on 1" when any was opened, else today's "shown on N" (delivered ones counted, as 0.29.6).
- A missing tap never changes anything: delivered stays delivered (the user, 2026-10-20).

## 2. A quiet count on the device row (Getting Notifications)

`Subscription` gains `last_opened_at` (the newest `clicked_at` of its deliveries) and `since_opened` (accepted
deliveries newer than it; all accepted when none was ever opened). The row's sub-line: "Last delivered 2 min ago · 7
since one was opened"; never opened: "· 7 not opened yet"; 0: nothing added. Plain tone (`row-sub`), never a badge or
a warning (the user: "en stillere notis"). Health is unchanged.

## 3. A log line per command stream

`commands_stream`: `tracing::info!(device = id, "command stream opened")` when the stream starts and, in
`StreamGuard::drop`, `tracing::info!(device, secs, "command stream closed")` with the stream's age (an `Instant` in the
guard). Both at `info`; they are the server-side half of the SSE diagnosis (the client's half is
`adb logcat -s CommandListenerService`). No `X-Accel-Buffering` header: Caddy needs none (DEPLOY.md, 2026-10-20).

## 4. Episode pictures: migration 0082 and the failures rule

- `0082_music_art_more_backfill.sql`: `UPDATE music_listings SET art_more = 1 WHERE art_more = 0 AND EXISTS (one
  `music_items` row of this listing whose `art_state IS NULL`, or whose `art_from` differs from its `art_url`)` - the
  entries checked under the old caps (2026-10-19) get their untried pictures without waiting for the 6-hour regular
  time. The implementer takes the join column from migration 0070/0073 (`music_items` -> its listing). The next free
  number is 0082 (server/CLAUDE.md, "Data model").
- `music_sweep.rs`, `Why::Art` (line ~612): today `row.failures == 0 && row.art_more > 0 && regular > now` -> due at
  once. New: with `failures > 0` the art reason is due at the listing's failure backoff time (the same `next` the
  failure path computes), never later than `regular`; so a listing whose host had a blip gets its pictures at the
  next backoff instead of waiting the whole regular interval, and a dead host is never hammered. Test:
  `music_sweep` tests for both branches (failures 0 -> now; failures 2 -> the backoff time; the regular time when
  it is earlier).

## 5. Send a Test: the device rows poll

After Send a Test (`POST /notifications/subscriptions/{id}/test`, back to `/notifications/settings…&notice=…#devices`),
`notifications.js` polls `GET /notifications/subscriptions.json` (new; this admin's `device_row`s as JSON: `id`,
`badge`, `badge_class`, `sub`) every 3 s for 30 s and re-renders only the badge and the sub-line of the rows in place
(`textContent`, the class), as Install Now's row does; no scroll, no reload, stops early once the tested row's `sub`
changes. The JSON route is `no-store` and added to `admin_auth`'s never-stored list.

## 6. Tests (required)

`web_push.rs`: `navigate` carries `?push=<id>` before the fragment; a GET with `push=<id>` of this admin's delivery sets
`clicked_at` and the event's `read_at` and the line says "Opened on"; another admin's id or garbage changes nothing;
the device row's count words for the three cases; `subscriptions.json` shape and `no-store`. `music_sweep`: §4's three
cases; a migration test that 0082 flags a listing with one untried item and not one with all tried. `streams`: the
closed line carries the age (a tracing test or a unit test on the guard's age). jstest: the poll re-renders rows and
stops.
