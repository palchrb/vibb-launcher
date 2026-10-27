# Design QA: 70 - the PWA in Norwegian and English (`docs/design/70-pwa-i18n.md`)

Checked against `main` (f643afb4): `i18n.rs` + catalogs, `calendar.rs`, `security.rs`, `models.rs`, `settings_log.rs`,
`audit.rs`, `admin_auth.rs`, `main.rs`, `sw.rs` + `sw.js`, `push.rs`, `parent_events.rs`, `retention.rs`,
`display_time.rs`, `time_state.rs`, `time_rules.rs`, six handlers, all 17 `static/*.js`, the templates, `migrations/`;
launcher `TimeLockModel.kt`, `TimeRulesRuntime.kt`, `TimeRules.kt`.

**Verdict: revise first.** Counts: **High 3, Medium 6, Low 8, Nit 5.** The approach holds: one catalog per language,
the language on `admin_users`, events as key + params, a scan per tab, pushes worded at send time. The user's scope is
kept: everything, nb first, Dagen's pages as the model. But three claims about today's code are wrong in ways that
break things. The service worker is a public, unescaped template. `phone_off`'s stored words are read as data, and its
`params` are already in use. The phone lines' hourly budget counts by `detail`. Each fix is a few lines in the note.

Correct as claimed: today's extractor reads `Accept-Language` only and `t` falls back to English, then `"?"`
(`i18n.rs:79-102`); both auth layers put `CurrentAdmin` in the extensions (`security.rs:833,857`), loaded by `SELECT *`
(`:618`); 13 `Kind`s = the note's 8 stored-words + 5 from params (`parent_events.rs:37-95`); `params` is 0091, the push
words 0098, `security_events` has no params (0003); 0103 is last; no email; `push_subscriptions.admin_id` exists
(0078); pages use the Phone Setup zone (`Zone::load`), not the phone's - language is not zone. Slice 5 truly needs no
launcher change: `ruleLabel` names a blank name by its kind (`TimeLockModel.kt:37-38`), and bedtime has sent `name: ""`
since design 66 (`time_rules.rs:112-116`) - cite that precedent. No contract file changes.

## High

