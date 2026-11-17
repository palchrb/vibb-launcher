# Design QA: design 108c, app push problems on Attention and the delivery time (`docs/design/108c-up-attention.md`)

Reviewed on `up-r2r3-server` at a06c87f80, 2026-11-17. Read: design 108c in full; design 108b §2-§4 and §6.2;
`server/CLAUDE.md` (Attention, migrations); `docs/notes/server/pwa.md` "The PWA's times, connection state and
Attention"; the server code `src/unified_push/{mod,store,publish,matrix,device,notify,rate}.rs`,
`src/handlers/app_push.rs`, `src/attention.rs` (`items`, `facts`, `facts_of`), `src/streams.rs`, the status intake in
`handlers/device_api.rs` and `retention.rs`'s status pruning; migrations 0152/0153. On the launcher side, read as the
behaviour the rows rest on: `MdmSyncWorker` (the policy GET, `UpRuntime.policyAccepted`, the report, `postReportOnly`),
`PolicyGate.policyState`, `UpRuntime.requestPull`/`pullLater`, `UpEngine.step`/`sent`, `UpDelivery.plan`,
`RaiseToForeground.send`.

The user's bar: a row only for a real fault, never for a quiet app. §1.1 and §1.2 are built on evidence (a push still
queued after syncs; refusals still coming a day later) and mostly meet it. §1.3 is built on the absence of a push and
breaks it (H1). The delivery figure's "connected" flag trusts a stream count the PWA itself no longer trusts alone
(H2).

**Counts: 2 High, 3 Medium, 9 Low.**

---

## High

### H1. §1.3 raises the outside row for a healed app that is quiet for two days

**Failure.** The mark ends only on a push through our gateway. Day 0: Element X's discovery failed once, an outside
push stamps `outside_gateway_at` and starts `outside_since`. That evening the phone is in use, the heal sends
`NEW_ENDPOINT`, Element X's discovery succeeds and the homeserver's pusher now points at our gateway: fixed. The kid's
chat is then silent for two days (the user's own example of normal). No push comes through our gateway, so the mark
holds; `outside_since <= now - 48 h`, the phone reported a day after it began: the row "An app's notifications go via
an outside service" shows, for an app that is fine and quiet. This is exactly the false positive the user asked to
avoid. The design's own wording names the gap: "the heal has had two days and the mark still holds" - the mark holding
says nothing when nothing was pushed.

**Fix.** Make §1.3 evidence-based, as §1.2 is: raise only when **outside pushes still arrive a day or more after the run
began** - `outside_gateway_at >= outside_since + 24 h` (and marked, and `last_seen` as now), in place of
`outside_since <= now - 48 h`. A healed app that goes quiet never raises; a failed heal raises at the next message that
comes outside, which is when it matters. Test it: a run of 3 days with no outside push after the first hour doesn't
raise; one with an outside push 25 h in does.

### H2. §7's `connected` counts a dead stream, and with few samples the 95th percentile is that one push

**Failure.** `connected` is `command_streams.state(id).open > 0` at accept. `streams.rs` says it itself: a phone that
vanishes without a FIN (Flymodus - design 98 turns the radio off at bedtime -, a dead battery, a tunnel, a lift) "still
counts until this server's TCP gives up on the peer - so Online also needs a recent contact". Behind Caddy or tsnet the
server's socket is to the proxy, which notices the phone only after its own retransmission timeout (Linux defaults: on
the order of 15 min). Every push in that window is sampled with the time until the phone comes back: hours, overnight.
Per phone a kid gets few pushes a day, and §7.3's nearest rank makes p95 the maximum below 20 samples. One Matrix
message just after Flymodus at 21:00 makes Device › Apps say "95 % within 10 h 12 min" all next day - the overnight
figure the revision set out to remove, and on a page the parent reads as a fault.

**Fix.** Sample only pushes delivered **on the stream they were accepted on**. Store the stream's generation at accept
(`up_messages.stream_since_ms` = `StreamState.since` in ms, or `NULL` when not open; it replaces the 0/1 flag) and pass
the current `StreamState` from `device::messages` into `pull`; write a sample only for a key whose stored generation
equals the current one with `open > 0`. A phone that vanished reopens a new stream when it returns (`open()` moves
`since`), or its old stream has been dropped (`open == 0`): no sample. A phone back within the dead-peer window keeps
its sample, which is then minutes, and real. Test: accept with a stream open, reopen the stream, ack: no sample.

