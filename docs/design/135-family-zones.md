# 135 - Zones shared by the family, picked per phone (a revision of design 64)

Status: **design, revision 2**, 2026-11-25 (branch `design-135`), read against `main` a0b68549a. Item 5 of PLAN's
locked list, approved by the owner on 2026-11-20: "alt av soner bør jo kunne brukes per familie, og ikke per barn ...
alt du satt av soner kunne velges og brukes per barn". **Server only:** one migration (0181), the zone pages, the
weather pick, the push filter. No launcher code, no contract file, no change to the policy JSON. Revision 1 was
ec58f81ee. Revision 2 takes the design QA (H1, M1-M3, L1-L6; §13) and the owner's answers of 2026-11-25 (§12).

## 0. Short answer

- **One zone list for the family, and a link per phone and zone.** The list is `family_zones`: name, centre and
  radius. The links are the existing `device_zones` rows, which keep their ids, their side state, their cursor and
  the events that name them. A second phone gets "Hjem" with one switch instead of drawing it again.
- **The phone gets exactly what it gets today.** `location_policy.zones` still lists only that phone's zones as
  `{id, latitude, longitude, radius_m}`. The `id` is still the `device_zones` id, now the link's. After the migration
  every phone is served the same values, so its fences and its learned Wi-Fi zone links (design 125 D12, keyed by id
  and circle) stay as they are.
- **Migration 0181 folds identical typed-in zones.** Zones with the same name (ignoring ASCII case and surrounding
  spaces), the same centre and the same radius become one family zone. On the owner's data, where every zone is on
  one phone, it folds nothing. Each link keeps its state, and the weather moves to the family zone. The zone's
  Arrive/Leave switches become each parent's own switches for that phone and zone. Nothing is judged again and
  nothing is raised.
- **Every crossing is stored, and the push is each parent's.** A zone event always goes to the family's inbox
  (design 35: every kind is stored, the switches govern the push). A parent's "Kommer"/"Drar" for a phone and a zone
  decides that parent's push.
- **PWA:**
  - The family's list is at `/zones`, behind a "Soner" row on Locate's all-phones view.
  - The zone's editor is at `/zones/{id}` and has a "Telefoner" checkbox group.
  - The phone's Zones page becomes one auto-saving form. Each family zone has a switch for this phone, and while that
    switch is on, the signed-in parent's "Kommer" and "Drar".
  - Every form posts only what changed on it, so a stale page never undoes another parent's change (§5.4).

## 1. Today (design 64, `docs/notes/server/zones.md`)

- **The table.** `device_zones` (0091) has one row per phone and zone. Each row holds:
  - the circle: `name`, `latitude`, `longitude`, `radius_m`;
  - the family's switches `notify_arrive`/`notify_leave`, which decide whether a crossing is an event at all;
  - the rule's state: `side`, `side_since`, `pending_*`, and the cursor `evaluated_at`/`evaluated_fix_id`.

  Names are unique per phone, checked in code. A phone has at most 10 zones (`MAX_ZONES`).
- **Where the row's id is used:**
  - the policy's `zones[].id`, which keys the phone's fences (`vibb-zone:<id>`) and its zone-link key
    `hmac("zone|id|lat|lng|radius")` (`PlacesPlan.zone`);
  - events' `params.zone_id` and `dedup_key` (`<zone id>:<side>:<at>`);
  - `device_policy.weather_zone_id` (FK, `ON DELETE SET NULL`, 0122), picked from the phone's zones;
  - `zones.json`;
  - the zone's page URL.
- **The pages.** Locate (one phone) has a "Soner" row leading to `/devices/{id}/zones`, the list with "Her nå" and the
  warnings. Each zone opens `/devices/{id}/zones/{zone_id}`: map, name, centre, radius, Arrive/Leave, Delete.
- **The push.** A parent gets a push when `notification_prefs.push_zones` is on and the zone's switch raised the
  event. `push_zones` is one switch for every phone and zone.

## 2. Decisions

1. **The family zone holds the place; the link holds everything else.**
   - `family_zones` stores `name`, `latitude`, `longitude`, `radius_m`, `created_by`, `created_at` and `updated_at`.
   - `device_zones` is the link. It holds `device_id`, `zone_id`, the state and the cursor. Its
     `created_by`/`created_at`/`updated_at` now mean who linked the zone and when.
   - The rule (`zones::step`, `evaluate`) is unchanged and judges each link on its own phone's fixes.
2. **The link's id is the id the phone and the events see.** The migration doesn't change it. A new link gets a new
   id, and unlinking deletes the row. The policy, `zones.json` and the events keep naming `device_zones.id`.
3. **No contract change.** `zones::policy_zones` reads the circle through the link. A zone not linked to a phone
   never reaches that phone.
4. **Editing a shared zone changes it for every phone that uses it.**
   - A moved centre or a changed radius runs `start_over` on every link of the zone (a silent first side, as design
     64 decision 4) and nudges each phone.
   - A rename changes the name only.
   - The page says so behind the place group's (i).
