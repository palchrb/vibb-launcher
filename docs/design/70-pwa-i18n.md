# 70 - The PWA in Norwegian and English (round S-i18n)

Status: design note, architect 2026-10-27, on `main` at bc7408b3; revised 2026-10-27 after `docs/review/qa-70-design.md`
(H1-H3, M1-M6, L1, L6 taken; the rest in "Carried into the briefs"). Not built. Server only (no launcher or contract
change). PLAN: "Round S-i18n". The user, 2026-10-26: "Husk at jeg ønsker i18n på ting her ... Ønsker det på hele
oppsettet vårt". Standing rule: no new user-facing string is hardcoded.

## 1. Where we start

- `server/src/i18n.rs` (design 69): `Lang { En, Nb }` as an axum extractor from `Accept-Language` (`nb`/`nn`/`no` →
  Nb), `t(lang, key)`, `t_params(lang, key, &[(name, value)])`, catalogs `src/i18n/en.json` + `nb.json` (108 keys:
  `calendar.*`, `common.*`, 3 `apps.*`) compiled in; tests: parity of keys and `{params}`, every used key exists.
- The model page: Device › Calendar (`handlers/calendar.rs`, `CalendarTemplate { lang, .. }` + `fn t(&self, key)`,
  `templates/device_calendar*.html` with `<html lang="{{ lang.code() }}">` and `{{ self.t("…") }}`).
