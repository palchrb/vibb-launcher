# Server round 5 - the small items (proposal)

Asked 2026-10-19/20, decided in PLAN ("Next server round", "PWA small things", the pictures entry). Server only. Goes
out with designs 43, 44 and 45 as server 0.30.0.

## 1. The tap on a declarative notification marks the delivery opened

On Safari a tap on a declarative notification goes straight to its `navigate` URL; WebKit skips the worker when the
notification has one, so no `clicked` receipt ever comes from an iPhone (0.29.6, `docs/notes/server/notifications.md`).

- `push::send`'s `navigate` carries the delivery: `?push=<delivery id>` inserted before any `#fragment`
  (`/devices/3/calls?push=48`, `/devices/3?push=48#foreign-access`, `/notifications?push=48`). The classic worker's
  `data.navigate` is the same string, so Chromium's tap lands the same way (its `click` receipt still comes too).
- An admin-router layer (`middleware::from_fn`, GET only) reads `push=<i64>` from the query and, for a delivery of
  one of this admin's subscriptions, sets `clicked_at = COALESCE(clicked_at, now)`, the subscription's
  `last_opened_at`, and the delivery's event `read_at = COALESCE(read_at, now)` (a summary or test has no `event_id`:
  `clicked_at` and `last_opened_at` only). In `main.rs` it is added *before* `.layer(require_full_auth)` (a later
  `.layer` runs earlier), so it runs inside it and sees `CurrentAdmin`. Signed out, `require_full_auth` redirects to
  `/login` without the URL and the marker is lost: acceptable, a missing tap changes nothing. After marking, the
  layer answers a 303 to the same URL without `push` (the fragment is not sent to the server, so it survives), so
  Back and reload stop re-marking and no page ever sees the parameter (it is not part of `page_query`/`back_query`;
  `scroll-restore.js` keys on `pathname`, unaffected). Unknown or foreign id: the same 303, nothing marked.
- `push::send` inserts `&push=` instead of `?push=` when a stored target already has a `?` (none has today).
- Words: `delivery_line` gains the opened state (a 5th field, `clicked_at IS NOT NULL`): one delivery -> "Opened on
  iPhone · Safari" before "Shown on" before "Delivered to" before "Pushed to …, not confirmed"; several -> "Pushed to
  2 devices · opened on 1" when any was opened, else today's "shown on N" (delivered ones counted, as 0.29.6).
- A missing tap never changes anything: delivered stays delivered (the user, 2026-10-20).

## 2. A quiet count on the device row (Getting Notifications)

Migration 0082 adds `push_subscriptions.last_opened_at` (set by the marker layer and by a click receipt; deliveries
are pruned after 30 days, so the column outlives them). `Subscription` gains `since_opened`: accepted deliveries newer
than the basis `max(last_receipt_at, last_opened_at)`; all accepted when no basis exists. The row's sub-line: "Last
delivered 2 min ago · 7 since one was opened" when a basis exists; "· 7 not opened yet" when none ever; 0: nothing
added. Plain tone (`row-sub`), never a badge or
a warning (the user: "en stillere notis"). Health is unchanged.

## 3. A log line per command stream

`commands_stream`: `tracing::info!(device = id, "command stream opened")` when the stream starts and, in
`StreamGuard::drop`, `tracing::info!(device, secs, "command stream closed")` with the stream's age (`opened: Instant`,
`fn age()` on the guard; `close_all` at shutdown logs one line per stream, as expected). Both at `info`; they are the server-side half of the SSE diagnosis (the client's half is
`adb logcat -s CommandListenerService`). No `X-Accel-Buffering` header: Caddy needs none (DEPLOY.md, 2026-10-20).

## 4. Episode pictures: migration 0082

- `0082_art_backfill_and_last_opened.sql`: `UPDATE music_listings SET art_more = 1 WHERE art_more = 0 AND EXISTS
  (SELECT 1 FROM music_items i WHERE i.entry_id = music_listings.entry_id AND i.state = 'ok' AND i.art_url IS NOT NULL
  AND (i.art_from IS NULL OR i.art_from <> i.art_url))` - the condition of `art_candidate`/`art_due`
  (`music_sweep.rs`), so the entries checked under the old caps (2026-10-19) get their untried pictures without waiting
  for the 6-hour regular time (`art_more` is a count; 1 means "some left"). The same file adds
  `ALTER TABLE push_subscriptions ADD COLUMN last_opened_at` (§2). The next free number is 0082.
- The `Why::Art` failures rule is dropped from this round (as written it equalled today's `Why::Interval`). "Does one
  bad episode URL raise the listing's `failures`, and what holds the other pictures?" goes to PLAN as an investigation.

## 5. Send a Test: the device rows poll

After Send a Test (`POST /notifications/subscriptions/{id}/test`, back to `/notifications/settings…&notice=…#devices`),
`notifications.js` polls `GET /notifications/subscriptions.json` (new; this admin's `device_row`s as JSON: `id`,
`badge`, `badge_class`, `sub`; `notifications.js` loads on the settings page, 45 §1.7) every 3 s for 30 s and re-renders only the badge and the sub-line of the rows in place
(`textContent`, the class), as Install Now's row does; no scroll, no reload, stops early once the tested row's `sub`
changes. The JSON route is already `no-store` (`["notifications", ..]` in `no_store_path`); nothing is added, `no_store_paths` tests it.

## 6. Tests (required)

`web_push.rs`: `navigate` carries `?push=<id>` before the fragment (`&push=` after an existing `?`); a GET with
`push=<id>` of this admin's delivery sets `clicked_at`, `last_opened_at` and the event's `read_at`, answers a 303 to the
same URL without `push`, and the line says "Opened on"; a summary (no event) sets `clicked_at` only; another admin's id
or garbage changes nothing; the device row's count words for the three cases (basis from a receipt, from
`last_opened_at` after pruning, none); `subscriptions.json` shape and `no-store`. A migration test that 0082 flags a listing
with one untried item and not one with all tried, nor one whose only untried item has no `art_url`. `streams`: the
guard keeps `opened: Instant` and `fn age()`, unit-tested (no tracing-capture crate). jstest: the poll re-renders rows and
stops.

## Revision (2026-10-20, after qa-r5-design.md)

- r5-1: the marker layer is added before `.layer(require_full_auth)`; a signed-out tap loses the marker (acceptable); the layer answers a 303 to the same URL without `push`.
- r5-2: the count's basis is `max(last_receipt_at, last_opened_at)`; 0082 adds `push_subscriptions.last_opened_at` (marker layer and click receipt); words "since one was opened" / "not opened yet" / nothing.
- r5-3: the `Why::Art` failures rule dropped; §4 keeps migration 0082 only; the bad-episode-URL question goes to PLAN as an investigation.
- r5-4: 0082's art condition is `art_candidate`'s, join `entry_id`, file renamed `0082_art_backfill_and_last_opened.sql`; test for an untried item without `art_url`.
- r5-5: `&push=` after an existing `?`; a summary or test sets `clicked_at` only.
- r5-6: `subscriptions.json` is already no-store; nothing added, tested in `no_store_paths`; `notifications.js` on the settings page.
- r5-7: the age lives on the guard (`opened`, `age()`), unit-tested.
