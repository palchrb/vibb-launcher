# 70 - The PWA in Norwegian and English (round S-i18n)

Status: design note, architect 2026-10-27, on `main` at bc7408b3. Not built. Server only, plus one policy rule in slice 5
(no launcher change). PLAN: "Round S-i18n - the PWA in Norwegian and English". The user, 2026-10-26: "Husk at jeg ønsker
i18n på ting her ... Ønsker det på hele oppsettet vårt". Standing rule: no new user-facing string is hardcoded.

## 1. Where we start

- `server/src/i18n.rs` (design 69): `Lang { En, Nb }` as an axum extractor from `Accept-Language` (`nb`/`nn`/`no` →
  Nb), `t(lang, key)`, `t_params(lang, key, &[(name, value)])`, catalogs `src/i18n/en.json` + `nb.json` (108 keys,
  `calendar.*`, `common.*`) compiled in; tests: parity of keys and `{params}`, every used key exists, the header.
- The model page: Device › Calendar (`handlers/calendar.rs`, `CalendarTemplate { lang, .. }` + `fn t(&self, key)`,
  `templates/device_calendar*.html` with `<html lang="{{ lang.code() }}">` and `{{ self.t("…") }}`).
- Left to move: 49 templates (~6400 lines, ~1100 text fragments), the handlers' own words (~1000 English literals; most
  in `handlers/admin.rs`, `device_pages.rs`, `locate.rs`, `devices.rs`, `tracked_apps.rs`, `kid_lock.rs`), the event
  words (`parent_events.rs`: 8 kinds stored as prose, 5 told from params), push words (`push.rs` `SUMMARY_TITLE`,
  `TEST_TITLE`, `templates/sw.js`'s fallback), the security log (`handlers/admin.rs` `label_of`: ~90 labels; 66
  `record_security_event` writers with an English `detail`), the times (`display_time.rs`), and 11 static scripts with
  words (`reconnect.js`, `locate.js`, `notifications.js`, `reveal.js`, `app-display.js`, `nav-timing.js`, ...).
- `app_permissions::words` (marked `// i18n: moves to i18n.rs`) moves with the Apps tab (§5). There is no email.

## 2. The catalog (slice 1)

- **One flat JSON per language**, `src/i18n/<code>.json`, key → plain text. Keys are dotted, lower snake case, by
  area then page then thing: `common.*` (Save, Cancel, On, Off), `nav.*` (the tab bar), `devices.*`, `device.<page>.*`
  (`device.lock.*`, `device.time.*`), `apps.*`, `locate.*`, `music.*`, `settings.*`, `account.*`, `auth.*` (sign-in,
  2FA), `events.<kind>.*`, `push.*`, `security.event.<kind>`, `security.detail.*`, `time.*`, `calendar.*` (as is). A
  key names the thing, not its English words (`devices.summary.off_since`, not `devices.off_since_yesterday`).
- **Params** `{name}` (ASCII snake). `t_params` fills each once and never rescans a value, so a phone named `{count}`
  stays literal. **No markup in a catalog**: a value with `<` or `>` fails a test; a sentence with a link or bold is
  split at the element (`….before`, `….link`, `….after`). Askama escapes every `{{ }}` (plain-text `&` is fine), and no
  template puts `|safe` on a word (the scan, §5.2). Values from user data (phone and contact names, app labels, feed
  titles) only ever go in as params.
- **Plurals: two keys**, `<key>.one` and `<key>.other`, both with `{count}`, read through a new
  `t_count(lang, key, n, params) -> String` (`.one` when `n == 1`, else `.other`; `{count}` filled). CLDR gives nb and
  en the same integer rule; a third language with more forms adds `.few`/`.many` and its rule in `Lang::plural_form` -
  no key changes. Zero is not a plural form: a page that says "None yet" picks its own key (`locate.history.none`).
