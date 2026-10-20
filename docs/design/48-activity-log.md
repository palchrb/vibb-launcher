# 48 - One Activity log on the phone's summary; Locate's Recent becomes Requests (proposal)

Asked 2026-10-20 (the summary after round 5: "Hvorfor recent og today, og så vises ikke den today saken på recent
heller? ... samle loggen på bunnen"; Locate's Recent: "Den expired location event burde jo forsvinne etterhvert? ...
Trenger også tidspunkt og ikke dato ... Usikker på om vi skal beholde den"). Server only. Builds on design 44 (`partials/recent.html`,
`locate::recent(kinds)`, the cancel's `back`), design 35 (the inbox rows, `parent_events`), design 24b
(`RelockCard.answers`, `handlers/window_relock.rs`), `display_time`.

## 0. Today

The summary (`device_detail.html`) has Quick actions (`#quick`), **Recent** (round 5: the override commands
`end_windows`/`start_pause`/`allow_blocked`) and **Today** (`#today`). Today holds the running lifts (today's pauses and
extra time) with **End now** (`lifts.rs`, `summary_group("lifts") = "today"`), `relock.answers`, and the PIN notices from
`device_pin_events` ("wrong tries", "Typed where your child could see?"); no `parent_events` are on the summary today.
Locate has its own Recent (`locate`, `ring`, `stop_ring`, `lock`, `wipe`), ten rows, no age limit, dates without the
clock past yesterday (`Zone::when`). `device_commands` are never pruned.

## 1. Decisions

1. **`#today` becomes "Now" (`#now`)** and keeps exactly the state items: the running lifts with End now and the PIN
   notices. `summary_group("lifts")` points at `now` (and its doc comment at `lifts.rs:52`, "are Today", follows), so End now's
   save comes back to `#now` with its flash.
   `relock.answers` is no longer rendered (the loop in `devices.rs:1629-1643` goes; `last_answers` stays, the blocks
   need it): the command rows carry the status words (Done / Failed / Waiting / Sent / Received), and there is no
   de-dup rule. The round-5 Recent goes.
2. **One group below Now, `#activity` "Activity"**: history only, newest first, the last 7 days, at most 10 rows, mixing:
   - a **command row** as `partials/recent.html` draws it (label, the answer as sub-line, the time, the status word,
     Cancel while it waits) - every kind of command of this phone;
   - an **event row** as the inbox draws it (title, sub-line, the time, the chevron to the event's target) -
     `parent_events` of this phone, built by the inbox's builder extracted from `page()`, without the avatar. Its `open`
     form (a POST that marks it read) carries hidden `from=devices` and `device=<id>` (`back_of` knows `devices`, not
     `device`), so a pruned event or a non-local target comes back to this phone's filtered inbox
     (`/notifications?from=devices&device=<id>`), as the inbox does today.
   Then a last row **"Everything for this phone ›"** to `/notifications?from=devices&device={id}`.
   Sort key: the row's own time - a command's `requested_at`, an event's `at`. A **cancellable** (in-flight) command
   shows at any age, outside the 7-day window and the 10-row cap. Successful Update Locations are ordinary rows here
   (the cap is the limit; Requests hides them).
3. **Every row carries the clock** through the existing `Zone::at`: "10:59", "yesterday 21:40", "12 Oct 08:12" (with the
   year past a year) - English, like the rest of the page; command and event rows both use it. The inbox and the devices
   list keep their relative words. The exact time stays in `title`. No new time function.
4. **Locate's Recent becomes "Requests"**, defined by `CommandStatus` (`models.rs:755-815`), one bucket formula:
   shown at any age = `Waiting`, `Sent{NotYet | OfferedAgain | CantConfirm}`, `Received{overdue: false}`, `Erasing`
   (with Cancel where the status allows); shown within the last 24 h = `Failed`, `Expired`, `Sent{Lost}`,
   `Received{overdue: true}`; plus the newest `ring`/`stop_ring`/`lock`/`wipe` whatever its status; never
   `Cancelled{Parent}`; a Done locate is never a row.
   `partials/recent.html` takes a parameter for its title ("Requests" on Locate, "Activity" on the summary), so Locate
   keeps its own wording. Requests and Activity are shown when they have rows **or** a flash (the Cancel flash), as the
   summary already does, so a Cancel never lands under a hidden group.
5. **The Cancel's redirect**: the summary's Cancel comes back to `#activity` (the partial's form action carries it);
   Locate's stays `#recent`, and `device_locate.html:116` is reworded to "Requests".
6. **Retention**: `retention::prune` deletes `device_commands` older than 30 days by `requested_at` with the pop's
   undelivered rule (`models.rs:581-587`) spelled in the SQL: `DELETE … WHERE requested_at < ? AND (acknowledged_at IS
   NOT NULL OR cancelled_at IS NOT NULL OR delivered_at IS NOT NULL OR command IN (<EXPIRING_COMMANDS>))` - so an
   undelivered, uncancelled `lock`, `wipe`, `end_windows`, `allow_blocked` or `install_update` is kept at any age, and a
   cancelled or acknowledged one of those is prunable like the rest; counted in `Pruned` (the literal in
   `tests/cleanup.rs` gains the field). The Activity log's 7-day window is a query filter, not retention.
7. **One query per page**: `activity::rows(state, device_id, zone, now)` reads both tables, merges by the sort key, cuts
   at 10 (plus the cancellable ones); the template gets a `Vec<ActivityRow>` enum (`Command(CommandRow)`,
   `Event(InboxRow)`). No new table, no migration.
8. The Locations page, the inbox and Getting Notifications are unchanged.

## 2. Tests (required)

Summary: a command and an event interleave newest first; the cap at 10 and the 7-day window; an 8-day-old waiting
`end_windows` still shows Cancel; the Cancel lands on `/devices/{id}#activity`; an event's `open` form carries
`from=device` and `device`, and a pruned event comes back to this phone's filtered inbox; End now lands on `#now` with its flash; the PIN
notices are in Now; no `relock.answers` line; the clock words ("13:41", "yesterday 14:50", "15 Oct 14:02"). Locate: one
test per `CommandStatus` bucket of §1.4 (in flight at any age, Failed/Expired/No answer within 24 h and not after, the
newest Done of Ring/Stop Ringing/Lock/Erase, a Done locate never); cancelling the last waiting row shows the flash in
the group. Retention: a 31-day-old undelivered wipe and lock are kept, a 31-day-old expired locate and a done ring are
pruned, `Pruned.commands` counts them. `check_forms` on the summary (`tests/settings_pages.rs`) and Locate
(`tests/locate_page.rs`).

## 3. Device check

iPhone: the summary reads top-down (quick actions, Now, then Activity); after Unlock for 2 h the row appears with the
clock and Done; a PIN event sits between command rows in time order; End now and Cancel never jump; Locate shows
Requests only while something is in flight or just failed.

## Revision (2026-10-20, after qa-r6-server-design.md)

- 48-1: §0 corrected; `#today` becomes "Now" (`#now`) with the lifts and PIN notices; `summary_group("lifts")` = `now`.
- 48-2: `relock.answers` no longer rendered, loop removed, no de-dup rule.
- 48-3: `Zone::at` for command rows, English; §0 names `Zone::when`; no Norwegian examples.
- 48-4: Requests defined by `CommandStatus` buckets.
- 48-5: Requests and Activity shown on rows or a flash.
- 48-6: retention in SQL as the pop's undelivered rule, counted in `Pruned`.
- 48-7: cancellable commands show at any age outside window and cap.
- 48-8: summary Cancel to `#activity`; Locate's wording via a partial parameter.
- 48-9: sort key is the row's own time; `Zone::at` words for both kinds.
- 48-10: event `open` form carries `from=device` and `device=<id>`; builder extracted; no avatar.
- 48-11: Update Location rows are ordinary rows in Activity (the cap is the limit).

## Implementation status (2026-10-20, branch r7-a)

Implemented as revised: `handlers/activity.rs` (`activity::rows`, `ActivityRow`), `locate::requests` and
`in_requests` (the §1.4 buckets; "newest" = the newest row of each of ring/stop_ring/lock/wipe, which counts whatever
its status except `Cancelled{Parent}`), `notifications::inbox_row` (extracted; `ROW_COLUMNS`), `partials/recent.html`
(`row` and `show(recent, title)`), Now/Activity in `device_detail.html`, `retention::prune`'s commands
(`COMMAND_DAYS`, `Pruned.commands`). `RelockCard.answers` and `window_relock::{answer, Answer, AnswerState}` are
deleted. Every command row's time is `Zone::at` (Locate's and Install Now's too). Choices: Activity sits after
Blocked Apps and Locked Again, right before the Settings rows ("the log at the bottom"; still below Now); a
Restart/Enrolled/Replaced cancel that isn't the newest of its kind is no Request (no bucket holds it) and is read in
Activity. Event rows show no unread dot and no delivery line. Device check (§3) owed.