---

## Medium

### M1. §1.1 counts reports that did not pull

**Failure.** The premise "each report follows a pull" does not hold for every report:
- `policyState(UNREACHABLE, …)` is `"ok"` (`PolicyGate.kt:291`): a sync whose policy GET timed out skips
  `UpRuntime.policyAccepted` (no pull) but still posts its report with `policy_state = "ok"`;
- `postReportOnly` (Flymodus turning on, design 98) posts a report with no policy GET and no pull, also `"ok"`;
- a report with `policy_state` `server_error`/`rejected_suspect` didn't pull either, yet counts; only the *latest*
  report is checked (`policy_problem`). After a policy outage ends, the first `ok` report is under 2 min old, the two
  failed ones before it count, and the row flashes until the pull acks.

With the stream down (weak mobile signal is when both happen), two such reports after `C + 10 min` raise the row for a
phone whose UP works.

**Fix.** Count only `policy_state = 'ok'` reports (one more term in the `COUNT`, same index), and ask for **three**
reports in the window rather than two (about an hour at the 30-min backstop; nothing here is urgent, Q1). Alternative,
if the floor should stay at two: a server-side pull stamp per phone (in `UnifiedPush`, in memory, or a column written at
most once a minute), and require a pull in `[C + 10 min, now - 2 min]` - since a pull answers oldest first, the oldest
push was then fetched and not acknowledged, which is the fault itself.

### M2. §7 measures the phone's 6-second ack flush, not delivery

**Failure.** The ack rides the next pull: after `sent`, `pullLater(ACK_FLUSH_MS = 6000)`, and a raised delivery adds up
to 5 s before the send. So almost every sample is 6-11 s while the app had the message in about a second. The median
will read "7 s" for every healthy phone: it shows a launcher constant, and Device › Apps' "Delivered in 7 s as a rule"
is not true.

