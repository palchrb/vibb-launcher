# 48 - One Activity log on the phone's summary; Locate's Recent becomes Requests (proposal)

Asked 2026-10-20 (the summary after round 5: "Hvorfor recent og today, og så vises ikke den today saken på recent
heller? ... samle loggen på bunnen"; Locate's Recent: "Den expired location event burde jo forsvinne etterhvert? ...
Trenger også tidspunkt og ikke dato ... Usikker på om vi skal beholde den"). Server only. Builds on design 44
(`partials/recent.html`, `locate::recent(kinds)`, the cancel's `back`), design 35 (the inbox rows, `parent_events`),
design 24b (`RelockCard.answers`, `handlers/window_relock.rs`), `display_time`.

## 0. Today

The summary (`device_detail.html`) has Quick actions (`#quick`), **Recent** (round 5: the override commands
`end_windows`/`start_pause`/`allow_blocked`, minus the rows Today's card already shows) and **Today** (`#today`: the
phone's events of the day plus `relock.answers`). Locate has its own Recent (`locate`, `ring`, `stop_ring`, `lock`,
`wipe`), ten rows, no age limit, dates without the clock past yesterday (`display_time::relative`). `device_commands`
are never pruned.

## 1. Decisions

1. **One group at the bottom of the summary, `#activity` "Activity"**, newest first, the last 7 days, at most 10
   rows, mixing two row kinds:
   - a **command row** as `partials/recent.html` draws it (label, the answer as sub-line, the time, the status word,
     Cancel while it waits) - every kind of command of this phone (the override kinds and Locate's);
   - an **event row** as the inbox draws it (title, sub-line, the time, the chevron to the event's target; `open`
     marks it read as the inbox's tap does) - `parent_events` of this phone.
   Then a last row **"Everything for this phone ›"** to `/notifications?device={id}`. Today's card
   (`#today`) and the round-5 Recent go; `relock.answers` (Today's answers to the quick actions) are not shown twice:
   a command row that is the newest of its kind within the card's window is the one shown, and the card's answer
   line for it is dropped (the command row carries the same words).
2. **Every command row carries the clock**: `display_time::day_and_time` (new): "i dag 13:41", "i går 14:50",
   "15. okt 14:02" (older than a year: with the year), in the page's zone; `relative` stays for the inbox's event
   rows and the devices list (they say "4 h ago", which is right there). The exact time stays in `title`.
3. **Locate's Recent becomes "Requests"**: only what waits (with Cancel), what failed or expired in the last 24 h,
   and the newest result of Ring, Lock and Erase; a successful Update Location is no row (its fix is on the Locations
   page); the group is hidden when empty. The clock words as in §1.2. The cancel's `back` whitelist gains nothing
   (`locate`, `summary` exist; the summary's Cancel comes back to `#activity`).
4. **Retention**: `retention::prune` deletes `device_commands` older than 30 days that are not waiting (status not
   `queued`/`sent`-unconfirmed: the existing status words decide; a waiting one is never deleted), counted in
   `Pruned`; the Activity log's 7-day window is a query filter, not retention.
5. **One query per page**: `activity::rows(state, device_id, zone, now)` reads the two tables (each limited to 10
   within 7 days), merges by time, cuts at 10; the template gets a `Vec<ActivityRow>` enum (`Command(CommandRow)`,
   `Event(InboxRow)`). No new table.
6. The Locations page, the inbox and Getting Notifications are unchanged.

## 2. Tests (required)

The summary: a command and an event interleave newest first; the cap at 10 and the 7-day window; the Cancel of a
waiting command from the summary lands on `/devices/{id}#activity`; an event row's tap goes through `open` to its
target and marks it read; Today's answer for a command shown as a row is not repeated. Locate: a successful locate is
no row, a waiting lock has Cancel, a 25-hour-old expired locate is gone, the newest Ring result stays, the group hidden
when empty, the clock words on every row ("i dag 13:41", "i går …", "15. okt 14:02"). Retention: a 31-day-old done
command is pruned, a waiting one of the same age is not, `Pruned` counts it. `check_forms` on the summary and Locate.

## 3. Device check

iPhone: the summary reads top-down (quick actions, then Activity); after Unlock for 2 h the row appears with the
clock and Done; a PIN event sits between command rows in time order; Locate shows Requests only while something waits
or just failed; nothing jumps on Cancel.