- **A missing key**: refused by the tests, never papered over. `both_catalogs_have_every_key_with_the_same_parameters`
  already fails on a key in one file only; at runtime `t` returns **the key itself** (visible in the page:
  "devices.summary.off_since"), logged once per key at `warn`. The English fallback in today's `t` goes, and so does
  "?". Signature: `pub fn t<'a>(lang: Lang, key: &'a str) -> &'a str` (a catalog text is `'static`, so it coerces).
- **Dynamic keys** (`format!("security.event.{kind}")`) only over a closed set with `ALL` (`parent_events::Kind::ALL`,
  `security::PhoneLine::ALL`, the label test's ids); each such set has a test that every member's key exists. The
  used-key scan (`every_key_used_exists`) takes its namespaces from the catalog's first segments instead of the
  hard-coded `"calendar."`/`"common."` markers.
- **One trait for every template struct**, in `i18n.rs`: `pub trait Words { fn lang(&self) -> Lang; fn t(..);
  fn tp(..); fn tc(..) }` with the three as default methods; each struct adds `lang: Lang` and
  `impl Words for X { fn lang(&self) -> Lang { self.lang } }`, and the module `use crate::i18n::Words;` (Askama's
  generated code calls `self.t("…")`). `CalendarTemplate`'s own `fn t` goes over to it.
- **Wording**: nb is written by the implementer and read by the user in each PR's catalog diff. Plain, calm, "du"; the
  launcher's `values-nb` terms where they overlap (Skjermtid, Leggetid, Skjermfri tid, Foreldre-PIN, Barnets PIN, Finn,
  Soner, Dagen). "Vibb Phone" and "Vibb One" are names and stay as they are in both.

## 3. The parent's language (slice 1)

- **Migration `0104_admin_lang.sql`** (0103 is the last on `main`):
  `ALTER TABLE admin_users ADD COLUMN lang TEXT CHECK (lang IN ('en', 'nb'));` - NULL until known. `AdminUser.lang:
  Option<String>` (`security.rs` loads `SELECT *`), `Lang::from_code(&str) -> Option<Lang>`.
- **The extractor** (`impl FromRequestParts for Lang`), first match wins: (1) `?lang=en|nb` in the query - this request
  only, never stored, any other value ignored (QA and screenshots, the sign-in page); (2) the signed-in account's
  `lang`, from the `CurrentAdmin` that `require_session`/`require_full_auth` already put in the request's extensions -
  no database read in the extractor; (3) `Accept-Language` as today; (4) En.
- **Before a choice** (D2): a completed sign-in (`handlers::auth` after the TOTP step) and a new push subscription run
  `UPDATE admin_users SET lang = ? WHERE id = ? AND lang IS NULL` with that request's `Accept-Language` language, so
  an account has a language for its pushes without visiting a row. An account still NULL pushes in English.
- **The row**: Settings › Account gets a group "Language" / "Språk" (`id="language"`) with one select, options
  "Norsk" and "English" - each in its own language in both catalogs (`account.language.nb` = "Norsk" everywhere), the
  account's language selected (the request's language while NULL). It saves with `static/autosave.js` (a `<noscript>`
  Save, as Calendar's select); `POST /account/language` (`lang=en|nb`, anything else 400), CSRF as every form, 303 to
  `/account#language` - the page comes back in the new language at the same place (pwa.md: a save never jumps to the
  top). `settings_log` logs it as a save; no event of its own.
- **One language per account**, not per device: a parent with an iPhone and a laptop gets every push in the account's
  language; a second parent chooses their own. Every moved page sets `<html lang="{{ lang.code() }}">`.
- Slice 1 also moves `templates/account.html` and its handler words (`handlers/auth.rs::account_page`,
  `handlers/parents.rs::set_name`), so the row lands on a page that is already in both languages.

## 4. Events, pushes, the security log and the times (slice 2)

- **The times move here** (D1), because a push's body is a time: `display_time::Zone` gets a `lang: Lang` field (`En`
  from `from_setting`/`load`, `zone.in_lang(lang)` where a page or a push knows its reader), and every method words
  through `time.*` keys. nb: "nå nettopp", "for 3 min siden", "for 2 t siden", "i går 21:40", "i dag 16:00", "27. okt.",
  "27. okt. 2025", "i morgen 14:05", "man. 14:30"; en exactly as today. Keys: `time.just_now`, `time.min_ago`,
  `time.h_ago`, `time.today`, `time.yesterday`, `time.tomorrow`, `time.weekday_clock`, `time.date`, `time.date_year`,
  `time.month.1`..`time.month.12` (nb "jan.", "feb.", "mars", "apr.", "mai", "juni", "juli", "aug.", "sep.", "okt.",
  "nov.", "des."), `time.weekday.1`..`7` (nb "man."...). Month and day names come from the catalog, not chrono's
  `unstable-locales` (one dependency less, words we control). **Clock: 24 h "14:30" in both** (D3). The " UTC" suffix
  of the fallback zone and `exact()`'s "2026-10-13 10:59:13 CEST" stay as they are in both. `capitalized()` stays.
