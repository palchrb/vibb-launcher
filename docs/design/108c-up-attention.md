# Design 108c: App push problems on Attention, and the delivery time

Status: design, **revision 2**, 2026-11-17 (branch `up-r2r3-server`). **Not built.** Revision 1 was a06c87f80; its
design QA is `docs/review/qa-108c-design.md` (ebe511134: 2 H, 3 M, 9 L), and §8 maps every finding to its fix.

An addendum to design 108b (R2/R3), built after R23-S's code QA Lows (cb040b347, whose `vapid_refused_why` it reads).
Server only: no launcher change, no contract change, no policy key. Migration **0154** (0153 is pushed and frozen).

**Decided 2026-11-17** (the user: "Enig i dine anbefalinger"):
- Q1 (a): list and badge only, no Web Push to the parent;
- Q2 (a): an app not using Vibb is not on Attention (§6).

The same day the user approved a delivery-time figure on Settings › App push (§7).

The user, 2026-11-17: "Høres lurt ut om man får et varsel om trenger oppmerksomhet på UP, med mindre det blir masse
false positives der?" The promise: **a row only for a real fault, never for a quiet app.** A kid's chat can be silent
for a week, so every row rests on something that happened (a push, a pull, a refusal), never on a push that didn't
come. "No delivery in 7 days" stays a plain line on Device › Apps, as today.

**What revision 2 changed, in short:**
- **The outside row needs evidence (H1):** an outside push a day or more into the run, not two quiet days.
- **The waiting row counts what the server saw (M1):** policies it served and pulls it answered, not reports.
- **The delivery time (H2, M2, M3):**
  - measured to the pull that hands the push out, not to the ack;
  - only on the stream that was open when the push was accepted;
  - the 95% only from 20 pushes.

---

## 1. The three signals

Each is computed when a page is rendered (no sweep, no stored row). It shows in the phone's summary under Attention,
and the Devices list's badge counts it: both read the same `attention::items`. All three rows are **Action** (orange),
never Alarm: a lost notification matters, but nothing on the phone is open or unsafe. They come right after
`NotificationsOff` (the other "notifications don't reach the kid" row), in this order. Every target is
`/devices/{id}/apps#app-push`. A phone not set up has none (as every row). The phone's switch off, the address cleared
or an app stopped deletes the registrations and their queue, so no row can outlive them.

"The phone reported" below always means **the latest status report's `reported_at`** (`latest` in
`facts`/`facts_of`), never `devices.last_seen_at`. Design 91's locked report bumps `last_seen_at` from a phone before
its first unlock, where UP neither pulls nor heals (L1).

### 1.1 Pushes waiting on the server

**Three new columns on `up_messages`** (0154), all epoch ms; §7 uses them too:
- `stream_ms` is the phone's stream generation at accept (§7.2);
- `fetched_ms` is the first pull that handed the row out;
- `handed_ms` is the latest pull that handed it out.

A pull answers the oldest rows first, so every pull hands out the phone's oldest push again until it is acknowledged.
The hand-out write is one UPDATE per pull that returns messages: `fetched_ms = COALESCE(fetched_ms, ?now)`,
`handed_ms = ?now`.

**Policies served, in memory:** `UnifiedPush.policy_served`, the last 4 instants per phone at which
`device_api::policy` answered 200 or 304. It costs no write. A restart forgets them, and the row stays quiet until
three new ones are seen.

**Raised when the phone's oldest live push** (`expires_at_ms > now`, lowest `id`), queued at `C` with
`now - C >= 30 min`, meets **either** arm:

- **(a) Never fetched although the phone took its policy.**
  - `fetched_ms` is null;
  - at least **three** policies were served in `[C + 10 min, now - 2 min]`;
  - no report in that window says the phone couldn't apply its policy (`policy_state` not `ok`; one indexed `EXISTS`).

  The phone pulls after every policy it accepts (`MdmSyncWorker`: `UpRuntime.policyAccepted`). Three accepted
  policies and no pull mean the pull isn't running: a wedged UP engine, or the store's one-day hold. A policy GET that
  timed out is never "served", and a report-only post (`postReportOnly`) is no policy at all. So neither case counts
  (M1). Three is about an hour at the 30-minute backstop. Nothing here is urgent (Q1).
- **(b) Fetched but never acknowledged.**
  - `fetched_ms` is set;
  - `handed_ms >= fetched_ms + 2 min`: the phone pulled again well after its 6-second ack flush, and the push is
    still queued.

  The phone's broadcast to the app keeps failing, or its acks never arrive. This is the stronger arm: the server saw
  both pulls.

