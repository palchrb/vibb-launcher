# 135 - Zones shared by the family, picked per phone (a revision of design 64)

Status: **design, revision 1**, 2026-11-25 (branch `design-135`), read against `main` a0b68549a. Item 5 of PLAN's
locked list, approved by the owner on 2026-11-20: "alt av soner bør jo kunne brukes per familie, og ikke per barn ...
alt du satt av soner kunne velges og brukes per barn". **Server only:** one migration (0181), the zone pages, the
push filter. No launcher code, no contract file, no change to the policy JSON.

## 0. Short answer

- **One zone list for the family** (`family_zones`: name, centre, radius) and **a link per phone and zone**: the
  existing `device_zones` rows, which keep their ids, their side state, their cursor and the events that name them.
  A second child gets "Hjem" with one switch, not by drawing it again.
- **The phone gets what it gets today.** `location_policy.zones` still lists only that phone's zones as
  `{id, latitude, longitude, radius_m}`, and `id` is still the `device_zones` id (now the link's id). After the
  migration every phone is served byte-identical zones, so its fences and its learned Wi-Fi zone links (design 125
  D12, keyed by id and circle) stay as they are.
- **Migration 0181 folds identical zones.** Zones with the same name (case and spaces aside), the same centre and the
  same radius become one family zone. Each phone's link keeps its state. The zone's Arrive/Leave switches become
  each parent's own switches for that phone and zone. Nothing is judged again and nothing is raised.
- **Alerts per parent, per phone, per zone.** A parent's "Kommer"/"Drar" switches for a phone and a zone decide
  that parent's push. An event is raised only while at least one parent wants it, as today with one switch.
- **PWA:** the family's list at `/zones` (a "Soner" row on Locate's all-phones view). The zone's editor at
  `/zones/{id}` has a "Telefoner" checkbox group. The phone's Zones page becomes one auto-saving form: per family
  zone, a switch for this phone, and while it is on, the signed-in parent's "Kommer"/"Drar".

## 1. Today (design 64, `docs/notes/server/zones.md`)

- `device_zones` (0091) is one row per phone and zone. It holds the circle (`name`, `latitude`, `longitude`,
  `radius_m`), the family's switches `notify_arrive`/`notify_leave`, and the rule's state (`side`, `side_since`,
  `pending_*`, the cursor `evaluated_at`/`evaluated_fix_id`). A name is unique per phone (checked in code). There
  are at most 10 zones per phone (`MAX_ZONES`).
- The row's id is used in five places: the policy's `zones[].id` (the phone's fences, `vibb-zone:<id>`, and its
  zone-link key `hmac("zone|id|lat|lng|radius")`, `PlacesPlan.zone`); events' `params.zone_id` and `dedup_key`
  (`<zone id>:<side>:<at>`); `device_policy.weather_zone_id` (FK, `ON DELETE SET NULL`, 0122); `zones.json`; the
  zone's page URL.
- The pages: Locate (one phone), with its "Soner" row → `/devices/{id}/zones` (the list, "Her nå", warnings) → the
  zone's page `/devices/{id}/zones/{zone_id}` (map, name, centre, radius, Arrive/Leave, Delete).
- A push goes to a parent when `notification_prefs.push_zones` is on (one switch for every phone and zone) and the
  zone's switch for that direction raised the event at all.

## 2. Decisions

1. **The family zone holds the place, the link holds everything else.** `family_zones` stores `name`, `latitude`,
   `longitude`, `radius_m`, `created_by`, `created_at` and `updated_at`. `device_zones` is the link: `device_id`,
   `zone_id`, the state and the cursor, and `created_by`/`created_at`/`updated_at` (now when and by whom it was
   linked). The rule (`zones::step`, `evaluate`) is unchanged and judges each link on its own phone's fixes.
2. **The link's id is the id the phone and the events see.** It is unchanged by the migration. A new link gets a new
   id. Unlinking deletes the row. The policy, `zones.json`, events and the weather zone keep naming `device_zones.id`.
3. **No contract change.** `zones::policy_zones` reads the circle through the link. `policy_json_keys_snapshot` and
   `PolicyResponseCompatTest` stay as they are. A zone not linked to a phone never reaches that phone.