- **Events as key + params**, design 64's shape: the 8 stored-words kinds (`emergency_call`, `pin_used`,
  `pin_lockout`, `foreign_access`, `unseen_boot`, `low_battery`, `phone_off`, `phone_on`) raise with
  `parent_events.params` (a serde struct per kind like `ZoneParams`: the phone's name, instants as RFC 3339 UTC,
  numbers, the variant as a short code - `phone_off.reason` `user|battery|heat|restart`, `phone_on.after`
  `restart|shutdown|unseen`, `pin_used.place`) and store `title`, `summary`, `push_title`, `push_body` empty. No
  migration: the columns exist (0091, 0098).
- **Worded at read time** in the reader's language: `told(lang, ..)`, `pushed(lang, ..)` and `told_as(form, lang, ..)`
  take a `Lang`; every params kind reads `events.<kind>[.<variant>].inbox.title|inbox.summary|push.title|push.body`.
  The rules stay: the push is "<name>: <fact>" plus one short clause (notifications.md, "Push wording"); `fit_name`
  cuts the phone's name so the worded title fits `TITLE_MAX` (60) in that language; `SUMMARY_MAX` (100) as before; a
  summary that is only its clock is still dropped in Activity (`activity::without_own_clock`, both languages).
- **Older rows stay prose**: a row with words and no params (every stored-words row raised before slice 2) shows its
  stored English as today; retention ends them (90 days). No backfill: the prose can't be parsed back reliably.
- **Pushes in the subscriber's language, at send time**: `push::send_due` joins each delivery's subscription to
  `admin_users.lang` and words the event then; `SUMMARY_TITLE`/`SUMMARY_BODY`/`TEST_TITLE` become `push.summary.*`,
  `push.test.*`. `templates/sw.js`'s fallback body ("Something new - open Notifications") is rendered with the `Lang` of
  the request that fetches the worker (the session cookie goes along); it is used only when a push has no body.
- **The security log**: `label_of` becomes `security.event.<id>` in both catalogs, `event_label(lang, kind)`; an id
  this server never wrote still reads as `words_of(id)`. **Migration `0105_security_params.sql`**:
  `ALTER TABLE security_events ADD COLUMN params TEXT;` - `{"key": "security.detail.<name>", "p": {..}}`.
  `record_security_event(db, kind, user, ip, detail)` takes `detail: Option<Detail>`, where
  `Detail::words(key, params)` is stored in `params` and `Detail::facts(text)` in `detail`. Facts are only
  identifiers: `settings_saved`'s route list (`settings_log.rs`, paths and ids, never words), a tag, an address. Every
  sentence ("wrong current password", "every other session signed out", "lift 3 ended early", "3 failed attempts",
  "device 3: 7 days") becomes words. `record_phone_event` (`PhoneLine`) goes the same way. The page (`EventRow` in
  `handlers/admin.rs`) words `params` in the reader's language; an older row's prose `detail` shows as stored.
- The log's times stay UTC lines (display_time's header: "The security log keeps its UTC lines").

## 5. The templates, tab by tab (slice 3)

### 5.1 The order and what moves together