**Fix.** Either measure the server's leg directly - stamp `up_messages.fetched_ms` when a pull first answers the row
(`UPDATE … SET fetched_ms = ? WHERE id IN (answered) AND fetched_ms IS NULL`, one write per pull that returns messages)
and sample `fetched_ms - created_at_ms` at the ack, so only delivered pushes count - or keep the ack and word it as
what it is ("confirmed by the phone within", not "delivered in"). The first is what the user asked for ("from this
server to the phone").

### M3. The per-phone p95 below 20 samples is the maximum, worded as a percentile

**Failure.** With 1-19 samples "95 % within {p95}" is the slowest push. Even after H2, one slow push (a pull retried
after a network blip, a process killed before its ack and re-fetched) sets the line for the day. "95 % within 4 min"
from 6 pushes claims a distribution that isn't there; the count in brackets doesn't undo it for a parent.

**Fix.** Below 20 samples show the median and the count only (or name it plainly: "slowest {max}"), and the 95 % from
20. The pure table already tests 19 and 20, so the rule is one branch and the same rows.

---

## Low

- **L1. `last_seen_at` as "the phone reported" (§1.2, §1.3).** Today only the status intake writes it, so it holds. Design
  91 (`POST /api/devices/locked-report`) bumps `last_seen_at` without a `device_status` row, from a phone before its
  first unlock, where UP never pulls or heals: once 91 lands, a phone left locked after a restart for a day would meet
  the "in touch" condition. Use the latest report's `reported_at` (already read as `latest` in `facts`/`facts_of`).
- **L2. §1.2's CASE across `time` refusals.** The 7-day reset reads the previous `vapid_refused_at`, which may be a
  `time` or `address` stamp. An `unsigned` run 6 days old, then days of `time` refusals, then one `unsigned`: the old
  `since` is kept and the row raises at once. Reset `since` also when the previous `vapid_refused_why` is not
  `unsigned`, and gate the CASE on the bound `why` in the SQL (not in Rust), so the one UPDATE stays one statement.
- **L3. A verified TTL-0 push doesn't end the run.** `accept` returns `Dropped` without `enqueue` while the stream is
  closed, so `vapid_refused_since` isn't cleared. Mastodon sends 48 h, so this is theory today; clear `since` on the
  Dropped path too, or state it.
- **L4. The refused sentence can land on the wrong line.** The page's line takes the newest `stamp|why` per package
  across registrations (`MAX(…)`); with two registrations (Fedilab, two accounts), the run's "This has gone on for over
  a day" can be appended to a `time` line ("our clock"). Append it only when the line shown is the `unsigned` one, or
  word the line from the raised registration's stamp.
- **L5. The two-pusher flip (§1.3 "What stays open").** It leaves a third party in the path of every push with no row and
  a flickering page line. Accepted as written, but say so in `unified-push.md`'s paragraph, so a later reader doesn't
  take it for covered.
- **L6. §1.1's reads.** `created_at_ms` is epoch ms and `reported_at` is `datetime('now')` text: bind the window's bounds
  as text formatted in Rust (`%Y-%m-%d %H:%M:%S`) so the `(device_id, reported_at)` index range is used, and run the
  `COUNT` only when the oldest live push is 30 min old (the Devices list renders often; most phones then pay one indexed
  `LIMIT 1`).
- **L7. The 20,000 cap and "last 24 hours".** One phone at its bucket (0.2/s + 120) can make about 17,400 samples a day;
  two such phones push the cap below 24 h while the words say "in the last 24 hours", and the busiest phone evicts a
  quiet phone's samples. Cap per phone (say 5,000 per `device_id`) or say "the newest" when the cap cut.
- **L8. Words.** `duration_words` must say floor or round: with round, 59.6 s reads "60 s" (floor, so 59 s). The en
  per-phone line needs one/other ("1 push"). English writes "95%" without the space (nb "95 %" is right). Settings'
  row "Median" and Device › Apps' "as a rule" name the same number twice over: "Usually" / "Vanligvis" on both is
  plainer for a parent. nb "Push talt med" reads stiff; "Antall push" or "Push med i tallene". The waiting line's
  "Restarting the phone usually gets them going again" promises more than the store hold allows; "If it stays, restart
  the phone" / "Blir det stående, start telefonen på nytt".
- **L9. Tests that the thresholds need (still lean).** §1.1: two reports both inside the first 30 min don't raise (the
  30-min floor is untested as listed). §1.2: the key update through the register retry clears the run; the
  `last_seen` gate negative; the 7-day reset. §1.3: outside (backdated), a gateway push, then an outside push restarts
  `outside_since` (the CASE's hard branch), plus H1's two rows. §7: H2's reopened-stream row. Each is a few lines in
  the integration tests already planned.

---

## Checked and holding

- **Server restart:** §1.1-§1.3 read only SQLite; §7 drops samples for pushes accepted before the phones reconnect.
- **Clock skew:** every instant §1 and §7 compare is the server's (`created_at_ms`, `reported_at`, the `datetime('now')`
  stamps); the phone's clock never enters.
- **UP off, an app stopped, the address cleared, a registration deleted, the 30-day unseen prune:** every path deletes
  through `delete_registrations`/`delete_everywhere`, and `up_messages` cascades (foreign keys on), so no row outlives
  its registration or queue.
- **Doze, heartbeats, Leggetid:** a heartbeat is a full `device_status` row; the pull is posted with a timed wake lock
  in the same maintenance window; suspended apps still get the broadcast and ack (`UpDelivery.plan`), hidden and gone
  apps are dropped and acked; the 2-min margin covers the async pull and the 6 s flush.
- **TTL:** TTL 0 lives 30 s and never raises; a stuck queue re-raises with the next push after expiry; Matrix pushes
  live the full day.
- **A phone before its first unlock** makes no `device_status` row (the design's claim; `requestPull` is a no-op there
  too; the sync's direct-boot behaviour not re-read here), so §1.1 can't raise there.
- **Migration 0154:** `ADD COLUMN … NOT NULL DEFAULT 0` is valid in SQLite; `up_delivery`'s `ON DELETE CASCADE` has its
  `device_id`-first index; no other branch holds 0154 (checked the local branches).
- **Attention plumbing:** `read_from_report = false` for all three is right (server data); the place after
  `NotificationsOff` sits before the calls rows in `items`; the list and the summary share `items`. Titles are typed
  and app-name free, under 8 words.
- **Query cost on the Devices list:** one grouped indexed read of `up_registrations`, one `LIMIT 1` on `up_messages`, one
  indexed `COUNT` (L6): fine at 1-5 phones. The figure is never read by the list.