- Left to move: 65 templates (49 pages + 19 partials, Calendar's 3 done; ~1100 text fragments), the handlers' words
  (~1000 literals, most in `admin.rs`, `device_pages.rs`, `locate.rs`, `devices.rs`), 95 plain-text error bodies, the
  event words (`parent_events.rs`: 8 kinds stored as prose, 5 told from params), push words
  (`push.rs`), the security log (`label_of`: 116 labels; 66 `record_security_event` writers and four that bypass it),
  the times (`display_time.rs`), three JSON routes with words, and six scripts with their own words (`locate.js`,
  `notifications.js`, `reconnect.js`, `nav-timing.js`, `reveal.js`, `app-display.js`). There is no email.

## 2. The catalog (slice 1)

- **One flat JSON per language**, `src/i18n/<code>.json`, key → plain text. Keys dotted, lower snake case, area then
  page then thing: `common.*`, `nav.*` (tab bar), `error.*`, `devices.*`, `device.<page>.*`, `apps.*`, `locate.*`,
  `music.*`, `settings.*`, `account.*`, `auth.*`, `events.<kind>.*`, `push.*`, `security.event.<id>`,
  `security.detail.*`, `time.*`, `calendar.*` (as is). A key names the thing, not its English words.
- **Params** `{name}` (ASCII snake). `t_params` fills each once and never rescans a value. **No markup in a catalog**:
  a value with `<` or `>` fails a test; a sentence with a link is split at the element (`….before`, `….link`,
  `….after`). Askama escapes every `{{ }}`; no template puts `|safe` on a word (§5.2). User data (phone, contact, app,
  feed names) only ever goes in as a param.
- **Plurals: two keys**, `<key>.one` and `<key>.other`, both with `{count}`, through a new
  `t_count(lang, key, n, params) -> String` (`.one` when `n == 1`). CLDR gives nb and en the same integer rule; a third
  language adds `.few`/`.many` and its rule in `Lang::plural_form`. Zero is its own key the caller picks.
- **A missing key** is refused by the tests (`both_catalogs_have_every_key_with_the_same_parameters` already fails on a
  key in one file only). At runtime `t` returns **the key itself** (visible: "devices.summary.off_since") with a `warn!`;
  today's English fallback and "?" go. `pub fn t<'a>(lang: Lang, key: &'a str) -> &'a str`.
- **Dynamic keys** (`format!("security.event.{id}")`) only over a closed set with `ALL` (`parent_events::Kind::ALL`,
  `security::PhoneLine::ALL`, the label test's ids), each with a test that every member's key exists in both.
  `every_key_used_exists` takes its namespaces from the catalog's first segments, not hard-coded markers.
- **One trait**: `pub trait Words { fn lang(&self) -> Lang; fn t(..); fn tp(..); fn tc(..) }` in `i18n.rs`, the three
  as default methods. **Slice 1 gives every template struct (~65) `lang: Lang` + `impl Words`** and the handlers their
  `lang: Lang` extractor - mechanical, no words moved - because `head`, `tab_bar` and `bell` render inside every page
  with that page's `self`; each slice-3 PR then moves words only. `CalendarTemplate`'s own `fn t` goes over to it.
- **Error bodies**: `i18n::error(lang, status, key) -> Response` in slice 1 (a `t`-worded plain-text body), for the
  CSRF refusal ("Refused: this form was sent from another site.", `admin_auth.rs:597-600`) at once; the 95
  `(StatusCode::…, "English")` bodies move with their tab's handlers (slice 3) or in slice 4, and slice 4 adds a scan
  that refuses a `StatusCode::X, "<letters>"` literal in `src/` outside tests.
- **Wording**: nb by the implementer, read by the user in each PR's catalog diff; plain, calm, "du"; the launcher's
  `values-nb` terms where they overlap (Skjermtid, Leggetid, Foreldre-PIN, Finn, Soner). "Vibb Phone"/"Vibb One" are names.

## 3. The parent's language (slice 1)

- **Migration `0104_admin_lang.sql`** (0103 is the last on `main`): `ALTER TABLE admin_users ADD COLUMN lang TEXT;` -
  NULL until known, no CHECK (a third language needs no table rebuild). `AdminUser.lang: Option<String>` (`models.rs`;
  `security.rs` loads `SELECT *`), read through `Lang::from_code(&str) -> Option<Lang>` (an unknown value is `None`).
- **The extractor**, first match wins: (1) `?lang=en|nb` - this request only, never stored, any other value ignored
  (QA, screenshots, the sign-in page); (2) the account's `lang`, from the `CurrentAdmin` that
  `require_session`/`require_full_auth` already put in the extensions - no database read; (3) `Accept-Language`;
  (4) En.
- **Before a choice** (D2): `require_full_auth`, when `admin.lang` is `None`, runs one
  `UPDATE admin_users SET lang = ? WHERE id = ? AND lang IS NULL` with the request's `Accept-Language` language (never
  `?lang=`). Only the first signed-in request writes; today's sessions and existing push subscriptions are covered at
  the next page load. An account still NULL pushes in English.
- **The row**: Settings › Account, group "Language" / "Språk" (`id="language"`), one select with "Norsk" and "English"
  - each in its own language in both catalogs - the account's language selected. Saves with `static/autosave.js` (a
  `<noscript>` Save, as Calendar's select); `POST /account/language` (`lang=en|nb`, else 400), behind full auth and the
  global `csrf_guard`, 303 to `/account#language`: the page comes back in the new language at the same place (pwa.md:
  a save never jumps to the top). `settings_log` logs it as a save.
- **One language per account**, not per device; a second parent chooses their own. Every moved page sets
  `<html lang="{{ lang.code() }}">`. Slice 1 moves `account.html` and its handler words with the row.

## 4. Events, pushes, the security log and the times (slice 2)

- **The times move here** (D1): a push's body is a time. `display_time::Zone` gets a `lang: Lang` field
  (`zone.in_lang(lang)`), every method words through `time.*`. nb: "nå nettopp", "for 3 min siden", "for 2 t siden",
  "i går 21:40", "i dag 16:00", "27. okt.", "27. okt. 2025", "i morgen 14:05", "man. 14:30"; en exactly as today. Keys:
  `time.just_now`, `time.min_ago`, `time.h_ago`, `time.today`, `time.yesterday`, `time.tomorrow`,
  `time.weekday_clock`, `time.date`, `time.date_year`, `time.month.1`..`12` (nb "jan.", "feb.", "mars", "apr.", "mai",
  "juni", "juli", "aug.", "sep.", "okt.", "nov.", "des."), `time.weekday.1`..`7` - from the catalog, not chrono's
  `unstable-locales`. **24 h "14:30" in both** (D3). The fallback zone's " UTC" and `exact()` stay as they are.
- **Events as key + params**, design 64's shape: the 8 stored-words kinds (`emergency_call`, `pin_used`,
  `pin_lockout`, `foreign_access`, `unseen_boot`, `low_battery`, `phone_off`, `phone_on`) raise with
  `parent_events.params` (a serde struct per kind like `ZoneParams`: the phone's name, instants as RFC 3339 UTC,
  numbers, the variant as a short code) and store `title`, `summary`, `push_title`, `push_body` and `detail` empty.
  No migration (0091, 0098).