One PR per tab, each mergeable alone: **3a Devices** (L: `devices_list`, `device_detail` and every `device_*.html`
but Locate's, `device_add`, `enroll_code`, `provision_qr`; the shared partials `tab_bar`, `bell`, `head`,
`flash_note`, `group_flash`, `info_row`, `note_row`, `note_warning`, `recent`, `exceptions`, `budget_rows`,
`device_app_row`; and the every-page scripts `reconnect.js`, `pull-refresh.js`, `nav-timing.js`, `auto-reload.js`,
`reveal.js`), **3b Apps** (M: `apps_list`, `tracked_app_*`, `app_display_form`, `app_permissions_form`,
`app_permissions::words`, `app-display.js`, `install-now.js`), **3c Locate** (M: `device_locate`,
`device_locations`, `device_zone`, `locate.js`, `zones.js`), **3d Music** (M: `music*.html`, `device_music`,
`music_card`, `music_status*`, `spotify_setup`, `music-cards.js`), **3e Settings** (L: `settings_hub`,
`notifications*`, `schedules`, `schedule_*`, `dns_*`, `backups`, `updates`, `wallpapers`, `provisioning_settings`,
`security`, `change_password`, `login`, `setup_2fa`, `verify_2fa`, `notifications.js`, `rule-kind.js`,
`focus-mode.js`). A page moves with its handler's own words (titles, notices, row values, errors) as `t(lang, …)`;
the `<title>` is the handler's `title: t(lang, "<page>.title")` into `head.html`'s `{{ title }} · Vibb Phone`.

### 5.2 The scan test

`i18n::tests::moved_templates_have_no_literal_words`, with `const MOVED: &[&str]` in `i18n.rs` that each PR extends
(3e replaces it with "every file in `templates/` and `templates/partials/`"). Per file it removes `{# #}`, `{% %}`,
`{{ }}`, `<!-- -->`, `<script>…</script>`, `<style>…</style>` and every tag, keeping the values of `title`,
`aria-label`, `placeholder`, `alt`, every `data-w-*` (below), and `value` on `<button>` and `<input type="submit">` /
`type="button"`. What is left fails if it contains a letter (`char::is_alphabetic`), after dropping HTML entities and
the two names "Vibb Phone" and "Vibb One". The same test refuses `|safe` on a line that calls `t(`, `tp(` or `tc(`.
`sw.js` is scanned too (its strings are `{{ }}`).

### 5.3 Static JS: `data-w-*` attributes

The words a script writes come from the page, never from the script: `data-w-<name>="{{ self.t("…") }}"` on the
element the script owns (as `locate.js` already reads `data-text` and `app-display.js` `data-app-label`). Every-page
scripts (`reconnect.js`, `pull-refresh.js`, `nav-timing.js`) read one `<div id="js-words" hidden data-w-…>` from
`partials/js_words.html`, included by `tab_bar.html` and by the sign-in pages that have no tab bar. One helper per
script: `function w(el, name) { return (el && el.getAttribute("data-w-" + name)) || name; }` - a missing word shows
its name, as Rust shows the key. Plurals pass both forms (`data-w-count-one`, `data-w-count-other`) and fill
`{count}` with `.split("{count}").join(String(n))`. Words go in with `textContent` only - never `innerHTML`, never
concatenated into HTML. A JSON block was the alternative; attributes need no parser, are escaped by Askama's default
attribute escaping, and follow the pattern the scripts already use.

`jstest/words.test.js` (node, run by `server-ci.yml`'s `node --test jstest/*.test.js`) is the scripts' scan: per moved
script, outside comments, a string literal fails if it starts with an upper-case letter followed by a lower-case one or
contains a letter and a space, unless it is in that file's short list of non-words (a header name, a CSS selector).

## 6. The words Rust builds (slice 4)

What slice 3 did not take with a page: the phone's summary rows and Attention (`handlers/device_pages.rs`,
`attention.rs`), the findings' words (`kid_lock.rs`, `kiosk_escapes.rs`, `window_relock.rs`, `parent_pin.rs` - their
PWA texts; nothing the phone shows), the music and Spotify status words (`music.rs`, `spotify.rs`), and the helpers:

