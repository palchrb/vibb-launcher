# 136 - CalDAV accounts belong to a phone; "Delt i familien" is a choice (a revision of design 72 step 2 / 72b)

Status: **design, revision 2**, 2026-11-25 (branch `design-136`), read against `main` fd101bd2d. Item 6 of PLAN's
locked list, approved by the owner on 2026-11-20: "på caldav om man skal skrive så må man jo skrive inn som riktig
bruker" and "gi valget om å dele er OK, da har man fleks til begge deler!". **Server only:** one migration (0182), the
account pages, the Calendar page's sources group, Add Calendar's CalDAV form, the Kalender select. No launcher code, no
contract file, and no change to the policy JSON. Revision 1 was f7c9aad70. Revision 2 takes the design QA (1 High,
3 Medium, 5 Low; §13). The owner has not answered §11 yet, so its defaults stand.

## 0. Short answer

- **An account belongs to the phone whose page added it.** `caldav_accounts` gains `owner_device_id` and `shared`.
  An unshared account shows only on its phone's Calendar page.
- **"Delt i familien" is a switch on the owner's page of the account.** When it is on, every phone's page shows the
  account, and any phone may tick its calendars to read them.
- **Only the owner's phone writes.** "Barnets avtaler skrives hit" (`kid_writes`) can be set only on a calendar of an
  account the phone owns, shared or not. A sibling never writes with another child's login. The database holds this
  rule with four triggers as well as the code.
- **Migration 0182 gives each account an owner:**
  - Calendars ticked on exactly one phone: the account becomes that phone's, not shared. This is what the owner most
    likely has.
  - Ticked on two or more phones, or on none: the account becomes shared. Its owner is the phone that writes the kid's
    events to it, else none.
  - If two phones write with the same login today, the lower device id keeps its mark and the other mark ends.
  - A read-only preview (§3.5) prints this per account before the update, for the owner to check.
- **The phone sees nothing new.** `calendar.feeds` and `calendar.kid_caldav` keep their shape. A phone loses a
  calendar only when the parent unshares an account it read. The only other change is a second writer's mark ending at
  the migration. Both are paths the launcher already handles.
- **An owner is only ever claimed with a typed password.** The password stays sealed under
  `caldav-v1:<fingerprint>:<id>`, and the owner is not part of the seal. Ownership changes in two ways only:
  - A passing Test with a password **typed on that phone's page** claims an account no phone owns. That is a first
    Test that never passed, an orphan, or a shared account with no owner. The Test can be Add Calendar's or
    "Test på nytt…" with a typed password. A shared account stays shared.
  - The owner phone is deleted.

  The stored password never makes an account any phone's.

## 1. Today (design 72 §6, 72b; `docs/notes/server/calendar.md`, "CalDAV accounts")

- **`caldav_accounts` (0110) is the family's.** Every phone's Calendar page lists every account that passed a Test.
  Each phone ticks its own calendars through `device_calendar_feeds` (PK device + source). There is one row per
  `(server_url, username)`, and a Test with the same pair on any phone's page replaces the password.
- **The mark** `device_calendar_feeds.kid_writes` (0120) sits on the phone's row of one ticked CalDAV calendar.
  `save_dagen`'s `caldav_source` checks only that the calendar is ticked on this phone and not `writable: false`.
- **The fault:** Telefon 1's own account (the kid's login) shows on Telefon 2's page. A parent can tick its calendars
  there, or pick one in Telefon 2's "Barnets avtaler". The server then writes Telefon 2's kid's events as Telefon 1's
  user.
- **Account removal** ("Fjern konto…") on any phone's page deletes the account for every phone. `caldav::sweep`
  deletes an account no phone has a calendar of, a day after both its last Test and its last calendar leaving
  (`unused_since`).
- **A source's uid** is the account's identity (`vibb-caldav:` + server NUL username NUL collection), not its row id.
  An account removed and added again therefore gives each calendar its key, settings and rules back.

## 2. Decisions

1. **Owner and shared are two columns on the account.** `owner_device_id` (the phone, `ON DELETE SET NULL`) and
   `shared` (0/1). A new account is the adding phone's and is not shared.
2. **Who sees an account on phone X's pages:** `owner_device_id = X OR shared = 1` (and `principal IS NOT NULL`, as
   today). An unshared account with no owner is shown nowhere. It is an orphan whose phone was deleted, and it is swept
   as unused.
3. **Who writes: the owner's phone only, shared or not** (the PLAN entry's "read-only" applies to every other phone;
   Q1 offers the strict reading). The owner wants the kid to write as the right user. The parent states which user
   that is by adding the account on that phone's page, and sharing only widens who reads. A shared account with no
   owner is read-only for every phone until a phone claims it (decision 6).
4. **What each page may do with an account:**
   - The sharing switch: only the owner's page, so only an account that has an owner.
   - "Fjern konto…": the owner's page, or any page for a shared account with no owner (as today).
   - "Test på nytt…": every page that shows the account. It never shows the password, and a sibling seeing
     "Passordet ble avvist" can fix it where it sees it. With a typed password, a pass on an account with no owner
     claims it (decision 6).
5. **Sharing off removes the account's calendars from the other phones.**
   - Their `device_calendar_feeds` rows go, and the phones are nudged.
   - Their `device_calendars` rows (settings, colours, title rules) stay by key, so sharing and ticking again brings
     them back.
   - The owner is unchanged; only an owner can switch sharing off.
   - There is no confirm (Q3). The save's line names the phones it left.
   - If the owner ticks none of the account's calendars, unsharing leaves it with no source. The existing trigger then
     stamps `unused_since`, and `caldav::sweep` deletes the account and its password a day later, unless the owner
     ticks one first. This is today's rule for an account no phone uses.