- **`phone_off` keeps its params and its readers** (H2). `PhoneOffParams` keeps `location` and adds `reason`
  (`user|battery|heat|restart`), `battery` (the level, optional) and `late` (" · told at the next start",
  `parent_events.rs:466-468`). The readers that parse today's English - `off_said` (`title.ends_with(" is
  restarting")`, `:741-743`), `off_since_line` (the battery from `summary`, `:761-765`), Locate's `before_off`
  (`locate.rs:1141-1157`) and the device page's "Off since" (`devices.rs:1527-1542`) - read the params, and keep
  today's text parse for older rows. Retention removes only the location
  (`UPDATE … SET params = json_remove(params, '$.location')`, `retention.rs:367-380`, the detail no longer stored), so
  the event keeps its words its 90 days. Test: a restart and a battery-flat `phone_off` raised after slice 2 read right
  in all four places, before and after the location is pruned.
- **`detail` is worded too** ("Device admin: Helper.", "Last position from 21:10: shown on Locate",
  `:356-374,474-482`): `events.<kind>.detail.*` keys from the params at read time.
- **Worded at read time** in the reader's language: `told(lang, ..)`, `pushed(lang, ..)`, `told_as(form, lang, ..)`
  read `events.<kind>[.<variant>].inbox.title|inbox.summary|push.title|push.body|detail.*`. The push rule stays
  "<name>: <fact>" plus one short clause (notifications.md, "Push wording"); `fit_name` cuts the name so the worded
  title fits `TITLE_MAX` (60) in that language; `SUMMARY_MAX` (100) and `activity::without_own_clock` as before.
- **An older row is one whose `title` is not empty** (not "no params": every `phone_off` since 2026-10-25 has both):
  it shows its stored English until retention ends it. No backfill.
- **Pushes in the subscriber's language, at send time**: `push::send_due` joins each delivery's subscription to
  `admin_users.lang`; `SUMMARY_TITLE`/`SUMMARY_BODY`/`TEST_TITLE` become `push.summary.*`, `push.test.*`.
- **`/sw.js` gets no words** (H1): it is a public route (`main.rs:782`, outside both auth layers, so no account) and
  `ServiceWorkerTemplate` is `escape = "none"` (`handlers/sw.rs:16`). The fallback for a push without a body becomes
  title "Vibb Phone" (a name) and an empty body - the server never sends a push without one. The worker's bytes stay
  the same for every reader (a body, if ever wanted: both languages via `serde_json::to_string`, picked by
  `self.navigator.language`).
- **The security log**: `label_of` becomes `security.event.<id>`, `event_label(lang, kind)`; an id this server never
  wrote still reads as `words_of(id)`. **Migration `0105_security_params.sql`**: `ALTER TABLE security_events ADD
  COLUMN params TEXT;` - `{"key": "security.detail.<name>", "p": {..}}`. `record_security_event(db, kind, user, ip,
  detail)` takes `Option<Detail>`: `Detail::words(key, params)` goes to `params`, `Detail::facts(text)` to `detail`.
  Facts are identifiers only: `settings_saved`'s routes, a tag, an address, a phone line's `device {id}` (below).
  Every sentence ("wrong current password") becomes words. `EventRow` (`handlers/admin.rs`) words `params` for the
  reader; an older row's prose shows as stored.
- **The phone lines' hourly budget** (security review L1) counts by `detail LIKE 'device N:%'` (`security.rs:215-221`).
  So `record_phone_event` keeps `detail = "device {id}"` as a fact and puts its words in `params`; the `LIKE` changes
  to `detail = ?1 OR detail LIKE ?1 || ':%' OR detail LIKE ?1 || ',%'` (older rows count too). Test: 61 worded lines in
  an hour still write one `phone_lines_dropped`.
- **The writers that bypass `record_security_event`**, worded in slice 2 too: `audit.rs::insert` and `counted()`, which
  appends "(N times in a minute)" to `detail` (`audit.rs:224-279`; the count becomes a param, `{times}`);
  `time_rules::log_conversion` (`time_rules.rs:597-610`); the raw insert at `handlers/auth.rs:102`; and
  `banned_ips.reason` (`security.rs:290`, shown at `security.html:19`), stored as a key from then on (older rows'
  prose shown as stored).

## 5. The templates, tab by tab (slice 3)

### 5.1 The order and what moves together

