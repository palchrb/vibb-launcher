# QA 30 step 1 (code) - two mobile-data switches and the music storage limit (server)

Reviewer: code QA, 2026-10-13. Branch `design-30-s1`, `6f3157e8..97b197f1` (one commit, `97b197f1`; `6f3157e8` merges
`main` into the design branch). Read against `docs/design/30-music-streaming-data.md` (§1.2, §1.3, §1.8, §1.10, §3,
§6, the Design QA section and the step 1 status), `qa-30-design.md`, the root and server `CLAUDE.md`. Also read: the
code the step touches (`migrations/0073_music_data_and_storage.sql`, `models.rs` `DevicePolicy`, `music.rs`,
`handlers/music.rs` `device_card` / `mobile_switches` / `save_device_settings`, `templates/device_detail.html`
`#music` / `#music-settings`, `templates/music_entry.html`, `static/scroll-restore.js`, `static/style.css`), every
reader of `device_policy` (`SELECT *` into `DevicePolicy`, `handlers/devices.rs:128`'s insert), the triggers on
`device_policy` (0071, 0072), the CSRF layer (`main.rs:1171`), and on the launcher side `musicPolicy`
(`launcher/.../music/MusicPlan.kt:76-91`) and `PolicyResponseCompatTest`. Line numbers are `97b197f1`'s. No product
code was changed: the mutations below were temporary, each reverted with `git checkout -- server`, and `git status`
was clean before this file was written.

**Verdict: ready to merge; the Lows are optional and can go into step 2's sitting.** The migration, the wire keys, the
save handler and the card do what §3 says, and every rule that matters is pinned. 9 of 10 mutations went red. The one
that survived is the event text for an old page's post. Cross-version holds: today's launcher reads the music object
by key, and `mobile_data` stays as AND. Counts: 0 High, 0 Medium, 4 Low.

## What I ran (under the shared build lock, rustc 1.99.0)

- **Unchanged `97b197f1`:** `cargo test`: **670 passed, 0 failed**. `cargo fmt --check`: clean. `cargo clippy
  --all-targets`: 13 warnings (dead fields in `models.rs`, `dns_engine.rs`, `storytel.rs`, `devices.rs:1321`). None is
  on a line this step touches.
- **Mutation runs.** Each mutation was a temporary product-code edit, run against `tests::music::`,
  `tests::music_storytel` and the `music::tests` unit tests, then reverted. Mutations in one run had disjoint target
  tests, so each red test belongs to one mutation.

  | Mutation | Result |
  |---|---|
  | M1 `mobile_data_wire`: `stream \|\| download` (`music.rs:120`) | red: `mobile_data_on_the_wire_is_both_switches`, `the_policy_carries_a_small_music_object` |
  | M2 migration: `music_download_mobile_data = 0` instead of the old value (`0073:15`) | red: `migration_0073_...` |
  | M3 `older_phone_note` without `cache_bytes.is_some()` (`music.rs:133`) | red: the unit test and `the_card_says_an_older_phone_...` (the launcher-only report) |
  | M4 the warning at `>=` the limit (`music.rs:168`) | red: `the_card_warns_when_kept_items_alone_pass_the_limit` |
  | M5 the hidden `v=2` removed from the template | red: `the_card_shows_two_switches_...` |
  | M6 `mobile_switches` ignores `v` (always the new names) | red: `a_page_from_before_the_update_...` |
  | M7 `storage_limit`: a stored non-choice -> `None` | red: `storage_limit_reads_choices_null_and_junk`, `the_policy_carries_...` |
  | M8 the `CASE` always writes the posted limit (`.bind(true)`) | red: `the_policy_carries_...` ("a post without the limit keeps it"), `a_page_from_before_...` |
  | M9 a bad limit saved as "no limit" instead of 400 | red: `the_policy_carries_...` |
  | M10 the template without the `data_note` block | red: `the_card_says_an_older_phone_...` |
  | M11 the event names the posted limit (`limit.flatten()`), not the stored one (`handlers/music.rs:3818`) | **survives** (L2) |

## Low

### L1. The over-the-limit warning can say "2.0 GB of the 2 GB limit"

- **Where.** `storage_line` (`music.rs:165-173`) warns when `kept > limit`. `gigabytes` (`music.rs:1724`) prints
  `{:.1}`, which rounds to the nearest tenth.
- **Scenario.** With a 2 GB limit, kept items of 2,040,000,000 bytes trigger the warning, and it reads "Kept episodes
  and books alone use 2.0 GB of the 2 GB limit - lower an entry's offline depth or raise the limit." To the parent that
  looks like "at the limit", which isn't a reason to act. A keep set grows an episode at a time, so it is likely to sit
  just past the limit. From 1.95 GB to 2.05 GB the display shows "2.0 GB", but only the part above 2 GB warns. Nothing
  reaches the card before step 3a (the card passes `None` for `audio_bytes`), so 3a is the latest point to fix it.
- **Fix.** Round the kept figure up to the tenth for this message, so that over the limit always reads above it
  (`(bytes + 99_999_999) / 100_000_000` tenths). Or say how far over: "use 2.04 GB, over the 2 GB limit". Add the
  2,000,000,001-byte case's text to `the_card_warns_when_kept_items_alone_pass_the_limit`.

### L2. The event for an old page's post isn't pinned (M11 survives)

- **Where.** `save_device_settings` logs the limit in force (`RETURNING music_storage_limit_gb`, `:3794`, `:3818`).
  That is the reason for the `RETURNING`: a post without `storage_limit` keeps the stored limit, and the event should
  name it. `a_page_from_before_the_update_sets_both_switches_from_mobile_data` checks the policy but not the event.
  `the_policy_carries_...` checks the event only after posts that carry a limit.
- **Scenario.** A later edit logs the posted value (`limit.flatten()`, M11). An old page's save then logs "storage
  limit no limit" while the phone keeps 10 GB, and no test goes red. The parent reading the event log sees a limit
  that was never set.
- **Fix.** In `a_page_from_before_...`, after the old page's post, assert the last `music_phone_settings` event:
  "device {device}: streaming on mobile data true, downloads on mobile data true, storage limit 10 GB, volume cap off,
  Storytel false".

### L3. The launcher's pinned sample of the music object is the old shape until step 2

- **Where.** `launcher/.../server/PolicyResponseCompatTest.kt:416-421` pins the music object as the four keys. The root
  `CLAUDE.md` says: "The policy JSON shape is pinned on both sides ... A change to the API goes into both directories
  in the same commit." Spec §4 moves that test to step 2, and the status section says so.
- **Scenario.** If step 1 merges to `main` alone (steps of designs 28 and 29 were merged one at a time), `main` has two
  pins that disagree. Nothing in the launcher's tests shows that the shipped `musicPolicy` accepts the seven-key
  object. It does: it reads `library_version`, `mobile_data`, `volume_cap_pct` and `storytel_generation` by key and
  ignores the rest (`MusicPlan.kt:76-91`, checked by reading). But that is the compatibility claim of §1.3's table row
  "new server, old launcher", and it has no test.
- **Fix.** Either merge step 1 to `main` together with step 2, or add a test-only launcher change to this step:
  `PolicyResponseCompatTest`'s sample with the seven keys, and `musicPolicy` of it returning the old four values. That
  is the "old launcher, new server" pin, and step 2 keeps it.

### L4. "Update both" before there is an update

- **Where.** `older_phone_note` (`music.rs:127-138`). Until step 3a, every phone with an app report gets the note
  while the switches differ (spec §3, accepted by design).
- **Scenario.** A `server-v*` tag cut from a `main` that has step 1 and not 2/3a gives the parent a working pair of
  switches and a note that says "Update both". No launcher or music release that reads two switches exists yet. The
  parent has nothing to update.
- **Fix.** No code change. Release order: cut the first `server-v*` with step 1 no earlier than the `launcher-v*` and
  `music-v*` releases of steps 2 and 3a. Or put that in PLAN.md's design 30 entry, so the orchestrator holds the
  server tag. If the server has to ship first, the note could read "... Update both once a new version is out."

## The implementer's interpretations

- **`older_phone_note` / `storage_line` take `Option<i64>`s, not `&MusicState`: agree.** `MusicState` gains
  `audio_bytes`/`extra_bytes` only in 3a, where the fixture test needs every field at once. This way both rules are
  complete and unit-tested now. 3a changes two call sites (`handlers/music.rs:3561`, `:3616`), both commented.
- **`old_database` stays in `docker.rs`: agree.** QA L7 asked for a 0072 database. `tests::cleanup::migrated_before(73)`
  plus `finish` builds one from the same `sqlx::migrate!` set as production. It is the helper every other migration
  test uses (0037, 0048, 0049, ...). `docker.rs`'s version writes a file-backed WAL database with an admin row for the
  image tests, which a migration test doesn't need. Moving it would be churn.
- **The older-phone note as a `.error` block: agree.** `.error` is the only warning style in `static/style.css`, and
  the card's other warnings (bridge, Storytel refused, "the launcher ... doesn't handle music yet") use it. It sits
  under the two switches, inside `#music-settings`. After the save that makes the switches differ, the anchor lands
  on the form and the note is in view.
- **A present-but-empty `storage_limit` is 400 (the volume cap reads `""` as off): agree.** A `<select>` always posts
  one of its options, so `""` only comes from a hand-made post. Refusing it can't hurt an old page, which never sends
  the field.

## Checked and fine

- **Migration 0073.** It adds both switches `NOT NULL DEFAULT 0`, copies the old value into both, drops
  `music_mobile_data` and adds `music_storage_limit_gb INTEGER DEFAULT 2`, so existing rows get 2 and NULL stays
  possible for "no limit".
  - No index, view or trigger names the dropped column. The `device_policy` triggers (0071, 0072) are `AFTER UPDATE OF`
    other columns, so the copy's `UPDATE` bumps no revision.
  - The drop has precedents (0012, 0017). sqlx 0.8's `sqlite` feature bundles SQLite, so `DROP COLUMN` is available on
    the Pi and in the image.
  - Every `DevicePolicy` read is `SELECT *`. The only explicit insert (`devices.rs:128`) names none of the music
    columns, so a new phone gets the defaults.
  - The 0073 test covers an upgraded database (old 1/0 rows, the other columns kept, the old column gone) and a phone
    added after it. The 0049 test checks a fresh install's defaults: `(false, false, None, false, Some(2))`.
  - As with every migration, a downgraded binary refuses to start (sqlx: the applied 73 is missing), and the rollback
    is the pre-migration backup (DEPLOY.md).
- **Policy keys.** `mobile_data` = `stream && download` (`mobile_data_wire`). `storage_limit_gb` is a choice, `null`
  for NULL, and 2 for a stored non-choice (tested with 7 and junk). `policy_json_keys_snapshot` has all seven keys
  with a fresh phone's defaults. The policy has no ETag or 304, so the new keys reach a phone at its next poll.
- **The save handler.**
  - **`v=2`:** with it, each checkbox saves alone and a missing one is off. Without it (an old page), `mobile_data` sets
    both and the stored limit stays (`CASE WHEN ? THEN ? ELSE ... END`), both tested. With `v=2`, a stray
    `mobile_data` is ignored (tested).
  - **The limit:** missing = kept. Present but not `none` or a choice = 400 before any write. The test posts
    `stream_mobile_data=on` with each bad value and checks that streaming stayed off.
  - **404:** an unknown phone is a 404 (`RETURNING` -> `fetch_optional` -> `None`), with no event and no nudge.
  - **CSRF/auth:** the route is in the admin router under the global `csrf_guard` layer, unchanged, and is in the CSRF
    test's endpoint list (`tests/music.rs:3676`).
  - **The event:** it names both switches, the limit in force, the cap and Storytel (tested for posts with a limit;
    L2 for one without).
- **No jump to the top.** The form keeps `id="music-settings"` and the redirect `#music-settings` (tested on both the
  new and the old page's post). `scroll-restore.js` leaves a redirect with a fragment to the browser's anchor, as
  before. The new controls are `onchange="this.form.submit()"` like the old ones.
- **The card.** The wording is §3's (labels, "(not for an entry that keeps nothing)", the roaming subtitle, the limit's
  subtitle), and `music_entry.html`'s hint names the downloads switch. The select shows 1/2/5/10 GB/No limit, with a
  stored non-choice shown as 2. The note needs differing switches and an app report (`cache_bytes`), and not a report
  with only the launcher's fields (tested end to end). `storage_line` keeps today's line without `audio_bytes`, and
  its arithmetic saturates on junk numbers.
- **Escaping.** The note, the storage line and the warning go through askama's default escaping (`{{ }}`, no `|safe`
  in the card). The card test sees `This phone&#39;s`.
- **Cross-version.**
  - Today's launcher still gets `mobile_data` and reads only its four keys; the new ones are ignored.
  - The music app reads only the launcher's managed config, which is unchanged until step 2.
  - Migrated phones have both switches equal, so `mobile_data` is what it was. Nothing changes on any phone until a
    parent sets the switches apart. Then an old phone does the conservative thing (AND), and the card says so.
- **Docs.** `server/CLAUDE.md` (data, PWA, wire, the Storytel note) matches the code. The step 1 status in the spec
  lists the interpretations and says §8 check 1 wasn't run on a device.
