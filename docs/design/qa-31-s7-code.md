# QA 31 step 7 (code) - Device › Music

Reviewer: code QA, 2026-10-14. Branch `design-31-s7`, `9d19277f..f97bf56e` (one commit, 22 files, server and docs).
I read it against `docs/design/31-pwa-redesign.md`:
- §1.2, §1.6, §3.0, §3.2 (Device › Music), §3.4, §4, §5 (§5.1 incl. §5.1.4, §5.2, §5.5, §5.6), §8 step 7 and §9;
- step 6's status and fix-round table, and "Step 7 implementation status" with its 7 choices;
- design 30 (the two mobile-data switches, `v=2`, the storage limit, the played extras, the older-phone rule);
- `qa-31-s6-code.md` and `qa-31-s14-code.md` (from `2eee4de1`) for the format and the bar, and s14's L4 (a fragment
  must name the form's own group).

I also read the code the step touches or leans on:
- `handlers/device_pages.rs` (`view_music`, `Row`, `Note`, `version_of_code`, About's Music app row),
  `handlers/music.rs` (`device_card` in full, `set_device_entry`, `save_device_settings`, `save_device_sonos_rooms`,
  `refused_rooms`, `notice_text`, `phone_music_href`), `handlers/devices.rs` (`toggle_app`'s `back=music` paths,
  `toggle_refusal`, the summary's Music row), `music.rs` (`storage`, `older_phone`, `sanitize_music_state`/`word`,
  `entry_error_text`), `attention.rs` (which facts reach the summary);
- `device_music.html`, `device_detail.html`'s diff, `partials/note_warning.html`, `partials/info_row.html`, the CSS
  and head diffs; `static/scroll-restore.js`, `autosave.js`;
- the launcher's `MusicPlan.kt` (the `bridge` word is only ever `bound` or `unbound`);
- every changed test (`tests/device_pages.rs`'s step 7 part in full, `tests/music.rs`, `tests/music_storytel.rs`)
  and the docs diff (`server/CLAUDE.md`, `server/DEPLOY.md`, `music/CLAUDE.md`, `docs/testing/emulator.md`).

Line numbers are `f97bf56e`'s. No product code was changed: the mutations below were temporary and are reverted, and
`git status` was clean before this file was written.

**Verdict: merge after a small fix round.** The step does what §8 step 7 asks:
- one GET route, `/devices/{id}/music`, chained with the settings form's POST on the admin router (full auth, CSRF,
  session); unknown phone 404; every read the page needs is a 500 "Couldn't read this phone", never an empty library
  or the defaults (pinned by renaming four tables, and by mutation 7);
- the forms: the app switch returns to the page without a fragment (its first group), an entry's switch to
  `#music-entry-<id>`, the settings form to `#music-settings` (its action carries the same fragment), Sonos to
  `#music-sonos`; the refusals land in their own group (`#music-app`, `#music-entry-<id>` with `music_entry=`,
  `#music-sonos`), a deleted entry at `#library`, a deleted catalog row at `#music-app`. The redirect table is built
  from the rendered forms and applies s14 L4's rule by hand: each fragment names the element that holds its form, and
  an action's own fragment equals the redirect's. Every auto-saving form has its own `<noscript>` Save;
- design 30 is intact: the `v=2` form is one form with the five fields (pinned by `form_action_of` on each), the
  handler and its redirect are unchanged apart from the path, an older page's `mobile_data` still sets both;
  `server/testdata/music_*.json` and `music_state.json` are byte-identical (`git diff` is empty for them), and
  `policy_json_keys_snapshot` passes untouched;
- the phone for every write comes from the path; entry ids are bound parameters; `back` only picks between two fixed
  targets; the Storytel login is never on the page (the page is in `the_card_shows_no_login`'s sweep, with a stored
  login, a ticked Storytel entry and the switch on);
- phone-reported text is safe by construction: `entry_errors`' codes, `storytel` and `bridge` pass `music::word`
  (`[a-z0-9_]`, short) at intake, the package `valid_package_name`; Sonos names are escaped (pinned with
  `Kjøkken <b>`), and an unseen room's uid sits only in `title`;
- the typed values (M5): `music::storage` and `music::older_phone` replace the sentence builders, with their unit
  tests moved; the On the phone rows use `Row`, the warnings `Note`; the report's age is relative with the exact time
  in `title`.

On the choices the review was asked to look at:
- **(1) The app switch without a fragment.** Right. It is the page's first group: without JS the answer lands at the
  top of the page, which is where the switch is (only the page-level warnings sit above it); with JS scroll-restore
  keeps the exact place (same path). Its refusals carry `#music-app` and the flash is inside that group.
- **(2) `#music-settings` under a long library.** Right. Without JS it is the only way back below 200 entries; with
  JS it puts the form's top ("Mobile data") at the top of the viewport, and the lowest control (Storytel) is about
  400 px below it on a phone, so the changed switch stays in view. Same trade as Locate's `#location-settings`.
- **(3) Three groups inside one form.** §8 step 7 says "as one group"; §5.6's real rule (H1) is one form, which this
  keeps: every control still saves all five values. Advanced › Restrictions set the precedent in step 3.
- **(4) Errors for entries no longer in the library dropped.** Right: there is no row and nothing to fix, and the
  phone stops reporting them once it has the new library. (An error for an entry that is in the library but switched
  off on this phone still shows under its off switch until the phone's next report - N3.)
- **(5) The bridge's raw state not shown.** Nothing is lost: the launcher sends only `bound` or `unbound`
  (`MusicPlan.kt:287`), so "(unbound)" said no more than the warning does.
- **(6) A deleted entry's switch returns to `#library`.** Right (the double-submit rule), and an unknown phone stays a
  404 (both pinned, mutation 5).
- **(7) The renamed labels.** They match what the music app does (design 30 §1.1, `music/CLAUDE.md`): "Stream on
  mobile data" gates playback off Wi-Fi and the save-as-it-plays (`streamAllowed`, `StreamGate`), "Download on mobile
  data" the download job and Storytel books (`downloadAllowed`). "Storage limit", "Music downloads … of 2 GB" and
  "Played, kept for now" follow design 30 §1.10. `music/CLAUDE.md` is updated to the new names.

What is left:
- **M1:** the music warnings left the phone's summary with no Attention row and no hint on the Music row.
- **L1:** an entry's title is escaped but nothing pins it on this page, and titles come from feeds.
- **L2:** nothing pins the report's exact time as local.
- **L3:** "On the phone" shows the library's JSON size as a value, also for a phone that never reported.

Counts: 0 High, 1 Medium, 3 Low, 4 Nits.

## What I ran

All cargo runs used my own target dir (`~/.cache/handy-target/qa-31-7`), under `~/build.lock`.
- **`cargo test`, unchanged:** 796 passed, 0 failed.
- **`cargo fmt --check`:** clean.
- **`cargo clippy --all-targets --locked`:** the same 13 warnings as on main (`models.rs` dead fields,
  `dns_engine.rs`, `storytel.rs`, the old `sort_by` in `devices.rs`). None is in the step's code.
- **`node --test server/jstest/*.test.js`:** 23 passed.
- **The mutations.** Each was run with `cargo test -- music device_pages` (232 tests), then reverted:

  | # | Mutation | Caught by |
  |---|---|---|
  | 1 | an entry's switch returns to `/devices/{id}/music` without `#music-entry-<id>` | `music_forms_return_to_their_groups`, `the_policy_carries_a_small_music_object` |
  | 2 | the settings form returns without `#music-settings` | `music_forms_return_to_their_groups` and 3 more in `music.rs` |
  | 3 | a refused app switch (`back=music`) returns without `#music-app` | `music_refusals_land_in_their_group_and_the_page_fails_loudly` |
  | 4 | the 3 MB refusal drops `music_entry=` (the reason no longer by its row) | `ticks_past_3_mb_are_refused` |
  | 5 | an entry deleted since the page loaded is a 404 again | `the_music_page_ticks_entries_and_switches_the_app` |
  | 6 | the entry's title rendered `|safe` | **nothing** (L1) |
  | 7 | `device_card`'s library read `.unwrap_or_default()` (an empty library on a DB error) | `music_refusals_land_in_their_group_and_the_page_fails_loudly` |
  | 8 | the over-limit check on all downloads instead of the kept items (`audio` for `kept`) | `the_page_warns_when_kept_items_alone_pass_the_limit` |
  | 9 | the report's exact time in `title` as UTC (`at.format(...)` for `zone.exact`) | **nothing** (L2) |
  | 10 | the settings form's `<noscript>` Save removed | `music_holds_the_moved_card` |
  | 11 | the app switch's save returns to `#music-app` (a fragment on the first group) | `music_forms_return_to_their_groups`, `the_music_page_ticks_entries_and_switches_the_app` |
  | 12 | the phone's entry errors never shown under their rows | `the_phone_reports_music_state_with_known_fields_only` |
  | 13 | "N Storytel entries aren't sent" only for the switch off (not for a missing login) | `library_gates_storytel` |
  | 14 | a deleted catalog row with `back=music` returns without `#music-app` | `music_refusals_land_in_their_group_and_the_page_fails_loudly` |

  Two of fourteen survived (L1, L2).

## Medium

### M1. The music warnings left the summary with no signal there
`src/handlers/devices.rs:1537-1551` (the Music row), `src/attention.rs` (no music fact),
`src/handlers/music.rs:3814-3935` (the warnings).

Before this step the device page showed the Music card's warnings as red boxes on the page itself: "The launcher on
this phone doesn't handle music yet", "The launcher can't reach the music app", "Storytel refused the phone's login",
the storage warning (kept items over the limit), the older-phone note, and each entry the phone couldn't fetch. They
now live only on Device › Music, behind a row whose value is "6 in library" in every case, and none of them is an
Attention fact, so the summary and the Devices list's badge say nothing. A parent who doesn't open the Music page
learns about none of them: a phone whose music app is gone or can't be reached plays nothing and says so nowhere they
look.

Steps 3 and 6 settled this pattern: qa-31-s3-code M4/L4 added orange Attention rows for the warnings that moved to
Advanced, and step 6 made the Apps row say "List unreadable" for a corrupt allowlist. §1.12 also puts needs-action
messages in Attention.

**Fix:** add Attention facts for the ones that need the parent - "Vibb can't deliver music" (a reporting launcher
without `music_v1` while entries are on) and "Music app not reachable" (bridge not bound) at least, and arguably
"Storytel refused the phone's login" - each an orange row targeting `/devices/{id}/music` (the warnings sit above the
first group, so no fragment is needed; the Storytel one `#music-settings`). They come from the last report, so
`attention::facts` can read `music_state_json` and the capabilities it already has. Or, the smaller step, let the
Music row's value say "Needs attention" (orange) while `device_card` would show a warning, as the Apps row does. Pin it
in `tests/attention.rs` (a row per fact) and on the summary.

## Low

### L1. An entry's title is escaped, but nothing pins it on this page
`templates/device_music.html:57`.

Mutation 6 (`{{ tick.name|safe }}`) passed every test. Askama escapes it today, so this is a missing pin, not a hole.
It matters more than for most fields: an entry's name is the feed's or NRK's own title when the parent adds a link
(`add_link` takes `check_link`'s title), or a Storytel group's name, so it is third-party text. The Sonos names on the
same page are pinned (`Kjøkken <b>`), and step 6 pinned the app names (`<b>Game</b>`).

**Fix:** in `music_holds_the_moved_card` (or `music_phone`), add an own-files entry named `Eventyr <b>` and assert
the page has `&#60;b&#62;` (or `&lt;b&gt;`) and no `Eventyr <b>`.

### L2. Nothing pins the report's exact time as local
`src/handlers/device_pages.rs:1395-1398`, `tests/music.rs:3402-3407`.

Mutation 9 (the `title` as UTC) passed. The test asserts only "At the last report, <span title=\"" and ">just now<",
and "just now" doesn't depend on the zone. qa-31-s6-code L2 asked for the same pin on Device › Apps' Network row;
local time is a §4.2 rule on every page.

**Fix:** in `the_phone_reports_music_state_with_known_fields_only`, set Phone Setup's zone to Europe/Oslo and the
report's `reported_at` to a fixed instant yesterday, then assert the footer's relative text ("yesterday 12:00" in
Oslo, never the UTC clock) and the local time in its `title`.

### L3. "On the phone" shows the library's JSON size, also for a phone that never reported
`src/handlers/music.rs:3792-3799, 3911-3917`.

Before the phone's first music report, `device_card` inserts a row "Library · 3 entries · 12.3 kB" into On the phone.
Two problems:
- §2.2b lists exactly this ("This phone's library: 6 entries · 12.3 kB": package, version code, JSON size) as an
  implementation detail leaking into the page. With a report the size moved behind the row's ⓘ, which is right;
  without one it is the visible value. A parent reads "12.3 kB" next to a 2 GB storage limit and can't place it.
- The group is called "On the phone", but for a phone that has never reported - or isn't set up yet, which has the
  page too - nothing is on the phone. The row says what it *will* get.

**Fix:** before a music report, say what is true and short: "Library · Not there yet" (the value the reporting case
already uses for a phone without the library), with "This phone's library: 3 entries · 12.3 kB." behind the ⓘ as in
the reporting case - or leave the row out until the phone reports, so a phone not set up has no On the phone group.
`the_phone_reports_music_state_with_known_fields_only` and `the_music_page_ticks_entries_and_switches_the_app` pin
`starts_with("1 entry · ")` and move with it.

## Nits

- **N1.** The Storytel login row still shows an unknown code as text (`music.rs:3887-3891`, `other.replace('_', "
  ")`); §2.2b names "Storytel on the phone: {code}" as one of the raw fallbacks to remove, and the bridge's was. The
  music app only sends `none`/`ok`/`refused` (`StorytelAccount.state()`), so the arm is dead today: drop it (no row),
  or show "Unknown" with the code in `title`.
- **N2.** The Music app row (the version from the versionCode, "Installed" without one, the package and code behind
  ⓘ) is built twice, word for word: About This Phone (`device_pages.rs:529-543`) and `device_card`
  (`music.rs:3802-3819`). One `pub(crate) fn music_app_row(&MusicState) -> Option<Row>` in `device_pages` would keep
  them from drifting; About's key is `music-app`, this page's `app`.
- **N3.** An error the phone reported for an entry stays under that entry's row after the parent switches it off on
  this phone, until the phone's next report drops it (`device_card`'s `errors` map is read for every listed entry).
  "On the phone: not found at the source" under an off switch reads as current. Showing it only for entries that are
  on (`checked`) matches choice 4's reasoning.
- **N4.** Stale "device card" in the docs: `server/CLAUDE.md:587` (the library cache: "the policy poll, the library
  route and the device card build once per change") and the doc comments at `music.rs:1031` and `music.rs:1131` ("a
  tick on the device card"). "the phone's Music page".