6. **Claims and the same login on another phone's page.**

   An account is **claimable** when no phone owns it: `principal IS NULL` (a first Test that never passed), or
   `owner_device_id IS NULL` (an orphan, or a shared account with no owner). It becomes X's only by a passing Test
   with a password typed on X's page, from Add Calendar or from "Test på nytt…". A Test with the stored password never
   claims. A claim leaves `shared` as it was.

   Adding an account that exists already, matched by `(server_url, username)`:
   - **It is claimable:** the Test runs, and a pass claims it.
   - **It is shown on X and has an owner** (X's own, or another phone's shared account): a Test as today. A pass
     replaces the password, and the owner is unchanged.
   - **It is another phone's and not shared:** refused before any request with "Kontoen hører til {phone}.", the fix
     behind its (i). Nothing is tested, and the 10 s gap is not used.

   **The same login under another spelling** (L5): a pass on X whose discovered `principal` equals the principal of
   any account another phone owns is refused the same way and rolled back. An e-mail on one page and a server
   address on another must not make a second row the sibling can use.

   **The claim is conditional, inside the pass's transaction** (M1):
   `UPDATE caldav_accounts SET owner_device_id = X WHERE id = ? AND (principal IS NULL OR owner_device_id IS NULL)`,
   run before the pass writes `principal`.
   - Zero rows means another phone claimed it first, so there is no claim.
   - If the account is then not shown on X, the whole pass is rolled back and answered as "Kontoen hører til {phone}."
   - If it is still shown on X (it is shared), the pass goes on as an ordinary Test.

   There is no "move" action. To move an account, the parent removes it and adds it on the other phone's page. The
   keys come back because the uid is the identity.
7. **A deleted owner phone** sets `owner_device_id` to NULL.
   - A shared account then stays shared with no owner, and its siblings keep reading.
   - An unshared one becomes an orphan. Its calendars left with the phone, so the existing trigger stamps
     `unused_since`, and `caldav::sweep` deletes it a day later.
8. **No change to the phone's policy or contract** (§6). `calendar.feeds` is still the phone's ticked sources, and
   `calendar.kid_caldav` is still the phone's mark.
9. **Every form sends the values it was drawn with** (design 135 decision 12). A save applies only what the parent
   changed, so a stale tab never undoes another parent's change.
   - The sharing switch posts `was_shared`.
   - The ticks post `was_on_<id>`.
   - "Fjern konto…" posts `was_shared` (M3). A remove whose page showed an unshared account, while it is shared now,
     is refused: other phones would lose calendars the confirm never named.
10. **The cap stays the family's:** 10 accounts per server (`MAX_ACCOUNTS`, counting every account that passed a
    Test). A per-phone cap is out of scope.
11. **A replaced or re-enrolled phone takes its account back with the app password** (H1). There are three cases:
    - **The old phone still exists:** the account is still its. Adding it on the new phone's page answers "Kontoen
      hører til {old phone}." The parent deletes the old phone under Enheter, or removes the account on its page,
      then adds it on the new phone's page with the app password.
    - **The old phone is deleted and the account was shared:** the account is shared with no owner, and the new
      phone's page shows it. "Test på nytt…" there, with the app password typed, makes it the new phone's. Then the
      parent picks its calendar in Kalender's "Barnets avtaler" again.
    - **The old phone is deleted and the account wasn't shared:** it is an orphan, shown nowhere. Within the day
      before the sweep, adding it on the new phone's page with the app password claims the same row. After the sweep
      it is simply added anew.

    Either way the calendars' keys come back by uid, so their settings and title rules return. The ticks and the
    mark are chosen again. A new mark starts with `accepted: null` ("send it all", design 72b D1.6).

## 3. Data model and migration `0182_caldav_per_phone.sql`