One PR per tab, each mergeable alone, words only (every struct has `Words` since slice 1). **3a Devices** (L:
`devices_list`, `device_detail` and every `device_*.html` but Locate's, `device_add`, `enroll_code`, `provision_qr`;
the shared partials `tab_bar`, `bell`, `head`, `flash_note`, `group_flash`, `info_row`, `note_row`, `note_warning`,
`recent`, `exceptions`, `budget_rows`, `device_app_row`, `app_permissions_form`; `app_permissions::words` (read by
`devices.rs:1886-1887` and `device_pages.rs:1941`); `reconnect.js`, `nav-timing.js`, `reveal.js`), **3b Apps** (M:
`apps_list`, `tracked_app_*`, `app_display_form`, `app-display.js`, `install-now.js` and `install-now.json`), **3c
Locate** (M: `device_locate`, `device_locations`, `device_zone`, `locate.js` and `locate-result.json`), **3d Music** (M:
`music*.html`, `device_music`, `music_card`, `music_status*`, `spotify_setup`), **3e Settings** (L: `settings_hub`,
`notifications*` with `subscriptions.json`, `schedules`, `schedule_*`, `dns_*`, `backups`, `updates`, `wallpapers`,
`provisioning_settings`, `security`, `change_password`, `login`, `setup_2fa`, `verify_2fa`, `notifications.js`). A page
moves with its handler's words (title, notices, row values, error bodies) as `t(lang, …)`; `<title>` is the handler's
`title: t(lang, "<page>.title")` into `head.html`'s `{{ title }} · Vibb Phone`.

**JSON routes with words** (M2): `subscriptions.json` (`notifications.rs:420-438`: `sub`, `badge`, written by
`notifications.js:52-54`), `install-now.json` (`install_now.rs:367`, `install-now.js:66`) and `locate-result.json`
(`locate.rs:824`, `text`) take `lang: Lang` and word through the catalog in their tab's PR (`textContent` as today).

### 5.2 The scan test

`i18n::tests::moved_templates_have_no_literal_words`, with `const MOVED: &[&str]` in `i18n.rs` that each PR extends
(3e: every file in `templates/` and `templates/partials/`). Per file it removes `{# #}`, `{% %}`, `{{ }}`, `<!-- -->`,
`<script>…</script>`, `<style>…</style>` and every tag, keeping the values of `title`, `aria-label`, `placeholder`,
`alt`, every `data-w-*`, and `value` on `<button>` and `<input type="submit|button">`. What is left fails if it has a
letter (`char::is_alphabetic`), after dropping HTML entities and "Vibb Phone"/"Vibb One". It also refuses `|safe` on a
line that calls `t(`, `tp(` or `tc(`.

### 5.3 Static JS: `data-w-*` attributes

A script's words come from the page: `data-w-<name>="{{ self.t("…") }}"` (always quoted) on the element the script
owns, as `locate.js` reads `data-text` and `app-display.js` `data-app-label`. The every-page scripts (`reconnect.js`,
`nav-timing.js`) read one `<div id="js-words" hidden data-w-…>` from `partials/js_words.html`, included by `tab_bar.html`
and by the sign-in pages. One helper per script: `function w(el, name) { return (el && el.getAttribute("data-w-" +
name)) || name; }` - a missing word shows its name. Plurals pass both forms (`data-w-count-one`/`-other`), `{count}`
filled with `.split("{count}").join(String(n))`. Words go in with `textContent` only - never `innerHTML`, never
concatenated into HTML. Attributes need no parser and are escaped by Askama's default escaping.

