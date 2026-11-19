# Code QA: `server-small` (server 0.86.1), 2026-11-20

Branch `origin/server-small` at `a18e9c752`, reviewed as `git diff 4751595c4...origin/server-small` (19 files, server
and docs only). Commits: `5c05496c8` (Check Now answers at the listing), `154c3f553` (catalog logos in Notifications),
`63613235b` (Mobilnett: the ship header, `switch_stops_data`), `95c682ae1` (the backup check race), `a18e9c752` (0.86.1).

Targeted tests run (`-j 1`, `CARGO_TARGET_DIR=/home/palchrb/cargo-targets/server-small`): see "Tests run" at the end.

**Verdict: no High.** One Medium (a choice for the user, on the ship header). The Check Now locking, the 10 s
answer, the auto-reload's stop and the backup fix are correct.

## What was checked and holds

- **Check Now's guard** (`handlers/tracked_apps.rs` `check_now`, ~l.4183): `AppSyncs::start` is taken in the handler
  with no `.await` between it and `tokio::spawn`, so a dropped request can't leak it; the guard moves into the task and
  `SyncGuard::end` consumes it. In `end` the `MutexGuard` (`map`) is a body local and `self` a parameter, so the map
  lock is released before `Drop for SyncGuard` locks again - no self-deadlock. A panic in the task drops the guard
  during unwind (running_since cleared). Two syncs of one app can't run: `start` checks and sets `running_since` under
  one lock; the hourly loop and Check Now both go through it. A Check Now that finds one running answers at once.
- **The 10 s**: the only wait is `timeout(CHECK_NOW_WAIT, select!{sync, downloading})`; before it only one indexed
  `SELECT`. The listing calls are bounded on their own (GitHub client 15 s, F-Droid `REQUEST_TIMEOUT`/`STALL_TIMEOUT`),
  the download by `DOWNLOAD_TOTAL_TIMEOUT`, so the background task always ends.
- **`downloading`** is set only in `sync_release` after the cached and the held early returns, cleared by `start` and
  by the guard's `Drop`; `end` reads it before `Drop` clears it, so `nothing_new` is right. The test-only callers of
  `sync_release`/`sync_one_app` without a guard can leave it set, but nothing shows it without `running_since`, and the
  next `start` clears it.
- **The auto-reload can't loop on a failure**: `data-waits` is drawn only inside `{% if let Some(since) = sync_running %}`;
  every way out of a sync (Ok, Err, panic, `Removed`) drops the guard and clears `running_since`. `auto-reload.js` is in
  the head partial on every page, refreshes in place through `handySwap.refresh` (falls back to a reload that keeps the
  place), and holds while a `<details>` is open, a field is edited or a save is on its way - so a refresh never jumps
  and never eats the Details or "Show on phones as" forms.
- **"No new version"** clears at the next `start` (hourly, Check Now, approve/ask again/update now/forget holds all take
  the guard), is false on any failure, and is hidden while a release waits or is held.
- **Logos on Notifications**: one `SELECT ... IN json_each` for the apps and one `catalog_logos_for` (two queries and
  a stat per candidate hash) per page - no N+1. `catalog_app_of` takes only rows with no `device_id`, so `app_updated`
  (per phone) keeps the phone's photo; `as_i64` ignores a non-number. `tracked_apps.id` is `AUTOINCREMENT` and never
  rebuilt by a later migration, so an old event can't pick up a new app's logo; a removed app falls back to the letter.
  The logo rule is the Apps tab's (`catalog_logos`, the Vibb mark for the launcher) - nothing a parent couldn't see on
  the Apps tab already.
- **The backup race fix** (`handlers/backups.rs` `forget_gone`, ~l.547): with the rows read first, a row can only be
  deleted if its file was missing when the folder was listed after the read; a row inserted after the read is never
  touched. Every row is written after its file exists (`perform_backup`, the upload, `check_newest_unchecked`), so the
  fix is complete. The other caller (`delete_backup`, ~l.1146) is only helped by it. A row of a file pruned while its
  check ran is removed by that check's own `forget_gone`. The new test reproduces the old order's loss.
- **Mobilnett against `docs/testing/2026-11-19-roaming-emulator.md`**: row 8 unflagged (`enabled:false, roaming:false,
  where:abroad`) now gives the plain switch line and "Roamer i Sverige" (finding 2 fixed); row 8 flagged (no session)
  keeps "Roamer i Sverige – Androids roamingbryter er av" with "ingen mobildata nå"; a lingering session
  (`data_active:true`) has data and gets the plain line; Av (rows 7, 14, 16) returns before the new rule; row 12 (ship,
  unflagged, Bare Vibb) - the header now says nothing, as the page does (finding 1 as written; but see M1). The changed
  fixture in `tests/mobile_network.rs` (`data_active` true -> false) is the realistic flagged state.
- **i18n**: `apps.app.downloading` and `apps.app.nothing_new` are in both catalogs; the ship header and switch words are
  existing keys.

## Medium

### M1 - On a ship network Android doesn't flag, the header now hides that the phone is on a ship at all

`server/src/mobile_network.rs:342` (`summary_roaming`), with the page's same gap at `:601` (`card`'s roaming line
filter, which drops a ship through `abroad_unflagged`).