**Built as 0183** (code QA L3): design 137's `0182_cell_near.sql` merged first, so the build's file is
`0183_caldav_per_phone.sql`; read 0182 in this note as 0183. **Merged as 0184** (the post-release chain,
2026-11-26): design 138's `zone_networks` reached `main` as 0180 first, then phase D 0181, design 135 0182 and design
137 0183, so the file is `0184_caldav_per_phone.sql` (its content as built but the header's numbers).

**The number.** Main is used up to 0179. Phase D (`phase-d-server`, design 132) holds 0180, and design 135 (item 5)
holds 0181. Both merge before this item. 136 is item 6, so it takes **0182**. Never deploy 0182 without 0180 and 0181
(the order hazard, `server/CLAUDE.md`). If the order changes, renumber to the next free number before the first push
to `main`.

**No table rebuild.** The migration only adds columns, an index and triggers, and clears marks that break the rule.

### 3.1 The SQL

The architect ran this with Python's `sqlite3` (SQLite 3.46) on a database made from `main`'s 0001-0179, seeded with
every case in §9. The design QA ran revision 1's SQL (without the fourth trigger) on its own scratch database, with
the same results. The architect's run of revision 2:
- The owners, shares and cleared marks came out as in §9.
- `foreign_key_check` and `integrity_check` were clean.
- Each trigger refused its case. A second conditional claim changed no row.
- Deleting an owner phone fired no trigger, even with its mark on the account.

```sql
-- Design 136 (docs/design/136-caldav-per-phone.md at the monorepo root), a revision of design 72 step 2 / 72b: a
-- CalDAV account belongs to the phone whose page added it (`owner_device_id`), and is shown only on that phone's
-- Calendar page unless the parent chose "Delt i familien" (`shared`): then every phone's page shows it, to tick its
-- calendars and read them. Only the owner's phone may mark one of its calendars for the kid's events (`kid_writes`),
-- so a phone never writes with another child's login. A NULL owner is an account shared before 136 that no phone
-- wrote to, or one whose phone was deleted (an unshared one is then hidden on every page and swept as unused,
-- `caldav::sweep`); a passing Test with a password typed on a phone's page makes it that phone's. The password stays
-- sealed under `caldav-v1:<fingerprint>:<id>`: the owner is not in it.
ALTER TABLE caldav_accounts ADD COLUMN owner_device_id INTEGER REFERENCES devices(id) ON DELETE SET NULL;
ALTER TABLE caldav_accounts ADD COLUMN shared INTEGER NOT NULL DEFAULT 0 CHECK (shared IN (0, 1));
CREATE INDEX idx_caldav_accounts_owner ON caldav_accounts(owner_device_id);

-- The accounts of before 136. Calendars ticked on exactly one phone: that phone's, not shared. On two or more
-- phones, or on none: shared, owned by the phone that writes the kid's events to one of its calendars - the lowest
-- device id if several do - else by none.
UPDATE caldav_accounts SET
    owner_device_id = CASE
        WHEN (SELECT COUNT(DISTINCT d.device_id) FROM device_calendar_feeds d
              JOIN calendar_sources s ON s.id = d.source_id WHERE s.account_id = caldav_accounts.id) = 1
        THEN (SELECT MIN(d.device_id) FROM device_calendar_feeds d
              JOIN calendar_sources s ON s.id = d.source_id WHERE s.account_id = caldav_accounts.id)
        ELSE (SELECT MIN(d.device_id) FROM device_calendar_feeds d
              JOIN calendar_sources s ON s.id = d.source_id
              WHERE s.account_id = caldav_accounts.id AND d.kid_writes = 1)
    END,
    shared = CASE
        WHEN (SELECT COUNT(DISTINCT d.device_id) FROM device_calendar_feeds d
              JOIN calendar_sources s ON s.id = d.source_id WHERE s.account_id = caldav_accounts.id) = 1
        THEN 0 ELSE 1
    END;

-- A mark on an account the phone doesn't own ends as `calendar_kid_events::unmark` ends one: the two event tables'
-- rows of that phone go (what was written stays in the calendar, the kid's events stay on the phone, the tags stay),
-- then the flag. Only a second phone writing with the same login loses its mark.
DELETE FROM kid_caldav_events WHERE device_id IN (
    SELECT d.device_id FROM device_calendar_feeds d JOIN calendar_sources s ON s.id = d.source_id
    JOIN caldav_accounts a ON a.id = s.account_id
    WHERE d.kid_writes = 1 AND a.owner_device_id IS NOT d.device_id);
DELETE FROM device_kid_events WHERE device_id IN (
    SELECT d.device_id FROM device_calendar_feeds d JOIN calendar_sources s ON s.id = d.source_id
    JOIN caldav_accounts a ON a.id = s.account_id
    WHERE d.kid_writes = 1 AND a.owner_device_id IS NOT d.device_id);
UPDATE device_calendar_feeds SET kid_writes = 0
WHERE kid_writes = 1 AND NOT EXISTS (
    SELECT 1 FROM calendar_sources s JOIN caldav_accounts a ON a.id = s.account_id
    WHERE s.id = device_calendar_feeds.source_id AND a.owner_device_id = device_calendar_feeds.device_id);

-- The rules, held by the database as well as the code (the code refuses first, with words): a phone ticks a CalDAV
-- calendar only of its own account or a shared one; it marks one only of its own account; an account is made
-- unshared only with an owner and with no other phone's calendar left; and an account gets a new owner only while no
-- other phone writes to it. A deleted phone (`SET NULL` on the owner) writes a NULL owner and touches nothing else,
-- so none of these fires for it.
CREATE TRIGGER caldav_tick_own_or_shared BEFORE INSERT ON device_calendar_feeds
WHEN EXISTS (SELECT 1 FROM calendar_sources s JOIN caldav_accounts a ON a.id = s.account_id
             WHERE s.id = NEW.source_id
               AND ((a.shared = 0 AND a.owner_device_id IS NOT NEW.device_id)
                 OR (NEW.kid_writes = 1 AND a.owner_device_id IS NOT NEW.device_id)))
BEGIN
    SELECT RAISE(ABORT, 'design 136: a CalDAV calendar of another phone''s account');
END;

CREATE TRIGGER caldav_kid_writes_own BEFORE UPDATE OF kid_writes ON device_calendar_feeds
WHEN NEW.kid_writes = 1 AND NOT EXISTS (
    SELECT 1 FROM calendar_sources s JOIN caldav_accounts a ON a.id = s.account_id
    WHERE s.id = NEW.source_id AND a.owner_device_id = NEW.device_id)
BEGIN
    SELECT RAISE(ABORT, 'design 136: the kid''s events go only to the phone''s own CalDAV account');
END;

CREATE TRIGGER caldav_unshare_clean BEFORE UPDATE OF shared ON caldav_accounts
WHEN NEW.shared = 0 AND (NEW.owner_device_id IS NULL OR EXISTS (
    SELECT 1 FROM device_calendar_feeds d JOIN calendar_sources s ON s.id = d.source_id
    WHERE s.account_id = NEW.id AND d.device_id IS NOT NEW.owner_device_id))
BEGIN
    SELECT RAISE(ABORT, 'design 136: unshared with another phone''s calendar left, or no owner');
END;

CREATE TRIGGER caldav_owner_keeps_marks BEFORE UPDATE OF owner_device_id ON caldav_accounts
WHEN NEW.owner_device_id IS NOT NULL AND EXISTS (
    SELECT 1 FROM device_calendar_feeds d JOIN calendar_sources s ON s.id = d.source_id
    WHERE s.account_id = NEW.id AND d.kid_writes = 1 AND d.device_id IS NOT NEW.owner_device_id)
BEGIN
    SELECT RAISE(ABORT, 'design 136: a new owner while another phone writes to the account');
END;
```

### 3.2 Notes for the implementer

- **Order of the statements matters:**
  - The two event-table deletes select by `kid_writes = 1`, so they run before the flag is cleared.
  - The triggers come last, so the migration's own UPDATEs don't fire them.
- **The event tables' FKs cascade on a delete of `device_calendar_feeds`, never on an update.** Ending a mark is
  therefore three statements, exactly as `calendar_kid_events::unmark` does it. `kid_caldav_tags` stays, as at an
  unmark.
- **Untested accounts** (`principal IS NULL`) have no ticks, so the migration makes them shared with no owner. They stay
  hidden and are swept as today. Any phone can claim one (decision 6).
- **No trigger fires for a deleted phone:**
  - `caldav_unshare_clean` fires on `UPDATE OF shared` only.
  - `caldav_owner_keeps_marks` fires only for a non-NULL new owner.

  The FK's `SET NULL` writes a NULL owner and nothing else, so deleting a phone never aborts. The unshare handler
  writes `shared` alone, after deleting the other phones' rows.
- **The claim's `UPDATE` is conditional** (decision 6). `caldav_owner_keeps_marks` is the database's guard behind it.
  An account with no owner can't carry a mark, so the trigger only fires on a code path that would hand a marked
  account to another phone.
- **The `ON CONFLICT(server_url, username) DO UPDATE`** in `test_new`'s insert must not touch the owner.
- **A trigger's abort** has a message starting `design 136:`. The handlers map it to their refusal, never to a 500
  page (§4).
- **`backup_schema`:** the restore check compares the schema with what the migrations make. Check that
  `migration_trigger_and_view_names` picks up the four new triggers. `migrations_from_73_in_order` gains the file.

### 3.3 What is kept

| What | After 0182 |
|---|---|
| Every account, its password, hosts, calendars, state | unchanged (same id, same seal) |
| Every phone's ticks (`device_calendar_feeds`) | unchanged: an account ticked on several phones is shared |
| Every phone's `calendar.feeds` | unchanged |
| A mark (`kid_writes`) and its event rows | kept where the phone owns the account; a second writer on the same login loses it (Q2) |
| `device_calendars` settings and title rules | unchanged (by key) |

### 3.4 Rollback

- Forward-only, like every migration.
- `startup::pre_migration_backup` takes `data/backups/pre-migrate-<from>-<to>.db`. A rollback restores that copy
  together with the older binary (DEPLOY.md, "Rolling back the server"). Sharing changes and claims made since the
  update are lost.
- The phones need nothing: the restored server serves the same feeds. A mark ended by the migration comes back with
  `accepted: null`, which means "send it all".

### 3.5 Read-only preview of the migration

This prints what 0182 would do, with counts, account usernames and phone names. It never prints a password, a server
or a calendar address.

**Run it on a copy, never the live database.** The image is distroless and has no `sqlite3`, so the newest backup zip
on the host is the copy. Make a fresh backup on the Backups page first. Then, on the host, in the folder with
`compose.yaml` (`sqlite3` and `unzip` from apt):

```
f=$(sudo ls -t backups/ | grep '^backup-.*\.zip$' | head -1); d=$(mktemp -d)
sudo unzip -q "backups/$f" kidphone.db -d "$d"
sudo sqlite3 -readonly -header -column "$d/kidphone.db" < caldav-preview.sql
sudo rm -rf "$d"
```

**What to check** (M2):
1. **Each part-2 row:** is the username the login of the phone named as its owner? Read "no owner" as: read by those
   phones, written by none.
   - A kid's own account should be owned by that kid's phone.
   - A parent's family account should be shared, with no owner, or owned by the one phone that writes to it.
2. **Line 6** ("write marks that end") should be 0. If it isn't, Q2 applies.
3. **Line 7** ("logins on more than one account") should be 0 (code QA L3). If it isn't, one login sits in two rows
   under two spellings of the server. After the update, a pass on a phone that doesn't own the other row is refused
   as "Kontoen hører til {phone}." (L5), so remove the extra row on its page and keep one.

**The remedy for a wrong owner**, after the update:
- Remove the account on its owner's page ("Fjern konto…").
- Add it on the right phone's page with its app password.
- Tick its calendars and pick the kid's calendar in Kalender again.

The calendars' keys come back by uid, so their settings and title rules return. A family account that should show on
every phone needs no remove: switch "Delt i familien" on at its owner's page.

`caldav-preview.sql` (SELECTs only):

```sql
-- Design 136: what migration 0182 would do to the CalDAV accounts. Read-only (SELECTs only); counts, account
-- usernames and phone names - never a password, a server or a calendar address.
WITH ticks AS (
    SELECT s.account_id AS account, d.device_id AS device, MAX(d.kid_writes) AS writes
    FROM device_calendar_feeds d JOIN calendar_sources s ON s.id = d.source_id
    WHERE s.account_id IS NOT NULL
    GROUP BY s.account_id, d.device_id),
plan AS (
    SELECT a.id, a.username, a.principal IS NOT NULL AS shown,
           (SELECT COUNT(*) FROM ticks t WHERE t.account = a.id) AS phones,
           CASE WHEN (SELECT COUNT(*) FROM ticks t WHERE t.account = a.id) = 1
                THEN (SELECT t.device FROM ticks t WHERE t.account = a.id)
                ELSE (SELECT MIN(t.device) FROM ticks t WHERE t.account = a.id AND t.writes = 1)
           END AS owner
    FROM caldav_accounts a)
SELECT 1 AS part, 1 AS k, 'accounts (shown on the page)' AS what,
       (SELECT COUNT(*) FROM plan WHERE shown) AS n, '' AS detail
UNION ALL SELECT 1, 2, 'one phone''s, not shared', (SELECT COUNT(*) FROM plan WHERE shown AND phones = 1), ''
UNION ALL SELECT 1, 3, 'shared, its owner writes there',
    (SELECT COUNT(*) FROM plan WHERE shown AND phones > 1 AND owner IS NOT NULL), ''
UNION ALL SELECT 1, 4, 'shared, no owner (2+ phones read it)',
    (SELECT COUNT(*) FROM plan WHERE shown AND phones > 1 AND owner IS NULL), ''
UNION ALL SELECT 1, 5, 'shared, no owner (no phone uses it)',
    (SELECT COUNT(*) FROM plan WHERE shown AND phones = 0), ''
UNION ALL SELECT 1, 6, 'write marks that end',
    (SELECT COUNT(*) FROM ticks t JOIN plan p ON p.id = t.account
      WHERE t.writes = 1 AND t.device IS NOT p.owner), ''
UNION ALL SELECT 1, 7, 'logins on more than one account',
    (SELECT COUNT(*) FROM (SELECT a.principal FROM caldav_accounts a WHERE a.principal IS NOT NULL
      GROUP BY a.principal HAVING COUNT(*) > 1)), ''
UNION ALL SELECT 2, p.id, p.username, p.phones,
    COALESCE((SELECT 'owner ' || dv.name FROM devices dv WHERE dv.id = p.owner), 'no owner')
    || CASE WHEN p.phones = 1 THEN '' ELSE ', shared' END
    || ': ' || COALESCE((SELECT group_concat(dv.name
                  || CASE WHEN t.writes = 1 AND t.device IS NOT p.owner THEN ' (mark ends)'
                          WHEN t.writes = 1 THEN ' (writes)' ELSE '' END, ', ')
                FROM ticks t JOIN devices dv ON dv.id = t.device WHERE t.account = p.id), 'no phone')
FROM plan p WHERE p.shown
ORDER BY part, k;
```

On the architect's seeded copy, it printed:

```
1  1  accounts (shown on the page)            5
1  2  one phone's, not shared                 1
1  3  shared, its owner writes there          2
1  4  shared, no owner (2+ phones read it)    1
1  5  shared, no owner (no phone uses it)     1
1  6  write marks that end                    1
1  7  logins on more than one account         0
2  1  a   1  owner Telefon 1: Telefon 1 (writes)
2  2  b   2  owner Telefon 2, shared: Telefon 1, Telefon 2 (writes)
2  3  c   2  no owner, shared: Telefon 2, Telefon 3
2  4  d   2  owner Telefon 3, shared: Telefon 3 (writes), Telefon 4 (mark ends)
2  5  e   0  no owner, shared: no phone
```

## 4. The server's rules (where each is checked)

| Place | Rule |
|---|---|
| `caldav::accounts` | takes the phone: `WHERE principal IS NOT NULL AND (owner_device_id = ? OR shared = 1)`; `AccountView` gains `owner_device_id`, `shared`. Add Calendar's `accounts_full` keeps counting the family's accounts (a `COUNT(*)` as `test_new` does), not this filtered list |
| `caldav::test_new` | takes the phone and the typed password; decision 6's three cases before `claim`; a new row inserted with `owner_device_id = X` |
| `caldav::test_again` | takes the phone; a typed password may claim (decision 6), the stored one never does |
| `run_test`'s pass | in one transaction: L5's principal check, the conditional claim **before** `principal` is written, and the rollback with "Kontoen hører til {phone}." when the account is then not shown on X |
| `caldav::save_ticks` | **one transaction** (L1): the account is re-read inside it and must be shown on X, else `TickError::Gone`; `listed` is only the ids whose `on_` differs from `was_on_` (decision 9); never half done |
| `caldav::set_shared` (new) | §5.3's handler logic, in one `BEGIN IMMEDIATE`; X must be the owner, re-read inside it; returns the phones whose calendars it removed |
| `caldav::remove` | in one `BEGIN IMMEDIATE`: X must manage it (decision 4) and `was_shared` must match (M3), both re-read inside it |
| `show_caldav`, `test_caldav_again` | the account must be shown on X, else 303 `#sources` (as a gone account today) |
| `remove_caldav`, the sharing POST | a refusal is `settings_log::refused`: a 303 back to the page's group |
| `handlers::calendar::caldav_source` (`save_dagen`) | the source's account has `owner_device_id = X`, **in both branches** (the "already the pick" short cut too); re-checked in `save_dagen`'s transaction |
| the Kalender select's options | only CalDAV calendars whose account X owns |
| every write above | a SQLite abort whose message starts `design 136:` (a trigger, §3.1) rolls back and answers as that route's refusal, never a 500 page (L1) |
| `calendar_kid_events` (policy, endpoint, job) | unchanged: the triggers guarantee a mark is always the owner's |
| `caldav::sweep`, `integration_health` | unchanged |

A sibling page can't see an unshared account, tick it, mark it, test it or remove it. Every POST route checks this
itself, because a hidden link is not a check.

## 5. The PWA

Every save posts to an anchor on its own page (`#calendars`, `#sharing`, `#test`, `#remove`, `#dagen`), and the
autosave goes through `scroll-restore.js`, so a save never jumps to the top. A refusal is a 303 back to its group,
carrying `Refused` in the response's extensions (`settings_log` doesn't count it), or for Add Calendar a 400 re-render
as typed.