**Never raised by:**
- a phone that is offline, switched off, or not unlocked since a boot (it fetches no policy and makes no pull);
- a phone that just came back: its first pull fetches the push, and the ack follows within seconds;
- a TTL-0 push (kept 30 s);
- an app that is suspended, hidden or gone. Its messages are still pulled and acknowledged: dropped on the phone, R1-L
  L5.

**Clears** when the oldest push is acknowledged or expires (TTL at most 24 h). If the fault persists, the next push
raises the row again.

**Read (L6):**
- one `LIMIT 1` on `up_messages` (the `up_messages_device` index) for every phone;
- only when that push is 30 minutes old, arm (a)'s count in memory and its `EXISTS` on `device_status`. The window's
  bounds are bound as text (`%Y-%m-%d %H:%M:%S`), so the `(device_id, reported_at)` index range is used.

### 1.2 Pushes refused by the VAPID check, past the heal

Only refusals the sender caused: `vapid_refused_why = 'unsigned'` (no signature, a wrong key, a bad signature; the
gateway's refusal of a keyed pushkey too). **Never `time` or `address`**: those are a correctly signed push that our
own clock or origin refused, so they may be the server's fault. Device › Apps already words them apart.

A single refusal is not a fault the parent needs to act on:
- Mastodon drops the subscription at the first 403;
- the heal (108b §4.4) sends the app a `NEW_ENDPOINT` within the day;
- Fedilab subscribes again;
- and the app may then be quiet for days.

The row needs refusals that **go on for a day** with nothing accepted in between. That is a sender whose key no longer
matches, which the heal didn't fix. A leaked endpoint used by someone else for a day looks the same, and the same fix
works for both (§2).

**New column `up_registrations.vapid_refused_since`** (0154): the first `unsigned` refusal of the current run.
- **`store::note_refusal`** writes it in its existing UPDATE (once a minute at most). The rule is in the SQL, on the
  bound `why`, so it stays one statement (L2): `vapid_refused_since = CASE WHEN ?why <> 'unsigned' THEN
  vapid_refused_since WHEN vapid_refused_since IS NULL OR vapid_refused_at IS NULL OR vapid_refused_at <
  datetime('now', '-7 days') OR COALESCE(vapid_refused_why, 'unsigned') <> 'unsigned' THEN datetime('now') ELSE
  vapid_refused_since END`.
  - SQLite reads the old row on the right-hand side.
  - A run restarts after 7 quiet days, or after a `time`/`address` stamp.
- **It is cleared** by:
  - `store::enqueue`'s existing `last_push_at` UPDATE (an accepted push ends the run);
  - the key update (with `vapid_refused_at`);
  - `accept`'s TTL-0 `Dropped` path. A verified push that wasn't stored still proves the key works (L3):
    `UPDATE … SET vapid_refused_since = NULL WHERE id = ? AND vapid_refused_since IS NOT NULL`.

**Raised for a registration when all of these hold:**
- `vapid_refused_why = 'unsigned'`, and `vapid_refused_at` is within 7 days (the page line's window);
- `vapid_refused_at >= vapid_refused_since + 24 h`: refusals still came a day after the first;
- the phone reported a day or more after the run began (`reported_at >= vapid_refused_since + 24 h`). The heal only
  runs on a phone that is on and in touch.

**Clears:** an accepted push, a key update, the registration deleted (Stop, the switch, the allowlist), or 7 days
without a refusal.

### 1.3 The outside gateway, past the heal

The mark is 108b §3.2's: an outside push within 7 days, and no push through our own gateway since. While it holds,
the phone asks the app once a day, when the phone is in use, to check the address again.

**The mark holding proves nothing on a quiet chat (H1).** A heal that worked leaves the mark until the next push. So
the row needs **an outside push that came a day or more after the run began**: the heal had its day, and the next
message still went outside.

**New column `up_registrations.outside_since`** (0154): when the current run of outside pushes began.
- It is written in `publish.rs`'s existing `outside_gateway_at` UPDATE (once a minute at most).
- It is set to now when no mark was standing, and kept otherwise: `CASE WHEN outside_since IS NULL OR
  outside_gateway_at IS NULL OR outside_gateway_at < datetime('now', '-7 days') OR (gateway_at IS NOT NULL AND
  gateway_at >= outside_gateway_at) THEN datetime('now') ELSE outside_since END`.
- The gateway's path writes nothing new: the mark's end is read from `gateway_at`.

**Raised for a registration when all of these hold:**
- it is marked (the same SQL as the page line and `refresh`);
- `outside_gateway_at >= outside_since + 24 h`: an outside push came a day or more into the run;
- the phone reported a day or more after the run began (`reported_at >= outside_since + 24 h`).

**What stays open:**
- A phone that reports but isn't used for a day gets no heal, and the row may show. It is still true: a third party
  was in the path of that push. It sees the event and room ids and the unread count.
- **A homeserver that keeps two pushers** flips the mark with each push and restarts `outside_since`, so it never gets
  a row. The page's line comes and goes with each push. This is accepted, and `unified-push.md`'s paragraph says so,
  so no later reader takes it for covered (L5).

**Clears:** a push through our gateway, 7 days without an outside push (the mark fades), or the registration deleted.

### 1.4 Not on Attention

- **"No delivery in 7 days"**: a quiet week is normal.
- **"Many pushes"**: a noisy app is the app's nature, not a fault, and the page says what it costs.
- **`time`/`address` refusals**: possibly our own clock or origin. The page's line says so, and DEPLOY.md's clock note
  covers it.
- **An app without a registration ("isn't using Vibb")**: decided, Q2.

---

## 2. Words

Titles: at most 8 words, from typed values only (no app names: an installed app's label is phone-supplied text, and
the target names each app). Keys are `device.attention.*`, as the rest.

| Row (`data-attention`) | en | nb |
|---|---|---|
| `app-push-waiting` | App notifications aren't reaching the phone | Appvarsler kommer ikke fram til telefonen |
| `app-push-refused` one / other | {count} app's notifications are refused / {count} apps' notifications are refused (shipped: the catalog test needs `{count}` in both forms) | Varsler til {count} app blir avvist / Varsler til {count} apper blir avvist |
| `app-push-outside` one / other | {count} app's notifications go via an outside service / {count} apps' notifications go via an outside service | Varsler til {count} app går via en ekstern tjeneste / Varsler til {count} apper går via en ekstern tjeneste |

The count is apps (packages), not registrations: Element X with two accounts is one app.

**On Device › Apps' App push group**, so the target explains itself:
- **Waiting:** a group-level warning line under the switch (`AppPushGroup.waiting`), shown exactly when the row is
  raised (L8):
  - en: "Notifications for the phone's apps have waited on the server since {when}, although the phone has been in
    touch since. If it stays, restart the phone."
  - nb: "Varsler til appene på telefonen har ventet på serveren siden {when}, selv om telefonen har vært i kontakt
    etterpå. Blir det stående, start telefonen på nytt."
- **Refused:** while the row is raised for a registration of the app, its line is worded **from that registration's
  stamp** (`unsigned`), not the package's newest stamp. Then the sentence below can't land on a `time` line (L4):
  - en: "Pushes not signed by the app's server were refused, last {when}. This has gone on for over a day. Opening
    {app} on the phone once usually fixes it; if not, Stop… and Allow Again gives it a new address."
  - nb: "Push som ikke var signert av appens server, ble avvist, sist {when}. Dette har vart i over et døgn. Å åpne
    {app} på telefonen én gang fikser det som regel; hvis ikke, gir Stopp… og Tillat igjen appen en ny adresse."
  - Stop deletes the registration and its secret is tombstoned. The app registers anew at its next start, and its
    server must subscribe again: the same fix for a stale key and a leaked endpoint.
- **Outside:** the existing line already says "If this stays for days, the phone can't reach the address: see “Last
  seen from outside”". Nothing is added.

No save on the page changes, so nothing can jump.

---

## 3. Code (server)

- **`unified_push::attention(state, device_id, latest_reported_at, policy_problem, now) -> AppPushAttention {
  waiting_since: Option<DateTime>, refused: Vec<(String, String)>, outside: Vec<String> }`**:
  - `refused` holds a package and its raised registration's stamp; `outside` holds packages;
  - one grouped query over `up_registrations` for §1.2 and §1.3, plus §1.1's reads;
  - Device › Apps' `group` calls it too, so the row and the page agree.
- **`attention.rs`:**
  - three `Kind`s: `AppPushWaiting`, `AppPushRefused(usize)`, `AppPushOutside(usize)`;
  - `Facts.app_push`, and `Sources.app_push` (the summary passes what it read);
  - `read_from_report` is false for all three: the server's own data;
  - `facts()` reads it after the music parts.
- **`UnifiedPush.policy_served`** (§1.1), noted by `device_api::policy` on a 200 or 304.
- **`store::pull`** takes the phone's current stream generation (`device::messages` reads
  `command_streams.state(id)`) for §7. Its hand-out UPDATE writes `fetched_ms`, `handed_ms` and §7's `stream_ms`
  rule.
- **Migration 0154** (0153 is pushed and frozen):
  - `up_registrations.vapid_refused_since` and `outside_since`: TEXT, nullable, no backfill. A run that began before
    the upgrade starts counting at its next stamp;
  - `up_messages.stream_ms`, `fetched_ms` and `handed_ms`: INTEGER, nullable;
  - §7's `up_delivery` table.
- **Words:** `device.attention.app_push_*`, `app_push.waiting`, `app_push.refused_fix` and §7's `app_push.delivery.*`,
  in `en.json` and `nb.json`.
- **Notes:** `docs/notes/server/unified-push.md` gains an "Attention and delivery time (design 108c)" paragraph, with
  L5's open case. `pwa.md`'s Attention bullet names the three rows.

## 4. Tests (lean)

- **`attention::tests`:** the three kinds' place (after `NotificationsOff`), level, target, key, and the nb/en
  one/other titles. This extends the existing table rows.
- **`tests::unified_push::attention`:** one integration test. Its stamps are backdated by SQL, and every column is
  written through its real path (`note_refusal`, `enqueue`, publish, `pull`, the policy GET).
  - **waiting (a):**
    - raised with three served policies in the window;
    - not with two;
    - not with three that all fall inside the first 30 minutes (L9);
    - not with a not-`ok` report in the window;
    - not after the ack.
  - **waiting (b):**
    - raised by a second hand-out 3 minutes after the first;
    - not by one 30 s after it.
  - **refused:**
    - an `unsigned` run of 24 h, with a report a day in, raises;
    - none of these raise: a single refusal; a `time` run; a run without a report a day in (L9); an `unsigned` run
      broken by a `time` stamp (L2);
    - a run is ended by an accepted push, by the key update through the register retry (L9), and by a TTL-0 `Dropped`
      (L3);
    - after 7 quiet days the next refusal starts a new run (L9).
  - **outside:**
    - an outside push 25 h into the run raises;
    - a run whose only outside push was in its first hour doesn't, even 3 days later (H1);
    - an outside push, then a push through our gateway, then an outside push restarts `outside_since` (L9).
  - **one count:** the Devices list's badge and the summary's group count the same rows. Device › Apps shows the
    waiting line and the refused sentence, the latter only on the `unsigned` line (L4).

## 5. Size

**Server S**, about 450 lines with the tests:
- the Attention rows (§1-§4), about 280:
  - the query and rules, ~100;
  - `attention.rs`, ~40;
  - the page lines, ~30;
  - the words;
  - the tests, ~110.
- the delivery time (§7), about 170:
  - the columns, the table and their writes, ~50;
  - the figure and its words, ~50;
  - the two page rows, ~20;
  - the tests, ~50.

No launcher, contract or gate work. It goes on `up-r2r3-server`, then gets its own code QA (XS).

## 6. Questions for the user: decided 2026-11-17, (a) and (a)

**Q1. Should these rows also push to the parent?** A push would need:
- a `parent_events` kind;
- a sweep, since the conditions are time-based;
- a dedup key per run;
- a `notification_prefs` switch.

Today only rows that are also events push (an app that may read the PIN, a start without Vibb One). **Decided: (a)
list only, the Attention row and the badge.** None of the three is urgent, and a push for a condition the heal often
fixes would be noise. It can be added later from the same rules.

**Q2. An app allowed and installed but not registered with Vibb ("isn't using Vibb"): on Attention?** Element X keeps
its Play build (the user's Q4, 2026-11-16), so it gets its notifications through Google. Nothing is lost. **Decided:
(a) no; it stays the line on Device › Apps.** As a row it would leave an orange badge that can't be dismissed for as
long as the parent keeps Google.

---

## 7. Delivery time on Settings › App push

Approved by the user on 2026-11-17: how long pushes take from this server to the phone, over the last 24 hours.

### 7.1 What is measured

From **the server accepting a push** to **the first pull that hands it to the phone** (M2). Both instants are the
server's own clock, so a phone whose clock is off changes nothing.
- **Accepted:** `up_messages.created_at_ms`, written by `store::enqueue`.
- **Handed out:** `up_messages.fetched_ms`, written by the pull's hand-out UPDATE (§1.1).

The sample is written **at the ack**, so only pushes the phone confirmed count. The ack's own delay is not in it: the
phone's 6-second flush (`pullLater`) and up to 5 s in the raise. So the figure is "delivered to the phone", and the
words say so.

If a pull's answer is lost on the way, the phone gets the push at a later pull, and the sample is shorter than the
truth by the time between those pulls. That is rare, and accepted.

**Only pushes handed out on the stream they were accepted on (H2).** The stream registry's `StreamState.since` moves
whenever a stream opens, and when the last one closes. So `since` (in ms) while `open > 0` is a **generation**:
- At accept, `unified_push::accept` already reads `command_streams.state(device_id)` for the TTL-0 rule. It stores the
  generation as `stream_ms`, or NULL when no stream is open.
- At the first hand-out, the pull passes the current generation. The UPDATE keeps `stream_ms` only when it equals that
  generation: `stream_ms = CASE WHEN fetched_ms IS NULL THEN (CASE WHEN stream_ms = ?gen THEN stream_ms END) ELSE
  stream_ms END`.

A phone that vanished without a FIN (Flymodus, a flat battery, a tunnel) comes back on a new stream, or its dead one
has been dropped. Either way the generation differs and there is no sample: the overnight wait never enters the figure.
A phone back within the proxy's dead-peer window, on the same stream, keeps its sample. That sample is minutes, and
real.

What is not in the figure:
- a push accepted or handed out without that same stream;
- a push that expired undelivered;
- a push replaced by a newer one with the same `Topic`.

### 7.2 Where the samples are kept

The queue row is deleted at the ack, so the sample needs a store of its own, in **0154**:

```sql
CREATE TABLE up_delivery (
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    acked_ms INTEGER NOT NULL,
    ms INTEGER NOT NULL                 -- fetched - accepted, at least 0
);
CREATE INDEX up_delivery_at ON up_delivery (acked_ms);
CREATE INDEX up_delivery_device ON up_delivery (device_id, acked_ms);
```

- **The write**, in `pull`'s ack transaction before the `DELETE`: `INSERT INTO up_delivery (device_id, acked_ms, ms)
  SELECT device_id, ?now, MAX(0, fetched_ms - created_at_ms) FROM up_messages WHERE device_id = ? AND stream_ms IS NOT
  NULL AND fetched_ms IS NOT NULL AND key IN (…)`.
  - Only a key still queued gives a sample, so an ack sent twice counts once.
  - A clock stepped back gives 0.
  - A row queued before the upgrade has no `stream_ms` and gives no sample.
- **Nothing per message beyond the three numbers**: no key, registration, package or body.
- **Pruned hourly** in `store::prune`: rows older than 24 h, and beyond the newest **5,000 per phone** (L7). The cap is
  per phone, so a busy phone can't evict a quiet one's samples. A phone at its push bucket can exceed the cap in a day;
  its words then say "the newest" (§7.4).
- **It survives a restart**, since it is SQLite. The stream registry starts empty after a restart, so pushes accepted
  before the phones reconnect have no generation and are left out.

### 7.3 The figure

A pure `delivery_figure(ms_sorted, capped) -> Option<Figure { count, median_ms, p95_ms: Option<i64>, capped }>` works
on the window's samples (`acked_ms > now - 24 h`, read sorted; at most 5,000 per phone).
- **Both values are nearest-rank**, so each is a time that really happened. The median is element `ceil(0.5 n)` and
  the 95th percentile is element `ceil(0.95 n)` (1-based).
- **With fewer than 20 samples there is no 95%** (M3): the median and the count only. Below 20, the nearest-rank 95% is
  the slowest push, and that is not a percentile.
- **With no samples**, the group says so.

`duration_words(lang, ms)` **floors** each unit (L8), so 59.6 s reads "59 s":
- under 1 s: "under a second" / "under ett sekund";
- under 1 min: "{n} s";
- under 1 h: "{n} min";
- otherwise: "{h} h {m} min" / "{h} t {m} min".

### 7.4 Words and places

(Words changed by designs 112 and 113: Settings › App push has one "Delivery" / "Levering" group, "Last seen from
outside" and "Usually delivered in" with the 95th percentile and the count under it; Device › Apps' line has no 95th
percentile.)

**Settings › App push** gets a new group, "Delivery to the phones" / "Levering til telefonene", under "Last seen from
outside" (`#delivery`). Its rows have no save, so nothing can jump. The rows (L8):
- "Usually" / "Vanligvis": the median, for example "2 s" or "under a second";
- "95% within" / "95 % innen", from 20 pushes only: for example "9 s";
- "Pushes" / "Antall push": "38 in the last 24 hours" / "38 siste døgn". When a phone's cap cut, it reads "the newest
  5,000" / "de nyeste 5 000".
- With none, one row: "No pushes delivered while connected in the last 24 hours" / "Ingen push levert mens telefonen
  var tilkoblet, siste døgn".

The footer:
- en: "From this server receiving a push to the push being delivered to the phone, for every phone together. Only pushes
  that came while the phone was connected are counted."
- nb: "Fra serveren tar imot et push til det er levert til telefonen, for alle telefonene samlet. Bare push som kom
  mens telefonen var tilkoblet, er talt med."

**Device › Apps' App push group, per phone.** It is cheap: the same function over the `device_id` index. It is one
line under the switch, only when the phone has samples, with one/other forms (L8):
- From 20 pushes:
  - en: "Usually delivered in {median}, 95% within {p95} ({count} pushes while connected, last 24 hours)";
  - nb: "Vanligvis levert på {median}, 95 % innen {p95} ({count} push mens den var tilkoblet, siste døgn)".
- Below 20:
  - en: "Usually delivered in {median} ({count} push / pushes while connected, last 24 hours)";
  - nb: "Vanligvis levert på {median} ({count} push mens den var tilkoblet, siste døgn)".

### 7.5 Tests (lean)

- **`unified_push::store::tests`:**
  - pushes enqueued with a generation, one without, and one whose stream reopened before the pull (H2);
  - one pull hands them all out and a second acks them all, then the same keys are acked again;
  - this gives samples only for the same-stream rows, each `fetched - accepted` (not the ack's time) and at least 0;
  - a sample backdated past 24 h is pruned, and a phone's 5,001st is cut.
- **`tests::unified_push`:** a push through `/up/` with a stream open in the registry, and one without, gives
  `stream_ms` set and NULL. This checks that `accept` reads the registry.
- **A pure table:**
  - `delivery_figure` with 0, 1, 19, 20 and 40 samples: no 95% below 20, both nearest ranks from 20, `capped`;
  - `duration_words` in nb and en at each boundary (999 ms, 1 s, 59.6 s, 60 s, 59 min, 1 h).
- **One page test:**
  - Settings › App push shows the group with seeded samples, and "No pushes…" without them;
  - Device › Apps shows the phone's line only for the phone with samples, in its below-20 form.

---

## 8. QA handled (`docs/review/qa-108c-design.md`)

| Finding | Fix |
|---|---|
| H1 the outside row for a healed, quiet app | §1.3: raised only by an outside push a day or more into the run (`outside_gateway_at >= outside_since + 24 h`) |
| H2 a dead stream counted as connected | §7.1: the stream generation (`StreamState.since`) stored at accept, and kept only if it is the same at the first hand-out |
| M1 reports that didn't pull | §1.1: arm (a) counts policies the server served (in memory, three, no not-`ok` report in the window); arm (b) counts the server's own hand-outs (`fetched_ms`, `handed_ms`). Taken over a report count: what the server saw is the evidence |
| M2 the ack flush in the figure | §7.1: `fetched_ms - created_at_ms`, written at the ack; worded "delivered to the phone" |
| M3 the p95 of few samples | §7.3: below 20, the median and the count only |
| L1 `last_seen_at` | §1: the latest report's `reported_at` |
| L2 the CASE across `time` stamps | §1.2: the run restarts after a non-`unsigned` stamp; the rule is in the SQL on the bound `why` |
| L3 a verified TTL-0 push | §1.2: `Dropped` clears `vapid_refused_since` |
| L4 the sentence on the wrong line | §2: the line is worded from the raised registration's `unsigned` stamp |
| L5 the two-pusher flip | §1.3: stated as accepted; the notes say so |
| L6 the reads | §1.1: the `LIMIT 1` first; the rest only past 30 minutes, with text bounds |
| L7 the cap and "24 hours" | §7.2: 5,000 per phone, and "the newest" when it cut |
| L8 the words | §2, §7.3, §7.4: floored durations, one/other, "95%" in English, "Usually"/"Vanligvis", "Antall push", "If it stays, restart the phone" |
| L9 the tests | §4, §7.5: the 30-minute floor, the key update, the report gate, the 7-day reset, the outside restart, H1's and H2's rows |
