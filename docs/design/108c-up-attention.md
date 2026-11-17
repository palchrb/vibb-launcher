# Design 108c: App push problems on Attention

Status: design, 2026-11-17 (branch `up-r2r3-server`). **Not built.** An addendum to design 108b (R2/R3), built
after R23-S's code QA Lows (cb040b347, whose `vapid_refused_why` it reads). Server only: no launcher change, no contract
change, no policy key. **Decided 2026-11-17** (the user: "Enig i dine anbefalinger"): Q1 (a) list and badge only, no
Web Push to the parent; Q2 (a) an app not using Vibb is not on Attention (§6). The same day the user approved a
delivery-time figure on Settings › App push (§7). Migration **0154** (0153 is pushed and frozen).

The user, 2026-11-17: "Høres lurt ut om man får et varsel om trenger oppmerksomhet på UP, med mindre det blir masse
false positives der?" The promise: **a row only for a real fault, never for a quiet app.** A kid's chat can be silent for
a week, so nothing here is raised from the absence of pushes. "No delivery in 7 days" stays a plain line on Device ›
Apps, as today.

---

## 1. The three signals

Each is computed when a page is rendered (no sweep, no stored row). It shows in the phone's summary under Attention,
and the Devices list's badge counts it: both read the same `attention::items`. All three rows are **Action** (orange),
never Alarm: a lost notification matters, but nothing on the phone is open or unsafe. They come right after
`NotificationsOff` (the other "notifications don't reach the kid" row), in this order. Every target is
`/devices/{id}/apps#app-push`. A phone not set up has none (as every row). The phone's switch off, the address cleared
or an app stopped deletes the registrations and their queue, so no row can outlive them.

### 1.1 Pushes waiting on the server (the strongest)