### 5.1 The Calendar page, "Hentes av serveren" (`#sources`)

The rows are the accounts X sees (decision 2). A shared account's sub-line gains a quiet "· delt". On a sibling's page
it gains "· delt fra {owner}" instead. Nothing is orange because of sharing: sharing is a choice.

```
Telefon 2 › Kalender
 HENTES AV SERVEREN
 ┌──────────────────────────────────────┐
 │ ▣ Skolen                           › │
 │   skole.example.no · Hentet 15:10    │
 │ ▣ bruker@example.no                › │
 │   iCloud · 1 av 2 kalendere ·        │
 │   delt fra Telefon 1                 │
 │ Legg til kalender…                   │
 └──────────────────────────────────────┘
  (i)
```

### 5.2 The account's page `/devices/{id}/calendar/caldav/{account}`

**(a) On the owner's phone.** A new group `#sharing` sits between the calendars and Test:

```
‹ Kalender              bruker@example.no
 ┌──────────────────────────────────────┐
 │ Server                   iCloud  (i) │
 │ Logget inn              i dag 15:12  │
 └──────────────────────────────────────┘
 PÅ DENNE TELEFONEN
 ┌──────────────────────────────────────┐
 │ Hjemme                        [●══]  │
 │ Hentet 15:10 · 12 hendelser          │
 │ Barnets avtaler skrives hit          │
 │ Jobb                          [══○]  │
 └──────────────────────────────────────┘
 ┌──────────────────────────────────────┐
 │ Delt i familien               [══○]  │   NEW
 └──────────────────────────────────────┘
  (i)
 ┌──────────────────────────────────────┐
 │ Test på nytt…                        │
 └──────────────────────────────────────┘
 ┌──────────────────────────────────────┐
 │ Fjern konto…                         │
 └──────────────────────────────────────┘
```