`jstest/words.test.js` (CI's `node --test`) scans each moved script: outside comments, a literal that starts upper- then
lower-case, or has a letter and a space, fails unless on that file's non-word list.

## 6. The words Rust builds (slice 4)

What slice 3 did not take with a page: the phone's summary rows and Attention (`device_pages.rs`, `attention.rs`), the
badge's `lock_name` (`time_state.rs:110-127`), the findings' PWA words (`kid_lock.rs`, `kiosk_escapes.rs`,
`window_relock.rs`, `parent_pin.rs`), the music and Spotify status words (`music.rs`, `spotify.rs`), the error bodies
left, and the helpers:

- **Durations**: `lifts::minutes_text`, `music::duration_text` → `display_time::duration(lang, minutes)` ("1 h 30
  min"; nb "1 t 30 min"); `music::ago` → `Zone::relative`. **Plurals**: `settings_hub::plural`, `music::plural` →
  `t_count`.
- **Numbers**: `i18n::number(lang, value, decimals)` for the PWA's few decimals ("1.5 GB", nb "1,5 GB").
  `display_time.rs` is the one module for times and durations, `i18n.rs` for words, plurals and numbers.

Slice 4 introduces `tests::i18n_pages::nb_pages_have_no_english` (§8) over every page and JSON route, and the scan of
`StatusCode` literals (§2); the `// i18n:` markers are gone.

## 7. The seam with the phone (slice 5)

The policy is already keys (`rules[].kind`, `pending_command.command`, `kid_lock.keyguard`, `calendar.*`, `camera`, the
time-request `reason`, the Storytel `status`); the launcher words them. The one English the server writes for the kid
is a **time rule's `name` the server made itself** (`legacy_to_rules`: "Bedtime", "Outside allowed hours", "…
(morning)"/"(evening)"; `morning_name`: "<name> (morning)"), shown on the lock screen and glance via `ruleLabel`.

- **Precedent**: a blank name is already handled on both sides. `ruleLabel` names a blank name by its kind
  (`TimeLockModel.kt:37-38`: "Skole" / "Leggetid" / "Skjermfri tid"), and the bedtime rule has sent `name: ""` since
  design 66 (`time_rules.rs:112-116`). So slice 5 is **server-only, no launcher change, no contract change**
  (`PolicyResponseCompatTest` pins only keys).
- **The rule**: `TimeRuleRow::to_policy` sends `name: ""` for a name in `time_rules::SERVER_NAMES` (the four exact
  strings) and drops a trailing " (morning)" / " (evening)" from any other name, so both halves of a split rule show
  the parent's name. Existing rows are covered without a migration.
- **The PWA names an active rule from `active_rule_id`** (M1): the phone reports back the name it was sent
  (`TimeRulesRuntime.kt:206`), so a blank name parses to `None` (`time_state.rs:468`) and the badge and Diagnostics
  would fall back to "A time rule" (`time_state.rs:110-127`, `device_pages.rs:655-666`). Both look the rule up by
  `active_rule_id` and word it as the schedules page does (below); the reported name is only the fallback.
- **In the PWA**, a `SERVER_NAMES` name shows through `device.time.rule.default.<n>` and the suffix through
  `device.time.rule.morning` ("{name} (morning)" / "{name} (morgen)") until the parent renames it.
- Tests: `time_rules::tests::server_names_go_to_the_phone_blank` (the four names, "Natt (morning)" → "Natt", "Morning
  run" untouched); `tests::time_pages::the_badge_names_a_blank_reported_rule_by_its_id`.
- Seeded "Musikk" (Home tile, music categories, 0049) is the parent's data (D5). A later field change follows the API
  rule: one commit, `server/testdata/*.json` byte-identical in `launcher/app/src/test/resources/`.

## 8. Testing

- **Catalogs** (`i18n::tests`): parity; `.one`/`.other` pairs with `{count}`; no `<`/`>`; no empty value; every used key.
- **Closed sets**: `parent_events::tests::every_kind_is_worded_in_both_languages` (`Kind::ALL` × variant × form, en and
  nb, within `TITLE_MAX`/`SUMMARY_MAX` with a 20-character name); `the_push_words_are_short` gets an nb column;
  `every_written_event_has_a_label` asserts `security.event.<id>` in both; the phone-line budget test (§4).
- **Times**: every `display_time::tests` case gets its nb twin.
- **The language**: `tests::parents::language_is_each_parents` (two accounts, en and nb: `<html lang>`, the Account row,
  a push worded per subscriber, NULL → the first signed-in request stores `Accept-Language`, `?lang=` wins once and is
  never stored, a bad `lang=` POST is 400); `tests::music_spotify::migrations_73_to_103_in_order` takes 0104 and 0105.
- **Scans**: `moved_templates_have_no_literal_words` (§5.2), `words.test.js` (§5.3), the `StatusCode` scan (§2).
- **No English on an nb page** (from slice 4, when it can pass - 3a's routes show slice-4 words until then):
  `tests::i18n_pages::nb_pages_have_no_english` signs in an nb account on a **new seeded family**
  (`tests::seeded_family`, built on `enrolled_device` and `post_status`: a status with `time_state`, battery and zones
  and an active rule; one event of each `Kind`; a security line of each writer; a tracked app with a release; a music
  entry), GETs every page and the three JSON routes, strips tags and scripts, and fails on a whole word that appears in
  some en value and in no nb value (a short allowlist for the fixtures' names). Slice-3 PRs carry only the scans.
- **Browser pass** per slice-3 PR and after slice 4: 390 px, light/dark, JS off, nb and en (Account row or `?lang=`);
  the longest nb words (the tab bar's "Innstillinger", rows beside a switch, badges) are shortened in the catalog.

## 9. Not in this round

The launcher and the music app (both have `values-nb`); a third language (nothing blocks one: a `Lang` variant, a
catalog, its plural rule); user data (app, feed, contact, phone, zone and rule names); `static/manifest.webmanifest` (a
brand name; its description is shown nowhere); the Dagen pages (the model); per-device or per-subscription languages.

## 10. Steps

Five slices off `main`, in order, each mergeable alone with its tests, marked done in PLAN in its merge commit.
1. **`pwa-i18n-1` - the layer and the language (M)**: `t` without the English fallback, `t_count`, `Words` on every
   template struct, `i18n::error`, the extractor's order, migration 0104, the first-request store, the Account row and
   `POST /account/language`, `account.html` moved. The commit that ships the row is this slice's.
2. **`pwa-i18n-2` - events, pushes, log, times (M)**: §4; migration 0105; `display_time` in both languages.
3. **`pwa-i18n-3a`..`3e` - the tabs (L in five PRs)**: §5; each adds its files to `MOVED`, its scripts to
   `words.test.js`, rewrites the jstests that assert its English, and does its browser pass.
4. **`pwa-i18n-4` - the words Rust builds (M)**: §6; the seeded family and the nb-page test over every route.
5. **`pwa-i18n-5` - the seam (S)**: §7. Docs: `pwa.md` "Words" + `server/CLAUDE.md` in 1 and 3e, `notifications.md` in 2.

## Carried into the briefs

- L2 (slice 2): `payload_json`'s `"lang": "en"` (`push.rs:243`) → the subscriber's `lang.code()`; the test push's body
  `format!("Notifications work on {label}")` (`push.rs:592`) → `push.test.body` with `{label}`.
- L3 (slice 2): times formatted outside `display_time` - `time_state.rs:247` (`%-d %b`), `parent_pin.rs:374,473` (`%b`).
- L4 (slice 2): `Zone::load(db, lang)`, so no page forgets the language; the sender loads once, `in_lang` per delivery.
- L5 (slice 1): `account.html` also shows the notices of `parents::add/reset/remove`, `auth::update_account_password`,
  `reset_totp` and `sign_out_everywhere` - all move with it.
- L7 (slice 1): `provisioning_settings.locale` (0034, `nb_NO`) is the phones' locale, not a parent's; it is not the
  NULL fallback (say so in pwa.md).
- L8 (slice 3): the jstests assert English (`locate.test.js`, `notifications.test.js`, `reveal.test.js`, …) and
  `sw.test.js` reads the template; each PR's size counts its tests.
- N1: §1's counts (65 templates, 116 labels, six scripts with words; `pull-refresh`, `auto-reload`, `zones`,
  `music-cards`, `rule-kind`, `focus-mode` have none).
- N2: `AdminUser` is in `models.rs`. N3: a plain `warn!` per missing key. N5: dropping " (morning)" also drops a typed one.
- N4: the page-timing tokens (`dns`, `ttfb`, `nav-timing.js:100-104`) are diagnostics, on `words.test.js`'s non-words.
- Every brief: every `data-w-*` value quoted; no `server-v*` tag between 3a and 3e unless the owner accepts mixed pages;
  the nb catalog diff goes to the user to read ("Push wording" asks the same); the 390 px pass in nb and en.

## § Decisions for the owner

The owner has not answered yet; these stand as the defaults the briefs build on until the owner says otherwise.

1. **The times move with the events (slice 2), not slice 4.** A push's body is a time, so an nb push needs nb times.
2. **Before a parent chooses, the first signed-in request stores the browser's language** (`require_full_auth`, while
   `lang` is NULL). Covers today's sessions and subscriptions. Else: English pushes until the row is used.
3. **English keeps today's formats: 24 h "14:30", "27 Oct 2026".** Else: US style ("2:30 PM", "Oct 27, 2026").
4. **Security-log details become words for new rows; routes, ids and a phone line's `device {id}` stay facts.** Else:
   only the labels move (slice 2 S instead of M).
5. **Seeded "Musikk" (Home tile) and the music categories stay the parent's data.** Else: an English install seeds
   English names (a later round; the seed runs before any account exists).