- **H1 - `sw.js` can't get the account's language, and it is not escaped.** `/sw.js` sits in `public_routes`
  (`main.rs:782`), outside both auth layers, so `CurrentAdmin` is never in its extensions. §4's "the session cookie goes
  along" doesn't help: the extractor would fall back to `Accept-Language`. Worse, `ServiceWorkerTemplate` is
  `escape = "none"` (`handlers/sw.rs:16`). A catalog value with `"`, `\` or a newline inside a JS string breaks the
  worker. A fresh install then can't register it, and every push is lost. The `<`/`>` test doesn't cover a JS context.
  Per-language bytes also make the browser's byte-diff see a "new" worker whenever the language changes.
  **Fix:** the server never sends a push without a body, so drop the fallback's body: title "Vibb Phone" (a name),
  empty body. If a body is wanted, put both languages' values in through `serde_json::to_string` (a JSON string literal)
  and pick one with `self.navigator.language`. The bytes stay the same for every reader.
- **H2 - `phone_off`'s words are data, and its params are taken.** Several places parse the stored English:
  `off_said` (`title.ends_with(" is restarting")`, `parent_events.rs:741-743`), `off_since_line` (the battery from
  `summary`, `:761-765`), Locate's `before_off` (`locate.rs:1141-1157`) and the device page's "Off since"
  (`devices.rs:1527-1542`). With empty words, a restart shows "Off since" and the battery level is gone. `phone_off`
  already stores `params = {"location": …}` (`:474-483`). Retention sets `params = NULL, detail = NULL` with the
  location history (`retention.rs:367-380`), so a params-only event would lose all its words at day N. The note's test
  for "older row" ("words and no params", §4) is also wrong for every `phone_off` since 2026-10-25, which has both.
  `parent_events.detail` (inbox only: "Device admin: Helper.", "Last position from 21:10: shown on Locate",
  `:356-374,474-482`) is never mentioned. **Fix:** an older row is one whose `title` is not empty. `PhoneOffParams` keeps
  `location` and adds `reason`, `battery` and `late` (" · told at the next start", `:466-468`). `off_said` and
  `off_since_line` read the params, and keep today's text parse for older rows. Retention removes only the location:
  `json_remove(params, '$.location')`. `detail` becomes worded at read time as well, under
  `events.<kind>.detail.*` keys made from the params.
- **H3 - the phone lines' budget (security review L1) counts by `detail`.** `record_phone_event` counts the hour's rows
  with `detail LIKE 'device N:%'` (`security.rs:215-221`). Move the sentence into `params` and the count never matches,
  so a leaked token can again bury the sign-ins. **Fix:** a phone line's `detail` stays the fact `device {id}`, and the
  words go to `params`. Or 0105 adds an indexed `device_id` column and the count uses that. A test: 61 worded lines in an
  hour still write one `phone_lines_dropped`. Other writers bypass `record_security_event` and must be listed in slice 2
  too: `audit.rs::insert` and `counted()`, which appends "(N times in a minute)" to `detail` (`audit.rs:224-279`; make
  the count a param, or a fact "×N" as `settings_log` does); `time_rules::log_conversion` (`time_rules.rs:597-610`); the
  raw insert at `handlers/auth.rs:102`; and `banned_ips.reason` (English prose, `security.rs:290`, shown at
  `security.html:19`).

## Medium

- **M1 - slice 5 blanks the PWA's own badge.** The phone reports back the name it was sent (`activeRuleName = top?.name`,
  `TimeRulesRuntime.kt:206`). A blank name parses to `None` (`time_state.rs:468`). The badge and the Diagnostics row
  then fall back to "Time rule" / "A time rule" (`time_state.rs:110-127`, `device_pages.rs:655-666`), so the parent
  loses "Outside allowed hours". **Fix:** both name the rule from `active_rule_id`, as the server's row worded per §7.
  The reported name is the fallback. Add a test.
- **M2 - JSON routes carry words.** `subscriptions.json` (`notifications.rs:420-438`: `sub`, `badge`; written by
  `notifications.js:52-54`), `install-now.json` (`install_now.rs:367`, `install-now.js:66`) and `locate-result.json`
  (`locate.rs:824`, `text`). §5.3 says the words come from the page, but these come from the server. **Fix:** these
  handlers take `Lang`, move with their tab, and `nb_pages_have_no_english` GETs them too.
- **M3 - the slice 3/4 boundary versus the nb-page test.** 3a's routes show slice-4 words: the summary rows, Attention,
  `lifts::minutes_text`, `settings_hub::plural` and the badge's `lock_name`. So "each slice-3 PR adds its routes to
  `nb_pages_have_no_english`" can't be green before slice 4. **Fix:** add `const SLICE_4_WORDS` to the test (an
  allowlist that slice 4 empties and deletes), or run the nb test from slice 4 on and give the slice-3 PRs only the
  template scan.
- **M4 - partials need their parent's `Words`.** `head`, `tab_bar` and `bell` render inside every page with that page's
  `self`, so moving them in 3a forces `lang` + `impl Words` onto all ~65 template structs in 3a. `device_app_row` and
  `app_permissions_form` are shared by `device_apps` (3a) and `tracked_app_detail` (3b). `app_permissions::words` is
  read by `devices.rs:1886-1887` and `device_pages.rs:1941` (3a), yet the note moves it in 3b. **Fix:** in slice 1,
  every template struct gets `lang: Lang` + `impl Words`. That is mechanical, moves no words, and keeps each slice-3 PR to
  words only. `app_permissions::words` moves in 3a.
- **M5 - plain-text bodies.** There are 95 `(StatusCode::…, "English")` bodies in `src/`. Examples: "No such entry"
  (`music.rs:1133`) and the CSRF refusal "Refused: this form was sent from another site." (`admin_auth.rs:597-600`).
  The template scan can't see them, and the nb test GETs only routes that work. **Fix:** name them in slice 4, worded
  with `t(lang, …)`, plus a scan that refuses a `StatusCode::X, "<letters>"` literal in `src/` outside tests.
- **M6 - D2 misses today's sessions and subscriptions.** Sessions last `inactivity_days`, and this family's push
  subscriptions already exist. If the language is stored only at sign-in or at a new subscription, their pushes stay
  English after slice 2 until someone signs in again. **Fix (simpler too):** one `UPDATE admin_users SET lang = ? WHERE
  id = ? AND lang IS NULL` in `require_full_auth` when `admin.lang` is `None`. It reads `Accept-Language`, and only the
  first request writes. This replaces both hooks.

## Low

- **L1** The `CHECK (lang IN ('en','nb'))` contradicts §9 ("nothing blocks a third language"): SQLite needs a table
  rebuild to change a CHECK. Drop the CHECK and read through `Lang::from_code` (an unknown value reads as `None`).
- **L2** `payload_json` hardcodes `"lang": "en"` (`push.rs:243`), so set it to the subscriber's `lang.code()`. The test
  push body `format!("Notifications work on {label}")` (`push.rs:592`) needs a `push.test.body` key.
- **L3** Some times are formatted outside `display_time`: `time_state.rs:247` (`%-d %b`) and `parent_pin.rs:374,473`
  (`%b`). §6's "nothing else formats a time" must name them (slice 2).
- **L4** `Zone::load(db)` gives En without a word. A page that forgets `.in_lang(lang)` shows English times, which only
  a route in the nb test would catch. Make it `Zone::load(db, lang)`; push loads once and calls `in_lang` per row.
- **L5** Slice 1's Account page also shows the notices of `parents::add/reset/remove`,
  `auth::update_account_password`, `reset_totp` and `sign_out_everywhere`, not only `set_name`'s.
- **L6** "The test harness's seeded family" doesn't exist yet: `tests/mod.rs` has `enrolled_device` and `post_status`.
  Name it as new, with what it seeds: a status with `time_state`, battery and zones; one event of each kind; a tracked
  app; a music entry. Otherwise the nb test checks empty pages.
- **L7** §9 says "the server keeps no locale of its own", but `provisioning_settings.locale` exists (0034, `nb_NO`).
  Say why it isn't the NULL fallback (it is the phones' locale), or use it instead of English.
- **L8** The jstests assert English (`locate.test.js`, `notifications.test.js`, `reveal.test.js`, …), and
  `sw.test.js` reads the template. Each moved script changes its test, so the PRs' size estimates should count that.

## Nit

- **N1** Counts: 65 templates are left (49 + 19 partials, 3 done), not 49; `label_of` has 116 arms, not ~90; the 108
  keys include 3 `apps.*`. Six scripts carry their own words (locate, notifications, reconnect, nav-timing, reveal,
  app-display), two show the server's (install-now, notifications); `pull-refresh`, `auto-reload`, `zones`,
  `music-cards`, `rule-kind`, `focus-mode` have none. **N2** `AdminUser` is in `models.rs` (`security.rs:618` is the
  `SELECT *`). **N3** "Logged once per key" needs a static set; a plain `warn!` per miss is less code. **N4** The
  page-timing tokens (`dns`, `ttfb`, `nav-timing.js:100-104`) are diagnostics: literal, on `words.test.js`'s non-word
  list. **N5** Dropping " (morning)" from any name also drops one the parent typed; say so, it is fine.

**Security, otherwise sound:** `?lang=` is never stored (D2 reads `Accept-Language`, not the resolved `Lang`) nor
reflected (only `Lang::code()`); Askama escapes `& < > " '`, enough for quoted attributes - the `<`/`>` test guards
against markup in a catalog, not escaping; no template has an inline `<script>`, the one `|safe` is the QR's SVG;
`POST /account/language` is behind full auth, the global `csrf_guard` (`main.rs:1566`) and `settings_log`. Robustness:
the strict parity (keys and `{params}`) is right, a missing key shown as itself is fine, CLDR one/other covers nb and
en, 0104/0105 are the next numbers (renumber at merge if a parallel round takes one). **Over-engineering:** none to cut
- `Words` (a trait with defaults) is less code than three methods per struct; `?lang=` is three lines and serves QA.

## The decisions

1. **Times in slice 2: agree** - a push's body is a time. 2. **The browser's language before a choice: agree, other
hook** (M6): the first signed-in request, not sign-in + subscription - covers today's sessions, less code. 3. **24 h and
"27 Oct 2026" in English: agree** - no change for today's readers. 4. **Security-log details worded, routes as facts:
agree** (the user's "everywhere"); M only with H3 in the slice-2 brief. 5. **Seeded "Musikk" stays the parent's data:
agree** - rows the parent renames, not words.

## What the implementer's brief must carry

The note's revision takes H1-H3, M1-M6, L1 and L6. The briefs carry the rest: L2-L5, L7, L8 and N1-N5. Also: every
`data-w-*` value is quoted; no server release tag between 3a and 3e (or say mixed pages are accepted); the nb catalog
diff goes to the user to read, as "Push wording" asks; the 390 px browser pass in nb and en.