**(b) On a sibling's phone**, for a shared account owned by another phone:
- a "Hører til" row with the owner's name, linking to the owner's page of this account;
- no sharing group, and no "Fjern konto…".

```
‹ Kalender              bruker@example.no
 ┌──────────────────────────────────────┐
 │ Server                   iCloud  (i) │
 │ Logget inn              i dag 15:12  │
 │ Hører til              Telefon 1   › │   NEW
 └──────────────────────────────────────┘
 PÅ DENNE TELEFONEN
 ┌──────────────────────────────────────┐
 │ Hjemme                        [●══]  │
 │ Hentet 15:10 · 12 hendelser          │
 │ Jobb                          [══○]  │
 └──────────────────────────────────────┘
 ┌──────────────────────────────────────┐
 │ Test på nytt…                        │
 └──────────────────────────────────────┘
```

**(c) A shared account with no owner, on any phone's page** (here Telefon 2):
- "Hører til" shows a quiet "ingen". Its (i) holds `shared_info_none`, the way to claim it.
- No sharing switch.
- "Fjern konto…" with the shared question.

```
‹ Kalender              familie@example.no
 ┌──────────────────────────────────────┐
 │ Server                Nextcloud  (i) │
 │ Logget inn              i går 09:40  │
 │ Hører til                ingen   (i) │   NEW
 └──────────────────────────────────────┘
 PÅ DENNE TELEFONEN
 ┌──────────────────────────────────────┐
 │ Familien                      [●══]  │
 │ Hentet 15:10 · 31 hendelser          │
 └──────────────────────────────────────┘
 ┌──────────────────────────────────────┐
 │ Test på nytt…                        │
 └──────────────────────────────────────┘
 ┌──────────────────────────────────────┐
 │ Fjern konto…                         │
 └──────────────────────────────────────┘
```

