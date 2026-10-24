# QA (code): design 64 step 1 - safe zones on the server

Branch `design-64-step1` on `main` f787a34a, six commits: a5fd125f (the design note), 485bfa1f (migration 0091),
21e9effc (the rule, the dedupe, the evaluation), 4ca19427 (events and push), c577f6c3 (pages and map), 77efc3a3
(docs). Server only. Reviewed 2026-10-24 on branch `qa-design-64-step1` (`git diff f787a34a..design-64-step1`, 40
files, +4392/-104). No Gradle, no emulator, nothing pushed, nothing fixed.

## Summary

- **"No duplicates, nothing lost" holds.**
  - A fix is stored once. The key is the phone, the normalised `captured_at`, latitude and longitude. That key is
    right for what the launcher sends (`Instant.ofEpochMilli(location.time).toString()`).
  - Every stored fix after a zone's cursor is judged exactly once, in `(captured_at, id)` order, in the same
    `BEGIN IMMEDIATE` transaction that stores the fix and raises the events. A crash leaves all of it or none.
  - One crossing is one row (`<zone>:<side>:<first fix>`, `ON CONFLICT DO NOTHING`, zone ids never reused under
    `AUTOINCREMENT`). The push is queued in the same transaction, and the sender's timer sends it if the wake-up
    is lost.
- **The rule is right.**
  - `d + a <= r` / `d - a >= r` in whole millimetres, unsure on missing, NaN or negative accuracy.
  - The pair needs two sure fixes 60 s or more apart; a bad fix between two good ones forgets the pending side.
  - The first side of a new or moved zone is silent.
  - I re-computed five of the vector rows and the antimeridian and pole rows independently, and they agree to the
    millimetre.
- **One thing reads wrong on the page (Q-1).** The fix's badge, the popup, "Here now" and "At Home since" come
  from the zone's *confirmed* side. When the newest fix is the first one outside (the pending side), the map
  shows the marker 1 km from Home with "at Home" under it, for a whole interval.
- **Mutations found two missing tests** (Q-4, Q-5): the freshness is never tested against the first fix's age,
  and the tie clause of the fetch is untested.
- **All suites are green.**

Counts: Blocker 0, High 0, Medium 1, Low 7, Note 8.

## Runs

| Side | Command | Result |
|---|---|---|
| server | `cargo test` (`CARGO_TARGET_DIR=~/.cache/handy-target/qa-design-64-step1`, jobs 2, no debug info, `flock ~/build.lock`) | `test result: ok. 1241 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 433.71s` |
| server | `cargo fmt --check` | clean (exit 0) |
| server | `cargo clippy --all-targets` | `Finished`, exit 0; 13 warnings, all on lines this branch doesn't touch (`models.rs`, `storytel.rs:144`, `dns_engine.rs:54-55`, `devices.rs:1324` = main's 1322 shifted by the new field). A first run printed `error: could not compile kid_phone_server (bin "kid_phone_server" test)` with no diagnostic (most likely rustc killed: 1.5 GB free at the time). A rerun after `touch src/main.rs` was clean, exit 0 (N-7). |
| js | `node --test jstest/*.test.js` (node v20) | `# tests 158`, `# pass 158`, `# fail 0` |
| by hand | haversine of the vector rows (Python, independent) | 30.023, 1000.756, 200.151, 144.554, 177.912 m; antimeridian and pole 111.195 m - as the notes say. 144.554 + 5.446 = 150.000 and 177.912 - 27.912 = 150.000 exactly. |

### Mutations (one flip each, the one test or module run, reverted)

