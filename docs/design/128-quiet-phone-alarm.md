# Design 128: A phone that has gone quiet

Status: design, revision 1, 2026-11-24 (branch `silent-alarm`, from main 1f1802f43). Server only. Approved in
principle by the owner on 2026-11-23: a clear alarm to the parent when a phone has gone quiet in a way that suggests
it may have been reset to factory settings. **Not built: three choices with a cost the parent sees wait for the owner
(§6).** The rest follows from them.

This reverses one line of design 35 (§10, decision 4, 2026-10-18: "no event for a silent phone"). Design 38 kept that
("never from a server-side silence timer"); this note adds one, for one reason only, and says so in its words.

## 0. Short answer

- A phone that is set up and has said nothing to the server for **24 hours** (§6, Q1) gets one orange Attention row,
  "Silent for over a day", and one push. Nothing more until it is heard from again; then the row goes by itself.
- The server can't tell a reset phone from one that is off, flat, broken or out of coverage: a reset phone never
  talks to the server again, so silence is the only sign there is. The (i) says so plainly.
- No launcher change, no new report field. One migration (a push switch).

## 1. What exists (read in the code at 1f1802f43)

- **Connection state** (`streams::connection_state`, `devices::connection_of`; `docs/notes/server/pwa.md`, "The
  PWA's times, connection state and Attention"): last contact = the newest of `limits::last_access`,
  `devices.last_seen_at` and an open stream. Online up to 35 min, "No contact" (orange) up to 45 min, then "Offline ·
  last seen 13:05" (grey) for ever. Nothing is pushed on any of it (design 35 §2.3).
- **Turned off and on** (design 38, `docs/notes/server/notifications.md`): `phone_off` from Android's shutdown
  broadcast (with the battery and, when known, the reason: battery flat, too hot), `phone_on` at the next start, a
  start with no shutdown seen says the last report's battery. "Turned off 21:14 · battery was 3 %" on the Devices row
  and the header until a start or a report. A sudden power loss sends nothing.
- **A chosen quiet** (design 121 §4.3, `phone_status::Held`): Vibb's own airplane switch, or abroad with Roaming Av -
  grey, never "No contact", never pushed.
- **Unseen boots** (design 24b): a boot in which Vibb never ran, told at the next boot in which it does. A phone
  without Vibb never tells it.

None of these fires for a phone that never comes back. A reset phone is exactly that: Vibb, its token and its key are
gone, it can't enrol itself again, and the old device row just stops. A new enrolment happens only when the parent
scans the setup QR again - the parent already knows then - so "a new enrolment while the old row is silent" adds
nothing and is left out.

## 2. The rule

A phone is **quiet** when all of these hold:

1. It is set up and has a token (not "Not set up", "Not seen yet", "Disconnected" / access removed, not deleted).
2. Its quiet time - now minus the later of its last contact and the server's own start (§2.1) - is at least
   `QUIET_AFTER` = 24 h (Q1).
3. It is not in a chosen quiet the parent made (Q3): abroad with Roaming Av. Vibb's airplane switch does not count
   (Q3).

A clean "turned off" does not stop it (Q2): a reset from the recovery menu usually starts with an ordinary power-off,
so a rule that waited for "no shutdown seen" would miss the case it is for. The words still use what the off said
(§3).

### 2.1 The server's own downtime

If the server was down or unreachable, every phone's last contact is old when it starts again. The quiet time counts
from the later of the last contact and this process's start (`AppState` gets the start instant), so a server down
over a weekend never raises a row or a push for every phone. A phone quiet across a server restart is told 24 h after
the restart at the earliest - late, never false.

### 2.2 Where it is judged

- **The pages** judge it on read: pure `phone_quiet::quiet(connection, held, started_at, now) -> Option<Quiet {
  since }>` beside `phone_status::dot`. Attention, the Devices row and the summary read the same value, so they agree.
- **The push** needs a clock, since nothing arrives: a sweep every 10 min in an existing background loop (beside
  `retention::run_pruning`), one query over the set-up phones' last contact, then `parent_events::raise` for each
  quiet phone. Key `quiet:<last contact, RFC 3339 ms>`: one event per silence, a resend or a second server raises
  nothing; a phone heard from and quiet again later is a new silence and a new event.
- Contact again (a report, a stream, a power report) ends it: the row goes on the next read. No "back" push of its
  own - a start already pushes "turned on" (design 38), and a phone back in coverage shows green.

## 3. Words (nb and en)

Checked against CLAUDE.md "Words in the UI": one short orange line, the explanation behind the (i) alone.

| Where | en | nb |
|---|---|---|
| Attention title (orange, to the summary) | Silent for over a day | Stille i over et døgn |
| Devices row / header, first part (orange dot, replaces "Offline") | Silent since Mon 21:10 | Stille siden man. 21:10 |
| (i) behind the Attention row | A phone that stays silent may be switched off, flat, broken or without coverage - or reset to factory settings, which removes Vibb. Vibb can't tell these apart. Check the phone. | En mobil som er stille kan være slått av, tom for strøm, ødelagt eller uten dekning - eller tilbakestilt til fabrikkinnstillinger, som fjerner Vibb. Vibb kan ikke se forskjell. Sjekk mobilen. |
| Push title | Anna: silent for a day | Anna: stille i et døgn |
| Push body (last report's battery) | Last seen Mon 21:10 · battery 64 %. Check the phone. | Sist sett man. 21:10 · batteri 64 %. Sjekk mobilen. |
| Push body after a "battery flat" off, or a last battery <= 10 % not charging | Last seen Mon 21:10 · battery 3 %, probably flat. | Sist sett man. 21:10 · batteri 3 %, trolig tom. |
| Push body after another turned-off | Turned off Mon 21:10 · battery 64 %. Check the phone. | Slått av man. 21:10 · batteri 64 %. Sjekk mobilen. |
| Notify Me About switch | Phone silent | Mobil stille |
| Inbox row | as the push, "Anna has been silent since Mon 21:10" | "Anna har vært stille siden man. 21:10" |

The words never say "reset" on their own line: only the (i) names it, beside the ordinary causes. The probable cause
the server knows (flat) is said; nothing else is guessed (design 38 §1.6).

## 4. Push

- `Kind::PhoneQuiet` (`phone_quiet`), after `PhoneOn` in `Kind::ALL`; urgency normal, TTL 43200, foldable, like
  `phone_off`/`phone_on`; target `/devices/{id}`.
- A switch of its own, `notification_prefs.push_phone_quiet`, default **on**: every kind has one, and the owner asked
  for the alarm. Migration **0172** (0170 is on main; 0171 is taken by the unmerged `trusted-time` branch).
- One push per silence, no repeat. The hourly event budget (`EVENTS_AN_HOUR`) can't be reached by one event a phone.

## 5. What it can't tell

- A reset phone and a phone that is off, flat, broken, lost or without coverage look the same: silence. The (i) says
  so; the push says only "check the phone".
- It is late by design: 24 h (Q1) after the last contact. A reset is not undone by knowing sooner; the parent needs
  the phone in hand either way.
- A phone wiped while the server is down is told 24 h after the server's start.
- It does not stop a reset. Stopping one (Android's factory reset protection with a parent's account) is the separate
  part (a), not in this design.

## 6. Choices for the owner (each a cost the parent sees)

- **Q1. How long before it speaks.** Recommended **24 hours**: a phone switched off for the night (up to ~12 h) never
  raises it; a phone left off over a weekend or in a drawer on holiday does, once. 12 h would catch it sooner but
  alarm for a long night off; 48 h would be quieter and later. One constant, the same for every phone, no setting.
- **Q2. After a clean "turned off" too?** Recommended **yes**, with the off in the words ("Turned off Mon 21:10 ·
  battery 64 %") and "probably flat" after a flat battery. Without it, the common path to a reset (switch off, then the
  recovery menu) raises nothing. The cost: a phone deliberately switched off for more than a day gets the push.
- **Q3. A chosen quiet.** Recommended: **abroad with Roaming Av stays quiet** (the parent chose it; a holiday without
  data would alarm every time - "a choice the parent made is never a warning"), **Vibb's airplane switch does not**
  (the kid chooses it, and a day in airplane mode is itself worth a look). The cost: a kid who keeps airplane mode on
  for a day gets the alarm with the same words.

Everything else (the words, one push, the switch default on, no launcher change) follows from the approval and the
existing pattern.

## 7. Build (once Q1-Q3 are answered)

One step, server only:

1. `src/phone_quiet.rs`: `QUIET_AFTER`, the pure `quiet(..)`, the sweep; `AppState.started_at`.
2. `parent_events`: `Kind::PhoneQuiet`, its words (`events.phone_quiet.*` in `en.json`/`nb.json`), switch.
3. `attention::Kind::Quiet` (orange, to `/devices/{id}`); `phone_status::dot` orange with "Silent since ..." while
   quiet (replacing "Offline · last seen" for that phone).
4. Migration `0172_phone_quiet.sql`: `notification_prefs.push_phone_quiet INTEGER NOT NULL DEFAULT 1`.
5. Tests: `phone_quiet::tests` (23 h 59 / 24 h, the server's start, abroad, airplane, not set up, access removed, a
   clean off), `tests::phone_quiet` (the sweep raises once per silence, a second sweep and a new contact, the switch,
   the Attention row and the dot, nb words).
6. Notes: `docs/notes/server/notifications.md` (a section), `pwa.md` "Off or out of touch says so".

Checked on: server only; no Android version is involved.
