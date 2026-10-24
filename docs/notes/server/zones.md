# Server notes: safe zones

## Safe zones, step 1: the server judges every fix (design 64, `docs/design/64-safe-zones.md`, migration `0091_safe_zones.sql`)

Server only; works with every launcher (zones judged at each fix the phone already sends). Step 2 (the launcher's
fences, `location_policy.zones`, `extra_locations`, `zone_watch`, the 30-min floor on the served interval) is the
next section; without a launcher that reports a watch the policy's interval stays the parent's and the zones are judged
at each fix, as below.

- **Data** (0091): `device_zones` - one row per zone (`device_id` cascade, `name`, `latitude`/`longitude`, `radius_m`,
  `notify_arrive`/`notify_leave` default 1, `created_by`, `created_at`/`updated_at`) **with the rule's state on the
  same row** (one row per phone + zone, so a save and an evaluation are one row): `side` (NULL = not known),
  `side_since`, `pending_side`/`pending_at`, and the cursor `evaluated_at` + `evaluated_fix_id` (the newest fix judged,
  `(captured_at, id)` of `device_locations`; no foreign key, retention prunes fixes). `notification_prefs.push_zones`
  (default 1). `parent_events.params` (JSON; NULL for the older kinds). The ranges are code's, never the migration's:
  `zones::RADIUS` 100..=2000 m (default 150, slider step 10), `MAX_ZONES` 10, `NAME_MAX` 40 (the user's decisions 1-2,
  2026-10-24); a name is unique per phone ignoring case (`to_lowercase`, checked in the save's transaction).
- **The fix is stored once** (`zones::store_fix`): `INSERT ... SELECT ... WHERE NOT EXISTS` (same phone,
  `captured_at` as stored - normalised UTC ms, so `+01:00` and `Z` spellings of one instant are one - latitude and
  longitude); the launcher re-sends its cached fix in every report and each copy used to be a row. Accuracy is not part
  of the key. Old duplicates are left to retention. A different time, or another place at that time, is another fix.
- **The rule** (`src/zones.rs`, pure): `side(circle, lat, lng, accuracy)` - haversine on 6 371 008.8 m, everything in
  whole millimetres (`d + a <= r` inside, `d - a >= r` outside, else unsure; no accuracy, NaN or negative = unsure) so
  the boundary is exact and reproducible on the launcher in step 2; pinned by `testdata/zone_vectors.json` (15 rows:
  inside, outside, both straddles, the cell fix, no accuracy, `d + a == r` and one mm past it, `d - a == r` and one mm
  short, centre with `a == r`, antimeridian, pole, a 2 km zone; `zones::tests::the_shared_vectors`). `step(state,
  circle, fix)`: a fix not after the cursor `(captured_at, id)` changes nothing; else the cursor moves to it and -
  unsure: nothing more; sure and the zone's side: the pending side is forgotten; sure, other than the side, equal to
  the pending side and >= `CONFIRM_GAP` (60 s) after it: the side changes, `side_since` = the pending fix, a
  `Crossing {to, at: pending fix, confirmed_at}` **unless the old side was unknown** (the first side is silent); the
  same under 60 s: nothing; sure and neither: it becomes the pending side. `evaluate(zones, fixes)` sorts by
  `(captured_at, id)` and steps every zone through every fix.
- **The intake** (`zones::intake`, from `device_api::status` where the plain insert was, after `checked_location`), two
  steps (code QA Q-6: a zone's error never costs the trail its fix): (1) the fix stored once, on its own; (2)
  `zones::judge`, one `BEGIN IMMEDIATE` transaction - `evaluate_stored` (the phone's zones; the stored fixes after the
  oldest cursor, at most 1000, in order, the tie at the cursor's instant by id; each zone skips what is at or before
  its own cursor; the changed states written), `parent_events::raise` for each event, commit, then `push.wake()`. A
  failure or a crash in step 2 rolls back step 2 only: the cursors didn't move, so the next report with a location
  judges the fix, once (`a_zone_error_keeps_the_fix_and_judges_it_later`,
  `a_stored_fix_the_zones_have_not_judged_is_judged_next_time_once`); two parallel reports take turns, and whichever
  judges first leaves nothing for the other. Step 2 runs on every report with a fix, also when the fix was a duplicate.
  A late fix older than a zone's cursor is stored for the trail and never judged.
- **A new or moved zone** (`zones::start_over`, in the save's transaction; the pure part `zones::seed`): side NULL,
  the cursor at the phone's newest fix - the history before the zone is never judged against it (no "arrived at
  School" from last week) - and that fix as the **pending** side when it is younger than `SEED_WITHIN` (30 min) and
  sure for the new circle (the coordinator's decision on the code QA's open question 2). A pending side raises
  nothing, and the next sure fix on that side 60 s or more later confirms it silently (the first side): "Here now"
  after one new location, not two. A moved centre or a changed radius starts over; a rename or a switch keeps the side. A new phone enrolled into the row
  (`device_api::enroll`, `zones::forget_sides`): the sides go, the cursors stay (the old phone's fixes are never judged
  again).
- **Events** (`parent_events`): `Kind::ZoneArrived` (`zone_arrived`) and `Kind::ZoneLeft` (`zone_left`), after
  `PhoneOn` in `Kind::ALL`; not urgent, foldable, TTL 3600, urgency normal; **one switch for both**,
  `("push_zones", "Zones: arrived and left")` - `Kind::switches()` lists each switch once (Notify Me About's rows, its
  save, its read). Raised only when the zone's switch for that direction is on (`zones::event_of`; decision 5: the
  zone's switches decide whether there is an event, each parent's "Zones" only the push). Key
  `<zone id>:<inside|outside>:<the first fix's captured_at>`, `happened_at` = the first fix of the pair. **Stored as a
  key plus parameters, never prose** (the user's decision 5, 2026-10-24): `title`/`summary` empty, `params` =
  `ZoneParams {zone_id, zone, phone, direction: arrived|left, at}` (the names as they were when raised);
  `parent_events::told(kind, title, summary, params, zone, now)` makes the words where they are shown or pushed - the
  inbox and the summary's Activity (`notifications::inbox_row`, `ROW_COLUMNS` gained `e.kind, e.params`) and the sender
  (`push::send_due`, whose `DueRow` is now a `FromRow` struct): "Anna arrived at Home" / "Anna left School" (the phone's
  name cut to fit 60), summary the first fix's time by `Zone::at`, capitalised ("14:02", "Yesterday 14:02") - told at
  read time, so it ages like the other times. A later translation adds strings to `told`. Every other kind keeps its
  stored words. Activity drops a summary that is only its own clock (`without_own_clock`), and both row templates
  leave out an empty summary's " · ". Target `/devices/locate?device={id}#location`.
- **Push only while news** (decision 8): `Push::Now` when the confirming fix is under `NEWS_FOR` (30 min) old at the
  intake, else `Push::No` - the inbox and Activity always. Each parent's `push_zones` filters the push as every switch
  does (`push::queue`). Pauses change nothing (decision 4: zones keep watching and pushing).
- **Retention** (decision 6): `retention::prune` deletes `zone_*` events older than the phone's
  `location_retention_days` (by `created_at`, at least 1 day; the default 7 for a phone without a policy row), counted
  in `Pruned::parent_events`; the other kinds keep 90 days. A zone and its state go with the zone or the phone
  (cascade); a deleted zone's events stay until retention takes them.
- **What the pages say of the phone** (code QA Q-1, Q-2): the rule's confirmed side is the state; the words are
  stricter. The newest fix's row and the map popup say "At Home" from **that fix's own** `zones::side` (never a
  confirmed side: a first fix outside keeps Home's side until its pair, and the page must not say "at Home" under a
  marker 1 km away). "Here now" (the zone's row) and the summary's "At Home since 15:42" need `Zone::here_now`: the side
  inside, **no other side pending**, and the newest fix **fresh** (`zones::fresh`: younger than 2 x the interval in
  Regularly, else 1 h; `zones::freshness_inputs`). Inside and nothing pending but stale: the row says "Last seen at
  Home 14:02" (the newest fix's time, `data-last-seen`), the summary nothing.
- **Pages**: the Locate page's `#zones` and the zone's page (`handlers::zones`, `device_zone.html`, `static/zones.js`)
  - see `pwa.md`, "Locate: zones (design 64 step 1)". The phone's summary header: "At Home since 15:42"
  (`devices::zone_now_line`, `data-zone-now`: the zones the phone is in now, the latest `side_since`).
- **A zone's name**: 1-40 characters after trimming, unique per phone ignoring case, any visible character (letters,
  digits, spaces, punctuation, emoji); refused: control characters (`Cc`) and Unicode's format characters (`Cf`:
  zero-width spaces and joiners, bidi overrides, the soft hyphen, the BOM - `handlers::zones::is_format`), which would
  make two names that look alike or turn a push's words around (code QA Q-8): "No line breaks or invisible
  characters."
- **Security log**: `zone_added` ("device 3 (Anna): Home, 150 m"), `zone_changed` ("device 3 (Anna): Home - renamed
  from Hjem, moved, radius 150 -> 250 m, arrive off"; a save that changed nothing logs nothing), `zone_deleted`
  ("device 3 (Anna): Home") - the phone and the zone's name, **never coordinates**; labels "Zone added/changed/deleted"
  (`handlers::admin`). Every save nudges the phone (`command_notify`), for step 2's fences.
- **Not in step 1** (step 2 built them, below): the served interval's floor `max(chosen, 30 min)` with zones (decision 3) comes with step 2, to
  phones that report `zones_v1` - the Locate page says nothing about it yet, its footer gives today's timing ("within
  about two intervals"); the mock-location flag (decision 7): the status body has none today (`models::LocationReport`
  is latitude, longitude, accuracy, time), so nothing is ignored or counted yet - step 2 adds the flag and the count;
  the kid's line (decision 6) is the launcher's.
- Tests: `zones::tests` (the vectors, the side table, the pair and its 60 s, one bad fix, unsure, the cursor, the
  first side silent, a moved zone, time order across zones, the distance), `parent_events::tests` (the kinds in
  `the_words_and_targets`, `a_zone_event_is_told_from_its_parameters`, `two_kinds_share_the_zones_switch`),
  `handlers::zones::tests` (the form's fields and edges, the log's words), `handlers::activity::tests`,
  `src/tests/zones.rs` (the dedupe, the pipeline with two parents, the switches and old news, move/rename and
  enrollment, the self-healing evaluation, retention, add/save/delete landing on the row, every refusal at
  `#zone-form` with nothing written, the 11th zone, the Locate page by mode, the badge and the summary line, the
  session; since the code QA's fix round: the confirming fix's age alone decides the push, the tie at a cursor's
  instant, a zone error keeping the fix, "Here now" needing an agreeing fresh fix, the seed),
  `zones::tests::a_new_zone_is_seeded_from_a_fresh_sure_fix_only` / `here_now_needs_a_fresh_fix`,
  `locate_page::every_form_on_the_locate_page_returns_to_its_group` and
  `shell::every_form_on_every_page_lands_on_its_own_group` (with a zone: Locate Regularly, the zone's two pages),
  `admin_auth::tests::no_store_paths`, `music_spotify::migrations_73_to_93_in_order`, `jstest/zones.test.js`,
  `jstest/locate.test.js` (the circles, their view after the locations).

## Safe zones, step 2: the phone watches (design 64 §4, migration `0092_zone_watch.sql`)

Both directories; the launcher's half is `launcher/CLAUDE.md`, "Safe zones on the phone". The server still decides every
arrival and departure from the stored fixes (decision 1): nothing new comes from the phone but fixes and the watch's
counts.

- **The policy** (`device_api::build_policy`): `location_policy` always has `zones`, `[{id, latitude, longitude,
  radius_m}]` while the mode is `interval` **and the phone's latest report since its enrollment claims `zones_v1`**
  (`models::ZONES_CAPABILITY`, read by `last_capabilities`; qa-design-64-step2-code Q-10: an older launcher can't use
  the centres, so it isn't sent them), `[]` otherwise (`zones::policy_zones`; **never a name**, decision 9);
  `interval_minutes` is the **served** interval, `zones::served_interval(mode, chosen, zones, watching)` =
  `max(chosen, HEARTBEAT_FLOOR_MINUTES = 30)` only while Regularly, the phone has zones and its latest report since its
  enrollment holds fences (`zones::last_zone_watch`, `zones::watching`: `zone_watch.fences > 0`) - the user's decision
  3: no separate heartbeat setting, the parent's choice never written, the last zone deleted gives it back. An older
  launcher (no watch), a watch without fences (a refused registration) or a choice of 30 min or more: as chosen.
  `zones::served_interval_of` reads the same for the pages (`freshness_inputs`, so "Here now" counts in the served
  interval). Pinned by `policy_json_keys_snapshot` (`location_policy`'s keys) and the launcher's
  `PolicyResponseCompatTest`.
- **The report** (`models::StatusReportRequest`): `extra_locations` - the fixes the phone queued since its last report
  the server took (a crossing's two in a tunnel, a report that failed), oldest first; read leniently
  (`models::lenient_locations`: not a list, or an entry that isn't a `LocationReport`, is dropped and logged, never the
  report); each through `checked_location`; the newest `MAX_EXTRA_LOCATIONS` (20) kept (`zones::report_fixes`). A fix's
  `provider` and `mock` keys are the phone's, stored on the fix since migration 0093 (`device_locations.provider` -
  `models::location_provider`: a short lowercase word or nothing - and `mock`; only a debug launcher ever sends
  `mock: true`), not part of its identity (the dedupe is unchanged) and shown only in the Locate page's newest-fix row's
  title ("... · gps", "... · fused · test location", `locate::fix_title`). `zone_watch` - `{fences, motion_sensor, location_on, background_location, mock_ignored}`, all five or nothing
  (`zones::sanitize_zone_watch`: types, fences 0..=100, mock 0..=10^9, at most 512 bytes; other keys dropped), stored in
  `device_status.zone_watch_json` (0092). The launcher also claims `zones_v1`, which gates only the zones' coordinates
  in the policy (Q-10); the floor keys on the watch. `zone_watch.still` (Q-3, not stored with the watch -
  `zones::report_still`, a JSON `true` only): the report re-sends its cached fix while the phone's motion sensor says it
  hasn't moved since; `intake` then stamps that fix's row `device_locations.resent_at` (0093) with now, and "Here now"
  (`freshness_inputs`, `DeviceLocation::seen_at` on the Locate page) counts from the later of `captured_at` and it -
  the fix's own time and the trail are unchanged, a re-send without `still` stamps nothing.
- **The intake** (code QA N-4; `zones::intake(state, device, fixes, resent)`): every checked fix of the report - the extras,
  then the report's own - is stored first (`store_fix`, each on its own, the dedupe as step 1), then the zones are
  judged **once** (`judge`, one `BEGIN IMMEDIATE`); so a pair inside one report confirms at its first fix whatever the
  order it came in, and nothing is judged twice. A late extra older than a zone's cursor is stored for the trail and
  never judged (decision 3). Two parallel reports carrying the same fixes take turns: one crossing
  (`zone_watch::two_parallel_reports_raise_one_crossing`).
- **Mock fixes** (decision 14, the user's 7): a release launcher ignores a fix with `Location.isMock` and counts it;
  `zone_watch.mock_ignored` is the phone's **running** count since its data was created (not "since the last report":
  a lost report loses nothing; the phone counts a fix once by its time and place, Q-9). A rise against the count last
  logged (`zones::mock_rise`: `devices.mock_logged`, 0093, read and written, and the security line inserted, in one `BEGIN IMMEDIATE` transaction (the counter never moves without its line), so
  two parallel reports or a report without `zone_watch` in between log each rise once with its real size - F-5; a lower
  count - the data cleared - is taken silently and clears the date; an enrollment clears both, `device_api::enroll`) writes the security event `mock_location_ignored` ("Made-up locations ignored",
  "device 3: the phone ignored 2 made-up location(s) - an app pretended to be somewhere else (5 in all)" - never a
  place, `zones::mock_log_line`) and stamps `devices.mock_logged_at`; the Locate page shows the running count in the
  location group with when it last rose ("4 made-up locations ignored, the last 14:02").
- **Pages**: Locate's `#zones` footer and the watch's line, the "Every" row's note while the floor applies, the mock row
  - `pwa.md`, "Locate: zones", step 2.
- Tests: `zones::tests` (`the_served_interval_has_the_floor_only_while_watched`,
  `the_zone_watch_keeps_its_five_keys_only`, `the_mock_line_comes_with_each_rise`,
  `a_reports_fixes_are_checked_capped_and_ordered`, `the_policy_carries_the_zones_only_while_regularly`),
  `src/tests/zone_watch.rs` (the contract file both ways - `the_zone_contract_matches_the_launchers_copy`,
  `a_launcher_report_raises_the_arrival` -, the policy's zones and floor, every extra stored then one judging, two
  parallel reports, the mock counter and junk watches, the Locate page; the code QA's
  `a_watch_from_before_a_re_enrollment_gives_no_floor`, `a_location_older_than_its_extra_is_judged_in_order`,
  `here_now_counts_the_served_interval_and_a_still_re_send`), `device_api::policy_json_keys_snapshot`,
  `time_rules` (the policy's `location_policy` with `zones`), `music_spotify::migrations_73_to_93_in_order`,
  `zone_watch::a_fix_keeps_its_provider_and_mock_flag_in_the_title_only`.