5. **A name is checked only when it changes** (the owner's Q2 answer, 2026-11-25). Two zones with the same name in
   different places are allowed to stay as they are. The checks that apply to a new or changed name are 1-40
   characters, no `Cc`/`Cf` characters, and unique in the family (`to_lowercase`). The family list's sub-line names
   the phones that use each zone, which tells two such zones apart.
6. **Caps.**
   - 10 links per phone (`MAX_ZONES`, unchanged), for the phone's work and the page.
   - 50 zones in the family (`MAX_FAMILY_ZONES`, a code constant), because the phone's page draws one cell per
     family zone. A family already over 50 after the fold loses only "Legg til sone".
   - The fences' sanity cap (`zone_watch.fences` <= 100) is untouched.
7. **Every crossing is an event; alerts govern only the push** (the owner's Q3 answer, M3).
   - `zone_event` no longer checks a switch, and `notify_arrive`/`notify_leave` are dropped.
   - The alert switches live in `zone_alerts(admin_id, device_zone_id, arrive, leave)`; no row means both on.
   - A parent gets the push when their Notify Me About "Soner" (`push_zones`) is on and their switch for the link
     and the direction is on.
   - This changes one thing for the owner: a direction switched off today now leaves an inbox row without a push.
8. **A zone used by no phone is a choice, not a fault.** It is kept, never judged and never served. Its sub-line
   says "ingen telefon" quietly, with no orange line.
9. **The weather follows the family zone** (M1). The new column `device_policy.weather_family_zone_id` references
   `family_zones` with `ON DELETE SET NULL`.
   - The picker lists every family zone; the weather needs a place, not a link.
   - Turning a zone off for a phone leaves its weather as it is.
   - Deleting the family zone clears it.
10. **Deleting a family zone deletes its links** (FK cascade). The cascade also removes their alert rows and clears a
    weather zone that pointed at it. Past events stay until the location history's retention takes them (64
    decision 6).
11. **Turning a zone off for a phone deletes that link.** Turning it on again makes a new link: a new id, a silent
    first side, and the phone relearns its Wi-Fi link for that zone (design 125 D12, at least 2 days).
12. **A form changes only what its parent changed on it** (H1). Every page carries the values it was drawn with
    (`was_*`), and the save applies only the fields that differ from them, so a stale page never undoes another
    parent's change.

## 3. Data model and migration `0181_family_zones.sql`

**The number.** Main is used up to 0179. Phase D (`phase-d-server`, design 132) holds 0180 and merges after rc.4,
stable 0.43.0 and the server tag. 135 is item 5, after phase D, so it takes **0181**. Never deploy 0181 without 0180
(the order hazard, `server/CLAUDE.md`). If 135 should merge first, it renumbers to 0180 before its first push to
`main`.

**No table rebuild.** The migration only adds tables and columns and drops plain columns. A copy-drop-rename of
`device_zones` would set every `weather_zone_id` to NULL (`docs/notes/server/zones.md`, "A zone stands in for the
weather").

### 3.1 The SQL

```sql
-- Design 135: zones are the family's; a phone links to the ones it uses. The link is the old
-- device_zones row - its id, state and cursor unchanged - so the policy's zone ids, the phone's
-- fences and Wi-Fi zone links and the events' params all stay valid. The weather moves to the
-- family zone. Alerts become each parent's, per link; every crossing is an event.
CREATE TABLE family_zones (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,                 -- 1..40, unique in the family ignoring case: code's, on a change
    latitude REAL NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude REAL NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    radius_m INTEGER NOT NULL CHECK (radius_m > 0),
    created_by INTEGER REFERENCES admin_users(id) ON DELETE SET NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);

-- The fold: one family zone per group of identical typed-in zones - the same name (ASCII case and
-- surrounding spaces aside), the same centre (stored at 6 decimals) and the same radius - made from
-- the group's oldest row. Same-name zones elsewhere stay two zones with their names.
INSERT INTO family_zones (name, latitude, longitude, radius_m, created_by, created_at, updated_at)
SELECT trim(d.name), d.latitude, d.longitude, d.radius_m, d.created_by, d.created_at, d.updated_at
FROM device_zones d
WHERE d.id = (SELECT MIN(e.id) FROM device_zones e
              WHERE lower(trim(e.name)) = lower(trim(d.name))
                AND e.latitude = d.latitude AND e.longitude = d.longitude AND e.radius_m = d.radius_m)
ORDER BY d.id;

ALTER TABLE device_zones ADD COLUMN zone_id INTEGER REFERENCES family_zones(id) ON DELETE CASCADE;
UPDATE device_zones SET zone_id = (
    SELECT f.id FROM family_zones f
    WHERE lower(f.name) = lower(trim(device_zones.name))
      AND f.latitude = device_zones.latitude AND f.longitude = device_zones.longitude
      AND f.radius_m = device_zones.radius_m);
CREATE UNIQUE INDEX idx_device_zones_link ON device_zones(device_id, zone_id);
CREATE INDEX idx_device_zones_zone ON device_zones(zone_id);

-- The weather's zone is a place: the family zone. weather_zone_id stays, NULL and unread (a column
-- with a reference isn't dropped).
ALTER TABLE device_policy ADD COLUMN weather_family_zone_id INTEGER
    REFERENCES family_zones(id) ON DELETE SET NULL;
UPDATE device_policy SET weather_family_zone_id =
    (SELECT zone_id FROM device_zones WHERE id = device_policy.weather_zone_id)
WHERE weather_zone_id IS NOT NULL;
UPDATE device_policy SET weather_zone_id = NULL;

-- Each parent's alerts per link (no row = both on). The old per-zone switches become every
-- parent's own - removed ones too, so a parent restored by reset-2fa finds them as they were.
CREATE TABLE zone_alerts (
    admin_id INTEGER NOT NULL REFERENCES admin_users(id) ON DELETE CASCADE,
    device_zone_id INTEGER NOT NULL REFERENCES device_zones(id) ON DELETE CASCADE,
    arrive INTEGER NOT NULL DEFAULT 1,
    leave INTEGER NOT NULL DEFAULT 1,
    PRIMARY KEY (admin_id, device_zone_id)
);
INSERT INTO zone_alerts (admin_id, device_zone_id, arrive, leave)
SELECT a.id, d.id, d.notify_arrive, d.notify_leave
FROM device_zones d CROSS JOIN admin_users a
WHERE d.notify_arrive = 0 OR d.notify_leave = 0;
CREATE INDEX idx_zone_alerts_link ON zone_alerts(device_zone_id);

ALTER TABLE device_zones DROP COLUMN name;
ALTER TABLE device_zones DROP COLUMN latitude;
ALTER TABLE device_zones DROP COLUMN longitude;
ALTER TABLE device_zones DROP COLUMN radius_m;
ALTER TABLE device_zones DROP COLUMN notify_arrive;
ALTER TABLE device_zones DROP COLUMN notify_leave;
```

Revision 1's SQL was run by the design QA on a seeded database: ids, sides and cursors were kept, the served values
were equal, and the FK and integrity checks were clean. Revision 2 drops the rename and adds the weather move and
alert rows for every parent; the migration test (§10) runs it.

### 3.2 Notes for the implementer

- **"Identical" means typed-in.** A zone placed with "Bruk telefonens posisjon" or a tap on the map has the centre of
  that moment, so two such zones on two phones are never identical and stay two family zones. The parent turns one
  on for the other phone and deletes the duplicate.
- **The fold key's `lower()` is ASCII only** (SQLite without ICU). "Øvre" and "øvre" with the same circle stay two
  zones. That is harmless (decision 5).
- **No unique index on names.** Names are unique only when a name changes; a migrated pair keeps both names.
- **The unique `(device_id, zone_id)` index always holds.** A phone can't hold two zones of one group: its names are
  unique after trimming, ignoring case, and `to_lowercase` folds at least what ASCII `lower` does.
- **`zone_id` stays nullable in the schema**, because `ADD COLUMN` with a reference can't be `NOT NULL`. Every write
  path sets it, and the migration test reads that no row is NULL.
- **`DROP COLUMN`** applies to none that is indexed, part of a key, or named in another column's CHECK. `created_by`
  (a reference) stays on the link.
- **Ids are new for `family_zones`.** Tests use families where zone ids and link ids differ (§10).
- **No backup schema changes.** `backup_schema` needs nothing new. `migrations_from_73_in_order` gains the file.

### 3.3 What is kept

| What | Where after 0181 |
|---|---|
| Each phone's zones, ids, circles as served | the links (same ids) → their family zone (same centre and radius) |
| Side, pending side, cursor, `side_since` | untouched on the link: no zone starts over, no event, "Her nå" stays |
| The Arrive/Leave switches | a `zone_alerts` row per parent where a switch was off (push only, decision 7) |
| History: events and their words | untouched (`params.zone_id` = the link id, the names as raised); retention unchanged |
| The weather zone | `weather_family_zone_id` = the link's family zone |
| The phone's fences and Wi-Fi zone links | untouched: the served zones are equal in value, so the fence fingerprint and `PlacesPlan.zone` keys are unchanged |

### 3.4 Rollback

- The migration is forward-only, like every migration.
- The server takes `data/backups/pre-migrate-<from>-<to>.db` before it runs (`startup::pre_migration_backup`).
- An older binary refuses the migrated database. A rollback therefore restores that copy together with the older
  binary (DEPLOY.md, "Rolling back the server"). Zone edits, links, alert switches and fixes made since the update
  are lost.
- The phones need nothing: the restored server serves the same ids and circles. A zone created after the update
  disappears, and its phone drops the fence at the next sync.

### 3.5 Optional: a read-only preview of the fold

The fold folds nothing on the owner's data (every zone is on one phone). For another install, or to check, this
prints what 0181 would do: counts and zone names, never a coordinate.

**It must run on a copy, not on the live database.** The image is distroless: no shell and no `sqlite3` to
`docker compose exec`, and the server has no SQL subcommand. So the newest backup zip on the host is the copy, and it
must be read before the update.

Make a fresh backup on the Backups page first. Then, on the host, in the folder with `compose.yaml` (`sqlite3` and
`unzip` from apt):

```
f=$(sudo ls -t backups/ | grep '^backup-.*\.zip$' | head -1); d=$(mktemp -d)
sudo unzip -q "backups/$f" kidphone.db -d "$d"
sudo sqlite3 -readonly -header -column "$d/kidphone.db" < fold-preview.sql
sudo rm -rf "$d"
```

`fold-preview.sql` (SELECTs only):

```sql
-- Design 135: what migration 0181 would fold. Read-only; counts and zone names, never a coordinate.
WITH member AS (
    SELECT d.id, d.name,
           (SELECT MIN(e.id) FROM device_zones e
             WHERE lower(trim(e.name)) = lower(trim(d.name))
               AND e.latitude = d.latitude AND e.longitude = d.longitude
               AND e.radius_m = d.radius_m) AS keeper
    FROM device_zones d),
fold AS (
    SELECT keeper, (SELECT trim(k.name) FROM device_zones k WHERE k.id = keeper) AS name,
           COUNT(*) AS phones
    FROM member GROUP BY keeper)
SELECT 1 AS part, 1 AS k, 'zones now' AS what, (SELECT COUNT(*) FROM device_zones) AS n
UNION ALL SELECT 1, 2, 'family zones after', (SELECT COUNT(*) FROM fold)
UNION ALL SELECT 1, 3, 'folded (used by 2+ phones)', (SELECT COUNT(*) FROM fold WHERE phones > 1)
UNION ALL SELECT 1, 4, 'same name, other place (kept apart)',
    (SELECT COUNT(*) FROM fold f WHERE EXISTS
        (SELECT 1 FROM fold o WHERE lower(o.name) = lower(f.name) AND o.keeper <> f.keeper))
UNION ALL SELECT 1, 5, 'over the family cap of 50', (SELECT COUNT(*) > 50 FROM fold)
UNION ALL SELECT 1, 6, 'zones with an alert off',
    (SELECT COUNT(*) FROM device_zones WHERE notify_arrive = 0 OR notify_leave = 0)
UNION ALL SELECT 1, 7, 'weather zones set',
    (SELECT COUNT(*) FROM device_policy WHERE weather_zone_id IS NOT NULL)
UNION ALL SELECT 2, keeper, name, phones FROM fold
ORDER BY part, k;
```

The second part lists each family zone after the fold, with how many phones use it.

## 4. The server's rule, the policy, the intake

- **`zones::list(db, device_id)`** joins `device_zones` to `family_zones`. It returns the `Zone` the code uses today:
  `id` (the link), `zone_id`, `name`, the circle and the state. `notify_arrive`/`notify_leave` are gone from `Zone`.
- **Working on links:** `evaluate_stored`, `seed`/`start_over`, `forget_sides`, `served_interval_of`, `policy_zones`
  and `devices::zone_now`.
- **`event_of`** no longer filters: every crossing is an event (decision 7).
- **The weather** (`weather.rs`'s `cell_of`, `weather_page.rs`'s card and save) reads `weather_family_zone_id` and the
  family zone's centre. Its picker lists `family_zones`, and its save checks the id against that table.
- **Unchanged:**
  - the two-fix rule;
  - the coarse and Wi-Fi-alone classes (125b, 131);
  - the seed;
  - push only while news;
  - retention by the phone's days;
  - the 30-min floor (it keys on the phone having links);
  - the `zone_watch` intake.
- **A new link** inserts the row and runs `start_over` with the phone's own fixes, in the save's `BEGIN IMMEDIATE`. It
  never replays history.
- **A moved family zone** runs `start_over` on each of its links in one transaction.
- **The events' words** take the family zone's name at raise time (`ZoneParams.zone`, as today).

## 5. The PWA

Every page here is in Locate's tab, and a save never lands at the top:

- forms post to an anchor;
- the autosave goes through `scroll-restore.js`;
- a refusal is a 400 re-render at its form;
- for the autosave form, a refusal is a 303 back to the zone's cell, with the reason under it. The response carries
  `Refused` in its extensions, so `settings_log` doesn't count it as a save.

### 5.1 Where the family's list lives

- **Locate, all phones** (`/devices/locate`, `#all`): a new group under Update All, with one row "Soner" whose value
  is the family's count. It leads to `/zones`.
- **A phone's Zones page** ends with "Alle soner ›", leading to `/zones?device={id}` (Back returns to that phone).

```
Finn                                   (all phones)
 [ map with every phone ]
 ┌──────────────────────────────────────┐
 │ Oppdater alle                        │
 └──────────────────────────────────────┘
 ┌──────────────────────────────────────┐
 │ ◎ Soner                        3   › │   NEW
 └──────────────────────────────────────┘
```

### 5.2 The family's zones `/zones` (`family_zones.html`, new)

```
‹ Finn                Soner
 ┌──────────────────────────────────────┐
 │ Hjem                               › │
 │ 150 m · Telefon 1, Telefon 2         │
 │ Skole                              › │
 │ 200 m · Telefon 1                    │
 │ Skole                              › │
 │ 300 m · Telefon 2                    │
 │ Fotball                            › │
 │ 150 m · ingen telefon                │
 │ Legg til sone                        │
 └──────────────────────────────────────┘
  (i)
```

- **Rows** are oldest first. The sub-line is the radius plus the phones that use the zone, by name, or "ingen
  telefon" (quiet, decision 8). This tells two zones of one name apart.
- **After a save**, its line sits under its row (`?zone=saved&zone_id=N#zone-N`). After a delete, at `#zones`.
- **At 50 zones**, "Legg til sone" becomes the note "50 soner er det meste".
- **The (i)** says: "Sonene er felles for familien. Velg hvilke hver telefon bruker under telefonens Soner." With no
  zones yet, it shows the existing `footer_empty`.
- **No coordinates** on the page. It is a `no_store_path` anyway, like the other zone pages.

### 5.3 The zone's editor `/zones/new`, `/zones/{zone_id}` (`device_zone.html` becomes `family_zone.html`)

Today's form and `zones.js`, plus a "Telefoner" group. The Arrive/Leave switches move to the phone's page, because
they are each parent's.

```
‹ Soner             Hjem              [Lagre]
 [ map: the circle, tap to move ]
 ┌──────────────────────────────────────┐
 │ Navn                            Hjem │
 │ Breddegrad                 59.913900 │
 │ Lengdegrad                 10.752200 │
 │ Radius                        150  m │
 │ ───────────o──────────               │
 │ Bruk posisjonen til Telefon 1        │
 │ Bruk posisjonen til Telefon 2        │
 └──────────────────────────────────────┘
  Trykk på kartet for å flytte midten.  (i)
 TELEFONER
 ┌──────────────────────────────────────┐
 │ ☑ Telefon 1                          │
 │ ☑ Telefon 2                          │
 └──────────────────────────────────────┘
 ┌──────────────────────────────────────┐
 │ Slett sone…                          │
 └──────────────────────────────────────┘
```

- **The place group's (i)** gains one line: "Sonen er felles. Endringer gjelder alle telefonene som bruker den." No
  orange line.
- **"Bruk posisjonen til {phone}"**: one button for each phone that has a fix, the `device` phone first.
- **Where the map opens:** an existing zone opens on itself. A new zone opens at the `device` phone's newest fix,
  else at the newest fix of any phone, else with the "no location yet" note.
- **Telefoner** has one checkbox per phone.
  - On a new zone opened with `?device=N`, that phone starts ticked; otherwise none does.
  - No ticks at all is allowed (decision 8).
  - A phone already at 10 zones is refused on Save, under the group: "{phone} har allerede 10 soner." (with
    `aria-invalid`; nothing is written).
- **What the form carries** (H1, decision 12):
  - `was_name`, `was_latitude`, `was_longitude` and `was_radius_m`, the values the page was drawn with;
  - `was_phone_<id>` per phone (1 or 0).
- **Save** runs in one `BEGIN IMMEDIATE`:
  - It writes a field only when it differs from its `was_` value. The centre (latitude and longitude) counts as one
    field, so another parent's change to the other fields stays.
  - It links or unlinks only the phones whose tick differs from `was_phone_<id>`. A phone linked meanwhile from its
    own page stays linked.
  - It runs `start_over` on new links, and on every link when the centre or radius was written.
  - Each phone whose policy changed is nudged.
- **Where the save lands:**
  - From a phone (`device` is a real phone id; anything else is ignored): `/devices/{device}/zones?zone=saved&
    zone_id=Z#zone-Z`.
  - From the family list: `/zones?zone=saved&zone_id=Z#zone-Z`.
  - A save that changed nothing lands on the zone's row with no line.
- **Refusals** (400 at `#zone-form`, as typed), with nothing written:
  - a changed name that is empty, over 40, has invisible characters, or is taken in the family ("Familien har
    allerede en sone med det navnet.");
  - a coordinate or radius out of range;
  - a 51st zone;
  - a phone at its cap.
- **Slett sone…** (`<details class="confirm">`):
  - The question is "Slette Hjem? Den brukes av Telefon 1 og Telefon 2.", or "Slette Hjem?" with no phone.
  - Delete cascades (decisions 9 and 10) and nudges the phones that used the zone.
  - It lands on `#zones` of the page it came from.

### 5.4 A phone's Zones page `/devices/{id}/zones` (`device_zones.html`, reworked)

One form posting to `POST /devices/{id}/zones` (today's create route, repurposed). Every control is `data-autosave`
with a `<noscript>` Save, like the calendar page's per-calendar switches (`device_calendar.html`, `#calendars`).

```
‹ Finn                Soner            Telefon 1
 [orange: Soner trenger Jevnlig  (i)]   (only as today)
 ┌──────────────────────────────────────┐
 │ Hjem                          [●══]  │
 │ 150 m · Her nå                       │
 │    Kommer                     [●══]  │
 │    Drar                       [══○]  │
 ├──────────────────────────────────────┤
 │ Skole                         [●══]  │
 │ 200 m                                │
 │    Kommer                     [●══]  │
 │    Drar                       [●══]  │
 ├──────────────────────────────────────┤
 │ Fotball                       [══○]  │
 │ 150 m                                │
 └──────────────────────────────────────┘
  (i)
 ┌──────────────────────────────────────┐
 │ Legg til sone                        │
 │ Alle soner                         › │
 └──────────────────────────────────────┘
```

- **One cell per family zone**, in the family's order. A toggle never reorders the cells, so a save comes back with
  the toggled cell where it was.
- **The first row** of a cell is the zone's name and its sub-line ("150 m", then "· Her nå" or "· Sist sett i sonen
  … 14:02" as today), with a switch: on means linked. Its `aria-label` is "Bruk på {phone}".
- **While linked**, two more rows, "Kommer" and "Drar": the signed-in parent's pushes for this phone and zone.
- **The group's (i)** keeps today's setting-dependent footer (Jevnlig, Bare når du spør, the watch line) and gains one
  line: "Kommer og Drar er dine egne varsler for denne telefonen." Today's "For alle foreldre …" line goes.
- **The fields** (H1):
  - every cell: `zone=<id>`, `was_use_<id>` (1/0) and `use_<id>`;
  - only cells drawn linked: `alerts=<link id>`, `was_arrive_<link>`/`was_leave_<link>` and
    `arrive_<link>`/`leave_<link>`.
- **The handler** runs in one `BEGIN IMMEDIATE`:
  - **Links.** For each `zone=` id whose `use_` differs from `was_use_`: link it when it isn't linked now, or unlink
    it when it is. A zone deleted meanwhile is skipped. A zone not named in the form (one made in another tab) is
    never touched. New links get `start_over`.
  - **Alerts.** For each `alerts=` link id that still exists now (the same row, so an unlink-and-relink meanwhile
    doesn't match), write this parent's arrive or leave only where it differs from its `was_` value. A cell just
    switched on has no alert fields, so its alerts stay at the default on.
  - **Cap.** A link that would make an 11th refuses the whole post. The 303 goes to `?refused=full&zone_id=Z#zone-Z`,
    with "10 soner er det meste." under that cell, and nothing is written.
  - **Nudge.** A change of links nudges the phone. A change of alerts alone doesn't.
  - **Landing.** The first zone that changed gives the 303 its `#zone-Z` (`?zone=saved&zone_id=Z`). When nothing
    changed, it lands on `#zones` (a stale page whose change was already made).
- **"Legg til sone"** opens `/zones/new?device={id}` with this phone ticked, and lands back here. **"Alle soner"**
  opens `/zones?device={id}`.
- **Unchanged:** the warnings, the watch line and Locate Regularly stay as today ("only while the phone has zones"
  now means links).
- **No family zones yet:** no cells, only the two rows, and `footer_empty` in the (i).

### 5.5 Routes

| Route | What |
|---|---|
| `GET /zones` | the family list (`?device=N` sets Back) |
| `GET /zones/new`, `POST /zones` | a new zone (`device` carried in a hidden field) |
| `GET /zones/{zone_id}`, `POST /zones/{zone_id}` | the editor and its save |
| `POST /zones/{zone_id}/delete` | delete |
| `GET /devices/{id}/zones`, `POST /devices/{id}/zones` | the phone's page and its autosave |
| `GET /devices/{id}/zones.json` | unchanged: the phone's linked zones for the Locate map (`id` = link) |
| Weather's zone pick | its list becomes the family's zones (`weather_family_zone_id`) |
| removed | `/devices/{id}/zones/new`, `/devices/{id}/zones/{zone_id}`, `…/delete` (no redirect; push targets are `/devices/locate?device=N#location`) |

- Every route needs full auth and CSRF.
- `no_store_path` covers `/zones`, `/zones/new`, `/zones/{zone_id}` (coordinates) and `zones.json`, as today.
- An unknown zone or phone is a 404.

### 5.6 Words (nb / en)

Checked against root CLAUDE.md, "Words in the UI": explanations sit behind the (i), and no choice gets an orange line.

| Key | nb | en |
|---|---|---|
| family list row sub | `{radius} m · {phones}` / `{radius} m · ingen telefon` | `{radius} m · {phones}` / `{radius} m · no phone` |
| family list (i) | Sonene er felles for familien. Velg hvilke hver telefon bruker under telefonens Soner. | Zones are the family's. Pick the ones each phone uses on its Zones page. |
| family full | 50 soner er det meste | 50 zones is the most |
| editor place (i), added line | Sonen er felles. Endringer gjelder alle telefonene som bruker den. | The zone is shared. A change applies to every phone that uses it. |
| editor group | Telefoner | Phones |
| use location | Bruk posisjonen til {phone} | Use {phone}'s location |
| name taken | Familien har allerede en sone med det navnet. | The family already has a zone with that name. |
| phone at cap (editor) | {phone} har allerede {max} soner. | {phone} already has {max} zones. |
| delete question | Slette {name}? Den brukes av {phones}. / Slette {name}? | Delete {name}? {phones} use it. / Delete {name}? |
| phone page switch aria | Bruk på {phone} | Use on {phone} |
| phone page rows | Kommer / Drar (existing keys) | Arrive / Leave |
| phone page (i), added line | Kommer og Drar er dine egne varsler for denne telefonen. | Arrive and Leave are your own notifications for this phone. |
| phone at cap (autosave) | {max} soner er det meste. | {max} zones is the most. |
| link row | Alle soner | All Zones |
| security log labels | Sone tatt i bruk / Sone tatt bort | Zone linked / Zone unlinked |

Gone:

- `locate.zone.notify_title` and `notify_footer`;
- `locate.zones.sub`'s notify part and `locate.zones.notify.*`: the sub-line no longer says which alerts are on,
  because the switches are in view;
- `locate.zone.error.full`'s "Slett en først".

## 6. Notifications

- **The event is always stored.** A crossing raises `zone_arrived`/`zone_left` as today, with no switch in the way
  (decision 7). The inbox, Activity and the summary's "Hjem siden 15:42" are unchanged.
- **The push is filtered when it is queued**, at the raise. `push::queue_as` inserts one delivery per active
  subscription. For a zone kind it adds one condition, read from the event itself, so no caller passes it:

  ```sql
  AND NOT EXISTS (SELECT 1 FROM parent_events e JOIN zone_alerts za
                    ON za.device_zone_id = json_extract(e.params, '$.zone_id')
                 WHERE e.id = ?event AND za.admin_id = s.admin_id
                   AND ((e.kind = 'zone_arrived' AND za.arrive = 0)
                     OR (e.kind = 'zone_left' AND za.leave = 0)))
  ```

  - For every other kind the subquery finds nothing.
  - A retry re-sends a delivery that already exists, and is not filtered again. A switch turned off after the raise
    doesn't recall a push already queued.
  - Zone kinds have no push budget, so there is no digest for them.
- **Notify Me About** keeps "Soner: kom og dro" (`push_zones`) as each parent's switch for all zones. Per phone and
  zone is the phone's Zones page.
- **A new parent** starts with every link's alerts on (no rows).
- **A removed parent** (`removed_at`) gets no push: `queue_as` takes only active accounts. Their rows stay, because
  the app never deletes an account. `kid_phone_server reset-2fa` restores a removed account, and its switches then
  apply again. The migration writes the old switches for removed parents too, so a restored parent finds them as
  they were.

## 7. The phone: fences, the limit, the zone links (designs 64, 124, 125, 131, 134)

- **Nothing changes on the phone.** It still gets its own list of at most 10 circles with ids. The ids are the
  links', unchanged at the migration, so:
  - `ZoneFences`' fingerprint is unchanged and no fence is re-registered;
  - `PlacesPlan.zone`'s key `hmac(id|lat|lng|radius)` is unchanged, so every learned **zone link** (125 D12) and every
    `check: "link"` (131) survives.
- **After an edit of a shared zone's circle**, every phone using it sees a new fingerprint and re-registers, as one
  moved zone does today. Each phone drops its Wi-Fi zone link for that zone and relearns it (at least 3 teaching
  fixes over at least 2 days). On the server every link starts over silently. A rename touches neither.
- **Unlink and relink** give a new id: a new fence, and a new zone link to learn (decision 11). The weather is not
  affected (decision 9).
- **When a phone's last link goes**, it behaves as when the last zone is deleted today:
  - `zoneWatchActive` ends;
  - `stillWatchActive` (124) keeps the heartbeat on a Regularly phone;
  - the served interval's 30-min floor goes.
- **134's still-place fingerprint** is not keyed by zones, so it is unaffected.
- **The fences limit:** Android's proximity alerts have no cap (64 §2), the server sends at most `MAX_ZONES` 10 per
  phone, and `sanitize_zone_watch` still accepts 0..=100.

## 8. Privacy and security

- **Nothing new leaves the server.**
  - The policy is the same per-phone list without names (64 decision 9).
  - A zone a phone doesn't use never reaches that phone: one phone never learns the other's school.
  - The weather sends the phone a forecast, never the zone (design 75, unchanged).
- **What a parent sees:**
  - Every phone's zones, as before; zones were already the family's for every parent.
  - The editor's "Bruk posisjonen til …" uses each phone's newest fix, which every parent already sees on Locate.
    The page is `no-store`.
- **Security log:**
  - `zone_added`, `zone_changed` and `zone_deleted` become family lines: "Hjem, 150 m", "Hjem - moved, radius 150 ->
    250 m", "Hjem (Telefon 1, Telefon 2)".
  - `zone_linked` and `zone_unlinked` are new: "device 3 (Telefon 1): Hjem".
  - None ever carries coordinates.
  - An alert switch alone becomes a `settings_saved` line from the middleware (routes and ids, never values), like
    Notify Me About.
- **Input handling:**
  - Every name is escaped by Askama and set with `textContent`.
  - Form ids and `was_` values are parsed as integers or numbers and checked against the tables in the transaction.
  - `device` is a phone id that must exist; anything else is ignored.
- **Retention is unchanged.** Zone events go with the phone's location history. A family zone outlives a deleted
  phone (its link cascades) and then shows "ingen telefon".

## 9. Steps

| # | Step | Size |
|---|---|---|
| B | Server, **one reviewable unit** (the data alone wouldn't compile against the old pages): migration 0181; `zones::list`, links and family-zone writes (`start_over` per link); `event_of` without the switch; the push filter; the weather's family zone and picker; the pages (`/zones`, the editor with Telefoner, the `was_` diff and the per-phone location buttons in `zones.js`, the phone's page as one autosave form, Locate's all-phones row); routes, `no_store_paths`, nb/en, the security log words; the tests (§10); `docs/notes/server/zones.md`, `pwa.md`, `notifications.md` | M |
| G | Emulator gate §6bt on Android 14 and 16 (§11) | S |

**No launcher step.** `docs/notes/launcher/zones-and-power.md` gains one line: a policy zone's id is the phone's link
to a family zone.

**Release.** A server release ships it. It works with every launcher since design 64 step 2, because the policy is
unchanged, and it needs phase D's 0180 on the server first.

## 10. Tests (server)

The rule's tests use a family whose zone ids and link ids differ (a zone linked to two phones before the second zone
is made).

**`migration_0181_folds_and_keeps_every_phone`** (`tests::cleanup::migrated_before(181)`).

The seed:

- phones Telefon 1, 2 and 3;
- two active parents and one removed;
- Telefon 1's "Hjem" with `notify_leave` 0, side inside, a cursor and a pending side;
- Telefon 2's " hjem" with the same circle;
- three "Skole" zones in three places, one per phone;
- a group with both switches off;
- more than 50 zones after the fold (the cap only blocks adding);
- a name over 40 characters;
- Telefon 1's weather on its Hjem;
- zone events with params.

After the migration:

- Hjem is one family zone used by two phones, and the three Skole stay three, each with its name.
- Every link keeps its id, state, cursor and `created_at`. No `zone_id` is NULL, and `(device_id, zone_id)` is unique.
- `zone_alerts` has a row with leave 0 for **every** parent, the removed one included, on Telefon 1's Hjem. It has a
  row with both off for the both-off group, and none for links with both on.
- `weather_family_zone_id` is Hjem's family zone and `weather_zone_id` is NULL. Unlinking Hjem from Telefon 1
  afterwards keeps the weather; deleting Hjem clears it.
- The events' params are unchanged.
- The over-40 name saves a radius change unchanged, and a family over 50 loses only "Legg til sone".
- **The policy's `zones` for each phone equals, value for value, the `(id, latitude, longitude, radius_m)` read
  before the migration.** This is the proof that the phones see no change. `policy_json_keys_snapshot` pins only the
  keys (L4).

`migrations_from_73_in_order` gains the file.

**`zones::tests` and `tests::zones`.**

- **Judging:**
  - A zone linked to two phones is judged per phone: one arrives, the other raises nothing.
  - A moved family zone starts every link over silently and nudges both phones. A rename keeps every side.
  - A new link seeds from its phone's fresh fine fix and never replays history.
  - Unlinking deletes the state and the alerts, and drops the zone from the policy. Relinking gives a new id and a
    silent first side.
  - A zone used by no phone is in no policy and never judged.
  - A delete cascades to the links, the alerts and the weather, and nudges the phones that used the zone.
- **Caps and names:**
  - An 11th link is refused from both pages, with nothing written and the refusal at `#zone-Z`.
  - A 51st zone is refused.
  - A taken or over-long name is refused only when the name changed.
- **Stale forms (H1):**
  - A stale phone page doesn't unlink a zone linked meanwhile.
  - A stale phone page doesn't turn alerts off or on that it didn't change, and doesn't write alerts for a link
    replaced meanwhile.
  - A stale editor keeps a link made meanwhile and another parent's radius.
  - A post that changed nothing lands on `#zones`.
- **Alerts and push (L1):**
  - Every crossing is an inbox event, also with every switch off.
  - Parent B's arrive off for Telefon 1/Hjem means the arrival's deliveries are queued for A only.
  - A removed parent gets none.
  - A new parent gets the push.
  - A cell just switched on keeps its alerts on.
  - The filter runs at queueing: a switch turned off afterwards leaves the queued delivery as it was.
- **Landing:**
  - Every form lands on its row or group: `every_form_on_the_locate_page_returns_to_its_group` and
    `shell::every_form_on_every_page_lands_on_its_own_group` gain the three pages.
  - `device` is whitelisted: an unknown id lands on `/zones`.
- **Other:**
  - `no_store_paths` covers the new pages.
  - The security log lines carry no coordinates.
  - The weather picker lists the family's zones.

**Elsewhere.** `seeded_family` is updated. `jstest/zones.test.js` checks that the per-phone location buttons write the
fields. The launcher's tests are unchanged.

## 11. Emulator gate (`docs/testing/emulator.md` §6bt, Android 14 and 16 only)

**Setup:** two phones of one family, an API 34 image (Android 14) and `jelly36` (Android 16), both enrolled to one
server. The debug launcher, with mock fixes as in §6t (`cmd location providers add-test-provider fused`). The
launcher is unchanged, so 15 and 17 don't run.

1. **Before the update.** On a server at `main` with 0180:
   - On the 14: "Hjem" (a typed centre, 150 m) and "Skole". On the 16: "Hjem" with the same numbers.
   - Both phones Regularly, with a fix inside Hjem on both, twice 2 min apart ("Her nå").
   - Drar off on the 14's Hjem, and the 14's weather zone set to Hjem.
   - Note `adb shell dumpsys location | grep -A3 -i geofence` on both.
2. **Update the server (0181).**
   - `/zones` shows Hjem (both phones) and Skole (the 14).
   - "Her nå" is still there on both, and no event was raised.
   - At the next sync, `logcat -s ZoneWatch` shows no re-registration and `dumpsys location` shows the same fences.
   - The 14's Weather still says Hjem.
3. **Pick per phone.** Switch Skole on for the 16:
   - The page comes back at Skole's cell, and the 16 gets one more fence after the nudge.
   - A fix inside Skole, twice, raises nothing (a silent first side).
   - A fix 1 km away and the confirm raise "… left Skole" for the 16 only.
4. **Edit a shared zone.** Move Hjem 300 m in the editor (the (i) shows the shared line):
   - Both phones re-register one fence with the new circle, and neither raises an event.
   - A crossing on each phone then raises the event for that phone.
5. **Alerts per parent.** Parent B turns Kommer off for the 16's Hjem. An arrival on the 16 shows in both inboxes and
   queues one `push_deliveries` row, for parent A only.
6. **Stale page.** Open the 16's Zones page in two tabs. In tab 1 switch Fotball on; then in tab 2 switch Kommer for
   Hjem. Fotball stays linked.
7. **Unlink, relink, delete.**
   - Hjem off for the 14: its fence is removed (`dumpsys`) and the 14's Weather still says Hjem.
   - Hjem on again: a new fence and a silent first side.
   - Delete Hjem: both phones' fences go, and the 14's Weather says none.
8. **Cap.** With 10 zones on the 14, an 11th switch comes back off at its cell with "10 soner er det meste."

## 12. The owner's answers (2026-11-25) and the settled defaults

There are no open questions.

- **Same name, different places (was Q2):** no rename. Both keep their name, and the family list's sub-line names the
  phones that use each (decision 5). The owner's zones are all on one phone, so the fold folds nothing there; it stays,
  minimal, for other installs.
- **Events (was Q3):** always stored; the per-parent switches filter only the push (decision 7).
- **Settled at their defaults (the coordinator, 2026-11-25):**
  - Only identical typed-in zones fold, and near-duplicates are not merged (§3.2).
  - Turning a zone off for a phone forgets that phone's state for it, with no confirm (decision 11). Since M1 the
    weather is not affected.
  - The all-phones map doesn't draw zones.
  - The list lives behind Locate's all-phones "Soner" row and each phone's "Alle soner".

## 13. Revision 2: the design QA (1H 3M 6L) and the owner's answers

- **H1 (stale forms):** the `was_*` diff on both pages (decision 12, §5.3, §5.4), and the landing from the diff;
  tests and gate step 6.
- **M1 (weather):** `weather_family_zone_id`, the family picker, unlink keeps the weather; gate steps 2 and 7.
- **M2 (fold):** "identical" means typed-in (§3.2); every name rule applies only to a changed name (decision 5); the
  read-only preview (§3.5, optional).
- **M3 (events):** always stored, push filtered per parent, no computed `notify_*` (decision 7, §6); removed parents
  and `reset-2fa` (§6).
- **L1:** the filter at queueing, without the retry/digest claim (§6).
- **L2:** a post that changed nothing lands on `#zones`.
- **L3:** the migration seed grows (§10). The rename cases went with the owner's answer.
- **L4:** the value equality in the migration test is the proof; the snapshot pins keys.
- **L5:** "10 soner er det meste."
- **L6:** unchanged.
- **Names:** example names are "Telefon 1"/"Telefon 2", `{phone}` in copy.
- **Steps:** S1 and S2 are one unit (§9).

## 14. Out of scope

- Near-duplicate merging
- Zone schedules
- Polygons
- The kid's view of zones
- Other families (design 63)
- Zones on the all-phones map
- CalDAV's "Delt i familien" (item 6, its own design)
