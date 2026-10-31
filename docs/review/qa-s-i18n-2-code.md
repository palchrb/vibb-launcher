# QA (code) - round S-i18n slice 2: events, pushes, the security log and the times in the parent's language

Reviewer: code QA, 2026-10-31. Branch `pwa-i18n-2`, head `ea3b9b7f` (d6b7921f the times + 0107, f8a0f4ec the events
and pushes, 1faa7475 the security log, cbee7056 the docs, ea3b9b7f the merge of `main` 9064518e), read as
`git diff 9064518e...pwa-i18n-2` in full (86 files), against design 70 §4, §8, §10, "Carried into the briefs" L2-L4,
the owner's decisions 1-4, `qa-70-design.md` H1-H3, pwa.md "Words", notifications.md "Push wording" and slice 1's review.
Line numbers are `ea3b9b7f`'s. No product code was changed; each mutation was applied by a script and the sources
restored (`git status` clean after).

**Verdict: sound - merge after one fix (M1) and a small test round.** The language rides on the Zone, every stored-words
kind raises as parameters and is worded when read, the pushes are worded per subscriber at send time, the security log's
rows are key + params with the facts kept apart, and the phone lines' budget still counts. No secret reaches a sentence.
The one thing a parent loses: the security log no longer shows a row's facts once it has words - the refused request's
route, and which phone a per-phone Screen Time save was for (M1). The rest is test gaps and small lossy corners.

**Counts: High 0, Medium 1, Low 5, Nit 7.**

## Setup and what holds

- **The times** (`display_time.rs`): `Zone { tz, fallback, lang }`, `Zone::load(db, lang)` (:69), `in_lang` (:87),
  `lang()`; every word through `time.*` (`word`/`with_clock`/`date_of`/`month_name`/`weekday_name`), no chrono locale.
  The English is the old text word for word - checked against the removed `format!`s ("just now", "{n} min ago",
  "yesterday {clock}", "{clock} tomorrow", "%a", "%-d %b", "%-d %b %Y"). `exact()` and the fallback's " UTC" untouched.
  24 h in both (D3). `every_time_form_in_both_languages` (:418) runs 35 en and 29 nb rows: "yesterday"/"i går" at Oslo's
  midnight (21:59 vs 22:00 UTC), "tomorrow"/"i morgen" across it, another year ("31 Dec 2025", "20. jan. 2027 13:30"),
  the ahead cases; `months_weekdays_and_dates` (:532) every name in both; `a_duration_in_both_languages` (:563).
  The year boundary for "yesterday" (1 Jan reading 31 Dec) isn't a row, but `pred_opt` carries the year - fine. L3's
  three stray `%b`s moved (`time_state.rs:247` -> `date_of`, `parent_pin::end_text`, `until_text` gone). L4: every page
  loads with its `lang` (a `Zone::load` without one no longer compiles); the sender loads once, `in_lang` per delivery.