- **Durations**: `lifts::minutes_text` → `display_time::duration(lang, minutes)` ("15 min", "1 h 30 min"; nb "15 min",
  "1 t 30 min"), `music::duration_text` the same; `music::ago` goes to `Zone::relative`.
- **Plurals**: `settings_hub::plural` and `music::plural` go; their callers use `t_count` (and their own zero key).
- **Numbers**: `i18n::number(lang, value, decimals)` for the few decimals the PWA shows (backup sizes "1.5 GB", nb
  "1,5 GB"); integers and "64 %" are the same in both.
- `display_time.rs` is the one module for times and durations, `i18n.rs` for words, plurals and numbers. Nothing
  else formats a time or a count into a sentence.

Slice 4 ends with `tests::i18n_pages::nb_pages_have_no_english` (§8) over every page, and the `// i18n:` markers gone.

## 7. The seam with the phone (slice 5)

The policy is already keys: `rules[].kind`, `pending_command.command`, `kid_lock.keyguard`, `calendar.*`'s choices,
`app_permissions[].camera`, the time-request answer's `reason` (`capped|stale`, `time_request.json`), the Storytel
token answers' `status` - the launcher words them from `values`/`values-nb`. The one English the server writes for the
kid is a **time rule's `name` the server made itself**, shown on the lock screen and the glance card
(`LockActivity.kt:142`, `lock/RuleCard.kt`, via `TimeLockModel.ruleLabel`, which names a blank name by its kind:
"Skole" / "Leggetid" / "Skjermfri tid"):

- `time_rules::legacy_to_rules` (startup, old schedules): "Bedtime", "Outside allowed hours", "Outside allowed hours
  (morning)" / "(evening)"; `split_rule`/`morning_name` (`migrate_crossing`, every start): "<name> (morning)".
- **The rule**: `TimeRuleRow::to_policy` sends `name: ""` for a name in `time_rules::SERVER_NAMES` (those four exact
  strings), and drops a trailing " (morning)" / " (evening)" from any other name - the kid sees the parent's own name
  on both halves of a split rule, or the phone's word for the kind. Existing rows are covered without a migration.
- **In the PWA**, the schedules pages show a `SERVER_NAMES` name through `device.time.rule.default.<n>` and the suffix
  through `device.time.rule.morning` ("{name} (morning)" / "{name} (morgen)") until the parent renames it. The
  conversions keep writing the English defaults (they are one-off conversions; the stored text is only a marker).