4. **Editing a shared zone changes it for every phone that uses it.** A moved centre or a changed radius runs
   `start_over` on every link of the zone (a silent first side, as design 64 decision 4) and nudges each phone. A
   rename changes only the name (no state, no nudge needed, but nudged anyway, as today's every save). The page says
   so behind the place group's (i).
5. **Names are unique in the family, checked only when a name changes.** The rule is case-insensitive
   (`to_lowercase`). A name the migration left as it was never blocks a radius edit. Other rules unchanged: 1-40
   characters, no `Cc`/`Cf`.
6. **Caps: 10 links per phone** (`MAX_ZONES`, unchanged: the phone's work, the page), **50 zones in the family**
   (`MAX_FAMILY_ZONES`, a code constant: the phone page renders one cell per family zone). The fences' sanity cap
   (`zone_watch.fences` <= 100) is untouched; a phone never holds more than 10.
7. **Alerts are each parent's, per link.** In `zone_alerts(admin_id, device_zone_id, arrive, leave)`, no row means
   both on. A **push** goes to a parent whose Notify Me About "Soner" (`push_zones`) is on and whose switch for the
   link and the direction is on. An **event** is raised when at least one parent who isn't removed has that
   direction on for the link. With one parent that is today's "the zone's switch decides whether there is an event";
   §12 Q3. The inbox stays the family's: a parent who switched an arrival off still sees it in the inbox when another
   parent wanted it (design 35: every kind is stored, the switches govern the push).
8. **A zone used by no phone is a choice, not a fault.** It is kept, never judged and never served, and shows a
   quiet "ingen telefon". It has no orange line.
9. **Deleting a family zone deletes its links** (FK cascade). That cascade deletes their alert rows and clears a
   weather zone that pointed at one of them (`ON DELETE SET NULL`). Past events stay until the location history's
   retention takes them (decision 6 of 64, unchanged).
10. **Turning a zone off for a phone deletes that link.** Turning it on again makes a new link with a new id and a
    silent first side, so "Her nå" waits for one or two new fixes, and the phone relearns its Wi-Fi link for that
    zone (design 125 D12, >= 2 days). §12 Q4.

## 3. Data model and migration `0181_family_zones.sql`

**The number.** Main uses up to 0179. Phase D (`phase-d-server`, design 132) holds 0180 and merges after rc.4, stable
0.43.0 and the server tag. 135 is item 5, after phase D, so it takes **0181**. If 135 should merge first it
renumbers to 0180 before its first push to `main` (a migration's text is frozen once pushed, `server/CLAUDE.md`).
Never deploy 0181 without 0180 (the order hazard there).

**No table rebuild.** The migration only adds columns, adds tables and drops columns. A copy-drop-rename of
`device_zones` would null every `weather_zone_id` (`docs/notes/server/zones.md`, "A zone stands in for the
weather").

```sql
-- Design 135: zones are the family's; a phone links to the ones it uses. The link is the old
-- device_zones row - its id, state and cursor unchanged - so the policy's zone ids, the phone's
-- fences and Wi-Fi zone links, events' params and device_policy.weather_zone_id all stay valid.
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

-- The fold: one family zone per group of identical zones - the same name (ASCII case and
-- surrounding spaces aside), the same centre (stored at 6 decimals) and the same radius - made
-- from the group's oldest row.
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

-- Same name, another place (two schools): every such zone but the oldest gets its first phone's
-- name, "Skole (Emil)" (Q2). Names stay as they are in the events already raised.
UPDATE family_zones SET name = name || ' (' || (
    SELECT dv.name FROM device_zones l JOIN devices dv ON dv.id = l.device_id
    WHERE l.zone_id = family_zones.id ORDER BY l.id LIMIT 1) || ')'
WHERE EXISTS (SELECT 1 FROM family_zones o
              WHERE lower(o.name) = lower(family_zones.name) AND o.id < family_zones.id);

-- Each parent's alerts per link (no row = both on). The old per-zone switches become every
-- current parent's own, so nothing changes for anyone at the migration.
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
WHERE a.removed_at IS NULL AND (d.notify_arrive = 0 OR d.notify_leave = 0);
CREATE INDEX idx_zone_alerts_link ON zone_alerts(device_zone_id);

ALTER TABLE device_zones DROP COLUMN name;
ALTER TABLE device_zones DROP COLUMN latitude;
ALTER TABLE device_zones DROP COLUMN longitude;
ALTER TABLE device_zones DROP COLUMN radius_m;
ALTER TABLE device_zones DROP COLUMN notify_arrive;
ALTER TABLE device_zones DROP COLUMN notify_leave;
```

Notes for the implementer:

- **The fold key's `lower()` is ASCII only** (SQLite without ICU). "Øvre" and "øvre" with the same circle stay two
  zones. That is harmless, because decision 5 checks a name only when it changes.
- **No unique index on names.** Two phones with the same name can still leave two zones called "Skole (Anna)". Code
  refuses a new clash and never an old one.
- **A phone can't hold two zones of one group.** Names are unique per phone after trimming, ignoring case
  (`to_lowercase`, a superset of ASCII `lower`), so the unique `(device_id, zone_id)` index holds on any existing data.
- `zone_id` stays nullable in the schema: `ADD COLUMN` with a reference can't be `NOT NULL`. Every write path sets
  it, and a test reads that no row is NULL after the migration.
- **`DROP COLUMN` limits:** none of the six columns is indexed, part of a key, or named in another column's CHECK.
  `created_by` (a reference) stays on the link. The migration test checks that `weather_zone_id` survives the drops.
- **IDs are new for `family_zones`** (not copied from links). Tests must use families where zone ids and link ids
  differ, so a mix-up can't pass by accident (§10).
- `backup_schema` needs nothing new (a fresh database runs the same file). `migrations_from_73_in_order` gains the
  file.

**What is kept, and how:**

| What | Where after 0181 |
|---|---|
| Each phone's zones, ids, circles as served | the links (same ids) → their family zone (same centre and radius) |
| Side, pending side, cursor, `side_since` | untouched on the link: no zone starts over, no event is raised, "Her nå" stays |
| The Arrive/Leave switches | a `zone_alerts` row per current parent where a switch was off; a parent added later starts with both on |
| History: events and their words | untouched (`params.zone_id` = the link id, the names as raised); retention unchanged |
| The weather zone | `weather_zone_id` → the same link id |
| The phone's fences and Wi-Fi zone links | untouched: the policy is byte-identical, so the fence fingerprint and `PlacesPlan.zone` keys are unchanged |

**Rollback.** The migration is forward-only, as every migration is. The server takes
`data/backups/pre-migrate-<from>-<to>.db` before it runs (`startup::pre_migration_backup`), and `update.sh` keeps its
backup (DEPLOY.md, "Rolling back the server"). An older binary refuses the migrated database, so a rollback restores
that copy together with the older binary. Lost: zone edits, links, alert switches and fixes since the update.
The phones need nothing: the restored server serves the same ids and circles, so fences stay. A zone created after the
update disappears, and its phone drops the fence at the next sync.

## 4. The server's rule, the policy, the intake

- `zones::list(db, device_id)` joins `device_zones` to `family_zones` and returns the `Zone` the code uses today:
  `id` (the link), `zone_id`, `name`, the circle, the state. `notify_arrive`/`notify_leave` are now computed:
  `EXISTS` a non-removed parent with `COALESCE(zone_alerts.arrive, 1) = 1` for the link, and the same for leaving.
  `evaluate_stored`, `event_of`, `seed`/`start_over`, `forget_sides`, `served_interval_of`, `policy_zones`, the
  weather reads (`weather.rs`, `weather_page.rs`) and `devices::zone_now` keep working on links. The weather reads
  take the circle through the join.
- **Unchanged:** the two-fix rule, the coarse and Wi-Fi-alone classes (125b, 131), the seed, push-only-while-news,
  retention by the phone's days, the 30-min floor (it keys on the phone having links), the `zone_watch` intake.
- **A new link** (from either page) inserts the row and runs `start_over` with the phone's own fixes, in the save's
  `BEGIN IMMEDIATE` transaction. It never replays history (the cursor starts at the newest fix).
- **A moved family zone** runs `start_over` on each of its links in one transaction.
- **The events' words** take the family zone's name at raise time (`ZoneParams.zone`, as today).

## 5. The PWA

Every page here is in Locate's tab. Saves land on their own row or group, never at the top: the forms post to an
anchor, and the autosave goes through `scroll-restore.js`. A refusal is a 400 re-render at its form, or for the
autosave form a 303 back to the zone's cell with the reason under it (`Refused` in the extensions, so `settings_log`
doesn't log it as a save).

### 5.1 Where the family's list lives

- **Locate, all phones** (`/devices/locate`, `#all`): a new group under Update All with one row "Soner", the value
  the family's count, → `/zones`.
- **A phone's Zones page** ends with "Alle soner ›" → `/zones?device={id}` (Back returns to that phone).

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
 │ 150 m · Anna, Emil                   │
 │ Skole                              › │
 │ 200 m · Anna                         │
 │ Fotball                            › │
 │ 150 m · ingen telefon                │
 │ Legg til sone                        │
 └──────────────────────────────────────┘
  (i)
```

- Rows oldest first. The sub-line is the radius and the phones that use the zone, by name, or "ingen telefon"
  (quiet, decision 8). A save's line sits under its row (`?zone=saved&zone_id=N#zone-N`), a delete's at `#zones`.
- At 50 zones, "Legg til sone" becomes the note "50 soner er det meste".
- (i): "Sonene er felles for familien. Velg hvilke hver telefon bruker under telefonens Soner." With no zones, the
  existing `footer_empty`.
- No coordinates on the page; `no_store_path` anyway, like the other zone pages.

### 5.3 The zone's editor `/zones/new`, `/zones/{zone_id}` (`device_zone.html` becomes `family_zone.html`)

Today's form and `zones.js`, plus a "Telefoner" group. The Arrive/Leave switches move to the phone's page (they are
each parent's).

```
‹ Soner             Hjem              [Lagre]
 [ map: the circle, tap to move ]
 ┌──────────────────────────────────────┐
 │ Navn                            Hjem │
 │ Breddegrad                 59.913900 │
 │ Lengdegrad                 10.752200 │
 │ Radius                        150  m │
 │ ───────────o──────────               │
 │ Bruk posisjonen til Anna             │
 │ Bruk posisjonen til Emil             │
 └──────────────────────────────────────┘
  Trykk på kartet for å flytte midten.  (i)
 TELEFONER
 ┌──────────────────────────────────────┐
 │ ☑ Anna                               │
 │ ☑ Emil                               │
 └──────────────────────────────────────┘
 ┌──────────────────────────────────────┐
 │ Slett sone…                          │
 └──────────────────────────────────────┘
```

- **The place group's (i)** adds one line: "Sonen er felles. Endringer gjelder alle telefonene som bruker den." This
  is the shared-edit warning the ask wants behind an (i); it has no orange line.
- **"Bruk posisjonen til {name}"**: one button per phone that has a fix, the `device` phone first (today's single
  "Use Phone's Location", per phone). The map opens at the zone. A new zone opens at the `device` phone's newest fix,
  else at the newest fix of any phone, else the "no location yet" note.
- **Telefoner**: one checkbox per phone. On a new zone opened with `?device=N`, that phone starts ticked, and none
  otherwise. No ticks is allowed (decision 8). There is no sub-line and no disabled state. A phone already at 10 is
  refused on Save under the group: "Anna har allerede 10 soner." (`aria-invalid`, nothing written).
- **Save**: one `BEGIN IMMEDIATE`: the zone, then the links added and removed, `start_over` on new links (and on
  every link when the circle moved). Each phone whose policy changed is nudged. The save lands on
  `/devices/{device}/zones?zone=saved&zone_id=Z#zone-Z` when it came from a phone (`device` a real phone id, else
  ignored), or on `/zones?zone=saved&zone_id=Z#zone-Z`.
- **Refusals** (400 at `#zone-form`, as typed): empty name, over 40, or invisible characters; a name taken in the
  family ("Familien har allerede en sone med det navnet.", only when the name changed); a coordinate or radius out
  of range; a 51st zone; a phone at its cap.
- **Slett sone…** (`<details class="confirm">`): "Slette Hjem? Den brukes av Anna og Emil." With no phone: "Slette
  Hjem?". Delete cascades (decision 9), nudges the phones that used it, and lands on `#zones` of where it came from.

### 5.4 A phone's Zones page `/devices/{id}/zones` (`device_zones.html`, reworked)

One form, `POST /devices/{id}/zones` (today's create route, repurposed), every control `data-autosave` with a
`<noscript>` Save, as the calendar page's per-calendar switches (`device_calendar.html`, `#calendars`).

```
‹ Finn                Soner                 Anna
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

- **A cell per family zone**, in the family's order. Toggling never reorders, so a save comes back with the toggled
  cell where it was (`#zone-Z`).
- **The first row** is the zone's name and its sub-line ("150 m", "· Her nå" or "· Sist sett i sonen … 14:02" as
  today), with a switch whose `aria-label` is "Bruk på Anna" (on = linked).
- **While linked**, two rows "Kommer" and "Drar": the **signed-in parent's** alerts for this phone and zone.
- **The group's (i)** holds today's setting-dependent footer (Jevnlig/Bare når du spør/the watch line) and one new
  line: "Kommer og Drar er dine egne varsler for denne telefonen." Today's "For alle foreldre …" line goes.
- **The form's fields**: `zone=<id>` hidden per cell (the zones the page showed), `use_<id>`, and `arrive_<id>`/
  `leave_<id>` only in cells that were linked when the page was drawn.
- **The handler**, in one `BEGIN IMMEDIATE`:
  - Only zones named by `zone=` are touched, so a zone made in another tab is never unlinked.
  - A named zone deleted meanwhile is skipped.
  - Links added get `start_over`; links removed are deleted.
  - Alerts are written (an upsert of `zone_alerts` for this parent) **only for links that existed before the post and
    still exist**. A cell just switched on has no `arrive_`/`leave_` fields, so treating "missing as off" there would
    turn a new link's alerts off.
  - A link added past 10 refuses the whole post. The 303 goes to `?refused=full&zone_id=Z#zone-Z` with "10 soner er
    det meste for én telefon. Slå av en først." under that cell, and nothing is written.
  - Each change of links nudges the phone. A change to alerts alone doesn't.
- **"Legg til sone"** → `/zones/new?device={id}`, with this phone ticked; it lands back here. **"Alle soner"** →
  `/zones?device={id}`.
- **Not on this page:** the zone's circle and name (the editor's), Delete (the editor's). The warnings, the watch line
  and Locate Regularly stay as today ("only while the phone has zones" = links).
- **No family zones yet**: no cell, only the two rows and `footer_empty` in the (i).

### 5.5 Routes

| Route | What |
|---|---|
| `GET /zones` | the family list (`?device=N` sets Back) |
| `GET /zones/new`, `POST /zones` | a new zone (`device` carried in a hidden field) |
| `GET /zones/{zone_id}`, `POST /zones/{zone_id}` | the editor and its save |
| `POST /zones/{zone_id}/delete` | delete |
| `GET /devices/{id}/zones`, `POST /devices/{id}/zones` | the phone's page and its autosave |
| `GET /devices/{id}/zones.json` | unchanged: the phone's linked zones for the Locate map (`id` = link) |
| removed | `/devices/{id}/zones/new`, `/devices/{id}/zones/{zone_id}`, `…/delete` (no redirect; nothing links there - push targets are `/devices/locate?device=N#location`) |

All of them use full auth and CSRF. `no_store_path` covers `/zones`, `/zones/new`, `/zones/{zone_id}` (coordinates)
and `zones.json` as today. An unknown zone or phone is a 404.

### 5.6 Words (nb / en)

The copy table, checked against root CLAUDE.md "Words in the UI": words go behind the (i), and there is no orange
for a choice.

| Key | nb | en |
|---|---|---|
| family list row sub | `{radius} m · {phones}` / `{radius} m · ingen telefon` | `{radius} m · {phones}` / `{radius} m · no phone` |
| family list (i) | Sonene er felles for familien. Velg hvilke hver telefon bruker under telefonens Soner. | Zones are the family's. Pick the ones each phone uses on its Zones page. |
| family full | 50 soner er det meste | 50 zones is the most |
| editor place (i), added line | Sonen er felles. Endringer gjelder alle telefonene som bruker den. | The zone is shared. A change applies to every phone that uses it. |
| editor group | Telefoner | Phones |
| use location | Bruk posisjonen til {name} | Use {name}'s location |
| name taken | Familien har allerede en sone med det navnet. | The family already has a zone with that name. |
| phone at cap (editor) | {name} har allerede {max} soner. | {name} already has {max} zones. |
| delete question | Slette {name}? Den brukes av {phones}. / Slette {name}? | Delete {name}? {phones} use it. / Delete {name}? |
| phone page switch aria | Bruk på {phone} | Use on {phone} |
| phone page rows | Kommer / Drar (existing keys) | Arrive / Leave |
| phone page (i), added line | Kommer og Drar er dine egne varsler for denne telefonen. | Arrive and Leave are your own notifications for this phone. |
| phone at cap (autosave) | {max} soner er det meste for én telefon. Slå av en først. | {max} zones is the most for one phone. Turn one off first. |
| link row | Alle soner | All Zones |
| security log labels | Sone tatt i bruk / Sone tatt bort | Zone linked / Zone unlinked |

Gone: `locate.zone.notify_title`, `notify_footer`, `locate.zones.sub`'s notify part and `locate.zones.notify.*`
(the sub-line no longer says which alerts are on, because the switches are in view).

## 6. Notifications

- **Raise** (`zones::event_of`, unchanged): the computed `notify_arrive`/`notify_leave` (§4) decide whether a crossing
  is an event at all.
- **Push** (`push::queue_as`, for every delivery of a zone event, digests included): today's `push_zones` filter plus
  one condition read from the event itself, so no caller has to pass it:

  ```sql
  AND NOT EXISTS (SELECT 1 FROM parent_events e JOIN zone_alerts za
                    ON za.device_zone_id = json_extract(e.params, '$.zone_id')
                 WHERE e.id = ?event AND za.admin_id = s.admin_id
                   AND ((e.kind = 'zone_arrived' AND za.arrive = 0)
                     OR (e.kind = 'zone_left' AND za.leave = 0)))
  ```

  For every other kind the subquery finds nothing.
- **A link deleted after the event and before a retry**: its alert rows are gone, so the condition passes and the
  push follows `push_zones` alone. That is harmless (the event is real) and rare.
- **Notify Me About** keeps "Soner: kom og dro" (`push_zones`) as each parent's master switch. Per phone and zone
  is the phone's Zones page.
- **A new parent** starts with every link's alerts on (no rows). **A removed parent** (`removed_at`) counts for
  nothing; their rows stay until the account is deleted (cascade).
- **The inbox, Activity and the summary's "Hjem siden 15:42"** are unchanged.

## 7. The phone: fences, the limit, the zone links (designs 64, 124, 125, 131, 134)

- **Nothing changes on the phone.** It still gets its own list of at most 10 circles with ids. The ids are the
  links', unchanged at the migration, so `ZoneFences`' fingerprint is unchanged, no fence is re-registered, and
  `PlacesPlan.zone`'s key `hmac(id|lat|lng|radius)` is unchanged, so every learned **zone link** (125 D12) and every
  `check: "link"` (131) survives.
- **After an edit of a shared zone's circle**, every phone using it sees a new fingerprint and re-registers, as one
  moved zone does today. Each drops its Wi-Fi zone link for that zone and relearns it (>= 3 teaching fixes over >= 2
  days). On the server every link starts over silently. A rename touches neither.
- **Unlink and relink** give a new id: a new fence and a new zone link to learn (decision 10).
- **The last link of a phone removed** works as the last zone deleted does today. `zoneWatchActive` ends (fences,
  passive feed, series, queue stop). `stillWatchActive` (124) keeps the heartbeat on a Regularly phone. The served
  interval's 30-min floor goes.
- **134's still-place fingerprint** is not keyed by zones: unaffected.
- **The fences limit:** Android's proximity alerts have no cap (64 §2), the server sends at most `MAX_ZONES` 10 per
  phone, and `sanitize_zone_watch` still accepts 0..=100. A family of 50 zones never reaches a phone beyond its own
  links.

## 8. Privacy and security

- **Nothing new leaves the server.** The policy is the same per-phone list without names (64 decision 9). A zone a
  phone doesn't use never reaches that phone, so Emil's phone never learns Anna's school.
- **What a parent sees:** every phone's zones, as before (zones were already the family's for every parent). The
  editor's "Bruk posisjonen til …" shows each phone's newest fix, which every parent already sees on Locate. The page
  is `no-store`.
- **Security log:**
  - `zone_added`, `zone_changed` and `zone_deleted` become family lines: "Hjem, 150 m", "Hjem - moved, radius 150 ->
    250 m", "Hjem (Anna, Emil)".
  - `zone_linked` and `zone_unlinked` are new: "device 3 (Anna): Hjem".
  - None of them ever carries coordinates.
  - An alert switch alone is a `settings_saved` line from the middleware (routes and ids, never values), like Notify
    Me About.
- **Escaping:** every name goes through Askama escaping and `textContent`. Form ids are parsed as integers and
  checked against the table in the transaction. `device` is a phone id that must exist, and anything else is
  ignored.
- **Retention:** unchanged. Zone events go with the phone's location history. A family zone outlives a deleted phone
  (its link cascades), shown with "ingen telefon".

## 9. Steps

| # | Step | Size |
|---|---|---|
| S1 | Server, the data: migration 0181; `zones::list` and the computed alerts; links and family-zone writes (`start_over` per link); the push filter; weather's join; security log words; the migration test and the rule tests (§10) | M |
| S2 | Server, the pages: `/zones`, the editor with Telefoner and the per-phone location buttons (`zones.js`), the phone's page as one autosave form, Locate's all-phones row, routes, `no_store_paths`, nb/en, `jstest`; `docs/notes/server/zones.md`, `pwa.md`, `notifications.md` | M |
| G | Emulator gate §6bs on Android 14 and 16 (§11) | S |

S1 and S2 are two commits on one branch, merged together (S1 alone would leave the old pages reading dropped
columns). **There is no launcher step.** `docs/notes/launcher/zones-and-power.md` gains one line: a policy zone's id
is the phone's link to a family zone. A server release ships it. It works with every launcher since design 64 step 2
(the policy is unchanged), and needs phase D's 0180 on the server first.

## 10. Tests (server)

**The rule's tests use a family whose zone ids and link ids differ** (a zone linked to two phones before the second
zone is made).

- **`migration_0181_folds_and_keeps_every_phone`** (`tests::cleanup::migrated_before(181)`):
  - The seed: phones Anna and Emil, two parents and one removed. Anna's "Hjem" has `notify_leave` 0, side inside, a
    cursor and a pending side. Emil's " hjem" has the same circle. Both have "Skole" at different places. Anna's
    weather zone is her Hjem. There are zone events with params.
  - After the migration, `family_zones` holds 3: Hjem, Skole, and Skole (Emil).
  - Every link keeps its id, state, cursor and `created_at`. No `zone_id` is NULL, and `(device_id, zone_id)` is
    unique.
  - `zone_alerts` has a row with leave 0 for each active parent on Anna's Hjem, none for the removed parent, and none
    for links with both on.
  - `weather_zone_id` and the events' params are unchanged.
  - **The policy's `zones` for each phone equals the `(id, latitude, longitude, radius_m)` read before the
    migration.**
- **`migrations_from_73_in_order`** gains the file.
- **`zones::tests` and `tests::zones`:**
  - Judging:
    - A zone linked to two phones is judged per phone: Anna arrives and Emil raises nothing.
    - A moved family zone starts every link over silently and nudges both phones; a rename keeps every side.
    - A new link seeds from its phone's fresh fine fix and never replays history.
    - Unlinking deletes the state and the alerts, and the policy drops the zone.
    - Relinking gives a new id and a silent first side.
  - The policy and the weather:
    - A zone used by no phone is in no policy and never judged.
    - A delete cascades the links, the alerts and the weather zone, and nudges the phones that used it.
  - Caps and names:
    - An 11th link is refused from both pages with nothing written.
    - A 51st zone is refused.
    - A taken name is refused only when the name changed; an old clash still saves a radius.
  - Alerts:
    - Parent B's arrive off for Anna/Hjem: the arrival is raised and pushed to A only.
    - Every active parent off: no event.
    - A removed parent's off row counts for nothing.
    - A new parent gets pushes.
    - **A cell just switched on keeps its alerts on.** This guards the missing-field trap of §5.4.
  - Landing and logging:
    - Each form lands on its row or group (`every_form_on_the_locate_page_returns_to_its_group`,
      `shell::every_form_on_every_page_lands_on_its_own_group` gain the three pages).
    - `device` is whitelisted: an unknown id lands on `/zones`.
    - The autosave's refusal lands at `#zone-Z` with its line.
    - `no_store_paths`.
    - The security log lines carry no coordinates.
- **`tests::web_push`**: the filter on a retry and on a digest. **`seeded_family`** is updated.
- **`jstest/zones.test.js`**: the per-phone location buttons write the fields.
- **The launcher's tests are unchanged.** `policy_json_keys_snapshot` passes untouched, which is itself the check that
  the contract didn't move.

## 11. Emulator gate (`docs/testing/emulator.md` §6bs, Android 14 and 16 only)

The two AVDs are the family's two phones: an API 34 image (Android 14) and `jelly36` (Android 16), both enrolled to one
server, the debug launcher, mock fixes as in §6t (`cmd location providers add-test-provider fused`). The launcher is unchanged, so
there is no 15 or 17 run (root CLAUDE.md: the gate covers the versions the change touches).

1. **Before the update**, on a server at `main` with 0180: on the 14, "Hjem" (typed centre, 150 m) and "Skole"; on
   the 16, "Hjem" with the same numbers. Both are Regularly; a fix inside Hjem on both, twice 2 min apart ("Her nå").
   Turn Drar off on the 14's Hjem. Note `adb shell dumpsys location | grep -A3 -i geofence` on both and
   `PlacesPlan`'s zone-link count in the `wifi_places` dump if any.
2. **Update the server** (0181). `/zones` shows Hjem (both phones) and Skole (the 14). Both phones' "Her nå" is
   still there, and no event was raised. At the next sync `logcat -s ZoneWatch` shows no re-registration, and
   `dumpsys location` shows the same fences. The 14's Hjem has Drar off for both parents.
3. **Pick per phone**: switch Skole on for the 16 on its Zones page. The page comes back at Skole's cell, and the 16
   gets one more fence after the nudge. A fix inside Skole twice raises nothing (silent first side). A fix 1 km away
   and the confirm raise "… left Skole" for the 16 only.
4. **Edit a shared zone**: move Hjem 300 m in the editor (the (i) shows the shared line). Both phones re-register one
   fence with the new circle, and neither raises an event. Then a crossing on each phone raises the event for that
   phone.
5. **Alerts per parent**: parent B turns Kommer off for the 16's Hjem. An arrival on the 16 shows in both inboxes and
   gives one `push_deliveries` row (parent A only).
6. **Unlink and delete**: Hjem off for the 14 removes its fence (`dumpsys`). If Hjem was the 14's weather zone,
   Weather says none. Deleting Skole removes the 16's Skole fence; `/zones` lists only Hjem.
7. **Cap**: with 10 zones on the 14, an 11th switch comes back off at its cell with the line.

## 12. Questions for the owner (default in bold)

1. **Near-duplicates** (same name, centres a few metres apart): fold those too? **No: only identical ones.** You turn
   the other one on for the phone and delete the duplicate.
2. **Same name, different places** after the fold (two "Skole"): the later one becomes **"Skole (Emil)"**, or both
   keep "Skole".
3. **Every parent has Kommer off** for a phone's zone: **no event at all, as today**, or an inbox row without a
   push.
4. **Turning a zone off for a phone** forgets that phone's state for it ("Her nå" waits for new fixes; the phone
   relearns its Wi-Fi link in about 2 days): **accept, no confirm**, or ask first.
5. **The all-phones map** draws the family's zones: **no** (the one-phone map keeps drawing that phone's), or yes.
6. **Where the list lives**: **a "Soner" row on Locate's all-phones view, plus "Alle soner" on each phone's Zones
   page**, or a top-level item under Settings.

## 13. Out of scope

Near-duplicate merging (Q1), zone schedules, polygons, the kid's view of zones, other families (design 63), CalDAV's
"Delt i familien" (item 6, its own design).