| # | Mutation | Test run | Result |
|---|---|---|---|
| M1 | `step`: a fix on the zone's side no longer forgets the pending side | `zones::tests::` | caught (1 failed) |
| M2 | inside `d + a <= r` -> `<` | `the_shared_vectors` | caught |
| M3 | outside `d - a >= r` -> `>` | `the_shared_vectors` | caught (a first run hit the module doc's copy of the text; rerun on the code) |
| M4 | accuracy `>= 0.0` -> `> 0.0` | `the_shared_vectors` | caught (the accuracy-0 row) |
| M5 | the 60 s gap `<` -> `<=` | `zones::tests::` | caught (2 failed) |
| M6 | the dedupe without latitude/longitude | `a_resent_fix_is_stored_once` | caught |
| M7 | the event key without the side | `parent_events::tests::` | caught |
| M8 | the event key without the time | `zones` | caught |
| M9 | freshness from the first fix (`crossing.at`) instead of the confirming one | `zones` | **survived** (Q-4) |
| M10 | freshness always true | `zones` | caught (`the_switches_and_old_news`) |
| M11 | `RADIUS` max 2000 -> 1999 | `handlers::zones::tests::` | caught (the edge row) |
| M12 | the radius parse without an upper bound | `zones` | caught (2 failed) |
| M13 | the cursor `key <= cursor` -> `<` | `zones` | survived - an equivalent mutant (N-3) |
| M14 | the fetch's `OR (captured_at = ?2 AND id > ?3)` dropped | `zones` | **survived** (Q-5) |
| M15 | a new zone's cursor left empty | `zones` | caught |
| M16 | the first side raises a crossing | `zones::tests::` | caught (2 failed) |
| M17 | enrollment keeps the sides | `a_moved_zone_forgets_its_side_and_a_rename_keeps_it` | caught |
| M18 | zone events never pruned early | `zone_events_go_with_the_location_history` | caught |
| J1 | `zones.js` `clampRadius` without the max | `jstest/zones.test.js` | caught (2 failed) |
| J2 | `zones.js` `place` writes the raw number, not 6 decimals | `jstest/zones.test.js` | caught (3 failed) |
| J3 | `locate.js` zone tooltip `textContent` -> `innerHTML` | `jstest/locate.test.js` | caught |

## 1. `server/src/zones.rs`

### The intake

- **The transaction.** `intake` (`zones.rs:353`) runs under `BEGIN IMMEDIATE` and does, in order:
  `store_fix`, `evaluate_stored`, `raise` for each event, commit, then `push.wake()` only when a row was new.
  `parent_events::raise` queues `push_deliveries` on the same connection.
  - A crash before the commit leaves nothing, and the phone re-sends its cached fix.
  - A crash after the commit leaves the deliveries queued, and `run_sender`'s timer picks them up. No push is lost.
  - The push goes late, though. The freshness check is made at intake only, so after a long outage a queued
    push can arrive later than 30 min. TTL 3600 bounds it (N-5).
- **Two parallel reports.** `busy_timeout` is 5 s (`main.rs:1509`), so the second report waits, then sees the
  first one's cursor and fix. Nothing is judged twice, and the dedupe holds because the `NOT EXISTS` runs inside
  the write lock.
- **The normalisation is right for every format a phone sends.**
  - `checked_location` parses RFC 3339 and stores `%Y-%m-%dT%H:%M:%S%.3fZ` UTC.
  - The launcher sends `Instant.ofEpochMilli(location.time).toString()`. That is `...:00Z` when the
    milliseconds are zero and `...:00.123Z` otherwise. Both normalise to the same string, and an offset spelling
    of the same instant does too (the test covers `+01:00`).
  - The cached fix is re-sent from the same `Location`. The JSON text is the same each time, and serde parses it
    to the same `f64` each time, so `latitude = ?2` is a deterministic equality.
  - Accuracy is rightly not part of the key.
  - The stored format sorts as text in time order, so `captured_at > ?2` and the Rust `(DateTime, id)`
    comparison agree.
- **The 1000-per-pass cap.**
  - The fetch starts from the oldest cursor (`Option::min` puts `None` first, so a zone without a cursor reads
    everything) and reads at most 1000 fixes, in order.
  - Each zone skips what is at or before its own cursor.
  - Past 1000, every zone's cursor stops at row 1000 and the next report continues from there. The cursors
    converge, and nothing is skipped.
- **A failure loses the fix too (Q-6).** Any error rolls back the fix with the evaluation, not only a busy
  database.

### `evaluate_stored`

- The `ORDER BY captured_at, id` matches the tuple comparison `(captured_at, id) > cursor`, in SQL and in `step`
  alike.
- Ties at an equal `captured_at` are told apart by the id. The tie clause in the SQL is what fetches them, and no
  test covers it (Q-5).
- A late fix older than a zone's cursor is stored and never judged. That is what decision 3 says ("only fixes
  newer than the last one evaluated count"). I accept it for step 1: the launcher sends one fix per report, and
  it is monotonic in practice. For step 2's `extra_locations` the order is safe as long as every extra fix is
  stored before the evaluation in the same transaction, which is how the intake is built (N-4).
- A state is written only when it changed (`*state == zone.state()`). That is cheap, and it is right.

### `step` and `start_over`

`step` (`zones.rs:164`) is exactly §3.2's table:

- a fix not after the cursor changes nothing;
- otherwise the cursor moves to it, and an unsure fix does nothing more;
- a sure fix on the zone's side clears the pending side;
- a sure fix on the pending side, 60 s or more after it, changes the side (`side_since` = the pending fix) and
  raises a crossing only when the old side was known;
- the same under 60 s does nothing;
- any other sure fix becomes the pending side.

The tests (M1, M5, M16) pin this.

**The deviation, the new zone's cursor at the phone's newest fix, is right.** §3.1's empty cursor would replay up
to 30 days of kept trail into a new zone. The first side would be silent, but every later crossing in that history
would then be raised as an event: in the inbox, though not pushed, since each is old news. So the parent would get
"arrived at School 08:10" from last week. With the cursor at the newest fix, "Here now" does appear only after two
*new* fixes 60 s or more apart (`a_moved_zone_forgets_its_side_and_a_rename_keeps_it`, M15). My recommendation is
under question (2) in §5.

- **Re-enrolment** clears the sides and keeps the cursors. That is right: the old phone's fixes are never judged
  against the new phone. It runs outside any transaction and only logs a failure (N-6).
- **A rename or a switch keeps the side; a moved centre or a changed radius starts over.** The comparison is on
  the 6-decimal values (`round6`) inside the save's `BEGIN IMMEDIATE`, against the row read in the same
  transaction. A form saved unchanged doesn't move the zone: `coordinate` shows `{:.6}` of the stored value, and
  `round6` of that parses back to the stored `f64`.
- **The haversine** clamps `sqrt(h)` to 1 before `asin`. The pole and antimeridian rows are right, and the 2 km
  zone row is right. One margin is thin but safe: the boundary rows sit 0.4 mm from a rounding edge
  (144553.6 mm), far above double precision.

## 2. Events and push

- **One event per crossing.** The key is `<zone id>:<inside|outside>:<the first fix's time>`, inserted with
  `ON CONFLICT (device_id, kind, dedup_key) DO NOTHING`. The cursor makes it impossible for a fix to be the
  pending fix twice, so two crossings can't share a key. M7 and M8 are caught.
- **The zone's switches decide the event, each parent's `push_zones` decides the push.**
  - `event_of` returns `None` when the zone's direction switch is off.
  - `push::queue` filters on `COALESCE(p.push_zones, 1)`.
  - Tested with two parents (`a_report_pair_raises_one_arrival_for_the_parents_who_want_it`).
- **The freshness.** The code tests `now - confirmed_at < NEWS_FOR`, which is the user's rule. A confirming fix
  up to 5 min in the future (`MAX_FIX_AHEAD`) counts as news, which is right. No test separates the confirming
  fix from the first one (Q-4).
- **The words** (`told`, `parent_events.rs`).
  - Titles: "Anna arrived at Home" / "Anna left School". The phone's name is cut to fit 60 characters, and the
    zone name is clipped to 40.
  - The summary is `capitalized(zone.at(at, now))`, so "14:02" or "Yesterday 14:02".
  - The zone is the **Phone Setup time zone** (`Zone::load`), the same as every other kind (`Phone::load`). It is
    not a per-phone zone.
  - The words are told at read time: in the inbox, in Activity and in `send_due`.
  - Parameters that don't parse fall back to the stored (empty) words. That shouldn't happen.
  - The key and parameters are i18n-ready: `direction` and the names are data, and English exists only in `told`.
- **The inbox row and Activity.**
  - `ROW_COLUMNS` gained `e.kind, e.params`, and both callers (`notifications::page`'s query and
    `activity::rows`) go through `inbox_row`.
  - Both templates drop " · " for an empty summary.
  - The changed assertion (`without_own_clock("14:09", "14:09")` is now `""`) also affects two older kinds. An
    emergency call on an earlier day ("Yesterday 21:14") and a phone-off without a battery ("21:14") now show
    only `when` in Activity. That is harmless, because the row always shows `when` (§5, question 3).
- **Retention.**
  - `zone_*` rows go by the phone's `location_retention_days` (at least 1, default 7) by `created_at`. Fixes go by
    `received_at`, so both are pruned by when they were stored. That is consistent with decision 6.
  - The general 90-day sweep runs after it.
  - Only the two zone kinds are touched (the `kind IN (?, ?)` binds come from `Kind::wire()`), and the test keeps
    an `unseen_boot` row.
  - `Pruned::parent_events` counts both sweeps.
- **The sender's row type.** `DueRow` became a `FromRow` struct with `e.kind AS event_kind` and `e.params`, and
  `send_due` is its only user. The column names (`id`, `subscription_id`, `kind`, `state`, ...) are the
  unqualified ones sqlx sees. The fold path words the newest two through `told` as well.

## 3. Pages

- **Validation** (`handlers/zones.rs`).
  - Name: trimmed, 1-40 characters, no `Cc` control characters. Unique per phone ignoring case (`to_lowercase`,
    inside the save's `BEGIN IMMEDIATE`, together with the count of 10).
  - Latitude ±90 and longitude ±180, finite. A decimal comma reads as a point.
  - Radius: an integer in `RADIUS`; "1e3" and "150.0" are refused. The step of 10 isn't enforced on the server,
    which is fine.
  - Each refusal is a 400 at `#zone-form` with the field marked and nothing written (the test checks each field,
    the 11th zone and the taken name on both routes).
  - Another phone's zone is a 404 on GET, save and delete.
  - Q-8 is about the name rule.
- **Where saves land.**
  - Add and Save go to `...&zone=added|saved&zone_id=N#zone-N`, with the line right under the row.
  - Delete goes to `#zones`, and Locate Regularly to `#zones` (the `back=zones` whitelist; anything else stays
    `#location-settings`). Locate Regularly keeps the interval (the test asserts 30).
  - The zone page and Locate are different paths, so `scroll-restore.js` keeps no record and the browser lands on
    the fragment. That is the intended "the row where the parent sees the result".
  - A refusal on an existing zone posts to its own path, so the form record restores the place. A new zone posts
    to `/zones` from `/zones/new`, so it lands at `#zone-form`. That is the page's top, but the sticky bar shows
    "Not saved" and the field has `autofocus`, so it reads as the time rule's page does.
  - None of the zone forms is in the two repo-wide "every form lands" tests (Q-3).
- **The zone page.** The slider row and "Use Phone's Location" are `hidden` until `zones.js` runs. The slider has
  no `name`, so the number field is what posts. "Use Phone's Location" exists only with a fix.
- **The Locate group.**
  - The warnings per mode follow §3.6: Only when asked shows the orange row and Locate Regularly; Off adds
    "Zones can't see where the phone is."; "Keep location on" off adds the Advanced row.
  - The footer by mode includes "within about two intervals (here 1 hour)".
  - All of these show only with zones.
  - The badge, "Here now" and "At Home since" are covered under Q-1 and Q-2.
- **The map.** `locate.js` (`?v=3`) fetches `zones.json` only with `data-zones` and draws `L.circle`s with the
  tooltip as a text node (J3 is caught). There is one view race (Q-7).
- **no-store.** `no_store_path` covers `["devices", _, "zones.json"]` and `["devices", _, "zones", ..]`, which
  includes the 400 answers. The tests assert the header on both.
- **The security log.**
  - It names the phone and the zone, never a coordinate: "moved", not the numbers. The test asserts no "59." or
    "10.75".
  - A save that changed nothing logs nothing.
  - Labels are in `handlers::admin`.
- **XSS and escaping.**
  - Zone names reach HTML only through Askama: the rows, the title, Delete's question, the summary header and
    `data-shown-text`.
  - In JSON they go through serde. In the tooltip they are `textContent`.
  - In pushes they are encrypted JSON that the service worker shows as text.
  - There is no `|safe` and no `innerHTML`.
- **CSRF and origin.** The admin router's global `csrf_guard` covers the three new POSTs. `a_zone_needs_a_session`
  checks the session.
- **Register.** The words are calm and plain: "Here now", "Zones need Regularly", "10 zones is the most". The keys
  are i18n-ready as above.

## 4. Migration, docs, snapshots, jstests

- **Migration 0091** is one file: the table with the state columns, the index, `push_zones` (default 1) and
  `parent_events.params`.
  - The ranges are kept out of the SQL as intended (only `radius_m > 0`).
  - The cursor has no foreign key, which is right because retention prunes fixes.
  - The next free number is 0092, and `migrations_73_to_91_in_order` is renamed. Its doc comment doesn't add
    "(was `migrations_73_to_90_in_order`)" like the earlier renames (N-8).
- **`server/CLAUDE.md`**: the migration chain, the data-model rows (`device_locations`' dedupe, `device_zones`,
  `parent_events`' params, `notification_prefs.push_zones`) and the index line for `zones.md` are there.
- **No API change.** `policy_json_keys_snapshot` and the contract files are untouched, which is right for step 1.
  Step 2 brings `location_policy.zones`.
- **jstests**: `zones.test.js` covers `clampRadius`, `fixed6`, `wrapLng`, `readCoord`, a tap, the slider and
  field, and "Use Phone's Location" with a fake Leaflet. `locate.test.js` covers the circles fetched only with
  `data-zones`.

## 5. The implementer's three open questions

1. **"Here now" / "At Home since" from the last confirmed side even when the newest fix is days old.** Gate it
   on age, and on the pending side (Q-1, Q-2).
   - The confirmed side is the right *state* to keep.
   - But "Here now" is a claim about the present, and a phone that has been off for three days isn't known to be
     anywhere.
   - Show "Here now" and the summary's "At Home since" only while the zone's side is inside, no other side is
     pending, and the newest stored fix is fresh. Fresh means no older than 2 x the interval in Regularly, else
     1 h.
   - Otherwise drop both. The fix row's age already says how old the knowledge is.
2. **A new zone is seeded only after two new fixes.** Keep it.
   - It is the safe reading of decision 4, and the wait is one or two intervals.
   - If the wait bothers the user, add one refinement: count the newest stored fix as the *pending* side when it
     is under 30 min old, so the next fix 60 s or more later confirms it silently. A pending side raises nothing,
     so this can't create an event.
   - Not needed for the merge (N-2).
3. **Activity dropping a clock-only summary.** Keep it.
   - The row always shows `when`, so "15:42 · 15:42" would only repeat it.
   - The same now applies to an emergency call or a phone-off on an earlier day. That is an improvement too.
   - Note it in `notifications.md` as a rule ("a summary that is only the row's own clock is dropped"), so a later
     kind doesn't rely on its summary being shown.

## Findings

### Q-1 (Medium) - the badge, the popup and "Here now" contradict the newest fix while a crossing is pending

`handlers/locate.rs:839-855` (`badge` from `z.here()`), `:172` (`here: z.here()`), `handlers/devices.rs:2748`
(`zone_now_line`), `zones.rs:261` (`Zone::here`).

`here()` is the confirmed side. Take the kid leaving Home:

1. The first sure fix outside is stored as the pending side, and it is the newest fix.
2. The map puts the marker 1 km from the circle.
3. The fix row says "Within 20 m · At Home", the popup "... · at Home", the zone row "Here now", and the summary
   "At Home since 07:30".

This stays until the second fix: 30 min in Regularly, and in Only when asked until the parent taps Update
Location again. In that case the parent has just asked where the phone is, and the page answers "at Home" under
a marker at school.

What to do:

- Word the fix line and the popup from the newest fix's own `zones::side()`, per zone. It is a property of that
  fix ("Within 20 m · At Home" = this fix is surely inside Home), and it is cheap: the zones are already loaded.
- Give "Here now" and the summary line `z.here() && z.pending_side.is_none()` (plus Q-2's age gate). A pending
  side exists only when the latest sure fix disagrees with the confirmed side.
- Add a test: the confirmed side inside, one fix outside, so no badge, no "Here now" and no summary line.

### Q-2 (Low) - "Here now" and "At Home since" never go stale

The same places as Q-1. "Here now" shows for a phone whose newest fix is days old (open question 1). Gate both on
the newest fix's age, as in §5 (1), and test a stale phone.

### Q-3 (Low) - the repo-wide "every form lands on its group" tests don't see the zone forms

`src/tests/shell.rs:622` (`every_form_on_every_page_lands_on_its_own_group`) and `src/tests/locate_page.rs:85`
(`every_form_on_the_locate_page_returns_to_its_group`).

- Neither test creates a zone. So Locate Regularly (`#zones`, only with zones and Only when asked) and the zone
  page's two forms are outside their "a form the table has no row for fails" guard.
- The shell test's page list doesn't visit `/devices/{id}/zones/new` or `/zones/{id}` for its head and title-bar
  checks either.
- Design §8 asked for the Locate table to gain `#zones` and `back=zones`.
- `tests::zones` checks these redirects one by one. The guards exist so that a *later* form on these pages can't
  land at the top.

What to do: add a zone (with the phone in Only when asked) to both tests, the Locate Regularly row to the Locate
table, and the two zone pages to the shell's list.

### Q-4 (Low) - no test separates the confirming fix's age from the first fix's (M9 survived)

`zones.rs:459`. `push = now - crossing.confirmed_at < NEWS_FOR` is the user's rule ("the confirming fix under 30
min old"). Using `crossing.at` instead passes every test:

- the pipeline's pair is 10 and 5 min old;
- the old-news pair is 200 and 180 min old.

Add the case that matters in Only when asked: the first fix 3 h old, the confirming one 2 min old. It must be
pushed.

### Q-5 (Low) - the fetch's tie clause is untested (M14 survived)

`zones.rs:390`. Dropping `OR (captured_at = ?2 AND id > ?3)` passes all tests. A fix stored with the same
`captured_at` as a zone's cursor but a higher id would then never be fetched, so never judged. The trigger is a
second fix at the same instant from another place, or a fix stored by another path. Add an intake test: two
reports with the same `captured_at` and different places, where the second must move the zone's state (pending or
side).

### Q-6 (Low) - an intake failure drops the fix itself, not only the judgement

`zones.rs:355-369`.

- Before this branch a failed insert dropped the fix (`.ok()`). Now any zone-side error rolls the fix back too: a
  zone row that doesn't decode, a failing `raise` or `push::queue`.
- A *persistent* zone error would stop that phone's trail entirely.
- The phone re-sends only its cached fix, so a fix replaced before the next good report is gone.

The evaluation already self-heals from stored fixes (`a_stored_fix_the_zones_have_not_judged_is_judged_next_time_once`).
So the fix costs nothing: on an error, log it and store the fix alone in its own transaction (`store_fix`). The
next report's intake judges it, once. That turns "never lost" from "unless an error" into "always".

### Q-7 (Low) - the Locate map's view: the zones vs an empty trail, a race

`static/locate.js:150` and `:305`.

- "A phone with no fix yet shows its first zone" holds only if `locations.json` answers before `zones.json`. If
  the zones come first, `draw([])` then sets the world view (`[20, 0], 2`).
- Switching to a span with no points resets to the world view too, with the circles off-screen.

What to do: in `draw`'s empty branch, fit the zone layer when it has layers; keep the world view only when there
are no zones either. A jstest should resolve the two fetches in both orders.

### Q-8 (Low) - a zone name may hold bidi and zero-width characters; the refusal's words are off

`handlers/zones.rs:124-125`.

- `char::is_control` refuses only `Cc`. `U+202E` (right-to-left override), `U+200B`/`U+2060` (zero-width) and
  similar `Cf` characters pass.
- So "Home" and "Ho\u{200B}me" are two distinct names that look alike, and an override can make the push text
  "Anna arrived at …" render reversed. These are the parent's own words, so this is not a security issue, but it
  is a confusing one.
- The message "Letters, digits and spaces - no line breaks." is not the rule: punctuation and emoji are accepted.

What to do: refuse `Cf` too (or strip it), and reword the message to "No line breaks or invisible characters."

### N-1 (Note) - the cursor deviation and re-enrolment are right

See §1. The new zone's cursor at the newest fix avoids replaying a month of trail into a new zone. Re-enrolment
clears the sides and keeps the cursors.

### N-2 (Note) - the optional seed for a new zone

§5 (2): the newest fix as the *pending* side when it is under 30 min old. It halves the wait for "Here now" and
can't raise anything. This is the user's call.

### N-3 (Note) - M13 is an equivalent mutant

`zones.rs:166`. Re-judging the exact cursor fix is idempotent in `step`, and the SQL never fetches it anyway:

- if it was unsure, it does nothing;
- if it set the pending side, the gap is 0 s, so it does nothing;
- if it is on the side, it clears a pending side that is already empty.

`<=` is the clearer reading. Keep it; no test needed.

### N-4 (Note) - late fixes and step 2

A fix that arrives after a newer one (two syncs out of order) is stored and never judged. That is decision 3. The
crossing is then timed at the next fix rather than lost. Step 2's `extra_locations` must keep inserting every
extra fix before `evaluate_stored`, in the same transaction, which is how `intake` is shaped today. Worth a line
in step 2's brief.

### N-5 (Note) - a queued push can outlive its "news" window

Freshness is decided at intake. A delivery queued just before a server outage or a long push-service failure is
still sent later, within its TTL of 3600 s. That is fine; mentioned only because the rule reads as absolute.

### N-6 (Note) - `forget_sides` at enrollment is best-effort

`handlers/device_api.rs:152`. It runs after the token refresh, outside a transaction, and only logs a failure. If
it fails, the new phone's first sure pair could be told against the old phone's side, for example a false "left
Home". It is a single `UPDATE` on a healthy database, so I accept it. Moving it into the enrollment's own write
would close the gap.

### N-7 (Note) - the first clippy run

`error: could not compile ... (bin "kid_phone_server" test)` came with no diagnostic line, at about 1.5 GB free.
The rerun was exit 0 with only the 13 known warnings. It is not the branch.

### N-8 (Note) - small text nits

- `tests/music_spotify.rs:176-184`: the doc list of renames lacks "(was `migrations_73_to_90_in_order`)".
- `zones_json` answers `[]` with 200 for an unknown phone, where the zone pages give 404. It is admin-only and
  harmless.
- Attribution: the six commits are the implementer's; the merger decides.

## Verdict

**Merge after a short fix round.** The core is correct, and it meets the user's "no duplicates, nothing lost"
demand:

- the dedupe;
- the once-only, in-order evaluation under one transaction;
- one row per crossing;
- the push decided per parent and per freshness;
- retention with the trail.

In the round:

- Q-1: the page must not say "at Home" under a fix outside.
- Q-4 and Q-5: the two tests the mutations found.
- Q-3: the zone forms in the repo-wide guards.

Q-2, Q-6, Q-7 and Q-8 are small and can go in the same round. Q-6 is the one step that makes "never lost"
unconditional.