- **No launcher change and no contract change**: the field and its blank case exist, `PolicyResponseCompatTest` pins
  only keys. Tests: `time_rules::tests::server_names_go_to_the_phone_blank` (the four names, a split "Natt (morning)"
  → "Natt", a parent's "Morning run" untouched). Should a later slice change a field's shape, the rule holds: `server/`
  and `launcher/` in one commit, the contract in `server/testdata/*.json` byte-identical in
  `launcher/app/src/test/resources/`.
- Seeded Norwegian data the kid sees (the Home tile label "Musikk" and the categories "Musikk", "Eventyr",
  "Lydbøker", "Podkast", migration 0049) are the parent's data to rename, not words (D5).

## 8. Testing

- **Catalogs** (`i18n::tests`): parity of keys and params (exists); every `.one` has its `.other`, both with `{count}`;
  no value with `<`/`>`; no empty value; `every_key_used_exists` over every namespace; `t` returns the key when missing.
- **Closed sets**: `parent_events::tests::every_kind_is_worded_in_both_languages` (every `Kind::ALL` × variant × form
  in en and nb, each within `TITLE_MAX`/`SUMMARY_MAX` with a 20-character name); `the_push_words_are_short` gets an nb
  column; `admin::tests::every_written_event_has_a_label` asserts `security.event.<id>` in both catalogs.
- **Times**: `display_time::tests` - every existing case gets its nb twin (relative steps, yesterday, dates across a
  year, until/tomorrow, DST).
- **The language**: `tests::parents::language_is_each_parents` (two accounts, en and nb: each page's `<html lang>`, the
  Account row, a push worded per subscriber, NULL → Accept-Language stored at sign-in, `?lang=` wins once and is not
  stored, a bad `lang=` POST is 400); `tests::music_spotify::migrations_73_to_103_in_order` takes 0104 and 0105.
- **Scans**: `moved_templates_have_no_literal_words` (§5.2), `jstest/words.test.js` (§5.3).
- **No English on an nb page**: `tests::i18n_pages::nb_pages_have_no_english` signs in an nb account on the test
  harness's seeded family, GETs every moved route, strips tags and scripts, and fails on a whole word that appears in
  some en value and in no nb value (a short allowlist for the fixtures' names). This catches handler-built words the
  template scan can't see. Each slice-3 PR adds its routes.
- **Browser pass** per slice-3 PR and after slice 4: 390 px, light and dark, JS off, nb and en (the Account row, or
  `?lang=` for one page). Look for the longest nb words in narrow places: the tab bar ("Innstillinger" in a fifth of
  390 px), row labels with a value or a switch, buttons, badges, the summary rows; a word that breaks is shortened in
  the catalog, not by CSS.

## 9. Not in this round

The launcher (done: `values` + `values-nb`); the music app (has `values-nb`); a third language (nothing blocks one: a
`Lang` variant, a catalog file, its plural rule); user data (app names, feed and episode titles, contact, phone and
zone names, rule names the parent typed); `static/manifest.webmanifest` (the name is a brand; the description is shown
nowhere a parent reads); the Dagen pages (already the model); per-device or per-subscription languages (one per
account, §3); a family-wide default language (each account has its own; the server keeps no locale of its own).

## 10. Steps

Five branches off `main`, in order, each mergeable alone with its tests, each marked done in PLAN in its merge commit.

1. **`pwa-i18n-1` - the layer and the language (S)**: `t` without the English fallback, `t_count`, `Words`, the
   extractor's order, migration 0104, the Account row and `POST /account/language`, `account.html` moved, the sign-in
   store of the browser's language. The commit that ships the row is this slice's.
2. **`pwa-i18n-2` - events, pushes, log, times (M)**: §4; migration 0105; `display_time` in both languages.
3. **`pwa-i18n-3a`..`3e` - the tabs (L in five PRs)**: §5, Devices, Apps, Locate, Music, Settings; each adds its files
   to `MOVED`, its routes to `nb_pages_have_no_english`, its scripts to `words.test.js`, and its browser pass.
4. **`pwa-i18n-4` - the words Rust builds (M)**: §6; the nb-page test over every route.
5. **`pwa-i18n-5` - the seam (S)**: §7.

Docs: `pwa.md` "Words" + `server/CLAUDE.md` "Words (i18n)" in slices 1 and 3e, `notifications.md` in slice 2.

## § Decisions for the owner

1. **The times move with the events (slice 2), not with slice 4.** Recommended: a push's body is a time, so an nb push
   needs nb times; slice 4 keeps durations, plurals, numbers and the summary words. Else: slice 2 pushes nb words with
   English times until slice 4.
2. **Before a parent chooses, the browser's language is stored at sign-in and at a push subscription.** Recommended:
   pushes have a language without anyone visiting Account. Else: NULL means "follow the browser" on pages and English
   in pushes until the row is used.
3. **English keeps today's formats: 24 h "14:30", "27 Oct 2026".** Recommended: nothing changes for anyone reading
   English today, and it matches a European family. Else: US style ("2:30 PM", "Oct 27, 2026"), a few more keys.
4. **Security-log details become words in the reader's language for new rows; `settings_saved`'s route list stays as
   written.** Recommended: routes and ids are not words. Else: every detail stays English and only the labels move
   (less work in slice 2, S instead of M).
5. **Seeded "Musikk" (Home tile) and the music categories stay the parent's data.** Recommended: they are rows the
   parent can rename, and this family is Norwegian. Else: an English install seeds English names (a later round:
   the seed would need a language before any account exists).