The phone pulls on every `up` event, every stream reopen and every sync, right after the fresh policy and before the
status report (`MdmSyncWorker`: `UpRuntime.policyAccepted`, then the report). A push still queued after two syncs is
therefore a fault in Vibb One's delivery: the pull isn't running, or what it fetched never gets acknowledged. The likely
causes are a wedged UP engine, an unreadable store (R1's one-day hold) or a launcher bug.

**Raised when all of these hold:**
- the phone's oldest live queued push (`up_messages`, `expires_at_ms > now`, lowest `id`) was queued at `C`, and
  `now - C >= 30 min`;
- **at least two status reports** (`device_status.reported_at`) lie in `[C + 10 min, now - 2 min]`. That is two
  completed syncs after the push, each of which pulled. The 2 minutes keep a pull that is still running (a 15 s step,
  then the acks 6 s later) from counting. History is kept 48 h (`STATUS_HISTORY_HOURS`), far more than needed;
- the policy row doesn't show (`policy_state` is `ok`). A phone that can't take its policy skips `policyAccepted` and
  so the pull. "Phone can't apply its settings" already says that, and is the cause.

**Never raised by:**
- a phone that is offline, switched off or not unlocked since a boot. Nothing reports before the first unlock, so no
  report can follow the push;
- a phone that just came back. Its first sync reports after it has started the pull, and one report isn't enough;
- a TTL-0 push (kept 30 s);
- an app that is suspended, hidden or gone. Its messages are still pulled and acknowledged (dropped on the phone, R1-L
  L5).

**Clears** when the queue's oldest push is acknowledged or expires (TTL at most 24 h). If the fault persists, the next
push raises the row again after two syncs.

**Read:** one `SELECT created_at_ms FROM up_messages WHERE device_id = ? AND expires_at_ms > ? ORDER BY id LIMIT 1` (the
`up_messages_device` index) and one `COUNT(*)` on `device_status` (its `(device_id, reported_at)` index).

### 1.2 Pushes refused by the VAPID check, past the heal

Only refusals the sender caused: `vapid_refused_why = 'unsigned'` (no signature, a wrong key, a bad signature; the
gateway's refusal of a keyed pushkey too). **Never `time` or `address`**: those are a correctly signed push that our
own clock or origin refused, so they may be the server's fault. Device › Apps already words them apart (L3).

A single refusal is not a fault the parent needs to act on:
- Mastodon drops the subscription at the first 403;
- the phone's heal (108b §4.4, `refresh` `refused`) sends the app a `NEW_ENDPOINT` within the day;
- Fedilab then subscribes again;
- and the app may then be quiet for days, which is fine.

So the row needs refusals that **go on for a day** with nothing accepted in between. That is a sender whose key no
longer matches, and the heal didn't fix it. A leaked endpoint used by someone else for a day looks the same, and the
same fix works for both (§3).

**New column `up_registrations.vapid_refused_since`**: the first `unsigned` refusal of the current run.
- `store::note_refusal` with `why = 'unsigned'` sets it in its existing UPDATE (once a minute at most). It sets it to
  now when it is null or the previous stamp is over 7 days old, and keeps it otherwise:
  `CASE WHEN vapid_refused_since IS NULL OR vapid_refused_at IS NULL OR vapid_refused_at < datetime('now', '-7
  days') THEN datetime('now') ELSE vapid_refused_since END`. SQLite reads the old row on the right-hand side.
- `time` and `address` refusals leave it alone.
- `store::enqueue`'s existing `last_push_at` UPDATE sets it to NULL: an accepted push ends the run.
- The key update (the registration retry that clears `vapid_refused_at`) clears it too.

**Raised for a registration when all of these hold:**
- `vapid_refused_why = 'unsigned'` and `vapid_refused_at` is within 7 days (the page line's window);
- `vapid_refused_at - vapid_refused_since >= 24 h`: refusals still came a day after the first one;
- nothing was accepted since: `last_push_at` is null or before `vapid_refused_since`. That always holds, because
  enqueue clears the run. It is stated for the reader;
- the phone reported at least once a day or more after the run began (`devices.last_seen_at >= vapid_refused_since
  + 24 h`). The heal only runs on a phone that is on and in touch.

**Clears:** an accepted push, a key update, the registration deleted (the app's Stop, the switch, the allowlist), or 7
days without a refusal.

### 1.3 The outside gateway, past the heal

The mark is 108b §3.2's: an outside push within 7 days, and no push through our own gateway since. While it holds, the
phone asks the app once a day, when the phone is in use, to check the address again (§3.3). The row says that the
heal has had two days and the mark still holds.

**New column `up_registrations.outside_since`**: when the current mark began.
- It is written in `publish.rs`'s existing `outside_gateway_at` UPDATE (once a minute at most).
- It is set to now when no mark was standing, and kept otherwise: `CASE WHEN outside_since IS NULL OR
  outside_gateway_at IS NULL OR outside_gateway_at < datetime('now', '-7 days') OR (gateway_at IS NOT NULL AND
  gateway_at >= outside_gateway_at) THEN datetime('now') ELSE outside_since END`.
- The gateway's path writes nothing new: the mark's end is already read from `gateway_at`.

**Raised for a registration when all of these hold:**
- it is marked (the same SQL as the page line and `refresh`);
- `outside_since <= now - 48 h`;
- the phone reported at least once a day or more after the mark began (`devices.last_seen_at >= outside_since +
  24 h`).

**What stays open:** a phone that is on and reporting but not used for two days gets no heal, and the row shows anyway.
That is still true: a third party is in the path (it sees the event and room ids and the unread count). A homeserver
that keeps two pushers flips the mark with each push and restarts `outside_since`, so no row. Our gateway is in use
there.

**Clears:** a push through our gateway, 7 days without an outside push (the mark fades), or the registration deleted.

### 1.4 Not on Attention

- **"No delivery in 7 days"**: a quiet week is normal.
- **"Many pushes"**: a noisy app is the app's nature, not a fault, and the page says what it costs.
- **`time`/`address` refusals**: possibly our own clock or origin. The page's line says so, and DEPLOY.md's clock note
  covers it.
- **An app without a registration ("isn't using Vibb")**: Q2.

---

## 2. Words

Titles: at most 8 words, from typed values only (no app names: an installed app's label is phone-supplied text, and
the target names each app). Keys are `device.attention.*`, as the rest.

| Row (`data-attention`) | en | nb |
|---|---|---|
| `app-push-waiting` | App notifications aren't reaching the phone | Appvarsler kommer ikke fram til telefonen |
| `app-push-refused` one / other | An app's notifications are refused / {count} apps' notifications are refused | Varsler til en app blir avvist / Varsler til {count} apper blir avvist |
| `app-push-outside` one / other | An app's notifications go via an outside service / {count} apps' notifications go via an outside service | Varsler til en app går via en ekstern tjeneste / Varsler til {count} apper går via en ekstern tjeneste |

The count is apps (packages), not registrations: Element X with two accounts is one app.

**On Device › Apps' App push group**, so the target explains itself:
- **Waiting:** a group-level warning line under the switch (`AppPushGroup.waiting`), shown exactly when the row is
  raised:
  - en: "Notifications for the phone's apps have waited on the server since {when}, although the phone has been in
    touch since. Restarting the phone usually gets them going again."
  - nb: "Varsler til appene på telefonen har ventet på serveren siden {when}, selv om telefonen har vært i kontakt
    etterpå. En omstart av telefonen får dem som regel i gang igjen."
- **Refused:** the app's existing line ("Pushes not signed by the app's server were refused, last {when}") gains a
  second sentence while the row is raised:
  - en: "This has gone on for over a day. Opening {app} on the phone once usually fixes it; if not, Stop… and Allow
    Again gives it a new address."
  - nb: "Dette har vart i over et døgn. Å åpne {app} på telefonen én gang fikser det som regel; hvis ikke, gir Stopp…
    og Tillat igjen appen en ny adresse."
  - Stop deletes the registration and its secret is tombstoned. The app registers anew at its next start, and its
    server must subscribe again, which is the same fix for a stale key and a leaked endpoint.
- **Outside:** the existing line already says "If this stays for days, the phone can't reach the address: see “Last
  seen from outside”". Nothing is added.

No save on the page changes, so nothing can jump (`pwa-save-no-jump`).

---

## 3. Code (server)

- **`unified_push::attention(db, device_id, now) -> AppPushAttention { waiting_since: Option<DateTime>,
  refused: Vec<String>, outside: Vec<String> }`** (packages). It runs one query over `up_registrations` for the two
  per-registration rules, grouped by package, and §1.1's two reads. `Device › Apps`' `group` calls it too, so the row
  and the page agree, as every other Attention row does.
- **`attention.rs`:**
  - three `Kind`s: `AppPushWaiting`, `AppPushRefused(usize)`, `AppPushOutside(usize)`;
  - `Facts.app_push`, and `Sources.app_push` (the summary passes what it read);
  - `read_from_report` is false for all three: the server's own data, never "at last report";
  - `facts()` reads it after the music parts;
  - `waiting` is dropped while `policy_problem` holds (§1.1).
- **Migration 0154** (0153 is pushed and frozen): the two columns, nullable TEXT with no backfill (a run that began
  before the upgrade starts counting at its next stamp), and §7's `up_messages.connected` and table.
- **Words:** `device.attention.app_push_*`, `app_push.waiting` and `app_push.refused_fix` in `en.json` and `nb.json`.
- `docs/notes/server/unified-push.md` gains an "Attention (design 108c)" paragraph. `pwa.md`'s Attention bullet names
  the three rows.

## 4. Tests (lean)

- **`attention::tests`:** the three kinds' place (after `NotificationsOff`), level, target, key, and the nb/en
  one/other titles. This extends the existing table rows.
- **`tests::unified_push::attention`:** one integration test with stamps backdated by SQL, through the real
  `note_refusal`/`enqueue`/publish paths where a column is written:
  - **waiting:** raised with two reports in the window. Not raised with one, with reports only before `C + 10 min`,
    with the second report under 2 minutes old, with `policy_state` not `ok`, or after the pull's ack;
  - **refused:** an `unsigned` run of 24 h with a report a day in raises. A single refusal, a `time` run, and a run
    ended by an accepted push (enqueue clears `vapid_refused_since`) don't;
  - **outside:** 48 h with a report a day in raises. 47 h doesn't, and neither does a mark ended by a push through our
    gateway;
  - the Devices list's badge and the summary's group count the same rows. Device › Apps shows the waiting line and the
    refused sentence.

## 5. Size

**Server S**, about 400 lines with the tests:
- the Attention rows (§1-§4), ~250: the query and rules ~80, `attention.rs` ~40, the page lines ~30, the words, the
  tests ~100;
- the delivery time (§7), ~160: the flag, the table and their writes ~40, the figure and its words ~50, the two page
  rows ~20, the tests ~50.

No launcher, contract or gate work. It goes on `up-r2r3-server` after the Lows fix, then gets its own code QA (XS).

## 6. Questions for the user (decided 2026-11-17: (a) and (a))

**Q1. Should these rows also push to the parent?** The PWA's Web Push to parents would need:
- a `parent_events` kind;
- a sweep, because the conditions are time-based and no request raises them;
- a dedup key per run;
- a `notification_prefs` switch, which is a migration and a row on Notify Me About.

That adds about S. Today only rows that are also events push (an app that may read the PIN, a start without Vibb One).
"Notifications off", music and calls show in the list only.
- **(a) List only: the Attention row and the badge.**
- (b) A push too, once per run, with its own switch, on by default.

**Decided: (a)**, as recommended. None of the three is urgent. They are seen the next time the PWA is opened, and a push for a
condition the heal often fixes would be the noise the user asked to avoid. A push can be added later from the same
rules.

**Q2. Element X (or FluffyChat) allowed and installed, but not registered with Vibb ("isn't using Vibb"): on
Attention?** Element X keeps its Play build (the user's Q4, 2026-11-16), so it gets its notifications through Google.
Nothing is lost: it is a choice the parent may make on purpose, or a setup step not yet done. As an Attention row it
would show an orange badge on the Devices list for as long as the parent keeps Google, with no way to dismiss it.
- **(a) No: it stays the line on Device › Apps, as today.**
- (b) Yes, as an Action row ("Element X isn't using Vibb").

**Decided: (a)**, as recommended.

---

## 7. Delivery time on Settings › App push

Approved by the user on 2026-11-17: how long pushes take from this server to the phone, over the last 24 hours.
Revised before design QA, the same day. A plain maximum would almost always be hours: a phone switched off overnight
gets its queued pushes in the morning, and that says nothing about delivery. So only pushes that came **while the
phone was connected** count, and the figure is the median and the 95th percentile.

### 7.1 What is measured

From **the server accepting a push** to **the phone's ack of it**. Both instants are the server's own clock, so a phone
whose clock is off changes nothing.
- **Accepted:** `up_messages.created_at_ms`, written by `store::enqueue` when `/up/` or the gateway queues the push.
- **Acked:** the `POST /api/devices/up/messages` whose `ack` names the key, taken as `now_ms` in `store::pull`.

The phone acks a key only after its `sendBroadcast` ran, and sends the ack with its next pull: at once in a running
pull, or 6 s later (`pullLater`). So the figure is "on the phone", give or take a few seconds. It is not "read by the
child". A message the phone drops, because its app is hidden or gone, is acked too and counts: it reached the phone.

**Only pushes accepted while the phone held its command stream count.** The server knows this cheaply.
`unified_push::accept` already reads the in-memory stream registry, `state.command_streams.state(device_id)` and its
`open`, for the TTL-0 rule. It reads it for every push, takes it as `connected`, and passes it to `enqueue`, which
stores it on the queue row.

An open stream the server hasn't yet seen die is counted as connected. That happens when the phone lost its network
and the keepalive hasn't failed yet. Its slow delivery is real, and it is what the 95th percentile should show.

What is not in the figure:
- a push accepted while the phone had no stream;
- a push that expired undelivered (its row is pruned, never acked);
- a push replaced by a newer one with the same `Topic`.

### 7.2 Where the samples are kept

The flag lives on the queue row. The sample needs a store of its own, because the ack deletes the queue row. Both go in
**0154**:

```sql
ALTER TABLE up_messages ADD COLUMN connected INTEGER NOT NULL DEFAULT 0;   -- the phone's stream open at accept
CREATE TABLE up_delivery (
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    acked_ms INTEGER NOT NULL,
    ms INTEGER NOT NULL                 -- acked - accepted, at least 0
);
CREATE INDEX up_delivery_at ON up_delivery (acked_ms);
CREATE INDEX up_delivery_device ON up_delivery (device_id, acked_ms);
```

- **The write**, in `pull`'s ack transaction before the `DELETE`: `INSERT INTO up_delivery (device_id, acked_ms, ms)
  SELECT device_id, ?now, MAX(0, ?now - created_at_ms) FROM up_messages WHERE device_id = ? AND connected = 1 AND key
  IN (…)`.
  - Only a key that is still queued gives a sample, so an ack sent twice counts once.
  - A clock stepped back gives 0, never less.
  - A row queued before the upgrade has `connected = 0` and gives no sample.
- **Nothing per message beyond the three numbers**: no key, registration, package or body.
- **Pruned hourly** in `store::prune`: rows older than 24 h. It also keeps at most the newest **20,000** rows, about
  600 KB. The phone's own bucket allows at most about 840 pushes an hour, so that cap is reached only by several busy
  phones together.
- **It survives a restart**, since it is SQLite. A server update loses nothing. The stream registry starts empty after a
  restart, and phones reconnect within seconds (up to 5 min after a crash). Pushes accepted in that gap count as not
  connected, so they are left out.

### 7.3 The figure

A pure `delivery_figure(ms_sorted) -> Option<Figure { count, median_ms, p95_ms }>` works on the last 24 h's samples
(`acked_ms > now - 24 h`, read sorted; at most 20,000 integers).
- **Both are nearest-rank**, so each is a time that really happened. The median is element `ceil(0.5 n)` and the 95th
  percentile is element `ceil(0.95 n)` (1-based).
- **With 1 to 19 samples**, the 95th percentile is the slowest, since `ceil(0.95 n) = n`. That is honest at that count,
  and the count beside it says how few.
- **With no samples**, the group says so and shows no figure.

Durations are worded by `duration_words(lang, ms)`:
- under 1 s: "under a second" / "under ett sekund";
- under 1 min: "{n} s";
- under 1 h: "{n} min";
- otherwise: "{h} h {m} min" / "{h} t {m} min".

### 7.4 Words and places

**Settings › App push** gets a new group "Delivery to the phones" ("Levering til telefonene") under "Last seen from
outside" (`#delivery`). Its rows have no save, so nothing can jump.
- **Row** "Median" ("Median"): "2 s", "under a second" …
- **Row** "95 % within" ("95 % innen"): "9 s".
- **Row** "Pushes counted" ("Push talt med"): "38 in the last 24 hours" / "38 siste døgn".
- **With none:**
  - en: one row "No pushes to a connected phone in the last 24 hours";
  - nb: "Ingen push til en tilkoblet telefon siste døgn".
- **Footer:**
  - en: "From this server receiving a push to the phone confirming it, for every phone together. Only pushes that
    came while the phone was connected are counted."
  - nb: "Fra serveren tar imot et push til telefonen bekrefter det, for alle telefonene samlet. Bare push som kom mens
    telefonen var tilkoblet, er talt med."

**Device › Apps' App push group, per phone.** This is cheap: the same function over the `device_id` index. It is one
line under the switch, only when the phone has samples:
- en: "Delivered in {median} as a rule, 95 % within {p95} ({count} pushes while connected, last 24 hours)";
- nb: "Levert på {median} som regel, 95 % innen {p95} ({count} push mens den var tilkoblet, siste døgn)".

### 7.5 Tests (lean)

- **`unified_push::store::tests`:**
  - three pushes enqueued, two `connected` and one not;
  - one pull acknowledges all three, then the same keys are acknowledged again;
  - this gives two samples, with the device id and `ms >= 0`;
  - one sample backdated past 24 h is pruned.
- **`tests::unified_push`:** one push through `/up/` with a stream open in the registry, and one without, gives
  `connected` 1 and 0. This checks that `accept` reads the registry.
- **A pure table for `delivery_figure`:** 0, 1, 2, 19, 20 and 40 samples, with the median and the 95th percentile at
  their nearest ranks. Also `duration_words` in nb and en at each boundary (999 ms, 1 s, 59 s, 60 s, 59 min, 1 h).
- **One page test:**
  - Settings › App push shows the group with seeded samples, and "No pushes…" without them;
  - Device › Apps shows the phone's line only for the phone with samples.