**(d) The same account seen from Telefon 3 after Telefon 2 claimed it** ("Test på nytt…" with the app password typed):
it is drawn as (b). "Hører til" says "Telefon 2 ›", and the page has no "Fjern konto…". On Telefon 2's page it is
drawn as (a), with "Delt i familien" on.

**The (i) texts and the remove question:**
- **The (i) under "Delt i familien"** (owner's page only): "Delt: alle telefonene kan vise kalenderne. Bare {owner}
  kan skrive barnets avtaler hit."
- **The (i) of "Hører til · ingen":** "Alle telefonene kan vise kalenderne, men ingen skriver hit. Test på nytt med
  app-passordet her, så hører kontoen til {phone}."
- **"Fjern konto…"'s question** says where the calendars go:
  - Unshared: "Kalenderne forsvinner fra telefonen, og app-passordet slettes fra serveren."
  - Shared: today's "… forsvinner fra alle telefonene …".

### 5.3 The sharing switch (`POST …/caldav/{account}/sharing`)

One `data-autosave` form with a `<noscript>` Save, drawn only on the owner's page. It carries `was_shared` (1/0) and
`shared` (the switch). The handler runs in one `BEGIN IMMEDIATE`:

1. **The account is gone** (or never passed a Test): 303 to `#sources`, refused.
2. **X isn't its owner now** (a stale tab, or a phone deleted and the account claimed since): 303 to the account page,
   refused, and nothing is written.
3. **`shared` equals `was_shared`, or already equals the stored value** (another parent did it): nothing changes. The
   save lands on `#sharing` with no line.
4. **On:** `shared = 1`. No phone's policy changes, so no nudge.
5. **Off:** in this order:
   - read the other phones with a calendar of the account;
   - delete their `device_calendar_feeds` rows for the account's sources (the orphan trigger drops a source no phone
     has left; decision 5's sweep line);
   - `UPDATE caldav_accounts SET shared = 0 WHERE id = ?` (the owner is unchanged);
   - commit, then nudge those phones and run `colors_after_link_save` for each;
   - land on `?notice=unshared&from=<ids>#sharing`. The line under the group is "Fjernet fra Telefon 2." It names
     only phones that still exist; with none, there is no line.

```
 ┌──────────────────────────────────────┐
 │ Delt i familien               [══○]  │
 └──────────────────────────────────────┘
  Fjernet fra Telefon 2.
```

### 5.4 The ticks form, made stale-safe (`#calendars`)

The form gains `was_on_<id>` (1/0) for every listed calendar. `save_caldav_calendars` passes `save_ticks` only the ids
whose `on_` differs from `was_on_`. A calendar ticked in another tab meanwhile stays ticked. A post that changed
nothing lands on `#calendars` with no line.

Today's notices are unchanged: `kid_caldav_ended`, `caldav_full` and the unticked-mark line.

### 5.5 "Fjern konto…" (`POST …/caldav/{account}/remove`, M3)

The confirm's form carries `was_shared`. In one `BEGIN IMMEDIATE`:
- **X doesn't manage it now:** refused, 303 to the account page.
- **It is shared now and `was_shared` is 0:** refused, nothing deleted. The 303 goes to `?notice=remove_shared#remove`,
  and the page draws the confirm open with the shared question, so the parent sees that other phones lose calendars.
- **Otherwise:** removed as today, and every phone that had one of its calendars is nudged.

### 5.6 Add Calendar's CalDAV form

The fields don't change, and there is no sharing choice at add: an account is shared on its page.

Decision 6's refusal renders the form as typed (400, CalDAV chosen). The reason goes under **Brukernavn**, with the fix
behind its (i) (L3):

```
 Brukernavn
 ┌──────────────────────────────────────┐
 │ bruker@example.no                    │
 └──────────────────────────────────────┘
 Kontoen hører til Telefon 1.        (i)
```

The (i) says: "Del den under Telefon 1, eller fjern den der og legg den til her."

### 5.7 Kalender's "Barnets avtaler" (`#dagen`)

The CalDAV options list only calendars ticked on X whose account X owns. Kalender's (i) (`calendar.kid_caldav.info`)
gains one sentence: "Bare kalendere fra telefonens egen konto kan velges." A posted `caldav:<uid>` of any other account
is today's 400 (`calendar.error.kid_calendar`).

### 5.8 Routes

| Route | What |
|---|---|
| `POST /devices/{id}/calendar/caldav/{account}/sharing` | new: the switch, the owner's page only (a save; routes and ids in the settings log, never a value) |
| `GET …/caldav/{account}`, `POST …/test` | shown on X, else 303 `#sources`; a typed password may claim |
| `POST …/calendars` | shown on X; `was_on_<id>` diff; one transaction |
| `POST …/remove` | managed by X; `was_shared` (M3) |
| `POST /devices/{id}/calendar/caldav` | decision 6 |
| `POST /devices/{id}/calendar/dagen` | the owner check |

Every route needs full auth and CSRF. `every_calendar_route_needs_a_session` gains the new route.

### 5.9 Words (nb / en)

Checked against root CLAUDE.md, "Words in the UI":
- The label is the owner's own words.
- Explanations and fixes sit behind the (i).
- The state is quiet: "· delt", "Hører til · ingen".
- No sharing state is a warning, because sharing is the parent's choice.

| Key | nb | en |
|---|---|---|
| `calendar.caldav.shared` | Delt i familien | Shared with the family |
| `calendar.caldav.shared_info` | Delt: alle telefonene kan vise kalenderne. Bare {owner} kan skrive barnets avtaler hit. | Shared: every phone can show its calendars. Only {owner} can write the kid's events here. |
| `calendar.caldav.owner_row` | Hører til | Belongs to |
| `calendar.caldav.owner_none` | ingen | none |
| `calendar.caldav.shared_info_none` | Alle telefonene kan vise kalenderne, men ingen skriver hit. Test på nytt med app-passordet her, så hører kontoen til {phone}. | Every phone can show its calendars, but none writes to them. Test again here with the app password to make the account {phone}'s. |
| `calendar.caldav.unshared` | Fjernet fra {phones}. | Removed from {phones}. |
| `calendar.caldav.remove_confirm_own` | Kalenderne forsvinner fra telefonen, og app-passordet slettes fra serveren. | The account's calendars leave this phone, and the app password is deleted from the server. |
| `calendar.caldav.error.other_phone` | Kontoen hører til {phone}. | The account belongs to {phone}. |
| `calendar.caldav.error.other_phone_info` | Del den under {phone}, eller fjern den der og legg den til her. | Share it on {phone}'s page, or remove it there and add it here. |
| `calendar.sources.caldav_shared` | {sub} · delt | {sub} · shared |
| `calendar.sources.caldav_shared_from` | {sub} · delt fra {owner} | {sub} · shared from {owner} |
| `calendar.kid_caldav.info`, one sentence added | Bare kalendere fra telefonens egen konto kan velges. | Only calendars of this phone's own account can be picked. |

`calendar.caldav.remove_confirm` (the shared case) and every other key stay. The source notes' "the family's" lines
change in `docs/notes/server/calendar.md` at the build.

## 6. The phone and the policy

- **No change to the policy JSON, the contract files or the launcher.** `policy_json_keys_snapshot` and
  `PolicyResponseCompatTest` are untouched.
- **What a phone can see happen**, all through existing paths:
  - Unsharing removes the account's calendars from the policy's `calendar.feeds`. The launcher deletes their
    `vibb-feed-<uid>` calendars (design 72 §4, "Feeds gone"). Sharing and ticking again bring them back with the
    same key.
  - The migration ends a second writer's mark. That phone's `calendar.kid_caldav` becomes `null`, and the launcher
    stops posting (design 72b D1.6). The kid's events stay in "Min kalender", and what was written stays in the
    calendar.
- **Android 14-17:** no launcher change, so there is nothing to check per version.

## 7. Privacy and security

- **The password stays sealed under `caldav-v1:<fingerprint>:<id>`** and is never rendered (unchanged). Ownership is
  not part of the seal, so no re-seal happens.
- **An owner is only ever claimed with a password typed on that phone's page** (decision 6). The claim is conditional
  in the pass's transaction, and the database refuses a new owner while another phone writes to the account. The
  stored password never makes an account a phone's, and unsharing never changes the owner.
- **A sibling never uses another child's login to write** (the triggers, §3.1). **It reads with it only when the
  parent shared the account.**
  - An unshared account is invisible on the sibling's pages and refused on its routes.
  - The same login typed under another spelling is refused at the pass (L5).
- **Parents see every phone's pages, as before.** The refusal "Kontoen hører til Telefon 1" tells a parent nothing
  they couldn't see.
- **Logs:** no username, password or address in any log line. The sharing save and the claim log route and ids only.

## 8. Steps and release

| # | Step | Size |
|---|---|---|
| B | Server, one branch: migration 0182 (§3.1); `caldav::accounts(db, device)`, `test_new`/`test_again` with the typed-password claim (conditional, in the pass's transaction) and L5's principal check, `save_ticks` in one transaction with its visibility and `was_on_` diff, `set_shared`, `remove`'s `was_shared`; the trigger aborts mapped to refusals; the handlers' checks (§4); the sources rows' sub-lines; the account page's four states (§5.2), `#sharing`, the remove question; the Kalender options and `caldav_source`'s owner check; the route; nb/en; the tests (§9, §10); `docs/notes/server/calendar.md` ("CalDAV accounts": the family's → the phone's, sharing, claims; "Device › Calendar's sources"; 72b's model line) | S-M |

**No launcher step and no emulator gate.** There is no launcher change, and the phone sees only two existing paths
(§6), which design 72's §6y and 72b's §6z cover. The implementer checks §5's pages in a browser against a seeded
server, at phone width, all four states of §5.2 included.

**Release.** A server release ships it, after 0180 and 0181. Its release notes carry one line (M2):

> CalDAV accounts now belong to the phone they were added on; one used on several phones is shared ("Delt i
> familien"), and only its own phone writes the kid's events to it. Before updating, run the preview in design 136
> §3.5 on a backup copy and check each account's owner; a wrong one is fixed by removing the account and adding it on
> the right phone's page.

## 9. Migration test

**`migration_0182_gives_each_account_a_phone`** (`tests::cleanup::migrated_before(182)`).

The seed:
- Telefon 1-4;
- account A ticked on Telefon 1, which writes to it;
- B ticked on Telefon 1 and 2, with Telefon 2 writing;
- C ticked on 2 and 3, with no writer;
- D ticked on 3 and 4, both writing (to two of its calendars);
- E ticked nowhere;
- F untested;
- kid-event rows for every mark.

After the migration:
- A is Telefon 1's and not shared.
- B is shared, owned by Telefon 2.
- C is shared with no owner.
- D is shared, owned by Telefon 3. Telefon 4's mark, `device_kid_events` row and `kid_caldav_events` rows are gone, and
  its tags stay.
- E and F are shared with no owner.
- Every phone's `calendar.feeds` equals what it was before. Every phone's `calendar.kid_caldav` equals what it was
  before, except Telefon 4's, which is `null`.
- `foreign_key_check` is clean.
- The four triggers refuse their cases:
  - Telefon 2 ticks A;
  - Telefon 3 marks C;
  - Telefon 1 marks B;
  - B is unshared while Telefon 1 ticks it;
  - C is unshared with no owner;
  - D is given to Telefon 4 while Telefon 3 writes to it.
- The conditional claim makes C Telefon 2's (one row). A second claim by Telefon 3 changes no row.
- Deleting Telefon 1 leaves A with no owner and its `unused_since` stamped, and B unchanged. Deleting Telefon 3, D's
  writing owner, fires no trigger and leaves D shared with no owner.

`migrations_from_73_in_order` gains the file.

## 10. Tests (server, `tests::caldav`, `calendar_kid_events::tests`, `tests::calendar`)

- **Ownership:**
  - An account added on Telefon 1 is Telefon 1's: its row and page don't show on Telefon 2.
  - From Telefon 2, every route answers 303 or is refused, and nothing is written:
    - GET the page;
    - POST ticks, Test, sharing and remove.
  - Telefon 2's Kalender has no option for it, and a posted `caldav:<uid>` is a 400.
- **The same login elsewhere:**
  - Adding Telefon 1's unshared account on Telefon 2 is the 400 with `other_phone` under Brukernavn, with its (i). The
    fake DAV sees no request, and the password and `tested_at` are unchanged.
  - Adding a shared one with an owner on Telefon 2 runs Test. A pass replaces the password and keeps the owner.
  - L5: Telefon 1's unshared account typed on Telefon 2 under another spelling (the e-mail on one page, the server
    address on the other) passes discovery with the same principal. It is refused and rolled back, and no second row
    stays usable on Telefon 2.
- **Claims (H1, M1):**
  - An untested row left by a failed first Test on Telefon 1 is claimed by Telefon 2's passing add.
  - An orphan (owner deleted, not shared) is claimed only by a passing add with the typed password. A wrong password
    leaves it an orphan.
  - A shared account with no owner: on Telefon 2's page, "Test på nytt…" with the typed password makes it Telefon 2's.
    `shared` stays 1, and Telefon 3 keeps its ticks. "Test på nytt…" with the stored password changes no owner. Its
    page shows no sharing switch before the claim, and the switch after.
  - Two phones' Tests race for one account with no owner: exactly one claims. The other's pass goes on as an ordinary
    Test when the account is still shown on it, else it is rolled back with "Kontoen hører til {phone}".
  - A replaced phone (decision 11): Telefon 1 writes to its shared account and is deleted. The new Telefon 5's page
    shows the account with "Hører til · ingen". Telefon 5's typed-password Test claims it, and its Kalender can mark
    the calendar again. The calendar's `device_calendars` settings come back by key.
- **Sharing on:**
  - Telefon 2 then sees "· delt fra Telefon 1" and the "Hører til" row, and can tick a calendar. Telefon 2's
    `calendar.feeds` gains it.
  - Telefon 2 can't mark it: there is no option, and a post is a 400.
  - Telefon 1's mark and writes go on.
  - Test from Telefon 2 works. Remove and the sharing post from Telefon 2 are refused.
- **Sharing off:**
  - Telefon 2's ticks go, Telefon 2 is nudged, and the line names Telefon 2.
  - Telefon 2's `device_calendars` rows stay, so sharing and ticking again bring the settings back.
  - Telefon 1's mark is untouched.
  - An account with no owner has no switch, and a forged sharing post for it is refused.
  - L4: unsharing an account the owner ticks none of leaves no source. `unused_since` is stamped, and the sweep
    deletes the account after `UNUSED_HOURS` unless the owner ticks one first.
- **Stale forms:**
  - A stale tab with `was_shared = 1` posting off after another parent unshared changes nothing.
  - A stale sibling tab is refused.
  - A stale ticks tab doesn't untick a calendar ticked meanwhile, and a post that changed nothing lands on
    `#calendars`.
  - M3: a remove posted with `was_shared = 0` after the account was shared is refused. Nothing is deleted, and the
    page comes back at `#remove` with the shared question open.
- **Aborts are refusals (L1):** a tick or a mark that hits a trigger (forced in the test by sharing off between the
  page's read and the save) answers the route's refusal, not a 500, and `save_ticks` leaves no tick of that post
  behind.
- **Deleted owner:**
  - A shared account becomes ownerless, and the siblings keep their feeds.
  - An unshared one is hidden everywhere and swept after `UNUSED_HOURS`.
- **Landing:** `shell::every_form_on_every_page_lands_on_its_own_group` covers `#sharing` and `#remove`.
  `every_calendar_route_needs_a_session` covers the new route.
- **The settings log:** the sharing route is a save that records route and ids only. A refusal isn't counted.
- **`seeded_family`** gives its accounts an owner.

## 11. Open owner questions (each with a recommended default)

The owner has not answered these yet. The build follows the defaults unless the owner says otherwise.

1. **Does the owner phone keep writing when the account is shared?** Recommended: **yes**. Other phones read, and
   only the phone the parent added the account on writes, as its own user.

   The alternative is PLAN's strict reading, "shared means read-only for all". It would change exactly this:
   - **Decision 3** becomes "a shared account is read-only for every phone, its owner included".
   - **The migration:** every shared account that has a writer loses its mark. These are the preview's "shared, its
     owner writes there" rows (b and d in §3.5's example). Their writing phones keep their ticks, and line 6 counts
     them too.
   - **After the update:** switching sharing on ends the owner's mark. That switch would then need a confirm, and a
     calendar that one kid writes to and another reads would need two accounts.
2. **Two phones writing with the same login today:** the lower device id keeps its mark, and the other ends at the
   update. What was written stays, and that kid's events stay on the phone. Recommended: **yes**. Line 6 of the
   preview says whether it happens at all. It is most likely 0.
3. **Switching sharing off while another phone reads the account:** no confirm, and the line says "Fjernet fra
   Telefon 2." Recommended: **no confirm**. Sharing and ticking again undo it, and the calendar's settings come back.
   The alternative is a confirm naming the phones, only when another phone has a calendar of it.

## 12. Out of scope

- Moving an account between phones without retyping its password
- A per-phone account cap
- A sharing choice on the add form
- Two rows of the same login that are both shown on one phone (as today)
- Sharing calendar links (`kind = 'ics'`; they are already per phone, the fetch shared)
- Design 72b's write path itself (unchanged)

## 13. Revision 2: the design QA (1H 3M 5L)

- **H1 (claims):** an owner is claimed only by a passing Test with a typed password on that page (add, or "Test på
  nytt…"), and `shared` stays as it was. The sharing switch is drawn only for an account with an owner. Unsharing no
  longer sets the owner (the `COALESCE` is gone). `shared_info_none` is rewritten as the way to claim. Decision 11
  covers the replaced or re-enrolled phone. The tests are in §10, "Claims".
- **M1:** the claim is a conditional `UPDATE` in the pass's transaction, and a lost race means no claim. The fourth
  trigger, `caldav_owner_keeps_marks`, refuses a new owner while another phone holds a mark on the account. Both were
  run on the seeded database (§3.1).
- **M2:** §3.5 asks the owner to check every part-2 row's owner, gives the remedy (remove and add on the right page;
  the keys come back by uid), and §8 carries the release-note line.
- **M3:** the remove form posts `was_shared`. A remove of an account shared since the page was drawn is refused, with
  the confirm drawn again (§5.5).
- **L1:** every write re-reads ownership inside its transaction, `save_ticks` is one transaction, and a `design 136:`
  abort is mapped to the route's refusal.
- **L2:** §5.2 draws (c), a shared account with no owner, and (d), that account from a sibling after a claim.
- **L3:** the add refusal is "Kontoen hører til {phone}.", with the fix behind the (i). The English sentences are
  fixed (§5.9).
- **L4:** decision 5 and a test cover the one-day sweep after an unshare leaves no source.
- **L5:** a pass whose principal equals another phone's unshared account is refused (decision 6).
- **Owner questions:** none answered yet. Q1 now says exactly what the strict reading would change.