- **The events** (`parent_events.rs`): `Phone::with_params` (:224) stores `params` and leaves `title`/`summary`/
  `push_title`/`push_body`/`detail` empty for all 8 kinds; a serde struct per kind (`PhoneAtParams`, `PinParams`,
  `ForeignAccessParams`, `LowBatteryParams`, `PhoneOffParams` with `reason`/`battery`/`charging`/`late` beside `location`
  and `#[serde(default)]` on every field so a 2026-10-25 row's `{"location"}` still parses, `PhoneOnParams`). Worded by
  `told`/`pushed`/`told_as` (:1521-1586) and `detail` (:1590) in the Zone's language; `fit_name` (:1113) cuts the name,
  `titled` clips again. `tests::notifications::…` asserts the five intake rows have empty columns and params, and reads
  the inbox in en and nb (nothing English on the nb page); `every_kind_is_worded_in_both_languages` (:3202) runs all
  13 kinds x their variants x both forms x both languages, no key showing, within the maxima with a 20-character name,
  en and nb titles differing; the four `…` tests pin the cut within `TITLE_MAX`.
- **The older row** = non-empty `title` (`told_as` :1581, `detail` :1598); `off_said`/`off_since_line` use the params'
  `reason` instead (every new `phone_off` has one, a 2026-10-25 row hasn't, so the two rules agree). Exercised: the
  inbox and Activity by `tests::summary::event` and `tests::notifications::many_events` (stored `pin_used` titles, read
  through `inbox_row` -> `told`); Locate's `before_off` unit test has the stored title and the params; the device page's
  "Off since" and the push by `tests::power::a_shutdowns_words_read_in_all_four_places_before_and_after_the_prune`
  (:679) - a restart and a battery-flat shutdown, inbox en/nb with the detail, "Off since"/"Av siden", Locate's line,
  the push, then `retention::prune` and all four again. H2 is closed.
- **Retention** (`retention.rs:376`): `json_remove(params, '$.location')` keyed on `json_type(.., '$.location')`, so a
  second pass touches nothing; `tests::cleanup` asserts `{}` is left with `detail` NULL on a 2026-10-25-shaped row.
- **Pushes** (`push.rs`): `send_due` joins `admin_users.lang` (:531), `Lang::from_code(..).unwrap_or_default()` (NULL or
  unknown -> en), `setup_zone.in_lang(lang)` per delivery, so a language change applies to a delivery still queued at
  the next pass; the summary is per subscription (`fold` groups by `subscription_id`, `first.lang` :690/:707), so an
  nb and an en parent each get their own; `push.summary.*`/`push.test.*` with `{label}` (L2); `payload_json`'s `lang`
  is the subscriber's code (:246) - the only payload change. `a_push_is_worded_per_subscriber` (web_push.rs:1082): one
  event, nb Mamma and NULL Pappa, titles, bodies and `lang` each; the test push in nb.
- **`/sw.js`** (H1): `sw.rs` untouched, the template's fallback `title: "Vibb Phone", body: ""`; `sw.test.js` updated;
  `the_worker_has_the_same_bytes_for_every_reader` (web_push.rs:1162) compares five fetches (`?lang=nb`, an nb header,
  an nb account, `?lang=en`) byte for byte.
- **The security log** (`security.rs:129-362`): `Detail { facts, words: Vec<Sentence> }`, a `Sentence` = `key` + `p`
  (text) + `t` (instants, RFC 3339) + `w` (words of ours by key) + `d` (durations in minutes) + `n` (a count ->
  `t_count`); `columns()` -> (`detail`, `params` as one object or a list); `worded` (:316) parses one, then a list, else
  `None`; `worded_or_stored` (:336) for `banned_ips.reason` and prose. `record_security_event(.., Option<Detail>)`; all
  writers moved, the four that bypass it too: `audit.rs` (`insert`/`update_raw` write `params`; `counted` ->
  `.and("security.detail.times")`; the flood summary), `time_rules::log_conversion(Detail)` with `scoped_detail`,
  `settings_log` as facts (the page and its routes), `banned_ips.reason` a sentence's JSON (`check_and_ban_ip_if_needed`).
  `handlers/auth.rs:102` is the `login_failed` insert with no detail at all - nothing to word; "untouched" is right.
  Labels: `event_label(lang, kind)` over `security.event.<id>` with `label_key` guarding the id's shape and `has()`,
  else `words_of` (admin.rs:60-80); `every_written_event_has_a_label` asserts both catalogs. `csrf_check` returns
  `security.detail.csrf.*`, `gate::Refusal::key()` `security.detail.refused.*`.
- **H3 - the budget**: `record_phone_event` (security.rs:445-483) writes `detail = "device {id}"` from the id alone and
  the line's sentences to `params`; the count is `detail = ?1 OR LIKE ?1 || ':%' OR LIKE ?1 || ',%'` (older rows count).
  The phone writes neither column: `detail` is the server's `fact`, `params` are the server's keys and the report's
  clipped names. `tests::parent_pin::a_flood_of_lockouts_is_bounded`: 100 lockouts -> 60 lines all `detail = "device
  {id}"`, one `phone_lines_dropped` (en and nb), 20 events, 3 pushes; `zone_watch::alternating_mock_counts_…` 60 + 1.
- **Secrets**: every `Detail::words`/`counted`/`.and`/`.word`/`.at`/`facts` call read (66 + the bypasses). Parameters
  are names (phone, zone, app, account display names, catalog titles, packages), routes clipped at 100, peer addresses,
  counts, codes (`spotify.rs:1391`'s `code` is a failed login's error code, not the authorization code), the Spotify
  client ID's last four (as before), a Storytel `generation`. No token, password, OTP, TOTP, session id or cookie; the
  one-time password's row says only "One-time password". `tests::music_spotify::no_secret_in_logs_events_or_pages` now
  scans `detail || params`. Rendering: `{{ detail }}`, `{{ reason }}`, `{{ e.title }}` through Askama's escaping, no
  `|safe` added (`tests::settings_pages` asserts `&#60;b&#62;Natt&#60;/b&#62;` in a worded row); `banned_ips.reason`
  prose can't panic (`worded` returns `None`, the text shows as stored - tested with "20 failed login attempts").
- **Migration 0107**: `ALTER TABLE security_events ADD COLUMN params TEXT`, no reference to 0105's or 0106's tables, so
  it applies after either; `migrations_73_to_107_in_order`; 0104-0106 untouched (the diff's only migration file is 0107);
  `models::SecurityEvent.params` for the `SELECT *`.
- **Catalogs**: 684 keys each, +396; parity, `.one`/`.other`, no markup, every used key (the scanner's new `com`/`org`/
  `net` exception excludes only a literal whose last segment is one of those - no key ends so, so it hides nothing; it
  exists because `push` is now a namespace and the tests name `web.push.apple.com`). `t` warns once per (lang, key)
  (slice 1 nit 4 closed). Dynamic keys all over closed sets with a test: `events.<kind>.<form>.title[.<variant>]`,
  `events.pin_used.entry.<5>`, `events.extra_time_request.rule.<3>`, `security.detail.pin.place.<5>`,
  `security.event.<id>`.
- **The departures**, judged: the language on the Zone - right, a push's body is a time and every page already has a
  Zone; `Detail` as facts + sentences - right, it keeps H3 and the routes greppable; facts kept as ids - D4; the `Phone`
  zone at intake with `Lang::default()` (:208) - fine, it serves day arithmetic only (`TimeAsk::of`, the request's
  day), no word is made at intake; `storytel_login_saved` without a detail - fine (it said nothing useful); the PIN
  lockout's `until` stored only while ahead at the raise (:273) - right, a past end told as "until 14:05" would mislead;
  `extra_time_request.rule.any` for an unknown rule kind - fine; `banned_ips.reason` as a sentence's JSON - fine with
  the lenient read.

## Findings

**Medium 1 - the security log drops a row's facts once it has words.** `handlers/admin.rs:123`:
`detail: words.or_else(|| event.detail…)` - the facts show only when there are no words. Rows with both: `csrf_refused`
(facts `METHOD /path`, `admin_auth.rs:597`), `admin_refused` (`gate.rs:126`, `METHOD /path`), `device_auth_failed`
(`security.rs:1284`, the path), and `screen_time_saved` per phone (`schedules.rs:1344`: facts `device {id}`, then the
words "own rules on" / "budget …", which never name the phone). Before this slice the row read "Origin is another host
(POST /devices/3/lock)", "not a tailnet or loopback client (peer 10.0.0.9, GET /security)", "device 3: own rules on,
budget 60". Failure: after a CSRF refusal the parent (or the next security review) opens Security Log and reads "The
Origin header names another host" with no route; a per-phone Screen Time save reads "own rules on" with no phone. The
test helper `tests::security_words` (mod.rs:754) has the same `.or(detail)`, so no test can see the loss, and
`tests::time_rules:1260` codifies it (`starts_with("own rules ")` where it was `"device {id}: "`). Fix: show both -
`{words} · {facts}` (or the facts in the ⓘ's `<p>` under the words, as the raw id already is), `security_words` the
same, and one assertion that a `csrf_refused` row shows its `POST /…`. The lift/lock-screen/zone rows are unaffected:
their words carry `phone {device}`.

**Low 1 - the `Detail` builder drops things silently.** (a) `record_phone_event` discards a line's facts
(`security.rs:476`, `let (_, params) = line.columns()`): right for H3 - the phone's line can never set `detail` - but a
future `window_relock::log_line(..).with_facts(..)` vanishes without a word; a `debug_assert!(line.facts.is_none())`
or a `Detail::sentences()` accessor in the signature says so. (b) `.at`/`.word`/`.duration` on a `Detail` with no
sentence do nothing (`security.rs:271,279,288`): `Detail::facts("x").at("at", t)` loses the instant. No caller does
either today (all read).

**Low 2 - the nb push body is pinned only by its title.** `the_push_words_are_short` (:2620) has no nb column (§8 asked
for one); `every_kind_is_worded_in_both_languages` checks an nb push only for a key, the maximum and "differs from en",
and `tests::notifications` five nb titles. An nb push body that drifted to the inbox's long form ("14:02 · batteri 3 %,
trolig tomt" instead of "14:02 · batteri 3 %") would pass. Add the nb twin of the table (title and body) - the user
reads the push words in the catalog diff, the test should hold them.

**Low 3 - the older-row rule is untested for the rows it exists for.** The inbox's stored-title path is exercised only
by rows the tests insert *without* `params` and *without* a detail (`summary.rs`, `notifications.rs::many_events`),
where `told_as`/`detail` would fall back the same way without the rule. The rule earns its keep on a 2026-10-25
`phone_off` (title + `{"location"}`; here `phone_event_told` also fails on `reason`, so it is doubly held) and on an
older `foreign_access` with a stored detail (`detail` :1598 is the only guard: without it `serde_json::from_str(params?)`
returns `None` and "Device admin: Helper." is gone). Add one test: insert both shapes, GET `/notifications?lang=nb`,
assert the English title, summary and detail show as stored. Mutation (d) below says what today's tests do.

**Low 4 - lossy corners in the words.** `locate.rs:1490` puts `"{requested_at} UTC"` (a `datetime('now')` text) into
`{asked}` as plain text instead of `.at("asked", t)`: the one new sentence that shows a raw UTC stamp in both languages
("asked 2026-10-31 14:02:03 UTC"), where every other instant is told in the reader's zone. The two fallbacks for an
unconvertible millisecond value (`device_api.rs:1710`, `window_relock.rs:817`) put the raw number in `{until}`/`{at}` -
unreachable for any value the intake accepts, fine as a last resort.

**Low 5 - the nb wording** (all 396 values read; placeholders identical, "du", sentence case): four lines worth a look
before the user reads the diff - below.

**Nit 1** `handlers/admin.rs:79`: `label_key` checks `has(Lang::En, ..)` only; parity makes it equal, but
`has(lang, ..)` reads truer. **Nit 2** English left in the moved modules, all later slices': `parent_events::battery_line`
(:1016, the summary's "Battery 14 %, charging" - slice 4), `push.rs:1180` "The push service refused it" (the
subscription's state on Notifications - 3e), `admin.rs:121` "Time unknown"/"a while" (the Security page - 3e),
`Kind::switch`'s labels (Notify Me About - 3e), `parent_pin::where_`/`lockout_text` (slice 4). Name them in PLAN so
3e/4 don't miss them. **Nit 3** `PLAN.md` is not in the branch: the round's queue entry must say slice 2 is built, in
the merge commit. **Nit 4** `tests::security_words` and `tests::time_rules:1241` each re-implement the page's
`words.or(detail)` - one helper, and M1's fix lands in one place. **Nit 5** `security.detail.enrolled.*` say "(phone
{device})" while `device {id}` stays the fact - fine (D4), just note in pwa.md that "phone N" on the page is "device N"
in the facts and the greps. **Nit 6** `Detail::worded` reads `key` from the row and `t_params` warns on a key the
catalog has lost (a key renamed in a later slice shows as itself on old rows) - a note in pwa.md "Words": a
`security.detail.*` key is frozen once a release wrote it, or the rename maps the old key. **Nit 7** `display_time`'s
doc says "worded in the reader's language" but `from_setting`/`utc_fallback` are English by construction (`Lang::default`);
the doc comment at :45-47 says so - fine; the 24 non-test callers are all `Zone::load`.

## The nb wording

- `events.emergency_call.inbox.at_today` "Kl. {at}" for "At {at}": right register. `events.phone_off.push.title.battery`
  "{name}: tomt batteri, slått av" / `.heat` "{name}: for varm, slått av": good.
- → `events.unseen_boot.inbox.summary` "Sikker modus eller en avbrutt start": fine; "Sikkermodus" is Android's own
  spelling in Norwegian.
- → `security.detail.pin.lockout_end` "Lagrer du en ny foreldre-PIN, oppheves det når telefonen melder seg": good
  Norwegian; "melder seg" reads as "reports in" - "neste gang telefonen melder seg" says the timing.
- → `security.detail.refused.*` "(motpart {peer})": "motpart" is the legal word; "fra {peer}" is what a parent reads.
- → `time.month.6`/`7` "juni"/"juli" unabbreviated beside "jan."/"feb.": that is the Språkrådet form (no dot on the
  short names) - right, keep.
- "I går 19:14" (capitalised at a summary's start, lowercase inside a sentence): consistent with `capitalized()`.

## Mutations

Each alone, on a pure function, run with the named filter only (the full suite once, below); the sources restored
with `git checkout -- server/src` after each.

| # | Mutation | Filter | Result |
|---|---|---|---|
| a | `Zone::when` takes the day from UTC, not the zone (the yesterday boundary) | `display_time::` | PENDING |
| b | `fit_name`'s room ignores the words' own length | `parent_events::` | PENDING |
| c | the budget's count drops `detail = ?1` (a worded line no longer counts) | `tests::parent_pin::` | PENDING |
| d | `detail()` without the older-row rule (every row read from its params) | `notifications` | PENDING |

## Runs

`cargo fmt --check` clean; `node --test jstest/` 193 passed, 0 failed. **`cargo test --locked` could not complete on
this machine during the review**: the test crate's `rustc` was OOM-killed three times (kernel log 22:30 and 22:35,
anon RSS 4.4 GB each; also with `CARGO_PROFILE_DEV_DEBUG=0` and `CODEGEN_UNITS=1`) while the emulator held 3.9 GB of
the 11.9 GB and ~4.4 GB was available; the branch is not on origin, so there is no CI run to read, and the reviewer
pushes nothing. The implementer reports 1462 passed. The four mutations and `cargo clippy --all-targets --locked` are
therefore **not run** (the table says PENDING); the verdict rests on the full read of the diff and the tests' text.
Run them once the emulator is down: `scripts` for both are in the review session's scratchpad (`run-tests-lean.sh`,
`mutate.sh a|b|c|d`), or re-run from this note's table - each mutation is a one-line change named above.