Scenario: Bare Vibb (a new phone's default), the phone on Telenor Maritime `90112` unflagged (row 12). The parent gets
the notice "Emma er på roaming på et skips- eller flynett", opens the phone, and neither the summary's header nor the
Mobilnett page says anything about a ship. The same ship flagged by Android shows the orange "Roamer på et skips- eller
flynett" in the header and on the page, and an unflagged Swedish network shows "Roamer i Sverige". Under Bare Vibb the
sink still lets Vibb itself through (rows 4 and 8), so Vibb uses maritime data either way - whether Android sets the
flag is a carrier-config detail, but it now decides whether the parent sees the ship. Finding 1 was right that
"låsen dekker det ikke" is wrong under Bare Vibb/Av. The fix silenced the whole line, not just that wrong clause.

Fix: when `sink_covers_ship`, word an unflagged ship as `data_roaming.ship` (the flagged wording, orange as today),
both in `summary_roaming` and in `card`'s roaming line (let the filter take `ship_network(report) &&
sink_covers_ship(policy)`). Keep `ship_home` for På only. The user sees this, so ask them before building
(visible-cost choice).

## Low

### L1 - Ship coverage is read from the policy, not from the phone's sink

`server/src/mobile_network.rs:342` and `:411` (`sink_covers_ship(policy)`). The launcher arms the abroad sink only
without a lift (`abroadArmed(choice, lift)`, `launcher/.../server/MobileNetwork.kt:42`). During "pause all
restrictions", or before the phone has the new choice, an unflagged ship isn't covered, but the header (newly) and the
page (as before) say it is. Before this branch the header said the orange `ship_home` then, which was true. Since a
pause is the parent's choice, this is Low. Fix: treat the ship as covered only when the report's
`mobile_data.sink == "abroad"` (or `!latest.paused_at(now)`), in both places.

### L2 - The plain switch line's ⓘ is wrong in the new unflagged-abroad case

`server/src/mobile_network.rs` `switch_line` else branch -> `mobile.abroad.switch.off_sub` ("Uten den har telefonen
ingen mobildata i utlandet, heller ikke Vibb. Slå den på én gang før dere reiser ..."). After finding 2's fix this
line also shows when the phone is abroad on an unflagged network and has data. The ⓘ then says it has none and talks
of "before you travel". Fix: a sub for `where == "abroad" && !switch_stops_data` (e.g. that data works on this
network but stops on one Android counts as roaming), or reword `off_sub` so it holds in both cases.

### L3 - Check Now during an hourly sync gets no answer

`server/src/handlers/tracked_apps.rs:4196`. When the hourly sync holds the guard, Check Now redirects at once and the
page follows the hourly sync. That sync ends through `finish` (`check = false`), so "No new version" never shows for
the click, and the hourly sync isn't `forced`, so a refused release isn't retried. The parent pressed the button and
sees the row go away without a word. Rare (an hourly window), so Low. Fix: when `start` returns `None`, set a
`check_asked` flag on the status for `end` to honour, or keep it as is and note it.

### L4 - "No new version" next to a declined release; sticks on a disabled app

`server/src/handlers/tracked_apps.rs:3875-3878`. The filter hides it for `held`/`waiting` but not for `declined`, so
the check group says "Ingen ny versjon" above the update group's "Beholdt 1.0 · 1.1 avslått". Also, the hourly loop
syncs only `enabled = 1` apps, so on a disabled app the line stays until a restart or the next Check Now. Fix: add
`u.declined.is_none()` to the filter (or accept: the declined one isn't new to the parent). The disabled case is
acceptable next to "Last checked".

### L5 - The Notifications avatar isn't quite the tile's rule

`server/src/handlers/notifications.rs:359`, `templates/notifications.html:66`. The house rule for an app's tile (the
band, the Apps tab, the lock-screen list, PLAN item of 2026-11-18) is logo -> chosen glyph -> letter. Notifications go
logo -> letter, so an app with no logo but a chosen glyph shows the grey letter here and the glyph everywhere else.
Also `.avatar-img` has no hairline (`.app-row-logo`'s `box-shadow: 0 0 0 1px var(--sep)`), so a white logo has no
edge on the white card, which the Apps tab avoids. Fix: carry `AppTile` (or logo plus glyph) from `logos_by_id`, render
the glyph like the band, and add the hairline for a catalog row's logo.

### L6 - Nit: the start time hangs on the version number

`server/templates/tracked_app_detail.html:30`: the downloading row puts `title="{{ since.exact }}"` on the version
("1.0.57"), so hovering the version shows a start time with nothing to say what it is. Drop the `title`, or put it on
the label.

## Copy ("Words in the UI")

- "New version found, downloading" / "Ny versjon funnet, laster ned" with the version as the sub-line: a state in a
  few words, no restated label, fine. A shorter "Laster ned 1.0.57" would also do, but the user asked for "viser hva
  som skjer", so this is not a finding.
- "No new version" / "Ingen ny versjon" is quiet (`row-label-quiet`), shown only as the answer to the parent's own
  click: fine.
- No new orange lines; the Mobilnett change only removes a wrong one (but see M1 and L2).

## Tests run

`cargo test -j 1 --locked -- check_now a_catalog_event_shows_the_apps_logo a_check_beside_another
the_header_takes_the_pages_ship_rule mobile_network a_backup_that_cant_be_read` on `a18e9c752`: 20 passed, 0 failed
(among them `check_now_answers_at_the_listing_and_says_what_it_found`, `check_now_while_a_sync_runs_says_so`,
`a_catalog_event_shows_the_apps_logo`, `a_check_beside_another_keeps_the_new_backups_row`,
`a_backup_that_cant_be_read_shows_until_it_is_gone`, `the_header_takes_the_pages_ship_rule`,
`the_switch_line_shows_only_when_off_and_keeps_the_national_line`, `tests::mobile_network::the_contract` and the
page/summary tests). Not run: the whole suite, fmt, clippy (CI's job).
